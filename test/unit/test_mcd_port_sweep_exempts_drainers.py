"""The port sweep is defined ONCE and every call site uses it (mc-58po4).

These are source-shape assertions and they are the weaker half of this bead's
coverage. test_mcd_port_sweep_executes.py runs the sweep for real against a fake
docker and is what proves it behaves.

But there is one property the behaviour harness CANNOT reach, and it is the one
that made this bead necessary. The harness exercises the helper in isolation. It
cannot tell whether mcdServerPipeline actually CALLS the helper, or how many
times, or whether a third inline copy of the loop is still sitting somewhere
killing drainers. The sweep was duplicated byte for byte at two call sites
before this change, and a fix applied to one of them looked complete in review
and in any grep that stopped at the first hit. So the count is asserted here.
"""

from __future__ import annotations

from pathlib import Path

_VARS = Path(__file__).parent.parent.parent / "vars"
_HELPER = _VARS / "mcdPortSweep.groovy"
_SERVER_PIPELINE = _VARS / "mcdServerPipeline.groovy"


def test_the_helper_exists():
    assert _HELPER.exists(), "vars/mcdPortSweep.groovy is missing"


def test_every_sweep_call_site_goes_through_the_helper():
    """Both of them. This is the assertion the behaviour harness cannot make."""
    body = _SERVER_PIPELINE.read_text()
    calls = body.count("mcdPortSweep(")
    assert calls == 2, (
        f"expected exactly 2 mcdPortSweep call sites in mcdServerPipeline, found {calls}. "
        "There were two byte-identical inline copies of this sweep before mc-58po4: one "
        "in the main deploy and one in the 'proxy not running, starting...' branch of the "
        "no-change path. If this count drops to 1 a call site has been reverted to inline "
        "shell and that path will SIGKILL drainers again; if it rises, a new site needs "
        "checking against the same reasoning."
    )


def test_no_inline_copy_of_the_sweep_survives():
    """An inline loop is how the duplication comes back."""
    body = _SERVER_PIPELINE.read_text()
    assert "docker ps -q --filter 'network=host'" not in body, (
        "an inline port sweep has reappeared in mcdServerPipeline. The sweep is defined "
        "once, in vars/mcdPortSweep.groovy, so that the drainer exemption cannot be "
        "present at one call site and absent at another (mc-58po4)."
    )


def test_the_exemption_is_specifically_the_drainer_role():
    helper = _HELPER.read_text()
    assert 'index .Config.Labels "mcd.role"' in helper, (
        "the sweep no longer reads the mcd.role label, so nothing is exempt and a "
        "drainer will be docker rm -f'd mid-match (mc-r15kh blocker A)"
    )
    # The helper is a Groovy GString, so shell dollars are escaped in the source.
    assert r'"\$role" = "drainer"' in helper, (
        "the exemption is no longer keyed on the drainer role"
    )


def test_the_config_cmd_match_is_preserved():
    """637a43a is the reason this match exists. Narrowing it is the regression.

    Host networking leaves .NetworkSettings.Ports empty, so a host-network
    container's port appears nowhere in its inspect output except its argv. A
    name match misses legacy containers from other compose projects, which is
    the bug 637a43a fixed. The drainer label composes alongside this match; it
    does not replace it.
    """
    helper = _HELPER.read_text()
    assert '{{join .Config.Cmd " "}}' in helper, (
        "the Config.Cmd match is gone. Under host networking it is the ONLY signal "
        "that a container holds the port; removing it reintroduces the bug "
        "mcd-jenkins-shared 637a43a fixed (mc-58po4)"
    )
    assert "637a43a" in helper, (
        "the comment citing 637a43a is gone, and it is the only thing stopping the "
        "next reader from 'simplifying' the Config.Cmd match back into a name match"
    )


def test_the_helper_refuses_to_build_a_sweep_without_its_arguments():
    """Both defaults would be silently wrong, so both must be refused.

    A missing port makes the grep match nothing, so the sweep does nothing and
    the host looks clean. A missing keepName makes the sweep remove the very
    container the deploy is about to reuse.
    """
    helper = _HELPER.read_text()
    assert "tcpPort is required" in helper
    assert "keepName is required" in helper
