"""Tests for CrashReporting's entry in the Postgres-backed Service Tests stage (mc-56jd).

Src/CrashReporting shipped for months with Go tests that no pipeline ran. Two
separate things had to be true at once for that to stay invisible, and this
file pins both of them down.

1. The stage has to wake up. CRASH_REPORTING_CHANGED already existed, but it
   is ANDed with deployCrashReporting, and 'main' is deliberately not in
   crashEnvironments, because MCDServices-Main owns main's crash stack. So on
   MCDAppServices-Main that flag is false for every CrashReporting push, and
   the build marked itself NOT_BUILT before reaching any test. Builds #568 and
   #571 (the two crash-dedup merges) both read "No app service changes,
   skipped". The tests therefore hang off CRASH_REPORTING_SRC_CHANGED, which
   is the raw change-detection signal with no deploy gate on it.

2. The stage has to actually execute something. The module's tests soft-skip
   without a reachable database, so an invocation that never reached Postgres
   would report green having run nothing. `make test-go-db` sets
   MCDC_REQUIRE_DB_TESTS=1, which turns every one of those escapes into a
   failure.

Run with: pytest test/unit/test_mcd_crash_reporting_service_tests.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_APPSVC_SRC = _REPO_ROOT / "vars" / "mcdAppServicesPipeline.groovy"


def _src() -> str:
    if not _APPSVC_SRC.exists():
        pytest.fail(
            f"{_APPSVC_SRC} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-56jd."
        )
    return _APPSVC_SRC.read_text()


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-56jd."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-56jd."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-56jd.")


# ---------------------------------------------------------------------------
# 1. The stage has to wake up on a CrashReporting change
# ---------------------------------------------------------------------------


def test_src_changed_flag_is_not_gated_on_the_deploy_flag() -> None:
    """CRASH_REPORTING_SRC_CHANGED must be the raw detection signal (mc-56jd).

    The moment someone ANDs deployCrashReporting into this the way
    CRASH_REPORTING_CHANGED does, the tests stop running on
    MCDAppServices-Main and nothing says so.
    """
    src = _src()
    assert "env.CRASH_REPORTING_SRC_CHANGED = changes.crashReportingChanged.toString()" in src, (
        "CRASH_REPORTING_SRC_CHANGED must be assigned straight from "
        "changes.crashReportingChanged, with no deployCrashReporting gate. "
        "'main' is not in crashEnvironments, so a gated flag is always false "
        "there and the Postgres-backed crash tests never run. See mc-56jd."
    )


def test_src_changed_flag_reaches_any_work() -> None:
    """A CrashReporting-only push must not be declared NOT_BUILT (mc-56jd)."""
    src = _src()
    any_work_start = src.find("def anyWork =")
    assert any_work_start != -1, "no anyWork computation in source. See mc-56jd."
    any_work = src[any_work_start:src.find(")", src.find("CRASH_REPORTING", any_work_start)) + 1]
    assert "CRASH_REPORTING_SRC_CHANGED" in any_work, (
        "anyWork must include CRASH_REPORTING_SRC_CHANGED. Without it a "
        "CrashReporting-only push sets currentBuild.result = 'NOT_BUILT' and "
        "the Service Tests stage is never reached. See mc-56jd."
    )


def test_service_tests_stage_wakes_for_crash_reporting() -> None:
    """The stage's when{} must include the crash flag (mc-56jd)."""
    body = _stage_body(_src(), "Service Tests")
    when_block = body[:body.find("steps")]
    assert "CRASH_REPORTING_SRC_CHANGED" in when_block, (
        "Service Tests must run when Src/CrashReporting changes. Today the "
        "when{} covers only Auth/AccountService/AuctionHouse. See mc-56jd."
    )


# ---------------------------------------------------------------------------
# 2. The stage has to actually execute the DB-backed tests
# ---------------------------------------------------------------------------


def test_crash_reporting_runs_through_the_db_required_target() -> None:
    """`make test-go-db`, not a bare `go test` (mc-56jd).

    A bare `go test` in this stage would soft-skip every Postgres-backed test
    if the database were unreachable and still exit 0: the precise failure
    the stage is being added to end.
    """
    body = _stage_body(_src(), "Service Tests")
    assert "make test-go-db MODULE=CrashReporting" in body, (
        "CrashReporting must run via `make test-go-db MODULE=CrashReporting`, "
        "which sets MCDC_REQUIRE_DB_TESTS=1 so an unreachable database fails "
        "the stage instead of skipping quietly. See mc-56jd."
    )


def test_crash_reporting_targets_its_own_database() -> None:
    """PGDATABASE=mechacorps_crashes, the database postgres-init.sql creates (mc-56jd)."""
    body = _stage_body(_src(), "Service Tests")
    assert "PGDATABASE=mechacorps_crashes" in body, (
        "The CrashReporting run must set PGDATABASE=mechacorps_crashes. "
        "docker/postgres-init.sql creates auth/crashes/account; without this "
        "the harness falls back and migrates the wrong database. See mc-56jd."
    )


def test_crash_reporting_invocation_is_not_guarded_into_a_no_op() -> None:
    """No file-existence skip around the crash tests (mc-56jd).

    Other stages in this library guard on `make -n <target>` for branch skew.
    That is the right call for an advisory check and the wrong call here: a
    silent skip is exactly the failure mode this stage exists to end. The
    MCDClient half lands first, so the target is always present.
    """
    body = _stage_body(_src(), "Service Tests")
    invocation_line = next(
        line for line in body.splitlines() if "make test-go-db MODULE=CrashReporting" in line
    )
    assert "||" not in invocation_line, (
        "The CrashReporting invocation must not swallow its exit status. "
        "See mc-56jd."
    )
    assert "make -n test-go-db" not in body, (
        "No `make -n test-go-db` existence guard: a skipped crash-test run is "
        "indistinguishable from a passing one, which is the bug. See mc-56jd."
    )


def test_app_service_trio_still_runs_together() -> None:
    """Adding the crash flag must not narrow the existing coverage (mc-56jd).

    The stage now wakes for a CrashReporting-only change, so the trio is
    wrapped in a guard. Any one of the three must still run all three, the way
    it did before.
    """
    body = _stage_body(_src(), "Service Tests")
    for module, database in (
        ("Src/Auth", "mechacorps_auth"),
        ("Src/AccountService", "mechacorps_account"),
        ("Src/AuctionHouse", "mechacorps_auction"),
    ):
        assert f"cd {module} && PGDATABASE={database} go test ./..." in body, (
            f"{module} must still run in Service Tests. See mc-56jd."
        )
    assert '[ "$AUTH_CHANGED" = "true" ]' in body, (
        "The trio must be guarded on the app-service flags so a "
        "CrashReporting-only build does not run them. See mc-56jd."
    )
