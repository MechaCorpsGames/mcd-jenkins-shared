"""Tests for coalescing a merge burst into one build (bead mc-h2nm2).

Tim, 2026-08-31: "trim the jenkins builds so only the latest build actually
builds."

THE MECHANISM THESE PINS PROTECT.

Jenkins collapses queued items of the same job only when their PARAMETERS are
identical. The Generic Webhook Trigger plugin, at its default of
`allowSeveralTriggersPerBuild: false`, stamps a fresh
`jenkins-generic-webhook-trigger-plugin_uuid` StringParameterValue on every
invocation, so no two pushes ever look alike and nothing can coalesce. Combined
with `disableConcurrentBuilds()` a burst SERIALIZES instead: nine merges in four
minutes queued eight MCDServer builds and four MCDAppServices builds, each one
destined to check out the same tip and repeat the same work.

Read out of the INSTALLED plugin (generic-webhook-trigger 2.3.1) rather than its
documentation. `GenericTrigger.trigger()` passes this field as the third
argument to `ParameterActionUtil.createParameterAction`, and that method adds
the uuid parameter if and only if the argument is false. The field has four
bytecode references in the entire plugin: the declaration, its setter, its
getter and that one call.

WHY THE PR PIPELINE IS EXCLUDED, WHICH IS THE HALF THAT NEEDS A GUARD.

A branch job's queued items all mean the same thing: build the tip of one
branch. Collapsing them is exactly right. MCD-PR-Main and MCD-PR-Release serve
EVERY open pull request from one job and declare no build parameters at all
(checked on the controller: no ParametersDefinitionProperty in either
config.xml). `pr_number` is a webhook variable, not a parameter. So with the
uuid removed, two queued items for DIFFERENT PRs would be parameter-identical
and Jenkins would collapse them: PR X's build would report against PR Y, and PR
Y's check would sit pending forever. That is mc-waxw arriving through a
different door, and the obvious future edit here is somebody "finishing the job"
by applying the flag to all six pipelines.

WHY NOT abortPrevious OR milestone(), BOTH OF WHICH ARE ONE LINE.

Neither can help and one is forbidden. abortPrevious aborts the older build
wherever it is, which
ADR 2026-08-25-superseded-cancel-goes-where-nothing-is-published rejects for the
two publishing pipelines because an abort landing mid-rsync leaves half-written
state; test_mcd_superseded_build_cancellation.py pins that. milestone() can only
abort an older build when a NEWER build passes the milestone, and under
disableConcurrentBuilds() the newer build never starts, so it has no Run and
reaches no milestone. That is the same objection the ADR raised against the
`currentBuild.nextBuild` self-skip, and it applies to milestones for the same
reason. Coalescing acts on the QUEUE, before any build exists, so it interrupts
nothing.

Run with: pytest test/unit/test_mcd_webhook_burst_coalescing.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_FLAG = "allowSeveralTriggersPerBuild"

# Branch pipelines: one job per branch, so every queued item means "build the
# tip of this branch" and collapsing them loses nothing.
_BRANCH_PIPELINES = (
    "mcdServerPipeline.groovy",
    "mcdClientPipeline.groovy",
    "mcdAppServicesPipeline.groovy",
    "mcdServicesPipeline.groovy",
    "mcdDiscordBotPipeline.groovy",
)

# One job, every open PR. Collapsing here merges unrelated pull requests.
_PR_PIPELINE = "mcdPRValidationPipeline.groovy"


def _src(name: str) -> str:
    path = _VARS / name
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-h2nm2."
        )
    return path.read_text()


def _generic_trigger_block(src: str, name: str) -> str:
    """The argument list of the GenericTrigger( ... ) call.

    Paren-matched rather than regexed to a closing line: the block contains
    nested list literals and prose comments with parentheses in them.
    """
    start = src.index("GenericTrigger(")
    depth = 0
    for index in range(start, len(src)):
        if src[index] == "(":
            depth += 1
        elif src[index] == ")":
            depth -= 1
            if depth == 0:
                return src[start : index + 1]
    pytest.fail(f"{name}: GenericTrigger( is never closed")


def _uncommented(block: str) -> str:
    """The block with // comment tails removed.

    Load-bearing: every one of these files explains the flag in prose that
    names it, so a substring search over the raw text passes on the comment
    alone. The PR pipeline's exclusion comment is the case that would break.
    """
    out = []
    for line in block.splitlines():
        stripped = line.split("//", 1)[0]
        out.append(stripped)
    return "\n".join(out)


@pytest.mark.parametrize("name", _BRANCH_PIPELINES)
def test_branch_pipelines_coalesce_a_burst(name: str) -> None:
    block = _uncommented(_generic_trigger_block(_src(name), name))
    assert f"{_FLAG}: true" in block, (
        f"{name}: its GenericTrigger does not set {_FLAG}: true, so the plugin "
        "stamps a unique uuid parameter on every push and Jenkins can never "
        "collapse the queued items. With disableConcurrentBuilds() that turns a "
        "merge burst into one full build per push, all of them checking out the "
        "same tip. See mc-h2nm2."
    )


@pytest.mark.parametrize("name", _BRANCH_PIPELINES)
def test_the_flag_is_inside_the_trigger_not_loose_in_the_file(name: str) -> None:
    src = _src(name)
    block = _generic_trigger_block(src, name)
    outside = _uncommented(src.replace(block, ""))
    assert f"{_FLAG}:" not in outside, (
        f"{name}: {_FLAG} appears outside the GenericTrigger block. It is a "
        "field of that trigger and means nothing anywhere else, so a copy left "
        "in options{} or a stage would read as configured while the trigger "
        "still stamps its uuid."
    )


def test_pr_validation_never_coalesces_across_pull_requests() -> None:
    src = _src(_PR_PIPELINE)
    block = _uncommented(_generic_trigger_block(src, _PR_PIPELINE))
    assert f"{_FLAG}" not in block, (
        f"{_PR_PIPELINE}: {_FLAG} must NOT be set here. This job serves every "
        "open PR against its target branch and declares no build parameters, so "
        "removing the plugin's uuid makes two queued items for DIFFERENT pull "
        "requests parameter-identical. Jenkins would collapse them, the survivor "
        "keeps the first payload, and the other PR's check sits pending forever "
        "with no build running. That is mc-waxw. Supersession here is keyed on "
        "pr_number in mcdPrSupersession.groovy."
    )


def test_the_exclusion_records_its_reason_where_someone_would_undo_it() -> None:
    """The comment is the guard's other half.

    A test that only says no teaches nobody. The obvious future edit is
    "finish the job" across all six pipelines, and the person making it reads
    the file, not this test.
    """
    src = _src(_PR_PIPELINE)
    assert _FLAG in src, (
        f"{_PR_PIPELINE}: the deliberate absence of {_FLAG} is no longer "
        "explained in the file. Restore the comment saying why this pipeline is "
        "excluded, or the next editor will add the flag and merge two PRs' "
        "builds into one. See mc-h2nm2."
    )


@pytest.mark.parametrize("name", _BRANCH_PIPELINES)
def test_coalescing_pipelines_still_serialize(name: str) -> None:
    """Coalescing replaces the duplicate builds, not the serialization.

    disableConcurrentBuilds() is what stops two builds rsyncing into the same
    deploy path at once (mc-ehn1). Removing it because "the queue is short now"
    would trade a wasted executor for a corrupted publish.
    """
    src = _src(name)
    assert "disableConcurrentBuilds()" in src, (
        f"{name}: lost disableConcurrentBuilds(). Coalescing collapses duplicate "
        "REQUESTS; it does not stop two genuinely different commits building at "
        "once, and both of these pipelines publish through rsync into shared "
        "paths. See mc-ehn1 and ADR "
        "2026-08-25-superseded-cancel-goes-where-nothing-is-published."
    )
