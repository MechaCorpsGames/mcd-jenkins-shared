# The GDScript stage carries its own deadline, and the client pipeline gets a backstop

- **Status:** Accepted
- **Date:** 2026-09-02
- **PR:** branch `users/tim/ezb8q_stage_timeouts` (bead mc-ezb8q). Paired with
  `users/tim/ezb8q_crash_exits_build` on `MechaCorpsGames/MCDClient`.

## Context

`MCDClient-FeatureCard` #216 (b4b426f) took a native signal 11 inside the headless
GDScript stage. Godot printed its crash banner and backtrace, the log reached

```
Sentry: DEBUG: Processing event ebe50260-80b4-4568-67c7-2fef115613a2
```

and stopped. The process did not exit. Reproduced locally on the MCDClient side by
putting an `OS.crash()` in a gdUnit suite: the main thread spins in state R at ~100%
CPU and never returns.

`vars/mcdClientPipeline.groovy` had **no `timeout` anywhere**, at build level or
stage level, so nothing noticed. The build ran for **10h46m** until Julius cancelled
it.

The cancel is the second half, and it is why "a human will notice" is not a control.
`mcdClientPipeline` carries `disableConcurrentBuilds()`, which is correct and is
there for a real reason (the per-env bot-runtime `rsync --delete` races otherwise).
Serialisation without a deadline means one hung build holds the whole branch.
Cancelling #217 threw a `StackOverflowError` inside
`CpsFlowExecution#notifyListeners`; `WorkflowRun.finish()` nulls the log listener
*after* that call, so #217 was written ABORTED on disk with its listener still open,
and `disableConcurrentBuilds` keys off `isLogUpdated()`, i.e. `listener != null`.
#218 was therefore refused with "Build #217 is already in progress" against nothing
running, and had to be freed from the script console.

One nondeterministic crash, three builds.

Contrary to the bead's reading, `Jenkinsfile.pr.main` was **not** unprotected. Both
`.Jenkins/Jenkinsfile.client.feature-card` and `.Jenkins/Jenkinsfile.pr.main`
delegate entirely to this library, and `mcdPRValidationPipeline.groovy` already
carried `timeout(time: 45, unit: 'MINUTES')` at build level. Only
`mcdClientPipeline` had nothing at all. Grepping the `.Jenkins/` files answers a
question about those files, not about the pipelines they call.

## Decision

**The `GDScript Tests` stage carries its own timeout in both pipelines**: 45 minutes
in `mcdClientPipeline`, 30 minutes in `mcdPRValidationPipeline`.

**`mcdClientPipeline` also gains a build-level `timeout(time: 120, unit: 'MINUTES')`**,
deliberately looser than the stage deadline, as a backstop for a hang in a stage
nobody has instrumented.

### Why stage-level is the primary control and build-level is not

A build-level timeout **aborts**. An aborted build publishes no junit, names no
stage, and reaches none of the `post` blocks, so the operator gets `ABORTED` with
nothing to read, which is barely better than the wedge it replaced. A stage timeout
**fails the stage**: the `post { always { junit ... } }` block still runs, whatever
the suite produced is still published, and the Discord notification names
`GDScript Tests`.

So the build-level number must never be the one that fires on a merely slow build,
and `test_build_level_backstop_is_looser_than_the_stage_deadline` pins that ordering.

### Why 45 minutes and not the 30 first proposed

Measured, not chosen. The longest healthy run of this stage observed is **20m45s**
(`MCDClient-FeatureCard` #219, 2026-09-02). That build's parallel
`MCDCoreExt Linux Release` branch took 10m41s against 3m56s in `MCDClient-Main`
#1377, so agent contention alone moves work in this stage by more than 2x. 30
minutes is 1.4x the worst observed: close enough that a loaded agent produces a red
build, and a control that cries wolf gets removed by the third false alarm.

The PR job's copy takes 30 minutes because it is a different measurement: **9m52s**
(`MCD-PR-Main` #2112, 2026-09-02), and because it must stay under that pipeline's
existing 45-minute build-level cap so the stage is always the thing that reports.

`test/unit/test_gdscript_stage_has_a_deadline.py` pins a floor of 25 minutes and a
ceiling of 90, with those measurements written into the failure messages, so the
next person to tune this is arguing with numbers rather than taste.

### What this does not do

It does not fix the crash. The crash is fixed in MCDClient, by not initializing the
Sentry SDK in an automated run, which is the only lever measured to make a native
crash exit rather than spin. This deadline is the part that does not care **why**
the stage hung, and it is the part that would have limited #216 to 45 minutes with
no human involved.

## Consequences

- A hung `GDScript Tests` stage fails at 45 minutes (branch jobs) or 30 (PR job),
  names itself, and publishes whatever junit exists.
- A hang anywhere else in a client build ends it at 120 minutes as an ABORT.
- The worst realistic cost of a genuine slow run is a red build at 2.2x the slowest
  healthy time ever observed. If that starts happening, the number is wrong and the
  test's failure message says which measurement to re-take.
