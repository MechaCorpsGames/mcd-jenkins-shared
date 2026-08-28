"""The discord-bot job must decide it has nothing to do BEFORE it allocates the
heavyweight agent (mjs-j5z).

THE MEASUREMENT THIS EXISTS TO PROTECT.

Src/Tools/discord-bot changed ZERO times on main between 2026-07-28 and
2026-08-28. In that same window MCDDiscordBot-Main ran 409 builds, #246 through
#655: the job fires per PUSH, not per relevant change. Every retained build is
NOT_BUILT "No discord-bot changes - skipped".

The change detection was never wrong. The gate just sat behind the expensive
part. With `agent { docker { image 'mcd-build-agent:latest' ... } }` declared at
PIPELINE level, all 409 of those pushes allocated an executor, started the
container and ran a full checkout of MCDClient before 'Detect Changes' got to
say "nothing to do". The stage timings show the cost is the ALLOCATION and not
the work: build #645 spent ~46s across all its stages against a wall duration of
11m52s, and #653 spent ~22s against 3m59s.

WHY A "Build HAS A when GUARD" TEST IS NOT GOOD ENOUGH.

The Build and Deploy stages were ALREADY correctly guarded on
DISCORD_BOT_CHANGED while all of this was happening. A test asserting that would
have passed on every one of those 409 wasteful builds. The defect was never the
guard, it was where the agent sat relative to it.

So the property pinned here is the one that actually moves: no path from the
pipeline root reaches a docker agent without passing a `when` that is evaluated
BEFORE that agent is entered.

`beforeAgent true` is what makes that true and it is NOT the default. Jenkins
declarative enters a stage's agent first and evaluates its `when` second unless
you ask for the opposite. Delete that one line and every symptom of the fix
survives: the guards still skip the work, the build still reports NOT_BUILT, the
job still looks correct in the UI, and the container starts on every no-op push
exactly as before. That is a silent regression, which is why it gets its own
assertion with its own failure message.

WHAT ELSE IS PINNED, AND WHY.

Splitting one agent into several does not just move cost, it splits the
WORKSPACE, and that is how this fix could break the deploy it is meant to leave
alone. Two of these tests exist for that: the stage that runs `go build` and the
stage that installs the binary must share one agent, and the group that owns the
heavyweight agent must check out its own source. Get either wrong and the job
still passes every other test in this suite, then installs a binary that was
never built.

SCOPE. Deliberately this pipeline only. The other branch pipelines share the
shape (a pipeline-level docker agent in front of a *_CHANGED gate), but they do
real work on nearly every push, so the same restructure would buy little and
risk a great deal. mcd-jenkins-shared #117 broke three consecutive MCDServer
deploys today and had to be reverted by #119; this repo has no CI, and a local
pytest run is the only evidence that exists. Widening this test is a separate
decision with its own measurement.

Run with: pytest test/unit/test_mcd_discord_bot_agent_is_gated.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"
_SRC = _VARS / "mcdDiscordBotPipeline.groovy"

_DETECT_STAGE = "Detect Changes"

# The heavyweight thing. Naming the image rather than the word "docker" keeps
# this honest if a stage ever legitimately needs a different, cheaper image.
_BUILD_AGENT_IMAGE = "mcd-build-agent"

_AGENT_DIRECTIVE = re.compile(r"^\s*agent\b\s*(\{|any\b|none\b|label\b|docker\b)", re.M)


def _src() -> str:
    if not _SRC.exists():
        pytest.fail(
            f"{_SRC} not found. This test must run from the mcd-jenkins-shared "
            "repo root. See mjs-j5z."
        )
    return _SRC.read_text()


def _mask_comments(src: str) -> str:
    """Blank `//` comment tails, preserving length so offsets stay valid.

    Two reasons, both learned here. First, the pipeline's comments ARGUE about
    agents at length ("agent any, not a label", "give them an agent each"), so a
    structural search for an agent directive reads the argument as a
    declaration. test_mcd_trim_non_pr_builds_to_latest.py keeps an equivalent
    helper after its own first draft failed on its own explanation. Second, a
    brace inside a comment would otherwise derail the brace matching below.

    Replacing with spaces rather than deleting keeps every index identical to
    the raw source, so a match found here can be reported against the real file.

    Known limit: a `//` inside a string literal is masked too (the
    JENKINS_URL_BASE URL). That is harmless here because it removes no braces,
    and this module only ever matches braces and directives.
    """
    out = []
    for line in src.splitlines(keepends=True):
        hit = line.find("//")
        if hit == -1:
            out.append(line)
            continue
        tail = line[hit:]
        keep = len(tail) - len(tail.rstrip("\r\n"))
        out.append(line[:hit] + " " * (len(tail) - keep) + tail[len(tail) - keep :])
    return "".join(out)


def _matched_block(src: str, start: int) -> tuple[int, int]:
    """From the first '{' at or after `start`, the brace-matched block bounds."""
    opened = src.index("{", start)
    depth = 0
    for index in range(opened, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return opened, index + 1
    raise AssertionError("unterminated block")


@dataclass(frozen=True)
class Stage:
    name: str
    start: int
    end: int
    block: str


def _stages(masked: str) -> list[Stage]:
    found = []
    for match in re.finditer(r"stage\('([^']+)'\)", masked):
        start, end = _matched_block(masked, match.start())
        found.append(Stage(match.group(1), start, end, masked[start:end]))
    return found


def _ancestors(stage: Stage, stages: list[Stage]) -> list[Stage]:
    """Enclosing stages, innermost first."""
    outer = [s for s in stages if s.start < stage.start and stage.end <= s.end]
    return sorted(outer, key=lambda s: s.start, reverse=True)


def _own_text(stage: Stage, stages: list[Stage]) -> str:
    """The stage's own body with every nested stage's block blanked out.

    So "does THIS stage declare an agent" cannot be answered by a child's.
    """
    text = list(stage.block)
    for other in stages:
        if other.start > stage.start and other.end <= stage.end:
            for i in range(other.start - stage.start, other.end - stage.start):
                text[i] = " "
    return "".join(text)


def _declares_agent(text: str) -> bool:
    return _AGENT_DIRECTIVE.search(text) is not None


def _agent_text(stage: Stage, stages: list[Stage]) -> str:
    """The stage's own agent directive, or '' if it declares none."""
    own = _own_text(stage, stages)
    match = _AGENT_DIRECTIVE.search(own)
    if not match:
        return ""
    if match.group(1) != "{":
        return match.group(0)
    start, end = _matched_block(own, match.start())
    return own[start:end]


def _pipeline_agent(masked: str, stages: list[Stage]) -> str:
    """The agent directive governing the pipeline itself."""
    head = masked[: min(s.start for s in stages)]
    match = _AGENT_DIRECTIVE.search(head)
    assert match, "the pipeline declares no agent at all"
    if match.group(1) != "{":
        return match.group(0)
    start, end = _matched_block(head, match.start())
    return head[start:end]


def _when_text(stage: Stage, stages: list[Stage]) -> str:
    own = _own_text(stage, stages)
    match = re.search(r"\bwhen\b\s*\{", own)
    if not match:
        return ""
    start, end = _matched_block(own, match.start())
    return own[start:end]


def _heavyweight_stages(stages: list[Stage]) -> list[Stage]:
    return [s for s in stages if _BUILD_AGENT_IMAGE in _agent_text(s, stages)]


def _stage_named(name: str, stages: list[Stage]) -> Stage:
    for stage in stages:
        if stage.name == name:
            return stage
    pytest.fail(
        f"mcdDiscordBotPipeline has no stage named '{name}'. If it was renamed, "
        "this module's assumptions need rechecking, not its constants patching. "
        "See mjs-j5z."
    )


def _stage_running(fragment: str, stages: list[Stage]) -> Stage:
    """The innermost stage whose own body runs `fragment`."""
    hits = [s for s in stages if fragment in _own_text(s, stages)]
    assert hits, (
        f"no stage in mcdDiscordBotPipeline runs {fragment!r} any more. This "
        "module uses it to find the real build and install steps; if the step "
        "changed, re-point it deliberately. See mjs-j5z."
    )
    return min(hits, key=lambda s: s.end - s.start)


@pytest.fixture(name="parsed")
def _parsed() -> tuple[str, list[Stage]]:
    masked = _mask_comments(_src())
    stages = _stages(masked)
    assert stages, "no stages parsed out of mcdDiscordBotPipeline"
    return masked, stages


def test_the_pipeline_does_not_declare_the_heavyweight_agent(parsed) -> None:
    """The 409-build defect, in one assertion.

    A pipeline-level docker agent is entered before ANY stage runs, so no `when`
    anywhere below can prevent it. This is the line that made every no-op push
    start a container.
    """
    masked, stages = parsed
    agent = _pipeline_agent(masked, stages)
    assert _BUILD_AGENT_IMAGE not in agent, (
        "mcdDiscordBotPipeline declares the mcd-build-agent docker agent at "
        "PIPELINE level. That agent is entered before any stage runs, so the "
        "change-detection gate cannot stop it: every push to main allocates an "
        "executor and starts the container just to be told there is nothing to "
        "do. That was 409 builds and 0 relevant changes between 2026-07-28 and "
        "2026-08-28. Move it onto the stage that needs it, and gate that stage "
        "with `when { beforeAgent true ... }`. See mjs-j5z."
    )
    assert re.search(r"\bagent\s+none\b", agent), (
        "mcdDiscordBotPipeline's pipeline-level agent is no longer `agent none`. "
        "Detection must run on a cheap executor and the heavyweight agent must "
        "be reached only through a gated stage. See mjs-j5z."
    )


def test_every_heavyweight_agent_is_gated_before_it_is_entered(parsed) -> None:
    """THE load-bearing assertion. `beforeAgent true` is not the default.

    Declarative evaluates a stage's `when` AFTER entering its agent unless
    beforeAgent is set. A stage that carries the docker agent and a correct
    guard, but no beforeAgent, still starts the container on every no-op push
    while looking entirely fixed: the guard skips the work, the build reports
    NOT_BUILT, and nothing in the UI says otherwise.
    """
    _, stages = parsed
    heavy = _heavyweight_stages(stages)
    assert heavy, (
        "no stage declares the mcd-build-agent docker agent. If the bot no "
        "longer needs it this module should be rewritten deliberately, not left "
        "passing vacuously. See mjs-j5z."
    )

    for stage in heavy:
        candidates = [stage, *_ancestors(stage, stages)]
        gated = [s for s in candidates if "beforeAgent true" in _when_text(s, stages)]
        assert gated, (
            f"stage '{stage.name}' enters the mcd-build-agent container without "
            "a `when { beforeAgent true ... }` on it or any stage enclosing it. "
            "Declarative enters the agent BEFORE evaluating `when` unless "
            "beforeAgent is true, so the container will start on every push even "
            "though the guard correctly skips the work. The build still reports "
            "NOT_BUILT and still looks fixed. That silent shape is the whole "
            "defect in mjs-j5z."
        )


def test_the_gate_is_the_flag_the_detection_actually_sets(parsed) -> None:
    """A gate on the wrong flag is a gate that never opens, or never closes.

    Pinning the flag NAME by reading it out of both places, rather than
    hardcoding it here, so a rename has to stay consistent instead of quietly
    stranding the deploy.
    """
    _, stages = parsed
    detect = _stage_named(_DETECT_STAGE, stages)
    assigned = set(
        re.findall(r"env\.([A-Z0-9_]+_CHANGED)\s*=", _own_text(detect, stages))
    )
    assert assigned, (
        f"the '{_DETECT_STAGE}' stage no longer assigns a *_CHANGED flag, so "
        "there is nothing for the agent gate to test. See mjs-j5z."
    )

    for stage in _heavyweight_stages(stages):
        candidates = [stage, *_ancestors(stage, stages)]
        tested: set[str] = set()
        for candidate in candidates:
            when = _when_text(candidate, stages)
            if "beforeAgent true" in when:
                tested.update(re.findall(r"env\.([A-Z0-9_]+_CHANGED)\b", when))
        assert tested & assigned, (
            f"the gate in front of '{stage.name}' tests {sorted(tested)}, but "
            f"'{_DETECT_STAGE}' assigns {sorted(assigned)}. The gate is reading "
            "a flag nothing sets, so it either never opens (the bot stops "
            "deploying) or never closes (nothing was saved). See mjs-j5z."
        )


def test_the_detection_never_runs_inside_the_heavyweight_agent(parsed) -> None:
    """The decision has to happen OUTSIDE the thing it decides about.

    Nesting 'Detect Changes' under the docker agent would satisfy every other
    test in this file and restore the original defect exactly: the container
    starts, and then the build decides it was not needed.
    """
    _, stages = parsed
    detect = _stage_named(_DETECT_STAGE, stages)
    heavy = {s.name for s in _heavyweight_stages(stages)}
    enclosing = {s.name for s in _ancestors(detect, stages)}
    overlap = heavy & enclosing
    assert not overlap, (
        f"'{_DETECT_STAGE}' runs inside {sorted(overlap)}, which owns the "
        "mcd-build-agent container. The container therefore starts before the "
        "build knows whether it needs one, which is the original defect with "
        "extra steps. See mjs-j5z."
    )


def test_the_build_and_the_install_share_one_agent(parsed) -> None:
    """The way this fix could break the deploy it is meant to leave alone.

    An agent directive is also a WORKSPACE. Give Build and Deploy an agent each
    and Deploy gets an empty workspace, so `install` reaches for a binary that
    was built somewhere else. Nothing else in this suite would notice: the
    guards, the ordering and the gate would all still be right.
    """
    _, stages = parsed
    build = _stage_running("go build", stages)
    install = _stage_running("install -m 755", stages)

    def owner(stage: Stage) -> str | None:
        for candidate in [stage, *_ancestors(stage, stages)]:
            if _declares_agent(_own_text(candidate, stages)):
                return candidate.name
        return None

    build_owner, install_owner = owner(build), owner(install)
    assert build_owner is not None, (
        f"'{build.name}' resolves to no agent at all. With `agent none` at "
        "pipeline level that stage cannot run. See mjs-j5z."
    )
    assert build_owner == install_owner, (
        f"'{build.name}' builds the binary on agent '{build_owner}' but "
        f"'{install.name}' installs it from agent '{install_owner}'. Those are "
        "different workspaces, so the install would copy a binary that was "
        "never built there. Keep both under the one agent. See mjs-j5z."
    )


def test_the_heavyweight_group_checks_out_its_own_source(parsed) -> None:
    """A fresh agent is a fresh workspace, and nothing is in it.

    The checkout in the detection group happens on the detection agent. Without
    its own checkout the build's `cd Src/Tools/discord-bot` lands in an empty
    directory. This only ever runs when the bot actually changed, which was 0
    times in the month measured, so it costs nothing in practice.
    """
    _, stages = parsed
    heavy = _heavyweight_stages(stages)
    for stage in heavy:
        assert "checkout scm" in stage.block, (
            f"'{stage.name}' owns its own agent and therefore its own "
            "workspace, but nothing checks out into it. The build would run "
            "against an empty directory. See mjs-j5z."
        )


def test_the_detection_group_checks_out_before_it_reads_git(parsed) -> None:
    """Detect Changes shells out to git; something has to have fetched it.

    Pinned because the restructure is exactly what could separate them: the
    checkout and the detection have to stay under one agent, in that order.
    """
    _, stages = parsed
    detect = _stage_named(_DETECT_STAGE, stages)
    enclosing = _ancestors(detect, stages)
    assert enclosing, (
        f"'{_DETECT_STAGE}' is no longer nested under a stage that owns an "
        "agent and a checkout. See mjs-j5z."
    )
    group = enclosing[0]
    checkout = group.block.find("checkout scm")
    assert checkout != -1, (
        f"'{group.name}' does not check out any source, but '{_DETECT_STAGE}' "
        "inside it reads git history. See mjs-j5z."
    )
    assert checkout < (detect.start - group.start), (
        f"'{group.name}' checks out AFTER '{_DETECT_STAGE}' runs, so the "
        "detection reads a workspace that has not been populated yet. "
        "See mjs-j5z."
    )
