// vars/mcdStripAuthoringData.groovy
//
// Shared "Strip Authoring Data" step for the Validated Card Data Pipeline
// (MCDClient bead mc-0xm, plan docs/VALIDATED_DATA_PIPELINE_PLAN.md §5,
// Scenario 3 — hermetic build source isolation).
//
// Relocates the AUTHORING card data (Data/Cards + Data/References) out of the
// workspace via `make strip-authoring-data`, so every stage that runs after it
// (tests, packaging, artifact staging) proves nothing depends on authoring
// data: only the generated, DONE-filtered, validated Data/GameData/ remains
// reachable. A `.authoring-data-stripped` marker in the workspace records the
// relocation destination; MCDClient's export_done_cards.py no-ops loudly on it
// (MCDCoreExt builds auto-run the export after install), and the
// export-consistency GDScript test skips its source-side comparisons.
//
// Ordering contract: this MUST run AFTER the export ("Populate GameData" /
// MCDCoreExt install) and AFTER "Validate GameData", and BEFORE tests +
// packaging. It is the "environment" enforcement point of the pipeline's
// invariant (plan §1), on top of the editor gate (Scenario 1) and the build
// validation gate (Scenario 2).
//
// The build intentionally finishes with the workspace stripped; the next
// build's `checkout scm` restores the tracked authoring dirs. To undo locally:
// `make restore-authoring-data` (the marker records where the data went).
//
// Both steps are hard failures: a branch only arms this stage
// (config.stripAuthoringData) once its test suites read generated data
// exclusively, so any failure here is a real regression — either in the strip
// mechanism itself (self-test) or a workspace in an unexpected state.
//
// Usage from a pipeline stage:
//   steps { mcdStripAuthoringData() }

def call(Map args = [:]) {
    // (1) Gate-mechanism self-test — hermetic sandbox fixtures, never touches
    // the real workspace data. A broken strip/restore always fails loudly
    // before we mutate the actual workspace.
    sh 'make test-strip-gate'

    // (2) Relocate Data/Cards + Data/References out of the workspace.
    sh 'make strip-authoring-data'

    echo '[Strip Authoring Data] workspace is hermetic — every stage below ' +
         'runs against generated Data/GameData/ only.'
}
