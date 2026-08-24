// MechaCorps Server Pipeline - Shared Library
// Builds: GameServer, Proxy, TestClient
// Deploys with zero-downtime to environment-specific paths/ports

def call(Map config) {
    // Required config:
    //   branch: 'main' or 'release'
    //   environment: 'development' or 'production'
    //   deployPath: '/opt/mechacorps/main' or '/opt/mechacorps/release'
    //   tcpPort: 43069 or 42069
    //   wsPort: 43070 or 42070
    //   serverHost: 'dev.mechacorpsgames.com' or 'play.mechacorpsgames.com'
    //   webhookToken: 'mcdserver-main' or 'mcdserver-release'
    //   jobName: 'MCDServer-Main' or 'MCDServer-Release'

    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                args '-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /var/lib/jenkins/.android:/var/lib/jenkins/.android:ro -v /opt/mechacorps:/opt/mechacorps -v /var/opt/mechacorpsgames/Src:/var/opt/mechacorpsgames/Src --network host --group-add 111 --group-add 995 --group-add 1000'
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '10', artifactDaysToKeepStr: '7', artifactNumToKeepStr: '10'))
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            BRANCH_NAME = "${config.branch}"
            DEPLOY_ENV = "${config.environment}"
            DEPLOY_PATH = "${config.deployPath}"
            TCP_PORT = "${config.tcpPort}"
            WS_PORT = "${config.wsPort}"
            SERVER_HOST = "${config.serverHost}"
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'ref', value: '$.ref'],
                    [key: 'repo_name', value: '$.repository.full_name'],
                    [key: 'commit_sha', value: '$.after'],
                    [key: 'commit_message', value: '$.head_commit.message'],
                    [key: 'commit_author', value: '$.head_commit.author.name'],
                    [key: 'pusher_name', value: '$.pusher.name'],
                    [key: 'commits_count', value: '$.commits.length()'],
                    [key: 'before_sha', value: '$.before']
                ],
                causeString: "Triggered by push to ${config.branch}",
                token: config.webhookToken,
                tokenCredentialId: '',
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
                // Filter on the ref ONLY. Path filtering deliberately does not
                // happen here: see the 'Detect Changes' stage below, which is
                // what actually decides whether this build does any work.
                //
                // This used to also match against $.commits[*].{added,modified,
                // removed}[*]. Those JSONPaths flatten to one env var per file
                // PLUS an aggregate var holding the whole list as a single
                // string, and the plugin injects all of them into the build
                // environment. A cascade merge made that aggregate exceed the
                // kernel's MAX_ARG_STRLEN (131,072 B, the cap on ONE env
                // string, not on the environment as a whole), after which every
                // exec from the build JVM failed with E2BIG. The build died on
                // its first exec, `git init`, while cloning THIS library,
                // before any Jenkinsfile ran. See MCDClient bead mc-4qz7: the
                // measured client pushes carried a 171,999 B and a 221,028 B
                // files_added. The server jobs share the same trigger shape and
                // the same cascade pushes, so they were one cascade away from
                // the identical failure.
                //
                // Dropping the path filter cannot skip a build that used to
                // run: the trigger was ANDed with SERVER_CHANGED /
                // MCP_GAME_SERVER_CHANGED, and those gates are still here. It
                // only lets a few more builds start and immediately mark
                // themselves NOT_BUILT. Every other pipeline in this library
                // (app services, services, discord bot) already triggers on
                // $ref alone for the same reason.
                regexpFilterText: '$ref',
                regexpFilterExpression: "refs/heads/${config.branch}"
            )
        }

        stages {
            stage('Setup Build Info') {
                steps {
                    script {
                        // SERVER_VERSION is derived from Src/GameServer/CMakeLists.txt
                        // in the Checkout stage — the repo is not on disk yet here, so
                        // the display name starts without a version and gains it there.
                        // This pipeline stamped a private "0.1.${BUILD_NUMBER}" from
                        // its initial commit while CMake shipped 1.0.x and then 0.2.x:
                        // every displayName, manifest serverVersion and Discord line
                        // disagreed with the binary and its deploy path (mc-glpn,
                        // found via mc-bs84's v0.2.873).
                        def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
                        currentBuild.displayName = "#${BUILD_NUMBER} (${shortSha})"

                        def commitMsg = env.commit_message ? env.commit_message.split('\n')[0].take(60) : 'Manual build'

                        def author = env.commit_author ?: 'Unknown'
                        if (author == 'Unknown') {
                            def buildCause = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
                            if (buildCause && buildCause.size() > 0) {
                                author = buildCause[0].userName ?: buildCause[0].userId ?: 'Unknown'
                            }
                        }
                        env.BUILD_AUTHOR = author

                        // GitHub username for Discord mentions
                        env.BUILD_GITHUB_USER = env.pusher_name ?: ''
                        if (!env.BUILD_GITHUB_USER && author != 'Unknown') {
                            def buildCause2 = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
                            if (buildCause2 && buildCause2.size() > 0) {
                                env.BUILD_GITHUB_USER = buildCause2[0].userId ?: ''
                            }
                        }

                        currentBuild.description = "${commitMsg}\nby ${author} → ${config.environment}"

                        env.BUILD_ENV = sh(script: 'uname -s -r', returnStdout: true).trim()
                        env.GCC_VERSION = sh(script: 'gcc --version | head -1', returnStdout: true).trim()
                        env.GO_VERSION = sh(script: 'go version | cut -d" " -f3', returnStdout: true).trim()

                        echo "Branch: ${config.branch}"
                        echo "Environment: ${config.environment}"
                        echo "Deploy path: ${config.deployPath}"
                        echo "Ports: TCP=${config.tcpPort}, WS=${config.wsPort}"
                    }
                }
            }

            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        // THE VERSION HAS ONE SOURCE OF TRUTH (mc-glpn).
                        // Src/GameServer/CMakeLists.txt says "Developers control
                        // MAJOR.MINOR, CI/CD controls BUILD" — so MAJOR.MINOR are read
                        // from it and only BUILD_NUMBER belongs to CI. A parse failure
                        // refuses to stamp a made-up version instead of defaulting:
                        // a silent fallback is the two-sources bug with extra steps.
                        // grep|head|tr runs without pipefail on purpose — a missing
                        // line yields empty output here and the error() below, not an
                        // opaque nonzero sh exit.
                        def major = sh(script: 'grep "^set(VERSION_MAJOR " Src/GameServer/CMakeLists.txt | head -1 | tr -dc "0-9"', returnStdout: true).trim()
                        def minor = sh(script: 'grep "^set(VERSION_MINOR " Src/GameServer/CMakeLists.txt | head -1 | tr -dc "0-9"', returnStdout: true).trim()
                        if (!(major ==~ /\d+/) || !(minor ==~ /\d+/)) {
                            error("mc-glpn: could not derive VERSION_MAJOR/VERSION_MINOR from Src/GameServer/CMakeLists.txt (got '${major}' / '${minor}') — refusing to stamp a made-up version")
                        }
                        env.SERVER_VERSION = "${major}.${minor}.${BUILD_NUMBER}"
                        def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
                        currentBuild.displayName = "#${BUILD_NUMBER} v${env.SERVER_VERSION} (${shortSha})"
                        echo "Server Version: ${env.SERVER_VERSION} (MAJOR.MINOR from Src/GameServer/CMakeLists.txt)"
                    }
                }
            }

            stage('Detect Changes') {
                steps {
                    script {
                        def baseRef = env.before_sha
                        if (!baseRef || baseRef.startsWith('0000000')) {
                            echo "No valid before SHA — building everything"
                            env.SERVER_CHANGED = 'true'
                            env.MCP_GAME_SERVER_CHANGED = 'true'
                        } else {
                            // Ensure the before SHA is available locally
                            sh "git fetch origin ${baseRef} 2>/dev/null || true"
                            def changes = mcdChangeDetection.detect(baseRef)
                            env.SERVER_CHANGED = changes.serverChanged.toString()
                            env.MCP_GAME_SERVER_CHANGED = changes.mcpGameServerChanged.toString()
                        }

                        if (env.SERVER_CHANGED != 'true' && env.MCP_GAME_SERVER_CHANGED != 'true') {
                            currentBuild.description += "\n⏭️ No server changes — skipped"
                            currentBuild.result = 'NOT_BUILT'
                        }
                    }
                }
            }

            stage('Go Lint') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh '''
                        # Pinned (mc-qhu): unpinned @latest broke unrelated PRs whenever
                        # upstream released a new linter set. Bump deliberately in a
                        # follow-up PR after triaging any new findings.
                        go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.12.0
                        export PATH="$(go env GOPATH)/bin:$PATH"
                        make lint
                    '''
                }
            }

            // Data/GameData/ is gitignored — it's a build artifact produced
            // from Data/Cards/* by Src/MCDCoreExt/export_done_cards.py against
            // the DONE-cards registry. It must exist BEFORE the build stage:
            // the MCDServerTest binary loads its card library from
            // Data/GameData/Cards/* at runtime, and on branches with the
            // MCD_DATA_DIR repoint (MCDClient bead mc-0xm) the GameServer
            // `cmake --install` also copies Data/GameData/ into each versioned
            // server dir. On branches where the GameData refactor hasn't
            // landed (e.g. the older release branch) the target may not exist;
            // the `|| echo ...` fallback keeps the pipeline backward-compatible.
            stage('Populate GameData') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh 'make export-done || echo "[Populate GameData] make target missing on this branch — skipping"'
                }
            }

            stage('Build GameServer, TestClient & Proxy') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        rm -rf bin/versions/v* bin/testclient-versions/v*
                        cd Src
                        chmod +x deploy.sh
                        ./deploy.sh --clean --release --build-number ${BUILD_NUMBER}
                    """
                }
            }

            stage('Build WASM Bots') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    // bots/*.wasm are the artifacts the proxy loads via
                    // --bot-dir. Post-MCDClient #1425 (Nix → Docker dev shell
                    // migration), `make wasm-bots` is the only variant — it
                    // shells out to scripts/docker_dev.py to build inside the
                    // mcd/wasm-build container, so the Rust/clang/lld/nodejs
                    // toolchain comes from the image instead of requiring
                    // the build agent host to ship it.
                    //
                    // catchError → UNSTABLE because the current build-agent
                    // docker doesn't accept `buildx build --load` (older CLI
                    // version), so the wasm-build image fails to build. The
                    // proxy can still ship — bots are loaded at runtime from
                    // bots/ on the deployed VM, which retains previous-build
                    // artifacts until a successful build replaces them. Tracked
                    // separately; remove the catch when the agent has a
                    // buildx-capable docker.
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        // script{} is required: `steps` is declarative and accepts
                        // steps only, so a bare try/catch fails to COMPILE the
                        // whole pipeline — "Expected a step @ line 210". That took
                        // MCDServer-Main and MCDServer-FeatureBackend down entirely
                        // from #82 until mc-lg8x. Do not unwrap it.
                        script {
                            try {
                                sh 'make wasm-bots'
                            } catch (hudson.AbortException e) {
                                // Name the cause here and rethrow, leaving catchError
                                // to do the marking exactly as before: this is the
                                // only point that knows the wasm image is what went
                                // soft, and post{ unstable } has no way to work it
                                // out afterwards (bead mjs-q4x). Narrowed to
                                // AbortException — what a non-zero sh throws — so an
                                // aborted or interrupted build is never mislabelled
                                // as a wasm failure.
                                mcdUnstableReason('WASM bot build failed (the proxy is shipping the bots from the previous build)')
                                throw e
                            }
                        }
                    }
                }
            }

            stage('Verify Build') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        env.SERVER_VERSION_PATH = readFile('bin/versions/latest.txt').trim()
                        env.TESTCLIENT_VERSION_PATH = readFile('bin/testclient-versions/latest.txt').trim()
                        // CMake wrote latest.txt from its own PROJECT_VERSION; the
                        // SERVER_VERSION derived at Checkout must be the same string
                        // or the two version sources have split again — fail here,
                        // loudly, not in a release-ordering query months later
                        // (mc-glpn).
                        if (env.SERVER_VERSION_PATH != "v${env.SERVER_VERSION}/MCDServer") {
                            error("mc-glpn: version drift — CMake produced '${env.SERVER_VERSION_PATH}' but the pipeline derived 'v${env.SERVER_VERSION}/MCDServer'")
                        }
                        sh "test -x 'bin/versions/${env.SERVER_VERSION_PATH}'"
                        sh "test -x 'bin/testclient-versions/${env.TESTCLIENT_VERSION_PATH}'"
                        sh "test -x 'bin/MCDProxy'"

                        env.SERVER_SIZE = sh(script: "du -h bin/versions/${env.SERVER_VERSION_PATH} | cut -f1", returnStdout: true).trim()
                        env.TESTCLIENT_SIZE = sh(script: "du -h bin/testclient-versions/${env.TESTCLIENT_VERSION_PATH} | cut -f1", returnStdout: true).trim()
                        env.PROXY_SIZE = sh(script: "du -h bin/MCDProxy | cut -f1", returnStdout: true).trim()

                        echo "Build verified: v${env.SERVER_VERSION} (Server: ${env.SERVER_SIZE}, TestClient: ${env.TESTCLIENT_SIZE}, Proxy: ${env.PROXY_SIZE})"
                    }
                }
            }

            // Validated Card Data Pipeline (MCDClient bead mc-8ko, plan §4):
            // validate the *generated* Data/GameData/ tree right after it is
            // populated and before any consumer (Unit Tests / packaging) runs.
            // Gated on config.validateGameData so it is inert for pipelines that
            // do not opt in (main/release stay unaffected while the corpus is
            // cleaned up — plan §7). config.validateGameDataHardFail flips it from
            // a soft (UNSTABLE) gate to a blocking one.
            stage('Validate GameData') {
                when {
                    expression { env.SERVER_CHANGED == 'true' && config.validateGameData }
                }
                steps {
                    mcdValidateGameData(hardFail: config.validateGameDataHardFail ?: false)
                }
            }

            // Hermetic build (MCDClient bead mc-0xm, plan §5, Scenario 3): with
            // the generated data exported and validated, relocate the AUTHORING
            // data (Data/Cards + Data/References) out of the workspace so every
            // stage below — Unit Tests, Integration Test, artifact staging —
            // proves nothing reads authoring data. Gated on
            // config.stripAuthoringData: a branch opts in once its tests read
            // generated Data/GameData/ only (features/card first). The build
            // finishes stripped; the next build's checkout restores the tree.
            stage('Strip Authoring Data') {
                when {
                    expression { env.SERVER_CHANGED == 'true' && config.stripAuthoringData }
                }
                steps {
                    mcdStripAuthoringData()
                }
            }

            stage('Unit Tests') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        cd Src/GameServer
                        ./build.sh --test --release
                    """
                }
            }

            stage('Proxy Unit Tests') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh 'make test-proxy'
                }
            }

            stage('Integration Test') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        def testResult = sh(script: '''
                            set +e

                            echo "Starting integration test..."

                            TEST_TCP_PORT=$((30000 + (BUILD_NUMBER % 10000)))
                            TEST_WS_PORT=$((40000 + (BUILD_NUMBER % 10000)))

                            # Get TestClient path from latest.txt
                            TESTCLIENT_PATH="bin/testclient-versions/$(cat bin/testclient-versions/latest.txt)"

                            # Logs live in the WORKSPACE, not /tmp, so the whole
                            # file can be archived as a build artifact. The 20-line
                            # tails below stay for quick console reading, but they
                            # are not enough to diagnose the intermittent timeout
                            # in mc-n37x: the proxy closes a player connection
                            # WELL BEFORE the end of the run, so the evidence has
                            # already scrolled out of a 20-line tail by the time
                            # the clients hit their 180s cap.
                            LOG_DIR=integration-logs
                            rm -rf "$LOG_DIR"
                            mkdir -p "$LOG_DIR"

                            cleanup() {
                                echo ""
                                echo "=== Proxy Log (last 20 lines) ==="
                                tail -20 "$LOG_DIR/proxy.log" 2>/dev/null || echo "(no proxy log)"
                                echo ""
                                echo "=== Client 1 Log (last 15 lines) ==="
                                tail -15 "$LOG_DIR/client1.log" 2>/dev/null || echo "(no client1 log)"
                                echo ""
                                echo "=== Client 2 Log (last 15 lines) ==="
                                tail -15 "$LOG_DIR/client2.log" 2>/dev/null || echo "(no client2 log)"

                                # mc-n37x signature scan. The suspected cause is
                                # Player.sendToPlayer in Src/Proxy/main.go: when a
                                # player's 128-deep send channel fills, the proxy
                                # closes that player's connection outright, which
                                # presents to the peer as Connection_PlayerDisconnected
                                # mid-match and then nobody reconnects. A starved
                                # client on a loaded shared agent is exactly how the
                                # channel fills. This scan turns the next failure into
                                # a confirmation or a refutation instead of a rerun.
                                echo ""
                                echo "=== Proxy disconnect scan (mc-n37x) ==="
                                if grep -n "send channel full" "$LOG_DIR/proxy.log" 2>/dev/null; then
                                    echo "^^ CONFIRMS the mc-n37x hypothesis: the proxy dropped a player"
                                    echo "   because its send channel filled, not because the client left."
                                else
                                    echo "(no 'send channel full' warning: mc-n37x hypothesis NOT confirmed by this run)"
                                fi
                                echo "--- other disconnect/timeout lines ---"
                                grep -niE "disconnect|reconnect|write error|timeout" "$LOG_DIR/proxy.log" 2>/dev/null | head -30 \
                                    || echo "(none)"

                                kill $PROXY_PID 2>/dev/null || true
                                kill $CLIENT1_PID $CLIENT2_PID 2>/dev/null || true
                            }
                            trap cleanup EXIT

                            ./bin/MCDProxy -port $TEST_TCP_PORT -wsport $TEST_WS_PORT > "$LOG_DIR/proxy.log" 2>&1 &
                            PROXY_PID=$!
                            echo "Test proxy started on TCP:$TEST_TCP_PORT, WS:$TEST_WS_PORT (PID: $PROXY_PID)"
                            sleep 3

                            if ! kill -0 $PROXY_PID 2>/dev/null; then
                                echo "ERROR: Proxy failed to start!"
                                exit 1
                            fi

                            $TESTCLIENT_PATH 127.0.0.1 $TEST_TCP_PORT TestBot1 0 --timeout=180 > "$LOG_DIR/client1.log" 2>&1 &
                            CLIENT1_PID=$!
                            sleep 1
                            $TESTCLIENT_PATH 127.0.0.1 $TEST_TCP_PORT TestBot2 1 --timeout=180 > "$LOG_DIR/client2.log" 2>&1 &
                            CLIENT2_PID=$!

                            echo "Test clients started (PIDs: $CLIENT1_PID, $CLIENT2_PID)"
                            echo "Waiting for game to complete (timeout: 180s)..."

                            wait $CLIENT1_PID
                            EXIT1=$?
                            wait $CLIENT2_PID
                            EXIT2=$?

                            echo ""
                            echo "=== Test Results ==="
                            echo "Client 1 exit code: $EXIT1"
                            echo "Client 2 exit code: $EXIT2"

                            if [ $EXIT1 -eq 0 ] && [ $EXIT2 -eq 0 ]; then
                                echo ""
                                echo "✓ Integration test PASSED - full game completed"
                                exit 0
                            else
                                echo ""
                                echo "✗ Integration test FAILED"
                                echo "  Client 1: exit code $EXIT1 (0=complete, 1=error, 2=timeout, 3=denied)"
                                echo "  Client 2: exit code $EXIT2"
                                exit 1
                            fi
                        ''', returnStatus: true)

                        if (testResult != 0) {
                            // Keep the FULL logs, not just the console tails.
                            // mc-n37x is intermittent (~1 run in 3) and its
                            // evidence is the proxy's own account of why it
                            // closed a player connection, which sits far above
                            // the tail. Retention here is the buildDiscarder's
                            // artifactDaysToKeepStr of 7 days, so a failure
                            // stays diagnosable for a week rather than being
                            // gone by the time anyone looks.
                            archiveArtifacts artifacts: 'integration-logs/*.log',
                                             allowEmptyArchive: true,
                                             fingerprint: false
                            error("Integration test failed")
                        }
                    }
                }
            }

            stage('MCP Game Server Tests') {
                // Hermetic Go test suite for the Claude-as-Player MCP harness.
                // Unit tests cover protocol codec, session/legal-actions, tools,
                // artifacts. Integration tests boot the MCP binary against an
                // in-process FakeProxy (one server / one match per ADR mc-4bi.1
                // §11.2) — no external Proxy/GameServer/AccountService stack.
                //
                // On failure the suite's dumpSessionLogOnFail helper prints
                // mcp_session.log via t.Logf, which `go test -v` captures into
                // mcp-test.log. That log is archived as the postmortem artifact
                // (logs/{gameUUID}/ live in t.TempDir() and are cleaned by the
                // test framework before this stage's post block runs).
                when {
                    allOf {
                        expression { env.MCP_GAME_SERVER_CHANGED == 'true' }
                        // Src/MCPGameServer/ doesn't exist on main yet (the
                        // module lives on a feature branch); guard so the
                        // stage no-ops on branches that don't ship the dir.
                        expression { fileExists('Src/MCPGameServer') }
                    }
                }
                steps {
                    sh '''#!/bin/bash
                        # -e is not optional here. A Jenkins sh step takes the
                        # exit status of the LAST command in the body, and this
                        # body has a shebang, so Jenkins runs it directly rather
                        # than through its default 'sh -xe'. Without -e the unit
                        # run below could fail and the stage still reported
                        # SUCCESS, because the integration run after it exited 0.
                        # That is how the protocol drift guard ran on every PR
                        # and had its verdict discarded (mc-91jj).
                        #
                        # -timeout is not optional either, and 4m is
                        # deliberately far under Go's 10m default. `go test
                        # ./...` buffers each package's output until that
                        # package finishes, so a hung test prints NOTHING.
                        # The stage simply goes quiet, Jenkins' durable-task
                        # wrapper eventually reports JENKINS-48300 ("wrapper
                        # script does not seem to be touching the log file"),
                        # and the build dies without ever naming the test that
                        # hung. Go's own timeout panics with a full goroutine
                        # dump identifying the stuck goroutine, so it has to
                        # fire FIRST: before the durable-task heartbeat window
                        # and before the 10m default. mc-11nh: MCD-PR-Main
                        # #1616, #1617, #1619, #1620, #1621 and #1622 each
                        # burned roughly 10 minutes on that silence and every
                        # one of them reported the JENKINS-48300 red herring
                        # instead of a test name. A healthy run of this stage
                        # takes about 53s (build #1633), so 4m is over 4x
                        # headroom.
                        set -euo pipefail
                        mkdir -p reports/mcp-game-server
                        cd Src/MCPGameServer
                        go test -v -timeout 4m ./... 2>&1 | tee ../../reports/mcp-game-server/unit.log
                        go test -v -timeout 4m -tags=integration ./integration_test/... 2>&1 | tee ../../reports/mcp-game-server/integration.log
                    '''
                }
                post {
                    failure {
                        archiveArtifacts artifacts: 'reports/mcp-game-server/**', allowEmptyArchive: true, fingerprint: true
                    }
                }
            }

            stage('Stage Artifacts') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        mkdir -p artifacts/server

                        cp bin/versions/${SERVER_VERSION_PATH} artifacts/server/MCDServer
                        cp bin/MCDProxy artifacts/server/
                        cp bin/testclient-versions/${TESTCLIENT_VERSION_PATH} artifacts/server/MCDTestClient
                        cp bin/versions/latest.txt artifacts/server/server-latest.txt
                        cp bin/testclient-versions/latest.txt artifacts/server/testclient-latest.txt

                        COMMIT_SHA_VAL="\${commit_sha:-manual}"
                        COMMIT_AUTHOR_VAL="\${commit_author:-Unknown}"
                        cat > artifacts/server/BUILD_INFO.txt << EOF
Build Number: ${BUILD_NUMBER}
Version: ${SERVER_VERSION}
Branch: ${BRANCH_NAME}
Environment: ${DEPLOY_ENV}
Server Host: ${SERVER_HOST}
Ports: TCP=${TCP_PORT}, WS=${WS_PORT}
Date: \$(date -Iseconds)
Commit: \$COMMIT_SHA_VAL
Author: \$COMMIT_AUTHOR_VAL
Build System: ${BUILD_ENV}
GCC Version: ${GCC_VERSION}
Go Version: ${GO_VERSION}
EOF

                        echo "" >> artifacts/server/BUILD_INFO.txt
                        echo "Binary Sizes:" >> artifacts/server/BUILD_INFO.txt
                        ls -lh artifacts/server/MCDServer artifacts/server/MCDProxy artifacts/server/MCDTestClient | awk '{print "  " \$9 ": " \$5}' >> artifacts/server/BUILD_INFO.txt
                    """
                }
            }

            // Same contract as the client pipeline's stage of this name: a
            // failed upload is reported, never swallowed, because a crash
            // without symbols tells us nothing but the signal number.
            stage('Upload Debug Symbols') {
                when {
                    expression { env.SERVER_CHANGED == 'true' && fileExists('bin/versions') }
                }
                steps {
                    script {
                        def sentryCliExists = sh(script: 'which sentry-cli', returnStatus: true) == 0
                        if (!sentryCliExists) {
                            env.SYMBOLS_UPLOADED = 'false'
                            echo "⚠️ sentry-cli is not installed on the build agent, so server debug symbols were NOT uploaded. Native crashes from this build cannot be symbolicated."
                            mcdUnstableReason('debug symbols not uploaded — sentry-cli is missing from the build agent')
                            currentBuild.result = 'UNSTABLE'
                            return
                        }
                        // `set +x` first: Jenkins runs sh with -x, which used to
                        // echo the Sentry auth token into the console log.
                        def status = sh(returnStatus: true, script: """
                            set +x
                            SENTRY_ENV=/var/opt/mechacorpsgames/Src/.env.sentry
                            if [ ! -f "\$SENTRY_ENV" ]; then
                                echo "Cannot authenticate to Sentry: \$SENTRY_ENV does not exist on this agent."
                                exit 1
                            fi
                            SENTRY_AUTH_TOKEN=\$(sed -n 's/^SENTRY_TOKEN=//p' "\$SENTRY_ENV" | tr -d '"' | tr -d '[:space:]')
                            if [ -z "\$SENTRY_AUTH_TOKEN" ]; then
                                echo "Cannot authenticate to Sentry: SENTRY_TOKEN is empty in \$SENTRY_ENV."
                                exit 1
                            fi
                            export SENTRY_AUTH_TOKEN
                            set -e

                            # Only symbol-bearing artifacts. The build trees also contain .o objects
                            # under CMakeFiles/ and CMake compiler-probe binaries, which carry no
                            # symbolication value, inflate every upload, and are what Sentry rejected
                            # with "an unknown error occurred" on MCDClient-Main #1109 and #1110.
                            SYMBOL_PATHS="bin/versions/"
                            for d in Src/GameServer/build/Release; do
                                if [ -d "\$d" ]; then
                                    SYMBOL_PATHS="\$SYMBOL_PATHS \$(find "\$d" -type f \\( -name '*.so' -o -name '*.dll' -o -name '*.pdb' \\) | tr '\\n' ' ')"
                                fi
                            done

                            echo "Uploading server debug symbols for all platforms..."
                            # --wait: block until Sentry has processed the files, so the
                            # verification below cannot race the upload.
                            #
                            # The upload runs WITHOUT -e on purpose. sentry-cli exits non-zero if ANY
                            # file fails, and a Sentry-side "still processing"/"unknown error" on one
                            # file is not evidence that the shipped library is missing. The
                            # verification below asks the only question that matters -- is THIS
                            # binary's build id registered in Sentry -- so it, not the uploader's
                            # exit code, decides the stage. Failures are still reported, never
                            # swallowed: a failed verification marks the build UNSTABLE below.
                            # Sentry can also fail to PROCESS a file it already accepted, and that
                            # failure is transient. MCDServer-Main #873 got "An unknown error occurred"
                            # on the shipped binary; #875, #876, #877 and #880 uploaded the same
                            # artifact cleanly with no pipeline change in between. A single-shot upload
                            # turns that hiccup into a permanent hole, because nothing ever re-uploads
                            # symbols for a version that has already deployed -- a native crash in
                            # v0.2.873 will symbolicate to nothing for as long as that build exists.
                            # So retry the pair, and let the verifier say when to stop.
                            SYMBOL_UPLOAD_ATTEMPTS=3
                            SYMBOL_RETRY_DELAY=30
                            SYMBOL_ATTEMPT=1
                            while : ; do
                                set +e
                                sentry-cli --url https://us.sentry.io debug-files upload \
                                    --org mechacorps-llc --project mcd-server \
                                    --include-sources --wait \
                                    \$SYMBOL_PATHS
                                UPLOAD_STATUS=\$?
                                set -e
                                if [ "\$UPLOAD_STATUS" -ne 0 ]; then
                                    echo "sentry-cli exited \$UPLOAD_STATUS on attempt \$SYMBOL_ATTEMPT of \$SYMBOL_UPLOAD_ATTEMPTS. Verifying what actually reached Sentry rather than assuming nothing did."
                                fi

                                if [ ! -f scripts/verify_sentry_symbols.py ]; then
                                    echo "Cannot verify the upload: scripts/verify_sentry_symbols.py is not on this branch. Merge MCDClient main into this branch."
                                    exit 1
                                fi
                                echo "Verifying the deployed binary is registered in Sentry..."
                                # The verifier, not sentry-cli's exit code, is the loop's condition. It is
                                # the only thing here that asks whether THIS binary can symbolicate.
                                set +e
                                python3 scripts/verify_sentry_symbols.py \
                                    --org mechacorps-llc --project mcd-server \
                                    --url https://us.sentry.io \
                                    bin/versions/${SERVER_VERSION_PATH}
                                VERIFY_STATUS=\$?
                                set -e
                                if [ "\$VERIFY_STATUS" -eq 0 ]; then
                                    break
                                fi
                                if [ "\$SYMBOL_ATTEMPT" -ge "\$SYMBOL_UPLOAD_ATTEMPTS" ]; then
                                    echo "Symbols are still not in Sentry after \$SYMBOL_UPLOAD_ATTEMPTS attempts, so this is not a transient processing error."
                                    exit \$VERIFY_STATUS
                                fi
                                echo "Attempt \$SYMBOL_ATTEMPT of \$SYMBOL_UPLOAD_ATTEMPTS did not put the build id in Sentry. Retrying in \$SYMBOL_RETRY_DELAY seconds."
                                sleep \$SYMBOL_RETRY_DELAY
                                SYMBOL_ATTEMPT=\$((SYMBOL_ATTEMPT + 1))
                            done
                        """)
                        env.SYMBOLS_UPLOADED = status == 0 ? 'true' : 'false'
                        if (status != 0) {
                            echo "⚠️ Server debug symbols are NOT in Sentry for this build. Native crashes will symbolicate to <unknown> frames until this is fixed."
                            mcdUnstableReason('debug symbols missing from Sentry (native crashes will not symbolicate)')
                            currentBuild.result = 'UNSTABLE'
                        } else {
                            echo "✅ Debug symbols uploaded and verified against Sentry."
                        }
                    }
                }
            }

            stage('Generate Server Manifest') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        def protocolVersion = sh(
                            script: "grep -oP 'PROTOCOL_VERSION\\s*=\\s*\\K[0-9]+' Src/Include/protocol_ext.h || echo '1'",
                            returnStdout: true
                        ).trim()

                        env.PROTOCOL_VERSION = protocolVersion

                        sh """
                            cat > artifacts/server/manifest.json << EOF
{
    "serverVersion": "${SERVER_VERSION}",
    "protocolVersion": ${PROTOCOL_VERSION},
    "buildNumber": ${BUILD_NUMBER},
    "branch": "${BRANCH_NAME}",
    "environment": "${DEPLOY_ENV}",
    "serverHost": "${SERVER_HOST}",
    "ports": {
        "tcp": ${TCP_PORT},
        "ws": ${WS_PORT}
    },
    "buildDate": "\$(date -Iseconds)",
    "commit": "\${commit_sha:-manual}"
}
EOF
                            echo "Generated server manifest.json:"
                            cat artifacts/server/manifest.json
                        """
                    }
                }
            }

            stage('Archive Artifacts') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    archiveArtifacts artifacts: 'artifacts/**/*', fingerprint: true
                }
            }

            stage('Deploy GameServer & TestClient') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        mkdir -p ${config.deployPath}/versions ${config.deployPath}/testclient-versions ${config.deployPath}/Data/GameData
                        rsync -rlvz --no-group bin/versions/ ${config.deployPath}/versions/
                        rsync -rlvz --no-group bin/testclient-versions/ ${config.deployPath}/testclient-versions/
                        # MCDServer auto-detects its data dir as <binary-parent-2>/Data/GameData/Cards;
                        # the deployed binary at versions/v.../MCDServer resolves to ${config.deployPath}/Data/GameData/Cards.
                        # Without the rsync the server falls back to the compile-time path (the Jenkins
                        # workspace) and crashes immediately on the first card-data load.
                        # Skip if Data/GameData/ wasn't produced (older branches without the GameData refactor).
                        if [ -d Data/GameData ]; then \
                          rsync -rlvz --no-group --delete Data/GameData/ ${config.deployPath}/Data/GameData/; \
                          echo "✓ Deployed Data/GameData to ${config.environment}"; \
                        else \
                          echo "ℹ Data/GameData/ not present in workspace — skipping (release-branch path)"; \
                        fi
                        echo "✓ Deployed GameServer to ${config.environment}: \$(cat ${config.deployPath}/versions/latest.txt)"
                        echo "✓ Deployed TestClient to ${config.environment}: \$(cat ${config.deployPath}/testclient-versions/latest.txt)"
                    """
                }
            }

            stage('Deploy Bots') {
                // Must land before the proxy container restart so docker-
                // compose mounts a populated ${deployPath}/bots:/app/bots.
                // --delete so an old .wasm removed from the repo doesn't
                // linger on the host and shadow the new manifest.
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        mkdir -p ${config.deployPath}/bots
                        rsync -rlvz --no-group --delete bots/ ${config.deployPath}/bots/
                        echo "✓ Deployed bots to ${config.environment}: \$(ls ${config.deployPath}/bots/*.wasm 2>/dev/null | wc -l) wasm bots"
                    """
                }
            }

            stage('Deploy Proxy (if changed)') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        def envFile = config.proxyEnvFile ?: '.env.proxy'
                        def composeProject = config.proxyProject ?: 'src'
                        def basePort = config.tcpPort - 69  // e.g., 42069 -> 42000
                        // Per-env CI-managed Godot project for practice-match bots.
                        // Populated by mcdClientPipeline's "Publish Bot Runtime"
                        // stage; the proxy mounts this path read-only.
                        def botProjectPath = config.botProjectPath ?: "${config.deployPath}/godot-bot-project"

                        // Generate proxy env file from pipeline config.
                        // Read Account Service API key from its env file so the proxy
                        // can fetch decks via the internal API.
                        def envSuffix = envFile.replace('.env.proxy.', '').replace('.env.proxy', 'main')
                        def accountEnvFile = ".env.account.${envSuffix}"
                        def accountApiKey = config.accountApiKey ?: ''
                        if (!accountApiKey) {
                            accountApiKey = sh(
                                script: "grep '^INTERNAL_API_KEY=' /var/opt/mechacorpsgames/Src/${accountEnvFile} 2>/dev/null | cut -d= -f2 || echo ''",
                                returnStdout: true
                            ).trim()
                        }
                        sh """
                            cat > /var/opt/mechacorpsgames/Src/${envFile} <<'ENVEOF'
# Auto-generated by mcdServerPipeline — do not edit manually
PROXY_TCP_PORT=${config.tcpPort}
PROXY_WS_PORT=${config.wsPort ?: config.tcpPort + 1}
PROXY_BASE_PORT=${basePort}
DEPLOY_PATH=${config.deployPath}
BOT_PROJECT_ROOT=${botProjectPath}
SERVER_SENTRY_DSN=${config.sentryDsn ?: ''}
CRASH_REPORT_URL=${config.crashReportUrl ?: "http://localhost:${basePort + 90}"}
AUTH_URL=${config.authUrl ?: "http://localhost:${basePort + 81}"}
AUTH_INTERNAL_KEY=${accountApiKey}
ACCOUNT_URL=${config.accountUrl ?: "http://localhost:${basePort + 82}"}
ACCOUNT_API_KEY=${accountApiKey}
CRASH_REPORTING_API_KEY=${config.crashReportKey ?: ''}
BOT_WS_PORT=${config.botWsPort ?: basePort + 71}
BOT_ACCOUNT=${config.botAccount ?: ''}
BOT_PASSWORD=${config.botPassword ?: ''}
# Test-only: set to "-gauntlet-instant-resolve" via the per-env Jenkinsfile to
# resolve gauntlet matches via 50/50 coin flip instead of spawning a real
# GameServer. Without this, the proxy spawns the C++ server and runs the full
# match — useful for prod, but the swarm test wants instant resolution. The
# var is consumed by docker-compose.proxy.yml via "\${GAUNTLET_INSTANT_RESOLVE:-}"
# so an empty value safely no-ops.
GAUNTLET_INSTANT_RESOLVE=${config.gauntletInstantResolve ?: ''}
ENVEOF
                        """
                        // Practice bots are spawned from BOT_PROJECT_ROOT, published by
                        // mcdClientPipeline's "Publish Bot Runtime" stage in a DIFFERENT
                        // job. Nothing ties the two together, so when an env's
                        // botProjectPath is wrong or that stage is skipped, the tree
                        // silently rots: the bot then loses the protocol handshake and
                        // every play-vs-bot match on the env fails, while this pipeline
                        // reports a clean deploy. GH #2188 — release-staging sat on the
                        // 2026-07-14 build (protocol v41) against a v43 server for 12
                        // days. Warn rather than fail: a stale bot runtime does not
                        // affect human-vs-human matches.
                        sh """
                            BOT_EXT="${botProjectPath}/bin/lib/Linux-x86_64/libMCDCoreExt-d.so"
                            if [ ! -f "\$BOT_EXT" ]; then
                                echo "⚠️ BOT RUNTIME MISSING: \$BOT_EXT — play-vs-bot will not work on ${config.environment}."
                                echo "   Check botProjectPath in this env's Jenkinsfile.client.* matches BOT_PROJECT_ROOT above."
                            else
                                bot_age=\$(stat -c %Y "\$BOT_EXT")
                                srv_age=\$(stat -c %Y "bin/MCDProxy" 2>/dev/null || echo 0)
                                skew=\$(( (srv_age - bot_age) / 86400 ))
                                if [ "\$skew" -gt 1 ]; then
                                    echo "⚠️ BOT RUNTIME STALE: \$BOT_EXT is \${skew} days older than this build."
                                    echo "   A protocol bump since then means practice bots are rejected at the handshake."
                                    echo "   Re-run this env's MCDClient job, or check botProjectPath vs BOT_PROJECT_ROOT."
                                else
                                    echo "✓ Bot runtime current (\$(stat -c %y "\$BOT_EXT"))"
                                fi
                            fi
                        """

                        def containerName = "${composeProject}-proxy-1"

                        // The commit the proxy image must be built from. Read from the
                        // workspace rather than env.GIT_COMMIT: this pipeline performs
                        // several checkouts (main + the target branch), so the env var
                        // does not reliably name the branch we just built.
                        def proxyCommit = sh(script: "git rev-parse HEAD", returnStdout: true).trim()

                        def newHash = sh(script: "sha256sum bin/MCDProxy | cut -d' ' -f1", returnStdout: true).trim()
                        def oldHash = sh(script: "sha256sum ${config.deployPath}/MCDProxy 2>/dev/null | cut -d' ' -f1 || echo 'none'", returnStdout: true).trim()
                        def binaryChanged = (newHash != oldHash)

                        // Also check if proxy Docker config changed (compose file, env file)
                        def configHash = sh(script: "cat Src/docker-compose.proxy.yml Src/${envFile} 2>/dev/null | sha256sum | cut -d' ' -f1", returnStdout: true).trim()
                        def oldConfigHash = sh(script: "cat /tmp/.mcd-proxy-config-hash-${composeProject} 2>/dev/null || echo 'none'", returnStdout: true).trim()
                        def configChanged = (configHash != oldConfigHash)

                        if (binaryChanged || configChanged) {
                            if (binaryChanged) {
                                echo "⚠️ Proxy binary changed - rebuilding Docker container for ${config.environment}"
                            } else {
                                echo "⚠️ Proxy config changed - recreating Docker container for ${config.environment}"
                            }
                            discordNotify.simple("🔄 ${config.environment.capitalize()} proxy container rebuild in progress", "16776960")

                            sh """
                                # Stop systemd proxy service and legacy containers holding our ports
                                sudo systemctl stop mcdproxy-release.service 2>/dev/null || true
                                sudo systemctl disable mcdproxy-release.service 2>/dev/null || true

                                # Kill any Docker container holding our target TCP or WS port (host networking)
                                for cid in \$(docker ps -q --filter 'network=host'); do
                                    cname=\$(docker inspect --format '{{.Name}}' "\$cid" | sed 's|^/||')
                                    if [ "\$cname" = "${containerName}" ]; then continue; fi
                                    cmd=\$(docker inspect --format '{{join .Config.Cmd " "}}' "\$cid" 2>/dev/null || true)
                                    if echo "\$cmd" | grep -qE '(^|\\s)${config.tcpPort}(\\s|\$)'; then
                                        echo "Removing container \$cname holding port ${config.tcpPort}"
                                        docker rm -f "\$cid" 2>/dev/null || true
                                    fi
                                done
                                docker rm -f ${containerName} 2>/dev/null || true

                                # Src/Proxy/Dockerfile COMPILES the proxy from its build
                                # context, and 'docker compose build' takes that context
                                # from the compose project dir (/var/opt/mechacorpsgames/Src)
                                # — NOT this build's workspace. Nothing has ever refreshed
                                # that tree: it sat at cc5fee0a/2026-05-01 while every build
                                # here reported "proxy container restarted successfully", so
                                # months of proxy commits were compiled out of a stale
                                # checkout and never shipped. The 'bin/MCDProxy' this
                                # pipeline builds, tests and hashes was only ever a
                                # change-detection marker; no image or mount consumed it.
                                #
                                # Build the image straight from the workspace and tag it
                                # exactly what compose would have produced, then bring the
                                # service up with --no-build so compose adopts that image
                                # instead of compiling its own. The project dir is left
                                # alone: it is shared with other services and owned by
                                # another user, so writing into it is both a cross-service
                                # side effect and a permission failure waiting to happen.
                                docker build \\
                                    --no-cache \\
                                    --build-arg GIT_COMMIT='${proxyCommit}' \\
                                    -f Src/Proxy/Dockerfile \\
                                    -t ${composeProject}-proxy:latest \\
                                    Src

                                # Compose DEFINITION from the workspace (so command args and
                                # mounts track the branch) but project dir unchanged (so
                                # container identity and relative paths do not move).
                                docker compose -p ${composeProject} \\
                                    --project-directory /var/opt/mechacorpsgames/Src \\
                                    -f "\$(pwd)/Src/docker-compose.proxy.yml" \\
                                    --env-file /var/opt/mechacorpsgames/Src/${envFile} \\
                                    up -d --force-recreate --no-build proxy

                                sleep 3
                                if docker ps --filter 'name=${containerName}' --format '{{.Status}}' | grep -q 'Up'; then
                                    # Provenance gate. The image stamps the commit it was
                                    # built from (Src/Proxy/Dockerfile LABEL). If the running
                                    # container does not carry the commit this build tested,
                                    # the image came from somewhere else — fail loudly here
                                    # rather than let another silent-stale-proxy month pass.
                                    want='${proxyCommit}'
                                    got=\$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' ${containerName} 2>/dev/null || echo '')
                                    if [ -n "\$want" ] && [ "\$got" != "\$want" ]; then
                                        echo "✗ Proxy image provenance mismatch for ${config.environment}"
                                        echo "  expected commit: \$want"
                                        echo "  running image:   \${got:-<unstamped>}"
                                        echo "  The proxy image was NOT built from this commit — see the build-context sync above."
                                        exit 1
                                    fi
                                    echo "✓ ${config.environment} proxy container restarted successfully (commit \${got:-<unstamped>})"

                                    # Marker LAST: it is the change-detection input for the
                                    # next build, so writing it before the container is
                                    # verified would make a failed deploy look up-to-date
                                    # and skip the retry forever.
                                    rm -f ${config.deployPath}/MCDProxy
                                    cp bin/MCDProxy ${config.deployPath}/MCDProxy
                                    chmod +x ${config.deployPath}/MCDProxy
                                    echo "${configHash}" > /tmp/.mcd-proxy-config-hash-${composeProject}
                                else
                                    echo "✗ Failed to start proxy container"
                                    docker logs ${containerName} --tail 20 2>&1 || true
                                    exit 1
                                fi
                            """
                            env.PROXY_DEPLOYED = "true"
                        } else {
                            sh """
                                if ! docker ps --filter 'name=${containerName}' --format '{{.Status}}' | grep -q 'Up'; then
                                    echo "Proxy container not running, starting..."

                                    # Stop systemd proxy service and legacy containers holding our ports
                                    sudo systemctl stop mcdproxy-release.service 2>/dev/null || true
                                    sudo systemctl disable mcdproxy-release.service 2>/dev/null || true

                                    # Kill any Docker container holding our target TCP or WS port (host networking)
                                    for cid in \$(docker ps -q --filter 'network=host'); do
                                        cname=\$(docker inspect --format '{{.Name}}' "\$cid" | sed 's|^/||')
                                        if [ "\$cname" = "${containerName}" ]; then continue; fi
                                        cmd=\$(docker inspect --format '{{join .Config.Cmd " "}}' "\$cid" 2>/dev/null || true)
                                        if echo "\$cmd" | grep -qE '(^|\\s)${config.tcpPort}(\\s|\$)'; then
                                            echo "Removing container \$cname holding port ${config.tcpPort}"
                                            docker rm -f "\$cid" 2>/dev/null || true
                                        fi
                                    done
                                    docker rm -f ${containerName} 2>/dev/null || true

                                    cd /var/opt/mechacorpsgames/Src
                                    docker compose -p ${composeProject} -f docker-compose.proxy.yml --env-file ${envFile} up -d proxy
                                else
                                    echo "✓ Proxy unchanged and container already running"
                                fi
                            """
                            env.PROXY_DEPLOYED = "false"
                        }
                    }
                }
            }

            stage('Cleanup Old Versions') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        cd ${config.deployPath}/versions
                        ls -dt v*/ 2>/dev/null | tail -n +6 | xargs -r rm -rf || true
                        cd ${config.deployPath}/testclient-versions
                        ls -dt v*/ 2>/dev/null | tail -n +6 | xargs -r rm -rf || true
                        echo "✓ Cleanup complete for ${config.environment}"
                    """
                }
            }
        }

        post {
            success {
                script {
                    if (env.SERVER_CHANGED != 'true') {
                        echo "No server changes detected — nothing deployed"
                        return
                    }

                    def proxyNote = (env.PROXY_DEPLOYED == "true") ? " + Proxy" : ""
                    discordNotify.success(
                        title: "MechaCorps Server Build",
                        message: "✅ Deployed Server v${env.SERVER_VERSION}${proxyNote} to ${config.environment}",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: config.environment,
                        branch: config.branch,
                        version: env.SERVER_VERSION ?: 'N/A',
                        serverHost: config.serverHost,
                        tcpPort: config.tcpPort,
                        wsPort: config.wsPort
                    )
                }
            }
            // Declarative `post` runs `success` only on SUCCESS, so an UNSTABLE
            // build would otherwise notify nobody.
            //
            // The message names the CAUSE, taken from env.UNSTABLE_REASON, which
            // mcdUnstableReason records at whichever site actually went soft —
            // the WASM bot build, the symbol upload, the card gate. It replaces
            // a bare "finished UNSTABLE", which told nobody what to go and fix
            // (bead mjs-q4x). Do not swap in a phase/stage name here: that
            // reports where the build got TO rather than what went wrong, which
            // is the mistake the client pipeline made.
            unstable {
                script {
                    if (env.SERVER_CHANGED != 'true') {
                        return
                    }
                    // Every cause recorded during this build, "; "-joined, so a
                    // run that trips both the WASM gate and the symbol upload
                    // reports both. The old if/else overwrote one with the other.
                    def reason = env.UNSTABLE_REASON?.trim()
                    def envName = config.environment.capitalize()
                    def detail = reason
                        ? "⚠️ ${envName} finished with ${reason}"
                        : "⚠️ ${envName} finished with warnings — see the console log"
                    discordNotify.unstable(
                        title: "MechaCorps Server Build",
                        message: detail,
                        reason: reason,
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: config.environment,
                        branch: config.branch
                    )
                }
            }
            failure {
                script {
                    discordNotify.failure(
                        title: "MechaCorps Server Build",
                        message: "❌ ${config.environment.capitalize()} failed",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: config.environment,
                        branch: config.branch
                    )
                }
            }
        }
    }
}

return this
