"""A proxy deploy must build its new image BEFORE removing the running one (mc-ic6h).

The `Deploy Proxy (if changed)` stage in `mcdServerPipeline.groovy` used to run,
in one `sh` block and in this order:

    docker rm -f mcd-main-proxy-1     <- the serving container, destroyed
    docker build --no-cache ...       <- a full from-scratch image build
    docker compose up --force-recreate

Nothing serves for the whole length of that build. The `--no-cache` makes it a
real build every time (apt-get, go build), not a cache hit.

On 2026-08-24 that gap was ten seconds on MCDServer-Main #958, and it destroyed
a live match. Timestamps from the build log and the player's net log
(bundle 1b5d3cc3), which agree to the second:

    22:22:31  match healthy, "Unpicked cards scrapped"
    22:22:33  docker rm -f      -> client sees 1006 Abnormal closure
    22:22:35  still building    -> reconnect 1/5 gets 502 Bad Gateway
    22:22:43  compose up        -> reconnect 2/5 fires this very second
    22:22:47  new proxy, no rooms -> 401 "match no longer exists", and it is gone

The 502 window IS the image build. A 502 means the gateway had no backend at
all, which is precisely true between the `rm -f` and the `up`.

This is worse than a gateway blip because the proxy container also SPAWNS the
game server processes (`-godot-binary` / `-godot-project` in its compose cmd),
so removing it kills every in-flight match along with the socket.

Reordering does NOT make deploys safe for live matches: the `--force-recreate`
is still a hard cut, and a client still gets 1006 then 401. It removes only the
part of the outage that was pure ordering, taking it from a full image build
down to a container recreate. The remaining question (should a deploy defer
while a match holds an `.in-use` lease) is deliberately not settled here.

Why this test is structural rather than a text match: the fix is an ORDER, and
both commands are present either way. Only their relative position encodes it.
Comments are stripped first because the explanatory comment added alongside the
fix quotes the command name in prose, and a naive scan would match that.

Run with: pytest test/unit/test_proxy_image_builds_before_teardown.py
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

# The container the pipeline is replacing, as written in the groovy source.
_RM_SERVING = re.compile(r"^docker rm -f \$\{containerName\}")
_DOCKER_BUILD = re.compile(r"^docker build\b")


def _sh_blocks(src: str) -> list[str]:
    """Return the bodies of every triple-quoted block in a groovy source file.

    The pipeline writes its shell as `sh \"\"\"...\"\"\"`, so the odd-indexed
    segments of a split on the triple quote are the block bodies.
    """
    parts = src.split('"""')
    return parts[1::2]


def _strip_sh_comments(block: str) -> list[str]:
    """Drop whole-line `#` comments from a shell block, keeping line order.

    Only whole-line comments go. A `#` inside a command survives, which is
    what we want: this is about which COMMANDS run in which order, and the
    fix ships with a comment that names both commands in prose.
    """
    out = []
    for line in block.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        out.append(stripped)
    return out


def _pipeline_files() -> list[Path]:
    return sorted(_VARS.glob("*.groovy"))


def _first_index(lines: list[str], pattern: re.Pattern) -> int:
    for i, line in enumerate(lines):
        if pattern.match(line):
            return i
    return -1


@pytest.mark.parametrize("path", _pipeline_files(), ids=lambda p: p.name)
def test_image_is_built_before_the_serving_container_is_removed(path: Path) -> None:
    """Any block that both builds an image and removes the serving proxy builds first."""
    blocks = _sh_blocks(path.read_text())

    checked = 0
    for n, block in enumerate(blocks):
        lines = _strip_sh_comments(block)
        rm_at = _first_index(lines, _RM_SERVING)
        build_at = _first_index(lines, _DOCKER_BUILD)

        # Only blocks that do BOTH are in scope. The "container not running,
        # starting..." branch removes a stale name without building, and has
        # no live match to protect, so it is correctly exempt.
        if rm_at == -1 or build_at == -1:
            continue

        checked += 1
        assert build_at < rm_at, (
            f"{path.name}: shell block {n} removes the serving proxy container "
            f"(line {rm_at} of the block) BEFORE building its replacement image "
            f"(line {build_at}). That leaves nothing serving for the length of a "
            f"--no-cache image build, which on MCDServer-Main #958 was ten seconds "
            f"and destroyed a live match (mc-ic6h). Build the image first, then "
            f"remove and recreate."
        )

    if path.name == "mcdServerPipeline.groovy":
        assert checked == 1, (
            f"expected exactly one build-and-replace block in {path.name}, "
            f"found {checked}. If the deploy was restructured, re-scope this test "
            f"rather than deleting it."
        )
