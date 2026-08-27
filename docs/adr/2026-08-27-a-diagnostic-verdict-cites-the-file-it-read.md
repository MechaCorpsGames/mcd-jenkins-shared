# A diagnostic verdict cites the file it read, and an empty input is never a negative

Date: 2026-08-27

Status: Accepted

## Context

Bead mc-n37x: the MCDServer integration test times out on roughly one run in
three, with the same commit going pass, fail, pass. To stop each failure costing
a rerun, PR 100 added a signature scan to `mcdServerPipeline.groovy`'s Integration
Test stage. It greps the proxy log for `send channel full`, the line
`Player.sendToPlayer` writes when it closes a player's connection because the
128-deep send channel filled, and prints a verdict either way:

```
(no 'send channel full' warning: mc-n37x hypothesis NOT confirmed by this run)
```

The bead's own next step read, in the mayor's dispatch, "IF NO: the hypothesis is
dead."

The scan was grepping a file that is empty by construction. `MCDProxy` writes
nothing to stdout or stderr: `Src/Proxy/main.go:66` defaults `--log-dir` to
`"logs"`, and `main()` at `:842-858` opens `<log-dir>/proxy.log` and points both
`log.SetOutput` and `slog.SetDefault` at it, falling back to stderr only if that
open fails. The stage never passed `--log-dir`, so its shell redirect
`> "$LOG_DIR/proxy.log"` created a correctly named empty file while the proxy
wrote its real log to the workspace `logs/` directory. Line numbers are against
MCDClient `origin/main` e548eaf14.

So for two days the scan printed the same sentence on every failing build,
regardless of the truth, and that sentence was wired to retire a live hypothesis.

Fixing the path is obvious and was done. The interesting question is why nothing
caught it, and the answer is that **the output of a scan that cannot see its
subject was indistinguishable from the output of a scan that looked and found
nothing.** Both printed "NOT confirmed". No amount of care in reading the console
would have separated them.

## Decision

Two rules, applied to the disconnect scan in both `mcdServerPipeline.groovy` and
`mcdPRValidationPipeline.groovy`, and intended for any diagnostic a pipeline
prints from here on.

**1. A verdict names its source and that source's size.** Every positive and
every negative is preceded by
`scanned <path> (<n> bytes, <m> lines)`, and the negative restates the line
count: "NEITHER ... appears in the 66 lines of \<path\> scanned above." A reader,
or a later agent, can tell from the console alone whether the instrument had
anything to work with. This is the durable half. Pointing the grep at the right
path fixes today's bug; a self-citing verdict is what stops the next
misdirection from being believed.

**2. An empty or missing input is reported as "SCAN DID NOT RUN", never as a
result.** The stage guards on `[ ! -s "$PROXY_LOG" ]` and, when it trips, says
in as many words that this refutes nothing, names the two `main.go` sites that
explain where the proxy actually writes, and lists the files that *do* exist
under the log directory with their sizes. That listing is the whole diagnosis of
a misdirected log path: "the file I expected is empty, and here is the one that
is not."

Supporting changes that follow from the same reading:

- The proxy is launched with `--log-dir "$LOG_DIR"`. This was chosen over
  teaching the stage where the default puts things, because one flag fixes the
  tail, the grep and the archive together, and it also captures the logs of every
  GameServer the proxy spawns, which the proxy hands the same `--log-dir`
  (`main.go:2610`). Stdout is still captured, as `proxy-stdout.log`, since it is
  the only thing that survives a crash before logging is initialised.
- The archive glob widens to `integration-logs/**` so those nested server logs
  are kept.
- The scan names **both** proxy paths that close a live player's connection:
  `send channel full` from `sendToPlayer` (`main.go:602`) and `player write
  error` from `writePump`'s 10-second write deadline (`main.go:576`). Both
  present to the peer as `Connection_PlayerDisconnected` mid-match. A scan for
  one of them returns a negative on a failure the other caused, which is the same
  false-refutation shape in a second guise.
- `mcdPRValidationPipeline.groovy` gets the identical treatment. It runs the same
  test against the same binaries and hits the same flake, and it is the only job
  that does so **without** deploying to development, which makes it the only one
  a flake can safely be re-fired on.

## Consequences

A failing Integration Test now either names the mechanism or records an
attributable negative. The console carries the verdict for 60 builds; the full
logs are archived for the shorter of 10 builds and 7 days.

The rules cost a few lines of shell per diagnostic and make the output longer.
That is the intended trade: a scan that says nothing useful is a nuisance, and a
scan that says something false with confidence is worse than no scan, because it
ends the investigation.

This ADR does not decide anything about mc-n37x's cause. As of writing the
hypothesis is neither confirmed nor refuted on CI. Locally the mechanism is real
and sufficient: with the send channel capacity cut from 128 to 8 as a positive
control, a run reproduces the bead's signature exactly, both clients exiting 2 at
184 seconds, with `send channel full` logged first and the write error following
on the already-closed socket. At the shipped capacity of 128 the same harness
peaked at 15-24 queued messages across 21 runs, including ten concurrent runs
with twenty clients pinned to two CPUs and a client frozen outright with SIGSTOP
for 60 seconds. That is a measurement, not a refutation, and it is recorded on
the bead rather than here.
