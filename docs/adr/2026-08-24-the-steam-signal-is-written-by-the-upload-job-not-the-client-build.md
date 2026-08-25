# The latest-client-on-Steam signal is written by the upload job, not the client build

Date: 2026-08-24

Status: Accepted

## Context

Bead mc-cdjn added a client update prompt to the protocol back-compat design.
Tim's decision, recorded there on 2026-08-24: old clients should be asked to
restart and update, **but only once the newer client is actually live on Steam**.
Until the upload completes, back-compat routing runs silently and nobody is
nagged.

That needs a signal: the newest client build published to Steam, and the protocol
that build speaks. This repo owns producing it. The consumer half (a proxy that
relays it, a client that acts on it) is MCDClient bead mc-fxt7, and its own ADR,
`docs/adr/2026-08-24-the-latest-client-on-steam-signal.md` in that repo, covers
the transmit side.

**One failure mode dominates every other consideration here.** If the signal says
a build is downloadable when it is not, a player is told to restart, restarts,
gets the same client back, and is told again. There is no action available to
them that succeeds, and nothing on their screen suggests the fault is ours. A
signal that is merely *late* costs a player a few minutes of not knowing an
update exists. Every decision below resolves in favour of late.

### Where the bead said to write it, and why that could not work

mc-fxt7 specified `mcdClientPipeline.groovy`'s `Publish to Steam` stage (its
last), on the reasoning that a stage named "Publish to Steam" is where publishing
to Steam completes. Reading it, the whole stage body is:

```groovy
build job: 'MCDSteam-Upload',
    parameters: [ /* SOURCE_JOB, SOURCE_BUILD, STEAM_BRANCH */ ],
    wait: false,
    propagate: false
```

It fires the upload job and returns. With `wait: false` it does not have a
result to inspect, and with `propagate: false` it would not adopt one anyway. A
signal written there means "an upload was requested", which is precisely the
failure above: the client build number is real, but the bytes may be minutes away
or may never arrive.

This is the same trap as bead mc-91jj, which the bead itself cited as the thing
to learn from, and it is worth naming that the bead's own instruction would have
reproduced it.

## Decision

**Write the signal in a new `Publish Steam Signal` stage in
`mcdSteamUploadPipeline.groovy`, positioned after `Upload to Steam`.**

That job is where `steamcmd.sh` actually runs. Declarative Pipeline fails a stage
whose `sh` step exits non-zero, and a failed stage stops the pipeline, so a stage
placed after the upload runs **only** if steamcmd genuinely finished. The
guarantee is structural: there is no ordering of future edits to the upload stage
that lets a failure fall through into the signal stage. That is stronger than the
`set -e` discipline the bead asked for, which depends on nobody editing the shell
block carelessly.

Three further guards close the remaining routes to a false signal:

- **`UPLOAD_SUPERSEDED != 'true'`.** A coalesced build (see
  `2026-08-23-steam-uploads-coalesce-on-artifact-freshness.md`) is marked
  NOT_BUILT before steamcmd runs. Every other stage in this job already carries
  this guard; the signal stage carries it for the same reason.
- **`STEAM_BRANCH != 'default'`.** `Prepare Steam Content` deliberately leaves
  `setlive` empty for the public branch and logs "flip public manually in
  Steamworks". Those bytes reach Steam but no player can download them. It is the
  one path where the upload legitimately succeeds and nothing became available.
- **A missing `protocolVersion` refuses rather than defaults.** A source build
  predating `Generate Compatibility Manifest` has no protocol in its manifest.
  That field drives the client's HARD block, so a default would be the difference
  between nudging a player and locking them out of a game they can still play.
  The stage logs and writes nothing, and the proxy keeps serving the last good
  signal.

### Shape and location of the file

`/opt/mechacorps/steam-signals/<STEAM_BRANCH>.json`, written to a temp file and
renamed (rename(2) within one directory is atomic, and the proxy re-reads this
path on a timer).

```json
{
    "clientVersion": "0.2.148",
    "protocolVersion": 54,
    "steamBranch": "main",
    "sourceJob": "MCDClient-Main",
    "sourceBuild": "512",
    "uploadedAt": "2026-08-24T19:00:00+00:00"
}
```

`clientVersion` and `protocolVersion` are the signal. The rest is for whoever
reads this file during an incident; the proxy deliberately does not model them,
so no code can start depending on them.

**A file on the deploy host, not an endpoint.** `mcdServerPipeline` already
deploys by writing straight into `/opt/mechacorps` (its agent mounts that path),
so this is the mechanism the existing deploy already makes easy: no new service,
port, endpoint or credential, and nothing to keep running. The upload job's agent
gains the same mount and the same `--group-add` list, copied from the job that
demonstrably writes there today rather than guessed at.

**Keyed by Steam branch, not by deploy environment.** `MCDClient-Release` uploads
to the Steam `staging` beta while pointing at `wss://play.mechacorpsgames.com`,
so branch-to-environment is genuinely ambiguous. Rather than encode a guess, each
proxy is pointed at the file for the branch its players are on, via
`--steam-signal-file`. The mapping lives in deploy config where an operator can
see and change it.

### A failed write is UNSTABLE, never FAILURE

By the time the signal stage runs the upload has already succeeded. A bare `sh`
would turn a successful Steam upload into a red build and a Discord message
saying the upload failed, sending somebody to chase an outage that did not
happen. The write uses `returnStatus: true`; a non-zero status records the cause
via `mcdUnstableReason` and sets UNSTABLE.

That required adding a `post { unstable }` handler to this job, which had only
`success` and `failure`. Declarative runs `success` only on SUCCESS, so without
it the UNSTABLE result would notify nobody, which is the same silence the signal
exists to end. `mcdClientPipeline` hit this exact problem in bead mjs-q4x; the
handler here is the same shape, including reading `env.UNSTABLE_REASON` rather
than a build phase.

## Consequences

**The public Steam branch has no completion event, and this ADR does not give it
one.** For `STEAM_BRANCH=default` the build goes live only when a human flips it
in Steamworks, and no pipeline observes that. So the hard-block case (case 3 of
the mc-cdjn state machine) cannot be driven automatically for players on the
public branch: they can be nudged only once a beta upload of the same build has
written a signal, which is not the same population. Closing this needs either
automating the Set Live or instrumenting it, and that is its own bead, not
something to paper over here. Flagged to the mayor 2026-08-24.

**The signal is per Steam branch, so a proxy pointed at the wrong file nudges the
wrong players.** The mapping is now an operator's explicit choice, which is the
intent, but it is one more thing to get right at deploy time. The proxy treats a
missing file as "feature off" and says nothing, so the failure mode of getting it
wrong by omission is silence rather than a wrong prompt.

**Nothing about existing uploads changes.** The new stage is additive and gated;
an upload that skips it behaves exactly as it did before this PR.

## Tests

`test/unit/test_mcd_steam_signal_stage.py`, 13 tests. The ordering guarantee is
pinned directly (`test_signal_stage_runs_after_the_upload_stage`), as is the
reasoning about the client pipeline (`test_client_pipeline_does_not_write_the_signal`),
so a future edit cannot quietly move the write back to a stage that cannot know
whether the upload succeeded.
