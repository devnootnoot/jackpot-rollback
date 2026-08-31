# RUNBOOK

Operational guide for the rollback netcode fleet: what every metric means, what a healthy value looks
like, and the exact steps for the failure modes this system actually has.

Read `ARCHITECTURE.md` for how the pieces fit together, `DEPLOYMENT.md` for how they are shipped and
`DEPLOY.md` for how a release goes out. This document assumes all three and only covers running the
thing.

---

## 0. The one number that matters

**Desync-abort rate.** A desync means the two peers of a duel computed different game states from the
same inputs. It is the single failure this entire determinism effort exists to prevent, and in
production it almost always means **two builds are in the fleet at once**.

| Where | Signal |
| --- | --- |
| Prometheus (per edge) | `rollback_edge_desync_abort_ratio` |
| Prometheus (fleet) | `rollback_fleet_desync_abort_ratio`, `rollback_fleet_version_fences` |
| Edge log | `SEVERE` banner `######## DESYNC ABORT ALERT ########` |
| Core log | `SEVERE` banner `######## DESYNC ABORT ALERT ########` with the fence triple per edge |
| In game | staff with `practice.command.rollbackmetrics` get a red chat alert |
| HTTP | `GET http://<edge>:7788/healthz` returns **503** while the alert is firing |
| Command | `/rollbackmetrics` on core, `/edge metrics` on an edge |

### Alert definition

```
ALERT  RollbackDesyncStorm
EXPR   rollback_fleet_desync_abort_ratio >= 0.02
       OR rollback_fleet_version_fences > 1
FOR    0m                       (fires on the first evaluation that crosses)
WINDOW 15 minutes rolling, minimum 5 ended matches and 2 desyncs
SEVERITY page
```

The same rule runs in-process on both sides so the alert exists even with no Prometheus:

- **Core** (`RollbackDesyncAlert`) evaluates every 5s on the async rollback timer. It fires on the
  rate **or** on `fences.size() > 1` - a split fleet is a partial deploy and is treated as an
  outage before any player has desynced.
- **Each edge** (`EdgeDesyncAlert`) evaluates every second on the async broker poll, over its own
  15-minute window, and flips `/healthz` to 503.

Both print the **version fence triple** so an operator can tell a deploy skew from a real sim bug
without leaving the log line:

```
inputBytes / checksumRev / protocolVersion
     67    /     184     /      17336
```

`protocolVersion = (InputCodec.BYTES << 8) | (CHECKSUM_REV & 0xFF)`, so 67 * 256 + 184 = 17336.
Core's expected mod version (`RollbackModRegistry.EXPECTED_VERSION`) is the same number.

**Do not compare a running fleet against the triple printed above.** It is what this checkout builds,
and the number moves whenever `CHECKSUM_REV` does. The triple you compare against during an incident
is the one the processes themselves print: every edge logs it at startup (`EdgePlugin`, "on this
build: inputBytes=... checksumRev=... protocolVersion=...") and repeats it on the desync banner, and
it is a label on the Prometheus series. **If two edges print different triples, you have a partial
deploy and nothing else needs investigating.** The number above is checked against
`InputCodec.BYTES`, `Protocol.CHECKSUM_REV` and `Protocol.VERSION` by
`RunbookVersionFenceTest`, so a rev bump that does not update this page fails the build rather than
leaving an operator a wrong number to trust.

A healthy fleet: `rollback_fleet_desync_abort_ratio` is **0.000**, and
`rollback_fleet_version_fences` is **1**. Any sustained non-zero desync ratio is an incident.

---

## 1. Where the numbers live

Everything follows what the project already does: **Redis is the cross-process bus, and per-process
HTTP is the scrape surface.**

| Surface | Who writes it | Who reads it |
| --- | --- | --- |
| `http://<edge>:7788/metrics` | edge plugin, rebuilt every 1s off the server thread | Prometheus |
| `http://<edge>:7788/` | same snapshot, plain text | a human with `curl` |
| `http://<edge>:7788/healthz` | 200 ok / 503 while the desync alert is firing | load balancer, uptime check |
| `http://<relay>:7779/metrics` | relay, rendered per request | Prometheus |
| Redis HASH `rollback:edge:metrics` | edge, field = `edgeId`, 300s TTL | core `RollbackMetrics.refresh()` |
| Redis STRING `rollback:metrics:fleet` | core, every 5s, 300s TTL | a scraper that can only reach Redis |
| `/rollbackmetrics` (core, `/edgemetrics`) | - | staff, in game |
| `/rollbackmetrics prom` | writes the fleet render to Redis and dumps it to console | staff |
| `/edge metrics` (on an edge, console works too) | - | operator on the box |

The Redis path is the authoritative one: metrics reach core even when nothing can scrape the edges.
The HTTP path exists so a scraper does not have to speak Redis.

**Threading contract.** No metric ever performs I/O on the server thread. Counters are plain atomic
increments taken during the tick; the snapshot, the Redis write and the HTTP render all happen on the
async broker poll or on the HTTP executor's own daemon threads. The sim-probe counters are copied
into the published mirror once per second **on the server thread** (`EdgeMetrics.mirrorProbes`) so
the async reader never races a partial write.

---

## 2. Metric reference

### 2.1 Rollback health (per edge)

| Metric | Meaning | Healthy |
| --- | --- | --- |
| `rollback_edge_rollbacks_total` | Rollbacks executed. One per remote-input batch that contradicted a prediction. | Grows with every match. A 1v1 at 60ms RTT rolls back most ticks - a rollback is normal work, not an error. |
| `rollback_edge_resimulated_frames_total` | Frames re-simulated, measured as the unconfirmed window at the tick each rollback fired. | Divided by `rollbacks_total` this is average rollback depth. **1-4 frames** on a good link, **5-8** on a bad one. |
| `rollback_edge_rollback_depth_frames_max` | Deepest single rollback since start. | Under the ring capacity (1024). Anything near it means the peer went silent and came back. |
| `rollback_edge_frame_deficit_frames` | Current worst `head - confirmedFrame` across live matches. How far ahead of confirmed truth the sim is running. | **0-5.** Sustained above ~20 means one side is starved of the peer's input. |
| `rollback_edge_frame_deficit_frames_max` | High-water deficit. | Spikes at match start are normal. |
| `rollback_edge_catchup_bursts_total` | Times the session ran extra frames to close a deficit. | Occasional. A steady stream means one peer is consistently behind. |
| `rollback_edge_catchup_frames_total` | Extra frames run during those bursts. | Ratio to bursts should stay in single digits. |
| `rollback_edge_sim_frames_total` | Host ticks driven across all matches. Denominator for the rest. | - |

### 2.2 Outcomes and aborts (per edge)

`rollback_edge_match_outcomes_total{cause=...}` breaks every ended match down by the cause this edge
filed with core. The causes are exactly `EdgeOutcome`:

| Cause | Meaning | Healthy |
| --- | --- | --- |
| `FINISHED` | The sim confirmed a death. The normal path. | Should dominate: **>90%** of ended matches. |
| `LOCAL_QUIT` | Our player disconnected or `/leave`d mid-match. | A few percent. |
| `LOCAL_FORFEIT` | Our player forfeited deliberately. | Low. |
| `PEER_GONE` | The peer stopped sending for `PEER_TIMEOUT_TICKS` (400 ticks = 20s). Also `rollback_edge_peer_timeouts_total`. | **<2%.** A spike is a network or peer-edge problem, not a sim problem. |
| `DESYNC` | Checksum mismatch. **This is the alert.** | **0.** |
| `SELF_FAULT` | Our own sim threw. Also `rollback_edge_sim_faults_total`. | **0.** Any occurrence is a bug; grab the stack from the edge log. |
| `PEER_NEVER_ARRIVED` | The arena handshake timed out before frame 0. | Low. Rises when one edge cannot paste the arena in time. |
| `ARENA_MISMATCH` | The two hosts disagreed on the arena hash, a spawn, or the frame-0 state checksum. | **0.** Read the refusal line: `arena hash` means the arena bytes in Redis differ from what one side loaded; `frame 0 state` means the two hosts decoded the SAME setup blob into different opening states, which in cross-play means the mod jar and the edge jar do not decode frame 0 the same way and one of them is stale; `version N` means the two jars are different builds outright. |
| `NO_FRAME_ZERO` | Aborted before frame 0 (player left, paste timed out, shutdown). | Low. |

Supporting counters: `rollback_edge_matches_started_total`, `rollback_edge_matches_ended_total`,
`rollback_edge_desync_aborts_total`, `rollback_edge_last_desync_frame`,
`rollback_edge_last_desync_unixtime_millis`.

### 2.3 Claim rejections (per edge)

The authority model refuses a claim when a player asks for a hit they could not have had. These come
from `SimProbe`, so they count every evaluation **including re-simulated ones** - treat them as
ratios against their own attempt counter, never as absolute player behaviour. `EDGE_METRICS_SIM_PROBE=false`
removes the probe from the sim hot path and zeroes this whole section; nothing else is affected.

| Metric | Meaning | Healthy |
| --- | --- | --- |
| `rollback_edge_claim_melee_attempts_total` / `..._granted_total` | Melee claims offered and honoured. | Grant rate **>85%**. |
| `rollback_edge_claim_melee_refused_out_of_reach_total` | Melee refused: no rewound hull was inside reach. | Small fraction of attempts. A sudden climb on one edge means clock or position drift. |
| `rollback_edge_claim_melee_refused_occluded_total` | Melee refused: a block was in the way. | Rises on build-heavy game types. Expected. |
| `rollback_edge_claim_melee_granted_off_aim_total` | Melee claims honoured with the crosshair not covering the body. | A large, honest number: jump crits and mid-turn hits. Compare it as a RATIO against `claim_melee_granted_total`, never as an absolute. The scripted determinism scenario alone produces 186 against 557 grants, so roughly a third is normal for the fleet. It is not a refusal and must never become one; the per-player version of this ratio is `PlayerState.meleeClaimsOffAim` / `meleeClaimsGranted`, which is replicated and checksummed, and `ClaimAuthority.offAimRateExceeds` is what a policy layer should read. |
| `rollback_edge_authority_stamp_walked_total` | Edge authority position stamps big enough to need sub-stepping (delta over 0.5 blocks). | Low. Every ordinary 20 Hz sample is one leg. A rising count is lag catch-up, i.e. the edge handing the sim several ticks of movement at once. |
| `rollback_edge_authority_stamp_clipped_total` | Sub-stepped stamps the collider did not deliver in full because geometry was in the way. | **Near 0.** This is a stamp whose straight-line path crossed the arena. A sustained non-zero on one edge means that edge's movement validator is accepting samples the sim then has to refuse, and the player is being rubber-banded. |
| `rollback_edge_claim_arrow_attempts_total` / `..._refused_total` | Arrow claims. | Refusal **<10%**. |
| `rollback_edge_claim_sightline_tests_total` | Line-of-sight tests run. | - |
| `rollback_edge_claim_sightline_blocked_by_arena_total` | Sightline blocked by static arena geometry. | Arena-dependent. |
| `rollback_edge_claim_sightline_blocked_by_placed_block_total` | Sightline blocked by a block a player placed. | Rises with crystal/build modes. |
| `rollback_edge_claim_sightline_cobweb_crossed_total` | A cobweb crossed the sightline. | Low. |
| `rollback_edge_claim_block_reach_refused_total` | A block intent this edge stamped targeted a cell outside `Combat.blockReachLimit()`, so the sim will drop it. Measured at the edge, not in the sim. | Near zero for legitimate play. A player generating these steadily is reaching, or their client is desynced from the sim's idea of where they are. |
| `rollback_edge_claim_projectile_spawn_refused_total` | The sim refused to spawn a projectile (budget or state). | Near zero. |

### 2.4 Assignment path (per edge)

| Metric | Meaning | Healthy |
| --- | --- | --- |
| `rollback_edge_assignments_received_total` | Assignments drained from `rollback:edge:assign:<edgeId>`. | Matches core's `rollback_core_assignments_issued_total` over time. |
| `rollback_edge_assignments_expired_total` | Assignments dropped because they aged past 120s before this edge drained them. | **0.** Non-zero means the edge was down or Redis was slow. |
| `rollback_edge_assignments_unparseable_total` | Assignment JSON this build could not read. | **0.** Non-zero is a version skew between core and edge. |
| `rollback_edge_assignments_unclaimed` | Assignments held for a player who is not here yet. | Briefly 1-2 during transfers. Sustained means players are not arriving. |
| `rollback_edge_assignment_pickup_millis_{sum,count,max}` | Core publishing to this edge draining it. | mean **<1500ms** (the poll is 1s). `max` above ~5000 means Redis or the poll is stalling. |
| `rollback_edge_assignment_frame_zero_millis_{sum,count,max}` | Core publishing to frame 0 (the whole arena paste and handshake). | mean **<15s** for a small arena, up to **~60s** for courtyard-sized. `max` at 60000 is the paste timeout. |

Cross-machine clocks: these are computed by subtracting a core-stamped `issuedAtMs` from the edge's
own `System.currentTimeMillis()`. Clock skew between core and an edge shows up here as a constant
offset - if one edge's pickup latency is negative or absurd, fix NTP before believing the number.

### 2.5 Edge process health

| Metric | Meaning | Healthy |
| --- | --- | --- |
| `rollback_edge_matches_live` / `rollback_edge_matches_pending` | Matches simulating / waiting for frame 0. | pending should drain within seconds. |
| `rollback_edge_redis_up` | 1 when the last Redis operation succeeded. | **1.** |
| `rollback_edge_results_queued` | Match results held in the outbox because Redis is unreachable. | **0.** Non-zero and rising means Redis is down and results are being buffered (capacity 256, then the oldest is dropped and logged `SEVERE`). |
| `rollback_edge_results_dropped_total` | Results lost to outbox overflow. | **0.** Every one is an unrecorded match. |
| `rollback_edge_direct_link_up` | 1 when this edge bound its direct UDP port. | 1 if `EDGE_DIRECT_ENABLED=true`. 0 means every match falls back to the relay - a degradation, not an outage. |
| `rollback_edge_movement_clamps_total` | Times the movement validator clamped a reported position. | Low. A single player generating most of them is worth reviewing. |
| `rollback_edge_render_faults_total` | The renderer threw. The sim keeps running; nothing is drawn. | **0.** |
| `rollback_edge_container_blobs_refused_total` | Malformed out-of-band blobs refused from the peer. | **0** between two same-build edges. |
| `rollback_edge_teardown_faults_total` | A step of a match teardown threw. The remaining steps still ran, but the player may still be holding something the match should have handed back. | **0.** Any non-zero value: check that player with `/edge attackspeed <name>` and repair if it reports DAMAGED. |
| `rollback_edge_uptime_millis` | Process uptime. | Resets tell you it restarted. |

### 2.6 Core

| Metric | Meaning | Healthy |
| --- | --- | --- |
| `rollback_core_assignments_issued_total` | Sessions successfully published to two edges. | - |
| `rollback_core_assignments_failed_total` | Redis write of the assignment failed; the session was forgotten. | **0.** |
| `rollback_core_assignments_no_edge_total` | No live edge could be picked. | **0.** Non-zero means `EdgeRegistry.live()` is empty or every candidate was excluded. |
| `rollback_core_cross_play_assignments_total` | Cross-play (vanilla edge vs modded client) assignments. | - |
| `rollback_core_reports_filed_total` | Per-slot match reports accepted. | About 2 per ended session. |
| `rollback_core_reports_forwarded_total` | Reports forwarded to the server that owns the session. | Normal in a multi-region core fleet. |
| `rollback_core_reports_dropped_total` | Reports nobody owns, or forwarded 3 times without finding an owner. | Low. Each one is a match that settled without its second opinion. |
| `rollback_core_reports_unreadable_total` | Result JSON core could not parse. | **0.** Non-zero is a version skew. |
| `rollback_core_sessions_settled_total` | Sessions decided normally. | Should dominate. |
| `rollback_core_sessions_arbitrated_total` | Sessions decided by the player directory because one peer never filed. | Low. |
| `rollback_core_sessions_voided_total` and `rollback_core_void_causes_total{cause=...}` | Sessions thrown out. `cause=DESYNC` is the alert input. | `DESYNC` and `ARENA_MISMATCH` should be **0**. |
| `rollback_core_mod_version_rejected_total` | Handoffs aborted because a client's mod version did not equal `RollbackModRegistry.EXPECTED_VERSION`. | Spikes right after a mod release while players update. Sustained means the release did not reach clients. |
| `rollback_fleet_edges` | Edges that published metrics in the last 2 minutes. | Equals the fleet size. |
| `rollback_fleet_version_fences` | Distinct version-fence triples live. | **1.** |

### 2.6a Core: the relay referee (third witness)

| Metric | Meaning | Healthy |
| --- | --- | --- |
| `rollback_core_referee_authorized_total` | Mod sessions the relay accepted an `AUTHORIZE_SETUP` for. | Equals mod-path sessions started. |
| `rollback_core_referee_authorize_failed_total` | Sessions the relay refused or could not be told about, including arenas with no sim-canonical geometry. | **0.** Each one is a duel with only two witnesses. |
| `rollback_core_referee_unreachable_total` | Control-link connect attempts that failed. | **0.** |
| `rollback_core_referee_unconfigured_total` | Sessions started with the referee switched off or unconfigured. | **0** in production. |
| `rollback_core_referee_forged_result_total` | Control frames whose HMAC did not verify. | **0.** Non-zero means something other than our relay is talking on that port. |
| `rollback_core_referee_verdicts_total` | Signed verdicts received. | Roughly one per ended mod session. |
| `rollback_core_referee_settled_total` | Sessions settled by a referee verdict. | Should dominate `sessions_settled` on the mod path. |
| `rollback_core_referee_overrode_clients_total` | Verdicts that resolved a case the two clients could not (a contested claim, a fabricated `DESYNC`, a lone claim). | Low but non-zero. This is the hole the referee closes. |
| `rollback_core_referee_disagreed_total` | Verdicts naming a different winner than a settled client report. | **0.** Non-zero is tampering or a determinism bug; also raises the desync alert with cause `REFEREE_DISAGREEMENT`. |
| `rollback_core_referee_absent_at_decision_total` | Sessions where the grace window expired with no verdict and the two-witness rule decided. | Low. A rising count means the referee is not keeping up or is not really witnessing. |

The relay side prints the same story without a scrape. `[relay] referee: watching session <id>` on
every authorize, `[relay] referee verdict: session <id> - ...` on every result it signs, and
`[relay] #### THE REFEREE COULD NOT TAKE SESSION <id> ####` when core authorized it with a frame 0 or
arena bytes the relay cannot rebuild. That last one leaves the link up on purpose: one session the
referee cannot take must not cost every other session on the same link its third witness. A relay
started with no `RELAY_CONTROL_SECRET`, or one that could not bind the control port, says
`#### NO REFEREE ON THIS RELAY ####` once at startup and keeps forwarding.

### 2.7 Relay

| Metric | Meaning | Healthy |
| --- | --- | --- |
| `rollback_relay_forwarded_total` | Packets forwarded. | The workload. |
| `rollback_relay_hello_total` | Slot bindings. | ~2 per session. |
| `rollback_relay_unauthorized_total` | **HELLOs whose slot token was not the one derived for that session and slot.** | **0.** A flood with `hello` flat means core and the relay hold different `RELAY_SLOT_SECRET`s and nothing can bind; a trickle means somebody is trying to bind a slot they do not own. Investigate before dismissing. |
| `rollback_relay_version_mismatch_total` | Sessions aborted because the two peers advertised different protocol versions. | **0.** This is the desync alert caught one layer earlier - same root cause, same fix. |
| `rollback_relay_rate_limited_total` | Packets dropped by the per-source, per-session or global limiter. | Near 0. A rising count is either abuse or a genuinely busy relay. |
| `rollback_relay_invalid_total` | Undecodable packets. | Near 0. |
| `rollback_relay_unknown_source_total` | Packets from an address that never sent a HELLO. | Low; internet background noise. |
| `rollback_relay_no_peer_total` | Forward attempted with only one side bound. | Brief spikes at match start. |
| `rollback_relay_session_cap_total` | Session slots refused at the cap. | **0.** Non-zero means raise the cap or add relays. |
| `rollback_relay_evicted_total` / `rollback_relay_reclaimed_total` / `rollback_relay_orphan_pins_total` | Idle sweep bookkeeping. | Grows slowly with traffic. |
| `rollback_relay_handler_error_total` | The relay loop caught a throwable. | **0.** |
| `rollback_relay_sessions` | Live sessions. | ~ concurrent duels routed via relay. |

---

## 3. Kill switch and rollback

### The kill switch

```
rollback.edge.enabled: false      # mcleagues-core config.yml
```

Set it and reload core (or restart it). Core immediately stops assigning duels to the netcode:
`EdgeSessionBroker.pollResults()` and the metrics refresh stop being called, and
`RollbackHandoffManager` routes matches the ordinary way. **Matches already in flight are not
killed** - they run to their natural end on the edges and their results still land, because the
edges keep flushing their outbox regardless of what core is doing.

Related switches:

| Flag | Effect |
| --- | --- |
| `rollback.edge.direct-links: false` | Every match dials the relay instead of edge-to-edge. Use when a direct link is suspected but the relay is healthy. |
| `EDGE_BROKER_ENABLED=false` on one edge | That edge stops taking assignments. It stops heartbeating into `rollback:edge:servers`, so core stops picking it within 30s. The way to drain one box. |
| `EDGE_METRICS_ENABLED=false` | Stops the HTTP endpoint only. Counters and the Redis publish continue. |
| `EDGE_DIRECT_ENABLED=false` | That edge advertises no direct endpoint; all its matches use the relay. |

### Rolling back a release

1. `rollback.edge.enabled: false` on core. New duels stop going to the netcode.
2. Wait for `rollback_fleet_edge_matches_live` to reach 0 across the fleet (`/rollbackmetrics`).
3. Redeploy **every** edge and **the mod** to the previous tag, in lockstep. Never one then the other.
4. Confirm `rollback_fleet_version_fences == 1` and that the triple matches the old build.
5. `rollback.edge.enabled: true`.

The edge jar and the mod jar are a matched pair. Deploying one without the other is exactly the
partial deploy the alert exists to catch.

---

## 4. Failure modes

### 4.1 A desync storm

**Symptom.** `rollback_fleet_desync_abort_ratio` above 0, the `DESYNC ABORT ALERT` banner in the
core and edge logs, players reporting matches "cancelled" mid-fight, void causes climbing on
`cause=DESYNC`.

**This is a deploy problem until proven otherwise.**

1. `/rollbackmetrics`. Read the **fences live** line.
2. **If more than one triple is listed** - partial deploy. Identify the odd edge from the per-edge
   rows, redeploy it to match the rest, done. If the majority is the *new* build and one box is old,
   push the new jar to it; if the majority is old and one is new, pull the new one back.
3. **If exactly one triple is listed and the mod also matches** (`core expects mod version N` on the
   same line), the two sides speak the same protocol but do not simulate the same way. That is a real
   determinism break:
   - Pull the kill switch (`rollback.edge.enabled: false`).
   - Note `last desync at frame X` from the per-edge rows. That frame number is the diagnostic.
   - Check whether the edges are on the **same CPU architecture** (see 4.3). A mixed-architecture
     fleet desyncs exactly like this.
   - Re-run the cross-machine determinism gate (`.github/workflows/determinism.yml`, or
     `HarnessMain` on one x86_64 and one aarch64 box) and compare stream digests. The first differing
     tick is the offending expression.
   - Do not re-enable until the gate is green on both architectures.
4. **If `rollback_relay_version_mismatch_total` is also climbing**, the relay already caught it: two
   peers advertised different `Protocol.VERSION`. Same fix as step 2, and it confirms the diagnosis
   without needing the edge logs.

Voided desync sessions are **not** awarded to either player (`EdgeReportArbiter.voidCause`), so no
ELO is wrongly moved while you work. That is the one piece of good news during a storm.

### 4.2 A stale mod jar after a partial deploy

**Symptom.** `rollback_core_mod_version_rejected_total` climbing; players told their client is out of
date; or - worse - matches starting and then desyncing because a *third-party* build slipped the
gate.

1. `/rollbackmetrics` - the fence line prints `core expects mod version N`.
2. Compare with what the mod build actually ships:
   `Protocol.VERSION = (InputCodec.BYTES << 8) | (CHECKSUM_REV & 0xFF)`.
3. If core expects a version no released mod has, core was deployed ahead of the mod. Either ship the
   matching mod build now, or roll core's `RollbackModRegistry.EXPECTED_VERSION` back and redeploy -
   **the fence and the sim must move together**, so rolling the constant back alone is only correct
   if the sim in that core jar is also the old one.
4. If the mod is ahead of core, ship core.
5. Rejections that fall off on their own over a few hours after a mod release are just players
   updating. Rejections that plateau mean the launcher is not serving the new jar - check the mod
   distribution, not the netcode.

### 4.3 An edge pinned to the wrong CPU architecture

**Symptom.** One edge desyncs against every other edge and against modded clients, but never against
itself. Its version fence triple is *identical* to everyone else's, which rules out a partial deploy.

The sim is written to avoid every construct whose result may differ across platforms, but until the
cross-architecture gate has actually passed, **the fleet must be pinned to one architecture**.

1. On each edge host: `uname -m`. Any box that disagrees with the rest is the suspect.
2. Drain it: `EDGE_BROKER_ENABLED=false`, wait for `matches_live` to hit 0, stop it.
3. Confirm the desync rate returns to 0 without it.
4. Either move that region's edge onto the fleet's architecture, or run the determinism gate across
   both architectures and only re-admit it once the stream digests match at the current
   `CHECKSUM_REV`.
5. `EdgeArchGate` refuses to enable brokering when `os.arch` does not match `EDGE_EXPECTED_ARCH`,
   so an edge in this state has no value configured at all, or the wrong one. Check its startup log:
   `CPU ARCHITECTURE NOT PINNED` at WARNING means nothing was set and the box was never checked, and
   that is the likely case here. Set `EDGE_EXPECTED_ARCH` on every edge stack to the fleet's
   architecture so the next wrong box refuses instead of brokering, and pin the architecture in the
   scheduler constraints as well. `DEPLOY.md` section 3 has the reasoning.

### 4.4 The relay refuses binds

**Symptom.** `rollback_relay_unauthorized_total` or `rollback_relay_session_cap_total` climbing;
matches abort with `PEER_NEVER_ARRIVED`; `rollback_edge_matches_pending` stuck above 0.

1. `curl http://<relay>:7779/metrics`. Read which counter is moving.
2. `unauthorized` climbing:
   - With `RELAY_SLOT_SECRET` set (the production configuration), a HELLO is accepted only if its
     token equals `HMAC-SHA256(secret, "rollback-slot-v1" || sessionId || slot)`. **The overwhelmingly
     likely cause of a sudden flood is that core and this relay hold different secrets** - one of them
     was rotated or redeployed alone. Every bind fails, so `hello` stays flat while `unauthorized`
     climbs at the rate matches are being handed off. Compare the value on core
     (`rollback.relay-slot-secret` / `ROLLBACK_RELAY_SLOT_SECRET`) with the relay's `RELAY_SLOT_SECRET`
     and roll them back together.
   - A steady trickle with matches still starting is external traffic, and the relay is doing its job.
   - Verify the relay's startup banner says it is verifying: the last startup line reads
     `listening on udp/<host>:<port> in DERIVED mode`. Any other mode means no secret is configured.
     `PINNED_LOOPBACK` means the relay bound `127.0.0.1` and no player can reach it at all - that is
     the no-secret default and in production it presents as *every* match on this relay timing out,
     not as a weak one. `PINNED_PUBLIC` and `OPEN` are reachable and weak, and both require an
     explicit variable (`RELAY_ALLOW_PUBLIC_WITHOUT_SECRET`, `RELAY_ALLOW_UNAUTHENTICATED`) that must
     be **false or absent** in production; `RELAY_ALLOW_UNAUTHENTICATED` is ignored outright while a
     slot secret is set. A relay with no secret and a routable `RELAY_BIND` does not start at all: it
     exits 78 with a `RELAY REFUSED TO START` banner and, in a container, restart-loops on it.
   - In the pinning fallback only, a session id reused across a restart or an edge retrying with a
     token from a previous assignment produces the same counter. Derived tokens do not have that
     failure mode: they are a pure function of the session id, so a retry re-presents the same token.
3. `sessionCap` climbing: the relay hit `maxSessions`. Raise the cap or add a relay. Idle sessions are
   swept, so a cap hit under normal load means real concurrency growth.
4. `rateLimited` climbing on a specific source: that peer is flooding. Per-source, per-session and
   global buckets are all in play; the counter does not say which one tripped, so correlate with
   `forwarded`.
5. **Relay cannot bind at all** (nothing in `/metrics`, process restarting): another process holds
   `RELAY_PORT` udp/7777 or `RELAY_CONTROL_PORT` tcp/7778. Note that a relay whose *metrics* port
   fails to bind logs the failure and keeps forwarding - metrics are never load-bearing.
6. **Relay is up but nothing reaches it** (`hello` flat at 0, every match `PEER_NEVER_ARRIVED`, the
   container answers on no port): read the startup banner. `in PINNED_LOOPBACK mode` means the stack
   has no `RELAY_SLOT_SECRET`, so the relay deliberately bound `127.0.0.1` and a published docker
   port forwards to nothing. Set `RELAY_SLOT_SECRET` to core's `ROLLBACK_RELAY_SLOT_SECRET` and
   redeploy; the relay then binds `0.0.0.0` on its own. Do **not** reach for
   `RELAY_ALLOW_PUBLIC_WITHOUT_SECRET` to make it reachable - that trades an outage for silent
   impersonation of players.
7. While the relay is down, edge-to-edge matches with `rollback.edge.direct-links: true` keep working.
   Only modded clients (behind NAT) and mixed pairs need it. That is your degraded mode.

### 4.5 Redis down

**Symptom.** `rollback_edge_redis_up` is 0, `rollback_edge_results_queued` rising, core logs
`[edge] registry read failed` / `[edge] metrics read failed`, `/rollbackmetrics` shows no live edges.

What survives on its own:

- **Matches in flight keep running.** The sim is peer-to-peer over UDP; Redis is not in the packet
  path.
- **Results are not lost.** Each edge buffers up to 256 results in memory and flushes them when
  Redis returns. The edge logs how many are queued on every poll.

What stops:

- New assignments (core cannot publish, `assignments_failed_total` climbs).
- Edge discovery (`rollback:edge:servers` goes stale after 30s, so `assignments_no_edge_total`
  climbs).
- Fleet metrics (`rollback:edge:metrics` stops refreshing; core's view goes empty after 120s).

Steps:

1. Confirm it is Redis and not one edge: if **every** edge reports `redis_up=0`, it is the server.
   If one does, it is that box's network.
2. Restore Redis. No manual replay is needed - the edges push their queued results on the next poll
   and core drains them.
3. **Do not restart edges while Redis is down.** The outbox is in memory; a restart loses every
   queued result and those matches settle with only one side's report (or get voided).
4. If Redis was down long enough to exceed the 256-result outbox, `results_dropped_total` will be
   non-zero and those matches are gone. Each drop is logged `SEVERE` with the full result JSON -
   that log line is the only remaining record.
5. Consider the kill switch while Redis is unhealthy, so players are not routed into a system that
   cannot record their result.

### 4.6 One region cannot reach another

**Symptom.** Matches between two specific regions abort with `PEER_GONE` while matches inside each
region are fine. `rollback_edge_peer_timeouts_total` climbs on both sides of the pair.

1. `/rollbackmetrics` - compare per-edge `peerTimeouts`. If the elevated counters are symmetric
   between exactly two edges, it is the path between them.
2. Determine whether those two edges use a **direct link** or the relay: an assignment carries a peer
   endpoint only when both edges advertise a direct port and `rollback.edge.direct-links` is true.
   `rollback_edge_direct_link_up` tells you whether each side bound its UDP port.
3. If direct: the two edge hosts cannot exchange UDP on 7787. Check the security group / firewall
   between those two regions. Immediate mitigation: `rollback.edge.direct-links: false` on core,
   which sends every pair through the relay. That costs a hop of latency and is the correct trade
   during an outage. If the port is open and packets are being **rejected** instead, check
   `DirectLink.peerRebinds()`: a channel is pinned to the exact source socket whose `Hello` proved
   the link token, so a peer whose NAT or load balancer keeps moving its source port shows up as a
   climbing rebind count and, between rebinds, as rejected datagrams.
4. If relayed: one region cannot reach the relay. Check `rollback_relay_unknown_source_total` and
   `rollback_relay_no_peer_total` - `no_peer` climbing means one side is arriving and the other is
   not. The one that never arrives is the broken path.
5. To take a region out of matchmaking entirely, set `EDGE_BROKER_ENABLED=false` on its edges;
   `EdgeRegistry` drops them within 30s and `RollbackRegionChooser` routes players elsewhere.

### 4.7 Assignments arrive but matches never start

**Symptom.** `rollback_edge_assignments_unclaimed` above 0 for more than a few seconds,
`assignment_frame_zero_millis_max` climbing, `NO_FRAME_ZERO` outcomes rising.

- The player has not connected to the edge yet. The edge logs an unclaimed warning every 10s naming
  the assignment and whether the player is online. If they are online and it is still unclaimed, the
  arena bytes are not ready (`arenaStore.resolve` is `PENDING`) - watch the paste.
- If `NO_FRAME_ZERO` is dominated by paste timeouts, the arena is too big for
  `arena.blocks-per-tick` / `arena.millis-per-tick`. The wait is capped at 60s.
- `assignments_expired_total` rising instead means the edge was not polling at all: check
  `redis_up`.

### 4.8 A version fence mismatch

**Symptom.** Matches abort immediately, before frame 0. `rollback_relay_version_mismatch_total`
climbing, or `rollback_core_mod_version_rejected_total` climbing, or a modded client refused at the
edge with `[mod-handoff]`. The fence is an exact equality check, so it does not degrade: one stale
artifact refuses every match it touches.

**The log now tells you which artifact.** Every fence refusal prints a block headed
`######## ROLLBACK VERSION FENCE MISMATCH ########` that names both sides' triples, marks one
`LIKELY STALE`, and lists the three artifacts with the command that verifies each. You do not need
to open a jar.

The rule the `LIKELY STALE` line applies: `checksumRev` is the middle number of the triple and it
only ever goes up, so the side with the **lower** `checksumRev` is the older build.

Where each refusal is printed:

| Refusal | Printed by | Both sides visible? |
| --- | --- | --- |
| relayed peers disagree | relay stdout, `[relay]` | yes, slot 0 and slot 1 |
| modded client vs its edge | edge log, `[mod-handoff]` | yes, mod and edge |
| direct link never binds | edge log, `[direct-link]`, on the metrics tick | yes, peer and this build |
| core's mod gate | practice server log, `[rollback]` | yes, client and `EXPECTED_VERSION` |
| a peer aborted us | the aborting side's log; this side prints its own triple only | no |

Three artifacts can be the stale one, and each has a check that fails rather than guesses:

```
jackpot-rollback     gradlew verifyEmbeddedSimVersion   # jackpot-edge.jar, relay.jar, limbo.jar
pvphq-rollback-mod   gradlew verifyModJars              # versions/<mc>/build/libs/pvphq-mod-<mc>.jar
mcleagues-core       gradlew verifyRollbackFence        # mcleagues.jar EXPECTED_VERSION
```

`gradlew modJars` in the mod repo lists every mod jar on disk **with the protocolVersion it will
send**, newest first. Any version build deletes a sibling jar whose nested sim-core is not current,
and refuses the build if it cannot delete it, so a jar you can still see is a jar that was current
when it was last swept.

Local dev: `gradlew devSetup` deploys the jar it just built to every edge under `build/devenv`,
verifies the copy byte for byte, clears the Paper remap cache (which is keyed by file name, not by
content, and has previously made a server start with **no plugin at all**), and prints the deployed
fence per node. It refuses rather than leaving a stale plugin behind.

---

## 5. Daily checks

```
curl -s http://<any-edge>:7788/            # human summary, includes the alert banner
curl -s http://<relay>:7779/metrics | grep -E 'unauthorized|version_mismatch'
```

In game, as staff: `/rollbackmetrics`.

Green looks like: one version fence, desync ratio 0.000, every edge `redis=up`, `queuedResults=0`,
`deficit` in single digits, `FINISHED` dominating the outcome breakdown, and
`rollback_relay_unauthorized_total` and `rollback_relay_version_mismatch_total` both flat at 0.

## 5a. Cross-play: bringing one up by hand

Cross-play is one modded client, hosted on its own machine in the limbo, against one **unmodded**
client hosted by an edge plugin. The two halves never share a process, so every identity between
them has to be handed to them by the server: **one session id, one setup blob, one arena, one relay,
and a different per-slot token derived from the same secret.** Anything that differs shows up as an
`ARENA_MISMATCH` before frame 0 or a desync a few seconds in, never as a clean error.

### The switches core needs

Both default to off, so an untouched `config.yml` never produces a cross-play match. In
`plugins/MCLeagues/config.yml`, or as environment variables:

| key | env | why cross-play needs it |
| --- | --- | --- |
| `rollback.edge.enabled: true` | `ROLLBACK_EDGE_ENABLED` | the unmodded half is simulated by an edge plugin. Off, a mixed pair is refused outright (`REFUSE_NO_MOD`) |
| `rollback.cross-play: true` | `ROLLBACK_CROSS_PLAY` | off, a mixed pair is hosted **entirely** on the edge instead - which works, and is not cross-play |
| `rollback.limbo-server` or `limbo-host`/`limbo-port` | - | the modded half is transferred here. With no reachable limbo the router falls back to edge-vs-edge |
| `rollback.relay-slot-secret` | `ROLLBACK_RELAY_SLOT_SECRET` | must equal `RELAY_SLOT_SECRET` on the relay. Set on one side only, every session silently fails to pair and `rollback_relay_unauthorized_total` climbs |
| `rollback.referee.control-secret` | `ROLLBACK_REFEREE_CONTROL_SECRET` | must equal `RELAY_CONTROL_SECRET` on the relay. Cross-play authorizes the referee at handoff exactly as the mod path does, so with it set the relay re-simulates the mixed match and its verdict outranks either host's report. Unset, the handoff logs `[cross-play] referee: ... falls back to two-witness corroboration` and the match is decided by the two hosts alone |

Cross-play also requires a real, published arena, and unlike the edge-vs-edge path it does not read
`rollback.edge.require-real-arena` at all - it refuses outright. The modded client builds its
collision from the bytes the server ships it and has no arena file to fall back on, so without them
the two hosts would be fighting on different arenas.

### Running one locally, end to end

Two Minecraft clients are needed for the last step; everything before it is checkable headlessly.

```
cd jackpot-rollback
gradlew devStackUp                                   # redis 6380 + mongo 27018 in docker
gradlew devLimboProbe                                # every limbo capture logs in and transfers out
gradlew devRunBrokered -PrelaySlotSecret=devsecret   # relay + limbo + edge-a + edge-b (blocks)
```

Wait for `edge ready:` from **both** edges and `listening on udp/127.0.0.1:7777 in DERIVED mode`
from the relay. Then, from a second terminal:

```
gradlew devAssign -PplayerA=<modded> -PplayerB=<vanilla> -Pmod=A -ParenaName=colosseum -PgameType=CRYSTAL -PrelaySlotSecret=devsecret
```

`-Pmod=A` is the cross-play shape: slot 0 is MOD-hosted, slot 1 is EDGE-hosted. `-ParenaName` is
**not** optional here - `devAssign` refuses a MOD slot with no arena bytes for the reason above.
The two players must then join the ports the tool names: the modded one `127.0.0.1:25566`, the
unmodded one `127.0.0.1:25567`. Joining them the other way round starts nothing and both edges log
`waiting for <name> to join THIS edge`.

Stop it with `gradlew devStop` (ctrl-c is trapped by gradle), and `gradlew devStackDown`.

### What to check when it does not start

| symptom | cause |
| --- | --- |
| both edges log `waiting for <name> to join THIS edge` forever | the two players are on each other's ports, or one never joined. Re-push with the names swapped |
| `STALE EDGE PLUGIN - REFUSING TO ENABLE` | the jar under `plugins/` is not the one gradle built. On a server that remaps plugins on load, the fence compares the source hash `.paper-remapped/index.json` names, so this really is a stale deploy: `gradlew devSetup`. Every `:edge:jar` now clears `plugins/.paper-remapped` and `devVerifyEdgeDeploy` resolves what Paper will LOAD rather than hashing the jar it just wrote, so if `devSetup` does not clear the banner run `gradlew devVerifyEdgeBoot`: it starts each edge for real and fails naming the node whose own fence refused. If THAT passes and a manual run still refuses, it is a fence bug, not a stale jar |
| the relay shows `hello=0` and `unauthorized` climbing | the secret the assignment derived tokens from is not the one the relay verifies against. Both sides take the same string |
| `refusing the match ... the arena bytes ... are unusable` | the peer reads the same redis key, so starting on a local arena instead would desync. Re-push the assignment |
| the modded client lands in the limbo and nothing happens | the mod never got `match_setup`, or it got it more than 30s before the limbo JOIN. Check the client log for `match setup received` |
| `ARENA_MISMATCH ... frame 0 state` | the mod jar and the edge jar do not decode the same setup blob into the same opening state. One of them is stale - compare the triples below |

Every artifact in a cross-play match carries the same fence triple. Read it off all four:

```
curl -s http://<edge>:7788/metrics  | grep rollback_edge_build_info
curl -s http://<relay>:7779/metrics | grep rollback_relay_build_info
```

and the mod with `gradlew verifyModJars` in `pvphq-rollback-mod`, and core with
`gradlew verifyRollbackFence` in `mcleagues-core`. Four numbers, one value.

Under `build/devenv` the two edges serve those metrics on **7797** and **7798** instead of 7788,
because they share a box and one metrics port cannot serve both.

## 6. Limbo captures (one per Minecraft version)

A MOD-hosted slot is not hosted by its edge: the edge sends the client `match_setup` + arena +
cage and then transfers it to the limbo, which holds the client in an empty world while the mod
drives the sim. The limbo does not implement Minecraft. It **replays a recorded configuration and
join stream**, so it needs one capture pair per protocol version, and a version with no capture
cannot be served at all.

`pvphq-rollback-mod` builds three targets, so the limbo needs three pairs:

| Minecraft | protocol | config | play | chunk sections |
|---|---|---|---|---|
| 1.21.11 | 774 | `limbo/config.bin` | `limbo/play.bin` | one short (no fluid count) |
| 26.1.2 | 775 | `limbo/config-26.1.2.bin` | `limbo/play-26.1.2.bin` | two shorts (fluid count) |
| 26.2 | 776 | `limbo/config-26.2.bin` | `limbo/play-26.2.bin` | two shorts (fluid count) |

### Recording one

```
gradlew devLimboCapture -PcaptureVersion=26.1.2
```

No Minecraft client and no human are involved. The task downloads that version of Paper (checking
the sha256 papermc publishes), generates the server's own `--reports` packet report, provisions a
throwaway flat server with `online-mode=false` and `network-compression-threshold=-1`, logs a
headless client in with the ids from that report, and writes the two `.bin` files. Every packet id
comes from the report, so nothing is guessed.

### Proving the limbo can actually serve them

```
gradlew devLimboConfig devLimboProbe
```

`devLimboProbe` starts the limbo on a scratch port and joins it once per configured version with a
headless client. For each one it checks that login finishes, that the configuration replay
completes, that every void chunk the limbo builds decodes, and that the `jackpotrollback:return`
payload comes back as a clientbound Transfer. It fails the build if any version cannot be served.
It also cross-checks the configured play ids against that version's own packet report, so a stale
`play.id.transfer` or `play.id.custompayload` is caught rather than agreed with.

### Failure mode: a version with no capture

The limbo used to fall back to the FIRST capture for any protocol it did not recognise, which meant
a 26.x client was sent 1.21.11 registry data and disconnected mid-handshake with nothing in any log
to explain it. It now refuses that login with a real disconnect screen naming the protocol, and
`captures.lenient=false` (the default) makes a declared-but-missing capture abort startup instead of
producing a limbo that looks healthy.

### When a Minecraft version is added to the mod

1. add it to `versions/` in `pvphq-rollback-mod/settings.gradle`
2. add it to `ext.limboVersions` in `devenv.gradle` and to `limbo/limbo.properties` (as
   `capture.N.protocol` / `.name` / `.config` / `.play`)
3. `gradlew devLimboCapture -PcaptureVersion=<version>`
4. `gradlew devLimboConfig devLimboProbe` and `gradlew :limbo:test`

## 7. What CI proves, and what it does not

`.github/workflows/determinism.yml` ends with a `gate` job that writes the table below into the run
summary with live results. Read that summary, not the badge.

### Proven by a green run

| axis | how |
| --- | --- |
| every module here compiles and its tests pass | root `build` (sim-core, sim-host, edge, relay, limbo) on linux-x64 and windows-x64 |
| those tests actually ran, rather than being restored from the build cache | `verifyGatesRan`, finalizer of every test task and every `check` |
| the committed reference digest still matches | `checkHarnessDigest`, wired into `check` |
| Linux and Windows agree tick for tick | two full harness streams compared byte for byte |
| every jar built here embeds this sim-core | `verifyEmbeddedSimVersion` |
| the limbo can serve every configured Minecraft version | `devLimboProbe`: headless login, spawn and return-transfer per protocol |
| mcleagues-core expects the version this sim-core sends | `VersionFenceTest` against a real core checkout, when `MCLEAGUES_CORE_REPO` is set |
| the three mod jars nest this sim-core | `verifyModJars` after building every stonecutter target, when `MOD_REPO` is set |

### NOT proven by a green run

- **The architecture axis. Phase 3 stays red.** Both harness runners are x64. Only an x64 run and an
  arm64 run agreeing closes it. The `harness_arm64` and `cross_architecture` jobs are written and
  skipped; set the repository variable `ARM64_RUNNER` to an aarch64 label and they become real gates.
  Linux and Windows agreeing is real, and it is all the workflow claims.
- **mcleagues-core compiling.** Only its `RollbackModRegistry` source is read. Core resolves
  `JackpotMCFramework` and the Bookstore api out of a developer's local Maven repository, so no
  hosted runner can build it.
- **The mod at runtime.** `mod_jars` builds and fences the jars; no Minecraft client is launched.
- **A real two-client duel.** Nothing in CI plays a match.

### What BUILD SUCCESSFUL means

`org.gradle.caching=true` is on and stays on. On a warm cache that used to mean `gradlew clean build`
restored every test result and executed no test at all - `> Task :sim-core:test FROM-CACHE` - while
still printing BUILD SUCCESSFUL. The numbers were genuine, but the verification was not the one the
line appeared to claim, and CI is worse than a laptop here because `setup-gradle` restores the cache
between runs.

Two things now stand between you and that:

- **Test tasks are declared non-restorable.** `outputs.cacheIf { false }` *and*
  `outputs.upToDateWhen { false }` in the root `build.gradle`, because those are separate gates: a
  task that is only "not up to date" still gets a cache lookup, and a task that is only "not
  cacheable" is still skipped as UP-TO-DATE. Everything else - compilation, jars, resources - is a
  pure function of its declared inputs and is still restored, so a full forced run is about a minute.
- **`verifyGatesRan` proves it after the fact.** Every gate stamps a per-invocation id into
  `build/gate-evidence` at the moment it really runs. `verifyGatesRan` finalizes every test task and
  every `check`, prints a per-gate verdict, and fails the build on any gate that left no stamp:

```
=== what this build actually verified ===
  :sim-core:checkHarnessDigest           EXECUTED    harness replayed against reference-digest.txt
  :sim-core:test                         EXECUTED    1011 tests, 0 failed, 0 skipped
  :verifyEmbeddedSimVersion              EXECUTED    5 embedded sim-core copies checked
```

If somebody makes test results cacheable again, the resulting silent build turns RED instead of
green. Read the block, not the last line.

Why the test tasks and not everything: a test here is not a function of a fixed input set. These
tests read the mod tree, the mcleagues-core tree, this build's own scripts, a committed golden digest
and the limbo capture blobs, and several assert things about the state of the build tree itself. The
input declarations for all of that stay in `build.gradle` and `limbo/build.gradle` and are still
asserted by `CachedTestsDeclareWhatTheyFenceTest` and `GreenBuildMeansTheGatesRanTest` - a wrong
cache key is a correctness bug in its own right, not something switching restoration off would fix.

### The two cross-repo checkouts

The mod and mcleagues-core are separate trees, so CI has to fetch them:

| variable | secret | what turns on |
| --- | --- | --- |
| `MCLEAGUES_CORE_REPO` (`owner/name`) | `MCLEAGUES_CORE_TOKEN` if private | the version fence is compared for real |
| `MOD_REPO` (`owner/name`) | `MOD_REPO_TOKEN` if private | all three mod jars are built and fenced |
| `ALLOW_UNFENCED_CI=true` | | states in writing that this repo does not gate on the fence |

With `MCLEAGUES_CORE_REPO` unset and no waiver on file the `gate` job fails on purpose. The fence
used to `assumeTrue(Files.exists(...))` and pass silently on every machine without a core checkout,
which is every CI machine there has ever been. It now fails whenever `CI=true` or
`-Drollback.versionFence=require`, and the workflow runs a negative control that asserts the test
FAILS against a checkout that does not exist, so the guard cannot quietly evaporate again. A
standalone checkout with no core beside it still skips.

### Running the same checks by hand

```
gradlew build                                        # every module, every test
gradlew verifyGatesRan                               # per-gate verdict; already a finalizer of check
gradlew verifyEmbeddedSimVersion                     # jars built here
gradlew verifyEmbeddedSimVersion -PjarDir=<dir>      # any other jars, mod jars included
gradlew devLimboConfig devLimboProbe                 # limbo serves every version
gradlew :sim-core:test --tests '*VersionFenceTest' -Drollback.versionFence=require
```

and in the mod checkout:

```
gradlew 1.21.11:build 26.1.2:build 26.2:build
gradlew clearDevlibs                                 # drop the unremapped jars the build leaves
gradlew verifyModJars                                # each jar nests the current sim-core
```

`clearDevlibs` is not optional before shipping. Every version build leaves its unremapped jar in
`versions/<mc>/build/devlibs` because that file is `remapJar`'s input. It carries `fabric.mod.json`
and the mixin config, so a client loads it and then dies on the first mapped name. `verifyModJars`
used to log `NOT INSTALLABLE` about it and pass anyway, which put the trap on the record as known and
harmless; it now refuses the tree until the file is gone. No version build deletes it - a build must
not reach into another version's output directory while that build may be running - so removing it is
a deliberate step you run when nothing else is in flight. The next build regenerates the one it needs.
