// vars/mcdPrSupersession.groovy
//
// Answers "has a newer build of the SAME pull request already started?" for
// mcdPRValidationPipeline (bead mc-waxw).
//
// THE PROBLEM THIS EXISTS TO KILL
// ------------------------------
// mc-w2iu put disableConcurrentBuilds(abortPrevious: true) on this pipeline so
// that a second push to a PR would cancel the first push's build. The option
// works, but it is JOB-scoped, and MCD-PR-Main is ONE job serving EVERY open
// pull request. So a push to any PR aborted the in-flight build of an unrelated
// one. On 2026-08-25, in a single hour, builds #1757 (PR-2708), #1759
// (PR-2716), #1761 (PR-2714), #1762 (PR-2718) and #1766 (PR-2720) all ended
// NOT_BUILT, most of them killed by a push to somebody else's branch.
//
// Tim, 2026-08-25: "PR builds should only trim to latest when there are
// duplicate builds for a single PR, otherwise, all of them should run."
//
// Supersession is a property of the PULL REQUEST, not of the job, so it has to
// be keyed on pr_number. Nothing Jenkins offers at the options{} level can do
// that: disableConcurrentBuilds and milestone() are both scoped to the job and
// ordered by build number. mcdSteamSourceBuild.groovy already records the same
// finding for the same reason, one job serving four Steam branches: "It has no
// notion of a parameter, so a main upload passing the milestone would abort a
// QUEUED backend upload that is not superseded by anything."
//
// WHY nextBuild WORKS HERE NOW, HAVING BEEN REJECTED YESTERDAY
// -----------------------------------------------------------
// ADR 2026-08-25-superseded-cancel-goes-where-nothing-is-published rejected a
// self-skip because "it would test currentBuild.nextBuild, which is null for a
// build that has not started. Exactly when it is needed." That was true, and it
// was true BECAUSE OF the serialization it was arguing for: under
// disableConcurrentBuilds the newer build sits in the queue with no Run object
// at all, so there is nothing to see.
//
// Removing the job-wide serialization removes the objection with it. Concurrent
// PR builds start immediately, each with a Run and a build number, so the older
// build can see the newer one and stand down. Different PRs no longer touch each
// other, which was the whole complaint.
//
// This is a SELF-skip, not a kill: build #N notices #N+2 and stops itself. It
// cannot reach into another build, and it needs no privileged Jenkins API. That
// matters here, because nothing in this library has ever used one, and
// mcdSteamSourceBuild.groovy records the standing rule that an unverifiable
// dependency is not one to take blind: "A pipeline that dies on 'No such DSL
// method' breaks every upload."
//
// WHY CONCURRENCY IS SAFE ON THIS PIPELINE SPECIFICALLY
// ----------------------------------------------------
// It publishes nothing. /opt/mechacorps appears exactly once in the file, in the
// agent's docker mount arguments, and in no sh body; there is no rsync and no
// write under a shared path. Jenkins hands concurrent runs their own @2/@3
// workspaces (MCD-PR-Main@2 has been observed). And it is the status quo, not a
// new experiment: this pipeline had no concurrency control at all until
// 2026-08-24, and the failure it is being restored to fix appeared the same day
// the control did.
//
// The two pipelines that DO publish through rsync are a different matter and are
// handled by mcdRedundantBuild.groovy, which never interrupts anything.

// The build number of a later build of this same job carrying the same
// pr_number, as a String, or null when this build is still the newest one for
// its PR.
//
// Walks forward from this build rather than asking Jenkins for the job's build
// list, because RunWrapper.getNextBuild() is a plain safe API and enumerating a
// job's runs is not. The chain is bounded by buildDiscarder (20 builds).
//
// READ PR_NUMBER, NOT pr_number, AND THE DIFFERENCE IS THE WHOLE BUG (mc-k0z9l).
// -----------------------------------------------------------------------------
// This function used to read candidate.buildVariables['pr_number'] directly, and
// on that reading it NEVER FIRED ONCE. buildVariables surfaces a build's
// PARAMETERS plus the env a pipeline SET ITSELF; a GenericTrigger
// genericVariable is neither, so pr_number is readable in its own build and
// absent from every other build's view of it. `mine` was always set, `theirs`
// was always null, no candidate ever matched, and every older build ran to
// completion believing it was still current.
//
// Measured 2026-09-03, three independent overlaps in one night, older build
// running to SUCCESS every time: PR-3095 #2163 (37m7s, roughly thirty stage
// boundaries evaluated while #2164 and #2165 were live on the same PR), PR-3086
// #2156 against #2158, PR-3068 #2149 against #2150.
//
// mcdPRValidationPipeline's 'Setup PR Info' now republishes the trigger values
// as PR_NUMBER and PR_HEAD_SHA, which DOES land in buildVariables. The fallback
// to the raw trigger name is kept deliberately: it costs one `?:` and it means
// a build that started before this change, or any future caller wired without
// the republish, degrades to the old always-null behaviour rather than throwing.
// The PR number this build is validating. Prefers the value the pipeline
// republished into its own environment, because that is the one another build
// can see; falls back to the raw trigger variable, which is readable only from
// inside the build that owns it. See the note on supersededBy().
String identityOf(def build) {
    if (build == currentBuild) {
        return firstNonEmpty(env.PR_NUMBER, env.pr_number)
    }
    Map vars = build.buildVariables ?: [:]
    return firstNonEmpty(vars['PR_NUMBER'], vars['pr_number'])
}

// The first of the given values that is a non-blank String, or null.
String firstNonEmpty(Object... values) {
    for (v in values) {
        String s = v?.toString()?.trim()
        if (s) {
            return s
        }
    }
    return null
}

String supersededBy() {
    String mine = identityOf(currentBuild)
    if (!mine) {
        return null
    }

    def candidate = currentBuild.nextBuild
    while (candidate != null) {
        // Fail open. A candidate whose variables cannot be read is treated as
        // unrelated, so the worst case is a build that runs when it did not have
        // to. The opposite default would cancel validation somebody is waiting
        // on, which is strictly worse than burning an executor.
        String theirs = null
        String theirSha = null
        try {
            Map vars = candidate.buildVariables ?: [:]
            theirs = firstNonEmpty(vars['PR_NUMBER'], vars['pr_number'])
            theirSha = firstNonEmpty(vars['PR_HEAD_SHA'], vars['pr_head_sha'])
        } catch (Exception ignored) {
            theirs = null
        }

        if (theirs && theirs == mine) {
            env.SUPERSEDED_BY_SHA = theirSha ?: ''
            return candidate.number.toString()
        }

        candidate = candidate.nextBuild
    }

    return null
}

// True while this build's verdict still matters. False once the PR has been
// merged out from under it, or once a newer build of the same PR exists.
//
// This is the gate every stage sits behind, so it is called ~30 times per build
// and has to stay cheap and idempotent. Once a build is marked superseded the
// answer is sticky: the marking work happens once.
//
// It has a side effect on that first false, which is deliberate. A when{}
// expression is the only hook that runs at EVERY stage boundary, and a build
// that has already spent eighteen minutes needs to find out mid-flight, not at
// the top. The alternative, a supersession-check stage wedged between each pair
// of real stages, is the same side effect with thirty times the diff.
boolean stillCurrent() {
    if (env.PR_ALREADY_MERGED == 'true') {
        return false
    }
    if (env.PR_SUPERSEDED == 'true') {
        return false
    }

    String newer = supersededBy()
    if (!newer) {
        return true
    }

    env.PR_SUPERSEDED = 'true'
    env.SUPERSEDED_BY = newer
    currentBuild.displayName = "#${BUILD_NUMBER} PR-${env.pr_number} superseded by #${newer}"
    currentBuild.description =
        "Skipped: PR #${env.pr_number} build #${newer} is newer\n${env.pr_head_ref ?: ''} → ${env.TARGET_BRANCH ?: ''}"
    // NOT_BUILT, not FAILURE: being superseded is the system working. The
    // pipeline's post{} handlers key off this the same way mcdSteamUploadPipeline
    // keys off UPLOAD_SUPERSEDED, so a trimmed build notifies nobody.
    currentBuild.result = 'NOT_BUILT'
    echo "Superseded: PR #${env.pr_number} has a newer validation build (#${newer}). Skipping the rest of this one."

    return false
}
