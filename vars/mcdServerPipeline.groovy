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
                args '-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /var/lib/jenkins/.android:/var/lib/jenkins/.android:ro -v /opt/mechacorps:/opt/mechacorps -v /var/opt/mechacorpsgames/Src:/var/opt/mechacorpsgames/Src --network host --group-add 111 --group-add 995'
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
                    [key: 'before_sha', value: '$.before'],
                    [key: 'files_added', value: '$.commits[*].added[*]'],
                    [key: 'files_modified', value: '$.commits[*].modified[*]'],
                    [key: 'files_removed', value: '$.commits[*].removed[*]']
                ],
                causeString: "Triggered by push to ${config.branch}",
                token: config.webhookToken,
                tokenCredentialId: '',
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
                // Filter: only trigger when the push is to our branch AND touches server-relevant paths
                // Paths: GameServer, Proxy, TestClient (server-only), Include/External/Data (shared),
                //        Shared (Go services), Validation (unknown→both), deploy/go.work/docker-compose.proxy,
                //        flake.nix/lock, Jenkinsfile.server (pipeline itself)
                regexpFilterText: '$ref $files_added $files_modified $files_removed',
                regexpFilterExpression: "refs/heads/${config.branch}[\\s\\S]*(Src/(GameServer|Proxy|TestClient|Include|External|Shared|Validation)/|Data/|Src/(deploy|go\\.work|docker-compose\\.proxy)|\\.Jenkins/Jenkinsfile\\.server|flake\\.|scripts/dev-pg)"
            )
        }

        stages {
            stage('Setup Build Info') {
                steps {
                    script {
                        env.SERVER_VERSION = "0.1.${BUILD_NUMBER}"

                        def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
                        currentBuild.displayName = "#${BUILD_NUMBER} v${env.SERVER_VERSION} (${shortSha})"

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
                        echo "Server Version: ${env.SERVER_VERSION}"
                        echo "Environment: ${config.environment}"
                        echo "Deploy path: ${config.deployPath}"
                        echo "Ports: TCP=${config.tcpPort}, WS=${config.wsPort}"
                    }
                }
            }

            stage('Checkout') {
                steps {
                    checkout scm
                }
            }

            stage('Detect Changes') {
                steps {
                    script {
                        def baseRef = env.before_sha
                        if (!baseRef || baseRef.startsWith('0000000')) {
                            echo "No valid before SHA — building everything"
                            env.SERVER_CHANGED = 'true'
                            env.ECONOMY_TESTS_NEEDED = 'true'
                        } else {
                            // Ensure the before SHA is available locally
                            sh "git fetch origin ${baseRef} 2>/dev/null || true"
                            def changes = mcdChangeDetection.detect(baseRef)
                            env.SERVER_CHANGED = changes.serverChanged.toString()
                            // Economy tests cover Auth, AccountService, AuctionHouse
                            env.ECONOMY_TESTS_NEEDED = (changes.serverChanged || changes.authChanged || changes.accountServiceChanged || changes.auctionHouseChanged).toString()
                        }

                        if (env.SERVER_CHANGED != 'true' && env.ECONOMY_TESTS_NEEDED != 'true') {
                            currentBuild.description += "\n⏭️ No server changes — skipped"
                            currentBuild.result = 'NOT_BUILT'
                        }
                    }
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

            stage('Verify Build') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        env.SERVER_VERSION_PATH = readFile('bin/versions/latest.txt').trim()
                        env.TESTCLIENT_VERSION_PATH = readFile('bin/testclient-versions/latest.txt').trim()
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

            stage('Unit Tests') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        cd Src/GameServer
                        ./build.sh --test --release
                    """
                }
            }

            stage('Economy Service Tests') {
                when { expression { env.ECONOMY_TESTS_NEEDED == 'true' } }
                steps {
                    sh '''
                        unset GOROOT
                        nix develop . --command bash -c '
                            dev-pg.sh init && dev-pg.sh start || exit 1
                            trap "dev-pg.sh stop" EXIT
                            cd Src/Auth && go test ./... &&
                            cd ../AccountService && go test ./... &&
                            cd ../AuctionHouse && go test ./...
                        '
                    '''
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

            stage('Upload Debug Symbols') {
                when {
                    expression { env.SERVER_CHANGED == 'true' && fileExists('bin/versions') }
                }
                steps {
                    script {
                        def sentryCliExists = sh(script: 'which sentry-cli', returnStatus: true) == 0
                        if (sentryCliExists) {
                            sh """
                                export SENTRY_AUTH_TOKEN=\$(grep SENTRY_TOKEN /var/opt/mechacorpsgames/Src/.env.sentry | cut -d= -f2)
                                sentry-cli --url https://us.sentry.io \
                                    upload-dif --org mechacorps-llc --project mcd-server \
                                    bin/versions/ \
                                    Src/GameServer/build/ \
                                    || echo "⚠️ Symbol upload failed (non-fatal)"
                            """
                        } else {
                            echo "sentry-cli not installed, skipping symbol upload"
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
                        mkdir -p ${config.deployPath}/versions ${config.deployPath}/testclient-versions
                        rsync -rlvz --no-group bin/versions/ ${config.deployPath}/versions/
                        rsync -rlvz --no-group bin/testclient-versions/ ${config.deployPath}/testclient-versions/
                        echo "✓ Deployed GameServer to ${config.environment}: \$(cat ${config.deployPath}/versions/latest.txt)"
                        echo "✓ Deployed TestClient to ${config.environment}: \$(cat ${config.deployPath}/testclient-versions/latest.txt)"
                    """
                }
            }

            stage('Deploy Proxy (if changed)') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        def envFile = config.proxyEnvFile ?: '.env.proxy'
                        def composeProject = config.proxyProject ?: 'src'
                        def containerName = "${composeProject}_proxy_1"

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

                                if [ "${binaryChanged}" = "true" ]; then
                                    rm -f ${config.deployPath}/MCDProxy
                                    cp bin/MCDProxy ${config.deployPath}/MCDProxy
                                    chmod +x ${config.deployPath}/MCDProxy
                                fi

                                cd /var/opt/mechacorpsgames/Src
                                docker-compose -p ${composeProject} -f docker-compose.proxy.yml --env-file ${envFile} build --no-cache proxy
                                docker-compose -p ${composeProject} -f docker-compose.proxy.yml --env-file ${envFile} up -d --force-recreate proxy

                                sleep 3
                                if docker ps --filter 'name=${containerName}' --format '{{.Status}}' | grep -q 'Up'; then
                                    echo "✓ ${config.environment} proxy container restarted successfully"
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
                                    docker-compose -p ${composeProject} -f docker-compose.proxy.yml --env-file ${envFile} up -d proxy
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
