"""Every pipeline that calls the trim must publish the commit it owes (mc-k0z92).

WHY THIS FILE EXISTS SEPARATELY FROM test_mcd_trim_keys_on_commit_ancestry.py.

That file tests the helper. This one tests its CALLERS, and the distinction is
the whole point. The mayor's retraction of 2026-09-03, demonstrated on
jenkins-shared PR #129 rather than argued: an isolation test cannot see its own
callers, so if the failure you fear is "the right code stops being called", or
"stops being called with what it needs", no amount of testing that code finds it.

The fix for mc-k0z92 made the trim depend on something it does not own. It reads
`env.commit_sha`, which is contributed by each pipeline's OWN GenericTrigger from
the webhook's `$.after`. The helper cannot check that it is there. And the
failure is SILENT by construction: `mcdRedundantBuild.trim()` falls back to the
checked-out HEAD when the variable is absent, deliberately, so that a caller
without a webhook keeps behaving as it did. A pipeline that drops or renames
`commit_sha` therefore goes back to comparing checked-out commits, which IS
mc-k0z92, while every test of the helper stays green.

The list of pipelines is DERIVED from which files call the trim, not hardcoded,
so a sixth caller added later is covered the day it is added rather than the day
somebody remembers this file.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_VARS = Path(__file__).parent.parent.parent / "vars"

# The variable mcdRedundantBuild reads, and the JSONPath it must be bound to.
# GitHub's push payload puts the commit the push landed on in `after`.
_OWED_VAR = "commit_sha"
_OWED_JSONPATH = "$.after"


def _uncommented(src: str) -> str:
    """Source with `//` comment tails removed.

    Discovery has to do this too, not just the assertions. Files in vars/ discuss
    each other in prose: `mcdChangeBase.groovy` arrived on 2026-09-04 with the
    line "// mcdRedundantBuild.trim(), which writes it whether or not that build
    is", and a raw substring search read that comment as a call and demanded a
    GenericTrigger from a helper that has none. Caught only by re-running against
    a base that had moved. A shape scan over-reports unless it is told what code
    is.
    """
    return "\n".join(re.sub(r"//.*$", "", line) for line in src.splitlines())


def _trim_callers() -> list[Path]:
    callers = sorted(
        path
        for path in _VARS.glob("*.groovy")
        if "mcdRedundantBuild.trim()" in _uncommented(path.read_text())
    )
    assert callers, (
        "no pipeline in vars/ calls mcdRedundantBuild.trim(). Either the trim was "
        "removed from every branch pipeline, or this test is looking in the wrong "
        "place; both are worth failing on. See mc-waxw and mc-k0z92."
    )
    return callers


def _ids(paths: list[Path]) -> list[str]:
    return [p.name for p in paths]


_CALLERS = _trim_callers()


@pytest.mark.parametrize("pipeline", _CALLERS, ids=_ids(_CALLERS))
def test_a_trim_caller_publishes_the_commit_it_was_triggered_for(pipeline: Path) -> None:
    """The caller contract the fix depends on and the helper cannot enforce."""
    src = pipeline.read_text()

    assert re.search(rf"key:\s*'{_OWED_VAR}'", src), (
        f"{pipeline.name} calls mcdRedundantBuild.trim() but its GenericTrigger no "
        f"longer contributes '{_OWED_VAR}'. The trim reads env.{_OWED_VAR} to learn "
        "which commit this build is accountable for; without it the helper falls "
        "back to the checked-out HEAD and silently goes back to comparing two "
        "checked-out commits, which is exactly mc-k0z92. Nothing in the helper's "
        "own tests can see this."
    )


@pytest.mark.parametrize("pipeline", _CALLERS, ids=_ids(_CALLERS))
def test_the_owed_commit_is_bound_to_the_push_head(pipeline: Path) -> None:
    """Present is not enough. It has to be bound to the right thing.

    `commit_sha` pointing at `$.before`, or at a head_commit id that a coalesced
    push does not carry, would leave the variable present and the containment
    test answering about the wrong commit. That is worse than absent: absent
    fails open into a normal build, wrong trims against a tree chosen by a typo.
    """
    src = pipeline.read_text()

    match = re.search(
        rf"\[\s*key:\s*'{_OWED_VAR}'\s*,\s*value:\s*'([^']+)'\s*\]", src
    )
    assert match, (
        f"{pipeline.name} declares '{_OWED_VAR}' in a shape this test cannot read. "
        "It is checked because the trim's correctness depends on what that key is "
        "bound to, so if the idiom changed, this check has to follow it."
    )
    assert match.group(1) == _OWED_JSONPATH, (
        f"{pipeline.name} binds '{_OWED_VAR}' to '{match.group(1)}' rather than "
        f"'{_OWED_JSONPATH}'. The trim asks whether an earlier build already "
        "contains the commit this push landed on, and GitHub puts that in `after`. "
        "Bound to anything else the containment test still runs, still exits 0 or "
        "1, and answers about the wrong commit. See mc-k0z92."
    )
