// test/groovy/mcd_drainer_handoff_behaviour.groovy
//
// EXECUTES the shell that vars/mcdDrainerHandoff.groovy produces (mc-r15kh 3a).
// It compiles the helper, calls it, writes the snippet to a script and RUNS it
// under `sh -e` with a fake `docker` on PATH, then asserts what happened to the
// containers: what was renamed, what was signalled, what was stopped.
//
// WHY THIS FILE EXISTS
//   test/unit/test_mcd_drainer_handoff.py pins the SHAPE of the source. Those
//   assertions pass against Groovy that does not compile and against shell whose
//   rename and signal are in the wrong order. This answers "does the handoff do
//   the thing", and the two are not the same question.
//
// WHAT THIS PROVES
//   * The outgoing proxy is RENAMED, de-policied and signalled, in that order.
//     The order is the point: signalling before the rename opens a window in
//     which a drain runs on a container the port sweep still calls a squatter.
//   * SIGUSR1 is the signal. SIGTERM here would be the hard stop, which ends the
//     matches this exists to protect.
//   * A young drainer from an earlier deploy is LEFT ALONE. This is the positive
//     control: without it every assertion below passes for a handoff that stops
//     every drainer it sees, which would kick exactly the matches this protects.
//   * A stale one is stopped with `docker stop`, never `docker rm -f`.
//   * An empty host is a no-op rather than an error.
//
// WHAT THIS DOES NOT PROVE
//   There is no Docker here: `docker` is a fake answering from a fixture, so this
//   pins the handoff's logic and its use of the CLI surface, not Docker's
//   behaviour. Two claims it rests on were measured separately on the deploy host
//   and are NOT re-checked here: that `docker rm -f` is a SIGKILL with no grace,
//   and that `unless-stopped` restarts a container which exits cleanly while one
//   the daemon stopped stays stopped. Jenkins' CPS transform and script-security
//   sandbox are absent too.
//
// RUN:  groovy test/groovy/mcd_drainer_handoff_behaviour.groovy

import org.codehaus.groovy.control.CompilerConfiguration

int passed = 0
List<String> failures = []

abstract class PipelineStubScript extends Script {
    void error(String message) { throw new IllegalArgumentException(message) }
}

def loadHelper = {
    CompilerConfiguration cc = new CompilerConfiguration()
    cc.scriptBaseClass = PipelineStubScript.class.name
    GroovyShell shell = new GroovyShell(this.class.classLoader, new Binding(), cc)
    return shell.parse(new File('vars/mcdDrainerHandoff.groovy'))
}

class Host {
    File dir
    List<Map> containers = []

    static Host of(List<Map> containers) {
        Host h = new Host()
        h.dir = File.createTempDir('mcd-handoff', '')
        h.containers = containers
        StringBuilder fixture = new StringBuilder()
        containers.each { c -> fixture << "${c.id}\t${c.name}\n" }
        new File(h.dir, 'containers.tsv').text = fixture.toString()
        ['renamed.txt', 'signalled.txt', 'stopped.txt', 'removed.txt', 'updated.txt', 'ops.txt'].each {
            new File(h.dir, it).text = ''
        }
        File bin = new File(h.dir, 'bin'); bin.mkdirs()
        // The fake lives in its own file rather than inside a Groovy string:
        // a '''...''' literal processes backslash escapes, and the fake's regexes
        // are full of them. Embedding it made the harness fail to COMPILE, which
        // is a confusing way to learn about your own quoting.
        File docker = new File(bin, 'docker')
        docker.text = new File('test/groovy/fake_docker_handoff.py').text
        docker.setExecutable(true)
        return h
    }

    Map run(String snippet) {
        File script = new File(dir, 'handoff.sh')
        script.text = "set -e\n" + snippet + "\n"
        ProcessBuilder pb = new ProcessBuilder('sh', '-e', script.absolutePath)
        pb.directory(dir)
        pb.environment().put('PATH', new File(dir, 'bin').absolutePath + ':' + System.getenv('PATH'))
        pb.environment().put('MCD_FAKE_DOCKER_ROOT', dir.absolutePath)
        pb.redirectErrorStream(true)
        Process p = pb.start()
        StringBuffer out = new StringBuffer()
        p.consumeProcessOutput(out, out)
        p.waitFor()
        def read = { String f -> new File(dir, f).readLines().findAll { it } }
        return [out: out.toString(), rc: p.exitValue(),
                renamed: read('renamed.txt'), signalled: read('signalled.txt'),
                stopped: read('stopped.txt'), removed: read('removed.txt'),
                updated: read('updated.txt'), ops: read('ops.txt')]
    }
}

def check = { String name, Closure body ->
    try { body(); passed++; println "  ok   ${name}" }
    catch (Throwable t) { failures << "${name}: ${t.message}"; println "  FAIL ${name}: ${t.message}" }
}

def script = loadHelper()
KEEP = 'mcd-main-proxy-1'
BUILD = '4217'
String snippet = script.call(containerName: KEEP, buildNumber: BUILD)

long now = System.currentTimeMillis() / 1000L

// --- 1. The whole point. ----------------------------------------------------
check('the outgoing proxy is renamed, de-policied and SIGUSR1d, in that order') {
    Host h = Host.of([[id: 'c1', name: KEEP]])
    Map r = h.run(snippet)
    assert r.rc == 0 : "handoff exited ${r.rc}:\n${r.out}"
    assert r.renamed.size() == 1 && r.renamed[0].startsWith("${KEEP} -> ${KEEP}-drainer-${BUILD}-") :
        "the outgoing proxy was not renamed to a drainer: ${r.renamed}"
    assert r.updated.any { it.contains('--restart=no') } :
        "the restart policy was not cleared, so a SIGKILLed drainer would come back " +
        "from the OLD image and serve the previous build: ${r.updated}"
    assert r.signalled == ["USR1 ${KEEP}-drainer-${BUILD}-" + r.renamed[0].split('-').last()] ||
           r.signalled.size() == 1 && r.signalled[0].startsWith('USR1 ') :
        "the drainer was not sent SIGUSR1: ${r.signalled}"
    assert r.removed == [] : "the handoff destroyed a container: ${r.removed}"
    assert r.out.contains('Handed the outgoing proxy over') : "no confirmation printed:\n${r.out}"

    // THE ORDER IS THE POINT, so assert it rather than trusting that three
    // separate things each happened. Signalling before the rename opens a window
    // in which a drain is running on a container the port sweep still considers a
    // squatter and would docker rm -f. Clearing the restart policy after the
    // signal leaves the same gap for a SIGKILL to resurrect it.
    List<String> verbs = r.ops.collect { it.split(' ')[0] }
    assert verbs == ['renamed', 'updated', 'signalled'] :
        "expected rename, then restart=no, then SIGUSR1. Got ${verbs}"
}

check('the signal is SIGUSR1, never SIGTERM') {
    Host h = Host.of([[id: 'c1', name: KEEP]])
    Map r = h.run(snippet)
    assert r.signalled.every { it.startsWith('USR1 ') } :
        "SIGTERM here is the HARD STOP: it ends the matches this exists to protect. Got ${r.signalled}"
}

// --- 2. The positive control. Without it, "stop everything" passes. ---------
check('a YOUNG drainer from an earlier deploy is left alone') {
    Host h = Host.of([
        [id: 'c1', name: KEEP],
        [id: 'c2', name: "${KEEP}-drainer-4216-${now - 60}"],
    ])
    Map r = h.run(snippet)
    assert r.stopped == [] :
        "stopped a drainer that is 60s into a 30-minute bleed-out, which kicks exactly " +
        "the matches this change protects: ${r.stopped}"
    assert r.out.contains('may still be draining')
}

check('a STALE drainer is stopped, and stopped rather than force-removed') {
    Host h = Host.of([
        [id: 'c1', name: KEEP],
        [id: 'c2', name: "${KEEP}-drainer-4216-${now - 5000}"],
    ])
    Map r = h.run(snippet)
    assert r.stopped == ["${KEEP}-drainer-4216-${now - 5000}"] :
        "a drainer well past its deadline was not collected: ${r.stopped}"
    assert !r.removed.any { it.startsWith('FORCED') } :
        "used docker rm -f on a drainer. That is a SIGKILL with no grace, and a stopped " +
        "container stays stopped whereas a forced one was never given the chance: ${r.removed}"
}

check('a drainer with no timestamp in its name is left alone, not guessed at') {
    Host h = Host.of([
        [id: 'c1', name: KEEP],
        [id: 'c2', name: "${KEEP}-drainer-legacy"],
    ])
    Map r = h.run(snippet)
    assert r.stopped == [] : "stopped a drainer whose age is unknowable: ${r.stopped}"
    assert r.out.contains('no timestamp in its name')
}

// --- 3. Nothing to do. ------------------------------------------------------
check('an empty host is a no-op and succeeds') {
    Host h = Host.of([])
    Map r = h.run(snippet)
    assert r.rc == 0 : "handoff exited ${r.rc} on an empty host:\n${r.out}"
    assert r.renamed == [] && r.signalled == [] && r.stopped == []
    assert r.out.contains('No outgoing proxy container to hand over')
}

check('a host with only a stale drainer and no proxy still collects it') {
    Host h = Host.of([[id: 'c2', name: "${KEEP}-drainer-4216-${now - 5000}"]])
    Map r = h.run(snippet)
    assert r.stopped.size() == 1 : "a stale drainer must be collected even when the deploy " +
        "has no outgoing proxy of its own: ${r.stopped}"
    assert r.renamed == []
}

// --- 4. The guards. ---------------------------------------------------------
check('a missing containerName is refused') {
    try {
        script.call(buildNumber: BUILD)
        assert false : 'accepted a missing containerName'
    } catch (IllegalArgumentException e) { assert e.message.contains('containerName is required') }
}

check('a missing buildNumber is refused') {
    try {
        script.call(containerName: KEEP)
        assert false : 'accepted a missing buildNumber'
    } catch (IllegalArgumentException e) { assert e.message.contains('buildNumber is required') }
}

println ""
println "${passed} passed, ${failures.size()} failed"
if (failures) { println ""; failures.each { println "FAILED: ${it}" }; System.exit(1) }
System.exit(0)
