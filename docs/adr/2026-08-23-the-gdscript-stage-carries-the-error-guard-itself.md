# The GDScript stage carries the ERROR-line guard itself, rather than calling `make test-gdscript`

Date: 2026-08-23

Status: Accepted

## Context

A GDScript suite that fails to PARSE is not reported as failed. gdUnit builds
its suite list by loading each candidate script and asking whether what came
back is a test suite; a script that will not parse fails that question and is
dropped. The dropped suite then contributes zero cases, so the summary reads
`0 errors | 0 failures`, both halves of `Executed test suites: (N/M)` are
counted after that load and still match, and the process exits 0.

This is not theoretical. On 2026-08-23 `tests/test_hangar_view.gd` was
parse-broken on MCDClient `main` from 06:21 (commit `4c4ba50e`), 42 lines of
`Parse Error: Identifier "CardStatData" not declared in the current scope`.
MCDClient PR #2627 ran a full "client only" validation over that tree at 18:33
and passed in 6 min 56 s. It touches neither that file nor `CardStatData`, so
nothing in the PR masked or fixed it. A whole suite stopped executing and every
PR for twelve hours was told everything was fine.

What does notice is the ERROR-class line the engine prints while loading the
broken script. MCDClient's `make test-gdscript` pipes the run through
`tests/_log_filter.py`, which drops the log lines tests emit on purpose, and
then fails the build if any `^(ERROR|SCRIPT ERROR|USER ERROR): ` line survived.
That guard has existed since MCDClient #1527 (2026-05-16).

The `GDScript Tests` stage in `mcdPRValidationPipeline` never went through that
target. It invoked gdUnit directly. So for three months the only guard that
catches this class was decorative on the only check that gates a merge, and the
same tree was red for a developer running `make` and green in CI at the same
time. That is bead `mc-rqgm`.

## Decision

The stage keeps invoking gdUnit directly, and the guard is ported into the stage
body: the run is piped through the checkout's own `tests/_log_filter.py`, tee'd
to `build/godot-test-output.log`, and that log is grepped for surviving
ERROR-class lines. `allowEmptyResults` on the JUnit publisher goes to `false`.

## Why not point the stage at `make test-gdscript`

Bead `mc-rqgm` proposed that first, and preferred it, on the grounds that it
removes the drift rather than duplicating the check. Two things decided against
it.

**`make test-gdscript` does not pass `-c`, so it is fail-fast.** In gdUnit4, `-c`
is what calls `disable_fail_fast()`; without it `GdUnitTestCIRunner._ready` runs
`_executor.fail_fast(true)` and the run stops at the first failing test. This
stage has always passed `-c`. Handing it to the make target would have two
effects, and the second is the serious one:

- The JUnit report this stage publishes shrinks to whatever ran before the first
  failure. A PR with one early failure would report a fraction of the suite, and
  the number of suites CI executed would once again not be the number of suites
  in `tests/`.
- MCDClient PR #2642 has just landed `tests/test_suite_discovery_guard.gd`,
  which fails the run when a declared suite did not load. Under fail-fast that
  suite runs only if every suite scanned ahead of it passed. A gate that runs
  only while everything else is already green is not a gate, and adopting the
  make target would partly undo the fix that just landed.

**The branch-skew argument that motivated the same shape elsewhere does not
apply here.** `Script Tests` probes for its target because `release`,
`features/backend` and `features/card` lag `main` and lacked it. Checked on
2026-08-23: all four branches carry `tests/_log_filter.py`,
`tests/_log_filter_patterns.txt`, a `test-gdscript` target AND the post-filter
guard inside it. So the make target is not missing anywhere. It is simply the
wrong invocation for CI, and it also re-enters `Src/MCDCoreExt/build.sh
--configure --build --install` through its `ext` prerequisite, after the
dedicated `Build MCDCoreExt Linux (for tests)` stage has already done exactly
that.

## What is deliberately not duplicated

Only the assertion "no ERROR-class line survived" is now stated in two places.
The knowledge is not. Which lines are test-intentional is decided by that
branch's own `tests/_log_filter.py` and `tests/_log_filter_patterns.txt`, and
the stage pipes through them, so a pattern added in MCDClient takes effect in CI
with no change here. The filter runs under `python3 -u`: it sits between Godot
and the console, and a buffered filter holds the whole run's output back, which
is how a working stage comes to look hung.

A missing filter fails the stage rather than running it ungated. On the two
branches this library serves (`main` and `release`, per the two jobs in
README.md) that file has shipped since 2026-05, so its absence is a broken
checkout, not a skew to route around.

## `allowEmptyResults: false`

The stage existed to catch "nothing ran, reported as success". An empty JUnit
result set is that same claim in another form, and the publisher was configured
to accept it. The stage always runs the whole `tests/` tree, so a build that
reaches the publisher with no `reports/**/results.xml` has not passed, it has
failed to run. Measured on MCD-PR-Main #1634 (2026-08-24): 7748 tests published,
so the pattern does match on a normal client build.

## Consequences

**MCDClient `main` is red under this guard today, and that has to be fixed
first.** Measured on `origin/main` at `3253fb04`, full `make test-gdscript` in a
clean worktree on 2026-08-23:

```
Overall Summary: 5340 test cases | 0 errors | 0 failures | 0 flaky | 7 skipped | 0 orphans |
Executed test suites: (392/392)
Exit code: 0
make: *** [Makefile:591: test-gdscript] Error 1
```

gdUnit is green and the guard is not. Exactly one line survives the filter:

```
ERROR: Object '<Object#...>' was freed or unreferenced while a signal is being emitted from it.
   at: ~Object (core/object/object.cpp:2423)
   [0] test_tutorial_combat_drives_enter_combat_reveal_in_lockstep
       (res://tests/test_tutorial_combat_tactical_reveal.gd:402)
```

That is beads `mc-0c5i` (2026-08-19) and `mc-y7aa` (2026-08-21, the same line on
`features/backend`), a teardown that frees six nodes with `free()` while a signal
from one of them is still being emitted. Both are open. Merging this change
before that fix turns every client PR on `main` and `release` red, so the
landing order is `mc-0c5i` first, then this. Widening
`tests/_log_filter_patterns.txt` to cover that line instead is explicitly
rejected: `mc-0c5i` says so, and a pattern that hides one instance of an error
class hides the next one too.

**A guard that fires reddens the branch, on purpose.** That is the trade this
bead asks for. The failure message names the two ways out (mark the line with
`GdUnitErrorExpectation.expect_error`, or declare it in
`tests/_log_filter_patterns.txt` with a reason) and says not to widen the
pattern to silence a real failure. The filtered log is archived on failure so
the surviving lines can be read in context.

**The make target's own pytest self-tests stay out of CI, and that is the one
real cost of this choice.** `make test-gdscript` opens by running
`tests/test_log_filter_script.py`, `tests/test_post_filter_guard.py` and
`tests/test_check_authoring_data_refs.py`, and MCDClient's `test-scripts` list
(the one `Script Tests` invokes) names none of the three, so nothing in this
pipeline runs them. Calling the make target would have brought them in. The
exposure is bounded: the stage now runs `tests/_log_filter.py` directly under
`pipefail`, so a filter that fails to start fails the stage rather than passing
quietly. The clean fix is to name those files in MCDClient's `test-scripts`
target, which is where the rest of the pipeline's Python tests live; bead
`mc-owr0` already tracks that whole class.

**This repo has no CI**, so the stage body is covered by
`test/unit/test_mcd_gdscript_error_guard.py`, which parses the Groovy and also
EXECUTES the extracted guard against synthetic logs, including the verbatim
`mc-x4hz` parse error. Eleven mutations of the stage body were applied and every
one failed at least one test.
