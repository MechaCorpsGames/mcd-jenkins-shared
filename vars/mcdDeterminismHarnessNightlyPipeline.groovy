// MechaCorps Determinism Harness Nightly Pipeline
// Replays sampled playtest-bench artifacts against co-located baselines.

def call(Map config) {
    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                args '-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /var/lib/jenkins/.android:/var/lib/jenkins/.android:ro -v /var/lib/jenkins/.local/share/godot/export_templates:/home/jenkins/.local/share/godot/export_templates:ro -v /opt/mechacorps:/opt/mechacorps -v /var/opt/mechacorpsgames/Src:/var/opt/mechacorpsgames/Src --network host --group-add 111 --group-add 995 --group-add 1000'
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '20'))
            timeout(time: 45, unit: 'MINUTES')
        }

        triggers {
            cron(config.cron ?: 'H H(2-5) * * *')
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            TARGET_BRANCH = "${config.branch}"
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                    sh 'git clean -fdx'
                }
            }

            stage('determinism-harness-replay') {
                // mcd-build-agent is the Linux + Godot agent image used by
                // the rest of CI; v1 is intentionally not cross-arch.
                steps {
                    script {
                        mcdDeterminismHarness.runNightly(config.determinismHarness ?: [:])
                    }
                }
                post {
                    always {
                        script {
                            try {
                                junit allowEmptyResults: true, skipPublishingChecks: true, testResults: config.determinismHarness?.junitGlob ?: 'test-results/determinism-harness/**/*.xml'
                            } catch (NoSuchMethodError e) {
                                echo "JUnit plugin not installed - skipping determinism harness report publishing"
                            }
                            archiveArtifacts artifacts: 'test-results/determinism-harness/**', allowEmptyArchive: true, fingerprint: true
                        }
                    }
                }
            }
        }

        post {
            unsuccessful {
                script {
                    def buildUrl = "${env.JENKINS_URL_BASE}/job/${config.jobName}/${BUILD_NUMBER}/console"
                    discordNotify.simple(
                        "Determinism harness nightly needs attention - ${config.branch} - View: ${buildUrl}",
                        "15158332"
                    )
                }
            }
        }
    }
}

return this
