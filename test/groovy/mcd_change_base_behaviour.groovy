// test/groovy/mcd_change_base_behaviour.groovy
//
// EXECUTES vars/mcdChangeBase.groovy. Does not read it, does not regex it: it
// compiles the file and calls resolve() with a stand-in `currentBuild` and a
// `sh` step wired to a REAL git repository built for each case.
//
// WHY THIS FILE EXISTS
// --------------------
// test/unit/test_mcd_change_base_is_the_last_evaluated_build.py pins the SHAPE
// of the source: that the pipelines call resolve(), that NOT_BUILT appears in
// the exclusion, that an ancestor check is present. Every one of those passes
// against Groovy that would not compile, and against a walk that selects the
// wrong build. This file is the other half. It answers "does the code do the
// thing", and the two questions are not the same question.
//
// WHAT THIS PROVES
//   * mcdChangeBase.groovy parses and compiles.
//   * resolve() picks the right anchor across the verdict matrix, the lookback
//     bound, unreadable builds, rewritten history, and the incident itself.
//   * usableBase() and the older-of-the-two ordering are exercised against real
//     `git merge-base --is-ancestor`, not against a stub that agrees with them.
//   * The widening invariant holds on every case run here: the base returned is
//     before_sha, or an ancestor of before_sha, or null.
//
// WHAT THIS DOES NOT PROVE, and no amount of work in this file will
//   * Jenkins runs pipeline code through the CPS transform and the script
//     security sandbox. Neither is present here. A construct that compiles under
//     plain Groovy can still be rejected in-sandbox or mistransformed by CPS.
//   * This runs on Groovy 4.x. Jenkins pipeline Groovy is 2.4-flavoured, which
//     is stricter. Passing here does not license 2.5+ syntax; the file under
//     test deliberately stays within the old dialect.
//   * The live reproduction bead mc-okhtp asks for (push A, push B docs-only,
//     abort A's build, confirm B detects the client change) still requires a
//     controller. That remains open.
//
// RUN:  groovy test/groovy/mcd_change_base_behaviour.groovy
// Exits 0 when every case passes, 1 otherwise, and prints one line per case.

import org.codehaus.groovy.control.CompilerConfiguration

// ---------------------------------------------------------------------------
// The pipeline steps mcdChangeBase.groovy actually calls.
// ---------------------------------------------------------------------------

abstract class PipelineStubScript extends Script {
    void echo(String message) {
        List<String> log = (List<String>) binding.getVariable('ECHOED')
        log << message
    }

    // Real execution, in the repo the case built. `sh` in a pipeline runs bash
    // in the workspace, so that is what this does. Returning a canned status
    // here would make the ancestor logic answer to the stub instead of to git,
    // which is the one thing the shape tests already cannot rule out.
    int sh(Map args) {
        File workspace = (File) binding.getVariable('WORKSPACE')
        Process p = ['bash', '-c', args.script as String].execute(null, workspace)
        p.waitForProcessOutput(new StringBuffer(), new StringBuffer())
        return p.exitValue()
    }
}

// ---------------------------------------------------------------------------
// A stand-in for a Jenkins RunWrapper: the three members the walk reads.
// ---------------------------------------------------------------------------

class FakeBuild {
    int number
    String result                 // null models a build still running
    Map buildVariables = [:]
    FakeBuild wired               // the real link; previousBuild is the observed accessor
    boolean explodeOnRead = false // models an unreadable build

    // Every touch of the history is recorded here. An assertion that history
    // was NOT consulted has to observe the absence of a read; trying to prove
    // it by making a build throw does not work, because lastEvaluatedCommit()
    // catches exactly that exception by design.
    List<String> reads = []

    def getResult() {
        reads << "#${number}.result"
        if (explodeOnRead) throw new RuntimeException("build #${number} is unreadable")
        return result
    }

    Map getBuildVariables() {
        reads << "#${number}.buildVariables"
        if (explodeOnRead) throw new RuntimeException("build #${number} is unreadable")
        return buildVariables
    }

    FakeBuild getPreviousBuild() {
        reads << "#${number}.previousBuild"
        return wired
    }
}

// Builds a chain newest-first from a list of [number, result, builtCommit] and
// returns the newest. A null builtCommit means the build recorded none.
FakeBuild chain(List<List> rows, List<String> reads) {
    FakeBuild newest = null
    FakeBuild previous = null
    rows.each { row ->
        FakeBuild b = new FakeBuild(number: row[0] as int, result: row[1] as String, reads: reads)
        if (row.size() > 2 && row[2] != null) {
            b.buildVariables = ['BUILT_COMMIT': row[2] as String]
        }
        if (row.size() > 3 && row[3]) {
            b.explodeOnRead = true
        }
        if (newest == null) { newest = b } else { previous.wired = b }
        previous = b
    }
    return newest
}

// The build the pipeline is running on. Reads of ITS previousBuild are recorded
// too, so "did resolve() consult history at all" is an observable question.
FakeBuild newCurrent(int number, FakeBuild history, List<String> reads) {
    return new FakeBuild(number: number, wired: history, reads: reads)
}

// ---------------------------------------------------------------------------
// A real git repository. Commits are made in order and their SHAs handed back
// by the label the case gave them.
// ---------------------------------------------------------------------------

class Repo {
    File dir
    Map<String, String> sha = [:]

    static String run(File cwd, String command) {
        Process p = ['bash', '-c', command].execute(null, cwd)
        StringBuffer out = new StringBuffer()
        StringBuffer err = new StringBuffer()
        p.waitForProcessOutput(out, err)
        if (p.exitValue() != 0) {
            throw new RuntimeException("git failed: ${command}\n${err}")
        }
        return out.toString().trim()
    }

    // Each entry is [label, filename]. The file is created and committed, and
    // the resulting SHA recorded under the label.
    static Repo of(List<List<String>> commits) {
        Repo r = new Repo()
        r.dir = File.createTempDir('mcd-change-base-', '')
        run(r.dir, 'git init -q -b main .')
        run(r.dir, 'git config user.email t@example.invalid && git config user.name T')
        // A bare origin so the `git fetch origin <sha>` inside usableBase() has
        // a remote to talk to. Everything is local already, so the fetch is a
        // no-op that succeeds, exactly as it does on a Jenkins agent whose
        // workspace already has the commit.
        run(r.dir, 'git config --add remote.origin.url .')
        commits.each { entry ->
            String label = entry[0]
            String file = entry[1]
            run(r.dir, "mkdir -p \$(dirname ${file}) && echo ${label} > ${file}")
            run(r.dir, "git add -A && git commit -q -m ${label}")
            r.sha[label] = run(r.dir, 'git rev-parse HEAD')
        }
        return r
    }

    // Files git reports between a base and HEAD, i.e. what Detect Changes sees.
    List<String> changedFrom(String base) {
        return run(dir, "git diff --name-only ${base} HEAD").readLines().findAll { it }
    }

    boolean isAncestor(String a, String b) {
        Process p = ['bash', '-c', "git merge-base --is-ancestor ${a} ${b}"].execute(null, dir)
        p.waitForProcessOutput(new StringBuffer(), new StringBuffer())
        return p.exitValue() == 0
    }
}

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

File helper = new File('vars/mcdChangeBase.groovy')
if (!helper.exists()) {
    System.err.println("run me from the repository root: ${helper} not found")
    System.exit(2)
}

CompilerConfiguration cc = new CompilerConfiguration()
cc.scriptBaseClass = PipelineStubScript.name

int passed = 0
List<String> failures = []
List<String> invariantChecked = []

// Compiles a fresh instance of the file under test for each case, so no case
// can leak state into the next one.
def load = { FakeBuild current, File workspace, List<String> echoed ->
    Binding binding = new Binding()
    binding.setVariable('currentBuild', current)
    binding.setVariable('WORKSPACE', workspace)
    binding.setVariable('ECHOED', echoed)
    GroovyShell shell = new GroovyShell(this.class.classLoader, binding, cc)
    return shell.parse(helper)
}

List<String> reads = []

def check = { String name, Closure body ->
    reads.clear()
    try {
        body()
        passed++
        println "  ok   ${name}"
    } catch (Throwable t) {
        failures << "${name}: ${t.message}"
        println "  FAIL ${name}"
        println "       ${t.message}"
    }
}

// Runs resolve() and asserts the widening invariant on the way out, on every
// single case, whatever else that case is checking.
def resolveWithInvariant = { Repo repo, FakeBuild current, String beforeSha, String caseName ->
    List<String> echoed = []
    def script = load(current, repo.dir, echoed)
    String got = script.resolve(beforeSha)

    if (got != null && beforeSha && !beforeSha.startsWith('0000000')) {
        boolean widerOrSame = (got == beforeSha) || repo.isAncestor(got, beforeSha)
        if (!widerOrSame) {
            throw new AssertionError(
                "WIDENING INVARIANT VIOLATED in ${caseName}: resolve() returned ${got}, " +
                "which is NOT an ancestor of before_sha ${beforeSha}. A narrower base " +
                "means commits go unbuilt, which is the bug this file exists to prevent." as Object)
        }
    }
    invariantChecked << caseName
    return [base: got, echoed: echoed]
}

println "mcdChangeBase.groovy, executed (Groovy ${GroovySystem.version})"
println ""

// --- 0. It compiles at all. -------------------------------------------------
check('the file compiles and exposes resolve() and lastEvaluatedCommit()') {
    def script = load(new FakeBuild(number: 1), File.createTempDir(), [])
    assert script.class.methods*.name.contains('resolve')
    assert script.class.methods*.name.contains('lastEvaluatedCommit')
    assert script.class.methods*.name.contains('usableBase')
}

// --- 1. THE INCIDENT, end to end. -------------------------------------------
// MCDServer-Main, 2026-09-02:
//   #1149 SUCCESS at 56ae238   <- the last build that reached a verdict
//   #1150 ABORTED, hard-killed while queued, recorded no BUILT_COMMIT
//   #1151 NOT_BUILT at d4f210a, having diffed from ad2e857 and seen one docs file
// The next build's before_sha is ad2e857. Today that hides the client change.
check('the incident: resolve widens past the ABORTED and NOT_BUILT builds to the last SUCCESS') {
    Repo repo = Repo.of([
        ['base',     'README.md'],
        ['s56ae238', 'Src/GameServer/GameFlow.cpp'],   // #1149 built this tip
        ['ad2e857',  'custom.tscn'],                   // PR #3045: the client change
        ['d4f210a',  'docs/plans/overhaul.md'],        // the docs-only push
    ])
    FakeBuild history = chain([
        [1151, 'NOT_BUILT', repo.sha['d4f210a']],
        [1150, 'ABORTED',   null],
        [1149, 'SUCCESS',   repo.sha['s56ae238']],
    ], reads)
    FakeBuild current = newCurrent(1152, history, reads)

    def r = resolveWithInvariant(repo, current, repo.sha['ad2e857'], 'the incident')
    assert r.base == repo.sha['s56ae238'] : "expected the #1149 anchor, got ${r.base}"

    // The point of the whole change, stated as file sets rather than as SHAs.
    assert !repo.changedFrom(repo.sha['ad2e857']).contains('custom.tscn') :
        'premise broken: the old base was supposed to HIDE the client change'
    assert repo.changedFrom(r.base).contains('custom.tscn') :
        'the widened base still does not surface the client change'
}

// --- 2. The verdict matrix, executed. ---------------------------------------
[
    ['SUCCESS',  true],
    ['UNSTABLE', true],
    ['FAILURE',  true],
    ['ABORTED',  false],
    ['NOT_BUILT', false],
    [null,       false],   // still running
].each { row ->
    String verdict = row[0]
    boolean counts = row[1]
    check("a ${verdict ?: 'still-running'} build ${counts ? 'counts as' : 'does NOT count as'} an evaluated tree") {
        Repo repo = Repo.of([['old', 'a.txt'], ['mid', 'b.txt'], ['tip', 'c.txt']])
        FakeBuild history = chain([[9, verdict, repo.sha['old']]], reads)
        FakeBuild current = newCurrent(10, history, reads)

        def r = resolveWithInvariant(repo, current, repo.sha['mid'], "verdict ${verdict}")
        if (counts) {
            assert r.base == repo.sha['old'] :
                "${verdict} should have anchored the base to its own commit, got ${r.base}"
        } else {
            assert r.base == repo.sha['mid'] :
                "${verdict} must not anchor anything; expected the before SHA, got ${r.base}"
        }
    }
}

// --- 3. NOT_BUILT specifically cannot launder the hole forward. -------------
// This is the case that makes the difference between a transient hole and a
// permanent one, so it gets its own scenario rather than riding on the matrix.
check('a run of NOT_BUILT builds keeps widening rather than settling on the bad base') {
    Repo repo = Repo.of([
        ['anchor',  'a.txt'],      // the last SUCCESS
        ['unbuilt', 'client.gd'],  // the change that must not be lost
        ['docs1',   'docs/1.md'],
        ['docs2',   'docs/2.md'],
        ['docs3',   'docs/3.md'],
    ])
    FakeBuild history = chain([
        [5, 'NOT_BUILT', repo.sha['docs2']],
        [4, 'NOT_BUILT', repo.sha['docs1']],
        [3, 'ABORTED',   null],
        [2, 'SUCCESS',   repo.sha['anchor']],
    ], reads)
    FakeBuild current = newCurrent(6, history, reads)

    def r = resolveWithInvariant(repo, current, repo.sha['docs2'], 'NOT_BUILT run')
    assert r.base == repo.sha['anchor'] :
        "three unevaluated builds deep, the anchor must still be the last SUCCESS, got ${r.base}"
    assert repo.changedFrom(r.base).contains('client.gd') :
        'the change stranded behind the NOT_BUILT run is still invisible'
}

// --- 4. Manual runs, decided before history is consulted. -------------------
['', null, '0000000000000000000000000000000000000000'].each { manual ->
    check("a hand-started build (before_sha = ${manual == null ? 'null' : (manual ?: 'empty')}) builds everything without reading history") {
        Repo repo = Repo.of([['only', 'a.txt']])
        FakeBuild older = chain([[1, 'SUCCESS', repo.sha['only']]], reads)
        FakeBuild current = newCurrent(2, older, reads)

        def r = resolveWithInvariant(repo, current, manual as String, 'manual run')
        assert r.base == null : "a manual run must resolve to null (build everything), got ${r.base}"

        // The ordering, stated as an observation rather than as a hope. Making
        // the history throw cannot prove this: lastEvaluatedCommit() catches
        // exactly that exception on purpose, so the walk would swallow the probe
        // and the case would pass against code that walks first, guards second.
        assert reads.isEmpty() :
            "a manual run consulted build history before deciding: ${reads}"
    }
}

// --- 5. The lookback is bounded, and running out is safe. -------------------
check('an evaluated build past the lookback is not found, and the fallback is the before SHA') {
    Repo repo = Repo.of([['ancient', 'a.txt'], ['recent', 'b.txt'], ['tip', 'c.txt']])
    List<List> rows = []
    (1..25).each { i -> rows << [100 - i, 'ABORTED', null] }   // 25 > lookback of 20
    rows << [70, 'SUCCESS', repo.sha['ancient']]
    FakeBuild current = newCurrent(200, chain(rows, reads), reads)

    def r = resolveWithInvariant(repo, current, repo.sha['recent'], 'lookback bound')
    assert r.base == repo.sha['recent'] :
        "running out of lookback must degrade to today's behaviour, got ${r.base}"
}

check('an evaluated build just inside the lookback IS found') {
    Repo repo = Repo.of([['ancient', 'a.txt'], ['recent', 'b.txt'], ['tip', 'c.txt']])
    List<List> rows = []
    (1..19).each { i -> rows << [100 - i, 'ABORTED', null] }
    rows << [70, 'SUCCESS', repo.sha['ancient']]               // the 20th build back
    FakeBuild current = newCurrent(200, chain(rows, reads), reads)

    def r = resolveWithInvariant(repo, current, repo.sha['recent'], 'lookback inclusive')
    assert r.base == repo.sha['ancient'] :
        "the 20th build back is inside the lookback and should anchor, got ${r.base}"
}

// --- 6. Unreadable builds fail open. ----------------------------------------
check('an unreadable build is walked past rather than trusted') {
    Repo repo = Repo.of([['anchor', 'a.txt'], ['lost', 'client.gd'], ['tip', 'docs/x.md']])
    FakeBuild history = chain([
        [9, 'SUCCESS', repo.sha['lost'], true],   // reads throw
        [8, 'SUCCESS', repo.sha['anchor']],
    ], reads)
    FakeBuild current = newCurrent(10, history, reads)

    def r = resolveWithInvariant(repo, current, repo.sha['lost'], 'unreadable build')
    assert r.base == repo.sha['anchor'] :
        "an unreadable build must not anchor; the walk should continue, got ${r.base}"
}

check('a history walk that throws outright falls back to the before SHA, never to a narrower one') {
    Repo repo = Repo.of([['old', 'a.txt'], ['mid', 'b.txt'], ['tip', 'c.txt']])
    // previousBuild itself explodes: not a build that is unreadable, the walk.
    FakeBuild current = newCurrent(10, null, reads)
    current.metaClass.getPreviousBuild = { -> throw new RuntimeException('history unavailable') }

    def r = resolveWithInvariant(repo, current, repo.sha['mid'], 'walk throws')
    assert r.base == repo.sha['mid'] :
        "a broken walk must fall back to the before SHA, got ${r.base}"
}

// --- 7. Real ancestry, real git. --------------------------------------------
check('a base that is not an ancestor of HEAD is discarded, not diffed against') {
    Repo repo = Repo.of([['old', 'a.txt'], ['mid', 'b.txt'], ['tip', 'c.txt']])
    // A commit on an unrelated root. `git diff` against it SUCCEEDS and prints a
    // confident, wrong file list, which is the whole reason usableBase() exists.
    Repo.run(repo.dir, 'git checkout -q --orphan stray && git rm -q -rf . && echo x > stray.txt && git add -A && git commit -q -m stray')
    String strandedSha = Repo.run(repo.dir, 'git rev-parse HEAD')
    Repo.run(repo.dir, 'git checkout -q main')

    FakeBuild history = chain([[9, 'SUCCESS', strandedSha]], reads)
    FakeBuild current = newCurrent(10, history, reads)

    def r = resolveWithInvariant(repo, current, repo.sha['mid'], 'non-ancestor base')
    assert r.base == repo.sha['mid'] :
        "a non-ancestor base must be discarded in favour of the before SHA, got ${r.base}"
    assert r.echoed.any { it.contains('not an ancestor of HEAD') } :
        'discarding a base silently is how this bug class hides; it must say so'
}

check('when neither candidate is usable, resolve returns null so everything builds') {
    Repo repo = Repo.of([['only', 'a.txt']])
    String bogusEvaluated = 'dead' + ('beef' * 9)   // 40 hex chars, no such object
    String bogusBefore    = 'face' + ('feed' * 9)
    FakeBuild history = chain([[9, 'SUCCESS', bogusEvaluated]], reads)
    FakeBuild current = newCurrent(10, history, reads)

    List<String> echoed = []
    def script = load(current, repo.dir, echoed)
    String got = script.resolve(bogusBefore)
    assert got == null : "two unusable candidates must mean build everything, got ${got}"
}

// --- 8. Already-wide before_sha is left alone. ------------------------------
check('when the push already reaches further back than the last build, the push wins') {
    Repo repo = Repo.of([['old', 'a.txt'], ['newer', 'b.txt'], ['tip', 'c.txt']])
    // The last evaluated build is NEWER than before_sha: builds are keeping up
    // and a re-push reopened an older range. Widening is not needed.
    FakeBuild history = chain([[9, 'SUCCESS', repo.sha['newer']]], reads)
    FakeBuild current = newCurrent(10, history, reads)

    def r = resolveWithInvariant(repo, current, repo.sha['old'], 'push already wider')
    assert r.base == repo.sha['old'] :
        "the older of the two is the safe base; expected the push's, got ${r.base}"
}

check('the steady state, builds keeping up with pushes, changes nothing') {
    Repo repo = Repo.of([['prev', 'a.txt'], ['tip', 'b.txt']])
    FakeBuild history = chain([[9, 'SUCCESS', repo.sha['prev']]], reads)
    FakeBuild current = newCurrent(10, history, reads)

    def r = resolveWithInvariant(repo, current, repo.sha['prev'], 'steady state')
    assert r.base == repo.sha['prev'] : "steady state must be a no-op, got ${r.base}"
    assert repo.changedFrom(r.base) == ['b.txt']
}

check('a first build of a job, with no history at all, falls back to the before SHA') {
    Repo repo = Repo.of([['first', 'a.txt'], ['tip', 'b.txt']])
    FakeBuild current = newCurrent(1, null, reads)

    def r = resolveWithInvariant(repo, current, repo.sha['first'], 'no history')
    assert r.base == repo.sha['first'] : "a first build has nothing to widen to, got ${r.base}"
}

// ---------------------------------------------------------------------------

println ""
println "widening invariant asserted on ${invariantChecked.size()} resolve() calls"
println "${passed} passed, ${failures.size()} failed"
if (failures) {
    println ""
    failures.each { println "FAILED: ${it}" }
    System.exit(1)
}
System.exit(0)
