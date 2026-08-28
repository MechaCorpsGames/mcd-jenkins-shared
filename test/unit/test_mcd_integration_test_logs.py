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

THE CAPTURE WAS AIMED AT THE WRONG FILE FOR ITS FIRST TWO DAYS, which is why
the assertions below now pin the --log-dir flag rather than only the redirect.
MCDProxy writes nothing to stdout or stderr: Src/Proxy/main.go:66 defaults
--log-dir to "logs", and main() at :820-838 opens <log-dir>/proxy.log and points
both log.SetOutput and slog.SetDefault at it. So `> "$LOG_DIR/proxy.log"` created
an empty file with the right name, the real log went to the workspace logs/
directory, and the signature scan printed "NOT confirmed" on every run whether
the warning had fired or not. A confident false refutation is worse than no scan
at all, so the flag is now asserted directly.

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
    assert "integration-logs/**" in body, (
        "archiveArtifacts must cover the integration logs RECURSIVELY. The "
        "GameServer the proxy spawns writes under integration-logs/<gameID>/ "
        "(server-stdout.log, proxy/proxy.log), because the proxy hands it the "
        "same --log-dir (Src/Proxy/main.go:2428). A flat '*.log' glob archives "
        "the client and proxy logs and silently drops the server's half of the "
        "disconnect. See mc-n37x."
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
    from a grep that did not run. The original wording here was the bare
    "hypothesis NOT confirmed by this run", which solved that problem and
    created a worse one: it said the same thing whether the log held a
    thousand lines with no match or nothing at all. The scan now states the
    line count it searched, so absence is attributable.
    """
    assert "NEITHER 'send channel full' NOR 'player write error' appears in" in body, (
        "The signature scan must state explicitly when it finds nothing, so a "
        "silent scan is never mistaken for a missing one. See mc-n37x."
    )


def test_a_negative_cites_the_file_it_read(body: str) -> None:
    """Every verdict names its source and its size (mc-n37x).

    This is the durable half of the fix, and the half that matters more than
    the path. The first version of this scan grepped a file that was empty by
    construction and printed "mc-n37x hypothesis NOT confirmed by this run" on
    every failing build. Nothing in that line said which file had been read or
    that it held nothing, so for two days it read as a refutation of a live
    hypothesis, on evidence that did not exist. Aiming the grep at the right
    path fixes today's bug; making the verdict cite its own source is what
    stops the next one from being believed.
    """
    assert 'scanned $PROXY_LOG ($(wc -c < "$PROXY_LOG") bytes' in body, (
        "The scan no longer reports the file it read and its size, so its "
        "verdict cannot be told apart from a verdict on an empty file. "
        "See mc-n37x."
    )


def test_an_empty_log_is_not_reported_as_a_negative(body: str) -> None:
    """An empty or missing proxy log says "scan did not run" (mc-n37x).

    A scan whose input is empty has not refuted anything, and must not be
    allowed to sound like it has. It also has to say what it DID find on disk,
    because "the file I expected is empty" plus a listing of the files that are
    not is the whole diagnosis of how the scan got pointed at the wrong path.
    """
    assert "SCAN DID NOT RUN" in body, (
        "An empty or missing proxy log is being reported as a scan result "
        "again. It is not one: it means the scan could not see its subject. "
        "See mc-n37x."
    )
    assert 'if [ ! -s "$PROXY_LOG" ]; then' in body, (
        "The scan no longer checks that its input is non-empty before drawing "
        "a conclusion from it. See mc-n37x."
    )
    assert 'find "$LOG_DIR" -type f' in body, (
        "When the proxy log is empty the scan must list the files that DO "
        "exist, which is what identifies a misdirected log path. See mc-n37x."
    )


# ---------------------------------------------------------------------------
# The scan has to read the file the proxy actually writes
# ---------------------------------------------------------------------------


def test_proxy_is_told_where_to_write_its_log(body: str) -> None:
    """The proxy is launched with --log-dir "$LOG_DIR" (mc-n37x).

    This is the assertion that would have caught the two days the capture spent
    aimed at an empty file. MCDProxy never writes to stdout or stderr:
    Src/Proxy/main.go:66 defaults --log-dir to "logs", and main() at :820-838
    opens <log-dir>/proxy.log and points both log.SetOutput and slog.SetDefault
    at it, falling back to stderr only if that open fails. Without the flag the
    shell redirect below produces a correctly named empty file while the real
    log lands in the workspace logs/ directory, so the tail, the grep and the
    archive all read nothing.
    """
    assert '--log-dir "$LOG_DIR"' in body, (
        "The Integration Test stage no longer passes --log-dir to MCDProxy. "
        "Without it the proxy writes its log to the workspace logs/ directory "
        "and every tail, grep and archive in this stage reads an empty file. "
        "See mc-n37x."
    )


def test_stdout_redirect_does_not_shadow_the_real_log(body: str) -> None:
    """The shell redirect uses its own name, not proxy.log (mc-n37x).

    Redirecting the proxy's (empty) stdout to $LOG_DIR/proxy.log truncates the
    file the proxy is about to write, so the two capture paths fight over one
    name and the empty one wins. Keep the stdout capture -- it is the only thing
    that survives a crash before logging is initialised -- but under a name of
    its own.
    """
    assert '> "$LOG_DIR/proxy-stdout.log"' in body, (
        "The proxy's stdout must be captured under its own name so it cannot "
        "truncate the log the proxy writes itself. See mc-n37x."
    )
    assert '> "$LOG_DIR/proxy.log"' not in body, (
        "The shell redirect is pointed at $LOG_DIR/proxy.log again. That is the "
        "file MCDProxy opens and writes itself, so the redirect creates an "
        "empty file with the right name and the scan reports a confident false "
        "refutation. See mc-n37x."
    )


def test_scan_names_the_write_deadline_path_too(body: str) -> None:
    """The scan distinguishes the two proxy paths with the same signature (mc-n37x).

    Two places in Src/Proxy/main.go close a live player's connection and present
    to the peer as Connection_PlayerDisconnected mid-match: sendToPlayer when the
    128-deep send channel fills ("send channel full"), and writePump when a
    socket write fails or its 10-second write deadline expires ("player write
    error"). A scan that names only the first reports "NOT confirmed" for a
    failure the second one caused, which reads as a refutation and is not one.
    """
    assert "player write error" in body, (
        "The disconnect scan no longer distinguishes writePump's write-deadline "
        "path from sendToPlayer's send-channel path. Both produce the same "
        "outward signature, so a scan for one of them cannot refute the other. "
        "See mc-n37x."
    )


def test_console_shows_the_game_server_side(body: str) -> None:
    """The spawned GameServer's logs are tailed too (mc-n37x).

    The proxy passes the same --log-dir to every GameServer it starts
    (Src/Proxy/main.go:2428), so the server's account of the match lands one
    level down in <gameID>/. A non-recursive listing of the log directory never
    sees it, and the server is the only party that knows what it had just sent
    when the socket went away.
    """
    assert 'find "$LOG_DIR" -mindepth 2' in body, (
        "The stage no longer tails the GameServer logs nested under $LOG_DIR. "
        "See mc-n37x."
    )


# ---------------------------------------------------------------------------
# MCD-PR-Main runs the same test and is the only job that can be re-fired
# ---------------------------------------------------------------------------

_PR_SRC = _VARS / "mcdPRValidationPipeline.groovy"


@pytest.fixture(name="pr_body")
def _pr_body() -> str:
    return _stage_body(_src(_PR_SRC), _STAGE)


def test_pr_validation_writes_its_logs_to_the_workspace(pr_body: str) -> None:
    """MCD-PR-Main's integration logs are archivable (mc-n37x).

    This job is the only one that runs the integration test WITHOUT also
    deploying to development, so it is the only one a flake can be re-fired on
    safely. While its logs lived in /tmp a failure there left nothing behind
    once the console rotated.
    """
    assert "LOG_DIR=integration-logs" in pr_body, (
        "MCD-PR-Main's Integration Test must write into the workspace. "
        "See mc-n37x."
    )
    assert "/tmp/test_proxy_" not in pr_body, (
        "The proxy log is back under /tmp on MCD-PR-Main, where Jenkins cannot "
        "archive it. See mc-n37x."
    )
    assert "/tmp/test_client1_" not in pr_body and "/tmp/test_client2_" not in pr_body, (
        "A client log is back under /tmp on MCD-PR-Main. See mc-n37x."
    )


def test_pr_validation_gets_the_real_proxy_log(pr_body: str) -> None:
    """MCD-PR-Main passes --log-dir too (mc-n37x)."""
    assert '--log-dir "$LOG_DIR"' in pr_body, (
        "MCD-PR-Main's Integration Test no longer passes --log-dir to MCDProxy, "
        "so its proxy log is empty by construction. See mc-n37x."
    )


def test_pr_validation_archives_and_scans(pr_body: str) -> None:
    """A red MCD-PR-Main integration run explains itself (mc-n37x).

    Both jobs run the identical test and hit the identical flake. Giving only
    one of them the scan and the archive means half the failures still cost a
    rerun.
    """
    assert "archiveArtifacts" in pr_body and "integration-logs/**" in pr_body, (
        "MCD-PR-Main no longer archives its integration logs on failure. "
        "See mc-n37x."
    )
    assert "allowEmptyArchive: true" in pr_body, (
        "MCD-PR-Main's archive must set allowEmptyArchive: true so an early "
        "failure reports its own cause. See mc-n37x."
    )
    assert "send channel full" in pr_body and "player write error" in pr_body, (
        "MCD-PR-Main runs the same integration test as MCDServer-Main and hits "
        "the same flake, so it needs the same disconnect scan. See mc-n37x."
    )


def test_pr_validation_negative_cites_its_source_too(pr_body: str) -> None:
    """MCD-PR-Main's scan is self-citing as well (mc-n37x).

    Both jobs run the identical integration test against the identical
    binaries. A verdict that is trustworthy on one job and not the other is
    worse than no verdict, because the reader has to remember which is which.
    """
    assert 'scanned $PROXY_LOG ($(wc -c < "$PROXY_LOG") bytes' in pr_body, (
        "MCD-PR-Main's disconnect scan does not report the file it read. "
        "See mc-n37x."
    )
    assert "SCAN DID NOT RUN" in pr_body, (
        "MCD-PR-Main reports an empty proxy log as a negative result rather "
        "than as a scan that could not run. See mc-n37x."
    )
