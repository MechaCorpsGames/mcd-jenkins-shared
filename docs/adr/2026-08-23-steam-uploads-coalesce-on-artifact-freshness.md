# Steam uploads coalesce on artifact freshness, not on a Jenkins lock

Date: 2026-08-23

Status: Accepted

## Context

Every client pipeline that arms `config.steamBranch` fires `MCDSteam-Upload`
once per build. On 2026-08-23 that produced `MCDSteam-Upload` #593, #594, #595,
#596 and #597, all from `MCDClient-Main`, all with `STEAM_BRANCH=main`, inside
about two hours. Each upload sets its build live on the same beta, so only the
last one had any effect. The four before it uploaded a build the next one
immediately superseded.

The cost is not just wasted work. The controller has four executors and no
agents (bead mc-sm6s), and the queue reached eleven items with nothing idle
while those uploads ran. Redundant uploads compete for executors with PR
validation, which is the work somebody is actually waiting on.

Tim's requirement (2026-08-23): "We only need a single upload job per branch,
and they must run as late as possible."

The obvious Jenkins idiom for the first half is `milestone()` plus
`lock(resource: "steam-upload-${params.STEAM_BRANCH}")`, and bead mc-fr2h
suggested exactly that. It does not survive contact with this job.

**`milestone()` cannot see a parameter.** `MCDSteam-Upload` is ONE job serving
four Steam branches (main, backend, card, staging). The milestone step is scoped
to the job and ordered by build number: per its documentation, "older builds
will not proceed (they are aborted) if a newer build already passed the
milestone." It has no notion of `STEAM_BRANCH`, so a `main` upload passing the
milestone would abort a queued `backend` upload that nothing has superseded.
That is the same defect as job-wide `disableConcurrentBuilds()`, and quieter:
the branch nobody was watching would just stop shipping.

**`lock()` keys correctly but only serialises.** Three queued `main` uploads
holding a per-branch lock still run one after another, and all three still
upload. It also needs the Lockable Resources plugin, which nothing in this
library uses today. Whether it is installed cannot be verified from this repo,
and a pipeline that dies on `No such DSL method 'lock'` breaks every upload.

## Decision

**Coalesce on artifact freshness.** An upload carrying `MCDClient-Main` #1200 is
pointless once #1202 has archived artifacts, because #1202's own pipeline fires
its own upload to the same Steam branch (same job, therefore same
`config.steamBranch`). `mcdSteamUploadPipeline` checks for exactly that and
skips when it finds it, in a first stage before any work and again immediately
before `steamcmd`.

This keys the decision on the source job and build number, which is strictly
finer-grained than `STEAM_BRANCH`: an upload can never suppress one for a
different branch, because a different branch is a different source job. It needs
no plugin, no Jenkins queue access and no trusted-library privileges. It reads
the same archived-build directory the pipeline already read to resolve an empty
`SOURCE_BUILD`, so the mechanism is one this job was already known to be able to
perform.

**Manual runs are exempt.** A person who opens `MCDSteam-Upload` and types a
build number is asking for that build, usually to put a known-good artifact back
on a beta after a bad one. `mcdSteamSourceBuild.supersededBy` returns null for
any build with a `UserIdCause` before it looks at artifacts at all.

**Superseded is `NOT_BUILT`, and silent.** Being superseded is the coalescing
working, not an incident. Declarative runs `post { success }` only on SUCCESS
and `post { failure }` only on FAILURE, so `NOT_BUILT` reaches neither, and both
handlers additionally return early on `env.UPLOAD_SUPERSEDED` so a future `post`
condition cannot page somebody for a normal outcome.

**`Publish to Steam` moves to the end of the client pipeline.** It sat ahead of
`Upload Debug Symbols`, handing the controller a second job to run while the
client build still had minutes of its own work left. `wait: false` and
`propagate: false` are unchanged: the client build must not block on the upload,
and a Steam hiccup must not red an otherwise good build.

## Consequences

Three uploads queued in quick succession now end with one that ran, carrying the
newest source build. The first two cost a few seconds each and appear grey in
the UI with a display name naming the build that superseded them.

**One race is narrowed, not closed.** An upload that passes the pre-`steamcmd`
check microseconds before a newer client build archives will still run. The
result is one redundant upload, and because the newer one sets itself live
afterwards, the beta still ends on the newest build. Closing it entirely needs
mutual exclusion between concurrent uploads, which is bead mc-v721. That bead
also carries the pre-existing hazard it exposes: every `MCDSteam-Upload` build
mounts the same `/var/lib/jenkins/.steam` content cache, so two overlapping
`steamcmd` runs share one. Neither is introduced here, and neither is made
worse.

**A client build that fails before its last stage now publishes nothing**, where
before it could publish and then fail. An UNSTABLE build still publishes, since
declarative only skips later stages on FAILURE and `Upload Debug Symbols`
reports a bad upload by marking the build UNSTABLE rather than failing it.

**A source job with no numbered builds left never coalesces.** If the build
discarder has pruned every archive, `mcdSteamSourceBuild.latest` returns null
and `supersededBy` returns null, so the upload proceeds. Failing open is
deliberate: the failure mode of failing closed is an upload that never happens.

`mcd-jenkins-shared` has no CI, so nothing about this change is gated by a
check and the evidence is the local suite: twelve tests in
`test/unit/test_steam_upload_coalescing.py`, each proven to fail against a
deliberately broken version of the property it pins.
The Groovy itself is unverified against a live Jenkins, which is true of every
change to this repo.
