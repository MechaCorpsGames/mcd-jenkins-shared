// MechaCorps Change Detection - Shared Library
// Detects which components changed between two git refs to enable
// selective builds (server-only, client-only, or both).

/**
 * Detect which components have changed files.
 *
 * @param baseRef  Git ref to diff against (e.g., 'origin/main', a commit SHA)
 * @return Map with: serverChanged (bool), clientChanged (bool), changedFiles (list)
 */
def detect(String baseRef) {
    def changedFilesRaw = sh(
        script: "git diff --name-only ${baseRef} HEAD 2>/dev/null || echo '__DIFF_FAILED__'",
        returnStdout: true
    ).trim()

    if (changedFilesRaw.contains('__DIFF_FAILED__') || changedFilesRaw.isEmpty()) {
        echo "⚠️ Change detection failed or no changes found — building everything"
        return [serverChanged: true, clientChanged: true, changedFiles: []]
    }

    def changedFiles = changedFilesRaw.split('\n').collect { it.trim() }.findAll { it }
    echo "=== Changed files (${changedFiles.size()}) ==="
    changedFiles.each { echo "  ${it}" }

    def serverChanged = false
    def clientChanged = false
    def unmatchedFiles = []

    for (file in changedFiles) {
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
            case 'docs':
                break
            default:
                unmatchedFiles.add(file)
                break
        }
    }

    if (unmatchedFiles) {
        echo "⚠️ Unmatched files (triggering both builds):"
        unmatchedFiles.each { echo "  ${it}" }
        serverChanged = true
        clientChanged = true
    }

    echo "=== Change detection: server=${serverChanged}, client=${clientChanged} ==="
    return [serverChanged: serverChanged, clientChanged: clientChanged, changedFiles: changedFiles]
}

/**
 * Categorize a file path into a component.
 * @return 'server', 'client', 'shared', 'docs', or 'unknown'
 */
def categorize(String filePath) {
    // Shared paths (trigger both server and client builds)
    def sharedPrefixes = ['Src/Include/', 'Src/External/', 'Data/']
    for (prefix in sharedPrefixes) {
        if (filePath.startsWith(prefix)) return 'shared'
    }

    // Server-only paths
    def serverPrefixes = ['Src/GameServer/', 'Src/Proxy/', 'Src/TestClient/']
    for (prefix in serverPrefixes) {
        if (filePath.startsWith(prefix)) return 'server'
    }
    if (filePath in ['Src/deploy.sh', 'Src/go.work', 'Src/go.work.sum']) return 'server'
    if (filePath.startsWith('Src/docker-compose')) return 'server'

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

    // Documentation / tooling paths (no build needed)
    def docPrefixes = [
        'docs/', '.github/', 'reports/',
        'Src/Wiki/', 'Src/CrashReporting/', 'Src/MCPServer/',
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

    // Root .uid and audit files
    if (!filePath.contains('/') && (filePath.endsWith('.uid') || filePath.endsWith('_audit.gd'))) return 'docs'

    return 'unknown'
}

return this
