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


# ---------------------------------------------------------------------------
# Persona packs and playtester docs reach the MCP guard tests (mc-5gbyxt, mc-28l686)
# ---------------------------------------------------------------------------
#
# mc-5gbyxt, from the architect ruling mc-28l686 (Finding 2), records that the
# persona guard tests live in the MCP Game Server Go suite, which the PR
# pipeline runs only when mcpGameServerChanged is set. Two path trees hold those
# guards' inputs and neither set the flag. Running the real categorize() and
# detect() under Groovy gave:
#
#   mcp/personas/**     matched no rule, so categorize() returned 'unknown' and
#                       the unmatched-file fallback set serverChanged and
#                       clientChanged but not mcpGameServerChanged.
#   docs/playtester/**  matched the 'docs/' entry of the docPrefixes loop, so
#                       categorize() returned 'docs' and no flag was set at all.
#                       A PR that touches only these files is then docs-only,
#                       because mcdPRValidationPipeline derives DOCS_ONLY from
#                       the same flags.
#
# The ruled fix is two prefix rules that return 'mcp-game-server', placed after
# the Src/MCPGameServer/ rule and ABOVE the docPrefixes loop: the 'docs/' entry
# would otherwise capture 'docs/playtester/' first.
#
# Like the mc-lvzi tests above, these read the source rather than a Python
# mirror, because a mirror would have to hand-copy the rule it is meant to
# check. The first three are regression tests, written to fail on the library as
# it stood before the rules existed. The last two are GUARDS: they pass before
# and after the fix and pin what the fix must leave alone, so a green guard says
# nothing about whether the fix is present.
#
# The rule tests pin the shape the acceptance criteria name: one executable
# `if (filePath.startsWith('<prefix>')) return 'mcp-game-server'` line per
# prefix. A combined `||` line or a prefix list would need these tests amended.

_CATEGORIZE_DEF = re.compile(r"^def\s+categorize\s*\(", re.M)
_DOC_PREFIXES_DEF = re.compile(r"^[ \t]*def\s+docPrefixes\b", re.M)


def _categorize_source() -> str:
    """The source from `def categorize(` on.

    Searching here rather than the whole file means a rule that sits somewhere
    else (in detect(), say) cannot satisfy a test about categorize().
    """
    src = _src()
    start = _CATEGORIZE_DEF.search(src)
    assert start is not None, (
        "mcdChangeDetection.groovy no longer has a `def categorize(` function. "
        "If the classifier was moved or renamed, update these tests. "
        "See mc-5gbyxt."
    )
    return src[start.start():]


def _prefix_rule(prefix: str, category: str) -> re.Pattern[str]:
    """An executable `if (filePath.startsWith('<prefix>')) return '<category>'`.

    Anchored to the start of a line with only blanks allowed before `if`, so a
    `//` comment that quotes the rule cannot satisfy it. A trailing `;` or `//`
    comment is allowed.
    """
    return re.compile(
        r"^[ \t]*if\s*\(\s*filePath\.startsWith\(\s*['\"]"
        + re.escape(prefix)
        + r"['\"]\s*\)\s*\)\s*return\s+['\"]"
        + re.escape(category)
        + r"['\"][ \t]*;?[ \t]*(?://.*)?$",
        re.M,
    )


def test_categorize_has_an_explicit_mcp_personas_rule() -> None:
    """REGRESSION (mc-5gbyxt): a file under mcp/personas/ is an MCP game server file."""
    rule = _prefix_rule("mcp/personas/", "mcp-game-server").search(_categorize_source())
    assert rule is not None, (
        "categorize() has no executable "
        "`if (filePath.startsWith('mcp/personas/')) return 'mcp-game-server'` "
        "line. Without it a persona pack file matches no rule, comes back "
        "'unknown', and the unmatched-file fallback sets serverChanged and "
        "clientChanged but NOT mcpGameServerChanged: a persona-only PR runs "
        "the server and client suites and never the MCP Game Server tests that "
        "guard the packs. A comment that quotes the rule does not count. "
        "See mc-5gbyxt and mc-28l686."
    )


def test_categorize_has_an_explicit_docs_playtester_rule() -> None:
    """REGRESSION (mc-5gbyxt): a file under docs/playtester/ is an MCP game server file."""
    rule = _prefix_rule("docs/playtester/", "mcp-game-server").search(_categorize_source())
    assert rule is not None, (
        "categorize() has no executable "
        "`if (filePath.startsWith('docs/playtester/')) return 'mcp-game-server'` "
        "line. Without it the 'docs/' entry of the docPrefixes loop captures "
        "the playtester docs, categorize() returns 'docs', no flag is set, and "
        "a PR that changes only those files is docs-only and skips the MCP "
        "Game Server tests that guard them. A comment that quotes the rule "
        "does not count. See mc-5gbyxt and mc-28l686."
    )


def test_persona_and_playtester_rules_sit_above_the_docs_prefix_loop() -> None:
    """REGRESSION (mc-5gbyxt): both rules must come before `def docPrefixes`.

    The 'docs/' entry captures docs/playtester/ first, so a rule placed below
    the loop never runs. Both rules must also EXIST before their order is
    compared, or this test would pass vacuously on a file that has neither.
    """
    src = _categorize_source()
    docs_loop = _DOC_PREFIXES_DEF.search(src)
    assert docs_loop is not None, (
        "categorize() no longer has a `def docPrefixes` line, so the order of "
        "the rules cannot be checked. If the docs loop was restructured, update "
        "this test. See mc-5gbyxt."
    )
    rules = {
        prefix: _prefix_rule(prefix, "mcp-game-server").search(src)
        for prefix in ("mcp/personas/", "docs/playtester/")
    }
    missing = [prefix for prefix, match in rules.items() if match is None]
    assert not missing, (
        f"No executable 'mcp-game-server' rule for: {', '.join(missing)}. Their "
        "order against `def docPrefixes` cannot be checked until both exist, "
        "and this test must not pass without them. See mc-5gbyxt."
    )
    for prefix, match in rules.items():
        assert match.start() < docs_loop.start(), (
            f"The '{prefix}' rule sits below `def docPrefixes`. Both rules "
            "belong above the docs loop (mc-28l686). For docs/playtester/ that "
            "is load-bearing: the 'docs/' entry returns 'docs' for the path "
            "before any rule below the loop runs. See mc-5gbyxt."
        )


def test_mcp_game_server_case_sets_only_its_own_flag() -> None:
    """GUARD, passes before and after the fix (mc-5gbyxt): the case stays narrow.

    The new rules send persona and playtester-doc paths to this case. If it
    also set serverChanged or clientChanged, those PRs would run the server and
    client suites for a change that touches neither, which is what the
    unmatched-file fallback did by accident. The regexes use a word boundary
    and `=(?!=)` so that `mcpGameServerChanged` does not read as a
    `serverChanged` assignment and a comparison does not read as an assignment.
    Trailing `//` comments are dropped first, so prose that names a flag does
    not read as one.
    """
    body = _case_blocks().get("mcp-game-server")
    assert body is not None, (
        "The category switch has no 'mcp-game-server' case, so a file "
        "categorized that way would hit `default` and be reported as "
        "unmatched. See mc-5gbyxt."
    )
    joined = "\n".join(re.sub(r"//.*$", "", ln) for ln in body)
    assert re.search(r"\bmcpGameServerChanged\s*=\s*true\b", joined), (
        "The 'mcp-game-server' case must set mcpGameServerChanged: it is the "
        "flag that gates the MCP Game Server tests. See mc-5gbyxt."
    )
    assert not re.search(r"\bserverChanged\s*=(?!=)", joined), (
        "The 'mcp-game-server' case must not set serverChanged. Persona and "
        "playtester-doc PRs would run the server suites for a change that "
        "touches none of the server. See mc-5gbyxt."
    )
    assert not re.search(r"\bclientChanged\s*=(?!=)", joined), (
        "The 'mcp-game-server' case must not set clientChanged. Persona and "
        "playtester-doc PRs would run the client suites for a change that "
        "touches none of the client. See mc-5gbyxt."
    )


def test_no_wholesale_mcp_or_docs_rule_and_wiki_stays_above_the_docs_loop() -> None:
    """GUARD, passes before and after the fix (mc-5gbyxt): the fix stays narrow.

    Two neighbours the fix must leave alone. No rule may send all of mcp/ or
    all of docs/ to 'mcp-game-server': that would run the MCP Game Server tests
    for unrelated paths and stop docs-only PRs from skipping them. And the
    docs/wiki/ rule must stay above the docs loop.
    """
    src = _categorize_source()
    for prefix in ("mcp/", "docs/"):
        wholesale = _prefix_rule(prefix, "mcp-game-server").search(src)
        assert wholesale is None, (
            f"categorize() sends all of '{prefix}' to 'mcp-game-server'. The "
            "ruling is two narrow prefixes, mcp/personas/ and docs/playtester/. "
            "A wholesale rule widens the MCP Game Server tests to unrelated "
            "paths. See mc-5gbyxt."
        )
    docs_loop = _DOC_PREFIXES_DEF.search(src)
    wiki = _prefix_rule("docs/wiki/", "wiki").search(src)
    assert docs_loop is not None and wiki is not None, (
        "categorize() must keep both its `docs/wiki/` rule and its "
        "`def docPrefixes` line so the order between them can be checked. If "
        "either was restructured, update this test. See mc-5gbyxt."
    )
    assert wiki.start() < docs_loop.start(), (
        "The docs/wiki/ rule must stay above `def docPrefixes`. Below it, wiki "
        "pages classify as 'docs', wikiChanged stops being set and the wiki "
        "stops being rebuilt. See mc-5gbyxt."
    )
