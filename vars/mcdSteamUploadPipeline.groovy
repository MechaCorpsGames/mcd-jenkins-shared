// MechaCorps Steam Upload Pipeline - Shared Library
// Standalone job: downloads artifacts from a client build and uploads to Steam via SteamPipe.
// Triggered manually — pick the source job and build number.

def call(Map config) {
    // Required config:
    //   jobName: 'MCDSteam-Upload'

    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                args '-v /var/lib/jenkins/.steam:/home/jenkins/.steam:rw --network host'
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        parameters {
            choice(
                name: 'SOURCE_JOB',
                choices: ['MCDClient-Main', 'MCDClient-Release'],
                description: 'Which client build job to download artifacts from'
            )
            string(
                name: 'SOURCE_BUILD',
                defaultValue: 'lastSuccessfulBuild',
                description: 'Build number to upload (or "lastSuccessfulBuild")'
            )
            choice(
                name: 'STEAM_BRANCH',
                choices: ['dev', 'alpha'],
                description: 'Steam branch to set the build live on'
            )
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            STEAM_CREDENTIALS = credentials('bde2ac32-eb1e-4a94-a8d0-c77e8f5be7e5')
        }

        stages {
            stage('Download Client Artifacts') {
                steps {
                    script {
                        def artifactUrl = "${env.JENKINS_URL_BASE}/job/${params.SOURCE_JOB}/${params.SOURCE_BUILD}/artifact/*zip*/archive.zip"

                        echo "Downloading artifacts from ${params.SOURCE_JOB} #${params.SOURCE_BUILD}..."

                        // Download using Jenkins internal access (localhost, no auth needed)
                        sh """
                            curl -sSf -o archive.zip "http://localhost:8080/job/${params.SOURCE_JOB}/${params.SOURCE_BUILD}/artifact/*zip*/archive.zip"
                            unzip -o archive.zip
                            rm archive.zip

                            echo "Downloaded artifacts:"
                            find archive/artifacts -type f | sort
                        """

                        // Read version from manifest
                        def manifestPath = sh(
                            script: "find archive/artifacts -name manifest.json | head -1",
                            returnStdout: true
                        ).trim()

                        if (!manifestPath) {
                            error "No manifest.json found in downloaded artifacts"
                        }

                        def manifest = readJSON file: manifestPath
                        env.CLIENT_VERSION = manifest.clientVersion
                        env.SOURCE_BRANCH = manifest.branch
                        env.SOURCE_COMMIT = manifest.commit

                        // Find the artifact directory
                        env.ARTIFACT_BASE = sh(
                            script: "dirname ${manifestPath}",
                            returnStdout: true
                        ).trim()

                        currentBuild.displayName = "#${BUILD_NUMBER} v${env.CLIENT_VERSION} → ${params.STEAM_BRANCH}"
                        currentBuild.description = "From ${params.SOURCE_JOB} #${params.SOURCE_BUILD} (${env.SOURCE_BRANCH})"

                        echo "Client version: ${env.CLIENT_VERSION}"
                        echo "Source: ${params.SOURCE_JOB} #${params.SOURCE_BUILD}"
                        echo "Steam branch: ${params.STEAM_BRANCH}"

                        sh "ls -lh ${env.ARTIFACT_BASE}/"
                    }
                }
            }

            stage('Prepare Steam Content') {
                steps {
                    checkout scm

                    sh """
                        rm -rf steam_content steam_output steam_build
                        mkdir -p steam_content/windows steam_content/linux steam_build

                        # Find and unzip platform artifacts
                        WIN_ZIP=\$(find ${ARTIFACT_BASE} -name '*Windows*.zip' | head -1)
                        LIN_ZIP=\$(find ${ARTIFACT_BASE} -name '*Linux*.zip' | head -1)

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

                        # Prepare VDF files with substitutions
                        cp steam/app_build.vdf steam_build/
                        cp steam/depot_windows.vdf steam_build/
                        cp steam/depot_linux.vdf steam_build/

                        sed -i "s/__DESCRIPTION__/v${CLIENT_VERSION} from ${SOURCE_BRANCH} (${SOURCE_COMMIT})/g" \
                            steam_build/app_build.vdf
                        sed -i "s/__BRANCH__/${params.STEAM_BRANCH}/g" steam_build/app_build.vdf

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
