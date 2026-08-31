# A merge burst coalesces in the queue, not by aborting builds

Date: 2026-08-31

Status: Accepted

## Context

Tim, 2026-08-31: "trim the jenkins builds so only the latest build actually
builds." Bead mc-h2nm2. The immediate queue was trimmed by hand that night: ten
queued items cancelled, keeping the newest per job.

Nine merges to `features/backend` in four minutes queued eight `MCDServer`
builds and four `MCDAppServices` builds behind one running build. Every one of
them would have checked out the same branch tip and repeated the same ten to
twenty minutes of work.

**Why they never collapsed, measured on the controller rather than inferred.**
Jenkins coalesces queued items of the same job only when their PARAMETERS are
identical. Read straight off `/queue/api/json`, every queued item on these jobs
carried exactly one parameter:

```
jenkins-generic-webhook-trigger-plugin_uuid = <a fresh uuid per invocation>
```

The Generic Webhook Trigger plugin stamps that on every push, so no two requests
are alike and nothing can ever collapse. Combined with
`disableConcurrentBuilds()` a burst does not coalesce, it SERIALIZES.

`mcdRedundantBuild.groovy` already mitigates the symptom: a build whose commit an
earlier build of the same job already built successfully stands itself down. But
it cannot stop the queue filling, because the skip happens only once the build
has started and taken an executor.

## Decision

**Set `allowSeveralTriggersPerBuild: true` on the five branch pipelines'
`GenericTrigger`, and let Jenkins' own queue coalescing do the work.** No queue
policing, no aborts, no new mechanism.

The field was read out of the INSTALLED plugin (generic-webhook-trigger 2.3.1 on
Jenkins 2.479.3) rather than out of its documentation, because the whole decision
rests on what it actually does:

- `GenericTrigger.trigger()` reads the field at bytecode offset 122 and passes it
  as the third argument to `ParameterActionUtil.createParameterAction`.
- `createParameterAction` adds the uuid `StringParameterValue` **if and only if**
  that argument is `false`.
- The field has four bytecode references in the entire plugin: the declaration,
  its setter, its getter, and that one call. It does nothing else.
- `getParametersWithRespectToDefaultValues` only ever iterates the job's
  DECLARED `ParameterDefinition`s. It never turns a webhook variable into a
  parameter.

All sixteen webhook jobs on the controller declare no build parameters at all
(no `ParametersDefinitionProperty` in any of their `config.xml`). So with the
uuid gone, a queued item carries NO parameters, and two pushes to the same branch
are indistinguishable to the queue, which is exactly what makes them collapse.

**Measured on the controller, on a scratch job created and deleted for the
purpose.** Five webhooks with distinct payloads, `disableConcurrentBuilds()` on:

| `allowSeveralTriggersPerBuild` | builds | queued items left |
| --- | --- | --- |
| `false` (today) | 1 | **4** (queue ids 21597, 21598, 21599, 21600) |
| `true` | 1 | **0** |

The coalesced build recorded **all five causes**, so the audit trail of which
pushes it answers survives.

### The publish constraint, and why this satisfies it rather than talks past it

ADR 2026-08-25-superseded-cancel-goes-where-nothing-is-published excludes
`abortPrevious` from the two publishing pipelines because it aborts the older
build wherever it is, and both publish through rsync into shared paths.

**Coalescing acts on the QUEUE, before a build exists.** There is no older build
to abort, no running build is signalled, and nothing can land inside an rsync,
because the mechanism has no way to reach a running build at all. The exclusion
is not weakened; it is not engaged.

`disableConcurrentBuilds()` stays on all five pipelines and is pinned by a test.
Coalescing collapses duplicate REQUESTS; it does not stop two genuinely different
commits building at once, and that serialization is what keeps two builds out of
the same deploy path (mc-ehn1).

### The variable the coalesced build keeps, which is load-bearing

`Detect Changes` computes `baseRef = env.before_sha` and gates the whole build on
the resulting diff. If a coalesced build kept the LAST push's `before_sha`, its
window would be `last^..tip` and the earlier pushes' changes would be invisible:
a build could mark itself `NOT_BUILT` while the work that mattered went unbuilt.

Measured, not assumed: the surviving build kept the **FIRST** push's variables
(`before_sha=before0001` after a five-push burst). The window therefore spans the
whole burst. A coalesced build over-detects; it cannot under-detect.

## Rejected alternatives

- **`disableConcurrentBuilds(abortPrevious: true)`.** Forbidden for these
  pipelines by the ADR above and pinned by
  `test_mcd_superseded_build_cancellation.py`. Also solves the wrong problem: it
  kills builds that have already taken an executor rather than stopping the queue
  filling.

- **`milestone()` placed immediately before the publish stage.** This was rated
  the second route and deserves its own paragraph, because the placement argument
  is genuinely sound and the objection to it is elsewhere. A milestone can only
  abort an older build when a NEWER build PASSES that milestone. Under
  `disableConcurrentBuilds()` the newer build never starts, has no `Run`, and
  reaches no milestone, so there is nothing to trigger the abort. This is the same
  objection the earlier ADR raised against a `currentBuild.nextBuild` self-skip,
  and it applies to milestones for the same structural reason: both look FORWARD
  at a build that, here, does not exist yet. Milestones would start working only
  if the serialization were removed, and the serialization is protecting the
  rsync.

- **A queue-trim step that cancels all but the newest queued item.** It works,
  and it is what was done by hand. It is also a new mechanism policing a queue
  that this change stops from filling, with its own failure modes (which item is
  "newest", what happens to a manual run, what happens when two jobs interleave).
  Least code wins.

- **Applying the flag to `mcdPRValidationPipeline` as well.** This is the edit a
  future reader will want to make, so it has a test and a comment rather than
  silence. That job serves EVERY open pull request from one job and also declares
  no build parameters, so removing the uuid would make two queued items for
  DIFFERENT pull requests parameter-identical. Jenkins would collapse them, the
  survivor keeps the first payload, and the other PR's check would sit pending
  forever with no build running. That is mc-waxw arriving through a different
  door. Supersession there is keyed on `pr_number` in `mcdPrSupersession.groovy`
  and is unaffected.

## Consequences

- A burst of N merges to one branch produces ONE build per job instead of N, with
  no hand intervention and nothing aborted.

- **The change takes effect one build later per job.** These triggers are declared
  in the pipeline's `triggers { }` block, and Jenkins writes the job's
  `config.xml` from it when the Jenkinsfile next runs. Each job's first build
  after this merges is what installs the new trigger config; coalescing applies
  from the burst after that. The current `false` in every job's `config.xml` is
  itself evidence of this: the library never set the field, so that value was
  persisted from the plugin default at the last run.

- **If a webhook is lost, nothing is skipped.** This matters because
  `mcdRedundantBuild.groovy`'s header calls it out as the property that
  distinguishes a safe scheme from an unsafe one. Coalescing merges PENDING
  requests and the survivor does the full work at the branch tip; it never decides
  "a newer build will handle this". Lose the middle webhook of a burst and the
  surviving build still builds the tip, which contains it. Lose the last one and
  the build that runs still checks out the tip at start time. No build ever stands
  down for a build that has not happened.

- `mcdRedundantBuild.groovy` keeps its job and is unaffected. It answers a
  different question, "has an earlier build already built this exact commit",
  which still arises when a build is queued for an unrelated reason or when a
  manual run overlaps.

- This does not fix, and does not inherit, bead mc-k0z92. That bead reports "Trim
  to Latest" skipping on build NUMBERS rather than commit ancestry. Coalescing is
  not a trim: it merges requests before any build exists and never decides that
  somebody else already built something. Worth flagging separately, as an
  observation from reading rather than a diagnosis: `mcdRedundantBuild.groovy` has
  exactly one commit in this repo's history (fe33b1c, 2026-08-24, five days before
  mc-k0z92 was filed) and compares `git rev-parse HEAD` against an earlier build's
  `BUILT_COMMIT` for exact equality, which is a commit test rather than a
  build-number one. The premise on that bead is worth re-checking before anyone
  builds from it.

- Nothing here is compile-checked. There is no groovy, groovyc or java on the box
  and this repo has no CI, so the evidence is the pytest suite parsing Groovy
  source (448 tests, including 17 new ones) plus the controller measurement above.
  The change is one literal field inside an existing call, which is the
  lowest-risk shape available in a file whose parse errors take every job down.
