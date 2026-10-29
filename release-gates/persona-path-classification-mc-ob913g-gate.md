# Persona and playtester path classification release gate

**Verdict:** **PASS**

Date: 2026-10-29T17:36:09.688579+00:00
Deploy bead: mc-ob913g. Build: mc-5gbyxt. Review: mc-62i8r1. Validator: mc-nkysyl.
Repository: MechaCorpsGames/mcd-jenkins-shared. deploy_mode: remote.
Reviewed source: `2ca2caebc98eaa8ffb4f7d02f35ed62304333055`.
Pinned base: `origin/main@abc6dc06fd45af04e6676168251e75c795395840`.
Tested prospective merge: `5c2236082e59e2139b9dfe797e18122336b82d45`.
Tested tree: `8e8e04f9e882579b58ace08d052560b5d6e128d8` (byte-identical to the reviewed source tree).
merge_state: clean (origin/main@abc6dc06fd45af04e6676168251e75c795395840)
gate_base_kind: current-tip
merge_validation: mayor must recheck the current main and prospective merge before landing; no merge performed by deployer.

| # | Criterion | Result | Evidence |
|---|---|---|---|
| 1 | Review PASS present | PASS | Review mc-62i8r1 verdict pass binds to the resolved reviewed source. No carryover or reviewer-added test repin. |
| 2 | Acceptance criteria | PASS | Pre-merge criteria 1-6 verified below; post-merge criteria 7-8 explicitly assigned to the merge/console reader, pending actual merge. |
| 3 | Tests pass | PASS | Full test/unit suite: 534 PASS, 0 FAIL, 0 SKIP; all five owned names PASS. |
| 4 | No unresolved HIGH findings | PASS | Review style_findings none; security_findings none; spec has no blocker. |
| 5 | Final branch clean | PASS | Source and tested scratch clean; publication contains exactly the reviewed two-file change plus this gate record, committed before push. |
| 6 | Merge state and build/syntax | PASS | Clean merge with pinned base; GroovyShell parsed changed production file; all 41 Python unit files AST-parsed; full Groovy harness wrappers executed in suite. |
| 7 | Single feature theme | PASS | Exactly two source paths: classification rules and their tests. One theme: persona/playtester paths select existing MCP guard tests. |

## Acceptance evidence

1. Two executable prefix rules return mcp-game-server for mcp/personas/ and docs/playtester/. New regression names and recorded real Groovy six-path execution establish both.
2. Rules follow the existing Src/MCPGameServer/ rule and precede def docPrefixes, with an ASCII comment explaining why. Fresh-view diff format/ASCII check PASS; owned ordering test PASS.
3. Existing 15 test bodies unchanged; MCP flag-only guard and narrow-prefix/wiki precedence guard PASS. Both pipeline files byte-identical. No new flag, dependency, broad prefix, duplicate-rule cleanup or release-gates classification edit.
4. Historical builder and independent reviewer RED observed exactly the first three regressions fail with the intended messages. Two guards pass before/after. Exact messages and every test purpose carried into PR body.
5. Entire test/unit suite freshly passed. All 20 tests in the changed file are included.
6. PR body prepared with five test purposes, three exact RED messages, six executed classification examples, expected extra docs-only checks and inferred persona-only effects, no-ADR explanation and one-commit revert. Base is library main.
7. PENDING AFTER MERGE: mayor records actual merge commit on mc-5gbyxt and verifies library main contains the rules before dependent docs work starts. A closed build/deploy bead is not landing evidence.
8. PENDING AFTER MERGE: console reader confirms first persona-only PR has no persona path in unmatched warning and MCP Game Server Tests runs (Src/MCPGameServer must exist). Record actual console evidence or exact access limitation on mc-5gbyxt. No Jenkins outcome claimed here.

## Test evidence

test_cmd: load-gate-run.sh --threshold 15 --max-wait 1800 -- isolated-test-run.sh --bead mc-ob913g -- env MCD_GROOVY=<retained Groovy 4.0.22 launcher> PYTHONDONTWRITEBYTECODE=1 python3 -m pytest -p no:cacheprovider -vv -rA --junitxml=<retained suite.xml> test/unit/
test_cmd_scope: full-suite
test_counts: 534 PASS, 0 FAIL, 0 SKIP
test_seconds: 58.14
heavy_mode: none
waiver_ref: none
failure_attribution: none
skip_justification: none needed (zero SKIP)
policy_lane: none - repository has no configured policy/lint target; fresh-tree-run.sh plus gate-base.sh ran whitespace and ASCII checks, PASS before suite.
drift_lane: none - repository has no CI generation/drift check.
ci_lane_run: n/a - diff changes classification prefixes only, no CI job, matrix, timeout or required-check list; both pipelines unchanged. This repo has no CI configuration; test/unit is its documented local gate.
test_environment: wrapper PASS-THROUGH; ambient environment inherited. No isolated clean-environment claim. Runtime supplied before suite; all three Groovy harness wrappers passed.
test_log_dir: `/var/tmp/gc-heavy-gate/runs/mc-ob913g-gate-20261029-retry.q3dgdp6z.c3/units` (retained).
test_junit: `/home/jaword/projects/gc-management/.gc/artifacts/MCDClient/mc-ob913g/gate-20261029/suite.xml` (native pytest counts and owned-name evidence; gate-test-evidence.py supports Go text only).
test_evidence: `/home/jaword/projects/gc-management/.gc/artifacts/MCDClient/mc-ob913g/gate-20261029/final-test-evidence.json`.

diff_tests_executed:
- `test_categorize_has_an_explicit_mcp_personas_rule`: PASS
- `test_categorize_has_an_explicit_docs_playtester_rule`: PASS
- `test_persona_and_playtester_rules_sit_above_the_docs_prefix_loop`: PASS
- `test_mcp_game_server_case_sets_only_its_own_flag`: PASS
- `test_no_wholesale_mcp_or_docs_rule_and_wiki_stays_above_the_docs_loop`: PASS

## Load evidence

load_threshold: 15
load_waited_seconds: 1561
load_wait_timed_out: 0
run_start_load: 14.66
run_max_load: 19.55
run_mean_load: 19.55
run_readings: 1
wait_first_load: 54.38
wait_max_load: 54.38
wait_mean_load: 31.00
wait_readings: 53
read_errors: 0

The successful retry waited for load to clear and ran once. The earlier disk refusal ran no tests and is retained separately; no results were combined.

## Publication and merge ownership

Publish only deploy/mc-ob913g-gate in the mayor-provisioned deploy checkout, from the exact reviewed source plus this gate commit. Builder/source checkout stays read-only.
Mayor owns merge under the recorded operator exception for this shared library and owns both sanctioned checkout removals after merge/abandonment. Library main is loaded unpinned by consumers. Clearance must cover exact published head before the verified merge-request. Post-merge obligations above remain pending until observed.
