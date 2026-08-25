"""Non-PR builds trim to latest without interrupting a publish (mc-waxw).

Tim, 2026-08-25: "for all builds other than PR builds we should be trimming to
latest."

Every branch pipeline carries disableConcurrentBuilds(), which serializes and
never trims: a burst of pushes queues one build per push and each then does the
full 20 minutes of work. They all check out the branch TIP rather than the
commit their own webhook carried, so the ones behind the first are doing
identical work for nothing.

The obvious one-line answer, abortPrevious, is forbidden here and
test_mcd_superseded_build_cancellation.py pins that: it aborts the older build
wherever it is, with no stage scoping, and both big pipelines publish through
rsync. So the trim is a self-skip that only ever stands down work an EARLIER
build of the same job already finished successfully, and it happens before any
publish step.

WHAT THIS FILE PINS.

1. Each branch pipeline has the trim, it runs AFTER Detect Changes, and it runs
   BEFORE anything that publishes.

2. The trim clears EVERY gate flag that pipeline uses. This is the one that will
   actually catch a regression, and it is why the flags are enumerated from the
   source rather than hardcoded here: the failure is silent. Add a stage gated on
   a new FOO_CHANGED, forget to clear it in the trim, and a trimmed build still
   runs that one stage, with a NOT_BUILT badge saying it did nothing. If the
   stage deploys, a build that reported "trimmed" deployed.

3. The trim never aborts anything, and skips only on SUCCESS. A build still
   running might yet fail; a failed or NOT_BUILT build did not necessarily
   publish. Neither is evidence the work is done.

4. A trimmed build is visibly NOT_BUILT, never silently green.

Run with: pytest test/unit/test_mcd_trim_non_pr_builds_to_latest.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_HELPER = _VARS / "mcdRedundantBuild.groovy"

_TRIM_STAGE = "stage('Trim to Latest')"

# The webhook-triggered branch pipelines. Value is a stage that publishes,
# quoted in the failure message so the ordering assertion says what it protects.
#
# mcdPlayUploadPipeline and mcdPromotePipeline are deliberately absent: they are
# run by a person choosing a build to ship, and mcdSteamUploadPipeline already
# coalesces on artifact freshness (mc-fr2h). Trimming a run somebody started by
# hand would break the one case where they are watching.
_BRANCH_PIPELINES = {
    "mcdClientPipeline.groovy": "Publish Bot Runtime",
    "mcdServerPipeline.groovy": "Deploy GameServer & TestClient",
    "mcdServicesPipeline.groovy": "Deploy CrashReporting + MCP",
    "mcdAppServicesPipeline.groovy": "Deploy Auth",
    "mcdDiscordBotPipeline.groovy": "Deploy",
}


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-waxw."
        )
    return path.read_text()


def _uncommented(src: str) -> str:
    """Source with // comment tails removed.

    mcdRedundantBuild argues at length about abortPrevious and names it
    repeatedly, so a raw substring check for the forbidden calls reads the
    argument as the call. The sibling test file keeps an identical helper for the
    same reason, and this is the second time that prose has tripped a check: the
    first draft of test_the_trim_never_interrupts_a_running_build failed on its
    own explanation.
    """
    return "\n".join(re.sub(r"//.*$", "", line) for line in src.splitlines())


def _matched_block(src: str, start: int) -> str:
    """From the first '{' at or after `start`, the brace-matched block."""
    opened = src.index("{", start)
    depth = 0
    for index in range(opened, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[opened : index + 1]
    raise AssertionError("unterminated block")


def _gate_flags(src: str) -> set[str]:
    """Every env FOO_CHANGED flag this pipeline gates a stage on.

    Read out of the when{} blocks by brace matching, including the ones nested
    inside parallel branches, so a flag cannot hide from the coverage check by
    living one level down.
    """
    flags: set[str] = set()
    for match in re.finditer(r"\bwhen\s*\{", src):
        block = _matched_block(src, match.start())
        flags.update(re.findall(r"env\.([A-Z0-9_]+_CHANGED)\b", block))
    return flags


def _trim_stage(src: str) -> str:
    index = src.find(_TRIM_STAGE)
    assert index != -1, "no Trim to Latest stage"
    return _matched_block(src, index)


@pytest.mark.parametrize("filename", sorted(_BRANCH_PIPELINES))
def test_branch_pipeline_trims_to_latest(filename: str) -> None:
    src = _src(_VARS / filename)
    assert _TRIM_STAGE in src, (
        f"{filename} has no 'Trim to Latest' stage. A burst of pushes queues one "
        "build per push behind its disableConcurrentBuilds(), and every one of "
        "them checks out the same branch tip and repeats the same work. See "
        "mc-waxw."
    )
    assert "mcdRedundantBuild.trim()" in src, (
        f"{filename}'s trim stage no longer calls mcdRedundantBuild.trim(). "
        "That helper is what makes the skip safe: it stands a build down only "
        "when an earlier build of the same job already built the same commit "
        "and succeeded."
    )


@pytest.mark.parametrize("filename", sorted(_BRANCH_PIPELINES))
def test_the_trim_clears_every_gate_flag(filename: str) -> None:
    """The silent failure. A missed flag leaves one stage running.

    The trim works by clearing the flags that gate the pipeline's work, which
    routes the build down the same no-op path it takes when a push touches
    nothing it owns. A flag it does not clear is a stage that still runs inside a
    build labelled 'trimmed'.
    """
    src = _src(_VARS / filename)
    required = _gate_flags(src)
    assert required, (
        f"{filename} appears to gate no stage on a *_CHANGED flag. If the gating "
        "idiom changed, this coverage check has to follow it, because the trim "
        "works by clearing those flags."
    )

    stage = _trim_stage(src)
    cleared = set(re.findall(r"env\.([A-Z0-9_]+_CHANGED)\s*=\s*'false'", stage))
    missing = required - cleared
    assert not missing, (
        f"{filename}'s 'Trim to Latest' stage does not clear {sorted(missing)}. "
        "Every stage gated on one of those still runs in a build that reports "
        "itself trimmed, and if one of them publishes, a build that did 'nothing' "
        "deployed. Clear it in the trim stage, next to the others. See mc-waxw."
    )


@pytest.mark.parametrize("filename", sorted(_BRANCH_PIPELINES))
def test_the_trim_runs_after_detect_changes(filename: str) -> None:
    """Order matters, and getting it wrong looks like nothing at all.

    Detect Changes ASSIGNS the gate flags. Put the trim before it and the trim's
    cleared flags are overwritten a moment later, the build runs in full, and the
    only symptom is that trimming silently never happens.
    """
    src = _src(_VARS / filename)
    detect = src.find("stage('Detect Changes')")
    trim = src.find(_TRIM_STAGE)
    assert detect != -1, f"{filename} lost its Detect Changes stage"
    assert trim > detect, (
        f"{filename} runs 'Trim to Latest' before 'Detect Changes'. Detect "
        "Changes assigns the gate flags, so it would overwrite everything the "
        "trim cleared and the trim would silently do nothing. See mc-waxw."
    )


@pytest.mark.parametrize(
    "filename,publish_stage", sorted(_BRANCH_PIPELINES.items())
)
def test_the_trim_runs_before_anything_publishes(filename: str, publish_stage: str) -> None:
    """The constraint the whole design is shaped around.

    A trim that ran after a publish would be pointless at best. The reason
    abortPrevious was rejected is that it can land mid-rsync; a self-skip that
    happened late would reintroduce the same hazard with extra steps.
    """
    src = _src(_VARS / filename)
    trim = src.find(_TRIM_STAGE)
    publish = src.find(f"stage('{publish_stage}")
    if publish == -1:
        pytest.skip(f"{filename} has no stage named '{publish_stage}' to order against")
    assert trim < publish, (
        f"{filename} runs 'Trim to Latest' after '{publish_stage}'. The trim has "
        "to happen before anything publishes: that is the whole reason it is a "
        "self-skip and not abortPrevious. See mc-waxw."
    )


def test_the_trim_never_interrupts_a_running_build() -> None:
    """It stands ITSELF down. It does not reach into another run.

    Reaching into another build to stop it needs a privileged Jenkins API, and
    nothing in this library has ever used one. mcdSteamSourceBuild.groovy records
    the standing rule for exactly this: an unverifiable dependency is not one to
    take blind, because a shared var that dies at runtime takes every job with it.
    """
    src = _uncommented(_src(_HELPER))
    for forbidden in ("doStop", "rawBuild", "Jenkins.get", "Jenkins.instance", "abortPrevious"):
        assert forbidden not in src, (
            f"mcdRedundantBuild uses '{forbidden}'. The trim must only ever skip "
            "its OWN work: these pipelines publish through rsync, and stopping a "
            "build wherever it happens to be is the thing that is forbidden here "
            "(mc-ehn1, mc-mhjd, and the .core.XYZ race on Publish Bot Runtime). "
            "See mc-waxw."
        )


def test_the_trim_only_stands_down_for_completed_successful_work() -> None:
    """The safety property. Skip only what is provably already done.

    A build still running might yet fail. A failed or NOT_BUILT build did not
    necessarily publish. If the trim ever skipped on the mere EXISTENCE of
    another build for the commit, a lost webhook or a failed build would leave
    the deploy stranded with nothing to replace it.
    """
    src = _src(_HELPER)
    assert "'SUCCESS'" in src, (
        "mcdRedundantBuild no longer requires the earlier build to have "
        "SUCCEEDED. It would then skip work that was started and abandoned, and "
        "nothing would deploy that commit. See mc-waxw."
    )
    assert "previousBuild" in src, (
        "mcdRedundantBuild no longer looks BACKWARDS. Looking forward is what "
        "the PR pipeline does, and it only works there because that pipeline "
        "does not serialize: behind disableConcurrentBuilds the newer builds are "
        "queued with no Run object to see. See mc-waxw."
    )
    assert "UserIdCause" in src, (
        "mcdRedundantBuild no longer exempts manually triggered builds. Somebody "
        "pressing Build is usually asking to redeploy the current tip on purpose, "
        "which is the one case where a person is watching. mcdSteamSourceBuild "
        "exempts them for the same reason."
    )


def test_a_trimmed_build_is_visibly_skipped() -> None:
    """Never silently green.

    A trimmed build that looked like a normal pass is worse than the duplicate
    work it replaced: the next person asking "did that commit deploy?" gets a yes
    from a build that did nothing. mcdSteamUploadPipeline sets the same three
    things for the same reason.
    """
    src = _uncommented(_src(_HELPER))
    assert "NOT_BUILT" in src, (
        "a trimmed build no longer reports NOT_BUILT, so it will read as a pass."
    )
    assert "displayName" in src and "description" in src, (
        "a trimmed build no longer says so in its display name and description. "
        "That is the only place a person looking at the job list can see why a "
        "build did nothing."
    )
