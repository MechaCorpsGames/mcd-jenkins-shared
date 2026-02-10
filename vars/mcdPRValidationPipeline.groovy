// MechaCorps PR Validation Pipeline - Shared Library
// Validates pull requests before merge via build + test (no deploy).
//
// Two tiers:
//   main PRs    — GameServer build + unit/integration tests + GDScript tests
//   release PRs — All of the above + MCDCoreExt multi-platform builds
//
// Reports GitHub commit status so results appear on the PR checks tab.

def call(Map config) {
    // Required config:
    //   targetBranch:  'main' or 'release'
    //   webhookToken:  'mcd-pr-main' or 'mcd-pr-release'
    //   jobName:       'MCD-PR-Main' or 'MCD-PR-Release'

    def statusContext = (config.targetBranch == 'release')
        ? 'jenkins/pr-release-validation'
        : 'jenkins/pr-validation'

    pipeline {
        agent any

        options {
            buildDiscarder(logRotator(numToKeepStr: '20'))
            timeout(time: 45, unit: 'MINUTES')
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            GITHUB_STATUS_TOKEN = credentials('github-status-token')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            TARGET_BRANCH = "${config.targetBranch}"
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'action', value: '$.action'],
                    [key: 'pr_number', value: '$.pull_request.number'],
                    [key: 'pr_head_sha', value: '$.pull_request.head.sha'],
                    [key: 'pr_head_ref', value: '$.pull_request.head.ref'],
                    [key: 'pr_base_ref', value: '$.pull_request.base.ref'],
                    [key: 'pr_title', value: '$.pull_request.title'],
                    [key: 'pr_author', value: '$.pull_request.user.login'],
                    [key: 'repo_full_name', value: '$.repository.full_name']
                ],
                causeString: "PR #\$pr_number (\$pr_head_ref → ${config.targetBranch})",
                token: config.webhookToken,
                tokenCredentialId: '',
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
                regexpFilterText: '$action $pr_base_ref',
                regexpFilterExpression: "(opened|synchronize|reopened) ${config.targetBranch}"
            )
        }

        stages {
            stage('Setup PR Info') {
                steps {
                    script {
                        if (!env.pr_number || !env.pr_head_sha) {
                            error("Missing PR webhook variables. Ensure this job is triggered by a pull_request event.")
                        }

                        def shortSha = env.pr_head_sha.take(7)
                        currentBuild.displayName = "#${BUILD_NUMBER} PR-${env.pr_number} (${shortSha})"
                        currentBuild.description = "${env.pr_title}\n${env.pr_head_ref} → ${config.targetBranch} by ${env.pr_author}"

                        echo "PR #${env.pr_number}: ${env.pr_title}"
                        echo "Branch: ${env.pr_head_ref} → ${config.targetBranch}"
                        echo "Author: ${env.pr_author}"
                        echo "Head SHA: ${env.pr_head_sha}"

                        // Set pending status on GitHub
                        setGitHubStatus('pending', 'Validation started', statusContext)
                    }
                }
            }

            stage('Checkout PR Merge Ref') {
                steps {
                    checkout scm
                    script {
                        def fetchResult = sh(
                            script: "git fetch origin +refs/pull/${env.pr_number}/merge:refs/remotes/origin/pr/${env.pr_number}/merge",
                            returnStatus: true
                        )
                        if (fetchResult != 0) {
                            setGitHubStatus('failure', 'Merge conflict — cannot merge cleanly', statusContext)
                            error("Failed to fetch PR merge ref. The PR likely has merge conflicts with ${config.targetBranch}.")
                        }
                        sh "git checkout refs/remotes/origin/pr/${env.pr_number}/merge"
                        echo "Checked out PR #${env.pr_number} merge ref (merged into ${config.targetBranch})"
                    }
                }
            }

            stage('Setup Dependencies') {
                steps {
                    sh 'chmod +x scripts/setup-deps.sh && ./scripts/setup-deps.sh'
                }
            }

            stage('GDScript Tests') {
                steps {
                    sh """
                        rm -rf reports/
                        echo "Importing Godot project resources..."
                        godot --headless --import 2>/dev/null || true
                        echo "Running GdUnit4 GDScript tests..."
                        godot --headless -s addons/gdUnit4/bin/GdUnitCmdTool.gd -a res://tests -c --ignoreHeadlessMode
                    """
                }
                post {
                    always {
                        junit allowEmptyResults: true, skipPublishingChecks: true, testResults: 'reports/**/results.xml'
                    }
                }
            }

            stage('Build GameServer, TestClient & Proxy') {
                steps {
                    sh """
                        rm -rf bin/versions/v* bin/testclient-versions/v*
                        cd Src
                        chmod +x deploy.sh
                        ./deploy.sh --clean --release --build-number ${BUILD_NUMBER}
                    """
                }
            }

            stage('Verify Server Build') {
                steps {
                    script {
                        env.SERVER_VERSION_PATH = readFile('bin/versions/latest.txt').trim()
                        env.TESTCLIENT_VERSION_PATH = readFile('bin/testclient-versions/latest.txt').trim()
                        sh "test -x 'bin/versions/${env.SERVER_VERSION_PATH}'"
                        sh "test -x 'bin/testclient-versions/${env.TESTCLIENT_VERSION_PATH}'"
                        sh "test -x 'bin/MCDProxy'"
                        echo "Server build verified"
                    }
                }
            }

            stage('Unit Tests') {
                steps {
                    sh """
                        cd Src/GameServer
                        ./build.sh --test --release
                    """
                }
            }

            stage('Integration Test') {
                steps {
                    script {
                        def testResult = sh(script: '''
                            set +e

                            echo "Starting integration test..."

                            TEST_TCP_PORT=$((30000 + (BUILD_NUMBER % 10000)))
                            TEST_WS_PORT=$((40000 + (BUILD_NUMBER % 10000)))

                            TESTCLIENT_PATH="bin/testclient-versions/$(cat bin/testclient-versions/latest.txt)"

                            cleanup() {
                                echo ""
                                echo "=== Proxy Log (last 20 lines) ==="
                                tail -20 /tmp/test_proxy_${BUILD_NUMBER}.log 2>/dev/null || echo "(no proxy log)"
                                echo ""
                                echo "=== Client 1 Log (last 15 lines) ==="
                                tail -15 /tmp/test_client1_${BUILD_NUMBER}.log 2>/dev/null || echo "(no client1 log)"
                                echo ""
                                echo "=== Client 2 Log (last 15 lines) ==="
                                tail -15 /tmp/test_client2_${BUILD_NUMBER}.log 2>/dev/null || echo "(no client2 log)"

                                kill $PROXY_PID 2>/dev/null || true
                                kill $CLIENT1_PID $CLIENT2_PID 2>/dev/null || true
                            }
                            trap cleanup EXIT

                            ./bin/MCDProxy -port $TEST_TCP_PORT -wsport $TEST_WS_PORT > /tmp/test_proxy_${BUILD_NUMBER}.log 2>&1 &
                            PROXY_PID=$!
                            echo "Test proxy started on TCP:$TEST_TCP_PORT, WS:$TEST_WS_PORT (PID: $PROXY_PID)"
                            sleep 3

                            if ! kill -0 $PROXY_PID 2>/dev/null; then
                                echo "ERROR: Proxy failed to start!"
                                exit 1
                            fi

                            $TESTCLIENT_PATH 127.0.0.1 $TEST_TCP_PORT TestBot1 0 --timeout=180 > /tmp/test_client1_${BUILD_NUMBER}.log 2>&1 &
                            CLIENT1_PID=$!
                            sleep 1
                            $TESTCLIENT_PATH 127.0.0.1 $TEST_TCP_PORT TestBot2 1 --timeout=180 > /tmp/test_client2_${BUILD_NUMBER}.log 2>&1 &
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
                            error("Integration test failed")
                        }
                    }
                }
            }

            // Release PRs: full multi-platform MCDCoreExt build
            stage('Build MCDCoreExt (Linux)') {
                when { expression { config.targetBranch == 'release' } }
                stages {
                    stage('MCDCoreExt Linux Debug') {
                        steps {
                            sh """
                                cd Src/MCDCoreExt
                                chmod +x build.sh
                                ./build.sh --clean --configure --build --install --debug
                            """
                        }
                    }
                    stage('MCDCoreExt Linux Release') {
                        steps {
                            sh """
                                cd Src/MCDCoreExt
                                ./build.sh --clean --configure --build --install --release
                            """
                        }
                    }
                }
            }

            stage('Build MCDCoreExt (Windows Cross-compile)') {
                when { expression { config.targetBranch == 'release' } }
                stages {
                    stage('Setup MinGW OpenSSL') {
                        steps {
                            sh """
                                OPENSSL_DIR=Src/External/mingw-openssl
                                if [ ! -d "\${OPENSSL_DIR}/mingw64/include/openssl" ]; then
                                    echo "Downloading MinGW OpenSSL..."
                                    mkdir -p \${OPENSSL_DIR}
                                    cd \${OPENSSL_DIR}

                                    curl -L -o openssl.tar.zst "https://mirror.msys2.org/mingw/mingw64/mingw-w64-x86_64-openssl-3.4.1-1-any.pkg.tar.zst"

                                    zstd -d openssl.tar.zst
                                    tar xf openssl.tar
                                    rm -f openssl.tar openssl.tar.zst

                                    echo "MinGW OpenSSL downloaded and extracted"
                                    ls -la mingw64/lib/*.a | head -5
                                else
                                    echo "MinGW OpenSSL already present"
                                fi

                                MINGW_LIB=/usr/x86_64-w64-mingw32/lib
                                if [ -f "\${MINGW_LIB}/libcrypt32.a" ] && [ ! -f "\${MINGW_LIB}/libCrypt32.a" ]; then
                                    echo "Creating Crypt32 symlink workaround..."
                                    sudo ln -sf libcrypt32.a \${MINGW_LIB}/libCrypt32.a || true
                                fi
                            """
                        }
                    }
                    stage('MCDCoreExt Windows Debug') {
                        steps {
                            sh """
                                cd Src/MCDCoreExt
                                ./build.sh --clean --configure --build --install --debug --windows
                            """
                        }
                    }
                    stage('MCDCoreExt Windows Release') {
                        steps {
                            sh """
                                cd Src/MCDCoreExt
                                ./build.sh --clean --configure --build --install --release --windows
                            """
                        }
                    }
                }
            }

            stage('Build MCDCoreExt (Android Cross-compile)') {
                when { expression { config.targetBranch == 'release' } }
                stages {
                    stage('MCDCoreExt Android arm64-v8a Debug') {
                        steps {
                            sh """
                                cd Src/MCDCoreExt
                                ./build.sh --clean --configure --build --install --debug --android arm64-v8a
                            """
                        }
                    }
                    stage('MCDCoreExt Android arm64-v8a Release') {
                        steps {
                            sh """
                                cd Src/MCDCoreExt
                                ./build.sh --clean --configure --build --install --release --android arm64-v8a
                            """
                        }
                    }
                }
            }

            stage('Verify All Platform Builds') {
                when { expression { config.targetBranch == 'release' } }
                steps {
                    sh """
                        echo "=== Linux Builds ==="
                        test -f bin/lib/Linux-x86_64/libMCDCoreExt-d.so
                        echo "✓ Linux debug build"
                        test -f bin/lib/Linux-x86_64/libMCDCoreExt.so
                        echo "✓ Linux release build"

                        echo ""
                        echo "=== Windows Builds ==="
                        test -f bin/lib/Windows-x86_64/MCDCoreExt-d.dll
                        echo "✓ Windows debug build"
                        test -f bin/lib/Windows-x86_64/MCDCoreExt.dll
                        echo "✓ Windows release build"

                        echo ""
                        echo "=== Android Builds ==="
                        test -f bin/lib/Android-arm64-v8a/libMCDCoreExt-d.so
                        echo "✓ Android arm64-v8a debug build"
                        test -f bin/lib/Android-arm64-v8a/libMCDCoreExt.so
                        echo "✓ Android arm64-v8a release build"

                        echo ""
                        echo "All platform builds verified successfully!"
                    """
                }
            }
        }

        post {
            success {
                script {
                    def duration = currentBuild.durationString.replace(' and counting', '')
                    def tier = (config.targetBranch == 'release') ? 'Full validation' : 'Validation'
                    setGitHubStatus('success', "${tier} passed (${duration})", statusContext)

                    discordNotify.simple(
                        "✅ PR #${env.pr_number} ${tier} passed (${duration}) — ${env.pr_head_ref} → ${config.targetBranch}",
                        "3066993"
                    )
                }
            }
            failure {
                script {
                    def duration = currentBuild.durationString.replace(' and counting', '')
                    def tier = (config.targetBranch == 'release') ? 'Full validation' : 'Validation'
                    def failedStage = env.STAGE_NAME ?: 'unknown'
                    setGitHubStatus('failure', "${tier} failed at ${failedStage}", statusContext)

                    def buildUrl = "${env.JENKINS_URL_BASE}/job/${config.jobName}/${BUILD_NUMBER}/console"
                    discordNotify.simple(
                        "❌ PR #${env.pr_number} ${tier} failed at ${failedStage} — ${env.pr_head_ref} → ${config.targetBranch}\n[View Console](${buildUrl})",
                        "15158332"
                    )
                }
            }
            aborted {
                script {
                    setGitHubStatus('error', 'Build was aborted', statusContext)
                }
            }
        }
    }
}

/**
 * Set GitHub commit status on the PR head SHA.
 * Uses the GitHub Status API via curl as a reliable fallback.
 */
def setGitHubStatus(String state, String description, String context) {
    def buildUrl = "${env.JENKINS_URL_BASE}/job/${env.JOB_NAME}/${BUILD_NUMBER}/"
    def truncDesc = description.take(140)

    sh """
        curl -s -X POST \
            -H "Authorization: token \$GITHUB_STATUS_TOKEN" \
            -H "Accept: application/vnd.github.v3+json" \
            "https://api.github.com/repos/MechaCorpsGames/MCDClient/statuses/${env.pr_head_sha}" \
            -d '{
                "state": "${state}",
                "target_url": "${buildUrl}",
                "description": "${truncDesc}",
                "context": "${context}"
            }' || echo "Warning: Failed to set GitHub status"
    """
}

return this
