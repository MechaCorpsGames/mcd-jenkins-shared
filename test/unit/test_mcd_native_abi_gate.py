"""Tests for the Native Library ABI Check stage (mc-if7v).

MCDClient's `check-native-abi` target is the static guard for mc-h53k
(docs/adr/0138): libsentry declares a versioned CURL_OPENSSL_4 requirement,
Steam's scout runtime pins a libcurl exporting only CURL_OPENSSL_3, the engine
could not `dlopen` the Sentry GDExtension at all, and every Linux Steam playtest
on the shipped build captured zero native crashes with nothing failing loudly.

The guard existed and no pipeline ran it. Counting every .groovy/.py/.md file on
this repo's origin/main on 2026-08-24, `check-native-abi` had zero hits, against
one for check-res-case, one for check-authoring-refs and three for
check-adr-ids. It sits in MCDClient's `precommit` chain, and MCDClient's
CLAUDE.md states plainly that no git hook is installed and running precommit is
developer-driven, so it ran when somebody remembered.

That matters because the condition it guards is invisible by construction: it
does not break a build, it deletes evidence. It is unenforced at the moment it
matters most, with three live native-crash beads (mc-adi8, mc-snmx, mc-j1hl)
whose common problem is that the crash evidence is not there.

This file guards the properties the stage has to keep:

1. It must actually invoke the target.
2. It must not red-line branches that lag main. This library is shared by every
   job. The target is present on all four MCDClient branches as of 2026-08-24,
   so the probe is a formality today, but the branch set is not fixed.
3. The skew guard must not become a failure swallow. A target that is absent is
   skipped and said out loud; a target that exists and fails still fails the
   build. This is the property the whole defect class is about (ADR 0141, ADR
   0214), and it is why the stage may not be gated behind `|| true`.
4. It must stay ungated. See test_the_stage_is_not_gated_on_client_changed for
   why that is the deliberate answer here rather than the lazy one.

Run with: pytest test/unit/test_mcd_native_abi_gate.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_PR_SRC = _VARS / "mcdPRValidationPipeline.groovy"

_STAGE = "Native Library ABI Check"
_TARGET = "check-native-abi"


def _src(path: Path = _PR_SRC) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-if7v."
        )
    return path.read_text()


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-if7v."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-if7v."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-if7v.")


# ---------------------------------------------------------------------------
# It must actually run the check
# ---------------------------------------------------------------------------


def test_stage_exists() -> None:
    """The stage is present at all (mc-if7v)."""
    assert f"stage('{_STAGE}')" in _src(), (
        f"mcdPRValidationPipeline lost the {_STAGE!r} stage. Without it "
        f"{_TARGET} goes back to running in no pipeline, which is the state "
        "that let every Linux Steam session capture zero native crashes. "
        "See mc-if7v and mc-h53k."
    )


def test_stage_invokes_the_target() -> None:
    """It calls the Make target, not merely the script (mc-if7v).

    Going through `make` keeps the interpreter choice in one place: the
    Makefile's PYTHON variable exists because Windows ships a python3 shim that
    is not an interpreter.
    """
    body = _stage_body(_src(), _STAGE)
    assert f"make {_TARGET}" in body, (
        f"{_STAGE} must invoke `make {_TARGET}`. See mc-if7v."
    )


# ---------------------------------------------------------------------------
# It must not red-line branches that lag main
# ---------------------------------------------------------------------------


def test_stage_guards_against_branch_skew() -> None:
    """A `make -n` probe precedes the invocation (mc-if7v).

    The target is on all four MCDClient branches today, so this is belt and
    braces, not a live need. It is pinned anyway: this library is shared by
    every job, and the cost of getting it wrong later is a red PR on a branch
    whose change had nothing to do with native libraries. Same guard as
    'ADR Identifier Gate' and 'Script Tests'.
    """
    body = _stage_body(_src(), _STAGE)
    assert f"make -n {_TARGET}" in body, (
        f"{_STAGE} lost its branch-skew guard. See mc-if7v, mc-plov, mc-lxj5."
    )


def test_skew_guard_precedes_the_invocation() -> None:
    """The probe is checked before the target is invoked (mc-if7v)."""
    body = _stage_body(_src(), _STAGE)
    probe = body.find(f"make -n {_TARGET}")
    # `make -n check-native-abi` does not contain the substring
    # `make check-native-abi`, so this finds the real invocation, not the probe.
    real = body.find(f"make {_TARGET}")
    assert probe != -1, "stage lost the probe"
    assert real != -1, "stage lost the unprefixed invocation"
    assert probe < real, (
        f"{_STAGE} runs the check before probing for the target, so the guard "
        "cannot prevent anything. See mc-if7v."
    )


def test_the_skip_path_says_so_out_loud() -> None:
    """An absent target is announced, not silently passed over (mc-if7v).

    A stage that skips in silence reads as a stage that ran. That confusion is
    how this class of defect survives.
    """
    body = _stage_body(_src(), _STAGE)
    assert re.search(rf"echo .*No {_TARGET} target on this branch", body), (
        f"{_STAGE} skips without saying why. See mc-if7v."
    )


# ---------------------------------------------------------------------------
# The guard must not become a failure swallow
# ---------------------------------------------------------------------------


def test_guard_does_not_swallow_a_real_failure() -> None:
    """A target that exists and fails still fails the build (mc-if7v).

    This is the property the whole bead is about. Wiring a check that cannot go
    red would reproduce the exact defect it was written to fix.
    """
    body = _stage_body(_src(), _STAGE)
    for swallow in (f"make {_TARGET} || true",
                    f"make {_TARGET} || echo",
                    f"make {_TARGET} || :"):
        assert swallow not in body, (
            f"{_STAGE} swallows the check's exit code. A gate that cannot fail "
            "is the same defect as a gate that never runs. See mc-if7v."
        )
    assert "catchError" not in body, (
        f"{_STAGE} wraps the check in catchError, which downgrades a real "
        "failure. See mc-if7v."
    )
    assert "|| exit 0" not in body, (
        f"{_STAGE} turns a failing check into a pass. See mc-if7v."
    )


def test_the_body_has_no_shebang_so_jenkins_supplies_errexit() -> None:
    """No shebang means Jenkins runs it as `sh -xe` (mc-if7v, mc-91jj).

    The body is a two-command script: the probe, then the check. Without
    errexit the step's status would be the LAST command's alone. This stage
    gets `-e` from Jenkins because it has no shebang; a future edit that adds
    one must also say `set -e`, which is what
    test_sh_bodies_gate_their_failures.py enforces repo-wide.
    """
    body = _stage_body(_src(), _STAGE)
    assert "#!" not in body, (
        f"{_STAGE} grew a shebang, which drops Jenkins' implicit -e. Add "
        "`set -euo pipefail` to the body if the shebang is intended. "
        "See mc-if7v and mc-91jj."
    )


# ---------------------------------------------------------------------------
# The gating decision
# ---------------------------------------------------------------------------


def test_the_stage_is_not_gated_on_client_changed() -> None:
    """Ungated on purpose, and the reason is not "it was easier" (mc-if7v).

    Both inputs to the check are in the client bucket today: mcdChangeDetection
    routes addons/ and scripts/ to 'client', and check_native_lib_abi.py scans
    addons/sentry/bin/linux and addons/godotsteam/linux{32,64}. So a
    CLIENT_CHANGED gate WOULD fire on a .so swap as things currently stand.
    This is not the 'ADR Identifier Gate' situation, where the gate would have
    skipped the very PRs it polices.

    It is ungated anyway. The check reads committed ELF headers in pure Python
    with no deps and measured 0.06s by hand, so the gate saves nothing
    measurable, while it would make this guard's firing depend on a second file
    continuing to route addons/ to 'client'. A control whose failure mode is
    silence should not be one edit in an unrelated file away from never running
    again -- which is, precisely, the state mc-if7v was filed to end.
    """
    body = _stage_body(_src(), _STAGE)
    assert "CLIENT_CHANGED" not in body, (
        f"{_STAGE} grew a CLIENT_CHANGED gate. That re-couples this guard to "
        "mcdChangeDetection's routing for no measurable saving on a 0.06s "
        "check. If the gate is wanted, change the comment and this test "
        "together, and add a routing pin like "
        "test_testclient_paths_route_to_the_gating_category. See mc-if7v."
    )
    assert "SERVER_CHANGED" not in body, (
        f"{_STAGE} grew a SERVER_CHANGED gate. The binaries it scans are "
        "client-side. See mc-if7v."
    )


def test_the_stage_still_skips_an_already_merged_pr() -> None:
    """PR_ALREADY_MERGED is honoured like every other stage (mc-if7v)."""
    body = _stage_body(_src(), _STAGE)
    when = re.search(r"when \{[^\n]*\}", body)
    assert when is not None, f"{_STAGE} has no when block. See mc-if7v."
    assert "PR_ALREADY_MERGED" in when.group(0), (
        f"{_STAGE} would run on an already-merged PR. See mc-if7v."
    )


def test_the_comment_records_the_gating_decision() -> None:
    """The reason is in the source, not only in the bead (mc-if7v).

    The bead's instruction was to make the gating call deliberately and write
    the reason down either way. A reader deciding whether to add a gate later
    needs the argument in front of them.
    """
    src = _src()
    stage_at = src.index(f"stage('{_STAGE}')")
    preamble = src[max(0, stage_at - 2000):stage_at]
    assert "NOT gated on CLIENT_CHANGED" in preamble, (
        f"the comment above {_STAGE} no longer records why it is ungated. "
        "See mc-if7v."
    )


# ---------------------------------------------------------------------------
# It has to fail fast
# ---------------------------------------------------------------------------


def test_the_stage_runs_before_the_expensive_ones() -> None:
    """A 0.06s check that needs no build should fail before the builds (mc-if7v).

    It reads binaries that are committed to the repo, so unlike the GDScript
    and server suites it has no prerequisite stage at all.
    """
    src = _src()
    here = src.index(f"stage('{_STAGE}')")
    for later in ("stage('Setup Dependencies')",
                  "stage('GDScript Tests')",
                  "stage('Build GameServer, TestClient & Proxy')"):
        assert here < src.index(later), (
            f"{_STAGE} runs after {later}, so a PR that breaks native crash "
            "capture burns a full build first. See mc-if7v."
        )


def test_the_stage_runs_after_the_checkout() -> None:
    """The working tree has to be the PR merge before the scan means anything."""
    src = _src()
    assert src.index("stage('Checkout PR Merge Ref')") < src.index(f"stage('{_STAGE}')")
