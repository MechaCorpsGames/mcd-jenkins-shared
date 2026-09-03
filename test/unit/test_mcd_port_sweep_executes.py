"""The port sweep is EXECUTED, not just pattern-matched (mc-58po4).

test_mcd_port_sweep_exempts_drainers.py pins the SHAPE of the change: that
vars/mcdPortSweep.groovy exists, that BOTH call sites in mcdServerPipeline use
it, that no inline copy of the loop survives. Those are regex assertions over
source text, and every one of them passes against Groovy that does not compile
and against shell whose condition is inverted so it removes only drainers.

This file runs the other half: test/groovy/mcd_port_sweep_behaviour.groovy
compiles vars/mcdPortSweep.groovy, calls it, and RUNS the shell it returns under
`sh -e` with a fake `docker` on PATH, then asserts which containers were removed.

WHY IT SKIPS RATHER THAN FAILS WITHOUT GROOVY. mcd-jenkins-shared has no CI and
the dev boxes do not all carry a JVM, so requiring one here would turn a suite
that runs everywhere into one that runs nowhere. The skip is deliberate and it is
a real gap: a skipped run of this file proves NOTHING about the sweep's
behaviour. The reason string says so, and says how to get a runtime.

Getting one takes about 90 seconds and needs no root: unpack a Temurin JRE and
the Apache Groovy 4.0.22 binary zip into a cache directory and point the
variables below at them. "There is no JVM on this box" is a false premise that
has already held up work in this repo twice.

Provide one of:
    * `groovy` on PATH
    * GROOVY_HOME set (the harness runs $GROOVY_HOME/bin/groovy)
    * MCD_GROOVY pointing straight at a groovy launcher
and JAVA_HOME pointing at a JRE 8+ if java is not on PATH either.

WHAT A GREEN RUN HERE STILL DOES NOT COVER. There is no Docker in the harness:
`docker` is a fake answering from a fixture, so this pins the sweep's logic and
its use of the docker CLI surface, not Docker's behaviour. In particular that
`docker rm -f` is a SIGKILL with no grace period is documented behaviour that
nothing here verifies. Jenkins' CPS transform and script-security sandbox are
absent too, and this runs on Groovy 4.x while Jenkins pipeline Groovy is
2.4-flavoured and stricter.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_HARNESS = _REPO_ROOT / "test" / "groovy" / "mcd_port_sweep_behaviour.groovy"

_NO_RUNTIME = (
    "no Groovy runtime found, so the behaviour of mcdPortSweep.groovy was NOT "
    "checked by this run: only its source shape was, by "
    "test_mcd_port_sweep_exempts_drainers.py. Put `groovy` on PATH, or set "
    "GROOVY_HOME or MCD_GROOVY, to close that gap. A Temurin JRE plus the Groovy "
    "4.0.22 binary zip unpack into a cache dir in about 90s with no root."
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


def test_the_harness_is_present_and_keeps_its_safety_property():
    """Cheap, always runs. Stops the executed half from being quietly deleted.

    A skipped behaviour test and an absent behaviour test look identical in a
    pytest summary, and this repo has no CI to notice the difference.
    """
    assert _HARNESS.exists(), (
        f"{_HARNESS} is missing: the executed half of mc-58po4's coverage is gone"
    )
    body = _HARNESS.read_text()
    assert "DRAINER INVARIANT VIOLATED" in body, (
        "the drainer safety property has been removed from the harness"
    )
    assert "an unlabelled container holding the port is still removed" in body, (
        "the positive control is gone; without it the suite passes for a fix that "
        "simply disables the sweep"
    )


@pytest.mark.skipif(_groovy() is None, reason=_NO_RUNTIME)
def test_the_sweep_behaves_when_executed():
    result = subprocess.run(
        [_groovy(), str(_HARNESS.relative_to(_REPO_ROOT))],
        cwd=_REPO_ROOT,
        capture_output=True,
        text=True,
        timeout=600,
    )
    output = result.stdout + result.stderr
    assert result.returncode == 0, f"the executed sweep misbehaved:\n{output}"

    # A harness that ran zero cases would also exit 0, which is the failure mode
    # these three lines exist to rule out.
    assert "0 failed" in output, output
    ran = [line for line in output.splitlines() if line.startswith("  ok   ")]
    assert len(ran) >= 11, f"expected the full case set to run, saw {len(ran)}:\n{output}"
    assert "drainer invariant asserted on" in output, output
