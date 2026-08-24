# The native library ABI check runs on every PR, ungated

Date: 2026-08-24

Status: Accepted

## Context

MCDClient's `check-native-abi` target is the static guard for mc-h53k. From
`scripts/check_native_lib_abi.py`'s own header: every Linux Steam playtest on
the shipped build captured zero native crashes, because the engine could not
`dlopen` the Sentry GDExtension at all. `libsentry` declares a versioned
`CURL_OPENSSL_4` requirement, Steam's scout runtime pins a `libcurl` exporting
only `CURL_OPENSSL_3`, and nothing failed loudly. ADR 0138 in MCDClient shipped
the diagnosis; this script is the guard against reintroducing the condition.

No pipeline in this repository ran it. Counting every `.groovy`, `.py` and `.md`
file on `origin/main` at 1a3dd41 on 2026-08-24:

```
check-native-abi         0 hits
check-copy-dashes        0 hits
check-tags-registered    0 hits
---- for contrast ----
check-res-case           1 hit    (stage 'Resource Path Case Check')
check-authoring-refs     1 hit    (stage 'Authoring Data Reference Check')
check-adr-ids            3 hits   (stage 'ADR Identifier Gate')
```

`check-native-abi` is in MCDClient's `precommit` chain (`Makefile:326`), and
MCDClient's CLAUDE.md states plainly that no git hook is auto-installed and that
running `precommit` is a developer-driven, opt-in invocation. So it ran when
somebody remembered.

This guard's failure mode is the reason that matters. It does not break a build.
It deletes evidence, silently, and the deletion is invisible until a player
crashes and no report ever arrives. Three native-crash beads were open when this
was written (mc-adi8, mc-snmx, mc-j1hl) whose common problem is missing crash
evidence.

The condition is currently clean. Run by hand on 2026-08-24:
`check_native_lib_abi: 8 bundled binaries OK against Steam Linux Runtime 3.0
(sniper)`, exit 0, 0.059s. This is insurance, not a fire.

## Decision

`mcdPRValidationPipeline.groovy` gains a `Native Library ABI Check` stage,
placed immediately after `ADR Identifier Gate` and before `Go Lint`, so it runs
ahead of `Setup Dependencies` and every build.

**It is not gated on `CLIENT_CHANGED`, and that is the load-bearing decision
here.**

The easy version of this argument would be the one `ADR Identifier Gate` makes
for itself: that the gate would skip the very PRs it polices. That argument does
not apply. `mcdChangeDetection` routes both `addons/` and `scripts/` to
`'client'`, and `check_native_lib_abi.py` scans `addons/sentry/bin/linux`,
`addons/godotsteam/linux64` and `addons/godotsteam/linux32`. Both inputs to the
comparison are in the client bucket, so a `CLIENT_CHANGED` gate would in fact
fire on a `.so` swap as things stand today. It was checked rather than assumed.

The reason to leave it ungated is different. The check reads committed ELF
headers in pure Python with no dependencies and no prerequisite build, and it
measured 0.059s. A gate therefore saves nothing measurable, and in exchange it
would make this guard's firing depend on a second file, in a different
directory, continuing to route `addons/` to `'client'`. That coupling is real
enough that `test_mcd_testclient_test_stage.py` carries a dedicated test for it
(`test_testclient_paths_route_to_the_gating_category`).

A control whose failure mode is silence should not be one edit in an unrelated
file away from never running again. That is the exact state this change exists
to end, and paying for it with 0.059s per PR is not a trade worth making.

The stage carries the branch-skew probe used by `ADR Identifier Gate` and
`Script Tests`: `make -n check-native-abi` decides whether to skip, and the skip
says so out loud. Unlike those two, the probe is a formality today. The target
and the script are present on all four MCDClient branches this library serves,
verified at `main` b87fc6c1, `release` a15c5546, `features/backend` 65ea8d4d and
`features/card` 6677e99b. It is kept because the branch set is not fixed and the
cost of being wrong later is a red PR on a branch whose change had nothing to do
with native libraries.

The probe is a skip, never a swallow. There is no `|| true`, no `catchError`, no
shebang. Without a shebang Jenkins runs the body as `sh -xe`, so a target that
exists and fails still fails the build. This is the property the whole defect
class is about, and wiring a gate that cannot go red would reproduce the defect
it was written to fix.

`test/unit/test_mcd_native_abi_gate.py` pins all of it, in the shape of
`test_mcd_testclient_test_stage.py`. Each of its twelve tests was checked
against a deliberately broken copy of the stage before being committed: deleting
the stage, adding `|| true`, wrapping in `catchError`, adding a
`CLIENT_CHANGED` gate, removing the probe, moving the stage after the builds,
and adding a shebang without `set -e` each turn the intended test red and leave
the rest green.

## Scope

This wires `check-native-abi` only. `check-copy-dashes` and
`check-tags-registered` were found unwired in the same sweep and are left alone
deliberately, per Tim's call on 2026-08-24.

They are not equivalent work, and it is worth recording why, because the natural
assumption is that all three are the same three lines.

`check-copy-dashes` would be: it is in `precommit` alongside `check-native-abi`
(`Makefile:326`) and would take the same stage shape.

`check-tags-registered` would not. It is defined at `Makefile:228` and is absent
from the `precommit` line, so unlike the other two it has no invocation anywhere
at all. Wiring it means first establishing that it passes on current main, which
this change did not do.

## Alternatives considered

**Gate it on `CLIENT_CHANGED`, consistent with `Resource Path Case Check` and
`Authoring Data Reference Check`.** Consistency is the real argument for this,
and it was rejected on the coupling above. Those two stages also differ in a way
that is easy to miss: they guard against a `.gd`/`.tscn` edit made in the same
PR, so their inputs and their gate move together. This check compares a
committed binary against a hard-coded runtime table, and its value comes from
running on PRs that were not thinking about native libraries at all.

**Use a file-existence probe (`[ -f scripts/check_native_lib_abi.py ]`) like
`Resource Path Case Check`.** Equivalent in effect here, and rejected for
uniformity with the two most recent stages: `make -n` probes the thing actually
invoked, so it cannot pass while the target is missing or misspelled.

**Leave it in `precommit` and document it harder.** This is the status quo. It
has been the status quo since the target was added, and the count above is what
it produced.
