"""Tests that the webhook trigger cannot build an environment that breaks exec (mc-4qz7).

MCDClient-FeatureBackend #528 and MCDClient-FeatureCard #95 both died in under
five seconds, each minutes after a cascade merge, with:

    > git init /var/lib/jenkins/workspace/MCDClient-FeatureBackend@libs/13ccd25...
    Cannot run program "git": error=7, Argument list too long
    ERROR: Error cloning remote repo 'origin'
    WorkflowScript: Loading libraries failed

`git init <path>` has one argument, so the argument list was never the problem.
error=7 is E2BIG, and the kernel charges envp as well as argv. The environment
was what had grown, and the build died on the first exec it attempted, which is
the clone of THIS repository. That is why the failure happens before any
Jenkinsfile runs and why the stage list is empty.

What grew it: mcdClientPipeline and mcdServerPipeline declared

    [key: 'files_added',    value: '$.commits[*].added[*]'],
    [key: 'files_modified', value: '$.commits[*].modified[*]'],
    [key: 'files_removed',  value: '$.commits[*].removed[*]']

Generic Webhook Trigger resolves a JSONPath that selects a list into BOTH one
indexed variable per element (files_added_0 ... files_added_N) AND an aggregate
variable holding the entire list as a single string, then injects all of them.
The indexed ones are individually small and harmless. The aggregate is the
problem, because GitHub reports each commit's file lists against its first
parent, so every merge commit in a cascade re-lists everything the merged
branch brought in. Measured on the two pushes that broke, by replaying the
exact commit ranges:

    push                         commits   files_added   aggregate bytes
    PR #2550 -> features/backend     371          3514           171,999
    PR #2552 -> features/card        446          4443           221,028

Both aggregates exceed 131,072.

131,072 is the number that matters, and it is NOT ARG_MAX. It is
MAX_ARG_STRLEN, the kernel's cap on a SINGLE argv/envp string (32 pages). The
totals above are only ~27% and ~35% of a typical 2 MiB ARG_MAX, so the
environment as a whole was nowhere near any aggregate limit, and raising
ARG_MAX or trimming other variables would have fixed nothing. One oversized
string is sufficient and was what happened.
test_a_single_oversized_env_string_is_what_breaks_exec proves that on the host
running the suite rather than asserting it from documentation.

The fix filters on $ref alone, which is what mcdAppServicesPipeline,
mcdServicesPipeline and mcdDiscordBotPipeline already did. Path filtering keeps
happening in the 'Detect Changes' stage, which reads the diff from git instead
of from the webhook body. That gate was always the one that decided whether a
build did work: the webhook filter was ANDed with it, so removing the webhook
filter cannot skip a build that used to run. It can only let a build start and
immediately mark itself NOT_BUILT.

The load-bearing test is
test_no_generic_variable_flattens_an_unbounded_array: it covers every pipeline
in vars/, not just the two that broke, so the next pipeline to reach for a file
list out of the webhook body fails here instead of on a cascade Friday.

Run with: pytest test/unit/test_webhook_env_stays_execable.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_CLIENT_SRC = _VARS / "mcdClientPipeline.groovy"
_SERVER_SRC = _VARS / "mcdServerPipeline.groovy"

# The kernel's cap on one argv/envp string: 32 * PAGE_SIZE on Linux.
_MAX_ARG_STRLEN = 131072


def _pipelines() -> list[Path]:
    if not _VARS.is_dir():
        pytest.fail(
            f"{_VARS} not found. This test must run from the mcd-jenkins-shared "
            "repo root. See mc-4qz7."
        )
    return sorted(_VARS.glob("*.groovy"))


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(f"{path} not found. See mc-4qz7.")
    return path.read_text()


def _generic_variables_block(src: str) -> str | None:
    """Return the text of the genericVariables: [...] list, bracket-matched.

    Bracket-matched rather than regexed because each entry is itself a
    bracketed map, and because the surrounding comments deliberately quote the
    removed JSONPaths. A looser scan would read those comments as config.
    """
    marker = "genericVariables:"
    start = src.find(marker)
    if start == -1:
        return None
    open_bracket = src.find("[", start)
    assert open_bracket != -1, "genericVariables: with no list. See mc-4qz7."
    depth = 0
    for index in range(open_bracket, len(src)):
        if src[index] == "[":
            depth += 1
        elif src[index] == "]":
            depth -= 1
            if depth == 0:
                return src[open_bracket:index + 1]
    pytest.fail("unterminated genericVariables list. See mc-4qz7.")


def _declared_variables(src: str) -> list[tuple[str, str]]:
    """Return [(key, value-expression)] for one file's genericVariables."""
    block = _generic_variables_block(src)
    if block is None:
        return []
    return re.findall(
        r"\[\s*key\s*:\s*'([^']*)'\s*,\s*value\s*:\s*'([^']*)'", block
    )


def _trigger_field(src: str, field: str) -> str | None:
    match = re.search(rf"{field}\s*:\s*(['\"])(.*?)\1", src, re.DOTALL)
    return match.group(2) if match else None


# --------------------------------------------------------------------------
# The guard that generalises: no pipeline may put an unbounded list in the env.
# --------------------------------------------------------------------------

def test_no_generic_variable_flattens_an_unbounded_array():
    """No webhook variable may resolve to a list of unbounded length.

    Generic Webhook Trigger injects the whole resolved list as one env string
    in addition to the indexed elements. A single env string over
    MAX_ARG_STRLEN makes every subsequent exec in the build fail with E2BIG,
    including the clone of this library, so the build dies before any pipeline
    code runs and the console shows no stage at all.

    '[*]' is the marker for that shape: it is JSONPath for "every element",
    and nothing in a push body that is worth reading has a bounded number of
    elements. Read a scalar, or compute it from git inside the pipeline where
    a long list is just a long list.
    """
    offenders = []
    for path in _pipelines():
        for key, value in _declared_variables(_src(path)):
            if "[*]" in value:
                offenders.append(f"{path.name}: {key} = {value}")
    assert not offenders, (
        "genericVariables resolving to unbounded lists:\n  "
        + "\n  ".join(offenders)
        + "\n\nThe resolved list is injected as a single env string. Over "
          f"{_MAX_ARG_STRLEN:,} bytes every exec in the build fails with "
          "E2BIG and the build dies while cloning this library. Filter on "
          "$ref and use mcdChangeDetection for paths. See mc-4qz7."
    )


def test_no_pipeline_reads_per_file_lists_out_of_the_webhook_body():
    """Belt and braces for the same rule, stated by variable name.

    A JSONPath without '[*]' can still select a list (for example
    '$.commits..added'). This catches the intent rather than the syntax.
    """
    offenders = []
    for path in _pipelines():
        for key, value in _declared_variables(_src(path)):
            if key in {"files_added", "files_modified", "files_removed"}:
                offenders.append(f"{path.name}: {key} = {value}")
    assert not offenders, (
        "per-file webhook variables are back:\n  "
        + "\n  ".join(offenders)
        + "\n\nSee mc-4qz7 and test_no_generic_variable_flattens_an_unbounded_array."
    )


# --------------------------------------------------------------------------
# The two pipelines that broke.
# --------------------------------------------------------------------------

@pytest.mark.parametrize("path", [_CLIENT_SRC, _SERVER_SRC], ids=["client", "server"])
def test_trigger_filters_on_ref_only(path: Path):
    """The regexp filter reads $ref and nothing else.

    Anything else in regexpFilterText has to be a genericVariable, and a
    genericVariable big enough to be worth filtering on is big enough to break
    exec.
    """
    src = _src(path)
    text = _trigger_field(src, "regexpFilterText")
    assert text is not None, f"{path.name} has no regexpFilterText. See mc-4qz7."
    assert text.strip() == "$ref", (
        f"{path.name} filters on {text!r}, not '$ref'. Path filtering belongs "
        "in the 'Detect Changes' stage, which reads git rather than the "
        "webhook body. See mc-4qz7."
    )


@pytest.mark.parametrize("path", [_CLIENT_SRC, _SERVER_SRC], ids=["client", "server"])
def test_before_sha_is_still_declared(path: Path):
    """'Detect Changes' reads env.before_sha, so the trigger must still set it.

    Deleting it alongside the files_* variables would not fail any build. It
    would make baseRef null on every webhook build, which takes the
    "No valid before SHA" branch and silently rebuilds everything forever. A
    green pipeline that stopped detecting changes is the quiet version of this
    bug, so it gets its own test.
    """
    src = _src(path)
    keys = [key for key, _ in _declared_variables(src)]
    assert "before_sha" in keys, (
        f"{path.name} no longer declares before_sha, but its 'Detect Changes' "
        "stage reads env.before_sha and falls back to building everything when "
        "it is missing. See mc-4qz7."
    )
    assert "env.before_sha" in src, (
        f"{path.name} declares before_sha but no longer reads it. See mc-4qz7."
    )


@pytest.mark.parametrize(
    "path,flag",
    [(_CLIENT_SRC, "CLIENT_CHANGED"), (_SERVER_SRC, "SERVER_CHANGED")],
    ids=["client", "server"],
)
def test_change_detection_still_gates_the_build(path: Path, flag: str):
    """The gate that replaced the webhook path filter is actually present.

    This is the whole safety argument for dropping the filter: the trigger was
    ANDed with this flag, so the set of builds that do real work is unchanged.
    If this stage or its gating ever goes away, dropping the webhook filter
    stops being free and every push to the branch runs a full build.
    """
    src = _src(path)
    assert "stage('Detect Changes')" in src, (
        f"{path.name} lost its 'Detect Changes' stage. See mc-4qz7."
    )
    assert "mcdChangeDetection.detect(" in src, (
        f"{path.name} no longer calls mcdChangeDetection. See mc-4qz7."
    )
    assert src.count(f"env.{flag} == 'true'") >= 5, (
        f"{path.name} gates fewer than five stages on {flag}. The webhook path "
        "filter was removed on the strength of this gate. See mc-4qz7."
    )


def test_the_library_agrees_on_one_trigger_shape():
    """Every push-triggered pipeline filters the same way.

    mcdClientPipeline and mcdServerPipeline were the only two that did anything
    else, and they were the only two that broke.
    """
    disagreeing = []
    for path in _pipelines():
        src = _src(path)
        text = _trigger_field(src, "regexpFilterText")
        if text is None or "$ref" not in text:
            continue  # PR-triggered or untriggered pipelines filter on other fields
        if text.strip() != "$ref":
            disagreeing.append(f"{path.name}: {text!r}")
    assert not disagreeing, (
        "push-triggered pipelines that do not filter on $ref alone:\n  "
        + "\n  ".join(disagreeing)
        + "\n\nSee mc-4qz7."
    )


# --------------------------------------------------------------------------
# The mechanism itself, proven on this host rather than cited.
# --------------------------------------------------------------------------

@pytest.mark.skipif(sys.platform != "linux", reason="MAX_ARG_STRLEN is a Linux limit")
def test_a_single_oversized_env_string_is_what_breaks_exec():
    """One env string over MAX_ARG_STRLEN fails exec; a large total does not.

    This is the test that stops the wrong fix. The obvious readings of
    "Argument list too long" are "the command line is too long" and "the
    environment is too big in total", and both are wrong here: the command was
    `git init <path>`, and the environment totalled ~545 KiB against a 2 MiB
    ARG_MAX. Only the per-string cap was exceeded, by exactly one variable.

    Both halves are asserted, because the second is what rules out capping the
    total or pruning unrelated variables as a fix.
    """
    def exec_with(env_extra: dict[str, str]) -> int | None:
        """Return None if exec succeeded, else the OSError errno."""
        env = {k: v for k, v in os.environ.items() if len(k) + len(v) < 4096}
        env.update(env_extra)
        try:
            subprocess.run(
                [sys.executable, "-c", ""],
                env=env,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                check=True,
            )
            return None
        except OSError as exc:
            return exc.errno

    e2big = 7

    assert exec_with({"MCD_PROBE": "x" * (_MAX_ARG_STRLEN - 4096)}) is None, (
        "a single env string just under MAX_ARG_STRLEN should exec fine"
    )

    assert exec_with({"MCD_PROBE": "x" * _MAX_ARG_STRLEN}) == e2big, (
        f"a single env string of {_MAX_ARG_STRLEN:,} bytes should fail exec "
        "with E2BIG. If this stopped being true the kernel limit moved, and "
        "the numbers in this file need re-measuring. See mc-4qz7."
    )

    # The measured aggregate from MCDClient-FeatureCard #95.
    assert exec_with({"MCD_PROBE": "x" * 221028}) == e2big, (
        "the files_added aggregate measured on the features/card cascade "
        "should fail exec. See mc-4qz7."
    )

    # Same total bytes, spread over many strings, each under the cap: fine.
    # This is why raising ARG_MAX or trimming other variables was never the fix.
    spread = {f"MCD_PROBE_{i}": "x" * 50_000 for i in range(20)}
    assert sum(len(k) + len(v) for k, v in spread.items()) > 4 * _MAX_ARG_STRLEN
    assert exec_with(spread) is None, (
        "a large environment made of individually small strings should exec "
        "fine. The failure is per-string, not aggregate. See mc-4qz7."
    )
