"""The change-base helper is EXECUTED, not just pattern-matched (mc-okhtp).

test_mcd_change_base_is_the_last_evaluated_build.py pins the SHAPE of
vars/mcdChangeBase.groovy: that the pipelines call resolve(), that NOT_BUILT sits
in the exclusion, that an ancestor check exists. Those are regex assertions over
source text, and every one of them passes against Groovy that does not compile
and against a history walk that picks the wrong build.

This file runs the other half: test/groovy/mcd_change_base_behaviour.groovy
compiles vars/mcdChangeBase.groovy and calls resolve() with a stand-in
`currentBuild` and a `sh` step wired to a real git repository, then asserts the
widening invariant on every call.

WHY IT SKIPS RATHER THAN FAILS WITHOUT GROOVY. There is no JVM on the dev boxes
and mcd-jenkins-shared has no CI, so requiring one here would turn a suite that
runs everywhere into one that runs nowhere. The skip is deliberate and it is a
real gap: a skipped run of this file proves nothing at all about the helper's
behaviour. The reason string says so, and says how to get a runtime.

Provide one of:
    * `groovy` on PATH
    * GROOVY_HOME set (the harness runs $GROOVY_HOME/bin/groovy)
    * MCD_GROOVY pointing straight at a groovy launcher
and JAVA_HOME pointing at a JRE 8+ if java is not on PATH either.

WHAT A GREEN RUN HERE STILL DOES NOT COVER. Jenkins puts pipeline code through
the CPS transform and the script security sandbox, and its Groovy dialect is
2.4-flavoured rather than the 4.x this is likely to run under. None of that is
exercised here, and neither is the live reproduction bead mc-okhtp asks for.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_HARNESS = _REPO_ROOT / "test" / "groovy" / "mcd_change_base_behaviour.groovy"

_NO_RUNTIME = (
    "no Groovy runtime found, so the behaviour of mcdChangeBase.groovy was NOT "
    "checked by this run: only its source shape was, by "
    "test_mcd_change_base_is_the_last_evaluated_build.py. Put `groovy` on PATH, "
    "or set GROOVY_HOME or MCD_GROOVY, to close that gap."
)


def _groovy() -> str | None:
    explicit = os.environ.get("MCD_GROOVY")
    if explicit and Path(explicit).exists():
        return explicit
    home = os.environ.get("GROOVY_HOME")
    if home:
        candidate = Path(home) / "bin" / "groovy"
        if candidate.exists():
            return str(candidate)
    return shutil.which("groovy")


def test_the_harness_is_present_and_names_the_incident():
    """Cheap, always runs. Stops the executed half from being quietly deleted.

    A skipped behaviour test and an absent behaviour test look identical in a
    pytest summary, and this repo has no CI to notice the difference.
    """
    assert _HARNESS.exists(), f"{_HARNESS} is missing: the executed half of mc-okhtp's coverage is gone"
    body = _HARNESS.read_text()
    assert "resolve(" in body
    assert "WIDENING INVARIANT" in body, "the safety property assertion has been removed from the harness"


@pytest.mark.skipif(_groovy() is None, reason=_NO_RUNTIME)
def test_mcd_change_base_behaves_when_executed():
    result = subprocess.run(
        [_groovy(), str(_HARNESS.relative_to(_REPO_ROOT))],
        cwd=_REPO_ROOT,
        capture_output=True,
        text=True,
        timeout=600,
    )
    output = result.stdout + result.stderr
    assert result.returncode == 0, f"the executed helper misbehaved:\n{output}"

    # A harness that ran zero cases would also exit 0, which is the failure mode
    # this line exists to rule out.
    assert "0 failed" in output, output
    ran = [line for line in output.splitlines() if line.startswith("  ok   ")]
    assert len(ran) >= 20, f"expected the full case set to run, saw {len(ran)}:\n{output}"
    assert "widening invariant asserted on" in output, output
