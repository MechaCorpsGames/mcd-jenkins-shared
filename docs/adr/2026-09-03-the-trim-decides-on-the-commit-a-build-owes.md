# The trim decides on the commit a build OWES, not on the one it checked out

- **Status:** Accepted
- **Date:** 2026-09-03
- **PR:** branch `users/tim/k0z92_trim_owed_commit` (bead mc-k0z92).

Amends [2026-08-25-build-trimming-is-keyed-on-the-thing-being-built](2026-08-25-build-trimming-is-keyed-on-the-thing-being-built.md)
on what the branch trim is keyed to. That ADR's decision to trim the branch
pipelines, and its choice not to interrupt anything to do it, both stand.

## Context

Bead mc-k0z92 reports MCDClient-Main #1321 standing itself down against #1320 on
2026-08-29, leaving main's tip unbuilt for about half an hour, including a P0 fix
Tim had filed twice. Its title says the trim "compares build numbers, not commit
ancestry".

**That premise is wrong, and the correction matters because it changes the fix.**
`vars/mcdRedundantBuild.groovy` has never compared build numbers. It has one
commit in this repo's history, fe33b1c, merged to `main` in PR #108 at
2026-08-24 23:47 PDT, four days before the incident, and it compares commit shas:

```groovy
env.BUILT_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
...
if (theirs && theirs == commitSha && theirResult == 'SUCCESS')
```

The 2026-08-31 coalescing ADR already flagged the premise as worth re-checking
before anyone built from it. It is re-checked here and it does not hold.

### What the incident actually shows

Both sides of that `==` are **the commit the build checked out**. Neither side is
**the commit the build was triggered for**, which every branch pipeline publishes
as `env.commit_sha` from GenericTrigger's `$.after`.

The bead's own evidence separates the two. `#1321 trimmed (built by #1320)` is
written by the trim; the description line it quotes, `Merge pull request #2872`,
comes from `env.commit_message`, i.e. from the webhook. So #1321's webhook carried
434540193, and for the `==` to have fired its `git rev-parse HEAD` must have
returned 16c3218, the commit #1320 built. Confirmed against the MCDClient repo:

| commit | merged (PDT) | in 16c3218? |
| --- | --- | --- |
| 16c3218e (PR #2879) | 2026-08-28 19:32:16 | itself |
| 0e977753 (PR #2870) | 2026-08-28 19:32:52 | no |
| 03a7fb47 (PR #2871) | 2026-08-28 19:32:56 | no |
| cf2f4b27 (PR #2873) | 2026-08-28 19:33:00 | no |
| 317bbfdf (PR #2874) | 2026-08-28 19:33:09 | no |
| 43454019 (PR #2872) | 2026-08-28 19:33:29 | no |

Six merges in 73 seconds. 16c3218 is a strict ancestor of the other five and
contains none of them, so "#1320 already built it" was false for every one.

**What is not established:** why #1321's checkout resolved to a commit older than
the one its webhook carried. `mcdClientPipeline` carries `disableConcurrentBuilds()`,
so #1321 cannot have started until #1320 finished, by which time the branch tip was
434540193; a plain `checkout scm` should have produced it. The build logs that would
settle this are gone. The job's `buildDiscarder` keeps ten builds and it is past
#1386. Candidate explanations (a `RevisionParameterAction` pinning the checkout to
the triggering revision, a stale cached ref on the agent) are **inferences that were
not confirmed**, and this ADR does not rest on any of them.

## Decision

Key the trim on the commit the build is accountable for, and test **containment**
rather than equality:

```groovy
git merge-base --is-ancestor ${owed} ${built}
```

where `owed` is `env.commit_sha` (falling back to HEAD when there is no webhook
variable) and `built` is an earlier successful build's recorded `BUILT_COMMIT`.
`BUILT_COMMIT` stays the checked-out commit, deliberately: a later build asks "did
that build's tree contain my commit?", and only what was actually checked out can
answer that. Recording the webhook commit on both sides would compare two claims
about what should have been built rather than one fact about what was.

This is chosen over any attempt to make the checkout agree with the webhook
**because it does not depend on the unknown above**. Whatever the checkout
resolves to, a build now refuses to stand down unless the commit it was triggered
for is provably inside a tree an earlier build of this job published.

It is also strictly better in both directions:

- **Safer.** A commit that is not contained is always built. Under `==`, a build
  that checked out an older commit than it owed was indistinguishable from one
  that had nothing to do.
- **Stronger.** A build queued for an older commit is now correctly trimmed
  against a newer build that contains it. Equality could not see that at all. On
  the incident itself the fix still trims four of the five: the first build to run
  checks out a tip containing all of them, and the rest then find their owed
  commits inside it.

Everything else about the mechanism is unchanged and is load-bearing: SUCCESS only,
backwards only, manual builds exempt, nothing is ever interrupted, and the skip
happens before any publish step. Those are pinned by
`test_mcd_trim_non_pr_builds_to_latest.py`, and the reason nothing may be
interrupted is
[2026-08-25-superseded-cancel-goes-where-nothing-is-published](2026-08-25-superseded-cancel-goes-where-nothing-is-published.md),
whose exclusion of `mcdServerPipeline` and `mcdClientPipeline` is the half of that
ADR its own amendment upholds.

### Fail open, on everything

`covers()` returns false unless git exits 0. `git merge-base --is-ancestor` exits 1
for "not an ancestor" and 128 for an object the local store does not have.

**Treating those two the same is a decision, not a default.** They are different
answers. Exit 1 says the work is genuinely unbuilt. Exit 128 says nothing about the
work at all: it says the workspace is not what this build assumed, through a
force-push, a deleted branch, a shallow clone, or a checkout that never reached the
owed commit. The asymmetry of the costs settles it. A build that runs when it did
not have to costs one executor slot. A build wrongly trimmed leaves a commit
undeployed with nothing coming to replace it, which is this bead and cost half an
hour of unbuilt main with a P0 fix inside it. So containment that cannot be PROVEN
is not assumed, whatever the reason it could not be proven.

Exit 128 is also specifically the shape of the incident, a workspace not containing
the commit it owes, so standing down on it would be standing down on the evidence
that something is already wrong.

Both operands are interpolated into a shell command and both arrive from outside
(one straight off a webhook payload), so anything that is not a bare hex sha is
refused rather than quoted. `==~` is already used for this purpose in
`mcdServerPipeline` and `mcdPlayUploadPipeline`.

## Consequences

- The trim now fires in a case it used to miss, so slightly more builds will show
  as trimmed. That is the intended widening.
- A shallow clone would leave `merge-base` unable to answer and the trim would stop
  firing. Nothing is lost when that happens; the job simply does duplicate work
  again. Not observed, noted as the failure shape to expect.
- A build that succeeded while skipping every stage cannot trim anything: those
  builds end NOT_BUILT, and only SUCCESS counts. The related question of what
  `Detect Changes` compares against is a different check and is tracked separately
  as mc-okhtp.
- Nothing here is compile checked. There is no groovy, groovyc or java on the box
  and this repo has no CI, so the evidence is the pytest suite: 480 passed,
  including 10 new ones, and the 9 of those 10 that were observed failing against
  the unfixed helper.
