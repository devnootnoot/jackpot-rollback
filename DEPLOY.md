# DEPLOY

The release procedure for the rollback netcode: what order the three artifacts go out in, what a
player experiences while the two sides disagree, how to turn it off, and the staged plan for opening
it to traffic.

This is the third of four documents and they do not overlap:

| Document | Answers |
| --- | --- |
| `ARCHITECTURE.md` | why the system is shaped this way |
| `DEPLOYMENT.md` | how each server type is built, packaged and wired into the runner |
| **`DEPLOY.md`** (this file) | **how a release goes out, in what order, and how far it is opened** |
| `RUNBOOK.md` | what to do once it is running and something is wrong |

Read `DEPLOYMENT.md` section 3 first if you have never stood an edge up. This document assumes the
stacks already exist and only covers moving them from one build to the next.

---

## 0. The three artifacts, and why they cannot move independently

`Protocol.VERSION` packs the input frame width with the checksum revision:

```
Protocol.VERSION = (InputCodec.BYTES << 8) | (Protocol.CHECKSUM_REV & 0xFF)
                 =  170 165
                 = 17324
```

Three artifacts carry a copy of that fence, and every one of them is compiled from `sim-core`
except the last, which is a hand-transcribed integer:

| Artifact | Stack | Carries | Checked against |
| --- | --- | --- | --- |
| `jackpot-edge.jar` | `NETCODE_EDGE` | the sim, `Protocol.VERSION`, `ArenaAgreement.VERSION` | its peer, at HELLO and at the arena agreement |
| the mod jar | the player's machine | the sim, `Protocol.VERSION`, `ArenaAgreement.VERSION` | its peer, at HELLO and at the arena agreement |
| `mcleagues.jar` | `GAME` | `RollbackModRegistry.EXPECTED_VERSION`, a hand-written copy of the number | every connecting client's advertised version |

`VersionFenceTest` reads `RollbackModRegistry.java` off disk and fails the build if the transcribed
constant has drifted from what `sim-core` compiles to. It has drifted twice, which is why the test
exists. `RunbookVersionFenceTest` does the same for the triple printed in `RUNBOOK.md` section 0.

Two artifacts are **not** in the lockstep set and deploy on their own schedule:

- **`relay.jar`.** The relay compares the two peers' advertised versions *against each other*
  (`RelayServer`, `sessionVersions`), never against its own. A relay from an older tree still aborts
  a mismatched pair correctly and still forwards a matched one.
- **`limbo.jar`.** It replays captured packet blobs and contains no sim.

So the lockstep rule is narrower than "deploy everything at once". It is:

> **The edge fleet, the mod jar and core's `EXPECTED_VERSION` are one release. The edge fleet must
> never be split across two builds, even briefly.**

---

## 1. The lockstep deploy

### 1.1 Which kind of release is this?

| Change | `CHECKSUM_REV` | Deploy |
| --- | --- | --- |
| Simulated behaviour, or the checksum function | **bumped**, with the reference digest re-recorded and `EXPECTED_VERSION` updated in the same commit | full lockstep, section 1.2 |
| `InputCodec.BYTES` | unchanged rev, but `Protocol.VERSION` still moves | full lockstep, section 1.2 |
| `ArenaAgreement.VERSION` | not necessarily | full lockstep, section 1.2. The agreement refuses an older peer by design |
| Edge-only rendering, metrics, logging, config plumbing | unchanged | section 1.3, no drain needed |
| Relay, limbo | unchanged | on their own, any time |

If you are unsure which you have, run `./gradlew :sim-core:checkHarnessDigest`. If it fails, you
changed simulated behaviour and this is a full lockstep release whether you meant it to be or not.

### 1.2 Ordering for a full lockstep release

The ordering exists to solve one problem: **you cannot atomically redeploy N edge containers.**
`./redeploy.sh netcode-edge` POSTs a webhook to each Dockhand stack in turn, so for the length of
that loop the fleet holds two builds. Two edges on different builds abort every cross-region match
between them at HELLO with `ABORT_VERSION_MISMATCH` (1001). The kill switch is what makes that
window empty of traffic instead of full of it.

```
1.  core:  rollback.edge.enabled: false      (or ROLLBACK_EDGE_ENABLED=false, see section 5.4)
2.  wait:  rollback_fleet_edge_matches_live == 0
3.  edge:  commit the new jackpot-edge.jar, ./redeploy.sh netcode-edge
4.  check: rollback_fleet_version_fences == 1, and the triple is the new one
5.  core:  ship the new mcleagues.jar to the GAME stacks
6.  mod:   publish the new mod jar to the launcher
7.  core:  rollback.edge.enabled: true
8.  watch: section 4, stage by stage
```

Step 2 is not optional and step 4 is not optional. Everything else about this list is ordinary.

**Why core after the edges and not before.** Core's only fence is `EXPECTED_VERSION` against the
*client's* mod version. It does not check the edges at all. So core is safe to ship at any point in
the window, and shipping it last means the mod-version gate flips to the new number only once every
edge can actually host the new build.

**Why the mod last.** A player who updates early and queues into a still-old fleet is the one case
that produces a visible abort rather than a graceful downgrade (section 1.4). Publishing the mod
after step 4 means the fleet is already new when the first updated client arrives.

### 1.3 A non-simulated edge-only release

No drain, no core, no mod. Commit the jar, `./redeploy.sh netcode-edge`, confirm
`rollback_fleet_version_fences` is still 1 afterwards. In-flight matches on a restarting container
end as `NO_FRAME_ZERO` or `LOCAL_QUIT` for that box only, and the container reports every pending
and live match before it goes, so nothing strands. Roll the fleet one region at a time so a bad jar
takes out one region rather than all of them.

### 1.4 What a player sees while the two sides disagree

This is the part worth reading twice, because the answer is different for each pair, and one of the
four is silent.

**Core new, mod stale (the normal post-release window).** `RollbackModRegistry.get(uuid)` returns
the client's advertised version, it does not equal `EXPECTED_VERSION`, so `okA` is false and
`RollbackRouting.decide` falls through the MOD plan. With `rollback.edge.enabled: true` and a live
edge, the decision is `EDGE` with the reason *"neither client runs the expected mod build"* and both
players are hosted on the edge. **The duel plays normally.** The player is not told anything, does
not see an error, and loses only their client-side sim: their inputs now round-trip to an edge
instead of being simulated locally. This is the graceful window, and it is why step 6 above is not
urgent.

With `rollback.edge.enabled: false`, the same pair hits `REFUSE_NO_MOD`, the game is finished as a
draw, `rollback_core_mod_version_rejected_total` increments, and each player is told, in red:

- the stale player: *"Your rollback mod is outdated, please update it. Rollback duels require the
  MCLeagues mod."*
- a player with no mod at all: *"You don't have the rollback mod installed. ..."*

So the kill switch converts a silent downgrade into a visible refusal. During a drain that is
correct. Left on by accident it reads to players as "ranked is broken".

**Mod new, core stale.** Identical shape, opposite side. `EXPECTED_VERSION` still holds the old
number, the updated client fails the same gate, and gets the same downgrade or the same refusal.

**Edge stale, mod new (cross-play).** This is the pair that aborts. The edge and the modded client
exchange HELLO through the relay or the direct link, the relay sees two different
`protocolVersion`s, sends `Abort(1001)` to both, and `NetSession` reports
*"protocol version mismatch - the two clients are running different mod builds"*. Even without the
relay in the path, `ArenaAgreement` refuses: version 2 does not decode a version 1 blob, and both
processes name it as a build skew rather than sitting in a timeout. The match voids. Neither player
loses ELO (`EdgeReportArbiter.voidCause`), and the player sees the match cancel.

**Edge stale, edge new (a split fleet).** Same abort as above for every cross-region match between
the two halves. Same-half matches are fine, which is exactly what makes this hard to spot from a
single edge's logs. The signal is fleet-wide: `rollback_fleet_version_fences > 1`, which the desync
alert treats as an outage on its own, before any player has desynced.

---

## 2. The kill switch and rolling back

### 2.1 The switch

```yaml
rollback:
  edge:
    enabled: false      # mcleagues-core config.yml, on the GAME stacks
```

Reload or restart core. `RollbackHandoffManager.edgeEnabled()` goes false, `EdgeSessionBroker`
stops polling results and the metrics refresh stops, and every new duel routes the ordinary way.

**In-flight matches survive.** Nothing kills them. They run to their natural end on the edges, and
their results still land, because the edge queues each result on the match thread and the async
broker poll pushes it to Redis on its own 1s timer regardless of what core is doing. Core drains
`rollback:edge:results` on its own path. The only thing the switch stops is *new* assignments.

That is the property the drain in section 1.2 step 2 depends on: flip the switch, then watch
`rollback_fleet_edge_matches_live` fall to 0 on its own. A duel started one second before the flip
still finishes and still settles.

### 2.2 The other switches, narrowest first

| Switch | Where | Effect | In-flight matches |
| --- | --- | --- | --- |
| `EDGE_METRICS_ENABLED=false` | one edge | stops the HTTP listener only. Counters and the Redis publish continue | unaffected |
| `EDGE_DIRECT_ENABLED=false` | one edge | that edge advertises no direct endpoint, all its matches use the relay | unaffected; existing direct links stay up until the match ends |
| `rollback.edge.direct-links: false` | core | every new assignment carries the relay instead of a peer endpoint | unaffected |
| `EDGE_BROKER_ENABLED=false` | one edge | that edge stops heartbeating, core stops picking it within 30s. The way to drain one box | run to completion |
| `EDGE_EXPECTED_ARCH` mismatch | one edge | brokering refused at startup, see section 3. Unset only warns | there are none; it never started |
| `rollback.cross-play: false` | core | a modded and an unmodded client in the same duel are both hosted on the edge instead | unaffected |
| `rollback.edge.enabled: false` | core | **the kill switch.** No new duels enter the netcode | run to completion |

### 2.3 Rolling a release back

```
1.  core:  rollback.edge.enabled: false
2.  wait:  rollback_fleet_edge_matches_live == 0 fleet-wide
3.  edge:  redeploy EVERY edge to the previous tag. Never a subset
4.  core:  redeploy the GAME stacks to the previous mcleagues.jar
5.  mod:   republish the previous mod jar to the launcher
6.  check: rollback_fleet_version_fences == 1 and the triple equals the OLD build's triple
7.  core:  rollback.edge.enabled: true
```

Steps 3, 4 and 5 are one unit. Rolling the edges back without the mod leaves every updated client
aborting at HELLO. Rolling `EXPECTED_VERSION` back on its own is only correct if the sim inside that
core jar is also the old one; the fence and the sim move together or not at all.

**Never roll the reference digest back by hand.** If a rollback needs a different digest, it needs
the old `sim-core`, which means the old jar.

---

## 3. The architecture constraint

> **Until the arm64 half of the determinism gate is green, every edge in the fleet runs on one CPU
> architecture.**

### 3.1 Why this is a hard rule and not a preference

`java.lang.Math` transcendentals are permitted to differ between platforms, and HotSpot substitutes
architecture-specific intrinsics for them. The sim is written to avoid every such construct and
`TranscendentalBytecodeGateTest` enforces that at the bytecode level, but the only thing that
*proves* an x64 run and an arm64 run agree is running the harness on both and comparing tick for
tick. `.github/workflows/determinism.yml` is built to do exactly that; the `harness-arm64` and
`cross-architecture` jobs are gated on the repository variable `ARM64_RUNNER` and are skipped until
it names an aarch64 runner. As of this build they have never run.

The operating-system axis **is** closed. Windows 11 (Oracle 21.0.6), Linux (Temurin 21.0.12) and
Linux (Corretto 21.0.12.1) agree on all 11740 ticks and on all three digests. A Windows development
box and a Linux production container are safe. An x64 container and an arm64 container are not
known to be.

A mixed fleet fails in the worst available way. Two such edges print **identical** version fence
triples, so the partial-deploy alert does not fire and `RUNBOOK.md` section 4.1 step 2 finds
nothing. They agree on the arena hash, the ground-y and both spawns, so `ArenaAgreement` passes.
They now agree on the frame-0 state checksum too, so even that gate passes. The divergence appears
mid-match at whatever tick first evaluates the differing expression, as a `DESYNC` void, for every
match between their players and no others.

### 3.2 The startup gate

A paragraph in a document does not survive a capacity expansion at 2am. So the plugin checks:

`EdgeArchGate` normalizes the JVM's `os.arch` (`amd64`, `x86_64`, `x64` all collapse to `x86_64`;
`aarch64`, `arm64`, `armv8` all collapse to `aarch64`) and compares it against a configured
expectation. `EdgePlugin.startBroker()` consults it immediately after the `EDGE_BROKER_ENABLED`
check, and on a refusal **returns without starting the broker or the direct link**:

| Verdict | Behaviour |
| --- | --- |
| `EDGE_EXPECTED_ARCH` unset | brokering starts, with a WARNING banner naming the value to set on this box and why. Unset is the shipped default, so refusing it would stop every deployment that predates the gate, and the local dev stack with them |
| `EDGE_EXPECTED_ARCH` is not one of `x86_64` / `aarch64` | refused. Catches `x64_86` and friends rather than passing a typo through, and the refusal says to unset it if a warning was what you wanted |
| set, valid, and different from `os.arch` | refused, naming both |
| set, valid, and equal to `os.arch` | brokering starts, and the agreement is logged at INFO |

Unset warns rather than refuses, but it is not a pin: it means nothing on this box can catch a node
that was scheduled onto the wrong architecture. Setting the value is what turns the rule into an
enforced one, and because the operator has to name the architecture rather than let the box describe
itself, the value in the compose is a deliberate claim about **every other edge**.

Configuration is the usual environment-over-config pair, `EDGE_EXPECTED_ARCH` or
`broker.expected-arch`. The shipped compose sets it to `x86_64`, so a stack deployed from it is
pinned from the first boot and a deliberate arm64 fleet has to say so in every region's stack
environment, which is precisely the change you want to be hard to make halfway.

`/edge` local testing is unaffected: the gate sits after the broker-enabled check, so a developer
box with `EDGE_BROKER_ENABLED=false` never reaches it.

### 3.3 What a refusal looks like

```
SEVERE  #### BROKERING REFUSED: CPU ARCHITECTURE MISMATCH #### This edge will not take
        assignments because this box is aarch64 and the fleet is pinned to x86_64. ...
```

An unpinned edge is the same banner one level down, and it keeps going:

```
WARNING #### CPU ARCHITECTURE NOT PINNED #### This edge is brokering anyway, but nothing has told
        it what architecture the fleet runs on ... Set EDGE_EXPECTED_ARCH=x86_64 ...
```

The edge still boots, still serves `/metrics`, still answers `/healthz`. It simply never appears in
`rollback:edge:servers`. Observable as: no `broker enabled:` line at startup, no field in
`HGETALL rollback:edge:servers`, and, if the whole region was refused,
`rollback_core_assignments_no_edge_total` climbing on core.

Cover the same ground in the scheduler as well when you can. The gate stops a wrong node from taking
traffic; a node-affinity or placement constraint on the Dockhand stack stops it from being scheduled
in the first place, which is one fewer alert.

### 3.4 What closes this

Set the repository variable `ARM64_RUNNER` to an aarch64 runner label. `harness-arm64` starts
running, `cross-architecture` turns from skipped into a real gate, and once it is green at the
current `CHECKSUM_REV` the rule can be relaxed to "any architecture the gate covers". Not before.
The same reasoning applies to the JDK: the gate exercises Temurin 21 and has been confirmed by hand
on Oracle and Corretto 21. Do not introduce a fourth vendor without a harness run.

---

## 4. The staged rollout

Five stages. Each one names the signal that says "go" and the signal that says "stop". Do not
compress two stages into one deploy, and do not advance on a stage that has run for less than its
stated soak.

Throughout: `rollback_fleet_desync_abort_ratio` at **0.000** and `rollback_fleet_version_fences` at
**1** are the preconditions for every stage. If either is wrong, the stage does not start.

### Stage 0. Dark. Edges deployed, core switch off

**Do.** Deploy the edges to one region pair with `EDGE_BROKER_ENABLED=true`, `EDGE_EXPECTED_ARCH`
set, and core's `rollback.edge.enabled` still **false**. Pick the pair with the lowest inter-region
RTT and the two regions you have the most operational history with.

**Watch.** `HGETALL rollback:edge:servers` shows both edges with a fresh `at`, the right `region`,
and a `directHost`/`directPort`. Both log `broker enabled:` and `direct edge link listening on
udp/7787`. `rollback_edge_redis_up` is 1. `rollback_edge_uptime_millis` climbs without resets.

**Soak.** 24 hours. An edge that restarts on a timer is a memory problem you want to find with no
players on it.

**Abort if.** An edge does not appear in the registry, `redis unavailable` appears in the log, the
process restarts unprompted, or either box logs the architecture refusal.

### Stage 1. One region pair, unranked, relay only

**Do.** `rollback.edge.enabled: true` on **only that pair's** game stacks. Leave
`rollback.edge.direct-links: false` so every match goes through the relay, which keeps one known
path in play while the sim is new. Unranked and casual queues only.

**Watch, in this order:**

| Signal | Go | Stop |
| --- | --- | --- |
| `rollback_edge_match_outcomes_total{cause=DESYNC}` | 0 | anything above 0 |
| `rollback_fleet_version_fences` | 1 | 2 |
| `cause=FINISHED` share of ended matches | >90% | <80% |
| `cause=SELF_FAULT` | 0 | anything above 0 |
| `cause=PEER_NEVER_ARRIVED` | low | rising with arena size, which means the paste budget |
| `rollback_edge_assignment_frame_zero_millis` mean | <15s small arena, <60s courtyard | `max` pinned at 60000 |
| `rollback_edge_frame_deficit_frames` | 0-5 | sustained above 20 |
| `rollback_edge_resimulated_frames_total / rollbacks_total` | 1-4 frames | above 8 sustained |
| edge tick p99 | the box is nowhere near 50ms at this volume | see `DEPLOYMENT.md` 3.9 |
| `rollback_relay_unauthorized_total` | 0 | any climb with `hello` flat, which is a `RELAY_SLOT_SECRET` split |

**Soak.** 500 completed matches or 72 hours, whichever is longer.

**Abort criteria.** Any `DESYNC` at all. Any `SELF_FAULT` at all. `FINISHED` under 80%. Both are
kill-switch events, not investigate-and-continue events: the whole determinism effort exists so that
the desync count is zero, and a non-zero one means an assumption is wrong somewhere you have not
looked yet.

### Stage 2. Same pair, direct links on

**Do.** Verify UDP 7787 reachability in **both** directions between the two regions first, by hand.
Then `rollback.edge.direct-links: true`.

A firewalled direct port does not fall back. Both edges bind, neither hears the other, and every
cross-region match on that pair voids as `PEER_NEVER_ARRIVED` 30 seconds in. There is no partial
failure mode to detect; either it works or every match dies.

**Two edges on different jars look identical to a firewall.** From CHECKSUM_REV 165 every direct-link
datagram carries a per-packet HMAC tag and a replay counter, under a new outer magic. An older peer's
frames are refused rather than misparsed, so the failure is safe, but it presents as the same
`PEER_NEVER_ARRIVED`. The one line that tells the two apart is in the edge log:
`[direct-link] the peer on the direct link is still framing datagrams the pre-tag way`. If you see it,
the problem is the deploy, not the network. **Deploy `jackpot-edge.jar` to every region in the pair
before flipping this stage on**, and if you cannot, set `direct.enabled=false` on the newer edge -
the relay path is unchanged and takes the traffic.

**Watch.** `rollback_edge_direct_link_up` is 1 on both. `cause=PEER_NEVER_ARRIVED` does **not**
move. `rollback_edge_assignment_frame_zero_millis` should fall, not rise.

**Soak.** 24 hours.

**Abort if.** `PEER_NEVER_ARRIVED` climbs at all after the flip. Revert to
`rollback.edge.direct-links: false` first, confirm it stops, then debug the firewall with no
traffic riding on it.

### Stage 3. Fleet-wide, unranked

**Do.** Repeat stages 0 to 2 for each remaining region pair, one pair at a time, with the same
soaks. Do not turn on three pairs to save a week. The failure this staging is designed to catch is
the one that only appears on a specific inter-region path.

**Watch.** Everything from stage 1, plus `rollback_fleet_edges` equal to the fleet size and
`rollback_core_assignments_no_edge_total` at 0.

**Abort if.** Any of the stage 1 criteria on any pair. Roll that pair back to relay-only, or pull
the kill switch fleet-wide if the signal is fleet-wide.

### Stage 4. Cross-play, unranked only

Cross-play (a modded client against an edge-hosted vanilla client) has one known asymmetry and it is
deliberate: `Input.projectileHit` is **mod only**. A modded player's arrows are force-resolved
against what their client rendered; an unmodded player's are resolved only by the sim's own
favour-the-victim collision. The estimated size is 1.0 to 1.5 blocks of positional favour on a
ranged hit, and it does not shrink on a good connection because it is a rule difference, not a
latency one.

**Do.** `rollback.cross-play: true` on unranked queues only. Requires a real published arena;
cross-play refuses the dev-spawn fallback outright.

**Watch.** `rollback_core_cross_play_assignments_total` climbing, `cause=ARENA_MISMATCH` at 0. Read
the refusal shape if it moves: `arena hash` means the Redis arena bytes differ from what one side
loaded, `frame 0 state` means the mod jar and the edge jar decode the same setup blob differently
and one is stale, `version N` means different builds outright. `RUNBOOK.md` 2.2 has the same table.

**Abort if.** Any `ARENA_MISMATCH`, or any `DESYNC` on a cross-play session specifically.

### Stage 5. Ranked

**Not yet.** The gate on this stage is not a soak, it is two open items:

1. **`Input.projectileHit` must stop being mod-only.** Either mirror favour-the-shooter on the edge,
   which has both the sim state and a rendered entity and can compute the same arrow sweep, or
   suppress the mod's `projectileHit` when the opponent is edge-hosted, which the mod can already
   tell from `setup.initialState.edgeHosted[1 - slot]`. Until one of the two ships, an unmodded
   ranked player is measurably worse off at range than a modded one, and ELO is exactly what makes
   a systematic edge matter.
2. **The remaining input-delay gap is acceptable but should be measured in production, not
   estimated.** An unmodded player carries roughly 30-45ms more input delay (0.6-0.9 sim ticks,
   2.4-3.6% of the 1250ms claim window) and roughly 35-65ms more motor-visual reaction loop. That is
   smaller than the region-to-region spread ranked already tolerates, which is why it is defensible
   on its own, but it has only ever been derived from source constants.

Everything the sim adjudicates is already host-neutral: there is exactly one `edgeHosted` branch in
the entire simulation (`Simulation.stamps`) and it only decides who gets pinned. Reach, attack
charge, click-rate ceiling, damage, knockback and the lag-compensation window are provably identical
between the two hosts.

When ranked does open, it opens on the same shape as stage 1: one region pair, relay first,
72 hours, and the same abort criteria plus a watch on ELO distribution by host kind.

---

## 5. Environment and compose reference

`DEPLOYMENT.md` section 3.3 is the full per-variable table with the reasoning. This section is the
release-time view: what the shipped compose actually sets today, and what changed this week.

### 5.1 The shipped compose

`edge/deploy/docker-compose.netcode-edge.yml` in this repository is the file to copy into
`mcleagues-docker-runner` as `docker-compose.netcode-edge.yml`. It lives here so it versions with
the plugin whose environment contract it encodes. It carries no credentials.

```yaml
SERVER_TYPE:            NETCODE_EDGE
REGION:                 (from the stack, must satisfy Region.safeValueOf)
EDGE_BROKER_ENABLED:    true
EDGE_ID:                (set explicitly; must equal the Velocity server name)
EDGE_EXPECTED_ARCH:     x86_64            <- new, section 3
EDGE_PUBLIC_HOST:       (the address the proxy reaches this container on)
EDGE_PUBLIC_PORT:       25565
EDGE_DIRECT_ENABLED:    true
EDGE_DIRECT_PORT:       7787              published, edge to edge only, never to players
EDGE_METRICS_ENABLED:   true
EDGE_METRICS_PORT:      7788              published, /metrics /healthz /
EDGE_METRICS_SIM_PROBE: true
EDGE_LIMBO_HOST:        (blank)           <- new, section 5.3
EDGE_LIMBO_PORT:        25565             <- new, section 5.3
REDIS_HOST/PORT/PASSWORD: GLOBAL redis, not the region's local one
NETCODE_EDGE_JVM_FLAGS: -Xms2G -Xmx4G -XX:+UseG1GC -XX:MaxGCPauseMillis=50 ...
```

Ports: **25565 is deliberately not published** (players reach an edge through Velocity, and a direct
connection would bypass forwarding). **7787/udp is published** and must be reachable from every
other edge in every region and from nothing else. **7788/tcp** is the metrics endpoint; set
`EDGE_METRICS_BIND` to an internal address if the node is exposed.

Heap is the binding resource, not CPU. Budget 128 sim drivers per edge at roughly 14MiB of live heap
each, hard stop at 192. `DEPLOYMENT.md` 3.9 has the measured table.

### 5.2 What changed this week: relay authentication

`RELAY_SLOT_SECRET` is now the production configuration and `DEPLOYMENT.md` section 2 documents it.
Two things it does **not** document, both of which are live in `RelayServer.main`:

| Variable | Default | What it does |
| --- | --- | --- |
| `RELAY_CONTROL_SECRET` | unset | Setting it enables **the referee and a TCP control endpoint**. Not a metrics port: it accepts control traffic authenticated by this secret. Left unset the relay prints `#### NO REFEREE ON THIS RELAY ####` at startup and keeps forwarding with two-witness results; if the port cannot be bound it prints the same banner and still keeps forwarding |
| `RELAY_CONTROL_PORT` | 7778 | The port that endpoint binds. Unset means it is not bound at all |
| `RELAY_ALLOW_UNAUTHENTICATED` | unset | Disables slot authentication entirely, and permits a routable bind without a secret. **Ignored while `RELAY_SLOT_SECRET` is set**, and the relay says so at startup |
| `RELAY_BIND` | unset | The address to listen on. Unset means `0.0.0.0` when a slot secret is set and `127.0.0.1` when there is none |
| `RELAY_ALLOW_PUBLIC_WITHOUT_SECRET` | unset | The only opt-in that lets a secret-less relay bind a routable address. Without it that combination exits 78 at startup |

Action for this release: if the relay compose publishes a port range rather than named ports,
confirm 7778 is not exposed. If the referee is wanted, `RELAY_CONTROL_SECRET` has to be set
deliberately and the port kept on the overlay.

### 5.2a The referee's core side

The relay half was always there; the core half now exists too. `mcleagues-core` opens one
authenticated TCP connection to `RELAY_CONTROL_PORT`, sends `AUTHORIZE_SETUP` for every modded
rollback session at handoff, and consumes the signed `Result` as a **third witness**.

| core key (`rollback.` prefix) | env | default | What it does |
| --- | --- | --- | --- |
| `referee.enabled` | `ROLLBACK_REFEREE_ENABLED` | `true` | Master switch. On its own it does nothing without a secret |
| `referee.control-secret` | `ROLLBACK_REFEREE_CONTROL_SECRET` | unset | Must equal `RELAY_CONTROL_SECRET` on every relay. Empty means **no referee** and core says so at SEVERE on the first handoff |
| `referee.control-host` | `ROLLBACK_REFEREE_CONTROL_HOST` | `rollback.relay-host` | Only set this when the control endpoint is not on the same host as the UDP relay |
| `referee.control-port` | `ROLLBACK_REFEREE_CONTROL_PORT` | `7778` | Must equal `RELAY_CONTROL_PORT` |
| `referee.grace-ms` | `ROLLBACK_REFEREE_GRACE_MS` | `4000` | How long a contested result waits for a verdict before the two-witness rule decides |
| `referee.session-ttl-ms` | `ROLLBACK_REFEREE_SESSION_TTL_MS` | `1800000` | Expiry the relay sweeps the session on, so a duel that never ends cannot leak a referee |

**The trust model.** A verdict is produced only when the relay's own re-simulation of the two
input streams reaches a decided match end.

1. A verdict with `violation` set voids the session.
2. A decided verdict **outranks both clients**, including a contested double claim, a fabricated
   `DESYNC`, and a concession that names a different winner. The relay is our infrastructure and
   sits in the input path; a client is not. A verdict that contradicts a settled client report is
   logged at SEVERE and raises the desync alert with cause `REFEREE_DISAGREEMENT`.
3. No verdict yet, and the client reports cannot settle the match between them: the result waits
   `referee.grace-ms` and is re-judged the instant the verdict lands.
4. No verdict at all - referee off, unconfigured, unreachable, its link dropped, its re-simulation
   never reached a decided end, or the grace window burned - degrades to **exactly** the two-witness
   rule that was there before. It never degrades to believing one client.

A concession settles immediately without waiting on a silent referee, because conceding is a
statement against the conceder's own interest.

**Arena coupling, which is not optional.** A referee that rebuilds a different arena would
re-simulate a different duel, so with `referee.enabled` on, core ships the **sim-canonical palette
geometry** to the two clients (`ArenaBlocksCodec.encodeForSim`) instead of the plain block snapshot,
and sends that identical blob to the relay. Both clients and the referee then derive collision from
one source. If an arena's geometry cannot be built, core logs it, ships the plain snapshot, and does
**not** authorize the session - that duel falls back to two witnesses rather than being judged
against collision nobody else used. Turning `referee.enabled` off restores the previous wire byte
for byte.

**Not yet wired:** cross-play and edge-hosted sessions. Those are hosted by an edge, which is our
own infrastructure already, so the referee adds nothing there; `EdgeSessionBroker` still resolves
them on its own. Note also that the referee and direct links are
mutually exclusive for a given match, because the referee's only vantage point is the relay it tees
from, so a fleet running stage 2 with direct links on is a fleet the referee cannot see.

The relay's last startup line names the mode it is in - `listening on udp/<host>:<port> in <MODE>
mode` - and the bind address enforces it rather than describing it:

| Mode | How you get it | Reachable |
| --- | --- | --- |
| `DERIVED` | `RELAY_SLOT_SECRET` set | Yes, `0.0.0.0`. **The only production mode** |
| `PINNED_LOOPBACK` | no secret, no `RELAY_BIND` | No - `127.0.0.1` only, so a published docker port forwards to nothing |
| `PINNED_PUBLIC` | no secret, routable `RELAY_BIND`, `RELAY_ALLOW_PUBLIC_WITHOUT_SECRET=true` | Yes, and weak. Logged to stderr at every start |
| `OPEN` | `RELAY_ALLOW_UNAUTHENTICATED=true` and no secret | Yes, and unauthenticated. Logged to stderr at every start |
| refuses to start | no secret, routable `RELAY_BIND`, no opt-in | Exits 78; a container restart-loops on the banner |

A relay stack that simply forgets the secret therefore fails **closed and visibly**: it binds
loopback, every match on it times out with `PEER_NEVER_ARRIVED`, and the banner says why. It cannot
end up quietly forwarding real matches on trust-on-first-use. The secret must be byte-identical to
core's `rollback.relay-slot-secret`. If they disagree, **every** bind is refused:
`rollback_relay_unauthorized_total` climbs at the rate matches are handed off while
`rollback_relay_hello_total` stays flat. `RUNBOOK.md` 4.4 has the procedure.

### 5.3 What changed this week: the game-type default

`EdgePlugin` reads `game-type` from `config.yml` with a default of `CRYSTAL`, and the shipped
`config.yml` does not define the key. So an edge with a default config reports
`/edge game-type=CRYSTAL` at startup.

This affects `/edge` local testing only. A brokered match carries its own kit in the assignment's
setup bytes and never consults `game-type`. Two things follow, and both are logged loudly at
startup:

- Two developers running `/edge` against each other must configure the **same** `game-type` and run
  the same build, or frame 0 differs and the match desyncs. The default now agrees between two
  fresh checkouts, which is an improvement, but a half-configured pair is worse than before because
  the value is no longer obviously unset.
- `dev-kit.enabled` (default false) picks a demo kit from the **assignment's** `gameType`, whose
  fallback is `VANILLA`, not `CRYSTAL`. The two defaults are deliberately different because they
  answer different questions, but it means "the default kit" is not one thing. Keep
  `dev-kit.enabled: false` in production; an assignment without setup bytes should refuse, not
  invent a kit.

`EDGE_LIMBO_HOST` and `EDGE_LIMBO_PORT` have been added to the compose. `EdgeModHandoff` reads them
through `envOrConfigString` with a default of `127.0.0.1:25565`, which is a loopback address in a
container. They matter only on the path where an assignment's own `hostKind` is `MOD` and the edge
performs the handoff itself. Blank in the compose keeps the existing default; set them if that path
is ever exercised in production, and point them at the `NETCODE_LIMBO` container.

### 5.4 The `ROLLBACK_EDGE_*` environment variables now exist

They did not until recently. `RollbackHandoffManager.envKey` replaced hyphens and not dots, so
`edge.enabled` looked for `ROLLBACK_EDGE.ENABLED` and an operator who set `ROLLBACK_EDGE_ENABLED=false`
expecting to kill the netcode changed nothing at all. Every nested `rollback.edge.*` setting was
unreachable from the environment; only the flat keys (`ROLLBACK_CROSS_PLAY`,
`ROLLBACK_RELAY_SLOT_SECRET`, `ROLLBACK_MAX_MATCH_MILLIS`) worked, and only because they contain no
dot.

`envKey` now builds:

```java
"ROLLBACK_" + key.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_')
```

So `edge.enabled` is `ROLLBACK_EDGE_ENABLED`, `edge.direct-links` is `ROLLBACK_EDGE_DIRECT_LINKS`,
`edge.cage.drop-height` is `ROLLBACK_EDGE_CAGE_DROP_HEIGHT`, and the names `DEPLOYMENT.md` section
3.3 lists are the real ones. The environment wins over `config.yml` exactly as it does for the flat
keys.

Two consequences worth knowing before the next deploy:

- A stack that carried the dotted workaround (`ROLLBACK_EDGE.ENABLED=false` in a compose
  `environment:` block) is no longer read at all. Rename those to the underscore form in the same
  deploy that ships this core build, or the switch they encode silently reverts to the `config.yml`
  value.
- Anything set in the environment now takes effect that previously did not. Check the game stacks
  for stale `ROLLBACK_EDGE_*` entries left behind by an earlier attempt before shipping, because
  they will start being obeyed.

---

## 6. Pre-deploy checklist

Run top to bottom. Every line is a command or a comparison, not a judgement call. Stop at the first
failure.

### Build

```
[ ] ./gradlew --offline :sim-core:test :sim-host:test :relay:test :edge:test
        expect: zero failures, zero skipped
[ ] ./gradlew --offline :sim-core:compileJava :sim-host:compileJava :edge:compileJava
        expect: clean, only the pre-existing Paper GameRule deprecation warnings in EdgePlugin
[ ] confirm VersionFenceTest ran and passed
        it reads mcleagues-core's RollbackModRegistry.java off disk. If it did not run, the
        transcribed constant was not checked and the whole fence is unverified
[ ] confirm RunbookVersionFenceTest ran and passed
        it checks the triple printed in RUNBOOK.md section 0 against what this tree compiles to
[ ] confirm HostProducerParityGateTest and HostInventorySurfaceGateTest passed
        the two input producers, and the host inventory surface
[ ] confirm CrossPlayDecoderExecutionParityTest passed
        it EXECUTES the modded MatchSetup.java against sim-core's MatchSetupFrame0Decoder over one
        setup blob and requires checksum-equal frame 0. A field the canonical decoder writes and
        the modded one does not is a cross-play desync at frame 0. It needs the pvphq-rollback-mod
        tree checked out beside this one and a JDK (it compiles the mod decoder in-process); it
        fails rather than skips if either is missing
[ ] confirm TranscendentalBytecodeGateTest passed
        no java.lang.Math transcendental on a path feeding simulated state
```

### Artifacts

```
[ ] ./gradlew :edge:jar        -> edge/build/libs/jackpot-edge.jar
        self-contained: PacketEvents, Jedis, Gson, sim-core and sim-host are inside it
[ ] the edge deploy repo contains ONLY jackpot-edge.jar in plugins/. Not mcleagues.jar
[ ] the mod jar is built from the SAME tree as jackpot-edge.jar
[ ] mcleagues.jar is built with RollbackModRegistry.EXPECTED_VERSION matching that tree
```

### Configuration

```
[ ] EDGE_EXPECTED_ARCH is set on every edge stack, and to the SAME value on all of them
[ ] uname -m on every edge host agrees with that value
[ ] EDGE_ID on every stack equals a Velocity server name the proxy resolves to that container
[ ] REGION on every stack is a name Region.safeValueOf accepts, and is not LOCAL
[ ] REDIS_HOST points at GLOBAL redis on every edge, not a region-local instance
[ ] EDGE_PUBLIC_HOST is not 127.0.0.1 on any edge
[ ] dev-kit.enabled is false and rounds is 1 in every edge config.yml
[ ] RELAY_SLOT_SECRET on every relay == rollback.relay-slot-secret on every core
[ ] every relay's startup line says "in DERIVED mode"; no RELAY_BIND, no
    RELAY_ALLOW_PUBLIC_WITHOUT_SECRET and no RELAY_ALLOW_UNAUTHENTICATED on any relay stack
[ ] RELAY_CONTROL_PORT (7778) is not published to the internet
[ ] udp/7787 is reachable edge to edge in both directions for every region pair you intend to
    enable direct links on, and is NOT reachable from players
[ ] rollback.edge.* settings are driven from core's config.yml, not the environment (section 5.4)
```

### Fleet state before the switch goes back on

```
[ ] HGETALL rollback:edge:servers   -> every expected edge, fresh 'at', correct region
[ ] rollback_fleet_edges == the fleet size
[ ] rollback_fleet_edge_matches_live == 0        (during a drain)
[ ] every edge's /healthz returns 200
```

### The three digests

Run on a machine building the release tree. All three come out of one command:

```
./gradlew :sim-core:checkHarnessDigest
```

Compare against `sim-core/src/main/resources/me/nootnoot/sim/harness/reference-digest.txt`:

```
[ ] arena hash     : fe21a2f81e31bc99
[ ] rollback dgst  : 9b7a69a1f8f87dba
[ ] stream digest  : 218bc25a4228a544
[ ] final checksum : 98b78a5d0629ac15
[ ] the command prints "OK: digest matches the committed reference"
[ ] the header reads ticks=11740 seed=c0ffee checksum-rev=165
```

Those values are what **this** checkout builds. They move whenever `CHECKSUM_REV` does. If you are
deploying a rev bump, the three digests in this checklist are expected to be different, and the
thing to verify is that the reference file was **re-recorded in the same commit** as the bump and
that `updateHarnessDigest` was run rather than the file edited by hand.

If you have streams from more than one machine:

```
./gradlew :sim-core:compareHarnessDigests -PharnessDir=<dir> -PharnessArches=1
```

`-PharnessArches=1` is the operating-system comparison, which is the axis that is closed. Drop it to
2 only once `ARM64_RUNNER` exists, at which point the CI job does it for you.

### The fence triple

Not the one printed below. **The one the running processes print.**

```
[ ] every edge logs at startup:
        Version fence on this build: inputBytes=67 checksumRev=171 protocolVersion=17324
[ ] every edge prints the SAME triple as every other edge
[ ] /rollbackmetrics on core reports  rollback_fleet_version_fences == 1
[ ] /rollbackmetrics on core reports  core expects mod version 17324
[ ] the mod jar being published computes to the same 17324
        Protocol.VERSION = (InputCodec.BYTES << 8) | (CHECKSUM_REV & 0xFF) = 67*256 + 171
```

Two edges printing different triples is a partial deploy and nothing else needs investigating.
`rollback_fleet_version_fences` above 1 is the same statement from core's side, and the desync alert
treats it as an outage on its own, before any player has desynced.

---

## 7. After the deploy

First hour, in this order:

1. `rollback_fleet_version_fences` is 1. If it is not, stop here and read section 1.4.
2. `rollback_fleet_desync_abort_ratio` is 0.000.
3. `cause=FINISHED` is above 90% of ended matches.
4. `cause=SELF_FAULT` is 0, `cause=ARENA_MISMATCH` is 0.
5. `rollback_edge_results_dropped_total` is 0 on every edge. Each one is an unrecorded match.
6. `rollback_core_mod_version_rejected_total` is climbing and then falling, which is players
   updating. A plateau means the launcher is not serving the new jar, and the netcode is fine.

Then `RUNBOOK.md` section 5 for the daily checks, and `RUNBOOK.md` section 4 for anything that is
not on this list.
