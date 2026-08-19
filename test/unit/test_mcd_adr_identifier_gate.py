"""Tests for the 'ADR Identifier Gate' stage in mcdPRValidationPipeline.groovy (mc-7i1o).

The stage runs scripts/check_adr_ids.py on every PR, failing a change that adds
an ADR reverting to the retired NNNN- scheme or reusing an identifier that
already exists on the base ref.

The property worth protecting here is the `when` condition. Every other script
check in this pipeline is gated on CLIENT_CHANGED, and docs/** classifies as
'docs' in mcdChangeDetection, so a docs-only PR leaves CLIENT_CHANGED false. A
standalone ADR PR IS a docs-only PR, so copying the surrounding gate would have
made the one check written to police ADRs skip exactly those PRs. If someone
later "tidies" this stage to match its neighbours, these tests fail.

Run with: pytest test/unit/test_mcd_adr_identifier_gate.py
No live Jenkins required: the tests parse the Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_PR_PIPELINE_SRC = _REPO_ROOT / "vars" / "mcdPRValidationPipeline.groovy"
_STAGE_NAME = "ADR Identifier Gate"


def _src() -> str:
    if not _PR_PIPELINE_SRC.exists():
        pytest.fail(f"{_PR_PIPELINE_SRC} not found. Run from the mcd-jenkins-shared repo root.")
    return _PR_PIPELINE_SRC.read_text()


def _stage_block(name: str) -> str:
    """The source of one stage, from its declaration to the next stage's."""
    src = _src()
    start = src.index(f"stage('{name}')")
    nxt = src.find("            stage('", start + 1)
    return src[start : nxt if nxt != -1 else len(src)]


def test_the_stage_exists():
    assert f"stage('{_STAGE_NAME}')" in _src()


def test_the_stage_is_not_gated_on_client_changed():
    """The whole point: a docs-only ADR PR must still be checked."""
    block = _stage_block(_STAGE_NAME)
    when = re.search(r"when \{[^\n]*\}", block)
    assert when, "stage has no when { } condition"
    assert "CLIENT_CHANGED" not in when.group(0), (
        "The ADR gate must NOT be gated on CLIENT_CHANGED. docs/** is classified "
        "'docs' by mcdChangeDetection, so a standalone ADR PR would skip it."
    )
    assert "SERVER_CHANGED" not in when.group(0)


def test_the_stage_still_skips_an_already_merged_pr():
    when = re.search(r"when \{[^\n]*\}", _stage_block(_STAGE_NAME))
    assert "PR_ALREADY_MERGED" in when.group(0)


def test_the_stage_names_the_base_ref_explicitly():
    """Guessing the base would compare a features/backend PR against main.

    check_adr_ids.py treats a named base as authoritative, and 'Checkout PR
    Merge Ref' fetches origin/<targetBranch>, so that ref is the true base.
    """
    block = _stage_block(_STAGE_NAME)
    assert "--base origin/${config.targetBranch}" in block


def test_the_stage_carries_the_branch_skew_guard():
    """release / features/* lag main and lack the target until this merges."""
    block = _stage_block(_STAGE_NAME)
    assert "make -n check-adr-ids" in block
    assert "exit 0" in block


def test_the_stage_runs_before_the_expensive_ones():
    """A sub-second check that needs no build should fail fast."""
    src = _src()
    assert src.index(f"stage('{_STAGE_NAME}')") < src.index("stage('GDScript Tests')")
    assert src.index(f"stage('{_STAGE_NAME}')") < src.index("stage('Setup Dependencies')")


def test_the_stage_runs_after_checkout_has_fetched_the_base():
    src = _src()
    assert src.index("stage('Checkout PR Merge Ref')") < src.index(f"stage('{_STAGE_NAME}')")
