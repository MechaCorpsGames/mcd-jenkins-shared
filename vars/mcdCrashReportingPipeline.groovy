// MechaCorps Crash Reporting Pipeline - Shared Library
// Builds and deploys: CrashReporting Go service (Log Bundler)
// Manages: docker-compose stack (Redis, GlitchTip, CrashReporting)

def call(Map config) {
    // Required config:
    //   branch: 'main'
    //   webhookToken: 'mcd-crash-reporting'
    //   jobName: 'MCDCrashReporting'

    pipeline {
        agent any

        options {
            buildDiscarder(logRotator(numToKeepStr: '10'))
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            BRANCH_NAME = "${config.branch}"
            COMPOSE_FILE = "Src/docker-compose.crash-reporting.yml"
            COMPOSE_PROJECT = "mcd-crash-reporting"
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'ref', value: '$.ref'],
                    [key: 'commit_sha', value: '$.after'],
                    [key: 'commit_message', value: '$.head_commit.message'],
                    [key: 'commit_author', value: '$.head_commit.author.name'],
                    [key: 'changed_files', value: '$.commits[*].["modified","added","removed"][*]']
                ],
                causeString: "Triggered by push to ${config.branch}",
                token: config.webhookToken,
                tokenCredentialId: '',
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
                regexpFilterText: '$ref $changed_files',
                regexpFilterExpression: "refs/heads/${config.branch}.*(Src/CrashReporting|Src/docker-compose\\.crash-reporting|Src/Shared).*"
            )
        }

        stages {
            stage('Setup') {
                steps {
                    script {
                        env.CR_VERSION = "0.1.${BUILD_NUMBER}"
                        def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
                        currentBuild.displayName = "#${BUILD_NUMBER} v${env.CR_VERSION} (${shortSha})"

                        def commitMsg = env.commit_message ? env.commit_message.split('\n')[0].take(60) : 'Manual build'
                        def author = env.commit_author ?: 'Unknown'
                        if (author == 'Unknown') {
                            def buildCause = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
                            if (buildCause && buildCause.size() > 0) {
                                author = buildCause[0].userName ?: buildCause[0].userId ?: 'Unknown'
                            }
                        }
                        env.BUILD_AUTHOR = author
                        currentBuild.description = "${commitMsg}\nby ${author}"
                    }
                }
            }

            stage('Checkout') {
                steps {
                    checkout scm
                }
            }

            stage('Build Go Binary') {
                steps {
                    sh """
                        cd Src/CrashReporting
                        CGO_ENABLED=0 GOOS=linux go build -o crash-reporting .
                        echo "✓ Go binary built"
                        ls -lh crash-reporting
                    """
                }
            }

            stage('Build Docker Image') {
                steps {
                    sh """
                        cd Src
                        docker-compose -f docker-compose.crash-reporting.yml -p ${COMPOSE_PROJECT} build crash-reporting
                        echo "✓ Docker image built"
                    """
                }
            }

            stage('Deploy') {
                steps {
                    sh """
                        cd Src
                        docker-compose -f docker-compose.crash-reporting.yml -p ${COMPOSE_PROJECT} up -d
                        echo "✓ Stack deployed"

                        # Wait for services to be healthy
                        sleep 5

                        echo "Service status:"
                        docker-compose -f docker-compose.crash-reporting.yml -p ${COMPOSE_PROJECT} ps
                    """
                }
            }

            stage('Health Check') {
                steps {
                    script {
                        def healthResult = sh(script: '''
                            set +e
                            for i in $(seq 1 10); do
                                RESULT=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8090/health)
                                if [ "$RESULT" = "200" ]; then
                                    echo "✓ Log Bundler health check passed"
                                    exit 0
                                fi
                                echo "Waiting for Log Bundler... (attempt $i/10)"
                                sleep 3
                            done
                            echo "✗ Log Bundler health check failed"
                            exit 1
                        ''', returnStatus: true)

                        if (healthResult != 0) {
                            sh "cd Src && docker-compose -f docker-compose.crash-reporting.yml -p ${COMPOSE_PROJECT} logs --tail=50 crash-reporting"
                            error("Health check failed")
                        }
                    }
                }
            }
        }

        post {
            success {
                script {
                    discordNotify.success(
                        title: "Crash Reporting Deploy",
                        message: "✅ Log Bundler v${env.CR_VERSION} deployed",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: "production",
                        branch: config.branch,
                        version: env.CR_VERSION
                    )
                }
            }
            failure {
                script {
                    discordNotify.failure(
                        title: "Crash Reporting Deploy",
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
