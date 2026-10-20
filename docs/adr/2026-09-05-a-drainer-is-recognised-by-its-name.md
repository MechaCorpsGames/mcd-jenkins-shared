# A drainer is recognised by its name, and the deploy hands the proxy over rather than destroying it

Date: 2026-09-05

Status: Accepted

Bead: mc-r15kh step 3a. Follows mc-58po4 (PR #129).

## Context

Two things, and the first is a correction to the second.

**The exemption that shipped in #129 was inert.** It kept a container out of the
port sweep when that container carried a `mcd.role=drainer` label. Docker sets
labels at creation and has no command to add one to a running container, while a
drainer is **by necessity the pre-existing container**, holding the live sockets
for live matches. That is the entire point of it: those sockets are what survive.
So no container could ever carry the label, and the sweep exempted a class that
cannot exist. Nothing was broken by it, because nothing creates drainers, but it
would have been discovered the moment something did.

**Nothing has ever sent `SIGUSR1`.** The proxy has been able to bleed out since
#2817. The deploy reached `docker rm -f ${containerName}` with a live proxy still
serving, and `docker rm -f` is a SIGKILL with no grace period (confirmed from
`docker rm --help` on the deploy host), so every deploy over a live match ended
it. Tim, GH #2854: *"I just hit 'Match ended - server no longer has this game...'.
We did work to make this happen without kicking people from their games."*

## Decision

**A drainer is recognised by its name, and the deploy renames the outgoing proxy
before signalling it.**

### The name, not the label

`<containerName>-drainer-<build>-<epoch>`. Step 3a has to rename the container
anyway so compose can create a fresh `<containerName>`, so the name is free
evidence, and it is applied at exactly the moment the container becomes a drainer.

**One rule, not two.** The label check is removed rather than kept alongside as
belt-and-braces. An `or` widens the exemption, and a wider exemption means more
containers survive a sweep whose job is to clear squatters; a stale container
someone happened to name `*drainer*` would then survive indefinitely. A test pins
that the old label alone no longer exempts anything, so a half-applied revert
cannot pass. The prefix is anchored on this deploy's own container name, so a
container cannot escape the sweep by choosing a name.

Every proxy still gains `mcd.role=proxy` at creation, but that label is for the
drain reaper and its metrics, not for this decision.

### The handoff, and why its order is asserted

1. **rename** so the sweep recognises it and compose can recreate the service
2. **`docker update --restart=no`** as defence in depth. #3120 already stops the
   resurrection by parking instead of exiting, but a drainer SIGKILLed by anything
   else would still be restarted from the **old image** by `restart: unless-stopped`,
   where it can bind the ports before the replacement and serve the previous build
   to real players
3. **`docker kill -s USR1`** only now, when it is already renamed and de-policied

Signalling first opens a window in which a drain is running on a container the
sweep still considers a squatter. The tests assert the **order** off a single
ordered log, not merely that three things each happened, because "all three
occurred" is a different and weaker claim.

### The stale-drainer bound belongs to the step that creates drainers

A parked drainer never exits by itself; that is what #3120 is for. So 3a must
bound how many can pile up, or it trades a silent failure (a killed match) for a
loud but real one (a host filling with idle containers). **You do not get to
create a thing without bounding it.**

The bound is deliberately crude and time-based: stop any drainer past its own
drain deadline plus a grace. It cannot ask a drainer whether it is finished,
because that needs the admin listener a drainer only has once step 3c gives every
proxy an `-admin-port`. What it **can** do is refuse to touch a drainer that might
still be draining, and one past its own deadline is definitionally either finished
or wedged.

The age comes from an epoch in the name because Docker records no "renamed at"
time and `.State.StartedAt` is when the **old proxy** started, which may be days
ago and says nothing about when it began draining.

`docker stop`, never `docker rm -f`: a container the daemon stopped stays stopped,
whereas one that exits on its own is restarted by `unless-stopped` even on a clean
exit 0 (measured on the deploy host: `RestartCount=7 lastexit=0` against
`status=exited`). And `docker stop` is a SIGTERM, which the proxy handles as its
hard stop, telling any remaining players with a terminal 4007 rather than dropping
them on a silent 1006.

## Consequences

**This is the first change in mc-r15kh that alters what a player experiences.**
Every prior step was an enabling piece that changed nothing observable. A deploy
over a live match should now let that match finish.

**It is also the first that can make things worse if it is wrong**, which is why
the order is asserted and why the stale bound ships with it rather than after it.

**The bound is an interim and is up to 35 minutes late.** Step 3d replaces it with
`mcdproxy-drain-reaper`, a host-side systemd timer that polls `/health` and stops a
drainer the moment it reports `"drained"`. That reaper must live outside the build:
a build can be superseded, and a reaper that dies with its build leaves a container
nothing will ever collect. Until 3d lands, a drainer can sit idle for the length of
the drain deadline plus grace, holding no player-facing port.

**Wired into the main deploy path only.** The "proxy not running, starting..."
branch has no live proxy to hand over by construction, and a test asserts the call
site count is exactly one so that assumption is revisited if it ever changes.

**How this is known to work:** `tools/deploy_over_live_match_probe.sh` in MCDClient,
run on a deploy host over a real live match. It has still never been run, and its
first real output remains the acceptance for mc-r15kh.

## Alternatives considered

**Keep the label and add a way to set it.** There is no way to set a label on a
running container, and the drainer cannot be anything but a running container.

**Label at creation with `mcd.role=drainer-eligible` and treat "eligible plus
renamed" as a drainer.** Same information as the name alone, with an extra
indirection and a second thing that can disagree.

**Have the drainer write a marker file both consumers read.** Rejected: a second
source of truth about container state that can disagree with Docker, which is the
class of bug ADR 0155 exists to prevent.

**Ship 3a without the stale bound and let 3d clean up.** Rejected: 3d does not
exist yet, and shipping a known per-deploy container leak in the meantime trades
one failure for another rather than removing one.
