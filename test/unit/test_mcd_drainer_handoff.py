"""The drainer handoff is wired into the deploy, and only where it belongs (mc-r15kh 3a).

Source-shape assertions. `test_mcd_drainer_handoff_executes.py` runs the handoff
for real against a fake docker and is what proves it behaves.

These hold the one property the behaviour harness cannot reach: whether
`mcdServerPipeline` actually CALLS the helper, and where. The harness exercises
the helper in isolation and stays green while the pipeline stops calling it
entirely, which is exactly how the port sweep's own duplication trap went
unnoticed until a mutation found it.
"""

from __future__ import annotations

import re
from pathlib import Path

_VARS = Path(__file__).parent.parent.parent / "vars"
_HELPER = _VARS / "mcdDrainerHandoff.groovy"
_PIPELINE = _VARS / "mcdServerPipeline.groovy"


def _code(path: Path) -> str:
    """The helper minus its comments.

    The header explains at length what this step REPLACES, and it names
    `docker rm -f` and `kill -s TERM` in that prose. A naive scan matches the
    explanation and reports the bug it was written to prevent. This repo already
    learned that once: see the comment-stripping in
    test_proxy_image_builds_before_teardown.py.
    """
    return "\n".join(
        line for line in path.read_text().splitlines()
        if not re.match(r"\s*//", line) and not re.match(r"\s*#", line.replace("\\$", "$"))
    )


def test_the_helper_exists():
    assert _HELPER.exists(), "vars/mcdDrainerHandoff.groovy is missing"


def test_the_handoff_runs_on_the_deploy_path_exactly_once():
    body = _PIPELINE.read_text()
    calls = body.count("mcdDrainerHandoff(")
    assert calls == 1, (
        f"expected exactly 1 mcdDrainerHandoff call site, found {calls}. There is one "
        "path that replaces a RUNNING proxy and it is the only one with anything to hand "
        "over; the 'proxy not running, starting...' branch has no live proxy by "
        "construction. A second call site means something changed about that assumption."
    )


def test_the_handoff_runs_BEFORE_the_sweep_and_the_forced_remove():
    """Order across the whole deploy body, not just inside the helper.

    The handoff renames the outgoing container so the sweep recognises it as a
    drainer and leaves it alone. Run the sweep first and it sees a container
    still called <containerName>... which it skips by name, and then
    `docker rm -f ${containerName}` destroys it with its matches. The ordering is
    what makes the rename mean anything.
    """
    body = _PIPELINE.read_text()
    handoff = body.index("mcdDrainerHandoff(")
    sweep = body.index("mcdPortSweep(", handoff - 4000)
    forced = body.index("docker rm -f ${containerName}", handoff)
    assert handoff < sweep, (
        "the port sweep runs BEFORE the handoff, so the outgoing container has not been "
        "renamed yet and the sweep's drainer exemption cannot recognise it"
    )
    assert handoff < forced, (
        "docker rm -f ${containerName} runs BEFORE the handoff, so the live proxy is "
        "destroyed before it is ever asked to drain. That is the bug this step removes."
    )


def test_the_handoff_stops_drainers_rather_than_forcing_them():
    """`docker rm -f` on a drainer is the whole bug, one level up."""
    helper = _code(_HELPER)
    assert "docker stop" in helper, "the stale-drainer bound no longer stops anything"
    assert "docker rm -f" not in helper, (
        "the handoff force-removes a container. That is a SIGKILL with no grace period, "
        "and it is what this entire step exists to stop doing to a proxy with matches on it."
    )


def test_the_signal_is_sigusr1():
    helper = _code(_HELPER)
    assert "kill -s USR1" in helper, (
        "the handoff no longer sends SIGUSR1, so nothing invokes the hot-swap and the "
        "whole step is inert. That was the state mc-r15kh's title describes."
    )
    assert "kill -s TERM" not in helper, (
        "SIGTERM is the HARD STOP: it ends the matches this exists to protect"
    )


def test_the_restart_policy_is_cleared():
    helper = _code(_HELPER)
    assert "--restart=no" in helper, (
        "the drainer keeps restart: unless-stopped, so a SIGKILL would bring it back from "
        "the OLD image where it can bind the ports before the replacement and serve the "
        "previous build to real players"
    )


def test_the_helper_refuses_to_build_a_handoff_without_its_arguments():
    helper = _HELPER.read_text()
    assert "containerName is required" in helper
    assert "buildNumber is required" in helper
