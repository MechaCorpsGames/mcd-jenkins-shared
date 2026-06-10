// MechaCorps Google Play Upload Pipeline - Shared Library
// Standalone job: reads the Android AAB archived by a client build and ships it to
// Google Play via the Android Publisher API (fastlane `supply`). Triggered manually.
//
// This mirrors mcdSteamUploadPipeline. Steam sets a build "live" on a named branch;
// Play assigns a build to a *track*. The analogy:
//
//     Steam branch  staging/main (beta)   ->  Play track  internal   (instant, <=100 testers, no review)
//     Steam branch  (closed beta)         ->  Play track  alpha      (closed testing)
//     Steam branch  default (public flip) ->  Play track  production (gated by rollout / draft)
//
// Two actions:
//   upload-from-build : take the AAB an MCDClient-* build archived, push it to a track.
//   promote-track     : move the build already on one track to another, with NO re-upload
//                       (the same signed bytes are promoted — Play's edge over Steam).
//
// Server<->track guard: a client build bakes in its server URL at build time. MCDClient-Main
// talks to the dev server, MCDClient-Release to prod. So a Main (dev) build must never reach
// the production track — this pipeline refuses that combination.

def call(Map config) {
    // Required config:
    //   jobName: 'MCDPlay-Upload'

    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                // Mount the Jenkins jobs dir read-only so we can read another job's
                // archived AAB, exactly like the Steam pipeline does. No keystore mount
                // needed — the AAB is already signed by the client build, and the Play
                // service-account JSON arrives via the credentials store.
                args '-v /var/lib/jenkins/jobs:/var/lib/jenkins/jobs:ro --network host'
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }

        parameters {
            choice(
                name: 'ACTION',
                choices: ['upload-from-build', 'promote-track'],
                description: 'upload-from-build: push a client build\'s AAB to a track. promote-track: move the build already on PROMOTE_FROM up to TRACK with no re-upload.'
            )
            choice(
                name: 'SOURCE_JOB',
                choices: ['MCDClient-Main', 'MCDClient-Release'],
                description: '[upload-from-build] Which client build to take the AAB from. Main = dev server (beta tracks only). Release = prod server (eligible for production).'
            )
            string(
                name: 'SOURCE_BUILD',
                defaultValue: '',
                description: '[upload-from-build] Build number (leave empty for the latest successful build that archived an AAB).'
            )
            choice(
                name: 'TRACK',
                choices: ['internal', 'alpha', 'production'],
                description: 'Target Play track. internal = instant private beta (no review). alpha = closed testing. production = public store (gated by RELEASE_STATUS / ROLLOUT).'
            )
            choice(
                name: 'PROMOTE_FROM',
                choices: ['internal', 'alpha', 'production'],
                description: '[promote-track] The track whose current build you want to promote INTO the TRACK above.'
            )
            string(
                name: 'ROLLOUT',
                defaultValue: '1.0',
                description: 'Staged-rollout fraction (0.1 = 10%, 1.0 = 100%). Only applied when RELEASE_STATUS is inProgress/halted. Use <1.0 for a staged production launch.'
            )
            choice(
                name: 'RELEASE_STATUS',
                choices: ['completed', 'draft', 'inProgress', 'halted'],
                description: 'completed = live to the whole track. draft = uploaded but NOT released (flip manually in Play Console — mirrors the Steam default-branch flip). inProgress = staged rollout to ROLLOUT fraction. halted = pause an in-progress rollout.'
            )
        }

        environment {
            DISCORD_WEBHOOK  = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            // Secret-file credential: the env var resolves to the path of the JSON key file.
            PLAY_JSON_KEY    = credentials('google-play-service-account')
            PACKAGE_NAME     = 'com.mechacorpsgames.mechacorpsdraft'
        }

        stages {
            stage('Resolve & Guard') {
                steps {
                    script {
                        def rollout = (params.ROLLOUT ?: '1.0').trim()
                        if (!(rollout ==~ /^(0(\.\d+)?|1(\.0+)?)$/)) {
                            error "ROLLOUT must be a fraction between 0 and 1 (e.g. 0.1, 0.5, 1.0). Got: '${rollout}'"
                        }
                        if (params.RELEASE_STATUS == 'inProgress' && rollout == '1.0') {
                            error "RELEASE_STATUS=inProgress needs ROLLOUT < 1.0. For a full release use RELEASE_STATUS=completed."
                        }
                        if (params.RELEASE_STATUS == 'completed' && rollout != '1.0') {
                            echo "Note: RELEASE_STATUS=completed ignores ROLLOUT — releasing to 100%."
                        }

                        // Server<->track guard (upload path): never push a dev-server build to production.
                        if (params.ACTION == 'upload-from-build' &&
                            params.TRACK == 'production' &&
                            params.SOURCE_JOB != 'MCDClient-Release') {
                            error "Refusing to upload a ${params.SOURCE_JOB} build to PRODUCTION. " +
                                  "Production only carries prod-server (MCDClient-Release) builds — a Main build points at the dev server. " +
                                  "Use MCDClient-Release, or target internal/alpha."
                        }
                        // Don't allow a blind internal->production leap.
                        if (params.ACTION == 'promote-track' &&
                            params.TRACK == 'production' &&
                            params.PROMOTE_FROM == 'internal') {
                            error "Refusing to promote internal -> production directly. Promote internal -> alpha, validate, then alpha -> production."
                        }

                        // Promote params into env so single-quoted sh blocks can read them
                        // (params are NOT auto-exported to the shell; env vars are).
                        env.TRACK            = params.TRACK
                        env.RELEASE_STATUS   = params.RELEASE_STATUS
                        env.PROMOTE_FROM     = params.PROMOTE_FROM
                        env.ROLLOUT_FRACTION = rollout

                        echo "Action: ${params.ACTION} | Track: ${env.TRACK} | Status: ${env.RELEASE_STATUS} | Rollout: ${env.ROLLOUT_FRACTION}"
                    }
                }
            }

            stage('Locate AAB Artifact') {
                when { expression { params.ACTION == 'upload-from-build' } }
                steps {
                    script {
                        def jobDir = "/var/lib/jenkins/jobs/${params.SOURCE_JOB}/builds"
                        def buildNum = params.SOURCE_BUILD?.trim()

                        if (!buildNum || !buildNum.isNumber()) {
                            buildNum = sh(
                                script: "ls -1d ${jobDir}/*/archive 2>/dev/null | sort -t/ -k8 -n | tail -1 | grep -oP '\\d+(?=/archive)'",
                                returnStdout: true
                            ).trim()
                            if (!buildNum) { error "No builds with artifacts found for ${params.SOURCE_JOB}" }
                            echo "Using latest build with artifacts: #${buildNum}"
                        }
                        env.SOURCE_BUILD_NUM = buildNum

                        def archiveDir = "${jobDir}/${buildNum}/archive"
                        def manifestPath = sh(script: "find ${archiveDir} -name manifest.json | head -1", returnStdout: true).trim()
                        if (!manifestPath) { error "No manifest.json found in ${params.SOURCE_JOB} #${buildNum}" }
                        env.ARTIFACT_DIR = sh(script: "dirname ${manifestPath}", returnStdout: true).trim()

                        env.CLIENT_VERSION = sh(script: "grep -oP '\"clientVersion\"\\s*:\\s*\"\\K[^\"]+' ${manifestPath}", returnStdout: true).trim()
                        env.SOURCE_BRANCH  = sh(script: "grep -oP '\"branch\"\\s*:\\s*\"\\K[^\"]+' ${manifestPath}", returnStdout: true).trim()
                        env.SOURCE_COMMIT  = sh(script: "grep -oP '\"commit\"\\s*:\\s*\"\\K[^\"]+' ${manifestPath} || echo manual", returnStdout: true).trim()

                        def aab = sh(script: "find ${env.ARTIFACT_DIR} -name '*Android*.aab' | head -1", returnStdout: true).trim()
                        if (!aab) {
                            error "No Android AAB in ${params.SOURCE_JOB} #${buildNum}. The client build only produces an AAB once the upload keystore credential (android-upload-keystore) is configured — see docs/play-store/README.md."
                        }
                        env.AAB_PATH = aab
                        env.ANDROID_VERSION_CODE = sh(script: "grep -oP '\"versionCode\"\\s*:\\s*\\K[0-9]+' ${manifestPath} | head -1 || echo ''", returnStdout: true).trim()

                        currentBuild.displayName = "#${BUILD_NUMBER} v${env.CLIENT_VERSION} -> ${params.TRACK}"
                        currentBuild.description = "${params.SOURCE_JOB} #${buildNum} (${env.SOURCE_BRANCH}) vc=${env.ANDROID_VERSION_CODE}"
                        echo "AAB: ${env.AAB_PATH}"
                        echo "versionCode=${env.ANDROID_VERSION_CODE} versionName=${env.CLIENT_VERSION}"
                        sh "ls -lh ${env.AAB_PATH}"
                    }
                }
            }

            stage('Prepare Release Notes') {
                when { expression { params.ACTION == 'upload-from-build' } }
                steps {
                    // fastlane supply reads changelogs from <metadata_path>/<locale>/changelogs/<versionCode>.txt
                    sh '''
                        set -e
                        mkdir -p supply_meta/en-US/changelogs
                        NOTE_FILE="supply_meta/en-US/changelogs/${ANDROID_VERSION_CODE}.txt"
                        echo "v${CLIENT_VERSION} (${SOURCE_BRANCH} @ ${SOURCE_COMMIT})" > "$NOTE_FILE"
                        echo "=== release note ($NOTE_FILE) ==="
                        cat "$NOTE_FILE"
                    '''
                }
            }

            stage('Upload to Play') {
                when { expression { params.ACTION == 'upload-from-build' } }
                steps {
                    retry(2) {
                        sh '''
                            set -e
                            ROLLOUT_ARG=""
                            if [ "$RELEASE_STATUS" = "inProgress" ] || [ "$RELEASE_STATUS" = "halted" ]; then
                                ROLLOUT_ARG="--rollout $ROLLOUT_FRACTION"
                            fi
                            fastlane supply \
                                --json_key "$PLAY_JSON_KEY" \
                                --package_name "$PACKAGE_NAME" \
                                --aab "$AAB_PATH" \
                                --track "$TRACK" \
                                --release_status "$RELEASE_STATUS" \
                                $ROLLOUT_ARG \
                                --metadata_path supply_meta \
                                --skip_upload_apk true \
                                --skip_upload_metadata true \
                                --skip_upload_images true \
                                --skip_upload_screenshots true
                        '''
                    }
                }
            }

            stage('Promote Track') {
                when { expression { params.ACTION == 'promote-track' } }
                steps {
                    retry(2) {
                        // No AAB upload — supply promotes the release currently on $PROMOTE_FROM
                        // (the same signed bytes) up to $TRACK.
                        sh '''
                            set -e
                            ROLLOUT_ARG=""
                            if [ "$RELEASE_STATUS" = "inProgress" ] || [ "$RELEASE_STATUS" = "halted" ]; then
                                ROLLOUT_ARG="--rollout $ROLLOUT_FRACTION"
                            fi
                            fastlane supply \
                                --json_key "$PLAY_JSON_KEY" \
                                --package_name "$PACKAGE_NAME" \
                                --track "$PROMOTE_FROM" \
                                --track_promote_to "$TRACK" \
                                --release_status "$RELEASE_STATUS" \
                                $ROLLOUT_ARG \
                                --skip_upload_changelogs true
                        '''
                    }
                }
            }
        }

        post {
            success {
                script {
                    def msg = (params.ACTION == 'promote-track')
                        ? "✅ Promoted Play build `${params.PROMOTE_FROM}` → `${params.TRACK}` (${env.RELEASE_STATUS})"
                        : "✅ Uploaded AAB to Play `${params.TRACK}` (status ${env.RELEASE_STATUS}, rollout ${env.ROLLOUT_FRACTION})"
                    discordNotify.success(
                        title: "Play Upload",
                        message: msg,
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: params.TRACK,
                        branch: env.SOURCE_BRANCH ?: '—',
                        version: env.CLIENT_VERSION ?: '—'
                    )
                }
            }
            failure {
                script {
                    discordNotify.failure(
                        title: "Play Upload",
                        message: "❌ Google Play upload failed (track ${params.TRACK})",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: params.TRACK,
                        branch: env.SOURCE_BRANCH ?: 'unknown'
                    )
                }
            }
        }
    }
}

return this
