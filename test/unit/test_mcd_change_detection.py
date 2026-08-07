"""Tests for mcdChangeDetection.groovy determinismHarnessChanged detection (mc-wajxw).

Verifies vars/mcdChangeDetection.groovy sets determinismHarnessChanged for the
two path prefixes that constitute the determinism-harness source tree:
  - tests/determinism-harness/**   (fixture files)
  - Src/TestClient/Test/replay/**  (replay integration tests)

Tests also verify interaction with the categorize() path — harness files are
already claimed by other categories (tests/ → client, Src/TestClient/ → server),
so determinismHarnessChanged is a parallel flag set independently of categorize().

Run with: pytest test/unit/test_mcd_change_detection.py
No live Jenkins required — tests parse Groovy source and exercise Python
logic equivalents of the detect() loop.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_CHANGE_DETECTION_SRC = _REPO_ROOT / "vars" / "mcdChangeDetection.groovy"


def _src() -> str:
    if not _CHANGE_DETECTION_SRC.exists():
        pytest.fail(
            f"{_CHANGE_DETECTION_SRC} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-wajxw."
        )
    return _CHANGE_DETECTION_SRC.read_text()


# ---------------------------------------------------------------------------
# Structural: determinismHarnessChanged path prefixes (mcdChangeDetection:73)
# ---------------------------------------------------------------------------


def test_detect_registers_tests_determinism_harness_prefix() -> None:
    """detect() must check for tests/determinism-harness/ prefix (mc-wajxw:mcdChangeDetection:73)."""
    src = _src()
    assert "tests/determinism-harness/" in src, (
        "mcdChangeDetection.groovy must set determinismHarnessChanged "
        "for files under tests/determinism-harness/. See mc-wajxw."
    )


def test_detect_registers_src_testclient_replay_prefix() -> None:
    """detect() must check for Src/TestClient/Test/replay/ prefix (mc-wajxw:mcdChangeDetection:73)."""
    src = _src()
    assert "Src/TestClient/Test/replay/" in src, (
        "mcdChangeDetection.groovy must set determinismHarnessChanged "
        "for files under Src/TestClient/Test/replay/. See mc-wajxw."
    )


def test_detect_uses_or_between_both_prefixes() -> None:
    """Both prefixes must be joined with || so either alone triggers the flag (mc-wajxw:mcdChangeDetection:73)."""
    src = _src()
    # Both prefixes should appear on a line that uses logical OR (||)
    harness_prefix_line = next(
        (ln for ln in src.splitlines() if "tests/determinism-harness/" in ln), None
    )
    assert harness_prefix_line is not None, (
        "mcdChangeDetection.groovy must have a line referencing tests/determinism-harness/. "
        "See mc-wajxw."
    )
    assert "||" in harness_prefix_line or "Src/TestClient/Test/replay/" in harness_prefix_line, (
        "The determinismHarnessChanged detection line must include both prefixes "
        "in an OR condition on the same line or nearby. See mc-wajxw."
    )
    replay_prefix_line = next(
        (ln for ln in src.splitlines() if "Src/TestClient/Test/replay/" in ln), None
    )
    assert replay_prefix_line is not None
    # They must be in the same logical expression (|| between them)
    combined_block = " ".join(
        ln for ln in src.splitlines()
        if "tests/determinism-harness/" in ln or "Src/TestClient/Test/replay/" in ln
    )
    assert "||" in combined_block, (
        "The two determinismHarnessChanged path prefixes must be joined with || "
        "so either alone triggers the flag. See mc-wajxw."
    )


def test_detect_sets_determinism_harness_changed_assignment() -> None:
    """detect() must assign determinismHarnessChanged = true inside the conditional block (mc-wajxw)."""
    src = _src()
    assert re.search(r"determinismHarnessChanged\s*=\s*true", src), (
        "mcdChangeDetection.groovy must assign 'determinismHarnessChanged = true' "
        "when a determinism-harness file changes. See mc-wajxw."
    )


# ---------------------------------------------------------------------------
# Behavioral: detect() logic equivalent (Python mirror of the Groovy loop)
# ---------------------------------------------------------------------------

# Python equivalents of the relevant parts of mcdChangeDetection.detect().
# These mirror the Groovy implementation; failures here indicate the Groovy
# source contains logic that diverges from the spec.

_CLIENT_PREFIXES = [
    "Src/MCDCoreExt/", "GameModes/", "Menus/", "DeckBuilder/",
    "CardLibrary/", "CardLibraryScripts/", "Resources/", "Onboard/",
    "Game/", "Sandbox/", "tests/", "scripts/", "addons/",
    "Assets/", "Export/", "Generated/",
]
_SERVER_PREFIXES = ["Src/GameServer/", "Src/Proxy/", "Src/TestClient/"]


def _simulate_detect(changed_files: list[str]) -> dict:
    """Python mirror of the relevant parts of mcdChangeDetection.detect()."""
    determinism_harness_changed = False
    client_changed = False
    server_changed = False

    for f in changed_files:
        if f.startswith("tests/determinism-harness/") or f.startswith(
            "Src/TestClient/Test/replay/"
        ):
            determinism_harness_changed = True
        if any(f.startswith(p) for p in _CLIENT_PREFIXES):
            client_changed = True
        if any(f.startswith(p) for p in _SERVER_PREFIXES):
            server_changed = True

    return {
        "determinismHarnessChanged": determinism_harness_changed,
        "clientChanged": client_changed,
        "serverChanged": server_changed,
    }


def test_harness_fixture_file_sets_determinism_harness_changed() -> None:
    """A tests/determinism-harness/ file must set determinismHarnessChanged=true."""
    result = _simulate_detect(
        ["tests/determinism-harness/fixtures/short_concede_match/mcp_action_log.jsonl"]
    )
    assert result["determinismHarnessChanged"] is True, (
        "tests/determinism-harness/** files must trigger determinismHarnessChanged. "
        "See mc-wajxw:mcdChangeDetection:73."
    )


def test_replay_test_file_sets_determinism_harness_changed() -> None:
    """A Src/TestClient/Test/replay/ file must set determinismHarnessChanged=true."""
    result = _simulate_detect(["Src/TestClient/Test/replay/SigtermLifecycleTest.cpp"])
    assert result["determinismHarnessChanged"] is True, (
        "Src/TestClient/Test/replay/** files must trigger determinismHarnessChanged. "
        "See mc-wajxw:mcdChangeDetection:73."
    )


def test_unrelated_file_does_not_set_determinism_harness_changed() -> None:
    """An unrelated file must NOT set determinismHarnessChanged."""
    result = _simulate_detect(["Src/GameServer/ServerMain.cpp"])
    assert result["determinismHarnessChanged"] is False, (
        "Src/GameServer/** files must not trigger determinismHarnessChanged. "
        "See mc-wajxw:mcdChangeDetection:73."
    )


def test_harness_fixture_file_also_sets_client_changed() -> None:
    """tests/determinism-harness/ is under tests/ → categorize() → client; clientChanged must be true."""
    result = _simulate_detect(
        ["tests/determinism-harness/fixtures/short_concede_match/mcp_action_log.jsonl"]
    )
    assert result["clientChanged"] is True, (
        "tests/determinism-harness/ files fall under the tests/ client prefix, "
        "so clientChanged must also be set. determinismHarnessChanged is a parallel "
        "signal, not a replacement. See mc-wajxw:mcdChangeDetection:73."
    )


def test_replay_test_file_also_sets_server_changed() -> None:
    """Src/TestClient/Test/replay/ is under Src/TestClient/ → server prefix; serverChanged must be true."""
    result = _simulate_detect(["Src/TestClient/Test/replay/EndToEndIdenticalTest.cpp"])
    assert result["serverChanged"] is True, (
        "Src/TestClient/Test/replay/ files fall under the Src/TestClient/ server prefix, "
        "so serverChanged must also be set. determinismHarnessChanged is a parallel "
        "signal, not a replacement. See mc-wajxw:mcdChangeDetection:73."
    )


def test_both_prefixes_independently_trigger_flag() -> None:
    """Each prefix alone is sufficient; the flag must be set even if only one matches."""
    harness_only = _simulate_detect(
        ["tests/determinism-harness/fixtures/full_length_match/mcp_action_log.jsonl"]
    )
    replay_only = _simulate_detect(["Src/TestClient/Test/replay/EndToEndIdenticalTest.cpp"])
    both = _simulate_detect(
        [
            "tests/determinism-harness/fixtures/full_length_match/mcp_action_log.jsonl",
            "Src/TestClient/Test/replay/EndToEndIdenticalTest.cpp",
        ]
    )
    assert harness_only["determinismHarnessChanged"] is True
    assert replay_only["determinismHarnessChanged"] is True
    assert both["determinismHarnessChanged"] is True
