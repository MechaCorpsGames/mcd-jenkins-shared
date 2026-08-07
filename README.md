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

### `mcdPRValidationPipeline`

Validates pull requests before merge. Two tiers:
- **PRs targeting `main`**: GameServer build + unit/integration tests + GDScript tests (~7-10 min)
- **PRs targeting `release`**: All of the above + MCDCoreExt multi-platform builds (Linux, Windows, Android) (~20-30 min)

Checks out GitHub's PR merge ref (`refs/pull/<id>/merge`) to test the exact post-merge state. Reports pass/fail as a GitHub commit status check on the PR.

```groovy
// For PRs targeting main (lightweight)
@Library('mcd-shared') _

mcdPRValidationPipeline(
    targetBranch: 'main',
    webhookToken: 'mcd-pr-main',
    jobName: 'MCD-PR-Main'
)
```

```groovy
// For PRs targeting release (full validation)
@Library('mcd-shared') _

mcdPRValidationPipeline(
    targetBranch: 'release',
    webhookToken: 'mcd-pr-release',
    jobName: 'MCD-PR-Release'
)
```

**Prerequisites:**
- Jenkins credential `github-status-token` — GitHub PAT with `repo:status` scope
- Jenkins credential `discord-webhook-url` — Discord webhook URL
- GitHub webhook sending `Pull requests` events to Jenkins

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

## Deploy Trees and the SSH Remote

`mcdServicesPipeline` and `mcdAppServicesPipeline` build and deploy out of a **deploy tree** on the host (`/var/opt/mechacorpsgames`, `/var/opt/mechacorpsgames-<env>`), not out of the Jenkins workspace — the compose files, the gitignored `.env.*` secrets, and the bind-mounted log volumes live there. Their `Sync Src Tree` stage resets that tree to `origin/<branch>` before any build reads it.

**The deploy tree's remote must be SSH.** The build container mounts `/var/lib/jenkins/.ssh` and nothing else credential-wise — it has **no HTTPS credentials**. So the tree is recovered and bootstrapped from an explicit `git@github.com:...` remote, *not* from `${GIT_URL}` (the job's SCM URL, which is HTTPS). Override per-caller with the `deployRemote` config key if a job tracks a different repo.

An HTTPS remote fails as `could not read Username for 'https://github.com'`, several layers down inside `git fetch`. It reads like a transient network fault, but the tree then quietly stops tracking the branch and later stages keep deploying stale source — building **green** the whole time. `Sync Src Tree` now checks the remote up front and fails the build with the offending URL instead. This cost ~15h of a red `MCDServices-Main` on 2026-08-06.

### Diagnosing: `git remote get-url` lies

A `url.<base>.insteadOf` rewrite can silently turn an SSH remote into an HTTPS one at fetch time, so a correct-looking `origin` proves nothing. The two commands disagree, and that disagreement *is* the rewrite:

```bash
git -C <tree> config --get remote.origin.url   # RAW configured value — ignores insteadOf
git -C <tree> remote get-url origin            # EFFECTIVE value — expands insteadOf, what fetch actually uses
```

When diagnosing, read the raw config rather than trusting `get-url`, and look for the rewrite itself:

```bash
cat <tree>/.git/config                  # raw, no rewrites applied
git config --get-regexp '^url\.'        # repo-level rewrites
git config --global --get-regexp '^url\.'   # global rewrites — this is what bit us
```

To repair a tree by hand:

```bash
git -C <tree> remote set-url origin git@github.com:MechaCorpsGames/MCDClient.git
git config --global --unset-all url.https://github.com/.insteadOf   # if an SSH->HTTPS rewrite is present
```

## GitHub Webhook Configuration

After creating the Jenkins jobs, configure GitHub webhooks:

1. Go to repo **Settings → Webhooks → Add webhook**
2. **Payload URL**: `https://jenkins.mechacorpsgames.com/generic-webhook-trigger/invoke?token=<TOKEN>`
3. **Content type**: `application/json`
4. **Events**: Select `Pushes` and `Pull requests`

Webhook tokens:
- `mcdserver-main` - Server pipeline (main branch)
- `mcdserver-release` - Server pipeline (release branch)
- `mcdclient-main` - Client pipeline (main branch)
- `mcdclient-release` - Client pipeline (release branch)
- `mcd-pr-main` - PR validation pipeline (PRs targeting main)
- `mcd-pr-release` - PR validation pipeline (PRs targeting release)

## Jenkins Jobs to Create

| Job Name | Jenkinsfile | Branch | Description |
|----------|-------------|--------|-------------|
| MCDServer-Main | `Jenkinsfile.server.main` | main | Server dev builds |
| MCDServer-Release | `Jenkinsfile.server.release` | release | Server prod builds |
| MCDClient-Main | `Jenkinsfile.client.main` | main | Client dev builds |
| MCDClient-Release | `Jenkinsfile.client.release` | release | Client prod builds |
| MCD-PR-Main | `Jenkinsfile.pr.main` | * | PR validation (main) |
| MCD-PR-Release | `Jenkinsfile.pr.release` | * | PR validation (release) |

### Deploy Jobs (MCDServer-*, MCDClient-*)

Each job should be configured as:
- **Pipeline from SCM**
- **Repository**: `https://github.com/MechaCorpsGames/MCDClient.git`
- **Branch**: `*/main` or `*/release` (matching the Jenkinsfile)
- **Script Path**: The corresponding Jenkinsfile (e.g., `.Jenkins/Jenkinsfile.server.main`)

### PR Validation Jobs (MCD-PR-*)

Each PR validation job should be configured as:
- **Pipeline from SCM**
- **Repository**: `https://github.com/MechaCorpsGames/MCDClient.git`
- **Branch**: `*/main` (the Jenkinsfile must be available; the pipeline checks out the PR merge ref itself)
- **Script Path**: `.Jenkins/Jenkinsfile.pr.main` or `.Jenkins/Jenkinsfile.pr.release`
- **Refspec**: Add `+refs/pull/*/merge:refs/remotes/origin/pr/*/merge` to Advanced Clone Behaviours so Jenkins can fetch PR merge refs

**Jenkins Credential Setup:**
1. Go to **Manage Jenkins → Credentials → System → Global credentials**
2. Add a **Secret text** credential:
   - **ID**: `github-status-token`
   - **Secret**: A GitHub Personal Access Token with `repo:status` scope
   - **Description**: "GitHub Status API token for PR checks"

## Docker Build Agent

All pipelines run inside a Docker container using the `mcd-build-agent` image. This provides isolated, reproducible builds and allows multiple jobs to run concurrently.

### Prerequisites

1. **Docker Pipeline plugin**: Install via Manage Jenkins → Plugins → Available → search "Docker Pipeline" (`docker-workflow`)
2. **Executor count**: Set to 4+ via Manage Jenkins → Nodes → Built-In Node → Number of executors

### Building the Image

```bash
cd docker/build-agent
./build.sh
```

This builds and tags `mcd-build-agent:latest` locally. Rebuild when tool versions change (Go, Godot, Android NDK, etc.).

### What's in the Image

Debian Bookworm with: GCC, CMake, Go 1.24, MinGW-w64, OpenJDK 17, Android SDK/NDK 26.1, Godot 4.6 headless, Docker CLI, docker-compose, sentry-cli.

### Verifying the Image

```bash
docker run --rm mcd-build-agent:latest bash -c \
  'gcc --version | head -1 && go version && godot --version && java -version 2>&1 | head -1 && echo "OK"'
```
