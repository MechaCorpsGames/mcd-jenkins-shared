"""The deploy publishes a protocol manifest, and cleanup respects a live match (mc-mhjd).

Phase 1 of protocol back-compat (design on mc-cdjn). The proxy is to route a
connecting client to a deployed server binary that speaks the client's protocol
version, instead of always spawning the newest one. Two things on the deploy
side have to be true before the proxy can do that, and this file pins both.

1. THE MAP HAS TO EXIST ON DISK. The proxy must answer "which protocol does
   versions/v0.2.51/MCDServer speak?" without executing the binary to ask it.
   Nothing recorded that: the pipeline knew PROTOCOL_VERSION at build time and
   dropped it into an archived manifest.json that never reached the deploy host
   alongside the versioned binaries. The deploy now writes protocol.txt next to
   each binary it ships.

2. CLEANUP HAS TO STOP DELETING VERSIONS THAT ARE STILL SERVING. 'Cleanup Old
   Versions' kept the newest five and deleted the rest. That was safe only
   while exactly one version was ever in use. Once the proxy routes old clients
   to old binaries, a version past the newest five can have a match running on
   it, and the cleanup would delete the binary out from under the players in
   it. That is the correctness fix here; keep-5 stays as a safety cap.

THE LEASE IS A CROSS-BEAD CONTRACT. The proxy (mc-epfh) writes the marker and
this pipeline reads it, in two different repos and two different languages,
with nothing but a string to hold them together. Both beads independently
proposed `<version-dir>/.in-use` and mc-mhjd asked for the exact path to be
agreed and recorded; the pinning test at the bottom is that record. If someone
renames it on one side, that test fails rather than the guard silently never
matching and a live match getting deleted again.

WHY A LEASE AND NOT A FLAG. A plain flag file is unsafe in both directions. If
the proxy dies mid-match the flag stays, and since versions only accumulate,
that pins a binary on the deploy host forever. So the marker expires: the proxy
refreshes it while a match is live and cleanup ignores one that has gone stale.

WHAT THESE TESTS ACTUALLY RUN. The retention tests execute the real cleanup
shell body against a temporary directory tree rather than matching text at it,
because the thing that can be wrong here is the algorithm, not the spelling. A
regex asserting the body contains ".in-use" would have passed just as happily
on a guard that never fired. The bodies are POSIX sh: the deploy host's /bin/sh
may be dash, so they are run with /bin/sh here too.

No live Jenkins required. Run with:
    pytest test/unit/test_mcd_protocol_manifest_and_lease.py
"""

from __future__ import annotations

import os
import re
import subprocess
import time
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"
_SERVER_SRC = _VARS / "mcdServerPipeline.groovy"

# The lease filename, shared verbatim with the proxy (mc-epfh) via mc-cdjn.
_LEASE_NAME = ".in-use"

# Stand-ins for the Groovy config the stages interpolate.
_FAKE_ENVIRONMENT = "test-env"


def _src() -> str:
    if not _SERVER_SRC.exists():
        pytest.fail(
            f"{_SERVER_SRC} not found. This test must run from the "
            "mcd-jenkins-shared repo root. See mc-mhjd."
        )
    return _SERVER_SRC.read_text()


def _stage_sh_body(stage: str) -> str:
    """Return the first triple-quoted `sh` body inside the named stage."""
    src = _src()
    start = src.find(f"stage('{stage}')")
    assert start != -1, (
        f"no stage('{stage}') in mcdServerPipeline.groovy. If it was renamed, "
        "the mc-mhjd guards need to follow it."
    )
    match = re.search(r'sh\s+"""(.*?)"""', src[start:], re.S)
    assert match is not None, f"stage('{stage}') has no triple-quoted sh body"
    return match.group(1)


def _render(body: str, deploy_path: Path) -> str:
    """Resolve the Groovy interpolations the way Jenkins would, then unescape.

    Groovy substitutes `${...}` and turns `\\$` into a literal `$` before /bin/sh
    ever sees the script. Anything still wearing a `${` after the known config
    keys are replaced is an interpolation this helper does not know about, and
    running it would silently test a different script than the pipeline runs.
    """
    rendered = body.replace("${config.deployPath}", str(deploy_path))
    rendered = rendered.replace("${config.environment}", _FAKE_ENVIRONMENT)
    leftover = re.search(r"(?<!\\)\$\{config\.", rendered)
    assert leftover is None, (
        f"unhandled Groovy interpolation {leftover.group(0)!r} in the stage body; "
        "_render() must learn it or these tests run a different script than "
        "Jenkins does."
    )
    return rendered.replace("\\$", "$")


def _run_sh(
    script: str, cwd: Path, protocol_version: str | None = None
) -> subprocess.CompletedProcess[str]:
    """Run a rendered body the way Jenkins runs a shebang-less `sh` step: sh -xe.

    Jenkins exports the build's env to the shell, which is how PROTOCOL_VERSION
    reaches the deploy stage from 'Generate Server Manifest'; pass it the same
    way here.
    """
    env = dict(os.environ)
    if protocol_version is not None:
        env["PROTOCOL_VERSION"] = protocol_version
    return subprocess.run(
        ["/bin/sh", "-xe", "-c", script],
        cwd=cwd,
        capture_output=True,
        text=True,
        env=env,
    )


# ---------------------------------------------------------------------------
# 1. The protocol manifest
# ---------------------------------------------------------------------------


def _manifest_prologue() -> str:
    """The part of the deploy body that writes protocol.txt, before the rsync.

    The rsync that carries the workspace tree to the deploy host is not run
    here (rsync is not a test dependency), so this executes the manifest logic
    and `test_deploy_still_rsyncs_the_tree_that_carries_the_manifest` pins the
    copy step that delivers it.
    """
    body = _stage_sh_body("Deploy GameServer & TestClient")
    head, sep, _ = body.partition("mkdir -p ")
    assert sep, (
        "the deploy body no longer starts with the manifest writes followed by "
        "`mkdir -p`; mc-mhjd's prologue split needs updating"
    )
    return head


def _fake_workspace(tmp_path: Path, version: str = "v0.2.53") -> Path:
    """A workspace shaped like the one the deploy stage runs in."""
    workspace = tmp_path / "workspace"
    for tree, binary in (
        ("bin/versions", "MCDServer"),
        ("bin/testclient-versions", "MCDTestClient"),
    ):
        version_dir = workspace / tree / version
        version_dir.mkdir(parents=True)
        (version_dir / binary).write_text("#!/bin/sh\n")
        (workspace / tree / "latest.txt").write_text(f"{version}/{binary}\n")
    return workspace


def test_deploy_writes_a_protocol_manifest_for_the_server_version(
    tmp_path: Path,
) -> None:
    """versions/<v>/protocol.txt carries the protocol that binary speaks (mc-mhjd).

    This is the file the proxy reads to build its protocolVersion -> binary map.
    Without it the proxy has to execute each deployed binary to interrogate it,
    which mc-cdjn rejected: running five server binaries at proxy startup to ask
    them their version is a far larger operational surface than a text file
    written by the build that already knows the answer.
    """
    workspace = _fake_workspace(tmp_path)
    result = _run_sh(
        _render(_manifest_prologue(), tmp_path / "deploy"),
        cwd=workspace,
        protocol_version="52",
    )
    assert result.returncode == 0, result.stderr

    manifest = workspace / "bin/versions/v0.2.53/protocol.txt"
    assert manifest.exists(), (
        "the deploy stage did not write versions/<v>/protocol.txt, so the "
        f"proxy has no way to learn this version's protocol.\n{result.stderr}"
    )
    assert manifest.read_text().strip() == "52"


def test_deploy_writes_a_protocol_manifest_for_the_testclient_version(
    tmp_path: Path,
) -> None:
    """The bots are version-routed too, so they need the same manifest (mc-mhjd).

    mc-cdjn Phase 2 spawns the testclient matching the match's protocol for
    disconnect-takeover and practice opponents. testclient-versions/ already
    keeps five versions on disk for that; this gives them the same sidecar the
    server versions get. TestClient is built from the same tree as the server
    and therefore speaks the same PROTOCOL_VERSION.
    """
    workspace = _fake_workspace(tmp_path)
    result = _run_sh(
        _render(_manifest_prologue(), tmp_path / "deploy"),
        cwd=workspace,
        protocol_version="52",
    )
    assert result.returncode == 0, result.stderr

    manifest = workspace / "bin/testclient-versions/v0.2.53/protocol.txt"
    assert manifest.exists(), (
        "the deploy stage did not write testclient-versions/<v>/protocol.txt; "
        f"mc-cdjn Phase 2 cannot version-route bots without it.\n{result.stderr}"
    )
    assert manifest.read_text().strip() == "52"


def test_manifest_lands_beside_the_binary_named_by_latest_txt(
    tmp_path: Path,
) -> None:
    """The manifest tracks whichever version is actually being deployed (mc-mhjd).

    The version directory is derived from latest.txt, the same source 'Verify
    Build' pins the binary path against, rather than from a separately-derived
    version string. If those two ever disagree, the pipeline already fails loudly
    at Verify Build (mc-glpn) instead of shipping protocol.txt beside a binary it
    does not describe. A manifest attached to the wrong version is worse than a
    missing one: the proxy would route confidently to a binary that cannot speak
    to the client.
    """
    workspace = _fake_workspace(tmp_path, version="v0.9.77")
    result = _run_sh(
        _render(_manifest_prologue(), tmp_path / "deploy"),
        cwd=workspace,
        protocol_version="52",
    )
    assert result.returncode == 0, result.stderr
    assert (workspace / "bin/versions/v0.9.77/protocol.txt").exists()
    assert not (workspace / "bin/versions/v0.2.53").exists()


def test_manifest_write_fails_loudly_when_the_protocol_is_unknown(
    tmp_path: Path,
) -> None:
    """An empty PROTOCOL_VERSION must stop the deploy, not write a blank file.

    A protocol.txt that exists but is empty is the worst outcome available: the
    proxy would parse it, get nothing, and either crash at startup or silently
    drop the version out of its routing map, which looks exactly like a client
    that has aged out of the support window. Failing here is loud and local.
    """
    workspace = _fake_workspace(tmp_path)
    result = _run_sh(
        _render(_manifest_prologue(), tmp_path / "deploy"),
        cwd=workspace,
        protocol_version="",
    )
    assert result.returncode != 0, (
        "the deploy stage wrote a protocol manifest without knowing the "
        "protocol. See mc-mhjd."
    )
    assert not (workspace / "bin/versions/v0.2.53/protocol.txt").exists()


def test_protocol_version_is_read_without_a_silent_fallback() -> None:
    """`|| echo '1'` would hand the router a plausible-looking lie (mc-mhjd).

    'Generate Server Manifest' used to read the header with a trailing
    `|| echo '1'`, so an unreadable or moved protocol_ext.h produced the
    perfectly valid-looking protocol 1 instead of an error. That was harmless
    while the value only decorated an archived manifest.json nobody routed on.
    It stops being harmless the moment the proxy uses this number to decide
    which binary a player connects to: every old client would be matched against
    a protocol that no deployed server speaks, and the failure would surface as
    mysterious connection failures far from the pipeline that caused them.
    """
    src = _src()
    match = re.search(r"grep -oP 'PROTOCOL_VERSION[^']*'[^\n]*", src)
    assert match is not None, (
        "PROTOCOL_VERSION is no longer read from the header in "
        "mcdServerPipeline.groovy. mc-mhjd's manifest depends on it."
    )
    assert "echo" not in match.group(0), (
        f"the PROTOCOL_VERSION read has a fallback again: {match.group(0)!r}. "
        "A wrong protocol here mis-routes every back-compat connection. "
        "See mc-mhjd."
    )


def test_deploy_still_rsyncs_the_tree_that_carries_the_manifest() -> None:
    """protocol.txt reaches the host only because the whole tree is copied.

    The manifest is written into the workspace version directory rather than
    poked into the deploy path directly, so that the binary and the description
    of its protocol are delivered by one operation and cannot arrive apart. That
    only holds while the deploy copies the tree wholesale. If this stops being
    true, the manifest tests above would keep passing while nothing reached the
    proxy at all, so fail here instead.
    """
    body = _stage_sh_body("Deploy GameServer & TestClient")
    for tree in ("bin/versions/", "bin/testclient-versions/"):
        assert re.search(rf"rsync[^\n]*{re.escape(tree)}", body), (
            f"the deploy stage no longer rsyncs {tree} to the deploy path. "
            "The protocol manifest is written into that tree and rides along "
            "with it; delivering it needs revisiting. See mc-mhjd."
        )


# ---------------------------------------------------------------------------
# 2. Retention: keep-5 as a cap, the lease as the correctness fix
# ---------------------------------------------------------------------------


def _make_versions(
    root: Path,
    count: int = 8,
    leased: dict[str, float] | None = None,
) -> Path:
    """Build a versions/ tree with deterministic ordering for `ls -dt`.

    Directory mtimes are set LAST and explicitly. Creating a lease file inside a
    version directory updates that directory's mtime, which would reorder
    `ls -dt` and quietly move the leased version into the keep-5 set: the test
    would then pass without the guard doing anything at all.
    """
    leased = leased or {}
    now = time.time()
    root.mkdir(parents=True, exist_ok=True)
    names = [f"v0.2.{i}" for i in range(1, count + 1)]

    for name in names:
        version_dir = root / name
        version_dir.mkdir()
        (version_dir / "MCDServer").write_text("#!/bin/sh\n")

    for name, age_minutes in leased.items():
        lease = root / name / _LEASE_NAME
        lease.write_text("")
        stamp = now - age_minutes * 60
        os.utime(lease, (stamp, stamp))

    # Oldest name first, newest last: v0.2.8 is the most recent deploy.
    for index, name in enumerate(names):
        stamp = now - (count - index) * 3600
        os.utime(root / name, (stamp, stamp))

    return root


def _run_cleanup(
    deploy_path: Path, cwd: Path | None = None
) -> subprocess.CompletedProcess[str]:
    """Run the cleanup body from a workspace, as Jenkins does.

    The stage runs with the Jenkins workspace as its working directory and
    reaches the deploy path by absolute path, so the two are kept distinct here
    rather than running from inside the tree being pruned.
    """
    workspace = cwd or deploy_path.parent / "workspace"
    workspace.mkdir(parents=True, exist_ok=True)
    return _run_sh(
        _render(_stage_sh_body("Cleanup Old Versions"), deploy_path),
        cwd=workspace,
    )


def _surviving(root: Path) -> set[str]:
    return {p.name for p in root.iterdir() if p.is_dir()}


def test_keep_five_still_applies_to_unleased_versions(tmp_path: Path) -> None:
    """The safety cap survives the new guard (mc-mhjd).

    The in-use lease is an exception to retention, not a replacement for it. If
    adding the guard had accidentally made every version look protected, the
    deploy host would fill up with server binaries and nothing would say so
    until it ran out of disk. Eight versions in, five unleased ones come out.
    """
    versions = _make_versions(tmp_path / "versions")
    _make_versions(tmp_path / "testclient-versions")
    result = _run_cleanup(tmp_path)
    assert result.returncode == 0, result.stderr

    assert _surviving(versions) == {
        "v0.2.4",
        "v0.2.5",
        "v0.2.6",
        "v0.2.7",
        "v0.2.8",
    }, f"keep-5 no longer holds for unleased versions.\n{result.stderr}"


def test_cleanup_skips_a_version_with_a_live_lease(tmp_path: Path) -> None:
    """A match in progress is not deleted out from under its players (mc-mhjd).

    This is the whole point of the bead. v0.2.2 is old enough to be past the
    keep-5 cutoff, and under the previous `ls -dt v*/ | tail -n +6 | xargs rm -rf`
    it would have been removed while the proxy still had a match running on it.
    The players in that match would have lost their game to a deploy of an
    unrelated change.
    """
    versions = _make_versions(tmp_path / "versions", leased={"v0.2.2": 0.0})
    _make_versions(tmp_path / "testclient-versions")
    result = _run_cleanup(tmp_path)
    assert result.returncode == 0, result.stderr

    survivors = _surviving(versions)
    assert "v0.2.2" in survivors, (
        "cleanup deleted a version holding a live in-use lease. A match running "
        f"on that binary would have died with it. See mc-mhjd.\n{result.stderr}"
    )
    assert survivors == {
        "v0.2.2",
        "v0.2.4",
        "v0.2.5",
        "v0.2.6",
        "v0.2.7",
        "v0.2.8",
    }, (
        "the lease should spare exactly the leased version; the other versions "
        f"past keep-5 are still due for deletion.\n{result.stderr}"
    )


def test_cleanup_reclaims_a_version_whose_lease_went_stale(tmp_path: Path) -> None:
    """A proxy that died mid-match must not pin a binary forever (mc-mhjd).

    The lease is refreshed while a match is live, so a lease that has not been
    touched for an hour means the process holding it is gone, not that a match
    has been running for an hour. Without expiry, one crashed proxy would
    permanently exempt a version from retention, and because versions only
    accumulate on this host, the disk cost grows with every subsequent deploy
    and nothing ever reports it.
    """
    versions = _make_versions(tmp_path / "versions", leased={"v0.2.2": 120.0})
    _make_versions(tmp_path / "testclient-versions")
    result = _run_cleanup(tmp_path)
    assert result.returncode == 0, result.stderr

    assert "v0.2.2" not in _surviving(versions), (
        "a lease older than the expiry still protected its version, so a "
        "crashed proxy can pin a binary on the deploy host indefinitely. "
        f"See mc-mhjd.\n{result.stderr}"
    )


def test_the_lease_guard_covers_the_testclient_tree_too(tmp_path: Path) -> None:
    """Bots are version-routed as well, so their versions can also be live.

    mc-cdjn Phase 2 spawns the testclient matching the match's protocol, which
    means a testclient-versions/ directory can be in use by a running practice
    or takeover bot exactly as a server version can. Guarding only versions/
    would leave the bot half of the same bug in place.
    """
    _make_versions(tmp_path / "versions")
    testclients = _make_versions(
        tmp_path / "testclient-versions", leased={"v0.2.1": 0.0}
    )
    result = _run_cleanup(tmp_path)
    assert result.returncode == 0, result.stderr

    assert "v0.2.1" in _surviving(testclients), (
        "cleanup deleted a testclient version holding a live lease; a bot "
        f"opponent running on it would have died. See mc-mhjd.\n{result.stderr}"
    )


def test_cleanup_survives_a_deploy_path_that_does_not_exist_yet(
    tmp_path: Path,
) -> None:
    """A missing versions directory must not redden an otherwise good deploy.

    Precisely: the previous body opened with a bare `cd ${deployPath}/versions`,
    and its `|| true` sat on the `xargs`, not on the `cd`. Under `sh -xe` a
    failed `cd` therefore aborted the whole body. That was latent rather than
    live, because 'Deploy GameServer & TestClient' runs first and its `mkdir -p`
    has already created both directories by the time cleanup runs, so the
    ordering was the only thing preventing it. The rewrite stops depending on
    that ordering and returns early instead, because retention housekeeping is
    not a reason to fail a deploy that has otherwise succeeded.
    """
    result = _run_cleanup(tmp_path / "nonexistent")
    assert result.returncode == 0, (
        "cleanup failed on an environment with no versions directory, which "
        f"would redden a successful first deploy.\n{result.stderr}"
    )


# ---------------------------------------------------------------------------
# 3. The cross-repo contract
# ---------------------------------------------------------------------------


def test_lease_filename_is_the_exact_string_the_proxy_writes() -> None:
    """Pin the one string holding two repos together (mc-mhjd / mc-epfh).

    The proxy writes this marker from Go in MCDClient; this pipeline reads it
    from shell in mcd-jenkins-shared. Nothing but agreement keeps them pointed
    at the same file, and a disagreement is silent in the dangerous direction:
    the guard simply never matches, retention behaves exactly as it did before,
    and the first symptom is a live match deleted by a routine deploy. mc-mhjd
    asked for the agreed path to be recorded; this is that record, and renaming
    it on either side has to break this test.
    """
    body = _stage_sh_body("Cleanup Old Versions")
    assert _LEASE_NAME in body, (
        f"the cleanup guard no longer looks for {_LEASE_NAME!r}. If the marker "
        "was renamed, the proxy side (mc-epfh) and mc-cdjn must change with it, "
        "or a live match will be deleted by the next deploy."
    )
