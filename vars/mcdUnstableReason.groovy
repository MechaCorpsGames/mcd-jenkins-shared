// vars/mcdUnstableReason.groovy
//
// Records WHY a build went UNSTABLE, so the post{ unstable } handlers can name
// the actual cause instead of guessing (bead mjs-q4x).
//
// The bug this exists to kill: the unstable notification used to report
// env.BUILD_PHASE, which produced "Build finished UNSTABLE at: Initializing" —
// a phase that is not where anything went wrong. Two separate reasons for that:
//
//   1. BUILD_PHASE tracks WHERE the build got to, not WHAT went wrong. An
//      unstable build has, by definition, kept going, so the phase it happens
//      to be sitting in when post{} runs says nothing about the cause.
//   2. BUILD_PHASE is declared in the pipeline-level environment{} block, and
//      declarative re-applies those entries as a contextual override. The
//      `env.BUILD_PHASE = '...'` assignments inside stages therefore do NOT
//      survive into post{} — the environment{} literal ("Initializing") wins.
//
// >>> Consequence for anyone extending this: UNSTABLE_REASON must NEVER be
// >>> declared in a pipeline's environment{} block. It is written only through
// >>> this step, at the site that already knows the cause, and read only in
// >>> post{}. Give it an environment{} default and it silently freezes at that
// >>> default, reproducing the exact bug above. (env.SYMBOLS_UPLOADED is the
// >>> working counter-example: not in environment{}, readable from post{}.)
//
// Additive by design. More than one thing can go soft in a single build — a
// card-validation error AND a missing Sentry symbol upload, say — and the
// notification has to say both. The previous handler used an if/else that
// overwrote one cause with the other, so whichever it checked second was the
// only one anybody heard about. Reasons accumulate here instead, joined with
// "; ", de-duplicated so a retried stage cannot say the same thing twice.
//
// This step does NOT mark the build unstable. Marking is the caller's job
// (catchError(buildResult: 'UNSTABLE'), currentBuild.result = 'UNSTABLE'), and
// keeping the two separate means recording a reason can never change a build's
// result by accident.
//
// Usage, at the point that knows the cause:
//   mcdUnstableReason("card validation errors (validator exit ${status})")
//
// Phrase the reason as a noun clause that reads after "Build finished with":
// "card validation errors", not "Card validation failed!".

def call(String reason) {
    String trimmed = reason?.trim()
    if (!trimmed) {
        return
    }

    List<String> reasons = (env.UNSTABLE_REASON ?: '').tokenize(';')
        .collect { it.trim() }
        .findAll { it }

    if (reasons.contains(trimmed)) {
        return
    }

    reasons.add(trimmed)
    env.UNSTABLE_REASON = reasons.join('; ')
    echo "[unstable reason] ${trimmed}"
}
