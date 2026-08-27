# A deploy pointer moves forwards only, and is keyed on the deploy path

Date: 2026-08-27

Status: Accepted. Bead mc-ehn1.

## Context

On 2026-08-27, `dev.mechacorpsgames.com` served two-commit-stale code for roughly
eight minutes and a *successful* deploy reported `FAILURE`. Both came from the
same cause, and neither was the cause the bead was originally filed for.

Five `MCDServer-Main` builds started inside 60 seconds (#1006-#1010,
03:46-03:47Z), one per merge to `main`. Two were correctly `NOT_BUILT`. The other
three ran the full pipeline **concurrently**, in workspaces `MCDServer-Main`,
`MCDServer-Main@2` and `MCDServer-Main@3`.

Each build published its version pointer by letting `latest.txt` ride inside the
deploy `rsync`, so the pointer belonged to whichever `rsync` finished last:

    #1008  built 066852d43 (NEWEST)  ->  wrote latest.txt 21:06:20.209 PDT
    #1006  built ef5b60f06 (OLDEST)  ->  wrote latest.txt 21:06:20.757 PDT

#1006 won by 548 milliseconds while two commits behind. The proxy reads
`versions/latest.txt` to choose the binary it spawns, so production-for-dev
silently regressed. `testclient-versions/latest.txt` went the same way, so the
bots were stale too. A human repointed both by hand at 04:14Z.

The same overlap produced the red build. `#1006`'s deploy does
`docker rm -f mcd-main-proxy-1` then `docker compose up --force-recreate`; a
concurrent build removed the container between compose resolving it and compose
recreating it, and compose died on `No such container: 0007b334f1c9...`. The
container that invocation created was in fact serving and reporting
`{"status":"ok","rooms":0}`. So the deploy worked, the build went red, and
`Cleanup Old Versions` was skipped as collateral.

### The premise that was wrong

The bead was filed as a **cross-job** hazard: six jobs sharing
`/var/opt/mechacorpsgames` with no lock. Its design note recorded that
`disableConcurrentBuilds()` (mc-2upj, #87) had already closed the within-job
case, leaving only cross-job contention.

That was false for this pipeline. Read on `origin/main` 7ce7d52,
`mcdServerPipeline.groovy`'s `options {}` contained **only**
`buildDiscarder(logRotator(...))`. mc-2upj put the guard on
`mcdServicesPipeline`, not here.

Worth recording *why* the gap survived review: the only occurrence of the string
`disableConcurrentBuilds` anywhere in `mcdServerPipeline.groovy` was a **comment**,
in the `Trim to Latest` stage, which read "A burst of pushes queues one build per
push behind this job's `disableConcurrentBuilds()`". A reader checking whether
the guard was present found the word and stopped. The comment described the
mechanism's precondition as though the file satisfied it.

That comment also concealed a second effect, though **an earlier version of this
ADR overstated it and the overstatement was merged. It is corrected here.**

What that version said: that `mcdRedundantBuild` "could never match" and was
"structurally unable to fire in precisely the bursts it exists for". **That is
false as stated**, and the build records disprove it. Read off `build.xml`
`displayName` on the Jenkins master by the mayor (this session has no Jenkins
access and did not verify it independently):

```
MCDServer-Main   #1009 trimmed (built by #1007)   <- the 2026-08-27 burst
MCDServer-Main   #1010 trimmed (built by #1007)   <- the 2026-08-27 burst
MCDServer-Main   #999  trimmed (built by #998)
```

Two of the five builds in the very burst this ADR is about **were** trimmed, by
the mechanism claimed to be incapable of firing. The console tell is in those
logs too.

The true statement is narrower, and it is the one the mechanism's own header
predicts: *"A queued build cannot see the builds behind it, but by the time it
starts it can see what the builds AHEAD of it did."*

> **The trim reaches the queued tail. It cannot reach the concurrent head.**

`#1009` and `#1010` sat queued behind an executor; by the time they *started*,
`#1007` had finished `SUCCESS` on the same tip, so both of `builtBy()`'s
conditions were satisfiable and they trimmed. `#1006`, `#1007` and `#1008` ran
genuinely in parallel, each checking out the tip at the moment *it* ran, which as
`main` moved meant three different commits (`ef5b60f06` / `d2d5130e8` /
`066852d43`). For those three neither condition could hold.

So what parallelism defeats is not the mechanism, it is the mechanism's **reach**:
three builds escaped ahead of it. That is still a reason to serialize, and a
sharper one than the overstatement was. A control that only reaches the queued
tail leaves the parallel head free to race on `latest.txt`, which is precisely
what happened at 21:06:20.209 and 21:06:20.757.

**A corollary worth knowing before reading this job's build list:** a trimmed
build presents as every stage "skipped due to when conditional". Do not read
those skips as "no relevant paths changed" on this job; `#1009` and `#1010` were
first characterised that way and it was wrong.

## Decision

**1. `disableConcurrentBuilds()` on `mcdServerPipeline` and `mcdPromotePipeline`.**
Bare, never `abortPrevious`: aborting has no stage scoping and would interrupt the
publishing `rsync`, which is pinned by
`test_mcd_superseded_build_cancellation.py::test_publishing_pipelines_never_abort_mid_rsync`.

This is more than a lock. Queueing moves the job into the same-tip regime
`mcdRedundantBuild` was written for, which *activates* the trim and collapses a
burst to one real build of the newest tip. The race is removed by deleting the
second writer, not by arbitrating between two.

**2. The pointer is published separately from the payload, and only forwards.**
`latest.txt` is now `--exclude`d from both version `rsync`s. Everything else in
those trees is purely additive (each build writes its own `v<x>.<y>.<build>/`
subtree), so the payload needs no lock; the single shared mutable byte is the
pointer, and it is published in its own guarded step.

Serializing that write is necessary but **not sufficient**: under a plain mutex
#1006 still takes its turn second and still overwrites #1008, just politely. So
the write refuses to move backwards, keyed on `BUILD_NUMBER`.

`BUILD_NUMBER` rather than the version string, because Jenkins build numbers
within a job increase monotonically and are never reused. "Refuse a lower number"
therefore rejects exactly the slow-older-build case and can never reject a
legitimately newer one. A manual re-run always carries a higher number, so the
operator case `mcdRedundantBuild` deliberately exempts is not blocked here
either. Version strings could not do this: they are per-branch and would compare
across release lines. `-lt` and not `-le`, so a Replay of the same build can
still republish, which is how an operator repairs a bad pointer without editing
files by hand. Absent or corrupt state fails **open** and publishes, the same
direction as `mcdRedundantBuild`.

### The guard orders by BUILD, not by COMMIT

**This is a real limit and it is deliberate. Read "monotonic" as "the pointer
never moves to a lower build number", not as "the pointer never moves to older
code".** They are the same thing for the race this ADR exists to fix and they
come apart in one case.

The case: a **manual rebuild of an older commit**. Jenkins hands it a higher
build number than everything before it, so the guard sees a forward move and
allows it, and the older code becomes current. The paragraph above notes the
benign half of that (a manual re-run is never *blocked*); this is the other half
of the same fact.

It is out of scope on purpose, for three reasons:

1. It is not the observed defect. The 2026-08-27 incident was #1006 overwriting
   #1008 with a *lower* number, which build ordering rejects exactly.
2. Ancestry (`git merge-base --is-ancestor`) drags git into the deploy step, and
   on a force-pushed branch it asks a question with no reliable answer. The
   deploy step currently needs no repository at all.
3. An operator who deliberately rebuilds an old commit and deploys it is usually
   asking for precisely that. Refusing it would break the rollback path.

So the guard protects against a **race**, not against an operator. If a future
change needs the stronger property, it needs commit ancestry and it needs an
answer for the force-push case; do not reach for it by tightening `-lt`, which
cannot express it.

A refusal keeps the build **green** and changes what Discord says. Being
superseded is the mechanism working, not a failure; but the payload having
deployed while `latest.txt` points at a newer build is not "Deployed Server
v<this build>", and announcing that would put a version in the channel that is
not the one serving.

**3. The lock is keyed on the deploy path, and lives in one place.**
`vars/mcdDeployLock.groovy` derives the lock file from the deploy path. Keying on
the path rather than the environment name is what makes the one genuinely
overlapping pair exclude each other: `MCDServer-Release-Staging` **writes**
`/opt/mechacorps/release-staging` while `MCDServer-Release-Promote` **reads** it
to build production. They are different jobs with different names for one
directory. The four server jobs have disjoint deploy paths, disjoint compose
projects and distinct TCP ports, so they still deploy in parallel.

It is a helper rather than a copied idiom for one reason: two call sites that
inline `flock` against slightly different filenames do not exclude each other,
every build still passes, and the race continues with nothing anywhere going red.
The lock path is the one thing that must not drift, so it is defined once.

`flock` and not `lock(resource:)` because the Lockable Resources plugin is not
installed on this controller (established by #64's DSL error, recorded in
`mcdClientPipeline.groovy`). A file under `/opt/mechacorps` is genuinely
cross-agent: it is bind-mounted into every containerised agent, and
`mcdPromotePipeline` runs on `agent any`, on the host.

**4. Promote re-verifies what the operator approved.**
`Confirm Promote` blocks on a human, potentially for hours, between reading the
staging pointer and rsyncing it. The lock deliberately does **not** span that
gate, because a cross-job mutex held across an input gate would wedge every
staging deploy behind an operator who wandered off. Instead the sync step
re-reads the staging pointers inside the lock and fails loudly if they moved.
Without it, a staging deploy landing during the gate would be shipped to
**production** while Discord announced the approved version, green throughout.

## Consequences

The `#1006` sequence now ends green: `disableConcurrentBuilds()` makes the
concurrent container removal impossible within the job, and the lock covers the
cross-job case it cannot reach.

**What this does not fix, stated plainly.** The bead's title is the shared
`/var/opt/mechacorpsgames` **source tree**, and that is deliberately left alone
here. A lock works only if every party takes it, and that tree has three:
`mcdServicesPipeline`'s `Sync Src Tree`, `mcdServerPipeline`'s proxy env-file
write, and `mcdPromotePipeline`'s `docker compose build --no-cache proxy`. Worse,
`mcdServicesPipeline`'s exposure spans `Sync Src Tree` *through* its later deploy
stages, and a per-`sh` `flock` cannot span stages when there is no `lock()` step
to hold across them. A gap-riddled lock on that tree would be worse than none,
because it would read as protection. Tracked as a follow-up.

**Also not established.** Nobody has yet observed a `MCDServer-Release-Promote`
and `MCDServices-Main` overlap in a real log. The bead's own design note flags
the same gap. The 2026-08-27 evidence is same-job contention; the cross-job risk
above is read off the pipeline sources and the job configs, not off a failure.

**Testing note.** Nothing compiles Groovy on the build box (`groovy`, `groovyc`
and `java` are all absent), so `test/unit` is this repo's only gate and it
text-matches. The pointer logic is therefore covered *behaviourally*:
`test_deploy_pointer_is_serialized_and_monotonic.py` renders the shell the way
Groovy would and runs it, asserting that #1006 loses to #1008; and it proves the
generated `flock` actually serializes two concurrent holders, with a
positive-control test that the same harness detects an unlocked run. Those are
claims about the script's logic and about `flock`, not about the pipeline as
Jenkins assembles it.
