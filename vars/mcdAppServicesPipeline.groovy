// MechaCorps App Services Pipeline - Shared Library
// Deploys per-environment services: Auth, AccountService, AuctionHouse,
// and — for the environments that own one — CrashReporting + MCP.
// Each service is independently catchError-wrapped so one failure
// marks the build UNSTABLE without blocking other deploys.

def call(Map config) {
    // Required config:
    //   branch: 'main', 'release', 'features/card', etc.
    //   environment: 'main', 'release', 'feature-card', 'feature-backend'
    //   webhookToken: 'mcdappservices-main'
    //   jobName: 'MCDAppServices-Main'

    // All environments use per-env .env files and isolated compose projects.
    // Port offsets from environments.toml: Auth=base+81, Account=base+82, Auction=base+83
    //
    // Each environment owns its own src tree so concurrent pipelines and
    // ad-hoc work in /var/opt/mechacorpsgames cannot poison each other's
    // docker builds. The Sync Src Tree stage resets srcRoot to origin/<branch>
    // before any compose build runs, under a per-env lock.
    def srcRoot = "/var/opt/mechacorpsgames-${config.environment}"
    def srcDir = "${srcRoot}/Src"

    // The remote the deploy tree is recovered/bootstrapped from. Deliberately
    // NOT ${GIT_URL} (the job's SCM URL, which is HTTPS): this build container
    // mounts only the SSH deploy key — /var/lib/jenkins/.ssh, see the agent
    // args below — and carries no HTTPS credentials. A tree recovered from
    // GIT_URL therefore authenticates against nothing and silently stops
    // syncing, which is what left MCDServices-Main red for ~15h on 2026-08-06
    // (MCDClient mc-t4m3). Overridable via config for callers on another repo.
    def deployRemote = config.deployRemote ?: 'git@github.com:MechaCorpsGames/MCDClient.git'

    def basePorts = [
        'release':         42000,
        'main':            43000,
        'feature-card':    44000,
        'feature-backend': 45000,
    ]
    def basePort = basePorts[config.environment]

    // Compose project names
    def authProject    = "mcd-${config.environment}-auth"
    def accountProject = "mcd-${config.environment}-account"
    def auctionProject = "mcd-${config.environment}-auction"

    // Env file flags
    def authEnvFlag    = "--env-file .env.auth.${config.environment}"
    def accountEnvFlag = "--env-file .env.account.${config.environment}"
    def auctionEnvFlag = "--env-file .env.auction.${config.environment}"

    // Health check ports
    def authPort    = "${basePort + 81}"
    def accountPort = "${basePort + 82}"
    def auctionPort = "${basePort + 83}"

    // Container name prefixes (compose project name with underscores)
    def authContainer    = "${authProject}-auth-1"
    def accountContainer = "${accountProject}-account-service-1"
    def auctionContainer = "${auctionProject}-auction-house-1"

    // Postgres container (auth stack owns postgres)
    def postgresContainer = "${authProject}-postgres-1"

    // Environments whose CrashReporting + MCP stack this pipeline owns.
    // 'main' is deliberately absent: MCDServices-Main deploys main's crash
    // stack under compose project 'src' (mcdServicesPipeline.groovy), and two
    // jobs deploying it would fight over the same published ports. Adding an
    // environment here requires .env.crash-reporting.<env> on its deploy host
    // (python3 Src/Tools/deploy/gen_env.py <env>).
    def crashEnvironments = ['feature-backend']
    def deployCrashReporting = crashEnvironments.contains(config.environment)

    def crashProject = "mcd-${config.environment}-crash"
    def crashEnvFile = ".env.crash-reporting.${config.environment}"
    def crashEnvFlag = "--env-file ${crashEnvFile}"

    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                args "-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /opt/mechacorps:/opt/mechacorps -v ${srcRoot}:${srcRoot} --network host --group-add 111 --group-add 995 --group-add 1000"
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '10'))
            // Serialize builds per job — each environment maps to one Jenkins job,
            // so this prevents concurrent webhooks on the same env from racing
            // on srcRoot during the Sync/Deploy stages.
            disableConcurrentBuilds()
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            BRANCH_NAME = "${config.branch}"
            DEPLOY_ENV = "${config.environment}"
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

                        currentBuild.description = "${commitMsg}\nby ${author} → ${config.environment}"
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
                        def baseRef = env.before_sha
                        if (!baseRef || baseRef.startsWith('0000000')) {
                            echo "No valid before SHA — deploying everything"
                            env.AUTH_CHANGED = 'true'
                            env.ACCOUNT_SERVICE_CHANGED = 'true'
                            env.AUCTION_HOUSE_CHANGED = 'true'
                            env.DOCKER_SMOKE_CHANGED = 'true'
                            env.CRASH_REPORTING_CHANGED = deployCrashReporting.toString()
                            env.CRASH_REPORTING_SRC_CHANGED = 'true'
                        } else {
                            sh "git fetch origin ${baseRef} 2>/dev/null || true"
                            def changes = mcdChangeDetection.detect(baseRef)
                            env.AUTH_CHANGED = changes.authChanged.toString()
                            env.ACCOUNT_SERVICE_CHANGED = changes.accountServiceChanged.toString()
                            env.AUCTION_HOUSE_CHANGED = changes.auctionHouseChanged.toString()
                            env.DOCKER_SMOKE_CHANGED = changes.dockerSmokeChanged.toString()
                            // Gated on deployCrashReporting so an environment that
                            // does not own a crash stack never lights this up. It
                            // feeds anyWork and the deploy stage's when{} both.
                            env.CRASH_REPORTING_CHANGED = (deployCrashReporting && changes.crashReportingChanged).toString()
                            // Deliberately NOT gated on deployCrashReporting: the
                            // Postgres-backed Service Tests stage runs the module's
                            // Go tests in every app-services environment, including
                            // the ones whose crash stack another job deploys. Gating
                            // the tests on the deploy flag is what left them
                            // unexecuted: 'main' is not in crashEnvironments, so
                            // CRASH_REPORTING_CHANGED was false for every
                            // CrashReporting push to main.
                            env.CRASH_REPORTING_SRC_CHANGED = changes.crashReportingChanged.toString()
                        }

                        def anyWork = (env.AUTH_CHANGED == 'true' ||
                                       env.ACCOUNT_SERVICE_CHANGED == 'true' ||
                                       env.AUCTION_HOUSE_CHANGED == 'true' ||
                                       env.DOCKER_SMOKE_CHANGED == 'true' ||
                                       env.CRASH_REPORTING_CHANGED == 'true' ||
                                       env.CRASH_REPORTING_SRC_CHANGED == 'true')
                        if (!anyWork) {
                            currentBuild.description += "\n⏭️ No app service changes — skipped"
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
                            env.AUTH_CHANGED = 'false'
                            env.ACCOUNT_SERVICE_CHANGED = 'false'
                            env.AUCTION_HOUSE_CHANGED = 'false'
                            env.DOCKER_SMOKE_CHANGED = 'false'
                            env.CRASH_REPORTING_CHANGED = 'false'
                            env.CRASH_REPORTING_SRC_CHANGED = 'false'
                        }
                    }
                }
            }

            stage('Service Tests') {
                when {
                    expression {
                        env.AUTH_CHANGED == 'true' ||
                        env.ACCOUNT_SERVICE_CHANGED == 'true' ||
                        env.AUCTION_HOUSE_CHANGED == 'true' ||
                        env.CRASH_REPORTING_SRC_CHANGED == 'true'
                    }
                }
                steps {
                    // Post-MCDClient #1425, the legacy local Postgres helper
                    // and Nix devShell are gone; scripts/docker_dev.py is now the
                    // canonical way to bring up Postgres + Auth/Account/
                    // Auction for integration tests. PGHOST=localhost gates
                    // each module's `requireIntegrationDB(t)` per MCDClient
                    // #1443.
                    //
                    // CrashReporting joined this stage in MCDClient mc-56jd.
                    // Its Postgres-backed tests, including the crash-dedup
                    // suite from #2399/#2402, had never executed in any
                    // pipeline: no stage ran them with a database, and the
                    // module's own harness exited 0 when it could not reach
                    // one. It runs through `make test-go-db`, which sets
                    // MCDC_REQUIRE_DB_TESTS=1 so a missing or unreachable
                    // database fails the stage instead of skipping quietly.
                    sh '''
                        set -e
                        # compose.yml hardcodes `name: mcd`, so every workspace
                        # that runs `docker compose up` shares the same global
                        # network `mcd_mcd-net` and volume `mcd_mcd-pgdata`.
                        # Concurrent AppServices builds (Main / FeatureBackend
                        # / FeatureCard) then trip over each other:
                        #   - duplicate `mcd_mcd-net` networks → compose v2
                        #     refuses with `ambiguous (2 matches found)`.
                        #   - postgres data corrupts across schema bumps when
                        #     two workspaces share `mcd_mcd-pgdata`.
                        # Override the project name per workspace so the
                        # network/volume names become `mcd-${JOB_NAME}_mcd-net`
                        # / `mcd-${JOB_NAME}_mcd-pgdata`, fully isolated.
                        # Compose requires lowercase project names.
                        export COMPOSE_PROJECT_NAME="mcd-$(echo "${JOB_NAME}" | tr 'A-Z' 'a-z')"

                        cleanup() {
                            python3 scripts/docker_dev.py down --pg || true
                        }
                        trap cleanup EXIT

                        # Tear down any leftover compose state from a prior
                        # build of THIS workspace (matched by the project name
                        # set above) before init/up.
                        docker compose -p "$COMPOSE_PROJECT_NAME" --project-directory "$PWD/docker" -f "$PWD/docker/compose.yml" down -v --remove-orphans 2>/dev/null || true

                        # First-time bring-up on a fresh workspace needs
                        # `init` to generate JWT keys and seed PGDATA before
                        # `up` will succeed. init is idempotent; safe to
                        # call every build.
                        python3 scripts/docker_dev.py init
                        python3 scripts/docker_dev.py up

                        # compose.yml binds postgres to an ephemeral host
                        # port (MCD_PG_HOST_PORT unset) so multiple concurrent
                        # workspaces don't collide on 5432. The Go test
                        # runners read libpq PGHOST/PGPORT, so look up the
                        # actual mapped host port for THIS workspace's
                        # postgres container and export. The build agent
                        # runs with --network host, so localhost reaches it.
                        export PGHOST=localhost
                        export PGPORT="$(docker port "${COMPOSE_PROJECT_NAME}-postgres-1" 5432/tcp | awk -F: 'NR==1{print $NF}')"
                        export PGUSER=mechacorps
                        export PGPASSWORD=mechacorps
                        if [ -z "$PGPORT" ]; then
                            echo "FAIL: could not resolve mapped postgres host port" >&2
                            exit 1
                        fi
                        echo "Postgres reachable at $PGHOST:$PGPORT"

                        # AUTH_CHANGED / ACCOUNT_SERVICE_CHANGED /
                        # AUCTION_HOUSE_CHANGED / CRASH_REPORTING_SRC_CHANGED
                        # are exported by the 'Detect Changes' stage's env
                        # assignments. The stage now also wakes for a
                        # CrashReporting-only change, so the app-service trio
                        # is guarded rather than run unconditionally; its
                        # behaviour is otherwise unchanged (any one of the
                        # three still runs all three). set -e is in force, so
                        # a failure inside either block fails the stage.
                        if [ "$AUTH_CHANGED" = "true" ] || \
                           [ "$ACCOUNT_SERVICE_CHANGED" = "true" ] || \
                           [ "$AUCTION_HOUSE_CHANGED" = "true" ]; then
                            (cd Src/Auth && PGDATABASE=mechacorps_auth go test ./...)
                            (cd Src/AccountService && PGDATABASE=mechacorps_account go test ./...)
                            (cd Src/AuctionHouse && PGDATABASE=mechacorps_auction go test ./...)
                        else
                            echo "Auth/AccountService/AuctionHouse unchanged, skipping their tests"
                        fi

                        # MCDClient's test-go-db target sets
                        # MCDC_REQUIRE_DB_TESTS=1, which makes every escape the
                        # old harness had (-short, MCDC_SKIP_DB_TESTS, unset
                        # PGHOST, a database that never answered) a hard
                        # failure. A green run here therefore means the
                        # Postgres-backed crash tests really executed.
                        #
                        # MERGE ORDER: this needs MCDClient's `test-go-db`
                        # (mc-56jd), which lands first. There is deliberately
                        # no file-existence guard. A silent skip is the exact
                        # failure mode this stage exists to end.
                        if [ "$CRASH_REPORTING_SRC_CHANGED" = "true" ]; then
                            PGDATABASE=mechacorps_crashes make test-go-db MODULE=CrashReporting
                        else
                            echo "CrashReporting unchanged, skipping its tests"
                        fi
                    '''
                }
            }

            // Stand up the full mcd compose stack against the just-checked-out
            // tree and run the docker-smoke pytest suite (tests/e2e/ -m docker).
            // The suite covers `python scripts/docker_dev.py up`: keypair
            // generation, compose health, /health endpoints, AccountService
            // /Data load — a strictly tighter contract than the per-service
            // go-test stage above, which only exercises in-process code.
            //
            // Test failures are catchError-wrapped to UNSTABLE so deploy can
            // still proceed. The post-block compose teardown is belt-and-
            // suspenders: docker_dev.py owns `down --pg`, but a partial-
            // failure or aborted run could leave the stack up on the agent.
            //
            // Wakes up on any of: scripts/docker_dev.py / docker/** / the
            // smoke fixtures themselves (tests/e2e/test_docker_dev_smoke.py
            // + conftest/helpers/test_assertions) — that's the
            // dockerSmokeChanged flag from mcdChangeDetection — OR when any
            // service the stack runs (Auth/Account/Auction) changes.
            stage('Docker Smoke') {
                when {
                    expression {
                        env.DOCKER_SMOKE_CHANGED == 'true' ||
                        env.AUTH_CHANGED == 'true' ||
                        env.ACCOUNT_SERVICE_CHANGED == 'true' ||
                        env.AUCTION_HOUSE_CHANGED == 'true'
                    }
                }
                // The compose stack the smoke pytest spins up uses the
                // default project name `mcd`, so containers (mcd-postgres-1
                // etc.) are GLOBAL to the docker daemon. Concurrent
                // AppServices builds (Main / FeatureBackend / FeatureCard)
                // all racing the same names produced random-hex-prefixed
                // duplicates and `compose ps reported no published host port`
                // false failures. Serialize Docker Smoke globally so only one
                // AppServices build at a time owns the `mcd-*` namespace.
                steps {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        // Lockable Resources plugin isn't installed on this
                        // Jenkins, so use a host-level file lock to serialize
                        // Docker Smoke across concurrent AppServices builds.
                        // The smoke spins up the default-project `mcd-*`
                        // container namespace, which is GLOBAL on the daemon;
                        // concurrent runs produced
                        // `Container "/<hex>_mcd-postgres-1" is already in use`.
                        // /opt/mechacorps is bind-mounted into every build
                        // agent (see pipeline `agent { docker { args ... } }`),
                        // so a flock on a file there is genuinely cross-agent.
                        sh '''
                            set -e
                            rm -rf test-results
                            mkdir -p test-results

                            mkdir -p /opt/mechacorps/.locks
                            (
                                flock 9
                                set -e
                                # Aggressive cleanup of stranded default-project
                                # containers — `down -v --remove-orphans` first,
                                # then force-rm in case compose lost track.
                                docker compose --project-directory "$PWD/docker" -f "$PWD/docker/compose.yml" down -v --remove-orphans 2>/dev/null || true
                                for cn in mcd-postgres-1 mcd-auth-1 mcd-account-1 mcd-auction-1; do
                                    docker rm -f "$cn" 2>/dev/null || true
                                done

                                python3 -m pytest tests/e2e/ -m docker --junitxml=test-results/docker-smoke.xml
                            ) 9>/opt/mechacorps/.locks/mcd-docker-smoke.lock
                        '''
                    }
                }
                post {
                    always {
                        // Belt-and-suspenders teardown — the smoke owns
                        // `down --pg` but a partial failure or pytest abort
                        // could leave the stack up.
                        sh '''
                            docker compose --project-directory "$PWD/docker" -f "$PWD/docker/compose.yml" down -v --remove-orphans 2>/dev/null || true
                        '''
                        script {
                            try {
                                junit allowEmptyResults: true, skipPublishingChecks: true, testResults: 'test-results/docker-smoke.xml'
                            } catch (NoSuchMethodError e) {
                                echo "JUnit plugin not installed — skipping test report publishing"
                            }
                        }
                    }
                }
            }

            // ================================================================
            // Sync + deploy run together so srcRoot is pinned to the branch
            // HEAD for the duration. disableConcurrentBuilds() at pipeline
            // level serializes builds of this job — and since each env has
            // its own job, that also serializes access to srcRoot.
            // Each service stage is catchError-wrapped: one failure marks
            // the build UNSTABLE but does NOT block other services.
            // ================================================================

            stage('Sync and Deploy') {
                when {
                    expression {
                        env.AUTH_CHANGED == 'true' ||
                        env.ACCOUNT_SERVICE_CHANGED == 'true' ||
                        env.AUCTION_HOUSE_CHANGED == 'true' ||
                        env.CRASH_REPORTING_CHANGED == 'true'
                    }
                }
                stages {
                    stage('Sync Src Tree') {
                        steps {
                            sh """
                                set -e

                                # Repair ownership if root-owned files crept in (e.g. from
                                # a manual sudo rsync or docker build that wrote as root).
                                # Without this, git checkout fails with "Permission denied"
                                # on dirs like Src/ that are root-owned inside a jenkins-
                                # owned parent.
                                #
                                # This runs inside a Docker build-agent container, so host
                                # sudo is not available. Instead, spawn a throwaway Alpine
                                # container via the mounted Docker socket — Docker runs
                                # containers as root by default, so it can chown without
                                # any sudoers configuration.
                                if [ -d ${srcRoot} ]; then
                                    if find ${srcRoot} -maxdepth 2 ! -user \$(id -u) -print -quit 2>/dev/null | grep -q .; then
                                        echo "Repairing ownership on ${srcRoot} (foreign-owned files detected)"
                                        docker run --rm -v ${srcRoot}:${srcRoot} alpine chown -R \$(id -u):\$(id -g) ${srcRoot}
                                    fi
                                fi

                                if [ ! -d ${srcRoot}/.git ]; then
                                    if [ -d ${srcRoot} ] && [ "\$(ls -A ${srcRoot} 2>/dev/null)" ]; then
                                        # Directory exists with files but no .git — recover in place
                                        # without wiping gitignored secrets (.env.auth.${env} etc.).
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
                                # -f -B: force-create-or-reset local branch to origin/<branch>
                                # and overwrite any untracked files that would conflict. Required
                                # on a freshly git-init'd deploy dir where pre-existing working-tree
                                # files collide with the incoming tracked content.
                                git checkout -f -B ${config.branch} origin/${config.branch}
                                # -fd (not -fdx): preserve gitignored secrets like .env.auth.${env}
                                git clean -fd
                                echo "Synced ${srcRoot} to \$(git rev-parse --short HEAD) on ${config.branch}"
                            """
                        }
                    }

                    stage('Deploy Auth') {
                        when { expression { env.AUTH_CHANGED == 'true' } }
                        steps {
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                script {
                                    echo "Auth service changed — rebuilding Docker container (${config.environment})"

                                    sh """
                                        cd ${srcDir}
                                        docker compose -p ${authProject} -f docker-compose.auth.yml ${authEnvFlag} build --no-cache auth
                                        docker compose -p ${authProject} -f docker-compose.auth.yml ${authEnvFlag} up -d --force-recreate auth
                                        sleep 5

                                        OK=false
                                        for i in \$(seq 1 10); do
                                            RESULT=\$(curl -s -o /dev/null -w '%{http_code}' http://localhost:${authPort}/health || true)
                                            if [ "\$RESULT" = "200" ]; then
                                                echo "✓ Auth service health check passed (${config.environment} :${authPort})"
                                                OK=true
                                                break
                                            fi
                                            echo "Waiting for Auth service... (attempt \$i/10)"
                                            sleep 3
                                        done
                                        if [ "\$OK" = "false" ]; then
                                            echo "✗ Auth service health check failed (${config.environment} :${authPort})"
                                            docker logs ${authContainer} --tail 20 2>&1 || true
                                            exit 1
                                        fi
                                    """
                                    env.AUTH_DEPLOYED = "true"
                                }
                            }
                        }
                    }

                    stage('Deploy AccountService') {
                        when { expression { env.ACCOUNT_SERVICE_CHANGED == 'true' } }
                        steps {
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                script {
                                    echo "AccountService changed — rebuilding Docker container (${config.environment})"

                                    sh """
                                        # Ensure the account database exists (shares postgres with Auth)
                                        docker exec ${postgresContainer} psql -U mechacorps -d postgres -c "SELECT 1 FROM pg_database WHERE datname = 'mechacorps_account'" | grep -q 1 || \
                                            docker exec ${postgresContainer} psql -U mechacorps -d postgres -c "CREATE DATABASE mechacorps_account;" || true

                                        cd ${srcDir}
                                        docker compose -p ${accountProject} -f docker-compose.account.yml ${accountEnvFlag} build --no-cache account-service
                                        docker compose -p ${accountProject} -f docker-compose.account.yml ${accountEnvFlag} up -d --force-recreate account-service
                                        sleep 5

                                        OK=false
                                        for i in \$(seq 1 10); do
                                            RESULT=\$(curl -s -o /dev/null -w '%{http_code}' http://localhost:${accountPort}/health || true)
                                            if [ "\$RESULT" = "200" ]; then
                                                echo "✓ AccountService health check passed (${config.environment} :${accountPort})"
                                                OK=true
                                                break
                                            fi
                                            echo "Waiting for AccountService... (attempt \$i/10)"
                                            sleep 3
                                        done
                                        if [ "\$OK" = "false" ]; then
                                            echo "✗ AccountService health check failed (${config.environment} :${accountPort})"
                                            docker logs ${accountContainer} --tail 20 2>&1 || true
                                            exit 1
                                        fi
                                    """
                                    env.ACCOUNT_SERVICE_DEPLOYED = "true"
                                }
                            }
                        }
                    }

                    stage('Deploy AuctionHouse') {
                        when { expression { env.AUCTION_HOUSE_CHANGED == 'true' } }
                        steps {
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                script {
                                    echo "AuctionHouse changed — rebuilding Docker container (${config.environment})"

                                    sh """
                                        cd ${srcDir}
                                        docker compose -p ${auctionProject} -f docker-compose.auction.yml ${auctionEnvFlag} build --no-cache auction-house
                                        docker compose -p ${auctionProject} -f docker-compose.auction.yml ${auctionEnvFlag} up -d --force-recreate auction-house
                                        sleep 5

                                        OK=false
                                        for i in \$(seq 1 10); do
                                            RESULT=\$(curl -s -o /dev/null -w '%{http_code}' http://localhost:${auctionPort}/health || true)
                                            if [ "\$RESULT" = "200" ]; then
                                                echo "✓ AuctionHouse health check passed (${config.environment} :${auctionPort})"
                                                OK=true
                                                break
                                            fi
                                            echo "Waiting for AuctionHouse... (attempt \$i/10)"
                                            sleep 3
                                        done
                                        if [ "\$OK" = "false" ]; then
                                            echo "✗ AuctionHouse health check failed (${config.environment} :${auctionPort})"
                                            docker logs ${auctionContainer} --tail 20 2>&1 || true
                                            exit 1
                                        fi
                                    """
                                    env.AUCTION_HOUSE_DEPLOYED = "true"
                                }
                            }
                        }
                    }

                    stage('Deploy CrashReporting + MCP') {
                        when { expression { env.CRASH_REPORTING_CHANGED == 'true' } }
                        steps {
                            catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                script {
                                    echo "CrashReporting/MCPServer changed — rebuilding Docker containers (${config.environment})"

                                    sh """
                                        set -e
                                        cd ${srcDir}

                                        if [ ! -f ${crashEnvFile} ]; then
                                            echo "✗ ${crashEnvFile} not found in ${srcDir}"
                                            echo "  Provision it on the deploy host: python3 Src/Tools/deploy/gen_env.py ${config.environment}"
                                            exit 1
                                        fi

                                        # docker-compose.crash-reporting.yml pins
                                        # `env_file: .env.crash-reporting` (unsuffixed) on both
                                        # services, so --env-file alone is not enough — compose
                                        # aborts when that exact filename is absent. This tree
                                        # only ever serves ${config.environment}, so make the
                                        # unsuffixed name resolve to this environment's file.
                                        cp ${crashEnvFile} .env.crash-reporting

                                        # crash-reporting persists to mechacorps_crashes on the
                                        # postgres the auth stack owns.
                                        docker exec ${postgresContainer} psql -U mechacorps -d postgres -c "SELECT 1 FROM pg_database WHERE datname = 'mechacorps_crashes'" | grep -q 1 || \
                                            docker exec ${postgresContainer} psql -U mechacorps -d postgres -c "CREATE DATABASE mechacorps_crashes;" || true

                                        docker compose -p ${crashProject} -f docker-compose.crash-reporting.yml ${crashEnvFlag} build --no-cache crash-reporting mcp-server
                                        docker compose -p ${crashProject} -f docker-compose.crash-reporting.yml ${crashEnvFlag} up -d --force-recreate crash-reporting mcp-server
                                        sleep 5

                                        # Read the ports back out of the same env file compose
                                        # substituted them from rather than recomputing them
                                        # here — gen_env.py owns the port formula. Fallbacks
                                        # mirror the compose file's own defaults.
                                        CR_PORT=\$(grep -E '^CR_PORT=' ${crashEnvFile} | tail -1 | cut -d= -f2)
                                        MCP_PORT=\$(grep -E '^MCP_PORT=' ${crashEnvFile} | tail -1 | cut -d= -f2)
                                        : "\${CR_PORT:=8090}"
                                        : "\${MCP_PORT:=8095}"

                                        PASS=true
                                        for SVC in "CrashReporting:\$CR_PORT" "MCP Server:\$MCP_PORT"; do
                                            NAME="\${SVC%%:*}"
                                            PORT="\${SVC##*:}"
                                            OK=false
                                            for i in \$(seq 1 10); do
                                                RESULT=\$(curl -s -o /dev/null -w '%{http_code}' http://localhost:\$PORT/health || true)
                                                if [ "\$RESULT" = "200" ]; then
                                                    echo "✓ \$NAME health check passed (${config.environment} :\$PORT)"
                                                    OK=true
                                                    break
                                                fi
                                                echo "Waiting for \$NAME... (attempt \$i/10)"
                                                sleep 3
                                            done
                                            if [ "\$OK" = "false" ]; then
                                                echo "✗ \$NAME health check failed (${config.environment} :\$PORT)"
                                                PASS=false
                                            fi
                                        done
                                        if [ "\$PASS" = "false" ]; then
                                            docker compose -p ${crashProject} -f docker-compose.crash-reporting.yml ${crashEnvFlag} logs --tail=50 crash-reporting mcp-server
                                            exit 1
                                        fi
                                    """
                                    env.CRASH_REPORTING_DEPLOYED = "true"
                                }
                            }
                        }
                    }
                }
            }
        }

        post {
            success {
                script {
                    def deployNotes = []
                    if (env.AUTH_DEPLOYED == "true") deployNotes << "Auth"
                    if (env.ACCOUNT_SERVICE_DEPLOYED == "true") deployNotes << "AccountService"
                    if (env.AUCTION_HOUSE_DEPLOYED == "true") deployNotes << "AuctionHouse"
                    if (env.CRASH_REPORTING_DEPLOYED == "true") deployNotes << "CrashReporting+MCP"

                    if (deployNotes.isEmpty()) {
                        echo "No app service changes detected — nothing deployed"
                        return
                    }

                    discordNotify.success(
                        title: "MechaCorps App Services Deploy",
                        message: "✅ Deployed: ${deployNotes.join(', ')}",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: config.environment,
                        branch: config.branch,
                        version: env.SVC_VERSION
                    )
                }
            }
            unstable {
                script {
                    def deployed = []
                    def failed = []
                    if (env.AUTH_DEPLOYED == "true") deployed << "Auth"
                    else if (env.AUTH_CHANGED == 'true') failed << "Auth"
                    if (env.ACCOUNT_SERVICE_DEPLOYED == "true") deployed << "AccountService"
                    else if (env.ACCOUNT_SERVICE_CHANGED == 'true') failed << "AccountService"
                    if (env.AUCTION_HOUSE_DEPLOYED == "true") deployed << "AuctionHouse"
                    else if (env.AUCTION_HOUSE_CHANGED == 'true') failed << "AuctionHouse"
                    if (env.CRASH_REPORTING_DEPLOYED == "true") deployed << "CrashReporting+MCP"
                    else if (env.CRASH_REPORTING_CHANGED == 'true') failed << "CrashReporting+MCP"

                    def msg = "⚠️ Partial deploy"
                    if (deployed) msg += " — OK: ${deployed.join(', ')}"
                    if (failed) msg += " — FAILED: ${failed.join(', ')}"

                    discordNotify.failure(
                        title: "MechaCorps App Services Deploy",
                        message: msg,
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
                        title: "MechaCorps App Services Deploy",
                        message: "❌ Deploy failed",
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
