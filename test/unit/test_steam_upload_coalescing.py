"""One Steam upload per branch, fired last, newest wins (mc-fr2h).

On 2026-08-23 MCDSteam-Upload #593, #594, #595, #596 and #597 all ran from
MCDClient-Main, all with STEAM_BRANCH=main, inside about two hours. Every one
of them sets a build live on the same beta, so only the last had any effect.
The other four uploaded a build the next one immediately superseded.

That is not just wasted work. The controller has four executors and no agents
(bead mc-sm6s), and the queue reached eleven items with nothing idle while
those uploads ran. Redundant uploads compete for executors with PR validation,
which is the work somebody is actually waiting on.

Two properties fix it, and this file pins both.

1. ONE UPLOAD PER BRANCH, NEWEST WINS. An upload carrying MCDClient-Main #1200
   is pointless once #1202 has archived artifacts, because #1202's pipeline
   fires its own upload to the same Steam branch. mcdSteamUploadPipeline
   detects that and skips, so three uploads queued in quick succession end with
   exactly one that ran, carrying the newest source build.

2. THE TRIGGER RUNS LAST. 'Publish to Steam' used to sit ahead of 'Upload Debug
   Symbols', handing the controller a second job to run while the client build
   still had minutes of its own work left.

WHY THE CHECKS BELOW LOOK LIKE THIS. The obvious Jenkins idiom for "newest
wins" is milestone() + lock(), and it is wrong here, which is why the tests
forbid rather than require it. MCDSteam-Upload is ONE job serving four Steam
branches. milestone() is scoped to the job and ordered by build number, with no
notion of a parameter, so a main upload passing the milestone would abort a
queued backend upload that nothing has superseded. Job-wide
disableConcurrentBuilds() has the same defect. A test that merely asserted a
lock exists would pass on exactly that broken arrangement, so what is pinned
instead is the property those mechanisms get wrong: the coalescing decision
must be derived from the SOURCE of the upload, never from a job-wide constant.

Run with: pytest test/unit/test_steam_upload_coalescing.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_CLIENT_SRC = _VARS / "mcdClientPipeline.groovy"
_UPLOAD_SRC = _VARS / "mcdSteamUploadPipeline.groovy"
_HELPER_SRC = _VARS / "mcdSteamSourceBuild.groovy"

_STAGE_DECL = re.compile(r"^(?P<indent> *)stage\('(?P<name>[^']+)'\)")
_SUPERSEDE_GUARD = "when { expression { env.UPLOAD_SUPERSEDED != 'true' } }"


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-fr2h."
        )
    return path.read_text()


def _strip_comments(src: str) -> str:
    """Blank out whole-line `//` comments and `/* */` blocks, keeping line count.

    Required, not cosmetic: the pipelines explain in prose WHY they do not use
    milestone() or a job-wide lock, and a raw grep for those names would match
    the explanation and fail the very checks that prose exists to justify.
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
        if stripped.startswith("//"):
            out.append("")
            continue
        out.append(line)
    return "\n".join(out)


def _top_level_stages(src: str) -> list[str]:
    """Names of the pipeline's own stages, in source order.

    Nested stages (the parallel Windows/Android blocks in the client pipeline)
    are excluded by taking only the shallowest indentation any stage
    declaration uses, so the check calibrates itself against the file rather
    than hardcoding a column that a reindent would invalidate.
    """
    found = [
        (len(m.group("indent")), m.group("name"))
        for line in _strip_comments(src).splitlines()
        for m in [_STAGE_DECL.match(line)]
        if m
    ]
    assert found, "no stage declarations found. See mc-fr2h."
    top = min(indent for indent, _ in found)
    return [name for indent, name in found if indent == top]


def _stage_body(src: str, stage_name: str) -> str:
    """Return just the braces of a named stage, brace-matched."""
    marker = f"stage('{stage_name}')"
    start = src.find(marker)
    assert start != -1, f"no {marker} in source. See mc-fr2h."
    open_brace = src.find("{", start)
    assert open_brace != -1, f"{marker} has no body. See mc-fr2h."
    depth = 0
    for index in range(open_brace, len(src)):
        if src[index] == "{":
            depth += 1
        elif src[index] == "}":
            depth -= 1
            if depth == 0:
                return src[start : index + 1]
    pytest.fail(f"unbalanced braces in {marker}. See mc-fr2h.")


# ---------------------------------------------------------------------------
# Change 2: the trigger runs last
# ---------------------------------------------------------------------------


def test_publish_to_steam_is_the_last_stage() -> None:
    """The upload trigger must be the final thing the client build does (mc-fr2h).

    Triggered mid-pipeline it queues a second job while this build still has
    minutes of work left, and on a four-executor controller with no agents that
    upload takes an executor away from PR validation.
    """
    stages = _top_level_stages(_src(_CLIENT_SRC))
    assert stages[-1] == "Publish to Steam", (
        "mcdClientPipeline must fire MCDSteam-Upload from its LAST stage, but "
        f"the last stage is {stages[-1]!r} and Publish to Steam sits at "
        f"position {stages.index('Publish to Steam') + 1} of {len(stages)}. "
        "See mc-fr2h."
    )


def test_publish_to_steam_stays_fire_and_forget() -> None:
    """Moving the stage must not change how it triggers (mc-fr2h).

    wait:false so the client build never blocks on Steam, propagate:false so a
    Steam hiccup cannot red an otherwise good build. Both were deliberate and
    the reasoning is in the comment above the stage.
    """
    body = _stage_body(_src(_CLIENT_SRC), "Publish to Steam")
    assert "wait: false" in body, (
        "Publish to Steam must keep wait:false, or the client build blocks on "
        "the Steam upload it just queued. See mc-fr2h."
    )
    assert "propagate: false" in body, (
        "Publish to Steam must keep propagate:false, or a Steam hiccup reds an "
        "otherwise good client build. See mc-fr2h."
    )


def test_publish_to_steam_still_names_its_source_build() -> None:
    """Coalescing needs SOURCE_BUILD to identify what this upload carries (mc-fr2h)."""
    body = _stage_body(_src(_CLIENT_SRC), "Publish to Steam")
    assert "string(name: 'SOURCE_BUILD', value: env.BUILD_NUMBER)" in body, (
        "Publish to Steam must pass its own build number as SOURCE_BUILD. That "
        "number is the whole input to the supersede check: without it every "
        "upload looks like 'whatever is latest' and nothing ever coalesces. "
        "See mc-fr2h."
    )
    assert "string(name: 'STEAM_BRANCH', value: config.steamBranch)" in body, (
        "Publish to Steam must keep passing config.steamBranch, which is the "
        "branch mapping the bead lists as do-not-change. See mc-fr2h."
    )


# ---------------------------------------------------------------------------
# Change 1: one upload per branch, newest wins
# ---------------------------------------------------------------------------


def test_coalescing_happens_before_any_work() -> None:
    """The supersede check must be the upload job's first stage (mc-fr2h).

    A superseded build should cost a few seconds, not a full artifact unpack.
    """
    stages = _top_level_stages(_src(_UPLOAD_SRC))
    assert stages[0] == "Coalesce by Source Build", (
        "mcdSteamUploadPipeline must decide whether it is superseded before it "
        f"does anything else, but its first stage is {stages[0]!r}. "
        "See mc-fr2h."
    )


def test_every_stage_after_the_check_is_gated_on_it() -> None:
    """A superseded build must skip all of it, not just the upload (mc-fr2h)."""
    src = _src(_UPLOAD_SRC)
    for name in (
        "Locate Client Artifacts",
        "Prepare Steam Content",
        "Re-check for Newer Artifacts",
        "Upload to Steam",
    ):
        body = _stage_body(src, name)
        assert _SUPERSEDE_GUARD in body, (
            f"stage {name!r} in mcdSteamUploadPipeline must be gated on "
            f"`{_SUPERSEDE_GUARD}`, or a superseded build still runs it. "
            "See mc-fr2h."
        )


def test_the_check_is_repeated_immediately_before_steamcmd() -> None:
    """Locating and unpacking takes minutes; artifacts can go stale in them (mc-fr2h).

    The re-check must sit directly before the upload, with nothing between the
    two, so the last thing this job knows before it runs steamcmd is that its
    payload is still the newest one.
    """
    stages = _top_level_stages(_src(_UPLOAD_SRC))
    assert "Re-check for Newer Artifacts" in stages, (
        "mcdSteamUploadPipeline must re-check for newer artifacts after "
        "preparing content. A check that only runs at the start cannot see a "
        "client build that archived while this one was unzipping. See mc-fr2h."
    )
    recheck = stages.index("Re-check for Newer Artifacts")
    upload = stages.index("Upload to Steam")
    assert upload == recheck + 1, (
        "'Re-check for Newer Artifacts' must be the stage immediately before "
        f"'Upload to Steam', but {upload - recheck - 1} stage(s) sit between "
        "them. Anything in that gap is time the check cannot account for. "
        "See mc-fr2h."
    )


def test_the_coalescing_key_is_the_source_not_a_constant() -> None:
    """Branches must never suppress each other's uploads (mc-fr2h).

    This is the failure mode the bead names: a coalescing key that is the same
    for every upload passes a naive "is there a lock" test while serialising
    main, backend, card and staging into one queue. Keying on the source job
    and build number cannot do that, because a different Steam branch is a
    different source job.
    """
    src = _strip_comments(_src(_UPLOAD_SRC))
    calls = re.findall(r"mcdSteamSourceBuild\.supersededBy\((.*?)\)", src, re.S)
    assert len(calls) == 2, (
        "expected exactly two supersede checks in mcdSteamUploadPipeline (one "
        f"at the start, one before the upload), found {len(calls)}. "
        "See mc-fr2h."
    )
    for call in calls:
        assert "params.SOURCE_JOB" in call and "params.SOURCE_BUILD" in call, (
            "every supersede check must be keyed on params.SOURCE_JOB and "
            f"params.SOURCE_BUILD, but one reads: {call.strip()!r}. A check "
            "that is not derived from the source cannot tell a superseded "
            "upload from a different branch's upload. See mc-fr2h."
        )

    helper = _strip_comments(_src(_HELPER_SRC))
    assert "/var/lib/jenkins/jobs/${sourceJob}/builds" in helper, (
        "mcdSteamSourceBuild.latest must look under the SOURCE JOB's own "
        "builds directory. A hardcoded job name would make every branch read "
        "one branch's artifacts. See mc-fr2h."
    )


def test_no_job_wide_serialisation_is_introduced() -> None:
    """milestone() and job-wide locks are the wrong tool for this job (mc-fr2h).

    MCDSteam-Upload is one job serving four Steam branches. milestone() is
    scoped to the job and ordered by build number, with no notion of a
    parameter: "older builds will not proceed (they are aborted) if a newer
    build already passed the milestone". A main upload passing it would abort a
    queued backend upload that nothing superseded, and the branch nobody was
    watching would silently stop shipping. disableConcurrentBuilds() fails the
    same way, more slowly.
    """
    src = _strip_comments(_src(_UPLOAD_SRC))
    assert "disableConcurrentBuilds" not in src, (
        "mcdSteamUploadPipeline must not use disableConcurrentBuilds(). It is "
        "job-wide, and this job serves four Steam branches, so it would "
        "serialise unrelated branches behind each other. See mc-fr2h."
    )
    assert "milestone(" not in src, (
        "mcdSteamUploadPipeline must not use milestone(). It aborts older "
        "builds of the JOB by build number and cannot see STEAM_BRANCH, so a "
        "main upload would abort a queued backend upload. See mc-fr2h."
    )
    for call in re.findall(r"\block\((.*?)\)", src, re.S):
        assert "params.STEAM_BRANCH" in call or "params.SOURCE_JOB" in call, (
            "a lock in mcdSteamUploadPipeline must be keyed on the branch or "
            f"the source job, but this one reads: {call.strip()!r}. A constant "
            "resource name serialises every Steam branch into one queue. "
            "See mc-fr2h."
        )


# ---------------------------------------------------------------------------
# Superseded is a normal outcome, not an incident
# ---------------------------------------------------------------------------


def test_a_superseded_upload_is_not_a_failure() -> None:
    """Skipping redundant work must not look like something broke (mc-fr2h)."""
    src = _src(_UPLOAD_SRC)
    for name in ("Coalesce by Source Build", "Re-check for Newer Artifacts"):
        body = _stage_body(src, name)
        assert "currentBuild.result = 'NOT_BUILT'" in body, (
            f"stage {name!r} must mark a superseded build NOT_BUILT. "
            "See mc-fr2h."
        )
        assert "currentBuild.result = 'FAILURE'" not in body and "error " not in body, (
            f"stage {name!r} must not fail the build when it is superseded. "
            "Being superseded is the coalescing working. See mc-fr2h."
        )


def test_a_superseded_upload_does_not_notify_discord() -> None:
    """An aborted upload must not page anybody (mc-fr2h).

    MCDSteam-Upload has its own Discord success and failure notifications. If
    coalescing starts announcing the builds it deliberately skipped, the change
    is worse than the five redundant uploads it replaced.
    """
    src = _strip_comments(_src(_UPLOAD_SRC))
    post = src[src.rindex("post {") :]
    for condition in ("success {", "failure {"):
        start = post.index(condition)
        handler = post[start : start + 400]
        guard_at = handler.find("if (env.UPLOAD_SUPERSEDED == 'true')")
        notify_at = handler.find("discordNotify.")
        assert guard_at != -1, (
            f"post {{ {condition.split()[0]} }} must return early when "
            "env.UPLOAD_SUPERSEDED is 'true'. See mc-fr2h."
        )
        assert guard_at < notify_at, (
            f"the UPLOAD_SUPERSEDED guard in post {{ {condition.split()[0]} }} "
            "must come BEFORE the discordNotify call. See mc-fr2h."
        )


def test_a_manual_upload_is_never_superseded() -> None:
    """Somebody who types a build number means that build number (mc-fr2h).

    The one case where a person is watching is a manual re-upload, usually
    putting a known-good artifact back on a beta after a bad one. Coalescing it
    away would break the only path with a human on the other end.
    """
    helper = _strip_comments(_src(_HELPER_SRC))
    body = helper[helper.index("String supersededBy(") :]
    guard_at = body.find("hudson.model.Cause$UserIdCause")
    latest_at = body.find("latest(sourceJob)")
    assert guard_at != -1, (
        "mcdSteamSourceBuild.supersededBy must exempt manually triggered "
        "builds by checking for a UserIdCause. See mc-fr2h."
    )
    assert guard_at < latest_at, (
        "the manual-trigger exemption must be checked BEFORE the artifact "
        "comparison, or a manual re-upload of an older build gets skipped. "
        "See mc-fr2h."
    )


# ---------------------------------------------------------------------------
# Do not change: the public branch stays a manual flip
# ---------------------------------------------------------------------------


def test_default_steam_branch_is_still_never_set_live() -> None:
    """'default' uploads but does not go live, and must stay that way (mc-fr2h).

    Public releases are a deliberate manual flip in Steamworks. Nothing in this
    change touches it, and this test exists so nothing later does by accident.
    """
    body = _stage_body(_src(_UPLOAD_SRC), "Prepare Steam Content")
    assert 'if [ "\\$SETLIVE_BRANCH" = "default" ]' in body, (
        "Prepare Steam Content must still special-case STEAM_BRANCH=default. "
        "See mc-fr2h."
    )
    guard = body[body.index('if [ "\\$SETLIVE_BRANCH" = "default" ]') :]
    guard = guard[: guard.index("else")]
    assert 'SETLIVE_BRANCH=""' in guard, (
        "STEAM_BRANCH=default must leave setlive EMPTY so the public branch "
        "stays a manual Steamworks flip. See mc-fr2h."
    )
