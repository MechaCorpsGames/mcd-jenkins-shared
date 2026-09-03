# The change-detection base is the last build that ran, not the last push

Date: 2026-09-02

Status: Accepted

Bead: mc-okhtp. Same family as
[2026-08-25-build-trimming-is-keyed-on-the-thing-being-built](2026-08-25-build-trimming-is-keyed-on-the-thing-being-built.md)
and mc-k0z92, and it interacts with both: the trim rule is one of the things
that produces the aborted build this closes over.

## Context

Every webhook-triggered branch pipeline took its change-detection base straight
from the push payload:

```groovy
def baseRef = env.before_sha        // $.before
def changes = mcdChangeDetection.detect(baseRef)
```

`$.before` is the tip of the **previous push**. It is a fact about the git
history, not about Jenkins, and in particular it says nothing about whether any
build ever evaluated the range it opens. The pipelines were treating it as if it
did.

That assumption holds only while every build finishes. It breaks the first time
one does not, and there are at least three routine ways for that to happen: the
trim rule standing an earlier build down, a Jenkins restart, and a person
pressing the X.

**Measured 2026-09-02 on MCDClient-Main and MCDServer-Main.**

1. Push `ad2e8573a`, the merge of PR #3045: `custom.tscn`,
   `opponent_hand_fan.gd` and `.tscn`. Real client changes. `MCDClient-Main`
   #1385 starts.
2. Push `d4f210a96`. Relative to `ad2e8573a` it adds ONLY
   `docs/plans/mc-5dcgd-deck-builder-overhaul.md`. #1386 queues behind
   `disableConcurrentBuilds()`.
3. The trim rule aborts the earlier build. #1385 ends `ABORTED`, having built
   nothing.
4. #1386 runs. Its log reads `Building d4f210a (no earlier build of this job has
   built it)`, then `=== Changed files (1) === docs/plans/...`, then `Change
   detection: server=false, client=false`, then every stage `skipped due to when
   conditional`. Result `NOT_BUILT`. `MCDServer-Main` #1151 did the same.

PR #3045's client changes were never built or tested by any Main pipeline.

The part that makes this worth an ADR rather than a one-line patch is the shape
of the failure. Nothing went red. `main`'s tip was **green by absence**: there
was no failing build to notice, because there was no build. The mayor caught it
by reading a `NOT_BUILT` badge and asking why, and re-triggered #1387 / #1152 /
#949 by hand at about 17:40Z. Nothing in the system would have raised it.

## Decision

Take the base from **the last build that actually reached a verdict on a tree**,
not from the last push.

A new shared var, `vars/mcdChangeBase.groovy`, exposes `resolve(beforeSha)`. All
five branch pipelines change one line:

```groovy
def baseRef = mcdChangeBase.resolve(env.before_sha)
```

`resolve()` walks back through `currentBuild.previousBuild` and returns the
commit recorded by the most recent build whose result is `SUCCESS`, `UNSTABLE`
or `FAILURE`. Those three ran the stages and reported on the tree, so everything
up to and including their commit has been looked at.

It needs no new state. `mcdRedundantBuild.trim()` already writes
`env.BUILT_COMMIT` on every build, trimmed or not, and later builds already read
it back through `RunWrapper.buildVariables`. This is the second consumer of that
same variable.

### `NOT_BUILT` is excluded, and that is the load-bearing half

`ABORTED` is the obvious exclusion: #1385 ran nothing.

`NOT_BUILT` is the one that is easy to get wrong, and excluding it is what makes
this a fix rather than a delay. A `NOT_BUILT` build looks trustworthy. It ran
Detect Changes, it diffed, and it concluded honestly that nothing it owns
changed. Anchoring to it would keep diffs pleasantly narrow.

But #1386 **was** a `NOT_BUILT` build, and it was sitting on a base that had
already skipped over real client changes. Had #1387 anchored to #1386's commit,
`ad2e8573a` would never have been built by any future build either. The bug
would have become permanent rather than transient. So the rule keys on "did this
build reach a verdict", not on "did this build look at something".

The cost of that exclusion is bounded and lands on the safe side: a run of
`NOT_BUILT` builds pushes the anchor further back, which widens the diff.

### The invariant to preserve: this can only widen a diff, never narrow one

Every branch of `resolve()` returns either a commit at least as old as
`before_sha`, or `null`, which routes the caller down the build-everything path
it already had for a missing before SHA. There is no input for which the new
code reports fewer changed files than the code it replaces.

This is stated in the file header and pinned by tests because a narrowing bug
here is invisible by construction: its symptom is a build that does not happen.
Failure modes were chosen to land on the safe side deliberately.

- No earlier build recorded a commit (fresh job, or the previous builds predate
  this change): return `before_sha`, i.e. exactly today's behaviour. It starts
  working on its own, the way the trim did.
- The lookback (20) runs out first: return `null`, build everything. Self-
  healing, because that build reaches a verdict and becomes the fresh anchor.
  On `MCDClient-*` the `buildDiscarder` keeps 10 builds, so retention hits before
  the lookback does.
- A candidate will not fetch, or is not an ancestor of `HEAD`: discard it. This
  check is not decoration. `git diff` between commits on unrelated histories
  exits 0 and prints a plausible file list, so a dangling base from a force push
  would never reach `mcdChangeDetection`'s `__DIFF_FAILED__` fallback. It would
  produce a confident wrong answer instead.
- Both candidates are usable but disagree: take the older one.
- The history walk throws: fall back to `before_sha`.

### Hand-started builds keep building everything

A manual run carries no webhook payload, so `before_sha` is unset and the
caller's existing branch builds everything. `resolve()` returns `null` on that
case **before** it reads any build history, so a hand-started build is never
narrowed to a history-derived base. Pressing Build is how a person asks for the
full thing, usually because they already suspect the incremental state is wrong.

## Alternatives considered

**A `FORCE_FULL_BUILD` boolean parameter**, which the bead proposed as the
manual escape hatch. Not added, because the escape hatch already exists and is
strictly easier to reach. These jobs have no `parameters {}` block, and a
parameter can only be set on a build somebody starts by hand; a build somebody
starts by hand already builds everything, for the reason above. Adding the
parameter would mean adding a `parameters {}` block to five pipelines to expose
a control whose only reachable setting duplicates the default behaviour of the
only builds that can reach it. If webhook-triggered builds ever need to be
forced full, that wants a different mechanism than a build parameter.

**`GIT_PREVIOUS_SUCCESSFUL_COMMIT`**, which Jenkins maintains for free. Rejected
on two counts. It tracks `SUCCESS` only, so a `FAILURE` build that genuinely did
evaluate the tree would not advance it and every subsequent diff would widen
until something passed. And it is maintained by the git plugin against the
build's own SCM checkout, which is not the same question as "what did this job
last render a verdict on" once a job's checkout behaviour changes. `BUILT_COMMIT`
is written by this library, on purpose, and already proven in
`mcdRedundantBuild`.

**Making the trim rule not abort.** That would close this particular hole and
leave the other two open. A Jenkins restart and a human cancel produce the same
`ABORTED` build, and the base selection would still be wrong for both.

## Consequences

- After an abort, the next build diffs from further back and does more work than
  before. That is the point, and it is the whole cost.
- Diffs are unchanged in the steady state where builds keep up with pushes,
  because `before_sha` and the last evaluated commit are then the same commit.
  `resolve()` returns early on that equality without running any git.
- `BUILT_COMMIT` now has two consumers. It was already load-bearing; it is more
  so. It is written in `Trim to Latest`, which runs after `Detect Changes`, so
  a build that dies before reaching it records nothing and is skipped by the
  walk, which widens rather than narrows.
- The interim rule the mayor was following by hand (after aborting an earlier
  build, check the survivor's Detect Changes line and re-trigger if it reports
  `NOT_BUILT`) is no longer needed for the abort case.

## Verification status

`mcd-jenkins-shared` has no CI, so no check in this repository runs on a push.
Everything below was run by hand and the numbers are from those runs.

The source-shape evidence is:

- `test/unit/test_mcd_change_base_is_the_last_evaluated_build.py`, 19 cases,
  parsing the Groovy source, all observed failing against the unfixed tree
  before the fix was applied (13 of 19; the other 6 pin properties that predate
  the change). Two positive controls were run to show the checks are not
  vacuous: loosening the helper to accept `NOT_BUILT` reddens exactly the
  `NOT_BUILT` exclusion test, and moving the hand-started-build guard after the
  history walk reddens exactly the ordering test.
- The full repo suite, 482 passed, 0 failed.

**The helper is now executed, not only parsed (2026-09-03).** The first version
of this ADR said no Groovy or Java runtime was available locally, and treated
that as fixed. It was not: a JRE and Groovy 4.0.22 unpack into a scratch
directory with no root and no change to the machine, and once they are there the
file under test can simply be run.

`test/groovy/mcd_change_base_behaviour.groovy` compiles `vars/mcdChangeBase.groovy`
through a `GroovyShell` whose base script class supplies the two steps the helper
calls, `echo` and `sh`. `sh` is not stubbed: it runs bash in a real git
repository built for each case, so `usableBase()` and the older-of-the-two
ordering answer to `git merge-base` rather than to a fake that agrees with them.
`currentBuild` is a stand-in exposing `previousBuild`, `result` and
`buildVariables`, and it records every read.

21 cases, 0 failed. They cover the incident end to end (the anchor lands on
#1149 and the widened diff surfaces `custom.tscn` while the old base hides it),
the full verdict matrix including a still-running build, a run of `NOT_BUILT`
builds, the three hand-started forms, both sides of the lookback bound, an
unreadable build, a walk that throws, a base on an unrelated root, two unusable
candidates, and a push that already reaches further back than the last build.
Every one of the 19 `resolve()` calls additionally asserts the widening
invariant: the base returned is `before_sha`, an ancestor of it, or null.

Eight positive controls were run against the fixed tree, each reddening exactly
the predicted cases and nothing else:

| Mutation | Reddens |
| --- | --- |
| accept `NOT_BUILT` as evaluated | the incident, the `NOT_BUILT` case, the `NOT_BUILT` run (3) |
| walk history before the hand-started guard | the three hand-started cases (3) |
| `usableBase()` always true | the non-ancestor case, the two-unusable case (2) |
| drop the older-of-the-two ordering | the already-wider push, on the invariant (1) |
| lookback 20 to 5 | the just-inside-the-lookback case (1) |
| drop the try/catch around a candidate read | the unreadable-build case (1) |
| count a still-running build as evaluated | the still-running case (1) |
| a syntax error in the helper | all 21 |

Two of those (`usableBase()` always true, and dropping the ordering) are caught
by the widening invariant rather than by a case-specific assertion, which is the
evidence that the invariant assertion is load-bearing and not decoration.

### The control that did not fire

The first attempt at the hand-started-build control reddened nothing, and that
is the part of this work worth carrying elsewhere.

The case originally proved the ordering by making the build history **throw**,
on the reasoning that a `resolve()` which walked first would surface the
exception. It cannot work. `lastEvaluatedCommit()` catches exactly that
exception by design, as its documented fail-open, so the walk swallowed the
probe and the case passed against code that consulted history first and guarded
second. It asserts on recorded reads now, and the mutant prints its own
evidence: `[#2.previousBuild, #1.result, #1.buildVariables]`.

Stated generally, because it is not specific to this file:

> An exception-based probe is defeated by the fail-open error handling you WANT
> in CI code, so the more resilient the code, the more thoroughly it hides a
> test shaped like that. If your test's claim is "X did not happen" or "A ran
> before B", assert on a recorded side effect, not on a throw.

A control that fails to fire is the only way to find a test shaped like that
one, and it is the hardest kind of negative result to notice, because a control
that reddens nothing looks exactly like a run that went well.

`test/unit/test_mcd_change_base_executes.py` wraps the harness for pytest. It
skips when no Groovy runtime is present, since there is none on the dev boxes,
and the skip reason says plainly that a skipped run proves nothing about
behaviour. One test in that file never skips: it asserts the harness still
exists and still carries the invariant assertion, because a deleted behaviour
test and a skipped one look identical in a pytest summary and this repo has no
CI to tell them apart.

**What the executed harness still does not cover.** Jenkins runs pipeline code
through the CPS transform and the script security sandbox, and its Groovy
dialect is 2.4-flavoured rather than the 4.x used here. A construct that
compiles under this harness can still be rejected in-sandbox or mistransformed
by CPS. The helper deliberately stays inside the old dialect, but that is a
property of how it was written, not something this harness checks.

**Validated against the incident's own recorded build history (2026-09-03).**
`MCDServer-Main` keeps 60 builds, so #1149 to #1152 were still in retention and
the rule could be checked against real data rather than against a reading of the
API:

- The two API reads this depends on are proven live. #1155
  (`trimmed (built by #1154)`) and #1144 (`Trimmed: #1143 already built
  2d47036`) show `candidate.result` and `candidate.buildVariables['BUILT_COMMIT']`
  returning real values. #1144 is the informative one: #1143's display name is
  `v0.2.1143 (d2d85cc)` while its `BUILT_COMMIT` was `2d47036`, confirming that
  `BUILT_COMMIT` is the tip actually checked out rather than the webhook's
  `commit_sha`.
- #1150, the abort, ends `Still waiting to schedule task / Waiting for next
  available executor / Hard kill! / Finished: ABORTED`. It never got an
  executor, so it never reached Trim to Latest and recorded no `BUILT_COMMIT`.
  Both exclusion conditions agree on it independently.
- #1151 logged `Changed files (1) docs/plans/mc-5dcgd-deck-builder-overhaul.md`
  and `server=false, client=false`. Its base is confirmed without the log head:
  `git diff --name-only ad2e8573a d4f210a96` returns exactly that one file.
- Applying the rule, the anchor is #1149 at `56ae238`. `git diff --name-only
  56ae238 d4f210a96` returns **38 files**, including 15+ `Src/GameServer/` files
  and `Src/Include/protocol_ext.h`. That last categorises as `shared`, so
  `serverChanged`, `clientChanged` and `mcpGameServerChanged` all become true.
  The unbuilt range on the server job contained a wire-format change.
- #1152, Tim's manual re-trigger, ran every server stage, which closed the hole
  by hand and independently confirms the manual-escape-hatch claim above.

One number is inferred rather than read: #1149's `BUILT_COMMIT` could not be
read directly (its log is 47 minutes long and only the tail is retrievable).
`56ae238` is its display-name commit, corroborated by #1150's `before_sha`. A
different value would be an earlier tip, which widens further.

**What is still unverified**: the runtime behaviour on a real controller. The
bead asks for a reproduction on a throwaway branch job (push A with a client
change, push B with docs, abort A's build, confirm B's build detects
`client=true`), and that has NOT been run. The section above applies the rule to
real recorded results by hand, and the harness runs the code under plain Groovy;
neither shows the controller running `resolve()`, and neither exercises the
script sandbox or the CPS transform.

That reproduction is blocked on job creation rather than on Jenkins being busy.
`.Jenkins/JOBS.md` records that jobs here are created by hand in the controller
UI, with no job-config-as-code in either repo. There is no sandbox job on the
controller, every existing job loads `@Library('mcd-shared')` unpinned so none
of them would load this branch, and an agent has no controller credentials.
