// vars/mcdPromotePipeline.groovy
//
// Manually-triggered promote pipeline. Reads the GameServer + TestClient +
// bots + godot-bot-project artifacts from a staging deploy path and rsyncs
// them onto a production deploy path, then restarts the production proxy
// container. The matching client Steam publish is a future addition; for
// now, the promote covers the server side only — operators promote the
// Steam client by clicking "Set Live" in Steamworks immediately after this
// job succeeds.
//
// Required config:
//   stagingDeployPath: source path, e.g. '/opt/mechacorps/release-staging'
//   prodDeployPath:    destination path, e.g. '/opt/mechacorps/release'
//   prodComposeProject: docker compose project for prod proxy, e.g. 'mcd-release'
//   prodEnvFile:        prod proxy env file, e.g. '.env.proxy.release'
//   jobName:            for Discord notifications
//
// Optional config:
//   prodSystemdService: legacy proxy systemd service name to stop before
//                       docker takeover (e.g. 'mcdproxy-release.service').
//                       Defaults to none (skipped).
//   notifyDiscord:      boolean. Defaults to true.
//
// Mental model:
//   merge to release      → mcdServerPipeline deploys to staging (auto)
//   BVT runs against staging (manual / out-of-band)
//   click "Build Now"     → mcdPromotePipeline copies staging→prod (this file)
//   click "Set Live" in   → Steam publish (Steamworks UI; this pipeline does
//   Steamworks              not touch Steam in V1)

def call(Map config) {
    pipeline {
        agent any

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
        }

        options {
            timestamps()
            buildDiscarder(logRotator(numToKeepStr: '50'))
        }

        stages {
            stage('Validate Staging Artifacts') {
                steps {
                    script {
                        // Fail fast if staging never ran or its artifacts are missing.
                        // Without the latest.txt pointer we don't know which build
                        // to promote.
                        sh """
                            set -e
                            test -f ${config.stagingDeployPath}/versions/latest.txt || {
                                echo "ERROR: ${config.stagingDeployPath}/versions/latest.txt missing — has the staging server pipeline run on this commit?"
                                exit 1
                            }
                            test -f ${config.stagingDeployPath}/testclient-versions/latest.txt || {
                                echo "ERROR: ${config.stagingDeployPath}/testclient-versions/latest.txt missing"
                                exit 1
                            }
                            test -x ${config.stagingDeployPath}/MCDProxy || {
                                echo "ERROR: ${config.stagingDeployPath}/MCDProxy missing or not executable"
                                exit 1
                            }
                        """
                        env.STAGING_SERVER_VERSION = sh(
                            script: "cat ${config.stagingDeployPath}/versions/latest.txt",
                            returnStdout: true
                        ).trim()
                        env.STAGING_TESTCLIENT_VERSION = sh(
                            script: "cat ${config.stagingDeployPath}/testclient-versions/latest.txt",
                            returnStdout: true
                        ).trim()
                        echo "Promoting staging build to production:"
                        echo "  GameServer:  ${env.STAGING_SERVER_VERSION}"
                        echo "  TestClient:  ${env.STAGING_TESTCLIENT_VERSION}"
                        echo "  staging dir: ${config.stagingDeployPath}"
                        echo "  prod dir:    ${config.prodDeployPath}"
                    }
                }
            }

            stage('Confirm Promote') {
                steps {
                    // Manual gate so an operator can re-check BVT signoff
                    // before flipping prod. Times out after 60min so a left-
                    // open prompt doesn't block forever.
                    timeout(time: 60, unit: 'MINUTES') {
                        input message: "Promote staging build ${env.STAGING_SERVER_VERSION} to production at ${config.prodDeployPath}?",
                              ok: 'Promote'
                    }
                }
            }

            stage('Sync Binaries → Prod') {
                steps {
                    sh """
                        set -e
                        mkdir -p ${config.prodDeployPath}/versions ${config.prodDeployPath}/testclient-versions ${config.prodDeployPath}/bots
                        # --delete on bots so removed practice bots don't linger;
                        # versions/ are append-only so no --delete there (preserve
                        # rollback artifacts).
                        rsync -rlvz --no-group ${config.stagingDeployPath}/versions/ ${config.prodDeployPath}/versions/
                        rsync -rlvz --no-group ${config.stagingDeployPath}/testclient-versions/ ${config.prodDeployPath}/testclient-versions/
                        rsync -rlvz --no-group --delete ${config.stagingDeployPath}/bots/ ${config.prodDeployPath}/bots/
                        rsync -rlvz --no-group --delete ${config.stagingDeployPath}/godot-bot-project/ ${config.prodDeployPath}/godot-bot-project/
                        # MCDProxy binary itself
                        if [ -x ${config.stagingDeployPath}/MCDProxy ]; then
                            cp ${config.stagingDeployPath}/MCDProxy ${config.prodDeployPath}/MCDProxy
                            chmod +x ${config.prodDeployPath}/MCDProxy
                        fi
                        # Sync Data dir if present (DONE-filtered card data).
                        if [ -d ${config.stagingDeployPath}/Data ]; then
                            rsync -rlvz --no-group ${config.stagingDeployPath}/Data/ ${config.prodDeployPath}/Data/
                        fi
                        echo "✓ Promoted GameServer:  \$(cat ${config.prodDeployPath}/versions/latest.txt)"
                        echo "✓ Promoted TestClient:  \$(cat ${config.prodDeployPath}/testclient-versions/latest.txt)"
                    """
                }
            }

            stage('Restart Prod Proxy') {
                steps {
                    script {
                        def containerName = "${config.prodComposeProject}-proxy-1"
                        def systemdService = config.prodSystemdService ?: ''
                        sh """
                            set -e
                            # Stop the legacy systemd proxy service if it's
                            # still around — leftover from before docker compose
                            # took over.
                            if [ -n "${systemdService}" ]; then
                                sudo systemctl stop ${systemdService} 2>/dev/null || true
                                sudo systemctl disable ${systemdService} 2>/dev/null || true
                            fi
                            cd /var/opt/mechacorpsgames/Src
                            # Rebuild proxy image so it picks up the new MCDProxy
                            # binary and the freshly synced bots/godot project.
                            docker compose -p ${config.prodComposeProject} -f docker-compose.proxy.yml --env-file ${config.prodEnvFile} build --no-cache proxy
                            docker compose -p ${config.prodComposeProject} -f docker-compose.proxy.yml --env-file ${config.prodEnvFile} up -d --force-recreate proxy
                            sleep 3
                            if docker ps --filter 'name=${containerName}' --format '{{.Status}}' | grep -q 'Up'; then
                                echo "✓ Production proxy restarted: ${containerName}"
                            else
                                echo "✗ Failed to start production proxy"
                                docker logs ${containerName} --tail 50 2>&1 || true
                                exit 1
                            fi
                        """
                    }
                }
            }
        }

        post {
            success {
                script {
                    if (config.notifyDiscord != false) {
                        discordNotify(
                            webhookUrl: env.DISCORD_WEBHOOK,
                            title: "✅ ${config.jobName} promoted to production",
                            description: "Server ${env.STAGING_SERVER_VERSION} is now live at ${config.prodDeployPath}.\n\n**Reminder:** click *Set Live* in Steamworks to publish the matching client build.",
                            color: 0x2ECC71
                        )
                    }
                }
            }
            failure {
                script {
                    if (config.notifyDiscord != false) {
                        discordNotify(
                            webhookUrl: env.DISCORD_WEBHOOK,
                            title: "❌ ${config.jobName} promote failed",
                            description: "Attempted to promote ${env.STAGING_SERVER_VERSION ?: 'unknown'} from ${config.stagingDeployPath} to ${config.prodDeployPath}. See build log: ${env.BUILD_URL}",
                            color: 0xE74C3C
                        )
                    }
                }
            }
            aborted {
                echo 'Promote aborted by operator.'
            }
        }
    }
}
