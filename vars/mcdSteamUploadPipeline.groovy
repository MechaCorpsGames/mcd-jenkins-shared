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
                // /opt/mechacorps is mounted read-write so 'Publish Steam Signal' below can
                // write the latest-client-on-Steam file the proxy reads (mc-fxt7).
                // mcdServerPipeline already mounts the same path the same way, which is
                // why a file on the deploy host was chosen over a new service or endpoint.
                // The --group-add list is copied from mcdServerPipeline for the same reason:
                // that job is the one that demonstrably writes into /opt/mechacorps today,
                // so its supplementary groups are the known-good answer rather than a guess.
                // If the write is still refused, 'Publish Steam Signal' marks the build
                // UNSTABLE and names the cause; it never fails a successful upload.
                args '-v /var/lib/jenkins/.steam:/home/jenkins/Steam:rw -v /var/lib/jenkins/jobs:/var/lib/jenkins/jobs:ro -v /opt/mechacorps:/opt/mechacorps --network host --group-add 111 --group-add 995 --group-add 1000'
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

                        // The protocol this client build speaks, for the signal stage below
                        // (mc-fxt7). Already written into manifest.json by the client
                        // pipeline's 'Generate Compatibility Manifest' stage, so nothing new
                        // is computed here. Empty if an older source build predates that
                        // stage; the signal stage refuses to write a signal it cannot fill in.
                        env.CLIENT_PROTOCOL_VERSION = sh(
                            script: "grep -oP '\"protocolVersion\"\\s*:\\s*\\K[0-9]+' ${manifestPath} || true",
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

            // Records what is now downloadable from Steam, for the proxy to relay
            // to connected clients (bead mc-fxt7, design mc-cdjn). Two values: the
            // client build that just went up, and the protocol it speaks. The proxy
            // passes them on; the client compares them against itself and decides
            // whether to offer the player an update.
            //
            // WHY THIS STAGE AND NOT THE CLIENT PIPELINE. The obvious home looks
            // like mcdClientPipeline's 'Publish to Steam' stage, but that stage only
            // fires this job with wait:false and returns, so it never learns whether
            // the upload worked. Writing the signal there would announce
            // "downloadable now" the instant an upload STARTED, which is the one
            // thing this signal must never do: a player told to restart for a build
            // that is not there yet has no way to succeed.
            //
            // WHY AFTER 'Upload to Steam' AND NOT INSIDE IT. Declarative fails a
            // stage whose sh step exits non-zero, and a failed stage stops the
            // pipeline, so a stage placed after the upload runs only when steamcmd
            // genuinely finished. That is structural rather than a convention
            // someone has to keep: there is no edit to the stage above that lets a
            // failed upload fall through into this one.
            //
            // THE OTHER THREE WAYS IT COULD STILL LIE, ALL GUARDED:
            //   * a superseded build never ran steamcmd  -> UPLOAD_SUPERSEDED gate
            //   * STEAM_BRANCH=default uploads WITHOUT set-live ("flip public
            //     manually in Steamworks"), so nothing became downloadable
            //                                            -> skipped by the same when{}
            //   * an old source build has no protocolVersion in its manifest
            //                                            -> refuse, never guess
            stage('Publish Steam Signal') {
                when {
                    expression {
                        env.UPLOAD_SUPERSEDED != 'true' && params.STEAM_BRANCH != 'default'
                    }
                }
                steps {
                    script {
                        if (!env.CLIENT_PROTOCOL_VERSION) {
                            // No invented default. This value drives the client's
                            // hard-block decision, so "unknown" must stay unknown: the
                            // proxy keeps serving the previous signal and no player is
                            // told anything false.
                            echo "No protocolVersion in the source manifest, so no Steam signal written. " +
                                 "Source build ${env.SOURCE_BUILD_NUM} predates 'Generate Compatibility Manifest'."
                            return
                        }

                        // Keyed by STEAM BRANCH, not by deploy path. A client built from
                        // the release branch uploads to the Steam 'staging' beta while
                        // pointing at production, so branch-to-environment is genuinely
                        // ambiguous and is not guessed here. Each proxy is pointed at the
                        // file for the branch its players are on, via --steam-signal-file.
                        String signalDir  = '/opt/mechacorps/steam-signals'
                        String signalPath = "${signalDir}/${params.STEAM_BRANCH}.json"

                        // Written to a temp file and renamed so a reader polling this path
                        // never sees a half-written signal: rename(2) inside one directory
                        // is atomic. set -euo pipefail so a failed mkdir or write fails the
                        // stage instead of leaving a stale signal in place unnoticed.
                        // returnStatus, not a bare sh: the upload has ALREADY SUCCEEDED by
                        // the time this runs, and a build that reports "Steam upload failed"
                        // to Discord because a side-channel file could not be written would
                        // send somebody chasing an outage that did not happen. A failed write
                        // is real (players stop being nudged), so it goes to UNSTABLE with a
                        // named cause rather than being swallowed.
                        int status = sh(returnStatus: true, script: """
                            set -euo pipefail
                            mkdir -p ${signalDir}
                            cat > ${signalPath}.tmp << EOF
{
    "clientVersion": "${env.CLIENT_VERSION}",
    "protocolVersion": ${env.CLIENT_PROTOCOL_VERSION},
    "steamBranch": "${params.STEAM_BRANCH}",
    "sourceJob": "${params.SOURCE_JOB}",
    "sourceBuild": "${env.SOURCE_BUILD_NUM}",
    "uploadedAt": "\$(date -Iseconds)"
}
EOF
                            mv ${signalPath}.tmp ${signalPath}
                            echo "Wrote ${signalPath}:"
                            cat ${signalPath}
                        """)

                        if (status != 0) {
                            echo "Could not write ${signalPath} (exit ${status}). The upload itself succeeded; " +
                                 "connected clients will keep seeing the PREVIOUS signal until this is fixed."
                            mcdUnstableReason("the latest-client-on-Steam signal was not written (exit ${status}). " +
                                              "players will not be told this build is available")
                            currentBuild.result = 'UNSTABLE'
                        }
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

            // Declarative runs post{success} only on SUCCESS, so without this an
            // UNSTABLE build notifies nobody. 'Publish Steam Signal' is the only
            // thing here that can go soft, and the whole point of that stage is that
            // a client learns an update exists, so a silent warning would put us back
            // where we were before the signal: players never told to update, and no
            // one aware of it. Same shape as mcdClientPipeline's unstable handler,
            // including reading env.UNSTABLE_REASON rather than a build phase.
            unstable {
                script {
                    if (env.UPLOAD_SUPERSEDED == 'true') {
                        return
                    }
                    def reason = env.UNSTABLE_REASON?.trim()
                    def detail = reason
                        ? "⚠️ Uploaded to Steam, but ${reason}"
                        : "⚠️ Uploaded to Steam with warnings, see the console log"
                    discordNotify.unstable(
                        title: "Steam Upload",
                        message: detail,
                        reason: reason,
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: params.STEAM_BRANCH,
                        branch: env.SOURCE_BRANCH ?: 'unknown'
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
