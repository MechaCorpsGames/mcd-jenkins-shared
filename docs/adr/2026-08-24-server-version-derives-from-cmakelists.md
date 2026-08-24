# The server pipeline derives MAJOR.MINOR from CMakeLists instead of stamping its own

Date: 2026-08-24

Status: Accepted

## Context

From this library's initial commit, `mcdServerPipeline.groovy` set
`env.SERVER_VERSION = "0.1.${BUILD_NUMBER}"` — a private constant. The
MCDClient repo's `Src/GameServer/CMakeLists.txt` composes the version the
build actually ships: `PROJECT_VERSION = MAJOR.MINOR.BUILD` (its own comment:
"Developers control MAJOR.MINOR, CI/CD controls BUILD"), installs the binary
under `versions/v${PROJECT_VERSION}/`, and writes `latest.txt` from it.

The two sources never agreed. CMakeLists said 1.0.x when the pipeline was
written and 0.2.x after MCDClient `77d59bbc`; the pipeline said 0.1.x
throughout. So every human- and tool-facing string this pipeline emits — the
build displayName, the server manifest's `serverVersion`, the Discord deploy
message, the webhook version field — disagreed with the binary, its deploy
directory, and the symbol-upload paths, which all carry CMake's version.

Found via mc-bs84: build #873's artifacts lived at `v0.2.873` while its
reporting said `v0.1.873`. Filed as mc-glpn. The sharp consequences:
release-string ordering (Sentry included) ranks every current build below
older ones; "which build is deployed" tooling reads the manifest and gets a
version that names nothing on disk; and the documented developer knob
(VERSION_MINOR in CMakeLists) changed the C++ define but nothing that
reported.

## Decision

1. The `"0.1.${BUILD_NUMBER}"` literal is gone. `Setup Build Info` sets a
   provisional versionless displayName — the repo is not on disk yet in that
   stage.
2. The `Checkout` stage, after `checkout scm`, derives MAJOR and MINOR from
   `Src/GameServer/CMakeLists.txt` (anchored greps on the `set(` lines,
   digits only), composes `SERVER_VERSION = MAJOR.MINOR.BUILD_NUMBER`, and
   writes the full displayName. A failed parse calls `error()` and refuses to
   stamp a made-up version: a silent fallback is the two-sources bug with
   extra steps.
3. `Verify Build` asserts `latest.txt` — written by CMake from its own
   `PROJECT_VERSION` — equals `v${SERVER_VERSION}/MCDServer`. If the sources
   ever split again (a changed file shape, a hand-passed `-DVERSION_BUILD`),
   the build fails at Verify with the mc-glpn diagnosis instead of shipping a
   two-faced version.

The derivation shell is executed by
`test/unit/test_mcd_server_version_single_source.py` against the real file
shape and against a file missing the lines; this repo has no CI, so pytest is
the gate.

## Alternatives rejected

- **Bump the pipeline constant to 2.** Reproduces the same bug with the
  numbers agreeing by coincidence until the next CMakeLists edit. The bead
  forbade it explicitly.
- **Default to 0.0 (or the old 0.1) when the parse fails.** A version that
  names nothing is worse than a failed build; the guard refuses instead.
- **Fix the client and Go-service pipelines in the same change.**
  `mcdClientPipeline.groovy`, `mcdAppServicesPipeline.groovy` and
  `mcdDiscordBotPipeline.groovy` carry the same `"0.1.${BUILD_NUMBER}"`
  pattern, but only the server has CMakeLists as an established second
  source. The Go services have no other version source — for them the
  pipeline literal IS the single source, which is a different (possibly
  fine) situation. The client's source of truth (project settings?) is a
  separate decision; flagged in mc-glpn's close-out for routing, not
  bundled here.

## Consequences

- MCDServer-Main and MCDServer-Release report the checked-out branch's own
  MAJOR.MINOR automatically; bumping `VERSION_MINOR` in CMakeLists now
  changes what deploys and reports, as its comment promises.
- The next MCDServer build after this merges reports 0.2.x, restoring
  monotonic ordering against the v0.2.x artifact paths that always existed.
  Historical display names (v0.1.x) stay wrong forever; the manifest of the
  next deploy supersedes them.
- The Verify drift check makes any future divergence a loud build failure
  rather than a quiet inconsistency.
