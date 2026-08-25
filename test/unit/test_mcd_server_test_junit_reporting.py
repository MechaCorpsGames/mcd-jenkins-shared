"""The server suite's per-case results reach Jenkins (mc-ek9f).

MCDServerTest is ~2450 gtest cases, registered with ctest individually by
Src/GameServer/Test/CMakeLists.txt's gtest_discover_tests. They have always been
GATED correctly by the 'Unit Tests' stage: a failing case exits non-zero and the
shebang-less sh body runs under /bin/sh -xe. What was missing was the ability to
see WHICH case failed. Nothing wrote JUnit XML and no junit step collected any,
so a full server run published to Jenkins as "Total: 1" (measured: MCD-PR-Main
#1670, a server-only PR, reported Total: 1 / Passed: 1 while 2434 cases ran on
that same commit).

"Total: 1" is worse than no number. It reads like a green that skipped
everything, and a mayor session on 2026-08-24 nearly declined to merge PR 2666
on that basis, proceeding only after reading the stage list instead of the badge.

WHAT THIS FILE PINS, and why the second half exists.

Collecting XML is the easy half. The trap is that neither of the two guards
people reach for can tell a clean run from a suite that never ran:

  * the exit code cannot, because ctest exits 0 when nothing is registered;
  * `allowEmptyResults: false` cannot, because --output-junit still writes a
    well-formed <testsuite tests="0"/> in that case, and junit accepts a file
    that is present and valid but describes nothing.

That is a measured claim about a tool this repo does not vendor, so it is
measured here by RUNNING ctest against temp trees rather than asserted in a
comment. If a future ctest starts exiting non-zero on an empty project, or stops
writing the file, these tests fail and tell you the design comment in
mcdPRValidationPipeline is now stale and `allowEmptyResults` can be tightened.

The guard that DOES catch the no-run case lives on the producing side, in
MCDClient's Src/GameServer/build.py (`require_tests_ran`), because only a check
on the file's CONTENT sees it. Its own tests are in that repo, in
tests/test_server_test_junit_output.py.

No live Jenkins required. Run with:
    pytest test/unit/test_mcd_server_test_junit_reporting.py
"""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path
from xml.etree import ElementTree

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"
_PR_SRC = _VARS / "mcdPRValidationPipeline.groovy"

_STAGE = "Unit Tests"
_JUNIT_PATH = "test-results/server-tests.xml"


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. This test must run from the mcd-jenkins-shared "
            "repo root. See mc-ek9f."
        )
    return path.read_text()


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-ek9f."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-ek9f."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-ek9f.")


def _block(body: str, marker: str) -> str:
    """Brace-matched sub-block of `body` starting at `marker`."""
    start = body.find(marker)
    assert start != -1, f"no {marker!r} block in stage body. See mc-ek9f."
    open_brace = body.find("{", start)
    depth = 0
    for index in range(open_brace, len(body)):
        if body[index] == "{":
            depth += 1
        elif body[index] == "}":
            depth -= 1
            if depth == 0:
                return body[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker!r}. See mc-ek9f.")


# ---------------------------------------------------------------------------
# The stage still runs the suite, and now publishes it
# ---------------------------------------------------------------------------


def test_stage_still_invokes_the_suite() -> None:
    """Reporting must not have replaced running (mc-ek9f)."""
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "./build.sh --test" in body, (
        f"{_STAGE} no longer invokes the server suite. Collecting results is "
        "pointless if nothing produced them. See mc-ek9f."
    )


def test_stage_collects_the_junit_xml() -> None:
    """The results are published at the path build.py writes (mc-ek9f)."""
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "junit" in body, (
        f"{_STAGE} has no junit step, so 2434 server cases go back to "
        'publishing as "Total: 1" and a server failure is nameable only by '
        "reading the raw console log. See mc-ek9f."
    )
    assert _JUNIT_PATH in body, (
        f"{_STAGE} collects a path other than {_JUNIT_PATH!r}. That is the "
        "exact path Src/GameServer/build.py passes to ctest --output-junit; "
        "the two are a cross-repo contract and a mismatch turns collection "
        "off silently rather than loudly. See mc-ek9f."
    )


def test_collection_runs_even_when_the_suite_fails() -> None:
    """junit sits in post{always}, not steps (mc-ek9f).

    A junit step in steps{} is skipped the moment the sh step fails, which is
    precisely the run whose per-case report is the entire point of the change.
    """
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "post {" in body, (
        f"{_STAGE} has no post block, so its junit step can only be in "
        "steps{}, where a failing suite skips it. See mc-ek9f."
    )
    post = _block(body, "post {")
    always = _block(post, "always")
    assert "junit" in always, (
        "the junit step is not inside post{always}. On a red suite it would "
        "not run, so the one build you most need per-case names for is the "
        "one that publishes none. See mc-ek9f."
    )


def test_the_suite_invocation_is_not_inside_the_post_block() -> None:
    """Guards test_collection_runs_even_when_the_suite_fails from passing vacuously."""
    body = _stage_body(_src(_PR_SRC), _STAGE)
    post = _block(body, "post {")
    assert "./build.sh --test" not in post, (
        "the suite is invoked from the post block. See mc-ek9f."
    )


# ---------------------------------------------------------------------------
# The gate is the exit code, and it must stay that way
# ---------------------------------------------------------------------------


def test_the_suite_body_stays_fatal() -> None:
    """The stage's gate is /bin/sh -xe on a shebang-less body (mc-ek9f).

    Adding result collection is exactly the kind of change that tempts someone
    to wrap the run in `set +e` or `|| true` so the junit step is always
    reached. post{always} already reaches it, so there is no reason to, and
    doing it would trade a real gate for a report. The repo-wide check in
    test_sh_bodies_gate_their_failures.py covers the shebang rule; this pins
    the swallow for the one stage this bead touches.
    """
    body = _stage_body(_src(_PR_SRC), _STAGE)
    steps = _block(body, "steps")
    assert "#!" not in steps, (
        f"{_STAGE} gained a shebang. Jenkins then drops the implicit -e and "
        "only the LAST command's status is the step's status, so a failing "
        "ctest stops failing the stage. See mc-91jj and mc-ek9f."
    )
    assert "set +e" not in steps, (
        f"{_STAGE} turned errexit off. A failing server suite would report "
        "SUCCESS with a tidy per-case report attached. See mc-ek9f."
    )
    assert "|| true" not in steps, (
        f"{_STAGE} swallows the suite's exit code with `|| true`. See mc-ek9f."
    )


# ---------------------------------------------------------------------------
# The measured premise: what ctest actually does, run against real trees
# ---------------------------------------------------------------------------


def _ctest() -> str:
    found = shutil.which("ctest")
    if not found:
        pytest.skip("ctest not installed; the premise tests need the real tool")
    return found


def _run_ctest(work: Path, junit: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [_ctest(), "--output-junit", str(junit)],
        cwd=str(work),
        capture_output=True,
        text=True,
    )


def test_ctest_exits_zero_with_nothing_registered(tmp_path: Path) -> None:
    """The reason the exit code cannot be the no-run guard (mc-ek9f).

    If this ever fails, ctest has started reporting an empty project as an
    error and the design note in mcdPRValidationPipeline should be revisited.
    """
    (tmp_path / "CTestTestfile.cmake").write_text('set(CTEST_PROJECT_NAME "probe")\n')
    junit = tmp_path / "out.xml"

    result = _run_ctest(tmp_path, junit)

    assert result.returncode == 0, (
        "ctest now signals an empty project through its exit code "
        f"(rc={result.returncode}). That is better than when this was written, "
        "and it means the server stage's no-run hazard is now covered by the "
        "exit code too. Revisit the comment on the 'Unit Tests' stage and "
        "MCDClient's require_tests_ran. See mc-ek9f."
    )


def test_ctest_writes_a_valid_empty_report_with_nothing_registered(
    tmp_path: Path,
) -> None:
    """The reason allowEmptyResults cannot be the no-run guard either (mc-ek9f).

    junit's allowEmptyResults fires when there is no result FILE. ctest gives it
    one, describing nothing, so the flag never gets the chance.
    """
    (tmp_path / "CTestTestfile.cmake").write_text('set(CTEST_PROJECT_NAME "probe")\n')
    junit = tmp_path / "out.xml"

    _run_ctest(tmp_path, junit)

    assert junit.is_file(), (
        "ctest no longer writes JUnit XML for an empty project. If that holds, "
        "allowEmptyResults: false WOULD catch a no-run and the server stage "
        "could be tightened to match the GDScript stage. See mc-ek9f."
    )
    root = ElementTree.parse(junit).getroot()
    assert root.get("tests") == "0", (
        f"expected a tests=\"0\" report, got tests={root.get('tests')!r}"
    )
    assert root.findall(".//testcase") == [], (
        "an empty ctest project reported testcases, which contradicts the "
        "premise this stage's design rests on. See mc-ek9f."
    )


def test_ctest_junit_names_each_case_individually(tmp_path: Path) -> None:
    """What the change actually buys: a failure you can name (mc-ek9f).

    The acceptance criterion is that a failed server test is identifiable from
    Jenkins test reporting without opening the console log. That requires the
    XML to carry per-case names and mark the failing one, not just a count.
    """
    (tmp_path / "CTestTestfile.cmake").write_text(
        "add_test([=[alpha]=] /bin/true)\n"
        "add_test([=[beta]=] /bin/false)\n"
    )
    junit = tmp_path / "out.xml"

    result = _run_ctest(tmp_path, junit)

    assert result.returncode != 0, (
        "ctest passed a suite containing a failing test, so this tree does not "
        "exercise what it claims to."
    )
    root = ElementTree.parse(junit).getroot()
    assert root.get("tests") == "2", f"expected 2 cases, got {root.get('tests')!r}"

    cases = {case.get("name"): case for case in root.iter("testcase")}
    assert set(cases) == {"alpha", "beta"}, (
        f"per-case names missing from the report: {sorted(cases)}. Without "
        "them Jenkins can show a count and nothing else, which is the state "
        "this change exists to leave. See mc-ek9f."
    )
    assert cases["beta"].find("failure") is not None, (
        "the failing case is not marked as failed in the XML, so Jenkins would "
        "publish it as a pass. See mc-ek9f."
    )
    assert cases["alpha"].find("failure") is None, (
        "the passing case is marked failed. See mc-ek9f."
    )
