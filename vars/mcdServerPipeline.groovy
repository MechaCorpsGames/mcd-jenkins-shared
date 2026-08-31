// MechaCorps Server Pipeline - Shared Library
// Builds: GameServer, Proxy, TestClient
// Deploys with zero-downtime to environment-specific paths/ports

def call(Map config) {
    // Required config:
    //   branch: 'main' or 'release'
    //   environment: 'development' or 'production'
    //   deployPath: '/opt/mechacorps/main' or '/opt/mechacorps/release'
    //   tcpPort: 43069 or 42069
    //   wsPort: 43070 or 42070
    //   serverHost: 'dev.mechacorpsgames.com' or 'play.mechacorpsgames.com'
    //   webhookToken: 'mcdserver-main' or 'mcdserver-release'
    //   jobName: 'MCDServer-Main' or 'MCDServer-Release'

    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                args '-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /var/lib/jenkins/.android:/var/lib/jenkins/.android:ro -v /opt/mechacorps:/opt/mechacorps -v /var/opt/mechacorpsgames/Src:/var/opt/mechacorpsgames/Src --network host --group-add 111 --group-add 995 --group-add 1000'
            }
        }

        options {
            // numToKeepStr is a HARD ceiling on everything a build leaves behind:
            // it discards the whole build record, console log and artifacts
            // together. artifactDaysToKeepStr and artifactNumToKeepStr can only
            // delete artifacts EARLIER from builds that still exist; neither can
            // extend anything past numToKeepStr.
            //
            // At 10 it was far too short to diagnose an intermittent failure.
            // Measured 2026-08-24: asking Jenkins for 25 builds returned 10, and
            // those ten (#943 10:19Z through #952 15:54Z) spanned 5h35m. That is
            // the real retention window, not the 7 days artifactDaysToKeepStr
            // suggests at a glance. mc-n37x's own evidence, build #908, was a 404
            // before anyone could read it, and the Integration Test capture added
            // in PR 100 was archiving into that same 5-hour window.
            //
            // 60 covers roughly a week at the observed rate (~9 builds/day) and
            // costs almost nothing, because what it preserves is the console log,
            // which is where the integration stage prints its confirm/refute
            // verdict. artifactNumToKeepStr stays at 10 deliberately: raising it
            // multiplies this job's artifact footprint (MCDServer, MCDProxy,
            // MCDTestClient plus debug symbols) on a shared agent, and the free
            // disk on that agent could not be measured from here. That half is
            // tracked separately rather than guessed at.
            buildDiscarder(logRotator(numToKeepStr: '60', artifactDaysToKeepStr: '7', artifactNumToKeepStr: '10'))

            // SERIALIZE THIS JOB AGAINST ITSELF (bead mc-ehn1).
            //
            // This option was missing until 2026-08-27, and the 'Trim to Latest'
            // comment below asserted it was already here, which is how it stayed
            // missing. What that cost, on MCDServer-Main, 03:46-03:47Z that night:
            // five builds started inside 60 seconds, #1006/#1007/#1008 ran the full
            // pipeline CONCURRENTLY in workspaces MCDServer-Main, @2 and @3, and
            // they finished out of order. #1006 was building ef5b60f06, two commits
            // BEHIND #1008's 066852d43, and it wrote versions/latest.txt 548ms
            // later. The proxy reads that file to choose the binary it spawns, so
            // dev.mechacorpsgames.com served two-commit-stale code until a human
            // repointed it by hand. The same overlap also removed #1006's proxy
            // container between compose resolving it and compose recreating it, so
            // a deploy that actually WORKED reported FAILURE and took 'Cleanup Old
            // Versions' down with it.
            //
            // Bare, never abortPrevious: aborting has no stage scoping and would
            // interrupt the publishing rsync in 'Deploy GameServer & TestClient'.
            // That exclusion is pinned by
            // test_mcd_superseded_build_cancellation.py::test_publishing_pipelines_never_abort_mid_rsync.
            //
            // Queueing rather than killing is also what makes 'Trim to Latest'
            // work at all here: see the stage comment for why the trim could not
            // fire while this job ran its bursts in parallel.
            disableConcurrentBuilds()
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            BRANCH_NAME = "${config.branch}"
            DEPLOY_ENV = "${config.environment}"
            DEPLOY_PATH = "${config.deployPath}"
            TCP_PORT = "${config.tcpPort}"
            WS_PORT = "${config.wsPort}"
            SERVER_HOST = "${config.serverHost}"
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'ref', value: '$.ref'],
                    [key: 'repo_name', value: '$.repository.full_name'],
                    [key: 'commit_sha', value: '$.after'],
                    [key: 'commit_message', value: '$.head_commit.message'],
                    [key: 'commit_author', value: '$.head_commit.author.name'],
                    [key: 'pusher_name', value: '$.pusher.name'],
                    [key: 'commits_count', value: '$.commits.length()'],
                    [key: 'before_sha', value: '$.before']
                ],
                causeString: "Triggered by push to ${config.branch}",
                token: config.webhookToken,
                tokenCredentialId: '',
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
                // COALESCE A MERGE BURST INTO ONE BUILD (bead mc-h2nm2).
                //
                // Tim, 2026-08-31: "trim the jenkins builds so only the latest
                // build actually builds."
                //
                // Jenkins collapses queued items of the same job only when their
                // PARAMETERS are identical. Left at its default of false, this
                // plugin stamps a fresh
                // `jenkins-generic-webhook-trigger-plugin_uuid` StringParameterValue
                // on every invocation, so no two pushes ever look alike and
                // nothing can ever coalesce. Combined with
                // disableConcurrentBuilds() above, a burst SERIALIZES instead:
                // nine merges in four minutes queued 8 MCDServer builds and 4
                // MCDAppServices builds, each destined to check out the same tip
                // and repeat the same 10-20 minutes of work.
                //
                // Read out of the installed plugin (generic-webhook-trigger
                // 2.3.1) rather than its documentation:
                // GenericTrigger.trigger() passes this field as the third
                // argument to ParameterActionUtil.createParameterAction, and
                // that method adds the uuid parameter if and only if the
                // argument is FALSE. The field has four bytecode references in
                // the whole plugin: the declaration, its setter, its getter, and
                // that one call. It does nothing else.
                //
                // These jobs declare no build parameters of their own
                // (ParametersDefinitionProperty is absent from all sixteen
                // webhook job configs), so with the uuid gone a queued item
                // carries NO parameters and Jenkins' own coalescing does the
                // whole job: no queue policing, no aborts, nothing to interrupt
                // the rsync in the publish stages that
                // ADR 2026-08-25-superseded-cancel-goes-where-nothing-is-published
                // exists to protect.
                //
                // The webhook's variables are NOT parameters, so coalescing
                // cannot merge the wrong commit into a build: the build checks
                // out the branch TIP either way. Measured on the controller, the
                // surviving build keeps the FIRST push's variables and records
                // all five causes, which is the safe direction for 'Detect
                // Changes' below: baseRef = env.before_sha then spans the whole
                // burst rather than only its last push.
                //
                // NOT ON mcdPRValidationPipeline. See the comment there.
                allowSeveralTriggersPerBuild: true,
                // Filter on the ref ONLY. Path filtering deliberately does not
                // happen here: see the 'Detect Changes' stage below, which is
                // what actually decides whether this build does any work.
                //
                // This used to also match against $.commits[*].{added,modified,
                // removed}[*]. Those JSONPaths flatten to one env var per file
                // PLUS an aggregate var holding the whole list as a single
                // string, and the plugin injects all of them into the build
                // environment. A cascade merge made that aggregate exceed the
                // kernel's MAX_ARG_STRLEN (131,072 B, the cap on ONE env
                // string, not on the environment as a whole), after which every
                // exec from the build JVM failed with E2BIG. The build died on
                // its first exec, `git init`, while cloning THIS library,
                // before any Jenkinsfile ran. See MCDClient bead mc-4qz7: the
                // measured client pushes carried a 171,999 B and a 221,028 B
                // files_added. The server jobs share the same trigger shape and
                // the same cascade pushes, so they were one cascade away from
                // the identical failure.
                //
                // Dropping the path filter cannot skip a build that used to
                // run: the trigger was ANDed with SERVER_CHANGED /
                // MCP_GAME_SERVER_CHANGED, and those gates are still here. It
                // only lets a few more builds start and immediately mark
                // themselves NOT_BUILT. Every other pipeline in this library
                // (app services, services, discord bot) already triggers on
                // $ref alone for the same reason.
                regexpFilterText: '$ref',
                regexpFilterExpression: "refs/heads/${config.branch}"
            )
        }

        stages {
            stage('Setup Build Info') {
                steps {
                    script {
                        // SERVER_VERSION is derived from Src/GameServer/CMakeLists.txt
                        // in the Checkout stage — the repo is not on disk yet here, so
                        // the display name starts without a version and gains it there.
                        // This pipeline stamped a private "0.1.${BUILD_NUMBER}" from
                        // its initial commit while CMake shipped 1.0.x and then 0.2.x:
                        // every displayName, manifest serverVersion and Discord line
                        // disagreed with the binary and its deploy path (mc-glpn,
                        // found via mc-bs84's v0.2.873).
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

                        // GitHub username for Discord mentions
                        env.BUILD_GITHUB_USER = env.pusher_name ?: ''
                        if (!env.BUILD_GITHUB_USER && author != 'Unknown') {
                            def buildCause2 = currentBuild.getBuildCauses('hudson.model.Cause$UserIdCause')
                            if (buildCause2 && buildCause2.size() > 0) {
                                env.BUILD_GITHUB_USER = buildCause2[0].userId ?: ''
                            }
                        }

                        currentBuild.description = "${commitMsg}\nby ${author} → ${config.environment}"

                        env.BUILD_ENV = sh(script: 'uname -s -r', returnStdout: true).trim()
                        env.GCC_VERSION = sh(script: 'gcc --version | head -1', returnStdout: true).trim()
                        env.GO_VERSION = sh(script: 'go version | cut -d" " -f3', returnStdout: true).trim()

                        echo "Branch: ${config.branch}"
                        echo "Environment: ${config.environment}"
                        echo "Deploy path: ${config.deployPath}"
                        echo "Ports: TCP=${config.tcpPort}, WS=${config.wsPort}"
                    }
                }
            }

            stage('Checkout') {
                steps {
                    checkout scm
                    script {
                        // THE VERSION HAS ONE SOURCE OF TRUTH (mc-glpn).
                        // Src/GameServer/CMakeLists.txt says "Developers control
                        // MAJOR.MINOR, CI/CD controls BUILD" — so MAJOR.MINOR are read
                        // from it and only BUILD_NUMBER belongs to CI. A parse failure
                        // refuses to stamp a made-up version instead of defaulting:
                        // a silent fallback is the two-sources bug with extra steps.
                        // grep|head|tr runs without pipefail on purpose — a missing
                        // line yields empty output here and the error() below, not an
                        // opaque nonzero sh exit.
                        def major = sh(script: 'grep "^set(VERSION_MAJOR " Src/GameServer/CMakeLists.txt | head -1 | tr -dc "0-9"', returnStdout: true).trim()
                        def minor = sh(script: 'grep "^set(VERSION_MINOR " Src/GameServer/CMakeLists.txt | head -1 | tr -dc "0-9"', returnStdout: true).trim()
                        if (!(major ==~ /\d+/) || !(minor ==~ /\d+/)) {
                            error("mc-glpn: could not derive VERSION_MAJOR/VERSION_MINOR from Src/GameServer/CMakeLists.txt (got '${major}' / '${minor}') — refusing to stamp a made-up version")
                        }
                        env.SERVER_VERSION = "${major}.${minor}.${BUILD_NUMBER}"
                        def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
                        currentBuild.displayName = "#${BUILD_NUMBER} v${env.SERVER_VERSION} (${shortSha})"
                        echo "Server Version: ${env.SERVER_VERSION} (MAJOR.MINOR from Src/GameServer/CMakeLists.txt)"
                    }
                }
            }

            stage('Detect Changes') {
                steps {
                    script {
                        def baseRef = env.before_sha
                        if (!baseRef || baseRef.startsWith('0000000')) {
                            echo "No valid before SHA — building everything"
                            env.SERVER_CHANGED = 'true'
                            env.MCP_GAME_SERVER_CHANGED = 'true'
                        } else {
                            // Ensure the before SHA is available locally
                            sh "git fetch origin ${baseRef} 2>/dev/null || true"
                            def changes = mcdChangeDetection.detect(baseRef)
                            env.SERVER_CHANGED = changes.serverChanged.toString()
                            env.MCP_GAME_SERVER_CHANGED = changes.mcpGameServerChanged.toString()
                        }

                        if (env.SERVER_CHANGED != 'true' && env.MCP_GAME_SERVER_CHANGED != 'true') {
                            currentBuild.description += "\n⏭️ No server changes — skipped"
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
            // THAT PARAGRAPH WAS FALSE FOR THIS FILE UNTIL 2026-08-27, and the
            // consequence is worth keeping written down (mc-ehn1). It describes
            // the mechanism's precondition, queued builds all landing on the same
            // branch tip, as though this pipeline satisfied it. It did not:
            // options{} carried no disableConcurrentBuilds() at all, the only
            // mention of it in this file was this comment, and so bursts here ran
            // in PARALLEL. Parallel builds each check out the tip at the moment
            // THEY run, which during a burst is a different commit per build
            // (#1006 got ef5b60f06, #1007 d2d5130e8, #1008 066852d43).
            //
            // AN EARLIER VERSION OF THIS COMMENT SAID THE TRIM WAS "STRUCTURALLY
            // UNABLE TO FIRE" HERE. That was overstated and the build records
            // disprove it: in that very burst, #1009 and #1010 were BOTH trimmed
            // (built by #1007), and #999 trimmed earlier the same way. The true
            // statement is narrower:
            //
            //   THE TRIM REACHES THE QUEUED TAIL. IT CANNOT REACH THE CONCURRENT
            //   HEAD.
            //
            // #1009 and #1010 sat queued behind an executor, so by the time they
            // STARTED, #1007 had finished SUCCESS on the same tip and both of
            // builtBy()'s conditions were satisfiable. #1006/#1007/#1008 ran
            // genuinely in parallel on three different commits, so for those three
            // neither condition could hold. What parallelism defeats is the
            // mechanism's REACH, not the mechanism: three builds escaped ahead of
            // it, and those three are exactly the ones that raced on latest.txt.
            //
            // The option is now present, so the paragraph above is true and the
            // trim reaches the whole burst rather than only its tail. Removing the
            // option does not make this stage inert, which is the easy misreading;
            // it shrinks its reach back to the queued tail and lets the parallel
            // head race the pointer again.
            //
            // READING THE BUILD LIST: a trimmed build presents as every stage
            // "skipped due to when conditional". Do NOT read those skips as "no
            // relevant paths changed" on this job. #1009 and #1010 were first
            // characterised that way and it was wrong.
            //
            // It only ever skips a commit an EARLIER build of this job already
            // built and succeeded on, so it cannot strand a deploy: see
            // mcdRedundantBuild.groovy for why that direction matters, and why
            // this is not abortPrevious.
            stage('Trim to Latest') {
                steps {
                    script {
                        if (mcdRedundantBuild.trim()) {
                            env.SERVER_CHANGED = 'false'
                            env.MCP_GAME_SERVER_CHANGED = 'false'
                        }
                    }
                }
            }

            stage('Go Lint') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh '''
                        # Pinned (mc-qhu): unpinned @latest broke unrelated PRs whenever
                        # upstream released a new linter set. Bump deliberately in a
                        # follow-up PR after triaging any new findings.
                        go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.12.0
                        export PATH="$(go env GOPATH)/bin:$PATH"
                        make lint
                    '''
                }
            }

            // Data/GameData/ is gitignored — it's a build artifact produced
            // from Data/Cards/* by Src/MCDCoreExt/export_done_cards.py against
            // the DONE-cards registry. It must exist BEFORE the build stage:
            // the MCDServerTest binary loads its card library from
            // Data/GameData/Cards/* at runtime, and on branches with the
            // MCD_DATA_DIR repoint (MCDClient bead mc-0xm) the GameServer
            // `cmake --install` also copies Data/GameData/ into each versioned
            // server dir. On branches where the GameData refactor hasn't
            // landed (e.g. the older release branch) the target may not exist;
            // the `|| echo ...` fallback keeps the pipeline backward-compatible.
            stage('Populate GameData') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh 'make export-done || echo "[Populate GameData] make target missing on this branch — skipping"'
                }
            }

            stage('Build GameServer, TestClient & Proxy') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        rm -rf bin/versions/v* bin/testclient-versions/v*
                        cd Src
                        chmod +x deploy.sh
                        ./deploy.sh --clean --release --build-number ${BUILD_NUMBER}
                    """
                }
            }

            stage('Build WASM Bots') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    // bots/*.wasm are the artifacts the proxy loads via
                    // --bot-dir. Post-MCDClient #1425 (Nix → Docker dev shell
                    // migration), `make wasm-bots` is the only variant — it
                    // shells out to scripts/docker_dev.py to build inside the
                    // mcd/wasm-build container, so the Rust/clang/lld/nodejs
                    // toolchain comes from the image instead of requiring
                    // the build agent host to ship it.
                    //
                    // catchError → UNSTABLE because the current build-agent
                    // docker doesn't accept `buildx build --load` (older CLI
                    // version), so the wasm-build image fails to build. The
                    // proxy can still ship — bots are loaded at runtime from
                    // bots/ on the deployed VM, which retains previous-build
                    // artifacts until a successful build replaces them. Tracked
                    // separately; remove the catch when the agent has a
                    // buildx-capable docker.
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        // script{} is required: `steps` is declarative and accepts
                        // steps only, so a bare try/catch fails to COMPILE the
                        // whole pipeline — "Expected a step @ line 210". That took
                        // MCDServer-Main and MCDServer-FeatureBackend down entirely
                        // from #82 until mc-lg8x. Do not unwrap it.
                        script {
                            try {
                                sh 'make wasm-bots'
                            } catch (hudson.AbortException e) {
                                // Name the cause here and rethrow, leaving catchError
                                // to do the marking exactly as before: this is the
                                // only point that knows the wasm image is what went
                                // soft, and post{ unstable } has no way to work it
                                // out afterwards (bead mjs-q4x). Narrowed to
                                // AbortException — what a non-zero sh throws — so an
                                // aborted or interrupted build is never mislabelled
                                // as a wasm failure.
                                mcdUnstableReason('WASM bot build failed (the proxy is shipping the bots from the previous build)')
                                throw e
                            }
                        }
                    }
                }
            }

            stage('Verify Build') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        env.SERVER_VERSION_PATH = readFile('bin/versions/latest.txt').trim()
                        env.TESTCLIENT_VERSION_PATH = readFile('bin/testclient-versions/latest.txt').trim()
                        // CMake wrote latest.txt from its own PROJECT_VERSION; the
                        // SERVER_VERSION derived at Checkout must be the same string
                        // or the two version sources have split again — fail here,
                        // loudly, not in a release-ordering query months later
                        // (mc-glpn).
                        if (env.SERVER_VERSION_PATH != "v${env.SERVER_VERSION}/MCDServer") {
                            error("mc-glpn: version drift — CMake produced '${env.SERVER_VERSION_PATH}' but the pipeline derived 'v${env.SERVER_VERSION}/MCDServer'")
                        }
                        sh "test -x 'bin/versions/${env.SERVER_VERSION_PATH}'"
                        sh "test -x 'bin/testclient-versions/${env.TESTCLIENT_VERSION_PATH}'"
                        sh "test -x 'bin/MCDProxy'"

                        env.SERVER_SIZE = sh(script: "du -h bin/versions/${env.SERVER_VERSION_PATH} | cut -f1", returnStdout: true).trim()
                        env.TESTCLIENT_SIZE = sh(script: "du -h bin/testclient-versions/${env.TESTCLIENT_VERSION_PATH} | cut -f1", returnStdout: true).trim()
                        env.PROXY_SIZE = sh(script: "du -h bin/MCDProxy | cut -f1", returnStdout: true).trim()

                        echo "Build verified: v${env.SERVER_VERSION} (Server: ${env.SERVER_SIZE}, TestClient: ${env.TESTCLIENT_SIZE}, Proxy: ${env.PROXY_SIZE})"
                    }
                }
            }

            // Validated Card Data Pipeline (MCDClient bead mc-8ko, plan §4):
            // validate the *generated* Data/GameData/ tree right after it is
            // populated and before any consumer (Unit Tests / packaging) runs.
            // Gated on config.validateGameData so it is inert for pipelines that
            // do not opt in (main/release stay unaffected while the corpus is
            // cleaned up — plan §7). config.validateGameDataHardFail flips it from
            // a soft (UNSTABLE) gate to a blocking one.
            stage('Validate GameData') {
                when {
                    expression { env.SERVER_CHANGED == 'true' && config.validateGameData }
                }
                steps {
                    mcdValidateGameData(hardFail: config.validateGameDataHardFail ?: false)
                }
            }

            // Hermetic build (MCDClient bead mc-0xm, plan §5, Scenario 3): with
            // the generated data exported and validated, relocate the AUTHORING
            // data (Data/Cards + Data/References) out of the workspace so every
            // stage below — Unit Tests, Integration Test, artifact staging —
            // proves nothing reads authoring data. Gated on
            // config.stripAuthoringData: a branch opts in once its tests read
            // generated Data/GameData/ only (features/card first). The build
            // finishes stripped; the next build's checkout restores the tree.
            stage('Strip Authoring Data') {
                when {
                    expression { env.SERVER_CHANGED == 'true' && config.stripAuthoringData }
                }
                steps {
                    mcdStripAuthoringData()
                }
            }

            stage('Unit Tests') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        cd Src/GameServer
                        ./build.sh --test --release
                    """
                }
            }

            stage('Proxy Unit Tests') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh 'make test-proxy'
                }
            }

            stage('Integration Test') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        def testResult = sh(script: '''
                            set +e

                            echo "Starting integration test..."

                            # mc-521yc. The server arms a 120s timer on EVERY
                            # wait it opens on a player (kDefaultTimeoutSeconds
                            # in Src/GameServer/Phases/WaitTimeout.cpp), but the
                            # test clients below get 180s total for a match that
                            # normally finishes in about 80. One unanswered
                            # prompt therefore spent two thirds of the budget,
                            # and the run died at the clients' 180s cap two
                            # phases downstream of the actual stall, wearing a
                            # dropped-socket signature it did not cause. That is
                            # what made mc-n37x cost three days and three
                            # instrumentation PRs.
                            #
                            # 30s sits comfortably above the slowest real bot
                            # decision measured here (4.8s) and well under the
                            # client budget, so a stall now costs 30s, the match
                            # still finishes, and the run fails at the real place
                            # instead of at the cap.
                            #
                            # This reaches the GameServer without a code change:
                            # WaitTimeout::Initialize() reads the variable at
                            # startup, and the proxy spawns the server with
                            # exec.Command and never sets cmd.Env, so the child
                            # inherits this shell's environment.
                            export MCD_TURN_TIMEOUT_SECONDS=30

                            TEST_TCP_PORT=$((30000 + (BUILD_NUMBER % 10000)))
                            TEST_WS_PORT=$((40000 + (BUILD_NUMBER % 10000)))

                            # Get TestClient path from latest.txt
                            TESTCLIENT_PATH="bin/testclient-versions/$(cat bin/testclient-versions/latest.txt)"

                            # Logs live in the WORKSPACE, not /tmp, so the whole
                            # file can be archived as a build artifact. The 20-line
                            # tails below stay for quick console reading, but they
                            # are not enough to diagnose the intermittent timeout
                            # in mc-n37x: the proxy closes a player connection
                            # WELL BEFORE the end of the run, so the evidence has
                            # already scrolled out of a 20-line tail by the time
                            # the clients hit their 180s cap.
                            LOG_DIR=integration-logs
                            rm -rf "$LOG_DIR"
                            mkdir -p "$LOG_DIR"

                            cleanup() {
                                echo ""
                                echo "=== Proxy Log (last 20 lines) ==="
                                tail -20 "$LOG_DIR/proxy.log" 2>/dev/null || echo "(no proxy log)"
                                echo ""
                                echo "=== Proxy stdout/stderr (expected empty, see the --log-dir note below) ==="
                                tail -5 "$LOG_DIR/proxy-stdout.log" 2>/dev/null || echo "(none)"
                                echo ""
                                # The proxy passes the same --log-dir to every GameServer
                                # it spawns (Src/Proxy/main.go:2610), so the server's own
                                # account of the match lands under $LOG_DIR in
                                # <gameID>/server-stdout.log and <gameID>/proxy/proxy.log,
                                # NOT beside proxy.log. A non-recursive glob misses it.
                                echo "=== Newest game-server logs under $LOG_DIR (last 40 lines each) ==="
                                for f in $(find "$LOG_DIR" -mindepth 2 -name '*.log' -printf '%T@ %p\n' 2>/dev/null \
                                           | sort -rn | head -3 | cut -d' ' -f2-); do
                                    echo "--- $f ---"
                                    tail -40 "$f"
                                done
                                echo ""
                                echo "=== Client 1 Log (last 15 lines) ==="
                                tail -15 "$LOG_DIR/client1.log" 2>/dev/null || echo "(no client1 log)"
                                echo ""
                                echo "=== Client 2 Log (last 15 lines) ==="
                                tail -15 "$LOG_DIR/client2.log" 2>/dev/null || echo "(no client2 log)"

                                # mc-n37x signature scan. The suspected cause is
                                # Player.sendToPlayer (Src/Proxy/main.go:602 on
                                # MCDClient e548eaf14): when a player's 128-deep send
                                # channel fills, the proxy closes that player's
                                # connection outright, which presents to the peer as
                                # Connection_PlayerDisconnected mid-match and then
                                # nobody reconnects. The second candidate with the same
                                # outward signature is writePump's 10-second write
                                # deadline at :576, which logs "player write error"
                                # instead, so the scan names both.
                                #
                                # EVERY VERDICT BELOW CITES THE FILE IT READ AND ITS
                                # SIZE, and an empty or missing log is reported as "scan
                                # did not run", never as a negative result. The first
                                # version of this scan read a file that was empty by
                                # construction and printed "hypothesis NOT confirmed by
                                # this run" on every single failure. Nothing about that
                                # output said which file it had read or that the file
                                # held nothing, so it read as a refutation for two days.
                                # An instrument that cannot see the thing it is aimed at
                                # is a nuisance; one that reports not seeing it as
                                # evidence of absence is worse. A negative that cites its
                                # own source cannot rot that way again.
                                echo ""
                                echo "=== Proxy disconnect scan (mc-n37x) ==="
                                PROXY_LOG="$LOG_DIR/proxy.log"
                                if [ ! -s "$PROXY_LOG" ]; then
                                    if [ -e "$PROXY_LOG" ]; then
                                        echo "SCAN DID NOT RUN: $PROXY_LOG exists but is empty (0 bytes)."
                                    else
                                        echo "SCAN DID NOT RUN: $PROXY_LOG does not exist."
                                    fi
                                    echo "  This is NOT a result and refutes nothing. MCDProxy writes its"
                                    echo "  log to <--log-dir>/proxy.log and nothing to stdout or stderr"
                                    echo "  (Src/Proxy/main.go:66 and :842-858). An empty file here means"
                                    echo "  the proxy was not given --log-dir '$LOG_DIR', or it died before"
                                    echo "  logging was initialised. Files actually present:"
                                    find "$LOG_DIR" -type f -printf '    %10s bytes  %p\n' 2>/dev/null \
                                        | sort -k3 || echo "    (none)"
                                else
                                    echo "scanned $PROXY_LOG ($(wc -c < "$PROXY_LOG") bytes, $(wc -l < "$PROXY_LOG") lines)"
                                    if grep -n "send channel full" "$PROXY_LOG" 2>/dev/null; then
                                        echo "^^ CONFIRMS the mc-n37x hypothesis: the proxy dropped a player"
                                        echo "   because its send channel filled, not because the client left."
                                    elif grep -n "player write error" "$PROXY_LOG" 2>/dev/null; then
                                        echo "^^ NOT the send-channel path, but the same outward signature:"
                                        echo "   writePump hit a socket write error or its 10s write deadline"
                                        echo "   and closed the connection (Src/Proxy/main.go:576)."
                                    else
                                        echo "NEITHER 'send channel full' NOR 'player write error' appears in"
                                        echo "the $(wc -l < "$PROXY_LOG") lines of $PROXY_LOG scanned above."
                                        echo "That is a real negative for BOTH proxy-side disconnect paths on"
                                        echo "this run: whatever dropped the player, the proxy did not choose"
                                        echo "to close the connection for either of those two reasons."
                                    fi
                                    echo "--- other disconnect/timeout lines ---"
                                    grep -niE "disconnect|reconnect|write error|timeout" "$PROXY_LOG" 2>/dev/null | head -30 \
                                        || echo "(none in $PROXY_LOG)"
                                fi

                                # mc-521yc: the wait timeline. The server now
                                # names every wait as it OPENS, not only when one
                                # expires, so a stall is attributable to the wait
                                # that hung rather than to whatever the run
                                # happened to be doing 120s later. The catch is
                                # that the server's own output lands under
                                # $LOG_DIR in <gameID>/server-stdout.log, which
                                # is exactly the kind of file nobody opened for
                                # three days. Print it here: per the archive note
                                # below, the CONSOLE log is what stays readable
                                # for about a week.
                                echo ""
                                echo "=== Server wait timeline (mc-521yc) ==="
                                SERVER_WAIT_LINES=$(grep -h -E "WAIT START|TIMEOUT" "$LOG_DIR"/*/server-stdout.log "$LOG_DIR"/game_*.log 2>/dev/null | tail -40)
                                if [ -n "$SERVER_WAIT_LINES" ]; then
                                    echo "$SERVER_WAIT_LINES"
                                else
                                    echo "(no WAIT START/TIMEOUT lines in the server logs under $LOG_DIR)"
                                fi


                                kill $PROXY_PID 2>/dev/null || true
                                kill $CLIENT1_PID $CLIENT2_PID 2>/dev/null || true
                            }
                            trap cleanup EXIT

                            # --log-dir is what makes every tail, grep and archive
                            # below read a file with something in it. MCDProxy writes
                            # NOTHING to stdout or stderr: Src/Proxy/main.go:66 defaults
                            # --log-dir to "logs", and main() at :842-858 opens
                            # <log-dir>/proxy.log and points BOTH log.SetOutput and
                            # slog.SetDefault at it, falling back to stderr only if that
                            # open fails. Without this flag the proxy's real log went to
                            # the workspace logs/ directory while this stage tailed,
                            # grepped and archived the empty shell redirect it had named
                            # proxy.log, so the mc-n37x scan below printed
                            # "NOT confirmed" on every run whether the warning fired or
                            # not. Keep stdout under a different name; it is expected to
                            # be empty and is captured only to catch a startup crash that
                            # happens before logging is initialised.
                            ./bin/MCDProxy -port $TEST_TCP_PORT -wsport $TEST_WS_PORT --log-dir "$LOG_DIR" > "$LOG_DIR/proxy-stdout.log" 2>&1 &
                            PROXY_PID=$!
                            echo "Test proxy started on TCP:$TEST_TCP_PORT, WS:$TEST_WS_PORT (PID: $PROXY_PID)"
                            sleep 3

                            if ! kill -0 $PROXY_PID 2>/dev/null; then
                                echo "ERROR: Proxy failed to start!"
                                exit 1
                            fi

                            $TESTCLIENT_PATH 127.0.0.1 $TEST_TCP_PORT TestBot1 0 --timeout=180 > "$LOG_DIR/client1.log" 2>&1 &
                            CLIENT1_PID=$!
                            sleep 1
                            $TESTCLIENT_PATH 127.0.0.1 $TEST_TCP_PORT TestBot2 1 --timeout=180 > "$LOG_DIR/client2.log" 2>&1 &
                            CLIENT2_PID=$!

                            echo "Test clients started (PIDs: $CLIENT1_PID, $CLIENT2_PID)"
                            echo "Waiting for game to complete (timeout: 180s)..."

                            wait $CLIENT1_PID
                            EXIT1=$?
                            wait $CLIENT2_PID
                            EXIT2=$?

                            echo ""
                            echo "=== Test Results ==="
                            echo "Client 1 exit code: $EXIT1"
                            echo "Client 2 exit code: $EXIT2"

                            if [ $EXIT1 -eq 0 ] && [ $EXIT2 -eq 0 ]; then
                                echo ""
                                echo "✓ Integration test PASSED - full game completed"
                                exit 0
                            else
                                echo ""
                                echo "✗ Integration test FAILED"
                                echo "  Client 1: exit code $EXIT1 (0=complete, 1=error, 2=timeout, 3=denied)"
                                echo "  Client 2: exit code $EXIT2"
                                exit 1
                            fi
                        ''', returnStatus: true)

                        if (testResult != 0) {
                            // Keep the FULL logs, not just the console tails.
                            // mc-n37x is intermittent (~1 run in 3) and its
                            // evidence is the proxy's own account of why it
                            // closed a player connection, which sits far above
                            // the tail.
                            //
                            // HOW LONG THESE ACTUALLY SURVIVE, stated precisely,
                            // because an earlier version of this comment claimed
                            // artifactDaysToKeepStr gave them a week and that was
                            // false. These artifacts live for the SHORTER of
                            // artifactNumToKeepStr (10 builds) and
                            // artifactDaysToKeepStr (7 days), and they are also
                            // destroyed outright when numToKeepStr discards the
                            // build record. At ~9 builds/day the binding limit is
                            // the 10-build one, so full logs are readable for
                            // roughly a day, NOT a week.
                            //
                            // What is readable for about a week is the CONSOLE
                            // log, which numToKeepStr now keeps for 60 builds and
                            // which carries the confirm/refute verdict printed by
                            // the disconnect scan above. Read the verdict there
                            // first; come here for the full logs only while the
                            // failing build is still recent.
                            // '**' not '*.log': the spawned GameServer's logs sit a
                            // level down in integration-logs/<gameID>/ (server-stdout.log,
                            // proxy/proxy.log) plus decisions.jsonl, and a flat glob
                            // silently leaves the server's half of the disconnect behind.
                            // A whole run is around 1 MB.
                            archiveArtifacts artifacts: 'integration-logs/**',
                                             allowEmptyArchive: true,
                                             fingerprint: false
                            error("Integration test failed")
                        }
                    }
                }
            }

            stage('MCP Game Server Tests') {
                // Hermetic Go test suite for the Claude-as-Player MCP harness.
                // Unit tests cover protocol codec, session/legal-actions, tools,
                // artifacts. Integration tests boot the MCP binary against an
                // in-process FakeProxy (one server / one match per ADR mc-4bi.1
                // §11.2) — no external Proxy/GameServer/AccountService stack.
                //
                // On failure the suite's dumpSessionLogOnFail helper prints
                // mcp_session.log via t.Logf, which `go test -v` captures into
                // mcp-test.log. That log is archived as the postmortem artifact
                // (logs/{gameUUID}/ live in t.TempDir() and are cleaned by the
                // test framework before this stage's post block runs).
                when {
                    allOf {
                        expression { env.MCP_GAME_SERVER_CHANGED == 'true' }
                        // Src/MCPGameServer/ doesn't exist on main yet (the
                        // module lives on a feature branch); guard so the
                        // stage no-ops on branches that don't ship the dir.
                        expression { fileExists('Src/MCPGameServer') }
                    }
                }
                steps {
                    sh '''#!/bin/bash
                        # -e is not optional here. A Jenkins sh step takes the
                        # exit status of the LAST command in the body, and this
                        # body has a shebang, so Jenkins runs it directly rather
                        # than through its default 'sh -xe'. Without -e the unit
                        # run below could fail and the stage still reported
                        # SUCCESS, because the integration run after it exited 0.
                        # That is how the protocol drift guard ran on every PR
                        # and had its verdict discarded (mc-91jj).
                        #
                        # -timeout is not optional either, and 4m is
                        # deliberately far under Go's 10m default. `go test
                        # ./...` buffers each package's output until that
                        # package finishes, so a hung test prints NOTHING.
                        # The stage simply goes quiet, Jenkins' durable-task
                        # wrapper eventually reports JENKINS-48300 ("wrapper
                        # script does not seem to be touching the log file"),
                        # and the build dies without ever naming the test that
                        # hung. Go's own timeout panics with a full goroutine
                        # dump identifying the stuck goroutine, so it has to
                        # fire FIRST: before the durable-task heartbeat window
                        # and before the 10m default. mc-11nh: MCD-PR-Main
                        # #1616, #1617, #1619, #1620, #1621 and #1622 each
                        # burned roughly 10 minutes on that silence and every
                        # one of them reported the JENKINS-48300 red herring
                        # instead of a test name. A healthy run of this stage
                        # takes about 53s (build #1633), so 4m is over 4x
                        # headroom.
                        set -euo pipefail
                        mkdir -p reports/mcp-game-server
                        cd Src/MCPGameServer
                        go test -v -timeout 4m ./... 2>&1 | tee ../../reports/mcp-game-server/unit.log
                        go test -v -timeout 4m -tags=integration ./integration_test/... 2>&1 | tee ../../reports/mcp-game-server/integration.log
                    '''
                }
                post {
                    failure {
                        archiveArtifacts artifacts: 'reports/mcp-game-server/**', allowEmptyArchive: true, fingerprint: true
                    }
                }
            }

            stage('Stage Artifacts') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        mkdir -p artifacts/server

                        cp bin/versions/${SERVER_VERSION_PATH} artifacts/server/MCDServer
                        cp bin/MCDProxy artifacts/server/
                        cp bin/testclient-versions/${TESTCLIENT_VERSION_PATH} artifacts/server/MCDTestClient
                        cp bin/versions/latest.txt artifacts/server/server-latest.txt
                        cp bin/testclient-versions/latest.txt artifacts/server/testclient-latest.txt

                        COMMIT_SHA_VAL="\${commit_sha:-manual}"
                        COMMIT_AUTHOR_VAL="\${commit_author:-Unknown}"
                        cat > artifacts/server/BUILD_INFO.txt << EOF
Build Number: ${BUILD_NUMBER}
Version: ${SERVER_VERSION}
Branch: ${BRANCH_NAME}
Environment: ${DEPLOY_ENV}
Server Host: ${SERVER_HOST}
Ports: TCP=${TCP_PORT}, WS=${WS_PORT}
Date: \$(date -Iseconds)
Commit: \$COMMIT_SHA_VAL
Author: \$COMMIT_AUTHOR_VAL
Build System: ${BUILD_ENV}
GCC Version: ${GCC_VERSION}
Go Version: ${GO_VERSION}
EOF

                        echo "" >> artifacts/server/BUILD_INFO.txt
                        echo "Binary Sizes:" >> artifacts/server/BUILD_INFO.txt
                        ls -lh artifacts/server/MCDServer artifacts/server/MCDProxy artifacts/server/MCDTestClient | awk '{print "  " \$9 ": " \$5}' >> artifacts/server/BUILD_INFO.txt
                    """
                }
            }

            // Same contract as the client pipeline's stage of this name: a
            // failed upload is reported, never swallowed, because a crash
            // without symbols tells us nothing but the signal number.
            stage('Upload Debug Symbols') {
                when {
                    expression { env.SERVER_CHANGED == 'true' && fileExists('bin/versions') }
                }
                steps {
                    script {
                        def sentryCliExists = sh(script: 'which sentry-cli', returnStatus: true) == 0
                        if (!sentryCliExists) {
                            env.SYMBOLS_UPLOADED = 'false'
                            echo "⚠️ sentry-cli is not installed on the build agent, so server debug symbols were NOT uploaded. Native crashes from this build cannot be symbolicated."
                            mcdUnstableReason('debug symbols not uploaded — sentry-cli is missing from the build agent')
                            currentBuild.result = 'UNSTABLE'
                            return
                        }
                        // `set +x` first: Jenkins runs sh with -x, which used to
                        // echo the Sentry auth token into the console log.
                        def status = sh(returnStatus: true, script: """
                            set +x
                            SENTRY_ENV=/var/opt/mechacorpsgames/Src/.env.sentry
                            if [ ! -f "\$SENTRY_ENV" ]; then
                                echo "Cannot authenticate to Sentry: \$SENTRY_ENV does not exist on this agent."
                                exit 1
                            fi
                            SENTRY_AUTH_TOKEN=\$(sed -n 's/^SENTRY_TOKEN=//p' "\$SENTRY_ENV" | tr -d '"' | tr -d '[:space:]')
                            if [ -z "\$SENTRY_AUTH_TOKEN" ]; then
                                echo "Cannot authenticate to Sentry: SENTRY_TOKEN is empty in \$SENTRY_ENV."
                                exit 1
                            fi
                            export SENTRY_AUTH_TOKEN
                            set -e

                            # Only symbol-bearing artifacts. The build trees also contain .o objects
                            # under CMakeFiles/ and CMake compiler-probe binaries, which carry no
                            # symbolication value, inflate every upload, and are what Sentry rejected
                            # with "an unknown error occurred" on MCDClient-Main #1109 and #1110.
                            SYMBOL_PATHS="bin/versions/"
                            for d in Src/GameServer/build/Release; do
                                if [ -d "\$d" ]; then
                                    SYMBOL_PATHS="\$SYMBOL_PATHS \$(find "\$d" -type f \\( -name '*.so' -o -name '*.dll' -o -name '*.pdb' \\) | tr '\\n' ' ')"
                                fi
                            done

                            echo "Uploading server debug symbols for all platforms..."
                            # --wait: block until Sentry has processed the files, so the
                            # verification below cannot race the upload.
                            #
                            # The upload runs WITHOUT -e on purpose. sentry-cli exits non-zero if ANY
                            # file fails, and a Sentry-side "still processing"/"unknown error" on one
                            # file is not evidence that the shipped library is missing. The
                            # verification below asks the only question that matters -- is THIS
                            # binary's build id registered in Sentry -- so it, not the uploader's
                            # exit code, decides the stage. Failures are still reported, never
                            # swallowed: a failed verification marks the build UNSTABLE below.
                            # Sentry can also fail to PROCESS a file it already accepted, and that
                            # failure is transient. MCDServer-Main #873 got "An unknown error occurred"
                            # on the shipped binary; #875, #876, #877 and #880 uploaded the same
                            # artifact cleanly with no pipeline change in between. A single-shot upload
                            # turns that hiccup into a permanent hole, because nothing ever re-uploads
                            # symbols for a version that has already deployed -- a native crash in
                            # v0.2.873 will symbolicate to nothing for as long as that build exists.
                            # So retry the pair, and let the verifier say when to stop.
                            SYMBOL_UPLOAD_ATTEMPTS=3
                            SYMBOL_RETRY_DELAY=30
                            SYMBOL_ATTEMPT=1
                            while : ; do
                                set +e
                                sentry-cli --url https://us.sentry.io debug-files upload \
                                    --org mechacorps-llc --project mcd-server \
                                    --include-sources --wait \
                                    \$SYMBOL_PATHS
                                UPLOAD_STATUS=\$?
                                set -e
                                if [ "\$UPLOAD_STATUS" -ne 0 ]; then
                                    echo "sentry-cli exited \$UPLOAD_STATUS on attempt \$SYMBOL_ATTEMPT of \$SYMBOL_UPLOAD_ATTEMPTS. Verifying what actually reached Sentry rather than assuming nothing did."
                                fi

                                if [ ! -f scripts/verify_sentry_symbols.py ]; then
                                    echo "Cannot verify the upload: scripts/verify_sentry_symbols.py is not on this branch. Merge MCDClient main into this branch."
                                    exit 1
                                fi
                                echo "Verifying the deployed binary is registered in Sentry..."
                                # The verifier, not sentry-cli's exit code, is the loop's condition. It is
                                # the only thing here that asks whether THIS binary can symbolicate.
                                set +e
                                python3 scripts/verify_sentry_symbols.py \
                                    --org mechacorps-llc --project mcd-server \
                                    --url https://us.sentry.io \
                                    bin/versions/${SERVER_VERSION_PATH}
                                VERIFY_STATUS=\$?
                                set -e
                                if [ "\$VERIFY_STATUS" -eq 0 ]; then
                                    break
                                fi
                                if [ "\$SYMBOL_ATTEMPT" -ge "\$SYMBOL_UPLOAD_ATTEMPTS" ]; then
                                    echo "Symbols are still not in Sentry after \$SYMBOL_UPLOAD_ATTEMPTS attempts, so this is not a transient processing error."
                                    exit \$VERIFY_STATUS
                                fi
                                echo "Attempt \$SYMBOL_ATTEMPT of \$SYMBOL_UPLOAD_ATTEMPTS did not put the build id in Sentry. Retrying in \$SYMBOL_RETRY_DELAY seconds."
                                sleep \$SYMBOL_RETRY_DELAY
                                SYMBOL_ATTEMPT=\$((SYMBOL_ATTEMPT + 1))
                            done
                        """)
                        env.SYMBOLS_UPLOADED = status == 0 ? 'true' : 'false'
                        if (status != 0) {
                            echo "⚠️ Server debug symbols are NOT in Sentry for this build. Native crashes will symbolicate to <unknown> frames until this is fixed."
                            mcdUnstableReason('debug symbols missing from Sentry (native crashes will not symbolicate)')
                            currentBuild.result = 'UNSTABLE'
                        } else {
                            echo "✅ Debug symbols uploaded and verified against Sentry."
                        }
                    }
                }
            }

            stage('Generate Server Manifest') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        // The proxy routes an old client to the server binary that
                        // speaks its protocol, and learns which protocol a deployed
                        // version speaks from the manifest this value feeds (mc-mhjd).
                        // A wrong number here mis-routes every back-compat connection
                        // silently, so read it strictly: the old `|| echo '1'` fallback
                        // turned an unreadable header into a plausible-looking lie.
                        def protocolVersion = sh(
                            script: "grep -oP 'PROTOCOL_VERSION\\s*=\\s*\\K[0-9]+' Src/Include/protocol_ext.h",
                            returnStdout: true
                        ).trim()
                        if (!(protocolVersion ==~ /[0-9]+/)) {
                            error("mc-mhjd: could not read PROTOCOL_VERSION from Src/Include/protocol_ext.h (got '${protocolVersion}'). Deploying this build would publish a protocol manifest the proxy cannot trust.")
                        }

                        env.PROTOCOL_VERSION = protocolVersion

                        sh """
                            cat > artifacts/server/manifest.json << EOF
{
    "serverVersion": "${SERVER_VERSION}",
    "protocolVersion": ${PROTOCOL_VERSION},
    "buildNumber": ${BUILD_NUMBER},
    "branch": "${BRANCH_NAME}",
    "environment": "${DEPLOY_ENV}",
    "serverHost": "${SERVER_HOST}",
    "ports": {
        "tcp": ${TCP_PORT},
        "ws": ${WS_PORT}
    },
    "buildDate": "\$(date -Iseconds)",
    "commit": "\${commit_sha:-manual}"
}
EOF
                            echo "Generated server manifest.json:"
                            cat artifacts/server/manifest.json
                        """
                    }
                }
            }

            stage('Archive Artifacts') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    archiveArtifacts artifacts: 'artifacts/**/*', fingerprint: true
                }
            }

            stage('Deploy GameServer & TestClient') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        # PROTOCOL MANIFEST (mc-mhjd). The proxy maps a deployed
                        # version -> the protocol it speaks so it can route an old
                        # client to a server that still understands it (mc-cdjn), and
                        # it must do that WITHOUT executing the binary to ask. Write
                        # the protocol next to the binary here in the workspace and
                        # let the rsync below carry it across, so the manifest and the
                        # binary can never be deployed apart.
                        #
                        # The version directory is read from latest.txt, the same
                        # source 'Verify Build' pinned the binary path against, so
                        # protocol.txt cannot land beside a different version than the
                        # one being deployed. TestClient is built from this same tree
                        # and therefore speaks this same protocol; the bots are
                        # version-routed too (mc-cdjn Phase 2).
                        [ -n "\${PROTOCOL_VERSION}" ] || { echo "mc-mhjd: PROTOCOL_VERSION is unset at deploy. 'Generate Server Manifest' must run first"; exit 1; }
                        SERVER_VERSION_DIR="bin/versions/\$(dirname "\$(cat bin/versions/latest.txt)")"
                        TESTCLIENT_VERSION_DIR="bin/testclient-versions/\$(dirname "\$(cat bin/testclient-versions/latest.txt)")"
                        echo "\${PROTOCOL_VERSION}" > "\${SERVER_VERSION_DIR}/protocol.txt"
                        echo "\${PROTOCOL_VERSION}" > "\${TESTCLIENT_VERSION_DIR}/protocol.txt"
                        echo "✓ Protocol manifest: protocol \${PROTOCOL_VERSION} written to \${SERVER_VERSION_DIR}/protocol.txt and \${TESTCLIENT_VERSION_DIR}/protocol.txt"

                        # THE LEASE BASE, AND NOTHING BELOW IT. This mkdir stops at
                        # leases/ deliberately. PR #117 also created
                        # leases/versions and leases/testclient-versions here, and
                        # that broke every deploy on an environment whose leases/
                        # the proxy had already made: the proxy container runs as
                        # root, so leases/ lands on the host root-owned, and this
                        # deploy runs as an ordinary user that cannot mkdir inside
                        # it. MCDServer-FeatureCard #143, #144 and #145 all died on
                        #   mkdir: cannot create directory
                        #   '/opt/mechacorps/feature-card/leases/versions':
                        #   Permission denied
                        # and #117 was reverted by #119 (mc-0d26v).
                        #
                        # mkdir -p on a directory that ALREADY EXISTS succeeds
                        # whether or not this user could have created it, so this
                        # line is safe on those environments. On one where leases/
                        # does not exist yet it runs before the proxy starts and
                        # creates it as the deploy user, which is strictly better
                        # than letting Docker auto-create the bind mount source as
                        # root.
                        #
                        # Everything below leases/ stays the proxy's to create, and
                        # has to be: a per-version lease directory cannot be
                        # pre-created by a deploy that does not yet know the version
                        # name. The proxy makes them traversable by this user
                        # (Src/Proxy/lease.go, MCDClient #2855), which is what lets
                        # the two guards below read them at all.
                        mkdir -p ${config.deployPath}/versions ${config.deployPath}/testclient-versions ${config.deployPath}/Data/GameData ${config.deployPath}/leases
                        # --exclude latest.txt IS THE RACE FIX (mc-ehn1). Everything
                        # else in these directories is purely ADDITIVE: each build
                        # writes its own v<x>.<y>.<build>/ subtree and never touches
                        # another build's, so the payload rsyncs need no lock. The
                        # single shared, mutable, last-writer-wins byte in the whole
                        # deploy is latest.txt, the pointer the proxy reads to choose
                        # which binary to spawn. Carried inside the synced directory
                        # it was published by whichever build's rsync finished last,
                        # which on 2026-08-27 was a build two commits behind. It is
                        # now published separately, under a lock, and only forwards:
                        # see the guarded step below.
                        rsync -rlvz --no-group --exclude latest.txt bin/versions/ ${config.deployPath}/versions/
                        rsync -rlvz --no-group --exclude latest.txt bin/testclient-versions/ ${config.deployPath}/testclient-versions/
                        # MCDServer auto-detects its data dir as <binary-parent-2>/Data/GameData/Cards;
                        # the deployed binary at versions/v.../MCDServer resolves to ${config.deployPath}/Data/GameData/Cards.
                        # Without the rsync the server falls back to the compile-time path (the Jenkins
                        # workspace) and crashes immediately on the first card-data load.
                        # Skip if Data/GameData/ wasn't produced (older branches without the GameData refactor).
                        if [ -d Data/GameData ]; then \
                          rsync -rlvz --no-group --delete Data/GameData/ ${config.deployPath}/Data/GameData/; \
                          echo "✓ Deployed Data/GameData to ${config.environment}"; \
                        else \
                          echo "ℹ Data/GameData/ not present in workspace — skipping (release-branch path)"; \
                        fi
                    """
                    // PUBLISH THE TWO POINTERS TOGETHER, UNDER A LOCK, AND ONLY
                    // FORWARDS (mc-ehn1). Serializing this write is necessary but
                    // NOT sufficient: a lock alone still lets an older build take
                    // its turn second and politely overwrite a newer one, which is
                    // precisely what happened on 2026-08-27 (#1006 published 548ms
                    // after #1008 and was two commits behind). So the write also
                    // refuses to move backwards.
                    //
                    // KEYED ON BUILD NUMBER, not on the version string. Jenkins
                    // build numbers within a job are monotonically increasing and
                    // never reused, so "refuse a lower number" rejects exactly the
                    // slow-older-build case and can never reject a legitimately
                    // newer one. A manual re-run always carries a HIGHER number
                    // than everything before it, so the operator case that
                    // mcdRedundantBuild deliberately exempts is not blocked here
                    // either. Version strings could not do this job: they are
                    // per-branch and would compare across release lines.
                    //
                    // -lt rather than -le on purpose: a Replay of the SAME build
                    // number must still be able to republish, which is how an
                    // operator repairs a bad pointer without editing files by hand.
                    //
                    // FAILS OPEN when .published-build is absent or unreadable,
                    // which is every deploy path on the first run after this lands.
                    // Same direction as mcdRedundantBuild: an unknown previous
                    // state means publish, never means silently skip a deploy.
                    script {
                        env.POINTER_VERDICT = mcdDeployLock(deployPath: config.deployPath, returnStdout: true, """
            this_build="\${BUILD_NUMBER:-}"
            case "\$this_build" in
                ''|*[!0-9]*)
                    echo "mc-ehn1: BUILD_NUMBER is '\$this_build', not a number. Refusing to publish a pointer that cannot be ordered." >&2
                    exit 1
                    ;;
            esac

            previous=\$(cat "${config.deployPath}/.published-build" 2>/dev/null || echo 0)
            case "\$previous" in ''|*[!0-9]*) previous=0 ;; esac

            if [ "\$this_build" -lt "\$previous" ]; then
                echo "mc-ehn1: REFUSING to move ${config.deployPath} backwards. This build is #\$this_build; #\$previous already published and is newer. Leaving latest.txt untouched." >&2
                echo refused
                exit 0
            fi

            # Both copies land BEFORE either rename. A missing or unreadable
            # source then aborts while the live pointers are still intact,
            # rather than after one of the two has already moved.
            cp bin/versions/latest.txt "${config.deployPath}/versions/.latest.txt.new"
            cp bin/testclient-versions/latest.txt "${config.deployPath}/testclient-versions/.latest.txt.new"
            mv "${config.deployPath}/versions/.latest.txt.new" "${config.deployPath}/versions/latest.txt"
            mv "${config.deployPath}/testclient-versions/.latest.txt.new" "${config.deployPath}/testclient-versions/latest.txt"
            echo "\$this_build" > "${config.deployPath}/.published-build"
            echo published
                        """)

                        // Honest about what this does and does not guarantee: a
                        // crash BETWEEN the two mv's still leaves the server and
                        // testclient pointers split. What changed is the size of
                        // that window, from the span of two full rsyncs to the span
                        // of two renames with no I/O between them. On a POSIX
                        // filesystem with no transaction across two files, that is
                        // the floor; claiming the pointers are atomic together
                        // would be a lie written into a comment.
                        if (env.POINTER_VERDICT == 'refused') {
                            echo "⚠ Pointer NOT published for ${config.environment}: a newer build already published. " +
                                 "The version payload for this build was still deployed and remains available; " +
                                 "only latest.txt was left pointing at the newer build. This is the mc-ehn1 guard working."
                        } else {
                            echo "✓ Deployed GameServer to ${config.environment}: " + sh(script: "cat ${config.deployPath}/versions/latest.txt", returnStdout: true).trim()
                            echo "✓ Deployed TestClient to ${config.environment}: " + sh(script: "cat ${config.deployPath}/testclient-versions/latest.txt", returnStdout: true).trim()
                        }
                    }
                }
            }

            stage('Deploy Bots') {
                // Must land before the proxy container restart so docker-
                // compose mounts a populated ${deployPath}/bots:/app/bots.
                // --delete so an old .wasm removed from the repo doesn't
                // linger on the host and shadow the new manifest.
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        mkdir -p ${config.deployPath}/bots
                        rsync -rlvz --no-group --delete bots/ ${config.deployPath}/bots/
                        echo "✓ Deployed bots to ${config.environment}: \$(ls ${config.deployPath}/bots/*.wasm 2>/dev/null | wc -l) wasm bots"
                    """
                }
            }

            stage('Deploy Proxy (if changed)') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        def envFile = config.proxyEnvFile ?: '.env.proxy'
                        def composeProject = config.proxyProject ?: 'src'
                        def basePort = config.tcpPort - 69  // e.g., 42069 -> 42000
                        // Per-env CI-managed Godot project for practice-match bots.
                        // Populated by mcdClientPipeline's "Publish Bot Runtime"
                        // stage; the proxy mounts this path read-only.
                        def botProjectPath = config.botProjectPath ?: "${config.deployPath}/godot-bot-project"

                        // Generate proxy env file from pipeline config.
                        // Read Account Service API key from its env file so the proxy
                        // can fetch decks via the internal API.
                        def envSuffix = envFile.replace('.env.proxy.', '').replace('.env.proxy', 'main')
                        def accountEnvFile = ".env.account.${envSuffix}"
                        def accountApiKey = config.accountApiKey ?: ''
                        if (!accountApiKey) {
                            accountApiKey = sh(
                                script: "grep '^INTERNAL_API_KEY=' /var/opt/mechacorpsgames/Src/${accountEnvFile} 2>/dev/null | cut -d= -f2 || echo ''",
                                returnStdout: true
                            ).trim()
                        }
                        sh """
                            cat > /var/opt/mechacorpsgames/Src/${envFile} <<'ENVEOF'
# Auto-generated by mcdServerPipeline — do not edit manually
PROXY_TCP_PORT=${config.tcpPort}
PROXY_WS_PORT=${config.wsPort ?: config.tcpPort + 1}
PROXY_BASE_PORT=${basePort}
DEPLOY_PATH=${config.deployPath}
BOT_PROJECT_ROOT=${botProjectPath}
SERVER_SENTRY_DSN=${config.sentryDsn ?: ''}
CRASH_REPORT_URL=${config.crashReportUrl ?: "http://localhost:${basePort + 90}"}
AUTH_URL=${config.authUrl ?: "http://localhost:${basePort + 81}"}
AUTH_INTERNAL_KEY=${accountApiKey}
ACCOUNT_URL=${config.accountUrl ?: "http://localhost:${basePort + 82}"}
ACCOUNT_API_KEY=${accountApiKey}
CRASH_REPORTING_API_KEY=${config.crashReportKey ?: ''}
BOT_WS_PORT=${config.botWsPort ?: basePort + 71}
BOT_ACCOUNT=${config.botAccount ?: ''}
BOT_PASSWORD=${config.botPassword ?: ''}
# Test-only: set to "-gauntlet-instant-resolve" via the per-env Jenkinsfile to
# resolve gauntlet matches via 50/50 coin flip instead of spawning a real
# GameServer. Without this, the proxy spawns the C++ server and runs the full
# match — useful for prod, but the swarm test wants instant resolution. The
# var is consumed by docker-compose.proxy.yml via "\${GAUNTLET_INSTANT_RESOLVE:-}"
# so an empty value safely no-ops.
GAUNTLET_INSTANT_RESOLVE=${config.gauntletInstantResolve ?: ''}
ENVEOF
                        """
                        // Practice bots are spawned from BOT_PROJECT_ROOT, published by
                        // mcdClientPipeline's "Publish Bot Runtime" stage in a DIFFERENT
                        // job. Nothing ties the two together, so when an env's
                        // botProjectPath is wrong or that stage is skipped, the tree
                        // silently rots: the bot then loses the protocol handshake and
                        // every play-vs-bot match on the env fails, while this pipeline
                        // reports a clean deploy. GH #2188 — release-staging sat on the
                        // 2026-07-14 build (protocol v41) against a v43 server for 12
                        // days. Warn rather than fail: a stale bot runtime does not
                        // affect human-vs-human matches.
                        sh """
                            BOT_EXT="${botProjectPath}/bin/lib/Linux-x86_64/libMCDCoreExt-d.so"
                            if [ ! -f "\$BOT_EXT" ]; then
                                echo "⚠️ BOT RUNTIME MISSING: \$BOT_EXT — play-vs-bot will not work on ${config.environment}."
                                echo "   Check botProjectPath in this env's Jenkinsfile.client.* matches BOT_PROJECT_ROOT above."
                            else
                                bot_age=\$(stat -c %Y "\$BOT_EXT")
                                srv_age=\$(stat -c %Y "bin/MCDProxy" 2>/dev/null || echo 0)
                                skew=\$(( (srv_age - bot_age) / 86400 ))
                                if [ "\$skew" -gt 1 ]; then
                                    echo "⚠️ BOT RUNTIME STALE: \$BOT_EXT is \${skew} days older than this build."
                                    echo "   A protocol bump since then means practice bots are rejected at the handshake."
                                    echo "   Re-run this env's MCDClient job, or check botProjectPath vs BOT_PROJECT_ROOT."
                                else
                                    echo "✓ Bot runtime current (\$(stat -c %y "\$BOT_EXT"))"
                                fi
                            fi
                        """

                        def containerName = "${composeProject}-proxy-1"

                        // The commit the proxy image must be built from. Read from the
                        // workspace rather than env.GIT_COMMIT: this pipeline performs
                        // several checkouts (main + the target branch), so the env var
                        // does not reliably name the branch we just built.
                        def proxyCommit = sh(script: "git rev-parse HEAD", returnStdout: true).trim()

                        def newHash = sh(script: "sha256sum bin/MCDProxy | cut -d' ' -f1", returnStdout: true).trim()
                        def oldHash = sh(script: "sha256sum ${config.deployPath}/MCDProxy 2>/dev/null | cut -d' ' -f1 || echo 'none'", returnStdout: true).trim()
                        def binaryChanged = (newHash != oldHash)

                        // Also check if proxy Docker config changed (compose file, env file)
                        def configHash = sh(script: "cat Src/docker-compose.proxy.yml Src/${envFile} 2>/dev/null | sha256sum | cut -d' ' -f1", returnStdout: true).trim()
                        def oldConfigHash = sh(script: "cat /tmp/.mcd-proxy-config-hash-${composeProject} 2>/dev/null || echo 'none'", returnStdout: true).trim()
                        def configChanged = (configHash != oldConfigHash)

                        if (binaryChanged || configChanged) {
                            if (binaryChanged) {
                                echo "⚠️ Proxy binary changed - rebuilding Docker container for ${config.environment}"
                            } else {
                                echo "⚠️ Proxy config changed - recreating Docker container for ${config.environment}"
                            }
                            discordNotify.simple("🔄 ${config.environment.capitalize()} proxy container rebuild in progress", "16776960")

                            sh """
                                # Src/Proxy/Dockerfile COMPILES the proxy from its build
                                # context, and 'docker compose build' takes that context
                                # from the compose project dir (/var/opt/mechacorpsgames/Src)
                                # — NOT this build's workspace. Nothing has ever refreshed
                                # that tree: it sat at cc5fee0a/2026-05-01 while every build
                                # here reported "proxy container restarted successfully", so
                                # months of proxy commits were compiled out of a stale
                                # checkout and never shipped. The 'bin/MCDProxy' this
                                # pipeline builds, tests and hashes was only ever a
                                # change-detection marker; no image or mount consumed it.
                                #
                                # Build the image straight from the workspace and tag it
                                # exactly what compose would have produced, then bring the
                                # service up with --no-build so compose adopts that image
                                # instead of compiling its own. The project dir is left
                                # alone: it is shared with other services and owned by
                                # another user, so writing into it is both a cross-service
                                # side effect and a permission failure waiting to happen.
                                docker build \\
                                    --no-cache \\
                                    --build-arg GIT_COMMIT='${proxyCommit}' \\
                                    -f Src/Proxy/Dockerfile \\
                                    -t ${composeProject}-proxy:latest \\
                                    Src

                                # TEARDOWN ORDER IS LOAD-BEARING (mc-ic6h). The image is
                                # built FIRST, above, and only then is the running proxy
                                # removed. It used to be the other way round, and the gap
                                # between the two was a total outage: the 'docker rm -f'
                                # below destroyed the container, and only then did
                                # '--no-cache' rebuild the image from scratch, with
                                # nothing serving for the length of a full image build.
                                #
                                # On 2026-08-24 that gap was 10s on MCDServer-Main #958
                                # and it killed a live match (bead mc-ic6h, GH-2688):
                                #   22:22:33  docker rm -f     -> client 1006 Abnormal closure
                                #   22:22:35  (still building) -> reconnect 502 Bad Gateway
                                #   22:22:43  compose up       -> reconnect 401, match gone
                                # The proxy container also SPAWNS the game servers
                                # (-godot-binary/-godot-project in its compose cmd), so
                                # removing it kills every in-flight match with it, not
                                # merely the gateway.
                                #
                                # The build needs neither the port nor the container name,
                                # so it is safe to run while the old proxy still serves.
                                # On its own this does NOT make a deploy safe for live
                                # matches: the recreate below is still a hard cut. It
                                # removed only the part of the outage that was pure
                                # ordering. The lease gate directly below is what now
                                # keeps that hard cut away from a live match.

                                # THE LEASE GATE (mc-ic6h). The recreate below destroys the
                                # proxy container, and because that container also SPAWNS
                                # the game servers, it destroys every match running under
                                # it. So it must not fire while a match is live.
                                #
                                # The .in-use lease is the SAME contract 'Cleanup Old
                                # Versions' already honours further down (see
                                # IN_USE_LEASE_MINUTES): the proxy touches
                                # <version-dir>/.in-use while a match is running on that
                                # version, and refreshes it every 5 minutes. Cleanup
                                # consulted that marker before deleting. The teardown did
                                # not consult it at all. That asymmetry is the bug: on
                                # 2026-08-24 this stage tore the proxy down at 22:22:33
                                # with a match live and killed game 753ec597 mid-play
                                # (GH-2688 and GH-2687, both players, 41 seconds apart).
                                #
                                # THE BOUND IS TIM'S RULING, 2026-08-27: wait up to 15
                                # minutes for the lease to clear, then tear down ANYWAY.
                                # Each rejected option is rejected for a reason worth
                                # keeping:
                                #   5 min  too short. A long combat sequence needs longer.
                                #   60 min equals IN_USE_LEASE_MINUTES, so a LEAKED lease
                                #          could hold a deploy for a full hour.
                                #   never  no ceiling at all: one leaked lease blocks every
                                #          deploy until a human clears it by hand.
                                # Do not quietly retune this to 5 as 'close enough'. 5 was
                                # on the table and was not chosen.
                                #
                                # THE WAIT DELIBERATELY HAPPENS OUTSIDE ANY CROSS-JOB LOCK.
                                # Waiting 15 minutes while holding the deploy-path flock
                                # (mc-ehn1) would stall every other job keyed on that path,
                                # a promote included, and turn a live-match protection into
                                # a fleet-wide stall.
                                #
                                # KNOWN NARROW RACE, written down so this is not read as a
                                # guarantee: a match can still start between the wait
                                # clearing and the teardown running. This gate shrinks that
                                # window from 'the whole deploy' to 'a few seconds'; it does
                                # not close it. Closing it needs the proxy to refuse new
                                # matches while a deploy is pending, which is proxy-side
                                # and is not this change.
                                #
                                # Scoped to versions/ only, not testclient-versions/: the
                                # lease contract is written by the proxy when a MATCH
                                # starts, and only matches are destroyed by this teardown.
                                PROXY_LEASE_WAIT_SECONDS=\${PROXY_LEASE_WAIT_SECONDS:-900}
                                PROXY_LEASE_POLL_SECONDS=\${PROXY_LEASE_POLL_SECONDS:-15}
                                PROXY_LEASE_MINUTES=\${PROXY_LEASE_MINUTES:-60}

                                # Leases live at
                                # ${config.deployPath}/leases/<tree>/<version>/.in-use,
                                # NOT inside versions/. That tree is mounted read-only into
                                # the proxy on purpose, so every lease write returned EROFS
                                # and this wait could never once fire (mc-2acos).
                                # maxdepth 3 rather than 2 because of the <tree> level, which
                                # keeps versions/ and testclient-versions/ (which number
                                # their versions independently) from masking each other.
                                # Either tree being leased means a match is live.
                                live_lease() {
                                    find "${config.deployPath}/leases" -maxdepth 3 \\
                                        -name .in-use -mmin -"\$PROXY_LEASE_MINUTES" \\
                                        2>/dev/null | head -n 1
                                }

                                lease_holder=\$(live_lease)
                                lease_waited=0
                                if [ -n "\$lease_holder" ]; then
                                    echo "PAUSE proxy teardown: a match is live on \$(basename "\$(dirname "\$lease_holder")")"
                                    echo "  waiting up to \${PROXY_LEASE_WAIT_SECONDS}s for its .in-use lease to clear"
                                    while [ -n "\$lease_holder" ] && [ "\$lease_waited" -lt "\$PROXY_LEASE_WAIT_SECONDS" ]; do
                                        sleep "\$PROXY_LEASE_POLL_SECONDS"
                                        lease_waited=\$((lease_waited + PROXY_LEASE_POLL_SECONDS))
                                        lease_holder=\$(live_lease)
                                    done
                                    if [ -n "\$lease_holder" ]; then
                                        echo "FORCED proxy teardown after \${lease_waited}s of waiting."
                                        echo "  \$(basename "\$(dirname "\$lease_holder")") STILL held its .in-use lease."
                                        echo "  A LIVE MATCH IS BEING DESTROYED BY THIS DEPLOY (mc-ic6h)."
                                        echo "  The bound is PROXY_LEASE_WAIT_SECONDS=\${PROXY_LEASE_WAIT_SECONDS}s."
                                    else
                                        echo "OK lease cleared after \${lease_waited}s. Tearing down with no match live."
                                    fi
                                fi
                            """
                            // THE LOCK STARTS HERE, NOT ABOVE (mc-ehn1). The image
                            // build deliberately sits OUTSIDE it. Holding a
                            // cross-job mutex across a --no-cache image build would
                            // pin the lock for minutes and buy nothing, because the
                            // build touches neither the container name nor the port
                            // (the mc-ic6h comment above is why it runs first, and
                            // that ordering is load-bearing).
                            //
                            // What IS inside is the region that is not idempotent
                            // under concurrency: the port-holder sweep, the
                            // `docker rm -f`, the `compose up --force-recreate`, the
                            // Up check, the provenance gate and the marker write.
                            // On 2026-08-27 MCDServer-Main #1006 died in exactly
                            // that window: a concurrent build removed the container
                            // between compose resolving it and compose recreating
                            // it, and compose exited on "No such container:
                            // 0007b334f1c9...". The deploy had in fact WORKED (the
                            // container that invocation created was serving and
                            // reporting rooms:0), but the stage went red and took
                            // 'Cleanup Old Versions' down with it as collateral.
                            //
                            // disableConcurrentBuilds() alone already makes that
                            // exact sequence impossible, because #1006 and #1008
                            // can no longer run at the same time. The lock covers
                            // the case the option cannot reach: a DIFFERENT job on
                            // the same deploy path. Today that pair is
                            // MCDServer-Release-Staging and MCDServer-Release-
                            // Promote, both on /opt/mechacorps/release-staging.
                            mcdDeployLock(deployPath: config.deployPath, """
                                # Stop systemd proxy service and legacy containers holding our ports
                                sudo systemctl stop mcdproxy-release.service 2>/dev/null || true
                                sudo systemctl disable mcdproxy-release.service 2>/dev/null || true

                                # Kill any Docker container holding our target TCP or WS port (host networking)
                                for cid in \$(docker ps -q --filter 'network=host'); do
                                    cname=\$(docker inspect --format '{{.Name}}' "\$cid" | sed 's|^/||')
                                    if [ "\$cname" = "${containerName}" ]; then continue; fi
                                    cmd=\$(docker inspect --format '{{join .Config.Cmd " "}}' "\$cid" 2>/dev/null || true)
                                    if echo "\$cmd" | grep -qE '(^|\\s)${config.tcpPort}(\\s|\$)'; then
                                        echo "Removing container \$cname holding port ${config.tcpPort}"
                                        docker rm -f "\$cid" 2>/dev/null || true
                                    fi
                                done
                                docker rm -f ${containerName} 2>/dev/null || true

                                # Compose DEFINITION from the workspace (so command args and
                                # mounts track the branch) but project dir unchanged (so
                                # container identity and relative paths do not move).
                                docker compose -p ${composeProject} \\
                                    --project-directory /var/opt/mechacorpsgames/Src \\
                                    -f "\$(pwd)/Src/docker-compose.proxy.yml" \\
                                    --env-file /var/opt/mechacorpsgames/Src/${envFile} \\
                                    up -d --force-recreate --no-build proxy

                                sleep 3
                                if docker ps --filter 'name=${containerName}' --format '{{.Status}}' | grep -q 'Up'; then
                                    # Provenance gate. The image stamps the commit it was
                                    # built from (Src/Proxy/Dockerfile LABEL). If the running
                                    # container does not carry the commit this build tested,
                                    # the image came from somewhere else — fail loudly here
                                    # rather than let another silent-stale-proxy month pass.
                                    want='${proxyCommit}'
                                    got=\$(docker inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' ${containerName} 2>/dev/null || echo '')
                                    if [ -n "\$want" ] && [ "\$got" != "\$want" ]; then
                                        echo "✗ Proxy image provenance mismatch for ${config.environment}"
                                        echo "  expected commit: \$want"
                                        echo "  running image:   \${got:-<unstamped>}"
                                        echo "  The proxy image was NOT built from this commit — see the build-context sync above."
                                        exit 1
                                    fi
                                    echo "✓ ${config.environment} proxy container restarted successfully (commit \${got:-<unstamped>})"

                                    # Marker LAST: it is the change-detection input for the
                                    # next build, so writing it before the container is
                                    # verified would make a failed deploy look up-to-date
                                    # and skip the retry forever.
                                    rm -f ${config.deployPath}/MCDProxy
                                    cp bin/MCDProxy ${config.deployPath}/MCDProxy
                                    chmod +x ${config.deployPath}/MCDProxy
                                    echo "${configHash}" > /tmp/.mcd-proxy-config-hash-${composeProject}
                                else
                                    echo "✗ Failed to start proxy container"
                                    docker logs ${containerName} --tail 20 2>&1 || true
                                    exit 1
                                fi
                            """)
                            env.PROXY_DEPLOYED = "true"
                        } else {
                            // Same lock as the changed-proxy branch above (mc-ehn1).
                            // This path is reached when the proxy itself did not
                            // change but the container is not running, and it does
                            // the same non-idempotent `docker rm -f` followed by a
                            // `compose up` against the same globally-named container
                            // and the same shared Src tree. An unlocked recovery
                            // path racing a locked deploy path is not serialized at
                            // all, so both branches have to take it.
                            mcdDeployLock(deployPath: config.deployPath, """
                                if ! docker ps --filter 'name=${containerName}' --format '{{.Status}}' | grep -q 'Up'; then
                                    echo "Proxy container not running, starting..."

                                    # Stop systemd proxy service and legacy containers holding our ports
                                    sudo systemctl stop mcdproxy-release.service 2>/dev/null || true
                                    sudo systemctl disable mcdproxy-release.service 2>/dev/null || true

                                    # Kill any Docker container holding our target TCP or WS port (host networking)
                                    for cid in \$(docker ps -q --filter 'network=host'); do
                                        cname=\$(docker inspect --format '{{.Name}}' "\$cid" | sed 's|^/||')
                                        if [ "\$cname" = "${containerName}" ]; then continue; fi
                                        cmd=\$(docker inspect --format '{{join .Config.Cmd " "}}' "\$cid" 2>/dev/null || true)
                                        if echo "\$cmd" | grep -qE '(^|\\s)${config.tcpPort}(\\s|\$)'; then
                                            echo "Removing container \$cname holding port ${config.tcpPort}"
                                            docker rm -f "\$cid" 2>/dev/null || true
                                        fi
                                    done
                                    docker rm -f ${containerName} 2>/dev/null || true

                                    cd /var/opt/mechacorpsgames/Src
                                    docker compose -p ${composeProject} -f docker-compose.proxy.yml --env-file ${envFile} up -d proxy
                                else
                                    echo "✓ Proxy unchanged and container already running"
                                fi
                            """)
                            env.PROXY_DEPLOYED = "false"
                        }
                    }
                }
            }

            stage('Cleanup Old Versions') {
                when { expression { env.SERVER_CHANGED == 'true' } }
                steps {
                    // LOCKED for the same reason the deploy is (mc-ehn1). This stage
                    // rm -rf's version directories out of the very tree that
                    // MCDServer-Release-Promote rsyncs FROM when it ships
                    // production. Unlocked, a retention sweep on release-staging can
                    // delete a version directory while promote is mid-copy of it,
                    // and promote's own build would be the one that goes red for it.
                    mcdDeployLock(deployPath: config.deployPath, """
                        # RETENTION (mc-mhjd). Keeping the newest five was the whole
                        # rule, and on its own it can delete a server binary out from
                        # under a LIVE MATCH: the proxy routes old clients to old
                        # versions (mc-cdjn), so a version past the newest five may
                        # still be serving players. The keep-5 count stays as the
                        # safety cap; the in-use lease below is the correctness fix.
                        # A version is deleted only if it is BOTH beyond keep-5 AND
                        # unleased.
                        #
                        # THE LEASE. This contract is shared verbatim with the proxy
                        # (mc-epfh); both sides hardcode the same path:
                        #   path       <lease-dir>/<tree>/<version>/.in-use
                        #   created    by the proxy when a match starts on that version
                        #   refreshed  touched at least every 5 minutes while any match
                        #              on that version is still live
                        #   removed    when the last match on that version ends
                        #
                        # It is a LEASE rather than a plain flag because a plain flag
                        # is unsafe in both directions. A proxy that dies mid-match
                        # would leave a flag behind that pins the version forever, and
                        # versions only accumulate here, so the disk grows without
                        # bound. An expiring lease reclaims that version on a later
                        # run. The expiry is 12x the refresh interval, so a live match
                        # would have to miss twelve consecutive touches to be treated
                        # as dead.
                        IN_USE_LEASE_MINUTES=60

                        # \$2 is the lease base for this tree. The marker moved OUT
                        # of the version directory: versions/ is mounted read-only
                        # into the proxy on purpose, so a lease written there
                        # returned EROFS and this guard has never once kept a
                        # version (mc-2acos).
                        # BOTH trees keep the guard. mc-cdjn Phase 2 routes bots by
                        # protocol too, so a testclient version can be live under a
                        # practice or takeover bot exactly as a server version can.
                        # They get separate lease bases because the two trees number
                        # their versions independently and would otherwise mask each
                        # other.
                        prune_versions() {
                            target="\$1"
                            lease_base="\$2"
                            [ -d "\$target" ] || return 0
                            cd "\$target" || return 0
                            for candidate in \$(ls -dt v*/ 2>/dev/null | tail -n +6); do
                                version="\${candidate%/}"
                                if [ -n "\$lease_base" ] && [ -n "\$(find "\$lease_base/\$version" -maxdepth 1 -name .in-use -mmin -"\${IN_USE_LEASE_MINUTES}" 2>/dev/null)" ]; then
                                    echo "⏸ keeping \${version} (in-use lease held, a match is live on it)"
                                    continue
                                fi
                                rm -rf "\$version"
                                # The lease directory for a version that no longer
                                # exists is dead weight; leaving them would grow one
                                # empty directory per deploy forever.
                                [ -n "\$lease_base" ] && rm -rf "\$lease_base/\$version"
                                echo "🗑 removed \${version}"
                            done
                        }

                        prune_versions "${config.deployPath}/versions" "${config.deployPath}/leases/versions" || true
                        prune_versions "${config.deployPath}/testclient-versions" "${config.deployPath}/leases/testclient-versions" || true
                        echo "✓ Cleanup complete for ${config.environment}"
                    """)
                }
            }
        }

        post {
            success {
                script {
                    if (env.SERVER_CHANGED != 'true') {
                        echo "No server changes detected — nothing deployed"
                        return
                    }

                    def proxyNote = (env.PROXY_DEPLOYED == "true") ? " + Proxy" : ""

                    // TELL THE TRUTH AFTER A REFUSED POINTER (mc-ehn1). The build
                    // is deliberately GREEN when the monotonic guard refuses: being
                    // superseded by a newer build is the mechanism working, not a
                    // failure, and the same argument mcdSteamUploadPipeline makes
                    // for its own coalescing applies here. But the payload having
                    // deployed while latest.txt was left pointing at a NEWER build
                    // is not "Deployed Server v<this build>", and announcing that to
                    // Discord would put a version into the channel that is not the
                    // one actually serving. That is the exact confusion the 04:06Z
                    // incident produced by hand.
                    def deployedMessage = (env.POINTER_VERDICT == 'refused')
                        ? "☑️ Built and staged Server v${env.SERVER_VERSION}${proxyNote} for ${config.environment}, but a NEWER build already published: latest.txt was left pointing at it and this version is not the one serving"
                        : "✅ Deployed Server v${env.SERVER_VERSION}${proxyNote} to ${config.environment}"

                    discordNotify.success(
                        title: "MechaCorps Server Build",
                        message: deployedMessage,
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: config.environment,
                        branch: config.branch,
                        version: env.SERVER_VERSION ?: 'N/A',
                        serverHost: config.serverHost,
                        tcpPort: config.tcpPort,
                        wsPort: config.wsPort
                    )
                }
            }
            // Declarative `post` runs `success` only on SUCCESS, so an UNSTABLE
            // build would otherwise notify nobody.
            //
            // The message names the CAUSE, taken from env.UNSTABLE_REASON, which
            // mcdUnstableReason records at whichever site actually went soft —
            // the WASM bot build, the symbol upload, the card gate. It replaces
            // a bare "finished UNSTABLE", which told nobody what to go and fix
            // (bead mjs-q4x). Do not swap in a phase/stage name here: that
            // reports where the build got TO rather than what went wrong, which
            // is the mistake the client pipeline made.
            unstable {
                script {
                    if (env.SERVER_CHANGED != 'true') {
                        return
                    }
                    // Every cause recorded during this build, "; "-joined, so a
                    // run that trips both the WASM gate and the symbol upload
                    // reports both. The old if/else overwrote one with the other.
                    def reason = env.UNSTABLE_REASON?.trim()
                    def envName = config.environment.capitalize()
                    def detail = reason
                        ? "⚠️ ${envName} finished with ${reason}"
                        : "⚠️ ${envName} finished with warnings — see the console log"
                    discordNotify.unstable(
                        title: "MechaCorps Server Build",
                        message: detail,
                        reason: reason,
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: config.environment,
                        branch: config.branch
                    )
                }
            }
            failure {
                script {
                    discordNotify.failure(
                        title: "MechaCorps Server Build",
                        message: "❌ ${config.environment.capitalize()} failed",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: config.environment,
                        branch: config.branch
                    )
                }
            }
        }
    }
}

return this
