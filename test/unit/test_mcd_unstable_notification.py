"""Tests for the UNSTABLE build notification (mjs-q4x).

An unstable build used to announce itself as

    ⚠️ Build finished UNSTABLE at: Initializing

which named a phase where nothing had happened yet. MCDClient-FeatureCard #92,
#93 and #94 all went out with that message; none of them told anybody that the
soft card-validation gate was what had tripped.

Two independent mistakes produced it, and this suite pins both shut:

1. The handler reported env.BUILD_PHASE — where the build got TO, not what went
   wrong. An unstable build by definition kept going, so the phase it happens to
   be sitting in when post{} runs says nothing about the cause.
2. BUILD_PHASE is declared in the pipeline-level environment{} block, and
   declarative re-applies those entries as a contextual override, so the
   `env.BUILD_PHASE = '...'` assignments inside stages never reach post{} at all
   — it only ever sees the literal "Initializing". env.SYMBOLS_UPLOADED is the
   working counter-example: not in environment{}, read correctly in the same
   block. Hence test_unstable_reason_is_not_declared_in_environment_block, which
   is the one test here that stops the bug being recreated wholesale.

The load-bearing test is test_every_unstable_marker_records_a_reason: it walks
every site that marks a build UNSTABLE and requires that site to say why. That
is what keeps the NEXT cause of unstable from inheriting the original bug, which
is the failure mode a notifier cannot afford — a notification that is wrong is
worse than none, because it gets believed.

Run with: pytest test/unit/test_mcd_unstable_notification.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_CLIENT_SRC = _VARS / "mcdClientPipeline.groovy"
_SERVER_SRC = _VARS / "mcdServerPipeline.groovy"
_DISCORD_SRC = _VARS / "discordNotify.groovy"
_RECORDER_SRC = _VARS / "mcdUnstableReason.groovy"
_VALIDATE_SRC = _VARS / "mcdValidateGameData.groovy"

# The two pipelines that build product and can go soft.
_NOTIFYING_PIPELINES = (_CLIENT_SRC, _SERVER_SRC)

# Discord embed colours already in use in discordNotify.groovy.
_AMBER = "16776960"
_FAILURE_RED = "15158332"


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mjs-q4x."
        )
    return path.read_text()


def _balanced_body(src: str, start: int, what: str) -> str:
    """Return src from `start` through the brace-matched end of its first block."""
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{what} has no body. See mjs-q4x."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {what}. See mjs-q4x.")


def _pipeline_post_body(src: str) -> str:
    """Return the pipeline-level post{} block.

    Anchored on the 8-space indent so the stage-level post{} blocks (24 spaces,
    e.g. the GDScript Tests junit publisher) cannot be picked up by mistake.
    """
    marker = "\n        post {"
    start = src.find(marker)
    assert start != -1, "no pipeline-level post{} block found. See mjs-q4x."
    assert src.count(marker) == 1, (
        "more than one pipeline-level post{} block — this helper is anchored on "
        "the 8-space indent and needs updating. See mjs-q4x."
    )
    return _balanced_body(src, start + 1, "pipeline post{}")


def _post_condition_body(src: str, condition: str) -> str:
    """Return one post condition's block (e.g. `unstable`) from the post{} body."""
    post = _pipeline_post_body(src)
    match = re.search(rf"^\s+{condition} \{{", post, re.MULTILINE)
    assert match, f"no post {{ {condition} }} block. See mjs-q4x."
    return _balanced_body(post, match.start(), f"post {{ {condition} }}")


def _method_body(src: str, signature: str) -> str:
    start = src.find(signature)
    assert start != -1, f"no `{signature}` in source. See mjs-q4x."
    return _balanced_body(src, start, signature)


def _environment_block(src: str) -> str:
    marker = "\n        environment {"
    start = src.find(marker)
    if start == -1:
        return ""
    return _balanced_body(src, start + 1, "environment{}")


# ---------------------------------------------------------------------------
# The message must name the cause, not the phase
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("path", _NOTIFYING_PIPELINES, ids=lambda p: p.name)
def test_unstable_handler_does_not_read_build_phase(path: Path) -> None:
    """The unstable message must not be built from BUILD_PHASE (mjs-q4x).

    This is the original defect: BUILD_PHASE reports where the build got to, and
    on the unstable path it reads "Initializing" regardless.
    """
    body = _post_condition_body(_src(path), "unstable")
    code = "\n".join(
        line for line in body.splitlines() if not line.strip().startswith("//")
    )
    assert "BUILD_PHASE" not in code, (
        f"{path.name} post {{ unstable }} reads BUILD_PHASE again. That is what "
        'produced "Build finished UNSTABLE at: Initializing" — the phase is not '
        "the cause, and the environment{} block shadows the per-stage "
        "assignments so post{} only ever sees the initial literal. Use "
        "env.UNSTABLE_REASON. See mjs-q4x."
    )


@pytest.mark.parametrize("path", _NOTIFYING_PIPELINES, ids=lambda p: p.name)
def test_unstable_handler_reports_the_recorded_reason(path: Path) -> None:
    """The unstable message is built from env.UNSTABLE_REASON (mjs-q4x)."""
    body = _post_condition_body(_src(path), "unstable")
    assert "env.UNSTABLE_REASON" in body, (
        f"{path.name} post {{ unstable }} must build its message from "
        "env.UNSTABLE_REASON, recorded by mcdUnstableReason at the site that "
        "knew the cause. See mjs-q4x."
    )


@pytest.mark.parametrize("path", _NOTIFYING_PIPELINES, ids=lambda p: p.name)
def test_unstable_handler_never_says_initializing(path: Path) -> None:
    """No fallback may reintroduce the meaningless phase text (mjs-q4x)."""
    body = _post_condition_body(_src(path), "unstable")
    code = "\n".join(
        line for line in body.splitlines() if not line.strip().startswith("//")
    )
    assert "Initializing" not in code, (
        f"{path.name} post {{ unstable }} can still say 'Initializing'. The "
        "fallback must read as warnings, never as a phase. See mjs-q4x."
    )


@pytest.mark.parametrize("path", _NOTIFYING_PIPELINES, ids=lambda p: p.name)
def test_unstable_handler_has_a_fallback_message(path: Path) -> None:
    """Nothing recorded a reason is still a sentence, not an empty embed (mjs-q4x)."""
    body = _post_condition_body(_src(path), "unstable")
    assert "warnings" in body, (
        f"{path.name} post {{ unstable }} needs a fallback for the case where "
        "nothing set UNSTABLE_REASON — a cause the recorder does not cover yet "
        "must still produce a readable message. See mjs-q4x."
    )


@pytest.mark.parametrize("path", _NOTIFYING_PIPELINES, ids=lambda p: p.name)
def test_unstable_handler_notifies_in_amber_not_failure_red(path: Path) -> None:
    """Unstable is announced by discordNotify.unstable, not .failure (mjs-q4x).

    An unstable build is not a failed build. Painting it failure-red teaches
    people that red is often nothing, and then a real failure gets skimmed past.
    """
    body = _post_condition_body(_src(path), "unstable")
    assert "discordNotify.unstable(" in body, (
        f"{path.name} post {{ unstable }} must call discordNotify.unstable(), "
        "the amber notifier. See mjs-q4x."
    )
    assert "discordNotify.failure(" not in body, (
        f"{path.name} post {{ unstable }} still calls discordNotify.failure(), "
        "so an unstable build is indistinguishable from a broken one in the "
        "channel. See mjs-q4x."
    )


@pytest.mark.parametrize("path", _NOTIFYING_PIPELINES, ids=lambda p: p.name)
def test_unstable_handler_passes_the_reason_as_its_own_field(path: Path) -> None:
    """The cause survives skim-reading: it is a field, not only prose (mjs-q4x)."""
    body = _post_condition_body(_src(path), "unstable")
    assert re.search(r"^\s+reason:", body, re.MULTILINE), (
        f"{path.name} post {{ unstable }} must pass reason: through to "
        "discordNotify.unstable so the cause gets its own embed field. "
        "See mjs-q4x."
    )


# ---------------------------------------------------------------------------
# The SAME defect on the failure path
#
# mjs-q4x fixed post { unstable } and wrote every guard above scoped to it. The
# failure handler was left reading BUILD_PHASE, so every FAILING client build
# announced "Build failed at: Initializing", naming a phase where nothing had
# happened yet, on the path that fires when something is actually broken.
#
# That is the more expensive half of the original bug. An unstable build is a
# warning somebody may read later; a failed build is the one people open the
# channel for, and it was the one telling them nothing.
#
# These mirror the unstable guards one for one so the two handlers cannot drift
# apart again.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("path", _NOTIFYING_PIPELINES, ids=lambda p: p.name)
def test_failure_handler_does_not_read_build_phase(path: Path) -> None:
    """The failure message must not be built from BUILD_PHASE.

    Same reasoning as the unstable guard above, and the same environment{}
    shadowing: post{} only ever sees the "Initializing" literal, so reporting
    the phase reports a constant.
    """
    body = _post_condition_body(_src(path), "failure")
    code = "\n".join(
        line for line in body.splitlines() if not line.strip().startswith("//")
    )
    assert "BUILD_PHASE" not in code, (
        f"{path.name} post {{ failure }} reads BUILD_PHASE. The environment{{}} "
        "block shadows every per-stage assignment, so this always renders "
        '"Build failed at: Initializing" no matter what broke. Name the cause '
        "instead. See mjs-q4x."
    )


@pytest.mark.parametrize("path", _NOTIFYING_PIPELINES, ids=lambda p: p.name)
def test_failure_handler_never_says_initializing(path: Path) -> None:
    """No fallback may reintroduce the meaningless phase text on the red path."""
    body = _post_condition_body(_src(path), "failure")
    code = "\n".join(
        line for line in body.splitlines() if not line.strip().startswith("//")
    )
    assert "Initializing" not in code, (
        f"{path.name} post {{ failure }} can still say 'Initializing'. See mjs-q4x."
    )


def test_client_failure_handler_has_a_fallback_message() -> None:
    """A failure nothing recorded is still a sentence, not an empty embed.

    Not every failure has a recorded cause: a compile error in a cross-platform
    build throws without anything calling the recorder. The handler must still
    produce a readable line, and it must point at the console log rather than
    inventing a phase.

    CLIENT ONLY, deliberately. The server's failure handler builds an
    unconditional sentence from config.environment and has no reason lookup to
    fall back from, so it has no fallback branch to guard. The two guards above
    are the ones that are invariants for both.
    """
    body = _post_condition_body(_src(_CLIENT_SRC), "failure")
    assert "console log" in body, (
        "mcdClientPipeline.groovy post { failure } needs a fallback for the "
        "case where nothing recorded a cause. See mjs-q4x."
    )


# ---------------------------------------------------------------------------
# The trap that created the bug: environment{} shadowing
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("path", _NOTIFYING_PIPELINES, ids=lambda p: p.name)
def test_unstable_reason_is_not_declared_in_environment_block(path: Path) -> None:
    """UNSTABLE_REASON must never be an environment{} entry (mjs-q4x).

    Declarative re-applies environment{} entries as a contextual override, so a
    variable declared there cannot be updated by `env.VAR = ...` inside a stage
    — post{} keeps seeing the declared default. That is precisely why
    BUILD_PHASE reads "Initializing". Giving UNSTABLE_REASON a default here
    would freeze it the same way and recreate the whole bug.
    """
    environment = _environment_block(_src(path))
    assert "UNSTABLE_REASON" not in environment, (
        f"{path.name} declares UNSTABLE_REASON in environment{{}}. Declarative "
        "shadows per-stage assignments to environment{} entries, so the reason "
        "would freeze at this default and post{} would report it forever — the "
        "exact mechanism behind 'at: Initializing'. Remove it; mcdUnstableReason "
        "sets it at runtime. See mjs-q4x."
    )


# ---------------------------------------------------------------------------
# Every cause of UNSTABLE must name itself
# ---------------------------------------------------------------------------


def _unstable_marker_lines(src: str) -> list[tuple[int, str]]:
    """Line numbers of every site that marks the build UNSTABLE."""
    markers = []
    for number, line in enumerate(src.splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("//"):
            continue
        if "currentBuild.result = 'UNSTABLE'" in stripped:
            markers.append((number, stripped))
        elif "catchError(buildResult: 'UNSTABLE'" in stripped:
            markers.append((number, stripped))
    return markers


@pytest.mark.parametrize("path", _NOTIFYING_PIPELINES, ids=lambda p: p.name)
def test_every_unstable_marker_records_a_reason(path: Path) -> None:
    """Anything that marks a build UNSTABLE must also say why (mjs-q4x).

    The design constraint that keeps this fix alive: the reason has to come from
    whatever CAUSED the unstable state. Reusing a phase is what produced the
    original misleading text, and a second cause added later would inherit the
    same bug unless every marker is required to name itself. A window of 20
    lines either side is enough to cover "record, then mark" and the
    catchError/try-catch shape without matching an unrelated block. The two
    marker sites in each pipeline are ~45 lines apart, so the window cannot
    satisfy one site with the other site's reason.
    """
    src = _src(path)
    lines = src.splitlines()
    markers = _unstable_marker_lines(src)
    assert markers, (
        f"{path.name} marks nothing UNSTABLE — if that is now true, this test "
        "and the post {{ unstable }} handler should go. See mjs-q4x."
    )

    unnamed = []
    for number, text in markers:
        window = "\n".join(lines[max(0, number - 21):number + 20])
        if "mcdUnstableReason(" not in window:
            unnamed.append(f"  line {number}: {text}")

    assert not unnamed, (
        f"{path.name} marks the build UNSTABLE without recording a reason:\n"
        + "\n".join(unnamed)
        + "\n\nEach of these leaves post { unstable } with nothing to report but "
        "the generic fallback. Call mcdUnstableReason('<noun clause>') at the "
        "site, where the cause is known — do not reconstruct it in post{}. "
        "See mjs-q4x."
    )


def test_the_soft_card_gate_records_its_reason() -> None:
    """The soft validation gate names itself and its exit code (mjs-q4x).

    This gate is the one that made #92-#94 unstable, and it recorded nothing.
    """
    src = _src(_VALIDATE_SRC)
    assert "mcdUnstableReason(" in src, (
        "mcdValidateGameData no longer records a reason when the soft gate "
        "trips, so a card-validation failure notifies without saying so. "
        "See mjs-q4x."
    )
    reason_at = src.find("mcdUnstableReason(")
    catch_at = src.find("catchError(buildResult: 'UNSTABLE'")
    assert catch_at != -1, "the soft gate no longer marks UNSTABLE. See mjs-q4x."
    assert reason_at < catch_at, (
        "mcdValidateGameData must record the reason before handing off to "
        "catchError, so the cause is set whatever catchError does next. "
        "See mjs-q4x."
    )
    line = src[reason_at:src.find("\n", reason_at)]
    assert "validator exit" in line, (
        "the recorded reason must carry the validator exit code — it is what "
        "separates a corpus with errored cards from the validator itself "
        "falling over. See mjs-q4x."
    )


# ---------------------------------------------------------------------------
# The recorder itself
# ---------------------------------------------------------------------------


def test_recorder_exists_and_writes_the_env_var() -> None:
    """mcdUnstableReason is the single writer of env.UNSTABLE_REASON (mjs-q4x)."""
    src = _src(_RECORDER_SRC)
    assert "env.UNSTABLE_REASON =" in src, (
        "mcdUnstableReason must assign env.UNSTABLE_REASON. See mjs-q4x."
    )


def test_recorder_is_additive_not_overwriting() -> None:
    """A second cause must not erase the first (mjs-q4x).

    The handler this replaces used an if/else, so a build that tripped both the
    card gate and the symbol upload reported only whichever branch ran last.
    """
    body = _method_body(_src(_RECORDER_SRC), "def call(String reason)")
    read_at = body.find("env.UNSTABLE_REASON ?:")
    write_at = body.find("env.UNSTABLE_REASON =")
    assert read_at != -1, (
        "mcdUnstableReason must read the existing env.UNSTABLE_REASON before "
        "writing, or the second cause in a build overwrites the first. "
        "See mjs-q4x."
    )
    assert read_at < write_at, (
        "mcdUnstableReason reads UNSTABLE_REASON after assigning it, so the "
        "append is not actually appending. See mjs-q4x."
    )
    assert "join(" in body, (
        "mcdUnstableReason must join the accumulated reasons. See mjs-q4x."
    )


def test_recorder_does_not_duplicate_a_repeated_reason() -> None:
    """A retried stage must not say the same thing twice (mjs-q4x)."""
    body = _method_body(_src(_RECORDER_SRC), "def call(String reason)")
    assert "contains(" in body, (
        "mcdUnstableReason must skip a reason it has already recorded, so a "
        "retried stage does not produce 'X; X'. See mjs-q4x."
    )


def test_recorder_does_not_change_the_build_result() -> None:
    """Recording a reason must never mark a build unstable by itself (mjs-q4x).

    Marking belongs to the caller. If the recorder also marked, calling it from
    a diagnostic path would silently downgrade a green build.
    """
    src = _src(_RECORDER_SRC)
    code = "\n".join(
        line for line in src.splitlines()
        if not line.strip().startswith("//") and not line.strip().startswith(">>>")
    )
    assert "currentBuild.result" not in code, (
        "mcdUnstableReason must not set currentBuild.result — recording a "
        "reason and marking the build are deliberately separate. See mjs-q4x."
    )


# ---------------------------------------------------------------------------
# The notifier: amber, quiet, and valid JSON
# ---------------------------------------------------------------------------


def test_notifier_exists_alongside_success_and_failure() -> None:
    """discordNotify grows an unstable() sibling (mjs-q4x)."""
    src = _src(_DISCORD_SRC)
    assert "def unstable(Map config)" in src, (
        "discordNotify needs an unstable(Map config) alongside success() and "
        "failure(). See mjs-q4x."
    )


def test_notifier_is_amber_not_failure_red() -> None:
    """Amber, so unstable does not read as broken (mjs-q4x)."""
    body = _method_body(_src(_DISCORD_SRC), "def unstable(Map config)")
    assert f'"color": {_AMBER}' in body, (
        f"discordNotify.unstable must use amber ({_AMBER}), the tone "
        "awaitingApproval already uses. See mjs-q4x."
    )
    assert _FAILURE_RED not in body, (
        f"discordNotify.unstable uses failure red ({_FAILURE_RED}). An unstable "
        "build is not a failure, and colouring it red is how people learn to "
        "ignore red. See mjs-q4x."
    )


def test_notifier_does_not_mention_anyone() -> None:
    """Amber does not ping: a mention is a failure-grade signal (mjs-q4x)."""
    body = _method_body(_src(_DISCORD_SRC), "def unstable(Map config)")
    assert "allowed_mentions" not in body, (
        "discordNotify.unstable must not @-mention. Unstable builds are "
        "expected on branches with a soft gate armed; pinging for them is how "
        "the channel becomes noise. See mjs-q4x."
    )


def test_notifier_links_the_console_log() -> None:
    """The reason is a summary; the log is the detail (mjs-q4x)."""
    body = _method_body(_src(_DISCORD_SRC), "def unstable(Map config)")
    assert "consoleUrl" in body, (
        "discordNotify.unstable must link the console log — the recorded reason "
        "names the cause, the log has the validator output. See mjs-q4x."
    )


def test_notifier_escapes_the_interpolated_text() -> None:
    """Machine-generated text must not be able to break the payload (mjs-q4x)."""
    body = _method_body(_src(_DISCORD_SRC), "def unstable(Map config)")
    assert "jsonEscape(config.message" in body, (
        "discordNotify.unstable must escape config.message. See mjs-q4x."
    )
    assert "jsonEscape(config.reason" in body, (
        "discordNotify.unstable must escape config.reason — it carries "
        "validator text, paths and exit codes straight from a shell. "
        "See mjs-q4x."
    )


def test_json_escape_handles_every_character_that_breaks_a_payload() -> None:
    """Backslash, quote, CR, LF and TAB (mjs-q4x).

    Discord answers a malformed body with a silent 400, so an unescaped quote
    does not produce an ugly message — it produces no message at all, which is
    the same silence this whole change exists to remove.
    """
    body = _method_body(_src(_DISCORD_SRC), "def jsonEscape(String value)")
    for raw, why in [
        (r"replace('\\', '\\\\')", "a trailing backslash escapes the closing quote"),
        (r"""replace('"', '\\"')""", "an unescaped quote truncates the payload"),
        (r"replace('\r', '')", "a raw CR is not legal in a JSON string"),
        (r"replace('\n', '\\n')", "a raw newline is not legal in a JSON string"),
        (r"replace('\t', ' ')", "a raw tab is not legal in a JSON string"),
    ]:
        assert raw in body, (
            f"discordNotify.jsonEscape dropped `{raw}`: {why}. See mjs-q4x."
        )
    assert body.find(r"replace('\\', '\\\\')") < body.find(r"""replace('"', '\\"')"""), (
        "jsonEscape must escape backslashes BEFORE quotes, or the backslash it "
        "inserts in front of a quote gets escaped again. See mjs-q4x."
    )


def _interpolate(template: str, value: str = "X") -> str:
    """Replace every ${...} in a Groovy template with a scalar."""
    out = []
    index = 0
    while index < len(template):
        if template.startswith("${", index):
            depth = 0
            for scan in range(index + 1, len(template)):
                if template[scan] == "{":
                    depth += 1
                elif template[scan] == "}":
                    depth -= 1
                    if depth == 0:
                        out.append(value)
                        index = scan + 1
                        break
            else:
                pytest.fail("unterminated ${...} in payload template. See mjs-q4x.")
        else:
            out.append(template[index])
            index += 1
    return "".join(out)


def _payload_template(method_body: str) -> str:
    marker = 'def payload = """'
    start = method_body.find(marker)
    assert start != -1, "no payload template in the method. See mjs-q4x."
    start += len(marker)
    end = method_body.find('"""', start)
    assert end != -1, "unterminated payload template. See mjs-q4x."
    return method_body[start:end]


@pytest.mark.parametrize(
    "fields_json",
    [
        pytest.param(
            '{"name":"Environment","value":"Development","inline":true},'
            '{"name":"Branch","value":"features/card","inline":true},'
            '{"name":"Duration","value":"4 min 2 sec","inline":true},'
            '{"name":"Commit","value":"`abc1234` msg","inline":false},'
            '{"name":"Author","value":"trowsey","inline":true}',
            id="no-reason",
        ),
        pytest.param(
            '{"name":"Environment","value":"Development","inline":true},'
            '{"name":"Branch","value":"features/card","inline":true},'
            '{"name":"Duration","value":"4 min 2 sec","inline":true},'
            '{"name":"Unstable because",'
            '"value":"card validation errors (validator exit 3); '
            'debug symbols missing from Sentry","inline":false},'
            '{"name":"Commit","value":"`abc1234` msg","inline":false},'
            '{"name":"Author","value":"trowsey","inline":true}',
            id="two-reasons",
        ),
        pytest.param(
            '{"name":"Unstable because",'
            r'"value":"validator said \"bad card\" in C:\\Data\\x\nline 2",'
            '"inline":false}',
            id="pre-escaped-quotes-backslashes-newline",
        ),
    ],
)
def test_notifier_payload_is_valid_json(fields_json: str) -> None:
    """The hand-built embed must parse (mjs-q4x).

    discordNotify assembles its payloads as raw strings, so a stray comma or an
    unbalanced brace is not a compile error — it is a 400 from Discord and a
    notification nobody receives. This interpolates the template as it stands in
    the file and parses the result.
    """
    template = _payload_template(
        _method_body(_src(_DISCORD_SRC), "def unstable(Map config)")
    )
    payload = _interpolate(template.replace("${fieldsJson}", fields_json))
    try:
        parsed = json.loads(payload)
    except json.JSONDecodeError as exc:
        pytest.fail(
            f"discordNotify.unstable builds invalid JSON: {exc}\n"
            f"payload was:\n{payload}\nSee mjs-q4x."
        )
    assert parsed["embeds"][0]["color"] == int(_AMBER)
    assert parsed["embeds"][0]["fields"] == json.loads(f"[{fields_json}]")
    assert "content" not in parsed, (
        "the unstable embed must not carry a mention. See mjs-q4x."
    )
