# The deploy creates the lease base and the proxy owns everything below it

Date: 2026-08-28

Status: Accepted

## Context

PR #117 pointed both lease guards at the directory the proxy actually writes to.
It was right about that and it was reverted four hours later by #119, because it
also added the per-tree lease directories to the deploy's `mkdir`:

```groovy
mkdir -p ${config.deployPath}/versions ${config.deployPath}/testclient-versions \
         ${config.deployPath}/Data/GameData \
         ${config.deployPath}/leases/versions ${config.deployPath}/leases/testclient-versions
```

`MCDServer-FeatureCard` #143, #144 and #145 then failed in a row, all on commits
that had nothing to do with the lease:

```
mkdir: cannot create directory '/opt/mechacorps/feature-card/leases/versions': Permission denied
```

Every build and test stage passed. Only the deploy failed, about a second in.

### Why that mkdir cannot work

The proxy container runs as root and writes the lease through a bind mount, so
`leases/` arrives on the host owned by root. This deploy runs as an ordinary
user. It cannot create a subdirectory inside a root-owned directory, and a failed
`mkdir` fails the whole stage rather than degrading.

The asymmetry that makes the fix easy to state: **`mkdir -p` on a directory that
already exists succeeds regardless of who owns it.** Creating `leases/` is
therefore always safe. Creating anything *inside* it is never safe.

The blast radius was wider than the one environment that went red. `main`'s
`leases/versions` already existed, so that half of the `mkdir` no-opped and the
failure was masked; `leases/testclient-versions` would have failed identically on
the next real deploy. `release` and `feature-backend` pass today only because
their `leases/` does not exist yet, and they break the moment their proxy writes
a lease. That is a latent trap rather than a safe state.

## Decision

**The deploy creates `${deployPath}/leases` and stops. Everything below it is the
proxy's.**

The single `mkdir` is kept rather than dropped, and it earns its place:

- On an environment where `leases/` already exists it no-ops harmlessly.
- On one where it does not, this stage runs **before** the proxy container
  starts, so the directory is created by the deploy user rather than
  auto-created root-owned by Docker as a missing bind-mount source. That is
  strictly the better starting state.

Nothing below `leases/` could have been the deploy's job in any case: a
per-version lease directory cannot be pre-created by a deploy that does not yet
know the version name.

This depends on the proxy making what it creates traversable by the deploy user,
which is MCDClient #2855 and must land and deploy first. Pointing these guards at
a tree they cannot read changes nothing.

### What is re-landed from #117 unchanged

The read-side change was never the problem and comes back as it was:

- `live_lease()` searches `${deployPath}/leases` at `-maxdepth 3`. Depth 3 rather
  than 2 because of the `<tree>` level, which keeps `versions/` and
  `testclient-versions/` from masking each other. They number their versions
  independently.
- `prune_versions` takes a lease base per tree and looks for the marker under it.
- The `PAUSE` and `FORCED` messages use `basename "$(dirname ...)"`, so they name
  the version rather than a path.

### Keeping #117's rule against a silent no-op

#117 carried `test_the_deploy_creates_the_lease_directories` specifically to stop
anyone "fixing" this with `mkdir ... || true`, on the grounds that a lease that
silently no-ops is indistinguishable from a working one. That rule is right and
survives, restated to match where the boundary actually is:

- `test_the_deploy_creates_the_lease_base` keeps the requirement that the base is
  created.
- `test_the_deploy_does_not_mkdir_below_the_lease_base` is new and pins the line
  that broke everything. It reads the `mkdir` lines out of the stage and fails if
  any of them names a path inside `leases/`.

Neither is satisfied by `|| true`, so the original protection is intact without
the `mkdir` that caused the outage.

## Consequences

`live_lease()` and `prune_versions` will see leases for the first time once
MCDClient #2855 has deployed. Until then they behave as they do today: they find
nothing and never pause a deploy.

**The landing order is load-bearing and is not a preference.** MCDClient #2855
first, deployed, with the host directories confirmed traversable by the deploy
user. This PR second. Landing this one first reproduces the #117 outage.

The acceptance test is a deploy printing
`PAUSE proxy teardown: a match is live on <version>` while a match is live. That
line has never been printed in production. The `find` that should have printed it
searched `${deployPath}/versions`, the pre-mc-2acos location, so it matched
nothing and the 15-minute wait was skipped entirely, which is how
`MCDServer-Main` #1072 replaced the proxy under a live match on 2026-08-28
(GH #2854).

## Verification

`pytest test/unit/` locally: 416 passed. This repository has no CI, so a local
run is the only evidence available and is not a substitute for the host check
above.

The tests execute the real gate and cleanup shell bodies against temporary trees
rather than matching text at them, so what is pinned is the algorithm. Four
mutations, each attributed to distinct tests:

| mutation | result |
|----------|--------|
| restore #117's `mkdir` verbatim | `test_the_deploy_does_not_mkdir_below_the_lease_base` fails |
| drop the lease base from the `mkdir` | `test_the_deploy_creates_the_lease_base` fails |
| `live_lease()` back to `versions -maxdepth 2` (what is on `main` today) | 5 fail |
| keep the lease dir, drop to `-maxdepth 2` | 6 fail |

Two of the tests those mutations flip are new here, and they close a gap worth
naming. Every existing teardown test asserted the `PAUSE` line was **absent**;
none asserted it is ever printed. A guard that had stopped finding leases
altogether would have kept the file green, which is approximately the state
production was in. `test_the_pause_line_is_printed_and_names_the_version` pins
the positive case, and `test_a_lease_in_the_testclient_tree_also_pauses` covers
the `testclient-versions` branch of the layout, which no test had ever executed.
