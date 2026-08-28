"""The proxy teardown waits for a live match before it destroys one (mc-ic6h).

GH-2688 and GH-2687 are the same incident from both sides: on 2026-08-24 a
routine MCDServer-Main deploy tore the proxy down at 22:22:33 while a match was
live, and both players of game 753ec597 filed a report within 41 seconds of each
other. The build log of #958 and the dropped player's net log agree to the
second: docker rm -f at 22:22:33 produced the client's 1006, the image build
window produced the 502, and compose up at 22:22:43 produced the 401.

TWO HALVES, AND THIS FILE PINS THE SECOND. The ordering half shipped already
(#109, ADR 2026-08-25): the image is built BEFORE the running container is
removed, which cut a full --no-cache build out of the outage. It explicitly did
not make a deploy safe for a live match, because the recreate is still a hard
cut. This is the other half: the hard cut now refuses to fire while a match is
live, up to a bound.

THE BOUND IS A HUMAN DECISION AND THE TESTS PIN IT AS ONE. Tim ruled 15 minutes,
then force, on 2026-08-27. Each alternative was rejected for a recorded reason:
5 minutes is too short for a long combat sequence, 60 equals IN_USE_LEASE_MINUTES
so a leaked lease could hold a deploy for an hour, and never-forcing lets a
single leaked lease block every deploy until a human intervenes. A test that only
checked "it waits" would pass on any of the four. test_default_bound_is_tims
_fifteen_minutes pins the number itself, because the number is the ruling.

WHAT THESE TESTS ACTUALLY RUN. They EXECUTE the real gate, sliced out of the
pipeline source and run against a temporary directory tree, in the house style of
test_mcd_protocol_manifest_and_lease.py. That file states the reason and it
applies here with more force: a regex asserting the body mentions ".in-use" would
pass just as happily on a gate that never fires, and a gate that never fires is
exactly the bug being fixed. The bodies are POSIX sh because the deploy host's
/bin/sh may be dash.

The wait and poll intervals are read from the environment with the production
values as defaults, so these tests can drive a 15 minute wait in two seconds
without changing what Jenkins runs.

No live Jenkins required. Run with:
    pytest test/unit/test_proxy_teardown_waits_for_live_match.py
"""

from __future__ import annotations

import os
import re
import subprocess
import time
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_SERVER_SRC = _REPO_ROOT / "vars" / "mcdServerPipeline.groovy"

# Shared verbatim with the proxy (mc-epfh) and with 'Cleanup Old Versions'.
_LEASE_NAME = ".in-use"

# Tim's ruling, 2026-08-27: 15 minutes, then force.
_TIMS_BOUND_SECONDS = 900


def _src() -> str:
    if not _SERVER_SRC.exists():
        pytest.fail(
            f"{_SERVER_SRC} not found. This test must run from the "
            "mcd-jenkins-shared repo root. See mc-ic6h."
        )
    return _SERVER_SRC.read_text()


def _stage_region() -> str:
    """Everything inside stage('Deploy Proxy (if changed)'), up to the next stage.

    Reasoning happens over the whole stage rather than one sh body because the
    teardown no longer lives in an `sh` step at all: mc-ehn1 moved it into a
    mcdDeployLock(deployPath: ...) call. Anchoring on the stage means that kind
    of restructure relocates the code without blinding these tests to it.
    """
    src = _src()
    start = src.find("stage('Deploy Proxy (if changed)')")
    assert start != -1, (
        "no stage('Deploy Proxy (if changed)') in mcdServerPipeline.groovy. "
        "If it was renamed, the mc-ic6h gate needs to follow it."
    )
    end = src.find("stage('", start + 1)
    return src[start : end if end != -1 else len(src)]


def _gate_body() -> str:
    """Slice out just the lease gate: its first assignment to the end of its block.

    The gate is deliberately the LAST thing in the unlocked `sh` block, so the
    block's closing triple quote is its natural end. Sliced rather than run whole
    because the rest of the stage drives docker and sudo.
    """
    region = _stage_region()
    start = region.find("PROXY_LEASE_WAIT_SECONDS=")
    assert start != -1, (
        "the lease gate is gone from the Deploy Proxy stage. A deploy can once "
        "again destroy a live match: that is mc-ic6h / GH-2688 returning."
    )
    tail = region[start:]
    close = re.search(r'^\s*"""\s*$', tail, re.M)
    assert close is not None, (
        "the gate's enclosing sh block has no closing triple quote after it; "
        "the stage has been restructured and the slice needs rechecking by hand."
    )
    return tail[: close.start()]


def _render(body: str, deploy_path: Path) -> str:
    """Resolve Groovy's interpolation and escaping the way Jenkins would.

    Groovy substitutes ${...} and collapses backslash escapes (\\\\ to a single
    backslash, \\$ to a bare $) before /bin/sh ever sees the script.
    """
    rendered = body.replace("${config.deployPath}", str(deploy_path))
    leftover = re.search(r"(?<!\\)\$\{config\.", rendered)
    assert leftover is None, (
        f"unhandled Groovy interpolation {leftover.group(0)!r} in the gate; "
        "_render() must learn it or this test runs a different script than "
        "Jenkins does."
    )
    return re.sub(r"\\(.)", r"\1", rendered, flags=re.S)


def _run_gate(
    deploy_path: Path, wait: int = 2, poll: int = 1, lease_minutes: int = 60
) -> subprocess.CompletedProcess[str]:
    """Run the rendered gate the way Jenkins runs a shebang-less sh step: sh -xe."""
    env = dict(os.environ)
    env["PROXY_LEASE_WAIT_SECONDS"] = str(wait)
    env["PROXY_LEASE_POLL_SECONDS"] = str(poll)
    env["PROXY_LEASE_MINUTES"] = str(lease_minutes)
    return subprocess.run(
        ["/bin/sh", "-xe", "-c", _render(_gate_body(), deploy_path)],
        cwd=deploy_path,
        capture_output=True,
        text=True,
        env=env,
    )


def _lease_path(deploy_path: Path, name: str) -> Path:
    """Where the proxy actually writes this version's lease (mc-2acos)."""
    return deploy_path / "leases" / "versions" / name / _LEASE_NAME


def _version(deploy_path: Path, name: str, leased: bool, age_minutes: int = 0) -> Path:
    """Create versions/<name>/, optionally holding a lease of a given age.

    The lease goes to leases/versions/<name>/.in-use, NOT inside the version
    directory. versions/ is mounted read-only into the proxy on purpose, so a
    lease could never be written there; putting it there in a test would model a
    configuration that has never existed and cannot exist (mc-2acos).
    """
    version = deploy_path / "versions" / name
    version.mkdir(parents=True, exist_ok=True)
    if leased:
        lease = _lease_path(deploy_path, name)
        lease.parent.mkdir(parents=True, exist_ok=True)
        lease.touch()
        if age_minutes:
            old = time.time() - age_minutes * 60
            os.utime(lease, (old, old))
    return version


# ---------------------------------------------------------------------------
# The gate fires
# ---------------------------------------------------------------------------


def test_forces_after_the_bound_when_the_lease_never_clears(tmp_path: Path) -> None:
    """A lease held throughout must NOT block the deploy forever."""
    _version(tmp_path, "v0.2.51", leased=True)

    started = time.time()
    result = _run_gate(tmp_path, wait=2, poll=1)
    elapsed = time.time() - started

    assert result.returncode == 0, result.stderr
    assert "FORCED proxy teardown" in result.stdout, (
        "a permanently held lease did not force. That is the never-force option, "
        "which Tim rejected: one leaked lease would block every deploy.\n"
        f"stdout:\n{result.stdout}"
    )
    assert elapsed >= 2, (
        f"forced after only {elapsed:.1f}s against a 2s bound, so it did not "
        "actually wait for the match"
    )


def test_the_force_is_loud_and_names_what_it_destroyed(tmp_path: Path) -> None:
    """Tim asked for the force path to say which version, how long, and that it forced.

    A forced teardown that reads like a normal one is how this becomes invisible
    again, which is the failure mode that let the original incident sit
    unexplained.
    """
    _version(tmp_path, "v0.2.51", leased=True)

    result = _run_gate(tmp_path, wait=2, poll=1)

    assert "v0.2.51" in result.stdout, (
        "the force did not name the version dir that held the lease:\n"
        f"{result.stdout}"
    )
    assert re.search(r"FORCED proxy teardown after \d+s", result.stdout), (
        f"the force did not report how long it waited:\n{result.stdout}"
    )
    assert "LIVE MATCH IS BEING DESTROYED" in result.stdout, (
        f"the force did not say a live match was destroyed:\n{result.stdout}"
    )


def test_waits_and_does_not_force_when_the_lease_clears(tmp_path: Path) -> None:
    """The match finishing during the wait must produce a CLEAN teardown."""
    _version(tmp_path, "v0.2.51", leased=True)
    lease = _lease_path(tmp_path, "v0.2.51")

    # The match ends a second in, while the gate is polling.
    subprocess.Popen(["/bin/sh", "-c", f"sleep 1; rm -f '{lease}'"])

    result = _run_gate(tmp_path, wait=20, poll=1)

    assert "OK lease cleared" in result.stdout, (
        f"a lease that cleared mid-wait was not detected:\n{result.stdout}"
    )
    assert "FORCED" not in result.stdout, (
        "the gate forced even though the match ended in time. It is not "
        f"re-checking the lease while it waits:\n{result.stdout}"
    )


# ---------------------------------------------------------------------------
# The gate stays out of the way
# ---------------------------------------------------------------------------


def test_no_lease_means_no_wait(tmp_path: Path) -> None:
    """The common case, an idle server, must not pay for this at all."""
    _version(tmp_path, "v0.2.51", leased=False)

    started = time.time()
    result = _run_gate(tmp_path, wait=30, poll=5)
    elapsed = time.time() - started

    assert result.returncode == 0, result.stderr
    assert "PAUSE proxy teardown" not in result.stdout
    assert "FORCED" not in result.stdout
    assert elapsed < 5, f"an unleased deploy waited {elapsed:.1f}s for nothing"


def test_an_expired_lease_does_not_hold_the_deploy(tmp_path: Path) -> None:
    """A leaked lease from a dead proxy must age out, exactly as cleanup treats it.

    This is why the marker is a lease and not a flag. A proxy that dies mid-match
    leaves the file behind, and a plain flag would pin the deploy forever.
    """
    _version(tmp_path, "v0.2.51", leased=True, age_minutes=120)

    started = time.time()
    result = _run_gate(tmp_path, wait=30, poll=5, lease_minutes=60)
    elapsed = time.time() - started

    assert "PAUSE proxy teardown" not in result.stdout, (
        f"a 2-hour-old lease against a 60 minute expiry still blocked:\n{result.stdout}"
    )
    assert elapsed < 5, f"waited {elapsed:.1f}s on an expired lease"


def test_missing_versions_directory_is_not_an_error(tmp_path: Path) -> None:
    """A first deploy has no versions/ yet, and must not fail at the gate."""
    result = _run_gate(tmp_path, wait=2, poll=1)

    assert result.returncode == 0, (
        "the gate failed when versions/ did not exist, which is every first "
        f"deploy to a new environment:\nstderr:\n{result.stderr}"
    )
    assert "FORCED" not in result.stdout


# ---------------------------------------------------------------------------
# The ruling and the ordering
# ---------------------------------------------------------------------------


def test_default_bound_is_tims_fifteen_minutes() -> None:
    """The 900 is a human ruling, not a tuning constant.

    5 minutes was available and was NOT chosen, so drifting back to it is a
    silent reversal of a decision rather than a refactor.
    """
    gate = _gate_body()
    match = re.search(r"PROXY_LEASE_WAIT_SECONDS:-(\d+)", gate)
    assert match is not None, "the gate no longer carries a default wait bound"
    assert int(match.group(1)) == _TIMS_BOUND_SECONDS, (
        f"the default wait is {match.group(1)}s, not Tim's {_TIMS_BOUND_SECONDS}s "
        "(15 minutes, ruled 2026-08-27 on mc-ic6h). 5 minutes was rejected as too "
        "short for a long combat sequence and 60 as equal to IN_USE_LEASE_MINUTES. "
        "Changing this needs a new ruling, not a commit."
    )


def _code_only(body: str) -> str:
    """Drop comment lines before reasoning about ordering.

    The teardown carries a comment quoting the original incident second by
    second, and that comment contains the literal string "docker rm -f". A naive
    position search matches the QUOTED disaster instead of the command, and
    reports the gate as running after a teardown that is really just prose. This
    is the same class of mistake as citing a filename for its contents.
    """
    return "\n".join(
        line for line in body.splitlines() if not line.lstrip().startswith("#")
    )


def test_gate_runs_before_the_teardown() -> None:
    """Ordering IS the fix. A gate after the teardown protects nothing."""
    region = _code_only(_stage_region())
    gate = region.find("PROXY_LEASE_WAIT_SECONDS=")
    rm = region.find("docker rm -f")
    recreate = region.find("--force-recreate")
    assert gate != -1, "the lease gate is gone from the stage"
    assert rm != -1 and recreate != -1, "the teardown is gone from the stage"
    assert gate < rm, (
        "the lease gate now runs AFTER the container is removed. The match is "
        "already dead by then: this is mc-ic6h / GH-2688 with extra logging."
    )
    assert gate < recreate, (
        "the lease gate now runs AFTER the recreate, which is the hard cut it "
        "exists to hold back."
    )


def test_the_wait_happens_outside_the_deploy_lock() -> None:
    """The 15 minute wait must NOT be held inside mc-ehn1's cross-job mutex.

    Waiting for a live match while holding the deploy-path lock would stall every
    other job keyed on that path, a promote included, and convert a live-match
    protection into a fleet-wide stall. So the gate belongs in the unlocked block
    that precedes mcdDeployLock, not inside its body.

    This is the one property the two changes had to agree on, so it is pinned
    rather than trusted to survive the next edit of either.
    """
    region = _code_only(_stage_region())
    gate = region.find("PROXY_LEASE_WAIT_SECONDS=")
    lock = region.find("mcdDeployLock(")
    assert gate != -1, "the lease gate is gone from the stage"
    if lock == -1:
        pytest.skip("mcdDeployLock is not used in this stage")
    assert gate < lock, (
        "the lease gate has moved INSIDE (or after) mcdDeployLock. A 15 minute "
        "wait while holding the deploy-path mutex blocks every other job keyed "
        "on that path, including a promote. Wait unlocked, then lock."
    )


def test_the_recovery_branch_needs_no_gate_because_the_proxy_is_already_down() -> None:
    """The other teardown in this stage is deliberately ungated, and here is why.

    The `else` branch (nothing changed) also runs `docker rm -f`, so at a glance
    it looks like a second unguarded path to the same disaster. It is not: every
    action in it is conditioned on the proxy NOT being Up, and the proxy is what
    spawns and hosts the matches. If it is down there is no live match left to
    protect, and making that branch wait up to 15 minutes on a stale lease would
    only delay recovery of an already-dead proxy.

    That reasoning is load-bearing, so it is pinned rather than left in a comment.
    If someone removes the not-Up condition, this fails and the gate question has
    to be answered again instead of being silently inherited.
    """
    region = _stage_region()
    bodies = re.findall(r'mcdDeployLock\([^)]*?"""(.*?)"""', region, re.S)
    recovery = [b for b in bodies if "docker rm -f" in b and "--force-recreate" not in b]
    if not recovery:
        pytest.skip("no ungated recovery branch in this stage")
    for body in recovery:
        code = _code_only(body)
        rm = code.find("docker rm -f")
        guard = code.find("if ! docker ps")
        assert guard != -1 and guard < rm, (
            "a teardown in the Deploy Proxy stage is no longer conditioned on "
            "the proxy being down. It can now cut a LIVE match, and mc-ic6h's "
            "lease gate does not cover it."
        )
