"""Tests for the MCP Game Server Tests stage's go test timeout (mc-11nh).

Six MCD-PR-Main builds (#1616, #1617, #1619, #1620, #1621, #1622) died in this
stage after roughly 10 minutes. None of them named a failing test. Each one
ended the same way:

    ok  github.com/mechacorpsgames/mcp-game-server/artifacts/decisionlog 0.014s
    wrapper script does not seem to be touching the log file in
    /var/lib/jenkins/workspace/MCD-PR-Main@2@tmp/durable-9b5155f0
    (JENKINS-48300: ...)

That JENKINS-48300 line is a symptom, not a cause. It means the step produced
no output for long enough that Jenkins' durable-task wrapper gave up. The
reason a hung Go test produces no output at all is that `go test ./...`
buffers each package's output until that package finishes, and prints packages
in the order they were listed rather than the order they complete. One stuck
package therefore silences the whole run.

Go can report this itself. Its per-binary test timeout panics with a full
goroutine dump naming the stuck test. The catch is that the default is 10
minutes, which is longer than the window Jenkins is willing to sit through, so
by default Jenkins always wins the race and the useful diagnostic never lands.
An explicit, shorter -timeout is what makes the hang self-reporting.

This file guards that the flag stays, that it stays meaningfully below the 10m
default (a -timeout of 10m or more is the same as not passing it), and that
both pipelines carrying this stage keep it. The two stage bodies are
copy-pasted siblings and have drifted before.

Run with: pytest test/unit/test_mcp_game_server_stage_timeout.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_PR_SRC = _VARS / "mcdPRValidationPipeline.groovy"
_SERVER_SRC = _VARS / "mcdServerPipeline.groovy"

_STAGE = "MCP Game Server Tests"

# Go's built-in default. A -timeout at or above this changes nothing.
_GO_DEFAULT_TIMEOUT_MINUTES = 10

_SOURCES = pytest.mark.parametrize(
    "src_path",
    [_PR_SRC, _SERVER_SRC],
    ids=["mcdPRValidationPipeline", "mcdServerPipeline"],
)


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-11nh."
        )
    return path.read_text()


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-11nh."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-11nh."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-11nh.")


def _go_test_lines(body: str) -> list[str]:
    """Every `go test` invocation in a stage body, stripped."""
    return [
        line.strip()
        for line in body.splitlines()
        if re.search(r"\bgo test\b", line)
        and not line.strip().startswith("//")
        and not line.strip().startswith("#")
    ]


def _timeout_minutes(line: str) -> float:
    """Parse the -timeout value off one go test line, in minutes."""
    match = re.search(r"-timeout[= ](\d+(?:\.\d+)?)(ms|s|m|h)\b", line)
    assert match is not None, f"no parseable -timeout in: {line}"
    value, unit = float(match.group(1)), match.group(2)
    return value * {"ms": 1 / 60000, "s": 1 / 60, "m": 1, "h": 60}[unit]


# ---------------------------------------------------------------------------
# The flag has to be there at all
# ---------------------------------------------------------------------------


@_SOURCES
def test_stage_exists(src_path: Path) -> None:
    """The stage is present at all (mc-11nh)."""
    assert f"stage('{_STAGE}')" in _src(src_path), (
        f"{src_path.name} lost the {_STAGE!r} stage. See mc-11nh."
    )


@_SOURCES
def test_stage_runs_go_test(src_path: Path) -> None:
    """Guard the guard: the assertions below are vacuous without this."""
    body = _stage_body(_src(src_path), _STAGE)
    assert _go_test_lines(body), (
        f"{_STAGE} in {src_path.name} runs no `go test` at all, so every "
        "timeout assertion in this file would pass by having nothing to "
        "check. See mc-11nh."
    )


@_SOURCES
def test_every_go_test_carries_an_explicit_timeout(src_path: Path) -> None:
    """Both the unit run and the integration run need it (mc-11nh).

    The unit run is the one that hung in all six builds, but the integration
    run boots the MCP binary against a FakeProxy and is the likelier of the
    two to block on a socket. Neither is allowed to fall back to the default.
    """
    body = _stage_body(_src(src_path), _STAGE)
    for line in _go_test_lines(body):
        assert re.search(r"-timeout[= ]", line), (
            f"{_STAGE} in {src_path.name} runs a `go test` with no explicit "
            f"-timeout:\n    {line}\n"
            "Without it the binary falls back to Go's 10m default, which is "
            "longer than Jenkins will wait in silence. Jenkins then kills the "
            "step on JENKINS-48300 and the goroutine dump that would have "
            "named the stuck test is never printed. That is exactly how six "
            "builds failed without producing a single diagnostic. See "
            "mc-11nh."
        )


@_SOURCES
def test_timeout_is_below_the_go_default(src_path: Path) -> None:
    """A -timeout of 10m or more is decoration, not a control (mc-11nh)."""
    body = _stage_body(_src(src_path), _STAGE)
    for line in _go_test_lines(body):
        minutes = _timeout_minutes(line)
        assert minutes < _GO_DEFAULT_TIMEOUT_MINUTES, (
            f"{_STAGE} in {src_path.name} passes -timeout {minutes:g}m, which "
            f"is not below Go's {_GO_DEFAULT_TIMEOUT_MINUTES}m default:\n"
            f"    {line}\n"
            "Passing the default explicitly restores the original bug while "
            "looking like a fix. The whole point is for Go to panic and name "
            "the stuck test BEFORE Jenkins gives up on the silent step. See "
            "mc-11nh."
        )


@_SOURCES
def test_timeout_leaves_headroom_over_a_healthy_run(src_path: Path) -> None:
    """It must not be so tight that a slow but healthy run trips it.

    Build #1633 ran this stage green in 53s, covering both invocations. The
    build host has been observed at load 29.4 as well as 0.63, so a healthy
    run needs real headroom or this control becomes a flake generator, which
    is a worse failure than the one it replaces.
    """
    body = _stage_body(_src(src_path), _STAGE)
    for line in _go_test_lines(body):
        minutes = _timeout_minutes(line)
        assert minutes >= 3, (
            f"{_STAGE} in {src_path.name} passes -timeout {minutes:g}m:\n"
            f"    {line}\n"
            "That is under 3x the 53s a healthy run of this stage took in "
            "build #1633. Too tight a timeout turns host load into a red "
            "build. See mc-11nh."
        )


# ---------------------------------------------------------------------------
# The two pipelines are copy-pasted siblings and must not drift
# ---------------------------------------------------------------------------


def test_both_pipelines_use_the_same_timeout() -> None:
    """One stage, two homes, one answer (mc-11nh).

    mcdPRValidationPipeline and mcdServerPipeline carry byte-identical copies
    of this stage body. If one is tuned and the other is not, the hang comes
    back on whichever job was forgotten, and it comes back in exactly the
    undiagnosable form this fix exists to remove.
    """
    per_pipeline = {
        path.name: sorted(
            _timeout_minutes(line)
            for line in _go_test_lines(_stage_body(_src(path), _STAGE))
        )
        for path in (_PR_SRC, _SERVER_SRC)
    }
    values = list(per_pipeline.values())
    assert values[0] == values[1], (
        "the two copies of the MCP Game Server Tests stage disagree on "
        f"-timeout: {per_pipeline}. See mc-11nh."
    )
