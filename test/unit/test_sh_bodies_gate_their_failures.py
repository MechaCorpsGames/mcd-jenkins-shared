"""A shebang'd sh body must decide about errexit, not inherit one (mc-91jj).

The Jenkins `sh` step runs its script with `/bin/sh -xe` ONLY when the script
has no shebang. Give the body a `#!/bin/bash` line and Jenkins executes it
directly, the implicit `-e` disappears, and the step's exit status becomes the
status of the LAST command in the body. Every earlier command is then
unguarded: it can fail, print its failure to the log, and the stage still
reports SUCCESS.

That is not hypothetical. The 'MCP Game Server Tests' stage read:

    sh '''#!/bin/bash
        set -o pipefail
        mkdir -p reports/mcp-game-server
        cd Src/MCPGameServer
        go test -v ./... 2>&1 | tee ../../reports/mcp-game-server/unit.log
        go test -v -tags=integration ./integration_test/... 2>&1 | tee ...
    '''

`set -o pipefail` without `set -e` gets the exit status of a pipeline right and
then does nothing with it. The unit run failed on protocol drift (header 51 vs
the Go constant 48) on every PR that triggered the stage, the integration run
after it exited 0, and the stage went green. MCDClient PR #2628 (build 1611)
reported "Validation passed" that way against a base carrying the drift. The
constant sat at 48 while the header went 49, 50, 51, with a stage running the
guard that would have caught each bump and throwing the verdict away.

So the check is structural, and it is deliberately about the CHOICE rather than
about `-e` specifically. A body may legitimately want errexit off: the
determinism harness turns it off on purpose so it can capture `rc=$?`, write it
to a file and `exit 0`, after which the Groovy compares the code against the
cadence's expected exit status. That body says `set +e` out loud. What this
test forbids is the third state, where a body has a shebang and never mentions
errexit at all, because a reader cannot tell whether that is a decision.

Run with: pytest test/unit/test_sh_bodies_gate_their_failures.py
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

# Any triple-quoted Groovy string, single or double quoted.
_TRIPLE_QUOTED = re.compile(r"('''|\"\"\")(.*?)\1", re.S)

# `set -e`, `set -eu`, `set -euo pipefail`, `set -eo pipefail` ... but not
# `set -o pipefail` (no `e` in the flag cluster) and not `set +e`.
_SET_E = re.compile(r"^\s*set\s+-[a-z]*e", re.M)
_SET_PLUS_E = re.compile(r"^\s*set\s+\+[a-z]*e", re.M)


def _groovy_sources() -> list[Path]:
    files = sorted(_VARS.glob("*.groovy"))
    if not files:
        pytest.fail(
            f"No Groovy sources under {_VARS}. This test must run from the "
            "mcd-jenkins-shared repo root. See mc-91jj."
        )
    return files


def _shebang_bodies() -> list[tuple[str, int, str]]:
    """Every triple-quoted body in vars/ that opens with a shebang.

    Returns (filename, 1-based line of the opening quote, body).
    """
    found = []
    for path in _groovy_sources():
        src = path.read_text()
        for match in _TRIPLE_QUOTED.finditer(src):
            body = match.group(2)
            if not body.lstrip().startswith("#!"):
                continue
            line = src[: match.start()].count("\n") + 1
            found.append((path.name, line, body))
    return found


def test_the_sweep_finds_the_shebang_bodies() -> None:
    """Guard the guard: if this finds nothing, every assertion below is vacuous."""
    bodies = _shebang_bodies()
    assert bodies, (
        "Found no shebang'd sh bodies in vars/*.groovy at all. Either the "
        "library stopped using them or this test's parser broke. Both need a "
        "human. See mc-91jj."
    )


def test_every_shebang_body_decides_about_errexit() -> None:
    """A shebang body must say `set -e` or `set +e`, never stay silent (mc-91jj)."""
    silent = [
        f"{name}:{line}"
        for name, line, body in _shebang_bodies()
        if not _SET_E.search(body) and not _SET_PLUS_E.search(body)
    ]
    assert not silent, (
        "These sh bodies carry a shebang and never mention errexit: "
        f"{', '.join(silent)}. A shebang suppresses the '-xe' Jenkins would "
        "otherwise apply, so the step's result is only the LAST command's exit "
        "status and every earlier failure is discarded. Add 'set -euo pipefail' "
        "to gate them, or 'set +e' if the body captures the status itself and "
        "the Groovy checks it. See mc-91jj."
    )


def test_pipefail_alone_never_stands_in_for_errexit() -> None:
    """`set -o pipefail` without `-e` reports a status nothing acts on (mc-91jj)."""
    offenders = []
    for name, line, body in _shebang_bodies():
        has_bare_pipefail = re.search(r"^\s*set\s+-o\s+pipefail\s*$", body, re.M)
        if has_bare_pipefail and not _SET_E.search(body):
            offenders.append(f"{name}:{line}")
    assert not offenders, (
        f"These bodies set pipefail but not errexit: {', '.join(offenders)}. "
        "pipefail only decides which status a PIPELINE reports; without -e "
        "nothing exits on it. This exact pair is what let the MCP Game Server "
        "stage discard a failing 'go test' for three protocol bumps. "
        "See mc-91jj."
    )


# ---------------------------------------------------------------------------
# The specific stage the bug was found in, in both pipelines that carry it.
# ---------------------------------------------------------------------------

_MCP_STAGE_PIPELINES = [
    "mcdPRValidationPipeline.groovy",
    "mcdServerPipeline.groovy",
]


@pytest.mark.parametrize("filename", _MCP_STAGE_PIPELINES)
def test_mcp_game_server_stage_gates_its_go_tests(filename: str) -> None:
    """Both copies of 'MCP Game Server Tests' must gate the unit run (mc-91jj).

    The stage body is duplicated verbatim between the PR pipeline and the
    server pipeline. Fixing one and not the other leaves a job still throwing
    away real test failures, so both are pinned.
    """
    path = _VARS / filename
    if not path.exists():
        pytest.fail(f"{path} not found. Run from the repo root. See mc-91jj.")
    src = path.read_text()

    assert "stage('MCP Game Server Tests')" in src, (
        f"{filename} no longer declares an 'MCP Game Server Tests' stage. If it "
        "moved or was renamed, update this test. See mc-91jj."
    )

    bodies = [
        body
        for name, _line, body in _shebang_bodies()
        if name == filename and "Src/MCPGameServer" in body
    ]
    assert len(bodies) == 1, (
        f"Expected exactly one MCPGameServer sh body in {filename}, found "
        f"{len(bodies)}. See mc-91jj."
    )
    body = bodies[0]

    assert _SET_E.search(body), (
        f"The MCP Game Server sh body in {filename} must enable errexit so a "
        "failing 'go test ./...' fails the stage. Without it the stage takes "
        "the integration run's exit status and reports SUCCESS over a red unit "
        "suite. See mc-91jj."
    )
    assert "pipefail" in body, (
        f"The MCP Game Server sh body in {filename} must keep pipefail: every "
        "go test is piped into tee, and without it the pipeline reports tee's "
        "status instead of the test's. See mc-91jj."
    )
    # Both runs must still be present; the fix is about gating them, not
    # dropping one to make the stage honest.
    assert "go test -v ./..." in body, (
        f"{filename} lost the MCP unit test run. See mc-91jj."
    )
    assert "-tags=integration" in body, (
        f"{filename} lost the MCP integration test run. See mc-91jj."
    )
