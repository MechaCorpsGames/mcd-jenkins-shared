# The proxy image is built before the running container is removed

Date: 2026-08-25

Status: Accepted

## Context

`Deploy Proxy (if changed)` in `mcdServerPipeline.groovy` ran three commands in
one `sh` block, in this order:

```
docker rm -f mcd-main-proxy-1     # the container currently serving players
docker build --no-cache ...       # a full from-scratch image build
docker compose up -d --force-recreate --no-build proxy
```

The container was destroyed first and its replacement image was built second.
Nothing served for the length of that build, and `--no-cache` guarantees it is
a real build (apt-get, go build) rather than a cache hit.

On 2026-08-24 this destroyed a live match. Bead mc-ic6h, reported as GH-2688
and GH-2687 (both players of game `753ec597` filed within 41 seconds). The
build log of `MCDServer-Main` #958 and the reporting player's net log (bundle
`1b5d3cc3`) agree to the second:

```
22:22:31  match healthy, "Unpicked cards scrapped"
22:22:33  docker rm -f          -> client sees 1006 Abnormal closure
22:22:35  image still building  -> reconnect 1/5 gets 502 Bad Gateway
22:22:43  docker compose up     -> reconnect 2/5 fires this very second
22:22:47  new proxy, no rooms   -> 401 "match no longer exists"
22:23:43  client gives up after 5 attempts
```

The 502 window is exactly the image build. A 502 means the gateway had no
backend at all, which is precisely true between the `rm -f` and the `up`.

Two details make this worse than a gateway blip. First, the proxy container
also spawns the game server processes (`-godot-binary` and `-godot-project` in
its compose cmd), so removing it kills the matches themselves, not just the
sockets. Second, `MCDServer-Main` #959 repeated the whole teardown at 22:23:29,
46 seconds later.

An earlier reading of this incident timed the deploy from the compose up and
concluded the restart landed ten seconds after the disconnect, which made the
deploy look innocent. The causal command is the `docker rm -f` at the start of
the stage, not the recreate at the end.

## Decision

Build the image first, then remove and recreate.

The whole teardown group moves after `docker build`: the legacy `systemctl`
stop, the loop that clears foreign containers off our port, and the `docker rm
-f` of our own container. None of it is needed before the image exists, and the
build needs neither the port nor the container name, so it is safe to run while
the old proxy is still serving.

This is deliberately scoped to the ordering and nothing else.

### What this does not do

It does not make a deploy safe for live matches. The `--force-recreate` is
still a hard cut: a client still sees 1006 and then 401, and the match is still
lost. What changes is the size of the window, from a full `--no-cache` image
build (ten seconds, measured) down to a container recreate.

Closing the window needs a policy decision that is not ours to make here. The
proxy already writes a `.in-use` lease per version (`Src/Proxy/lease.go`,
acquired at match start and released at match end) and the `Cleanup Old
Versions` stage in this same file already honours it. Teaching the deploy to
defer while that lease is fresh is the real fix, but the bound is an ops
tradeoff between deploy velocity and killing a live match, and it is being put
to the repository owner separately. Note that `IN_USE_LEASE_MINUTES` is 60
because refusing to delete a binary costs nothing; a deploy gate that waits an
hour would be its own outage, so it must not reuse that number.

Restoring a room from the client's reconnect token was considered and rejected
for now. Because `rm -f` takes the gateway and the match processes together,
there is nothing left to restore into until match state lives outside the
container.

### Relationship to mc-ehn1

mc-ehn1 (six jobs across three pipelines share `/var/opt/mechacorpsgames` with
no cross-job lock) is visible in this incident and is a real bug, but it is a
separate one. The ordering defect destroys live matches on a single clean
deploy even with a perfect cross-job lock. Fixing mc-ehn1 does not fix this,
and this does not fix mc-ehn1.

## Consequences

The outage on a proxy deploy shrinks to the recreate itself. Deploys otherwise
behave identically: same image, same provenance gate, same marker write.

The ordering is pinned by
`test/unit/test_proxy_image_builds_before_teardown.py`, which is structural
because both commands are present either way and only their relative position
encodes the fix. It strips whole-line shell comments first, since the comment
added alongside this change names both commands in prose.

The ordering was also verified by execution rather than by reading: the `sh`
block was rendered from the groovy with its interpolations substituted, run
against stubbed `docker` and `sudo` on `PATH`, and the recorded call order
compared against the same rendering of the pre-change source. The old source
records `RM, BUILD, UP` and the new one records `BUILD, RM, UP`.
