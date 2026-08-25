"""Tests for the latest-client-on-Steam signal stage (mc-fxt7).

The signal is two values, the newest client build on Steam and the protocol it
speaks, written to a file on the deploy host for the proxy to relay to connected
clients. A client that believes it is behind will tell the player to restart and
update.

That makes ONE failure mode much worse than the others: writing the signal when
the build is not actually downloadable. A player told to restart for a build that
is not there restarts, gets the same client back, and is told again. There is no
way for them to succeed and no way for them to tell it is our fault. Every test
here exists to keep that from becoming possible, and each one names the specific
route by which it could.

WHY THIS IS NOT IN mcdClientPipeline. Its 'Publish to Steam' stage looks like the
right home, and the original bead said so, but that stage is only

    build job: 'MCDSteam-Upload', ..., wait: false, propagate: false

which fires this job and returns. It cannot observe the upload at all, so a
signal written there would mean "an upload was requested", not "downloadable
now". test_client_pipeline_does_not_write_the_signal below pins that reasoning in
place so a future edit cannot quietly move it back.

Run with: pytest test/unit/test_mcd_steam_signal_stage.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_UPLOAD_SRC = _VARS / "mcdSteamUploadPipeline.groovy"
_CLIENT_SRC = _VARS / "mcdClientPipeline.groovy"

_SIGNAL_STAGE = "Publish Steam Signal"
_UPLOAD_STAGE = "Upload to Steam"


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-fxt7."
        )
    return path.read_text()


def _stage_start(src: str, stage_name: str) -> int:
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-fxt7."
    return start


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage.

    Brace-matched rather than "up to the next stage": Publish Steam Signal is the
    LAST stage in this pipeline, so a naive scan runs on into the post blocks and
    picks up the Discord handlers.
    """
    start = _stage_start(src, stage_name)
    open_brace = src.find("{", start)
    assert open_brace != -1, f"stage('{stage_name}') has no body. See mc-fxt7."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in stage('{stage_name}'). See mc-fxt7.")


# ---------------------------------------------------------------------------
# The signal is written only on a genuinely completed upload
# ---------------------------------------------------------------------------


def test_signal_stage_runs_after_the_upload_stage() -> None:
    """Ordering IS the guarantee, so ordering is what gets pinned (mc-fxt7).

    Declarative aborts the pipeline when a stage's sh step exits non-zero, so a
    stage positioned after 'Upload to Steam' cannot run unless steamcmd finished.
    Move this stage above the upload and that structural guarantee is gone with
    no other visible symptom: the signal would start meaning "an upload began".
    """
    src = _src(_UPLOAD_SRC)
    assert _stage_start(src, _SIGNAL_STAGE) > _stage_start(src, _UPLOAD_STAGE), (
        f"'{_SIGNAL_STAGE}' must come AFTER '{_UPLOAD_STAGE}'. Ahead of it, the "
        "signal announces an upload that has not happened, and players are told to "
        "restart for a build that is not on Steam yet. See mc-fxt7."
    )


def test_signal_is_not_written_inside_the_upload_stage() -> None:
    """The upload stage stays a single retried steamcmd call (mc-fxt7).

    Folding the write into 'Upload to Steam' would put it inside that stage's
    retry(2), so a retried upload would write the signal once per attempt, and a
    write placed before the steamcmd line would run whether or not the upload
    then worked.
    """
    body = _stage_body(_src(_UPLOAD_SRC), _UPLOAD_STAGE)
    assert "steam-signals" not in body, (
        f"the signal write moved inside '{_UPLOAD_STAGE}', where it sits within "
        "that stage's retry(2) and can run before steamcmd succeeds. Keep it in "
        f"'{_SIGNAL_STAGE}', which only runs after the upload stage passes. "
        "See mc-fxt7."
    )


def test_client_pipeline_does_not_write_the_signal() -> None:
    """The client pipeline can never know an upload finished (mc-fxt7).

    Its 'Publish to Steam' stage fires MCDSteam-Upload with wait:false and
    returns immediately. Anything it writes describes an upload that was
    REQUESTED. This is the exact trap mc-91jj was about, and the bead originally
    pointed here, so it is worth a test rather than a comment.
    """
    src = _src(_CLIENT_SRC)
    assert "steam-signals" not in src, (
        "mcdClientPipeline writes the latest-client-on-Steam signal. It triggers "
        "MCDSteam-Upload with wait:false and never learns whether the upload "
        "succeeded, so anything it writes means 'an upload started'. The write "
        f"belongs in mcdSteamUploadPipeline's '{_SIGNAL_STAGE}'. See mc-fxt7."
    )


# ---------------------------------------------------------------------------
# The three remaining ways it could announce a build nobody can download
# ---------------------------------------------------------------------------


def test_signal_stage_skips_superseded_builds() -> None:
    """A coalesced build never ran steamcmd, so it has nothing to announce."""
    body = _stage_body(_src(_UPLOAD_SRC), _SIGNAL_STAGE)
    assert "UPLOAD_SUPERSEDED" in body, (
        f"'{_SIGNAL_STAGE}' has no UPLOAD_SUPERSEDED guard. A superseded build is "
        "marked NOT_BUILT before steamcmd runs, so it would publish a signal for "
        "an upload that never happened. Every other stage in this job carries the "
        "same guard. See mc-fxt7."
    )


def test_signal_stage_skips_the_public_default_branch() -> None:
    """STEAM_BRANCH=default uploads without setting the build live (mc-fxt7).

    'Prepare Steam Content' deliberately leaves setlive empty for 'default' and
    logs "flip public manually in Steamworks". The bytes reach Steam but no
    player can download them, so this is the one upload path that completes
    successfully while nothing became available.
    """
    body = _stage_body(_src(_UPLOAD_SRC), _SIGNAL_STAGE)
    assert "STEAM_BRANCH != 'default'" in body, (
        f"'{_SIGNAL_STAGE}' does not exclude STEAM_BRANCH=default. That path "
        "uploads without setlive, so the build is NOT downloadable and the signal "
        "would be a lie for public-branch players. See mc-fxt7."
    )


def test_signal_stage_refuses_to_guess_a_missing_protocol_version() -> None:
    """An unknown protocol must stay unknown (mc-fxt7).

    protocolVersion drives the client's HARD block decision. A default here would
    make an old source build look like it speaks the current protocol, which is
    the difference between nudging a player and locking them out.
    """
    body = _stage_body(_src(_UPLOAD_SRC), _SIGNAL_STAGE)
    assert "if (!env.CLIENT_PROTOCOL_VERSION)" in body, (
        f"'{_SIGNAL_STAGE}' no longer checks CLIENT_PROTOCOL_VERSION before "
        "writing. A source build predating 'Generate Compatibility Manifest' has "
        "no protocolVersion, and substituting any value there feeds the client's "
        "hard-block decision a number nobody measured. See mc-fxt7."
    )


# ---------------------------------------------------------------------------
# The write itself
# ---------------------------------------------------------------------------


def test_signal_is_written_atomically() -> None:
    """The proxy polls this file, so it must never see a partial write."""
    body = _stage_body(_src(_UPLOAD_SRC), _SIGNAL_STAGE)
    assert ".tmp" in body and "mv " in body, (
        f"'{_SIGNAL_STAGE}' no longer writes to a temp file and renames it. The "
        "proxy re-reads this path on a timer, so a direct write hands it a "
        "truncated file. rename(2) within one directory is atomic. See mc-fxt7."
    )


def test_signal_write_uses_strict_shell_semantics() -> None:
    """A failed mkdir must not fall through to a half-built signal (mc-fxt7)."""
    body = _stage_body(_src(_UPLOAD_SRC), _SIGNAL_STAGE)
    assert "set -euo pipefail" in body, (
        f"'{_SIGNAL_STAGE}' dropped `set -euo pipefail`. Without it a failed "
        "mkdir or a failed write still reaches the `mv`, and the stage reports "
        "success over a signal that was never written. See mc-fxt7."
    )


def test_signal_write_failure_does_not_report_the_upload_as_failed() -> None:
    """The upload already succeeded by then, so UNSTABLE, not FAILURE (mc-fxt7).

    A bare sh here would turn a successful Steam upload into a red build and a
    Discord message saying the upload failed, sending somebody to chase an outage
    that did not happen. It must still be VISIBLE, though: a silent failure means
    players stop being told about updates and nobody knows.
    """
    body = _stage_body(_src(_UPLOAD_SRC), _SIGNAL_STAGE)
    assert "returnStatus: true" in body, (
        f"'{_SIGNAL_STAGE}' uses a failing sh step. The upload has already "
        "succeeded at that point, so a write failure would report 'Steam upload "
        "failed' to Discord for an upload that worked. See mc-fxt7."
    )
    assert "mcdUnstableReason" in body and "UNSTABLE" in body, (
        f"'{_SIGNAL_STAGE}' swallows a failed write. The signal not being written "
        "means players are never told this build exists, which is the whole point "
        "of the stage. Record the cause with mcdUnstableReason and mark the build "
        "UNSTABLE. See mc-fxt7."
    )


def test_unstable_builds_still_notify() -> None:
    """Declarative runs post{success} only on SUCCESS (mc-fxt7).

    Without a post{unstable} handler, marking the build UNSTABLE above tells
    nobody, which is the same silence the signal was built to end. mjs-q4x is the
    prior instance of exactly this in mcdClientPipeline.
    """
    src = _src(_UPLOAD_SRC)
    assert "unstable {" in src, (
        "mcdSteamUploadPipeline has no post{ unstable } handler, so a build marked "
        f"UNSTABLE by '{_SIGNAL_STAGE}' notifies nobody. See mc-fxt7 and mjs-q4x."
    )


def test_signal_carries_both_halves_of_the_value() -> None:
    """Build version AND protocol: the two decisions need different fields.

    Per Tim's decisions on mc-cdjn, a newer BUILD drives the gentle nudge and a
    newer PROTOCOL drives the hard block. Dropping either field silently disables
    one of the two behaviours on the client.
    """
    body = _stage_body(_src(_UPLOAD_SRC), _SIGNAL_STAGE)
    for field in ('"clientVersion"', '"protocolVersion"'):
        assert field in body, (
            f"the signal no longer carries {field}. The client needs the build "
            "version for the gentle nudge and the protocol version for the hard "
            "block; they are not interchangeable. See mc-fxt7 and mc-cdjn."
        )


def test_signal_path_is_keyed_by_steam_branch() -> None:
    """One file per Steam branch, not per deploy environment (mc-fxt7).

    MCDClient-Release uploads to the Steam 'staging' beta while pointing at
    production, so branch-to-environment is ambiguous and is not guessed in the
    pipeline. Each proxy is pointed at the file for the branch its players are on
    via --steam-signal-file, which keeps that mapping in deploy config where an
    operator can see and change it.
    """
    body = _stage_body(_src(_UPLOAD_SRC), _SIGNAL_STAGE)
    assert "${params.STEAM_BRANCH}.json" in body, (
        "the signal path is no longer keyed by Steam branch. A single shared file "
        "lets a staging upload nudge production players, and vice versa. "
        "See mc-fxt7."
    )


def test_upload_agent_can_reach_the_deploy_host_path() -> None:
    """The stage writes to /opt/mechacorps, so the agent has to mount it."""
    src = _src(_UPLOAD_SRC)
    assert "-v /opt/mechacorps:/opt/mechacorps" in src, (
        "the MCDSteam-Upload agent no longer mounts /opt/mechacorps, so "
        f"'{_SIGNAL_STAGE}' writes into the container and the file never reaches "
        "the proxy. mcdServerPipeline mounts the same path the same way. "
        "See mc-fxt7."
    )
