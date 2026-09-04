"""Change detection diffs against the last build that RAN, not the last push (mc-okhtp).

THE INCIDENT THIS PINS, measured 2026-09-02 on MCDClient-Main and MCDServer-Main.

  1. Push ad2e8573a, the merge of PR #3045: custom.tscn, opponent_hand_fan.gd
     and .tscn. Real client changes. MCDClient-Main #1385 starts.
  2. Push d4f210a96. Relative to ad2e8573a it adds ONLY
     docs/plans/mc-5dcgd-deck-builder-overhaul.md. #1386 queues.
  3. The trim rule aborts the earlier build. #1385 ends ABORTED, having built
     nothing at all.
  4. #1386 runs with before_sha = ad2e8573a, so its diff is one docs file.
     'Change detection: server=false, client=false', every stage skipped,
     result NOT_BUILT. MCDServer-Main #1151 did the same.

The client changes in ad2e8573a were never built or tested by any Main
pipeline. main's tip was green BY ABSENCE, which is the part that makes this
worth a test: there was no red build to notice, because there was no build.

WHAT THIS FILE PINS.

1. Every branch pipeline takes its change-detection base from
   mcdChangeBase.resolve(), not from env.before_sha directly. A revert to the
   raw webhook variable is the regression, and it is a one-line one.

2. The helper counts a build as having evaluated a tree ONLY on SUCCESS,
   UNSTABLE or FAILURE. ABORTED is the case from the incident. NOT_BUILT is the
   subtler one: a NOT_BUILT build ran Detect Changes and looks trustworthy, but
   #1386 WAS a NOT_BUILT build sitting on a bad base, so anchoring to one
   launders the hole forward and makes it permanent rather than transient.

3. A candidate base is ancestor-checked before it is used. `git diff` between
   commits on unrelated histories exits 0 and prints a plausible file list, so a
   dangling base fails silently rather than reaching the caller's
   __DIFF_FAILED__ build-everything path.

4. A hand-started build still builds everything, and resolve() decides that
   BEFORE it consults build history. Pressing Build is how a person asks for the
   full thing, usually because they already suspect the incremental state is
   wrong. Deriving a base for them from build history would take that away.

5. Every pipeline keeps a build-everything branch for a null base, because that
   is the path resolve() routes to when it has nothing it trusts.

6. The premise the whole fix rests on, exercised against real git rather than
   asserted: with the incident's commit shape, diffing from the previous PUSH
   hides the client file, and diffing from the last build that reached a
   verdict surfaces it.

Run with: pytest test/unit/test_mcd_change_base_is_the_last_evaluated_build.py
No live Jenkins required. Tests parse Groovy source, plus one real git repo.

THIS FILE ONLY PINS THE SOURCE SHAPE. Every assertion here passes against Groovy
that does not compile and against a history walk that picks the wrong build. The
other half is test/groovy/mcd_change_base_behaviour.groovy, which compiles
vars/mcdChangeBase.groovy and calls resolve() for real; run it through
test/unit/test_mcd_change_base_executes.py, which needs a Groovy runtime and
skips loudly without one.
"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_HELPER = _VARS / "mcdChangeBase.groovy"

# The webhook-triggered branch pipelines: the ones that take a base from a push
# payload at all. mcdPRValidationPipeline is deliberately absent. It diffs
# against refs/remotes/origin/<targetBranch>, which is a branch tip rather than a
# build history, so it has no aborted-build hole to close.
_BRANCH_PIPELINES = [
    "mcdAppServicesPipeline.groovy",
    "mcdClientPipeline.groovy",
    "mcdDiscordBotPipeline.groovy",
    "mcdServerPipeline.groovy",
    "mcdServicesPipeline.groovy",
]

# Results that mean a build reached a verdict on a tree: it ran the stages and
# reported on them.
_EVALUATED = ["SUCCESS", "UNSTABLE", "FAILURE"]

# Results that mean it did not. ABORTED is the incident. NOT_BUILT is the trap.
_NOT_EVALUATED = ["ABORTED", "NOT_BUILT"]


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-okhtp."
        )
    return path.read_text()


def _uncommented(src: str) -> str:
    """Source with // comment tails removed.

    mcdChangeBase argues at length about why NOT_BUILT is excluded and names it
    repeatedly, so a raw substring check for the string reads the argument as
    the code. The sibling trim test keeps an identical helper for the same
    reason.
    """
    return "\n".join(re.sub(r"//.*$", "", line) for line in src.splitlines())


def _matched_block(src: str, start: int) -> str:
    """From the first '{' at or after `start`, the brace-matched block."""
    opened = src.index("{", start)
    depth = 0
    for index in range(opened, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[opened : index + 1]
    raise AssertionError("unterminated block")


def _method_body(src: str, signature: str) -> str:
    index = src.find(signature)
    assert index != -1, f"mcdChangeBase has no {signature}"
    return _matched_block(src, index)


@pytest.mark.parametrize("filename", _BRANCH_PIPELINES)
def test_pipeline_takes_its_base_from_the_last_evaluated_build(filename: str) -> None:
    """The one-line regression: back to the raw webhook variable."""
    src = _uncommented(_src(_VARS / filename))

    assert "mcdChangeBase.resolve(env.before_sha)" in src, (
        f"{filename} no longer resolves its change-detection base through "
        "mcdChangeBase.resolve(). Something has to walk back past builds that "
        "reached no verdict, or an aborted build's commits are attributed to a "
        "build that never evaluated them and the next push closes over them "
        "unbuilt. See mc-okhtp."
    )
    assert not re.search(r"def baseRef = env\.before_sha\b", src), (
        f"{filename} takes its change-detection base straight from "
        "env.before_sha, the tip of the PREVIOUS PUSH. That says nothing about "
        "whether any build looked at the range it opens. This is the exact "
        "defect measured on MCDClient-Main #1386 on 2026-09-02: PR #3045's "
        "client changes were never built by any Main pipeline, and main's tip "
        "was green by absence. See mc-okhtp."
    )


@pytest.mark.parametrize("filename", _BRANCH_PIPELINES)
def test_pipeline_still_builds_everything_on_a_null_base(filename: str) -> None:
    """resolve() returns null to mean 'build everything'. Somebody has to honour it."""
    src = _uncommented(_src(_VARS / filename))
    assert re.search(r"if \(!baseRef \|\| baseRef\.startsWith\('0000000'\)\)", src), (
        f"{filename} no longer has a build-everything branch for a missing "
        "base. mcdChangeBase.resolve() returns null whenever it has no base it "
        "trusts: a hand-started build, a lookback that ran out, a base that is "
        "not an ancestor of HEAD. Without this branch that null reaches "
        "mcdChangeDetection.detect() as a literal and the diff is meaningless. "
        "See mc-okhtp."
    )


@pytest.mark.parametrize("result", _EVALUATED)
def test_a_build_that_reached_a_verdict_counts(result: str) -> None:
    body = _uncommented(_method_body(_src(_HELPER), "String lastEvaluatedCommit()"))
    assert f"'{result}'" in body, (
        f"mcdChangeBase.lastEvaluatedCommit() no longer treats {result} as a "
        "build that evaluated a tree. All three of SUCCESS, UNSTABLE and "
        "FAILURE ran the stages and reported on the tree, so the commits up to "
        "and including theirs have been looked at. Dropping one of them widens "
        "every diff for no reason. See mc-okhtp."
    )


@pytest.mark.parametrize("result", _NOT_EVALUATED)
def test_a_build_that_reached_no_verdict_does_not_count(result: str) -> None:
    """The load-bearing exclusion, and the one a well-meaning edit will undo.

    ABORTED is the incident: #1385 was aborted mid-flight carrying PR #3045's
    client changes.

    NOT_BUILT is the trap. It looks like it evaluated a tree, because it ran
    Detect Changes and concluded honestly that nothing it owns changed. But
    #1386 was a NOT_BUILT build sitting on a base that had already skipped real
    client changes. Anchoring to it would carry the hole forward forever
    instead of letting the next build close it.
    """
    body = _uncommented(_method_body(_src(_HELPER), "String lastEvaluatedCommit()"))
    assert f"'{result}'" not in body, (
        f"mcdChangeBase.lastEvaluatedCommit() now accepts {result} as a build "
        "that evaluated a tree. It did not. An ABORTED build ran nothing, and a "
        "NOT_BUILT build may itself have been sitting on a base that skipped "
        "real changes, which is what MCDClient-Main #1386 was on 2026-09-02. "
        "Anchoring to either makes the hole permanent. See mc-okhtp."
    )


def test_the_commit_is_only_taken_together_with_a_result() -> None:
    """Reading BUILT_COMMIT without checking the result is the whole bug, restated."""
    body = _uncommented(_method_body(_src(_HELPER), "String lastEvaluatedCommit()"))
    assert "BUILT_COMMIT" in body, (
        "mcdChangeBase.lastEvaluatedCommit() no longer reads BUILT_COMMIT. That "
        "variable is the only record of what an earlier build checked out; "
        "mcdRedundantBuild.trim() writes it on every build, trimmed or not. See "
        "mc-okhtp."
    )
    assert re.search(r"\bevaluated\s*&&\s*theirs\b", body), (
        "mcdChangeBase.lastEvaluatedCommit() returns a build's BUILT_COMMIT "
        "without requiring that the build also reached a verdict. A commit from "
        "an aborted build is exactly the base that produced the incident. Keep "
        "the result check and the commit check on the same condition. See "
        "mc-okhtp."
    )


def test_a_candidate_base_is_ancestor_checked() -> None:
    """A dangling base does not error, it lies."""
    src = _uncommented(_src(_HELPER))
    assert "merge-base --is-ancestor" in src, (
        "mcdChangeBase no longer ancestor-checks a candidate base. `git diff` "
        "between two commits on unrelated histories exits 0 and prints a "
        "plausible file list, so a dangling or rewritten base never reaches "
        "mcdChangeDetection's __DIFF_FAILED__ build-everything path. It "
        "produces a confident wrong answer instead. See mc-okhtp."
    )


def test_a_hand_started_build_is_decided_before_history_is_read() -> None:
    """Manual runs build everything, and must not be given a computed base.

    Ordering is the assertion. If the history walk ran first and the missing
    before_sha check second, a hand-started build could be narrowed to a
    history-derived base, which takes away the one escape hatch a person has
    when they already suspect the incremental state is wrong.
    """
    body = _uncommented(_method_body(_src(_HELPER), "String resolve(String beforeSha)"))

    guard = body.find("!beforeSha")
    assert guard != -1, (
        "mcdChangeBase.resolve() no longer special-cases a missing before SHA. A "
        "hand-started build has no webhook payload, and it must build "
        "everything. See mc-okhtp."
    )

    walk = body.find("lastEvaluatedCommit()")
    assert walk != -1, "mcdChangeBase.resolve() no longer consults build history"
    assert guard < walk, (
        "mcdChangeBase.resolve() reads build history before it checks for a "
        "missing before SHA, so a hand-started build can be narrowed to a "
        "history-derived base. Pressing Build is how a person asks for the full "
        "thing. Check for the missing before SHA first and return null. See "
        "mc-okhtp."
    )

    # The guard must return null (build everything), not a commit.
    tail = body[guard : walk]
    assert "return null" in tail, (
        "mcdChangeBase.resolve()'s missing-before-SHA guard no longer returns "
        "null. null is what the callers turn into 'build everything'. See "
        "mc-okhtp."
    )


def test_the_base_choice_is_what_decides_whether_the_client_file_is_seen() -> None:
    """The incident's commit shape, in a real git repo.

    This does not test the Groovy, which this repo has no runtime for. It pins
    the premise the Groovy rests on, which is the thing that was actually in
    doubt: that the choice between 'previous push' and 'last build that reached
    a verdict' is what decides whether PR #3045's client changes show up at all.

    Shape, matching 2026-09-02:
        Z  last commit any build reached a verdict on
        A  client changes (custom.tscn, opponent_hand_fan.gd), build ABORTED
        B  docs only, the push whose build ran
    """
    import tempfile

    def git(repo: str, *args: str) -> str:
        return subprocess.run(
            ["git", *args],
            cwd=repo,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    with tempfile.TemporaryDirectory() as repo:
        git(repo, "init", "-q", "-b", "main")
        git(repo, "config", "user.email", "test@example.invalid")
        git(repo, "config", "user.name", "test")

        (Path(repo) / "README.md").write_text("z\n")
        git(repo, "add", "README.md")
        git(repo, "commit", "-q", "-m", "Z: the last evaluated tree")
        z = git(repo, "rev-parse", "HEAD")

        client = Path(repo) / "GameModes" / "Custom"
        client.mkdir(parents=True)
        (client / "custom.tscn").write_text("client change\n")
        git(repo, "add", "GameModes/Custom/custom.tscn")
        git(repo, "commit", "-q", "-m", "A: PR #3045 client changes (build ABORTED)")
        a = git(repo, "rev-parse", "HEAD")

        plans = Path(repo) / "docs" / "plans"
        plans.mkdir(parents=True)
        (plans / "mc-5dcgd-deck-builder-overhaul.md").write_text("docs\n")
        git(repo, "add", "docs/plans/mc-5dcgd-deck-builder-overhaul.md")
        git(repo, "commit", "-q", "-m", "B: docs only")

        from_previous_push = git(repo, "diff", "--name-only", a, "HEAD").split()
        from_last_evaluated = git(repo, "diff", "--name-only", z, "HEAD").split()

    assert from_previous_push == ["docs/plans/mc-5dcgd-deck-builder-overhaul.md"], (
        "The incident's shape no longer reproduces. Diffing from the previous "
        f"push should see the docs file only, got {from_previous_push}."
    )
    assert "GameModes/Custom/custom.tscn" not in from_previous_push, (
        "Diffing from the previous push must NOT see the client file. That "
        "invisibility is the bug: MCDClient-Main #1386 reported client=false "
        "and skipped every stage. See mc-okhtp."
    )
    assert "GameModes/Custom/custom.tscn" in from_last_evaluated, (
        "Diffing from the last build that reached a verdict must see the client "
        "file. If it does not, walking back past aborted builds buys nothing "
        "and mcdChangeBase is solving the wrong problem. See mc-okhtp."
    )
