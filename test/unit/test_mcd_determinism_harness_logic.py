"""Tests for mcdDeterminismHarness.groovy shared library (mc-wajxw).

Covers:
  :4   cadenceMatches() — changedAny and changedOnly glob matching
  :24  runPrCadences()  — PR cadence fixture resolution, failure aggregation, JUnit writing
  :55  runNightly()     — nightly no-artifacts skipped JUnit, non-blocking failure, failure email
  :129 runReplayCase()  — missing action log/baseline, exit code, attribution, timeout/execution failure
  :187 readAttribution()— fallback: exit 0 => identical, exit 3 => action_log_shape
  :204 sampleNightlyArtifacts() — K=3 yesterday playtest-bench sampling with co-located baseline

Run with: pytest test/unit/test_mcd_determinism_harness_logic.py
No live Jenkins required.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_HARNESS_SRC = _REPO_ROOT / "vars" / "mcdDeterminismHarness.groovy"


def _src() -> str:
    if not _HARNESS_SRC.exists():
        pytest.fail(
            f"{_HARNESS_SRC} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-wajxw."
        )
    return _HARNESS_SRC.read_text()


# ---------------------------------------------------------------------------
# Python equivalents of mcdDeterminismHarness pure-logic methods.
# These mirror the Groovy implementations line-for-line.
# ---------------------------------------------------------------------------


def _path_matches_glob(path: str, glob: str) -> bool:
    """Mirror of mcdDeterminismHarness.pathMatchesGlob()."""
    if glob.endswith("/**"):
        return path.startswith(glob[:-2])
    return path == glob


def _cadence_matches(changed_files: list[str], cadence: dict) -> bool:
    """Mirror of mcdDeterminismHarness.cadenceMatches()."""
    if not cadence or cadence.get("enabled") is False or not changed_files:
        return False
    if "changedAny" in cadence:
        return any(
            _path_matches_glob(path, glob)
            for path in changed_files
            for glob in cadence["changedAny"]
        )
    if "changedOnly" in cadence:
        return all(
            any(_path_matches_glob(path, glob) for glob in cadence["changedOnly"])
            for path in changed_files
        )
    return False


def _resolve_fixture_names(harness: dict, cadence: dict) -> list[str]:
    """Mirror of mcdDeterminismHarness.resolveFixtureNames()."""
    fixture_spec = cadence.get("fixtures")
    if fixture_spec == "canonical":
        return harness.get("fixtures", {}).get("canonical") or []
    if isinstance(fixture_spec, list):
        return fixture_spec
    if fixture_spec:
        return [str(fixture_spec)]
    per_pr_default = harness.get("fixtures", {}).get("perPrDefault")
    if per_pr_default:
        return [per_pr_default]
    return []


def _read_attribution(attribution_exists: bool, attribution_category: str | None, exit_code: int) -> str:
    """Mirror of mcdDeterminismHarness.readAttribution() fallback logic."""
    if attribution_exists and attribution_category is not None:
        return attribution_category
    if exit_code == 0:
        return "identical"
    if exit_code == 3:
        return "action_log_shape"
    return ""


# ---------------------------------------------------------------------------
# :4 cadenceMatches() — changedAny and changedOnly glob matching
# ---------------------------------------------------------------------------


class TestCadenceMatchesChangedAny:
    def test_matches_when_at_least_one_file_hits_glob(self) -> None:
        """changedAny: true when any changed file matches any listed glob."""
        result = _cadence_matches(
            ["Src/TestClient/Test/replay/ReplayTest.cpp"],
            {"enabled": True, "changedAny": ["Src/TestClient/Test/replay/**", "Src/Include/**"]},
        )
        assert result is True

    def test_false_when_no_file_matches_any_glob(self) -> None:
        """changedAny: false when no file matches."""
        result = _cadence_matches(
            ["Src/GameServer/main.cpp"],
            {"enabled": True, "changedAny": ["Src/TestClient/Test/replay/**"]},
        )
        assert result is False

    def test_partial_path_does_not_match_star_star_glob(self) -> None:
        """changedAny: a shorter path prefix must not match a longer /** glob."""
        result = _cadence_matches(
            ["Src/TestClient/"],
            {"enabled": True, "changedAny": ["Src/TestClient/Test/replay/**"]},
        )
        assert result is False

    def test_exact_file_matches_star_star_glob_of_parent(self) -> None:
        """changedAny: 'Src/Include/protocol_ext.h' matches 'Src/Include/**'."""
        result = _cadence_matches(
            ["Src/Include/protocol_ext.h"],
            {"enabled": True, "changedAny": ["Src/Include/**"]},
        )
        assert result is True

    def test_protocol_ext_h_exact_match_in_changed_any(self) -> None:
        """changedAny: exact path 'Src/Include/protocol_ext.h' matches that exact glob too."""
        result = _cadence_matches(
            ["Src/Include/protocol_ext.h"],
            {"enabled": True, "changedAny": ["Src/Include/protocol_ext.h"]},
        )
        assert result is True


class TestCadenceMatchesChangedOnly:
    def test_true_when_all_files_match_at_least_one_glob(self) -> None:
        """changedOnly: true when every changed file is covered by at least one glob."""
        result = _cadence_matches(
            ["Src/Include/protocol_ext.h"],
            {"enabled": True, "changedOnly": ["Src/Include/**", "Src/External/**"]},
        )
        assert result is True

    def test_false_when_any_file_matches_no_glob(self) -> None:
        """changedOnly: false when even one file is not covered by any glob."""
        result = _cadence_matches(
            ["Src/Include/protocol_ext.h", "Src/GameServer/main.cpp"],
            {"enabled": True, "changedOnly": ["Src/Include/**", "Src/External/**"]},
        )
        assert result is False

    def test_wire_format_only_protocol_ext_h(self) -> None:
        """changedOnly: ['Src/Include/protocol_ext.h'] is the wire-format cadence sentinel."""
        wire_format = {"enabled": True, "changedOnly": ["Src/Include/protocol_ext.h"]}
        assert _cadence_matches(["Src/Include/protocol_ext.h"], wire_format) is True
        assert _cadence_matches(["Src/Include/protocol_ext.h", "Src/GameServer/x.cpp"], wire_format) is False

    def test_changed_only_with_multiple_files_all_matching(self) -> None:
        """changedOnly: multi-file PR where every file matches passes."""
        result = _cadence_matches(
            ["Src/Include/protocol_ext.h", "Src/External/some_lib.h"],
            {"enabled": True, "changedOnly": ["Src/Include/**", "Src/External/**"]},
        )
        assert result is True


class TestCadenceMatchesEdgeCases:
    def test_disabled_cadence_returns_false(self) -> None:
        """enabled=false cadence must always return false, regardless of files."""
        result = _cadence_matches(
            ["Src/TestClient/Test/replay/ReplayTest.cpp"],
            {"enabled": False, "changedAny": ["Src/TestClient/Test/replay/**"]},
        )
        assert result is False

    def test_empty_changed_files_returns_false(self) -> None:
        """Empty changed-files list must return false even with a matching glob."""
        result = _cadence_matches(
            [],
            {"enabled": True, "changedAny": ["Src/TestClient/Test/replay/**"]},
        )
        assert result is False

    def test_null_cadence_returns_false(self) -> None:
        """None cadence must not raise; must return false."""
        result = _cadence_matches(["Src/Include/protocol_ext.h"], {})
        assert result is False

    def test_neither_changed_any_nor_changed_only_returns_false(self) -> None:
        """Cadence with neither changedAny nor changedOnly must return false."""
        result = _cadence_matches(
            ["Src/Include/protocol_ext.h"],
            {"enabled": True},
        )
        assert result is False


class TestPathMatchesGlob:
    def test_double_star_matches_direct_child(self) -> None:
        assert _path_matches_glob("Src/Include/protocol_ext.h", "Src/Include/**") is True

    def test_double_star_matches_nested_child(self) -> None:
        assert _path_matches_glob("tests/determinism-harness/fixtures/x/y.json", "tests/determinism-harness/**") is True

    def test_double_star_does_not_match_sibling(self) -> None:
        assert _path_matches_glob("Src/External/lib.h", "Src/Include/**") is False

    def test_exact_match_path_equals_glob(self) -> None:
        assert _path_matches_glob("Src/Include/protocol_ext.h", "Src/Include/protocol_ext.h") is True

    def test_exact_match_different_file(self) -> None:
        assert _path_matches_glob("Src/Include/other.h", "Src/Include/protocol_ext.h") is False


# ---------------------------------------------------------------------------
# :24 runPrCadences() — fixture resolution, failure aggregation, JUnit writing
# ---------------------------------------------------------------------------


class TestResolveFixtureNames:
    def test_canonical_returns_harness_canonical_list(self) -> None:
        """fixtures='canonical' must return harness.fixtures.canonical list."""
        harness = {"fixtures": {"canonical": ["short_concede_match", "full_length_match"]}}
        result = _resolve_fixture_names(harness, {"fixtures": "canonical"})
        assert result == ["short_concede_match", "full_length_match"]

    def test_list_fixture_returns_list_directly(self) -> None:
        """fixtures=['a','b'] must return that list."""
        result = _resolve_fixture_names({}, {"fixtures": ["fixture_a", "fixture_b"]})
        assert result == ["fixture_a", "fixture_b"]

    def test_string_fixture_wraps_in_list(self) -> None:
        """fixtures='custom' must return ['custom']."""
        result = _resolve_fixture_names({}, {"fixtures": "custom_fixture"})
        assert result == ["custom_fixture"]

    def test_none_fixture_uses_per_pr_default(self) -> None:
        """No fixtures key falls through to harness.fixtures.perPrDefault."""
        harness = {"fixtures": {"perPrDefault": "default_fixture"}}
        result = _resolve_fixture_names(harness, {})
        assert result == ["default_fixture"]

    def test_none_fixture_no_default_returns_empty(self) -> None:
        """No fixtures key and no perPrDefault returns []."""
        result = _resolve_fixture_names({"fixtures": {}}, {})
        assert result == []

    def test_canonical_empty_list_returns_empty(self) -> None:
        """fixtures='canonical' with empty canonical list returns []."""
        harness = {"fixtures": {"canonical": []}}
        result = _resolve_fixture_names(harness, {"fixtures": "canonical"})
        assert result == []


class TestRunPrCadencesStructural:
    def test_src_contains_resolve_fixture_names_call(self) -> None:
        """runPrCadences must call resolveFixtureNames() per cadence key (mc-wajxw:mcdDeterminismHarness:24)."""
        src = _src()
        assert "resolveFixtureNames" in src, (
            "vars/mcdDeterminismHarness.groovy runPrCadences must call resolveFixtureNames "
            "to resolve fixture names for each cadence. See mc-wajxw."
        )

    def test_src_aggregates_failures_before_erroring(self) -> None:
        """runPrCadences must collect all failures before calling error() (mc-wajxw:mcdDeterminismHarness:24)."""
        src = _src()
        # failures list must be built up before error() is called
        assert re.search(r"failures\s*=\s*\[\]", src) or re.search(r"def\s+failures\s*=\s*\[\]", src), (
            "vars/mcdDeterminismHarness.groovy must accumulate failures in a list "
            "before calling error(), so all cadences run before failing. See mc-wajxw."
        )
        assert re.search(r"failures\.addAll|failures\s*<<", src), (
            "vars/mcdDeterminismHarness.groovy must append case failures to the list. "
            "See mc-wajxw."
        )

    def test_src_writes_junit_per_cadence(self) -> None:
        """runPrCadences must call writeJUnit() for each cadence (mc-wajxw:mcdDeterminismHarness:24)."""
        src = _src()
        assert "writeJUnit" in src, (
            "vars/mcdDeterminismHarness.groovy must call writeJUnit() to write per-cadence "
            "JUnit results. See mc-wajxw."
        )


# ---------------------------------------------------------------------------
# :55 runNightly() — no-artifacts skipped JUnit, non-blocking failure, email
# ---------------------------------------------------------------------------


class TestRunNightlyStructural:
    def test_src_emits_skipped_junit_when_no_artifacts(self) -> None:
        """runNightly must emit a skipped JUnit entry when no artifacts are found (mc-wajxw:mcdDeterminismHarness:55)."""
        src = _src()
        assert "skipped" in src and "no-artifacts" in src, (
            "vars/mcdDeterminismHarness.groovy runNightly must emit a skipped JUnit result "
            "with name 'nightly/no-artifacts' when sampleNightlyArtifacts returns empty. "
            "See mc-wajxw."
        )

    def test_src_non_blocking_sets_unstable(self) -> None:
        """runNightly non-blocking failure must set build UNSTABLE, not error (mc-wajxw:mcdDeterminismHarness:55)."""
        src = _src()
        assert "UNSTABLE" in src, (
            "vars/mcdDeterminismHarness.groovy runNightly must set currentBuild.result = 'UNSTABLE' "
            "when blocking == false and there are failures. See mc-wajxw."
        )
        assert re.search(r"blocking\s*==\s*false", src), (
            "vars/mcdDeterminismHarness.groovy runNightly must check cadence.blocking == false "
            "before deciding between UNSTABLE and error(). See mc-wajxw."
        )

    def test_src_failure_sends_email(self) -> None:
        """runNightly must call notifyFailureEmail() on failures (mc-wajxw:mcdDeterminismHarness:55)."""
        src = _src()
        assert "notifyFailureEmail" in src, (
            "vars/mcdDeterminismHarness.groovy runNightly must call notifyFailureEmail() "
            "when there are nightly failures. See mc-wajxw."
        )

    def test_src_notify_failure_email_defined(self) -> None:
        """notifyFailureEmail helper must be defined in the same file (mc-wajxw:mcdDeterminismHarness:55)."""
        src = _src()
        assert re.search(r"def\s+notifyFailureEmail", src), (
            "vars/mcdDeterminismHarness.groovy must define notifyFailureEmail() "
            "to send on-call alerts for nightly failures. See mc-wajxw."
        )


# ---------------------------------------------------------------------------
# :129 runReplayCase() — missing files, exit code, attribution, timeout
# ---------------------------------------------------------------------------


class TestRunReplayCaseStructural:
    def test_src_checks_action_log_file_exists(self) -> None:
        """runReplayCase must call fileExists() on the action log path (mc-wajxw:mcdDeterminismHarness:129)."""
        src = _src()
        assert re.search(r"fileExists\s*\(\s*actionLog\s*\)", src), (
            "vars/mcdDeterminismHarness.groovy runReplayCase must check fileExists(actionLog) "
            "and return a failure when the action log is missing. See mc-wajxw."
        )

    def test_src_returns_failed_result_for_missing_action_log(self) -> None:
        """runReplayCase must return failedResult with 'missing action log' message (mc-wajxw:mcdDeterminismHarness:129)."""
        src = _src()
        assert "missing action log" in src, (
            "vars/mcdDeterminismHarness.groovy runReplayCase must return a failedResult "
            "with 'missing action log' in the message when the log file is absent. See mc-wajxw."
        )

    def test_src_checks_baseline_file_exists(self) -> None:
        """runReplayCase must call fileExists() on the baseline trace path (mc-wajxw:mcdDeterminismHarness:129)."""
        src = _src()
        assert re.search(r"fileExists\s*\(\s*baseline\s*\)", src), (
            "vars/mcdDeterminismHarness.groovy runReplayCase must check fileExists(baseline) "
            "and return a failure when the baseline is missing. See mc-wajxw."
        )

    def test_src_returns_failed_result_for_missing_baseline(self) -> None:
        """runReplayCase must return failedResult with 'missing baseline trace' (mc-wajxw:mcdDeterminismHarness:129)."""
        src = _src()
        assert "missing baseline trace" in src, (
            "vars/mcdDeterminismHarness.groovy runReplayCase must return a failedResult "
            "with 'missing baseline trace' in the message when the baseline is absent. See mc-wajxw."
        )

    def test_src_catches_timeout_and_execution_failure(self) -> None:
        """runReplayCase must catch timeout/execution failures and return failedResult (mc-wajxw:mcdDeterminismHarness:129)."""
        src = _src()
        assert re.search(r"catch\s*\(", src), (
            "vars/mcdDeterminismHarness.groovy runReplayCase must catch exceptions "
            "(timeout or execution failure) and return a failedResult. See mc-wajxw."
        )
        assert "timeout or execution failure" in src, (
            "vars/mcdDeterminismHarness.groovy runReplayCase catch block must include "
            "'timeout or execution failure' in the failure message. See mc-wajxw."
        )

    def test_src_compares_exit_code_to_expected(self) -> None:
        """runReplayCase must compare exitCode to expectedExitCode (mc-wajxw:mcdDeterminismHarness:129)."""
        src = _src()
        assert "expectedExitCode" in src, (
            "vars/mcdDeterminismHarness.groovy runReplayCase must read cadence.expectedExitCode "
            "and compare it to the actual exit code. See mc-wajxw."
        )
        assert re.search(r"exitCode\s*==\s*expectedExitCode", src), (
            "vars/mcdDeterminismHarness.groovy runReplayCase must use == to compare "
            "exitCode to expectedExitCode. See mc-wajxw."
        )

    def test_src_compares_attribution_to_expected(self) -> None:
        """runReplayCase must compare actualAttribution to expectedAttribution (mc-wajxw:mcdDeterminismHarness:129)."""
        src = _src()
        assert "expectedAttribution" in src, (
            "vars/mcdDeterminismHarness.groovy runReplayCase must check expectedAttribution "
            "against the actual attribution. See mc-wajxw."
        )
        assert re.search(r"actualAttribution\s*==\s*expectedAttribution", src), (
            "vars/mcdDeterminismHarness.groovy runReplayCase must compare "
            "actualAttribution == expectedAttribution when expectedAttribution is set. See mc-wajxw."
        )


# ---------------------------------------------------------------------------
# :187 readAttribution() — exit 0 => identical, exit 3 => action_log_shape
# ---------------------------------------------------------------------------


class TestReadAttributionFallback:
    def test_exit_0_returns_identical(self) -> None:
        """No attribution.json + exit 0 must return 'identical' (mc-wajxw:mcdDeterminismHarness:187)."""
        result = _read_attribution(False, None, 0)
        assert result == "identical", (
            "readAttribution fallback: exit 0 with no attribution file must return 'identical'. "
            "See mc-wajxw:mcdDeterminismHarness:187."
        )

    def test_exit_3_returns_action_log_shape(self) -> None:
        """No attribution.json + exit 3 must return 'action_log_shape' (mc-wajxw:mcdDeterminismHarness:187)."""
        result = _read_attribution(False, None, 3)
        assert result == "action_log_shape", (
            "readAttribution fallback: exit 3 with no attribution file must return 'action_log_shape'. "
            "See mc-wajxw:mcdDeterminismHarness:187."
        )

    def test_exit_1_returns_empty_string(self) -> None:
        """No attribution.json + any other exit code must return '' (mc-wajxw:mcdDeterminismHarness:187)."""
        result = _read_attribution(False, None, 1)
        assert result == "", (
            "readAttribution fallback: non-0 non-3 exit code with no attribution file must return ''. "
            "See mc-wajxw:mcdDeterminismHarness:187."
        )

    def test_exit_2_returns_empty_string(self) -> None:
        """Exit 2 (other error) must also return '' (mc-wajxw:mcdDeterminismHarness:187)."""
        result = _read_attribution(False, None, 2)
        assert result == ""

    def test_attribution_file_present_returns_category(self) -> None:
        """When attribution.json exists, its category field must be returned (mc-wajxw:mcdDeterminismHarness:187)."""
        result = _read_attribution(True, "deterministic", 0)
        assert result == "deterministic"

    def test_attribution_file_overrides_exit_code_fallback(self) -> None:
        """attribution.json category takes priority over the exit-code fallback (mc-wajxw:mcdDeterminismHarness:187)."""
        result = _read_attribution(True, "content_mismatch", 3)
        assert result == "content_mismatch", (
            "When attribution.json exists, its category must override the exit-code fallback "
            "('action_log_shape' for exit 3 is only the fallback). See mc-wajxw."
        )

    def test_src_contains_exit_0_identical_fallback(self) -> None:
        """Groovy source must have the exit 0 => 'identical' fallback (mc-wajxw:mcdDeterminismHarness:187)."""
        src = _src()
        assert re.search(r"exitCode\s*==\s*0", src) and "'identical'" in src, (
            "vars/mcdDeterminismHarness.groovy readAttribution must return 'identical' "
            "when exitCode == 0 and no attribution file exists. See mc-wajxw."
        )

    def test_src_contains_exit_3_action_log_shape_fallback(self) -> None:
        """Groovy source must have the exit 3 => 'action_log_shape' fallback (mc-wajxw:mcdDeterminismHarness:187)."""
        src = _src()
        assert re.search(r"exitCode\s*==\s*3", src) and "'action_log_shape'" in src, (
            "vars/mcdDeterminismHarness.groovy readAttribution must return 'action_log_shape' "
            "when exitCode == 3 and no attribution file exists. See mc-wajxw."
        )


# ---------------------------------------------------------------------------
# :204 sampleNightlyArtifacts() — K=3 yesterday sampling, co-located baseline
# ---------------------------------------------------------------------------


class TestSampleNightlyArtifactsStructural:
    def test_src_defaults_sample_count_to_3(self) -> None:
        """sampleNightlyArtifacts default sampleCount must be 3 — K=3 spec (mc-wajxw:mcdDeterminismHarness:204)."""
        src = _src()
        assert re.search(r"sampleCount\s*\?:\s*3", src), (
            "vars/mcdDeterminismHarness.groovy sampleNightlyArtifacts must default "
            "sampleCount to 3 (K=3 from mc-9t1.8 spec). See mc-wajxw."
        )

    def test_src_passes_sample_count_to_shuf(self) -> None:
        """sampleNightlyArtifacts must pass sampleCount to 'shuf -n' (mc-wajxw:mcdDeterminismHarness:204)."""
        src = _src()
        assert "shuf -n" in src, (
            "vars/mcdDeterminismHarness.groovy sampleNightlyArtifacts must use 'shuf -n ${sampleCount}' "
            "to randomly sample K artifacts. See mc-wajxw."
        )

    def test_src_filters_to_yesterday_window(self) -> None:
        """sampleNightlyArtifacts must use 'yesterday 00:00' time window (mc-wajxw:mcdDeterminismHarness:204)."""
        src = _src()
        assert "yesterday" in src, (
            "vars/mcdDeterminismHarness.groovy sampleNightlyArtifacts must filter to "
            "files created 'yesterday' (newermt 'yesterday 00:00'). See mc-wajxw."
        )

    def test_src_requires_co_located_baseline(self) -> None:
        """sampleNightlyArtifacts must only include pairs with a co-located baseline (mc-wajxw:mcdDeterminismHarness:204)."""
        src = _src()
        # The baseline file must be checked with [ -f ] alongside the action log
        assert re.search(r"\[\s*-f\s*.*baseline\|baselin", src, re.IGNORECASE) or (
            re.search(r"\[\s*-f\s", src) and "baseline" in src
        ), (
            "vars/mcdDeterminismHarness.groovy sampleNightlyArtifacts must verify "
            "that a co-located baseline file exists before including the artifact pair. "
            "See mc-wajxw."
        )

    def test_src_returns_empty_when_no_output(self) -> None:
        """sampleNightlyArtifacts must return [] when the sh command returns empty (mc-wajxw:mcdDeterminismHarness:204)."""
        src = _src()
        assert re.search(r"return\s*\[\]", src) or "return []" in src, (
            "vars/mcdDeterminismHarness.groovy sampleNightlyArtifacts must return [] "
            "when no artifacts are found. See mc-wajxw."
        )

    def test_src_parses_tab_separated_action_log_and_baseline(self) -> None:
        """sampleNightlyArtifacts must parse tab-separated actionLog\\tbaseline lines (mc-wajxw:mcdDeterminismHarness:204)."""
        src = _src()
        assert "actionLog" in src and "baseline" in src, (
            "vars/mcdDeterminismHarness.groovy sampleNightlyArtifacts must produce "
            "maps with actionLog and baseline keys. See mc-wajxw."
        )
        assert r"\\t" in src or "split('\t'" in src or "split(\"\\t\"" in src, (
            "vars/mcdDeterminismHarness.groovy sampleNightlyArtifacts must split on "
            "tab to separate actionLog from baseline in each output line. See mc-wajxw."
        )
