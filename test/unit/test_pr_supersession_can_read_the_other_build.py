"""Supersession can actually SEE the build it is comparing itself to (mc-k0z9l).

THE DEFECT THIS PINS, and it is a silent one.

`mcdPrSupersession.stillCurrent()` is wired into the `when { }` of every stage in
`mcdPRValidationPipeline`. An older build is supposed to notice a newer build of
the same pull request through `currentBuild.nextBuild` and stand itself down.

It had never once fired.

`supersededBy()` read `candidate.buildVariables['pr_number']`.
`RunWrapper.buildVariables` surfaces a build's PARAMETERS plus the env a pipeline
SET ITSELF. A GenericTrigger `genericVariable` is neither: it is contributed to
its own build's environment. So `pr_number` reads fine inside the build that owns
it and is absent from every other build's view. `mine` was always set, `theirs`
was always null, nothing ever matched, and every superseded build ran to
completion believing it was the newest.

MEASURED 2026-09-03 on MCD-PR-Main, three independent overlaps in one night, the
older build running to SUCCESS every time:

    PR-3095   #2163 started 05:34, 37m7s, SUCCESS, while #2164 (05:35) and
              #2165 (05:49) were both running. Its stage list shows roughly
              thirty stage boundaries crossed in that window, each one
              evaluating stillCurrent(), each one returning true.
    PR-3086   #2156 started 03:19, 38m16s, SUCCESS, while #2158 (03:25) ran.
    PR-3068   #2149 started 01:30, 24m35s, SUCCESS, while #2150 (01:43) ran.

Corroborating that `pr_number` is not a parameter, with a positive control so the
absence means something: `MCD-PR-Main` #2163 reports no parameters at all, while
`MCDSteam-Upload` #837 reports `SOURCE_JOB`, `SOURCE_BUILD` and `STEAM_BRANCH`
through the same API.

WHAT THIS FILE PINS.

1. The pipeline republishes the trigger's identity variables into its own
   environment as `PR_NUMBER` / `PR_HEAD_SHA`. This is the fix, and dropping it
   restores a mechanism that looks wired up and does nothing.

2. It does so in `Setup PR Info`, which is ungated and runs before every stage
   that consults supersession. A republish inside a gated stage would record the
   variables only sometimes.

3. `mcdPrSupersession` actually reads the republished names off the other build.
   Reading only the raw trigger name is the original bug.

4. The comparison still keys on the PULL REQUEST rather than the job. That is
   Tim's constraint, 2026-08-25: "PR builds should only trim to latest when there
   are duplicate builds for a single PR, otherwise, all of them should run."

The job-wide `abortPrevious` ban is NOT pinned here. It already is, by
`test_mcd_superseded_build_cancellation.py::test_pr_validation_has_no_job_wide_concurrency_control`.

Run with: pytest test/unit/test_pr_supersession_can_read_the_other_build.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_PIPELINE = _VARS / "mcdPRValidationPipeline.groovy"
_HELPER = _VARS / "mcdPrSupersession.groovy"

# The variables the trigger contributes that supersession has to compare across
# builds, mapped to the name the pipeline republishes them under.
_REPUBLISHED = {
    "pr_number": "PR_NUMBER",
    "pr_head_sha": "PR_HEAD_SHA",
}


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-k0z9l."
        )
    return path.read_text()


def _uncommented(src: str) -> str:
    """Source with // comment tails removed.

    Both files argue at length about the raw `pr_number` read being the bug and
    name it repeatedly, so a substring check against raw source reads the
    explanation as the code. The sibling trim and supersession tests keep an
    identical helper for the same reason.
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


def _stage(src: str, name: str) -> str:
    marker = f"stage('{name}')"
    index = src.find(marker)
    assert index != -1, f"no {marker} stage in mcdPRValidationPipeline"
    return _matched_block(src, index)


@pytest.mark.parametrize("trigger_var,published", sorted(_REPUBLISHED.items()))
def test_the_pipeline_republishes_its_identity(trigger_var: str, published: str) -> None:
    src = _uncommented(_src(_PIPELINE))
    assert re.search(rf"env\.{published}\s*=\s*env\.{trigger_var}\b", src), (
        f"mcdPRValidationPipeline no longer republishes {trigger_var} as "
        f"env.{published}. A GenericTrigger variable lives only in its own "
        "build's environment, so without this assignment "
        "RunWrapper.buildVariables shows another build nothing, and "
        "mcdPrSupersession silently never fires. That is not hypothetical: it "
        "never fired at all until this line existed. See mc-k0z9l."
    )


@pytest.mark.parametrize("published", sorted(_REPUBLISHED.values()))
def test_the_republish_happens_in_an_ungated_early_stage(published: str) -> None:
    """Where it happens decides whether later builds can rely on it.

    'Setup PR Info' is the first stage after checkout and carries no when{}, so
    the variables are recorded on every build before anything consults them. A
    republish inside a gated stage would record them only sometimes, which is a
    worse failure than not recording them at all: supersession would work for
    some builds and not others, with nothing in the log to say which.
    """
    src = _uncommented(_src(_PIPELINE))
    stage = _stage(src, "Setup PR Info")

    assert f"env.{published}" in stage, (
        f"env.{published} is no longer assigned in the 'Setup PR Info' stage. "
        "It must be recorded in an ungated stage that runs before any stage "
        "gated on mcdPrSupersession.stillCurrent(), or a build's identity is "
        "recorded only sometimes. See mc-k0z9l."
    )
    assert "when" not in stage.split("steps")[0], (
        "'Setup PR Info' has acquired a when{} guard. It is where the build "
        "records the identity every LATER build reads to decide whether it has "
        "been superseded, so it has to run unconditionally. See mc-k0z9l."
    )


@pytest.mark.parametrize("published", sorted(_REPUBLISHED.values()))
def test_supersession_reads_the_republished_name(published: str) -> None:
    src = _uncommented(_src(_HELPER))
    assert published in src, (
        f"mcdPrSupersession no longer reads {published}. Reading only the raw "
        "GenericTrigger name is the original defect: it resolves inside the "
        "build that owns it and is absent from candidate.buildVariables, so "
        "every comparison against another build comes back null and no build is "
        "ever found to supersede this one. See mc-k0z9l."
    )


def test_supersession_reads_the_other_builds_variables_at_all() -> None:
    src = _uncommented(_src(_HELPER))
    assert "buildVariables" in src, (
        "mcdPrSupersession no longer reads candidate.buildVariables. That is the "
        "only way it can learn which PR a neighbouring build belongs to. See "
        "mc-k0z9l."
    )
    assert re.search(r"vars\['PR_NUMBER'\]", src), (
        "mcdPrSupersession reads buildVariables but no longer pulls PR_NUMBER "
        "out of it. The raw pr_number key is never present in another build's "
        "variables. See mc-k0z9l."
    )


def test_supersession_still_keys_on_the_pull_request_not_the_job() -> None:
    """Tim's constraint, and the reason abortPrevious is banned here.

    2026-08-25: "PR builds should only trim to latest when there are duplicate
    builds for a single PR, otherwise, all of them should run." A comparison
    that stopped matching on the PR identity and stood a build down for merely
    being older would reproduce the cross-PR kill that took out five unrelated
    pull requests in one hour.
    """
    src = _uncommented(_src(_HELPER))
    body = _matched_block(src, src.index("String supersededBy()"))
    assert re.search(r"theirs\s*(==|\.equals)", body), (
        "mcdPrSupersession.supersededBy() no longer compares the neighbouring "
        "build's PR identity against its own before standing down. Without that "
        "comparison it supersedes on build order alone, which is exactly the "
        "job-scoped behaviour that killed PR-2708, PR-2716, PR-2714, PR-2718 and "
        "PR-2720 inside one hour on 2026-08-25. See mc-k0z9l and mc-waxw."
    )
