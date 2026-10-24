# OpenSSL development files in the Jenkins build-agent image

**Verdict:** **PASS**

Evaluated 2026-10-24T16:43:52.577580+00:00.

Deploy bead: mc-p909qw. Review bead: mc-53w2qo. Source build: mc-3u0khs.

## Checklist

| # | Result | Evidence |
|---|---|---|
| 1 | PASS | Reviewer verdict is pass; style/security have no findings. The active policy requires one reviewer pass. No review carryover or reviewer-added commit. |
| 2 | PASS | Only libssl-dev is added to the existing apt package list. A complete build-agent image build and an independent OpenSSL compile/link/run smoke passed. The documented GCC, Go, Godot and Java tools passed their version checks. The source prerequisite remains OPEN pending landing and Jenkins rebuild/rollout. |
| 3 | PASS | Full repository unit tree: 527 collected, 525 PASS, 0 FAIL, 0 ERROR, 2 unrelated environment SKIPs; exit 0, 13.29 seconds. Terminal detached output and JUnit were both read and reconciled. |
| 4 | PASS | No unresolved HIGH review findings. |
| 5 | PASS | The reviewed source and tested merge scratch were clean. The isolated deploy branch contains the reviewed change plus this gate-record commit; final status and the gate-only child diff are verified before push. |
| 6 | PASS | The clean merge against the recorded gate base was materialized and its complete Dockerfile image built successfully. The resulting image passed the OpenSSL smoke. |
| 7 | PASS | One feature theme: provide the OpenSSL development package needed to configure and link the Jev TestClient. |

## Build and regression evidence

The deploy rebuilt the full Dockerfile as mcd-build-agent-mc-p909qw:gate. The image has libssl-dev 3.0.22-1~deb12u1, /usr/include/openssl/ssl.h, and the libssl.so/libcrypto.so link files. A program using SSL_CTX_new(TLS_method()) compiled, linked with -lssl -lcrypto, ran successfully, and reported OpenSSL 3.0.22. Tool checks reported GCC 12.2.0, Go 1.24.0, Godot 4.6 and Java 17.0.20.1.

Retained logs: c6-image-build.log and c6-image-smoke.log in the artifact directory. c6-image-smoke-initial.log retains an initial check-script error: pkg-config was assumed present and exited 127. The corrected smoke uses the compiler and linker directly; the image was unchanged.

Audited builder evidence is separate from the deploy's image build/smoke: Dockerfile.red and Dockerfile.green match the git revisions by SHA-256. The original image failed TestClient configuration for missing OpenSSL (exit 2); the changed image found OpenSSL 3.0.22 and passed 370 tests from 65 suites (exit 0). The proxy image's ldd output resolves both libssl.so.3 and libcrypto.so.3. This is retained builder native-consumer evidence, not a newly repeated native-consumer run by the deployer.

## Test policy

test_cmd: python3 -m pytest test/unit/ -v -ra --junitxml=/home/jaword/projects/gc-management/.gc/artifacts/MCDClient/mc-p909qw/full-unit.xml
test_cmd_scope: full-suite
test_counts: 525 PASS, 0 FAIL, 0 ERROR, 2 SKIP (527 collected; 13.29 seconds; exit 0)
diff_tests_executed: none (the diff modifies no test or test helper)
test_log_dir: none (pytest writes one retained stdout/stderr pair and JUnit XML)
failure_attribution: none
waiver_ref: none
heavy_mode: none (classifier output retained in heavy-classify.txt)
policy_lane: none — no policy/lint target is defined in this repository; whitespace and bash syntax checks passed in a separate fresh merge-tree view (fast-lane.log).
drift_lane: none — no generated-file drift lane or CI workflow exists in this repository.
ci_lane_run: n/a — no CI job, matrix, timeout or required-check list changes; the package installation is verified by the full image build and smoke above.

## Deployment boundary

This repository has no CI. Local image validation does not update Jenkins. After merge, the named operator must run docker/build-agent/build.sh from the merged checkout on the Jenkins machine to rebuild mcd-build-agent:latest; the rebuild/rollout owner is still pending with mayor/operator. mc-3u0khs stays OPEN until both landing and Jenkins image rebuild/rollout evidence exist, and continues to block mc-u3kmik.

The session stays at the gc-provisioned MCDClient/deployer path. The shared-repository checkout was explicitly provisioned and authorized by the mayor (gm-wisp-liffqfk). The wrapper reports PASS-THROUGH for this repository: commands used their ambient environment in clean scratch/provisioned trees; no stripped-environment isolation is claimed.

Upstream main advanced while the gate was running. This gate certifies the recorded merge base, not the newer main tip; the merge authority must validate the actual merge result. No rebase or branch repoint is performed by the deployer. No automatic Jenkins validation covers that merge.

## Pinned execution record

reviewed_commit: 947c4bbfeb2d4d50673db293443ddbd03bcbb868
gate_base_sha: 18c00faeb1357100525e0da1d828cff770557c7c
gate_base_kind: current-tip
merge_state: clean (origin/main@18c00faeb1357100525e0da1d828cff770557c7c)
merge_validation: authorized mayor/merge authority validates the current merge result; no automatic CI guarantee exists for this repository
merge_scratch_commit: 3aabfbd701eae5481e507d146f6b704ce798f79e
tested_tree: ada078695d682481c634bc2fcdb6590c20a0edfa
current_origin_main_observed_after_tests: 7b17a668c59e6cf7e1d57c62b34e90d7e62689bb
deploy_mode: remote
deploy_branch: deploy/mc-p909qw-gate
stack: none
ancestry_scope: PASS, accepted IDs mc-p909qw and confirmed source build mc-3u0khs; one source commit, no agent-configuration paths
criterion3_run_dir: /var/tmp/gc-heavy-gate/runs/mc-p909qw.c3
criterion3_run: complete, rc=0; systemd service inactive/dead with MainPID=0
artifact_dir: /home/jaword/projects/gc-management/.gc/artifacts/MCDClient/mc-p909qw
load_threshold: 15
load_waited_seconds: 1800
load_wait_timed_out: true
run_start_load: 96.09
run_max_load: NA
run_mean_load: NA
run_readings: 0
load_record: threshold=15 waited_seconds=1800 wait_timed_out=1 run_start_load=96.09 run_max_load=NA run_mean_load=NA run_readings=0 wait_first_load=34.89 wait_max_load=96.09 wait_mean_load=68.35 wait_readings=62 read_errors=0 prewait_s=1800
admission: local-heavy, floor_gib=40, free_gib=41.420875549316406, cap=3, slot_waited_seconds=0.001, prewait_s=1800.684

The ordinary load wait reached its bound and proceeded under the documented soft-timeout policy; no load threshold or admission control was changed. No in-run load samples were recorded, so maximum/mean load remain NA. The unit suite completed with no failures.

skip_justification: test/unit/test_mcd_change_base_executes.py::test_mcd_change_base_behaves_when_executed and test/unit/test_mcd_port_sweep_executes.py::test_the_sweep_behaves_when_executed require a Groovy runtime absent from PATH/MCD_GROOVY/GROOVY_HOME. Their decorators and function bodies are unchanged, and neither helper/test reads the Dockerfile or OpenSSL package. The retained base preflight also reports 525 PASS and the same 2 SKIPs. These skips provide no executed Groovy behavior coverage; they do not exercise or depend on this package-list-only change. No diff-owned test was skipped.

The final gate-record commit adds only this Markdown file above the exact reviewed source; it changes no executable input to the completed gate. The primary checkout, builder checkouts and mayor review worktrees remain untouched.
