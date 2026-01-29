# MechaCorps Jenkins Shared Library

Shared pipeline code for MechaCorps CI/CD.

## Setup in Jenkins

1. Go to **Manage Jenkins → System → Global Pipeline Libraries**
2. Click **Add**
3. Configure:
   - **Name**: `mcd-shared`
   - **Default version**: `main`
   - **Allow default version to be overridden**: ✓
   - **Retrieval method**: Modern SCM
   - **Source Code Management**: Git
   - **Project Repository**: `https://github.com/MechaCorpsGames/mcd-jenkins-shared.git`
   - **Credentials**: (select GitHub credentials if private)

## Available Pipelines

### `mcdServerPipeline`

Builds and deploys the GameServer, Proxy, and TestClient.

```groovy
@Library('mcd-shared') _

mcdServerPipeline(
    branch: 'main',
    environment: 'development',
    deployPath: '/opt/mechacorps/main',
    tcpPort: 43069,
    wsPort: 43070,
    serverHost: 'dev.mechacorpsgames.com',
    webhookToken: 'mcdserver-main',
    jobName: 'MCDServer-Main'
)
```

### `mcdClientPipeline`

Builds MCDCoreExt GDExtension for Linux, Windows, and Android.

```groovy
@Library('mcd-shared') _

mcdClientPipeline(
    branch: 'main',
    environment: 'development',
    serverUrl: 'wss://dev.mechacorpsgames.com',
    webhookToken: 'mcdclient-main',
    jobName: 'MCDClient-Main'
)
```

### `discordNotify`

Discord notification helpers (used internally by pipelines).

```groovy
// Simple notification
discordNotify.simple("Build started", "3447003")

// Success notification with full details
discordNotify.success(
    title: "Build",
    message: "Success!",
    jenkinsUrl: "https://jenkins.example.com",
    jobName: "MyJob",
    environment: "production",
    branch: "main",
    version: "1.0.0"
)

// Failure notification
discordNotify.failure(
    title: "Build",
    message: "Failed!",
    jenkinsUrl: "https://jenkins.example.com",
    jobName: "MyJob",
    environment: "production",
    branch: "main"
)
```

## GitHub Webhook Configuration

After creating the Jenkins jobs, configure GitHub webhooks:

1. Go to repo **Settings → Webhooks → Add webhook**
2. **Payload URL**: `https://jenkins.mechacorpsgames.com/generic-webhook-trigger/invoke?token=<TOKEN>`
3. **Content type**: `application/json`
4. **Events**: Just the push event

Webhook tokens:
- `mcdserver-main` - Server pipeline (main branch)
- `mcdserver-release` - Server pipeline (release branch)
- `mcdclient-main` - Client pipeline (main branch)
- `mcdclient-release` - Client pipeline (release branch)

## Jenkins Jobs to Create

| Job Name | Jenkinsfile | Branch | Description |
|----------|-------------|--------|-------------|
| MCDServer-Main | `Jenkinsfile.server.main` | main | Server dev builds |
| MCDServer-Release | `Jenkinsfile.server.release` | release | Server prod builds |
| MCDClient-Main | `Jenkinsfile.client.main` | main | Client dev builds |
| MCDClient-Release | `Jenkinsfile.client.release` | release | Client prod builds |

Each job should be configured as:
- **Pipeline from SCM**
- **Repository**: `https://github.com/MechaCorpsGames/MCDClient.git`
- **Branch**: `*/main` or `*/release` (matching the Jenkinsfile)
- **Script Path**: The corresponding Jenkinsfile (e.g., `Jenkinsfile.server.main`)
