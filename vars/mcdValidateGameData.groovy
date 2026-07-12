// vars/mcdValidateGameData.groovy
//
// Shared "Validate GameData" step for the Validated Card Data Pipeline
// (MCDClient bead mc-8ko, plan docs/VALIDATED_DATA_PIPELINE_PLAN.md §4, Scenario 2).
//
// Runs the standalone Src/Validation binary over the *generated* Data/GameData/
// tree via `make validate-gamedata`. That binary is the same compiled engine as
// the in-editor card validator, so CI enforces exactly the editor's rules on the
// generated output — no reimplementation, parity by construction (plan §2).
//
// Ordering contract: this MUST run AFTER card data is generated (the server
// pipeline's "Populate GameData" stage, or the client pipeline's MCDCoreExt
// build which exports transitively) and BEFORE tests + packaging. It is the
// "validate" half of the fixed generate -> validate -> package order (plan §4.4).
//
// Rollout control — the live corpus still legitimately carries errored-DONE
// cards until a separate cleanup pass lands (plan §7 "Corpus cleanup"), so the
// gate is introduced soft and flipped hard per-branch via config:
//   hardFail == true   a validation error FAILS the build (the plan's end state).
//   hardFail == false  a validation error marks the build UNSTABLE and continues
//                      (visibility without blocking a shared integration branch).
//
// The gate *mechanism* self-test (`make test-validate-gate`) always runs as a
// hard check: it is corpus-independent (hermetic fixtures), so a clean corpus can
// never mask a broken gate, and a broken gate always fails loudly.
//
// Usage from a pipeline stage:
//   steps { mcdValidateGameData(hardFail: false) }

def call(Map args = [:]) {
    boolean hardFail = args.get('hardFail', false)

    // (1) Gate-mechanism self-test — hermetic, corpus-independent, always hard.
    sh 'make test-validate-gate'

    // (2) The real gate over the generated Data/GameData/ tree.
    int status = sh(script: 'make validate-gamedata', returnStatus: true)

    if (status == 0) {
        echo '[Validate GameData] PASS — generated card data is validation-clean.'
        return
    }

    if (hardFail) {
        error("[Validate GameData] FAIL — generated card data has validation " +
              "errors (validator exit ${status}).")
    }

    echo "[Validate GameData] validation errors found (validator exit ${status}) — " +
         "SOFT gate: marking build UNSTABLE, not blocking."
    echo '[Validate GameData] This flips to a blocking gate once the card corpus ' +
         'is clean (plan §7); pass validateGameDataHardFail: true to enforce.'
    catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
        error("Validate GameData soft failure (validator exit ${status})")
    }
}
