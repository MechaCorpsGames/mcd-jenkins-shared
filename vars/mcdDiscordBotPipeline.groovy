// MechaCorps Discord Bot Pipeline - Shared Library
// Builds and deploys the Jenkins/Discord slash-command bot
// (Src/Tools/discord-bot) as a systemd service on the host.
//
// The bot is a single Go binary — no docker-compose, no per-env split.
// Build happens in the mcd-build-agent container; the binary is
// installed into /opt/mechacorps (bind-mounted into the agent) and
// the mcd-discord-bot.service unit is restarted via host systemd.

def call(Map config) {
    // Required config:
    //   branch: 'main'
    //   webhookToken: 'mcd-discord-bot-main'
    //   jobName: 'MCDDiscordBot-Main'

    def botDir     = "Src/Tools/discord-bot"
    def binaryName = "mcd-discord-bot"
    def installDir = "/opt/mechacorps"
    def serviceName = "mcd-discord-bot"

    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                args '-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /opt/mechacorps:/opt/mechacorps --network host --group-add 111 --group-add 995 --group-add 1000'
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '10'))
            disableConcurrentBuilds()
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
                            echo "No valid before SHA — building bot"
                            env.DISCORD_BOT_CHANGED = 'true'
                        } else {
                            sh "git fetch origin ${baseRef} 2>/dev/null || true"
                            def changes = mcdChangeDetection.detect(baseRef)
                            env.DISCORD_BOT_CHANGED = changes.discordBotChanged.toString()
                        }

                        if (env.DISCORD_BOT_CHANGED != 'true') {
                            currentBuild.description += "\n⏭️ No discord-bot changes — skipped"
                            currentBuild.result = 'NOT_BUILT'
                        }
                    }
                }
            }

            stage('Build') {
                when { expression { env.DISCORD_BOT_CHANGED == 'true' } }
                steps {
                    sh """
                        cd ${botDir}
                        # go.mod is not in go.work, so a plain build is fine.
                        # Static linux build matches how the binary has
                        # historically been produced on the host.
                        CGO_ENABLED=0 GOOS=linux GOWORK=off go build -o ${binaryName} .
                        ls -l ${binaryName}
                    """
                }
            }

            stage('Deploy') {
                when { expression { env.DISCORD_BOT_CHANGED == 'true' } }
                steps {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        script {
                            echo "Installing ${binaryName} to ${installDir} and restarting ${serviceName}.service"

                            // systemctl is not available inside the build-agent
                            // container, so systemd operations shell out to the
                            // host via SSH to jenkins@localhost. That account has
                            // NOPASSWD sudoers rules (see /etc/sudoers.d/
                            // jenkins-discord-bot) limited to this unit.
                            def sshHost = "jenkins@localhost"
                            def sshOpts = "-o BatchMode=yes -o StrictHostKeyChecking=accept-new -i /var/lib/jenkins/.ssh/id_ed25519"

                            sh """
                                # install(1) handles atomic replace + mode. /opt/mechacorps
                                # is bind-mounted and writable by the jenkins user (group
                                # 1000 / mechacorps), so no sudo needed here.
                                install -m 755 ${botDir}/${binaryName} ${installDir}/${binaryName}

                                ssh ${sshOpts} ${sshHost} 'sudo /usr/bin/systemctl restart ${serviceName}.service'

                                # Give the bot a moment to connect to Discord before
                                # checking status.
                                sleep 3

                                if ssh ${sshOpts} ${sshHost} 'sudo /usr/bin/systemctl is-active ${serviceName}.service' | grep -q '^active\$'; then
                                    echo "✓ ${serviceName}.service is active"
                                else
                                    echo "✗ ${serviceName}.service failed to start"
                                    ssh ${sshOpts} ${sshHost} 'sudo /usr/bin/journalctl -u ${serviceName}.service -n 40 --no-pager' || true
                                    exit 1
                                fi
                            """
                            env.DISCORD_BOT_DEPLOYED = "true"
                        }
                    }
                }
            }
        }

        post {
            success {
                script {
                    if (env.DISCORD_BOT_DEPLOYED != "true") {
                        echo "No discord-bot changes detected — nothing deployed"
                        return
                    }

                    discordNotify.success(
                        title: "MechaCorps Discord Bot Deploy",
                        message: "✅ Deployed discord-bot",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: "production",
                        branch: config.branch,
                        version: env.SVC_VERSION
                    )
                }
            }
            failure {
                script {
                    discordNotify.failure(
                        title: "MechaCorps Discord Bot Deploy",
                        message: "❌ Deploy failed",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: "production",
                        branch: config.branch
                    )
                }
            }
            unstable {
                script {
                    discordNotify.failure(
                        title: "MechaCorps Discord Bot Deploy",
                        message: "⚠️ Deploy marked unstable",
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
