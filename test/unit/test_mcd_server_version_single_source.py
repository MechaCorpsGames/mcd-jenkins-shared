"""The server version has ONE source of truth: Src/GameServer/CMakeLists.txt (mc-glpn).

From this library's initial commit, mcdServerPipeline stamped a private
`env.SERVER_VERSION = "0.1.${BUILD_NUMBER}"` while the MCDClient repo's
CMakeLists composed the version the build actually ships: PROJECT_VERSION =
MAJOR.MINOR.BUILD, the binary installed under `versions/v${PROJECT_VERSION}/`,
and `latest.txt` written from it. The two never agreed — CMake said 1.0.x at
the time, then 0.2.x after MCDClient 77d59bbc — so every displayName, manifest
`serverVersion` and Discord line disagreed with the binary and its deploy
path. Found via mc-bs84: build #873's artifacts lived at v0.2.873 while its
reporting said v0.1.873, and anything ordering releases by version string
ranks every current build below the older one.

Three properties:

1. The MAJOR.MINOR derivation is EXECUTED here, not just matched: the sh
   bodies extracted from the Checkout stage run against a fixture CMakeLists
   with the real file's shape and must print the digits. This repo has no CI
   of its own; pytest is the only gate.
2. The failure path is real: on a CMakeLists without the lines the extracted
   shell yields EMPTY output with exit 0 (the pipeline is deliberately
   unpiped by pipefail), which is exactly what the groovy error() guard
   catches — so the guard is reachable, and it must refuse rather than
   default.
3. No second source may return: the "0.1.${BUILD_NUMBER}" literal must stay
   gone from the server pipeline, and Verify Build must hold the drift check
   tying the derived version to CMake's own latest.txt.

Run with: pytest test/unit/test_mcd_server_version_single_source.py
No live Jenkins required.
"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_SRC_PATH = _REPO_ROOT / "vars" / "mcdServerPipeline.groovy"

# The real MCDClient file's shape, verbatim (Src/GameServer/CMakeLists.txt).
_FIXTURE_CMAKELISTS = """\
# Version scheme: Developers control MAJOR.MINOR, CI/CD controls BUILD
set(VERSION_MAJOR 0)
set(VERSION_MINOR 2)
if(NOT DEFINED VERSION_BUILD)
\tset(VERSION_BUILD 0)  # Default for local dev builds
endif()
set(PROJECT_VERSION "${VERSION_MAJOR}.${VERSION_MINOR}.${VERSION_BUILD}")
"""


def _src() -> str:
    if not _SRC_PATH.exists():
        pytest.fail(
            f"{_SRC_PATH} not found. This test must run from the "
            "mcd-jenkins-shared repo root. See mc-glpn."
        )
    return _SRC_PATH.read_text()


def _stage_block(src: str, stage_name: str) -> str:
    """Return the named stage, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-glpn."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-glpn."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start:index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-glpn.")


def _derivation_scripts(checkout_block: str) -> dict[str, str]:
    """The single-quoted sh scripts that read VERSION_MAJOR / VERSION_MINOR."""
    scripts = {}
    for name in ("VERSION_MAJOR", "VERSION_MINOR"):
        found = re.findall(
            r"sh\(script: '([^']*" + name + r"[^']*)'", checkout_block
        )
        assert len(found) == 1, (
            f"expected exactly one sh(script: '...') reading {name} in the "
            f"Checkout stage, found {len(found)}. See mc-glpn."
        )
        scripts[name] = found[0]
    return scripts


def _run(script: str, cwd: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["bash", "-c", script], cwd=cwd, capture_output=True, text=True
    )


@pytest.fixture()
def repo_shape(tmp_path: Path) -> Path:
    gs = tmp_path / "Src" / "GameServer"
    gs.mkdir(parents=True)
    (gs / "CMakeLists.txt").write_text(_FIXTURE_CMAKELISTS)
    return tmp_path


# ---------------------------------------------------------------------------
# 1. The derivation is executed, and extracts what CMakeLists says
# ---------------------------------------------------------------------------


def test_derivation_extracts_major_and_minor(repo_shape: Path) -> None:
    scripts = _derivation_scripts(_stage_block(_src(), "Checkout"))
    major = _run(scripts["VERSION_MAJOR"], repo_shape)
    minor = _run(scripts["VERSION_MINOR"], repo_shape)
    assert major.returncode == 0 and major.stdout.strip() == "0", (
        f"VERSION_MAJOR derivation returned {major.returncode} / "
        f"{major.stdout!r} against the real file shape. See mc-glpn."
    )
    assert minor.returncode == 0 and minor.stdout.strip() == "2", (
        f"VERSION_MINOR derivation returned {minor.returncode} / "
        f"{minor.stdout!r} against the real file shape. See mc-glpn."
    )


def test_derivation_survives_a_leading_tab_or_spaces_never_matching() -> None:
    """The greps anchor on ^set( — an indented or commented copy of the line
    must NOT satisfy them, or a stray mention would silently win over the
    real knob."""
    scripts = _derivation_scripts(_stage_block(_src(), "Checkout"))
    for script in scripts.values():
        assert '"^set(VERSION_' in script, (
            "the derivation lost its line anchor; an indented or commented "
            "mention of VERSION_MAJOR/MINOR could now satisfy it. See mc-glpn."
        )


# ---------------------------------------------------------------------------
# 2. The failure path: empty output + exit 0, caught by a refusing error()
# ---------------------------------------------------------------------------


def test_missing_lines_yield_empty_output_not_a_shell_error(
    tmp_path: Path,
) -> None:
    gs = tmp_path / "Src" / "GameServer"
    gs.mkdir(parents=True)
    (gs / "CMakeLists.txt").write_text("# version lines removed\n")
    scripts = _derivation_scripts(_stage_block(_src(), "Checkout"))
    for name, script in scripts.items():
        result = _run(script, tmp_path)
        assert result.returncode == 0, (
            f"{name} derivation must not fail the sh step itself on a missing "
            f"line (got exit {result.returncode}); the groovy error() guard "
            "carries the diagnosis. See mc-glpn."
        )
        assert result.stdout.strip() == "", (
            f"{name} derivation printed {result.stdout!r} from a file without "
            "the line — the error() guard would never fire. See mc-glpn."
        )


def test_guard_refuses_rather_than_defaults() -> None:
    checkout = _stage_block(_src(), "Checkout")
    assert re.search(r"error\(\s*\"mc-glpn: could not derive", checkout), (
        "the Checkout stage lost the refusing error() guard; a parse failure "
        "would stamp a made-up version. See mc-glpn."
    )
    assert re.search(
        r"env\.SERVER_VERSION\s*=\s*\"\$\{major\}\.\$\{minor\}\.\$\{BUILD_NUMBER\}\"",
        checkout,
    ), "SERVER_VERSION is no longer composed from the derived MAJOR.MINOR. See mc-glpn."


# ---------------------------------------------------------------------------
# 3. No second source may return
# ---------------------------------------------------------------------------


def test_the_private_version_literal_is_gone() -> None:
    src = _src()
    assignments = re.findall(r'env\.SERVER_VERSION\s*=\s*"([^"]*)"', src)
    for value in assignments:
        assert not re.match(r"\d+\.\d+\.", value), (
            f"env.SERVER_VERSION is assigned the literal prefix {value!r} — "
            "that is the second source of truth this test exists to keep "
            "dead. See mc-glpn."
        )


def test_display_name_gains_the_version_at_checkout() -> None:
    checkout = _stage_block(_src(), "Checkout")
    assert 'v${env.SERVER_VERSION}' in checkout, (
        "the Checkout stage no longer writes the versioned displayName; the "
        "build would keep its provisional unversioned name. See mc-glpn."
    )


def test_verify_build_holds_the_drift_check() -> None:
    verify = _stage_block(_src(), "Verify Build")
    assert re.search(
        r'SERVER_VERSION_PATH\s*!=\s*"v\$\{env\.SERVER_VERSION\}/MCDServer"',
        verify,
    ), (
        "Verify Build lost the drift check tying the derived version to "
        "CMake's latest.txt — the two sources could split again silently. "
        "See mc-glpn."
    )
    assert re.search(r"error\(\s*\"mc-glpn: version drift", verify), (
        "the drift check no longer fails the build with the mc-glpn "
        "diagnosis. See mc-glpn."
    )
