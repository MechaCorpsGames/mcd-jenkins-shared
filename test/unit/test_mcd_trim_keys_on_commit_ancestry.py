"""The trim decides on COMMIT CONTAINMENT, not on equality of what was checked out (mc-k0z92).

MCDClient-Main #1321, 2026-08-29: queued for 434540193, it stood itself down
against #1320, which had built 16c3218. Checked afterwards with
`git merge-base --is-ancestor`, not one of the five pull requests merged after
16c3218 was contained in it, so for roughly half an hour there was no client
build carrying that day's later work, including a P0 fix that had been filed
twice.

The mechanism, read off vars/mcdRedundantBuild.groovy as it was then: a build
recorded `git rev-parse HEAD` as BUILT_COMMIT and trimmed when an earlier
successful build's BUILT_COMMIT was `==` to its own. Both sides of that
comparison are the CHECKED OUT commit. A build is accountable for a different
commit, the one its webhook carried, and the two coincide only while every
queued build resolves the same tip. When they do not, two builds accountable for
different commits check out the same commit, the `==` fires, and the newer
build's work is dropped by a build that never contained it.

So the question has to be containment of the OWED commit:

    git merge-base --is-ancestor <owed> <built>

WHAT THIS FILE PINS.

1. The behaviour, executed rather than asserted about. The exact command text is
   lifted out of the Groovy and run against a real two-commit repository, so
   these cases are decided by git, not by a Python restatement of git.

2. The ARGUMENT ORDER, which is the failure mode this fix could most easily be
   broken by later. `--is-ancestor` is not symmetric. Swapped, every build stands
   down against its own ancestors and nothing after the first commit of the day
   is ever built: a total, silent outage of the job.

3. The whole chain from the webhook variable to that first git argument. The
   original defect was not a wrong comparison, it was comparing the wrong
   COMMIT, and every link between `env.commit_sha` and the argument is a place
   the same defect could come back.

4. Fail open. Only a clean exit 0 may skip work.

Run with: pytest test/unit/test_mcd_trim_keys_on_commit_ancestry.py
No live Jenkins required. Needs git on PATH.
"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_HELPER = _REPO_ROOT / "vars" / "mcdRedundantBuild.groovy"

# The webhook variable every branch pipeline publishes from GenericTrigger's
# $.after. This is the commit a build is accountable for.
_QUEUED_COMMIT_VAR = "commit_sha"


def _src() -> str:
    if not _HELPER.exists():
        pytest.fail(
            f"{_HELPER} not found. "
            "This test must run from the mcd-jenkins-shared repo root. See mc-k0z92."
        )
    return _HELPER.read_text()


def _uncommented(src: str) -> str:
    """Source with // comment tails removed.

    This file's own explanation names the swapped-argument mistake it exists to
    prevent, and mcdRedundantBuild's header quotes the git command in prose. A
    raw substring search reads the warning as the code, exactly as it did in the
    sibling trim test.
    """
    return "\n".join(re.sub(r"//.*$", "", line) for line in src.splitlines())


def _covers_signature() -> tuple[str, str]:
    """The two parameter names of `covers`, in declaration order.

    Read out of the source rather than hardcoded so the test pins the ROLES
    (owed first, built second) and survives a rename.
    """
    src = _uncommented(_src())
    match = re.search(
        r"boolean\s+covers\s*\(\s*String\s+(\w+)\s*,\s*String\s+(\w+)\s*\)", src
    )
    assert match, (
        "mcdRedundantBuild has no `boolean covers(String, String)`. That helper "
        "is where the containment test lives; without it the trim is back to "
        "comparing checked-out commits for equality. See mc-k0z92."
    )
    return match.group(1), match.group(2)


def _is_ancestor_operands() -> tuple[str, str]:
    """The two Groovy variables interpolated into `git merge-base --is-ancestor`."""
    src = _uncommented(_src())
    match = re.search(
        r"git\s+merge-base\s+--is-ancestor\s+\$\{(\w+)\}\s+\$\{(\w+)\}", src
    )
    assert match, (
        "mcdRedundantBuild does not run `git merge-base --is-ancestor ${a} ${b}`. "
        "That single command IS the fix for mc-k0z92: a build may only be trimmed "
        "when the commit it was queued for is contained in a commit an earlier "
        "build actually built."
    )
    return match.group(1), match.group(2)


# --------------------------------------------------------------------------
# 1 + 2. The behaviour, decided by git, using the command text from the source.
# --------------------------------------------------------------------------


@pytest.fixture(scope="module")
def repo(tmp_path_factory) -> tuple[Path, str, str]:
    """A real repository with commit B a descendant of commit A."""
    path = tmp_path_factory.mktemp("trim-ancestry")

    def git(*args: str) -> str:
        return subprocess.run(
            ["git", *args],
            cwd=path,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    git("init", "-q", "-b", "main")
    git("config", "user.email", "test@example.invalid")
    git("config", "user.name", "trim ancestry test")

    (path / "a.txt").write_text("a\n")
    git("add", "a.txt")
    git("commit", "-q", "-m", "A")
    commit_a = git("rev-parse", "HEAD")

    (path / "b.txt").write_text("b\n")
    git("add", "b.txt")
    git("commit", "-q", "-m", "B")
    commit_b = git("rev-parse", "HEAD")

    assert commit_a != commit_b
    return path, commit_a, commit_b


def _covers(repo_path: Path, owed: str, built: str) -> bool:
    """Run the pipeline's OWN command and return its trim decision.

    The command text and the operand order both come from the Groovy source, so
    editing either of them in `vars/` changes what this executes.
    """
    first, second = _is_ancestor_operands()
    owed_var, built_var = _covers_signature()
    bindings = {owed_var: owed, built_var: built}

    command = [
        "git",
        "merge-base",
        "--is-ancestor",
        bindings[first],
        bindings[second],
    ]
    completed = subprocess.run(command, cwd=repo_path, capture_output=True, text=True)
    # mcdRedundantBuild: `return status == 0`.
    return completed.returncode == 0


def test_a_descendant_commit_is_not_trimmed_against_its_ancestor(repo) -> None:
    """THE BUG. This is #1321 against #1320, reduced to two commits.

    Builds queued for A then B, B a descendant of A, the A build already
    finished. B carries work that is not in A. It must be built.
    """
    path, commit_a, commit_b = repo
    assert not _covers(path, owed=commit_b, built=commit_a), (
        "a build queued for a DESCENDANT commit is being trimmed against a build "
        "that only built its ancestor. That is mc-k0z92: everything merged "
        "between the two commits goes unbuilt, and the job self-corrects only if "
        "somebody happens to push again. Check the operand order of "
        "`git merge-base --is-ancestor`: the commit this build OWES goes first."
    )


def test_the_same_commit_is_still_trimmed(repo) -> None:
    """The case the trim is FOR. Two builds queued for one commit; the second trims."""
    path, commit_a, _ = repo
    assert _covers(path, owed=commit_a, built=commit_a), (
        "two builds queued for the SAME commit no longer collapse, so the trim "
        "does nothing and a burst of pushes is back to repeating 20 minutes of "
        "identical work per push. See mc-waxw."
    )


def test_an_ancestor_commit_is_trimmed_against_a_later_build(repo) -> None:
    """The trim gets STRONGER, not just safer.

    A build queued for A whose turn comes after another build already built B is
    genuinely redundant: A's tree is inside B's. Equality of checked-out commits
    could not see this; containment can.
    """
    path, commit_a, commit_b = repo
    assert _covers(path, owed=commit_a, built=commit_b), (
        "a build queued for a commit already CONTAINED in a later successful "
        "build is being rebuilt. That work is provably done. See mc-k0z92."
    )


def test_an_unknown_commit_fails_open(repo) -> None:
    """The workspace that does not contain the commit it owes must BUILD.

    git exits 128 rather than 1 here, and that is not a corner case to tolerate:
    a build whose checkout does not contain its own webhook commit is exactly the
    situation that produced mc-k0z92.
    """
    path, _, commit_b = repo
    absent = "0" * 40
    assert not _covers(path, owed=absent, built=commit_b), (
        "an unresolvable commit is being treated as already built. Only a clean "
        "exit 0 from git may skip work; 1 and 128 both mean build it."
    )


# --------------------------------------------------------------------------
# 3. The chain from the webhook variable to that first git argument.
# --------------------------------------------------------------------------


def test_the_trim_reads_the_commit_the_build_was_queued_for() -> None:
    """The original defect, stated directly.

    The pre-fix helper never read a webhook variable at all. Both sides of its
    comparison were `git rev-parse HEAD`, so it could not tell a build
    accountable for a new commit apart from one accountable for an old one.
    """
    src = _uncommented(_src())
    assert f"env.{_QUEUED_COMMIT_VAR}" in src, (
        f"mcdRedundantBuild never reads env.{_QUEUED_COMMIT_VAR}, so it is deciding "
        "entirely from what the workspace happens to have checked out. All five "
        "branch pipelines publish that variable from GenericTrigger's $.after, "
        "and it is the only thing that says what this build is accountable for. "
        "See mc-k0z92."
    )


def test_the_queued_commit_is_what_gets_looked_up() -> None:
    """`builtBy` must be asked about the OWED commit, not the checked-out one.

    Reading env.commit_sha and then passing HEAD to the lookup anyway would leave
    the bug in place with a variable to make it look fixed.
    """
    src = _uncommented(_src())
    owed = re.search(rf"(\w+)\s*=\s*\(\s*env\.{_QUEUED_COMMIT_VAR}\b", src)
    assert owed, (
        f"cannot find the local that env.{_QUEUED_COMMIT_VAR} is assigned to. If the "
        "idiom changed, this check has to follow it: it is what proves the queued "
        "commit reaches the lookup."
    )
    name = owed.group(1)
    assert re.search(rf"\bbuiltBy\(\s*{re.escape(name)}\s*\)", src), (
        f"env.{_QUEUED_COMMIT_VAR} is read into `{name}` but `{name}` is not what "
        "builtBy() is asked about. The lookup has to be keyed on the commit this "
        "build owes; keying it on HEAD again reinstates mc-k0z92 while looking "
        "like it was fixed."
    )


def test_the_owed_commit_is_the_first_argument_to_is_ancestor() -> None:
    """The asymmetry. Swapping these two is a silent, total outage.

    `git merge-base --is-ancestor A B` asks whether A is an ancestor of B. With
    the operands swapped every build would find its own ancestors "containing"
    it, trim against the previous build, and the job would stop producing
    artifacts entirely while every build reported a tidy NOT_BUILT.
    """
    src = _uncommented(_src())
    owed_param, built_param = _covers_signature()
    first, second = _is_ancestor_operands()

    assert (first, second) == (owed_param, built_param), (
        f"`git merge-base --is-ancestor ${{{first}}} ${{{second}}}` has its operands "
        f"the wrong way round: covers({owed_param}, {built_param}) takes the owed "
        f"commit first, so the command must be `--is-ancestor ${{{owed_param}}} "
        f"${{{built_param}}}`. As written, a build trims against its own ancestor "
        "and the job silently stops building anything. See mc-k0z92."
    )

    assert re.search(rf"\bcovers\(\s*commitSha\s*,\s*\w+\s*\)", src), (
        "builtBy() no longer passes its own lookup commit as the FIRST argument "
        "to covers(). That argument is the commit this build owes."
    )


def test_the_earlier_builds_recorded_commit_is_what_it_checked_out() -> None:
    """BUILT_COMMIT stays HEAD, and that is deliberate.

    The two commits play different roles and the fix depends on keeping them
    apart. A later build asks "did that build's tree contain my commit?", and
    only the commit that build actually CHECKED OUT can answer it. Recording the
    webhook commit here instead would compare two accountability claims and prove
    nothing about what was built.
    """
    src = _uncommented(_src())
    assert re.search(r"env\.BUILT_COMMIT\s*=\s*head\b", src), (
        "BUILT_COMMIT is no longer the commit this build checked out. It is read "
        "by LATER builds as the tree that was actually built and published; if it "
        "becomes the webhook commit, the containment test compares two claims "
        "about what should have been built rather than one fact about what was. "
        "See mc-k0z92."
    )


# --------------------------------------------------------------------------
# 4. Fail open, and do not hand a webhook string to a shell unchecked.
# --------------------------------------------------------------------------


def test_only_a_clean_exit_zero_trims() -> None:
    src = _uncommented(_src())
    assert re.search(r"\bstatus\s*==\s*0\b", src), (
        "the containment test no longer requires git to exit 0. git exits 1 for "
        "'not an ancestor' and 128 for an object it does not have, and both of "
        "those mean BUILD IT. Anything looser skips work on an error."
    )
    assert "returnStatus: true" in src, (
        "the git call no longer uses returnStatus, so a non-zero exit aborts the "
        "build instead of answering the question. 'Not an ancestor' is the normal "
        "answer, not a failure."
    )


def test_the_shas_are_validated_before_reaching_a_shell() -> None:
    """One operand comes off a webhook payload and is interpolated into `sh`."""
    src = _uncommented(_src())
    assert re.search(r"\[0-9a-fA-F\]\{7,40\}", src), (
        "mcdRedundantBuild interpolates two externally supplied strings into a "
        "shell command without checking they are bare hex shas. One of them is "
        f"lifted straight off a webhook payload (env.{_QUEUED_COMMIT_VAR}). Refusing "
        "anything else fails open into a normal build, which is the safe outcome."
    )
