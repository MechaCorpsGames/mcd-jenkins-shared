# Build trimming is keyed on the thing being built, not on the job

Date: 2026-08-25

Status: Accepted

Amends [2026-08-25-superseded-cancel-goes-where-nothing-is-published](2026-08-25-superseded-cancel-goes-where-nothing-is-published.md),
which is reversed on where PR trimming lives and upheld on everything else.

## Context

Tim, 2026-08-25: "for all builds other than PR builds we should be trimming to
latest. PR builds should only trim to latest when there are duplicate builds for
a single PR, otherwise, all of them should run." Bead mc-waxw. Today it is close
to the inverse on both halves.

The day before, mc-w2iu put `disableConcurrentBuilds(abortPrevious: true)` on
`mcdPRValidationPipeline`. It does exactly what it says, and it was still wrong,
for a reason invisible from the pipeline source: the option is scoped to the
**job**, and `MCD-PR-Main` is **one job serving every open pull request**. A push
to any PR aborted the in-flight build of an unrelated one.

Measured on `MCD-PR-Main`, inside one hour on 2026-08-25: #1757 (PR-2708), #1759
(PR-2716), #1761 (PR-2714), #1762 (PR-2718) and #1766 (PR-2720) all ended
`NOT_BUILT`.

The wasted executors were the smaller half. `Setup PR Info` posts a `pending`
GitHub status before anything else runs, and a killed build never replaces it, so
the PR sits on a check that can never resolve and `mergeStateStatus` goes
`BLOCKED`. PR-2714 and PR-2720 were both in that state when this was written,
with no build running for either.

**Two things had to be true for that, and reading only the first sends you to the
wrong fix.** The kill records `NOT_BUILT`, which Declarative's `post{aborted}`
does not fire on. But the killed build also never reaches the post block at all:
#1764, which genuinely passed, has a `Declarative: Post Actions` stage in its
stage list, while #1763 and #1766, which were killed, have none. So no post
handler, present or future, could have rescued those builds. Not killing them is
the fix; a status backstop is a second, independent protection for the
`NOT_BUILT`s that arrive through the pipeline's own front door.

Two diagnostic facts are worth recording, because `NOT_BUILT` on this job does
not mean any one thing and a concurrent investigation reached the opposite
conclusion from the same build list:

- A stage its `when{}` skipped costs about a second. A `NOT_EXECUTED` stage with
  real time against it was **running** when the interrupt landed: #1766 shows
  `Build GameServer, TestClient & Proxy` `NOT_EXECUTED` with 54s.
- A build that ends `NOT_BUILT` may still have passed and reported. #1764 ran
  every content stage green, posted `success: Validation passed (17 min)` to
  PR-2719, and took its `NOT_BUILT` from a trailing all-skipped block propagating
  to the run result.

Meanwhile the branch pipelines, which Tim wants trimmed hardest, trim not at all:
`mcdClientPipeline`, `mcdAppServicesPipeline`, `mcdServicesPipeline` and
`mcdDiscordBotPipeline` carry a bare `disableConcurrentBuilds()`, which serializes
and never trims, and `mcdServerPipeline` has no concurrency control at all.

## Decision

**Trimming is keyed on the unit of work, and the unit of work is different in the
two cases.** No job-level Jenkins option can express either key, which is why
both halves move out of `options{}`.

### PR validation trims on `pr_number`

`disableConcurrentBuilds(abortPrevious: true)` comes off `mcdPRValidationPipeline`
and nothing replaces it at the options level. Supersession moves to
`mcdPrSupersession.groovy`: an older build walks `currentBuild.nextBuild`, finds a
newer build carrying the **same `pr_number`**, and stands itself down.

The check needs the newer build to be RUNNING. That is precisely why the earlier
ADR rejected it, and the objection was conditional on the serialization it was
arguing for: behind `disableConcurrentBuilds` the newer build is queued with no
Run object. Removing the job-wide option removes the objection with it.

Concurrency is safe on this pipeline and only on this one. It publishes nothing:
`/opt/mechacorps` appears once, in the agent's docker mount arguments, and in no
`sh` body. Jenkins gives concurrent runs their own `@2`/`@3` workspaces. And it is
the status quo restored rather than a new experiment, because this pipeline ran
with no concurrency control at all until 2026-08-24.

A `post{cleanup}` backstop guarantees the `pending` check resolves for any build
that runs its post block, guarded on whether anything terminal was already posted
rather than on the causes of `NOT_BUILT`. The causes multiply, and one of them
(#1764) had already posted a pass that must not be overwritten.

### Branch pipelines trim on the commit, by looking backwards

`mcdRedundantBuild.groovy` records the commit each build checked out and stands a
build down when an **earlier** build of the same job already built that commit
**and succeeded**. A `Trim to Latest` stage sits after `Detect Changes` in all
five branch pipelines.

Looking backwards rather than forwards is forced: these pipelines keep their
serialization, so newer builds are queued with no Run object. It also happens to
be the safer direction. A branch build checks out the branch TIP, not the commit
its webhook carried, so builds queued behind one another check out the same
commit and do identical work; the trim collapses a burst of five pushes into one
real build of the newest commit and four visible no-ops.

The safety property is that it **only ever skips work that is already done**:

- If it under-fires, nothing is skipped and the pipeline behaves as it does
  today. That is also why the first builds after this merges will not trim, and
  why it needs no migration.
- If it fires, the artifacts for that commit were already built and published by
  the build it names.

A "newest wins" scheme keyed on the existence of a newer build would not have
that property: a lost webhook for the newest push would make every build before
it stand down for a build that never comes, and nothing would deploy.

Trimming clears the same gate flags `Detect Changes` clears when a push touches
nothing the pipeline owns, routing the build down a no-op path that already runs
several times a day. In all five pipelines the only stages with no gate at all
are `Setup`, `Checkout` and `Detect Changes`.

### What is upheld from the earlier ADR

**`abortPrevious` still goes nowhere near `mcdServerPipeline` or
`mcdClientPipeline`**, and the test that pins that exclusion is unchanged. It
aborts the older build wherever it is, with no stage scoping, and both publish
through rsync: `Deploy GameServer & TestClient` into `config.deployPath`, where
mc-ehn1 records six jobs sharing the path with no cross-job lock and mc-mhjd
requires manifest and binary to arrive together; and `Publish Bot Runtime`, an
`rsync -a --delete` into a shared path that already blew up once on `.core.XYZ`
temp files. Nothing here interrupts a running build at all.

## Rejected alternatives

- **`milestone()` on the PR pipeline.** Ordered by build number and scoped to the
  job, with no notion of a parameter, so it has the identical cross-PR defect as
  `abortPrevious`. `mcdSteamSourceBuild.groovy` already records this finding for
  one job serving four Steam branches.
- **A conditional option, e.g. `abortPrevious` only for some target.** Declarative
  parses `options{}` statically; a computed value there is a parse error in a
  shared var, which takes every job down (#82).
- **Reaching into the other run to abort it** (`rawBuild.doStop()`, or asking the
  queue what is pending). Both need privileged Jenkins API access. Nothing in this
  library has ever used one, and `mcdSteamSourceBuild.groovy` records the standing
  rule that an unverifiable dependency is not one to take blind: a shared var that
  dies at runtime breaks every job. A self-skip needs nothing beyond
  `RunWrapper.previousBuild` / `nextBuild`, which the library already relies on.
- **Trimming a branch build against the branch tip** ("my trigger sha is not the
  tip, so stand down"). It trims a queued stack correctly and has the wrong
  failure mode: a dropped webhook for the newest push strands every build behind
  it and nothing deploys at all.
- **Trimming `mcdPlayUploadPipeline` and `mcdPromotePipeline`.** Both are run by a
  person choosing a build to ship, and `mcdSteamUploadPipeline` already coalesces
  on artifact freshness (mc-fr2h). Trimming a run somebody started by hand breaks
  the one case where they are watching.

## Consequences

Different PRs no longer abort each other, and a PR's check always resolves once
its build reaches the post block. Two PR builds for the same PR can now overlap
briefly before the older one notices at its next stage boundary, which costs a
little executor time and is the price of not killing strangers' builds.

The five branch jobs gain a `Trim to Latest` stage in their stage view, and a
trimmed build shows `NOT_BUILT` with a display name naming the build that did the
work. Trimmed builds are visibly skipped, never silently green.

Nothing here has been executed against a live Jenkins: this repo has no CI, and
the pipelines it defines cannot be exercised from a pull request. The evidence is
the pytest suite (343 tests), the stage lists and GitHub statuses of the builds
quoted above, and the fact that every mechanism used here already appears
somewhere in this library. The first real test is the first burst of pushes after
it merges, and the failure direction is chosen so that under-firing looks exactly
like today.

**The five wedged PRs will not unwedge themselves.** Their checks are stuck on a
`pending` status posted by a build that is long gone; they need a re-trigger after
this merges, not before, or the cascade simply repeats.
