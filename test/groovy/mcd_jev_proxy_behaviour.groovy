// Fixture-only Jenkins step stubs, following mcd_port_sweep_behaviour.groovy.
// Shell parsing, key handling and probes execute. Legacy deployment mutations
// are recorded, not executed. CPS, sandbox approval and real deployment are absent.
import groovy.json.JsonOutput
import org.codehaus.groovy.control.CompilerConfiguration

def loader = new GroovyClassLoader(this.class.classLoader)
def interruptedType = loader.parseClass('''
package org.jenkinsci.plugins.workflow.steps
class FlowInterruptedException extends InterruptedException {}
''')
loader.parseClass('package hudson; class AbortException extends IOException {}')
def helperClass = loader.parseClass(new File('vars/mcdJevProxy.groovy'))
// Compile the complete pipeline too, not just the new helper.
loader.parseClass(new File('vars/mcdServerPipeline.groovy'))

class JevFixture {
    File dir = File.createTempDir('mcd-jev-fixture-', '')
    Map localEnv = [:]
    Map probe = [listed: false, key: '', libraries: 'resolved']
    Map build = [result: 'SUCCESS']
    List<String> console = []
    List<String> shellOutput = []
    List<String> scripts = []
    List<String> events = []
    List<String> mutations = []
    Throwable injected
    String route = 'changed'
    def helper
    final String canary = 'fixture-jev.A_19~+/=:@-'
    final String second = 'fixture-other-secret.Z_72'

    JevFixture(Class helperClass) {
        new File(dir, 'keys/fixture').mkdirs()
        new File(dir, 'bin').mkdirs()
        new File(dir, 'testclient/v1').mkdirs()
        new File(dir, 'testclient/latest.txt').text = 'v1/MCDTestClient\n'
        new File(dir, 'testclient/v1/MCDTestClient').text = 'fixture binary'
        new File(dir, '.env.proxy.fixture').text = 'EXISTING=fixture\n'
        executable('docker', '''#!/usr/bin/env python3
import json, os, subprocess, sys
from pathlib import Path
root = Path(os.environ['MCD_JEV_FIXTURE'])
p = json.loads((root / 'probe.json').read_text())
if len(sys.argv) != 6 or sys.argv[1:5] != ['exec', 'fixture-proxy-1', 'sh', '-c']:
    sys.exit(71)
command = sys.argv[5]
if 'sha256sum' in command:
    if p.get('docker_error'): sys.exit(1)
    env = dict(os.environ)
    env['TYPESAFE_API_KEY'] = p['key']
    sys.exit(subprocess.run(['sh', '-c', command], env=env).returncode)
elif 'ldd' in command:
    if p.get('library_error'): sys.exit(1)
    command = command.replace('/app/testclient-versions', str(root / 'testclient'))
    sys.exit(subprocess.run(['sh', '-c', command]).returncode)
sys.exit(72)
''')
        executable('ldd', '''#!/usr/bin/env python3
import json, os
from pathlib import Path
p = json.loads((Path(os.environ['MCD_JEV_FIXTURE']) / 'probe.json').read_text())
print('libssl.so.3 => not found' if p['libraries'] == 'missing' else 'libssl.so.3 => /lib/libssl.so.3')
''')
        executable('curl', '''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
root = Path(os.environ['MCD_JEV_FIXTURE'])
p = json.loads((root / 'probe.json').read_text())
calls = root / 'curl-count'
count = int(calls.read_text()) + 1 if calls.exists() else 1
calls.write_text(str(count))
if p.get('remove_key'):
    (root / 'keys/fixture/secrets.toml').unlink(missing_ok=True)
if p.get('query_error'): sys.exit(22)
if p.get('invalid_json') or count <= p.get('invalid_first', 0):
    print('{"bots": "not-a-list"}')
else:
    print(json.dumps({'bots': [{'id': 'jev'}] if p['listed'] else []}))
''')
        // Advance fixture time instead of spending 20 seconds on each error case.
        executable('date', '''#!/bin/sh
n=$(cat "$MCD_JEV_FIXTURE/clock" 2>/dev/null || printf 0)
printf '%s' "$((n + 1))" > "$MCD_JEV_FIXTURE/clock"
printf '%s' "$n"
''')
        executable('sleep', '#!/bin/sh\nexit 0\n')
        helper = helperClass.newInstance()
        helper.binding = new Binding([
            currentBuild: build,
            withEnv: { List values, Closure body ->
                Map saved = new LinkedHashMap(localEnv)
                values.each { String value ->
                    int split = value.indexOf('=')
                    localEnv[value.substring(0, split)] = value.substring(split + 1)
                }
                try { body() } finally { localEnv = saved }
            },
            sh: { Map args -> runShell(args) },
            echo: { Object value -> console.add(value.toString()) },
            mcdUnstableReason: { Object value -> console.add('[unstable reason] ' + value) },
            error: { String value -> throw new IllegalStateException(value) }
        ])
    }

    void executable(String name, String text) {
        File file = new File(dir, 'bin/' + name)
        file.text = text
        assert file.setExecutable(true)
    }

    void key(String text) {
        new File(dir, 'keys/fixture/secrets.toml').text = text
    }

    String runShell(Map args) {
        if (injected) { throw injected }
        String script = args.script.toString()
        assert script.startsWith('#!/bin/sh -e\nset +x\n')
        scripts.add(script)
        events.add(localEnv.containsKey('MCD_JEV_CONTAINER') ? 'check' : 'append')
        new File(dir, 'probe.json').text = JsonOutput.toJson(probe)
        File run = new File(dir, 'run.sh')
        run.text = script.replace('/var/opt/mechacorpsgames/Src', dir.absolutePath)
        ProcessBuilder pb = new ProcessBuilder('/bin/sh', '-e', run.absolutePath)
        pb.directory(dir)
        pb.environment().putAll(localEnv.collectEntries { k, v -> [(k.toString()): v.toString()] })
        pb.environment().put('MCD_JEV_FIXTURE', dir.absolutePath)
        pb.environment().put('PATH', new File(dir, 'bin').absolutePath + ':' + System.getenv('PATH'))
        // Inherited tracing cannot expose the file-read value either.
        pb.environment().put('SHELLOPTS', 'xtrace')
        Process process = pb.start()
        StringBuffer out = new StringBuffer(), err = new StringBuffer()
        process.waitForProcessOutput(out, err)
        shellOutput.add(out.toString())
        shellOutput.add(err.toString())
        if (process.exitValue() != 0) { throw new IOException('fixture shell failed') }
        return out.toString()
    }

    void check(String state = 'unconfigured') {
        byte[] before = new File(dir, '.env.proxy.fixture').bytes
        helper.check([typesafeKeyFile: 'keys/fixture/secrets.toml', tcpPort: 42069], 'fixture-proxy-1', state)
        assert new File(dir, '.env.proxy.fixture').bytes == before: 'check modified the env file'
    }

    List<String> verdicts() {
        console.findAll { it.startsWith('Jev:') || it.startsWith('WARN Jev check skipped') }
    }

    void noLeaks() {
        String output = (console + shellOutput + scripts).join('\n')
        assert !output.contains(canary): 'key leaked outside the env file'
        assert !output.contains(second): 'second secret leaked'
        assert !new File(dir, '.env.proxy.fixture').text.contains(second)
        assert !output.contains(java.security.MessageDigest.getInstance('SHA-256')
            .digest(canary.bytes).encodeHex().toString()): 'digest leaked'
    }

    void close() {
        File restricted = new File(dir, 'keys/fixture/secrets.toml')
        if (restricted.exists()) { restricted.setReadable(true) }
        assert dir.deleteDir()
    }
}

int passed = 0
def run = { String name, Closure body ->
    def fixture = new JevFixture(helperClass)
    try {
        body(fixture)
        fixture.noLeaks()
        passed++
        println '  ok   ' + name
    } catch (Throwable failure) {
        // Failed assertions may include fixture file contents. Never print them.
        def site = failure.stackTrace.find { it.fileName == 'mcd_jev_proxy_behaviour.groovy' }
        throw new IllegalStateException('Jev fixture failed: ' + name + ' (' + failure.class.simpleName + ', line ' + site?.lineNumber + ')')
    } finally { fixture.close() }
}

def keyFile = 'keys/fixture/secrets.toml'
def envFile = '.env.proxy.fixture'
[
    unquoted: { f -> "TYPESAFE_API_KEY = ${f.canary}\nOTHER_SECRET = ${f.second}\n" },
    quoted: { f -> " \tTYPESAFE_API_KEY \t= \"${f.canary}\" \t\nOTHER_SECRET = ${f.second}\n" },
    crlf: { f -> "TYPESAFE_API_KEY = \"${f.canary}\"\r\nOTHER_SECRET = ${f.second}\r\n" }
].each { name, value ->
    run('valid ' + name) { f ->
        f.key(value(f))
        java.nio.file.Files.setPosixFilePermissions(new File(f.dir, envFile).toPath(),
            java.nio.file.attribute.PosixFilePermissions.fromString('rw-r--r--'))
        // Tighten the mode before any key bytes reach the file.
        f.executable('chmod', '''#!/bin/sh
if grep -q '^TYPESAFE_API_KEY=' "$2"; then exit 73; fi
exec /bin/chmod "$@"
''')
        assert f.helper.appendKey(keyFile, envFile)
        assert new File(f.dir, envFile).text == "EXISTING=fixture\nTYPESAFE_API_KEY=${f.canary}\n"
        assert java.nio.file.Files.getPosixFilePermissions(new File(f.dir, envFile).toPath()) ==
            java.nio.file.attribute.PosixFilePermissions.fromString('rw-------')
        assert f.build.result == 'SUCCESS'
    }
}

[
    missing: [null, 'not found'],
    absent: ['OTHER_SECRET = dummy\n', 'no TYPESAFE_API_KEY line'],
    commented: ['# TYPESAFE_API_KEY = dummy\n', 'no TYPESAFE_API_KEY line'],
    prefix: ['NOT_TYPESAFE_API_KEY = dummy\n', 'no TYPESAFE_API_KEY line'],
    duplicate: ['TYPESAFE_API_KEY=a\n TYPESAFE_API_KEY=b\n', 'more than one'],
    empty: ['TYPESAFE_API_KEY = ""\n', 'key value rejected'],
    space: ['TYPESAFE_API_KEY = "bad key"\n', 'key value rejected'],
    quote: ['TYPESAFE_API_KEY = "bad"key"\n', 'key value rejected'],
    comment: ['TYPESAFE_API_KEY = key # comment\n', 'key value rejected'],
    dollar: ['TYPESAFE_API_KEY = key$expand\n', 'key value rejected'],
    unicode: ['TYPESAFE_API_KEY = cl\u00e9\n', 'key value rejected']
].each { name, fixture ->
    run('reject ' + name) { f ->
        if (fixture[0] != null) { f.key(fixture[0]) }
        assert !f.helper.appendKey(keyFile, envFile)
        assert f.build.result == 'UNSTABLE'
        assert f.console.join().contains(fixture[1])
        assert new File(f.dir, envFile).text == 'EXISTING=fixture\n'
        f.check('unusable')
        assert f.verdicts() == ['Jev: not offered on this environment (key file unusable)']
        assert f.console.count { it.startsWith('[unstable reason]') } == 1
    }
}
['../secrets.toml', 'keys/../secrets.toml', 'keys/..', '/tmp/secrets.toml', 'keys/space name', 'keys/$(command)', 'keys/cl\u00e9'].each { value ->
    run('invalid path ' + value) { f ->
        assert !f.helper.appendKey(value, envFile)
        assert f.console == ['[unstable reason] Jev not offered: typesafeKeyFile invalid']
        assert new File(f.dir, envFile).text == 'EXISTING=fixture\n'
    }
}
run('unreadable mode 000') { f ->
    f.key('TYPESAFE_API_KEY=dummy\n')
    def file = new File(f.dir, keyFile)
    java.nio.file.Files.setPosixFilePermissions(file.toPath(), [] as Set)
    assert !file.canRead(): 'run the mode-000 fixture as an unprivileged user'
    assert !f.helper.appendKey(keyFile, envFile)
    assert f.console.join().contains('not readable by the build account')
    assert new File(f.dir, envFile).text == 'EXISTING=fixture\n'
}
run('non-owner does not change env mode') { f ->
    f.key('TYPESAFE_API_KEY=dummy\n')
    f.executable('stat', '#!/bin/sh\nprintf 4294967294\n')
    def file = new File(f.dir, envFile)
    def mode = java.nio.file.attribute.PosixFilePermissions.fromString('rw-r-----')
    java.nio.file.Files.setPosixFilePermissions(file.toPath(), mode)
    assert f.helper.appendKey(keyFile, envFile)
    assert java.nio.file.Files.getPosixFilePermissions(file.toPath()) == mode
}

[
    offered: ['ready', true, 'same', 'SUCCESS', 'Jev: offered'],
    rotated: ['ready', true, 'old', 'UNSTABLE', 'key not current'],
    emptyContainer: ['ready', true, '', 'UNSTABLE', 'key not current'],
    unlisted: ['ready', false, 'same', 'UNSTABLE', 'expected but not listed'],
    off: ['unconfigured', false, '', 'SUCCESS', '(not configured)'],
    strayListing: ['unconfigured', true, '', 'UNSTABLE', 'did not opt in'],
    strayKey: ['unconfigured', false, 'same', 'UNSTABLE', 'did not opt in'],
    failedRead: ['unusable', false, '', 'SUCCESS', '(key file unusable)'],
    failedReadListing: ['unusable', true, '', 'UNSTABLE', 'key file is unusable'],
    failedReadKey: ['unusable', false, 'same', 'UNSTABLE', 'key file is unusable']
].each { name, scenario ->
    run('verdict ' + name) { f ->
        f.key("TYPESAFE_API_KEY=${f.canary}\nOTHER_SECRET=${f.second}\n")
        f.probe.listed = scenario[1]
        f.probe.key = scenario[2] == 'same' ? f.canary : scenario[2]
        f.check(scenario[0])
        assert f.build.result == scenario[3]
        assert f.verdicts().size() == 1
        assert f.verdicts()[0].contains(scenario[4])
        assert new File(f.dir, 'curl-count').text == '1': 'a valid list must be judged once'
    }
}
['query_error', 'invalid_json', 'docker_error', 'remove_key'].each { failure ->
    run('soft check ' + failure) { f ->
        f.key("TYPESAFE_API_KEY=${f.canary}\n")
        f.probe.key = f.canary
        f.probe.listed = true
        f.probe[failure] = true
        f.check('ready')
        assert f.build.result == 'SUCCESS'
        assert f.verdicts().size() == 1
        assert f.verdicts()[0].startsWith('WARN Jev check skipped:')
    }
}
run('unavailable library probe preserves the offered verdict') { f ->
    f.key("TYPESAFE_API_KEY=${f.canary}\n")
    f.probe += [key: f.canary, listed: true, library_error: true]
    f.check('ready')
    assert f.build.result == 'SUCCESS'
    assert f.verdicts() == ['Jev: offered in /api/bots (WARN: bot libraries could not be checked)']
}
run('invalid list then valid list') { f ->
    f.probe.invalid_first = 2
    f.check()
    assert f.verdicts() == ['Jev: not offered on this environment (not configured)']
    assert new File(f.dir, 'curl-count').text == '3'
}
run('missing libraries are a positive observation') { f ->
    f.key("TYPESAFE_API_KEY=${f.canary}\n")
    f.probe += [key: f.canary, listed: true, libraries: 'missing']
    f.check('ready')
    assert f.build.result == 'UNSTABLE'
    assert f.verdicts()[0].contains('unresolved shared libraries')
}
run('ordinary Groovy error is one WARN') { f ->
    f.injected = new IOException('fixture ordinary error')
    f.check()
    assert f.verdicts() == ['WARN Jev check skipped: probe failed or timed out']
    assert f.build.result == 'SUCCESS'
}
run('unknown shell verdict is one WARN') { f ->
    f.helper.binding.setVariable('sh', { Map args -> 'unknown' })
    f.check()
    assert f.verdicts() == ['WARN Jev check skipped: probe returned an unknown result']
    assert f.build.result == 'SUCCESS'
}
run('unknown append verdict and ordinary append error propagate') { f ->
    f.helper.binding.setVariable('sh', { Map args -> 'unknown' })
    boolean unknownThrew = false
    try { f.helper.appendKey(keyFile, envFile) } catch (IllegalStateException expected) { unknownThrew = true }
    assert unknownThrew
    f.helper.binding.setVariable('sh', { Map args -> throw new IOException('fixture append failure') })
    boolean errorThrew = false
    try { f.helper.appendKey(keyFile, envFile) } catch (IOException expected) { errorThrew = true }
    assert errorThrew
    assert f.console.empty
}
run('shell timeout is one WARN') { f ->
    f.executable('timeout', '#!/bin/sh\nexit 124\n')
    f.check()
    assert f.verdicts() == ['WARN Jev check skipped: probe failed or timed out']
    assert f.build.result == 'SUCCESS'
}
run('abort propagates from check and append') { f ->
    f.injected = interruptedType.newInstance()
    boolean checkThrew = false, appendThrew = false
    try { f.check() } catch (Exception e) { assert e.is(f.injected); checkThrew = true }
    try { f.helper.appendKey(keyFile, envFile) } catch (Exception e) { assert e.is(f.injected); appendThrew = true }
    assert checkThrew && appendThrew
    assert f.console.empty
}
run('non-ordinary error propagates') { f ->
    f.injected = new LinkageError('fixture fatal error')
    boolean threw = false
    try { f.check() } catch (LinkageError e) { assert e.is(f.injected); threw = true }
    assert threw
    assert f.console.empty
}
run('thread interruption propagates') { f ->
    f.injected = new InterruptedException('fixture interruption')
    boolean threw = false
    try { f.check() } catch (InterruptedException e) { assert e.is(f.injected); threw = true }
    assert threw
    assert f.console.empty
}
run('leak scanner has positive controls') { f ->
    [f.canary, f.second, java.security.MessageDigest.getInstance('SHA-256')
        .digest(f.canary.bytes).encodeHex().toString()].each { value ->
        f.console.add(value)
        boolean caught = false
        try { f.noLeaks() } catch (AssertionError expected) { caught = true }
        f.console.clear()
        assert caught: 'leak scanner missed a deliberately injected leak'
    }
}

// Execute the actual stage's Groovy control flow. Existing shell mutations are
// stubs, allowing comparison of their order and arguments without a deploy host.
String branchSource = new File('vars/mcdServerPipeline.groovy').text
def executeStage = { String source, String route, Map options = [:] ->
    def f = new JevFixture(helperClass)
    f.route = route
    String stage = source.substring(source.indexOf("stage('Deploy Proxy (if changed)')"), source.indexOf("stage('Cleanup Old Versions')"))
    String code = stage.substring(stage.indexOf('script {') + 'script {'.length(), stage.lastIndexOf('\n                    }'))
    Map env = [:]
    def config = [tcpPort: 42069, deployPath: '/fixture/deploy', proxyEnvFile: envFile,
        proxyProject: 'fixture', environment: 'fixture', accountApiKey: 'fixture-account-key']
    if (options.state in ['ready', 'unusable']) {
        config.typesafeKeyFile = keyFile
        if (options.state == 'ready') { f.key("TYPESAFE_API_KEY=${f.canary}\nOTHER_SECRET=${f.second}\n") }
    }
    f.probe.putAll(options.probe ?: [:])
    if (f.probe.key == 'same') { f.probe.key = f.canary }
    Closure oldSh = { Object value ->
        Map args = value instanceof Map ? value : [script: value]
        String script = args.script.toString()
        if (script.contains("<<'ENVEOF'")) {
            File render = new File(f.dir, 'render.sh')
            render.text = script.replace('/var/opt/mechacorpsgames/Src', f.dir.absolutePath)
            Process process = new ProcessBuilder('/bin/sh', '-e', render.absolutePath).start()
            assert process.waitFor() == 0
            f.console.add('legacy env generation')
        } else if (script == 'git rev-parse HEAD') { return 'fixture-commit' }
        else if (script.startsWith('sha256sum bin/MCDProxy')) { return route == 'changed' ? 'new' : 'same' }
        else if (script.startsWith('sha256sum /fixture')) { return 'same' }
        else if (script.startsWith('cat Src/docker-compose')) { return 'config' }
        else if (script.startsWith('cat /tmp/.mcd-proxy')) { return 'config' }
        else { f.mutations.add(script); f.console.add('legacy shell step') }
        return ''
    }
    def binding = new Binding([config: config, env: env, sh: oldSh,
        echo: { Object value -> f.console.add(value.toString()) },
        discordNotify: new Expando(simple: { Object a, Object b -> f.console.add(a.toString()) }),
        mcdPortSweep: { Map args -> 'fixture-port-sweep' },
        mcdDeployLock: { Map args, Object script ->
            f.events.add('lock')
            f.mutations.add(script.toString())
            f.console.add(route == 'changed' ? 'legacy restart' : route == 'recovery' ? 'legacy recovery' : 'legacy no-op')
        }, mcdJevProxy: options.withoutJev ?
            new Expando(appendKey: { String keyPath, String envPath -> false },
                        check: { Map cfg, String container, String state -> }) : f.helper])
    new GroovyShell(loader, binding).evaluate(code)
    return f
}
['changed', 'recovery', 'noop'].each { route ->
    // Compare the same stage with and without Jev, independent of git refs.
    def before = executeStage(branchSource, route, [withoutJev: true])
    def after = executeStage(branchSource, route)
    try {
        assert new File(before.dir, envFile).bytes == new File(after.dir, envFile).bytes
        assert !new File(after.dir, envFile).text.contains('TYPESAFE')
        assert after.console == before.console + ['Jev: not offered on this environment (not configured)']
        assert after.mutations == before.mutations
        assert before.events == ['lock']
        assert after.events == ['lock', 'check']
        assert !after.events.contains('append')
        if (System.getenv('MCD_JEV_EVIDENCE')) {
            File evidence = new File(System.getenv('MCD_JEV_EVIDENCE'))
            evidence.mkdirs()
            new File(evidence, "unset-${route}-base.env").bytes = new File(before.dir, envFile).bytes
            new File(evidence, "unset-${route}-branch.env").bytes = new File(after.dir, envFile).bytes
            new File(evidence, "unset-${route}-base.console").text = before.console.join('\n') + '\n'
            new File(evidence, "unset-${route}-branch.console").text = after.console.join('\n') + '\n'
        }
        before.noLeaks(); after.noLeaks()
        passed++
        println '  ok   unset stage ' + route
    } finally { before.close(); after.close() }
}
def stageCases = [
    [state: 'ready', probe: [listed: true, key: 'same'], result: 'SUCCESS', line: 'Jev: offered'],
    [state: 'ready', probe: [listed: true, key: 'old'], result: 'UNSTABLE', line: 'key not current'],
    [state: 'ready', probe: [listed: false, key: 'same'], result: 'UNSTABLE', line: 'expected but not listed'],
    [state: 'unconfigured', probe: [listed: false, key: ''], result: 'SUCCESS', line: '(not configured)'],
    [state: 'unconfigured', probe: [listed: true, key: ''], result: 'UNSTABLE', line: 'did not opt in'],
    [state: 'unconfigured', probe: [listed: false, key: 'same'], result: 'UNSTABLE', line: 'did not opt in'],
    [state: 'unusable', probe: [listed: false, key: ''], result: 'UNSTABLE', line: '(key file unusable)', reasons: 1],
    [state: 'unusable', probe: [listed: true, key: ''], result: 'UNSTABLE', line: 'key file is unusable', reasons: 2],
    [state: 'unusable', probe: [listed: false, key: 'same'], result: 'UNSTABLE', line: 'key file is unusable', reasons: 2],
    [state: 'ready', probe: [query_error: true], result: 'SUCCESS', line: 'WARN Jev check skipped'],
    [state: 'ready', probe: [invalid_json: true], result: 'SUCCESS', line: 'WARN Jev check skipped'],
    [state: 'ready', probe: [docker_error: true], result: 'SUCCESS', line: 'WARN Jev check skipped'],
    [state: 'ready', probe: [remove_key: true], result: 'SUCCESS', line: 'WARN Jev check skipped'],
    [state: 'ready', probe: [listed: true, key: 'same', library_error: true], result: 'SUCCESS', line: 'Jev: offered in /api/bots (WARN: bot libraries could not be checked)'],
    [state: 'ready', probe: [listed: true, key: 'same', libraries: 'missing'], result: 'UNSTABLE', line: 'unresolved shared libraries']
]
['changed', 'recovery', 'noop'].each { route ->
    stageCases.eachWithIndex { scenario, index ->
        def f = executeStage(branchSource, route, scenario)
        try {
            assert f.build.result == scenario.result
            assert f.verdicts().size() == 1
            assert f.verdicts()[0].contains(scenario.line)
            assert f.events == (scenario.state == 'unconfigured' ? ['lock', 'check'] : ['append', 'lock', 'check'])
            if (scenario.reasons) { assert f.console.count { it.startsWith('[unstable reason]') } == scenario.reasons }
            f.noLeaks()
            passed++
            println "  ok   stage ${route} verdict ${index + 1}"
        } catch (Throwable failure) {
            throw new IllegalStateException("Jev stage fixture failed: ${route} case ${index + 1} (${failure.class.simpleName})")
        } finally { f.close() }
    }
}
println 'E1 env bytes identical; E2 exactly one added line on all three paths; existing mutation arguments identical'
println "Jev contract: ${passed} passed, 0 failed"
