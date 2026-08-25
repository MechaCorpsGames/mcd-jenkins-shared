# Superseded-build cancellation goes where nothing is published

Date: 2026-08-25

Status: Amended the same day by
[2026-08-25-build-trimming-is-keyed-on-the-thing-being-built](2026-08-25-build-trimming-is-keyed-on-the-thing-being-built.md).

The decision below that `abortPrevious` goes on `mcdPRValidationPipeline` is
**reversed**: the option is scoped to the job, and `MCD-PR-Main` is one job
serving every open PR, so it aborted unrelated PRs and left their checks pending
forever (bead mc-waxw, five PRs inside one hour). PR supersession now keys on
`pr_number` in `mcdPrSupersession.groovy`.

Everything else here **stands**, including the exclusion of `mcdServerPipeline`
and `mcdClientPipeline` and the reasoning for it. One detail is worth reading with
the amendment in hand: the rejection of a `nextBuild` self-skip below is correct
*for a serialized pipeline*, and stops being true for the PR pipeline once the
serialization is removed.

## Context

Tim, 2026-08-24: "when they get stacked up, we need to manually go cancel builds
until we're building only the latest, or just have patience." Bead mc-w2iu.

The observed cost is two-sided. Executors burn on 20-25 minute builds that the
next push already superseded, and worse, a superseded build's verdict gets read
against the wrong commit. On 2026-08-24 an agent spent an hour reasoning about a
RED `jenkins/pr-validation` on MCD-PR-Main #1713; a re-run of the identical
branch head went green on #1723 and merged, with nothing changed in between.

The bead proposed `disableConcurrentBuilds(abortPrevious: true)` on
`mcdServerPipeline` and `mcdClientPipeline`, plus `milestone()` after Checkout in
both. Reading those two files first, as the dispatch asked, changed the answer.

Two things are true of the controller and are worth recording because neither is
discoverable from this repo without effort. Jenkins core is **2.479.3**, read
from the `x-jenkins` response header, which the host returns even on a 403 and is
the only thing it will tell an unauthenticated caller. And the **Lockable
Resources plugin is not installed**, recorded in `mcdClientPipeline.groovy` and
established empirically from build #64's DSL error listing the valid steps.
`abortPrevious` shipped in `workflow-job` 1289 (August 2022), so a January 2025
LTS core has it; the plugin inventory could not be enumerated, so `milestone()`
stayed unproven.

## Decision

**`disableConcurrentBuilds(abortPrevious: true)` goes on
`mcdPRValidationPipeline` only.** That pipeline had no concurrency control at
all, runs the 20-25 minute PR builds the complaint is about, and publishes
nothing: no rsync, no write under `/opt/mechacorps`. An aborted run leaves a
workspace and nothing else.

**The two pipelines the bead named are deliberately excluded, and a test pins
that exclusion.** `abortPrevious` aborts the older running build wherever it is;
Jenkins offers no stage scoping. Both of those pipelines publish through rsync:
`mcdServerPipeline`'s `Deploy GameServer & TestClient` into `config.deployPath`,
where mc-ehn1 already records six jobs sharing the path with no cross-job lock
and mc-mhjd requires a protocol manifest and its binary to arrive together; and
`mcdClientPipeline`'s `Publish Bot Runtime`, an `rsync -a --delete` into a shared
per-env path whose serialization exists *because* that rsync already blew up once
on `.core.XYZ` temp files during the MCDClient-FeatureBackend 21:01 burst.
Interrupting either mid-rsync produces the half-written state those controls were
added to prevent. Auto-cancel applies to validation and stops at the publish
boundary, which is the dispatch's own constraint.

Rejected alternatives:

- **`milestone()` after Checkout, in either pipeline.** It aborts older builds
  that have not yet *passed* the milestone. A build eighteen minutes into
  validation has passed one placed after Checkout and survives; the build it does
  cancel has cost nothing yet. It cancels the cheap case and spares the expensive
  one.
- **A self-skip in `mcdSteamUploadPipeline`'s `UPLOAD_SUPERSEDED` style**, which
  the dispatch rightly asked to be considered first as the house idiom. It cannot
  work here. The natural check is `currentBuild.nextBuild`, and on a pipeline
  serialized by `disableConcurrentBuilds()` the newer build never starts, so it
  has no `Run` object and `nextBuild` is null exactly when it is needed. The
  Steam idiom answers a *cross-job* question about archived artifacts, which is a
  different question with a different answer available.
- **Making the option conditional so the release lane is exempt.** The bead asked
  for release to be excluded by default. Declarative `options` blocks are parsed
  statically and nothing in this library puts a computed value in one; inventing
  that pattern risks a parse error in a shared var, which takes every job down
  (the #82 outage). It is also unnecessary here: the reason release is normally
  exempt is that a release *deploy* must never be aborted, and validating a pull
  request deploys nothing. `MCD-PR-Release` and `MCD-PR-FeatureBackend` therefore
  get the same treatment as `MCD-PR-Main`.
- **Adding plain `disableConcurrentBuilds()` to `mcdServerPipeline`**, which has
  no concurrency control at all today and so can already rsync two builds into
  the same deploy path at once. That is a real gap, but closing it here would
  hand the server pipeline the same queue-stacking symptom the bead is trying to
  remove, so it is left for a decision rather than taken silently.

## Consequences

- Superseded PR builds are cancelled the moment a newer one starts, on all three
  PR lanes. Executors free immediately and a stale verdict can no longer be
  attributed to a commit it was not built from.
- **The exclusion is now load-bearing and needs a guard, so it has one.**
  `test/unit/test_mcd_superseded_build_cancellation.py` fails if
  `mcdPRValidationPipeline` loses the option or regresses to a bare
  `disableConcurrentBuilds()`, and fails if either publishing pipeline *gains*
  `abortPrevious`. The obvious future edit here is someone "finishing the job" by
  applying it to all three; that edit now fails a test that names the rsync it
  would interrupt.
- The server and client pipelines keep their current behaviour, which means the
  client still queues rather than cancels. Tim's complaint is only partly
  addressed for those two jobs. The unblocking work is to make `Publish Bot
  Runtime` and `Deploy GameServer & TestClient` interrupt-safe (rsync to a temp
  directory, then rename), after which `abortPrevious` becomes safe for both and
  mc-ehn1 gets easier at the same time. That is follow-up work, not this change.
- Nothing here is compile-checked. There is no groovy, groovyc or java on the
  box and this repo has no CI, so the only evidence is the pytest suite parsing
  Groovy source plus the existing structural guards
  (`test_declarative_steps_contain_only_steps.py`,
  `test_sh_bodies_gate_their_failures.py`). The change is one line inside an
  existing `options` block with a literal argument, which is the lowest-risk
  shape available for a file whose parse errors are job-wide.
