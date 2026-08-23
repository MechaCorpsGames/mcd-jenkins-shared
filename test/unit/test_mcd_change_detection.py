"""Tests for mcdChangeDetection.groovy (mc-wajxw, mc-lvzi).

Verifies vars/mcdChangeDetection.groovy sets determinismHarnessChanged for the
two path prefixes that constitute the determinism-harness source tree:
  - tests/determinism-harness/**   (fixture files)
  - Src/TestClient/Test/replay/**  (replay integration tests)

Tests also verify interaction with the categorize() path — harness files are
already claimed by other categories (tests/ → client, Src/TestClient/ → server),
so determinismHarnessChanged is a parallel flag set independently of categorize().

The second half covers the category switch itself (mc-lvzi): that every case
terminates, and that the root Makefile has a rule rather than reaching its
classification through the unmatched-file fallback.

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


# ---------------------------------------------------------------------------
# Switch integrity: every case must terminate (mc-lvzi)
# ---------------------------------------------------------------------------
#
# Groovy switch cases fall through exactly like C. The 'docker-smoke' case was
# written without a break and fell into 'mcp-game-server', so every compose
# file, every edit to scripts/docker_dev.py and every tests/e2e smoke fixture
# also set mcpGameServerChanged and ran the MCP Game Server Go suite. Every
# other case in the switch breaks, and the docker-smoke comment says nothing
# about the MCP game server, so it was a slip rather than a decision.
#
# Reading the switch is what catches this. A behavioural mirror in Python
# cannot: it would have to hand-copy the fall-through to reproduce the bug.

_CASE_LINE = re.compile(r"^\s*(?:case\s+'([a-z0-9-]+)'|default)\s*:")
_TERMINATOR = re.compile(r"^\s*(break|return|throw)\b")


def _switch_body() -> list[str]:
    """The lines inside `switch (category) { ... }`, brace-counted."""
    src = _src()
    lines = src.splitlines()
    start = next(
        (i for i, ln in enumerate(lines) if re.match(r"\s*switch\s*\(\s*category\s*\)", ln)),
        None,
    )
    assert start is not None, (
        "mcdChangeDetection.groovy no longer has a `switch (category)` block. "
        "If the dispatch was restructured, update this test. See mc-lvzi."
    )
    depth = 0
    body = []
    for ln in lines[start:]:
        depth += ln.count("{") - ln.count("}")
        body.append(ln)
        if depth == 0 and len(body) > 1:
            return body
    raise AssertionError("Unbalanced braces in the category switch. See mc-lvzi.")


def _case_blocks() -> dict[str, list[str]]:
    """Map each case label to the lines belonging to it, comments stripped."""
    blocks: dict[str, list[str]] = {}
    label = None
    for ln in _switch_body():
        match = _CASE_LINE.match(ln)
        if match:
            label = match.group(1) or "default"
            blocks[label] = []
            continue
        if label is not None and not ln.strip().startswith("//"):
            blocks[label].append(ln)
    return blocks


def test_switch_parses_into_recognisable_cases() -> None:
    """Guard the guard: a parser that finds nothing makes the checks vacuous."""
    blocks = _case_blocks()
    assert len(blocks) >= 10, (
        f"Only parsed {len(blocks)} cases out of the category switch, which is "
        "fewer than the library has. The parser is likely broken, so the "
        "termination check below would pass vacuously. See mc-lvzi."
    )
    for expected in ("server", "client", "docker-smoke", "mcp-game-server", "default"):
        assert expected in blocks, (
            f"Case '{expected}' missing from the parsed switch. See mc-lvzi."
        )


def test_every_switch_case_terminates() -> None:
    """No case may fall through into the next one (mc-lvzi)."""
    fell_through = [
        label
        for label, body in _case_blocks().items()
        if not any(_TERMINATOR.match(ln) for ln in body)
    ]
    assert not fell_through, (
        f"These cases never break: {', '.join(sorted(fell_through))}. Groovy "
        "falls through to the next case, so each of these silently sets the "
        "flags of whatever case follows it. That is how docker-smoke files came "
        "to trigger the MCP Game Server suite. See mc-lvzi."
    )


def test_docker_smoke_does_not_reach_the_mcp_game_server_case() -> None:
    """The specific fall-through, pinned by name (mc-lvzi)."""
    blocks = _case_blocks()
    body = blocks["docker-smoke"]
    assert any(_TERMINATOR.match(ln) for ln in body), (
        "The 'docker-smoke' case must break. Without it every docker/**, "
        "scripts/docker_dev.py and tests/e2e smoke fixture also sets "
        "mcpGameServerChanged and runs the MCP Game Server Go suite on a PR "
        "that touches no Go. See mc-lvzi."
    )
    assert not any("mcpGameServerChanged" in ln for ln in body), (
        "The 'docker-smoke' case must not set mcpGameServerChanged. The docker "
        "smoke stack and the MCP game server are unrelated. See mc-lvzi."
    )


# ---------------------------------------------------------------------------
# The root Makefile has a rule, not a fallback (mc-lvzi)
# ---------------------------------------------------------------------------
#
# 'Makefile' matched no rule in categorize(): not the docker-smoke exact list,
# no shared/server/client prefix, not the client or doc exact lists, and the
# final root-level rule requires a '.uid' or '_audit.gd' suffix. It returned
# 'unknown', landed in unmatchedFiles, and the fallback set serverChanged AND
# clientChanged, so editing the Makefile built the GameServer.
#
# The fan-out is kept, because the Makefile really does drive both halves of
# the build. What changes is that it is now a stated rule instead of a file we
# know about arriving through the path meant for files we do not.


def test_makefile_has_an_explicit_categorize_rule() -> None:
    """categorize() must name the Makefile rather than let it fall through (mc-lvzi)."""
    src = _src()
    assert re.search(r"filePath\s*==\s*'Makefile'", src), (
        "categorize() must classify 'Makefile' explicitly. Without a rule it "
        "returns 'unknown' and reaches server+client through the unmatched-file "
        "fallback, which also logs it as unmatched and hides that this is the "
        "intended classification. See mc-lvzi."
    )


def test_build_system_case_triggers_both_builds() -> None:
    """The Makefile's category must fan out to server and client (mc-lvzi)."""
    body = _case_blocks().get("build-system")
    assert body is not None, (
        "The category switch has no 'build-system' case, so a file categorized "
        "as 'build-system' would hit `default` and be reported as unmatched. "
        "See mc-lvzi."
    )
    joined = "\n".join(body)
    assert re.search(r"serverChanged\s*=\s*true", joined), (
        "'build-system' must set serverChanged: the Makefile carries the "
        "'server', 'proxy' and 'testclient' targets. See mc-lvzi."
    )
    assert re.search(r"clientChanged\s*=\s*true", joined), (
        "'build-system' must set clientChanged: the Makefile carries the 'ext', "
        "'test-gdscript' and 'export-done' targets. See mc-lvzi."
    )
