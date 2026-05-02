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
 *         proxyChanged, sharedChanged, mcpServerChanged,
 *         changedFiles (list)
 *
 * proxyChanged / sharedChanged / mcpServerChanged are computed via direct
 * filePath prefix scans (not via categorize()) so the per-module Go test
 * stage in mcdPRValidationPipeline can gate each Go module independently.
 * They sit alongside the existing category-driven flags rather than
 * replacing them — Src/Proxy/ still routes to 'server' for the server
 * build pipeline; Src/MCPServer/ still routes to 'crash-reporting' for
 * the bundled deploy in mcdServicesPipeline.
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
                proxyChanged: true, sharedChanged: true, mcpServerChanged: true,
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
    // Per-Go-module flags (independent of categorize(); see method doc)
    def proxyChanged = false
    def sharedChanged = false
    def mcpServerChanged = false
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

        def category = categorize(file)
        switch (category) {
            case 'server':
                serverChanged = true
                break
            case 'client':
                clientChanged = true
                break
            case 'shared':
                serverChanged = true
                clientChanged = true
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
            case 'wiki':
                wikiChanged = true
                break
            case 'monitoring':
                monitoringChanged = true
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

    echo "=== Change detection: server=${serverChanged}, client=${clientChanged}, auth=${authChanged}, wiki=${wikiChanged}, monitoring=${monitoringChanged}, crashReporting=${crashReportingChanged}, accountService=${accountServiceChanged}, auctionHouse=${auctionHouseChanged}, discordBot=${discordBotChanged}, proxy=${proxyChanged}, shared=${sharedChanged}, mcpServer=${mcpServerChanged} ==="
    return [serverChanged: serverChanged, clientChanged: clientChanged,
            authChanged: authChanged, wikiChanged: wikiChanged,
            monitoringChanged: monitoringChanged,
            crashReportingChanged: crashReportingChanged,
            accountServiceChanged: accountServiceChanged,
            auctionHouseChanged: auctionHouseChanged,
            discordBotChanged: discordBotChanged,
            proxyChanged: proxyChanged,
            sharedChanged: sharedChanged,
            mcpServerChanged: mcpServerChanged,
            changedFiles: changedFiles]
}

/**
 * Categorize a file path into a component.
 * @return 'server', 'client', 'shared', 'services-shared', 'auth',
 *         'account-service', 'auction-house', 'crash-reporting',
 *         'wiki', 'monitoring', 'docs', or 'unknown'
 */
def categorize(String filePath) {
    // Shared paths (trigger both server and client builds)
    def sharedPrefixes = ['Src/Include/', 'Src/External/', 'Data/']
    for (prefix in sharedPrefixes) {
        if (filePath.startsWith(prefix)) return 'shared'
    }

    // Go shared library (affects Proxy, Auth, CrashReporting, MCPServer)
    if (filePath.startsWith('Src/Shared/')) return 'services-shared'

    // Server-only paths
    def serverPrefixes = ['Src/GameServer/', 'Src/Proxy/', 'Src/TestClient/']
    for (prefix in serverPrefixes) {
        if (filePath.startsWith(prefix)) return 'server'
    }
    if (filePath in ['Src/deploy.sh', 'Src/deploy.py', 'Src/go.work', 'Src/go.work.sum', 'scripts/dev-pg.sh', 'flake.nix', 'flake.lock']) return 'server'
    if (filePath.startsWith('Src/docker-compose.proxy')) return 'server'

    // Auth service
    if (filePath.startsWith('Src/Auth/')) return 'auth'
    if (filePath.startsWith('Src/docker-compose.auth')) return 'auth'

    // Client-only paths
    def clientPrefixes = [
        'Src/MCDCoreExt/', 'GameModes/', 'Menus/', 'DeckBuilder/',
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

    // AccountService (per-environment app service)
    if (filePath.startsWith('Src/AccountService/')) return 'account-service'
    if (filePath.startsWith('Src/docker-compose.account')) return 'account-service'

    // AuctionHouse (per-environment app service)
    if (filePath.startsWith('Src/AuctionHouse/')) return 'auction-house'
    if (filePath.startsWith('Src/docker-compose.auction')) return 'auction-house'

    // Discord bot (standalone systemd service on host)
    if (filePath.startsWith('Src/Tools/discord-bot/')) return 'discord-bot'

    // Documentation / tooling paths (no build needed)
    def docPrefixes = [
        'docs/', '.github/', 'reports/',
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
