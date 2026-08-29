# The change-detection gate sits in front of the agent, not behind it

Date: 2026-08-28

Status: Accepted

## Context

Tim, 2026-08-28: "I feel we may be building/rebuilding the discord bot pipeline
during times when the bot or no relevant code has changed. Verify and fix if this
is true." Bead mjs-j5z.

It is true, and the detection was never the problem.

`Src/Tools/discord-bot` changed **zero** times on `main` between 2026-07-28 and
2026-08-28. In that window `MCDDiscordBot-Main` ran **409 builds**, #246 through
#655, against 599 merge commits: the job fires per PUSH, not per relevant change.
Every retained build is `NOT_BUILT` with "No discord-bot changes — skipped".

`mcdChangeDetection` is correct, and `Build` and `Deploy` were already properly
guarded on `DISCORD_BOT_CHANGED`. The waste was entirely upstream of that
decision. With `agent { docker { image 'mcd-build-agent:latest' ... } }` declared
at PIPELINE level, every push allocated an executor, started the container and
checked out MCDClient before `Detect Changes` could say "nothing to do".

The stage timings say the cost is the ALLOCATION, not the work. Build #645: ~46s
across all stages (Checkout SCM 3s, Setup 2s, Checkout 2s, Detect Changes 34s,
Trim 2s) against a wall duration of **11m52s**. #653: ~22s of stages, 3m59s wall.
Retained no-op durations: 45s, 52s, 56s, 57s, 1m53s, 3m59s, 5m32s, 10m32s,
11m52s.

The console log of #656 shows the whole shape in one place, and it is still
happening: `Still waiting to schedule task` / `Waiting for next available
executor`, then `Running on Jenkins in /var/lib/jenkins/workspace/`, then
`docker run ... mcd-build-agent:latest cat`, then four stages of detection, then
`Stage "Build" skipped due to when conditional`, then `docker stop` and
`docker rm -f`. A container started and destroyed to learn nothing had changed.

This matters now rather than in principle. Jenkins was queue-saturated the same
day, with a worker reporting 11 of 13 items unstarted and PR validations taking
36 to 108 minutes. This job takes a heavyweight executor roughly 13 times a day
to do nothing, and carries `disableConcurrentBuilds()`, so a burst of merges
queues nose to tail.

## Decision

**The pipeline declares `agent none`, and the heavyweight agent moves onto the
one stage that needs it, behind a gate that is evaluated before the agent is
entered.**

### `beforeAgent true` is the load-bearing line

Declarative enters a stage's agent FIRST and evaluates its `when` SECOND, unless
`beforeAgent true` says otherwise. This is the whole mechanism, and it is not the
default.

Without that line the change would be a silent no-op that looks fixed: the
guards would still skip the work, the build would still report `NOT_BUILT`, the
stage view would look identical, and the container would still start on every
push. Nothing observable distinguishes the fixed pipeline from the broken one
except whether a container was created.

That is why `test_mcd_discord_bot_agent_is_gated.py` asserts on the reachability
of the agent rather than on the presence of a guard. A test asserting "Build has
a `when` guard" would have passed on all 409 wasteful builds.

### Stages are grouped by AGENT, because an agent is also a workspace

The stages are not given an agent each. They are grouped into two, because
splitting an agent splits the workspace, and that is how this change could break
the deploy it is meant to leave untouched.

- **`Detect`** (`agent any`) wraps `Setup`, `Checkout`, `Detect Changes` and
  `Trim to Latest`. One workspace, so `Detect Changes` reads the history
  `Checkout` just fetched.
- **`Build & Deploy`** (the docker agent, gated) wraps `Build` and `Deploy`. One
  workspace, so `Deploy` installs the binary `Build` produced. An agent each and
  `install` would copy a binary that was never built there.

`Build` gains its own `checkout scm`, because that group now owns a fresh
workspace. It runs only when the bot actually changed, which was 0 times in the
month measured, so it costs nothing in practice.

The inner `when` guards on `Build` and `Deploy` are left exactly as they were.
They are now redundant with the group's gate, and they stay: they are what
`test_mcd_trim_non_pr_builds_to_latest.py` enumerates to prove the trim clears
every flag that gates a stage.

### `agent any`, not a label

This Jenkins has no agent nodes, only executors on the controller
(`mcdSteamSourceBuild.groovy` records "the controller had four executors and no
agents"). A label nobody publishes would leave the job queued forever.
`mcdPromotePipeline` runs on `agent any` for the same reason and executes `sh`
bodies there.

**That the detection can run there is read from the job's own console log, not
assumed.** In #656 the implicit `Declarative: Checkout SCM` performs a full
`git fetch` and `git checkout -f` on the node, reporting `git version 2.39.5`,
BEFORE `withDockerContainer` starts. The same log carries the JENKINS-30600
warning on the explicit `Checkout` stage ("a typical symptom is the Git
executable not being run inside a designated container"), so that checkout was
already executing on the host despite sitting inside the docker agent. Moving
detection to `agent any` does not relocate git. It stops misdescribing where it
already runs.

## Rejected alternatives

- **Filtering at the webhook**, extending `GenericTrigger`'s `regexpFilterText`
  to the push payload's changed paths so the job never triggers. Cheaper still,
  and rejected on failure DIRECTION: GitHub truncates the push payload's commits
  array (20 commits, limited files), so a large push could hide a real
  discord-bot change and silently strand a deploy. This repo has already been
  here. `test_webhook_env_stays_execable.py` records that the webhook filter was
  cut back to `$ref` alone precisely because path filtering belongs in
  `Detect Changes`, "which reads the diff from git instead of from the webhook
  body", and its `test_no_generic_variable_flattens_an_unbounded_array` covers
  every pipeline in `vars/`. The idea is not merely worse here, it is already
  fenced off by a test.
- **An agent per stage.** Splits the workspace four ways in the detection group
  and two ways in the build group, so `Detect Changes` reads an empty directory
  and `Deploy` installs a binary that was never built. It would still pass a
  naive "the agent is not at pipeline level" check, which is why the workspace
  properties are pinned separately.
- **Widening this to the other branch pipelines.** `mcdClientPipeline`,
  `mcdServerPipeline`, `mcdServicesPipeline` and `mcdAppServicesPipeline` share
  the shape: a pipeline-level docker agent in front of a `*_CHANGED` gate. They
  also do real work on nearly every push, so the same restructure buys little
  and risks a great deal, and none of them has the measurement this one has.
  mcd-jenkins-shared #117 broke three consecutive MCDServer deploys today and was
  reverted by #119. A separate bead with its own numbers, or nothing.
- **Dropping the now-redundant inner `when` guards.** They cost nothing and the
  trim's coverage check reads them.

## Consequences

A push touching nothing under `Src/Tools/discord-bot` no longer starts the
`mcd-build-agent` container. It still takes a controller executor for the
detection, which is the cheap part: ~34s of git in #645 against 11m52s of wall
clock. A push that DOES touch the bot builds, installs and restarts
`mcd-discord-bot.service` exactly as before, with one added `checkout scm` into
its own workspace.

The stage view gains two grouping stages, `Detect` and `Build & Deploy`, and the
implicit `Declarative: Checkout SCM` disappears, since that only runs when the
pipeline itself has an agent. The explicit `Checkout` stage was always doing that
work a second time.

**Nothing here has been executed against a live Jenkins.** This repo has no CI
and the pipelines it defines cannot be exercised from a pull request. The
evidence is the pytest suite (425 tests, up from 418 on b2f3422), the console
log and stage timings of the builds quoted above, and the fact that every
mechanism used here is either already in this library or read out of this job's
own log.

The failure directions were chosen deliberately and they are not symmetric:

- If `beforeAgent` does not behave as documented, the container starts as it does
  today. The change degrades to the status quo; nothing breaks.
- If the detection group could not run on `agent any`, the job would break
  outright on the next push. That is the dangerous direction, and it is the one
  verified against #656's log rather than reasoned about.
- If the added `checkout scm` were wrong, it would strand a bot deploy. That path
  has run 0 times in a month, so the first exercise of it will be the first
  discord-bot change after this merges, and it should be watched.
