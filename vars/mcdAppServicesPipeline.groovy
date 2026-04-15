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
    def srcDir = '/var/opt/mechacorpsgames/Src'

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
                args '-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /opt/mechacorps:/opt/mechacorps -v /var/opt/mechacorpsgames/Src:/var/opt/mechacorpsgames/Src --network host --group-add 111 --group-add 995 --group-add 1000'
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '10'))
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
                        } else {
                            sh "git fetch origin ${baseRef} 2>/dev/null || true"
                            def changes = mcdChangeDetection.detect(baseRef)
                            env.AUTH_CHANGED = changes.authChanged.toString()
                            env.ACCOUNT_SERVICE_CHANGED = changes.accountServiceChanged.toString()
                            env.AUCTION_HOUSE_CHANGED = changes.auctionHouseChanged.toString()
                        }

                        def anyWork = (env.AUTH_CHANGED == 'true' ||
                                       env.ACCOUNT_SERVICE_CHANGED == 'true' ||
                                       env.AUCTION_HOUSE_CHANGED == 'true')
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

            // Deploy stages run docker-compose build from the shared
            // /var/opt/mechacorpsgames/Src tree, which every environment's
            // pipeline reuses. Sync it to the branch HEAD before any deploy
            // so the container images match the code being deployed.
            stage('Sync Src Tree') {
                when {
                    expression {
                        env.AUTH_CHANGED == 'true' ||
                        env.ACCOUNT_SERVICE_CHANGED == 'true' ||
                        env.AUCTION_HOUSE_CHANGED == 'true'
                    }
                }
                steps {
                    sh """
                        cd ${srcDir}/..
                        git fetch origin ${config.branch}
                        git checkout ${config.branch}
                        git reset --hard origin/${config.branch}
                        git log -1 --oneline
                    """
                }
            }

            // ================================================================
            // Each service stage is catchError-wrapped: one failure marks
            // the build UNSTABLE but does NOT block other services.
            // ================================================================

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
