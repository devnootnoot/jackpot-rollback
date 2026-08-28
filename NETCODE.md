# Netcode stack: runtime topology

What runs, what it listens on, what it talks to, and which Redis keys carry the traffic.

`ARCHITECTURE.md` explains why the system is shaped this way. `DEPLOYMENT.md` explains how to ship it.
This file is the reference you keep open while debugging one.

---

## 1. The four server types

Three of them are deployed from this repository. The fourth is the game server that brokers the match
and belongs to `mcleagues-core`.

| Type | `SERVER_TYPE` | What it is | Listens on | Reached by |
| --- | --- | --- | --- | --- |
| Relay | `NETCODE_RELAY` | Headless UDP forwarder, plain JVM app (`relay.jar`) | UDP 7777, published to the host | Players' machines and edges, by public IP |
| Netcode limbo | `NETCODE_LIMBO` | Minimal packet-replay server (`limbo.jar`) that holds a modded client while its arena streams | TCP 25565 on the overlay only | The Velocity proxy, by internal DNS |
| Edge | `NETCODE_EDGE` | Paper server running the `JackpotEdge` plugin, hosts a vanilla player's half of a duel | TCP 25565 on the overlay, UDP 7787 published to the host | The proxy for players (by server name); other edges for the direct link (by public IP) |
| Game | `GAME` | `mcleagues-core`. Brokers the duel, prepares the arena, arbitrates the result | Normal backend | The proxy |

Regions are per-stack: one relay, one netcode limbo and one or more edges per region that serves
rollback duels, each with `REGION` set to that region's name.

---

## 2. Two match topologies

### Mod versus mod (the original path)

```
player A client (sim host)  --UDP-->  relay :7777  <--UDP--  player B client (sim host)
        |                                                            |
        +-- both are parked on the netcode limbo while the arena streams --+
```

The players' machines are behind NAT, so they cannot dial each other. The relay is mandatory here and
is not going away.

### Edge versus edge

```
vanilla player A  --TCP-->  edge A (sim host)  ==UDP==  edge B (sim host)  <--TCP--  vanilla player B
                                    |                          |
                                    +------ relay :7777 -------+   (fallback path)
```

The two edges are servers we run, with routable addresses, so they can dial each other directly on UDP
7787. That is `DirectLink`, and it is off by default (`rollback.edge.direct-links`). When it is off, or
when either edge advertises no direct port, or when both players landed on the same edge, the match
runs over the relay exactly as before.

The edge path uses no limbo: the vanilla player waits on the edge itself while the arena pastes.

---

## 3. Ports

| Port | Protocol | Who binds it | Must be reachable from |
| --- | --- | --- | --- |
| 7777 | UDP | relay | The public internet (players' machines) and every edge |
| 7787 | UDP | edge (`DirectLink`) | Every other edge in the fleet, in every region. Not from players |
| 25565 | TCP | edge, netcode limbo | The Velocity proxies, over the cluster overlay only. Do **not** publish these |

The direct link is one long-lived socket per edge that multiplexes every direct match, which is why it
is a fixed port rather than an ephemeral one, and why each datagram carries a 37-byte
`LinkFrame` header - magic, session, slot, send counter and a 16-byte HMAC tag - in front of the
`Protocol` payload.

---

## 4. Redis keyspace

Everything the edge path uses lives on **global** Redis (`REDIS_HOST` / `REDIS_PASSWORD`), not local.
An edge in one region has to be visible to a broker in another, so local Redis cannot work.

| Key | Type | Written by | Read by | Lifetime |
| --- | --- | --- | --- | --- |
| `rollback:edge:servers` | HASH `edgeId` to JSON | Edge heartbeat, every 1s | `EdgeRegistry.live()` | Entries older than 30s are ignored; the hash itself has a 120s TTL that the heartbeat refreshes |
| `rollback:edge:assign:<edgeId>` | LIST of assignment JSON | `EdgeSessionBroker.assign` | `EdgeBroker.drainAssignments`, up to 16 per poll | 300s key TTL, 120s per-assignment expiry |
| `rollback:edge:arena:<sha256>` | STRING (binary) | `EdgeArenaBroker.publish` | Both edges of the session | 1800s, refreshed on reuse |
| `rollback:edge:results` | LIST of result JSON | Edge result outbox | `EdgeSessionBroker.drainResults`, up to 32 per drain | Trimmed to the newest 4096 |
| `rollback:edge:metrics` | HASH `edgeId` to JSON | Edge metrics snapshot, every broker poll (1s) | `RollbackMetrics.refresh()` | Entries older than 120s are ignored; 300s key TTL refreshed on write |
| `rollback:metrics:fleet` | STRING (prometheus text) | `RollbackMetrics.publishFleet`, every 5s | Any scraper that can reach redis but not the edges | 300s |
| `rollback:return:<uuid>` | STRING | core | core | Short-lived return bypass marker |

The heartbeat value is:

```json
{"edgeId":"...","region":"ASHBURN","host":"...","port":25565,
 "directHost":"...","directPort":7787,"at":1734000000000}
```

`directHost` and `directPort` are omitted entirely when the direct link is disabled or failed to bind,
and their absence is exactly how the broker decides that a pair must use the relay.

For the mod-hosted path core also publishes over pub/sub channels rather than keys:
`jackpotrollback:match_setup`, `jackpotrollback:arena`, `jackpotrollback:cage`,
`jackpotrollback:match_result`, and the limbo's own `jackpotrollback:return`.

---

## 5. Lifetime of one edge duel

1. Two vanilla players are matched. `RollbackHandoffManager.awaitPlayersThenStart` finds neither passes
   the mod gate and, with `rollback.edge.enabled` on, calls `tryEdgeHandoff`.
2. Core extracts the arena, publishes the bytes to `rollback:edge:arena:<sha>`, builds the cage payload
   and the kit setup bytes.
3. `EdgeSessionBroker.assign` picks two edges from `EdgeRegistry.live()`, mints a session id, two slot
   tokens and (if direct links are on and both edges qualify) one shared link token, and pushes one
   assignment per slot.
4. Both players are sent to their edge by server name. Each edge drains its assignment within 1s and
   logs `assignment received: ...`.
5. The player joins, the edge claims the assignment, fetches the arena blob, pastes it, applies the
   loadout and pins the player. Log: `match pending for <player> ... link=direct <addr>` or
   `link=relay <host>:<port>`.
6. `EdgeHandshake` agrees the arena with the peer. Log: `arena agreed with the peer: hash=...`.
7. Frame 0. From here the edge logs `sync: head=... confirmed=... rollbacks=... desync=-1` every 2s.
8. The sim confirms a winner. Each edge queues a result and the async poll pushes it to
   `rollback:edge:results` within 1s.
9. Core drains, corroborates and calls `finishFromRollback`, which keeps `rollbackResult=true` so ELO
   still scores.

Worst case from a peer going silent to a settled result is 31s: 20s peer timeout, 1s publish, 5s core
drain, 5s arbitration grace. `EDGE-PROTOCOL.md` section 6 is the full table.

---

## 6. What breaks what

| If this is down | Matches in progress | New matches |
| --- | --- | --- |
| Relay | Relay-path matches time out in 20s and are settled by cause. Direct-link matches are unaffected | Relay-path matches cannot start; the arena handshake times out at 30s and voids |
| One edge | That player's peer settles within 31s; the survivor wins by forfeit if the directory agrees | The dead edge ages out of `EdgeRegistry.live()` in 30s and is no longer picked |
| Global Redis | Unaffected (the sim is pure UDP). Results queue in each edge's bounded outbox and flush when Redis returns | Cannot start: the broker cannot publish assignments |
| Netcode limbo | Mod-hosted matches lose their holding area | Edge matches are unaffected; they use no limbo |
| The proxy | Players cannot be moved to their edge | Handoff aborts |
