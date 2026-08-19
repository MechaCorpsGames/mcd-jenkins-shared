"""Behavioural tests for the symbol upload retry loop (mc-lg8x).

test_mcd_symbol_upload_stages.py asserts on the Groovy SOURCE. That proves the
text says the right thing, which is not the same as proving the loop runs the
right number of times and exits the right way. A source assertion would happily
pass on a loop that never terminates.

So these tests extract the shell body out of the Groovy GString, decode the
escapes the way Groovy does, and run it under bash with `sentry-cli`, `sleep`
and `verify_sentry_symbols.py` stubbed. That is the real control flow from the
real file, with only the external commands faked.

What it is NOT: a Jenkins run. Nothing here proves the stage wiring, the
`when` conditions, or that a non-zero exit reaches `currentBuild.result`. Those
are pinned by the source assertions in the sibling file and by the pipeline
itself. It is also bash rather than the agent's `/bin/sh`; every construct in
the loop is POSIX, but that is an argument, not a test.

Run with: pytest test/unit/test_symbol_upload_retry_behaviour.py
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"
_PIPELINES = (_VARS / "mcdClientPipeline.groovy", _VARS / "mcdServerPipeline.groovy")

_MARK = 'def status = sh(returnStatus: true, script: """'

# The env file the stage reads lives on the build agent only. Substituting it is
# the single edit made to the extracted text; the retry loop is untouched.
_AGENT_ENV_PATH = "/var/opt/mechacorpsgames/Src/.env.sentry"

pytestmark = pytest.mark.skipif(
    shutil.which("bash") is None, reason="needs bash to run the extracted stage body"
)


def _degroovy(text: str) -> str:
    """Decode a triple-double-quoted GString body to what `sh` receives."""
    out: list[str] = []
    index = 0
    while index < len(text):
        char = text[index]
        if char != "\\" or index + 1 >= len(text):
            out.append(char)
            index += 1
            continue
        following = text[index + 1]
        if following == "\n":
            out.append("")  # Groovy eats a backslash-newline as a line continuation
        elif following == "\\":
            out.append("\\")
        elif following == "$":
            out.append("$")
        elif following == "n":
            out.append("\\n")  # `\\n` in source is a literal \n for tr
        elif following == '"':
            out.append('"')
        else:
            out.append(char + following)
        index += 2
    return "".join(out)


def _shell_body(path: Path) -> str:
    src = path.read_text()
    start = src.index(_MARK) + len(_MARK)
    end = src.index('"""', start)
    body = _degroovy(src[start:end])
    # Groovy interpolates ${SERVER_VERSION_PATH} before sh ever sees it.
    return re.sub(r"(?<!\\)\$\{[A-Za-z_]+\}", "v0.2.880/MCDServer", body)


def _run_stage(path: Path, verify_failures: int, tmp_path: Path):
    """Run the stage body with stubs. Returns (exit code, uploads, verifies, sleeps)."""
    env_file = tmp_path / "env.sentry"
    env_file.write_text('SENTRY_TOKEN="stub-token"\n')
    body = _shell_body(path).replace(_AGENT_ENV_PATH, str(env_file))

    bindir = tmp_path / "bin"
    bindir.mkdir()
    (bindir / "sentry-cli").write_text(
        '#!/bin/sh\necho "> Uploaded 2 missing debug information files"\n'
        'echo up >> "$TALLY_DIR/uploads"\nexit 0\n'
    )
    # Stubbed so three attempts cost no wall clock.
    (bindir / "sleep").write_text('#!/bin/sh\necho slept >> "$TALLY_DIR/sleeps"\nexit 0\n')
    for stub in ("sentry-cli", "sleep"):
        (bindir / stub).chmod(0o755)

    (tmp_path / "scripts").mkdir()
    (tmp_path / "scripts" / "verify_sentry_symbols.py").write_text(
        "import os, sys\n"
        "tally = os.path.join(os.environ['TALLY_DIR'], 'verifies')\n"
        "seen = len(open(tally).readlines()) if os.path.exists(tally) else 0\n"
        "open(tally, 'a').write('v\\n')\n"
        "failures = int(os.environ['VERIFY_FAILURES'])\n"
        "print('FAIL build id is NOT registered' if seen < failures else 'OK registered')\n"
        "sys.exit(1 if seen < failures else 0)\n"
    )

    tally = tmp_path / "tally"
    tally.mkdir()
    env = {
        **os.environ,
        "PATH": f"{bindir}{os.pathsep}{os.environ['PATH']}",
        "TALLY_DIR": str(tally),
        "VERIFY_FAILURES": str(verify_failures),
    }
    result = subprocess.run(
        ["bash"], input=body, text=True, capture_output=True, cwd=tmp_path, env=env, timeout=120
    )

    def tallied(name: str) -> int:
        target = tally / name
        return len(target.read_text().splitlines()) if target.exists() else 0

    return result.returncode, tallied("uploads"), tallied("verifies"), tallied("sleeps")


@pytest.mark.parametrize("path", _PIPELINES, ids=lambda p: p.name)
def test_a_healthy_build_uploads_once_and_never_sleeps(path: Path, tmp_path: Path) -> None:
    """The retry must be free when nothing is wrong (mc-lg8x).

    Every green build runs this stage. If the loop cost a sleep or a second
    upload on the success path it would tax roughly every build on the
    controller to fix a failure that happens rarely.
    """
    code, uploads, verifies, sleeps = _run_stage(path, verify_failures=0, tmp_path=tmp_path)
    assert (code, uploads, verifies, sleeps) == (0, 1, 1, 0), (
        f"{path.name}: a passing verification must end the stage immediately. "
        f"Got exit={code} uploads={uploads} verifies={verifies} sleeps={sleeps}. See mc-lg8x."
    )


@pytest.mark.parametrize("failures", (1, 2))
@pytest.mark.parametrize("path", _PIPELINES, ids=lambda p: p.name)
def test_a_transient_processing_failure_recovers(path: Path, failures: int, tmp_path: Path) -> None:
    """The case this whole change exists for (mc-lg8x).

    MCDServer-Main #873 failed verification once and the next builds passed. A
    build that hits that must re-upload and finish green, not deploy a version
    whose symbols nothing will ever send again.
    """
    code, uploads, verifies, sleeps = _run_stage(path, verify_failures=failures, tmp_path=tmp_path)
    assert code == 0, (
        f"{path.name}: {failures} transient failure(s) then a pass must end 0, got {code}. "
        "See mc-lg8x."
    )
    assert uploads == failures + 1, (
        f"{path.name}: the retry must re-run the UPLOAD, not just the verifier. "
        f"Expected {failures + 1} uploads, got {uploads}. See mc-lg8x."
    )
    assert (verifies, sleeps) == (failures + 1, failures)


@pytest.mark.parametrize("path", _PIPELINES, ids=lambda p: p.name)
def test_a_persistent_failure_stops_at_the_ceiling(path: Path, tmp_path: Path) -> None:
    """A revoked token must report, not spin (mc-lg8x).

    This library is shared by every job on the controller, so a loop that never
    gives up would hold an executor instead of marking the build UNSTABLE. The
    non-zero exit is what the stage turns into UNSTABLE.
    """
    code, uploads, verifies, sleeps = _run_stage(path, verify_failures=99, tmp_path=tmp_path)
    assert code != 0, (
        f"{path.name}: exhausted retries must exit non-zero so the stage marks the build "
        "UNSTABLE. Exiting 0 here is the mc-lxj5 swallow in a new shape. See mc-lg8x."
    )
    assert (uploads, verifies, sleeps) == (3, 3, 2), (
        f"{path.name}: expected exactly 3 bounded attempts, got uploads={uploads} "
        f"verifies={verifies} sleeps={sleeps}. See mc-lg8x."
    )
