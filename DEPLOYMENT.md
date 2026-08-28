# Deployment

How the netcode stack is shipped and operated. Three server types come out of this repository - relay,
netcode limbo and edge - and all three are deployed the same way as the rest of the network:
one `docker-compose.<type>.yml` in `mcleagues-docker-runner`, one `SERVER_TYPE`, one Dockhand stack per
region, redeployed by webhook.

`NETCODE.md` is the runtime topology. `ARCHITECTURE.md` is why the system is shaped this way.
`DEPLOY.md` is the release procedure: deploy ordering, the kill switch, the architecture pin and the
staged rollout. This document is how the stacks are built and wired.

---

## 1. How the runner works

Every server type in the network runs the **same image**: `ghcr.io/mcleagues-jackpotmc/mcleagues-docker-runner:latest`,
a JRE-25 base with git and node, whose entrypoint is `start.sh`.

`start.sh` does four things:

1. Reads `SERVER_TYPE` (upper-cased) and `REGION`. Both are mandatory; a missing one exits non-zero.
2. Switches on `SERVER_TYPE` to pick `GIT_REPO`, `SERVER_JAR`, `JVM_FLAGS` and `STARTUP_FLAGS`, each
   overridable by the matching top-level environment variable and each with a per-type default
   (`<TYPE>_GIT_REPO`, `<TYPE>_JVM_FLAGS`, `<TYPE>_STARTUP_FLAGS`).
3. Clones or hard-resets `/server` from the deploy repo at `GIT_BRANCH`. **The container has no
   persistent volume. The deploy repo is the server directory.** Anything not committed there does not
   exist at runtime, and any local change is destroyed on the next restart.
4. Writes `eula.txt` when `ACCEPT_EULA=true`, then `exec java $JVM_FLAGS -jar $SERVER_JAR $STARTUP_FLAGS`.

So deploying a new server type is three edits: a `case` arm in `start.sh`, a compose file, and a deploy
repo with the artifacts in it.

---

## 2. The existing netcode stacks

### Relay - `SERVER_TYPE=NETCODE_RELAY`

- Compose: `docker-compose.netcode-relay.yml`
- Deploy repo: `mcleagues-netcode`, which carries `relay.jar` at its root
- Jar: `./gradlew :relay:jar` produces `relay/build/libs/relay.jar`, a self-contained runnable jar
- Port: `${RELAY_PORT:-7777}:7777/udp`, published because players' machines dial it directly
- Metrics: `RELAY_METRICS_PORT` (default tcp/7779) serves prometheus text at `/metrics`. `RELAY_ID` labels the series. A metrics port that cannot bind is logged and ignored; the relay keeps forwarding
- **`RELAY_SLOT_SECRET` (required in production, and the relay is unreachable without it)**: the
  shared secret slot tokens are derived from. Core mints each side's token as
  `HMAC-SHA256(secret, "rollback-slot-v1" || sessionId || slot)` and the relay recomputes it, so a
  HELLO either presents the token its slot was minted for or it is refused. The relay stores nothing,
  needs no connection back to core, and never lets first arrival decide who owns a slot. Set the
  **same** value on every relay and on core as `ROLLBACK_RELAY_SLOT_SECRET` (or
  `rollback.relay-slot-secret` in the core config). If the two sides disagree, every bind is refused
  and `rollback_relay_unauthorized_total` climbs with `hello` flat
- **The bind address is the enforcement, not a checklist item.** Whether the weak path is reachable
  is decided by what the socket binds, and that is derived from whether a secret is configured -
  never from an operator remembering a variable:

  | `RELAY_SLOT_SECRET` | `RELAY_BIND` | Result |
  | --- | --- | --- |
  | set | unset | Binds `0.0.0.0`, mode `DERIVED`. **The production configuration** |
  | set | set | Binds what you asked for, mode `DERIVED` |
  | unset | unset | Binds `127.0.0.1`, mode `PINNED_LOOPBACK`. Trust on first use, reachable only from the same machine - through a published docker port it is reachable from nothing. **The development configuration**, and what a production stack that forgot the secret gets: a visible outage rather than a silent downgrade |
  | unset | routable | **Refuses to start**, exits 78, `RELAY REFUSED TO START` banner. In a container this is a restart loop |
  | unset | routable + `RELAY_ALLOW_PUBLIC_WITHOUT_SECRET=true` | Binds it, mode `PINNED_PUBLIC`, and screams on stderr every start. The only opt-in onto the weak path over the network |

  The last startup line names the mode: `listening on udp/<host>:<port> in <MODE> mode`. `DERIVED` is
  the only mode acceptable in production, and it is also the only one that needs no extra variable
- **`RELAY_CONTROL_SECRET` (optional)**: setting it enables the referee and a TCP control endpoint,
  authenticated by this secret, on `RELAY_CONTROL_PORT` (default 7778). Unset, nothing binds that
  port. Keep it on the cluster overlay; it is not a metrics port. The referee is mutually exclusive
  with direct edge links for a given match, because the referee's only vantage point is the relay it
  tees from
- `RELAY_ALLOW_UNAUTHENTICATED` disables slot authentication entirely - every HELLO binds, no token
  needed - and is **ignored while `RELAY_SLOT_SECRET` is set**. It also permits a routable bind, so it
  subsumes `RELAY_ALLOW_PUBLIC_WITHOUT_SECRET`. Isolated local testing only
- `RELAY_BIND` picks the address to listen on. Leave it unset: with a secret it binds every interface
  and without one it binds loopback, which is what makes forgetting the secret fail closed. Setting it
  to a routable address without a secret is the one combination the relay refuses to start on
- The relay is **not** in the lockstep set. It compares the two peers' advertised protocol versions
  against each other, never against its own, so a relay from an older tree still aborts a mismatched
  pair correctly. Same for the limbo, which carries no sim
- Memory: 1G default, `-Xms256M -Xmx512M`
- One per region. The port it binds comes from the compose, so `STARTUP_FLAGS` stays empty.

### Netcode limbo - `SERVER_TYPE=NETCODE_LIMBO`

- Compose: `docker-compose.netcode-limbo.yml`
- Deploy repo: `mcleagues-netcode`, which carries `limbo.jar`, `limbo.properties` and the captured
  packet blobs. There is one pair per Minecraft version the mod builds, and they ship together:
  `config.bin` / `play.bin` (1.21.11, protocol 774), `config-26.1.2.bin` / `play-26.1.2.bin`
  (protocol 775) and `config-26.2.bin` / `play-26.2.bin` (protocol 776). A version whose pair is
  missing is now a startup failure rather than a limbo that runs and drops those clients
  mid-handshake, so all six files must be copied whenever `limbo.jar` is. Record a missing one with
  `gradlew devLimboCapture -PcaptureVersion=<version>` and verify with `gradlew devLimboProbe`
  (RUNBOOK section 6)
- `STARTUP_FLAGS` is the path to `limbo.properties`
- No published port. The proxy reaches it over the cluster overlay by DNS name, which is why
  `FORWARDING_SECRET` must equal the proxy's `forwarding.secret`: the limbo has to complete the
  Velocity `velocity:player_info` handshake like any other backend
- The proxy registers it under `NETCODE_LIMBO_SERVER_NAME`, and core routes players to it by that name
  via `rollback.limbo-server`

---

## 3. The edge stack - `SERVER_TYPE=NETCODE_EDGE`

The edge is the new one. It is a **Paper server**, not a plain JVM app, so it needs a full server
directory and Paper configuration on top of the runner pattern.

### 3.1 `start.sh` case arm

Add to `mcleagues-docker-runner/start.sh`, next to the other two netcode arms:

```sh
  NETCODE_EDGE)
    GIT_REPO="${GIT_REPO:-${NETCODE_EDGE_GIT_REPO:-github.com/mcleagues-jackpotmc/mcleagues-netcode-edge.git}}"
    SERVER_JAR="${SERVER_JAR:-server.jar}"
    JVM_FLAGS="${JVM_FLAGS:-$NETCODE_EDGE_JVM_FLAGS}"
    STARTUP_FLAGS="${STARTUP_FLAGS:-${NETCODE_EDGE_STARTUP_FLAGS:-nogui}}"
    ;;
```

and add `NETCODE_EDGE` to the `Expected PROXY, LOBBY, ...` line in the `*)` arm, so an operator who
typos the type gets a list that contains it.

### 3.2 Compose file

`edge/deploy/docker-compose.netcode-edge.yml` in this repository is the file to copy into
`mcleagues-docker-runner` as `docker-compose.netcode-edge.yml`. It is kept here so it versions with the
plugin whose environment contract it encodes. It carries no credentials: `REDIS_HOST`, `REDIS_PORT` and
`REDIS_PASSWORD` are supplied by the Dockhand stack environment, the same values the game stack uses.

Two differences from the relay and limbo composes are deliberate:

- **The Minecraft port is not published.** Players reach an edge through the proxy over the cluster
  overlay, exactly as they reach a game server. Publishing 25565 would expose the edge to direct
  connections that bypass Velocity forwarding.
- **UDP 7787 is published.** That is the direct edge-to-edge link, and it must be reachable from every
  other edge in every region. It must not be reachable from players.

If the fleet's UniverseSpigot build is used instead of stock Paper, add `UNIVERSE_KEY`,
`UNIVERSE_PROFILE`, `UNIVERSE_VERSION` and `UNIVERSE_PLUS` with the same values as the game stack.

### 3.3 Environment variables

Runner variables, identical in meaning to every other stack:

| Name | Purpose |
| --- | --- |
| `SERVER_TYPE` | `NETCODE_EDGE`. Selects the `start.sh` arm. Also invisible to `GameCountRepository`, which only registers LOBBY, GAME and FFA, so an edge appears in no existing server picker |
| `REGION` | The region this edge serves. Read by the plugin and published in the heartbeat; `EdgeRegistry.pickFor` uses it to put a player on an edge in their own region. Must be a name `Region.safeValueOf` accepts |
| `GIT_REPO` / `NETCODE_EDGE_GIT_REPO` / `GIT_BRANCH` | The deploy repo and branch that becomes `/server` |
| `SERVER_JAR` | `server.jar`, the Paper jar committed in the deploy repo |
| `NETCODE_EDGE_JVM_FLAGS` | Heap and GC. Default `-Xms2G -Xmx4G` with G1 tuned for a 50ms pause target: an edge runs a fixed-timestep sim, so a long GC pause is a rollback storm |
| `NETCODE_EDGE_STARTUP_FLAGS` | `nogui` |
| `ACCEPT_EULA` | `true`, writes `eula.txt` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | **Global** Redis. This is the whole discovery mechanism: the edge heartbeats into it and reads its assignments from it. Local Redis is not used and must not be substituted, because a broker in another region has to see this edge |

Plugin variables, all read by `EdgePlugin` with the environment taking precedence over `config.yml`
(the same order `mcleagues-core` uses for its `ROLLBACK_*` variables). The one exception is `REGION`:
a non-blank `broker.region` in `config.yml` wins over it, so that the local dev stack, which writes a
different region into each edge's config, is not silently collapsed onto one region by a stray `REGION`
in a developer's shell.

| Name | Purpose |
| --- | --- |
| `EDGE_BROKER_ENABLED` | `true` in production. False leaves the plugin in `/edge` local-testing mode: no heartbeat, no assignments, config-driven single session |
| `EDGE_ID` | The edge's identity. It is the Redis hash field, the assignment queue name, **and the Velocity server name players are sent to**, so it must equal the name the proxy has registered for this container. Empty falls back to `$HOSTNAME` |
| `EDGE_EXPECTED_ARCH` | **The cpu architecture the whole fleet runs on**, `x86_64` or `aarch64`. `EdgePlugin.startBroker` compares it against this JVM's `os.arch` through `EdgeArchGate` and **refuses to enable brokering** if it is unrecognised or different from the running architecture. Unset warns loudly and brokers anyway, because unset is the shipped default and refusing it would stop every pre-gate deployment and the local dev stack from brokering at all; setting it is what makes the rule enforced, since until the arm64 half of the determinism gate is green a mixed fleet desyncs every match between its halves while printing identical version fences. `/edge` local testing is unaffected. See `DEPLOY.md` section 3 |
| `EDGE_PUBLIC_HOST` | The address published in the heartbeat, and the default advertise host for the direct link. A loopback value here is logged as a warning at startup |
| `EDGE_PUBLIC_PORT` | The Minecraft port published in the heartbeat. Advisory today; core routes by name, not by address. `0` or unset means the server's own port |
| `EDGE_DIRECT_ENABLED` | Bind the direct edge-to-edge UDP socket. `true` is safe to leave on: an edge only ever uses the direct path when an assignment carries a peer endpoint, and only core decides that |
| `EDGE_DIRECT_PORT` | UDP port for the direct link, default 7787. One socket carries every direct match on this edge |
| `EDGE_DIRECT_BIND` | Bind address, empty for all interfaces |
| `EDGE_DIRECT_ADVERTISE_HOST` | What to publish as `directHost` when it differs from `EDGE_PUBLIC_HOST` - the node's public IP when the container is behind a published port |
| `EDGE_DIRECT_HOST_PORT` | Compose-level only: the host port mapped to container 7787 |
| `EDGE_METRICS_ENABLED` | Serve the prometheus/human metrics endpoint. `false` keeps the counters and the redis publish and only stops the HTTP listener |
| `EDGE_METRICS_PORT` | TCP port for `/metrics`, `/healthz` and the human summary at `/`, default 7788. `/healthz` returns 503 while the desync alert is firing |
| `EDGE_METRICS_BIND` | Bind address for the metrics endpoint, empty for all interfaces. Set it to an internal address if the box is exposed |
| `EDGE_METRICS_HOST_PORT` | Compose-level only: the host port mapped to container 7788 |
| `EDGE_LIMBO_HOST` / `EDGE_LIMBO_PORT` | Where `EdgeModHandoff` transfers a client whose own assignment slot is MOD-hosted. Defaults to `127.0.0.1:25565`, which is this container in production, so set them to the `NETCODE_LIMBO` container if that path is ever exercised here. On the network it is `mcleagues-core`'s `RollbackHandoffManager` that does the mod handoff, not an edge |
| `EDGE_METRICS_SIM_PROBE` | Install the sim probe sink so claim-rejection counters are collected. Turning it off removes an array increment from the sim hot path and zeroes those metrics; nothing else changes |

Core-side settings, on the **game** stack, not on the edge. They resolve through
`RollbackHandoffManager.cfgBool`/`cfgStr`, which check the environment first and fall back to
`config.yml`'s `rollback.` block.

**The nested ones are reachable from the environment.** `RollbackHandoffManager.envKey` builds the
environment name as `"ROLLBACK_" + key.toUpperCase().replace('-', '_').replace('.', '_')`, so
`edge.enabled` is `ROLLBACK_EDGE_ENABLED` and `edge.cage.drop-height` is
`ROLLBACK_EDGE_CAGE_DROP_HEIGHT`. It replaced hyphens and not dots until recently, which made every
nested key environment-proof; `DEPLOY.md` section 5.4 has the history and what to check on a stack
that carried the dotted workaround.

| Config key (under `rollback:`) | Purpose |
| --- | --- |
| `edge.enabled` | Master switch for the edge path, and the kill switch. Off, a vanilla-versus-vanilla rollback duel aborts on the mod gate exactly as it does today |
| `edge.direct-links` | Write the peer block into assignments. Off, every edge match uses the relay |
| `edge.relay-host` / `edge.relay-port` | The relay endpoint **pinned into the assignment**, so both edges dial the same relay by construction rather than by both happening to have the same config file. Falls back to `relay-host` / `relay-port` |
| `edge.require-real-arena` | Default true. Refuses the handoff rather than starting a match on the dev spawns with no shared arena bytes |
| `edge.cage.drop-height` | Baked into the assignment spawn Y so both peers agree by construction. Clamped to the ceiling the sim's cage fall grace can cover |
| `cross-play` | Allow a modded client and an edge-hosted vanilla client in the same duel. Reachable as `ROLLBACK_CROSS_PLAY` |
| `relay-slot-secret` | Must equal every relay's `RELAY_SLOT_SECRET`. Reachable as `ROLLBACK_RELAY_SLOT_SECRET`. Unset, core mints random per-slot tokens and warns once, and the relays it talks to are bound to loopback and unreachable - so this is not optional in production either |

### 3.4 The deploy repo

`/server` is a git checkout, so the edge deploy repo is a complete Paper server directory:

```
server.jar                     Paper (or the fleet's UniverseSpigot build)
server.properties
bukkit.yml  spigot.yml
config/paper-global.yml        velocity forwarding block
config/paper-world-defaults.yml
plugins/jackpot-edge.jar       ./gradlew :edge:jar  ->  edge/build/libs/jackpot-edge.jar
plugins/JackpotEdge/config.yml optional; every production value comes from the environment
world/                         the arena world, or let Paper generate it on first boot
```

`jackpot-edge.jar` is self-contained: PacketEvents, Jedis, Gson, `sim-core` and `sim-host` are all
inside it, and `paper-api` is `compileOnly`. Nothing else goes in `plugins/`. In particular do not add
`mcleagues.jar`: an edge is not a game server, it registers itself through its own Redis keyspace, and
adding core would put it into the lobby/game/FFA pickers.

The plugin's `config.yml` matters for exactly one production value - the local `arena.*` paste budget -
because every determinism-critical value (spawns, ground-y, rounds, kit, cage, arena) arrives in the
assignment. Two settings are outright dangerous in production and are logged loudly at startup:
`dev-kit.enabled` and `rounds`, both of which only apply to `/edge` but both of which will desync a
match if the two edges disagree while using it.

### 3.5 Paper configuration

| File | Setting | Value | Why |
| --- | --- | --- | --- |
| `config/paper-global.yml` | `proxies.velocity.enabled` | `true` | The edge is a backend behind Velocity |
| | `proxies.velocity.online-mode` | `true` | |
| | `proxies.velocity.secret` | the fleet's forwarding secret | Must equal the proxy's `forwarding.secret`, same as every game server |
| `server.properties` | `online-mode` | `false` | Authentication happens at the proxy |
| | `server-port` | `25565` | Overlay only, not published |
| | `spawn-protection` | `0` | The arena is pasted around the origin |
| | `network-compression-threshold` | `-1` | Compression is the proxy's job; the edge sends a lot of small mirror packets |
| | `view-distance` | `6` | The edge mirrors the sim; it does not need a large loaded radius |
| | `simulation-distance` | `4` | Nothing vanilla is simulated. The sim is |
| | `allow-flight` | `true` | Players are pinned with gravity disabled before frame 0 and are moved by the mirror afterwards. Paper's flight check would otherwise kick them |
| | `level-type` | flat or void | The arena is pasted in. Generated terrain is wasted work and can clip the paste |
| | `max-players` | comfortably above the region's concurrent duel count | Two players per match, but every match holds a slot for its whole life |
| | `white-list` | `false` | Players are sent here by name with no chance to be added first. A whitelisted edge rejects every duel it is assigned |

The plugin locks the arena conditions itself once per world and re-asserts them every 5s: mob spawning,
daylight cycle, weather, fire tick, mob griefing, patrol/trader spawning and insomnia all off, time
pinned to noon, weather cleared, mobs purged. Do not fight it from `bukkit.yml`.

### 3.6 Registering the edge with the proxy

Core moves a player to their edge with `PlayerSendToServerMessage(uuid, edgeId)`, which the proxy
resolves as a **registered Velocity server name**. So for every edge container:

```
EDGE_ID  ==  the Velocity server name  ==  the name the proxy resolves to this container
```

Set `EDGE_ID` explicitly (for example `edge-ashburn-1`) rather than letting it fall back to a container
hostname, and register that exact name on the proxy the same way `NETCODE_LIMBO_SERVER_NAME` is
registered, pointing at the container's overlay DNS name on 25565. An edge whose name the proxy does
not know will heartbeat, will be picked by the broker, and will silently never receive its player: the
symptom is `waiting for <player> to join THIS edge` on the edge and a handshake timeout 30s later.

### 3.7 Dockhand

Same as every other stack. Create one Dockhand stack per region per type, then record the stack ids in
`dockhand-tools/dockhand-stacks.env`:

```
NETCODE_RELAY_STACKS=""
NETCODE_LIMBO_STACKS=""
NETCODE_EDGE_STACKS=""

ALL_STACKS="$PROXY_STACKS $LIMBO_STACKS $LOBBY_STACKS $GAME_STACKS $NETCODE_RELAY_STACKS $NETCODE_LIMBO_STACKS $NETCODE_EDGE_STACKS"
```

and add the matching `netcode-edge)` arm to `dockhand-tools/redeploy.sh` and its usage line.
`./redeploy.sh netcode-edge` then POSTs the git webhook to each stack in turn, which is what makes a
new `jackpot-edge.jar` committed to the deploy repo go live.

### 3.8 Rollout order

Flags are separate from deployment on purpose. Deploy first, switch on afterwards, one step at a time.
The abbreviated version is below; `DEPLOY.md` section 4 is the staged plan with the signal to watch
and the abort criteria at each stage, and `DEPLOY.md` section 1 is the ordering for a release that
changes the sim.

1. Deploy the edges with `EDGE_BROKER_ENABLED=true` and core's `ROLLBACK_EDGE_ENABLED` still **off**.
   The edges heartbeat and receive nothing. Confirm they appear in `rollback:edge:servers`.
2. Turn on `ROLLBACK_EDGE_ENABLED` on one region's game servers. Vanilla-versus-vanilla rollback duels
   start flowing over the relay.
3. Only after UDP 7787 reachability has been verified **for every region pair**, turn on
   `ROLLBACK_EDGE_DIRECT_LINKS`. A firewalled direct port does not fall back: both edges bind, neither
   hears the other, and every cross-region match on that pair voids as `PEER_NEVER_ARRIVED` 30s in.

To drain an edge, stop the container. It deletes its own heartbeat field on shutdown, reports every
pending and live match first, and ages out of `EdgeRegistry.live()` within 30s regardless.

### 3.9 Capacity: how many matches one edge can host

`EdgePlugin.tickAll` walks every `EdgeMatch` on the Bukkit main thread, once per tick. One
`EdgeMatch` is one **player**, not one duel: a duel where both players sit on the same edge costs two
sim drivers, and a cross-region duel costs one driver on each of the two edges. The numbers below are
therefore quoted per driver.

Measured with `./gradlew :sim-host:loadHarness`, which runs whole matches through the real
`MatchDriver`/`NetSession` over `LoopbackNetwork`.

**Read the table as one sample, not as a specification.** It is a single pass of the sweep, on one
developer desktop, with no repeats and therefore no error bar: an AMD Ryzen 7 9800X3D
(8 cores / 16 threads), Windows 11, Temurin-class JDK 21.0.6, `-Xms2G -Xmx4G` with G1 at a 50ms
pause target, paced at a real 20Hz, on a 50ms one-way link with 50ms of jitter and 1% loss, with
every peer frozen for 2s every 30s to force catch-up bursts. Each row is ~4000 measured ticks, so
p99.9 is roughly the fourth-worst tick of the run and moves by several ms between runs on the same
box. The figures are rounded to the precision the data supports; do not read a few ms of difference
between two adjacent rows as a trend, and do not use these as an acceptance threshold. Nothing in
CI reproduces them.

| drivers | duels co-hosted | tick p50 | tick p99 | tick p99.9 | alloc per edge tick |
| --- | --- | --- | --- | --- | --- |
| 16 | 8 | ~0.4ms | ~3ms | ~10ms | ~1.3MiB |
| 64 | 32 | ~1.2ms | ~11ms | ~22ms | ~5MiB |
| 128 | 64 | ~2.4ms | ~24ms | ~33ms | ~11MiB |
| 160 | 80 | ~3.0ms | ~29ms | ~43ms | ~13MiB |
| 192 | 96 | ~3.7ms | ~31ms | ~46ms | ~16MiB |

Every row was run; none is extrapolated. What *is* extrapolated is the leap from these numbers to a
real edge: the harness has no world, no entities, no chunk ticking, no packet I/O and no
`EdgeRenderer`, so it measures the driver pass alone and nothing else Paper does in the same tick.
An edge that also has to render is slower than this, never faster, by an amount nobody here has
measured. Reproduce with `./gradlew :sim-host:loadHarness -PloadArgs="--matches 8,32,64,80,96
--paced --stall-every 600 --stall-ticks 40 --json out.json"` and keep the json if the number is
going to be quoted anywhere.

What the shape of the table says is sturdier than any individual cell: the p50 per-driver cost is
roughly flat from 64 drivers upward, at about 19us of tick per driver, so the pass scales linearly
and only the tail grows. The tail is what a fixed timestep cares about, and at 192 drivers p99.9 is
in the mid-40s of ms against a 50ms budget.

**Budget an edge at 128 drivers (64 co-hosted duels), and treat 192 as the hard stop.** At 128 that
leaves the better part of the tick for everything Paper itself does, which the harness does not
measure at all.

The binding resource is **heap, not CPU**. A driver retains a `StateRingBuffer` of
`EdgeMatch.RING_CAPACITY` = 1024 `GameState` snapshots; measured after a forced GC that is
**about 14MiB of live heap per driver**. 128 drivers is then roughly 1.8GiB live and 192 drivers
roughly 2.7GiB, which is why the default `-Xmx4G` leaves G1 little room to work in well before the
tick budget is the thing that runs out.
Halving `RING_CAPACITY` halves the live set; it also halves how far behind a peer may fall before
the session aborts, so do not change it without re-reading `NetSession.freeRunCeiling`.

---

## 4. Operational runbook

### 4.1 Is this edge healthy?

Five checks, cheapest first.

**Startup lines.** A healthy edge logs, in this order:

```
arena paste: on, up to 16384 blocks/tick capped at 30ms/tick
edge ready: slot=0 relay=... session=... /edge rounds=1
direct edge link listening on udp/7787, advertised as <host>:7787
broker enabled: edgeId=<id> region=<REGION> redis=<host>:6379 advertising <host>:25565 direct-link udp/7787
forfeit bound: a peer that stops sending is declared gone after 400 ticks (20s)
movement validation: clamp ceiling=3.0 sustained=0.32 threshold=20
```

Missing `broker enabled` means `EDGE_BROKER_ENABLED` is not set: the edge is in `/edge` mode and will
take no matches. `(relay only)` at the end of that line, or a `could not bind the direct edge link`
severe, means the direct socket failed and every match on this edge falls back to the relay - a
degradation, not an outage.

**Is it in the registry?** On global Redis:

```
HGETALL rollback:edge:servers
```

Every live edge is one field. Check `region` is the region you meant, `at` is within the last few
seconds, and `directHost`/`directPort` are present if you expect direct links. An entry older than 30s
is ignored by the broker even though it is still in the hash.

**Is Redis reachable?** `redis unavailable: ...` is logged once on the transition to unhealthy and
`redis reachable again` once on recovery, so a healthy edge logs neither. While Redis is down the edge
keeps playing: results queue in a 256-entry outbox and are flushed oldest-first when it returns. The
severe you never want to see is `the result outbox overflowed and a match result was dropped`.

**Is it being given work?** `assignment received: ...` at the moment a duel is brokered, then
`match pending for <player> ... link=direct <addr>` or `link=relay <host>:<port>`.

**Is it playing?** Every 2s per live match:

```
sync: head=1240 confirmed=1236 rollbacks=87 desync=-1
```

`head` climbing, `confirmed` a handful of frames behind it, and `desync=-1` is a healthy match.

### 4.2 What the log lines mean

| Line | Meaning | Action |
| --- | --- | --- |
| `waiting for <player> to join THIS edge` | The assignment arrived but the player has not. Usually a proxy routing problem: the name in `EDGE_ID` is not registered on the proxy | Check 3.6 |
| `refusing the match ... the arena bytes for '<name>'` | The arena blob key was unreadable. The edge refuses rather than falling back to its local `arena.bin`, because a different arena is a guaranteed desync | Check the arena key TTL and global Redis |
| `arena agreed with the peer: hash=...` | Both hosts compared arena hash, ground-y, both spawns AND the frame-0 state checksum and matched. Frame 0 follows | None. This is the line that says the match is real |
| `match refused for <player> - ...` | `EdgeHandshake` reached MISMATCH. The two edges disagree about the arena | See 4.3 |
| `session <id> keeps the relay: the opponent hosts the sim on their own client` | The peer is mod-hosted and cannot be dialled | None. Correct behaviour |
| `#### NO KIT ON THIS ASSIGNMENT ####` | Core shipped no setup bytes and `dev-kit.enabled` is off | Core-side. The match will start with empty hands |
| `dev-kit.enabled=true` warning | A hardcoded demo kit will be used when setup bytes are missing. **Both** edges must have identical settings and builds or frame 0 differs | Turn it off in production |
| `#### SIM ARENA / WORLD MISMATCH AT THE SPAWN POINT ####` | The sim thinks there is a block under the spawn and the world does not, with no paste to fix it | Check `arena.paste` and the world height fit |
| `edge match faulted: ...` | A `RuntimeException` inside the tick. Reported as `SELF_FAULT`, which concedes the match | Read the stack trace. This is a bug |
| `match aborted: <reason>` | The session ended without the sim confirming a winner | The reason string maps to the cause the broker is told |
| `the result outbox overflowed` | Redis has been unreachable long enough to lose results | Page. Every dropped entry is a match that will strand |

Causes reported to core, in decreasing order of "everything is fine": `FINISHED`, `LOCAL_QUIT`,
`LOCAL_FORFEIT`, `PEER_GONE`, `NO_FRAME_ZERO`, `PEER_NEVER_ARRIVED`, `ARENA_MISMATCH`, `DESYNC`,
`SELF_FAULT`. On the core side each one appears in the `[edge]` log with its session id.

### 4.3 A match desynced

`desync=<frame>` other than `-1` in the `sync:` line, or a result reported as `DESYNC` with
`checksum mismatch at frame N`, means the two peers computed different state at frame N. The sim is
deterministic, so this is never noise. Work down the list; the first three cover almost every real case.

1. **Different builds.** Compare `Protocol.CHECKSUM_REV` on both peers. It is packed into
   `Protocol.VERSION` and checked at `Hello`, so a mismatch normally aborts with code 1001 before frame
   0 - which means a desync at frame N with matching versions is the dangerous case: **someone changed
   sim behaviour without bumping the rev**. Check whether the reference digest moved in the deploy that
   preceded the first desync.
2. **Different architectures.** Two edges on x86_64 and aarch64 can produce different results from the
   same build. See 4.4. This is the reason the fleet is pinned.
3. **Different local configuration reaching the sim.** `dev-kit.enabled` on one edge and off the other,
   or `rounds` differing, or one edge loading a local `arena.bin` while the other used the assignment.
   The arena case is caught by `EdgeHandshake` and reports `ARENA_MISMATCH` instead, so if you see
   `DESYNC` the arena was agreed and the divergence came later.
4. **Reproduce it offline.** `./gradlew :sim-core:runHarness` on both edge hosts writes
   `sim-core/build/harness/checksums-<os>-<arch>.txt`. Copy both into one directory and run
   `./gradlew :sim-core:compareHarnessDigests -PharnessDir=<dir>` (add `-PharnessArches=1` if both
   hosts are the same architecture). It names the first tick where the two streams part company, the
   last tick where they agreed, and which part of the state moved first - `p0.motion`, `projectiles`,
   `blocks` and so on - which is usually enough to find the expression.
5. **If the streams agree but matches still desync**, the divergence is in what the *hosts* fed the sim,
   not in the sim. That is an input problem - the authority stamping, the loadout decode, the cage - and
   the referee replay path, not the digest, is what covers it.

Desyncs void rather than settle: neither peer can claim a win, so no ELO moves.

### 4.4 The architecture-pinning rule

From the determinism gate, and it is an operational rule, not a development one:

> **Until the cross-architecture gate is green, every edge in the fleet must run on the same CPU
> architecture.**

This is enforced at startup, not just written down: `EdgeArchGate` compares the JVM's `os.arch`
against `EDGE_EXPECTED_ARCH` and `EdgePlugin.startBroker` returns without starting the broker or the
direct link when they disagree or when the value is unrecognised. An edge with no value configured
brokers, but logs a WARNING banner saying it is unpinned and naming the value to set. `DEPLOY.md`
section 3 covers the verdicts and what each one looks like.

The gate is built to run the same harness on x86_64 and aarch64 and fail if a single tick differs, but
the aarch64 half is skipped until the repository variable `ARM64_RUNNER` names an arm64 runner, so as
things stand it has not passed. Until it has, two edges on different architectures can desync every
match between their players and nothing in the system will say why: the version fence compares build
numbers, not results, so both peers believe they agree.

What *has* been measured is the operating-system axis, which is the one that separates a Windows
development box from a production Linux edge container: the full harness has been run on Windows 11
(Oracle 21.0.6), on Linux (Temurin 21.0.12) and on Linux (Corretto 21.0.12.1), and all three agree on
every one of the 11740 ticks and on the stream, arena and rollback digests.

Concretely, when adding edge capacity: pick the node's architecture deliberately, and treat a mixed
fleet as an outage waiting for traffic rather than as a configuration detail. The same applies to the
JDK - the gate only exercises Temurin 21.

The companion rule for anyone shipping a change:

> If sim behaviour changes, `Protocol.CHECKSUM_REV` and `RollbackModRegistry.EXPECTED_VERSION` are
> bumped in the same commit as the re-recorded reference digest. Deploying a sim change without the rev
> bump desyncs every peer still on the old build, and the handshake will not catch it.

Deploy both sides in lockstep. A rev bump makes the old client mod and the old edge jar mutually
incompatible by design.

### 4.5 A match stranded

If a `Game` is stuck in STARTED with no result, the escalation is bounded and mostly automatic. Worst
case is 31s: 20s peer timeout, 1s publish, 5s core drain, 5s arbitration grace. The 30-minute
`ROLLBACK_MAX_MATCH_MILLIS` sweep is the backstop, not the mechanism. If you are waiting on it, either
both edges failed to report or Redis was unreachable for both of them - check the edge logs for
`redis unavailable` and the outbox depth first, and `EDGE-PROTOCOL.md` section 6 for the full failure
matrix.
