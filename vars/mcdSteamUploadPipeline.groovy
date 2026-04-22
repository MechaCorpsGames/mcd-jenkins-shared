// MechaCorps Steam Upload Pipeline - Shared Library
// Standalone job: reads artifacts from a client build and uploads to Steam via SteamPipe.
// Triggered manually — pick the source job and build number.

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
                choices: ['MCDClient-Main', 'MCDClient-Release'],
                description: 'Which client build job to get artifacts from'
            )
            string(
                name: 'SOURCE_BUILD',
                defaultValue: '',
                description: 'Build number (leave empty for latest successful build with artifacts)'
            )
            choice(
                name: 'STEAM_BRANCH',
                choices: ['main', 'alpha'],
                description: 'Steam branch to set the build live on'
            )
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            STEAM_CREDENTIALS = credentials('bde2ac32-eb1e-4a94-a8d0-c77e8f5be7e5')
        }

        stages {
            stage('Locate Client Artifacts') {
                steps {
                    script {
                        def jobDir = "/var/lib/jenkins/jobs/${params.SOURCE_JOB}/builds"
                        def buildNum = params.SOURCE_BUILD?.trim()

                        // Resolve non-numeric values (empty, "lastSuccessfulBuild", etc.)
                        if (!buildNum || !buildNum.isNumber()) {
                            // Find latest build with archived artifacts
                            buildNum = sh(
                                script: "ls -1d ${jobDir}/*/archive 2>/dev/null | sort -t/ -k8 -n | tail -1 | grep -oP '\\d+(?=/archive)'",
                                returnStdout: true
                            ).trim()

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

                        sed -i "s/__DESCRIPTION__/v${CLIENT_VERSION} from ${SOURCE_BRANCH} (${SOURCE_COMMIT})/g" \
                            steam_build/app_build.vdf

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

            stage('Upload to Steam') {
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
            success {
                script {
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
