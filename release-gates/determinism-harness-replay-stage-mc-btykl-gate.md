# Release Gate - determinism harness replay stage

- **Bead:** `mc-btykl` - Review: mcd-jenkins-shared determinism harness replay stage
- **Source bead:** `mc-vaema` - Dispatch: mc-9t1.10 determinism harness paired shared-library PR
- **Validator follow-up:** `mc-wajxw` - test coverage for the shared-library determinism stage
- **Repo:** `MechaCorpsGames/mcd-jenkins-shared`
- **Branch:** `users/jim/determinism_harness_shared_stage_mc_vaema`
- **Base:** `origin/main`
- **Reviewed implementation commit:** `077c48c397c211ddf05cec50ef6eeef3eca2845e`
- **Final HEAD evaluated:** `f2fff679052c991192400204c2f6d9d07fc2a1cd`
- **Evaluated by:** MCDClient/deployer on 2026-05-26

`docs/PROJECT_MANIFEST.md` is not present in this repository. This gate uses
the deployer gate criteria plus the source bead exit contract and validator
follow-up evidence recorded in `mc-vaema`, `mc-btykl`, and `mc-wajxw`.

## Source commits

| Commit | Bead | Subject | Gate note |
|---|---|---|---|
| `077c48c` | `mc-vaema` | `feat(pipelines): add determinism harness replay stage` | Reviewed PASS in `mc-btykl`. |
| `1e43510` | `mc-wajxw` | `test(ci): shared-library determinism harness replay stage coverage` | Validator follow-up; adds Python coverage for the new Groovy shared-library behavior. |
| `f2fff67` | `mc-wajxw` | `chore: ignore Python pycache and pytest artifacts in test/` | Cleans generated pycache from the test commit and prevents recurrence. |

## Gate criteria

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Review PASS present | **PASS** | `bd show mc-btykl` records `Review verdict: PASS` from `MCDClient/reviewer` for commit `077c48c397c211ddf05cec50ef6eeef3eca2845e`. The branch head also includes the closed validator follow-up `mc-wajxw`, limited to test coverage and `.gitignore`. |
| 2 | Acceptance criteria met | **PASS** | `vars/mcdChangeDetection.groovy` now sets `determinismHarnessChanged` for `tests/determinism-harness/**` and `Src/TestClient/Test/replay/**`; `vars/mcdPRValidationPipeline.groovy` computes `DETERMINISM_WIRE_FORMAT_CHANGED` and `DETERMINISM_PER_PR_CHANGED`, suppresses per-PR replay for wire-format-only changes, and adds the `determinism-harness-replay` stage; `vars/mcdDeterminismHarness.groovy` adds PR/nightly replay helpers, JUnit XML, artifact sampling, exit-code and attribution checks; `vars/mcdDeterminismHarnessNightlyPipeline.groovy` adds the cron entrypoint. Validator coverage maps these surfaces in `test/unit/test_mcd_change_detection.py`, `test/unit/test_mcd_pr_cadence_selection.py`, and `test/unit/test_mcd_determinism_harness_logic.py`. |
| 3 | Tests pass | **PASS** | `python3 -m pytest test/unit/` collected 80 tests and passed all 80. `git diff --check origin/main...HEAD -- .gitignore vars test README.md` returned no output. |
| 4 | No high-severity review findings open | **PASS** | Reviewer found two LOW advisory issues: `baselineName` not shell-quoted in nightly artifact sampling, and `writeJUnit` defensive handling for no-slash paths. Both were explicitly marked non-blocking; there are no unresolved HIGH findings. |
| 5 | Final branch is clean | **PASS** | Before writing this gate file, `git status --short --branch` showed the branch clean at `users/jim/determinism_harness_shared_stage_mc_vaema...origin/users/jim/determinism_harness_shared_stage_mc_vaema`. After committing the gate file, the same command showed the branch clean and one local gate commit ahead of origin. |
| 6 | Branch diverges cleanly from main | **PASS** | `git fetch origin main users/jim/determinism_harness_shared_stage_mc_vaema` completed. `git merge-tree --write-tree HEAD origin/main` exited 0 and returned tree `9d915cd57104bfccb4a78e34e80f42adfc86a13d`. |

## Test output

```text
$ python3 -m pytest test/unit/
collected 80 items

test/unit/test_mcd_change_detection.py ..........                        [ 12%]
test/unit/test_mcd_determinism_harness_logic.py ........................ [ 42%]
............................                                             [ 77%]
test/unit/test_mcd_pr_cadence_selection.py ..................            [100%]

80 passed in 0.12s
```

## Verdict

**GATE PASS.** Ready to push the gate commit and open a human-reviewed PR.
