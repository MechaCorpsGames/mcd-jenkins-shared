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

            // This job SHIPS PRODUCTION out of a directory another job writes
            // (bead mc-ehn1). Two promotes running at once would rsync the same
            // staging tree into the same prod tree and recreate the same prod
            // proxy container, and the second `up -d --force-recreate` can land
            // on a container the first has just removed. Bare, never
            // abortPrevious: aborting mid-rsync is what leaves the half-written
            // prod tree these controls exist to prevent.
            disableConcurrentBuilds()
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
                    script {
                        if (config.notifyDiscord != false) {
                            // Default BVT client artifact: lastSuccessful
                            // MCDClient-Release build. Operators override via
                            // config.bvtClientJobName if the BVT client lives
                            // under a different Jenkins job name.
                            def bvtJob = config.bvtClientJobName ?: 'MCDClient-Release'
                            def jenkinsBase = env.JENKINS_URL_BASE ?: env.JENKINS_URL
                            // Trim trailing slash for consistent URL composition.
                            jenkinsBase = jenkinsBase?.replaceAll('/$', '')
                            def bvtUrl = "${jenkinsBase}/job/${bvtJob}/lastSuccessfulBuild/artifact/"

                            // Tester checklist surfaced inline so operators
                            // see the launch-args trick without having to
                            // find the wiki page.
                            // `play-staging.mechacorpsgames.com` fits Cloudflare's
                            // *.mechacorpsgames.com wildcard cert. Avoid second-
                            // level subdomains like staging.play... on the basic
                            // Universal SSL plan — TLS handshake fails.
                            //
                            // No port in the public URL: Cloudflare terminates
                            // TLS on 443 and proxies internally to localhost:46070
                            // via the tunnel. stagingWsPort is kept as a config
                            // knob for callers who BYPASS Cloudflare (e.g. LAN-
                            // direct testing), but the default checklist uses
                            // the bare https/wss form.
                            def stagingServerHost = config.stagingServerHost ?: 'play-staging.mechacorpsgames.com'
                            def stagingClientUrl = config.stagingClientUrl ?: "wss://${stagingServerHost}"
                            def checklist = """1. Download the latest MCDClient-Release artifact (button below).
2. Extract; launch the binary with command-line override:
   `--server-url ${stagingClientUrl}`
   (or set this in Steam Launch Options if testing via your Steam install.)
3. Run the BVT scenarios against play-staging.
4. If pass: click **Confirm in Jenkins** below.
5. After Jenkins promote succeeds, click *Set Live* in Steamworks."""

                            discordNotify.awaitingApproval(
                                title: "${config.jobName} #${BUILD_NUMBER} awaiting promote",
                                message: "Server **${env.STAGING_SERVER_VERSION}** has been deployed to staging and is ready for BVT. Once BVT passes, click Confirm to flip production.",
                                jobName: config.jobName,
                                jenkinsUrl: jenkinsBase,
                                environment: 'production',
                                version: env.STAGING_SERVER_VERSION,
                                bvtArtifactUrl: bvtUrl,
                                instructionsField: checklist
                            )
                        }
                    }
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
                    // LOCKED ON THE STAGING PATH, NOT THE PROD PATH (bead mc-ehn1).
                    // The contended directory is the SOURCE: MCDServer-Release-
                    // Staging writes /opt/mechacorps/release-staging (its deploy and
                    // its retention sweep both do) while this job reads it to build
                    // production. Nothing else writes prodDeployPath, so keying the
                    // lock there would exclude nobody. Both parties take the
                    // staging-path key via mcdDeployLock, which is the only reason
                    // this excludes anything at all.
                    //
                    // The lock deliberately does NOT span 'Confirm Promote'. That
                    // stage blocks on a human, potentially for hours, and a
                    // cross-job mutex held across an input gate would wedge every
                    // staging deploy behind an operator who wandered off. The
                    // re-verification below is what covers that window instead.
                    mcdDeployLock(deployPath: config.stagingDeployPath, """
                        set -e

                        # RE-VERIFY WHAT THE OPERATOR ACTUALLY APPROVED (mc-ehn1).
                        # 'Validate Staging Artifacts' read these two pointers, and
                        # 'Confirm Promote' then showed that version to a human and
                        # waited. In between, MCDServer-Release-Staging can publish a
                        # NEW version onto this same path. Without this check the
                        # rsync below would ship whatever is on staging NOW, while
                        # the post{success} Discord message announces the version the
                        # operator confirmed. That is a silent wrong PRODUCTION
                        # deploy: the build stays green, and the only symptom is that
                        # prod is running something nobody approved.
                        #
                        # Fail loudly instead. Re-running the job re-validates and
                        # re-asks, which is the correct recovery.
                        current_server=\$(cat ${config.stagingDeployPath}/versions/latest.txt)
                        current_testclient=\$(cat ${config.stagingDeployPath}/testclient-versions/latest.txt)
                        if [ "\$current_server" != "${env.STAGING_SERVER_VERSION}" ] || \\
                           [ "\$current_testclient" != "${env.STAGING_TESTCLIENT_VERSION}" ]; then
                            echo "✗ REFUSING TO PROMOTE: staging moved while waiting for confirmation." >&2
                            echo "  confirmed GameServer: ${env.STAGING_SERVER_VERSION}" >&2
                            echo "  staging now:          \$current_server" >&2
                            echo "  confirmed TestClient: ${env.STAGING_TESTCLIENT_VERSION}" >&2
                            echo "  staging now:          \$current_testclient" >&2
                            echo "  A staging deploy landed between validation and promote. Re-run this job to validate and confirm the new build." >&2
                            exit 1
                        fi

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
                    """)
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
                        // Use discordNotify.success for the rich-embed style
                        // (matches mcdServerPipeline / mcdClientPipeline format).
                        // environment=production so the embed picks up the prod
                        // green color + 🚀 emoji.
                        discordNotify.success(
                            title: "MechaCorps Server Promoted",
                            message: "🚀 Promoted Server ${env.STAGING_SERVER_VERSION} from staging to production.\n**Action required:** click *Set Live* in Steamworks to publish the matching client build.",
                            jenkinsUrl: env.JENKINS_URL_BASE ?: env.JENKINS_URL,
                            jobName: config.jobName,
                            environment: 'production',
                            branch: 'release',
                            version: env.STAGING_SERVER_VERSION ?: 'N/A'
                        )
                    }
                }
            }
            failure {
                script {
                    if (config.notifyDiscord != false) {
                        discordNotify.failure(
                            title: "MechaCorps Server Promote",
                            message: "❌ Failed to promote ${env.STAGING_SERVER_VERSION ?: 'unknown'} from ${config.stagingDeployPath} to ${config.prodDeployPath}.",
                            jenkinsUrl: env.JENKINS_URL_BASE ?: env.JENKINS_URL,
                            jobName: config.jobName,
                            environment: 'production',
                            branch: 'release'
                        )
                    }
                }
            }
            aborted {
                script {
                    if (config.notifyDiscord != false) {
                        discordNotify.simple(
                            "⚠️ ${config.jobName} #${BUILD_NUMBER} aborted before promote completed.",
                            "9807270"  // grey
                        )
                    }
                    echo 'Promote aborted by operator.'
                }
            }
        }
    }
}
