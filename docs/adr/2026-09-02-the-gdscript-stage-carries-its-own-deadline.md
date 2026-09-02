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

**The `GDScript Tests` stage in `mcdClientPipeline` carries its own timeout**, of 30
minutes.

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

### 30 minutes, and the argument for 45 that was not taken

Every healthy run of this stage measured on 2026-09-02:

| job | build | GDScript Tests |
|---|---|---|
| `MCD-PR-Main` | #2112 | 9m52s |
| `MCDClient-FeatureCard` | #218 | 12m20s |
| `MCDClient-Main` | #1377 | 15m43s |
| `MCDClient-FeatureCard` | #219 | **20m45s** |

30 minutes is 1.4x the slowest of those. That margin is thinner than it looks: #219's
parallel `MCDCoreExt Linux Release` branch took 10m41s against 3m56s in #1377, so
agent contention alone moves work in this stage by more than 2x, and the 20m45s end
of the range is what a loaded agent already produces.

45 minutes was considered on that basis and not taken. 30 is the number chosen, on
the grounds that it still turns an 11-hour wedge into a half-hour one and that a
false red is visible and cheap to correct while a silent 30-minute-too-loose window
is not. The measurement to re-take, if healthy builds start going red, is the one in
the table, and the number to raise is this one.

`test/unit/test_gdscript_stage_has_a_deadline.py` pins a floor of 25 minutes and a
ceiling of 90, with those measurements written into the failure messages, so the next
person to tune this is arguing with numbers rather than taste.

### What a declarative `timeout` clock actually covers

Worth stating, because the raw build durations in Jenkins contradict it on their
face. `MCD-PR-Main` #2116 finished **SUCCESS at 57m23s** under
`mcdPRValidationPipeline`'s `timeout(time: 45, unit: 'MINUTES')`. That is not a
broken timeout.

A declarative `options { timeout }` compiles to a `timeout` step *inside* the agent
allocation. The closing order in #2116's log says so:

```
[Pipeline] // timeout
[Pipeline] // withEnv
[Pipeline] // withCredentials
[Pipeline] // withDockerContainer
[Pipeline] // node
```

Queue wait, executor allocation and container startup are all outside it. So the
clock bounds the stages, which is exactly where a hung `GDScript Tests` lives, and a
build queued behind nine others is not penalised for waiting. That is what makes a
build-level timeout safe to add to a pipeline carrying `disableConcurrentBuilds()`.

It also means the 120-minute backstop is looser than it reads: it was sized against a
longest observed **total** build of 47m7s (`MCDClient-Main` #1377), and the stage time
inside that total is smaller again. Loose is the intent.

### Why `mcdPRValidationPipeline` is deliberately left alone

It carries the same stage, and it does **not** get a stage timeout here. It already
has a build-level `timeout(45, MINUTES)`, so a hang there ends in 45 minutes rather
than never; its measured run is the fastest of the four (9m52s); and it is one job
serving every open PR at once, so any new control there has a blast radius the branch
jobs do not. Adding one is a separate decision with separate evidence.

`test_pr_pipeline_still_relies_on_its_build_level_timeout` pins the premise that
argument rests on. If that build-level timeout ever disappears, the PR job silently
becomes as exposed as `mcdClientPipeline` was, and the decision has to be re-made
rather than inherited.

### What this does not do

It does not fix the crash. The crash is fixed in MCDClient, by not initializing the
Sentry SDK in an automated run, which is the only lever measured to make a native
crash exit rather than spin. This deadline is the part that does not care **why**
the stage hung, and it is the part that would have limited #216 to 45 minutes with
no human involved.

## Consequences

- A hung `GDScript Tests` stage in a client branch job fails at 30 minutes, names
  itself, and publishes whatever junit exists.
- A hang anywhere else in a client build ends it at 120 minutes as an ABORT.
- The worst realistic cost of a genuine slow run is a red build at 1.4x the slowest
  healthy time ever observed. That margin is the known risk of this change. If it
  starts happening, the number is wrong, and the test's failure message says which
  measurement to re-take.
