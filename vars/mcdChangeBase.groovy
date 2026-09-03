// vars/mcdChangeBase.groovy
//
// Answers "which commit should 'Detect Changes' diff HEAD against?" for the
// branch pipelines (bead mc-okhtp).
//
// THE HOLE THIS EXISTS TO CLOSE
// ----------------------------
// Every branch pipeline used to take its base straight from the webhook:
//
//     def baseRef = env.before_sha        // $.before on the push payload
//
// That is the tip of the PREVIOUS PUSH. It says nothing about whether any build
// ever looked at the range it opens. So the moment a build does not finish, the
// commits it was carrying are attributed to a build that never evaluated them,
// and the next push closes over them.
//
// MEASURED, 2026-09-02, MCDClient-Main and MCDServer-Main:
//
//   1. Push ad2e8573a (merge of PR #3045: custom.tscn, opponent_hand_fan.gd
//      and .tscn, real client changes). MCDClient-Main #1385 starts.
//   2. Push d4f210a96. Relative to ad2e8573a it adds ONLY
//      docs/plans/mc-5dcgd-deck-builder-overhaul.md. #1386 queues.
//   3. The trim rule aborts the earlier build: #1385 ABORTED, having built
//      nothing.
//   4. #1386 runs. before_sha = ad2e8573a, so the diff is one docs file:
//      'Change detection: server=false, client=false', every stage skipped,
//      result NOT_BUILT. Same on MCDServer-Main #1151.
//
// Net effect: the client changes in ad2e8573a were never built or tested by any
// Main pipeline, and main's tip was green BY ABSENCE. Nothing failed. There was
// no red build to notice, because there was no build.
//
// Any abort does this: the trim rule, a Jenkins restart, a human pressing the
// X. It is the same family as mc-k0z92 (Trim to Latest compares build numbers,
// not ancestry) but a different check, on a different variable.
//
// WHAT THIS DOES INSTEAD
// ----------------------
// Take the base from the last build that ACTUALLY EVALUATED A TREE, not from
// the last push. A build evaluated a tree if it reached a verdict on one:
// SUCCESS, UNSTABLE or FAILURE. Those three ran the stages and reported. An
// ABORTED build did not, and neither did a NOT_BUILT one. See below, that
// exclusion is the load-bearing half.
//
// Each build already records the commit it checked out in BUILT_COMMIT (see
// mcdRedundantBuild.trim(), which writes it whether or not that build is
// trimmed). This reads that same variable off earlier builds. No new state, no
// file, no lock, no plugin.
//
// WHY NOT_BUILT IS EXCLUDED, WHICH IS THE PART THAT IS EASY TO GET WRONG
// ---------------------------------------------------------------------
// A NOT_BUILT build looks like it evaluated a tree: it ran Detect Changes, it
// diffed, it concluded honestly that nothing it owns changed. Tempting to trust,
// and trusting it would keep the diffs narrow.
//
// It cannot be trusted, because that is exactly what the broken build in the
// incident above was. #1386 went NOT_BUILT off a base that had skipped over real
// client changes. If #1387 then anchored to #1386's commit, the hole would be
// laundered forward and ad2e8573a would never be built by any future build
// either. The bug would become permanent instead of transient.
//
// So NOT_BUILT is excluded, and the cost of excluding it is bounded: a run of
// NOT_BUILT builds simply pushes the anchor further back, which WIDENS the diff.
// Widening only ever causes work to be done that did not have to be. That is the
// safe direction, and the direction this whole file is biased in.
//
// THE SAFETY PROPERTY WORTH KEEPING WHEN EDITING THIS
// --------------------------------------------------
// THIS CAN ONLY EVER WIDEN THE DIFF, NEVER NARROW IT.
//
// Every branch below either returns a commit at least as old as env.before_sha,
// or returns null, which routes the caller down its existing "no valid before
// SHA, build everything" path. There is no input for which this reports fewer
// changed files than the code it replaces. Keep it that way: a narrowing bug
// here is invisible, because its symptom is a build that does not happen.
//
// Concretely, the failure modes all land on the safe side:
//   * No earlier build recorded a commit (fresh job, or this change had not
//     merged yet when the previous builds ran). Returns before_sha, i.e.
//     exactly today's behaviour. It starts working on its own, like the trim
//     did.
//   * The lookback runs out before finding an evaluated build. Returns null,
//     so everything builds. Self-healing: that build reaches a verdict and
//     becomes the fresh anchor for everything after it.
//   * A commit will not fetch, or is not an ancestor of HEAD (force push,
//     rewritten history). It is discarded as a candidate rather than diffed
//     against, because `git diff` between unrelated commits SUCCEEDS and
//     silently reports the wrong file set.
//   * Anything at all throws. Returns null or before_sha, never a narrower base.
//
// MANUAL RUNS BUILD EVERYTHING, AND STILL DO
// ------------------------------------------
// A build somebody started by hand has no webhook payload, so before_sha is
// unset and the caller's existing branch builds everything. That is the manual
// escape hatch, and it predates this file. resolve() returns null immediately in
// that case rather than consulting build history, so a hand-started build is
// never narrowed to a history-derived base. Do not "improve" this by giving
// manual runs a computed base: pressing Build is how a person asks for the full
// thing, usually because they already suspect the incremental state is wrong.

// The commit checked out by the most recent earlier build of this job that
// reached a verdict on a tree, or null when there is no such build within the
// lookback.
//
// SUCCESS, UNSTABLE and FAILURE all count: each of them ran the stages and
// reported on the tree, so the commits up to and including that one have been
// looked at. ABORTED and NOT_BUILT do not count, and null (still running) does
// not either.
String lastEvaluatedCommit() {
    // How far back to walk. Deliberately a local, not a script-level
    // `static final`: vars/*.groovy is a Groovy SCRIPT, and a field declaration
    // there is not the thing it looks like (the same note is on
    // mcdRedundantBuild.builtBy).
    //
    // 20 is past the point of mattering on the job where this bit. MCDClient-*
    // keeps 10 builds (buildDiscarder numToKeepStr: '10'), so the walk hits the
    // end of retention first and returns null, which builds everything. On
    // MCDServer-* (60 kept) this bounds the walk instead. Either way, running
    // out is safe.
    int lookback = 20
    def candidate = currentBuild.previousBuild
    int looked = 0

    while (candidate != null && looked < lookback) {
        looked++

        // Fail open on anything unreadable: an unknown build is treated as
        // never having evaluated anything, so the walk continues further back
        // and the diff gets wider. Worst case is work that did not have to run.
        String theirResult = null
        String theirs = null
        try {
            theirResult = candidate.result?.toString()
            Map vars = candidate.buildVariables ?: [:]
            theirs = vars['BUILT_COMMIT']?.toString()?.trim()
        } catch (Exception ignored) {
            theirResult = null
            theirs = null
        }

        boolean evaluated = (theirResult == 'SUCCESS' ||
                             theirResult == 'UNSTABLE' ||
                             theirResult == 'FAILURE')

        if (evaluated && theirs) {
            echo "Change base: build #${candidate.number} (${theirResult}) evaluated ${theirs.take(7)}."
            return theirs
        }

        if (theirs) {
            echo "Change base: skipping build #${candidate.number} (${theirResult ?: 'running'}) at ${theirs.take(7)}, it reached no verdict on that tree."
        }

        candidate = candidate.previousBuild
    }

    return null
}

// True when `sha` exists locally (fetching it if it does not) and is an ancestor
// of HEAD, so that `git diff sha HEAD` describes a real range.
//
// The ancestor test is not decoration. `git diff` between two commits on
// unrelated histories exits 0 and prints a file list, so a dangling or
// rewritten base produces a confident, wrong answer rather than an error the
// caller's __DIFF_FAILED__ path would catch.
boolean usableBase(String sha) {
    if (!sha || sha.startsWith('0000000')) {
        return false
    }
    sh(script: "git fetch origin ${sha} 2>/dev/null || true", returnStatus: true)
    int rc = sh(
        script: "git merge-base --is-ancestor ${sha} HEAD 2>/dev/null",
        returnStatus: true
    )
    if (rc != 0) {
        echo "Change base: ${sha.take(7)} is not an ancestor of HEAD (unfetchable, or history was rewritten), discarding it as a base."
    }
    return rc == 0
}

// The commit 'Detect Changes' should diff HEAD against, or null to mean "build
// everything", which is what the callers' existing `if (!baseRef)` branch
// already does with it.
//
// @param beforeSha  env.before_sha, the webhook push payload's $.before.
String resolve(String beforeSha) {
    // Manual run: no webhook payload. Build everything, and do NOT consult
    // build history to manufacture a base. See the header.
    if (!beforeSha || beforeSha.startsWith('0000000')) {
        echo "Change base: no before SHA (hand-started build?), building everything."
        return null
    }

    String evaluated = null
    try {
        evaluated = lastEvaluatedCommit()
    } catch (Exception e) {
        // Fail open. A broken history walk must never be able to narrow a diff.
        echo "Change base: could not read build history (${e.getMessage()}), falling back to the push's before SHA."
        return beforeSha
    }

    if (!evaluated || evaluated == beforeSha) {
        // Nothing to widen to, or the two already agree, which is the steady
        // state when builds keep up with pushes.
        return beforeSha
    }

    boolean beforeOk = usableBase(beforeSha)
    boolean evaluatedOk = usableBase(evaluated)

    if (!beforeOk && !evaluatedOk) {
        echo "Change base: neither ${beforeSha.take(7)} nor ${evaluated.take(7)} is a usable base, building everything."
        return null
    }
    if (!evaluatedOk) {
        return beforeSha
    }
    if (!beforeOk) {
        echo "Change base: using ${evaluated.take(7)}, the push's before SHA is not usable."
        return evaluated
    }

    // Both are ancestors of HEAD, so they are ordered with respect to each
    // other. Take the OLDER one, which is the widest diff of the two and
    // therefore the one that cannot miss a commit.
    int rc = sh(
        script: "git merge-base --is-ancestor ${beforeSha} ${evaluated} 2>/dev/null",
        returnStatus: true
    )
    if (rc == 0) {
        // before_sha is at or behind the last evaluated build. Today's answer is
        // already the wider one, so there is nothing to fix on this build.
        return beforeSha
    }

    echo "Change base: widening from ${beforeSha.take(7)} (previous push) to ${evaluated.take(7)} (last build that reached a verdict). Commits in between were carried by builds that never evaluated them."
    return evaluated
}
