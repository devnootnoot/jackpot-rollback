# Scalability phase: many relays, many limbos, no configuration

## What we measured, not guessed

Benchmarked on the real colosseum arena (voxel grid + 4652 partial boxes), not `Arena.flat`, which
is 20x cheaper and would have lied:

| what | measured | means |
|---|---|---|
| referee sim cost | **12.93 us/tick** | 77,359 sim-ticks/s on one thread |
| duels per referee thread | **~3,868** | a duel needs 20 sim-ticks/s |
| `Arena` object cost | **13.8 MB each** | 4,099,650 voxel cells, 69,216 merged solids |
| sessions per relay at `-Xmx512M` | **~37** | because every session builds its OWN Arena |

**CPU is not the wall. Memory is, and it is an un-interned object, not a physical limit.**
`RefereeManager.authorize` calls `ArenaCodec.toArena(...)` per session, so 500 duels on one map cost
6.9 GB of byte-identical immutable geometry instead of 13.8 MB.

## What already works, so we do not touch it

- **The wire already carries a per-match relay endpoint.** `MatchSetupCodec.encode(..., relayHost,
  relayPort, ...)` stamps it into every setup payload, and `EdgeAssignment` parses `relay.host` /
  `relay.port`. Core just reads a static `cfgStr("relay-host")` today. Multi-relay is a change from
  reading config to calling a chooser - NO protocol change, NO fence move.
- `Region` + `RegionService` already do GeoIP and measured ping for player routing.
- The relay already exposes Prometheus metrics and reads every port from the environment.

## Step 1 - intern the arena (do this first, it is the whole memory problem)

Cache `Arena` by arena hash in `RefereeManager`, keyed on the same hash the arena handshake already
computes. `Arena` is immutable - all fields final, and the mutable per-match part (`brokenArena`)
lives in `GameState`, not `Arena` - so one instance is safely shared by every session on that map.
Evict when no live session references the hash.

Turns ~37 sessions per relay into thousands. Roughly twenty lines, and it is the single highest
value change in this phase.

## Step 2 - relays report themselves, nothing is configured

Every relay writes a heartbeat into GLOBAL redis on a short TTL (`rollback:relay:<id>`, TTL ~15s):

    id, region, public host, public udp port, protocol fence (67/169/17321),
    live sessions, referee queue depth, capacity, cpu load

A relay that dies stops heartbeating and disappears from selection within one TTL. A relay near
capacity advertises itself as full instead of being removed, so we can tell "busy" from "gone".

**Fence in the heartbeat is not optional.** A relay on the wrong build must never be selected: every
match it carries aborts at HELLO. Selection filters on an exact fence match.

## Step 3 - choose the relay by TOTAL PATH, not by fairness

Rollback is symmetric. Each side predicts the peer, so what each player waits for is the peer's
inputs crossing the FULL path A -> R -> B, and that total is identical in both directions no matter
where R sits on it. There is no near-player advantage to balance, unlike client-server.

So the objective is:

    minimise  rtt(A, R) + rtt(R, B)      over healthy relays R with a matching fence
    tie-break by live session count      so load spreads

Consequences worth knowing:
- A relay AT one endpoint is usually optimal (one leg is ~0, total ~= the direct path). A midpoint
  only ties when it is exactly on the route, and any detour is strictly worse FOR BOTH players.
- Seattle vs Sydney: a Seattle relay and a Sydney relay are both ~85ms each way. Frankfurt is ~150ms
  each way. The bad choice hurts both equally; it never advantages one.
- Internet routing is not great-circle, so use MEASURED region-to-region RTT, not geography. Seed
  the matrix from `RegionService` and refresh it from the relays' own probes.

For an UNMODDED player the client<->edge leg is ordinary client-server and IS asymmetric, so edges
still go nearest the player. That is a separate axis from relay choice; do not conflate them.

## Step 4 - shed load loudly instead of quietly corrupting verdicts

`RefereeManager.tee` currently returns silently when `queueDepth >= MAX_QUEUE` (65,536). Under
overload the referee stops seeing input frames and simply stops producing verdicts, with no error
anywhere. That is the worst possible failure: arbitration degrades to two-witness invisibly.

Change it to shed WHOLE SESSIONS deliberately - stop refereeing a named session, log it, count it in
`rollback_relay_referee_shed_total`, and let core see that session came back unrefereed - rather
than dropping frames from every session at random.

## Step 5 - limbos

The modded client does the simulating, so limbos are near-stateless and scale horizontally by
player count. Selection is just "nearest healthy limbo in the player's region" and needs none of the
path arithmetic above. Same heartbeat shape, same fence filter.

## Verification

- A test that asserts two sessions on the same arena hash share one `Arena` instance.
- A selection test with a fixed RTT matrix asserting Seattle/Sydney picks an endpoint relay over a
  detour, and that a fence mismatch is never selected however close it is.
- A soak that authorizes N sessions on one relay and asserts heap stays flat, proving the interning.
- Watch `rollback_relay_unauthorized_total` and `rollback_relay_version_mismatch_total`; either
  moving off zero means an artifact is out of lockstep or a secret does not match.

## Order

1 (interning) is independent and ships alone. 2 and 5 share the heartbeat shape. 3 depends on 2.
4 is independent and should not wait for 3.
