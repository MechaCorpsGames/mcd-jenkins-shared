"""Tests for the TestClient Unit Tests stage (mc-plov).

MCDTestClientTest is 290 gtest cases under Src/TestClient/Test/, including the
determinism replay harness under Test/replay/. MCDClient's `testclient` target
has always BUILT that binary, and Src/TestClient/build.py has always accepted
--test, but no Makefile target and no pipeline stage ever invoked it. The suite
compiled on every build and executed nowhere.

What that cost: ProtocolVersionPinTest exists to make a silent PROTOCOL_VERSION
drift impossible. Its kExpectedProtocolVersion sat at 42 while PROTOCOL_VERSION
reached 45, drifting through three consecutive bumps, because nothing ever ran
the pin. A control that reports success by not running is worse than no control,
because its existence is the reason nobody looks.

This file guards the three properties that stage has to keep, and they pull
against each other on purpose:

1. It must actually invoke the suite, and against the Release tree the previous
   stage already built rather than a second full compile.
2. It must not red-line branches that lag main. This library is shared by every
   job, and release, features/backend and features/card have no test-testclient
   target until the MCDClient change reaches them. Same skew problem, and the
   same fix, as the Script Tests stage (mc-lxj5).
3. The skew guard must not become a failure swallow. A target that is absent is
   skipped and said out loud; a target that exists and fails still fails the
   build.

Run with: pytest test/unit/test_mcd_testclient_test_stage.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_PR_SRC = _VARS / "mcdPRValidationPipeline.groovy"
_CHANGE_SRC = _VARS / "mcdChangeDetection.groovy"

_STAGE = "TestClient Unit Tests"


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-plov."
        )
    return path.read_text()


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-plov."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-plov."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-plov.")


# ---------------------------------------------------------------------------
# It must actually run the suite
# ---------------------------------------------------------------------------


def test_stage_exists() -> None:
    """The stage is present at all (mc-plov)."""
    assert f"stage('{_STAGE}')" in _src(_PR_SRC), (
        f"mcdPRValidationPipeline lost the {_STAGE!r} stage. Without it "
        "MCDTestClientTest goes back to compiling on every build and running "
        "nowhere, which is how the protocol version pin drifted through three "
        "bumps unnoticed. See mc-plov."
    )


def test_stage_invokes_the_suite() -> None:
    """It calls the Make target, not merely the build (mc-plov)."""
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "make test-testclient" in body, (
        f"{_STAGE} must invoke `make test-testclient`. Building the binary is "
        "what already happened for years without running it. See mc-plov."
    )


def test_stage_reuses_the_release_build() -> None:
    """MODE=release, so the prerequisite build is incremental (mc-plov).

    'Build GameServer, TestClient & Proxy' has already produced
    Src/TestClient/build/Release via deploy.sh --release. Default MODE is debug,
    which would configure and compile a second, unrelated tree.
    """
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "MODE=release" in body, (
        f"{_STAGE} must pass MODE=release so it tests the Release tree the "
        "previous stage built, instead of compiling a whole second Debug tree. "
        "See mc-plov."
    )


# ---------------------------------------------------------------------------
# It must not red-line branches that lag main
# ---------------------------------------------------------------------------


def test_stage_guards_against_branch_skew() -> None:
    """A `make -n` probe precedes the invocation (mc-plov).

    release, features/backend and features/card have no test-testclient target
    until the MCDClient change reaches them, and this library is shared by every
    job, so an unguarded call fails PR validation on those branches the moment
    it merges.
    """
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "make -n test-testclient" in body, (
        f"{_STAGE} lost its branch-skew guard. Branches that lag main have no "
        "test-testclient target, so this stage would red-line their PR "
        "validation. Same guard as Script Tests. See mc-plov and mc-lxj5."
    )


def test_skew_guard_precedes_the_invocation() -> None:
    """The probe is checked before the suite is invoked (mc-plov)."""
    body = _stage_body(_src(_PR_SRC), _STAGE)
    probe = body.find("make -n test-testclient")
    real = body.find("make test-testclient MODE=release")
    assert probe != -1 and real != -1, "stage lost the probe or the invocation"
    assert probe < real, (
        f"{_STAGE} runs the suite before probing for the target, so the guard "
        "cannot prevent anything. See mc-plov."
    )


# ---------------------------------------------------------------------------
# The guard must not become a failure swallow
# ---------------------------------------------------------------------------


def test_guard_does_not_swallow_a_real_failure() -> None:
    """A target that exists and fails still fails the build (mc-plov).

    This is the property the whole bead is about. Wiring a runner that cannot
    go red would reproduce the exact defect it was written to fix.
    """
    body = _stage_body(_src(_PR_SRC), _STAGE)
    for swallow in ("make test-testclient MODE=release || true",
                    "make test-testclient MODE=release || echo"):
        assert swallow not in body, (
            f"{_STAGE} swallows the suite's exit code. A runner that cannot "
            "fail is the same defect as a suite that never runs. See mc-plov."
        )
    assert "catchError" not in body, (
        f"{_STAGE} wraps the suite in catchError, which downgrades a real test "
        "failure. See mc-plov."
    )


# ---------------------------------------------------------------------------
# The stage must not claim a check that does not exist
# ---------------------------------------------------------------------------


def test_stage_name_does_not_claim_determinism_is_verified() -> None:
    """The stage name must not imply the replay harness checks determinism (mc-plov).

    ReplaySession::Run validates the action-log header, walks the entries to
    confirm they parse, counts them, and returns ExitCode::Identical. It never
    connects to a GameServer and hands EmitTrace an unpopulated
    CheckpointSampler, so every run reports "checkpoint snapshots captured = 0"
    and all three committed baselines are the empty {"checkpoints":[]}. Real
    comparison arrives in mc-9t1.14.

    A stage name is what people read instead of the source. Naming this stage
    after determinism would switch on a green light for a check that does not
    exist, which is worse than the state before this stage existed, where at
    least nothing claimed anything.
    """
    src = _src(_PR_SRC)
    forbidden = ("stage('Determinism Harness')",
                 "stage('Determinism Check')",
                 "stage('Determinism Tests')",
                 "stage('Determinism Verification')")
    for name in forbidden:
        assert name not in src, (
            f"{name} claims the replay harness verifies determinism. It does not "
            "yet: the foundation walk only proves the action log parses. See "
            "mc-plov and mc-9t1.14."
        )
    assert "determinism" not in _STAGE.lower(), (
        f"the stage name {_STAGE!r} implies determinism is verified. It is not "
        "yet. See mc-plov and mc-9t1.14."
    )


# ---------------------------------------------------------------------------
# The gate has to agree with the routing
# ---------------------------------------------------------------------------


def test_stage_is_gated_on_server_changed() -> None:
    """SERVER_CHANGED is the flag Src/TestClient/ actually sets (mc-plov)."""
    body = _stage_body(_src(_PR_SRC), _STAGE)
    assert "env.SERVER_CHANGED == 'true'" in body, (
        f"{_STAGE} must be gated on SERVER_CHANGED, the category that "
        "Src/TestClient/ routes to in mcdChangeDetection. See mc-plov."
    )


def test_testclient_paths_route_to_the_gating_category() -> None:
    """Src/TestClient/ maps to 'server', so the gate above can fire (mc-plov).

    Gate and routing are edited in different files. If Src/TestClient/ is ever
    moved to another category, the stage silently stops running on the very
    changes it exists to cover, which is this bead's failure mode again.
    """
    src = _src(_CHANGE_SRC)
    assert "'Src/TestClient/'" in src, (
        "mcdChangeDetection no longer mentions Src/TestClient/. The "
        f"{_STAGE} stage is gated on SERVER_CHANGED and would stop firing on "
        "TestClient changes. See mc-plov."
    )
