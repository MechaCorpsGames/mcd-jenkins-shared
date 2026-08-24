"""Tests for the Integration Test stage's log capture (mc-n37x).

MCDServer-Main's integration test times out on roughly one run in three. Three
consecutive builds of the identical commit 717d7d0c went SUCCESS (#905),
FAILURE (#908), SUCCESS (#909) on the same agent.

The failure signature: both test clients exit 2 after 180s, player 0 gets
Connection_PlayerDisconnected mid-match, the 30s reconnect window never
resolves. A socket goes away; the game does not deadlock on a rule.

Nobody has been able to confirm WHY, and the reason is this stage. It wrote its
logs to /tmp, which Jenkins cannot archive, and dumped only the last 20 lines of
the proxy log on failure. The proxy closes a player connection well before the
clients hit their 180s cap, so by the time the tail is printed the evidence has
scrolled away. In #908 the proxy tail printed nothing useful at all, and the
build logs rotate after 10 builds, so the run cannot be revisited either.

The suspected cause, named here so the next failure either confirms or refutes
it rather than prompting another rerun: Player.sendToPlayer in Src/Proxy/main.go
closes a player's connection outright when its 128-deep send channel fills,
logging "player send channel full, disconnecting". A client starved of CPU on a
loaded shared agent is exactly how that channel fills, and the agent is shared:
mcdServerPipeline runs under `--network host`, and concurrent builds measurably
contend (#945 and #946 overlapped and took 48-51 minutes against 18-20 for
builds that ran alone).

This file guards the capture, not the hypothesis. Whatever the cause turns out
to be, the next failure has to leave enough behind to name it.

Run with: pytest test/unit/test_mcd_integration_test_logs.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_SERVER_SRC = _VARS / "mcdServerPipeline.groovy"

_STAGE = "Integration Test"


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-n37x."
        )
    return path.read_text()


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-n37x."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-n37x."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-n37x.")


@pytest.fixture(name="body")
def _body() -> str:
    return _stage_body(_src(_SERVER_SRC), _STAGE)


# ---------------------------------------------------------------------------
# The logs have to be somewhere Jenkins can archive from
# ---------------------------------------------------------------------------


def test_logs_are_written_to_the_workspace_not_tmp(body: str) -> None:
    """Logs land in the workspace, so archiveArtifacts can reach them (mc-n37x).

    archiveArtifacts resolves paths relative to the workspace and cannot pick up
    anything under /tmp. While the logs lived there they were unarchivable by
    construction, which is why a 1-in-3 flake has no surviving evidence.
    """
    assert "/tmp/test_proxy_" not in body, (
        "The proxy log is back under /tmp. Jenkins cannot archive /tmp, so the "
        "full log dies with the container and mc-n37x stays undiagnosable."
    )
    assert "/tmp/test_client1_" not in body and "/tmp/test_client2_" not in body, (
        "A client log is back under /tmp and can no longer be archived. "
        "See mc-n37x."
    )
    assert "LOG_DIR=integration-logs" in body, (
        "The stage must write its logs into a workspace directory. See mc-n37x."
    )


def test_all_three_logs_go_to_the_log_dir(body: str) -> None:
    """Proxy and BOTH clients are captured (mc-n37x).

    The disconnect has two sides. The client log says a peer vanished; only the
    proxy log says why it was dropped. Capturing one without the others leaves
    the same gap in a new place.
    """
    for redirect in ('"$LOG_DIR/proxy.log"',
                     '"$LOG_DIR/client1.log"',
                     '"$LOG_DIR/client2.log"'):
        assert redirect in body, (
            f"{redirect} is not written in the Integration Test stage. All "
            "three logs are needed to attribute the disconnect. See mc-n37x."
        )


# ---------------------------------------------------------------------------
# Failures have to leave the evidence behind
# ---------------------------------------------------------------------------


def test_failure_archives_the_full_logs(body: str) -> None:
    """A failed run archives the logs as artifacts (mc-n37x)."""
    assert "archiveArtifacts" in body, (
        "The Integration Test stage no longer archives its logs on failure. "
        "The 20-line console tails are not enough: the proxy drops the player "
        "long before the 180s cap, and build logs rotate after 10 builds. "
        "See mc-n37x."
    )
    assert "integration-logs/*.log" in body, (
        "archiveArtifacts must cover the integration logs. See mc-n37x."
    )


def test_archive_tolerates_missing_logs(body: str) -> None:
    """allowEmptyArchive, so archiving cannot mask the real failure (mc-n37x).

    The stage can fail before any log exists, e.g. the proxy never starts. With
    a strict archive that would fail the build on the ARCHIVE step and bury the
    actual error under a misleading one.
    """
    assert "allowEmptyArchive: true" in body, (
        "archiveArtifacts must set allowEmptyArchive: true so an early failure "
        "reports its own cause rather than an archiving error. See mc-n37x."
    )


def test_console_still_shows_the_tails(body: str) -> None:
    """The quick-read tails survive (mc-n37x).

    Artifacts are the deep evidence; the tails are what someone reads first.
    Replacing one with the other trades a fast triage path for a slow one.
    """
    assert 'tail -20 "$LOG_DIR/proxy.log"' in body, (
        "The proxy log tail disappeared from the console output. See mc-n37x."
    )


# ---------------------------------------------------------------------------
# The next failure should confirm or refute, not prompt a rerun
# ---------------------------------------------------------------------------


def test_stage_scans_for_the_suspected_signature(body: str) -> None:
    """The run greps for the send-channel-full warning (mc-n37x).

    This is the whole point of the change. The suspected cause has an exact log
    line, so the next failure can settle the question in the console without
    anyone downloading an artifact. If the line is absent the hypothesis is
    refuted, which is worth just as much.
    """
    assert "send channel full" in body, (
        "The Integration Test stage no longer scans the proxy log for "
        "'send channel full'. That string is emitted by Player.sendToPlayer in "
        "Src/Proxy/main.go when the proxy closes a player connection because "
        "its send channel filled, and it is the suspected cause of mc-n37x. "
        "Without the scan every failure is another rerun."
    )


def test_scan_reports_absence_as_well_as_presence(body: str) -> None:
    """A clean scan says so out loud (mc-n37x).

    A grep that prints nothing when it matches nothing is indistinguishable
    from a grep that did not run. Saying "NOT confirmed" keeps the next reader
    from assuming the check was skipped.
    """
    assert "NOT confirmed" in body, (
        "The signature scan must state explicitly when it finds nothing, so a "
        "silent scan is never mistaken for a missing one. See mc-n37x."
    )
