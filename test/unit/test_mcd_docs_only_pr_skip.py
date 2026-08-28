"""A docs-only PR must not pay for build stages that cannot look at it.

Two halves, and the first is the one that actually cost real build time:

1. categorize() must classify `tools/` rather than let it fall through. Before
   this rule `tools/` matched nothing, returned 'unknown', landed in
   unmatchedFiles, and the fallback set serverChanged AND clientChanged. GH
   #2862 -- a README plus a review-capture tool -- therefore ran the full client
   AND server suite, and on merge triggered a real MCDServer-Main build. Same
   defect class as the Makefile rule (mc-lvzi): a path we know about arriving
   through the path meant for files we do not.

2. mcdPRValidationPipeline must compute DOCS_ONLY and gate the two stages that
   no per-component flag otherwise guards, WITHOUT gating the ADR Identifier
   Gate (a standalone ADR PR is exactly a docs-only PR, and that gate is the one
   check written to police it) and WITHOUT skipping the status post (an ABSENT
   required check blocks merge forever).

Run with: pytest test/unit/test_mcd_docs_only_pr_skip.py
No live Jenkins required -- these parse the Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

_REPO_ROOT = Path(__file__).parent.parent.parent
_DETECT = _REPO_ROOT / "vars" / "mcdChangeDetection.groovy"
_PRPIPE = _REPO_ROOT / "vars" / "mcdPRValidationPipeline.groovy"


def _detect_src() -> str:
    return _DETECT.read_text(encoding="utf-8")


def _pr_src() -> str:
    return _PRPIPE.read_text(encoding="utf-8")


def _doc_prefixes() -> list[str]:
    """The docPrefixes list literal from categorize()."""
    m = re.search(r"def docPrefixes = \[(.*?)\]", _detect_src(), re.S)
    assert m, "categorize() no longer declares a docPrefixes list"
    return re.findall(r"'([^']+)'", m.group(1))


# --- 1. tools/ is classified, not unmatched -------------------------------


def test_tools_is_an_explicit_docs_prefix() -> None:
    """`tools/` must be named, or it reaches server+client via the fallback."""
    assert "tools/" in _doc_prefixes(), (
        "categorize() must classify 'tools/' as docs. Without it the tree "
        "returns 'unknown', lands in unmatchedFiles, and the fallback sets "
        "serverChanged AND clientChanged -- so a review-tool edit runs the full "
        "client and server suite. GH #2862 is the worked example."
    )


def test_docs_prefixes_still_cover_the_original_three() -> None:
    """Adding tools/ must not displace the prefixes that were already there."""
    prefixes = _doc_prefixes()
    for expected in ("docs/", ".github/", "reports/"):
        assert expected in prefixes, f"docPrefixes lost {expected!r}"


def test_wiki_is_matched_before_the_docs_prefixes() -> None:
    """docs/wiki/ must stay 'wiki', not be swallowed by the 'docs/' prefix.

    Ordering is the whole mechanism: categorize() returns on first match, so the
    docs/wiki/ rule has to appear ABOVE the docPrefixes loop. If it ever moves
    below, docs/wiki/ silently becomes 'docs', wikiChanged stops being set, and
    mcdServicesPipeline stops rebuilding the wiki -- with no error anywhere.
    """
    src = _detect_src()
    wiki_at = src.index("filePath.startsWith('docs/wiki/')")
    docs_at = src.index("def docPrefixes")
    assert wiki_at < docs_at, (
        "docs/wiki/ must be matched BEFORE the generic 'docs/' prefix, or wiki "
        "content classifies as docs and the wiki deploy stops firing."
    )


# --- 2. the PR pipeline's docs-only short-circuit -------------------------


def _stage_guard(stage: str) -> str:
    """The `when { ... }` line immediately following a stage declaration."""
    src = _pr_src()
    i = src.index(f"stage('{stage}')")
    tail = src[i : i + 600]
    m = re.search(r"when \{([^\n]*)\}", tail)
    return m.group(0) if m else ""


def test_pipeline_computes_docs_only() -> None:
    assert "env.DOCS_ONLY" in _pr_src(), (
        "mcdPRValidationPipeline must compute env.DOCS_ONLY in 'Detect Changes'."
    )


def test_docs_only_is_derived_from_the_change_flags() -> None:
    """DOCS_ONLY must read the same flags the stage guards read.

    A second hand-written path list would drift out of step with the guards and
    start skipping stages that DO have something to look at.
    """
    src = _pr_src()
    m = re.search(r"def buildFlags = \[(.*?)\]", src, re.S)
    assert m, "DOCS_ONLY must be derived from a buildFlags list"
    body = m.group(1)
    for flag in ("serverChanged", "clientChanged", "proxyChanged",
                 "sharedChanged", "tutorialChanged", "mcpGameServerChanged"):
        assert flag in body, f"buildFlags omits changes.{flag}"
    assert "changedFiles" in src[m.start() : m.start() + 900], (
        "DOCS_ONLY must require a non-empty changedFiles, or a detection "
        "failure that returns no files would read as 'docs-only' and skip "
        "every stage."
    )


def test_docs_only_gates_the_two_otherwise_ungated_stages() -> None:
    for stage in ("Native Library ABI Check", "Per-module Go tests"):
        assert "DOCS_ONLY" in _stage_guard(stage), (
            f"stage {stage!r} has no per-component flag guarding it, so it runs "
            "on a docs-only PR unless DOCS_ONLY is in its when-expression."
        )


def test_docs_only_does_NOT_gate_the_adr_identifier_gate() -> None:
    """The ADR gate exists to police docs-only PRs; skipping it there is the bug.

    This is the direction that matters. A standalone ADR PR is exactly a
    docs-only PR, so gating this stage on DOCS_ONLY would disable the check
    precisely where it is the only thing running.
    """
    assert "DOCS_ONLY" not in _stage_guard("ADR Identifier Gate"), (
        "'ADR Identifier Gate' must NOT be gated on DOCS_ONLY -- a standalone "
        "ADR PR is a docs-only PR, and this gate is the one check written to "
        "police it. Its own comment in the pipeline says so."
    )


def test_the_required_status_is_still_posted() -> None:
    """A skipped build that posts nothing leaves the required check ABSENT.

    'jenkins/pr-validation' is a required check on main. An absent required
    check blocks merge forever, which is strictly worse than a slow build, so
    the status post must not sit behind any DOCS_ONLY guard.
    """
    src = _pr_src()
    i = src.index("statuses/${env.pr_head_sha}")
    window = src[max(0, i - 2500) : i]
    assert "DOCS_ONLY" not in window, (
        "The pr-validation status post must not be gated on DOCS_ONLY. A "
        "docs-only PR still has to receive a SUCCESS status, or the required "
        "check stays ABSENT and the PR can never merge."
    )
