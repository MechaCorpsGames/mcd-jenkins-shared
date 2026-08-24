"""Tests for the ERROR-line guard on the 'GDScript Tests' stage (mc-rqgm).

A GDScript suite that fails to PARSE is not reported as failed. gdUnit builds
its suite list by loading each candidate and asking whether what came back is a
suite; a script that will not parse fails that question and is dropped. It then
contributes zero cases, the summary reads "0 errors | 0 failures", both halves
of "Executed test suites: (N/M)" are counted after that load so they still
match, and the process exits 0.

MCDClient's `make test-gdscript` has caught this since #1527 (2026-05-16), by
grepping the run's output for ERROR-class lines that the log filter did not
classify as expected. This pipeline never went through that target, so the
guard was decorative on the only check that gates a merge. On 2026-08-23
tests/test_hangar_view.gd was parse-broken on main from 06:21 and MCDClient
PR #2627 passed a full client validation over it at 18:33 in 6m56s.

Three properties, and the third is what makes the first two worth having:

1. The stage still runs the WHOLE tree with `-c`. The guard was ported into
   this body rather than handed to `make test-gdscript` precisely because that
   target is fail-fast, and a fail-fast run would both shrink the JUnit report
   and make MCDClient's own parse-break guard suite conditional on every suite
   ahead of it passing.
2. The run is piped through the branch's own tests/_log_filter.py, so which
   ERROR lines count as test-intentional stays in the repo that knows. A
   missing filter fails the stage rather than running it ungated.
3. The guard's shell is EXECUTED here, not just matched. This repo has no CI of
   its own: pytest is the only gate, so a test that merely greps for a string
   proves the string, not the behaviour.

Run with: pytest test/unit/test_mcd_gdscript_error_guard.py
No live Jenkins required. Tests parse Groovy source and run extracted shell.
"""

from __future__ import annotations

import re
import subprocess
import textwrap
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_PR_SRC = _VARS / "mcdPRValidationPipeline.groovy"

_STAGE = "GDScript Tests"


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-rqgm."
        )
    return path.read_text()


def _stage_block(src: str, stage_name: str) -> str:
    """Return the named stage including its post block, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-rqgm."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-rqgm."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-rqgm.")


def _sh_body(block: str) -> str:
    """Return the stage's single triple-quoted sh body."""
    bodies = re.findall(r"('''|\"\"\")(.*?)\1", block, re.S)
    assert len(bodies) == 1, (
        f"expected exactly one triple-quoted sh body in the {_STAGE!r} stage, "
        f"found {len(bodies)}. The tests below reason about one body. "
        "See mc-rqgm."
    )
    return bodies[0][1]


def _stage() -> str:
    return _stage_block(_src(_PR_SRC), _STAGE)


def _body() -> str:
    return _sh_body(_stage())


# ---------------------------------------------------------------------------
# 1. The run itself: whole tree, not fail-fast
# ---------------------------------------------------------------------------


def test_stage_exists() -> None:
    assert f"stage('{_STAGE}')" in _src(_PR_SRC), (
        f"mcdPRValidationPipeline lost the {_STAGE!r} stage. Nothing else in "
        "this pipeline runs the GDScript suite. See mc-rqgm."
    )


def test_stage_runs_the_whole_tests_tree() -> None:
    assert re.search(r"-a res://tests/?\s", _body()), (
        f"the {_STAGE!r} stage no longer runs the whole tree (`-a res://tests`). "
        "Narrowing the scope narrows the guard with it: a parse break in a "
        "suite outside the new scope goes back to being invisible, which is "
        "the state mc-rqgm was filed for. See mc-rqgm."
    )


def test_stage_keeps_continue_on_failure() -> None:
    """`-c` is why the guard lives here instead of in `make test-gdscript`."""
    body = _body()
    assert re.search(r"GdUnitCmdTool\.gd[^\n]*\s-c(\s|$)", body), (
        f"the {_STAGE!r} stage dropped `-c` from the gdUnit invocation. "
        "Without it gdUnit is fail-fast and stops at the first failing test, "
        "which (a) cuts the JUnit report this stage publishes down to whatever "
        "ran before that point and (b) makes MCDClient's own parse-break guard "
        "suite (tests/test_suite_discovery_guard.gd, #2642) conditional on "
        "every suite scanned ahead of it passing. `make test-gdscript` does "
        "not pass `-c`, so handing the stage to that target has this same "
        "effect. See mc-rqgm."
    )


# ---------------------------------------------------------------------------
# 2. The filter: the repo decides what is expected, and it must be present
# ---------------------------------------------------------------------------


def test_run_is_piped_through_the_repo_log_filter() -> None:
    body = _body()
    assert "tests/_log_filter.py" in body, (
        f"the {_STAGE!r} stage no longer pipes the run through "
        "tests/_log_filter.py. That file, and tests/_log_filter_patterns.txt "
        "beside it, are what decide which ERROR lines a test emits on purpose. "
        "Without the filter the guard below fires on every run, and the "
        "response to that is always to weaken the guard. See mc-rqgm."
    )
    assert re.search(r"\|\s*python3 -u tests/_log_filter\.py", body), (
        "the filter must run unbuffered (`python3 -u`). It sits between Godot "
        "and the console, so a buffered filter holds the whole run's output "
        "back and makes a working stage look hung for minutes. See mc-rqgm."
    )


def test_a_missing_filter_fails_the_stage_rather_than_running_ungated() -> None:
    body = _body()
    assert "if [ ! -f tests/_log_filter.py ]; then" in body, (
        f"the {_STAGE!r} stage no longer checks that tests/_log_filter.py is "
        "present before running. Both branches this library serves (main and "
        "release) have carried that file since 2026-05, so its absence is a "
        "broken checkout rather than a branch skew, and the stage says so "
        "instead of running the suite with no guard on it. See mc-rqgm."
    )
    guard_index = body.index("if [ ! -f tests/_log_filter.py ]; then")
    tail = body[guard_index:guard_index + 800]
    assert "exit 1" in tail, (
        "the missing-filter check no longer exits non-zero. A check that "
        "prints and continues is the defect mc-qc90 was filed for: the stage "
        "would run the suite with no guard on it and still report success. "
        "See mc-rqgm."
    )


# ---------------------------------------------------------------------------
# 3. The shell contract: errexit and pipefail
# ---------------------------------------------------------------------------


def test_body_sets_errexit_and_pipefail() -> None:
    body = _body()
    assert body.lstrip().startswith("#!/bin/bash"), (
        f"the {_STAGE!r} body needs bash: `set -o pipefail` is not POSIX and "
        "Jenkins runs a shebang-less body with /bin/sh, which is dash on the "
        "agent image. See mc-rqgm."
    )
    assert re.search(r"^\s*set -euo pipefail\s*$", body, re.M), (
        f"the {_STAGE!r} body lost `set -euo pipefail`. All three matter here. "
        "`-e` gives the guard the precondition the Makefile gives it (it runs "
        "only on a run that exited clean) and, because the shebang suppresses "
        "the `-xe` Jenkins would otherwise apply, it is the only thing gating "
        "every command before the last one (mc-91jj). `pipefail` is what makes "
        "the run's own exit status survive the pipe: without it `tee` reports "
        "0 for a Godot process that died. `-u` turns a mistyped log-path "
        "variable into a loud failure instead of a silently passing guard. "
        "See mc-rqgm."
    )


def test_guard_reads_the_log_the_run_writes() -> None:
    """One variable for the path, because a divergence passes silently."""
    body = _body()
    tee_paths = re.findall(r"\|\s*tee\s+\"?([^\s\"|]+)\"?", body)
    grep_paths = re.findall(
        r"grep -nE '\^\(ERROR\|SCRIPT ERROR\|USER ERROR\): '\s+\"?([^\s\";]+)\"?",
        body,
    )
    assert tee_paths, "no `tee` in the stage body. See mc-rqgm."
    assert grep_paths, "no ERROR-line grep in the stage body. See mc-rqgm."
    assert set(tee_paths) == set(grep_paths), (
        f"the run writes {tee_paths} and the guard reads {grep_paths}. They "
        "must be the same path, and the body uses one variable so they cannot "
        "drift: `grep` on a path that does not exist exits 2, the surrounding "
        "`if` reads that as 'no matches', and the guard then passes on every "
        "build while looking exactly like a working one. See mc-rqgm."
    )


# ---------------------------------------------------------------------------
# 4. The guard's shell, executed
# ---------------------------------------------------------------------------


def _guard_snippet(body: str) -> str:
    """Extract the `if grep ... fi` guard from the stage body, dedented."""
    start = body.find("if grep -nE")
    assert start != -1, (
        f"the {_STAGE!r} stage has no ERROR-line guard. That grep is the whole "
        "point of mc-rqgm: it is the only thing in this pipeline that notices "
        "a suite which failed to parse, because gdUnit reports such a suite as "
        "absent rather than failed and still exits 0."
    )
    end = body.find("\nfi", start)
    if end == -1:
        # The body is indented inside the Groovy string.
        match = re.search(r"^\s*fi\s*$", body[start:], re.M)
        assert match, f"unterminated `if` in the {_STAGE!r} guard. See mc-rqgm."
        end = start + match.end()
    else:
        end += len("\nfi")
    return textwrap.dedent(body[start:end])


def _run_guard(log_content: str, tmp_path: Path) -> subprocess.CompletedProcess[str]:
    """Run the guard extracted from the pipeline against a synthetic log."""
    log = tmp_path / "godot-test-output.log"
    log.write_text(log_content)
    script = 'set -euo pipefail\nGDSCRIPT_LOG="$1"\n' + _guard_snippet(_body())
    return subprocess.run(
        ["bash", "-c", script, "guard", str(log)],
        capture_output=True,
        text=True,
        cwd=tmp_path,
    )


def test_guard_trips_on_the_parse_error_that_started_this(tmp_path: Path) -> None:
    """The mc-x4hz line, verbatim, is what CI reported as green for 12 hours."""
    result = _run_guard(
        'SCRIPT ERROR: Parse Error: Identifier "CardStatData" not declared in the current scope.\n'
        "          at: GDScript::reload (res://tests/test_hangar_view.gd:38)\n"
        "Overall Summary: 5310 test cases | 0 errors | 0 failures | 0 flaky | 7 skipped | 0 orphans |\n",
        tmp_path,
    )
    assert result.returncode != 0, (
        "the guard passed a log containing the exact parse error that was live "
        "on main for twelve hours while every PR validation reported success. "
        f"stdout: {result.stdout!r}"
    )


def test_guard_trips_on_each_error_class(tmp_path: Path) -> None:
    for line in (
        "ERROR: Object '<Object#-9223372034707292164>' was freed or unreferenced while a signal is being emitted from it.",
        "SCRIPT ERROR: Compile Error: Failed to compile script",
        "USER ERROR: push_error called from user script",
    ):
        result = _run_guard(f"some output\n{line}\nmore output\n", tmp_path)
        assert result.returncode != 0, f"guard did not trip on: {line}"


def test_guard_passes_a_clean_run(tmp_path: Path) -> None:
    result = _run_guard(
        "Executed test suites: (392/392)\n"
        "Overall Summary: 5318 test cases | 0 errors | 0 failures | 0 flaky | 7 skipped | 0 orphans |\n",
        tmp_path,
    )
    assert result.returncode == 0, (
        "the guard failed a clean run. Every client PR would be red. "
        f"stdout: {result.stdout!r}"
    )


def test_guard_ignores_warnings_and_mid_line_matches(tmp_path: Path) -> None:
    """Anchored at line start, and WARNING is not an ERROR class."""
    result = _run_guard(
        "WARNING: [CardLibrary] unknown tag 'foo'\n"
        "test_reports_the_error_message: expected ERROR: nope\n"
        "  ERROR: indented continuation from a line the filter already dropped\n",
        tmp_path,
    )
    assert result.returncode == 0, (
        "the guard tripped on a line that is not an ERROR-class line at column "
        f"0. It would redden clean runs. stdout: {result.stdout!r}"
    )


def test_guard_names_the_fix_in_its_output(tmp_path: Path) -> None:
    """The reader has to know what to do, or the response is to delete the guard."""
    result = _run_guard("ERROR: something unexpected\n", tmp_path)
    assert "tests/_log_filter_patterns.txt" in result.stdout, (
        "the guard's failure output no longer tells the reader where an "
        "expected error is declared. A gate whose message is only 'failed' "
        f"gets deleted by the next person. stdout: {result.stdout!r}"
    )


# ---------------------------------------------------------------------------
# 5. The JUnit publisher
# ---------------------------------------------------------------------------


def test_junit_does_not_allow_empty_results() -> None:
    stage = _stage()
    assert "allowEmptyResults: false" in stage, (
        f"the {_STAGE!r} stage publishes JUnit with allowEmptyResults: true. "
        "This stage exists to stop 'nothing ran' from reading as success, and "
        "an empty result set is that same claim in another form: the stage "
        "always runs the whole tests/ tree, so a build that gets here with no "
        "results.xml has not passed, it has failed to run. Measured on "
        "MCD-PR-Main #1634 (2026-08-24): 7748 tests published, so the pattern "
        "does match on a normal client build. See mc-rqgm."
    )
    assert "testResults: 'reports/**/results.xml'" in stage, (
        f"the {_STAGE!r} JUnit pattern changed. With allowEmptyResults: false "
        "a pattern that matches nothing now fails the build, so this pattern "
        "and gdUnit's report directory have to stay in step. See mc-rqgm."
    )
