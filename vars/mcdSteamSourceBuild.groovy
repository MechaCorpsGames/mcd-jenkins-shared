// vars/mcdSteamSourceBuild.groovy
//
// Answers "which client build's artifacts should Steam get?" for
// mcdSteamUploadPipeline (bead mc-fr2h).
//
// THE PROBLEM THIS EXISTS TO KILL
// ------------------------------
// The client pipeline fires MCDSteam-Upload once per build. On 2026-08-23
// MCDSteam-Upload #593, #594, #595, #596 and #597 all ran from MCDClient-Main,
// all with STEAM_BRANCH=main, inside about two hours. Only the last had any
// effect: each upload sets the build live on the same beta, so the four before
// it were superseded the moment the next one finished. Meanwhile the
// controller had four executors and no agents, and the queue reached eleven
// items with nothing idle, so those four no-op uploads were competing for
// executors with the PR validation someone was actually waiting on.
//
// WHY NOT milestone() + lock()
// ----------------------------
// That is the usual Jenkins idiom for "newest wins", and it is the wrong tool
// HERE, because MCDSteam-Upload is ONE job serving four Steam branches.
// milestone() is scoped to the job and ordered by build number: per the
// milestone step documentation, "older builds will not proceed (they are
// aborted) if a newer build already passed the milestone". It has no notion of
// a parameter, so a main upload passing the milestone would abort a QUEUED
// backend upload that is not superseded by anything. That is the same defect
// as job-wide disableConcurrentBuilds(), just quieter, and it would silently
// drop uploads for the branch nobody was looking at.
//
// lock(resource: "steam-upload-${params.STEAM_BRANCH}") keys correctly, but it
// only serialises: three queued main uploads would still run one after another
// and all three would upload. It also needs the Lockable Resources plugin,
// which nothing in this library uses today and which this repo has no way to
// verify is installed. A pipeline that dies on "No such DSL method 'lock'"
// breaks every upload, so it is not a dependency to take blind.
//
// WHAT THIS DOES INSTEAD
// ---------------------
// Compare artifact freshness, which is the thing that actually decides whether
// an upload matters. An upload carrying MCDClient-Main #1200 is pointless if
// #1202 has already archived artifacts, because #1202's own pipeline fires its
// own upload to the same Steam branch (same job, therefore same
// config.steamBranch). So: if a NEWER build of the SAME source job has
// archived artifacts, this upload is superseded and should not run.
//
// That keys the decision on the source job and build number, which is strictly
// finer-grained than STEAM_BRANCH. An upload can never suppress one for a
// different branch, because a different branch is a different source job. It
// needs no plugin, no Jenkins queue access and no trusted-library privileges:
// it reads the same archived-build directory the pipeline already reads to
// resolve an empty SOURCE_BUILD.
//
// MANUAL RUNS ARE EXEMPT, deliberately. A person who opens MCDSteam-Upload and
// types a build number is asking for THAT build, usually to put a known-good
// artifact back on a beta after a bad one. Superseding that would break the
// one case where somebody is watching.

// The highest-numbered build of `sourceJob` that has archived artifacts, as a
// String, or null when the job has none.
//
// Sorts the extracted NUMBERS rather than the paths. The equivalent expression
// this replaces sorted on `-t/ -k8`, i.e. on a fixed path depth, which is
// correct only as long as the jobs directory sits exactly seven slashes deep.
// The permalink entries Jenkins keeps alongside the numbered directories
// (lastSuccessfulBuild and friends) carry no digits before /archive and drop
// out of the grep rather than sorting as zero.
String latest(String sourceJob) {
    if (!sourceJob) {
        return null
    }

    String jobDir = "/var/lib/jenkins/jobs/${sourceJob}/builds"
    String newest = sh(
        script: "ls -1d ${jobDir}/*/archive 2>/dev/null | grep -oP '\\d+(?=/archive)' | sort -n | tail -1",
        returnStdout: true
    ).trim()

    return newest ?: null
}

// The build number that makes this upload pointless, as a String, or null when
// this upload is carrying the newest artifacts there are.
//
// Returns null (never superseded) for a manually triggered build, and for a
// SOURCE_BUILD that is empty or non-numeric. An empty SOURCE_BUILD means
// "whatever is latest", which cannot be stale by definition.
String supersededBy(String sourceJob, String sourceBuild) {
    def userCauses = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
    if (userCauses && userCauses.size() > 0) {
        return null
    }

    String requested = sourceBuild?.trim()
    if (!requested || !requested.isNumber()) {
        return null
    }

    String newest = latest(sourceJob)
    if (!newest || !newest.isNumber()) {
        return null
    }

    return newest.toInteger() > requested.toInteger() ? newest : null
}
