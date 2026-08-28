# Architecture

Read this first. It describes the finished system: what runs where, who is allowed to decide what, and
the two version numbers that stop a mismatched build from ever reaching a match.

`EDGE-PROTOCOL.md` is the detailed contract for the brokering and result paths. `DEPLOYMENT.md` is how
the pieces are shipped and operated. `NETCODE.md` is the runtime topology, ports and Redis keyspace.
This document is the map those three hang off.

---

## 1. The one sentence

Two players run **one** deterministic simulation in lockstep over UDP, each on their own machine, and
every difference between the two copies of that simulation is a desync.

Everything below exists either to keep the two copies identical or to decide what happens when they
stop being identical.

---

## 2. Modules

| Module | What it is | Where it runs |
| --- | --- | --- |
| `sim-core` | The deterministic simulation, plus the wire `Protocol` and `DirectLink`. Pure JVM: no Bukkit, no Minecraft, no rendering. | Inside every other module |
| `sim-host` | Drives `sim-core` over the wire: prediction, rollback, confirmation, `MatchDriver` lifecycle | Edge plugin and client mod |
| `edge` | Paper plugin (`JackpotEdge`) that hosts a vanilla player's side of a duel and mirrors the sim back into the real world | Edge Paper servers |
| `relay` | Headless UDP forwarder that pairs the two peers of a session | Relay containers, one per region |
| `limbo` | Minimal server that holds a player while an arena is prepared | Limbo containers |
| `pvphq-rollback-mod` | Fabric client mod (separate repo). Hosts the sim on the player's own machine and renders it | Player machines |
| `mcleagues-core` | The broker. Decides a duel is a rollback duel, prepares the arena, allocates the session, arbitrates the result | Game servers |

---

## 3. Two hosts, one sim

A match always has exactly two peers. Each peer is one of two kinds, and the kind is decided per
player, not per match.

### Mod-hosted

The player has `pvphq-rollback-mod` and its reported version equals `RollbackModRegistry.EXPECTED_VERSION`.
The sim runs on the player's own machine. Their input is read from their own keyboard and their own
movement is produced by the sim, so the sim is the authority on where they are. This is the original
path and the one a ranked modded duel takes.

A player machine is behind NAT and cannot be dialled, which is why the relay exists and is not going
away.

### Edge-hosted

The player has no accepted mod. `RollbackHandoffManager.awaitPlayersThenStart` finds the mod gate
failed for **both** players, and with `rollback.edge.enabled` on it calls `tryEdgeHandoff` instead of
aborting. Core brokers a session, sends each player to an edge Paper server, and the `JackpotEdge`
plugin on that server runs that player's half of the sim.

The player is a normal vanilla client connected to a normal Paper server. They see a real world
because the edge **mirrors** the sim into it every tick: entity positions, held items, armour,
inventories, block changes, particles, sounds, titles. The sim is not rendered by the client, it is
re-staged for the client.

One consequence runs through the whole edge design: a vanilla client sends its own position and the
server cannot ask it to stop. So on an edge, **movement is client-authoritative** and is stamped into
`Input.Authority` rather than produced by the sim. `EdgeMovementValidator` clamps it (per-tick ceiling,
sustained rate with a burst credit, divergence correction, a violation threshold) so the authority
stream stays inside physically plausible bounds, but the sim on both peers consumes the same stamped
authority and therefore stays identical. Authority is a determinism device, not a trust decision.

### What is not wired yet

A modded player versus an edge-hosted player. The design supports it (the edge falls back to the relay
whenever the opponent is `hostKind=MOD`), but today's gate in `awaitPlayersThenStart` only takes the
edge path when **neither** player passes the mod gate. A mixed pair still aborts through
`abortForModGate`.

---

## 4. The broker

`mcleagues-core` is the only party that decides anything about a match before it starts, and the only
party that decides anything about it after it ends. The edges decide nothing except what the sim says.

### Allocation

1. `RollbackHandoffManager.tryEdgeHandoff` prepares the arena and the cage, then calls
   `EdgeSessionBroker.assign`.
2. `EdgeRegistry.live()` reads `rollback:edge:servers` off **global** Redis and drops any entry whose
   heartbeat is older than 30s. `pickFor` prefers an edge in the player's own region, then the nearest
   region with a live edge, then anything.
3. A random `sessionId` is generated, and with it the two 32-byte slot tokens - derived, not random,
   as `HMAC-SHA256(rollback.relay-slot-secret, "rollback-slot-v1" || sessionId || slot)`. A relay
   holding the same secret recomputes them, so it can refuse a slot bind it did not authorise without
   any connection back to core and without letting whoever arrives first own the slot. With no secret
   configured the tokens fall back to random and the relay falls back to trust on first use, and both
   sides warn at startup. If direct links are enabled and both chosen edges advertise a direct
   endpoint, one shared 32-byte link token is generated too.
4. Two assignments are pushed to `rollback:edge:assign:<edgeId>`, one per slot. The assignment pins
   every determinism-critical value: spawns, ground-y, rounds, the arena key, the setup (kit) bytes,
   the cage bytes, the relay endpoint or the peer endpoint. Nothing determinism-critical is read from
   an edge's own `config.yml`.
5. Both players are sent to their edge by server name via `PlayerSendToServerMessage`.

### Arbitration

Each edge reports its own view of the outcome to `rollback:edge:results` with a cause
(`FINISHED`, `LOCAL_QUIT`, `PEER_GONE`, `DESYNC`, `SELF_FAULT`, `ARENA_MISMATCH`, `NO_FRAME_ZERO`,
`PEER_NEVER_ARRIVED`, `LOCAL_FORFEIT`). Core drains that list and applies the corroboration rule: an
edge may **concede** a match unilaterally but may not **claim** one. Two self-claims void. A lone
`PEER_GONE` is armed and arbitrated 5s later against the global player directory. Section 5 and 6 of
`EDGE-PROTOCOL.md` are the full statement of this, including the bounded 31s worst case.

---

## 5. Arena and cage flow

This is the part that takes the wall-clock time between "match found" and the first simulated frame.

1. **Extract.** Core loads the real arena schematic, extends the border radius by
   `rollback.arena-play-margin`, encodes the region into sim arena bytes and caches it. Sim arenas are
   large: a colosseum is around 148k blocks, a courtyard around 2.1M.
2. **Publish.** The bytes are written to one global Redis key. Both slots are told **the same key**, so
   the two edges cannot load different arenas. An edge that cannot fetch the key refuses the match
   rather than falling back to its local `arena.bin`, because falling back would be a guaranteed
   desync.
3. **Paste.** Each edge pastes the arena into its own world, budgeted by `arena.blocks-per-tick` and
   `arena.millis-per-tick` so the server does not stall. `arena.refuse-on-clip` refuses a world too
   short to hold the arena when the missing blocks are solid to the sim and near the fight. The paste
   wait is capped at 1200 ticks (60s), after which the pending match is reported as `NO_FRAME_ZERO`.
4. **Agree.** Before any frame is simulated, `EdgeHandshake` exchanges an `ArenaAgreement` (arena hash,
   ground-y, both spawns, the frame-0 state checksum, source) with the peer and compares it with
   `Double.doubleToRawLongBits` equality. The frame-0 checksum is `Checksum.of` over the decoded
   setup blob, so two hosts that decoded the same bytes into different opening states are refused
   here rather than desyncing several rounds later - the failure mode cross-play kept hitting.
   Agreement logs `arena agreed with the peer: hash=...`.
   Disagreement is `ARENA_MISMATCH` and the match never starts. A peer on an older jar answers with
   an older agreement version and is refused as a build skew, not as a timeout. Timeout (default 600
   ticks, 30s) is `PEER_NEVER_ARRIVED`.
5. **Cage.** The assignment carries a cage payload (`EdgeCage` v2/v3: per-slot floor and wall blocks
   plus the gate material, capped at 16384 blocks per slot and offsets within 64). It is raised before
   the first countdown tick and cleared between rounds. If core ships no cage the edge uses its built-in
   barrier box. The cage **drop height** is not a cage property: core bakes it into the assignment
   spawn Y, so both peers start from the same numbers by construction rather than by both happening to
   have the same config value.
6. **Frame 0.** Only now does the sim advance. Until then the player is pinned in place with gravity
   disabled.

Everything in steps 1 to 5 exists so that step 6 starts from a bit-identical `GameState` on both peers.

---

## 6. The wire

`Protocol` frames nine message types (Hello, InputFrames, Checksum, Abort, Snapshot, Heartbeat, Finish,
Chat, Container) over UDP. `NetSession` (in `sim-core`, package `me.nootnoot.sim.net`) does the
prediction, the rollback and the
peer-liveness accounting. There are two timers, both `PEER_TIMEOUT_TICKS` (400 ticks, 20s) wide: a
peer that stops sending packets at all is declared gone, and so is one that keeps the socket warm but
stops advancing `RollbackController.confirmedFrame()`. The second timer is deliberately keyed on that
locally computed number rather than on the highest frame number the peer got accepted, because the
peer authors the latter: a lone far-ahead frame reset it while confirming nothing, and a peer filling
a gap *below* it after burst loss made real progress the timer could not see.

There are two paths to the peer, chosen per match by the broker:

- **Relay** (`UdpTransport` to `relay`). The default and the only option when either peer is
  mod-hosted. The relay pairs the two peers by session id and forwards everything verbatim; it parses
  `Hello` only, for routing, and never forwards one.
- **Direct** (`DirectLink`, in `sim-core`). Two edges dial each other. One long-lived socket per edge
  carries every direct match, so each datagram gets a 37-byte `LinkFrame` header (magic, session id,
  sender slot, send counter, and a 16-byte HMAC tag over all of it and the body) in front of an
  untouched `Protocol` payload. The tag and counter make every frame authenticated and non-replayable,
  not just the handshake. A session is bound only by an inner `Hello` whose token
  matches the shared link token, from the host the broker named. Like the relay, `DirectLink` **consumes**
  Hellos and never delivers them to the channel: a keepalive reaching `NetSession` would reset the peer
  timeout forever and a dead peer would hang the match.

Direct links are behind `rollback.edge.direct-links` (default off) and fall back to the relay silently
per match. The relay is also the referee's only vantage point, so `rollback.edge-referee` and direct
links are mutually exclusive for a given match. Pick one deliberately.

---

## 7. Authority model

State the rule twice because both halves matter.

**Determinism.** Every input that feeds the sim must be identical on both peers. That is why movement
authority is stamped into the input stream rather than recomputed, why the arena arrives as one blob
under one key, why the kit arrives as setup bytes rather than as a kit name, and why the cage drop
height is baked into the spawn coordinates.

**Trust.** Nothing outcome-deciding is ever taken from a client, and on the edge path the plugin is
treated as a semi-trusted reporter rather than an authority:

- A client's movement is clamped by `EdgeMovementValidator` before it becomes authority.
- A client cannot choose its own kit, arena, spawn, rounds or cage. All of those arrive in the
  assignment, which core wrote.
- An edge cannot award itself a win. It can concede. A win requires corroboration or the broker's own
  arbitration against the player directory.
- A result that names no winner (`PEER_GONE` from both sides, an ambiguous directory) voids. Voiding
  costs nobody ELO; guessing wrong does.

---

## 8. The version fence

Three numbers, and they move together.

```
Protocol.CHECKSUM_REV                 = 110
InputCodec.BYTES                      = 212
Protocol.VERSION                      = (212 << 8) | 110      = 54382
RollbackModRegistry.EXPECTED_VERSION  = (212 << 8) | 110      = 54382
```

`Protocol.VERSION` packs the input encoding width with the checksum revision, and it is validated in
the `Hello` handshake: a peer on a different version is aborted with `ABORT_VERSION_MISMATCH` (1001)
before a single frame is simulated. `RollbackModRegistry.EXPECTED_VERSION` is the same number on the
core side, checked against what a client mod reported when it connected. A client whose number differs
never reaches a duel at all.

The fence is only as good as the discipline behind it:

> **If sim behaviour changes, the harness digest moves, and `Protocol.CHECKSUM_REV` and
> `RollbackModRegistry.EXPECTED_VERSION` must be bumped in the same commit.**

A digest change *with* a rev bump is an intended sim change. A digest change *without* one is a bug
that will desync every peer still on the old build, silently, because the handshake will happily agree
that two different sims are the same version.

The fence does not cover CPU architecture. Two peers on the same version but different architectures
can still disagree, which is why the cross-architecture determinism gate exists and why the edge fleet
is pinned to one architecture until it is green. See `README.md` for the gate and `DEPLOYMENT.md` for
the pinning rule as an operational instruction.

---

## 9. Where to go next

| Question | Document |
| --- | --- |
| What does the assignment contain, byte by byte? | `EDGE-PROTOCOL.md` section 2 |
| What happens when a player, an edge, the wire or Redis dies? | `EDGE-PROTOCOL.md` section 6 |
| Which flags turn what on? | `EDGE-PROTOCOL.md` section 7 |
| What ports, what Redis keys, what talks to what? | `NETCODE.md` |
| How do I deploy or operate this? | `DEPLOYMENT.md` |
| Why is there a determinism gate and how do I keep it green? | `README.md` |
