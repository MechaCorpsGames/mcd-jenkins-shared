"""A deploy pointer must move forwards only, and never race (bead mc-ehn1).

WHAT WENT WRONG, MEASURED, 2026-08-27
-------------------------------------
mcdServerPipeline carried no `disableConcurrentBuilds()`. Five MCDServer-Main
builds started inside 60 seconds (#1006-#1010, 03:46-03:47Z) and three of them
ran the full pipeline CONCURRENTLY, in workspaces MCDServer-Main, @2 and @3.

Each build published its pointer by letting `latest.txt` ride along inside the
deploy rsync, so the pointer belonged to whichever rsync finished last:

    #1008  built 066852d43 (NEWEST)  ->  wrote latest.txt 21:06:20.209 PDT
    #1006  built ef5b60f06 (OLDEST)  ->  wrote latest.txt 21:06:20.757 PDT

#1006 won by 548 milliseconds while being two commits behind, so
dev.mechacorpsgames.com served stale code until a human repointed it by hand.
The same overlap also removed #1006's proxy container between compose resolving
it and compose recreating it, so a deploy that WORKED reported FAILURE and took
'Cleanup Old Versions' with it.

WHY A LOCK ALONE IS NOT THE FIX
-------------------------------
Serializing the write does not order it. Under a plain mutex #1006 still takes
its turn second and still overwrites #1008, just politely. The write itself has
to refuse to go backwards, which is why the behavioural tests below matter more
than the structural ones: text-matching a `flock` proves the word is present,
not that an older build loses.

Keyed on BUILD_NUMBER rather than the version string, because Jenkins build
numbers within a job increase monotonically and are never reused. A manual
re-run therefore always carries a higher number and is never blocked, which
keeps the operator case mcdRedundantBuild deliberately exempts working here too.

WHAT THIS FILE DOES NOT CLAIM
-----------------------------
Nothing here executes Jenkins, and nothing here compiles Groovy (there is no
groovy, groovyc or java on the build box, so the pytest suite is the only gate
this repo has). The behavioural tests render the shell the way Groovy would and
run THAT, which is evidence about the script's logic and about flock, not about
the pipeline as Jenkins assembles it.

Run with: pytest test/unit/test_deploy_pointer_is_serialized_and_monotonic.py
"""

from __future__ import annotations

import re
import subprocess
import textwrap
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"
_SERVER = _VARS / "mcdServerPipeline.groovy"
_PROMOTE = _VARS / "mcdPromotePipeline.groovy"
_HELPER = _VARS / "mcdDeployLock.groovy"

# Pipelines that publish into a deploy path another job also touches. Both must
# take the lock; a lock only one party holds excludes nobody.
_LOCKING_PIPELINES = ("mcdServerPipeline.groovy", "mcdPromotePipeline.groovy")

_OPTIONS_OPEN = re.compile(r"^options\s*\{")
_GUARD_CALL = re.compile(r"^disableConcurrentBuilds\s*\(")


# --------------------------------------------------------------------------
# source helpers
# --------------------------------------------------------------------------
def _strip_comments(src: str) -> list[str]:
    """Drop whole-line `//` comments and `/* */` blocks, preserving line count.

    Prose that merely NAMES a guard must not satisfy a check for it. That is
    not a hypothetical here: mcdServerPipeline's 'Trim to Latest' comment
    described "this job's disableConcurrentBuilds()" for weeks while the
    options block had no such call, and that sentence is the reason the gap
    survived review. Same approach as
    test_sync_src_tree_serializes_builds.py::_strip_comments.
    """
    out, in_block = [], False
    for line in src.splitlines():
        stripped = line.strip()
        if in_block:
            out.append("")
            if "*/" in stripped:
                in_block = False
            continue
        if stripped.startswith("/*"):
            out.append("")
            if "*/" not in stripped[2:]:
                in_block = True
            continue
        if stripped.startswith("//") or stripped.startswith("*"):
            out.append("")
            continue
        out.append(line)
    return out


def _options_body(lines: list[str]) -> list[str] | None:
    for start, raw in enumerate(lines):
        if not _OPTIONS_OPEN.match(raw.strip()):
            continue
        depth, body = 0, []
        for line in lines[start:]:
            depth += line.count("{") - line.count("}")
            body.append(line)
            if depth <= 0:
                return body
        return body
    return None


def _stage_source(src: str, stage: str) -> str:
    """The full source of one `stage('...')`, brace-counted."""
    match = re.search(r"stage\('%s'\)\s*\{" % re.escape(stage), src)
    assert match is not None, (
        f"no stage('{stage}') found. If it was renamed, the mc-ehn1 guards must "
        "follow it rather than silently stop checking."
    )
    depth, i = 0, match.end() - 1
    while i < len(src):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    return src[match.start():i]


def _lock_body(stage_src: str) -> str:
    """The shell body passed to mcdDeployLock inside a stage."""
    match = re.search(r'mcdDeployLock\([^"]*"""(.*?)"""', stage_src, re.S)
    assert match is not None, (
        "this stage no longer routes its shell through mcdDeployLock. The "
        "pointer publish and the container recreate are only serialized because "
        "they do (mc-ehn1)."
    )
    return match.group(1)


def _render(body: str, deploy_path: Path) -> str:
    """Resolve the interpolations Groovy would, then unescape, like Jenkins.

    Groovy substitutes `${...}` and turns `\\$` into a literal `$` before
    /bin/sh sees anything. Any `${` left standing afterwards is an
    interpolation this helper does not know about, and running it would test a
    different script than the pipeline runs.
    """
    rendered = body.replace("${config.deployPath}", str(deploy_path))
    rendered = rendered.replace("${config.environment}", "test-env")
    rendered = rendered.replace("${config.stagingDeployPath}", str(deploy_path))
    leftover = re.search(r"(?<!\\)\$\{(?!BUILD_NUMBER)", rendered)
    assert leftover is None, (
        f"unhandled Groovy interpolation at {rendered[leftover.start():leftover.start() + 40]!r}; "
        "_render() must learn it, or these tests run a script Jenkins never does."
    )
    return rendered.replace("\\$", "$")


def _fake_deploy_tree(tmp_path: Path, published: str | None = None) -> tuple[Path, Path]:
    """A workspace + deploy path shaped like the ones the deploy stage runs in."""
    workspace = tmp_path / "workspace"
    deploy = tmp_path / "deploy"
    for tree in ("versions", "testclient-versions"):
        (workspace / "bin" / tree).mkdir(parents=True, exist_ok=True)
        (deploy / tree).mkdir(parents=True, exist_ok=True)
    if published is not None:
        (deploy / ".published-build").write_text(published + "\n")
    return workspace, deploy


def _run_publish(workspace: Path, deploy: Path, build_number: str,
                 server: str, testclient: str) -> tuple[int, str, str]:
    """Render and run the real pointer-publish body from the pipeline."""
    (workspace / "bin/versions/latest.txt").write_text(server + "\n")
    (workspace / "bin/testclient-versions/latest.txt").write_text(testclient + "\n")

    body = _lock_body(_stage_source(_SERVER.read_text(), "Deploy GameServer & TestClient"))
    script = _render(body, deploy)
    proc = subprocess.run(
        ["sh", "-e", "-c", script],
        cwd=workspace,
        capture_output=True,
        text=True,
        env={"PATH": "/usr/bin:/bin", "BUILD_NUMBER": build_number},
    )
    return proc.returncode, proc.stdout.strip(), proc.stderr


# --------------------------------------------------------------------------
# structural: the guards exist at all
# --------------------------------------------------------------------------
@pytest.mark.parametrize("filename", _LOCKING_PIPELINES)
def test_publishing_pipeline_disables_concurrent_builds(filename: str) -> None:
    """The root fix. Without it the job races ITSELF, which is what happened."""
    body = _options_body(_strip_comments((_VARS / filename).read_text()))
    assert body is not None, f"{filename} has no options{{}} block"
    assert any(_GUARD_CALL.match(line.strip()) for line in body), (
        f"{filename} deploys out of a shared path but does not call "
        f"disableConcurrentBuilds() in its options{{}} block. On 2026-08-27 three "
        f"MCDServer-Main builds ran at once and the OLDEST published last, so dev "
        f"served two-commit-stale code for eight minutes (mc-ehn1). A comment "
        f"naming the guard is not the guard: this pipeline carried exactly such a "
        f"comment in 'Trim to Latest' the whole time it was missing."
    )


@pytest.mark.parametrize("filename", _LOCKING_PIPELINES)
def test_no_pipeline_inlines_its_own_lock_path(filename: str) -> None:
    """The lock path is single-sourced in mcdDeployLock, deliberately.

    Two call sites that inline `flock` against slightly different filenames do
    not exclude each other, every build still passes, and the race continues
    with nothing anywhere going red. That is the whole reason the helper exists
    rather than a copied idiom.
    """
    src = "\n".join(_strip_comments((_VARS / filename).read_text()))
    assert ".locks/" not in src, (
        f"{filename} names a lock file directly. Route it through "
        f"mcdDeployLock(deployPath: ...) instead, so every party to the lock "
        f"derives the same path from the same place (mc-ehn1)."
    )


def test_deploy_excludes_the_pointer_from_the_payload_rsync() -> None:
    """The pointer must not ride along inside the rsync. That WAS the race."""
    stage = _stage_source(_SERVER.read_text(), "Deploy GameServer & TestClient")
    rsyncs = [
        line.strip()
        for line in stage.splitlines()
        if line.strip().startswith("rsync") and "versions/" in line
    ]
    assert len(rsyncs) == 2, (
        f"expected the two version-tree rsyncs, found {len(rsyncs)}: {rsyncs}. "
        "If the deploy was restructured, re-scope this check rather than dropping it."
    )
    for line in rsyncs:
        assert "--exclude latest.txt" in line, (
            f"this rsync still carries latest.txt into the deploy path: {line!r}. "
            "Published that way, the pointer belongs to whichever concurrent "
            "build's rsync finishes last, which on 2026-08-27 was a build two "
            "commits behind (mc-ehn1)."
        )


def test_both_pointers_are_published_in_one_locked_step() -> None:
    """Server and testclient must move together, not in two independent steps."""
    body = _lock_body(_stage_source(_SERVER.read_text(), "Deploy GameServer & TestClient"))
    assert "versions/latest.txt" in body and "testclient-versions/latest.txt" in body, (
        "the locked publish does not move BOTH pointers. Split across two steps "
        "they can diverge, leaving the bots on a different build than the server "
        "(mc-ehn1, mayor requirement 2)."
    )


def test_promote_reverifies_the_version_the_operator_confirmed() -> None:
    """'Confirm Promote' blocks on a human; staging can move underneath it."""
    stage = _stage_source(_PROMOTE.read_text(), "Sync Binaries → Prod")
    assert "STAGING_SERVER_VERSION" in stage, (
        "'Sync Binaries → Prod' does not re-check the version the operator "
        "approved. A staging deploy landing during the confirmation gate would "
        "then be shipped to PRODUCTION while Discord announces the approved "
        "version, and the build stays green (mc-ehn1)."
    )


# --------------------------------------------------------------------------
# behavioural: the pointer actually refuses to go backwards
# --------------------------------------------------------------------------
def test_a_newer_build_publishes(tmp_path: Path) -> None:
    workspace, deploy = _fake_deploy_tree(tmp_path, published="1006")
    rc, out, err = _run_publish(workspace, deploy, "1008", "v0.2.1008/MCDServer", "v1.0.1008/MCDTestClient")

    assert rc == 0, err
    assert out == "published", f"stdout was {out!r}\n{err}"
    assert (deploy / "versions/latest.txt").read_text().strip() == "v0.2.1008/MCDServer"
    assert (deploy / "testclient-versions/latest.txt").read_text().strip() == "v1.0.1008/MCDTestClient"
    assert (deploy / ".published-build").read_text().strip() == "1008"


def test_an_older_build_is_refused_and_leaves_the_pointer_alone(tmp_path: Path) -> None:
    """THE REGRESSION TEST FOR THE INCIDENT. #1006 must not beat #1008."""
    workspace, deploy = _fake_deploy_tree(tmp_path, published="1008")
    (deploy / "versions/latest.txt").write_text("v0.2.1008/MCDServer\n")
    (deploy / "testclient-versions/latest.txt").write_text("v1.0.1008/MCDTestClient\n")

    rc, out, err = _run_publish(workspace, deploy, "1006", "v0.2.1006/MCDServer", "v1.0.1006/MCDTestClient")

    assert rc == 0, f"a refused publish must not fail the build:\n{err}"
    assert out == "refused", f"stdout was {out!r}\n{err}"
    assert (deploy / "versions/latest.txt").read_text().strip() == "v0.2.1008/MCDServer", (
        "the older build moved the pointer backwards, which is exactly the "
        "2026-08-27 production incident (mc-ehn1)."
    )
    assert (deploy / "testclient-versions/latest.txt").read_text().strip() == "v1.0.1008/MCDTestClient"
    assert (deploy / ".published-build").read_text().strip() == "1008"
    assert "REFUSING" in err, "a refusal must be loud; nobody can debug a silent skip"


def test_the_same_build_number_may_republish(tmp_path: Path) -> None:
    """A Replay repairs a bad pointer without hand-editing files, so -lt not -le."""
    workspace, deploy = _fake_deploy_tree(tmp_path, published="1008")
    rc, out, err = _run_publish(workspace, deploy, "1008", "v0.2.1008/MCDServer", "v1.0.1008/MCDTestClient")

    assert rc == 0, err
    assert out == "published", f"a Replay of the same build must republish, got {out!r}\n{err}"


def test_a_first_run_with_no_recorded_build_publishes(tmp_path: Path) -> None:
    """Fails OPEN, the same direction as mcdRedundantBuild.

    Every deploy path is in this state on the first build after this lands, and
    an unknown previous state must never mean 'silently skip the deploy'.
    """
    workspace, deploy = _fake_deploy_tree(tmp_path, published=None)
    rc, out, err = _run_publish(workspace, deploy, "1", "v0.2.1/MCDServer", "v1.0.1/MCDTestClient")

    assert rc == 0, err
    assert out == "published", f"stdout was {out!r}\n{err}"
    assert (deploy / "versions/latest.txt").read_text().strip() == "v0.2.1/MCDServer"


def test_a_corrupt_published_build_marker_fails_open(tmp_path: Path) -> None:
    workspace, deploy = _fake_deploy_tree(tmp_path, published="not-a-number")
    rc, out, err = _run_publish(workspace, deploy, "1008", "v0.2.1008/MCDServer", "v1.0.1008/MCDTestClient")

    assert rc == 0, err
    assert out == "published", f"stdout was {out!r}\n{err}"


def test_a_missing_build_number_is_refused_loudly(tmp_path: Path) -> None:
    """An unorderable pointer write is worse than no write, so this one is fatal."""
    workspace, deploy = _fake_deploy_tree(tmp_path, published="1006")
    (workspace / "bin/versions/latest.txt").write_text("v0.2.1008/MCDServer\n")
    (workspace / "bin/testclient-versions/latest.txt").write_text("v1.0.1008/MCDTestClient\n")

    body = _lock_body(_stage_source(_SERVER.read_text(), "Deploy GameServer & TestClient"))
    proc = subprocess.run(
        ["sh", "-e", "-c", _render(body, deploy)],
        cwd=workspace, capture_output=True, text=True,
        env={"PATH": "/usr/bin:/bin"},  # BUILD_NUMBER deliberately absent
    )
    assert proc.returncode != 0, "an unorderable publish must fail, not guess"
    assert "BUILD_NUMBER" in proc.stderr


def test_neither_pointer_moves_when_the_testclient_source_is_missing(tmp_path: Path) -> None:
    """Both copies land before either rename, so a bad source moves nothing."""
    workspace, deploy = _fake_deploy_tree(tmp_path, published="1000")
    (deploy / "versions/latest.txt").write_text("v0.2.1000/MCDServer\n")
    (deploy / "testclient-versions/latest.txt").write_text("v1.0.1000/MCDTestClient\n")
    (workspace / "bin/versions/latest.txt").write_text("v0.2.1008/MCDServer\n")
    # testclient latest.txt deliberately not written

    body = _lock_body(_stage_source(_SERVER.read_text(), "Deploy GameServer & TestClient"))
    proc = subprocess.run(
        ["sh", "-e", "-c", _render(body, deploy)],
        cwd=workspace, capture_output=True, text=True,
        env={"PATH": "/usr/bin:/bin", "BUILD_NUMBER": "1008"},
    )
    assert proc.returncode != 0
    assert (deploy / "versions/latest.txt").read_text().strip() == "v0.2.1000/MCDServer", (
        "the server pointer moved even though the testclient source was missing. "
        "Both copies must land before either rename (mc-ehn1)."
    )
    assert (deploy / "testclient-versions/latest.txt").read_text().strip() == "v1.0.1000/MCDTestClient"


# --------------------------------------------------------------------------
# behavioural: the lock the helper generates really excludes
# --------------------------------------------------------------------------
def _render_helper(lock_dir: Path, deploy_path: str, body: str) -> str:
    """Render mcdDeployLock's own generated script the way Groovy would."""
    src = _HELPER.read_text()
    match = re.search(r'String script = """(.*?)"""', src, re.S)
    assert match is not None, (
        "mcdDeployLock no longer builds its script in a `String script = \"\"\"...\"\"\"` "
        "block, so this test cannot render what it actually runs."
    )
    template = match.group(1)
    assert "flock 9" in template, "mcdDeployLock stopped using flock"
    rendered = template.replace("${deployPath}", deploy_path)
    rendered = rendered.replace("${body}", body)
    rendered = rendered.replace("\\$", "$")
    # The only substitution that is not a faithful render: the lock DIRECTORY is
    # moved into tmp so the test does not need /opt. The lock FILENAME, which is
    # the part that decides who excludes whom, is left exactly as generated.
    return rendered.replace("/opt/mechacorps/.locks", str(lock_dir))


def test_the_generated_lock_actually_serializes_two_holders(tmp_path: Path) -> None:
    """Prove the mutex, rather than text-matching the word `flock`.

    Two holders enter the same lock at once. Serialized, the log reads
    start/end/start/end. Unserialized, it reads start/start/end/end, which is
    the interleaving that let #1006 overwrite #1008.
    """
    lock_dir = tmp_path / "locks"
    log = tmp_path / "log"
    body = f'        echo start >> {log}\n        sleep 0.4\n        echo end >> {log}\n'
    script = _render_helper(lock_dir, "/opt/mechacorps/main", body)

    def hold() -> int:
        return subprocess.run(["sh", "-e", "-c", script], capture_output=True).returncode

    with ThreadPoolExecutor(max_workers=2) as pool:
        codes = [f.result() for f in [pool.submit(hold), pool.submit(hold)]]

    assert codes == [0, 0], f"a holder failed: {codes}"
    assert log.read_text().split() == ["start", "end", "start", "end"], (
        f"the two holders interleaved: {log.read_text().split()}. The lock is not "
        "excluding, so concurrent deploys can still overwrite each other (mc-ehn1)."
    )


def test_the_serialization_check_is_not_vacuous(tmp_path: Path) -> None:
    """Guard the guard.

    If the rendered script silently stopped locking, the test above would still
    pass whenever the two holders happened not to overlap. This runs the SAME
    two holders with the flock removed and asserts they DO interleave, which
    proves the previous test can actually fail.
    """
    lock_dir = tmp_path / "locks"
    log = tmp_path / "log"
    body = f'        echo start >> {log}\n        sleep 0.4\n        echo end >> {log}\n'
    unlocked = _render_helper(lock_dir, "/opt/mechacorps/main", body).replace("flock 9", "true")

    def hold() -> int:
        return subprocess.run(["sh", "-e", "-c", unlocked], capture_output=True).returncode

    with ThreadPoolExecutor(max_workers=2) as pool:
        [f.result() for f in [pool.submit(hold), pool.submit(hold)]]

    assert log.read_text().split() == ["start", "start", "end", "end"], (
        "without flock the two holders did NOT interleave, so this harness "
        "cannot distinguish a working lock from a missing one and the test "
        "above proves nothing. Fix the harness, not this assertion."
    )


def test_the_lock_filename_is_derived_from_the_deploy_path(tmp_path: Path) -> None:
    """Different deploy paths must not share a lock; the same path must.

    Keying on the path rather than the environment name is what makes
    MCDServer-Release-Staging and MCDServer-Release-Promote exclude each other:
    they are different jobs with different names for /opt/mechacorps/release-staging.
    """
    lock_dir = tmp_path / "locks"
    for deploy_path in ("/opt/mechacorps/main", "/opt/mechacorps/release-staging"):
        script = _render_helper(lock_dir, deploy_path, "        true\n")
        subprocess.run(["sh", "-e", "-c", script], capture_output=True, check=True)

    created = sorted(p.name for p in lock_dir.iterdir())
    assert created == [
        "deploy-opt-mechacorps-main.lock",
        "deploy-opt-mechacorps-release-staging.lock",
    ], (
        f"unexpected lock files {created}. Two different deploy paths must map to "
        "two different locks, and the same path to the same lock, or the mutex "
        "either over-serializes unrelated environments or fails to exclude the "
        "staging/promote pair it exists for (mc-ehn1)."
    )
