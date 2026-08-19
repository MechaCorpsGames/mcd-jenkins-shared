"""A pipeline that syncs a shared deploy tree must serialize its builds (mc-2upj).

`Sync Src Tree` does `git fetch origin --prune`, `git checkout -f -B`, and
`git clean -fd` against ONE directory on the build host that every later stage
then builds and deploys out of. That directory is shared mutable global state.
Two builds of the same job running at once therefore fight over it.

MCDServices-Main #563 lost that fight on 2026-08-18. #562 and #563 overlapped,
both fetched into /var/opt/mechacorpsgames, #562 won, and #563 died on exactly
the seven refs #562 had just moved:

    error: cannot lock ref 'refs/remotes/origin/main':
      is at 2e98e919... but expected 3212b14a...

Every deploy stage was skipped. The quiet half is worse than the red build:
#562 logged "Synced /var/opt/mechacorpsgames to 2e98e919", which is #563's
commit and not its own, then deployed the wiki out of that tree and reported
SUCCESS. Pinning srcRoot to the branch being deployed is the entire point of
the stage, and an unserialized job cannot keep that promise.

Text-matching the fetch would not have caught this, and neither would running
the pipeline once: the bug only appears when two builds overlap. So the check
is structural. Any pipeline that declares the stage must also declare
`disableConcurrentBuilds()` inside its `options {}` block.

mcdAppServicesPipeline already had the guard. mcdServicesPipeline lifted its
Sync Src Tree stage without it, which is the whole bug.

Run with: pytest test/unit/test_sync_src_tree_serializes_builds.py
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_SYNC_STAGE = re.compile(r"""^stage\s*\(\s*['"]Sync Src Tree['"]\s*\)""")
_OPTIONS_OPEN = re.compile(r"^options\s*\{")
_GUARD_CALL = re.compile(r"^disableConcurrentBuilds\s*\(")


def _strip_comments(src: str) -> list[str]:
    """Drop whole-line `//` comments and `/* */` blocks.

    Only whole-line comments are removed, so a `//` inside a URL or a shell
    heredoc survives untouched. That is enough here: the checks below anchor on
    the START of a stripped line, and a declarative option call always occupies
    its own line. Prose that merely NAMES the guard (mcdAppServicesPipeline has
    such a comment) must not satisfy the check, which is what this buys.
    """
    out, in_block = [], False
    for line in src.splitlines():
        stripped = line.strip()
        if in_block:
            out.append("")
            if "*/" in stripped:
                in_block = False
            continue
        if stripped.startswith("/*"):
            out.append("")
            if "*/" not in stripped[2:]:
                in_block = True
            continue
        if stripped.startswith("//") or stripped.startswith("*"):
            out.append("")
            continue
        out.append(line)
    return out


def _options_body(lines: list[str]) -> list[str] | None:
    """The lines inside the pipeline's `options { }` block, or None if absent.

    Brace-counted rather than regexed, so nested calls stay inside the block.
    Comments are already gone, and no options body in this library puts a brace
    inside a string literal.
    """
    for start, raw in enumerate(lines):
        if not _OPTIONS_OPEN.match(raw.strip()):
            continue
        depth, body = 0, []
        for line in lines[start:]:
            depth += line.count("{") - line.count("}")
            body.append(line)
            if depth <= 0:
                return body
        return body
    return None


def _declares_sync_stage(lines: list[str]) -> bool:
    return any(_SYNC_STAGE.match(line.strip()) for line in lines)


_SYNCING_PIPELINES = sorted(
    path
    for path in _VARS.glob("*Pipeline.groovy")
    if _declares_sync_stage(_strip_comments(path.read_text()))
)


def test_the_check_is_not_vacuous() -> None:
    """Guard the guard.

    If `Sync Src Tree` is ever renamed, `_SYNCING_PIPELINES` silently empties
    and every parametrized case below disappears into a green run. Two pipelines
    declare the stage today (mcdServicesPipeline, mcdAppServicesPipeline); fewer
    means the detector stopped detecting, not that the hazard went away.
    """
    names = sorted(path.name for path in _SYNCING_PIPELINES)
    assert len(names) >= 2, (
        f"Expected at least 2 pipelines declaring stage('Sync Src Tree'), found "
        f"{names}. Either the stage was renamed and _SYNC_STAGE needs updating, "
        f"or a deploy tree lost its sync."
    )


@pytest.mark.parametrize("path", _SYNCING_PIPELINES, ids=lambda p: p.name)
def test_sync_src_tree_pipeline_disables_concurrent_builds(path: Path) -> None:
    lines = _strip_comments(path.read_text())
    body = _options_body(lines)

    assert body is not None, (
        f"{path.name} declares stage('Sync Src Tree') but has no options{{}} "
        f"block, so it cannot declare disableConcurrentBuilds()."
    )
    assert any(_GUARD_CALL.match(line.strip()) for line in body), (
        f"{path.name} syncs a shared deploy tree but does not call "
        f"disableConcurrentBuilds() in its options{{}} block. Two builds of the "
        f"job will then race on that tree: the loser's `git fetch` dies with "
        f'"cannot lock ref ... is at X but expected Y" and skips every deploy, '
        f"and the winner resets the tree under whichever build is still using "
        f"it. That is MCDServices-Main #563 on 2026-08-18 (mc-2upj)."
    )
