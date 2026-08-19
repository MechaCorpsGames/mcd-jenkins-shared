"""Tests for the UI Tests (Xvfb) stage (mc-qc90).

MCDClient ships .Jenkins/Jenkinsfile.ui-tests, which runs the GdUnit4 suite under
a virtual framebuffer so Control nodes render and mouse interactions behave
realistically. Its header claimed "Triggered by: PR validation pipelines or
manual run". The first half was never true: no file in vars/ has ever mentioned
Xvfb, test-gdscript-ui, or that Jenkinsfile. So every Control render, mouse
interaction and focus path has been unverified at PR time, while a green
jenkins/pr-validation reads as "the client is tested".

This stage closes that in the job that demonstrably exists, rather than by
triggering one whose existence nobody has confirmed.

Three properties, and they pull against each other on purpose:

1. It must actually run the suite, gated the same way as the other client
   stages, so it costs nothing on a server-only PR.
2. It must be OPT-IN per branch. Until MCDClient's mc-1zjn fix reaches a branch,
   `make test-gdscript-ui` runs headless-asserting tests under a real display and
   cannot pass there on any commit. This library is shared by MCD-PR-Main and
   MCD-PR-Release, so an unconditional stage would red-line release PRs on
   arrival. Same skew problem as Script Tests (mc-lxj5) and TestClient Unit
   Tests (mc-plov), solved here with a config flag rather than a target probe,
   because the target EXISTS on those branches and is simply red.
3. The opt-in must not become a failure swallow. A missing xvfb-run FAILS the
   stage. A UI gate that quietly does nothing is the exact defect mc-qc90 was
   filed for, and it is the defect mc-fiu5 found in a whole Jenkinsfile.

Run with: pytest test/unit/test_mcd_ui_tests_stage.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_PR_SRC = _VARS / "mcdPRValidationPipeline.groovy"

_STAGE = "UI Tests (Xvfb)"


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-qc90."
        )
    return path.read_text()


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-qc90."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-qc90."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-qc90.")


# ---------------------------------------------------------------------------
# It must actually run the suite
# ---------------------------------------------------------------------------


def test_stage_exists() -> None:
    assert f"stage('{_STAGE}')" in _src(_PR_SRC), (
        f"mcdPRValidationPipeline lost the {_STAGE!r} stage. Without it the "
        "Xvfb suite goes back to running nowhere, which is the state mc-qc90 "
        "was filed for: .Jenkins/Jenkinsfile.ui-tests exists and nothing "
        "triggers it."
    )


def test_stage_invokes_the_ui_target() -> None:
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "make test-gdscript-ui" in body, (
        f"{_STAGE} must invoke `make test-gdscript-ui`. That target is the one "
        "thing that runs the suite under a real display. See mc-qc90."
    )


def test_stage_is_gated_on_client_changes() -> None:
    """A server-only PR must not pay for Xvfb (mc-qc90)."""
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "CLIENT_CHANGED" in body, (
        f"{_STAGE} must be gated on CLIENT_CHANGED, matching GDScript Tests "
        "and Card Validator Tests above it."
    )
    assert "PR_ALREADY_MERGED" in body, (
        f"{_STAGE} must skip an already-merged PR like every other stage here."
    )


# ---------------------------------------------------------------------------
# It must be opt-in per branch
# ---------------------------------------------------------------------------


def test_stage_is_opt_in_per_branch() -> None:
    """Off unless a Jenkinsfile asks for it (mc-qc90).

    release and the feature branches have the target but not the mc-1zjn fix, so
    the suite cannot pass there. An unconditional stage would red-line their PRs
    the moment this merges.
    """
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "config.uiTests?.enabled == true" in body, (
        f"{_STAGE} must check `config.uiTests?.enabled == true`. The safe "
        "sense matters: an absent config must mean OFF, so a branch that never "
        "heard of this flag is unaffected. See mc-qc90."
    )


def test_no_pipeline_turns_it_on_by_default() -> None:
    """The default lives in the CLIENT repo's Jenkinsfile, not here (mc-qc90)."""
    for groovy in sorted(_VARS.glob("*.groovy")):
        text = groovy.read_text()
        assert "uiTests: [enabled: true" not in text.replace(" ", " "), (
            f"{groovy.name} enables uiTests inside the shared library. The "
            "switch belongs to the consuming repo's Jenkinsfile, which is what "
            "keeps it per-branch."
        )


# ---------------------------------------------------------------------------
# The opt-in must not become a failure swallow
# ---------------------------------------------------------------------------


def test_a_missing_xvfb_fails_rather_than_skips() -> None:
    """The whole point is that a UI gate reports (mc-qc90).

    `.Jenkins/Jenkinsfile.pr.feature-backend` read like a full gate and ran
    nothing for months (mc-fiu5). A stage that quietly no-ops when the agent
    lacks xvfb would be the same shape, one level down.
    """
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "command -v xvfb-run" in body, (
        f"{_STAGE} must probe for xvfb-run so the failure names its own cause "
        "instead of surfacing as a confusing make error."
    )
    assert "exit 1" in body, (
        f"{_STAGE} must FAIL when xvfb-run is missing, not skip. See mc-qc90."
    )
    assert "|| true" not in body, (
        f"{_STAGE} must not swallow a failure. A green check that means nothing "
        "is what mc-fiu5 and mc-qc90 are both about."
    )


def test_results_are_published() -> None:
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "junit" in body, f"{_STAGE} must publish its JUnit XML."
    assert "gdscript-ui.xml" in body, (
        f"{_STAGE} must publish test-results/gdscript-ui.xml, which is what "
        "`make test-gdscript-ui` writes."
    )
