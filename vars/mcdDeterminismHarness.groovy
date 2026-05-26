// Shared helpers for the MCDClient determinism-harness replay cadences.
// Consumed by mcdPRValidationPipeline and mcdDeterminismHarnessNightlyPipeline.

def cadenceMatches(List changedFiles, Map cadence) {
    if (!cadence || cadence.enabled == false || !changedFiles) {
        return false
    }

    if (cadence.changedAny) {
        return changedFiles.any { path ->
            cadence.changedAny.any { glob -> pathMatchesGlob(path, glob as String) }
        }
    }

    if (cadence.changedOnly) {
        return changedFiles.every { path ->
            cadence.changedOnly.any { glob -> pathMatchesGlob(path, glob as String) }
        }
    }

    return false
}

def runPrCadences(Map harness, List cadenceKeys) {
    if (!harness || harness.enabled == false || !cadenceKeys) {
        return
    }

    buildTestClient(harness)

    def failures = []
    cadenceKeys.each { key ->
        def cadence = harness.cadences[key] as Map
        def fixtureNames = resolveFixtureNames(harness, cadence)
        def results = fixtureNames.collect { fixtureName ->
            def fixtureDir = "${harness.fixtures.root}/${fixtureName}"
            runReplayCase(
                harness,
                cadence,
                key,
                fixtureName,
                "${fixtureDir}/mcp_action_log.jsonl",
                "${fixtureDir}/replay_baseline_trace.json"
            )
        }
        writeJUnit(cadence.junit as String, "determinism-harness-${key}", results)
        failures.addAll(results.findAll { !it.passed && !it.skipped })
    }

    if (failures) {
        error("Determinism harness failed: ${failures.collect { it.name }.join(', ')}")
    }
}

def runNightly(Map harness) {
    if (!harness || harness.enabled == false) {
        echo "Determinism harness nightly disabled."
        return
    }

    buildTestClient(harness)

    def cadence = harness.cadence ?: [:]
    def pairs = sampleNightlyArtifacts(harness)
    def results = []

    if (!pairs) {
        results << [
            name: 'nightly/no-artifacts',
            passed: true,
            skipped: true,
            message: 'No yesterday playtest-bench artifacts with co-located baselines were found.'
        ]
    } else {
        results = pairs.collectWithIndex { pair, index ->
            runReplayCase(
                harness,
                cadence,
                'nightly',
                "sample-${index + 1}",
                pair.actionLog,
                pair.baseline
            )
        }
    }

    writeJUnit(cadence.junit as String, 'determinism-harness-nightly', results)

    def failures = results.findAll { !it.passed && !it.skipped }
    if (failures) {
        def summary = failures.collect { "${it.name}: ${it.message}" }.join('\n')
        notifyFailureEmail(cadence.failureEmail as String, summary)
        if (cadence.blocking == false) {
            currentBuild.result = 'UNSTABLE'
            echo "Non-blocking determinism nightly failures:\n${summary}"
        } else {
            error("Determinism nightly failed:\n${summary}")
        }
    }
}

def resolveFixtureNames(Map harness, Map cadence) {
    def fixtureSpec = cadence.fixtures
    if (fixtureSpec == 'canonical') {
        return harness.fixtures.canonical ?: []
    }
    if (fixtureSpec instanceof List) {
        return fixtureSpec
    }
    if (fixtureSpec) {
        return [fixtureSpec as String]
    }
    if (harness.fixtures?.perPrDefault) {
        return [harness.fixtures.perPrDefault as String]
    }
    return []
}

def buildTestClient(Map harness) {
    def testClient = harness.testClient ?: [:]
    if (testClient.buildCommand) {
        sh testClient.buildCommand as String
    }
    if (testClient.binary && !fileExists(testClient.binary as String)) {
        error("Determinism harness TestClient binary not found: ${testClient.binary}")
    }
}

def runReplayCase(Map harness, Map cadence, String cadenceName, String caseName, String actionLog, String baseline) {
    def outDir = "test-results/determinism-harness/${cadenceName}/${caseName}"
    if (!fileExists(actionLog)) {
        return failedResult(cadenceName, caseName, "missing action log: ${actionLog}")
    }
    if (!fileExists(baseline)) {
        return failedResult(cadenceName, caseName, "missing baseline trace: ${baseline}")
    }

    def testClient = harness.testClient ?: [:]
    def binary = testClient.binary as String
    def args = (testClient.args ?: []).collect { raw ->
        (raw as String)
            .replace('{fixture_dir}', actionLog.contains('/') ? actionLog.substring(0, actionLog.lastIndexOf('/')) : '.')
            .replace('{action_log}', actionLog)
            .replace('{baseline_trace}', baseline)
            .replace('{out_dir}', outDir)
    }
    def command = ([binary] + args).collect { shellQuote(it as String) }.join(' ')
    def budgetMinutes = (cadence.budgetMinutes ?: cadence.coldBudgetMinutes ?: 10) as Integer

    try {
        timeout(time: budgetMinutes, unit: 'MINUTES') {
            sh """#!/bin/bash
                set +e
                mkdir -p ${shellQuote(outDir)}
                ${command} > ${shellQuote("${outDir}/stdout.log")} 2> ${shellQuote("${outDir}/stderr.log")}
                rc=\$?
                echo "\$rc" > ${shellQuote("${outDir}/exit_code")}
                exit 0
            """
        }
    } catch (err) {
        return failedResult(cadenceName, caseName, "timeout or execution failure: ${err.getMessage()}")
    }

    def exitCode = readFile("${outDir}/exit_code").trim() as Integer
    def expectedExitCode = (cadence.expectedExitCode == null) ? 0 : cadence.expectedExitCode as Integer
    def actualAttribution = readAttribution(outDir, exitCode)
    def expectedAttribution = cadence.expectedAttribution as String

    def passed = (exitCode == expectedExitCode)
    if (passed && expectedAttribution) {
        passed = (actualAttribution == expectedAttribution)
    }

    return [
        name: "${cadenceName}/${caseName}",
        passed: passed,
        skipped: false,
        message: passed
            ? "exit=${exitCode}, attribution=${actualAttribution}"
            : "expected exit=${expectedExitCode}, attribution=${expectedAttribution}; got exit=${exitCode}, attribution=${actualAttribution}",
        stdout: "${outDir}/stdout.log",
        stderr: "${outDir}/stderr.log"
    ]
}

def readAttribution(String outDir, Integer exitCode) {
    def attributionPath = "${outDir}/attribution.json"
    if (fileExists(attributionPath)) {
        return sh(
            script: "python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(\"category\", \"\"))' ${shellQuote(attributionPath)}",
            returnStdout: true
        ).trim()
    }
    if (exitCode == 0) {
        return 'identical'
    }
    if (exitCode == 3) {
        return 'action_log_shape'
    }
    return ''
}

def sampleNightlyArtifacts(Map harness) {
    def artifacts = harness.artifacts ?: [:]
    def root = artifacts.playtestBenchRoot ?: 'logs/playtest-bench'
    def sampleCount = (artifacts.sampleCount ?: 3) as Integer
    def actionLogName = artifacts.actionLogName ?: 'mcp_action_log.jsonl'
    def baselineName = artifacts.baselineTraceName ?: 'replay_baseline_trace.json'

    def output = sh(
        script: """#!/bin/bash
            set -euo pipefail
            root=${shellQuote(root as String)}
            if [ ! -d "\$root" ]; then
                exit 0
            fi
            find "\$root" -type f -name ${shellQuote(actionLogName as String)} \\
                -newermt 'yesterday 00:00' ! -newermt 'today 00:00' \\
                | while IFS= read -r action_log; do
                    baseline="\$(dirname "\$action_log")/${baselineName}"
                    if [ -f "\$baseline" ]; then
                        printf '%s\\t%s\\n' "\$action_log" "\$baseline"
                    fi
                done | shuf -n ${sampleCount}
        """,
        returnStdout: true
    ).trim()

    if (!output) {
        return []
    }
    return output.split('\n').collect { line ->
        def parts = line.split('\t', 2)
        [actionLog: parts[0], baseline: parts[1]]
    }
}

def writeJUnit(String path, String suiteName, List results) {
    def junitPath = path ?: "test-results/determinism-harness/${suiteName}.xml"
    def failures = results.findAll { !it.passed && !it.skipped }.size()
    def skipped = results.findAll { it.skipped }.size()
    def cases = results.collect { result ->
        def body = ''
        if (result.skipped) {
            body = "<skipped message=\"${xmlEscape(result.message)}\"/>"
        } else if (!result.passed) {
            body = "<failure message=\"${xmlEscape(result.message)}\"/>"
        }
        "    <testcase classname=\"${xmlEscape(suiteName)}\" name=\"${xmlEscape(result.name)}\">${body}</testcase>"
    }.join('\n')

    sh "mkdir -p ${shellQuote(junitPath.substring(0, junitPath.lastIndexOf('/')))}"
    writeFile file: junitPath, text: """<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<testsuite name=\"${xmlEscape(suiteName)}\" tests=\"${results.size()}\" failures=\"${failures}\" skipped=\"${skipped}\">
${cases}
</testsuite>
"""
}

def notifyFailureEmail(String recipient, String body) {
    if (!recipient) {
        return
    }
    try {
        mail(
            to: recipient,
            subject: "MCD determinism harness nightly failed: ${env.JOB_NAME} #${BUILD_NUMBER}",
            body: body
        )
    } catch (NoSuchMethodError e) {
        echo "Mailer plugin not installed; unable to email ${recipient}"
    }
}

def failedResult(String cadenceName, String caseName, String message) {
    [name: "${cadenceName}/${caseName}", passed: false, skipped: false, message: message]
}

def pathMatchesGlob(String path, String glob) {
    if (glob.endsWith('/**')) {
        return path.startsWith(glob.substring(0, glob.length() - 2))
    }
    return path == glob
}

def shellQuote(String value) {
    return "'${value.replace("'", "'\"'\"'")}'"
}

def xmlEscape(String value) {
    return (value ?: '')
        .replace('&', '&amp;')
        .replace('<', '&lt;')
        .replace('>', '&gt;')
        .replace('"', '&quot;')
        .replace("'", '&apos;')
}

return this
