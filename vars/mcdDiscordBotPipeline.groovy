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
        // agent none, deliberately. See mjs-j5z.
        //
        // Src/Tools/discord-bot changed ZERO times on main between 2026-07-28
        // and 2026-08-28, while this job ran 409 builds (#246 to #655). It
        // fires per PUSH, not per relevant change. With the heavyweight docker
        // agent declared HERE, every one of those pushes allocated an executor,
        // started the mcd-build-agent container and ran a full checkout of
        // MCDClient before 'Detect Changes' got to say "nothing to do".
        //
        // The stage timings show the cost is the ALLOCATION, not the work:
        // build #645 spent ~46s across all its stages against a wall duration
        // of 11m52s. Retained no-op builds ran 45s, 52s, 56s, 57s, 1m53s,
        // 3m59s, 5m32s, 10m32s, 11m52s.
        //
        // So the gate now sits IN FRONT of the heavyweight agent: detection
        // runs on a cheap executor, and the container is entered only when the
        // bot actually changed. The detection logic itself is unchanged.
        agent none

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
                // Coalesce a merge burst into one build (bead mc-h2nm2). Left
                // false, the plugin stamps a unique
                // `jenkins-generic-webhook-trigger-plugin_uuid` parameter on
                // every push, so Jenkins never collapses queued items and
                // disableConcurrentBuilds() above turns a burst into a serial
                // queue of identical builds. The long version of this reasoning,
                // including the bytecode it was read from, is in
                // mcdServerPipeline.groovy. NOT ON mcdPRValidationPipeline.
                allowSeveralTriggersPerBuild: true,
                regexpFilterText: '$ref',
                regexpFilterExpression: "refs/heads/${config.branch}"
            )
        }

        stages {
            // Everything up to and including the change-detection decision.
            //
            // ONE agent for the whole group so these four stages share a single
            // workspace: 'Detect Changes' shells out to git and reads the
            // history that 'Checkout' just fetched. Split them into an agent
            // each and each gets its own workspace, and the detection reads an
            // empty one.
            //
            // `agent any`, not a label: this Jenkins has no agent nodes, only
            // executors on the controller (mcdSteamSourceBuild.groovy records
            // "the controller had four executors and no agents"), so a label
            // nobody publishes would leave this job queued forever.
            // mcdPromotePipeline runs on `agent any` for the same reason.
            stage('Detect') {
                agent any

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
                                // The base is the last build that actually REACHED A VERDICT on a tree,
                                // not the previous push. An aborted build's commits would otherwise be
                                // attributed to a build that never evaluated them, and the next push
                                // would close over them unbuilt (bead mc-okhtp). resolve() returns null
                                // when there is no trustworthy base, which routes us down the
                                // build-everything branch just below.
                                def baseRef = mcdChangeBase.resolve(env.before_sha)
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

                    // Trim to latest (bead mc-waxw). Tim, 2026-08-25: "for all builds
                    // other than PR builds we should be trimming to latest."
                    //
                    // A burst of pushes queues one build per push behind this job's
                    // disableConcurrentBuilds(), and each of them checks out the branch
                    // TIP rather than the commit its own webhook carried. So the ones
                    // behind the first do identical work for nothing. This stands those
                    // down by clearing the same flags 'Detect Changes' clears when a push
                    // touches nothing here, which routes the build down the no-op path
                    // this pipeline already takes several times a day.
                    //
                    // It only ever skips a commit an EARLIER build of this job already
                    // built and succeeded on, so it cannot strand a deploy: see
                    // mcdRedundantBuild.groovy for why that direction matters, and why
                    // this is not abortPrevious.
                    stage('Trim to Latest') {
                        steps {
                            script {
                                if (mcdRedundantBuild.trim()) {
                                    env.DISCORD_BOT_CHANGED = 'false'
                                }
                            }
                        }
                    }
                }
            }

            // The heavyweight half, and the whole point of mjs-j5z.
            //
            // `beforeAgent true` is LOAD-BEARING and is not the default.
            // Declarative enters a stage's agent BEFORE evaluating its `when`
            // unless you ask for the opposite. Without this line the container
            // would still start on every no-op push, the `when` guards below
            // would still correctly skip the work, the build would still report
            // NOT_BUILT, and the entire change would be a silent no-op that
            // looks fixed. A test that only checked "Build has a when guard"
            // would pass on that. test_mcd_discord_bot_agent_is_gated.py pins
            // this line specifically.
            //
            // Build and Deploy are nested under ONE agent on purpose: Deploy
            // installs the binary Build produced. Give them an agent each and
            // they get a workspace each, and the install has nothing to install.
            stage('Build & Deploy') {
                agent {
                    docker {
                        image 'mcd-build-agent:latest'
                        args '-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /opt/mechacorps:/opt/mechacorps --network host --group-add 111 --group-add 995 --group-add 1000'
                    }
                }
                when {
                    beforeAgent true
                    expression { env.DISCORD_BOT_CHANGED == 'true' }
                }

                stages {
                    stage('Build') {
                        when { expression { env.DISCORD_BOT_CHANGED == 'true' } }
                        steps {
                            // This group has its own agent, so it has its own
                            // workspace, and nothing has checked out into it:
                            // the checkout above happened on the detection
                            // agent. Without this the `cd ${botDir}` below
                            // lands in an empty directory.
                            //
                            // It costs nothing in practice. This path runs only
                            // when the bot actually changed, which was 0 times
                            // in the month measured in mjs-j5z.
                            checkout scm

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
