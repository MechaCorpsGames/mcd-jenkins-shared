// MechaCorps Client Pipeline - Shared Library
// Builds: MCDCoreExt (GDExtension library) for Linux, Windows, and Android
// Exports: Game executables for all platforms

def call(Map config) {
    // Required config:
    //   branch: 'main' or 'release'
    //   environment: 'development' or 'production'
    //   serverUrl: 'wss://dev.mechacorpsgames.com' or 'wss://play.mechacorpsgames.com'
    //   webhookToken: 'mcdclient-main' or 'mcdclient-release'
    //   jobName: 'MCDClient-Main' or 'MCDClient-Release'
    pipeline {
        agent {
            docker {
                image 'mcd-build-agent:latest'
                args '-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /var/lib/jenkins/.android:/var/lib/jenkins/.android:ro -v /var/lib/jenkins/.local/share/godot/export_templates:/home/jenkins/.local/share/godot/export_templates:ro -v /opt/mechacorps:/opt/mechacorps -v /var/opt/mechacorpsgames/Src:/var/opt/mechacorpsgames/Src --network host --group-add 111 --group-add 995 --group-add 1000'
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '10', artifactDaysToKeepStr: '7', artifactNumToKeepStr: '10'))
            // Serialize MCDClient-* builds so concurrent runs can't race on
            // the per-env bot-runtime deploy path (the Publish Bot Runtime
            // rsync --delete blew up on `.core.XYZ` temp files under the
            // MCDClient-FeatureBackend 21:01 burst). Lockable Resources
            // plugin isn't installed here (confirmed by #64's DSL error
            // listing valid steps — `lock` is absent), so we fall back to
            // pipeline-level serialization. Waiting 10–15 min in a bad
            // burst is better than a silent rsync race.
            disableConcurrentBuilds()
        }

        environment {
            DISCORD_WEBHOOK = credentials('discord-webhook-url')
            JENKINS_URL_BASE = "https://jenkins.mechacorpsgames.com"
            ANDROID_SDK_ROOT = "/opt/android-sdk"
            ANDROID_HOME = "/opt/android-sdk"
            ANDROID_NDK_HOME = "/opt/android-sdk/ndk/26.1.10909125"
            JAVA_HOME = "/usr/lib/jvm/java-17-openjdk-amd64"
            GODOT_ANDROID_KEYSTORE_DEBUG_PATH = "/var/lib/jenkins/.android/debug.keystore"
            GODOT_ANDROID_KEYSTORE_DEBUG_USER = "androiddebugkey"
            GODOT_ANDROID_KEYSTORE_DEBUG_PASSWORD = "android"
            // Release keystore is bound lazily via withCredentials around the
            // Android release build + export stages (see below). Doing it here
            // with credentials() in the environment block would abort every
            // client build before entering the node when the credentials are
            // missing — even Linux/Windows-only work would break.
            BRANCH_NAME = "${config.branch}"
            BRANCH_SAFE = "${config.branch.replaceAll('/', '-')}"
            DEPLOY_ENV = "${config.environment}"
            SERVER_URL = "${config.serverUrl}"
            BUILD_PHASE = "Initializing"
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
                    [key: 'before_sha', value: '$.before']
                ],
                causeString: "Triggered by push to ${config.branch}",
                token: config.webhookToken,
                tokenCredentialId: '',
                printContributedVariables: true,
                printPostContent: false,
                silentResponse: false,
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
                // before any Jenkinsfile ran. Measured on the two pushes that
                // broke: files_added was 171,999 B (MCDClient-FeatureBackend
                // #528) and 221,028 B (MCDClient-FeatureCard #95).
                //
                // Dropping the path filter cannot skip a build that used to
                // run: the trigger was ANDed with CLIENT_CHANGED, and that gate
                // is still here. It only lets a few more builds start and
                // immediately mark themselves NOT_BUILT. Every other pipeline
                // in this library (app services, services, discord bot) already
                // triggers on $ref alone for the same reason.
                regexpFilterText: '$ref',
                regexpFilterExpression: "refs/heads/${config.branch}"
            )
        }

        stages {
            stage('Setup Build Info') {
                steps {
                    script {
                        env.BUILD_PHASE = 'Setup Build Info'
                        env.CLIENT_VERSION = "0.1.${BUILD_NUMBER}"

                        def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
                        currentBuild.displayName = "#${BUILD_NUMBER} v${env.CLIENT_VERSION} (${shortSha})"

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

                        echo "Branch: ${config.branch}"
                        echo "Client Version: ${env.CLIENT_VERSION}"
                        echo "Environment: ${config.environment}"
                        echo "Server URL: ${config.serverUrl}"

                        env.BUILD_ENV = sh(script: 'uname -s -r', returnStdout: true).trim()
                        env.GCC_VERSION = sh(script: 'gcc --version | head -1', returnStdout: true).trim()
                        env.CMAKE_VERSION = sh(script: 'cmake --version | head -1', returnStdout: true).trim()

                        sh 'rm -rf artifacts exports'
                    }
                }
            }

            stage('Checkout') {
                steps {
                    script { env.BUILD_PHASE = 'Checkout' }
                    checkout scm
                }
            }

            stage('Detect Changes') {
                steps {
                    script {
                        env.BUILD_PHASE = 'Detect Changes'
                        def baseRef = env.before_sha
                        if (!baseRef || baseRef.startsWith('0000000')) {
                            echo "No valid before SHA — building everything"
                            env.CLIENT_CHANGED = 'true'
                        } else {
                            sh "git fetch origin ${baseRef} 2>/dev/null || true"
                            def changes = mcdChangeDetection.detect(baseRef)
                            env.CLIENT_CHANGED = changes.clientChanged.toString()
                        }

                        if (env.CLIENT_CHANGED != 'true') {
                            currentBuild.description += "\n⏭️ No client changes — skipped"
                            currentBuild.result = 'NOT_BUILT'
                        }
                    }
                }
            }

            stage('Setup Dependencies') {
                when { expression { env.CLIENT_CHANGED == 'true' } }
                steps {
                    script { env.BUILD_PHASE = 'Setup Dependencies' }
                    sh 'chmod +x scripts/setup-deps.sh && ./scripts/setup-deps.sh'
                }
            }

            // Linux Debug must be built first — GDScript tests depend on
            // GDExtension types (e.g. CreateCardIdTestHook, CardId).
            stage('MCDCoreExt Linux Debug') {
                when { expression { env.CLIENT_CHANGED == 'true' } }
                steps {
                    script { env.BUILD_PHASE = 'MCDCoreExt Linux Debug' }
                    sh """
                        cd Src/MCDCoreExt
                        chmod +x build.sh
                        ./build.sh --clean --configure --build --install --debug --server-url ${SERVER_URL} --build-number ${BUILD_NUMBER} --branch ${BRANCH_NAME}
                    """
                }
            }

            // The practice-match bot is a headless Godot instance the proxy
            // spawns from /app/project (see Src/docker-compose.proxy.yml).
            // We publish a coherent snapshot of the GDScript project plus the
            // freshly built Linux Debug MCDCoreExt to a per-env deploy path
            // so bot GDScript and the GDExtension .so never drift apart.
            //
            // Without this stage the proxy falls back to mounting the shared
            // dev checkout at /var/opt/mechacorpsgames, which only gets
            // rebuilt when a human runs build.sh by hand — any BuildInfo
            // method added in GDScript parses as "Identifier not declared"
            // until someone does that rebuild.
            //
            // Serialize writes to the per-env bot-runtime deploy path.
            // Without this lock, concurrent builds (e.g. a burst of merges to
            // features/backend) both rsync into the same
            // ${config.botProjectPath} with --delete, and the mid-transfer
            // temp files (e.g. `.core.XYZ`) vanish as the other build's
            // delete pass sweeps them — rsync exits 23 and every downstream
            // stage cascades to FAILED.
            //
            // MCDClient-FeatureBackend #57 was the poster child: five merges
            // landed in 15 minutes, #55 and #57 ran concurrently in
            // workspace@N dirs, both targeting /opt/mechacorps/feature-backend
            // /godot-bot-project, #57's rsync hit the race and failed.
            stage('Publish Bot Runtime') {
                when {
                    expression {
                        env.CLIENT_CHANGED == 'true' && config.botProjectPath
                    }
                }
                steps {
                    script { env.BUILD_PHASE = 'Publish Bot Runtime' }
                    sh """
                        mkdir -p ${config.botProjectPath}
                        # Re-import so .godot/extension_list.cfg reflects the
                        # just-built MCDCoreExt. Without this the headless bot
                        # fails parse with "Identifier BuildInfo not declared"
                        # because Godot does not auto-load extensions that are
                        # not registered in the cache.
                        godot --headless --import 2>/dev/null || true
                        # .godot/ IS included so the deploy path ships with a
                        # usable extension_list.cfg and imported asset cache.
                        # Source build dirs and extern/ are not needed at
                        # runtime (only the installed bin/ .so matters).
                        rsync -a --delete \
                            --exclude='.git/' \
                            --exclude='reports/' \
                            --exclude='Src/*/build*/' \
                            --exclude='Src/*/extern/' \
                            --exclude='Src/MCDCoreExt/build-win/' \
                            --exclude='Src/MCDCoreExt/build-windows/' \
                            --exclude='Src/MCDCoreExt/build-android/' \
                            ./ ${config.botProjectPath}/
                        echo "✓ Published bot runtime to ${config.botProjectPath} (\$(cd ${config.botProjectPath} && stat -c %y bin/lib/Linux-x86_64/libMCDCoreExt-d.so 2>/dev/null))"
                    """
                }
            }

            // Validated Card Data Pipeline (MCDClient bead mc-8ko, plan §4):
            // the MCDCoreExt Linux Debug build above exports Data/GameData/ via
            // build.py; validate that generated tree before the GDScript tests
            // (which load the runtime card library from it) run. Regenerate first
            // so the stage is self-contained. Gated on config.validateGameData so
            // it is inert unless a branch opts in (main/release unaffected while
            // the corpus is cleaned up — plan §7).
            stage('Validate GameData') {
                when {
                    expression { env.CLIENT_CHANGED == 'true' && config.validateGameData }
                }
                steps {
                    sh 'make export-done || echo "[Validate GameData] export-done target missing on this branch — skipping"'
                    mcdValidateGameData(hardFail: config.validateGameDataHardFail ?: false)
                }
            }

            // Hermetic build (MCDClient bead mc-0xm, plan §5, Scenario 3): with
            // the generated data exported and validated, relocate the AUTHORING
            // data (Data/Cards + Data/References) out of the workspace. The
            // GDScript tests below then prove the client reads generated
            // Data/GameData/ only, and 'Export Game Executables' physically
            // cannot pack authoring JSON into the shipped .pck. The MCDCoreExt
            // Release/Windows/Android builds below auto-run export_done_cards.py
            // after install; it no-ops loudly on the strip marker. Gated on
            // config.stripAuthoringData (features/card first). The build
            // finishes stripped; the next build's checkout restores the tree.
            stage('Strip Authoring Data') {
                when {
                    expression { env.CLIENT_CHANGED == 'true' && config.stripAuthoringData }
                }
                steps {
                    mcdStripAuthoringData()
                }
            }

            // After Linux Debug, run tests + remaining builds in parallel.
            // Each platform uses a separate build directory so there are no conflicts.
            stage('Cross-platform Builds & Tests') {
                when { expression { env.CLIENT_CHANGED == 'true' } }
                parallel {
                    stage('GDScript Tests') {
                        steps {
                            script { env.BUILD_PHASE = 'GDScript Tests' }
                            sh """
                                rm -rf reports/
                                echo "Importing Godot project resources..."
                                godot --headless --import 2>/dev/null || true
                                echo "Running GdUnit4 GDScript tests..."
                                godot --headless -s addons/gdUnit4/bin/GdUnitCmdTool.gd -a res://tests -c --ignoreHeadlessMode
                            """
                        }
                        post {
                            always {
                                script {
                                    try {
                                        // junit marks the build UNSTABLE by
                                        // itself when tests fail, and
                                        // post{ unstable } cannot tell that
                                        // apart from any other soft failure
                                        // after the fact — so the count is
                                        // recorded here, where it is known
                                        // (bead mjs-q4x). Only the int is kept:
                                        // a step result held in a CPS local has
                                        // to survive serialisation. `?: 0`
                                        // covers older junit plugins, which
                                        // return nothing from the step.
                                        int failed = junit(allowEmptyResults: true, skipPublishingChecks: true, testResults: 'reports/**/results.xml')?.failCount ?: 0
                                        if (failed) {
                                            mcdUnstableReason("GDScript test failures (${failed} failed)")
                                        }
                                    } catch (NoSuchMethodError e) {
                                        echo "JUnit plugin not installed — skipping test report publishing"
                                    }
                                }
                            }
                        }
                    }

                    stage('MCDCoreExt Linux Release') {
                        steps {
                            script { env.BUILD_PHASE = 'MCDCoreExt Linux Release' }
                            sh """
                                cd Src/MCDCoreExt
                                ./build.sh --clean --configure --build --install --release --server-url ${SERVER_URL} --build-number ${BUILD_NUMBER} --branch ${BRANCH_NAME}
                            """
                        }
                    }

                    stage('Build Windows (Cross-compile)') {
                        stages {
                            stage('Setup MinGW OpenSSL') {
                                steps {
                                    script { env.BUILD_PHASE = 'Setup MinGW OpenSSL' }
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
                                    script { env.BUILD_PHASE = 'MCDCoreExt Windows Debug' }
                                    sh """
                                        cd Src/MCDCoreExt
                                        ./build.sh --clean --configure --build --install --debug --windows --server-url ${SERVER_URL} --build-number ${BUILD_NUMBER} --branch ${BRANCH_NAME}
                                    """
                                }
                            }

                            stage('MCDCoreExt Windows Release') {
                                steps {
                                    script { env.BUILD_PHASE = 'MCDCoreExt Windows Release' }
                                    sh """
                                        cd Src/MCDCoreExt
                                        ./build.sh --clean --configure --build --install --release --windows --server-url ${SERVER_URL} --build-number ${BUILD_NUMBER} --branch ${BRANCH_NAME}
                                    """
                                }
                            }
                        }
                    }

                    stage('Build Android (Cross-compile)') {
                        stages {
                            stage('MCDCoreExt Android arm64-v8a Debug') {
                                steps {
                                    script { env.BUILD_PHASE = 'MCDCoreExt Android arm64-v8a Debug' }
                                    sh """
                                        cd Src/MCDCoreExt
                                        ./build.sh --clean --configure --build --install --debug --android arm64-v8a --server-url ${SERVER_URL} --build-number ${BUILD_NUMBER} --branch ${BRANCH_NAME}
                                    """
                                }
                            }

                            stage('MCDCoreExt Android arm64-v8a Release') {
                                steps {
                                    script { env.BUILD_PHASE = 'MCDCoreExt Android arm64-v8a Release' }
                                    sh """
                                        cd Src/MCDCoreExt
                                        ./build.sh --clean --configure --build --install --release --android arm64-v8a --server-url ${SERVER_URL} --build-number ${BUILD_NUMBER} --branch ${BRANCH_NAME}
                                    """
                                }
                            }

                            stage('MCDCoreExt Android armeabi-v7a Release') {
                                steps {
                                    script { env.BUILD_PHASE = 'MCDCoreExt Android armeabi-v7a Release' }
                                    sh """
                                        cd Src/MCDCoreExt
                                        ./build.sh --clean --configure --build --install --release --android armeabi-v7a --server-url ${SERVER_URL} --build-number ${BUILD_NUMBER} --branch ${BRANCH_NAME}
                                    """
                                }
                            }
                        }
                    }
                }
            }

            stage('Verify Builds') {
                when { expression { env.CLIENT_CHANGED == 'true' } }
                steps {
                    script {
                        env.BUILD_PHASE = 'Verify Builds'
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

                            test -f bin/lib/Windows-x86_64/libcrypto-3-x64.dll
                            test -f bin/lib/Windows-x86_64/libssl-3-x64.dll
                            echo "✓ Windows OpenSSL DLLs"

                            echo ""
                            echo "=== Android Builds ==="
                            test -f bin/lib/Android-arm64-v8a/libMCDCoreExt-d.so
                            echo "✓ Android arm64-v8a debug build"
                            test -f bin/lib/Android-arm64-v8a/libMCDCoreExt.so
                            echo "✓ Android arm64-v8a release build"
                            test -f bin/lib/Android-armeabi-v7a/libMCDCoreExt.so
                            echo "✓ Android armeabi-v7a release build"

                            echo ""
                            echo "All builds verified successfully!"
                        """

                        env.LINUX_RELEASE_SIZE = sh(script: "du -h bin/lib/Linux-x86_64/libMCDCoreExt.so | cut -f1", returnStdout: true).trim()
                        env.LINUX_DEBUG_SIZE = sh(script: "du -h bin/lib/Linux-x86_64/libMCDCoreExt-d.so | cut -f1", returnStdout: true).trim()
                        env.WIN_RELEASE_SIZE = sh(script: "du -h bin/lib/Windows-x86_64/MCDCoreExt.dll | cut -f1", returnStdout: true).trim()
                        env.WIN_DEBUG_SIZE = sh(script: "du -h bin/lib/Windows-x86_64/MCDCoreExt-d.dll | cut -f1", returnStdout: true).trim()
                        env.ANDROID_ARM64_SIZE = sh(script: "du -h bin/lib/Android-arm64-v8a/libMCDCoreExt.so | cut -f1", returnStdout: true).trim()

                        echo "Sizes - Linux: ${env.LINUX_RELEASE_SIZE}, Windows: ${env.WIN_RELEASE_SIZE}, Android arm64: ${env.ANDROID_ARM64_SIZE}"
                    }
                }
            }

            stage('Export Game Executables') {
                when { expression { env.CLIENT_CHANGED == 'true' } }
                steps {
                    script {
                        env.BUILD_PHASE = 'Export Game Executables'

                        // Detect whether the Play Store upload keystore is available.
                        // If not, skip the Android AAB export entirely — we still
                        // ship Linux + Windows builds, and the pipeline stays green
                        // so non-Android work isn't held hostage to Play credentials.
                        env.HAS_UPLOAD_KEYSTORE = 'false'
                        try {
                            withCredentials([
                                file(credentialsId: 'android-upload-keystore', variable: '_KS_PROBE')
                            ]) {
                                env.HAS_UPLOAD_KEYSTORE = 'true'
                            }
                        } catch (err) {
                            echo "⚠️  Upload keystore credentials not configured — skipping Android AAB export."
                            echo "    To enable: create Jenkins credentials android-upload-keystore (Secret file),"
                            echo "    android-upload-keystore-password (Secret text), android-upload-keystore-alias (Secret text)."
                            echo "    See docs/play-store/README.md in the client repo."
                        }
                    }

                    script {
                        // Globally-monotonic Android versionCode.
                        // Play requires versionCode to be unique AND strictly increasing per
                        // package across ALL tracks. BUILD_NUMBER is per-job, so MCDClient-Main
                        // and MCDClient-Release (independent counters) would eventually collide
                        // on Play and get rejected. Derive it from epoch-minutes (fits a 32-bit
                        // int for centuries) plus a small per-lane offset so two jobs building in
                        // the same minute can't tie. Lane: Release=0, Main=1, FeatureBackend=2,
                        // FeatureCard=3, other=4.
                        long epochMin = (sh(script: 'date +%s', returnStdout: true).trim().toLong()).intdiv(60)
                        int lane = env.JOB_NAME?.contains('Release') ? 0 :
                                   env.JOB_NAME?.contains('FeatureBackend') ? 2 :
                                   env.JOB_NAME?.contains('FeatureCard') ? 3 :
                                   env.JOB_NAME?.contains('Main') ? 1 : 4
                        env.ANDROID_VERSION_CODE = ((epochMin * 8L) + lane).toString()
                        echo "Android versionCode=${env.ANDROID_VERSION_CODE} (lane ${lane}), versionName=${env.CLIENT_VERSION}"
                    }

                    sh """
                        mkdir -p exports

                        # Inject the globally-monotonic version code (computed in the script
                        # block above) + the human-readable version name into the Android preset
                        # so Play won't reject duplicate uploads.
                        sed -i "s|^version/code=.*|version/code=${ANDROID_VERSION_CODE}|" export_presets.cfg
                        sed -i "s|^version/name=.*|version/name=\\"${CLIENT_VERSION}\\"|" export_presets.cfg
                        echo "Android versionCode=${ANDROID_VERSION_CODE}, versionName=${CLIENT_VERSION}"

                        echo "Exporting Windows build..."
                        godot --headless --export-release "Windows Desktop" exports/MechaCorpsDraft.exe 2>&1 || true
                        if [ ! -f exports/MechaCorpsDraft.exe ]; then
                            echo "Windows export failed, check export_presets.cfg"
                            exit 1
                        fi

                        echo "Exporting Linux build..."
                        godot --headless --export-release "Linux" exports/MechaCorpsDraft.x86_64 2>&1 || true
                        if [ ! -f exports/MechaCorpsDraft.x86_64 ]; then
                            echo "Linux export failed, check export_presets.cfg"
                            exit 1
                        fi
                    """

                    script {
                        if (env.HAS_UPLOAD_KEYSTORE == 'true') {
                            withCredentials([
                                file(credentialsId: 'android-upload-keystore', variable: 'GODOT_ANDROID_KEYSTORE_RELEASE_PATH'),
                                string(credentialsId: 'android-upload-keystore-alias', variable: 'GODOT_ANDROID_KEYSTORE_RELEASE_USER'),
                                string(credentialsId: 'android-upload-keystore-password', variable: 'GODOT_ANDROID_KEYSTORE_RELEASE_PASSWORD')
                            ]) {
                                sh '''
                                    echo "Exporting Android AAB (Play Store format)..."

                                    # Godot's gradle build (use_gradle_build=true) needs the Android
                                    # build template in res://android/build/. It is .gitignored, so
                                    # install it from the export templates matching this Godot build.
                                    GODOT_VER=$(godot --version 2>/dev/null | sed -E 's/\\.(official|custom_build|mono).*$//')
                                    TPL_DIR="$HOME/.local/share/godot/export_templates/$GODOT_VER"
                                    if [ ! -f android/build/build.gradle ]; then
                                        echo "Installing Android build template ($GODOT_VER)..."
                                        mkdir -p android/build
                                        unzip -o -q "$TPL_DIR/android_source.zip" -d android/build/
                                    fi
                                    touch android/.gdignore
                                    # Version marker Godot validates the template against.
                                    printf '%s' "$GODOT_VER" > android/.build_version
                                    printf '%s' "$GODOT_VER" > android/build/.build_version

                                    # Godot 4.6 export validation rejects an env-var-only release
                                    # keystore, so write it into the preset transiently, then restore
                                    # (drops the password back out of the workspace afterwards).
                                    cp export_presets.cfg /tmp/ep.play.bak
                                    python3 - <<'PY'
import os
path = os.environ["GODOT_ANDROID_KEYSTORE_RELEASE_PATH"]
user = os.environ["GODOT_ANDROID_KEYSTORE_RELEASE_USER"]
pw   = os.environ["GODOT_ANDROID_KEYSTORE_RELEASE_PASSWORD"]
f = "export_presets.cfg"
lines = open(f).read().splitlines()
out, ins = [], False
for l in lines:
    out.append(l)
    if l.strip() == "[preset.2.options]" and not ins:
        out += ['keystore/release="%s"' % path,
                'keystore/release_user="%s"' % user,
                'keystore/release_password="%s"' % pw]
        ins = True
open(f, "w").write("\\n".join(out) + "\\n")
print("keystore written into preset.2.options:", ins)
PY

                                    godot --headless --export-release "Android" exports/MechaCorpsDraft.aab 2>&1 || true

                                    cp /tmp/ep.play.bak export_presets.cfg

                                    if [ ! -f exports/MechaCorpsDraft.aab ]; then
                                        echo "Android export failed. Checklist:"
                                        echo "  - export_presets.cfg: gradle_build/use_gradle_build=true, export_format=1"
                                        echo "  - Android build template + .build_version present (auto-installed above)"
                                        echo "  - SDK 35 / build-tools 35.0.1 present in the agent image"
                                        echo "  - Upload keystore credential android-upload-keystore accessible"
                                        exit 1
                                    fi
                                '''
                            }
                        } else {
                            echo "Skipping Android AAB export (no upload keystore)."
                        }
                    }

                    sh """
                        echo ""
                        echo "Exported executables:"
                        ls -lh exports/
                    """

                    script {
                        env.WIN_EXE_SIZE = sh(script: "du -h exports/MechaCorpsDraft.exe | cut -f1", returnStdout: true).trim()
                        env.LINUX_EXE_SIZE = sh(script: "du -h exports/MechaCorpsDraft.x86_64 | cut -f1", returnStdout: true).trim()
                        if (fileExists('exports/MechaCorpsDraft.aab')) {
                            env.ANDROID_AAB_SIZE = sh(script: "du -h exports/MechaCorpsDraft.aab | cut -f1", returnStdout: true).trim()
                            echo "Executable sizes - Windows: ${env.WIN_EXE_SIZE}, Linux: ${env.LINUX_EXE_SIZE}, Android AAB: ${env.ANDROID_AAB_SIZE}"
                        } else {
                            env.ANDROID_AAB_SIZE = 'skipped'
                            echo "Executable sizes - Windows: ${env.WIN_EXE_SIZE}, Linux: ${env.LINUX_EXE_SIZE}, Android AAB: skipped"
                        }
                    }
                }
            }

            stage('Stage Artifacts') {
                when { expression { env.CLIENT_CHANGED == 'true' } }
                steps {
                    script { env.BUILD_PHASE = 'Stage Artifacts' }
                    retry(2) {
                    sh """
                        ARTIFACT_BASE="artifacts/${BRANCH_SAFE}/v${CLIENT_VERSION}"

                        mkdir -p \${ARTIFACT_BASE}/game/Windows
                        mkdir -p \${ARTIFACT_BASE}/game/Linux

                        # Godot's single-file export flattens every GDExtension
                        # library + dependency into exports/ regardless of the
                        # nested paths declared in the .gdextension files (those
                        # get baked into the pck). Runtime loads read the pck's
                        # .gdextension entries — e.g. res://bin/MCDCoreExt.gdextension
                        # → lib/Linux-x86_64/libMCDCoreExt.so — so each loose lib
                        # must be re-nested to match before zipping, otherwise
                        # Godot logs "GDExtension dynamic library not found" and
                        # every autoload that touches those classes fails to
                        # parse (brown-screen on Linux in Steam builds).

                        # --- Windows ---
                        cp exports/MechaCorpsDraft.exe \${ARTIFACT_BASE}/game/Windows/

                        mkdir -p \${ARTIFACT_BASE}/game/Windows/bin/lib/Windows-x86_64
                        mkdir -p \${ARTIFACT_BASE}/game/Windows/addons/godotsteam/win64
                        mkdir -p \${ARTIFACT_BASE}/game/Windows/addons/sentry/bin/windows/x86_64

                        # MCDCoreExt + its MinGW runtime deps (loaded by MCDCoreExt.dll,
                        # so they live next to it in Windows's DLL search order).
                        cp exports/MCDCoreExt.dll \${ARTIFACT_BASE}/game/Windows/bin/lib/Windows-x86_64/
                        cp bin/lib/Windows-x86_64/libcrypto-3-x64.dll \${ARTIFACT_BASE}/game/Windows/bin/lib/Windows-x86_64/
                        cp bin/lib/Windows-x86_64/libssl-3-x64.dll \${ARTIFACT_BASE}/game/Windows/bin/lib/Windows-x86_64/
                        cp bin/lib/Windows-x86_64/libwinpthread-1.dll \${ARTIFACT_BASE}/game/Windows/bin/lib/Windows-x86_64/

                        cp exports/libgodotsteam.windows.template_release.x86_64.dll \${ARTIFACT_BASE}/game/Windows/addons/godotsteam/win64/
                        cp exports/steam_api64.dll \${ARTIFACT_BASE}/game/Windows/addons/godotsteam/win64/

                        cp exports/libsentry.windows.release.x86_64.dll \${ARTIFACT_BASE}/game/Windows/addons/sentry/bin/windows/x86_64/
                        cp exports/crashpad_handler.exe \${ARTIFACT_BASE}/game/Windows/addons/sentry/bin/windows/x86_64/
                        cp exports/crashpad_wer.dll \${ARTIFACT_BASE}/game/Windows/addons/sentry/bin/windows/x86_64/

                        # --- Linux ---
                        cp exports/MechaCorpsDraft.x86_64 \${ARTIFACT_BASE}/game/Linux/

                        mkdir -p \${ARTIFACT_BASE}/game/Linux/bin/lib/Linux-x86_64
                        mkdir -p \${ARTIFACT_BASE}/game/Linux/addons/godotsteam/linux64
                        mkdir -p \${ARTIFACT_BASE}/game/Linux/addons/sentry/bin/linux/x86_64

                        cp exports/libMCDCoreExt.so \${ARTIFACT_BASE}/game/Linux/bin/lib/Linux-x86_64/

                        cp exports/libgodotsteam.linux.template_release.x86_64.so \${ARTIFACT_BASE}/game/Linux/addons/godotsteam/linux64/
                        cp exports/libsteam_api.so \${ARTIFACT_BASE}/game/Linux/addons/godotsteam/linux64/

                        cp exports/libsentry.linux.release.x86_64.so \${ARTIFACT_BASE}/game/Linux/addons/sentry/bin/linux/x86_64/
                        cp exports/crashpad_handler \${ARTIFACT_BASE}/game/Linux/addons/sentry/bin/linux/x86_64/
                        chmod +x \${ARTIFACT_BASE}/game/Linux/addons/sentry/bin/linux/x86_64/crashpad_handler

                        # Sanity check: fail loud if Godot didn't emit an expected lib
                        # instead of shipping a half-populated zip.
                        for f in \\
                            \${ARTIFACT_BASE}/game/Windows/bin/lib/Windows-x86_64/MCDCoreExt.dll \\
                            \${ARTIFACT_BASE}/game/Windows/addons/godotsteam/win64/libgodotsteam.windows.template_release.x86_64.dll \\
                            \${ARTIFACT_BASE}/game/Windows/addons/godotsteam/win64/steam_api64.dll \\
                            \${ARTIFACT_BASE}/game/Windows/addons/sentry/bin/windows/x86_64/libsentry.windows.release.x86_64.dll \\
                            \${ARTIFACT_BASE}/game/Windows/addons/sentry/bin/windows/x86_64/crashpad_handler.exe \\
                            \${ARTIFACT_BASE}/game/Linux/bin/lib/Linux-x86_64/libMCDCoreExt.so \\
                            \${ARTIFACT_BASE}/game/Linux/addons/godotsteam/linux64/libgodotsteam.linux.template_release.x86_64.so \\
                            \${ARTIFACT_BASE}/game/Linux/addons/godotsteam/linux64/libsteam_api.so \\
                            \${ARTIFACT_BASE}/game/Linux/addons/sentry/bin/linux/x86_64/libsentry.linux.release.x86_64.so \\
                            \${ARTIFACT_BASE}/game/Linux/addons/sentry/bin/linux/x86_64/crashpad_handler; do
                            if [ ! -f "\$f" ]; then
                                echo "ERROR: expected GDExtension artifact missing: \$f"
                                exit 1
                            fi
                        done

                        cd \${ARTIFACT_BASE}/game/Windows && zip -r ../../MechaCorpsDraft-${BRANCH_SAFE}-Windows-v${CLIENT_VERSION}.zip . && cd -
                        cd \${ARTIFACT_BASE}/game/Linux && zip -r ../../MechaCorpsDraft-${BRANCH_SAFE}-Linux-v${CLIENT_VERSION}.zip . && cd -
                        if [ -f exports/MechaCorpsDraft.aab ]; then
                            cp exports/MechaCorpsDraft.aab \${ARTIFACT_BASE}/MechaCorpsDraft-${BRANCH_SAFE}-Android-v${CLIENT_VERSION}.aab
                        else
                            echo "⚠️  Android AAB was skipped — not staging Android artifact."
                        fi

                        rm -rf \${ARTIFACT_BASE}/game

                        # Stage debug symbols as a separate archive
                        mkdir -p \${ARTIFACT_BASE}/symbols/Windows-x86_64
                        mkdir -p \${ARTIFACT_BASE}/symbols/Linux-x86_64
                        cp bin/lib/Windows-x86_64/MCDCoreExt.dll.sym \${ARTIFACT_BASE}/symbols/Windows-x86_64/ 2>/dev/null || true
                        cp bin/lib/Windows-x86_64/MCDCoreExt-d.dll.sym \${ARTIFACT_BASE}/symbols/Windows-x86_64/ 2>/dev/null || true
                        cp bin/lib/Linux-x86_64/libMCDCoreExt-d.so \${ARTIFACT_BASE}/symbols/Linux-x86_64/ 2>/dev/null || true
                        SYMBOL_COUNT=\$(find \${ARTIFACT_BASE}/symbols -type f | wc -l)
                        if [ "\$SYMBOL_COUNT" -gt 0 ]; then
                            cd \${ARTIFACT_BASE}/symbols && zip -r ../MechaCorpsDraft-${BRANCH_SAFE}-Symbols-v${CLIENT_VERSION}.zip . && cd -
                        else
                            echo "⚠️ No symbol files found to archive"
                        fi
                        rm -rf \${ARTIFACT_BASE}/symbols

                        echo "${CLIENT_VERSION}" > \${ARTIFACT_BASE}/latest.txt

                        COMMIT_SHA_VAL="\${commit_sha:-manual}"
                        COMMIT_AUTHOR_VAL="\${commit_author:-Unknown}"
                        cat > \${ARTIFACT_BASE}/BUILD_INFO.txt << EOF
Client Version: ${CLIENT_VERSION}
Build Number: ${BUILD_NUMBER}
Branch: ${BRANCH_NAME}
Environment: ${DEPLOY_ENV}
Server URL: ${SERVER_URL}
Date: \$(date -Iseconds)
Commit: \$COMMIT_SHA_VAL
Author: \$COMMIT_AUTHOR_VAL
Build Environment: ${BUILD_ENV}
GCC Version: ${GCC_VERSION}
CMake Version: ${CMAKE_VERSION}
Platforms: Linux-x86_64, Windows-x86_64, Android-arm64-v8a, Android-armeabi-v7a

Library Sizes:
  Linux Release: ${LINUX_RELEASE_SIZE}
  Linux Debug: ${LINUX_DEBUG_SIZE}
  Windows Release: ${WIN_RELEASE_SIZE}
  Windows Debug: ${WIN_DEBUG_SIZE}
  Android arm64: ${ANDROID_ARM64_SIZE}

Game Executables:
  Windows: ${WIN_EXE_SIZE}
  Linux: ${LINUX_EXE_SIZE}
  Android AAB: ${ANDROID_AAB_SIZE}
EOF

                        echo ""
                        echo "Artifacts (${BRANCH_NAME}/v${CLIENT_VERSION}):"
                        find artifacts -type f | sort
                    """
                    } // retry
                }
            }

            stage('Generate Compatibility Manifest') {
                when { expression { env.CLIENT_CHANGED == 'true' } }
                steps {
                    script {
                        env.BUILD_PHASE = 'Generate Compatibility Manifest'
                        def protocolVersion = sh(
                            script: "grep -oP 'PROTOCOL_VERSION\\s*=\\s*\\K[0-9]+' Src/Include/protocol_ext.h || echo '1'",
                            returnStdout: true
                        ).trim()

                        env.PROTOCOL_VERSION = protocolVersion

                        def androidEntry = (env.ANDROID_AAB_SIZE && env.ANDROID_AAB_SIZE != 'skipped') ? """,
        "android": {
            "download": "MechaCorpsDraft-${BRANCH_SAFE}-Android-v${CLIENT_VERSION}.aab",
            "package": "com.mechacorpsgames.mechacorpsdraft",
            "format": "aab",
            "versionCode": ${ANDROID_VERSION_CODE}
        }""" : ""

                        sh """
                            ARTIFACT_BASE="artifacts/${BRANCH_SAFE}/v${CLIENT_VERSION}"
                            cat > \${ARTIFACT_BASE}/manifest.json << EOF
{
    "clientVersion": "${CLIENT_VERSION}",
    "protocolVersion": ${PROTOCOL_VERSION},
    "buildNumber": ${BUILD_NUMBER},
    "branch": "${BRANCH_NAME}",
    "environment": "${DEPLOY_ENV}",
    "serverUrl": "${SERVER_URL}",
    "buildDate": "\$(date -Iseconds)",
    "commit": "\${commit_sha:-manual}",
    "platforms": {
        "windows": {
            "download": "MechaCorpsDraft-${BRANCH_SAFE}-Windows-v${CLIENT_VERSION}.zip",
            "executable": "MechaCorpsDraft.exe"
        },
        "linux": {
            "download": "MechaCorpsDraft-${BRANCH_SAFE}-Linux-v${CLIENT_VERSION}.zip",
            "executable": "MechaCorpsDraft.x86_64"
        }${androidEntry}
    }
}
EOF
                            echo "Generated manifest.json:"
                            cat \${ARTIFACT_BASE}/manifest.json
                        """
                    }
                }
            }

            stage('Archive Artifacts') {
                when { expression { env.CLIENT_CHANGED == 'true' } }
                steps {
                    script { env.BUILD_PHASE = 'Archive Artifacts' }
                    archiveArtifacts artifacts: 'artifacts/**/*', fingerprint: true
                }
            }

            // Without symbols every native crash in Sentry is 31 frames of
            // <unknown>, so a failure here is reported, never swallowed. The
            // stage marks the build UNSTABLE (the artifacts are still good) and
            // the post block turns that into a Discord message.
            stage('Upload Debug Symbols') {
                when { expression { env.CLIENT_CHANGED == 'true' } }
                steps {
                    script {
                        env.BUILD_PHASE = 'Upload Debug Symbols'
                        def sentryCliExists = sh(script: 'which sentry-cli', returnStatus: true) == 0
                        if (!sentryCliExists) {
                            env.SYMBOLS_UPLOADED = 'false'
                            echo "⚠️ sentry-cli is not installed on the build agent, so client debug symbols were NOT uploaded. Native crashes from this build cannot be symbolicated."
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
                            SYMBOL_PATHS="bin/lib/"
                            for d in Src/MCDCoreExt/build/Release Src/MCDCoreExt/build-windows Src/MCDCoreExt/build-android; do
                                if [ -d "\$d" ]; then
                                    SYMBOL_PATHS="\$SYMBOL_PATHS \$(find "\$d" -type f \\( -name '*.so' -o -name '*.dll' -o -name '*.pdb' \\) | tr '\\n' ' ')"
                                fi
                            done

                            echo "Uploading client debug symbols for all platforms..."
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
                                    --org mechacorps-llc --project mcd-client \
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
                                echo "Verifying the shipped library is registered in Sentry..."
                                # The verifier, not sentry-cli's exit code, is the loop's condition. It is
                                # the only thing here that asks whether THIS binary can symbolicate.
                                set +e
                                python3 scripts/verify_sentry_symbols.py \
                                    --org mechacorps-llc --project mcd-client \
                                    --url https://us.sentry.io \
                                    bin/lib/Linux-x86_64/libMCDCoreExt.so
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
                            echo "⚠️ Client debug symbols are NOT in Sentry for this build. Native crashes will symbolicate to <unknown> frames until this is fixed."
                            mcdUnstableReason('debug symbols missing from Sentry (native crashes will not symbolicate)')
                            currentBuild.result = 'UNSTABLE'
                        } else {
                            echo "✅ Debug symbols uploaded and verified against Sentry."
                        }
                    }
                }
            }

            // Auto-publish to Steam. Gated on config.steamBranch so this is a
            // complete no-op on any pipeline that doesn't opt in — the branch
            // Jenkinsfiles arm it (features/backend→backend, features/card→card,
            // main→main, release→staging). We hand off to the standalone
            // MCDSteam-Upload job (which runs in a container with steamcmd + the
            // Steam content cache mounted and reads THIS build's just-archived
            // artifacts by job+build number). Fire-and-forget: wait:false so the
            // client build doesn't block on the upload, propagate:false so a
            // Steam hiccup never reds an otherwise-good build — MCDSteam-Upload
            // has its own Discord success/failure notifications. The upload job
            // routes 'default' (public) to upload-only; all other branches are
            // set live on their beta automatically.
            //
            // THIS STAGE MUST STAY LAST (bead mc-fr2h). It used to sit ahead of
            // 'Upload Debug Symbols', so a client build handed the controller a
            // second job to run while it still had minutes of its own work
            // left. With four executors and no agents that upload competes for
            // an executor with the PR validation somebody is waiting on. Firing
            // it last costs nothing (wait:false means the client build does not
            // block on it either way) and stops the two from overlapping.
            //
            // Keeping it last has a second effect worth knowing about: a build
            // that dies before this stage now publishes nothing, where before it
            // could publish and then fail. An UNSTABLE build still publishes,
            // because declarative only skips later stages on FAILURE, and
            // 'Upload Debug Symbols' reports a bad upload by marking the build
            // UNSTABLE rather than failing it.
            //
            // test/unit/test_steam_upload_coalescing.py fails if it moves.
            stage('Publish to Steam') {
                when {
                    expression { env.CLIENT_CHANGED == 'true' && config.steamBranch }
                }
                steps {
                    script {
                        env.BUILD_PHASE = 'Publish to Steam'
                        build job: 'MCDSteam-Upload',
                            parameters: [
                                string(name: 'SOURCE_JOB', value: config.jobName),
                                string(name: 'SOURCE_BUILD', value: env.BUILD_NUMBER),
                                string(name: 'STEAM_BRANCH', value: config.steamBranch)
                            ],
                            wait: false,
                            propagate: false
                        echo "Triggered MCDSteam-Upload: ${config.jobName} #${env.BUILD_NUMBER} -> Steam branch '${config.steamBranch}'"
                    }
                }
            }

        }

        post {
            success {
                script {
                    if (env.CLIENT_CHANGED != 'true') {
                        echo "No client changes detected — skipped build"
                        return
                    }
                    def linuxSize = sh(script: "du -h bin/lib/Linux-x86_64/libMCDCoreExt.so 2>/dev/null | cut -f1 || echo 'N/A'", returnStdout: true).trim()
                    discordNotify.success(
                        title: "MechaCorps Client Build",
                        message: "✅ MCDCoreExt build succeeded",
                        jenkinsUrl: env.JENKINS_URL_BASE,
                        jobName: config.jobName,
                        environment: config.environment,
                        branch: config.branch,
                        version: env.CLIENT_VERSION,
                        serverUrl: config.serverUrl,
                        libSize: linuxSize
                    )
                }
            }
            // Declarative `post` runs `success` only on SUCCESS, so an UNSTABLE
            // build would otherwise notify nobody. Debug symbol upload marks the
            // build unstable, and a silent warning is what let months of failed
            // uploads go unnoticed.
            //
            // The message names the CAUSE, taken from env.UNSTABLE_REASON, which
            // mcdUnstableReason records at whichever site actually went soft. It
            // deliberately does not read env.BUILD_PHASE: that tracks where the
            // build got TO, not what went wrong, and since BUILD_PHASE is
            // declared in the environment{} block above, declarative re-applies
            // that literal over the per-stage assignments so post{} only ever
            // sees "Initializing". Together those produced the message this
            // replaces — "Build finished UNSTABLE at: Initializing", which named
            // a phase where nothing had happened yet (bead mjs-q4x).
            unstable {
                script {
                    if (env.CLIENT_CHANGED != 'true') {
                        return
                    }
                    // Every cause recorded during this build, "; "-joined, so a
                    // run that trips both the card gate and the symbol upload
                    // reports both. The old if/else overwrote one with the other.
                    def reason = env.UNSTABLE_REASON?.trim()
                    def detail = reason
                        ? "⚠️ Build finished with ${reason}"
                        : "⚠️ Build finished with warnings — see the console log"
                    discordNotify.unstable(
                        title: "MechaCorps Client Build",
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
                    def failedPhase = env.BUILD_PHASE ?: 'Unknown'
                    discordNotify.failure(
                        title: "MechaCorps Client Build",
                        message: "❌ Build failed at: ${failedPhase}",
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
