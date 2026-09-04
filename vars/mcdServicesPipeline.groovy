// MechaCorps Services Pipeline - Shared Library (main branch only)
// Deploys shared infrastructure: CrashReporting+MCP, Wiki sync, Monitoring
// Each service is independently catchError-wrapped so one failure
// marks the build UNSTABLE without blocking other deploys.

def call(Map config) {
    // Required config:
    //   branch: 'main'
    //   webhookToken: 'mcd-crash-reporting'
    //   jobName: 'MCDServices-Main'

    // The deploy tree. EVERY stage below builds and deploys out of this
    // directory, NOT out of the Jenkins workspace `checkout scm` populates —
    // the compose files, the gitignored .env.* secrets, and the bind-mounted
    // log volumes all live here. It therefore has to be synced to the branch
    // being deployed before anything reads it (see 'Sync Src Tree'), exactly
    // as mcdAppServicesPipeline does for its own srcRoot.
    //
    // The whole repo root is mounted, not just Src/: the sync needs .git, which
    // lives at the root. Mounting only Src/ is what let this tree drift.
    def srcRoot = '/var/opt/mechacorpsgames'
    def srcDir = "${srcRoot}/Src"

    // The remote the deploy tree is recovered/bootstrapped from. Deliberately
    // NOT ${GIT_URL} (the job's SCM URL, which is HTTPS): this build container
    // mounts only the SSH deploy key — /var/lib/jenkins/.ssh, see the agent
    // args below — and carries no HTTPS credentials. A tree recovered from
    // GIT_URL therefore authenticates against nothing and silently stops
    // syncing, which is what left MCDServices-Main red for ~15h on 2026-08-06
    // (MCDClient mc-t4m3). Overridable via config for callers on another repo.
    def deployRemote = config.deployRemote ?: 'git@github.com:MechaCorpsGames/MCDClient.git'

    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                args "-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /opt/mechacorps:/opt/mechacorps -v ${srcRoot}:${srcRoot} --network host --group-add 111 --group-add 995 --group-add 1000"
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '10'))
            // Serialize builds of this job. Every stage below reads and writes
            // ONE shared deploy tree (srcRoot, above), so two builds running at
            // once fight over it. Only MCDServices-Main uses this pipeline, so
            // serializing the job serializes srcRoot.
            //
            // That race is what took #563 down on 2026-08-18 (MCDClient
            // mc-2upj). #562 and #563 overlapped (#563 was handed the
            // "MCDServices-Main@2" workspace, which is Jenkins saying the base
            // one was still busy), both ran `git fetch origin --prune` in
            // srcRoot, and #562 won. #563's fetch then failed on exactly the
            // seven refs #562 had just moved, each one reading:
            //
            //   cannot lock ref 'refs/remotes/origin/main':
            //     is at 2e98e919 but expected 3212b14a
            //
            // i.e. the loser found its own update already applied. The four
            // brand-new branches in the same fetch succeeded, because git skips
            // the old-value check when it CREATES a remote-tracking ref. That
            // asymmetry looks like packed-refs corruption and is not: nothing
            // was wedged, and #564 went green with no intervention.
            //
            // The fetch error is the loud half. The quiet half is worse: #562
            // logged "Synced /var/opt/mechacorpsgames to 2e98e919", which is
            // #563's commit, not its own (b84e72f), and then deployed the wiki
            // out of that tree and reported SUCCESS. A build must not deploy a
            // tree another build reset underneath it. That is the same class of
            // failure the Sync Src Tree comment below exists to prevent.
            //
            // Do NOT "fix" the fetch by retrying it. A retry lets the losing
            // build win the second time and reset srcRoot while the other build
            // is still building out of it, which turns a red build into a
            // silently wrong deploy.
            //
            // Same guard, same reason, as mcdAppServicesPipeline. This pipeline
            // lifted that one's Sync Src Tree stage without the option that
            // makes the stage safe.
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
                            echo "No valid before SHA — deploying everything"
                            env.CRASH_REPORTING_CHANGED = 'true'
                            env.WIKI_CHANGED = 'true'
                            env.MONITORING_CHANGED = 'true'
                        } else {
                            sh "git fetch origin ${baseRef} 2>/dev/null || true"
                            def changes = mcdChangeDetection.detect(baseRef)
                            env.CRASH_REPORTING_CHANGED = changes.crashReportingChanged.toString()
                            env.WIKI_CHANGED = changes.wikiChanged.toString()
                            env.MONITORING_CHANGED = changes.monitoringChanged.toString()
                        }

                        def anyWork = (env.CRASH_REPORTING_CHANGED == 'true' ||
                                       env.WIKI_CHANGED == 'true' ||
                                       env.MONITORING_CHANGED == 'true')
                        if (!anyWork) {
                            currentBuild.description += "\n⏭️ No service changes — skipped"
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
                            env.CRASH_REPORTING_CHANGED = 'false'
                            env.WIKI_CHANGED = 'false'
                            env.MONITORING_CHANGED = 'false'
                        }
                    }
                }
            }

            // ================================================================
            // Each service stage is catchError-wrapped: one failure marks
            // the build UNSTABLE but does NOT block other services.
            // ================================================================

            // Pin the deploy tree to the branch being deployed BEFORE any stage
            // builds out of it. Without this the docker builds below compiled
            // whatever source happened to be sitting in srcRoot, so a deploy
            // could rebuild the image from scratch (--no-cache), recreate the
            // container, pass its health check, and ship code weeks old — a
            // green build that changed nothing. That is how the crash-reporting
            // service ran a 2026-07-21 fix's parent commit until 2026-08-06
            // (MCDClient issue #2350).
            //
            // Lifted from mcdAppServicesPipeline's 'Sync Src Tree', including
            // the ownership repair and the git-init recovery, so both pipelines
            // treat their deploy trees the same way.
            stage('Sync Src Tree') {
                when {
                    expression {
                        env.CRASH_REPORTING_CHANGED == 'true' ||
                        env.WIKI_CHANGED == 'true' ||
                        env.MONITORING_CHANGED == 'true'
                    }
                }
                steps {
                    sh """
                        set -e

                        # Repair ownership if root-owned files crept in (e.g. from
                        # a manual sudo rsync or a docker build that wrote as root).
                        # Without this, git checkout fails with "Permission denied".
                        # This runs inside a Docker build-agent container, so host
                        # sudo is not available — spawn a throwaway Alpine container
                        # via the mounted Docker socket instead, which runs as root.
                        if [ -d ${srcRoot} ]; then
                            if find ${srcRoot} -maxdepth 2 ! -user \$(id -u) -print -quit 2>/dev/null | grep -q .; then
                                echo "Repairing ownership on ${srcRoot} (foreign-owned files detected)"
                                docker run --rm -v ${srcRoot}:${srcRoot} alpine chown -R \$(id -u):\$(id -g) ${srcRoot}
                            fi
                        fi

                        if [ ! -d ${srcRoot}/.git ]; then
                            if [ -d ${srcRoot} ] && [ "\$(ls -A ${srcRoot} 2>/dev/null)" ]; then
                                # Directory exists with files but no .git — recover in
                                # place rather than wiping gitignored secrets
                                # (.env.crash-reporting, .env.monitoring).
                                echo "Recovering ${srcRoot}: exists without .git, initializing in place"
                                cd ${srcRoot}
                                git init -q
                                git remote add origin "${deployRemote}"
                            else
                                echo "Bootstrapping ${srcRoot} from ${deployRemote}"
                                git clone "${deployRemote}" ${srcRoot}
                            fi
                        fi
                        cd ${srcRoot}

                        # Fail here, loudly, rather than deep inside the fetch.
                        # An HTTPS remote dies with "could not read Username for
                        # 'https://github.com'" several layers down, which reads
                        # like a transient network fault; the tree then quietly
                        # stops tracking the branch and every later stage deploys
                        # stale source that still builds green.
                        #
                        # Two URLs matter and they can disagree: the RAW configured
                        # value, and the EFFECTIVE one git actually fetches from
                        # after url.<base>.insteadOf rewrites. `git remote get-url`
                        # expands insteadOf; `git config --get remote.origin.url`
                        # does not. A mismatch IS the rewrite — the half of the
                        # 2026-08-06 breakage that a correct-looking origin hid.
                        RAW_REMOTE=\$(git config --get remote.origin.url || true)
                        EFFECTIVE_REMOTE=\$(git remote get-url origin 2>/dev/null || true)
                        echo "Deploy tree remote: raw='\$RAW_REMOTE' effective='\$EFFECTIVE_REMOTE'"
                        if [ "\$RAW_REMOTE" != "\$EFFECTIVE_REMOTE" ]; then
                            echo "NOTE: a url.*.insteadOf rewrite is rewriting this remote (raw != effective)."
                            echo "      Inspect with: git config --get-regexp '^url\\.'"
                        fi
                        case "\$EFFECTIVE_REMOTE" in
                            https://*)
                                echo "ERROR: ${srcRoot} origin resolves to HTTPS: \$EFFECTIVE_REMOTE"
                                echo "       This container has no HTTPS credentials — only the SSH deploy key is mounted."
                                echo "       Expected: ${deployRemote}"
                                echo "       Fix on the deploy host:"
                                echo "         git -C ${srcRoot} remote set-url origin ${deployRemote}"
                                echo "         git config --global --get-regexp '^url\\.'   # then --unset-all any SSH->HTTPS rewrite"
                                exit 1
                                ;;
                        esac

                        git fetch origin --prune
                        # -f -B: force-create-or-reset the local branch to
                        # origin/<branch>, overwriting untracked files that would
                        # otherwise collide on a freshly git-init'd deploy dir.
                        git checkout -f -B ${config.branch} origin/${config.branch}
                        # -fd (not -fdx): preserve gitignored secrets and the
                        # bind-mounted logs/ directory.
                        git clean -fd
                        echo "Synced ${srcRoot} to \$(git rev-parse --short HEAD) on ${config.branch}"
                    """
                }
            }

            stage('Deploy CrashReporting + MCP') {
                when { expression { env.CRASH_REPORTING_CHANGED == 'true' } }
                steps {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        script {
                            echo "CrashReporting/MCP changed — building and deploying"

                            sh """
                                cd Src/CrashReporting
                                CGO_ENABLED=0 GOOS=linux GOWORK=off go build -o crash-reporting .
                                echo "✓ CrashReporting binary built"

                                cd ../MCPServer
                                CGO_ENABLED=0 GOOS=linux GOWORK=off go build -o mcp-server .
                                echo "✓ MCPServer binary built"

                                cd ${srcDir}
                                docker compose -p src -f docker-compose.crash-reporting.yml --env-file .env.crash-reporting build --no-cache crash-reporting mcp-server
                                docker compose -p src -f docker-compose.crash-reporting.yml --env-file .env.crash-reporting up -d --force-recreate crash-reporting mcp-server
                                sleep 5

                                PASS=true
                                for SVC in "Log Bundler:8090" "MCP Server:8095"; do
                                    NAME="\${SVC%%:*}"
                                    PORT="\${SVC##*:}"
                                    OK=false
                                    for i in \$(seq 1 10); do
                                        RESULT=\$(curl -s -o /dev/null -w '%{http_code}' http://localhost:\$PORT/health || true)
                                        if [ "\$RESULT" = "200" ]; then
                                            echo "✓ \$NAME health check passed"
                                            OK=true
                                            break
                                        fi
                                        echo "Waiting for \$NAME... (attempt \$i/10)"
                                        sleep 3
                                    done
                                    if [ "\$OK" = "false" ]; then
                                        echo "✗ \$NAME health check failed"
                                        PASS=false
                                    fi
                                done
                                if [ "\$PASS" = "false" ]; then
                                    cd ${srcDir}
                                    docker compose -f docker-compose.crash-reporting.yml --env-file .env.crash-reporting -p src logs --tail=50 crash-reporting mcp-server
                                    exit 1
                                fi
                            """
                            env.CRASH_REPORTING_DEPLOYED = "true"
                        }
                    }
                }
            }

            stage('Sync Wiki') {
                when { expression { env.WIKI_CHANGED == 'true' } }
                steps {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        script {
                            echo "Wiki content changed — syncing to Wiki.js"
                            withCredentials([
                                usernamePassword(credentialsId: 'wiki-credentials',
                                                 usernameVariable: 'WIKI_EMAIL',
                                                 passwordVariable: 'WIKI_PASSWORD')
                            ]) {
                                sh """
                                    export WIKI_URL=http://localhost:8070
                                    cd ${srcRoot}
                                    python3 Src/Wiki/load_wiki_pages.py
                                """
                            }
                            env.WIKI_SYNCED = "true"
                        }
                    }
                }
            }

            stage('Deploy Monitoring') {
                when { expression { env.MONITORING_CHANGED == 'true' } }
                steps {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        script {
                            echo "Monitoring config changed — redeploying stack"

                            sh """
                                cd ${srcDir}/Monitoring
                                docker compose -f docker-compose.monitoring.yml --env-file ${srcDir}/.env.monitoring up -d --force-recreate
                                sleep 5

                                OK=false
                                for i in \$(seq 1 10); do
                                    RESULT=\$(curl -s -o /dev/null -w '%{http_code}' http://localhost:9090/-/ready || true)
                                    if [ "\$RESULT" = "200" ]; then
                                        echo "✓ Prometheus is ready"
                                        OK=true
                                        break
                                    fi
                                    sleep 3
                                done
                                if [ "\$OK" = "false" ]; then
                                    echo "✗ Prometheus health check failed"
                                    exit 1
                                fi

                                curl -s -X POST http://localhost:9090/-/reload || true
                                echo "✓ Monitoring stack redeployed"
                            """
                            env.MONITORING_DEPLOYED = "true"
                        }
                    }
                }
            }
        }

        post {
            success {
                script {
                    def deployNotes = []
                    if (env.CRASH_REPORTING_DEPLOYED == "true") deployNotes << "CrashReporting+MCP"
                    if (env.WIKI_SYNCED == "true") deployNotes << "Wiki"
                    if (env.MONITORING_DEPLOYED == "true") deployNotes << "Monitoring"

                    if (deployNotes.isEmpty()) {
                        echo "No service changes detected — nothing deployed"
                        return
                    }

                    discordNotify.success(
                        title: "MechaCorps Services Deploy",
                        message: "✅ Deployed: ${deployNotes.join(', ')}",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: "production",
                        branch: config.branch,
                        version: env.SVC_VERSION
                    )
                }
            }
            unstable {
                script {
                    def deployed = []
                    def failed = []
                    if (env.CRASH_REPORTING_DEPLOYED == "true") deployed << "CrashReporting+MCP"
                    else if (env.CRASH_REPORTING_CHANGED == 'true') failed << "CrashReporting+MCP"
                    if (env.WIKI_SYNCED == "true") deployed << "Wiki"
                    else if (env.WIKI_CHANGED == 'true') failed << "Wiki"
                    if (env.MONITORING_DEPLOYED == "true") deployed << "Monitoring"
                    else if (env.MONITORING_CHANGED == 'true') failed << "Monitoring"

                    def msg = "⚠️ Partial deploy"
                    if (deployed) msg += " — OK: ${deployed.join(', ')}"
                    if (failed) msg += " — FAILED: ${failed.join(', ')}"

                    discordNotify.failure(
                        title: "MechaCorps Services Deploy",
                        message: msg,
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: "production",
                        branch: config.branch
                    )
                }
            }
            failure {
                script {
                    discordNotify.failure(
                        title: "MechaCorps Services Deploy",
                        message: "❌ Deploy failed",
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
