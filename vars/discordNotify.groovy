// Discord notification helpers for MechaCorps CI/CD pipelines

// GitHub username → Discord user ID mapping
// Keep in sync with .github/discord-users.json
def discordUsers() {
    return [
        'wajulius':    '289448040253751296',
        'trowsey':     '354141124463427597',
        'Connor-McC':  '579530510049476610',
        'Omegasythe':  '368986727085244427',
        'research00':  '270155485745905665'
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
    sh "curl -s -X POST -H 'Content-Type: application/json' -d '${payload}' \$DISCORD_WEBHOOK || true"
}

/**
 * Send a success notification with full build details
 */
def success(Map config) {
    def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
    def commitMsg = env.commit_message ? env.commit_message.split('\n')[0].take(50) : 'Manual trigger'
    def author = env.BUILD_AUTHOR ?: 'Unknown'
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
    sh "curl -s -X POST -H 'Content-Type: application/json' -d '${payload}' \$DISCORD_WEBHOOK || true"
}

/**
 * Send a failure notification
 */
def failure(Map config) {
    def shortSha = env.commit_sha ? env.commit_sha.take(7) : 'manual'
    def commitMsg = env.commit_message ? env.commit_message.split('\n')[0].take(50) : 'Manual trigger'
    def author = env.BUILD_AUTHOR ?: 'Unknown'
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
    sh "curl -s -X POST -H 'Content-Type: application/json' -d '${payload}' \$DISCORD_WEBHOOK || true"
}

return this
