// test/groovy/mcd_port_sweep_behaviour.groovy
//
// EXECUTES the shell that vars/mcdPortSweep.groovy produces. It does not read
// the Groovy and it does not regex the shell: it compiles the helper, calls it,
// writes the snippet it returns to a script, and RUNS that script under
// `/bin/sh -e` with a fake `docker` on PATH standing in for a host full of
// containers. The assertions are about which containers were actually removed.
//
// WHY THIS FILE EXISTS
// --------------------
// test/unit/test_mcd_port_sweep_exempts_drainers.py pins the SHAPE of the
// source: that a helper exists, that both call sites use it, that the label
// name appears. Every one of those assertions passes against Groovy that does
// not compile and against shell whose `if` is inverted so it removes ONLY
// drainers. This file answers "does the sweep do the thing", and the two
// questions are not the same question.
//
// WHAT THIS PROVES
//   * mcdPortSweep.groovy parses, compiles, and returns shell.
//   * That shell runs under a POSIX-ish sh without error.
//   * A container labelled mcd.role=drainer is left alone, even though it
//     matches every other condition the sweep selects on.
//   * An UNLABELLED squatter on the port is still removed. Without this the
//     suite would pass for a "fix" that simply disabled the sweep, which is the
//     obvious wrong way to stop it killing drainers.
//   * The foreign-compose-project case that mcd-jenkins-shared 637a43a exists
//     for still works: a differently named, unlabelled container whose port is
//     visible only on its command line is removed.
//   * The deploy's own container is never touched by the sweep.
//
// WHAT THIS DOES NOT PROVE, and no amount of work in this file will
//   * There is no Docker here. `docker` is a fake that answers from a fixture,
//     so this pins the sweep's LOGIC and its use of the docker CLI surface, not
//     Docker's real behaviour. In particular the claim that `docker rm -f` is a
//     SIGKILL with no grace period is documented behaviour that this file does
//     not and cannot verify.
//   * Jenkins runs pipeline code through the CPS transform and the script
//     security sandbox. Neither is present here.
//   * This runs on Groovy 4.x; Jenkins pipeline Groovy is 2.4-flavoured and
//     stricter. Passing here does not license 2.5+ syntax.
//   * Nothing yet creates a container labelled mcd.role=drainer. That is
//     orchestration step 3b in mc-r15kh. This file proves the sweep is SAFE for
//     drainers, not that drainers exist.
//
// RUN:  groovy test/groovy/mcd_port_sweep_behaviour.groovy
// Exits 0 when every case passes, 1 otherwise, and prints one line per case.

import org.codehaus.groovy.control.CompilerConfiguration

int passed = 0
List<String> failures = []
List<String> drainerInvariant = []

// ---------------------------------------------------------------------------
// The one pipeline step mcdPortSweep.groovy can call: error().
// ---------------------------------------------------------------------------

abstract class PipelineStubScript extends Script {
    void error(String message) {
        throw new IllegalArgumentException(message)
    }
}

def loadHelper = {
    CompilerConfiguration cc = new CompilerConfiguration()
    cc.scriptBaseClass = PipelineStubScript.class.name
    GroovyShell shell = new GroovyShell(this.class.classLoader, new Binding(), cc)
    return shell.parse(new File('vars/mcdPortSweep.groovy'))
}

// ---------------------------------------------------------------------------
// A host: a list of running host-network containers, and a fake `docker` that
// answers the four inspect/ps/rm forms the sweep uses.
// ---------------------------------------------------------------------------

class Host {
    File dir
    List<Map> containers = []      // [id: 'c1', name: 'x', role: null, cmd: '...']

    static Host of(List<Map> containers) {
        Host h = new Host()
        h.dir = File.createTempDir('mcd-port-sweep', '')
        h.containers = containers

        StringBuilder fixture = new StringBuilder()
        containers.each { c ->
            fixture << "${c.id}\t${c.name}\t${c.role ?: ''}\t${c.cmd}\n"
        }
        new File(h.dir, 'containers.tsv').text = fixture.toString()
        new File(h.dir, 'removed.txt').text = ''

        File bin = new File(h.dir, 'bin')
        bin.mkdirs()
        File docker = new File(bin, 'docker')
        // Deliberately a real executable rather than a shell function, because
        // the sweep invokes `docker` in command substitutions and in pipelines;
        // a function exported into the environment would not survive all of it.
        docker.text = '''#!/usr/bin/env python3
import os, sys
root = os.environ['MCD_FAKE_DOCKER_ROOT']
rows = []
for line in open(os.path.join(root, 'containers.tsv')):
    line = line.rstrip('\\n')
    if not line:
        continue
    cid, name, role, cmd = line.split('\\t')
    rows.append({'id': cid, 'name': name, 'role': role, 'cmd': cmd})
a = sys.argv[1:]
def find(cid):
    for r in rows:
        if r['id'] == cid:
            return r
    sys.exit(1)
if a[:1] == ['ps']:
    # Only the exact invocation the sweep uses is modelled. Anything else is a
    # test bug and must be loud rather than answered with a plausible guess.
    if a != ['ps', '-q', '--filter', 'network=host']:
        sys.stderr.write('fake docker: unmodelled ps: %r\\n' % (a,))
        sys.exit(2)
    print('\\n'.join(r['id'] for r in rows))
elif a[:1] == ['inspect']:
    fmt = a[a.index('--format') + 1]
    r = find(a[-1])
    if fmt == '{{.Name}}':
        print('/' + r['name'])
    elif fmt == '{{index .Config.Labels "mcd.role"}}':
        # Go templates print <no value> for a missing key or a nil map, which is
        # what an unlabelled container really produces.
        print(r['role'] if r['role'] else '<no value>')
    elif fmt == '{{join .Config.Cmd " "}}':
        print(r['cmd'])
    else:
        sys.stderr.write('fake docker: unmodelled inspect format: %r\\n' % (fmt,))
        sys.exit(2)
elif a[:2] == ['rm', '-f']:
    with open(os.path.join(root, 'removed.txt'), 'a') as fh:
        fh.write(find(a[2])['name'] + '\\n')
else:
    sys.stderr.write('fake docker: unmodelled command: %r\\n' % (a,))
    sys.exit(2)
'''
        docker.setExecutable(true)
        return h
    }

    // Runs the sweep snippet and returns [out: ..., removed: [names], rc: n].
    Map sweep(String snippet) {
        File script = new File(dir, 'sweep.sh')
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
        List<String> removed = new File(dir, 'removed.txt').readLines().findAll { it }
        return [out: out.toString(), removed: removed, rc: p.exitValue()]
    }
}

// ---------------------------------------------------------------------------

def check = { String name, Closure body ->
    try {
        body()
        passed++
        println "  ok   ${name}"
    } catch (Throwable t) {
        failures << "${name}: ${t.message}"
        println "  FAIL ${name}: ${t.message}"
    }
}

def script = loadHelper()

// Every sweep run in this file goes through here, so the safety property is
// asserted on all of them rather than only where a case remembered to.
def sweepWithInvariant = { Host host, String snippet ->
    Map r = host.sweep(snippet)
    assert r.rc == 0 : "the sweep exited ${r.rc}:\n${r.out}"
    host.containers.findAll { it.role == 'drainer' }.each { d ->
        assert !r.removed.contains(d.name) :
            "DRAINER INVARIANT VIOLATED: ${d.name} was removed:\n${r.out}"
    }
    drainerInvariant << snippet.md5()
    return r
}

String PORT = '13069'
String KEEP = 'mcd-main-proxy-1'
String snippet = script.call(tcpPort: PORT, keepName: KEEP)

// --- 1. The bug this bead exists to prevent. --------------------------------
check('a drainer holding the port is NOT removed') {
    Host h = Host.of([
        [id: 'c1', name: 'mcd-main-proxy-drainer', role: 'drainer',
         cmd: "/app/proxy -tcpport ${PORT} -wsport 13070"],
    ])
    Map r = sweepWithInvariant(h, snippet)
    assert r.removed == [] : "a bleeding-out drainer was destroyed: ${r.removed}"
    assert r.out.contains('Keeping drainer mcd-main-proxy-drainer') :
        "keeping a drainer silently is how this decision gets reverted by accident:\n${r.out}"
}

// --- 2. The positive control. Without it, disabling the sweep passes. -------
check('an unlabelled container holding the port is still removed') {
    Host h = Host.of([
        [id: 'c1', name: 'some-old-proxy', role: null,
         cmd: "/app/proxy -tcpport ${PORT} -wsport 13070"],
    ])
    Map r = sweepWithInvariant(h, snippet)
    assert r.removed == ['some-old-proxy'] :
        "the sweep stopped working; exempting drainers must not mean exempting everything: ${r.removed}"
    assert r.out.contains("Removing container some-old-proxy holding port ${PORT}")
}

// --- 3. The case 637a43a exists for. ----------------------------------------
check('a foreign compose project squatting on the port is still removed (637a43a)') {
    Host h = Host.of([
        [id: 'c1', name: 'otherproject_proxy_1', role: null,
         cmd: "/app/proxy -tcpport ${PORT}"],
    ])
    Map r = sweepWithInvariant(h, snippet)
    assert r.removed == ['otherproject_proxy_1'] :
        "narrowing the match to container NAME reintroduces the bug 637a43a fixed: ${r.removed}"
}

// --- 4. Mixed host: the discrimination, not just the two ends. --------------
check('on a mixed host only the unlabelled squatters go, and the drainer stays') {
    Host h = Host.of([
        [id: 'c1', name: 'mcd-main-proxy-drainer', role: 'drainer', cmd: "/app/proxy -tcpport ${PORT}"],
        [id: 'c2', name: 'legacy-squatter',        role: null,      cmd: "/app/proxy -tcpport ${PORT}"],
        [id: 'c3', name: KEEP,                     role: null,      cmd: "/app/proxy -tcpport ${PORT}"],
        [id: 'c4', name: 'unrelated-service',      role: null,      cmd: '/app/other -port 9999'],
    ])
    Map r = sweepWithInvariant(h, snippet)
    assert r.removed == ['legacy-squatter'] :
        "expected only the unlabelled squatter to be removed, got ${r.removed}"
}

// --- 5. The deploy's own container. -----------------------------------------
check("the deploy's own container is never swept") {
    Host h = Host.of([[id: 'c1', name: KEEP, role: null, cmd: "/app/proxy -tcpport ${PORT}"]])
    Map r = sweepWithInvariant(h, snippet)
    assert r.removed == [] : "the sweep removed the container the deploy is about to reuse: ${r.removed}"
}

// --- 6. A drainer on a DIFFERENT port is still not this deploy's business. --
check('a drainer not holding this port is left alone too') {
    Host h = Host.of([
        [id: 'c1', name: 'other-env-drainer', role: 'drainer', cmd: '/app/proxy -tcpport 14069'],
    ])
    Map r = sweepWithInvariant(h, snippet)
    assert r.removed == [] : "removed a container that does not even hold this port: ${r.removed}"
}

// --- 7. A container with some other role label is NOT exempt. ---------------
check('only mcd.role=drainer is exempt, not any labelled container') {
    Host h = Host.of([
        [id: 'c1', name: 'labelled-but-not-a-drainer', role: 'worker',
         cmd: "/app/proxy -tcpport ${PORT}"],
    ])
    Map r = sweepWithInvariant(h, snippet)
    assert r.removed == ['labelled-but-not-a-drainer'] :
        "the exemption must be for drainers specifically, not for anything with a label: ${r.removed}"
}

// --- 8. Port matching is on whole tokens, as the original grep intended. ----
check('a container whose port merely appears as a substring is not removed') {
    Host h = Host.of([
        [id: 'c1', name: 'substring-only', role: null, cmd: '/app/proxy -tcpport 113069 -x 130690'],
    ])
    Map r = sweepWithInvariant(h, snippet)
    assert r.removed == [] :
        "the token boundaries in the original grep are load-bearing and were lost: ${r.removed}"
}

// --- 9. An empty host is a no-op, not an error. -----------------------------
check('a host with no containers sweeps nothing and succeeds') {
    Host h = Host.of([])
    Map r = sweepWithInvariant(h, snippet)
    assert r.removed == [] : "removed something on an empty host: ${r.removed}"
}

// --- 10/11. The guards. A missing argument must be loud, not silent. --------
check('a missing tcpPort is refused rather than sweeping nothing') {
    try {
        script.call(keepName: KEEP)
        assert false : 'mcdPortSweep accepted a missing tcpPort; the sweep would silently match nothing'
    } catch (IllegalArgumentException e) {
        assert e.message.contains('tcpPort is required') : e.message
    }
}

check("a missing keepName is refused rather than sweeping the deploy's own container") {
    try {
        script.call(tcpPort: PORT)
        assert false : 'mcdPortSweep accepted a missing keepName; the sweep would remove the deploy container'
    } catch (IllegalArgumentException e) {
        assert e.message.contains('keepName is required') : e.message
    }
}

// ---------------------------------------------------------------------------

println ""
println "drainer invariant asserted on ${drainerInvariant.size()} sweep runs"
println "${passed} passed, ${failures.size()} failed"
if (failures) {
    println ""
    failures.each { println "FAILED: ${it}" }
    System.exit(1)
}
System.exit(0)
