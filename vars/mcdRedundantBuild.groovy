// vars/mcdRedundantBuild.groovy
//
// Answers "has an earlier build of this job already built this exact commit,
// successfully?" for the branch pipelines (bead mc-waxw).
//
// Tim, 2026-08-25: "for all builds other than PR builds we should be trimming to
// latest."
//
// THE PROBLEM THIS EXISTS TO KILL
// ------------------------------
// Every branch pipeline carries disableConcurrentBuilds(), which SERIALIZES and
// never trims: a burst of pushes queues a build per push, each waits its turn,
// and each then does the full 20 minutes of work. mcdServerPipeline has no
// concurrency control at all, so its burst runs the same work in parallel
// instead. Either way nobody asked for the middle builds.
//
// WHY NOT abortPrevious, WHICH WOULD BE ONE LINE
// ----------------------------------------------
// Because it aborts the older build WHEREVER IT IS, and Jenkins offers no stage
// scoping. Both of the big pipelines publish through rsync: mcdServerPipeline's
// 'Deploy GameServer & TestClient' into config.deployPath, where mc-ehn1 records
// six jobs sharing the path with no cross-job lock and mc-mhjd requires the
// protocol manifest and the binary to arrive together; and mcdClientPipeline's
// 'Publish Bot Runtime', an rsync -a --delete into a shared per-env path whose
// own options block records that this rsync already blew up once on .core.XYZ
// temp files. An abort landing mid-rsync leaves exactly the half-written state
// those controls exist to prevent. That exclusion is a decision, recorded in
// ADR 2026-08-25-superseded-cancel-goes-where-nothing-is-published and pinned by
// test_mcd_superseded_build_cancellation.py.
//
// WHY NOT THE FORWARD-LOOKING CHECK THE PR PIPELINE USES
// -----------------------------------------------------
// mcdPrSupersession walks currentBuild.nextBuild to find a newer build. That
// works there because that pipeline no longer serializes, so the newer build is
// RUNNING and has a Run object. Here the newer builds are QUEUED behind
// disableConcurrentBuilds, and a queued build has no build number and no Run at
// all, so there is nothing to look forward at. This is the objection the ADR
// raised, and on these pipelines it still stands.
//
// WHAT THIS DOES INSTEAD: LOOK BACKWARDS, NOT FORWARDS
// ---------------------------------------------------
// A queued build cannot see the builds behind it, but by the time it starts it
// can see what the builds AHEAD of it did. And a Jenkins branch build checks out
// the branch TIP, not the commit its webhook carried, so three builds queued
// behind one another all check out the same commit and do identical work.
//
// So: record the commit each build actually checked out, and skip when an
// earlier build of this job already built that same commit and SUCCEEDED. A
// burst of five pushes becomes one real build of the newest commit and four
// visible no-ops, which is what "trimming to latest" means here.
//
// It never interrupts anything. Nothing is aborted, no running build is touched,
// and the skip happens before any publish step, so the rsync exclusion above is
// respected rather than worked around.
//
// THE SAFETY PROPERTY WORTH KEEPING WHEN EDITING THIS
// --------------------------------------------------
// It only ever skips work that has ALREADY BEEN DONE, successfully, by this same
// job, on this same commit. That makes both failure directions safe:
//
//   * If it under-fires (buildVariables comes back empty, the previous build
//     predates this change, anything at all goes wrong) nothing is skipped and
//     the pipeline behaves exactly as it does today. That is also why the first
//     builds after this merges will not trim: no earlier build recorded a
//     commit. It starts working on its own.
//   * If it fires, the artifacts for that commit were already built and
//     published by the build it points at.
//
// A "newest wins" scheme that skipped on the presence of a NEWER build would not
// have that property: if the newest build's webhook were ever lost, every build
// before it would stand down for a build that never comes and nothing would
// deploy at all. That is why this keys on completed work rather than on
// pending work.
//
// MANUAL RUNS ARE EXEMPT, deliberately, the same way mcdSteamSourceBuild exempts
// them. Somebody who opens the job and presses Build is usually asking to
// redeploy the current tip on purpose, often because the deployed state drifted
// from what the last build left. Skipping that would break the one case where a
// person is watching.

// The build number of an earlier build of this job that already built
// `commitSha` and succeeded, as a String, or null when this build has real work
// to do.
String builtBy(String commitSha) {
    if (!commitSha) {
        return null
    }

    def userCauses = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
    if (userCauses && userCauses.size() > 0) {
        return null
    }

    // Bounded because a burst is what this is for, and the buildDiscarder on
    // these jobs keeps 10 to 60 builds anyway. Deliberately a local, not a
    // script-level `static final`: vars/*.groovy is a Groovy SCRIPT, and a
    // field declaration there is not the thing it looks like.
    int lookback = 12
    def candidate = currentBuild.previousBuild
    int looked = 0
    while (candidate != null && looked < lookback) {
        looked++

        // Fail open on anything unreadable: an unknown build is treated as
        // having built something else, so the worst case is a build that runs
        // when it did not have to.
        String theirs = null
        String theirResult = null
        try {
            theirResult = candidate.result?.toString()
            Map vars = candidate.buildVariables ?: [:]
            theirs = vars['BUILT_COMMIT']?.toString()?.trim()
        } catch (Exception ignored) {
            theirs = null
        }

        // SUCCESS only. A build still running might yet fail, and a failed or
        // NOT_BUILT build did not necessarily publish anything, so neither is
        // evidence that the work is done.
        if (theirs && theirs == commitSha && theirResult == 'SUCCESS') {
            return candidate.number.toString()
        }

        candidate = candidate.previousBuild
    }

    return null
}

// Record the commit this build checked out, and stand the build down if an
// earlier one already built it.
//
// Returns true when the build was trimmed, and the CALLER clears its own gate
// flags. That is deliberate on both counts.
//
// Clearing the flags routes a trimmed build down the pipeline's OWN no-op path,
// the one it already takes several times a day when a push touches nothing it
// owns. That path is proven, and every stage that can publish sits behind it: in
// each of these five pipelines the only stages with no gate at all are Setup,
// Checkout and Detect Changes. Reusing it is much less likely to go wrong than
// gating forty stages on a new flag.
//
// And the caller names its own flags in its own file, with the plain
// `env.NAME = 'false'` idiom, rather than passing a list of names here to be
// assigned dynamically. env is EnvActionImpl; `env.NAME = x` is the idiom the
// whole library uses and the one the sandbox is known to allow. Subscript
// assignment is not, and this is not the repo to find that out in: a shared var
// that dies at runtime takes every job with it (#82).
boolean trim() {
    String head = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
    // Written whether or not this build is trimmed, and read by LATER builds
    // through RunWrapper.buildVariables. This is the whole state this mechanism
    // keeps: no file, no lock, no plugin.
    env.BUILT_COMMIT = head

    String earlier = builtBy(head)
    if (!earlier) {
        echo "Building ${head.take(7)} (no earlier build of this job has built it)."
        return false
    }

    env.BUILD_REDUNDANT = 'true'
    env.REDUNDANT_WITH = earlier

    // Visibly skipped, never silently green. A trimmed build that looked like a
    // normal pass would be worse than the duplicate work it replaced: the next
    // person to ask "did that commit deploy?" would get a yes from a build that
    // did nothing.
    currentBuild.displayName = "#${BUILD_NUMBER} trimmed (built by #${earlier})"
    currentBuild.description =
        "${currentBuild.description ?: ''}\n⏭️ Trimmed: #${earlier} already built ${head.take(7)}"
    currentBuild.result = 'NOT_BUILT'
    echo "Trimmed: build #${earlier} already built ${head.take(7)} for this job and succeeded. Skipping the work."

    return true
}
