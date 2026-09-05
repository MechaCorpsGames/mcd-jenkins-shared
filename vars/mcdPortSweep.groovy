// vars/mcdPortSweep.groovy
//
// The shell that clears containers squatting on this environment's proxy port,
// with ONE exemption: a drainer, recognised by its name, is left alone.
//
// WHY THIS IS A HELPER RATHER THAN INLINE SHELL (bead mc-58po4)
// ------------------------------------------------------------
// It was inline, in two byte-identical copies, at mcdServerPipeline's main
// deploy and at the "proxy not running, starting..." branch of its no-change
// path. Both take the deploy lock and both sweep. A fix applied to one of them
// looks complete in review and in any grep that stops at the first hit, and the
// half that was missed still kills drainers on every deploy that takes the
// other path. This is the same argument mcdDeployLock's own header makes about
// inlining flock: a thing that must agree across call sites is defined once.
//
// WHY A DRAINER NEEDS EXEMPTING AT ALL
// ------------------------------------
// mc-r15kh is teaching the deploy to let the outgoing proxy bleed out: it keeps
// its live matches to their natural end while the replacement serves new ones.
// A drainer matches every condition this sweep selects on, by construction:
//
//   1. it is running .................. it is draining, so `docker ps -q` lists it
//   2. network=host ................... same image and compose as the proxy
//   3. name differs from keepName ..... being renamed is WHAT MAKES IT a drainer
//   4. Config.Cmd carries the port .... it is the same proxy command line
//
// and the action is `docker rm -f`, a SIGKILL with no grace period. So without
// this exemption, the moment the orchestration starts producing drainers every
// deploy SIGKILLs the container holding the live matches: strictly worse than
// today, where the lease-wait pauses up to 900s before forcing a teardown. That
// is why this lands BEFORE the orchestration, not after it.
//
// WHY THE Config.Cmd MATCH IS KEPT, AND MUST NOT BE "SIMPLIFIED"
// -------------------------------------------------------------
// The label composes ALONGSIDE the existing match; it does not replace it.
// Matching on the command line is not clumsiness, it is the only signal
// available: host networking leaves .NetworkSettings.Ports EMPTY, so the port
// appears nowhere in the container's inspect output except its argv.
// mcd-jenkins-shared 637a43a (2026-03-04) introduced the Config.Cmd match
// precisely because a name match MISSED legacy containers from other compose
// projects that were holding the port. Narrowing this back to a name match
// reintroduces that bug; the tests in test/groovy/mcd_port_sweep_behaviour.groovy
// assert an unlabelled foreign squatter is still removed, so that regression
// goes red rather than silent.
//
// HOW A DRAINER IS RECOGNISED, AND WHY IT IS NOT THE LABEL (mc-r15kh 3a)
// ---------------------------------------------------------------------
// This shipped keying on a `mcd.role=drainer` LABEL, and that was inert: Docker
// sets labels at creation and has no command to add one to a running container,
// while a drainer is by necessity THE PRE-EXISTING CONTAINER holding the live
// sockets. That is the entire point of it. So no container could ever carry the
// label and the exemption matched nothing.
//
// A drainer is now recognised by its NAME. Step 3a renames the outgoing
// container to `<keepName>-drainer-<build>` before signalling it, which it has
// to do anyway so compose can create a fresh `<keepName>`, so the rename is free
// evidence and it is applied at exactly the moment the container becomes a
// drainer.
//
// ONE RULE, NOT TWO. The label check is gone rather than kept alongside as
// belt-and-braces. An `or` widens the exemption, and a wider exemption means
// more containers survive a sweep whose job is to clear squatters. Every proxy
// still carries `mcd.role=proxy` from compose, but that label is for the drain
// reaper and its metrics (see docs/design/proxy_hot_swap_orchestration.md), not
// for this decision.
//
// USAGE
//   mcdDeployLock(deployPath: config.deployPath, """
//   ${mcdPortSweep(tcpPort: config.tcpPort, keepName: containerName)}
//       docker rm -f ${containerName} 2>/dev/null || true
//   """)
//
// Returns POSIX sh. It does NOT call `sh` itself, because every call site
// splices it into a larger body that is already running under the deploy lock.
// Keep it POSIX: this runs under `/bin/sh -xe` inside mcdDeployLock.

def call(Map args = [:]) {
    def tcpPort = args.tcpPort
    String keepName = args.keepName

    // Both are required and neither has a safe default. A missing port would
    // make the grep match nothing and the sweep silently do nothing, which
    // looks identical to a clean host; a missing keepName would make the sweep
    // remove the very container the deploy is about to reuse.
    if (tcpPort == null || tcpPort.toString().trim().isEmpty()) {
        error('mcdPortSweep: tcpPort is required. Without it the sweep matches no ' +
              'container and quietly does nothing, which is indistinguishable from ' +
              'a host with no squatter on it (mc-58po4).')
    }
    if (!keepName) {
        error('mcdPortSweep: keepName is required. Without it the sweep would remove ' +
              "the deploy's own container (mc-58po4).")
    }

    return """
                                # Kill any Docker container holding our target TCP or WS port (host networking).
                                # EXCEPT a drainer, which is bleeding out live matches and whose
                                # docker rm -f would SIGKILL them (mc-r15kh blocker A, bead mc-58po4).
                                # A drainer is `<keepName>-drainer-<build>`, the name step 3a gives it.
                                for cid in \$(docker ps -q --filter 'network=host'); do
                                    cname=\$(docker inspect --format '{{.Name}}' "\$cid" | sed 's|^/||')
                                    if [ "\$cname" = "${keepName}" ]; then continue; fi
                                    case "\$cname" in
                                        ${keepName}-drainer-*)
                                            echo "Keeping drainer \$cname: it is bleeding out live matches"
                                            continue
                                            ;;
                                    esac
                                    cmd=\$(docker inspect --format '{{join .Config.Cmd " "}}' "\$cid" 2>/dev/null || true)
                                    if echo "\$cmd" | grep -qE '(^|\\s)${tcpPort}(\\s|\$)'; then
                                        echo "Removing container \$cname holding port ${tcpPort}"
                                        docker rm -f "\$cid" 2>/dev/null || true
                                    fi
                                done"""
}
