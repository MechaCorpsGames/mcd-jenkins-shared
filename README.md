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

// Unstable notification — amber, and it does not @-mention anyone.
// `reason` is env.UNSTABLE_REASON; see "Unstable builds must name their cause".
discordNotify.unstable(
    title: "Build",
    message: "⚠️ Build finished with card validation errors",
    reason: "card validation errors (validator exit 3)",
    jenkinsUrl: "https://jenkins.example.com",
    jobName: "MyJob",
    environment: "production",
    branch: "main"
)
```

## Unstable Builds Must Name Their Cause

An UNSTABLE build is not a failed build — the artifacts exist, the deploy
usually happened — so `post { unstable }` reports it in **amber** via
`discordNotify.unstable`, never in failure red. Colouring soft failures red is
how a team learns to skim past red.

**If you add anything that marks a build UNSTABLE, record why at that site:**

```groovy
mcdUnstableReason('card validation errors (validator exit 3)')   // then mark
catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') { /* ... */ }
```

`mcdUnstableReason` accumulates into `env.UNSTABLE_REASON`, `"; "`-joined and
de-duplicated, so two things going soft in one build report both. The
`post { unstable }` handlers read it and fall back to "finished with warnings"
when nothing set it. Phrase the reason as a noun clause that reads after
"Build finished with". `test/unit/test_mcd_unstable_notification.py` fails if a
new UNSTABLE marker does not name itself.

**Do not report a phase or stage name instead.** `post {}` used to say
`Build finished UNSTABLE at: Initializing`, for two compounding reasons: a phase
says where the build got *to*, not what went wrong, and `BUILD_PHASE` is
declared in the pipeline `environment {}` block — declarative re-applies those
entries as a contextual override, so the `env.BUILD_PHASE = '...'` assignments
inside stages never reach `post {}` at all. **Never declare `UNSTABLE_REASON` in
an `environment {}` block** or it freezes at that default the same way.

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

### A deploy tree is shared mutable state: serialize the job

`Sync Src Tree` fetches, force-resets and cleans one directory that every later
stage then builds and deploys out of. Nothing about that is per-build. **Any
pipeline with a `Sync Src Tree` stage must also declare
`disableConcurrentBuilds()`**, and `test/unit/test_sync_src_tree_serializes_builds.py`
fails the suite if one does not.

Without it, two overlapping builds race on the tree and it fails in two ways at
once. The loud one is the loser's fetch:

```
error: cannot lock ref 'refs/remotes/origin/main': is at 2e98e919 but expected 3212b14a
 ! 3212b14a..2e98e919  main -> origin/main  (unable to update local ref)
```

That reads like a corrupt repo and is not. It says the winner already applied
the update the loser was about to make. New branches in the same fetch still
succeed, because git skips the old-value check when it creates a
remote-tracking ref, so only pre-existing refs fail. Nothing is wedged and the
next build goes green on its own; **there is nothing to clean up on the host,
and `git pack-refs` / `git update-ref -d` will not help.**

The quiet failure is the one that matters. On 2026-08-18 `MCDServices-Main` #562
logged `Synced /var/opt/mechacorpsgames to 2e98e919`, which was #563's commit
and not its own, then deployed the wiki out of that tree and reported SUCCESS.
Pinning the tree to the branch being deployed is the whole point of the stage,
and an unserialized job cannot keep that promise.

**Do not fix the fetch error by retrying it.** A retry lets the losing build win
the second time and reset the tree while the other build is still reading it,
converting a red build into a silently wrong deploy.

Serialization is per-job, so it only covers trees that a single job owns. It
does **not** cover a second job reading the same tree: `mcdPromotePipeline`
builds the prod proxy image out of `/var/opt/mechacorpsgames/Src`, the same tree
`mcdServicesPipeline` resets. Closing that needs a cross-job `lock(resource:)`.

## Steam uploads: one per branch, fired last

Every client pipeline that arms `config.steamBranch` fires `MCDSteam-Upload`
from its **last** stage. Two rules keep that from flooding the controller.

**The trigger stays the last stage.** It used to sit ahead of
`Upload Debug Symbols`, so a client build handed the controller a second job to
run while it still had minutes of its own work left. With four executors and no
agents that upload competes for an executor with the PR validation somebody is
waiting on. `wait: false` and `propagate: false` are deliberate and must stay:
the client build must not block on the upload, and a Steam hiccup must not red
an otherwise good build.

**Redundant uploads coalesce, newest wins.** On 2026-08-23, `MCDSteam-Upload`
#593 through #597 all ran from `MCDClient-Main` with `STEAM_BRANCH=main` inside
two hours. They all set a build live on the same beta, so only the last had any
effect. `mcdSteamUploadPipeline` now skips an upload whose `SOURCE_BUILD` is
older than the newest archived build of the same source job, because that newer
build fires its own upload to the same Steam branch. A superseded build is
`NOT_BUILT` and notifies nobody: being superseded is the mechanism working.
Manually triggered uploads are never superseded, so a hand re-upload of a
known-good artifact still runs.

**Do not reach for `milestone()` or `disableConcurrentBuilds()` here.**
`MCDSteam-Upload` is one job serving four Steam branches. Both are job-scoped
and ordered by build number, with no notion of `STEAM_BRANCH`, so a `main`
upload would abort a queued `backend` upload that nothing superseded. A `lock()`
in this pipeline must be keyed on the branch or the source job, never on a
constant. `test/unit/test_steam_upload_coalescing.py` fails the suite if any of
that changes, and
`docs/adr/2026-08-23-steam-uploads-coalesce-on-artifact-freshness.md` carries
the reasoning.

Note the contrast with the deploy trees above: `Sync Src Tree` **requires**
`disableConcurrentBuilds()` because one job owns one mutable directory. The
Steam job is the opposite shape, one job owning four independent destinations,
and the same guard would be a bug.

## GitHub Webhook Configuration

After creating the Jenkins jobs, configure GitHub webhooks:

1. Go to repo **Settings → Webhooks → Add webhook**
2. **Payload URL**: `https://jenkins.mechacorpsgames.com/generic-webhook-trigger/invoke?token=<TOKEN>`
3. **Content type**: `application/json`
4. **Events**: Select `Pushes` and `Pull requests`

Webhook tokens. **This list is the tokens MCDClient's Jenkinsfiles declare, not the
tokens registered on GitHub.** A token does nothing until a Jenkins job carrying it has
been built at least once, so a token appearing here is a claim by a file, not evidence of
a working trigger. Read from `MCDClient/.Jenkins/` on 2026-08-19:

| Token | Declared by | Note |
|---|---|---|
| `mcdserver-main` | `Jenkinsfile.server.main` | |
| `mcdserver-release` | `Jenkinsfile.server.release` | |
| `mcdserver-feature-backend` | `Jenkinsfile.server.feature-backend` | |
| `mcdserver-feature-card` | `Jenkinsfile.server.feature-card` | |
| `mcdclient-main` | `Jenkinsfile.client.main` | |
| `mcdclient-release` | `Jenkinsfile.client.release` | |
| `mcdclient-feature-backend` | `Jenkinsfile.client.feature-backend` | |
| `mcdclient-feature-card` | `Jenkinsfile.client.feature-card` | |
| `mcdappservices-main` | `Jenkinsfile.appservices.main` | |
| `mcdappservices-release` | `Jenkinsfile.appservices.release` | |
| `mcdappservices-feature-backend` | `Jenkinsfile.appservices.feature-backend` | |
| `mcdappservices-feature-card` | `Jenkinsfile.appservices.feature-card` | |
| `mcdservices-main` | `Jenkinsfile.services.main` | |
| `mcd-discord-bot-main` | `Jenkinsfile.discord-bot.main` | |
| `mcd-crash-reporting` | `Jenkinsfile.crash-reporting` | The job name this file declares is not on the controller. Unsettled; see JOBS.md. |
| `mcd-pr-main` | `Jenkinsfile.pr.main` | |
| `mcd-pr-release` | `Jenkinsfile.pr.release` | |
| `mcd-pr-feature-backend` | `Jenkinsfile.pr.feature-backend` | **No job exists for this token.** MCD-PR-FeatureBackend returns 404, so nothing consumes it, and features/backend PRs get no validation at all (mc-fiu5). |

## A Jenkinsfile is not a job

Jobs here are created by hand in the controller UI. There is no job-config-as-code in
this repo or in MCDClient, so a committed Jenkinsfile runs only if somebody made a job
whose Script Path points at it. Nothing in either repo notices when that step is skipped.

It has been skipped, and for months. `MCDClient/.Jenkins/Jenkinsfile.pr.feature-backend`
has read like a full PR gate since 2026-05-26, determinism harness included, while
`MCD-PR-FeatureBackend` has never existed: it returns HTTP 404 and across the last 25
merged PRs targeting features/backend, zero carry a `jenkins/pr-validation` check
(mc-fiu5). `MCD-Determinism-Harness-Nightly` is in the same state, so its cron has never
fired (mc-mhgd).

**`MCDClient/.Jenkins/JOBS.md` is the per-file record**: every Jenkinsfile against the
job it declares, with a status of `present`, `absent` or `unverified`, guarded by
`tests/test_jenkins_job_manifest.py` in that repo. Update it in the same change that
creates or retires a job. The table below is the starting set for a fresh controller;
JOBS.md is what is true today.

Note that `jobName:` is a **label, not a binding**. The pipelines pass it to
`discordNotify`, which builds `${jenkinsUrl}/job/${jobName}/${BUILD_NUMBER}/`
(`vars/discordNotify.groovy:74`). A job can load a Jenkinsfile whose `jobName` says
something else; the cost is a Discord build link that 404s, not a pipeline that fails to
run. `MCDClient/.Jenkins/Jenkinsfile.server.release` declares `MCDServer-Release-Staging`
while the controller lists `MCDServer-Release`, which is exactly that shape and is
recorded as unverified rather than guessed at.

## Jenkins Jobs to Create

| Job Name | Jenkinsfile | Branch | Description |
|----------|-------------|--------|-------------|
| MCDServer-Main | `Jenkinsfile.server.main` | main | Server dev builds |
| MCDServer-Release | `Jenkinsfile.server.release` | release | Server prod builds |
| MCDClient-Main | `Jenkinsfile.client.main` | main | Client dev builds |
| MCDClient-Release | `Jenkinsfile.client.release` | release | Client prod builds |
| MCD-PR-Main | `Jenkinsfile.pr.main` | * | PR validation (main) |
| MCD-PR-Release | `Jenkinsfile.pr.release` | * | PR validation (release) |

The controller ran 19 jobs when this was read on 2026-08-19, so the six above are a
subset and always were: the feature-backend and feature-card variants of the server,
client and appservices pipelines, plus MCDServices-Main, MCDDiscordBot-Main,
MCDPlay-Upload, MCDSteam-Upload and MCDServer-Release-Promote, all exist and none were
listed here. Two jobs that MCDClient Jenkinsfiles ask for do **not** exist:
MCD-PR-FeatureBackend and MCD-Determinism-Harness-Nightly. Whether to create or
deliberately retire those is tracked as mc-rptw.

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
