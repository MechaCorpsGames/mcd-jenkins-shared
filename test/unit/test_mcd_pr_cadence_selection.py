"""Tests for mcdPRValidationPipeline.groovy cadence selection and stage activation (mc-wajxw).

Covers:
  :159-167  Per-PR vs wire-format cadence selection, including:
              - protocol_ext.h-only change suppresses perPr and selects wireFormat
              - determinismHarnessChanged triggers perPr even without a cadence file match
  :504      determinism-harness-replay stage activation when either DETERMINISM_*_CHANGED flag is true

Run with: pytest test/unit/test_mcd_pr_cadence_selection.py
No live Jenkins required — tests parse Groovy source and exercise Python
logic equivalents of the cadence-selection expressions.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_PR_PIPELINE_SRC = _REPO_ROOT / "vars" / "mcdPRValidationPipeline.groovy"


def _src() -> str:
    if not _PR_PIPELINE_SRC.exists():
        pytest.fail(
            f"{_PR_PIPELINE_SRC} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-wajxw."
        )
    return _PR_PIPELINE_SRC.read_text()


# ---------------------------------------------------------------------------
# Python equivalents of the cadence-selection and stage-activation logic
# (mirrors mcdPRValidationPipeline lines 159-167 and 504)
# ---------------------------------------------------------------------------


def _path_matches_glob(path: str, glob: str) -> bool:
    if glob.endswith("/**"):
        return path.startswith(glob[:-2])
    return path == glob


def _cadence_matches(changed_files: list[str], cadence: dict) -> bool:
    if not cadence or cadence.get("enabled") is False or not changed_files:
        return False
    if "changedAny" in cadence:
        return any(
            _path_matches_glob(p, g)
            for p in changed_files
            for g in cadence["changedAny"]
        )
    if "changedOnly" in cadence:
        return all(
            any(_path_matches_glob(p, g) for g in cadence["changedOnly"])
            for p in changed_files
        )
    return False


def _compute_cadence_env_flags(
    changed_files: list[str],
    determinism_harness_changed: bool,
    wire_format_cadence: dict,
    per_pr_cadence: dict,
) -> dict[str, str]:
    """
    Mirror of mcdPRValidationPipeline.groovy lines 159-167.

    wire_format_cadence / per_pr_cadence are the cadence config maps.
    Returns {'DETERMINISM_WIRE_FORMAT_CHANGED': 'true'/'false',
             'DETERMINISM_PER_PR_CHANGED': 'true'/'false'}.
    """
    wire_format_changed = _cadence_matches(changed_files, wire_format_cadence)
    per_pr_changed = _cadence_matches(changed_files, per_pr_cadence)
    per_pr_env = not wire_format_changed and (per_pr_changed or determinism_harness_changed)
    return {
        "DETERMINISM_WIRE_FORMAT_CHANGED": str(wire_format_changed).lower(),
        "DETERMINISM_PER_PR_CHANGED": str(per_pr_env).lower(),
    }


def _stage_should_run(per_pr_changed: str, wire_format_changed: str) -> bool:
    """Mirror of the determinism-harness-replay stage when condition (line 504)."""
    return per_pr_changed == "true" or wire_format_changed == "true"


# Sample cadences matching the MCDClient Jenkinsfile config (mc-jq73l)
_WIRE_FORMAT_CADENCE = {
    "enabled": True,
    "changedOnly": ["Src/Include/protocol_ext.h"],
}
_PER_PR_CADENCE = {
    "enabled": True,
    "changedAny": ["Src/MCPGameServer/**", "Src/Include/protocol_ext.h",
                   "Src/TestClient/Test/replay/**", "tests/determinism-harness/**"],
}


# ---------------------------------------------------------------------------
# :159-167 Cadence selection logic
# ---------------------------------------------------------------------------


class TestCadenceSelectionLogic:
    def test_protocol_ext_only_sets_wire_format_suppresses_per_pr(self) -> None:
        """protocol_ext.h alone → wireFormat=true, perPr=false (mc-wajxw:mcdPRValidationPipeline:159-167)."""
        flags = _compute_cadence_env_flags(
            ["Src/Include/protocol_ext.h"],
            determinism_harness_changed=False,
            wire_format_cadence=_WIRE_FORMAT_CADENCE,
            per_pr_cadence=_PER_PR_CADENCE,
        )
        assert flags["DETERMINISM_WIRE_FORMAT_CHANGED"] == "true", (
            "A protocol_ext.h-only PR must select the wireFormat cadence. "
            "See mc-wajxw:mcdPRValidationPipeline:159-167."
        )
        assert flags["DETERMINISM_PER_PR_CHANGED"] == "false", (
            "A protocol_ext.h-only PR must suppress the perPr cadence "
            "(wireFormat takes precedence). See mc-wajxw:mcdPRValidationPipeline:159-167."
        )

    def test_per_pr_match_only_sets_per_pr(self) -> None:
        """Replay test change → perPr=true, wireFormat=false (mc-wajxw:mcdPRValidationPipeline:159-167)."""
        flags = _compute_cadence_env_flags(
            ["Src/TestClient/Test/replay/SigtermLifecycleTest.cpp"],
            determinism_harness_changed=True,
            wire_format_cadence=_WIRE_FORMAT_CADENCE,
            per_pr_cadence=_PER_PR_CADENCE,
        )
        assert flags["DETERMINISM_PER_PR_CHANGED"] == "true", (
            "A replay test change must set perPr=true. "
            "See mc-wajxw:mcdPRValidationPipeline:159-167."
        )
        assert flags["DETERMINISM_WIRE_FORMAT_CHANGED"] == "false", (
            "A replay test change must leave wireFormat=false. "
            "See mc-wajxw:mcdPRValidationPipeline:159-167."
        )

    def test_determinism_harness_changed_triggers_per_pr_without_file_match(self) -> None:
        """determinismHarnessChanged=true triggers perPr even when no cadence file matches (mc-wajxw:mcdPRValidationPipeline:162)."""
        flags = _compute_cadence_env_flags(
            ["Src/GameServer/ServerMain.cpp"],  # unrelated file, won't match perPr globs
            determinism_harness_changed=True,
            wire_format_cadence=_WIRE_FORMAT_CADENCE,
            per_pr_cadence={
                "enabled": True,
                "changedAny": ["tests/determinism-harness/**"],  # won't match GameServer
            },
        )
        assert flags["DETERMINISM_PER_PR_CHANGED"] == "true", (
            "determinismHarnessChanged=true must force DETERMINISM_PER_PR_CHANGED=true "
            "even when no cadence file-pattern matches. See mc-wajxw:mcdPRValidationPipeline:162."
        )

    def test_neither_changed_both_false(self) -> None:
        """Unrelated file change → both flags false (mc-wajxw:mcdPRValidationPipeline:159-167)."""
        flags = _compute_cadence_env_flags(
            ["Menus/MainMenu.gd"],
            determinism_harness_changed=False,
            wire_format_cadence=_WIRE_FORMAT_CADENCE,
            per_pr_cadence=_PER_PR_CADENCE,
        )
        assert flags["DETERMINISM_PER_PR_CHANGED"] == "false"
        assert flags["DETERMINISM_WIRE_FORMAT_CHANGED"] == "false"

    def test_wire_format_suppresses_per_pr_even_when_per_pr_matches(self) -> None:
        """When wireFormat fires, perPr is suppressed regardless of cadence-file match (mc-wajxw:mcdPRValidationPipeline:161)."""
        # protocol_ext.h matches both wireFormat changedOnly and perPr changedAny
        flags = _compute_cadence_env_flags(
            ["Src/Include/protocol_ext.h"],
            determinism_harness_changed=False,
            wire_format_cadence=_WIRE_FORMAT_CADENCE,
            per_pr_cadence=_PER_PR_CADENCE,  # changedAny includes protocol_ext.h
        )
        assert flags["DETERMINISM_WIRE_FORMAT_CHANGED"] == "true"
        assert flags["DETERMINISM_PER_PR_CHANGED"] == "false", (
            "wireFormat match must suppress perPr even when perPr cadence would also match. "
            "The !wireFormatChanged guard enforces this. See mc-wajxw:mcdPRValidationPipeline:161."
        )

    def test_mcp_game_server_change_triggers_per_pr(self) -> None:
        """Src/MCPGameServer/** change → perPr=true, wireFormat=false (mc-wajxw:mcdPRValidationPipeline:159-167)."""
        flags = _compute_cadence_env_flags(
            ["Src/MCPGameServer/handler.go"],
            determinism_harness_changed=False,
            wire_format_cadence=_WIRE_FORMAT_CADENCE,
            per_pr_cadence=_PER_PR_CADENCE,
        )
        assert flags["DETERMINISM_PER_PR_CHANGED"] == "true"
        assert flags["DETERMINISM_WIRE_FORMAT_CHANGED"] == "false"


# ---------------------------------------------------------------------------
# :159-167 Structural checks on the Groovy source
# ---------------------------------------------------------------------------


class TestCadenceSelectionStructural:
    def test_src_contains_wire_format_changed_flag(self) -> None:
        """mcdPRValidationPipeline must set DETERMINISM_WIRE_FORMAT_CHANGED (mc-wajxw:mcdPRValidationPipeline:159-167)."""
        src = _src()
        assert "DETERMINISM_WIRE_FORMAT_CHANGED" in src, (
            "vars/mcdPRValidationPipeline.groovy must set env.DETERMINISM_WIRE_FORMAT_CHANGED. "
            "See mc-wajxw."
        )

    def test_src_contains_per_pr_changed_flag(self) -> None:
        """mcdPRValidationPipeline must set DETERMINISM_PER_PR_CHANGED (mc-wajxw:mcdPRValidationPipeline:159-167)."""
        src = _src()
        assert "DETERMINISM_PER_PR_CHANGED" in src, (
            "vars/mcdPRValidationPipeline.groovy must set env.DETERMINISM_PER_PR_CHANGED. "
            "See mc-wajxw."
        )

    def test_src_wire_format_suppresses_per_pr_with_not_operator(self) -> None:
        """perPr assignment must negate wireFormatChanged to suppress perPr when wire-format fires (mc-wajxw:mcdPRValidationPipeline:161)."""
        src = _src()
        # Look for the pattern: !wireFormatChanged && (perPrChanged || ...)
        assert re.search(r"!wireFormatChanged", src), (
            "vars/mcdPRValidationPipeline.groovy must use '!wireFormatChanged' to suppress "
            "perPr cadence when the wire-format cadence fires. See mc-wajxw."
        )

    def test_src_includes_determinism_harness_changed_in_per_pr_condition(self) -> None:
        """perPr condition must OR in changes.determinismHarnessChanged (mc-wajxw:mcdPRValidationPipeline:162)."""
        src = _src()
        assert "changes.determinismHarnessChanged" in src or "determinismHarnessChanged" in src, (
            "vars/mcdPRValidationPipeline.groovy must include determinismHarnessChanged "
            "in the DETERMINISM_PER_PR_CHANGED assignment. See mc-wajxw."
        )

    def test_src_calls_cadence_matches_for_both_cadences(self) -> None:
        """mcdPRValidationPipeline must call cadenceMatches() for wireFormat and perPr (mc-wajxw:mcdPRValidationPipeline:159-167)."""
        src = _src()
        assert src.count("cadenceMatches") >= 2, (
            "vars/mcdPRValidationPipeline.groovy must call mcdDeterminismHarness.cadenceMatches() "
            "twice: once for wireFormat and once for perPr. See mc-wajxw."
        )


# ---------------------------------------------------------------------------
# :504 Stage activation condition
# ---------------------------------------------------------------------------


class TestStageActivation:
    def test_stage_runs_when_per_pr_true(self) -> None:
        """determinism-harness-replay stage must run when DETERMINISM_PER_PR_CHANGED=true (mc-wajxw:mcdPRValidationPipeline:504)."""
        assert _stage_should_run("true", "false") is True

    def test_stage_runs_when_wire_format_true(self) -> None:
        """determinism-harness-replay stage must run when DETERMINISM_WIRE_FORMAT_CHANGED=true (mc-wajxw:mcdPRValidationPipeline:504)."""
        assert _stage_should_run("false", "true") is True

    def test_stage_runs_when_both_true(self) -> None:
        """determinism-harness-replay stage must run when both flags are true (mc-wajxw:mcdPRValidationPipeline:504)."""
        assert _stage_should_run("true", "true") is True

    def test_stage_skips_when_both_false(self) -> None:
        """determinism-harness-replay stage must be skipped when both flags are false (mc-wajxw:mcdPRValidationPipeline:504)."""
        assert _stage_should_run("false", "false") is False

    def test_src_stage_uses_or_between_two_flags(self) -> None:
        """Stage when-condition must OR the two env flags (mc-wajxw:mcdPRValidationPipeline:504)."""
        src = _src()
        # Find the determinism-harness-replay stage block
        stage_match = re.search(
            r"stage\(['\"]determinism-harness-replay['\"].*?(?=stage\(['\"]|\Z)",
            src,
            re.DOTALL,
        )
        assert stage_match, (
            "vars/mcdPRValidationPipeline.groovy must define a 'determinism-harness-replay' stage. "
            "See mc-wajxw:mcdPRValidationPipeline:504."
        )
        stage_block = stage_match.group(0)
        assert "DETERMINISM_PER_PR_CHANGED" in stage_block, (
            "'determinism-harness-replay' stage when-condition must reference "
            "DETERMINISM_PER_PR_CHANGED. See mc-wajxw."
        )
        assert "DETERMINISM_WIRE_FORMAT_CHANGED" in stage_block, (
            "'determinism-harness-replay' stage when-condition must reference "
            "DETERMINISM_WIRE_FORMAT_CHANGED. See mc-wajxw."
        )
        assert "||" in stage_block, (
            "'determinism-harness-replay' stage when-condition must join the two flags "
            "with || so either alone activates the stage. See mc-wajxw."
        )

    def test_src_stage_cadence_keys_include_wire_format_when_wire_format_changed(self) -> None:
        """Stage must add 'wireFormat' to cadenceKeys when DETERMINISM_WIRE_FORMAT_CHANGED=true (mc-wajxw:mcdPRValidationPipeline:504)."""
        src = _src()
        stage_block_match = re.search(
            r"stage\(['\"]determinism-harness-replay['\"].*?(?=^\s*stage\(|\Z)",
            src,
            re.DOTALL | re.MULTILINE,
        )
        if stage_block_match:
            block = stage_block_match.group(0)
            assert "wireFormat" in block, (
                "'determinism-harness-replay' stage must add 'wireFormat' to cadenceKeys "
                "when DETERMINISM_WIRE_FORMAT_CHANGED is true. See mc-wajxw."
            )
            assert "perPr" in block, (
                "'determinism-harness-replay' stage must add 'perPr' to cadenceKeys "
                "when DETERMINISM_PER_PR_CHANGED is true. See mc-wajxw."
            )

    def test_src_stage_calls_run_pr_cadences(self) -> None:
        """Stage must call mcdDeterminismHarness.runPrCadences() (mc-wajxw:mcdPRValidationPipeline:504)."""
        src = _src()
        assert "runPrCadences" in src, (
            "vars/mcdPRValidationPipeline.groovy determinism-harness-replay stage must call "
            "mcdDeterminismHarness.runPrCadences(). See mc-wajxw."
        )
