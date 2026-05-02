# Release Gate — per-module Go tests Jenkins stage (`mc-eg0.2`)

- **Bead:** `mc-z4i` — Review: per-module Go tests stage on mcd-jenkins-shared (PR #32, mc-eg0.2)
- **Source bead:** `mc-eg0.2` · Parent ADR: `mc-eg0`
- **PR:** https://github.com/MechaCorpsGames/mcd-jenkins-shared/pull/32
- **Branch:** `users/jim/per_module_go_test_stage`
- **Base:** `main`
- **Commit:** `798b74eb`
- **Evaluated by:** MCDClient/deployer on 2026-05-02

## Source commit

| Bead | Commit | Subject |
|---|---|---|
| `mc-eg0.2` | `798b74eb` | feat(pipelines): per-module Go tests stage with change-detection gate (mc-eg0.2) |

## Gate criteria

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Review PASS present | **PASS** | First-pass PASS by `MCDClient/reviewer` recorded in `mc-z4i` notes (2026-05-02). Gemini second-pass disabled per current factory config. |
| 2 | Acceptance criteria met | **PASS** | Reviewer verified all 4 actionable criteria (AC5 — hand-broken handler test — explicitly deferred to post-merge integration). Deployer spot-check on the diff (151 lines, +89/-1 across `vars/mcdChangeDetection.groovy` and `vars/mcdPRValidationPipeline.groovy`): seven new env vars wired to seven gated parallel sub-stages, each labelled `test-go: <Module>`, each invoking `make test-go MODULE=<Name>`. `Src/Shared/` propagation (mcdChangeDetection.groovy:130-133) correctly fans out to `proxyChanged` + `mcpServerChanged` so a Shared-only PR triggers all seven sub-stages. |
| 3 | Tests pass | **PASS** (per coverage policy (c)) | Repo is a Jenkins shared library; no automated test framework for the Groovy DSL exists. Validation is by deployment — the next Go-touching PR will exercise the new stage end-to-end. PR description records this; pattern matches every prior pipeline change in this repo. |
| 4 | No high-severity review findings open | **PASS** | Zero blocking findings. Two informational notes from the reviewer: (a) the sibling Makefile target on MCDClient does not allowlist `$(MODULE)` before `cd Src/$(MODULE)` — out of scope for this PR; (b) parallel DB tests don't set `MCDC_SKIP_DB_TESTS` / `TEST_DATABASE_URL`, but pre-existing sequential stages have the same risk profile and `mc-0b0` (require-or-honest-skip) explicitly tightens this contract downstream. Neither is gate-blocking. |
| 5 | Final branch is clean | **PASS** | Branch contains exactly one commit ahead of `origin/main` (`798b74eb`); diff is +89/-1 across two files. No stray uncommitted state on the source clone. |
| 6 | Branch diverges cleanly from base | **PASS** | `gh pr view 32 --repo MechaCorpsGames/mcd-jenkins-shared` reports `mergeStateStatus=CLEAN`, `mergeable=MERGEABLE`. |

## Cross-repo merge-order constraint (CRITICAL)

**MCDClient PR #1438 (`mc-i6p` / `mc-eg0.1`) MUST land before this PR.**

If #32 lands first, every Go-touching PR validation will hit `make: *** No rule to make target 'test-go'` (the new pipeline stage invokes a Make target that does not yet exist on the MCDClient base branch). Reviewer surfaced this; PR #32's description spells out the merge order; deployer has gated PR #1438 (`per-module-go-test-targets-gate.md` in MCDClient/release-gates) ahead of this one and is mailing the mayor.

## Out-of-scope deferrals (informational)

- **AC5 — hand-broken handler test fails before merge.** Cannot be exercised until both PRs (and a Go-touching test PR) land; deferred to post-merge validation.
- **`mcdServicesPipeline.groovy` not modified.** The bundled CR+MCP deploy gate via `crashReportingChanged` is intentionally preserved; per-module flag is additive only. Documented in bead notes and PR #32 description.

## Verdict

**PASS** — gate file committed to PR branch; bead notes updated. Mayor mailed with merge-order warning.
