# Release Gate - appservices service-test helper marker

- **PR:** [mcd-jenkins-shared #35](https://github.com/MechaCorpsGames/mcd-jenkins-shared/pull/35)
- **Bead:** mc-m1xuoi (review bead) / mc-2pk5td (source bead)
- **Branch:** `users/jim/appservices_docker_service_tests_mc-uvetop`
- **Base:** `main`
- **Feature HEAD evaluated:** `2f0d1f00d14e9a8539abff57087c0cc2e992451d`
- **Evaluator:** MCDClient/deployer - 2026-05-15

## Criterion 1 - Review PASS present

**PASS.** `bd show mc-m1xuoi` records `Reviewer verdict: pass` and
`Verdict: pass` for PR #35 at `2f0d1f00`.

## Criterion 2 - Acceptance criteria met

**PASS.** The rebuilt branch is intentionally comment-only. It removes the stale
literal `scripts/dev-pg.sh` marker from the MCDAppServices Service Tests comment
without changing executable pipeline behavior.

| Acceptance criterion | Evidence | Result |
|---|---|---|
| Branch no longer carries the superseded docker-compose fixture implementation | Diff is limited to `vars/mcdAppServicesPipeline.groovy` comment text. | PASS |
| The stale `dev-pg` marker is gone from the AppServices pipeline comment | `rg "nix develop|dev-pg" vars/mcdAppServicesPipeline.groovy` returned no matches. | PASS |
| Existing canonical Docker service-test description remains accurate | Comment now refers to the legacy local Postgres helper and still points operators at `scripts/docker_dev.py`. | PASS |
| No executable code or shell body changed | `git diff --word-diff origin/main...HEAD -- vars/mcdAppServicesPipeline.groovy` shows only comment wording. | PASS |

## Criterion 3 - Tests pass on the final branch

**PASS.** No executable code changed in this rebuild. The deployer re-ran the
checks that apply to the final diff:

```text
$ git diff --check origin/main...HEAD
# no output

$ rg "nix develop|dev-pg" vars/mcdAppServicesPipeline.groovy
# no matches
```

The earlier functional service-test validation for the now-superseded
implementation remains recorded on `mc-2pk5td`; this final rebuild only removes
a stale comment marker after main already absorbed the functional Docker
service-test path.

## Criterion 4 - No high-severity review findings open

**PASS.** Reviewer notes for `mc-m1xuoi` list `Findings: none blocking`; no
unresolved HIGH findings are present.

## Criterion 5 - Final branch is clean

**PASS.** After committing this gate file, the temporary deployer worktree was
checked with `git status --short --branch`.

## Criterion 6 - Branch diverges cleanly from main

**PASS.** `gh pr view 35 --repo MechaCorpsGames/mcd-jenkins-shared` reports
`mergeStateStatus: CLEAN`. Local merge simulation also passed against the
updated `origin/main`:

```text
$ git merge-tree --write-tree origin/main HEAD
e79269f5a66c7180367d8ceb48293b1ec4842bba
```

## Verdict

**GATE PASS.** Ready for human merge.
