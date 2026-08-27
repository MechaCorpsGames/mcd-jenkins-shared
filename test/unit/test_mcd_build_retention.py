"""Tests for how long MCDServer-Main's builds survive (mc-n37x).

mc-n37x is an intermittent integration-test timeout: roughly one run in three,
with the identical commit going SUCCESS (#905), FAILURE (#908), SUCCESS (#909).
PR 100 made the failing run leave evidence behind by moving the proxy and client
logs into the workspace and archiving them. That fix was necessary and it was
not sufficient, because it archived them into a retention window measured in
hours.

THE THING THIS FILE EXISTS TO PREVENT. In Jenkins, numToKeepStr is a hard
ceiling on everything a build leaves behind: it discards the build record, and
the console log and the artifacts go with it. artifactDaysToKeepStr and
artifactNumToKeepStr can only delete artifacts EARLIER from builds that still
exist. Neither can extend anything. A config reading

    logRotator(numToKeepStr: '10', artifactDaysToKeepStr: '7', artifactNumToKeepStr: '10')

therefore keeps nothing for 7 days; it keeps everything for 10 builds. PR 100
shipped a comment claiming the opposite, that artifactDaysToKeepStr made a
failure "diagnosable for a week", while the pytest file added by that same PR
correctly said the logs "rotate after 10 builds". The PR contradicted itself,
and the wrong half was the half a future reader would act on.

MEASURED, 2026-08-24. Asking Jenkins for 25 builds of MCDServer-Main returned
10, proving rotation had already fired. Those ten were #943 (10:19Z) through
#952 (15:54Z): a real retention window of 5 hours 35 minutes. An integration
failure archived at 10:00 was gone by 16:00 the same day, and the window is
shortest on busy days, which is when a load-sensitive flake is most likely to
fire in the first place.

These tests pin the numbers and, just as importantly, pin the EXPLANATION. A
control that misreports the protection it provides is the defect that produced
mc-n37x's second round, so a wrong comment here is treated as a failure.

Run with: pytest test/unit/test_mcd_build_retention.py
No live Jenkins required. Tests parse Groovy source.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

_REPO_ROOT = Path(__file__).parent.parent.parent
_VARS = _REPO_ROOT / "vars"

_SERVER_SRC = _VARS / "mcdServerPipeline.groovy"

# Observed build rate on MCDServer-Main, 2026-08-24: builds #943 through #952
# all landed inside 5h35m, so ~9 builds/day is a normal working day rather than
# an outlier. A week of history at that rate needs about 60 build records.
_MIN_NUM_TO_KEEP = 60


def _src(path: Path) -> str:
    if not path.exists():
        pytest.fail(
            f"{path} not found. "
            "This test must run from the mcd-jenkins-shared repo root. "
            "See mc-n37x."
        )
    return path.read_text()


def _log_rotator_args(src: str) -> dict[str, str]:
    """Return the logRotator(...) keyword arguments as a dict."""
    match = re.search(r"logRotator\(([^)]*)\)", src)
    assert match is not None, (
        "no logRotator(...) in mcdServerPipeline.groovy. Without an explicit "
        "buildDiscarder the retention is Jenkins' default and mc-n37x's "
        "evidence lifetime is unpinned again."
    )
    return {
        key: value
        for key, value in re.findall(r"(\w+):\s*'([^']*)'", match.group(1))
    }


@pytest.fixture(name="rotator")
def _rotator() -> dict[str, str]:
    return _log_rotator_args(_src(_SERVER_SRC))


# ---------------------------------------------------------------------------
# The numbers
# ---------------------------------------------------------------------------


def test_num_to_keep_outlives_a_busy_day(rotator: dict[str, str]) -> None:
    """Build records survive long enough to read a flake's verdict (mc-n37x).

    numToKeepStr governs the console log, and the console log is where the
    Integration Test stage prints its confirm/refute verdict for the suspected
    send-channel disconnect. At 10 it held 5h35m of history on a normal day, so
    a failure that fired overnight or over a weekend was unreadable by the time
    anyone looked. That is not a retention preference; it is the difference
    between the capture working and not working.
    """
    assert "numToKeepStr" in rotator, (
        "numToKeepStr is unset, so build retention falls back to a Jenkins "
        "default nobody here has pinned. See mc-n37x."
    )
    num_to_keep = int(rotator["numToKeepStr"])
    assert num_to_keep >= _MIN_NUM_TO_KEEP, (
        f"numToKeepStr is {num_to_keep}, which at the observed ~9 builds/day is "
        f"about {num_to_keep / 9:.1f} days of history. mc-n37x fires on roughly "
        "one run in three and its evidence has already been lost once this way: "
        f"build #908 was a 404 before anyone read it. Keep at least "
        f"{_MIN_NUM_TO_KEEP} so a failure stays readable for about a week."
    )


def test_artifact_retention_cannot_exceed_build_retention(
    rotator: dict[str, str],
) -> None:
    """artifactNumToKeepStr above numToKeepStr would be a false promise (mc-n37x).

    Artifacts are destroyed with the build record. Configuring more artifact
    slots than build slots does not keep a single extra file; it only makes the
    config read as though it does. This is the same class of error as the
    comment that shipped in PR 100.
    """
    if "artifactNumToKeepStr" not in rotator or "numToKeepStr" not in rotator:
        pytest.skip("retention values not both pinned; covered by other tests")
    artifact_num = int(rotator["artifactNumToKeepStr"])
    num_to_keep = int(rotator["numToKeepStr"])
    assert artifact_num <= num_to_keep, (
        f"artifactNumToKeepStr ({artifact_num}) exceeds numToKeepStr "
        f"({num_to_keep}). Artifacts die with the build record, so the extra "
        "slots keep nothing and the config overstates what survives. "
        "See mc-n37x."
    )


# ---------------------------------------------------------------------------
# The explanation, which is what actually went wrong last time
# ---------------------------------------------------------------------------


def test_no_comment_claims_a_week_of_artifact_retention() -> None:
    """The retention claim that shipped false in PR 100 does not come back.

    PR 100's groovy said archived logs "stay diagnosable for a week" on the
    strength of artifactDaysToKeepStr, while artifactNumToKeepStr capped them at
    10 builds, about one day. The next person to read that comment would have
    trusted it and stopped looking for the real limit. Pin the phrasing so the
    wrong explanation cannot be reintroduced alongside correct numbers.
    """
    src = _src(_SERVER_SRC)
    assert "stays diagnosable for a week" not in src, (
        "The false retention claim from PR 100 is back in "
        "mcdServerPipeline.groovy. Artifact lifetime is bounded by "
        "artifactNumToKeepStr and by numToKeepStr, not by artifactDaysToKeepStr "
        "alone. See mc-n37x."
    )


def test_artifact_retention_is_explained_with_its_binding_limit() -> None:
    """Wherever artifactDaysToKeepStr is explained, the real cap is named too.

    artifactDaysToKeepStr is the misleading one: it is the largest number in the
    config and the only one denominated in time, so it reads like the answer.
    It is not, whenever artifactNumToKeepStr is smaller in practice. Any comment
    that reaches for it has to name the limit that actually binds, or it repeats
    PR 100's mistake in new words.
    """
    src = _src(_SERVER_SRC)
    for match in re.finditer(r"artifactDaysToKeepStr", src):
        window = src[max(0, match.start() - 1200):match.start() + 1200]
        if "logRotator(" in window and "//" not in window:
            continue  # the config line itself, not prose about it
        assert "artifactNumToKeepStr" in window or "numToKeepStr" in window, (
            "A comment explains artifactDaysToKeepStr without naming "
            "artifactNumToKeepStr or numToKeepStr, the limits that actually "
            "bind. That is how PR 100 shipped a false retention guarantee. "
            "See mc-n37x."
        )


def test_integration_capture_is_paired_with_retention() -> None:
    """Archiving the integration logs is pointless without retention (mc-n37x).

    These two changes only work together. Archiving without retention writes
    evidence into a 5-hour window, which is what PR 100 did; retention without
    archiving keeps a console log with no logs behind it. If a future change
    drops the archive step, this file's premise is gone and someone should be
    told rather than left with tests that still pass.

    The glob widened from 'integration-logs/*.log' to 'integration-logs/**'
    when the proxy was given --log-dir, because the GameServer it spawns writes
    a level down in integration-logs/<gameID>/. That is more files but barely
    more bytes: a whole run measures about 1 MB locally, against the ~19 MB
    apiece that MCDServer, MCDProxy and MCDTestClient already contribute to
    this job's artifact footprint. The retention arithmetic is unchanged.
    """
    src = _src(_SERVER_SRC)
    assert "integration-logs/**" in src, (
        "The Integration Test stage no longer archives the integration logs. "
        "If that was deliberate, the retention reasoning in this file and in "
        "the buildDiscarder comment needs revisiting. See mc-n37x."
    )
