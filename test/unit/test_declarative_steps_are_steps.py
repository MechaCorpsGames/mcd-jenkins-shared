"""Guard: no bare Groovy control flow inside a Declarative steps-block.

Jenkins Declarative Pipeline only accepts *steps* inside `steps { }` and inside
the closure body of a step such as `catchError { }` / `timeout { }` / `dir { }`.
A bare `try {` there is a compile error — "Expected a step @ line N" — which
fails the whole job at load time, before a single stage runs.

This is not hypothetical: mcdServerPipeline.groovy shipped exactly that in
PR #82 (bead mjs-q4x) and took MCDServer-FeatureBackend #297 down with a
CpsCompilationErrorsException. The other tests in this directory all passed,
because they assert on the *text* of the pipeline and never on its structure.

The fix in every case is to wrap the Groovy in a `script { }` block.
"""
import re
from pathlib import Path

VARS = Path(__file__).resolve().parents[2] / "vars"

# Steps whose closure body is itself a steps-context (Groovy is illegal inside
# them too, for the same reason). Not exhaustive — these are the ones used here.
STEP_BLOCKS = ("catchError", "timeout", "dir", "withEnv", "retry",
               "withCredentials", "warnError", "lock", "ws")

BARE_GROOVY = re.compile(r"^\s*(try)\s*\{")


def _strip_noise(src: str) -> str:
    """Blank out comments and string bodies, preserving line structure."""
    out, i, n = [], 0, len(src)
    while i < n:
        two = src[i:i + 2]
        three = src[i:i + 3]
        if two == "//":
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif two == "/*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(c if c == "\n" else " " for c in src[i:j]))
            i = j
        elif three in ("'''", '"""'):
            j = src.find(three, i + 3)
            j = n if j < 0 else j + 3
            out.append("".join(c if c == "\n" else " " for c in src[i:j]))
            i = j
        elif src[i] in "'\"":
            q, j = src[i], i + 1
            while j < n and src[j] != q:
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(" " * (j - i))
            i = j
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


def _violations(path: Path):
    """Yield (line_no, text) for bare Groovy in a steps-context."""
    clean = _strip_noise(path.read_text(encoding="utf-8"))
    # Stack of booleans: is this brace level a steps-context?
    stack, bad = [], []
    for lineno, line in enumerate(clean.split("\n"), 1):
        opens_steps = re.search(r"\bsteps\s*\{", line) is not None
        opens_script = re.search(r"\bscript\s*\{", line) is not None
        opens_stepblock = any(
            re.search(rf"\b{s}\s*\(.*\)\s*\{{|\b{s}\s*\{{", line)
            for s in STEP_BLOCKS
        )
        in_steps_ctx = bool(stack) and stack[-1]

        if in_steps_ctx and BARE_GROOVY.match(line):
            bad.append((lineno, line.strip()))

        for idx, ch in enumerate(line):
            if ch == "{":
                if opens_script:
                    stack.append(False)          # script{} = Groovy is legal
                elif opens_steps or (in_steps_ctx and opens_stepblock):
                    stack.append(True)           # still a steps-context
                else:
                    stack.append(in_steps_ctx if opens_stepblock else False)
            elif ch == "}" and stack:
                stack.pop()
    return bad


def test_no_bare_try_in_declarative_steps():
    offenders = {}
    for f in sorted(VARS.glob("*.groovy")):
        v = _violations(f)
        if v:
            offenders[f.name] = v
    assert not offenders, (
        "Bare Groovy `try {` inside a Declarative steps-block — this is a "
        "compile error that fails the job at load time. Wrap it in `script { }`:\n"
        + "\n".join(
            f"  {name}:{ln}: {txt}"
            for name, hits in offenders.items()
            for ln, txt in hits
        )
    )
