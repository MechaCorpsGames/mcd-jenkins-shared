// MechaCorps Services Pipeline - Shared Library (main branch only)
// Deploys shared infrastructure: CrashReporting+MCP, Wiki sync, Monitoring
// Each service is independently catchError-wrapped so one failure
// marks the build UNSTABLE without blocking other deploys.

def call(Map config) {
    // Required config:
    //   branch: 'main'
    //   webhookToken: 'mcd-crash-reporting'
    //   jobName: 'MCDServices-Main'

    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                args '-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /opt/mechacorps:/opt/mechacorps -v /var/opt/mechacorpsgames/Src:/var/opt/mechacorpsgames/Src -v /nix:/nix:rw --network host --group-add 111 --group-add 995'
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '10'))
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            BRANCH_NAME = "${config.branch}"
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'ref', value: '$.ref'],
                    [key: 'commit_sha', value: '$.after'],
                    [key: 'commit_message', value: '$.head_commit.message'],
                    [key: 'commit_author', value: '$.head_commit.author.name'],
                    [key: 'pusher_name', value: '$.pusher.name'],
                    [key: 'before_sha', value: '$.before']
                ],
                causeString: "Triggered by push to ${config.branch}",
                token: config.webhookToken,
                tokenCredentialId: '',
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
                regexpFilterText: '$ref',
                regexpFilterExpression: "refs/heads/${config.branch}"
            )
        }

        stages {
            stage('Setup') {
                steps {
                    script {
                        env.SVC_VERSION = "0.1.${BUILD_NUMBER}"
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

                        env.BUILD_GITHUB_USER = env.pusher_name ?: ''
                        if (!env.BUILD_GITHUB_USER && author != 'Unknown') {
                            def buildCause2 = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
                            if (buildCause2 && buildCause2.size() > 0) {
                                env.BUILD_GITHUB_USER = buildCause2[0].userId ?: ''
                            }
                        }

                        currentBuild.description = "${commitMsg}\nby ${author}"
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
                            echo "No valid before SHA — deploying everything"
                            env.CRASH_REPORTING_CHANGED = 'true'
                            env.WIKI_CHANGED = 'true'
                            env.MONITORING_CHANGED = 'true'
                        } else {
                            sh "git fetch origin ${baseRef} 2>/dev/null || true"
                            def changes = mcdChangeDetection.detect(baseRef)
                            env.CRASH_REPORTING_CHANGED = changes.crashReportingChanged.toString()
                            env.WIKI_CHANGED = changes.wikiChanged.toString()
                            env.MONITORING_CHANGED = changes.monitoringChanged.toString()
                        }

                        def anyWork = (env.CRASH_REPORTING_CHANGED == 'true' ||
                                       env.WIKI_CHANGED == 'true' ||
                                       env.MONITORING_CHANGED == 'true')
                        if (!anyWork) {
                            currentBuild.description += "\n⏭️ No service changes — skipped"
                            currentBuild.result = 'NOT_BUILT'
                        }
                    }
                }
            }

            // ================================================================
            // Each service stage is catchError-wrapped: one failure marks
            // the build UNSTABLE but does NOT block other services.
            // ================================================================

            stage('Deploy CrashReporting + MCP') {
                when { expression { env.CRASH_REPORTING_CHANGED == 'true' } }
                steps {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        script {
                            echo "CrashReporting/MCP changed — building and deploying"

                            sh """
                                cd Src/CrashReporting
                                CGO_ENABLED=0 GOOS=linux GOWORK=off go build -o crash-reporting .
                                echo "✓ CrashReporting binary built"

                                cd ../MCPServer
                                CGO_ENABLED=0 GOOS=linux GOWORK=off go build -o mcp-server .
                                echo "✓ MCPServer binary built"

                                cd /var/opt/mechacorpsgames/Src
                                docker-compose -p src -f docker-compose.crash-reporting.yml --env-file .env.crash-reporting up -d --build --force-recreate crash-reporting mcp-server
                                sleep 5

                                PASS=true
                                for SVC in "Log Bundler:8090" "MCP Server:8095"; do
                                    NAME="\${SVC%%:*}"
                                    PORT="\${SVC##*:}"
                                    OK=false
                                    for i in \$(seq 1 10); do
                                        RESULT=\$(curl -s -o /dev/null -w '%{http_code}' http://localhost:\$PORT/health)
                                        if [ "\$RESULT" = "200" ]; then
                                            echo "✓ \$NAME health check passed"
                                            OK=true
                                            break
                                        fi
                                        echo "Waiting for \$NAME... (attempt \$i/10)"
                                        sleep 3
                                    done
                                    if [ "\$OK" = "false" ]; then
                                        echo "✗ \$NAME health check failed"
                                        PASS=false
                                    fi
                                done
                                if [ "\$PASS" = "false" ]; then
                                    cd /var/opt/mechacorpsgames/Src
                                    docker-compose -f docker-compose.crash-reporting.yml --env-file .env.crash-reporting -p src logs --tail=50 crash-reporting mcp-server
                                    exit 1
                                fi
                            """
                            env.CRASH_REPORTING_DEPLOYED = "true"
                        }
                    }
                }
            }

            stage('Sync Wiki') {
                when { expression { env.WIKI_CHANGED == 'true' } }
                steps {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        script {
                            echo "Wiki content changed — syncing to Wiki.js"
                            withCredentials([
                                usernamePassword(credentialsId: 'wiki-credentials',
                                                 usernameVariable: 'WIKI_EMAIL',
                                                 passwordVariable: 'WIKI_PASSWORD')
                            ]) {
                                sh """
                                    export WIKI_URL=http://localhost:8070
                                    cd /var/opt/mechacorpsgames
                                    python3 Src/Wiki/load_wiki_pages.py
                                """
                            }
                            env.WIKI_SYNCED = "true"
                        }
                    }
                }
            }

            stage('Deploy Monitoring') {
                when { expression { env.MONITORING_CHANGED == 'true' } }
                steps {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        script {
                            echo "Monitoring config changed — redeploying stack"

                            sh """
                                cd /var/opt/mechacorpsgames/Src/Monitoring
                                docker-compose -f docker-compose.monitoring.yml --env-file /var/opt/mechacorpsgames/Src/.env.monitoring up -d --force-recreate
                                sleep 5

                                OK=false
                                for i in \$(seq 1 10); do
                                    RESULT=\$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9090/-/ready)
                                    if [ "\$RESULT" = "200" ]; then
                                        echo "✓ Prometheus is ready"
                                        OK=true
                                        break
                                    fi
                                    sleep 3
                                done
                                if [ "\$OK" = "false" ]; then
                                    echo "✗ Prometheus health check failed"
                                    exit 1
                                fi

                                curl -s -X POST http://localhost:9090/-/reload || true
                                echo "✓ Monitoring stack redeployed"
                            """
                            env.MONITORING_DEPLOYED = "true"
                        }
                    }
                }
            }
        }

        post {
            success {
                script {
                    def deployNotes = []
                    if (env.CRASH_REPORTING_DEPLOYED == "true") deployNotes << "CrashReporting+MCP"
                    if (env.WIKI_SYNCED == "true") deployNotes << "Wiki"
                    if (env.MONITORING_DEPLOYED == "true") deployNotes << "Monitoring"

                    if (deployNotes.isEmpty()) {
                        echo "No service changes detected — nothing deployed"
                        return
                    }

                    discordNotify.success(
                        title: "MechaCorps Services Deploy",
                        message: "✅ Deployed: ${deployNotes.join(', ')}",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: "production",
                        branch: config.branch,
                        version: env.SVC_VERSION
                    )
                }
            }
            unstable {
                script {
                    def deployed = []
                    def failed = []
                    if (env.CRASH_REPORTING_DEPLOYED == "true") deployed << "CrashReporting+MCP"
                    else if (env.CRASH_REPORTING_CHANGED == 'true') failed << "CrashReporting+MCP"
                    if (env.WIKI_SYNCED == "true") deployed << "Wiki"
                    else if (env.WIKI_CHANGED == 'true') failed << "Wiki"
                    if (env.MONITORING_DEPLOYED == "true") deployed << "Monitoring"
                    else if (env.MONITORING_CHANGED == 'true') failed << "Monitoring"

                    def msg = "⚠️ Partial deploy"
                    if (deployed) msg += " — OK: ${deployed.join(', ')}"
                    if (failed) msg += " — FAILED: ${failed.join(', ')}"

                    discordNotify.failure(
                        title: "MechaCorps Services Deploy",
                        message: msg,
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: "production",
                        branch: config.branch
                    )
                }
            }
            failure {
                script {
                    discordNotify.failure(
                        title: "MechaCorps Services Deploy",
                        message: "❌ Deploy failed",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: "production",
                        branch: config.branch
                    )
                }
            }
        }
    }
}

return this
