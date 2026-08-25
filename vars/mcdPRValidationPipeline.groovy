// MechaCorps PR Validation Pipeline - Shared Library
// Validates pull requests before merge via build + test (no deploy).
//
// Two tiers:
//   main PRs    — GameServer build + unit/integration tests + GDScript tests
//   release PRs — All of the above + MCDCoreExt multi-platform builds
//
// Reports GitHub commit status so results appear on the PR checks tab.

def call(Map config) {
    // Required config:
    //   targetBranch:  'main' or 'release'
    //   webhookToken:  'mcd-pr-main' or 'mcd-pr-release'
    //   jobName:       'MCD-PR-Main' or 'MCD-PR-Release'

    def statusContext = (config.targetBranch == 'release')
        ? 'jenkins/pr-release-validation'
        : 'jenkins/pr-validation'

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
            // NO JOB-WIDE CONCURRENCY CONTROL, DELIBERATELY. Read this before
            // adding one back (bead mc-waxw).
            //
            // mc-w2iu put disableConcurrentBuilds(abortPrevious: true) here to
            // make the newest push to a PR win. It did that, and it also did
            // something nobody wanted: the option is scoped to the JOB, and
            // MCD-PR-Main is one job serving every open pull request, so a push
            // to any PR aborted the in-flight build of an unrelated one. Within
            // an hour on 2026-08-25, builds #1757 (PR-2708), #1759 (PR-2716),
            // #1761 (PR-2714), #1762 (PR-2718) and #1766 (PR-2720) ended
            // NOT_BUILT, mostly killed by somebody else's branch. Tim: "PR
            // builds should only trim to latest when there are duplicate builds
            // for a single PR, otherwise, all of them should run."
            //
            // Worse than the waste: an abortPrevious kill records NOT_BUILT, and
            // Declarative's post{aborted} fires on ABORTED, not on NOT_BUILT. So
            // the terminal status never got posted and the PR sat on
            // 'Validation started' forever with mergeStateStatus BLOCKED.
            //
            // Nothing at this level can express "same PR". disableConcurrentBuilds
            // and milestone() are both keyed on the job and ordered by build
            // number, with no notion of a parameter; mcdSteamSourceBuild.groovy
            // records the identical finding for one job serving four Steam
            // branches. A computed value cannot go in an options block either:
            // Declarative parses it statically and a computed value here is a
            // parse error in a shared var, which takes every job down (#82).
            //
            // So supersession moved to where it can be keyed correctly, in
            // mcdPrSupersession.groovy: an older build sees a newer build of the
            // SAME pr_number through currentBuild.nextBuild and stands itself
            // down. That check needs the newer build to be RUNNING, which is
            // exactly why it was rejected while this option was here (a
            // serialized job leaves the newer build queued with no Run object)
            // and exactly why it works now that the option is gone.
            //
            // Concurrency is safe on THIS pipeline and only on this one. It
            // publishes nothing: /opt/mechacorps appears once, in the agent's
            // docker mount args, and in no sh body, so there is no rsync and no
            // write to a shared path. Jenkins gives concurrent runs their own
            // @2/@3 workspaces. And it is the status quo restored, not a new
            // experiment: this pipeline ran with no concurrency control at all
            // until 2026-08-24.
            //
            // The pipelines that DO publish through rsync still must not be
            // interrupted at an arbitrary point, and they are not: they trim
            // with mcdRedundantBuild.groovy, which only ever skips work a
            // previous build already finished, and never touches a running one.
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            GITHUB_STATUS_TOKEN = credentials('github-status-token')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            TARGET_BRANCH = "${config.targetBranch}"
            ANDROID_SDK_ROOT = "/opt/android-sdk"
            ANDROID_HOME = "/opt/android-sdk"
            ANDROID_NDK_HOME = "/opt/android-sdk/ndk/26.1.10909125"
        }

        triggers {
            GenericTrigger(
                genericVariables: [
                    [key: 'action', value: '$.action'],
                    [key: 'pr_number', value: '$.pull_request.number'],
                    [key: 'pr_head_sha', value: '$.pull_request.head.sha'],
                    [key: 'pr_head_ref', value: '$.pull_request.head.ref'],
                    [key: 'pr_base_ref', value: '$.pull_request.base.ref'],
                    [key: 'pr_title', value: '$.pull_request.title'],
                    [key: 'pr_author', value: '$.pull_request.user.login'],
                    [key: 'repo_full_name', value: '$.repository.full_name']
                ],
                causeString: "PR #\$pr_number (\$pr_head_ref → ${config.targetBranch})",
                token: config.webhookToken,
                tokenCredentialId: '',
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
                regexpFilterText: '$action $pr_base_ref',
                regexpFilterExpression: "(opened|synchronize|reopened) ${config.targetBranch}"
            )
        }

        stages {
            stage('Setup PR Info') {
                steps {
                    script {
                        if (!env.pr_number || !env.pr_head_sha) {
                            error("Missing PR webhook variables. Ensure this job is triggered by a pull_request event.")
                        }

                        def shortSha = env.pr_head_sha.take(7)
                        currentBuild.displayName = "#${BUILD_NUMBER} PR-${env.pr_number} (${shortSha})"
                        currentBuild.description = "${env.pr_title}\n${env.pr_head_ref} → ${config.targetBranch} by ${env.pr_author}"

                        echo "PR #${env.pr_number}: ${env.pr_title}"
                        echo "Branch: ${env.pr_head_ref} → ${config.targetBranch}"
                        echo "Author: ${env.pr_author}"
                        echo "Head SHA: ${env.pr_head_sha}"

                        env.BUILD_GITHUB_USER = env.pr_author

                        // Set pending status on GitHub
                        setGitHubStatus('pending', 'Validation started', statusContext)
                    }
                }
            }

            stage('Checkout PR Merge Ref') {
                steps {
                    checkout scm
                    // Clean untracked files so stale .uid / generated files
                    // from a previous build can't block the PR checkout
                    sh 'git clean -fdx'
                    script {
                        // Local merge: fetch target branch + PR head, then merge locally.
                        // GitHub's refs/pull/NNN/merge can be stale when the webhook fires
                        // before GitHub regenerates the ref (caused build #57 failure).
                        // Use GIT_SSH_COMMAND to ensure git uses the mounted deploy key,
                        // since checkout scm's GIT_SSH wrapper isn't active for manual fetches.
                        withEnv(['GIT_SSH_COMMAND=ssh -i /var/lib/jenkins/.ssh/id_ed25519 -o StrictHostKeyChecking=accept-new']) {
                            sh "git fetch origin +refs/heads/${config.targetBranch}:refs/remotes/origin/${config.targetBranch}"
                            sh "git fetch origin ${env.pr_head_sha}"
                        }
                        sh "git checkout refs/remotes/origin/${config.targetBranch}"
                        sh 'git config user.email "jenkins@mechacorpsgames.com" && git config user.name "Jenkins CI"'

                        def mergeResult = sh(
                            script: "git merge --no-edit ${env.pr_head_sha}",
                            returnStatus: true
                        )
                        if (mergeResult != 0) {
                            // Merge failed — check if the PR was already merged/closed
                            sh 'git merge --abort || true'
                            def prMerged = sh(
                                script: """
                                    curl -s -H "Authorization: token \$GITHUB_STATUS_TOKEN" \
                                        -H "Accept: application/vnd.github.v3+json" \
                                        "https://api.github.com/repos/${env.repo_full_name}/pulls/${env.pr_number}" \
                                        | python3 -c "import sys,json; pr=json.load(sys.stdin); print('merged' if pr.get('merged') else 'not_merged')"
                                """,
                                returnStdout: true
                            ).trim()

                            if (prMerged == 'merged') {
                                echo "PR #${env.pr_number} was already merged — skipping validation."
                                setGitHubStatus('success', 'Skipped — PR already merged', statusContext)
                                currentBuild.result = 'NOT_BUILT'
                                env.PR_ALREADY_MERGED = 'true'
                                return
                            }
                            setGitHubStatus('failure', 'Merge conflict — cannot merge cleanly', statusContext)
                            error("PR #${env.pr_number} has merge conflicts with ${config.targetBranch}.")
                        }
                        echo "Checked out PR #${env.pr_number} (${env.pr_head_sha.take(7)}) merged into ${config.targetBranch}"
                    }
                }
            }

            stage('Detect Changes') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() } }
                steps {
                    script {
                        def changes = mcdChangeDetection.detect("refs/remotes/origin/${config.targetBranch}")
                        env.SERVER_CHANGED = changes.serverChanged.toString()
                        env.CLIENT_CHANGED = changes.clientChanged.toString()
                        env.MCP_GAME_SERVER_CHANGED = changes.mcpGameServerChanged.toString()
                        env.DETERMINISM_HARNESS_CHANGED = changes.determinismHarnessChanged.toString()
                        // Per-Go-module flags drive the 'Per-module Go tests' stage.
                        env.AUTH_CHANGED = changes.authChanged.toString()
                        env.ACCOUNT_SERVICE_CHANGED = changes.accountServiceChanged.toString()
                        env.AUCTION_HOUSE_CHANGED = changes.auctionHouseChanged.toString()
                        env.PROXY_CHANGED = changes.proxyChanged.toString()
                        env.SHARED_CHANGED = changes.sharedChanged.toString()
                        env.CRASH_REPORTING_CHANGED = changes.crashReportingChanged.toString()
                        env.MCP_SERVER_CHANGED = changes.mcpServerChanged.toString()
                        // Gates 'Tutorial Validation' alongside the server scope.
                        env.TUTORIAL_CHANGED = changes.tutorialChanged.toString()
                        if (config.determinismHarness?.enabled) {
                            def cadences = config.determinismHarness.cadences ?: [:]
                            def wireFormatChanged = mcdDeterminismHarness.cadenceMatches(changes.changedFiles, cadences.wireFormat ?: [:])
                            env.DETERMINISM_WIRE_FORMAT_CHANGED = wireFormatChanged.toString()
                            // A protocol_ext.h-only PR uses the special wire-format
                            // cadence so a deliberate PROTOCOL_VERSION bump can pass
                            // by failing old logs with exit 3.
                            def perPrChanged = mcdDeterminismHarness.cadenceMatches(changes.changedFiles, cadences.perPr ?: [:])
                            env.DETERMINISM_PER_PR_CHANGED = (!wireFormatChanged && (perPrChanged || changes.determinismHarnessChanged)).toString()
                        } else {
                            env.DETERMINISM_PER_PR_CHANGED = 'false'
                            env.DETERMINISM_WIRE_FORMAT_CHANGED = 'false'
                        }

                        def parts = []
                        if (changes.serverChanged) parts << 'server'
                        if (changes.clientChanged) parts << 'client'
                        if (changes.determinismHarnessChanged) parts << 'determinism-harness'
                        // Only call out MCP separately when it isn't already
                        // implied by a 'server' build (the wire-drift gate via
                        // Src/Include/ sets both flags).
                        if (changes.mcpGameServerChanged && !changes.serverChanged) parts << 'mcp-game-server'
                        def scope = parts ? parts.join(' + ') : 'no builds needed'
                        currentBuild.description += "\nBuilds: ${scope}"
                    }
                }
            }

            // Fail a PR that adds an ADR reverting to the retired NNNN- scheme, or
            // whose YYYY-MM-DD-slug identifier already exists on the base ref.
            //
            // Deliberately NOT gated on CLIENT_CHANGED, unlike the other script
            // checks. docs/** classifies as 'docs' in mcdChangeDetection, so a
            // docs-only PR (which is exactly what a standalone ADR PR is) leaves
            // CLIENT_CHANGED false and would skip the one gate written to police
            // it. Costs well under a second and needs no build.
            //
            // The base is named explicitly rather than guessed: 'Checkout PR Merge
            // Ref' above fetches origin/${targetBranch} and merges the PR head onto
            // it, so that ref is the true base and the working tree is the merge.
            stage('ADR Identifier Gate') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() } }
                steps {
                    // Branch-skew guard, same shape as 'Script Tests' below: this
                    // library is shared by every job, and release / features/backend
                    // / features/card all lag main and lack this target, so a bare
                    // make would red-line them the moment this merges. A target that
                    // is absent is skipped and said out loud; a target that exists
                    // and fails still fails the build.
                    sh """
                        if ! make -n check-adr-ids >/dev/null 2>&1; then
                            echo "ℹ No check-adr-ids target on this branch, skipping. It arrives with the MCDClient change that adds scripts/check_adr_ids.py."
                            exit 0
                        fi
                        python3 scripts/check_adr_ids.py --base origin/${config.targetBranch}
                    """
                }
            }

            // Fail a PR whose bundled Linux native binaries need a versioned
            // symbol the Steam Linux Runtime we publish against does not export.
            //
            // This is the static guard for mc-h53k (docs/adr/0138): libsentry
            // declares a versioned CURL_OPENSSL_4 requirement, Steam's scout
            // runtime pins a libcurl exporting only CURL_OPENSSL_3, the engine
            // could not dlopen the Sentry GDExtension at all, and every Linux
            // Steam playtest on the shipped build captured ZERO native crashes.
            // Nothing failed loudly. The condition is invisible until a player
            // crashes and no report ever arrives, which is why it needs a gate
            // rather than a runbook.
            //
            // Deliberately NOT gated on CLIENT_CHANGED. Both inputs to the check
            // are in the client bucket today -- mcdChangeDetection routes
            // addons/ and scripts/ to 'client', and the scanned binaries live
            // under addons/sentry/bin/linux and addons/godotsteam/linux{32,64}
            // -- so a gate WOULD fire on a .so swap as things currently stand.
            // It is left ungated anyway: the check reads committed ELF headers
            // in pure Python with no deps and measured 0.06s by hand, so a gate
            // buys nothing, while it would make this guard's firing depend on a
            // second file continuing to route addons/ to 'client'. A control
            // whose failure mode is silence should not be one edit in an
            // unrelated file away from never running again. Same reasoning the
            // 'ADR Identifier Gate' above records for its own scope.
            //
            // Runs here, before Setup Dependencies and every build, so it fails
            // fast.
            stage('Native Library ABI Check') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() } }
                steps {
                    // Branch-skew guard, same shape as 'ADR Identifier Gate'
                    // above and 'Script Tests' below, and not a failure swallow.
                    // A target that is absent is skipped and said out loud; a
                    // target that exists and fails still fails the build.
                    //
                    // Verified 2026-08-24: check-native-abi is already present on
                    // all four MCDClient branches this library serves (main,
                    // release, features/backend, features/card), so the probe is
                    // a formality today. It is kept because the branch set is not
                    // fixed and the cost of being wrong is a red PR on a branch
                    // whose PR had nothing to do with this.
                    sh '''
                        if ! make -n check-native-abi >/dev/null 2>&1; then
                            echo "ℹ No check-native-abi target on this branch, skipping. It arrives with the MCDClient change that adds scripts/check_native_lib_abi.py."
                            exit 0
                        fi
                        make check-native-abi
                    '''
                }
            }

            stage('Go Lint') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.SERVER_CHANGED == 'true' } }
                steps {
                    sh '''
                        echo "Installing golangci-lint..."
                        # Pinned (mc-qhu): unpinned @latest broke unrelated PRs whenever
                        # upstream released a new linter set. Bump deliberately in a
                        # follow-up PR after triaging any new findings.
                        go install github.com/golangci/golangci-lint/v2/cmd/golangci-lint@v2.12.0
                        export PATH="$(go env GOPATH)/bin:$PATH"
                        echo "Running lint on all Go modules..."
                        make lint
                    '''
                }
            }

            // Per-module Go tests: runs `make test-go MODULE=<Name>` for each
            // Go module whose source tree is in the PR diff, gated by the
            // per-module flags emitted by `Detect Changes`. Sub-stage labels
            // include the module name so a Jenkins UI failure points directly
            // at the offending module (per architect audit §5/§8 P1).
            //
            // Make target is defined in MCDClient repo by sibling bead
            // mc-eg0.1 (PR #1438 against features/backend). This stage and
            // that PR must both merge for the gate to be effective; the
            // jenkins-shared PR description spells out the merge order.
            stage('Per-module Go tests') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() } }
                // PR validation doesn't bring up Postgres, so the
                // `requireIntegrationDB(t)` guard added by MCDClient #1443
                // would exit(1) any package that hits it. Tell the test
                // harness to skip DB-dependent paths honestly; unit-only
                // tests still run.
                environment {
                    MCDC_SKIP_DB_TESTS = '1'
                }
                parallel {
                    stage('test-go: Auth') {
                        when { expression { env.AUTH_CHANGED == 'true' } }
                        steps { sh 'make test-go MODULE=Auth' }
                    }
                    stage('test-go: AccountService') {
                        when { expression { env.ACCOUNT_SERVICE_CHANGED == 'true' } }
                        steps { sh 'make test-go MODULE=AccountService' }
                    }
                    stage('test-go: AuctionHouse') {
                        when { expression { env.AUCTION_HOUSE_CHANGED == 'true' } }
                        steps { sh 'make test-go MODULE=AuctionHouse' }
                    }
                    stage('test-go: Proxy') {
                        when { expression { env.PROXY_CHANGED == 'true' } }
                        steps { sh 'make test-go MODULE=Proxy' }
                    }
                    stage('test-go: Shared') {
                        when { expression { env.SHARED_CHANGED == 'true' } }
                        steps { sh 'make test-go MODULE=Shared' }
                    }
                    stage('test-go: CrashReporting') {
                        when { expression { env.CRASH_REPORTING_CHANGED == 'true' } }
                        steps { sh 'make test-go MODULE=CrashReporting' }
                    }
                    stage('test-go: MCPServer') {
                        when { expression { env.MCP_SERVER_CHANGED == 'true' } }
                        steps { sh 'make test-go MODULE=MCPServer' }
                    }
                }
            }

            // Case-strict scan for res:// references in committed resources.
            // Fails the PR if a tscn/tres/cfg/gd reference points at a path
            // whose casing doesn't match the filesystem — a regression that
            // previously only surfaced on Linux headless bots (PR #1036
            // SIGSEGV postmortem). Pure Python, no deps; runs before any
            // build so it fails fast.
            stage('Resource Path Case Check') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.CLIENT_CHANGED == 'true' } }
                steps {
                    sh '''
                        if [ -f scripts/check_res_path_case.py ]; then
                            make check-res-case
                        else
                            echo "scripts/check_res_path_case.py not present on this branch, skipping"
                        fi
                    '''
                }
            }

            // Static guard for the Validated Card Data Pipeline (plan §5 C2):
            // fails the PR if runtime/test code references authoring card data
            // (Data/Cards/ or Data/References/) instead of the generated
            // Data/GameData/ output. Pure Python, no deps; runs before any build
            // so it fails fast. File-existence guard keeps it a no-op on branches
            // that predate the script (mirrors the case check above).
            stage('Authoring Data Reference Check') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.CLIENT_CHANGED == 'true' } }
                steps {
                    sh '''
                        if [ -f scripts/check_authoring_data_refs.py ]; then
                            make check-authoring-refs
                        else
                            echo "scripts/check_authoring_data_refs.py not present on this branch, skipping"
                        fi
                    '''
                }
            }

            // Unit tests for the six-KPI log parser (scripts/bot_kpis.py).
            // The parser's format contract with the server's combat-log
            // markers is pinned on the C++ side by CombatLogMarkersTest (in
            // MCDServerTest); this stage covers the python side. Pure Python
            // stdlib, sub-second; gated on either scope because the contract
            // spans server (marker emission) and client/scripts (parsing).
            // File-existence guard keeps it a no-op on branches that predate
            // the script (mirrors the checks above).
            stage('Bot KPI Parser Tests') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && (env.SERVER_CHANGED == 'true' || env.CLIENT_CHANGED == 'true') } }
                steps {
                    sh '''
                        if [ -f scripts/test_bot_kpis.py ]; then
                            python3 scripts/test_bot_kpis.py
                        else
                            echo "scripts/test_bot_kpis.py not present on this branch, skipping"
                        fi
                    '''
                }
            }

            stage('Setup Dependencies') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && (env.SERVER_CHANGED == 'true' || env.CLIENT_CHANGED == 'true') } }
                steps {
                    sh 'chmod +x scripts/setup-deps.sh && ./scripts/setup-deps.sh'
                }
            }

            // Generate Data/GameData/ before ANY test stage that reads card data.
            //
            // The server's own unit tests load the generated tree through
            // CardLibrary: SetupPhaseContribDeck, DraftPoolBuilder,
            // HandicapDraftBeginSync, DraftStateRestack, StateInjectorApply and
            // others all fail with "Failed to open .../Data/GameData/Cards/
            // Mecha.json" when it is missing. Nothing on the SERVER path produced
            // it: the only producer in this pipeline was the MCDCoreExt stage
            // below, gated on CLIENT_CHANGED, while 'Unit Tests' is gated on
            // SERVER_CHANGED. A server-only PR therefore ran its unit tests
            // against card data that nothing had generated -- 73 failures on
            // MCD-PR-Main #1358, none of them in the code that PR touched.
            //
            // It stayed hidden because agents reuse their workspace, so a tree
            // left behind by an earlier client-touching build was usually still
            // sitting there. That also means this was never server-only-specific:
            // it would surface on any clean agent.
            //
            // Gated the same as Setup Dependencies rather than on CLIENT_CHANGED:
            // it is a Python pass over card JSON, trivial next to a GameServer
            // build, and both halves consume the output.
            stage('Populate GameData') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && (env.SERVER_CHANGED == 'true' || env.CLIENT_CHANGED == 'true') } }
                steps {
                    sh 'make export-done'
                    // Fail here, loudly, rather than 20 minutes later as a wall of
                    // unrelated-looking test failures.
                    sh 'test -d Data/GameData/Cards'
                }
            }

            // MCDCoreExt Linux debug must be built before GDScript tests
            // because tests depend on GDExtension types (CardId, etc.)
            stage('Build MCDCoreExt Linux (for tests)') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.CLIENT_CHANGED == 'true' } }
                steps {
                    sh """
                        cd Src/MCDCoreExt
                        chmod +x build.sh
                        ./build.sh --clean --configure --build --install --debug
                    """
                }
            }

            // Pure-Python tests for the build/release tooling under scripts/.
            // No Godot, no network, ~1s. Runs before the Godot suites so a
            // broken release script fails fast. `scripts/**` is part of the
            // client change filter, so any edit to those tools lands here.
            stage('Script Tests') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.CLIENT_CHANGED == 'true' } }
                steps {
                    // Branch-skew guard, not a failure swallow. This library is
                    // shared by every job, and release, features/backend and
                    // features/card all lag main and lack this target, so a
                    // bare `make test-scripts` would red-line PR validation on
                    // those branches the moment this merges. A target that is
                    // absent is skipped and said out loud; a target that exists
                    // and fails still fails the build.
                    sh '''
                        if ! make -n test-scripts >/dev/null 2>&1; then
                            echo "ℹ No test-scripts target on this branch, skipping. It arrives with the MCDClient change that adds scripts/verify_sentry_symbols.py."
                            exit 0
                        fi
                        make test-scripts
                    '''
                }
            }

            // The stage that could not see a suite which never ran (mc-rqgm).
            //
            // gdUnit reports a suite that fails to PARSE as absent, not as
            // failed. It contributes zero cases, the summary still reads
            // "0 errors | 0 failures", both halves of "Executed test suites:
            // (N/M)" are counted after the load so they still match, and the
            // process still exits 0. On 2026-08-23 tests/test_hangar_view.gd
            // was parse-broken on main from 06:21, and MCDClient PR #2627 ran
            // a full client validation over it at 18:33 and passed in 6m56s.
            //
            // What does notice is the ERROR-class line the engine prints while
            // loading the broken script. MCDClient's `make test-gdscript` has
            // grepped for exactly that since #1527 (2026-05-16). This stage has
            // never gone through that target, so the only guard that catches
            // this class has been decorative on the only check that gates a
            // merge, for three months.
            //
            // WHY THE GUARD IS PORTED HERE RATHER THAN BY CALLING THE TARGET.
            // `make test-gdscript` does not pass `-c`, so it runs fail-fast:
            // gdUnit stops at the first failing test. Handing this stage to it
            // would cut the JUnit report published below down to whatever ran
            // before the first failure, and would make the parse-break guard
            // suite MCDClient #2642 just added (tests/test_suite_discovery_
            // guard.gd) conditional on every suite scanned ahead of it passing.
            // A gate that only runs while everything else is green is not a
            // gate. The stage keeps `-c` and brings the guard to it.
            //
            // The knowledge is NOT duplicated. Which ERROR lines are
            // test-intentional is decided by that branch's own
            // tests/_log_filter.py and tests/_log_filter_patterns.txt, which
            // this pipes through. Only the assertion "nothing of that class
            // survived the filter" is stated here as well as in the Makefile.
            stage('GDScript Tests') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.CLIENT_CHANGED == 'true' } }
                steps {
                    sh '''#!/bin/bash
                        # -e is not optional. This body has a shebang, so Jenkins
                        # runs it directly instead of through its default
                        # `sh -xe`, and the step's status would otherwise be the
                        # LAST command's alone (mc-91jj). It also gives the guard
                        # below the same precondition the Makefile gives it: it
                        # is reached only on a run that exited clean. pipefail is
                        # load-bearing for the same reason -- the run is a
                        # pipeline now, and without it `tee` would report 0 for a
                        # Godot process that died.
                        set -euo pipefail

                        # One name for the log, so the file `tee` writes and the
                        # file the guard reads cannot drift apart. They must not:
                        # `grep` on a path that does not exist exits 2, the `if`
                        # below reads that as "no matches", and the guard would
                        # pass silently on every build. `set -u` turns a typo
                        # here into a loud failure instead.
                        GDSCRIPT_LOG=build/godot-test-output.log

                        rm -rf reports/
                        mkdir -p build

                        # The filter is what makes the guard usable: tests emit
                        # ERROR lines on purpose and mark them, and this drops
                        # the marked pairs. Without it the guard would fire on
                        # every run. It has shipped on every branch this library
                        # serves (main and release, both verified 2026-08-23), so
                        # its absence is a broken checkout, not a branch skew,
                        # and it is said out loud rather than skipped past.
                        if [ ! -f tests/_log_filter.py ]; then
                            echo "tests/_log_filter.py is missing from this checkout."
                            echo "The GDScript ERROR-line guard cannot run without it, and a suite that"
                            echo "fails to parse is invisible without the guard (mc-rqgm), so this fails"
                            echo "rather than running ungated."
                            exit 1
                        fi

                        echo "Importing Godot project resources..."
                        godot --headless --import 2>/dev/null || true

                        echo "Running GdUnit4 GDScript tests..."
                        # python3 -u: the filter sits between Godot and the
                        # console, and a buffered filter would hold the whole
                        # run's output back and make a working stage look hung.
                        godot --headless -s addons/gdUnit4/bin/GdUnitCmdTool.gd -a res://tests -c --ignoreHeadlessMode 2>&1 \
                            | python3 -u tests/_log_filter.py \
                            | tee "$GDSCRIPT_LOG"

                        echo "Checking for ERROR-class lines that survived tests/_log_filter.py..."
                        if grep -nE '^(ERROR|SCRIPT ERROR|USER ERROR): ' "$GDSCRIPT_LOG"; then
                            echo ""
                            echo "The lines above are ERROR-class Godot output that tests/_log_filter.py did"
                            echo "not classify as expected. gdUnit's own scoreboard above is green, and this"
                            echo "is the failure it cannot see: a suite that fails to parse contributes zero"
                            echo "cases, so the summary reads 0 errors | 0 failures and the run exits 0 while"
                            echo "the suite never executed (mc-rqgm)."
                            echo ""
                            echo "Fix the error. If the line is one a test emits on purpose, mark it with"
                            echo "GdUnitErrorExpectation.expect_error, or add it to"
                            echo "tests/_log_filter_patterns.txt with a comment saying why it is expected."
                            echo "Do not widen the pattern to silence a real failure."
                            exit 1
                        fi
                    '''
                }
                post {
                    always {
                        script {
                            try {
                                // allowEmptyResults stays FALSE (mc-rqgm). This
                                // stage exists to stop "nothing ran" from
                                // reading as success, and an empty result set is
                                // that same claim in another form. The stage
                                // always runs the whole tests/ tree, so a build
                                // that reaches here with no results.xml has not
                                // passed, it has failed to run. Measured on
                                // MCD-PR-Main #1634 (2026-08-24): 7748 tests
                                // published, so the pattern below does match on
                                // a normal client build.
                                junit allowEmptyResults: false, skipPublishingChecks: true, testResults: 'reports/**/results.xml'
                            } catch (NoSuchMethodError e) {
                                echo "JUnit plugin not installed — skipping test report publishing"
                            }
                        }
                    }
                    failure {
                        // The guard prints the offending lines, but the filtered
                        // log is what tells you what ran before them.
                        archiveArtifacts artifacts: 'build/godot-test-output.log', allowEmptyArchive: true, fingerprint: false
                    }
                }
            }

            // Xvfb UI tests (mc-qc90). The headless GDScript stage above cannot
            // prove that a Control renders, that a mouse wheel reaches a
            // ScrollContainer, or that focus traverses: the headless display
            // server draws nothing and reports no geometry. MCDClient ships
            // .Jenkins/Jenkinsfile.ui-tests for exactly this, and nothing has ever
            // triggered it, so that whole class of test has never run on a PR.
            //
            // Runs inside THIS job rather than by triggering that one: whether any
            // Jenkins job loads that file is unverified (MCDClient
            // .Jenkins/JOBS.md records the status and what would settle it), and a
            // second job is a second executor on a controller that is already the
            // bottleneck.
            //
            // OPT-IN PER BRANCH through config.uiTests.enabled, the same shape as
            // determinismHarness above. It is not on by default: until mc-1zjn
            // reaches a branch, `make test-gdscript-ui` runs the whole tests/ tree
            // under a real display, including tests that assert headless-only
            // behaviour, and cannot pass on any commit there.
            //
            // A missing xvfb-run FAILS this stage rather than skipping it. A UI
            // gate that quietly does nothing is the exact defect mc-qc90 was filed
            // for, and the agent fix is one apt-get.
            stage('UI Tests (Xvfb)') {
                when {
                    expression {
                        env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() &&
                        env.CLIENT_CHANGED == 'true' &&
                        config.uiTests?.enabled == true
                    }
                }
                steps {
                    sh '''
                        if ! command -v xvfb-run >/dev/null 2>&1; then
                            echo "xvfb-run is not installed on this agent, so the UI gate cannot run."
                            echo "It is a real gate, not an advisory one, so this fails rather than skips."
                            echo "Fix: sudo apt-get install -y xvfb"
                            exit 1
                        fi
                        rm -f test-results/gdscript-ui.xml
                        make test-gdscript-ui
                    '''
                }
                post {
                    always {
                        script {
                            try {
                                junit allowEmptyResults: true, skipPublishingChecks: true, testResults: config.uiTests?.junit ?: 'test-results/gdscript-ui.xml'
                            } catch (NoSuchMethodError e) {
                                echo "JUnit plugin not installed — skipping test report publishing"
                            }
                        }
                    }
                }
            }

            // Card-validator addon ships its own bespoke runner under
            // addons/card_validator/tests/ (predates GdUnit4 in this addon).
            // `make test-validator` invokes the headless runner and writes
            // JUnit XML to test-results/card_validator_junit.xml.
            // Gated on CLIENT_CHANGED to match GDScript Tests above; addons/**
            // already routes to the 'client' category in mcdChangeDetection.
            stage('Card Validator Tests') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.CLIENT_CHANGED == 'true' } }
                steps {
                    sh '''
                        rm -rf test-results/card_validator_junit.xml
                        make test-validator
                    '''
                }
                post {
                    always {
                        script {
                            try {
                                junit allowEmptyResults: true, skipPublishingChecks: true, testResults: 'test-results/card_validator_junit.xml'
                            } catch (NoSuchMethodError e) {
                                echo "JUnit plugin not installed — skipping test report publishing"
                            }
                        }
                    }
                }
            }

            // C++ gtest suites for the validation core (Src/Validation/Test/).
            // Src/Validation/Test is a standalone CMake project — Src/Validation's
            // own CMakeLists does not add_subdirectory(Test) — so nothing built or
            // ran this target before: not the tag_id_list_computer suite that had
            // been there for months, nor the pool/rarity/requirements/variant
            // suites added by MCDClient#2185. `make test-validator-cpp` configures
            // Test/ directly, builds, and runs it with gtest JUnit output.
            // Gated on CLIENT_CHANGED — Src/Validation/** now routes to the
            // 'client' category in mcdChangeDetection (see this PR).
            stage('Validation C++ Tests') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.CLIENT_CHANGED == 'true' } }
                steps {
                    sh '''
                        rm -f test-results/validation_tests.xml
                        make test-validator-cpp
                    '''
                }
                post {
                    always {
                        script {
                            try {
                                junit allowEmptyResults: true, skipPublishingChecks: true, testResults: 'test-results/validation_tests.xml'
                            } catch (NoSuchMethodError e) {
                                echo "JUnit plugin not installed — skipping test report publishing"
                            }
                        }
                    }
                }
            }

            stage('Build GameServer, TestClient & Proxy') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        rm -rf bin/versions/v* bin/testclient-versions/v*
                        cd Src
                        chmod +x deploy.sh
                        ./deploy.sh --clean --release --build-number ${BUILD_NUMBER}
                    """
                }
            }

            stage('Verify Server Build') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        env.SERVER_VERSION_PATH = readFile('bin/versions/latest.txt').trim()
                        env.TESTCLIENT_VERSION_PATH = readFile('bin/testclient-versions/latest.txt').trim()
                        sh "test -x 'bin/versions/${env.SERVER_VERSION_PATH}'"
                        sh "test -x 'bin/testclient-versions/${env.TESTCLIENT_VERSION_PATH}'"
                        sh "test -x 'bin/MCDProxy'"
                        echo "Server build verified"
                    }
                }
            }

            // MCDServerTest is 2434 gtest cases, and ctest knows every one of
            // them by name: Test/CMakeLists.txt registers them with
            // gtest_discover_tests. They have always been GATED correctly here,
            // because a failing case exits non-zero and this shebang-less body
            // runs under /bin/sh -xe. What was missing was the ability to see
            // WHICH case failed. Nothing wrote JUnit XML and no junit step
            // collected any, so a full server run published to Jenkins as
            // "Total: 1": #1670 (server-only PR 2666) reported Total: 1,
            // Passed: 1 while polecat-4 ran 2434 cases locally on that same
            // commit (mc-ek9f).
            //
            // "Total: 1" is worse than no number at all. It reads like a green
            // that skipped everything, and a mayor session on 2026-08-24 nearly
            // declined to merge PR 2666 on that basis, proceeding only after
            // reading the stage list instead of the badge.
            //
            // COLLECTION SITS IN post{always} DELIBERATELY. In steps{} it would
            // be skipped on exactly the runs whose per-case report is the whole
            // point: the ones where the suite failed.
            //
            // WHY allowEmptyResults IS TRUE HERE, against the GDScript stage's
            // precedent (mc-rqgm), and why that is not a hole:
            //
            //   1. It cannot be the no-run guard people expect it to be.
            //      Measured with ctest 4.4.0: a project with nothing registered
            //      prints "No tests were found!!!", exits 0, AND writes a
            //      well-formed <testsuite name="(empty)" tests="0"/>. junit
            //      accepts that file, so allowEmptyResults never fires. The
            //      guard has to read the file's CONTENT, and it does:
            //      Src/GameServer/build.py refuses a 0-case run itself
            //      (require_tests_ran), before this step is ever reached.
            //   2. FALSE would red-line branches that lag main. This library is
            //      shared by every job, and release, features/backend and
            //      features/card keep the old build.py, which writes no XML at
            //      all, until the MCDClient change reaches them. Same skew, and
            //      the same treatment, as 'TestClient Unit Tests' and 'Script
            //      Tests': a branch that cannot produce the artifact is passed
            //      over, while a branch that can and fails still fails.
            stage('Unit Tests') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.SERVER_CHANGED == 'true' } }
                steps {
                    sh """
                        cd Src/GameServer
                        ./build.sh --test --release
                    """
                }
                post {
                    always {
                        script {
                            try {
                                // allowEmptyResults is TRUE on purpose, next to a
                                // FALSE on the GDScript stage. Do not "fix" it to
                                // match: it cannot catch a no-run here (ctest
                                // exits 0 AND writes a valid tests="0" file, so
                                // there is always a result to accept), and FALSE
                                // would red-line release / features/backend /
                                // features/card while they still carry the old
                                // build.py. The no-run guard is require_tests_ran
                                // in MCDClient's build.py. Full reasoning above
                                // this stage and in the mc-ek9f ADRs.
                                junit allowEmptyResults: true, skipPublishingChecks: true, testResults: 'test-results/server-tests.xml'
                            } catch (NoSuchMethodError e) {
                                echo "JUnit plugin not installed — skipping test report publishing"
                            }
                        }
                    }
                }
            }

            // C++ gtest suite for the TestClient (Src/TestClient/Test/), 290 tests
            // including the replay harness tests under Test/replay/. The
            // TestClient binary was already built by the stage above, and
            // Src/TestClient/build.py has always accepted --test, but no Makefile
            // target ever invoked it, so the suite compiled on every build and ran
            // nowhere. That is how ProtocolVersionPinTest's kExpectedProtocolVersion
            // sat at 42 while PROTOCOL_VERSION reached 45: the pin could not fail
            // because nothing executed it. Same shape as 'Validation C++ Tests'.
            //
            // MODE=release reuses the Release tree that 'Build GameServer,
            // TestClient & Proxy' just produced, so the prerequisite build is
            // incremental rather than a second full compile.
            //
            // THE STAGE NAME IS DELIBERATELY 'TestClient Unit Tests' AND MUST NOT
            // IMPLY DETERMINISM IS VERIFIED. The replay harness does not yet check
            // determinism: ReplaySession::Run validates the action-log header, walks
            // the entries to confirm they parse, counts them, and returns
            // ExitCode::Identical. It never connects to a GameServer, and it hands
            // EmitTrace an unpopulated CheckpointSampler, so every run reports
            // "checkpoint snapshots captured = 0" and all three committed baselines
            // are the empty {"checkpoints":[]} (ReplaySession.cpp:209-213 says so
            // itself). A green line here means the action log PARSES. Real comparison
            // arrives in mc-9t1.14. A stage name is what people read instead of the
            // source, so naming this 'Determinism Harness' would switch on a green
            // light for a check that does not exist.
            //
            // Branch-skew guard, not a failure swallow, same as 'Script Tests':
            // this library is shared by every job, and release, features/backend
            // and features/card lack the test-testclient target until the MCDClient
            // change reaches them. Absent target is skipped and said out loud; a
            // target that exists and fails still fails the build.
            stage('TestClient Unit Tests') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.SERVER_CHANGED == 'true' } }
                steps {
                    sh '''
                        if ! make -n test-testclient >/dev/null 2>&1; then
                            echo "No test-testclient target on this branch, skipping. It arrives with the MCDClient change that wires MCDTestClientTest into the Makefile."
                            exit 0
                        fi
                        make test-testclient MODE=release
                    '''
                }
            }

            stage('Proxy Unit Tests') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.SERVER_CHANGED == 'true' } }
                steps {
                    sh 'make test-proxy'
                }
            }

            stage('Integration Test') {
                when { expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.SERVER_CHANGED == 'true' } }
                steps {
                    script {
                        def testResult = sh(script: '''
                            set +e

                            echo "Starting integration test..."

                            TEST_TCP_PORT=$((30000 + (BUILD_NUMBER % 10000)))
                            TEST_WS_PORT=$((40000 + (BUILD_NUMBER % 10000)))
                            TEST_BASE_PORT=$((50000 + (BUILD_NUMBER % 10000)))

                            TESTCLIENT_PATH="bin/testclient-versions/$(cat bin/testclient-versions/latest.txt)"

                            cleanup() {
                                echo ""
                                echo "=== Proxy Log (last 20 lines) ==="
                                tail -20 /tmp/test_proxy_${BUILD_NUMBER}.log 2>/dev/null || echo "(no proxy log)"
                                echo ""
                                echo "=== Client 1 Log (last 15 lines) ==="
                                tail -15 /tmp/test_client1_${BUILD_NUMBER}.log 2>/dev/null || echo "(no client1 log)"
                                echo ""
                                echo "=== Client 2 Log (last 15 lines) ==="
                                tail -15 /tmp/test_client2_${BUILD_NUMBER}.log 2>/dev/null || echo "(no client2 log)"

                                kill $PROXY_PID 2>/dev/null || true
                                kill $CLIENT1_PID $CLIENT2_PID 2>/dev/null || true
                            }
                            trap cleanup EXIT

                            ./bin/MCDProxy -port $TEST_TCP_PORT -wsport $TEST_WS_PORT -baseport $TEST_BASE_PORT > /tmp/test_proxy_${BUILD_NUMBER}.log 2>&1 &
                            PROXY_PID=$!
                            echo "Test proxy started on TCP:$TEST_TCP_PORT, WS:$TEST_WS_PORT, BasePort:$TEST_BASE_PORT (PID: $PROXY_PID)"
                            sleep 3

                            if ! kill -0 $PROXY_PID 2>/dev/null; then
                                echo "ERROR: Proxy failed to start!"
                                exit 1
                            fi

                            $TESTCLIENT_PATH 127.0.0.1 $TEST_TCP_PORT TestBot1 0 --timeout=180 > /tmp/test_client1_${BUILD_NUMBER}.log 2>&1 &
                            CLIENT1_PID=$!
                            sleep 1
                            $TESTCLIENT_PATH 127.0.0.1 $TEST_TCP_PORT TestBot2 1 --timeout=180 > /tmp/test_client2_${BUILD_NUMBER}.log 2>&1 &
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
                            error("Integration test failed")
                        }
                    }
                }
            }

            // Tutorial validation harness (MCDClient ADR 0075): drives the
            // scripted 6-turn tutorial game through the real engine —
            // MCDServer behind MCDProxy with two scripted TestClient seats —
            // and diffs the per-turn phase-end snapshots against engine-truth
            // checkpoints. Without this stage an engine change that falsifies
            // a tutorial beat (a bid that stops winning, a stat that drifts, a
            // combat that stops being a clean player win) only surfaces when
            // someone runs the harness by hand; the smoke tier proves
            // tutorial.txt *completes*, not that it stays on script.
            //
            // `make test-tutorial` builds its own DEBUG server/proxy/testclient
            // first: tests/e2e/conftest.py resolves binaries out of build/Debug/
            // and pytest.skip()s when they're absent, so pointing this at the
            // --release tree built above would skip green rather than validate.
            // Debug and Release are separate build dirs, so this neither reuses
            // nor clobbers the artifacts 'Verify Server Build' checked.
            //
            // ~8s of pytest once built; no Godot involved.
            stage('Tutorial Validation') {
                when {
                    expression {
                        env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() &&
                        (env.SERVER_CHANGED == 'true' || env.TUTORIAL_CHANGED == 'true')
                    }
                }
                steps {
                    sh '''
                        # The harness landed on features/backend (MCDClient
                        # #2242) and isn't on every target branch yet — no-op
                        # where it's absent, mirroring the check-script stages.
                        if [ ! -f tests/e2e/test_tutorial_validation.py ]; then
                            echo "tests/e2e/test_tutorial_validation.py not present on this branch, skipping"
                            exit 0
                        fi

                        rm -f test-results/tutorial-validation.xml

                        # The Makefile recipe invokes bare `python`, but the
                        # build agent installs Debian's python3, which ships no
                        # such binary. Shim it onto PATH only when it is really
                        # missing, so this keeps working unchanged once the
                        # image picks up python-is-python3 (added to
                        # docker/build-agent/Dockerfile by this same change).
                        if ! command -v python > /dev/null 2>&1; then
                            mkdir -p .ci-bin
                            ln -sf "$(command -v python3)" .ci-bin/python
                            PATH="$PWD/.ci-bin:$PATH"
                            export PATH
                        fi

                        make test-tutorial
                    '''
                }
                post {
                    always {
                        script {
                            try {
                                junit allowEmptyResults: true, skipPublishingChecks: true, testResults: 'test-results/tutorial-validation.xml'
                            } catch (NoSuchMethodError e) {
                                echo "JUnit plugin not installed — skipping test report publishing"
                            }
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
                        expression { env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.MCP_GAME_SERVER_CHANGED == 'true' }
                        // Src/MCPGameServer/ doesn't exist on main yet — skip
                        // the stage on branches that don't ship the dir.
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

            stage('determinism-harness-replay') {
                // Runs inside mcd-build-agent, the existing Linux + Godot CI
                // agent image. ADR mc-lf0 pins v1 to Linux, not cross-arch.
                when {
                    expression {
                        env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() &&
                        config.determinismHarness?.enabled == true &&
                        (env.DETERMINISM_PER_PR_CHANGED == 'true' || env.DETERMINISM_WIRE_FORMAT_CHANGED == 'true')
                    }
                }
                steps {
                    script {
                        def cadenceKeys = []
                        if (env.DETERMINISM_WIRE_FORMAT_CHANGED == 'true') {
                            cadenceKeys << 'wireFormat'
                        }
                        if (env.DETERMINISM_PER_PR_CHANGED == 'true') {
                            cadenceKeys << 'perPr'
                        }
                        mcdDeterminismHarness.runPrCadences(config.determinismHarness, cadenceKeys)
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

            // Release PRs: full multi-platform MCDCoreExt build.
            // Linux Debug was already built above (for tests), so we only need
            // Linux Release + Windows + Android — all run in parallel.
            stage('Cross-platform Builds (Release PR)') {
                when { expression { config.targetBranch == 'release' && env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.CLIENT_CHANGED == 'true' } }
                parallel {
                    stage('MCDCoreExt Linux Release') {
                        steps {
                            sh """
                                cd Src/MCDCoreExt
                                ./build.sh --clean --configure --build --install --release
                            """
                        }
                    }

                    stage('Build MCDCoreExt (Windows Cross-compile)') {
                        stages {
                            stage('Setup MinGW OpenSSL') {
                                steps {
                                    sh """
                                        OPENSSL_DIR=Src/External/mingw-openssl
                                        if [ ! -d "\${OPENSSL_DIR}/mingw64/include/openssl" ]; then
                                            echo "Downloading MinGW OpenSSL..."
                                            mkdir -p \${OPENSSL_DIR}
                                            cd \${OPENSSL_DIR}

                                            curl -L -o openssl.tar.zst "https://mirror.msys2.org/mingw/mingw64/mingw-w64-x86_64-openssl-3.4.1-1-any.pkg.tar.zst"

                                            zstd -d openssl.tar.zst
                                            tar xf openssl.tar
                                            rm -f openssl.tar openssl.tar.zst

                                            echo "MinGW OpenSSL downloaded and extracted"
                                            ls -la mingw64/lib/*.a | head -5
                                        else
                                            echo "MinGW OpenSSL already present"
                                        fi

                                        # Crypt32 symlink is pre-created in the Docker build agent image
                                        MINGW_LIB=/usr/x86_64-w64-mingw32/lib
                                        if [ -f "\${MINGW_LIB}/libcrypt32.a" ] && [ ! -f "\${MINGW_LIB}/libCrypt32.a" ]; then
                                            echo "Creating Crypt32 symlink workaround..."
                                            ln -sf libcrypt32.a \${MINGW_LIB}/libCrypt32.a || true
                                        fi
                                    """
                                }
                            }
                            stage('MCDCoreExt Windows Debug') {
                                steps {
                                    sh """
                                        cd Src/MCDCoreExt
                                        ./build.sh --clean --configure --build --install --debug --windows
                                    """
                                }
                            }
                            stage('MCDCoreExt Windows Release') {
                                steps {
                                    sh """
                                        cd Src/MCDCoreExt
                                        ./build.sh --clean --configure --build --install --release --windows
                                    """
                                }
                            }
                        }
                    }

                    stage('Build MCDCoreExt (Android Cross-compile)') {
                        stages {
                            stage('MCDCoreExt Android arm64-v8a Debug') {
                                steps {
                                    sh """
                                        cd Src/MCDCoreExt
                                        ./build.sh --clean --configure --build --install --debug --android arm64-v8a
                                    """
                                }
                            }
                            stage('MCDCoreExt Android arm64-v8a Release') {
                                steps {
                                    sh """
                                        cd Src/MCDCoreExt
                                        ./build.sh --clean --configure --build --install --release --android arm64-v8a
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Verify All Platform Builds') {
                when { expression { config.targetBranch == 'release' && env.PR_ALREADY_MERGED != 'true' && mcdPrSupersession.stillCurrent() && env.CLIENT_CHANGED == 'true' } }
                steps {
                    sh """
                        echo "=== Linux Builds ==="
                        test -f bin/lib/Linux-x86_64/libMCDCoreExt-d.so
                        echo "✓ Linux debug build"
                        test -f bin/lib/Linux-x86_64/libMCDCoreExt.so
                        echo "✓ Linux release build"

                        echo ""
                        echo "=== Windows Builds ==="
                        test -f bin/lib/Windows-x86_64/MCDCoreExt-d.dll
                        echo "✓ Windows debug build"
                        test -f bin/lib/Windows-x86_64/MCDCoreExt.dll
                        echo "✓ Windows release build"

                        echo ""
                        echo "=== Android Builds ==="
                        test -f bin/lib/Android-arm64-v8a/libMCDCoreExt-d.so
                        echo "✓ Android arm64-v8a debug build"
                        test -f bin/lib/Android-arm64-v8a/libMCDCoreExt.so
                        echo "✓ Android arm64-v8a release build"

                        echo ""
                        echo "All platform builds verified successfully!"
                    """
                }
            }
        }

        post {
            success {
                script {
                    if (env.PR_ALREADY_MERGED == 'true') {
                        echo "PR was already merged — no validation performed."
                        return
                    }
                    // Belt and braces, the same shape mcdSteamUploadPipeline uses
                    // for UPLOAD_SUPERSEDED. Declarative should already keep this
                    // handler off a NOT_BUILT run, but MCD-PR-Main #1764 finished
                    // NOT_BUILT and still posted 'Validation passed (17 min)' to
                    // PR-2719, so the ordering between a late result change and
                    // the post block is not something to bet a green check on. A
                    // trimmed build must never claim its PR passed.
                    if (env.PR_SUPERSEDED == 'true') {
                        echo "Superseded by build #${env.SUPERSEDED_BY} — not reporting a result for this one."
                        return
                    }
                    def duration = currentBuild.durationString.replace(' and counting', '')
                    def tier = (config.targetBranch == 'release') ? 'Full validation' : 'Validation'
                    def scope = ''
                    if (env.SERVER_CHANGED == 'true' && env.CLIENT_CHANGED == 'true') {
                        scope = ''
                    } else if (env.SERVER_CHANGED == 'true') {
                        scope = ' (server only)'
                    } else if (env.CLIENT_CHANGED == 'true') {
                        scope = ' (client only)'
                    } else if (env.MCP_GAME_SERVER_CHANGED == 'true') {
                        scope = ' (mcp-game-server only)'
                    } else {
                        scope = ' (no builds needed)'
                    }
                    setGitHubStatus('success', "${tier} passed${scope} (${duration})", statusContext)

                    discordNotify.simple(
                        "✅ PR #${env.pr_number} ${tier} passed${scope} (${duration}) — ${env.pr_head_ref} → ${config.targetBranch}",
                        "3066993"
                    )
                }
            }
            failure {
                script {
                    if (env.PR_SUPERSEDED == 'true') {
                        echo "Superseded by build #${env.SUPERSEDED_BY} — not reporting a result for this one."
                        return
                    }
                    def duration = currentBuild.durationString.replace(' and counting', '')
                    def tier = (config.targetBranch == 'release') ? 'Full validation' : 'Validation'
                    setGitHubStatus('failure', "${tier} failed (${duration})", statusContext)

                    def buildUrl = "${env.JENKINS_URL_BASE}/job/${config.jobName}/${BUILD_NUMBER}/console"
                    discordNotify.simple(
                        "❌ PR #${env.pr_number} ${tier} failed — ${env.pr_head_ref} → ${config.targetBranch} — View: ${buildUrl}",
                        "15158332",
                        env.pr_author
                    )
                }
            }
            aborted {
                script {
                    setGitHubStatus('error', 'Build was aborted', statusContext)
                }
            }

            // THE CHECK MUST NEVER BE LEFT PENDING. This is the backstop, and it
            // is the half of mc-waxw that actually blocked merges.
            //
            // 'Setup PR Info' posts 'pending' on the head SHA before anything
            // else runs, and until 2026-08-25 exactly three handlers could ever
            // replace it: success, failure and aborted. A build that ends
            // NOT_BUILT matches none of them, so the PR sat on a check that could
            // never resolve and mergeStateStatus went BLOCKED. Measured on
            // 2026-08-25: MCD-PR-Main #1761 and #1766 both ended NOT_BUILT and
            // left PR-2714 and PR-2720 reading 'pending: Validation started' with
            // no build still running for either.
            //
            // NOT_BUILT arrives from several directions and more will be added,
            // which is why this is a cleanup{} sweep over "did anything terminal
            // get posted" rather than a notBuilt{} handler enumerating causes.
            // cleanup runs last, after success/failure/aborted have had their
            // turn, so it can see what they did and stay out of the way.
            cleanup {
                script {
                    if (env.PR_STATUS_POSTED == 'true') {
                        return
                    }

                    // A newer build for the SAME head SHA owns that SHA's status.
                    // Posting here would race it and could paint a passing PR red,
                    // so the trimmed build says nothing and lets the winner report.
                    if (env.PR_SUPERSEDED == 'true' && env.SUPERSEDED_BY_SHA == env.pr_head_sha) {
                        echo "Superseded by build #${env.SUPERSEDED_BY} on the same SHA — leaving the status to that build."
                        return
                    }

                    String why = (env.PR_SUPERSEDED == 'true')
                        ? "Superseded by build #${env.SUPERSEDED_BY}"
                        : "Build did not complete (${currentBuild.currentResult})"
                    setGitHubStatus('error', why, statusContext)
                }
            }
        }
    }
}

/**
 * Set GitHub commit status on the PR head SHA.
 * Uses the GitHub Status API via curl as a reliable fallback.
 */
def setGitHubStatus(String state, String description, String context) {
    def buildUrl = "${env.JENKINS_URL_BASE}/job/${env.JOB_NAME}/${BUILD_NUMBER}/"
    def truncDesc = description.take(140)

    // What the cleanup{} backstop reads. 'pending' deliberately does not count:
    // it is the status the backstop exists to replace. Set before the curl, not
    // after, so a failed POST does not turn into a second POST from cleanup
    // reporting a different thing.
    if (state != 'pending') {
        env.PR_STATUS_POSTED = 'true'
    }

    sh """
        curl -s -X POST \
            -H "Authorization: token \$GITHUB_STATUS_TOKEN" \
            -H "Accept: application/vnd.github.v3+json" \
            "https://api.github.com/repos/MechaCorpsGames/MCDClient/statuses/${env.pr_head_sha}" \
            -d '{
                "state": "${state}",
                "target_url": "${buildUrl}",
                "description": "${truncDesc}",
                "context": "${context}"
            }' || echo "Warning: Failed to set GitHub status"
    """
}

return this
