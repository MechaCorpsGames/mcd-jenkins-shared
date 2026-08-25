# The deploy publishes a protocol manifest, and cleanup honors an expiring in-use lease

Date: 2026-08-24

Status: Accepted

## Context

mc-cdjn approved protocol back-compat by proxy version-routing: when a client
connects with an older protocol than the live server, route it to a deployed
server binary that still speaks that protocol instead of failing the
connection. The deploy host already keeps the newest five of each versioned
tree, so the binaries needed are usually already there. Two things on the
deploy side were missing before the proxy (mc-epfh) could use them.

First, nothing on the host recorded which protocol a given binary speaks.
`mcdServerPipeline.groovy` computes `PROTOCOL_VERSION` in `Generate Server
Manifest` by grepping `Src/Include/protocol_ext.h`, and writes it into
`artifacts/server/manifest.json`, which is archived in Jenkins and never
travels to the deploy host. `versions/v0.2.51/` therefore holds a binary and
no statement of what it can talk to.

Second, `Cleanup Old Versions` ran `ls -dt v*/ | tail -n +6 | xargs -r rm -rf`
against both `versions/` and `testclient-versions/`. Keeping the newest five
is safe only while exactly one version is ever in use. Under version-routing,
a version past the newest five can have a match running on it, so a deploy of
an unrelated change could delete a server binary out from under live players.
mc-cdjn names this as risk 3.

## Decision

**1. The deploy writes `protocol.txt` beside each binary.** The `Deploy
GameServer & TestClient` stage writes `env.PROTOCOL_VERSION` into
`bin/versions/<v>/protocol.txt` and `bin/testclient-versions/<v>/protocol.txt`
in the workspace, and the existing `rsync` of those trees carries the files to
the deploy host. mc-cdjn left "manifest vs. probe" open and recommended the
manifest; this takes the manifest. The proxy never executes a binary to learn
its protocol, which would mean starting up to five server processes at proxy
startup purely to interrogate them.

Writing into the workspace rather than poking the deploy path directly means
the binary and the description of its protocol are delivered by one operation
and cannot arrive apart. The version directory is derived from `latest.txt`,
the same source `Verify Build` already pins the binary path against (mc-glpn),
so the manifest cannot land beside a version other than the one deployed.
TestClient gets the same file because the bots are version-routed too
(mc-cdjn Phase 2) and are built from the same tree, so they speak the same
protocol.

**2. The `PROTOCOL_VERSION` read no longer falls back.** The grep carried
`|| echo '1'`, so an unreadable or moved header produced the valid-looking
protocol 1. That was inert while the value only decorated an archived
manifest. It is not inert once the number decides which binary a player
connects to: every back-compat connection would be matched against a protocol
no deployed server speaks, and the symptom would appear as connection failures
far from the pipeline that caused them. The stage now calls `error()`.

**3. Cleanup deletes a version only if it is BOTH beyond keep-5 AND unleased.**
The keep-5 count stays as a safety cap so the host cannot fill with binaries
if the lease logic misbehaves; the lease is the correctness fix.

**4. The lease is an expiring lease, not a flag.** The contract, shared
verbatim with mc-epfh:

    path       <version-dir>/.in-use
    created    by the proxy when a match starts on that version
    refreshed  touched at least every 5 minutes while a match is live
    removed    when the last match on that version ends

Cleanup skips a version whose marker was touched inside 60 minutes, twelve
refresh intervals.

## Alternatives considered

**A plain flag file with no expiry**, which is the literal reading of mc-mhjd
and of mc-cdjn risk 3, and is the smaller change. Rejected: it is unsafe in
the other direction. A proxy that dies mid-match leaves the flag behind, and
because versions only accumulate on this host, that version is exempt from
retention forever and the disk cost grows with every later deploy, with
nothing reporting it. The cost of the expiring version is that the proxy must
touch the file periodically rather than once. That obligation is written into
the contract above and into mc-cdjn, and mc-epfh has not started, so it costs
that bead nothing to adopt.

**Deriving retention from the Steam catch-up signal**, per the mayor's
reconciliation on mc-cdjn (back-compat for a protocol is only needed until the
matching client is downloadable). That is a better long-run retention policy,
but it depends on the Steam signal bead (mc-fxt7), which has not landed. The
lease is orthogonal to it and correct under either policy: whatever decides a
version is old enough to remove, it must still not remove one with a match on
it.

**Leaving the `|| echo '1'` fallback alone** as out of scope. Rejected because
this change is precisely what promotes that value from decoration to a routing
input.

## Consequences

* mc-epfh is unblocked and must write and refresh `.in-use` as specified. If
  it does not refresh, a match running longer than 60 minutes loses its
  protection, so the refresh is load-bearing rather than an optimization.
* `.in-use` is now a cross-repo contract held together by a string, in shell
  in this repo and in Go in MCDClient. A test pins it here, and mc-cdjn
  carries it for the proxy side. Renaming it on one side only is silent in
  the dangerous direction: the guard stops matching, retention behaves as it
  did before, and the first symptom is a deleted live match.
* A build whose `protocol_ext.h` cannot be parsed now fails instead of
  deploying. This is intended, and it can turn a previously green build red if
  the header ever moves.
* Retention can now exceed five versions per tree while matches are live. That
  is bounded by concurrent matches on distinct old versions, which the routing
  window makes small, but it is no longer a hard cap.
* This repo has no CI, so local `pytest test/` is the only evidence for any of
  it. 289 tests pass, 12 of them new.
