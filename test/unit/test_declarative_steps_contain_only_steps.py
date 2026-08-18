"""Declarative `steps {}` blocks must contain steps, not bare Groovy (mc-lg8x).

A `try {` written directly inside `steps {}` does not fail at runtime and does
not fail any text-matching test — it fails to COMPILE the entire pipeline:

    mcdServerPipeline.groovy: 210: Expected a step @ line 210, column 25.
                               try {

That is what took MCDServer-Main and MCDServer-FeatureBackend down completely
from the moment #82 merged. Every job using the library died at load time,
before a single stage ran, and the whole 134-test suite stayed green the entire
time because nothing here compiles Groovy.

Plain Groovy parsing would not catch it either: `try {` inside a closure is
valid Groovy. The rule is imposed by Jenkins' declarative plugin, so the check
has to be structural, which is what this module does.

The fix is always the same: wrap the control flow in `script { }`.

Run with: pytest test/unit/test_declarative_steps_contain_only_steps.py
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

# Groovy keywords that are NOT steps and therefore may not sit directly inside
# `steps {}`. `def` is included: a bare declaration is the same class of error.
_CONTROL_FLOW = re.compile(r"^(try|catch|finally|if|else|for|while|switch|def)\b")


def _strip_shell_bodies(src: str) -> list[str]:
    """Blank out triple-quoted shell heredocs.

    Shell inside a triple-quoted block (either quote style) is full of `if`/`for`,
    none of which is Groovy. Without this the check is pure false positives.
    """
    out, fence = [], None
    for line in src.splitlines():
        if fence is not None:
            out.append("")
            if line.count(fence) % 2 == 1:
                fence = None
            continue
        for candidate in ('"""', "\'\'\'"):
            if line.count(candidate) % 2 == 1:
                fence = candidate
                break
        if fence is not None:
            out.append("")
            continue
        out.append(line)
    return out


def _violations(src: str) -> list[tuple[int, str]]:
    """Control flow whose nearest enclosing block is `steps`, not `script`."""
    lines = _strip_shell_bodies(src)
    stack: list[str] = []
    found: list[tuple[int, str]] = []
    for n, raw in enumerate(lines, 1):
        stripped = raw.strip()
        if not stripped or stripped.startswith("//"):
            continue
        # Illegal when a `steps` block is open and no `script` has been
        # entered since. Wrapper steps (catchError, dir, withEnv, timeout)
        # sit between the two and do NOT make control flow legal — that is
        # exactly the shape mc-lg8x shipped: catchError { try { ... } }.
        if _CONTROL_FLOW.match(stripped) and "steps" in stack:
            last_steps = len(stack) - 1 - stack[::-1].index("steps")
            if "script" not in stack[last_steps:]:
                found.append((n, stripped[:60]))
        opens = raw.count("{")
        closes = raw.count("}")
        for _ in range(opens):
            if re.match(r"^steps\s*\{", stripped):
                stack.append("steps")
            elif re.match(r"^script\s*\{", stripped):
                stack.append("script")
            else:
                stack.append("other")
        for _ in range(closes):
            if stack:
                stack.pop()
    return found


_PIPELINES = sorted(_VARS.glob("*Pipeline.groovy"))


@pytest.mark.parametrize("path", _PIPELINES, ids=lambda p: p.name)
def test_no_bare_control_flow_inside_declarative_steps(path: Path) -> None:
    found = _violations(path.read_text())
    assert not found, (
        f"{path.name} has Groovy control flow directly inside a declarative "
        f"steps{{}} block: {found}. Jenkins rejects this at COMPILE time with "
        '"Expected a step", which kills every job using this library before a '
        "single stage runs — see mc-lg8x. Wrap it in script { }."
    )
