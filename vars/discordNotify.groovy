// Discord notification helpers for MechaCorps CI/CD pipelines

// GitHub username → Discord user ID mapping
// Keep in sync with .github/discord-users.json
def discordUsers() {
    return [
        'wajulius':    '289448040253751296',
        'trowsey':     '354141124463427597',
        'Connor-McC':  '579530510049476610',
        'Omegasythe':  '368986727085244427',
        'research00':  '270155485745905665',
        'quad341':     '612142866516410398'
    ]
}

/**
 * Look up a Discord user ID from a GitHub username.
 * Returns null if not found.
 */
def lookupDiscordId(String githubUser) {
    if (!githubUser) return null
    return discordUsers()[githubUser]
}

/**
 * POST a payload to the Discord webhook.
 *
 * Safe to call with or without a node context: declarative post{} blocks
 * run without a workspace when agent provisioning fails (e.g. the build
 * image is missing), which used to crash writeFile with
 * MissingContextVariableException — marking the build red AND silently
 * dropping the very notification that should have reported it. When no
 * FilePath context is available, grab the built-in node just long enough
 * to fire the webhook.
 */
def sendPayload(String payload) {
    try {
        writeFile file: '.discord_payload.json', text: payload
        sh 'curl -s -X POST -H "Content-Type: application/json" -d @.discord_payload.json $DISCORD_WEBHOOK || true'
        sh 'rm -f .discord_payload.json'
    } catch (org.jenkinsci.plugins.workflow.steps.MissingContextVariableException e) {
        node {
            writeFile file: '.discord_payload.json', text: payload
            sh 'curl -s -X POST -H "Content-Type: application/json" -d @.discord_payload.json $DISCORD_WEBHOOK || true'
            sh 'rm -f .discord_payload.json'
        }
    }
}

/**
 * Send a simple Discord notification (for in-progress updates)
 * Optional: pass githubUser to @ mention them.
 */
def simple(String message, String color, String githubUser = null) {
    def discordId = lookupDiscordId(githubUser)
    def contentField = ''
    def mentionsField = ''
    if (discordId) {
        contentField = """"content":"<@${discordId}>","""
        mentionsField = ""","allowed_mentions":{"users":["${discordId}"]}"""
    }
    def payload = """{${contentField}"embeds":[{"description":"${message}","color":${color}}]${mentionsField}}"""
    sendPayload(payload)
}

/**
 * Send a success notification with full build details
 */
def success(Map config) {
    def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
    def commitMsg = env.commit_message ? env.commit_message.split('\n')[0].take(50).replace('\\', '\\\\').replace('"', '\\"') : 'Manual trigger'
    def author = (env.BUILD_AUTHOR ?: 'Unknown').replace('\\', '\\\\').replace('"', '\\"')
    def duration = currentBuild.durationString.replace(' and counting', '')
    def buildUrl = "${config.jenkinsUrl}/job/${config.jobName}/${BUILD_NUMBER}/"
    def artifactUrl = "${buildUrl}artifact/artifacts/"

    // Color: green for production, blue for development
    def color = (config.environment == "production") ? 3066993 : 3447003
    def envEmoji = (config.environment == "production") ? "🚀" : "🔧"

    def fields = [
        [name: "Environment", value: config.environment?.capitalize() ?: 'Unknown', inline: true],
        [name: "Branch", value: config.branch ?: 'unknown', inline: true],
        [name: "Version", value: config.version ?: 'N/A', inline: true]
    ]

    // Add pipeline-specific fields
    if (config.serverHost) {
        fields << [name: "Server", value: config.serverHost, inline: true]
    }
    if (config.serverUrl) {
        fields << [name: "Server URL", value: config.serverUrl, inline: true]
    }
    if (config.tcpPort && config.wsPort) {
        fields << [name: "Ports", value: "TCP:${config.tcpPort}/WS:${config.wsPort}", inline: true]
    }
    if (config.libSize) {
        fields << [name: "Library Size", value: config.libSize, inline: true]
    }
    if (config.steamBranch) {
        fields << [name: "Steam", value: "Uploaded to `${config.steamBranch}`", inline: true]
    }

    fields << [name: "Duration", value: duration, inline: true]
    fields << [name: "Commit", value: "`${shortSha}` ${commitMsg}", inline: false]
    fields << [name: "Author", value: author, inline: true]

    def fieldsJson = fields.collect { f ->
        """{"name":"${f.name}","value":"${f.value}","inline":${f.inline}}"""
    }.join(',')

    def payload = """{
        "embeds": [{
            "title": "${envEmoji} ${config.title} #${BUILD_NUMBER}",
            "description": "${config.message}",
            "color": ${color},
            "fields": [${fieldsJson}],
            "timestamp": "${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))}",
            "footer": {"text": "Jenkins CI/CD"},
            "url": "${buildUrl}"
        }],
        "components": [{
            "type": 1,
            "components": [
                {"type": 2, "style": 5, "label": "View Build", "url": "${buildUrl}"},
                {"type": 2, "style": 5, "label": "Download Artifacts", "url": "${artifactUrl}"}
            ]
        }]
    }"""
    sendPayload(payload)
}

/**
 * Send a failure notification
 */
def failure(Map config) {
    def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
    def commitMsg = env.commit_message ? env.commit_message.split('\n')[0].take(50).replace('\\', '\\\\').replace('"', '\\"') : 'Manual trigger'
    def author = (env.BUILD_AUTHOR ?: 'Unknown').replace('\\', '\\\\').replace('"', '\\"')
    def duration = currentBuild.durationString.replace(' and counting', '')
    def buildUrl = "${config.jenkinsUrl}/job/${config.jobName}/${BUILD_NUMBER}/"
    def consoleUrl = "${buildUrl}console"

    def githubUser = env.BUILD_GITHUB_USER
    def discordId = lookupDiscordId(githubUser)
    def contentField = ''
    def mentionsField = ''
    if (discordId) {
        contentField = """"content": "<@${discordId}>","""
        mentionsField = ""","allowed_mentions": {"users": ["${discordId}"]}"""
    }

    def payload = """{
        ${contentField}
        "embeds": [{
            "title": "${config.title} #${BUILD_NUMBER}",
            "description": "${config.message}",
            "color": 15158332,
            "fields": [
                {"name": "Environment", "value": "${config.environment?.capitalize() ?: 'Unknown'}", "inline": true},
                {"name": "Branch", "value": "${config.branch ?: 'unknown'}", "inline": true},
                {"name": "Duration", "value": "${duration}", "inline": true},
                {"name": "Commit", "value": "`${shortSha}` ${commitMsg}", "inline": false},
                {"name": "Author", "value": "${author}", "inline": true}
            ],
            "timestamp": "${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))}",
            "footer": {"text": "Jenkins CI/CD"},
            "url": "${consoleUrl}"
        }],
        "components": [{
            "type": 1,
            "components": [
                {"type": 2, "style": 5, "label": "View Console Log", "url": "${consoleUrl}"}
            ]
        }]${mentionsField}
    }"""
    sendPayload(payload)
}

/**
 * Escape a value for embedding in the JSON payloads this file builds by hand.
 *
 * Raw CR/LF/TAB are not legal inside a JSON string, and an unescaped quote or
 * backslash truncates the payload — Discord answers a malformed body with a
 * silent 400, so the notification simply never arrives. That matters most for
 * machine-generated text (a validator message, a path, an unstable reason),
 * which is exactly what the callers below pass in.
 */
def jsonEscape(String value) {
    if (!value) return ''
    return value
        .replace('\\', '\\\\')
        .replace('"', '\\"')
        .replace('\r', '')
        .replace('\n', '\\n')
        .replace('\t', ' ')
}

/**
 * Send an "unstable" notification — amber, deliberately NOT failure red.
 *
 * An unstable build is not a failed build: the artifacts exist, the deploy
 * usually happened, but something soft went wrong — a validation gate on a
 * branch whose card corpus is still being cleaned up, a debug-symbol upload
 * that did not land. Painting that failure-red teaches people that red is
 * often nothing, and then a real failure gets skimmed past too. So: amber
 * (16776960, the same warm tone awaitingApproval uses) and no @-mention. A
 * ping is a failure-grade signal; amber means "look when you get a chance".
 *
 * config keys — the usual title/jenkinsUrl/jobName/environment/branch, plus:
 *   message: the description line, already phrased for a human
 *   reason:  optional cause (env.UNSTABLE_REASON, recorded by
 *            mcdUnstableReason at the site that knew) surfaced as its own
 *            field, so the cause is still there for someone who skims the
 *            description. Omitted from the embed when nothing set it.
 *
 * Call it qualified — discordNotify.unstable(...). Bare `unstable(...)` in a
 * pipeline is Jenkins' own built-in step, which marks the build unstable
 * instead of announcing it.
 */
def unstable(Map config) {
    def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
    def commitMsg = env.commit_message ? jsonEscape(env.commit_message.split('\n')[0].take(50)) : 'Manual trigger'
    def author = jsonEscape(env.BUILD_AUTHOR ?: 'Unknown')
    def duration = currentBuild.durationString.replace(' and counting', '')
    def buildUrl = "${config.jenkinsUrl}/job/${config.jobName}/${BUILD_NUMBER}/"
    def consoleUrl = "${buildUrl}console"

    def fields = [
        [name: "Environment", value: config.environment?.capitalize() ?: 'Unknown', inline: true],
        [name: "Branch", value: config.branch ?: 'unknown', inline: true],
        [name: "Duration", value: duration, inline: true]
    ]

    // The cause goes in a field as well as the description. The description is
    // what people skim in the channel; the field is what they read when they
    // want to know what to go fix.
    def reason = jsonEscape(config.reason as String)
    if (reason) {
        fields << [name: "Unstable because", value: reason, inline: false]
    }

    fields << [name: "Commit", value: "`${shortSha}` ${commitMsg}", inline: false]
    fields << [name: "Author", value: author, inline: true]

    def fieldsJson = fields.collect { f ->
        """{"name":"${f.name}","value":"${f.value}","inline":${f.inline}}"""
    }.join(',')

    def payload = """{
        "embeds": [{
            "title": "⚠️ ${config.title} #${BUILD_NUMBER}",
            "description": "${jsonEscape(config.message as String)}",
            "color": 16776960,
            "fields": [${fieldsJson}],
            "timestamp": "${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))}",
            "footer": {"text": "Jenkins CI/CD"},
            "url": "${consoleUrl}"
        }],
        "components": [{
            "type": 1,
            "components": [
                {"type": 2, "style": 5, "label": "View Console Log", "url": "${consoleUrl}"}
            ]
        }]
    }"""
    sendPayload(payload)
}

/**
 * Send an "awaiting approval" notification with action buttons.
 *
 * Use for pipelines that pause on a manual input{} step — gives operators
 * a one-click path from Discord to (a) the pipeline page, (b) the input
 * confirmation prompt, and (c) the downloadable artifact they need to
 * verify before approving (e.g. the BVT client build).
 *
 * config keys:
 *   title:       Headline for the embed (e.g. "Promote awaiting approval")
 *   message:     Body text explaining what to do
 *   jobName:     Jenkins job name (for the button URL + footer)
 *   jenkinsUrl:  Base Jenkins URL (e.g. https://jenkins.mechacorpsgames.com)
 *   version:     Optional version string surfaced as a field
 *   environment: Optional environment string surfaced as a field
 *   bvtArtifactUrl: Optional URL to the client build testers download.
 *                   Common pattern: `${jenkinsUrl}/job/MCDClient-Release/lastSuccessfulBuild/artifact/`
 *   instructionsField: Optional pre-formatted tester-instructions block
 *                      shown as a multi-line field above the buttons
 *                      (e.g. how to launch the BVT client pointed at staging).
 *   githubUser:  Optional GitHub username to @-mention
 */
def awaitingApproval(Map config) {
    def buildUrl = "${config.jenkinsUrl}/job/${config.jobName}/${BUILD_NUMBER}/"
    def inputUrl = "${buildUrl}input/"

    def discordId = lookupDiscordId(config.githubUser)
    def contentField = ''
    def mentionsField = ''
    if (discordId) {
        contentField = """"content":"<@${discordId}>","""
        mentionsField = ""","allowed_mentions":{"users":["${discordId}"]}"""
    }

    def fields = []
    if (config.environment) {
        fields << [name: 'Environment', value: config.environment.capitalize(), inline: true]
    }
    if (config.version) {
        fields << [name: 'Version', value: config.version, inline: true]
    }
    if (config.instructionsField) {
        fields << [name: 'Tester checklist', value: config.instructionsField, inline: false]
    }
    def fieldsJson = fields.collect { f ->
        def escapedValue = f.value.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
        """{"name":"${f.name}","value":"${escapedValue}","inline":${f.inline}}"""
    }.join(',')

    // Button row. The Confirm button takes operators straight to the input
    // prompt; clicking it opens the Jenkins page where the "Promote" button
    // is one more click away. Download button is omitted when no BVT
    // artifact URL is supplied (keeps the row tidy for non-BVT approvals).
    def buttons = [
        """{"type":2,"style":5,"label":"View Pipeline","url":"${buildUrl}"}""",
        """{"type":2,"style":5,"label":"Confirm in Jenkins","url":"${inputUrl}"}"""
    ]
    if (config.bvtArtifactUrl) {
        buttons << """{"type":2,"style":5,"label":"Download BVT Client","url":"${config.bvtArtifactUrl}"}"""
    }
    def buttonsJson = buttons.join(',')

    // Yellow/amber color (16776960) — same as the .simple "in progress"
    // notifications so the awaiting-approval message reads as "warm,
    // waiting on you" rather than success/failure.
    def payload = """{
        ${contentField}
        "embeds": [{
            "title": "⏳ ${config.title}",
            "description": "${config.message?.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n') ?: ''}",
            "color": 16776960,
            "fields": [${fieldsJson}],
            "timestamp": "${new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC'))}",
            "footer": {"text": "Jenkins CI/CD — ${config.jobName} #${BUILD_NUMBER}"},
            "url": "${buildUrl}"
        }],
        "components": [{
            "type": 1,
            "components": [${buttonsJson}]
        }]${mentionsField}
    }"""
    sendPayload(payload)
}

return this
