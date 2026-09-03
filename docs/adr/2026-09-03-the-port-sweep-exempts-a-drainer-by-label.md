# The port sweep exempts a drainer by label, and keeps its Config.Cmd match

Date: 2026-09-03

Status: Accepted

Bead: mc-58po4 (blocker A of MCDClient mc-r15kh)

## Context

MCDClient mc-r15kh is teaching the proxy deploy to let the outgoing proxy bleed
out: it keeps its live matches to their natural end while the replacement serves
new ones. Tim's own report on GH #2854 is what it answers.

`mcdServerPipeline.groovy` clears containers squatting on the environment's proxy
port before it recreates the proxy. A drainer matches every condition that sweep
selects on, by construction:

| condition | why a drainer matches |
|---|---|
| running (`docker ps -q`) | it is draining |
| `--filter network=host` | same image and compose as the proxy |
| name differs from `containerName` | being renamed is *what makes it* a drainer |
| `Config.Cmd` carries the TCP port | it is the same proxy command line |

The action is `docker rm -f`. So the moment the orchestration starts producing
drainers, every deploy destroys the container holding the live matches, with no
grace period. That is strictly worse than today's behaviour, where the `.in-use`
lease wait pauses the teardown for up to 900 seconds before forcing it.

The sweep also existed in **two byte-identical inline copies**: one in the main
deploy's `mcdDeployLock` body, one in the "proxy not running, starting..." branch
of the no-change path, which takes the same lock. A fix applied to one of them
looks complete in review and in any grep that stops at the first hit.

## Decision

**Exempt `mcd.role=drainer`, keep everything else, and define the sweep once.**

### The exemption is a label, not a weakening of the match

The `Config.Cmd` match stays exactly as it was. It is not clumsiness: under host
networking `.NetworkSettings.Ports` is empty, so a container's port appears
nowhere in its inspect output except its argv. Commit `637a43a` (2026-03-04)
introduced that match precisely because a name match missed legacy containers
from other compose projects that were holding the port. The label composes
alongside it.

`637a43a` is cited in the helper's own comment, and a test asserts that citation
is still there, because the comment is the only thing standing between the next
reader and a "simplification" back into the bug it fixed.

### The label is read per container, not filtered in `docker ps`

`docker ps --filter label=...` selects containers that *have* a label; there is
no negated label filter that could express "everything except drainers". Reading
the label inside the loop with `docker inspect` needs no filter capability this
code cannot verify, and matches how the loop already reads `.Name` and
`.Config.Cmd`. `{{index .Config.Labels "mcd.role"}}` prints `<no value>` for a
missing key or a nil map, which is not `drainer`, so an unlabelled container
takes the sweep path exactly as before.

### The sweep moves to `vars/mcdPortSweep.groovy`

Both call sites now interpolate one helper. This is the same argument
`mcdDeployLock`'s own header makes about inlining `flock`: a thing that must
agree across call sites is defined once, because two copies that disagree
produce no error anywhere. Here the consequence of disagreement is that one
deploy path SIGKILLs drainers and the other does not, and both builds go green.

The helper `error`s on a missing `tcpPort` or `keepName`. Neither has a safe
default: a missing port makes the grep match nothing, so the sweep silently does
nothing and the host looks clean; a missing `keepName` makes the sweep remove
the very container the deploy is about to reuse.

## Consequences

**Nothing changes today.** Nothing creates a container labelled
`mcd.role=drainer`; that is orchestration step 3b in mc-r15kh. This change makes
the sweep *safe* for drainers that do not exist yet, which is exactly why it can
and must land first.

**This must land before the orchestration, and that ordering is not a
preference.** If the orchestration starts creating drainers while the sweep still
`docker rm -f`s them, every deploy destroys the container holding the live
matches: worse than the current forced teardown at 900 seconds.

**A new failure mode is introduced and it is worth naming.** A container
mislabelled `mcd.role=drainer` now survives the sweep and can hold the port
against a deploy. The blast radius is a proxy that fails to bind and a deploy
that does not serve, which is loud, rather than a live match destroyed silently.
That asymmetry is why the exemption is opt-in by label rather than, say, a
heuristic on the container name.

**The `docker rm -f` semantics are cited, not verified here.** That it is a
SIGKILL with no grace period is documented Docker behaviour. There is no Docker
in the test harness and none on the box this was written on; `docker rm --help`
on a deploy host confirms it in one line. The behaviour that *is* verified is
which containers the sweep chooses to remove.

## Alternatives considered

**Narrow the sweep to a container-name match so drainers fall outside it.**
Rejected: this is the bug `637a43a` fixed. A test asserts a foreign compose
project's container is still removed, so the regression goes red rather than
silent.

**Disable the sweep on the deploy path.** Rejected: it is the obvious way to stop
drainers being killed and it reintroduces the port squatter the sweep exists for.
The behaviour harness carries a positive control for exactly this: an unlabelled
container holding the port must still be removed, so a suite cannot pass for a
fix that simply turned the sweep off.

**Leave the sweep inline and patch both copies.** Rejected: it is the state that
produced this bead's central trap. Two copies that must agree, with nothing
enforcing that they do, is a defect waiting for the next edit; a test now asserts
the call-site count is exactly two and that no inline copy survives.

**Exempt by container name prefix rather than label.** Rejected: the name is what
the orchestration is free to choose, and a name convention is not checkable by
the sweep without another regex to get wrong. A label is explicit, is set by
whoever creates the drainer, and cannot be acquired by accident.
