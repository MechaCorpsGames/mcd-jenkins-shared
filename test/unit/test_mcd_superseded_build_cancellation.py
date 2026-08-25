"""Tests for auto-cancelling superseded builds (mc-w2iu).

Tim, 2026-08-24: "when they get stacked up, we need to manually go cancel
builds until we're building only the latest, or just have patience." The mayor
watched several PRs burn full 20-25 minute builds that the next push had
already superseded, and at least one agent spend an hour reasoning about a RED
result that was not for the head commit. Wasted executor time is the smaller
cost; the real cost is a stale build's verdict being attributed to the wrong
commit.

WHAT THIS FILE PINS, AND WHY EACH HALF MATTERS.

1. mcdPRValidationPipeline carries disableConcurrentBuilds(abortPrevious: true).
   Plain disableConcurrentBuilds() is NOT the same control and is not
   sufficient: it only SERIALIZES, so a superseded build still runs to
   completion and the newer one waits behind it. That is the exact symptom
   being cancelled by hand, just moved into the queue. A future edit that drops
   the argument would look harmless and would silently restore the stacking, so
   the argument is pinned, not merely the call.

2. mcdServerPipeline and mcdClientPipeline must NOT carry abortPrevious.
   This half is the safety interlock and it is the reason this file exists
   rather than a one-line diff. abortPrevious aborts the older running build
   WHEREVER IT IS; Jenkins offers no stage scoping. Both of those pipelines
   publish through rsync:

     * mcdServerPipeline, 'Deploy GameServer & TestClient', rsyncs into
       config.deployPath. mc-ehn1 records that six jobs across three pipelines
       already share that path with no cross-job lock, and mc-mhjd's protocol
       manifest now lives in those same version directories with an explicit
       requirement that the manifest and the binary never deploy apart.
     * mcdClientPipeline, 'Publish Bot Runtime', runs rsync -a --delete into a
       shared per-env path. The comment on its own disableConcurrentBuilds()
       records that the serialization exists BECAUSE that rsync already blew up
       once, on .core.XYZ temp files during the MCDClient-FeatureBackend 21:01
       burst, and concludes that waiting 10-15 minutes beats a silent race.

   Killing either mid-rsync produces the half-written state those controls were
   added to prevent. So the absence of abortPrevious there is a DECISION, and
   an future edit that "finishes the job" by adding it to all three pipelines
   is the failure this test catches.

WHY NOT milestone() OR A SELF-SKIP, pinned here so the next reader does not
re-litigate it. Both need the NEWER build to be running, and on a serialized
pipeline the newer build is queued with no Run object at all. milestone()
aborts older builds that have not yet PASSED the milestone, which spares the
build eighteen minutes into validation and cancels only the one that has cost
nothing yet. A self-skip in mcdSteamUploadPipeline's UPLOAD_SUPERSEDED style
would test currentBuild.nextBuild, which is null for a build that has not
started, i.e. null exactly when it is needed.

Run with: pytest test/unit/test_mcd_superseded_build_cancellation.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_PR_SRC = _VARS / "mcdPRValidationPipeline.groovy"

# The pipelines that publish through rsync and therefore must never be
# interrupted at an arbitrary point. Value is the stage whose rsync is the
# hazard, quoted in the failure message so a future editor is told WHY rather
# than just told no.
_PUBLISHING_PIPELINES = {
    "mcdServerPipeline.groovy": "Deploy GameServer & TestClient",
    "mcdClientPipeline.groovy": "Publish Bot Runtime",
}


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-w2iu."
        )
    return path.read_text()


def _options_block(src: str) -> str:
    """The body of the pipeline-level options { } block.

    Brace-matched rather than regexed to a closing line, because the block
    contains nested calls and prose comments with braces in them.
    """
    start = src.index("options {")
    depth = 0
    for index in range(start, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start : index + 1]
    raise AssertionError("unterminated options block")


def _uncommented(block: str) -> str:
    """The block with // comment tails removed.

    Every pipeline in this library explains its concurrency choice in prose
    directly above the call, and those comments NAME the thing they are
    explaining. Matching against raw source would let a comment satisfy an
    assertion that the actual option is missing, which is the mistake
    test_mcd_build_retention.py exists to punish in the other direction.
    """
    return "\n".join(
        re.sub(r"//.*$", "", line) for line in block.splitlines()
    )


def test_pr_validation_aborts_the_superseded_build() -> None:
    block = _uncommented(_options_block(_src(_PR_SRC)))
    assert re.search(r"disableConcurrentBuilds\(\s*abortPrevious:\s*true\s*\)", block), (
        "mcdPRValidationPipeline lost disableConcurrentBuilds(abortPrevious: true). "
        "Superseded PR builds will run to completion again (20-25 minutes each), "
        "and a stale verdict will be reported against the wrong commit. See mc-w2iu."
    )


def test_pr_validation_does_not_merely_serialize() -> None:
    """A bare disableConcurrentBuilds() is the bug, not the fix.

    It queues the newer build behind the superseded one instead of cancelling
    anything, which is the hand-cancelling Tim is doing today.
    """
    block = _uncommented(_options_block(_src(_PR_SRC)))
    bare = re.findall(r"disableConcurrentBuilds\(\s*\)", block)
    assert not bare, (
        "mcdPRValidationPipeline has a bare disableConcurrentBuilds(). That only "
        "SERIALIZES: the superseded build still runs to completion and the newer "
        "one waits behind it. It needs abortPrevious: true. See mc-w2iu."
    )


@pytest.mark.parametrize("filename,hazard_stage", sorted(_PUBLISHING_PIPELINES.items()))
def test_publishing_pipelines_never_abort_mid_rsync(filename: str, hazard_stage: str) -> None:
    """The safety interlock. Do not "finish the job" by adding this everywhere.

    abortPrevious has no stage scoping, so it can interrupt the rsync these
    pipelines publish through and leave the half-written state their existing
    controls were added to prevent.
    """
    src = _src(_VARS / filename)
    assert "abortPrevious" not in _uncommented(_options_block(src)), (
        f"{filename} gained abortPrevious. It aborts the older build wherever it "
        f"is, including inside '{hazard_stage}', whose rsync must not be "
        "interrupted (mc-ehn1 shared deploy path, mc-mhjd manifest-with-binary, "
        "and the .core.XYZ rsync --delete race already recorded on "
        "mcdClientPipeline's own options block). Make the publish step "
        "interrupt-safe first. See mc-w2iu."
    )


def test_client_pipeline_keeps_its_serialization() -> None:
    """Removing the plain call is as bad as upgrading it.

    mcdClientPipeline's disableConcurrentBuilds() is not tidiness: it is what
    stops two Publish Bot Runtime rsyncs racing on the same path, and Lockable
    Resources is not installed here to do it properly (confirmed by #64's DSL
    error listing the valid steps, with lock absent).
    """
    block = _uncommented(_options_block(_src(_VARS / "mcdClientPipeline.groovy")))
    assert "disableConcurrentBuilds" in block, (
        "mcdClientPipeline lost disableConcurrentBuilds(). Two builds can then "
        "run Publish Bot Runtime concurrently and race on the same rsync "
        "--delete target, which is the failure that put it there. See mc-w2iu."
    )


def test_the_reason_is_recorded_next_to_the_option() -> None:
    """A control that does not say what it protects gets removed by the next reader.

    test_mcd_build_retention.py treats a wrong comment as a failure for the same
    reason: the explanation is the half a future editor acts on.
    """
    block = _options_block(_src(_PR_SRC))
    for token in ("mc-w2iu", "abortPrevious", "rsync"):
        assert token in block, (
            f"mcdPRValidationPipeline's options block no longer mentions '{token}'. "
            "The next person to read it will not know why the two publishing "
            "pipelines are deliberately excluded, and will add it to them."
        )
