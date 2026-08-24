// MechaCorps Steam Upload Pipeline - Shared Library
// Standalone job: reads artifacts from a client build and uploads to Steam via SteamPipe.
//
// Triggered two ways: manually (pick the source job and build number), and
// automatically by the last stage of every client pipeline that arms
// config.steamBranch. Automatic triggers coalesce (see 'Coalesce by Source
// Build' below and mcdSteamSourceBuild.groovy). Manual triggers never do: a
// build number somebody typed is a build number they meant.

def call(Map config) {
    // Required config:
    //   jobName: 'MCDSteam-Upload'

    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                args '-v /var/lib/jenkins/.steam:/home/jenkins/Steam:rw -v /var/lib/jenkins/jobs:/var/lib/jenkins/jobs:ro --network host'
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        parameters {
            choice(
                name: 'SOURCE_JOB',
                choices: ['MCDClient-Main', 'MCDClient-Release', 'MCDClient-FeatureBackend', 'MCDClient-FeatureCard'],
                description: 'Which client build job to get artifacts from'
            )
            string(
                name: 'SOURCE_BUILD',
                defaultValue: '',
                description: 'Build number (leave empty for latest successful build with artifacts)'
            )
            choice(
                name: 'STEAM_BRANCH',
                choices: ['staging', 'main', 'backend', 'card', 'default'],
                description: "Steam beta branch to set the build live on. 'default' = public branch: the build uploads but is NOT set live (flip it manually in Steamworks)."
            )
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            STEAM_CREDENTIALS = credentials('bde2ac32-eb1e-4a94-a8d0-c77e8f5be7e5')
        }

        stages {
            // One upload per Steam branch, newest wins (bead mc-fr2h).
            //
            // The client pipeline fires an upload per build, so a burst of
            // client builds queues a burst of uploads that all set a build live
            // on the SAME beta. Only the last one has any effect, and the
            // rest sit in the queue competing for executors with PR validation.
            //
            // An upload is pointless once a NEWER build of the same source job
            // has archived artifacts, because that build fires its own upload
            // to the same Steam branch. Detecting it that way keys the decision
            // on the source job, which can never suppress a different Steam
            // branch (a different branch is a different source job). See
            // mcdSteamSourceBuild.groovy for why this is not milestone()+lock().
            //
            // NOT_BUILT, not FAILURE: being superseded is the system working.
            // Declarative runs post{success} only on SUCCESS and post{failure}
            // only on FAILURE, so a NOT_BUILT build notifies Discord about
            // nothing, which is the intent. The notification handlers below
            // also check explicitly, so a future post condition cannot page
            // somebody for a normal outcome.
            stage('Coalesce by Source Build') {
                steps {
                    script {
                        env.UPLOAD_SUPERSEDED = 'false'

                        String newer = mcdSteamSourceBuild.supersededBy(
                            params.SOURCE_JOB, params.SOURCE_BUILD)
                        if (!newer) {
                            return
                        }

                        env.UPLOAD_SUPERSEDED = 'true'
                        env.SUPERSEDED_BY = newer
                        currentBuild.displayName =
                            "#${BUILD_NUMBER} superseded by ${params.SOURCE_JOB} #${newer}"
                        currentBuild.description =
                            "Skipped: ${params.SOURCE_JOB} #${newer} has newer artifacts for Steam branch '${params.STEAM_BRANCH}'"
                        currentBuild.result = 'NOT_BUILT'
                        echo "Superseded: this upload carries ${params.SOURCE_JOB} #${params.SOURCE_BUILD}, but #${newer} has already archived artifacts and fires its own upload to Steam branch '${params.STEAM_BRANCH}'. Skipping."
                    }
                }
            }

            stage('Locate Client Artifacts') {
                when { expression { env.UPLOAD_SUPERSEDED != 'true' } }
                steps {
                    script {
                        def jobDir = "/var/lib/jenkins/jobs/${params.SOURCE_JOB}/builds"
                        def buildNum = params.SOURCE_BUILD?.trim()

                        // Resolve non-numeric values (empty, "lastSuccessfulBuild", etc.)
                        if (!buildNum || !buildNum.isNumber()) {
                            // Find latest build with archived artifacts
                            buildNum = mcdSteamSourceBuild.latest(params.SOURCE_JOB)

                            if (!buildNum) {
                                error "No builds with artifacts found for ${params.SOURCE_JOB}"
                            }
                            echo "Using latest build with artifacts: #${buildNum}"
                        }

                        env.SOURCE_BUILD_NUM = buildNum
                        def archiveDir = "${jobDir}/${buildNum}/archive"

                        // Find manifest
                        def manifestPath = sh(
                            script: "find ${archiveDir} -name manifest.json | head -1",
                            returnStdout: true
                        ).trim()

                        if (!manifestPath) {
                            error "No manifest.json found in build #${buildNum}"
                        }

                        env.CLIENT_VERSION = sh(
                            script: "grep -oP '\"clientVersion\"\\s*:\\s*\"\\K[^\"]+' ${manifestPath}",
                            returnStdout: true
                        ).trim()
                        env.SOURCE_BRANCH = sh(
                            script: "grep -oP '\"branch\"\\s*:\\s*\"\\K[^\"]+' ${manifestPath}",
                            returnStdout: true
                        ).trim()
                        env.SOURCE_COMMIT = sh(
                            script: "grep -oP '\"commit\"\\s*:\\s*\"\\K[^\"]+' ${manifestPath}",
                            returnStdout: true
                        ).trim()

                        env.ARTIFACT_DIR = sh(
                            script: "dirname ${manifestPath}",
                            returnStdout: true
                        ).trim()

                        currentBuild.displayName = "#${BUILD_NUMBER} v${env.CLIENT_VERSION} → ${params.STEAM_BRANCH}"
                        currentBuild.description = "From ${params.SOURCE_JOB} #${buildNum} (${env.SOURCE_BRANCH})"

                        echo "Client version: ${env.CLIENT_VERSION}"
                        echo "Source: ${params.SOURCE_JOB} #${buildNum}"
                        echo "Steam branch: ${params.STEAM_BRANCH}"

                        sh "ls -lh ${env.ARTIFACT_DIR}/"
                    }
                }
            }

            stage('Prepare Steam Content') {
                when { expression { env.UPLOAD_SUPERSEDED != 'true' } }
                steps {
                    checkout scm

                    sh """
                        rm -rf steam_content steam_output steam_build
                        mkdir -p steam_content/windows steam_content/linux steam_build

                        WIN_ZIP=\$(find ${ARTIFACT_DIR} -name '*Windows*.zip' | head -1)
                        LIN_ZIP=\$(find ${ARTIFACT_DIR} -name '*Linux*.zip' | head -1)

                        if [ -z "\$WIN_ZIP" ]; then
                            echo "ERROR: No Windows zip found"
                            exit 1
                        fi
                        if [ -z "\$LIN_ZIP" ]; then
                            echo "ERROR: No Linux zip found"
                            exit 1
                        fi

                        unzip -o "\$WIN_ZIP" -d steam_content/windows/
                        unzip -o "\$LIN_ZIP" -d steam_content/linux/

                        # Include Steam API redistributable libraries
                        cp addons/godotsteam/win64/steam_api64.dll steam_content/windows/
                        cp addons/godotsteam/linux64/libsteam_api.so steam_content/linux/

                        cp steam/app_build.vdf steam_build/
                        cp steam/depot_windows.vdf steam_build/
                        cp steam/depot_linux.vdf steam_build/

                        # Use '|' as the sed delimiter, NOT '/': the git SOURCE_BRANCH
                        # can contain a slash (e.g. features/card, features/backend),
                        # which breaks an s/.../.../ expression ("unknown option to `s'")
                        # and kills the upload before steamcmd runs. Branch names and
                        # commit hashes never contain '|', so it's a safe delimiter.
                        sed -i "s|__DESCRIPTION__|v${CLIENT_VERSION} from ${SOURCE_BRANCH} (${SOURCE_COMMIT})|g" \
                            steam_build/app_build.vdf

                        # Route the chosen Steam branch into setlive so the upload goes
                        # live on that beta branch. 'default' (public) is intentionally
                        # left empty so the public branch stays a manual Steamworks flip.
                        SETLIVE_BRANCH="${params.STEAM_BRANCH}"
                        if [ "\$SETLIVE_BRANCH" = "default" ]; then
                            SETLIVE_BRANCH=""
                            echo "STEAM_BRANCH=default -> setlive left empty (flip public manually in Steamworks)"
                        else
                            echo "STEAM_BRANCH=\$SETLIVE_BRANCH -> build will be set live on this beta branch"
                        fi
                        sed -i "s|__SETLIVE__|\$SETLIVE_BRANCH|g" steam_build/app_build.vdf

                        echo "=== Steam build VDF ==="
                        cat steam_build/app_build.vdf

                        echo ""
                        echo "=== Windows content ==="
                        find steam_content/windows -type f | sort

                        echo ""
                        echo "=== Linux content ==="
                        find steam_content/linux -type f | sort
                    """
                }
            }

            // Checked again immediately before the only effectful step in this
            // job. Locating and unpacking the artifacts takes minutes, and a
            // client build can archive newer ones during them. Without this
            // the two would both reach steamcmd and race over which one ends up
            // live on the beta. The window is not closed completely: an upload
            // that passes this check microseconds before a newer build archives
            // still runs. That leaves one redundant upload, and the newer one
            // sets itself live afterwards, so the beta still ends on the newest
            // build. Closing it entirely needs mutual exclusion between
            // concurrent uploads, which is bead mc-v721, not this.
            stage('Re-check for Newer Artifacts') {
                when { expression { env.UPLOAD_SUPERSEDED != 'true' } }
                steps {
                    script {
                        String newer = mcdSteamSourceBuild.supersededBy(
                            params.SOURCE_JOB, params.SOURCE_BUILD)
                        if (!newer) {
                            return
                        }

                        env.UPLOAD_SUPERSEDED = 'true'
                        env.SUPERSEDED_BY = newer
                        currentBuild.displayName =
                            "#${BUILD_NUMBER} superseded by ${params.SOURCE_JOB} #${newer}"
                        currentBuild.description =
                            "Skipped before upload: ${params.SOURCE_JOB} #${newer} archived newer artifacts while this build was preparing content"
                        currentBuild.result = 'NOT_BUILT'
                        echo "Superseded during preparation: ${params.SOURCE_JOB} #${newer} archived newer artifacts. Not running steamcmd."
                    }
                }
            }

            stage('Upload to Steam') {
                when { expression { env.UPLOAD_SUPERSEDED != 'true' } }
                steps {
                    retry(2) {
                        sh """
                            steamcmd.sh \
                                +login "\${STEAM_CREDENTIALS_USR}" "\${STEAM_CREDENTIALS_PSW}" \
                                +run_app_build \${WORKSPACE}/steam_build/app_build.vdf \
                                +quit
                        """
                    }
                }
            }
        }

        post {
            // Both handlers return early on a superseded build. Declarative
            // already keeps them from running (NOT_BUILT is neither SUCCESS nor
            // FAILURE), but "superseded" is a NORMAL outcome and must never
            // reach Discord: a coalescing change that starts paging people
            // about builds it deliberately skipped is worse than the five
            // redundant uploads it replaced.
            success {
                script {
                    if (env.UPLOAD_SUPERSEDED == 'true') {
                        return
                    }
                    discordNotify.success(
                        title: "Steam Upload",
                        message: "✅ Uploaded to Steam",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: params.STEAM_BRANCH,
                        branch: env.SOURCE_BRANCH,
                        version: env.CLIENT_VERSION,
                        steamBranch: params.STEAM_BRANCH
                    )
                }
            }
            failure {
                script {
                    if (env.UPLOAD_SUPERSEDED == 'true') {
                        return
                    }
                    discordNotify.failure(
                        title: "Steam Upload",
                        message: "❌ Steam upload failed",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: params.STEAM_BRANCH,
                        branch: env.SOURCE_BRANCH ?: 'unknown'
                    )
                }
            }
        }
    }
}

return this
