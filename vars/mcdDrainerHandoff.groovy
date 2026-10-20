// vars/mcdDrainerHandoff.groovy
//
// Turn the outgoing proxy into a DRAINER instead of destroying it, and bound how
// long stale drainers may accumulate (mc-r15kh step 3a).
//
// WHAT THIS REPLACES
// ------------------
// The deploy used to reach `docker rm -f ${containerName}` with a live proxy
// still serving matches. That is a SIGKILL with no grace period (confirmed from
// `docker rm --help` on the deploy host), so every deploy over a live match
// ended it. Tim, GH #2854: "I just hit 'Match ended - server no longer has this
// game...'. We did work to make this happen without kicking people from their
// games."
//
// The proxy has been able to bleed out since #2817 and nothing has ever invoked
// it. This is the thing that invokes it.
//
// THE ORDER MATTERS AND IS NOT COSMETIC
// -------------------------------------
//   1. rename        so compose can create a fresh <containerName>, and so the
//                    port sweep recognises it as a drainer and leaves it alone.
//                    The sweep keys on exactly this name (vars/mcdPortSweep.groovy).
//   2. restart=no    defence in depth. #3120 already stops the resurrection by
//                    parking instead of exiting, but a drainer SIGKILLed by
//                    anything else would still be restarted from the OLD IMAGE by
//                    `restart: unless-stopped`, where it can bind the ports before
//                    the replacement and serve the previous build to real players.
//                    Removing the policy closes that at the source.
//   3. SIGUSR1       only now, when the container is already named and de-policied.
//                    Signalling first would open a window in which a drain is
//                    running on a container the sweep still considers a squatter.
//
// A drain then releases the TCP, WebSocket and bot-WebSocket ports for the
// replacement, keeps every live match on its existing socket, and parks when the
// last one ends.
//
// THE STALE-DRAINER BOUND, AND WHY IT IS HERE RATHER THAN IN 3d
// ------------------------------------------------------------
// A parked drainer never exits by itself; that is the point of #3120. So the
// step that CREATES drainers must also bound how many can pile up, or 3a trades
// a silent failure (a killed match) for a loud but real one (a host filling with
// idle containers). You do not get to create a thing without bounding it.
//
// The bound here is deliberately crude and time-based: stop any drainer older
// than the drain deadline plus a grace. It cannot ask a drainer whether it is
// finished, because that needs the admin listener a drainer only has once step
// 3c gives every proxy an -admin-port. What it CAN do is refuse to touch a
// drainer that might still be draining, and a drainer past its own deadline is
// definitionally either finished or wedged.
//
// The age comes from an epoch stamped into the drainer's NAME, because Docker
// records no "renamed at" time and .State.StartedAt is when the OLD proxy
// started, which may be days ago and says nothing about when it began draining.
//
// This is an INTERIM. Step 3d replaces it with `mcdproxy-drain-reaper`, a
// host-side systemd timer that polls /health and stops a drainer the moment it
// reports "drained" rather than up to 35 minutes later. That reaper must live
// outside the build because a build can be superseded and a reaper that dies
// with its build leaves a container nothing will ever collect. See
// docs/design/proxy_hot_swap_orchestration.md in MCDClient.
//
// `docker stop`, NEVER `docker rm -f`. A container the daemon stopped stays
// stopped; one that exits on its own is restarted by `unless-stopped` even on a
// clean exit 0 (measured on the deploy host: RestartCount=7 with lastexit=0,
// against status=exited for one the daemon stopped). And `docker stop` is a
// SIGTERM, which the proxy handles as its hard stop: it tells any remaining
// players with a terminal 4007 rather than dropping them on a silent 1006.
//
// USAGE
//   mcdDeployLock(deployPath: config.deployPath, """
//   ${mcdDrainerHandoff(containerName: containerName, buildNumber: env.BUILD_NUMBER)}
//   ${mcdPortSweep(tcpPort: config.tcpPort, keepName: containerName)}
//       docker rm -f ${containerName} 2>/dev/null || true
//   """)
//
// It must run BEFORE the sweep and before the `docker rm -f`, so that by the time
// those run the outgoing container no longer answers to <containerName>.
//
// Returns POSIX sh. It does not call `sh` itself: every call site splices it into
// a body already running under the deploy lock.

def call(Map args = [:]) {
    String containerName = args.containerName
    def buildNumber = args.buildNumber
    // Drain deadline plus grace, in seconds. 30m is the proxy's own
    // -drain-deadline default (Src/Proxy/main.go), and 300s of slack covers the
    // hard-stop conversion and the container actually going away.
    def staleAfter = args.staleAfterSeconds ?: 2100

    if (!containerName) {
        error('mcdDrainerHandoff: containerName is required. Without it there is ' +
              'nothing to rename and the deploy would fall through to destroying ' +
              'a live proxy (mc-r15kh 3a).')
    }
    if (buildNumber == null || buildNumber.toString().trim().isEmpty()) {
        error('mcdDrainerHandoff: buildNumber is required. It makes the drainer name ' +
              'unique, and two drainers sharing a name means the second rename fails ' +
              'and its proxy is destroyed instead of drained (mc-r15kh 3a).')
    }

    return """
                                # ---- mc-r15kh 3a: hand the outgoing proxy over as a drainer ----
                                # Stop drainers that are past their own drain deadline. Crude and
                                # time-based on purpose: without an admin port we cannot ask one
                                # whether it is finished, so we only touch those that cannot still
                                # be draining. Step 3d replaces this with a prompt /health reaper.
                                MCD_NOW=\$(date +%s)
                                for cid in \$(docker ps -q --filter "name=^/?${containerName}-drainer-"); do
                                    dname=\$(docker inspect --format '{{.Name}}' "\$cid" | sed 's|^/||')
                                    # <containerName>-drainer-<build>-<epoch>
                                    dstamp=\$(printf '%s' "\$dname" | sed -n 's/.*-drainer-[^-]*-\\([0-9][0-9]*\\)\$/\\1/p')
                                    if [ -z "\$dstamp" ]; then
                                        echo "Leaving \$dname alone: no timestamp in its name, so its age is unknown"
                                        continue
                                    fi
                                    if [ \$(( MCD_NOW - dstamp )) -lt ${staleAfter} ]; then
                                        echo "Leaving drainer \$dname alone: \$(( MCD_NOW - dstamp ))s old, may still be draining"
                                        continue
                                    fi
                                    echo "Stopping stale drainer \$dname (\$(( MCD_NOW - dstamp ))s old, past the drain deadline)"
                                    docker stop "\$cid" >/dev/null 2>&1 || true
                                    docker rm "\$cid" >/dev/null 2>&1 || true
                                done

                                # Hand over the outgoing proxy, if there is one.
                                MCD_OUTGOING=\$(docker ps -q --filter "name=^/?${containerName}\$" | head -n1)
                                if [ -n "\$MCD_OUTGOING" ]; then
                                    MCD_DRAINER="${containerName}-drainer-${buildNumber}-\$MCD_NOW"
                                    if docker rename "\$MCD_OUTGOING" "\$MCD_DRAINER" 2>/dev/null; then
                                        # Order matters: renamed, then de-policied, then signalled.
                                        docker update --restart=no "\$MCD_OUTGOING" >/dev/null 2>&1 \\
                                            || echo "WARNING: could not clear the restart policy on \$MCD_DRAINER; parking (#3120) is the only thing stopping a resurrection"
                                        if docker kill -s USR1 "\$MCD_OUTGOING" >/dev/null 2>&1; then
                                            echo "Handed the outgoing proxy over as \$MCD_DRAINER: its live matches keep playing"
                                        else
                                            echo "WARNING: renamed \$MCD_DRAINER but could not signal it. It is NOT draining and"
                                            echo "         nothing will collect it until it passes the stale bound above."
                                        fi
                                    else
                                        echo "WARNING: could not rename the outgoing proxy. Falling through to the old"
                                        echo "         behaviour, which DESTROYS a live match if one is running."
                                    fi
                                else
                                    echo "No outgoing proxy container to hand over."
                                fi
                                # ---- end 3a ----"""
}
