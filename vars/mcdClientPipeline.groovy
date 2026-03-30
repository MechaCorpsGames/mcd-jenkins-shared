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
                args '-v /var/run/docker.sock:/var/run/docker.sock -v /var/lib/jenkins/.ssh:/var/lib/jenkins/.ssh:ro -v /var/lib/jenkins/.ssh:/home/jenkins/.ssh:ro -v /var/lib/jenkins/.android:/var/lib/jenkins/.android:ro -v /var/lib/jenkins/.local/share/godot/export_templates:/home/jenkins/.local/share/godot/export_templates:ro -v /opt/mechacorps:/opt/mechacorps -v /var/opt/mechacorpsgames/Src:/var/opt/mechacorpsgames/Src -v /nix:/nix:rw --network host --group-add 111 --group-add 995'
            }
        }

        options {
            buildDiscarder(logRotator(numToKeepStr: '10', artifactDaysToKeepStr: '7', artifactNumToKeepStr: '10'))
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
            GODOT_ANDROID_KEYSTORE_RELEASE_PATH = "/var/lib/jenkins/.android/debug.keystore"
            GODOT_ANDROID_KEYSTORE_RELEASE_USER = "androiddebugkey"
            GODOT_ANDROID_KEYSTORE_RELEASE_PASSWORD = "android"
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
                                        junit allowEmptyResults: true, skipPublishingChecks: true, testResults: 'reports/**/results.xml'
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
                    script { env.BUILD_PHASE = 'Export Game Executables' }
                    sh """
                        mkdir -p exports

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

                        echo "Exporting Android build..."
                        godot --headless --export-release "Android" exports/MechaCorpsDraft.apk 2>&1 || true
                        if [ ! -f exports/MechaCorpsDraft.apk ]; then
                            echo "Android export failed, check export_presets.cfg and Android SDK setup"
                            exit 1
                        fi

                        echo ""
                        echo "Exported executables:"
                        ls -lh exports/
                    """

                    script {
                        env.WIN_EXE_SIZE = sh(script: "du -h exports/MechaCorpsDraft.exe | cut -f1", returnStdout: true).trim()
                        env.LINUX_EXE_SIZE = sh(script: "du -h exports/MechaCorpsDraft.x86_64 | cut -f1", returnStdout: true).trim()
                        env.ANDROID_APK_SIZE = sh(script: "du -h exports/MechaCorpsDraft.apk | cut -f1", returnStdout: true).trim()
                        echo "Executable sizes - Windows: ${env.WIN_EXE_SIZE}, Linux: ${env.LINUX_EXE_SIZE}, Android: ${env.ANDROID_APK_SIZE}"
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

                        cp exports/MechaCorpsDraft.exe \${ARTIFACT_BASE}/game/Windows/
                        cp exports/MechaCorpsDraft.x86_64 \${ARTIFACT_BASE}/game/Linux/

                        # CMake PREFIX "" strips the lib prefix for Windows builds.
                        # Do not fall back to libMCDCoreExt.dll — a stale build with
                        # the wrong name would produce a broken artifact.
                        cp bin/lib/Windows-x86_64/MCDCoreExt.dll \${ARTIFACT_BASE}/game/Windows/
                        cp bin/lib/Windows-x86_64/libcrypto-3-x64.dll \${ARTIFACT_BASE}/game/Windows/
                        cp bin/lib/Windows-x86_64/libssl-3-x64.dll \${ARTIFACT_BASE}/game/Windows/
                        cp bin/lib/Windows-x86_64/libwinpthread-1.dll \${ARTIFACT_BASE}/game/Windows/

                        cat > \${ARTIFACT_BASE}/game/Windows/MCDCoreExt.gdextension << 'GDEXT'
[configuration]
entry_symbol = "example_library_init"
compatibility_minimum = "4.3"

[libraries]
windows.release.x86_64 = "MCDCoreExt.dll"
GDEXT

                        mkdir -p \${ARTIFACT_BASE}/game/Linux/lib/Linux-x86_64
                        cp bin/lib/Linux-x86_64/libMCDCoreExt.so \${ARTIFACT_BASE}/game/Linux/lib/Linux-x86_64/

                        cat > \${ARTIFACT_BASE}/game/Linux/MCDCoreExt.gdextension << 'GDEXT'
[configuration]
entry_symbol = "example_library_init"
compatibility_minimum = "4.3"

[libraries]
linux.release.x86_64 = "lib/Linux-x86_64/libMCDCoreExt.so"
GDEXT

                        cd \${ARTIFACT_BASE}/game/Windows && zip -r ../../MechaCorpsDraft-${BRANCH_SAFE}-Windows-v${CLIENT_VERSION}.zip . && cd -
                        cd \${ARTIFACT_BASE}/game/Linux && zip -r ../../MechaCorpsDraft-${BRANCH_SAFE}-Linux-v${CLIENT_VERSION}.zip . && cd -
                        cp exports/MechaCorpsDraft.apk \${ARTIFACT_BASE}/MechaCorpsDraft-${BRANCH_SAFE}-Android-v${CLIENT_VERSION}.apk

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
Platforms: Linux-x86_64, Windows-x86_64, Android-arm64-v8a

Library Sizes:
  Linux Release: ${LINUX_RELEASE_SIZE}
  Linux Debug: ${LINUX_DEBUG_SIZE}
  Windows Release: ${WIN_RELEASE_SIZE}
  Windows Debug: ${WIN_DEBUG_SIZE}
  Android arm64: ${ANDROID_ARM64_SIZE}

Game Executables:
  Windows: ${WIN_EXE_SIZE}
  Linux: ${LINUX_EXE_SIZE}
  Android APK: ${ANDROID_APK_SIZE}
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
        },
        "android": {
            "download": "MechaCorpsDraft-${BRANCH_SAFE}-Android-v${CLIENT_VERSION}.apk",
            "package": "com.mechacorpsgames.mechacorpsdraft"
        }
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

            stage('Upload Debug Symbols') {
                when { expression { env.CLIENT_CHANGED == 'true' } }
                steps {
                    script {
                        env.BUILD_PHASE = 'Upload Debug Symbols'
                        def sentryCliExists = sh(script: 'which sentry-cli', returnStatus: true) == 0
                        if (sentryCliExists) {
                            sh """
                                export SENTRY_AUTH_TOKEN=\$(grep SENTRY_TOKEN /var/opt/mechacorpsgames/Src/.env.sentry | cut -d= -f2)
                                echo "Uploading debug symbols for all platforms..."
                                sentry-cli --url https://us.sentry.io \
                                    upload-dif --org mechacorps-llc --project mcd-client \
                                    bin/lib/ \
                                    Src/MCDCoreExt/build/ \
                                    Src/MCDCoreExt/build-windows/ \
                                    Src/MCDCoreExt/build-android/ \
                                    || echo "⚠️ Symbol upload failed (non-fatal)"
                            """
                        } else {
                            echo "sentry-cli not installed, skipping symbol upload"
                        }
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
