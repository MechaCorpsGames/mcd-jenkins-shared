"""Tests for trimming superseded builds (mc-w2iu, then mc-waxw).

Tim, 2026-08-24: "when they get stacked up, we need to manually go cancel
builds until we're building only the latest, or just have patience."
Tim, 2026-08-25: "for all builds other than PR builds we should be trimming to
latest. PR builds should only trim to latest when there are duplicate builds
for a single PR, otherwise, all of them should run."

WHY THIS FILE CHANGED SHAPE, AND WHAT IT USED TO SAY.

mc-w2iu answered the first quote with
`disableConcurrentBuilds(abortPrevious: true)` on mcdPRValidationPipeline, and
this file pinned that option, including a test asserting a bare
`disableConcurrentBuilds()` was the bug. That option did exactly what it says
and was still wrong, for a reason that is only visible from the job layout
rather than from the pipeline source: it is scoped to the JOB, and MCD-PR-Main
is ONE job serving EVERY open pull request. So a push to any PR aborted the
in-flight build of an unrelated one.

Measured on MCD-PR-Main, 2026-08-25, inside one hour: #1757 (PR-2708), #1759
(PR-2716), #1761 (PR-2714), #1762 (PR-2718) and #1766 (PR-2720) all ended
NOT_BUILT. And an abortPrevious kill records NOT_BUILT, which Declarative's
post{aborted} does not fire on, so nothing replaced the 'pending' status posted
at the top of the build: PR-2714 and PR-2720 were left reading
'pending: Validation started' with no build running for either, and
mergeStateStatus BLOCKED. Read those two facts together and the option cost
more merges than it saved executor-minutes.

So the pins here are now the inverse on that half, and unchanged on the other:

1. mcdPRValidationPipeline carries NO job-wide concurrency control, and trims
   on pr_number instead. A job-scoped control cannot express "same PR", so
   re-adding one is re-adding the outage.

2. Every stage that skips an already-merged PR also asks whether this build has
   been superseded, so a build finds out mid-flight rather than at the top.

3. The 'pending' status ALWAYS resolves. This is the half that blocked merges,
   and it is pinned independently of what causes NOT_BUILT, because more causes
   will be added.

4. mcdServerPipeline and mcdClientPipeline must NOT carry abortPrevious. This
   half is unchanged from mc-w2iu and is the safety interlock: abortPrevious
   aborts the older running build WHEREVER IT IS, and both of those pipelines
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
       burst.

   Killing either mid-rsync produces the half-written state those controls were
   added to prevent. Their trimming lives in mcdRedundantBuild.groovy instead,
   which only skips work a previous build already finished and never touches a
   running build. test_mcd_trim_non_pr_builds_to_latest.py pins that half.

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
_SUPERSESSION_SRC = _VARS / "mcdPrSupersession.groovy"

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
            "See mc-waxw."
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

    It matters more here than anywhere else in the repo: the options block now
    argues at length AGAINST an option, naming it repeatedly, and a test that
    matched raw source would read those arguments as the option itself.
    """
    return "\n".join(
        re.sub(r"//.*$", "", line) for line in block.splitlines()
    )


def test_pr_validation_has_no_job_wide_concurrency_control() -> None:
    """The bug was the SCOPE, not the strength.

    Any disableConcurrentBuilds on this pipeline is job-scoped, and one job
    serves every open PR. abortPrevious kills unrelated PRs; the bare form
    queues them behind each other, which is the stacking mc-w2iu set out to
    remove. Neither can express "same pull request", so neither belongs here.
    """
    block = _uncommented(_options_block(_src(_PR_SRC)))
    assert "disableConcurrentBuilds" not in block, (
        "mcdPRValidationPipeline gained a job-wide concurrency control. "
        "MCD-PR-Main is ONE job serving EVERY open PR, so it cannot express "
        "'same PR': abortPrevious aborts unrelated PRs (measured 2026-08-25, "
        "five PRs knocked each other out inside an hour and two were left with "
        "a pending check that could never resolve), and the bare form serializes "
        "them. Supersession is keyed on pr_number in mcdPrSupersession.groovy. "
        "See mc-waxw."
    )
    assert "milestone" not in block, (
        "mcdPRValidationPipeline gained milestone(). It is ordered by build "
        "number and scoped to the job with no notion of a parameter, so it has "
        "the same cross-PR defect as abortPrevious. mcdSteamSourceBuild.groovy "
        "records the identical finding for one job serving four Steam branches. "
        "See mc-waxw."
    )


def test_supersession_is_keyed_on_the_pull_request() -> None:
    """Trimming has to key on the PR, because the job is shared.

    Keying on anything job-wide is what mc-waxw exists to undo.
    """
    src = _src(_SUPERSESSION_SRC)
    assert "pr_number" in src, (
        "mcdPrSupersession no longer keys on pr_number. Anything else is "
        "job-scoped, and MCD-PR-Main serves every open PR. See mc-waxw."
    )
    assert "nextBuild" in src, (
        "mcdPrSupersession no longer looks forward at newer builds. It has to "
        "be the OLDER build that stands down: nothing in this library uses a "
        "privileged Jenkins API, and reaching into another run to abort it "
        "needs one. See mc-waxw."
    )


def test_every_already_merged_gate_also_checks_supersession() -> None:
    """A build must find out mid-flight, not only at the top.

    The stage gate is the only hook that runs at every stage boundary. If some
    stages carry the supersession check and others do not, a trimmed build
    silently resumes at the first gate that forgot, which is worse than not
    trimming at all: it burns the executor AND reports late.

    Written as "every already-merged gate" rather than a fixed count so that
    adding a stage cannot quietly opt out of it.
    """
    src = _src(_PR_SRC)
    merged_gates = src.count("env.PR_ALREADY_MERGED != 'true'")
    supersession_gates = src.count("mcdPrSupersession.stillCurrent()")
    assert merged_gates > 0, (
        "mcdPRValidationPipeline no longer gates any stage on PR_ALREADY_MERGED. "
        "If that gate moved, this test needs to follow it."
    )
    assert supersession_gates == merged_gates, (
        f"{merged_gates} stages skip an already-merged PR but only "
        f"{supersession_gates} also check for supersession. Every stage that can "
        "be skipped for one reason must be skippable for the other, or a "
        "superseded build resumes full validation at the first gate that "
        "forgot. See mc-waxw."
    )


def test_the_pending_check_always_resolves() -> None:
    """The half of mc-waxw that actually blocked merges.

    'Setup PR Info' posts 'pending' before anything else runs. Until
    2026-08-25 only success, failure and aborted could replace it, and a
    NOT_BUILT build matches none of them, so PR-2714 and PR-2720 were left on
    'Validation started' with mergeStateStatus BLOCKED and no build running.

    Pinned as a cleanup{} sweep over "was anything terminal posted", NOT as a
    handler for the causes known today, because the causes multiply: an
    abortPrevious kill, an all-skipped parallel branch propagating (MCD-PR-Main
    #1764), a merged-out-from-under-you PR, and now a trimmed build.
    """
    src = _src(_PR_SRC)
    assert re.search(r"\n\s*cleanup\s*\{", src), (
        "mcdPRValidationPipeline lost its post{cleanup} handler. That is the "
        "only handler that runs after success/failure/aborted regardless of "
        "result, so it is the only place that can guarantee the 'pending' "
        "status posted by 'Setup PR Info' is replaced. Without it a NOT_BUILT "
        "build leaves the PR's check pending forever. See mc-waxw."
    )
    assert "PR_STATUS_POSTED" in src, (
        "The cleanup backstop lost its guard. It must post ONLY when nothing "
        "terminal was posted already, or it will overwrite a legitimate result: "
        "MCD-PR-Main #1764 ended NOT_BUILT having already posted 'Validation "
        "passed (17 min)' to PR-2719, and that PR merged on it. See mc-waxw."
    )


def test_a_pending_status_does_not_arm_the_backstop() -> None:
    """The guard must ignore the very status it exists to replace.

    'Setup PR Info' calls setGitHubStatus('pending', ...) before any stage runs.
    If that armed PR_STATUS_POSTED, the backstop would consider the check
    already resolved on every single build and would never fire.
    """
    src = _src(_PR_SRC)
    assert re.search(r"state\s*!=\s*'pending'", src), (
        "setGitHubStatus no longer excludes 'pending' when marking a terminal "
        "status as posted. Every build posts 'pending' first, so without that "
        "exclusion the cleanup backstop is dead code and the wedge in mc-waxw "
        "comes straight back."
    )


def test_a_trimmed_build_reports_no_verdict() -> None:
    """A trimmed build must not claim its PR passed or failed.

    Declarative should already keep success/failure off a NOT_BUILT run, but
    MCD-PR-Main #1764 finished NOT_BUILT and still posted a passing status, so
    the ordering between a late result change and the post block is not
    something to bet a green check on. mcdSteamUploadPipeline guards its own
    handlers explicitly for the same reason.
    """
    src = _src(_PR_SRC)
    for handler in ("success", "failure"):
        block = re.search(
            r"\n            %s \{(.*?)\n            \}" % handler, src, re.S
        )
        assert block, f"could not find the post{{{handler}}} handler to check"
        assert "PR_SUPERSEDED" in block.group(1), (
            f"post{{{handler}}} does not check PR_SUPERSEDED. A build that was "
            "trimmed mid-flight would report a verdict for a PR it did not "
            "finish validating. See mc-waxw."
        )


@pytest.mark.parametrize("filename,hazard_stage", sorted(_PUBLISHING_PIPELINES.items()))
def test_publishing_pipelines_never_abort_mid_rsync(filename: str, hazard_stage: str) -> None:
    """The safety interlock, unchanged from mc-w2iu.

    abortPrevious has no stage scoping, so it can interrupt the rsync these
    pipelines publish through and leave the half-written state their existing
    controls were added to prevent. They trim with mcdRedundantBuild instead,
    which never touches a running build.
    """
    src = _src(_VARS / filename)
    assert "abortPrevious" not in _uncommented(_options_block(src)), (
        f"{filename} gained abortPrevious. It aborts the older build wherever it "
        f"is, including inside '{hazard_stage}', whose rsync must not be "
        "interrupted (mc-ehn1 shared deploy path, mc-mhjd manifest-with-binary, "
        "and the .core.XYZ rsync --delete race already recorded on "
        "mcdClientPipeline's own options block). Trim with mcdRedundantBuild, "
        "which skips only work a previous build already finished. See mc-w2iu."
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


def test_the_reason_is_recorded_next_to_the_absent_option() -> None:
    """An empty options block invites somebody to fill it.

    This one matters more than the usual "comment your control" pin, because
    what is being protected here is an ABSENCE. abortPrevious is the obvious
    thing to reach for, it was here as recently as 2026-08-24, and its damage
    is invisible from this file: you have to know MCD-PR-Main is one job serving
    every PR. If the block does not say so, the next reader puts it back.
    """
    block = _options_block(_src(_PR_SRC))
    for token in ("mc-waxw", "mc-w2iu", "abortPrevious", "rsync", "mcdPrSupersession"):
        assert token in block, (
            f"mcdPRValidationPipeline's options block no longer mentions '{token}'. "
            "It has to explain why there is no concurrency control here, where "
            "supersession moved to, and why the two publishing pipelines are "
            "still excluded from abortPrevious."
        )
