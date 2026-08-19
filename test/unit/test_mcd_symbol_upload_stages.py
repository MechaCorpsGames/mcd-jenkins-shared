"""Tests for the Sentry symbol upload and Script Tests stages (mc-lxj5).

Covers two properties that this shared library got wrong before, in opposite
directions.

1. The symbol upload stages must never swallow a failure. They used to end
   every sentry-cli call with `|| echo "(non-fatal)"`, so an HTTP 403 on every
   single upload still reported SUCCESS in 20 seconds and native crashes
   symbolicated to nothing but a signal number. See ADR 0135 in MCDClient.

2. The Script Tests stage must not red-line branches that lag main. This
   library is shared by every job, but `make test-scripts` and
   `scripts/verify_sentry_symbols.py` live in MCDClient and only exist on main.
   release, features/backend and features/card do not have them, so an
   unguarded invocation fails PR validation on those branches the moment this
   library merges.

The two pull in opposite directions, which is the point: a target that is
absent is skipped, a target that exists and fails still fails the build.

Run with: pytest test/unit/test_mcd_symbol_upload_stages.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_CLIENT_SRC = _VARS / "mcdClientPipeline.groovy"
_SERVER_SRC = _VARS / "mcdServerPipeline.groovy"
_PR_SRC = _VARS / "mcdPRValidationPipeline.groovy"

_UPLOAD_PIPELINES = (_CLIENT_SRC, _SERVER_SRC)


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-lxj5."
        )
    return path.read_text()


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage.

    Brace-matched rather than "up to the next stage": Upload Debug Symbols is
    the last stage in both pipelines, so a naive scan runs on into the post
    blocks and picks up unrelated things like `du -h ... || echo 'N/A'`.
    """
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-lxj5."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-lxj5."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-lxj5.")


# ---------------------------------------------------------------------------
# The upload must not swallow its own failure
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_upload_stage_does_not_swallow_failures(path: Path) -> None:
    """No `|| echo` swallow anywhere in Upload Debug Symbols (mc-lxj5)."""
    body = _stage_body(_src(path), "Upload Debug Symbols")
    assert "|| echo" not in body, (
        f"{path.name} Upload Debug Symbols reintroduced the `|| echo (non-fatal)` "
        "swallow. That is what kept a 403 on every upload invisible for months. "
        "See mc-lxj5 and ADR 0135."
    )


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_upload_failure_marks_build_unstable(path: Path) -> None:
    """A failed upload must set UNSTABLE, not pass silently (mc-lxj5)."""
    body = _stage_body(_src(path), "Upload Debug Symbols")
    assert "currentBuild.result = 'UNSTABLE'" in body, (
        f"{path.name} Upload Debug Symbols must mark the build UNSTABLE when "
        "symbols do not reach Sentry. See mc-lxj5."
    )


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_unstable_result_notifies_discord(path: Path) -> None:
    """post{success} does not run on UNSTABLE, so a post{unstable} must exist (mc-lxj5).

    Without this, marking the build unstable makes Discord quieter rather than
    louder, which is the opposite of the intent.
    """
    src = _src(path)
    assert "unstable {" in src, (
        f"{path.name} sets currentBuild.result = 'UNSTABLE' but has no "
        "post { unstable } handler, so an unstable build notifies nobody. "
        "See mc-lxj5."
    )


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_upload_waits_for_sentry_processing(path: Path) -> None:
    """--wait, so verification cannot race the upload (mc-lxj5)."""
    body = _stage_body(_src(path), "Upload Debug Symbols")
    assert "--wait" in body, (
        f"{path.name} must pass --wait to `debug-files upload` so Sentry has "
        "finished processing before the verifier asks about the build id. "
        "See mc-lxj5."
    )


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_auth_token_is_not_traced_into_the_build_log(path: Path) -> None:
    """`set +x` before the token is read (mc-lxj5).

    Jenkins runs sh with -x, which printed the Sentry auth token in plaintext
    into roughly a thousand console logs.
    """
    body = _stage_body(_src(path), "Upload Debug Symbols")
    assert "set +x" in body, (
        f"{path.name} must `set +x` before reading SENTRY_TOKEN, or Jenkins "
        "traces the token into the console log. See mc-lxj5."
    )
    token_pos = body.find("SENTRY_AUTH_TOKEN")
    assert body.find("set +x") < token_pos, (
        f"{path.name} must `set +x` BEFORE touching SENTRY_AUTH_TOKEN. "
        "See mc-lxj5."
    )


# ---------------------------------------------------------------------------
# Branch skew: the verifier only exists on MCDClient main
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_verifier_absence_is_reported_not_crashed_on(path: Path) -> None:
    """A branch without the verifier gets an actionable message (mc-lxj5).

    release and the long-lived feature branches do not carry
    scripts/verify_sentry_symbols.py, so calling it bare yields a raw python
    ENOENT that reads like a broken pipeline rather than branch skew.
    """
    body = _stage_body(_src(path), "Upload Debug Symbols")
    assert "if [ ! -f scripts/verify_sentry_symbols.py ]" in body, (
        f"{path.name} must check that scripts/verify_sentry_symbols.py exists "
        "before invoking it, so branches that lag main say why. See mc-lxj5."
    )


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_missing_verifier_still_fails_the_stage(path: Path) -> None:
    """Unverified is not the same as verified: the guard exits non-zero (mc-lxj5).

    The stage must not report success just because it could not run the check.
    """
    body = _stage_body(_src(path), "Upload Debug Symbols")
    guard = body[body.find("if [ ! -f scripts/verify_sentry_symbols.py ]"):]
    guard = guard[: guard.find("fi")]
    assert "exit 1" in guard, (
        f"{path.name} must exit non-zero when the verifier is absent. Skipping "
        "the check silently is how the original bug hid. See mc-lxj5."
    )


def test_script_tests_stage_skips_branches_without_the_target() -> None:
    """Script Tests must not fail on branches lacking `make test-scripts` (mc-lxj5).

    This library is shared by every job. release, features/backend and
    features/card all lag main and have no test-scripts target, so a bare
    `make test-scripts` reds out PR validation on those branches.
    """
    body = _stage_body(_src(_PR_SRC), "Script Tests")
    assert "make -n test-scripts" in body, (
        "Script Tests must probe for the target with `make -n test-scripts` "
        "before running it, so branches that predate the target are skipped "
        "rather than failed. See mc-lxj5."
    )
    assert "exit 0" in body, (
        "Script Tests must exit 0 when the target is absent. See mc-lxj5."
    )


def test_script_tests_stage_still_runs_the_target_when_present() -> None:
    """The guard must not turn into a permanent skip (mc-lxj5)."""
    body = _stage_body(_src(_PR_SRC), "Script Tests")
    # On its own line, so the probe (`make -n test-scripts`) does not satisfy
    # this. Matched by regex rather than literal indentation so a reindent of
    # the stage does not fail the build.
    assert re.search(r"^\s*make test-scripts\s*$", body, re.MULTILINE), (
        "Script Tests must still invoke `make test-scripts` unguarded once the "
        "probe succeeds, otherwise the stage never tests anything. See mc-lxj5."
    )


def test_script_tests_stage_does_not_swallow_test_failures() -> None:
    """A real test failure must still fail the build (mc-lxj5).

    The skip is for an absent target only. `make test-scripts || echo ...`
    would recreate exactly the bug this whole change exists to remove.
    """
    body = _stage_body(_src(_PR_SRC), "Script Tests")
    assert "|| echo" not in body, (
        "Script Tests must not swallow failures with `|| echo`. Skip an absent "
        "target, never a failing one. See mc-lxj5."
    )
    assert "|| true" not in body, (
        "Script Tests must not swallow failures with `|| true`. See mc-lxj5."
    )


# ---------------------------------------------------------------------------
# The upload must send symbols, not build scratch (mc-lg8x)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_upload_selects_symbol_files_not_whole_build_trees(path: Path) -> None:
    """Uploading a build tree wholesale ships .o objects and CMake probes.

    MCDClient-Main #1109 and #1110 both went UNSTABLE with "some symbols did
    not process correctly", and the files Sentry rejected were
    CMakeDetermineCompilerABI_*.bin and .o objects under CMakeFiles/ — build
    scratch with no symbolication value that nobody ships. The stage now
    selects symbol-bearing extensions out of the build trees instead of
    handing sentry-cli the directory.
    """
    body = _stage_body(_src(path), "Upload Debug Symbols")
    assert "SYMBOL_PATHS" in body, (
        f"{path.name} Upload Debug Symbols no longer builds a SYMBOL_PATHS "
        "list. Passing a build directory to sentry-cli uploads .o objects and "
        "CMake compiler-probe binaries, which is what failed #1109/#1110. "
        "See mc-lg8x."
    )
    assert "-name '*.so'" in body, (
        f"{path.name} Upload Debug Symbols must filter the build trees down to "
        "symbol-bearing files. See mc-lg8x."
    )


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_verification_runs_even_when_the_upload_reports_failure(path: Path) -> None:
    """A partial upload failure must not skip the check that answers the question.

    sentry-cli exits non-zero when ANY file fails, including files we never
    ship. Under `set -e` that aborted the block before
    verify_sentry_symbols.py ran, so the build reported "symbols missing"
    without anyone establishing whether the shipped library was in Sentry.
    The verification is the real gate and must always execute.

    This does NOT reintroduce the mc-lxj5 swallow: a failed *verification*
    still marks the build UNSTABLE, which
    test_upload_failure_marks_build_unstable pins.
    """
    body = _stage_body(_src(path), "Upload Debug Symbols")
    assert "UPLOAD_STATUS" in body, (
        f"{path.name} Upload Debug Symbols must capture the uploader's exit "
        "status rather than letting `set -e` abort before verification. "
        "See mc-lg8x."
    )
    upload_at = body.index("debug-files upload")
    verify_at = body.index("verify_sentry_symbols.py")
    assert upload_at < verify_at, (
        f"{path.name} verification must follow the upload, not precede it."
    )


# ---------------------------------------------------------------------------
# A transient Sentry processing failure must not orphan a build forever (mc-lg8x)
# ---------------------------------------------------------------------------


def _retry_loop_body(stage_body: str, path: Path) -> str:
    """Return the text between `while : ; do` and its matching `done`.

    The stage also contains a `for d in ...; do ... done` that builds
    SYMBOL_PATHS, and it sits above the retry loop, so scanning forward from
    the `while` for the first `done` line does not pick it up.

    Groovy's `\\$` escape is decoded to a plain `$` on the way out. The shell
    body is a GString, so every shell variable is spelled `\\$NAME` in the
    source; asserting on the escaped form would pin the quoting style rather
    than the behaviour, and would silently stop matching if the stage were
    ever moved into a single-quoted heredoc.
    """
    start = stage_body.find("while : ; do")
    assert start != -1, (
        f"{path.name} Upload Debug Symbols has no `while : ; do` retry loop. "
        "See mc-lg8x."
    )
    lines = stage_body[start:].splitlines()
    for index, line in enumerate(lines[1:], start=1):
        if line.strip() == "done":
            return "\n".join(lines[:index]).replace("\\$", "$")
    pytest.fail(f"{path.name} retry loop is never closed by `done`. See mc-lg8x.")


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_a_failed_verification_retries_the_upload(path: Path) -> None:
    """Sentry can reject a file it accepted, and it recovers on its own.

    MCDServer-Main #873 uploaded the shipped binary and Sentry answered "An
    unknown error occurred" twice, leaving the build id unregistered. #875,
    #876, #877 and #880 uploaded the same artifact cleanly with no pipeline
    change in between, so the failure was transient on Sentry's side.

    A single-shot upload turns that hiccup into a permanent hole: nothing ever
    re-uploads symbols for a version that already deployed, so v0.2.873's
    native crashes symbolicate to nothing for as long as that build exists.
    The retry has to re-run the UPLOAD, not just re-ask the verifier, because
    the file is what failed to process.
    """
    body = _stage_body(_src(path), "Upload Debug Symbols")
    loop = _retry_loop_body(body, path)
    assert "debug-files upload" in loop, (
        f"{path.name} retries around the verifier but leaves the upload "
        "outside the loop. Re-asking Sentry about a file it failed to process "
        "never changes the answer. See mc-lg8x."
    )
    assert "verify_sentry_symbols.py" in loop, (
        f"{path.name} retry loop does not re-verify, so it cannot tell whether "
        "the retry worked. See mc-lg8x."
    )


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_the_verifier_is_what_ends_the_retry_loop(path: Path) -> None:
    """sentry-cli's exit code must not decide when to stop (mc-lg8x).

    sentry-cli exits non-zero when ANY file fails, including files we do not
    ship, and it exits zero for an upload whose processing later failed. Only
    verify_sentry_symbols.py asks whether THIS binary's build id is in Sentry,
    so only it can say the loop is done.
    """
    loop = _retry_loop_body(_stage_body(_src(path), "Upload Debug Symbols"), path)
    assert "VERIFY_STATUS" in loop, (
        f"{path.name} retry loop does not capture the verifier's exit status. "
        "See mc-lg8x."
    )
    break_at = loop.find("break")
    assert break_at != -1, f"{path.name} retry loop never breaks. See mc-lg8x."
    condition = loop[:break_at]
    assert re.search(r'\[ "?\$VERIFY_STATUS"? -eq 0 \]', condition), (
        f"{path.name} must leave the retry loop on the verifier's result, not "
        "on the uploader's exit code. See mc-lg8x."
    )


@pytest.mark.parametrize("path", _UPLOAD_PIPELINES, ids=lambda p: p.name)
def test_the_retry_is_bounded_and_still_fails_the_stage(path: Path) -> None:
    """A retry that never gives up hangs the build instead of reporting (mc-lg8x).

    A genuinely broken upload -- a revoked token, a binary Sentry will never
    accept -- has to end as UNSTABLE, which means the shell block has to exit
    non-zero once the attempts are spent. Retrying forever would trade a
    visible failure for a hung executor, and this library is shared by every
    job on the controller.
    """
    loop = _retry_loop_body(_stage_body(_src(path), "Upload Debug Symbols"), path)
    assert "SYMBOL_UPLOAD_ATTEMPTS" in loop, (
        f"{path.name} retry loop has no attempt ceiling. See mc-lg8x."
    )
    assert re.search(r'-ge "?\$SYMBOL_UPLOAD_ATTEMPTS"?', loop), (
        f"{path.name} retry loop never compares the attempt against the "
        "ceiling, so it cannot stop. See mc-lg8x."
    )
    assert re.search(r"exit \$VERIFY_STATUS", loop), (
        f"{path.name} must exit non-zero when the attempts are spent, so the "
        "stage still marks the build UNSTABLE. Falling out of the loop quietly "
        "is the mc-lxj5 swallow in a new shape. See mc-lg8x."
    )
