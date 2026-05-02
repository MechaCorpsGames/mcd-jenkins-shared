// MechaCorps App Services Pipeline - Shared Library
// Deploys per-environment services: Auth, AccountService, AuctionHouse
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
    def authContainer    = "${authProject}_auth_1"
    def accountContainer = "${accountProject}_account-service_1"
    def auctionContainer = "${auctionProject}_auction-house_1"

    // Postgres container (auth stack owns postgres)
    def postgresContainer = "${authProject}_postgres_1"

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
                        } else {
                            sh "git fetch origin ${baseRef} 2>/dev/null || true"
                            def changes = mcdChangeDetection.detect(baseRef)
                            env.AUTH_CHANGED = changes.authChanged.toString()
                            env.ACCOUNT_SERVICE_CHANGED = changes.accountServiceChanged.toString()
                            env.AUCTION_HOUSE_CHANGED = changes.auctionHouseChanged.toString()
                            env.DOCKER_SMOKE_CHANGED = changes.dockerSmokeChanged.toString()
                        }

                        def anyWork = (env.AUTH_CHANGED == 'true' ||
                                       env.ACCOUNT_SERVICE_CHANGED == 'true' ||
                                       env.AUCTION_HOUSE_CHANGED == 'true' ||
                                       env.DOCKER_SMOKE_CHANGED == 'true')
                        if (!anyWork) {
                            currentBuild.description += "\n⏭️ No app service changes — skipped"
                            currentBuild.result = 'NOT_BUILT'
                        }
                    }
                }
            }

            stage('Service Tests') {
                when {
                    expression {
                        env.AUTH_CHANGED == 'true' ||
                        env.ACCOUNT_SERVICE_CHANGED == 'true' ||
                        env.AUCTION_HOUSE_CHANGED == 'true'
                    }
                }
                steps {
                    sh '''
                        unset GOROOT
                        nix develop . --command bash -c '
                            dev-pg.sh init && dev-pg.sh start || exit 1
                            trap "dev-pg.sh stop" EXIT
                            cd Src/Auth && go test ./... &&
                            cd ../AccountService && go test ./... &&
                            cd ../AuctionHouse && go test ./...
                        '
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
                steps {
                    catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                        sh '''
                            set -e
                            rm -rf test-results
                            mkdir -p test-results

                            # Pre-cleanup: a previous build that didn't tear
                            # down cleanly would leave containers using the
                            # same fixed names (mcd-postgres-1, mcd-auth-1,
                            # mcd-account-1, mcd-auction-1). docker_dev.py up
                            # would then either reuse them (masking real
                            # bugs) or conflict on names.
                            docker compose --project-directory docker -f docker/compose.yml down -v --remove-orphans 2>/dev/null || true

                            unset GOROOT
                            nix develop . --command bash -c '
                                python -m pytest tests/e2e/ -m docker --junitxml=test-results/docker-smoke.xml
                            '
                        '''
                    }
                }
                post {
                    always {
                        // Belt-and-suspenders teardown — the smoke owns
                        // `down --pg` but a partial failure or pytest abort
                        // could leave the stack up. The next build would
                        // hit name collisions on mcd-* containers.
                        sh '''
                            docker compose --project-directory docker -f docker/compose.yml down -v --remove-orphans 2>/dev/null || true
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
                        env.AUCTION_HOUSE_CHANGED == 'true'
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
                                        git remote add origin "\${GIT_URL}"
                                    else
                                        echo "Bootstrapping ${srcRoot} from \${GIT_URL}"
                                        git clone "\${GIT_URL}" ${srcRoot}
                                    fi
                                fi
                                cd ${srcRoot}
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
                                        docker-compose -p ${authProject} -f docker-compose.auth.yml ${authEnvFlag} build --no-cache auth
                                        docker-compose -p ${authProject} -f docker-compose.auth.yml ${authEnvFlag} up -d --force-recreate auth
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
                                        docker-compose -p ${accountProject} -f docker-compose.account.yml ${accountEnvFlag} build --no-cache account-service
                                        docker-compose -p ${accountProject} -f docker-compose.account.yml ${accountEnvFlag} up -d --force-recreate account-service
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
                                        docker-compose -p ${auctionProject} -f docker-compose.auction.yml ${auctionEnvFlag} build --no-cache auction-house
                                        docker-compose -p ${auctionProject} -f docker-compose.auction.yml ${auctionEnvFlag} up -d --force-recreate auction-house
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
