# The proxy teardown waits for a live match, then forces after 15 minutes

Date: 2026-08-27

Status: accepted

Bead: mc-ic6h (GH-2688, GH-2687)

## Context

`stage('Deploy Proxy (if changed)')` recreates the proxy container with
`docker compose up -d --force-recreate`, preceded by `docker rm -f`. That
container is not merely a gateway: its compose command carries `-godot-binary`
and `-godot-project`, so it SPAWNS the game servers. Removing it destroys every
match running under it, not just the sockets.

Until now that teardown consulted nothing before firing.

On 2026-08-24 it destroyed a live match. The build log of `MCDServer-Main` #958
and the dropped player's net log agree to the second:

```
22:22:31  match healthy, "Unpicked cards scrapped"
22:22:33  docker rm -f          -> client sees 1006 Abnormal closure
22:22:35  image still building  -> reconnect 1/5 gets 502 Bad Gateway
22:22:43  docker compose up     -> reconnect 2/5 fires this very second
22:22:47  new proxy, no rooms   -> 401 "match no longer exists"
22:23:43  client gives up after 5 attempts
```

Both players of game `753ec597` filed a report within 41 seconds of each other.
One of them concluded the cause was a connection timeout, because from inside
the game that is what it looks like. There was no timeout involved.

The ordering half of this was already fixed. ADR
`2026-08-25-the-proxy-image-is-built-before-the-running-container-is-removed`
moved the `docker build` ahead of the `docker rm -f`, which removed a full
`--no-cache` image build from the middle of the outage. That ADR says plainly
that it does not make a deploy safe for a live match, because the recreate is
still a hard cut. This ADR is the other half.

A lease already existed for exactly this question. The proxy touches
`<version-dir>/.in-use` while a match is running on that version and refreshes it
every 5 minutes; `Cleanup Old Versions` reads it and refuses to delete a leased
version. So the pipeline already had a reliable answer to "is a match live right
now", and the teardown was the one place that did not ask.

## Decision

The teardown waits for the `.in-use` lease to clear before it fires, up to 15
minutes, and then tears down anyway.

The bound is a ruling by Tim on 2026-08-27, put to him as four options. It is
recorded here because the number is a decision, not a tuning constant, and the
rejected options are as load-bearing as the chosen one:

| Option | Verdict | Reason |
| --- | --- | --- |
| 5 minutes | rejected | Too short. A long combat sequence needs longer. It was available and was not chosen, so drifting back to it later would silently reverse a decision. |
| **15 minutes, then force** | **chosen** | Long enough for a match in progress, bounded well below the lease expiry. |
| 60 minutes | rejected | Equal to `IN_USE_LEASE_MINUTES`, so a LEAKED lease could hold a deploy for a full hour, and deploys would queue behind each other. |
| Never force (drain only) | rejected | No ceiling at all. One leaked lease blocks every deploy until a human clears it by hand. |

Three properties of the implementation are deliberate.

**The force path is loud.** It reports which version directory still held the
lease, how long it waited, and that a live match is being destroyed. A forced
teardown that reads like a normal one is how this incident became invisible the
first time, and the whole reason it took two player reports and several days to
explain.

**The wait happens outside any cross-job lock.** mc-ehn1 is adding a `flock`
around the teardown region to serialize deploys against each other. Waiting 15
minutes while holding that lock would stall every other job keyed on the same
deploy path, a promote included, and convert a live-match protection into a
fleet-wide stall. The gate therefore runs before the lock is taken: wait
unlocked, then lock for the teardown itself.

**The recovery branch is intentionally not gated.** The `else` branch of this
stage also calls `docker rm -f`, but every action in it is conditioned on the
proxy not being `Up`. The proxy is what hosts the matches, so if it is down
there is no live match to protect, and making that path wait on a stale lease
would only delay recovery of an already-dead proxy. This is pinned by a test
rather than left as a comment, so that removing the not-`Up` condition forces
the question to be answered again.

## Consequences

A deploy that lands during a live match now takes up to 15 minutes longer. That
is the intended trade: the alternative is the match dying.

A deploy that lands during a match lasting longer than 15 minutes still kills
it, and now says so in the build log instead of leaving it to be reconstructed
from two players' client logs days later.

**This gate shortens a race, it does not close one.** A match can still start
between the wait clearing and the teardown running. The window goes from "the
whole deploy" to "a few seconds", which is a large improvement and not a
guarantee. Closing it properly requires the proxy to refuse new matches while a
deploy is pending, which is a proxy-side change and is deliberately not in scope
here. It is stated in the pipeline comment as well, so nobody reads the gate as
stronger than it is.

The tests execute the real gate against a temporary directory tree rather than
matching text at it. This follows the house rule set by
`test_mcd_protocol_manifest_and_lease.py`, and it matters more here than usual: a
regex asserting the body mentions `.in-use` would pass just as happily on a gate
that never fires, and a gate that never fires is precisely the bug being fixed.
The suite was confirmed non-vacuous by reverting the pipeline change and watching
8 of the 9 tests fail. The ninth is the recovery-branch guard, which pins
pre-existing behaviour and correctly passes either way.
