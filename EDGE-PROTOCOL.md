# Edge session brokering contract

Status: design only. No production code exists for any of this yet.

## What this document is for

Today a rollback duel requires the Fabric mod and puts both duelists on one regional game
server. That server's static config picks the relay and the limbo, so one of the two players
always eats the cross-region latency, and a player without the mod has the match cancelled
outright by `abortForModGate`.

This document specifies the contract that lets two **unmodded** players get matched, each
routed to an edge server in their own best region, with the two edges finding each other and
the result flowing back into ELO and stats through the existing `Game.finishFromRollback`
entry point.

It specifies a contract, not an implementation. Where the contract depends on runtime
behaviour that cannot be verified without Redis, Mongo, Velocity and multiple regional
servers, that is called out explicitly under [Unverified](#unverified) rather than presented
as settled.

## The one idea everything else follows from

**The edge derives nothing that affects the simulation.**

Two machines cannot disagree about a value that neither of them computes. Every byte that
feeds frame 0 is computed exactly once, on one machine, and shipped to both edges as opaque
bytes. The edges decode and run. They do not read their own `config.yml` for anything the sim
touches, they do not resolve a block palette, they do not probe their own world for a spawn
height, and they never construct a `RegistrySeeds`.

This is the difference between "we hope both edges agree" and "there is nothing for them to
disagree about".

Two existing pieces of sim-core make this possible, and neither of them needs changing:

- `GameStateFrame0Codec` round-trips a **complete** `GameState`, including
  `resistantBlockIds`, the seven item ids, the crossbow charged slots, the sealed
  `LoadoutCaps` values and the `roundInitial` snapshot. Every field that `RegistrySeeds`
  feeds into `MatchSetupFrame0Decoder` survives the round trip. So the broker can decode
  frame 0 once with its own seeds, re-encode the resulting `GameState`, and ship those bytes.
  **The `RegistrySeeds` problem disappears: seeds exist on the broker, once, and never travel.**
- `ArenaCodec` round-trips a full `Snapshot` including the palette-geometry extension
  (collision boxes, blast resistance, drop item ids), and `ArenaHash` gives a stable 64-bit
  fingerprint of the resulting `Arena`. So arena geometry is resolved once, on the broker,
  and shipped as gzip bytes.

There is a third piece already built and, as of today, wired to nothing: the relay's referee
(`RefereeManager`, `SessionReferee`, `RefereeSession`) accepts a `ControlProtocol.Authorize`
carrying exactly `{sessionId, expiry, slot0Token, slot1Token, arenaGroundY, arenaBoxes,
frame0}` and independently replays both input streams to produce an authoritative
`ControlProtocol.Result`. Nothing in mcleagues-core or the edge sends an `Authorize`. The
artifact this contract asks the broker to build is, field for field, the artifact the referee
already wants. One computation, three consumers: edge A, edge B, and the referee.

---

## 1. Session allocation

### Who brokers

The **game server that today runs `Game.startRollback()`** stays the broker. It is not moved
to the matchmaking service, the proxy, or an edge. It is the only machine that holds all of:

- the `Game` object, and therefore `finishFromRollback`, the `rollbackResult` flag that keeps
  ELO alive, and the round-count clamping
- the arena schematic and the `ArenaExtractor` pipeline
- both players' live Bukkit inventories, after `GameTeam.giveKit` has run
- the settings toggles (`INSTA_READY_UP`, `EXPLOSION_PARTICLES_AND_SOUNDS`, `TOTEM_PARTICLES`)
- the existing session, token and lock bookkeeping in `RollbackHandoffManager`

Its `serverId` is the `pickedArena` chosen at dispatch. That id is what the result has to find
its way back to.

### Session id

Minted as today, `ThreadLocalRandom.current().nextLong()`, then **claimed**:

```
SET rollback:edge:session:<sessionId> <brokerServerId> NX EX 2400
```

on global Redis. On failure, re-roll, up to 5 attempts, then abort the handoff before anyone
has moved.

Today the id is a bare `nextLong()` with no uniqueness check anywhere. The relay keys its
`sessions` map on sessionId alone, so a collision cross-wires two live matches into each
other's input streams. Sixty-four bits makes that unlikely, not impossible, and the failure
mode is silent and catastrophic. The claim turns a hope into a network-wide fact, and it
doubles as the result-routing record and the replay defence.

### Slot tokens

Unchanged: two 32-byte `SecureRandom` tokens from `newSlotToken()`, one per slot, held in
`sessionTokens`. Each token is sent to exactly one edge, and to the relay inside the
`Authorize`. The relay's `RefereeManager.tokenMatches` already enforces them on `Hello` for
any authorized session, so a third party who guesses a session id still cannot join it.

### Slot identity

Today slot is implicit: slot N is `game.getAllPlayers().get(N)`. There is no slot field
anywhere in the codebase. That is safe only because one machine does the indexing.

In edge mode the slot is **pinned explicitly** in the assignment, alongside the player's UUID.
No machine other than the broker ever derives a slot from a list order. This is not a
nice-to-have: two boxes independently flattening a team list is precisely the kind of silent
disagreement that produces a match where both players think they are player 0.

### Choosing the two edge regions

One edge per player, chosen independently, reusing the existing region machinery rather than
inventing a ranking.

For each player, the ideal region is the first of these that yields an answer:

1. `PingProfileManager.getSortedRegionsByPing(uuid).get(0)` - measured ping, best first.
2. The GeoIP region from `RegionService.getData(uuid)`, when present and `isReliable()`.
3. `PlayerLocation.regionId()` - the region the player is actually connected to right now,
   which is itself already the product of `connectToBestRegion` at join time.
4. `UserProfile.getLastPickedRegion()`.
5. The broker's own region.

Then remap to a region that can actually host, mirroring `RegionService.hostableLobbyRegion`
exactly:

```
hostableEdgeRegion(ideal) =
    EdgeServerRegistry.hasLive(ideal) ? ideal
                                      : ideal.nearestWith(EdgeServerRegistry::hasLive)
```

`Region.nearestWith` already exists and already does haversine-nearest-satisfying-predicate.
Use `Region.safeValueOf` for string decoding. `Region.from(String)` can never return non-null
and must not be used.

Two ordering notes worth being explicit about:

- **Ping before GeoIP, which is the reverse of `connectToBestRegion`.** That method runs on a
  lobby that has just performed the MaxMind lookup and holds `PlayerRegionData` in its local
  cache. The broker is a game server and generally holds neither player's `PlayerRegionData`.
  What it *can* read for both players, from any region, is the ping mirror that
  `PingProfileManager.mirrorPing` already maintains at `practice:pingprofile:<uuid>` on global
  Redis (a HASH of region name to milliseconds, 7-day TTL). That mirror is the only signal
  available to the broker for a player who is not on it, so it goes first. Read it off the
  main thread.
- **Both players may resolve to the same region.** That is allowed and is not a special case.
  It degenerates to today's topology minus the relay detour.

Selection must not block the main thread on Mongo or on `getProfile`. Read the global mirror
from a pool thread and hand the result back.

### Edge registry

A self-contained keyspace modelled on `FfaServerRegistry`, deliberately **not** an addition to
`GameCountRepository.ServerType`. Adding a constant there changes `clearOtherTypes` behaviour
for GAME, LOBBY and FFA, which a reviewer cannot verify at a glance. Conversely a server whose
`SERVER_TYPE` matches none of `isLobbyServer`, `isGameServer` or `isFfaServer` falls through
all three branches of that class's 1-second loop and registers nothing at all. So
`SERVER_TYPE=NETCODE_EDGE` (see `DEPLOYMENT.md`) is invisible to every existing picker with zero
changes to shared code.

One deliberate divergence from the `FfaServerRegistry` template: **these keys live on global
Redis, not local.** `FfaServerRegistry` is local-only because an FFA server is only ever
picked from within its own region. An edge in region B has to be visible to a broker in region
A, so local Redis cannot work here.

```
rollback:edge:servers:<region>                    SET of edgeServerId
rollback:edge:heartbeat:<region>:<edgeServerId>   SETEX 5,  value = free session slots
rollback:edge:endpoint:<edgeServerId>             SETEX 30, value = "host:port" or "@serverName"
```

`hasLive(region)` is a non-empty set after pruning members with no live heartbeat, exactly as
`FfaServerRegistry.pick` prunes. Do not reuse `GameCountRepository`: its `isWhitelisted()`
check fully deregisters any whitelisted server, and edges are likely to be whitelisted.

**What was actually built is simpler than the three keys above.** `EdgeBroker.heartbeat` writes one
field per edge into a single global HASH, `rollback:edge:servers`, keyed by `edgeId`, whose value
carries region, host, port, optional `directHost`/`directPort` and a millisecond timestamp;
`EdgeRegistry.live()` reads the whole hash and drops anything whose timestamp is older than 30s.
Region is a field rather than part of the key, so `pickFor` can fall back to the nearest region with a
live edge instead of only answering yes or no for one region. `NETCODE.md` has the current keyspace
table. Note that a **whitelisted edge is not deregistered** by this registry, but it will reject the
players sent to it, so leave `white-list=false` on an edge that takes real matches.

### Relay selection

Optional, behind its own sub-flag. The broker picks the relay minimising
`max(rtt(edgeA, relay), rtt(edgeB, relay))` from:

```
rollback:edge:relays                       HASH relayId -> "host:port:region"
rollback:edge:relay:heartbeat:<relayId>    SETEX 10
```

With the sub-flag off, the relay is today's static `rollback.relay-host` / `rollback.relay-port`.
Either way the chosen endpoint is **pinned into the assignment**, so both edges dial the same
relay by construction rather than by both happening to have the same config file.

---

## 2. What each edge is told

The assignment is a single versioned binary blob. Every determinism-critical value is in it.
Nothing determinism-critical is read from the edge's own configuration.

### Why binary, and why not JSON numbers

The Redis message bus serialises records with Gson. `ArenaAgreement.disagreement` compares
doubles with `Double.doubleToRawLongBits`, and `ArenaHash` mixes raw bits. A double that
round-trips through JSON text is not guaranteed to come back with identical bits, and if it
does not, the two edges compute different arena hashes and refuse to start, or worse compute
the same hash from different geometry.

So: the entire assignment is one base64 `String` field inside the JSON envelope. No
determinism-critical number is ever a JSON `double`. The envelope carries routing metadata
only.

### Layout

```
magic                 int     0x4A45414D
version               byte    1
sessionId             long
expiryEpochMs         long
slot                  byte                    0 or 1
simBuildId            u16-len utf8
brokerServerId        u16-len utf8
brokerRegion          u16-len utf8
matchId               u16-len utf8
selfUuid              long, long
peerUuid              long, long
selfName              u16-len utf8
peerName              u16-len utf8
slotToken             int-len bytes           this slot's token only
relayHost             u16-len utf8
relayPort             int
rounds                int
arenaHash             long
arenaGroundY          double
arenaBoxCount         int, then 6 doubles per box
spawn0                double x, y, z + float yaw, pitch
spawn1                double x, y, z + float yaw, pitch
renderWorld           u16-len utf8
renderOffX/Y/Z        int, int, int
frame0                int-len bytes           GameStateFrame0Codec.encode output
arena                 int-len bytes           ArenaCodec gzip, sessionId field ZEROED
ackDeadlineMs         int
handshakeTimeoutTicks int
```

### Field by field, and why it is pinned

| Field | Why it must be pinned rather than read locally |
|---|---|
| `sessionId` | Keys the relay session, the referee, the claim key and the result. Today the edge reads it from `config.yml`, where two edges trivially drift apart and end up in two different relay sessions, each waiting forever for a peer. |
| `slot` | The only thing that decides which `PlayerState` is "you". Currently `config.yml`. Two edges both configured slot 0 would both bind slot 0 at the relay and the second would evict the first. There is no other slot identity anywhere in the system. |
| `slotToken` | The relay's `tokenMatches` gate for authorized sessions. Currently an all-zero 32-byte array in `EdgePlugin`, i.e. no authentication at all. |
| `relayHost`, `relayPort` | Both edges must dial the *same* relay instance. Two edges with different relay config never meet, and the failure looks exactly like a network problem. |
| `rounds` | Sets `GameState.roundsTarget`. A mismatch means the two sims disagree about when the match ends, which surfaces as one edge reporting a winner while the other is still playing. |
| `frame0` | The entire initial `GameState`: positions, velocities, health, attack damage and speed, knockback, armour and toughness, protection and blast protection, food and saturation, all eleven hotbar arrays, arrows, pearls, elytra, potion effects, crossbow charged slots, sealed loadout caps, `roundInitial`, `playCentre`/`playRadius`/`playCircular`, `vanillaBuild`, `potSwordBoost`, and the seven registry item ids plus `resistantBlockIds`. Every single one of these is a desync if it differs by one bit. Shipping the encoded bytes makes disagreement structurally impossible. It also removes the need for a `RegistrySeeds` on the edge, which is the single largest open problem in the naive design. |
| `arena` | The voxel grid, partial collision boxes, per-voxel blast resistance, drop item ids and decor voxels. Two edges independently resolving a palette string like `minecraft:oak_stairs[facing=east,half=top,shape=straight]` to collision boxes is a determinism bet, not a guarantee. Resolve once, ship bytes. The embedded `sessionId` is zeroed so the blob is a pure function of the arena and can be cached and hashed per arena rather than per match. |
| `arenaHash` | Broker-computed `ArenaHash.of(arena)`. The edge recomputes after decoding and refuses the match if it differs. This is a cheap self-check that catches a corrupt or truncated blob before frame 0 rather than at frame 400. |
| `arenaGroundY` | Feeds `Arena.groundY`, is compared bit-exactly by `ArenaAgreement`, and is currently `AUTO`-resolved from `world.getHighestBlockYAt` when unset. That derivation reads the edge's own world file. Two edges with different world files derive different ground. |
| `spawn0`, `spawn1` | Both spawns go to both edges, not just the local one, because `ArenaAgreement` exchanges all six coordinates and compares them bit-exactly. Currently `spawn.y0`/`spawn.y1` fall back to `surfaceY(world, x, z)`, which is again a local-world read. |
| `arenaBoxCount` + boxes | What `MatchSetupFrame0Decoder` returns as `Frame0.arenaBoxes` and what `ControlProtocol.Authorize` wants. Empty today (`new double[0][]` at the `MatchSetupCodec.encode` call site) but pinned so it stays identical if that ever changes. |
| `peerUuid`, `peerName` | Rendering the opponent's nametag and skin, and knowing who to report about. Not sim input. |
| `brokerServerId`, `brokerRegion` | Where the result and the abort go. The region decides local versus `_global` addressing. Without this the edge has no way to find the machine holding the `Game`. |
| `matchId` | Correlates with the `Game` for logging and for the durable result mailbox. |
| `simBuildId` | Guards against two edges running different sim-core jars. Same sessionId, same everything else, different physics. The relay already aborts on `Protocol.VERSION` mismatch; this catches a build difference that does not bump the wire version. |
| `expiryEpochMs` | Replay defence. Mirrors the field `ControlProtocol.Authorize` already carries. |
| `renderWorld`, `renderOffX/Y/Z` | **Render only.** Where in the edge's Bukkit world the sim origin is placed. Deliberately separate from the sim coordinates so it can differ per edge without touching determinism. It must never be fed back into the sim. |
| `ackDeadlineMs`, `handshakeTimeoutTicks` | Timeouts, not sim inputs. Pinned only so both sides give up at roughly the same moment. |

### What the broker does, in order

Steps marked *(unchanged)* are today's code, untouched.

1. Mint and claim `sessionId`. Derive both slot tokens from it as
   `HMAC-SHA256(rollback.relay-slot-secret, "rollback-slot-v1" || sessionId || slot)`, so a relay
   holding the same secret can verify a slot bind without being told about the session at all.
   *(mint unchanged, claim is new)*
2. Prepare the arena *(unchanged extraction, cached)*, then resolve the palette to
   `ArenaCodec.PaletteEntry[]` geometry and encode an `ArenaCodec.Snapshot` with `sessionId`
   zeroed. Compute `arenaHash`.
3. `GameTeam.giveKit` for both teams, wait `kitDelayTicks`. *(unchanged)*
4. `readLoadout(a, spawn0)`, `readLoadout(b, spawn1)`. *(unchanged)*
5. `MatchSetupCodec.encode(...)` to build `payload0`. *(unchanged)* In edge mode this payload
   is never sent to a client. It exists only as the input to the next step.
6. `MatchSetupFrame0Decoder.decode(payload0, seeds)` once, then
   `GameStateFrame0Codec.encode(frame0.state())` to get `frame0Blob`.
7. Pick edge A, edge B and the relay.
8. `Authorize` the relay over the control connection.
9. Write both assignment blobs to Redis, publish both doorbells.
10. **Wait for both acks.** Nobody has moved yet.
11. Transfer each player to their own edge.

Step 10 is the most important ordering rule in this contract. See
[Failure modes](#6-failure-modes).

Note on step 6: `MatchSetupFrame0Decoder` applies `LoadoutCaps.seal`, snapshots `roundInitial`,
and hard-sets `onGround=true`, `vy=-0.0784`, `attackTicker=100`. All of that happens once, on
the broker, and is baked into `frame0Blob`. The edge must **not** call `LoadoutCaps.seal` or
`EdgeMatch.seed` again after decoding.

---

## 3. Delivery

### Tier convention

From `RedisSystem`, every server subscribes to three channels: `serverId` on local Redis,
`serverId + "_global"` on global Redis, and `global_mcleagues` on global Redis. The existing
rule, implemented identically in `ProxyManager`, `sendPlayerToServer` and
`transferPlayerToLocalSim`, is:

> target in my region, `publishLocal(msg, targetId)`; otherwise `publishGlobal(msg, targetId + "_global")`.

The test compares the **target's** region to mine. It is about which Redis the recipient is
listening on, not about which region owns the message.

Edge traffic is cross-region roughly half the time and there is no benefit to branching, so
**all edge messaging uses the global tier and `_global` channels unconditionally.** State the
rule once and do not make a reviewer verify a branch.

### Keys, all on global Redis

```
rollback:edge:session:<sessionId>                 STRING <brokerServerId>   SET NX EX 2400
rollback:edge:assign:<sessionId>:<slot>           STRING base64(blob)       SETEX to expiry
rollback:edge:ack:<sessionId>:<slot>              STRING <edgeServerId>     SETEX 120
rollback:edge:result:<sessionId>                  STRING base64(result)     SETEX 300
rollback:edge:servers:<region>                    SET
rollback:edge:heartbeat:<region>:<edgeServerId>   STRING free-slots         SETEX 5
rollback:edge:endpoint:<edgeServerId>             STRING host:port          SETEX 30
rollback:edge:relays                              HASH relayId -> host:port:region
rollback:edge:relay:heartbeat:<relayId>           SETEX 10
```

The assignment key is the durable copy. **Pub/sub is only a doorbell.** Redis pub/sub has no
delivery guarantee, and `RedisSubscriber.onMessage` swallows every exception, so a dropped
message produces zero log output anywhere. An edge that misses the doorbell but later learns
the sessionId must be able to `GET` the assignment. This is the same durable-mailbox shape as
`RobustDelivery`, which SETEXes a payload and then publishes a flush notification.

### Messages

Four new `@RedisType` records. Each name must be globally unique or `registerHandler` throws
`IllegalStateException` on startup.

| Type | Direction | Channel | Payload |
|---|---|---|---|
| `rollback_edge_assign` | broker to edge | `<edgeServerId>_global` | `(long sessionId, int slot, String brokerServerId, String blobBase64)` |
| `rollback_edge_ack` | edge to broker | `<brokerServerId>_global` | `(long sessionId, int slot, String edgeServerId, String playerHost, int playerPort)` |
| `rollback_edge_result` | edge to broker | `<brokerServerId>_global` | `(long sessionId, int slot, int winnerSlot, int winsP0, int winsP1, String tokenB64)` |
| `rollback_edge_abort` | edge to broker | `<brokerServerId>_global` | `(long sessionId, int slot, String reason)` |

`playerHost` / `playerPort` in the ack is what the broker feeds to `LocalSimTransferMessage`
to move the player. The edge reports its own reachable endpoint rather than the broker
guessing, because only the edge knows what address actually resolves to it from the proxy.

Relay to broker is **not** Redis. The broker holds the authenticated TCP control connection to
`ControlEndpoint` (HMAC-tagged frames, `ControlAuth`), sends `Authorize` on it, and reads
`Result` back on the same socket. That path already exists on the relay side and needs a
client on the broker side.

`abort` reasons are a closed set: `NO_PLAYER`, `PEER_TIMEOUT`, `ARENA_MISMATCH`,
`EXPIRED`, `REPLAY`, `SIM_FAULT`, `CAPACITY`.

Registering unknown types asymmetrically is safe: `RedisManager.dispatch` throws for an
unknown type but `RedisSubscriber.onMessage` swallows it, so servers that do not know these
types silently ignore them. Good for staged rollout, and the reason a message going nowhere
produces no log line at all, which is worth remembering while debugging.

---

## 4. Edge to edge: dial the peer directly, keep the relay as the fallback

**Decision (revised): two edges talk straight to each other. The relay stays, and is still the
only path for the modded client.**

The original decision in this document was to keep the relay for every match. That reasoning is
kept below, because most of it is still true - what changed is which of those costs turned out
to bind.

### What the direct path is

Each edge binds one long-lived UDP socket (`direct.port`, default 7787) and advertises it in its
registry heartbeat as `directHost`/`directPort`. When the broker assigns a session it puts the
*other* edge's endpoint, plus one shared per-session `linkToken`, into each slot's assignment
under `peer`. `DirectLink` in sim-core owns the socket and hands out one `Transport` per session.

Framing is a fixed 37-byte `LinkFrame` header - magic `0x4A444C32`, session id, sender slot, an
8-byte send counter and a 16-byte HMAC-SHA256 tag - in front of an untouched `Protocol` payload. The
session id and slot are there because one socket carries every match this edge is running, so the
source address alone cannot identify a session the way it can at the relay, where each peer gets its
own ephemeral port. The counter and tag are there because the source address is also the only thing
an off-path attacker needs to forge, and the broker hands each edge the other's host:port in clear:
the tag is keyed by the same per-session `linkToken` the slot tokens derive from and covers magic,
session, slot, counter and body, and the receiver keeps a 64-frame replay window on the counter, so a
recorded datagram cannot be handed back. The magic was `0x4A444C31` before CHECKSUM_REV 165, when the
header was 13 bytes and only the `Hello` was authenticated; an old peer's frames are now counted as
`untaggedFrames` and refused with a named log line rather than misparsed.

Admission mirrors `RelayServer.registerHello` deliberately:

- the payload must pass `Protocol.isWellFormed` or it is dropped and counted;
- an unbound session is bound only by an inner `Hello` whose session id matches the frame and
  whose token equals the shared `linkToken`, so an endpoint alone proves nothing;
- the sender slot must differ from ours, and, before anything has bound, the source must be the
  host the broker named;
- once a `Hello` has proved the token, the channel is pinned to the exact `SocketAddress` that
  proved it and every later datagram must come from that socket. Matching on the source **IP
  alone** is not enough: any other socket on the peer's host - a co-tenant process, another
  machine behind the same NAT, anything that can put that address in a source field - could
  otherwise inject inputs, a `Finish` or an `Abort` into a live match. The relay keys `peers` on
  the full `SocketAddress` that presented a valid slot token, and the direct path is the one that
  skips the relay entirely, so it has to be at least that strong. A later `Hello` may move the pin
  only by passing the token check again, which is how a genuine NAT rebind still works; each such
  move is counted in `DirectLink.peerRebinds()`;
- `Hello` is consumed by `DirectLink` and never delivered to the channel. This one is load
  bearing: the relay never forwards Hellos either, and if a keepalive reached `NetSession` it
  would reset `ticksSincePeerPacket` every 500 ms, so a peer whose sim had died but whose socket
  was still alive would never trip the peer timeout and the match would hang.

### When it is NOT used

Every one of these silently keeps the relay, per match, with no operator action:

| Condition | Why |
| --- | --- |
| `rollback.edge.direct-links` is false | Default. The whole feature is inert. |
| The opponent is `hostKind=MOD` | A player's own machine is behind NAT and has no dialable address. This is the case the relay exists for and it is not going away. |
| Either edge advertises no `directPort` | An older edge build, or one whose UDP bind failed. |
| Both players landed on the same edge | A peer-to-peer socket would have to talk to itself; `DirectLink.open` refuses a duplicate session id on one endpoint. |
| The direct socket could not bind at startup | Logged as a degradation, and every match on that edge falls back. |

### What it saves

The path stops being `edgeA -> relay -> edgeB` and becomes `edgeA -> edgeB`. By the triangle
inequality the saving is never negative and equals the relay detour:

```
saved_rtt = (rtt(A,R) + rtt(R,B)) - rtt(A,B)
```

- **Relay co-located with one edge** (what a static `edge.relay-host` on one game server usually
  produces): the detour is close to zero for the near edge and small for the far one. Little is
  saved in the mean.
- **Relay in a third region**, which is what actually happens when core lives in Frankfurt and
  the two edges are London and Ashburn: roughly 15 ms + 45 ms one way through the relay against
  roughly 40 ms direct. About 20 ms one way, 40 ms RTT. At the sim's 50 ms tick that is a little
  under one full frame of prediction depth removed in each direction.

The mean is the smaller half of the argument. The larger half is **jitter**: the relay adds a
kernel-to-userspace-to-kernel hop and one more process's scheduling quantum to every single
datagram. In a rollback net, prediction depth and rollback frequency are driven by the *tail* of
the arrival distribution, not its mean, so removing an entire scheduling stage is worth more than
the 40 ms suggests. It also removes a per-match single point of failure and a bandwidth and CPU
concentration point.

### What it costs, and this is the real trade

**The referee loses its vantage point.** `RefereeManager` tees every forwarded packet into
`SessionReferee`, which replays both input streams and produces an independent `Result`. With a
direct link there is nothing in the middle to tee from, and the result becomes what the two edges
say it is. Section 5's corroboration rule still bounds the damage - an edge can concede a match it
won but cannot unilaterally claim one - but "an independent third party replayed the match" is
gone for edge-versus-edge.

This is why the flag defaults to **false** and why it is a single switch: if and when the referee
(`rollback.edge-referee`, still unshipped as of this writing) goes live and matters more than the
hop, turn direct links off and everything reverts, because the relay path was never removed.

The other original costs still stand and are simply no longer paid: N-squared region-pair
reachability between edges is real, but edges have stable public addresses and no NAT, which is
the premise this whole section rests on. If that stops being true for a region, that region's
edges should advertise no direct port and they fall back automatically.

---

## 5. Result path

### Three reporters, one arbiter, one entry point

**Authoritative: the relay referee.** On `matchOver` or on session eviction, `RefereeManager`
emits `ControlProtocol.Result{sessionId, decided, winnerSlot, winsP0, winsP1, confirmedFrame,
violation}` down the control connection the broker opened. It lands on the broker directly:
no Redis hop, no trust in either edge. A `decided=true` referee result **wins unconditionally**
and resolves immediately.

**Corroborating: each edge.** When its own `MatchDriver` decides, an edge publishes
`rollback_edge_result` carrying its slot token as proof of identity. This is the fallback for
when the referee is off (`RELAY_CONTROL_SECRET` unset) or the control connection dropped.

**Last resort: the existing sweeps.** Untouched.

### The corroboration rule, unchanged

Edge reports go through the **same rule** `RollbackHandoffManager.resolveResult` already
applies to mod reports. Restating it because it is asymmetric and easy to misread:

- A report naming **your opponent** as the winner is accepted immediately and unilaterally.
  Conceding is always believed.
- A report naming **yourself** as the winner is only honoured if the opponent has *already*
  claimed you as the winner.
- Each slot may claim exactly once (`sessionClaims`, an `int[2]` initialised to `{-1,-1}`).
- Both slots claiming themselves resolves nothing, logs `both slots claimed the win`, and
  falls through to the sweep.
- `activeSessions.remove(sessionId)` is the single-resolution latch.

Only one thing changes: **the identity check.** In the mod path, `RollbackResultPacketListener`
takes the sender from the packet's own connection, so identity is the player's session. An
edge reports on behalf of a slot from a different machine, so identity becomes possession of
that slot's token. Same rule, different proof of who is speaking.

Note that `resolveResult`'s existing shape is *why* an edge-reported result is worth having at
all despite the edge being semi-trusted. A malicious edge can concede a match it won (harmless
to the opponent) but cannot unilaterally claim a win.

### Reaching `finishFromRollback`

Every path ends at the same call, on the broker, on the global region scheduler:

```java
game.finishFromRollback(winner, loser, winnerWins, loserWins);
```

with the identical preconditions and clamping the mod path uses:

- `game.getPhase() == GamePhase.STARTED`
- `cap = Math.max(1, game.getRounds())`, both win counts clamped to `[0, cap]`
- `loserWins = Math.min(loserWins, winnerWins - 1)`
- both UUIDs still members of the `Game`

Two traps that must not be re-derived by any new code:

- `finishFromRollback` sets `winnerTeam.setRoundsWon(max(0, winnerWins - 1))`, deliberately one
  **less**, because `Game.finish` increments the winner again. Passing the true score
  over-counts by one and can trip the first-to check.
- `finishFromRollback` passes `forceEnd=true`, which normally voids stats and ELO. The only
  thing keeping ELO alive is `this.rollbackResult = true` set one line earlier. Any new result
  path that bypasses `finishFromRollback` and forgets that flag finishes the match with
  silently zero ELO and a ranked-skipped warning.

The correct move, and the one this contract specifies, is that **no new code sets those fields**.
Everything calls `finishFromRollback` and inherits them.

### If the broker is gone

`rollback:edge:session:<sessionId>` holds the `brokerServerId`. Results additionally go into
the durable mailbox `rollback:edge:result:<sessionId>` (SETEX 300) with a flush notification on
`global_mcleagues`, mirroring `RobustDelivery`. A broker that was restarting picks it up on
arrival with GETDEL. If nobody ever claims it, the existing `GameManager` sweeps behave exactly
as they do today.

---

## 6. Failure modes

### The one rule that makes all of these work

**An edge only decides what it can see. Everything else is arbitrated by the broker.**

An edge knows two things for certain: whether its own sim confirmed a death, and whether its own
player left. It cannot tell "my opponent's machine died" from "the wire between us broke", and
those two have opposite correct outcomes - a forfeit and a void. So every result an edge publishes
carries a **cause** (`EdgeOutcome`), and only two causes name a winner.

| Cause | Published when | What the broker does |
| --- | --- | --- |
| `FINISHED` | the sim confirmed the match over | Names our own player the loser -> believed at once. Names ourselves the winner -> arbitrated (below). |
| `LOCAL_QUIT` | our player quit, crashed, was kicked, or this edge is shutting down | Concession. Believed at once, unilaterally. |
| `LOCAL_FORFEIT` | our player forfeited deliberately | Concession. |
| `SELF_FAULT` | our own sim threw | Concession. We are the faulty party. |
| `PEER_GONE` | the peer stopped sending | Armed, then arbitrated. |
| `DESYNC` | checksums diverged | Void. No ELO, no stats. |
| `ARENA_MISMATCH` | the two edges resolved different arenas | Void. |
| `PEER_NEVER_ARRIVED` | the pre-frame-0 handshake timed out | Void. Nothing was played. |
| `NO_FRAME_ZERO` | our player left, or this edge could not reach frame 0 | Void. |

A cause an older broker does not recognise falls through to void, never to a mis-awarded win.

### Arbitration of a claim the edge cannot decide alone

A lone `PEER_GONE`, or a lone `FINISHED` naming the reporter itself, is **armed** rather than
resolved. `EdgeSessionBroker.sweepArmedForfeits` settles it one grace window later, on the same
5-second timer that drains results, using **the same predicate the GameManager forfeit sweep
already applies** - `GameManager.rollbackPresenceSplit`, refactored out of
`tryRollbackAbandonForfeit` so there is genuinely one copy:

- opponent absent from the global player directory, reporter present -> **forfeit**, the reporter
  wins, `finishFromRollback`;
- directory cannot separate them (both online, or the snapshot older than `DIRECTORY_TRUST_MILLIS`)
  -> **void**. Awarding here would be guessing, and guessing wrong costs ELO;
- directory says the *reporter's* player is the one who left -> **void**.

If the other edge reports anything better inside the grace window, that wins instead. Two
`PEER_GONE` reports mean both players are alive and the link between the edges died: **void**, not
a forfeit for someone who was still playing. Two self-claims: **void**.

### Bounded time to a result

| Ending | Worst case |
| --- | --- |
| A clean quit, crash or kick | The quitting edge sends the teardown abort, so the peer's match ends on the **next tick**; the concession resolves on the next 5s drain. **~5s.** |
| The peer edge process dies, the machine dies, or the link stays down | `NetSession.PEER_TIMEOUT_TICKS` (400 ticks, 20s) + up to 1s to publish + up to 5s to drain + 5s grace. **~31s.** |
| Never reached frame 0 | `arena-handshake-timeout-ticks` (600, 30s), or the paste-wait bound (1200 ticks, 60s), then one drain. |

Nothing here waits on `ROLLBACK_MAX_MATCH_MILLIS`. That 30-minute sweep is still in place as the
last backstop and should now never be the thing that ends a match.

### An edge never picks up the assignment

Unchanged from the original design: the broker waits for both acks before transferring either
player, because two players standing on the game server is the cheapest failure available.

### A player disconnects during transfer, or before frame 0

`EdgePlugin.tickPending` now **reports**. Before this change it removed the pending entry, told
the player, and published nothing at all, so core's session sat open until the 30-minute sweep -
which is precisely the hang this section had to close. Every pre-frame-0 exit publishes
`NO_FRAME_ZERO`, `PEER_NEVER_ARRIVED` or `ARENA_MISMATCH` and the Game is voided within one drain.

Two other pre-frame-0 hangs are closed with it:

- **`onQuit` while pending** reports `NO_FRAME_ZERO` before dropping the entry.
- **A paste that never finishes.** `tickPending` used to `continue` forever while
  `paster.ready(world)` was false, with no bound at all. It is now capped at
  `PASTE_WAIT_TIMEOUT_TICKS` (1200, 60s) and refuses the match.

### One edge starts, the other never does

Still gated by `EdgeHandshake`: neither edge simulates a frame until the `ArenaAgreement` matches,
so there is no partial match state to reconcile. What is new is that the timeout is reported
rather than swallowed.

### Redis goes away mid-match

The match itself is unaffected - it is pure UDP between the two edges (or the relay) with no Redis
in the path. Two things used to break around it, and both are fixed:

- **The result was lost.** `EdgeBroker.reportResult` pushed through `withRedis`, which swallows
  every failure. The result now goes into a bounded in-memory outbox and is re-flushed on every
  broker poll (1s) until it lands. Delivery is at-least-once; core makes duplicates harmless
  because a slot may file only one report and session removal is the single-resolution latch.
- **The edge keeps advertising itself.** It does not: the heartbeat fails too, so the edge ages
  out of `EdgeRegistry.live()` after 30s and no new match is routed to it. Live matches finish.

If the edge process dies while results are still queued, they are gone, and the match falls back
to the GameManager forfeit sweep - which is the same predicate, so the outcome agrees.

### A player rejoins seconds later

The match is over: the sim cannot be paused, the peer's `NetSession` has already timed out or
been told, and `MatchDriver.end()` has run. The rejoin is therefore not a resume, and the
outstanding job is to make that legible and to not leave state on them:

- they are told, on join, that they left a live match and it was forfeited;
- their pre-match inventory is restored. `EdgeLoadout.restore` is a no-op against an offline
  player, so the quit-time restore silently did nothing and they used to come back still wearing
  the match kit. The snapshot is now held and applied in `onJoin`.

### Both edges report different winners

- **Referee on (relay path only):** the referee's independently replayed `Result` wins.
- **Referee off, or direct links on:** two self-claims void the match. This is stricter than the
  old behaviour, which logged and fell through to the 30-minute sweep.

### Session id collides / a stale assignment is replayed

Unchanged. Note that the direct path adds one more defence for free: a session on a `DirectLink`
is bound only by a `Hello` carrying that session's `linkToken`, and `open` refuses a duplicate
session id on one endpoint outright.

---

## 7. The config flag

```
rollback.edge-mode          (env ROLLBACK_EDGE_MODE)          default false
```

Resolved through the existing `RollbackHandoffManager.cfgBool` helper, so it inherits the
established order: environment variable `ROLLBACK_EDGE_MODE` first (non-blank, trimmed), then
`MCLeagues.getInstance().getConfig()` under the `rollback.` prefix, then the hardcoded default.
`cfgBool` uses `Boolean.parseBoolean`, so anything that is not literally `true` is false.

### Exactly what is inert when it is off

**In `awaitPlayersThenStart`,** one early branch is added, placed immediately **before** the
mod-gate block:

```java
if (edgeModeEnabled()) {
    startEdgeMatch(game, a, b);
    return;
}
```

With the flag off, that condition is false and every subsequent statement in the method is
byte-for-byte today's code, including the mod gate, `abortForModGate`, the arena future, kit
handout, `readLoadout`, `MatchSetupCodec.encode`, the plugin-message sends and the limbo
transfer. `startEdgeMatch` is new, additive, and unreachable.

**Everywhere else:**

- `Game.startRollback()` is unchanged. `GameManager`'s fork at
  `if (gameSettings.isRollback())` is unchanged. All four rollback sweeps and their constants
  are unchanged.
- No new Redis key is written and no new channel is published. The four new handlers are
  registered but never receive a message, and an unregistered type is silently dropped
  elsewhere anyway.
- The edge registry heartbeat only runs on a server with `SERVER_TYPE=NETCODE_EDGE`, which no existing
  server has. `GameCountRepository`'s loop already falls through all three of its branches for
  an unknown type, so no change to that class is needed and GAME, LOBBY and FFA behaviour is
  provably untouched.
- The relay control client is only constructed when a match is being brokered in edge mode. A
  relay with no `RELAY_CONTROL_SECRET` does not even open the control port.
- The existing modded rollback path and the normal server-authoritative duel path are both
  entirely untouched.

**On the edge plugin,** a separate flag in its own `config.yml`:

```
edge.broker.enabled                            default false
```

With it off, `EdgePlugin` behaves exactly as today: config-driven single session, `/edge`
command, `config.yml` slot and sessionId and spawns, all-zero token. With it on, the
`/edge` command is refused and every one of those values comes from the assignment.

### Sub-flags, all default off and individually inert

```
rollback.edge-referee          default false   authorize the relay referee, use its Result
rollback.edge-relay-registry   default false   per-match relay pick; off = static relay-host
rollback.edge.direct-links     default false   two edges dial each other; off = every match relays
rollback.edge-ack-timeout-ticks         default 100
rollback.edge-join-timeout-ticks        default 200
rollback.edge-handshake-timeout-ticks   default 600
```

`rollback.edge.direct-links` is the switch for section 4. With it off, `EdgeSessionBroker` writes
no `peer` block, every edge sees an assignment identical to today's, and `DirectLink` - although
bound and listening on every edge - is never handed a session. It and `rollback.edge-referee` pull
in opposite directions: the referee's only vantage point is the relay it tees from, so an
edge-versus-edge match cannot have both. Pick one deliberately.

On the edge plugin, the matching block in its own `config.yml`:

```
direct.enabled          default true    bind the direct endpoint and use it when told to
direct.port             default 7787    UDP, must be reachable from the other edges
direct.bind             default ''      bind address; empty = all interfaces
direct.advertise-host   default ''      what to publish; empty = broker.public-host
```

`direct.enabled` defaults to true on the edge and the feature is still off, because an edge only
ever uses the direct path when an assignment carries a peer endpoint - and only core decides that.
One flag, on the broker, controls the rollout. If the UDP bind fails the edge logs it and every
match falls back to the relay.

---

## 8. Cross-play: one modded client against one vanilla client

```
rollback.cross-play         (env ROLLBACK_CROSS_PLAY)         default false
```

Requires `rollback.edge.enabled` as well. With it off, `awaitPlayersThenStart` behaves exactly as
before: both modded goes to the limbo path, both vanilla goes to the edge path, and a mixed pair
is refused by `abortForModGate`.

### Why the sim does not care

`Simulation.tick` decides per player whether that player's movement is stamped, in one predicate:

```java
private static boolean stamps(GameState s, PlayerState p, Input in) {
    int idx = p == s.players[0] ? 0 : 1;
    return in.authority().present() && !p.dead && s.edgeHosted[idx]
            && withinAuthorityStep(p, in.authority());
}
```

`edgeHosted` is decoded from the setup blob's host section, so both peers know which slot is the
unmodded one from the same bytes; it is checksummed, so they cannot disagree about it quietly.
`Authority` rides inside `Input` and `InputCodec` serialises it, so both peers apply the identical
decision to the identical frame. Zero, one or two stamped players are all the same code path.
Prediction cannot break it either: `Input.heldOnly()` and `Input.released()`, the only two inputs
the predictor ever invents, both drop `Authority` by construction, so an unarrived vanilla frame is
free-simulated on both peers and corrected by the normal rollback when the real frame lands.

`stamps` is the ONLY place in sim-core or sim-host that reads `edgeHosted`. No combat, reach,
charge or click rule branches on which host a player is on.

### What core has to make identical

A cross-play handoff is one session with two very different delivery mechanisms, so the broker
builds each shared artefact **once** and ships the same bytes down both:

| Artefact | Modded side | Edge side |
|---|---|---|
| Frame 0 | `MatchSetupCodec` blob on `jackpotrollback:match_setup` | the same blob **re-addressed to the edge slot**, base64 in the assignment's `setup` |
| Arena | the **same** gzip, fragmented on `jackpotrollback:arena` | the same gzip, by sha256 key on global Redis |
| Wire | `UdpTransport` to `rollback.relay-host` | `UdpTransport` to the same relay; never a direct link |
| Cage | legacy cage payload | `EDGE_CAGE_MAGIC` payload, drop height forced to 0 |

Frame 0 is built once and each side gets a copy addressed to **itself**. The blob core sends the
modded client carries the mod's `slot` and the mod's slot token;
`MatchSetupCodec.readdress(setup, edgeSlot, tokens[edgeSlot])` rewrites just the address header -
`sessionId`, slot byte, length-prefixed token - and copies every following byte verbatim, and that
copy is what goes into the assignment's `setup`. Nothing downstream can tell the two apart: the
edge takes its slot and token from the assignment JSON, the mod takes them from its blob header,
and `MatchSetupFrame0Decoder` skips both fields on either side. `MatchSetupFrame0DecoderTest.`
`theAddressHeaderNeverReachesFrame0` is the gate that keeps re-addressing safe: it decodes two
blobs that differ only in that header and asserts the frame-0 checksums are equal.

Why it is done this way rather than shipping one blob to both: with derived tokens
(`SlotTokens.derive`) a slot token is the only thing standing between a process and that slot at
the relay. A single shared blob put the **modded** client's token in the hands of the edge hosting
the opponent, so a compromised edge could seat itself as the modded player and speak for them for
the whole match. Each host now holds exactly one token, and it is the token for the slot it is
entitled to bind. The relay's per-slot verification only means something if the tokens stay
per-slot on the way out.

### Frame 0 is verified, not assumed

One blob is necessary but not sufficient: the two hosts run two DIFFERENT decoders over it. The
edge runs sim-core's `MatchSetupFrame0Decoder`; the modded client runs its own `MatchSetup`. A
field one seeds and the other does not is a frame-0 divergence that no tick can explain, and it
surfaces at whatever later tick first reads that field - which is why the crossbow arrow-entry gap
looked like a round-2 desync.

Two things close it:

* `CrossPlayDecoderExecutionParityTest` (sim-core) **runs both decoders** over one setup blob and
  compares the states they produce. It reads the modded `MatchSetup.java` off disk, compiles it
  against sim-core plus four hand-written `net.minecraft` stubs, loads it in a child classloader and
  calls `decode`, then asserts `Checksum.of` and the `GameStateFrame0Codec` bytes are equal to what
  `MatchSetupFrame0Decoder` produced from the same bytes, plus the arena hash, the spawn identities,
  the selected slots and the inventory nbt that ride alongside the GameState. It fails the build
  rather than skipping when either file is missing.

  Stubbing is honest here because the only Minecraft the decoder touches is
  `readItemIdSet`, which resolves `Material` names to client item ids for the breakable/placeable
  gates - and those ids land in `MatchSetup` fields, never in `GameState`. Three tests keep that
  true: one asserts no `net.minecraft` reference outside the four stubbed types (a new one fails the
  build and says the gate can no longer cover the decoder), one asserts the stubbed lookup really
  ran and resolved the names in the blob, which is also what proves the byte offsets after it, and
  one asserts nothing it produced reached the checksum.

  Three further tests keep the comparison from being vacuous. The blob is checked to move **every**
  field the canonical decoder writes off its default value (50 of 50 at the time of writing), so a
  modded decoder that skipped one could not hide behind a default. Every one of those fields is
  checked to be visible to `Checksum.of`, by reverting it and requiring the checksum to change. And
  five deliberately broken copies of the modded decoder - crossbow seeding removed, host-kind byte
  dropped, name-set read shortened, and so on - are compiled and run, each required to diverge. The
  chain is: the blob exercises every field, the checksum sees every field, the two decoders agree on
  the checksum. Together those say the modded decoder cannot have omitted any of them.

  One nuance the mutation run turned up and that is worth knowing before reading a future failure:
  the per-player combat stats on the wire (`armor`, `protection`, `attackDamage`, `attackSpeed`,
  `knockbackLevel`) are advisory. `LoadoutCaps.seal` recomputes them from the replicated item
  dictionary at the end of both decoders, so a host that read those bytes wrongly still lands on the
  same frame 0. That is the authoritative-loadout-table design working, not a hole in the gate: the
  fields are checksummed, they are just not sourced from where they appear to be.
* `ArenaAgreement` now carries `stateChecksum` - `Checksum.of` over the decoded frame 0 - alongside
  the arena hash and the spawns. Both hosts exchange it before the first tick, so a frame 0 they do
  not share is refused as `frame 0 state local=... peer=...` instead of being discovered as a
  desync several rounds in. A dev match with no setup blob states `NO_STATE` and is not compared on
  one.

Cage drop height is forced to `0.0` because the edge path normally raises the frame-0 spawns by it
while the mod path never has. One spawn set, no drop.

### The arena, which was the real blocker

The two hosts used to derive collision from the same palette by two different routes: the edge from
the shipped `ArenaCodec` palette geometry, the mod from `BlockState.getCollisionShape` on the
client. Those agree for most vanilla blocks and disagree for a knowable set (a block with an empty
collision shape but a full outline is a full cube to the mod and decor to the server; a shape with
more than 256 boxes collapses to a full cube server-side only). `ArenaAgreement` would catch it and
abort, which is safe but useless.

Cross-play closes it by making the mod adopt the shipped numbers: `ArenaBlocks` now keeps the
`ArenaCodec.Snapshot` when the blob carries a palette-geometry section, and `buildArena` calls
`ArenaCodec.toArena` on it - the identical call the edge makes on the identical bytes. A legacy blob
without the section has no snapshot and the client-side derivation is used exactly as before, so
mod-versus-mod is untouched. Cross-play therefore **requires** a real published arena and refuses
the dev-spawn fallback outright.

### Result path

A cross-play session is registered in `EdgeSessionBroker` under the broker's own session id, so
both reports land in one `Report[2]` and the existing corroboration rules arbitrate. The edge's
report arrives over Redis within about a second; the modded client's arrives only after it has
transferred back to the network and rejoined, which takes several seconds. The arbitration grace
for a cross-play session is therefore 45s instead of 5s, so a lone self-claim waits for the mod's
report rather than being decided on a player directory that cannot see the limbo. A modded abort
(`winnerSlot == -1`) is filed as `MOD_ABORT` and voids the session.

---

## Unverified

Every item here is a guess about runtime behaviour that could not be checked without Redis,
Mongo, Velocity and multiple regional servers. None of it should be read as done.

1. **`ArenaBlocksCodec` produces no palette-geometry extension, but `ArenaCodec.toArena`
   requires one and throws without it.** The Fabric mod resolves palette strings to geometry
   client-side using its own registry. Nothing in mcleagues-core does. A new server-side
   palette-to-geometry resolver on the broker is required, and whether Paper can produce blast
   resistance, collision boxes and drop item ids for every palette string that match what the
   mod derives is unverified. This is the largest single gap in the design.
2. **Nothing has ever sent a `ControlProtocol.Authorize`.** The referee, `SessionReferee`,
   `RefereeManager` and the control endpoint all exist and have unit tests, but the end-to-end
   authorize-then-result path has never run. The design leans on it heavily for result
   arbitration.
3. **Whether the Velocity proxy will route a player to an arbitrary `host:port` that is not a
   registered server** via `LocalSimTransferMessage`. Asserted by a comment in `config.yml`;
   the proxy code is in another repo and was not read.
4. **Whether a proxy in region A can transfer a player directly to a backend in region B in
   one hop**, or whether a `ProxyTransferMessage` region hop is required first. Nothing in
   mcleagues-core establishes this, and it decides whether the transfer step is one message or
   two.
5. **Whether the broker holds usable ping data for both players.** The design reads the global
   mirror `practice:pingprofile:<uuid>`, which is written on a debounce and expires after 7
   days. A player who has never been ping-calibrated has no entry, and the ladder falls through
   to their current location's region. How often that happens in practice is unknown.
6. **Whether an unmodded vanilla client can be rendered a rollback match acceptably at all.**
   `EdgePlugin` explicitly does not paste blocks and warns that the world must already contain
   the same geometry as the sim arena. Today the mod renders client-side from the arena blob.
   An edge serving vanilla clients has to actually build the world, and that capability does
   not exist in the edge module. This is a phase-scoping risk, not a protocol risk, but it
   gates the whole feature.
7. **`RollbackModRegistry.clear(uuid)` has no caller found.** Entries appear to persist for the
   JVM lifetime, which matters if the mod gate is kept as a fallback alongside edge mode.
8. **The shipped `config.yml` and the code defaults disagree** on `kit-delay-ticks` (5 vs 10)
   and `player-wait-interval-ticks` (5 vs 10). Whatever is actually deployed wins, so real
   handoff timing is not what the code suggests. Any timeout tuned in this document assumes the
   code defaults.
