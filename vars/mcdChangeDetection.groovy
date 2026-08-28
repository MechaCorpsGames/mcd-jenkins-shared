// MechaCorps Change Detection - Shared Library
// Detects which components changed between two git refs to enable
// selective builds and deploys (server, client, auth, wiki, monitoring, etc.).

/**
 * Detect which components have changed files.
 *
 * @param baseRef  Git ref to diff against (e.g., 'origin/main', a commit SHA)
 * @return Map with: serverChanged, clientChanged, authChanged, wikiChanged,
 *         monitoringChanged, crashReportingChanged,
 *         accountServiceChanged, auctionHouseChanged, discordBotChanged,
 *         dockerSmokeChanged, mcpGameServerChanged, determinismHarnessChanged,
 *         proxyChanged, sharedChanged, mcpServerChanged, tutorialChanged,
 *         changedFiles (list)
 *
 * proxyChanged / sharedChanged / mcpServerChanged are computed via direct
 * filePath prefix scans (not via categorize()) so the per-module Go test
 * stage in mcdPRValidationPipeline can gate each Go module independently.
 * They sit alongside the existing category-driven flags rather than
 * replacing them — Src/Proxy/ still routes to 'server' for the server
 * build pipeline; Src/MCPServer/ still routes to 'crash-reporting' for
 * the bundled deploy in mcdServicesPipeline.
 *
 * tutorialChanged uses that same direct-scan approach for the tutorial
 * validation harness (MCDClient ADR 0075). Its inputs deliberately span
 * categories — the stacked deck is 'server', the scripted seat JSONs under
 * bots/ hit the unknown-file fallback, and the engine-truth checkpoint
 * table under tests/e2e/ is 'client' — so no single category expresses
 * "the tutorial harness cares about this file".
 */
def detect(String baseRef) {
    def changedFilesRaw = sh(
        script: "git diff --name-only ${baseRef} HEAD 2>/dev/null || echo '__DIFF_FAILED__'",
        returnStdout: true
    ).trim()

    if (changedFilesRaw.contains('__DIFF_FAILED__') || changedFilesRaw.isEmpty()) {
        echo "Warning: Change detection failed or no changes found - building everything"
        return [serverChanged: true, clientChanged: true, authChanged: true,
                wikiChanged: true, monitoringChanged: true,
                crashReportingChanged: true, accountServiceChanged: true,
                auctionHouseChanged: true, discordBotChanged: true,
                dockerSmokeChanged: true,
                mcpGameServerChanged: true, determinismHarnessChanged: true,
                proxyChanged: true, sharedChanged: true, mcpServerChanged: true,
                tutorialChanged: true,
                changedFiles: []]
    }

    def changedFiles = changedFilesRaw.split('\n').collect { it.trim() }.findAll { it }
    echo "=== Changed files (${changedFiles.size()}) ==="
    changedFiles.each { echo "  ${it}" }

    def serverChanged = false
    def clientChanged = false
    def authChanged = false
    def wikiChanged = false
    def monitoringChanged = false
    def crashReportingChanged = false
    def accountServiceChanged = false
    def auctionHouseChanged = false
    def discordBotChanged = false
    def dockerSmokeChanged = false
    def mcpGameServerChanged = false
    def determinismHarnessChanged = false
    // Per-Go-module flags (independent of categorize(); see method doc)
    def proxyChanged = false
    def sharedChanged = false
    def mcpServerChanged = false
    // Tutorial validation harness inputs (independent of categorize(); see method doc)
    def tutorialChanged = false
    def unmatchedFiles = []

    for (file in changedFiles) {
        // Per-Go-module signal: scan file paths directly so that the
        // per-module Go test stage can run only the modules whose source
        // tree actually changed. The Src/Shared/ propagation below mirrors
        // the 'services-shared' switch case (Shared is consumed by every
        // Go module).
        if (file.startsWith('Src/Proxy/')) proxyChanged = true
        if (file.startsWith('Src/Shared/')) sharedChanged = true
        if (file.startsWith('Src/MCPServer/')) mcpServerChanged = true
        if (file.startsWith('tests/determinism-harness/') || file.startsWith('Src/TestClient/Test/replay/')) {
            determinismHarnessChanged = true
        }

        // Tutorial validation harness signal: the artifacts that define the
        // scripted game (stacked deck + the scripted seat action lists) and
        // the engine-truth checkpoint table the pytest asserts against.
        // The engine itself is covered by serverChanged, which gates the
        // same stage — see mcdPRValidationPipeline's 'Tutorial Validation'.
        if (file.startsWith('Src/GameServer/StackedDecks/') ||
            file.startsWith('bots/') ||
            file == 'tests/e2e/test_tutorial_validation.py') tutorialChanged = true

        def category = categorize(file)
        switch (category) {
            case 'server':
                serverChanged = true
                break
            case 'client':
                clientChanged = true
                break
            case 'shared':
                // Src/Include/ + Src/External/ + Data/ touch the wire format
                // (protocol_ext.h drift gate) so the MCP Game Server, which
                // hand-ports the protocol, must rebuild + re-test on these.
                serverChanged = true
                clientChanged = true
                mcpGameServerChanged = true
                break
            case 'services-shared':
                // Src/Shared/ affects Proxy (server) and all Go services
                serverChanged = true
                authChanged = true
                crashReportingChanged = true
                accountServiceChanged = true
                auctionHouseChanged = true
                break
            case 'crash-reporting':
                crashReportingChanged = true
                break
            case 'auth':
                authChanged = true
                break
            case 'account-service':
                accountServiceChanged = true
                break
            case 'auction-house':
                auctionHouseChanged = true
                break
            case 'discord-bot':
                discordBotChanged = true
                break
            case 'docker-smoke':
                // Orchestrator + compose stack + tests/e2e smoke fixtures —
                // mcdAppServicesPipeline's "Docker Smoke" stage gates on this.
                //
                // The break below is load-bearing: without it Groovy falls
                // through into 'mcp-game-server' and every compose or
                // orchestrator file also sets mcpGameServerChanged, running the
                // MCP Game Server Go suite on PRs that touch no Go at all
                // (mc-lvzi).
                dockerSmokeChanged = true
                break
            case 'mcp-game-server':
                mcpGameServerChanged = true
                break
            case 'wiki':
                wikiChanged = true
                break
            case 'monitoring':
                monitoringChanged = true
                break
            case 'build-system':
                // The root Makefile drives both halves of the build: 'server',
                // 'proxy' and 'testclient' on one side, 'ext', 'test-gdscript'
                // and 'export-done' on the other, and the pipelines invoke those
                // targets directly. A path cannot tell us which half changed, so
                // this fans out to both. That is the same outcome the
                // unmatched-file fallback produced, but as a decision rather
                // than an accident (mc-lvzi).
                serverChanged = true
                clientChanged = true
                break
            case 'docs':
                break
            default:
                unmatchedFiles.add(file)
                break
        }
    }

    if (unmatchedFiles) {
        echo "Warning: Unmatched files (triggering both builds):"
        unmatchedFiles.each { echo "  ${it}" }
        serverChanged = true
        clientChanged = true
    }

    // Src/Shared/ is the Go shared library — every Go module imports from it,
    // so a Shared change must trigger every per-module test. The category
    // path 'services-shared' already wires Auth / AccountService / AuctionHouse
    // / CrashReporting (and serverChanged, which gates the GameServer build);
    // the per-module flags below cover Proxy and MCPServer to complete the set.
    if (sharedChanged) {
        proxyChanged = true
        mcpServerChanged = true
    }

    echo "=== Change detection: server=${serverChanged}, client=${clientChanged}, auth=${authChanged}, wiki=${wikiChanged}, monitoring=${monitoringChanged}, crashReporting=${crashReportingChanged}, accountService=${accountServiceChanged}, auctionHouse=${auctionHouseChanged}, discordBot=${discordBotChanged}, dockerSmoke=${dockerSmokeChanged}, mcpGameServer=${mcpGameServerChanged}, determinismHarness=${determinismHarnessChanged}, proxy=${proxyChanged}, shared=${sharedChanged}, mcpServer=${mcpServerChanged}, tutorial=${tutorialChanged} ==="
    return [serverChanged: serverChanged, clientChanged: clientChanged,
            authChanged: authChanged, wikiChanged: wikiChanged,
            monitoringChanged: monitoringChanged,
            crashReportingChanged: crashReportingChanged,
            accountServiceChanged: accountServiceChanged,
            auctionHouseChanged: auctionHouseChanged,
            discordBotChanged: discordBotChanged,
            dockerSmokeChanged: dockerSmokeChanged,
            mcpGameServerChanged: mcpGameServerChanged,
            determinismHarnessChanged: determinismHarnessChanged,
            proxyChanged: proxyChanged,
            sharedChanged: sharedChanged,
            mcpServerChanged: mcpServerChanged,
            tutorialChanged: tutorialChanged,
            changedFiles: changedFiles]
}

/**
 * Categorize a file path into a component.
 * @return 'server', 'client', 'shared', 'services-shared', 'auth',
 *         'account-service', 'auction-house', 'crash-reporting',
 *         'docker-smoke', 'discord-bot', 'mcp-game-server',
 *         'build-system',
 *         'wiki', 'monitoring', 'docs', or 'unknown'
 */
def categorize(String filePath) {
    // docker-smoke orchestrator + compose stack + the smoke pytest suite.
    // Matched BEFORE 'client' so paths like scripts/docker_dev.py and
    // tests/e2e/test_docker_dev_smoke.py route here, not to the broad
    // 'client' bucket — mcdAppServicesPipeline gates the docker-smoke
    // stage on this flag.
    def dockerSmokeExact = [
        'scripts/docker_dev.py',
        'tests/e2e/test_docker_dev_smoke.py',
        'tests/e2e/conftest.py',
        'tests/e2e/helpers.py',
        'tests/e2e/test_assertions.py',
        'tests/e2e/run_e2e.sh',
        'tests/e2e/__init__.py',
    ]
    if (filePath in dockerSmokeExact) return 'docker-smoke'
    // docker/ holds the compose stack (compose.yml, postgres-init.sql,
    // images/*) — but NOT docker/build-agent/ which is mcd-jenkins-shared
    // infrastructure that lives in this repo, not MCDClient.
    if (filePath.startsWith('docker/') && !filePath.startsWith('docker/build-agent/')) return 'docker-smoke'

    // Shared paths (trigger both server and client builds)
    def sharedPrefixes = ['Src/Include/', 'Src/External/', 'Data/']
    for (prefix in sharedPrefixes) {
        if (filePath.startsWith(prefix)) return 'shared'
    }

    // Go shared library (affects Proxy, Auth, CrashReporting, MCPServer)
    if (filePath.startsWith('Src/Shared/')) return 'services-shared'

    // Server-only paths. Src/BotArena/ is here because the WASM arena bots
    // are built ('make wasm-bots') and deployed by mcdServerPipeline's
    // 'Build WASM Bots' / 'Deploy Bots' stages — before this entry,
    // arena-only changes fell to the unknown-files fallback, which ALSO set
    // clientChanged and burned a full client build + publish on a Go bot edit.
    def serverPrefixes = ['Src/GameServer/', 'Src/Proxy/', 'Src/TestClient/', 'Src/BotArena/']
    for (prefix in serverPrefixes) {
        if (filePath.startsWith(prefix)) return 'server'
    }
    if (filePath in ['Src/deploy.sh', 'Src/deploy.py', 'Src/go.work', 'Src/go.work.sum', 'scripts/dev-pg.sh']) return 'server'
    if (filePath.startsWith('Src/docker-compose.proxy')) return 'server'

    // Auth service
    if (filePath.startsWith('Src/Auth/')) return 'auth'
    if (filePath.startsWith('Src/docker-compose.auth')) return 'auth'

    // Client-only paths.
    //
    // Src/Validation/ is the standalone card-validator core (the CardValidator
    // binary, its gtest suites, and the sources MCDCoreExt compiles into the
    // editor extension). It is authoring tooling, not runtime: it was previously
    // uncategorised, so every validator-only PR fell through to the unmatched
    // bucket and burned a full GameServer build. 'Validate GameData' runs in the
    // client pipeline too (gated on CLIENT_CHANGED), so the gamedata backstop
    // still re-runs with a rebuilt validator.
    def clientPrefixes = [
        'Src/MCDCoreExt/', 'Src/Validation/', 'GameModes/', 'Menus/', 'DeckBuilder/',
        'CardLibrary/', 'CardLibraryScripts/', 'Resources/', 'Onboard/',
        'Game/', 'Sandbox/', 'tests/', 'scripts/', 'addons/',
        'Assets/', 'Export/', 'Generated/',
    ]
    for (prefix in clientPrefixes) {
        if (filePath.startsWith(prefix)) return 'client'
    }
    def clientExact = [
        'project.godot', 'export_presets.cfg', 'build-godot.sh',
        'run_tests.gd', 'run_tests.gd.uid',
        'test_field_schema.gd', 'test_field_schema.gd.uid',
        'default_bus_layout.tres', 'mechacorps_draft.ico',
    ]
    if (filePath in clientExact) return 'client'

    // Wiki content
    if (filePath.startsWith('docs/wiki/')) return 'wiki'
    if (filePath.startsWith('Src/Wiki/')) return 'wiki'

    // Monitoring stack
    if (filePath.startsWith('Src/Monitoring/')) return 'monitoring'

    // CrashReporting + MCP Server (deployed by MCDServices pipeline)
    if (filePath.startsWith('Src/CrashReporting/') || filePath.startsWith('Src/MCPServer/')) return 'crash-reporting'
    if (filePath.startsWith('Src/docker-compose.crash-reporting')) return 'crash-reporting'

    // MCP Game Server (v1.1 Claude-as-player; local-only, no deploy pipeline).
    // Distinct from Src/MCPServer/ above (the v1 MCP-for-crashes binary).
    if (filePath.startsWith('Src/MCPGameServer/')) return 'mcp-game-server'

    // AccountService (per-environment app service)
    if (filePath.startsWith('Src/AccountService/')) return 'account-service'
    if (filePath.startsWith('Src/docker-compose.account')) return 'account-service'

    // AuctionHouse (per-environment app service)
    if (filePath.startsWith('Src/AuctionHouse/')) return 'auction-house'
    if (filePath.startsWith('Src/docker-compose.auction')) return 'auction-house'

    // Discord bot (standalone systemd service on host)
    if (filePath.startsWith('Src/Tools/discord-bot/')) return 'discord-bot'

    // MCP Game Server (Claude-as-Player MCP harness; Go module — see ADR mc-4bi.1).
    // Local dev tool, not deployed; tests run in the server pipeline.
    if (filePath.startsWith('Src/MCPGameServer/')) return 'mcp-game-server'

    // Root build system. Without this the Makefile matches no rule, lands in
    // unmatchedFiles and reaches the same both-builds outcome through the
    // fallback. The fallback also logs it as "unmatched", which hides that this
    // is the intended classification for a file we know about (mc-lvzi).
    if (filePath == 'Makefile') return 'build-system'

    // Documentation / tooling paths (no build needed)
    //
    // 'tools/' is the capture and review harness tree (tools/*_capture/,
    // grid_render_benchmark.gd, the coredump helpers). Nothing under it ships
    // in the client and nothing under it runs in the PR test path, which is
    // invoked as '-a res://tests'. It is driven by hand through Makefile
    // capture targets to produce review sheets.
    //
    // Before this rule it matched NOTHING and returned 'unknown', so it landed
    // in unmatchedFiles and the fallback set serverChanged AND clientChanged.
    // A PR touching only a review tool and a README therefore ran the full
    // client and server suite, and on merge triggered a real MCDServer-Main
    // build: GH #2862 is the worked example. Same defect class as the Makefile
    // rule below - a path we know about arriving through the path meant for
    // files we do not (mc-lvzi).
    //
    // Order matters here: docs/wiki/ is matched ABOVE this loop and must stay
    // there, or wiki content classifies as 'docs', wikiChanged stops being set
    // and mcdServicesPipeline silently stops rebuilding the wiki.
    def docPrefixes = [
        'docs/', '.github/', 'reports/', 'tools/',
    ]
    for (prefix in docPrefixes) {
        if (filePath.startsWith(prefix)) return 'docs'
    }
    def docExact = [
        'README.md', 'CLAUDE.md', 'STYLE_GUIDE.md', 'IMPLEMENTATION_GUIDE.md',
        '.gitignore', '.gitattributes', '.gitmodules',
        'cleanup-tracked-files.sh', 'verify-gitignore.sh',
    ]
    if (filePath in docExact) return 'docs'

    if (filePath.startsWith('Src/docker-compose.monitoring')) return 'monitoring'

    // Root .uid and audit files
    if (!filePath.contains('/') && (filePath.endsWith('.uid') || filePath.endsWith('_audit.gd'))) return 'docs'

    return 'unknown'
}

return this
