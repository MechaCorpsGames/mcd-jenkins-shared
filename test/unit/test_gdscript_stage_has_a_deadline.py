"""The GDScript Tests stage must carry a timeout (mc-ezb8q).

MCDClient-FeatureCard #216 took a native signal 11 inside the headless gdUnit4
run. Godot printed its crash banner and backtrace, the log reached

    Sentry: DEBUG: Processing event ebe50260-80b4-4568-67c7-2fef115613a2

and stopped there. The process did not exit: reproduced locally on
features/card at c6f54b660 with an OS.crash() in a gdUnit suite, the main
thread spins in state R at ~100% CPU and never returns. Jenkins had no deadline
anywhere in mcdClientPipeline, so the build ran for 10h46m until a human
cancelled it, and because that pipeline also carries disableConcurrentBuilds()
the cancel wedged the next build too (#217 aborted into a StackOverflowError
inside CpsFlowExecution#notifyListeners, its log listener was never nulled, and
#218 was refused with "Build #217 is already in progress" against nothing
running).

The crash itself is fixed on the client side by not initializing the Sentry SDK
in automated runs. This file guards the half that does not care WHY the stage
hung.

Why a STAGE timeout and not only a build-level one: a build-level timeout
aborts the build. An aborted build publishes no junit, names no stage, and
reaches none of the post blocks, so the operator gets ABORTED with no reading.
A stage timeout fails the stage, the post block still runs, and the
notification names "GDScript Tests".

Run with: pytest test/unit/test_gdscript_stage_has_a_deadline.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_CLIENT_SRC = _VARS / "mcdClientPipeline.groovy"
_PR_SRC = _VARS / "mcdPRValidationPipeline.groovy"

_STAGE = "GDScript Tests"

# Every healthy run of this stage observed on 2026-09-02:
#   MCD-PR-Main           #2112    9m52s
#   MCDClient-FeatureCard #218    12m20s
#   MCDClient-Main        #1377   15m43s
#   MCDClient-FeatureCard #219    20m45s
# A deadline at or under the top of that range is not a deadline, it is a
# flake generator, so the floor sits just above it.
_MIN_STAGE_TIMEOUT_MINUTES = 25

# Above this the control stops being one. 10h46m is what "no deadline" cost;
# an hour and a half of a wedged executor is already an incident.
_MAX_STAGE_TIMEOUT_MINUTES = 90

# Only mcdClientPipeline. mcdPRValidationPipeline carries the same stage and is
# deliberately left alone (mc-ezb8q): it already has a build-level
# timeout(45, MINUTES), so a hang there ends in 45 minutes rather than never,
# and that job serves every open PR at once. Adding a second control to it is a
# separate decision with a separate blast radius.
_SOURCES = pytest.mark.parametrize(
    "src_path",
    [_CLIENT_SRC],
    ids=["mcdClientPipeline"],
)


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-ezb8q."
        )
    return path.read_text()


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-ezb8q."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-ezb8q."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-ezb8q.")


def _timeout_minutes(text: str) -> list[float]:
    """Every timeout(time: N, unit: 'X') in `text`, in minutes."""
    scale = {"SECONDS": 1 / 60, "MINUTES": 1.0, "HOURS": 60.0, "DAYS": 1440.0}
    return [
        float(value) * scale[unit]
        for value, unit in re.findall(
            r"timeout\s*\(\s*time:\s*(\d+)\s*,\s*unit:\s*'(\w+)'\s*\)", text
        )
    ]


# ---------------------------------------------------------------------------
# Guard the guard: without these the assertions below pass by having nothing
# to check.
# ---------------------------------------------------------------------------


@_SOURCES
def test_stage_exists(src_path: Path) -> None:
    assert f"stage('{_STAGE}')" in _src(src_path), (
        f"{src_path.name} lost the {_STAGE!r} stage, so every timeout "
        "assertion in this file would pass vacuously. See mc-ezb8q."
    )


@_SOURCES
def test_stage_actually_runs_godot(src_path: Path) -> None:
    body = _stage_body(_src(src_path), _STAGE)
    assert re.search(r"\bgodot\b.*GdUnitCmdTool", body), (
        f"{_STAGE} in {src_path.name} no longer launches the gdUnit4 runner. "
        "This file is about the deadline on THAT process; if the stage does "
        "something else now, the deadline has to be re-derived. See mc-ezb8q."
    )


# ---------------------------------------------------------------------------
# The deadline itself
# ---------------------------------------------------------------------------


@_SOURCES
def test_stage_carries_its_own_timeout(src_path: Path) -> None:
    body = _stage_body(_src(src_path), _STAGE)
    values = _timeout_minutes(body)
    assert values, (
        f"{_STAGE} in {src_path.name} has no timeout of its own.\n"
        "A native crash in the headless gdUnit run does not end the process: "
        "it spins at 100% CPU. Without a stage deadline the run waits for a "
        "human, and on mcdClientPipeline that human's cancel also wedges the "
        "next build through disableConcurrentBuilds(). See mc-ezb8q."
    )


@_SOURCES
def test_stage_timeout_leaves_headroom_over_a_healthy_run(src_path: Path) -> None:
    body = _stage_body(_src(src_path), _STAGE)
    for minutes in _timeout_minutes(body):
        assert minutes >= _MIN_STAGE_TIMEOUT_MINUTES, (
            f"{_STAGE} in {src_path.name} has a {minutes:g}m timeout, under "
            f"the {_MIN_STAGE_TIMEOUT_MINUTES}m floor. Healthy runs measured on "
            "2026-09-02 span 9m52s (MCD-PR-Main #2112) to 20m45s "
            "(MCDClient-FeatureCard #219), and #219's parallel MCDCoreExt Linux "
            "Release branch took 10m41s against 3m56s in MCDClient-Main #1377, "
            "so contention alone moves this stage by more than 2x. A control "
            "that reddens healthy builds gets deleted. See mc-ezb8q."
        )


@_SOURCES
def test_stage_timeout_is_still_a_control(src_path: Path) -> None:
    body = _stage_body(_src(src_path), _STAGE)
    for minutes in _timeout_minutes(body):
        assert minutes <= _MAX_STAGE_TIMEOUT_MINUTES, (
            f"{_STAGE} in {src_path.name} has a {minutes:g}m timeout, over the "
            f"{_MAX_STAGE_TIMEOUT_MINUTES}m ceiling. Past this it stops being "
            "a deadline and becomes a formality: the executor is held, and on "
            "mcdClientPipeline the whole branch is held with it. See mc-ezb8q."
        )


# ---------------------------------------------------------------------------
# The build-level backstop on the branch pipeline
# ---------------------------------------------------------------------------


def _options_block(src: str) -> str:
    start = src.find("options {")
    assert start != -1, "no pipeline-level options block. See mc-ezb8q."
    depth = 0
    for index in range(src.find("{", start), len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail("unbalanced braces in the options block. See mc-ezb8q.")


def test_client_pipeline_serialises_builds() -> None:
    """Guard the guard for the test below."""
    assert "disableConcurrentBuilds()" in _options_block(_src(_CLIENT_SRC)), (
        "mcdClientPipeline no longer serialises builds. The next test's whole "
        "argument is that serialisation plus no deadline is what wedged the "
        "MCDClient-FeatureCard queue, so re-derive it before changing this. "
        "See mc-ezb8q."
    )


def test_client_pipeline_has_a_build_level_deadline() -> None:
    """disableConcurrentBuilds() without a timeout stops the branch (mc-ezb8q).

    The two options belong together. Serialising builds means one hung build
    holds every later one, which is precisely what #216 did to #217 and #218.
    This is the backstop for a hang in a stage nobody has instrumented, not the
    primary control.
    """
    values = _timeout_minutes(_options_block(_src(_CLIENT_SRC)))
    assert values, (
        "mcdClientPipeline carries disableConcurrentBuilds() and no "
        "build-level timeout. MCDClient-FeatureCard #216 ran 10h46m on that "
        "combination and took the branch down with it. See mc-ezb8q."
    )
    for minutes in values:
        assert minutes >= 60, (
            f"mcdClientPipeline's build-level timeout is {minutes:g}m. The "
            "longest healthy build observed is 47m7s (MCDClient-Main #1377, "
            "2026-09-02). A build-level timeout ABORTS, publishing no junit "
            "and naming no stage, so it must never be the thing that fires on "
            "a merely slow build. See mc-ezb8q."
        )


def test_build_level_backstop_is_looser_than_the_stage_deadline() -> None:
    """Ordering, so the stage timeout is always the one that reports.

    If the build-level abort could fire first, the operator gets ABORTED with
    no junit and no stage name, which is the signal this whole change exists to
    avoid.
    """
    build_values = _timeout_minutes(_options_block(_src(_CLIENT_SRC)))
    stage_values = _timeout_minutes(_stage_body(_src(_CLIENT_SRC), _STAGE))
    assert build_values and stage_values, (
        "mcdClientPipeline is missing one of the two deadlines this test "
        f"compares: build-level {build_values}, {_STAGE} {stage_values}. The "
        "two tests above say which one, and why. See mc-ezb8q."
    )
    build_level = min(build_values)
    stage_level = max(stage_values)
    assert build_level > stage_level, (
        f"mcdClientPipeline's build-level timeout ({build_level:g}m) is not "
        f"looser than the {_STAGE} stage timeout ({stage_level:g}m). The build "
        "would abort before the stage could fail and name itself. See "
        "mc-ezb8q."
    )


# ---------------------------------------------------------------------------
# The PR pipeline is deliberately NOT given a stage timeout
# ---------------------------------------------------------------------------


def test_pr_pipeline_still_relies_on_its_build_level_timeout() -> None:
    """mcdPRValidationPipeline is left alone on purpose (mc-ezb8q).

    It already carries a build-level timeout(45, MINUTES), so a hang there ends
    in 45 minutes instead of never, and it is one job serving every open PR at
    once. This pins the premise that argument rests on: if that build-level
    timeout ever disappears, the PR job silently becomes as exposed as
    mcdClientPipeline was, and this decision has to be re-made rather than
    inherited.
    """
    values = _timeout_minutes(_options_block(_src(_PR_SRC)))
    assert values, (
        "mcdPRValidationPipeline lost its build-level timeout. That timeout is "
        "the only reason its GDScript Tests stage was left without one of its "
        "own. See mc-ezb8q."
    )
    assert max(values) <= 60, (
        f"mcdPRValidationPipeline's build-level timeout is now {max(values):g}m. "
        "Past an hour it stops bounding a hung GDScript stage usefully and the "
        "stage needs its own deadline after all. See mc-ezb8q."
    )
