// vars/mcdDeployLock.groovy
//
// A cross-JOB mutex around one deploy path (bead mc-ehn1).
//
// WHY THIS EXISTS AS A HELPER RATHER THAN AN INLINE flock
// ------------------------------------------------------
// A lock only excludes the parties that take the SAME file. Two call sites that
// inline `flock` and name their lock files slightly differently do not exclude
// each other at all, and nothing anywhere goes red: both builds pass, both
// believe they are serialized, and the race they were meant to stop carries on
// exactly as before. That failure mode is invisible in review and invisible in
// CI, so the lock path is defined ONCE, here, and every caller derives it the
// same way.
//
// WHY flock AND NOT lock(resource:)
// ---------------------------------
// The Lockable Resources plugin is not installed on this controller. Confirmed
// in writing by mcd-jenkins-shared #64, whose DSL error enumerated the valid
// steps with `lock` absent; the finding is recorded in
// test_mcd_superseded_build_cancellation.py::test_client_pipeline_keeps_its_serialization.
// So a file lock is the only cross-job primitive available here.
//
// WHY A FILE LOCK IS GENUINELY CROSS-AGENT
// ----------------------------------------
// /opt/mechacorps is bind-mounted into every containerised build agent (see each
// pipeline's own `agent { docker { args ... } }`; mcdServerPipeline's args carry
// `-v /opt/mechacorps:/opt/mechacorps`), and mcdPromotePipeline runs on `agent
// any`, straight on the host. Both therefore reach the same inode. This is the
// same reasoning, and the same lock directory, that mcdAppServicesPipeline's
// 'Docker Smoke' stage already relies on.
//
// KEYED ON THE DEPLOY PATH, NOT ON THE ENVIRONMENT NAME
// ----------------------------------------------------
// The four mcdServerPipeline jobs have disjoint deploy paths, so keying on the
// path lets unrelated environments deploy in parallel while still excluding the
// one pair that genuinely overlaps: MCDServer-Release-Staging WRITES
// /opt/mechacorps/release-staging and MCDServer-Release-Promote READS that same
// path to build the production release. An environment-name key would not have
// matched those two, because they are different jobs with different names for
// the same directory.
//
// The filename is derived in SHELL with tr rather than in Groovy, which keeps
// regex out of the script-security sandbox and makes the mapping obvious to a
// reader: /opt/mechacorps/release-staging -> deploy-opt-mechacorps-release-staging.lock
//
// USAGE
//   mcdDeployLock(deployPath: config.deployPath, '''
//       ...POSIX sh, already running under `set -e`...
//   ''')
//
//   def verdict = mcdDeployLock(deployPath: p, returnStdout: true, '''
//       echo diagnostics >&2
//       echo onetoken
//   ''')
//
// The body runs under `/bin/sh -xe`, so keep it POSIX: `case` and `[ ]` are
// fine, `[[ ]]` and arrays are not. Do NOT give the body a shebang; that would
// drop Jenkins' implicit -e and is separately banned by
// test_sh_bodies_gate_their_failures.py. When capturing, write diagnostics to
// stderr and exactly one token to stdout, because the -x trace also goes to
// stderr and would otherwise be captured with your value.

def call(Map args = [:], String body) {
    String deployPath = args.deployPath
    if (!deployPath) {
        error('mcdDeployLock: deployPath is required. Without it the lock key is ' +
              'ambiguous and two callers could take different locks while believing ' +
              'they are mutually excluded (mc-ehn1).')
    }
    boolean capture = args.returnStdout ?: false

    // `set -e` appears twice on purpose: once for the outer script, and once
    // INSIDE the subshell, because a subshell does not inherit errexit through
    // the `( )` in every sh implementation. Without the inner one a failing
    // command in the body would be skipped past and the lock released as though
    // the work had succeeded.
    String script = """
        set -e
        mkdir -p /opt/mechacorps/.locks
        MCD_LOCK="/opt/mechacorps/.locks/deploy\$(printf '%s' '${deployPath}' | tr '/' '-').lock"
        (
            flock 9
            set -e
${body}
        ) 9>"\$MCD_LOCK"
    """

    if (capture) {
        return sh(script: script, returnStdout: true).trim()
    }
    return sh(script: script)
}
