# The MCP Game Server Tests stage carries its own go test timeout

Date: 2026-08-23

Status: Accepted

## Context

Six `MCD-PR-Main` builds died in the `MCP Game Server Tests` stage on
2026-08-23: #1616, #1617, #1619, #1620, #1621 and #1622. None of them named a
failing test. Every one ended at the byte-identical point:

```
ok  github.com/mechacorpsgames/mcp-game-server/artifacts/decisionlog  0.014s
wrapper script does not seem to be touching the log file in
/var/lib/jenkins/workspace/MCD-PR-Main@2@tmp/durable-9b5155f0
(JENKINS-48300: if on an extremely laggy filesystem, consider
-D...HEARTBEAT_CHECK_INTERVAL=86400)
```

Build #1622 spent 10m7s in the stage before it died.

Two hypotheses were chased across a full day and both were wrong. Protocol
drift was disproved: #1621 and #1622 ran on branches rebased onto main after
`PROTOCOL_VERSION` 52 was synced and failed identically. Host contention was
disproved: the same stop point was reproduced at load 0.63 and at load 29.4.

The JENKINS-48300 line is the thing worth understanding, because it is a
symptom that reads like a cause. It does not mean the filesystem is slow. It
means the step produced no output for long enough that Jenkins' durable-task
wrapper concluded the process was gone. Chasing the "laggy filesystem" advice
in that message is chasing nothing.

Why a hung Go test produces no output at all is the actual mechanism.
`go test ./...` runs packages concurrently but buffers each package's output
until that package finishes, and it emits packages in the order they were
listed rather than the order they complete. A single stuck package therefore
silences the entire run, including every package that already passed behind it.

Go can report this condition itself, and reports it well: the per-binary test
timeout panics with a full goroutine dump naming the stuck test. The catch is
that its default is 10 minutes, and the stage ran with no `-timeout` at all.
Ten minutes is longer than Jenkins is willing to sit through a silent step, so
Jenkins always won the race and the diagnostic that would have ended this in
one build was never printed. Six builds produced a heartbeat warning and
nothing else.

## Decision

Both copies of the stage, in `mcdPRValidationPipeline.groovy` and
`mcdServerPipeline.groovy`, pass an explicit `-timeout 4m` to both the unit run
and the integration run.

The value is chosen against two constraints pulling in opposite directions.

It has to be **below the durable-task heartbeat window**, not merely below Go's
10m default, or Jenkins still kills the step first and nothing improves. No
`HEARTBEAT_CHECK_INTERVAL` is configured anywhere in this library, so the
plugin default applies. We did not read the value off the controller, so 4m is
picked to sit under the documented default rather than tuned against a measured
one. If a future hang still dies silently at some other interval, that
measurement is the first thing to take.

It has to leave **headroom over a healthy run**, or the control becomes a flake
generator, which is a worse failure than the one it replaces. Build #1633 ran
this stage green in 53s covering both invocations, and the same suite runs in
13s on a developer box. 4m is over 4x the slowest healthy run observed on the
build host.

`test/unit/test_mcp_game_server_stage_timeout.py` pins all of it: that the flag
is present on every `go test` in the stage, that it stays under the 10m default
(passing the default explicitly would restore the bug while looking like a
fix), that it stays at or above 3m so load spikes do not redden builds, and
that the two pipeline copies agree. The last one matters because these two
stage bodies are copy-pasted siblings and have drifted before.

## What this does and does not fix

This does not fix whatever hangs. It makes the next hang name itself instead of
costing a day.

That distinction is the whole point, and it needs stating plainly because a
reader skimming the diff will see a green stage and assume the hang is gone.
The hang is bead mc-11nh and it is still open.

Two things about it were established while making this change and are worth
recording, because both correct a premise that was being reasoned from.

**The culprit is one package, not eight.** Because `go test` prints results in
list order rather than completion order, "ok `artifacts/decisionlog` then
silence" means the very next package in the list never finished. That is
`cmd/playtest-bench`. It is not, as previously assumed, an open set of all
eight packages that failed to report. The ordering was confirmed directly: in a
local run `cmd/playtest-bench` (3.017s) printed before `personapack` (0.004s),
which had finished roughly 750 times sooner.

**MCDClient PR #2646 is a different defect in that same package.** #2646 fixed
`terminateDetachedClaude`, which shelled out to `kill(1)`. The build agent image
is `debian:bookworm` installed with `--no-install-recommends` and no `procps`,
so `/bin/kill` genuinely is absent there and the terminate path genuinely was
broken. But running that package with `kill` removed from `PATH` reproduces the
defect as a **failure in 5.1 seconds**, with
`TestTerminateDetachedClaude_PidfileAppearsMidPoll` failing at 2.70s. The same
package built at #2646 passes the same sandbox in 3.5s. A fast named failure
cannot produce a ten minute silence, so #2646 is not this bug. It is a real fix
for the 24.4s failure seen in build #1632, and the two were easy to conflate
because they live in the same package.

## Alternatives considered

**Raise `HEARTBEAT_CHECK_INTERVAL`, as the JENKINS-48300 message suggests.**
This is the message's own advice and it is backwards. It buys silence rather
than removing it: the stage would sit quiet for as long as the new interval
allows and then still die without naming a test. It also applies to every
durable task on the controller, so one stage's hang would be paid for by every
other job's ability to detect a genuinely dead step.

**`go test -p 1 ./...` to serialise packages.** This makes reporting order
meaningful, which is a real diagnostic gain, and it was recommended on the
bead. It is the wrong permanent setting: it serialises a suite whose slowest
package (`transport`, 12.4s locally) currently overlaps with the rest, and it
still yields silence for the full duration of whichever package hangs. It stays
useful as an ad hoc debugging flag, not as pipeline configuration.

**A Jenkins `timeout()` block around the stage.** This bounds the damage but
throws away the diagnostic, which is the expensive part. Jenkins aborting the
step at 4m tells you no more than Jenkins aborting it at 10m did. Only Go's own
timeout produces the goroutine dump, so the timeout has to live in the test
runner.
