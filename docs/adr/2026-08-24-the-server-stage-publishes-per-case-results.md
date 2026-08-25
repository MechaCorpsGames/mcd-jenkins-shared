# The server stage publishes per-case results

- **Status:** Accepted
- **Date:** 2026-08-24
- **Bead:** mc-ek9f
- **PR:** users/tim/ek9f_server_junit (this repo), users/tim/ek9f_server_junit_xml (MCDClient)

## Context

A full MCDServerTest run published to Jenkins as **`Total: 1`**. Measured: MCD-PR-Main **#1670**
(server-only PR 2666) reported `Total: 1, Passed: 1` while 2434 cases ran on that commit.

The suite was correctly gated the whole time. `Unit Tests` runs `./build.sh --test --release` in
a shebang-less body, so Jenkins runs it under `/bin/sh -xe` and a failing case fails the stage.
What was missing was per-case visibility, and `Total: 1` actively misleads: it reads like a green
that skipped everything.

Two things were missing, and only one is in this repo. MCDClient's `build.py` never asked ctest
for JUnit XML, and this pipeline never collected any. The producing half, the zero-test floor,
and the full rationale are in MCDClient's
`docs/adr/2026-08-24-the-server-suite-reports-per-case-and-refuses-a-run-of-nothing.md`.

## Decision

**`Unit Tests` collects `test-results/server-tests.xml` from `post { always }`.**

- **`post { always }`, not `steps`.** A junit step in `steps{}` is skipped the moment the sh step
  fails, which is precisely the run whose per-case report is the entire point.
- **`allowEmptyResults: true`, against the GDScript stage's `false` (mc-rqgm).** Two reasons, and
  neither is a relaxation of that precedent:

  1. **It cannot be the no-run guard here.** Measured with ctest 4.4.0: a project with nothing
     registered prints `No tests were found!!!`, exits 0, **and writes a well-formed
     `<testsuite tests="0"/>`**. junit accepts that file, so `allowEmptyResults` never gets the
     chance to fire. Only a check on the file's content catches it, and that lives on the
     producing side (`require_tests_ran` in MCDClient's `build.py`), which runs before this step
     is reached. The GDScript stage's `false` works because its runner produces no file at all in
     the equivalent case; ctest's behaviour is different, so the same flag does different work.
  2. **`false` would red-line branches that lag main.** This library is shared by every job, and
     `release`, `features/backend` and `features/card` keep the old `build.py`, which writes no
     XML, until the MCDClient change reaches them. Same skew, and the same treatment, as
     `TestClient Unit Tests` and `Script Tests`.

- **The sh body is untouched and stays fatal.** Adding collection is exactly the change that
  tempts someone to wrap the run in `set +e` or `|| true` so the junit step is always reached.
  `post { always }` already reaches it, so there is no reason to, and a test pins the absence of
  a shebang, `set +e` and `|| true` in this stage specifically.

## Consequences

- A failing server case is nameable from Jenkins test reporting. On the verification run against
  the real suite, 88 failures came through individually named.
- Per-case history exists, so a flaky server test becomes trackable across builds instead of
  re-rolled.
- **`test-results/server-tests.xml` is a cross-repo contract** between this file and MCDClient's
  `build.py`, held together by nothing but a string. Both sides pin the exact path in a test, so
  a rename fails loudly rather than turning collection off silently.
- During the skew window, lagging branches report exactly what they report today.
- **This repo has no CI**, so review is the only gate. `test/unit/` is the evidence:
  `test_mcd_server_test_junit_reporting.py` runs real ctest against temp trees to pin the
  behaviour the `allowEmptyResults` choice rests on, rather than asserting it in a comment.
- **Those three tests are designed to expire, and that is deliberate rather than incidental.**
  They pin a fact about a tool this repo does not vendor and cannot pin a version of. If a future
  ctest starts signalling an empty project through its exit code, or stops writing a report for
  one, the premise under `allowEmptyResults: true` is gone, the flag could be tightened to `false`
  to match the GDScript stage, and `require_tests_ran` would become redundant. Nothing would
  otherwise announce that: the stage would keep passing and the comment would quietly become a
  lie. So each of the three fails with a message naming what changed and what to revisit, which
  makes them a dated measurement that reports its own expiry rather than a regression guard.
- **TestClient (290 cases) and Proxy have the identical gap and are not fixed here.** The bead's
  acceptance is server-only; the mechanism and the path convention now exist for both.
