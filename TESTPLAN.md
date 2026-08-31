# TESTPLAN

How to test the rollback netcode with two Minecraft clients and a couple of hours.

Read this before starting. It is short on purpose. `RUNBOOK.md` is the reference for anything that
goes wrong; this document is only the order to do things in.

**Run the three configurations in the order below.** Each one adds exactly one layer, so a failure
tells you which layer broke:

| # | configuration | what it adds | what it proves |
|---|---|---|---|
| 1 | server-sided vs server-sided | nothing. Two edge plugins, no mod, no limbo | the sim, the arena, the relay, the result path |
| 2 | mod vs mod | the mod jar, the limbo transfer, client prediction | the client half, in the easy case where both halves are identical |
| 3 | server-sided vs mod (cross-play) | two DIFFERENT hosts in one match | frame-0 agreement across two decoders |

Do not start at 3. If cross-play desyncs and you have not run 1 and 2, you have three suspects
instead of one.

**If you read nothing else on this page, read this.** The three mod jars sitting in the tree were
built before the last two source changes and do not contain them, and **`verifyModJars` cannot tell
you that** - it compares the protocol fence, the fence did not move, so it passes on these jars
today. Build them anyway. The three `:build` lines are under *Before anything* and they are the
single most likely thing to cost you an hour, because section 1 never loads a mod jar and will run
perfectly on a stale one. Everything else on this page is a checklist; that one is a prerequisite.

---

## Before anything

Three artifacts have to carry the same protocol version or every match aborts at the fence with no
useful error: the **mod jar**, the **edge plugin** and the **relay**. Build them from the current tree
and read the number back:

```
cd jackpot-rollback
gradlew --stop                                       # see time-waster 4. do this first, every time
gradlew clean build --no-build-cache                 # every module, every test
gradlew :sim-core:checkHarnessDigest                 # the determinism gate
gradlew devSetup devVerifyEdgeBoot                   # both dev edges really boot on this build

cd ../pvphq-rollback-mod
gradlew 1.21.11:build 26.1.2:build 26.2:build        # NOT optional. the jars are behind the source
gradlew verifyModJars                                # the fence only; it will NOT catch that
```

**There is no `updateHarnessDigest` line, and you must not add one.** An earlier draft of this page
told you to re-record the determinism reference before building. **That has since been done, and was
done again when the container-drop fix moved the rev to 170.**
`sim-core/src/main/resources/me/nootnoot/sim/harness/reference-digest.txt` now reads
`checksum-rev=166`, `final-checksum=98b78a5d0629ac15`, `stream-digest=48453cac71e1cd3a`,
`rollback-digest=33edd517998bd147`, `arena-hash=fe21a2f81e31bc99`, which is exactly what 170 replays
to. Note that only the rev moved: all four digests are byte-identical to what 165 produced, because
the scripted scenario never drops an item out of an open container, so the new path it opened is NOT
covered by this gate. That is a coverage gap, not a passing grade for the new code. So `clean build --no-build-cache` should be **0 failures** across five gates - sim-core, sim-host,
edge, relay and limbo - and `:sim-core:checkHarnessDigest` should print `OK: digest matches the
committed reference`.

If `checkHarnessDigest` fails on this checkout, **that is a finding, not a chore.** Do not reach for
`updateHarnessDigest` to make it green: the reference already describes this rev, so a mismatch means
either somebody changed a simulated rule without moving the rev, or this machine does not agree with
the amd64 run that recorded it - which is precisely the cross-architecture question section 8 of
`HANDOVER.md` says is open. Capture the whole harness block it prints and stop.

Read the test count out of the `=== what this build actually verified ===` block the build prints for
itself rather than off a number written down here; the totals move whenever tests are added and the
assertion that matters is that every gate reports EXECUTED and zero failed.

`devVerifyEdgeBoot` is the slowest line in that list. It starts each Paper edge for real and waits for
it to reach `edge ready:`, with a 300-second ceiling per node (`-PbootTimeoutSeconds` moves it). Two
edges booting cold on a fresh checkout is a few minutes, and that is the task working, not hanging.

`verifyModJars` no longer just reports: it **fails the build** on a stale jar and on any unremapped
`devlibs` jar left in the tree. You no longer have to clear those by hand: every version build now
deletes its own the moment the remap that reads it has run, so the three `:build` lines above leave a
tree the gate passes. `gradlew clearDevlibs` still exists and is the remedy if `verifyModJars` ever
prints a `NOT INSTALLABLE` line, which now means an interrupted build left one behind.

**The three mod jars in the tree are behind the source, and `verifyModJars` will not say so.** This
was checked rather than assumed. The `sim-core` nested inside
`versions/1.21.11/build/libs/pvphq-mod-1.21.11.jar` reports `CHECKSUM_REV = 171` and
`VERSION = 17336`, which is exactly what the tree compiles to - so all three jars pass the fence and
`verifyModJars` prints `VERSION 17336 OK` six times **before** you build anything. That is not proof
they are current. `verifyModJars` compares one number, the protocol fence, and the fence only moves
when simulated behaviour or the checksum function does. Two source changes have landed since these
jars were built and neither moved it:

- `RollbackController.RESIM_FRAMES_PER_ADVANCE` was re-derived from 64 to 24. The jars still carry
  64. It is a local resource policy, not replicated state, so a 64-jar against a 24-edge does not
  desync - the two sides simply disagree about when a peer has demanded too much resimulation.
- `Containers.decodePeerStack` in the mod gained a hard per-stack decode window. The jars do not have
  it. It is receive-side hardening on shulker and ender-chest sync, so its absence shows up as
  nothing at all until somebody sends a hostile blob.

So the three `:build` lines above are still the one build step on this page that is genuinely not
optional, and the way to know they worked is the build succeeding, not `verifyModJars` passing. Run
`verifyModJars` anyway, after the build: it is what catches an unremapped `devlibs` jar, and a jar
that fails the fence is a different and much louder problem than a jar that is merely behind.

**Both edges must be on the same jar.** CHECKSUM_REV 165 changed the framing of the direct
edge-to-edge link (every datagram now carries a per-packet HMAC tag and a replay counter, not just the
handshake). A peer edge running an older jar is refused with a named banner rather than misparsed, but
the match will still sit at the arena handshake until it times out. If a direct-link match never
starts, look for `[direct-link] the peer on the direct link is still framing datagrams the pre-tag
way` in the edge log before looking at anything else. Setting `direct.enabled=false` puts every match
back on the relay, which is unchanged.

That paragraph is about a real fleet, not about this stack. `devSetup` provisions both dev edges from
one build in one pass and fails outright if they end up disagreeing on a protocol version, and nothing
on this page hands `devAssign` the `-PdirectA` / `-PdirectB` pair a direct link needs, so every match
here is relayed and both edges are by construction the same jar.

`mcleagues-core` is a fourth artifact carrying the same fence in production, but it is **not part of
the dev stack** and you do not need to build it for any of this. `devAssign` writes assignments into
Redis directly, which is what core would otherwise do.

**The relay referee is switched on here.** It is the third witness: it re-simulates both input
streams off the packets the relay forwards and signs the result, and on the network that verdict
outranks either client's word. `devRun` starts the relay with `RELAY_CONTROL_SECRET` set (default
`dev-referee-secret`) and passes it, with `-PrelaySlotSecret`, to both edges. The EDGE that picks an
assignment up is what authorizes it, because it is the only thing here with a server to build frame 0
with, and it authorizes at pickup - before either client can connect and before a single frame is
forwarded, which is the ordering the referee needs (`RefereeManager.tee` drops frames for a session
that is not authorized yet, so a late authorize leaves a hole at frame 0 that never closes). Both
edges try; the relay keeps the first and refuses to reset a referee that has already taken frames.
Expect TWO lines like this, one per edge, before you join:

```
[edge-a] [JackpotEdge] [referee] session 123456 authorized with the relay at 127.0.0.1:7778 (32521B of frame 0, 359400B of arena)
```

and the relay prints the verdict in the `devRun` window:

```
[relay] referee: watching session 123456 for /127.0.0.1:54321 - every forwarded input frame is re-simulated here
[relay] referee verdict: session 123456 - slot 0 wins 3-1 after re-simulating 4200 frames
```

**Watch for that pair on every duel you push with `devAssign`, and only on those.** Three things about
the referee are easy to misread on this stack, so they are stated flatly:

- **Section 1 produces no verdict at all, and that is correct.** The relay only re-simulates a session
  somebody *authorized*, and `devAssign` is the only thing here that authorizes. A `/edge` match is
  never authorized, so in section 1 you will see `[relay] referee + control endpoint on tcp/7778` at
  startup and then nothing else about the referee for the whole match. Do not spend time hunting for a
  verdict there. The referee rows below live in sections 2 and 3.
- **`the verdict above could not be delivered` is expected here.** `devAssign` writes the authorize
  frame, waits 250 ms and closes its socket; it is a one-shot tool, not a long-lived control client
  like `mcleagues-core`. So at match end the relay prints its verdict and then immediately prints that
  it could not hand it to whoever authorized the session. The verdict line above it is the real
  output. On the network core stays connected and receives it.
- **A direct edge-to-edge link has no referee by construction.** The relay is the referee's only
  vantage point. `devAssign` only takes the direct path when you pass **both** `-PdirectA` and
  `-PdirectB`, and nothing on this page does, so every match here is relayed - but if you experiment
  with those flags, expect the referee to fall silent.

**What the dev stack still cannot show you** is the consuming half: the side that weighs a verdict
against the client reports is `mcleagues-core`, which is not in this stack, so nothing here acts on
the verdict - read it, do not expect it to change the outcome. A line reading
`#### NO REFEREE ON THIS RELAY ####` (printed by the relay at startup) or
`[referee] session ... gets NO referee:` (printed by the edge, with the reason) means that duel
really did run with two witnesses instead of three; say so in the report rather than ignoring it.
The absence of the two `[referee] ... authorized` lines means the same thing. `-PnoReferee` on
`devRun` deliberately runs the degraded two-witness shape - it is no longer a `devAssign` flag,
because `devAssign` no longer authorizes anything.

**One more thing the dev stack cannot test.** `devAssign` cannot carry a real per-player kit, because
it has no server to serialize an inventory with, so every match uses a demo kit. As of 2026-08-26
that is a phase-5 gap and no longer a mechanics gap: the demo kits were extended to reach every
mechanic the sim implements, and the short list of things still out of reach - all of them unmodelled
rather than unequipped - is under *Deferred* after the checklist.

The mod jars land in `pvphq-rollback-mod/versions/<mc>/build/libs/pvphq-mod-<mc>.jar`.

**The dev edges are Paper 1.21.11.** Use `pvphq-mod-1.21.11.jar` and a 1.21.11 client for all local
testing. The other two jars exist for the live fleet and cannot join the dev stack at all.

**Game type is CRYSTAL for all three configurations**, which is the only one with every mechanic
switched on. The single exception is the splash-potion block at the end of the mechanics checklist,
which needs the POT kit and says so where it sits. Any
other type disables things silently, and the edge prints a banner at match start naming every disabled
item that is still in your kit. In section 1 you pass nothing: the dev edge config carries no
`game-type` key and the plugin defaults to CRYSTAL. In sections 2 and 3 `devAssign` also defaults to
CRYSTAL, and the flag is written out anyway so the commands can be copied without a surprise.

**Rounds default to 1 in every configuration**, meaning the match ends at the first death and you
will never see a round reset, never see the cage return, and never find out whether anything survives
a round boundary. Several rows in the checklist below need more than one round to mean anything, so
every command below passes `-Prounds=3`.

`rounds` is **first-to-N, not best-of-N**. It sets `GameState.roundsTarget`, which is the number of
round wins that ends the match, and the edge says so when it starts one: `first to 3`. So
`-Prounds=3` is a first-to-three, at most five rounds. Where the flag has to go differs and is called
out at each step, because `/edge` takes rounds from the edge config file written at provisioning time
while a brokered match takes them from the assignment.

---

## 1. Server-sided vs server-sided

No redis, no mod, no limbo, no assignment. Two accounts, two servers, one command each.

```
cd jackpot-rollback
gradlew devRun -Prounds=3       # blocks. relay + limbo + edge-a + edge-b
```

One command. `devRun` already builds and deploys the edge plugin, verifies the deployed jar is the one
this tree just built, writes both edge configs and pastes an arena, so there is no separate setup
step. `-Prounds=3` has to be on **this** invocation, because it is written into both edge config
files at provisioning time, not read at match start.

**The arena in section 1 is not `colosseum`.** `devRun` depends on `devSampleArena`, which *generates*
a voxel arena into each edge's `arena.bin`: a 48-block radius, floor at y **-61**, an 8-block wall,
seed 1337. It is real voxel terrain rather than the flat fallback, which is what the arena handshake
and the collision rows need, but it is not one of the nine shipped `.farena` files. Those only enter
in sections 2 and 3, via `-ParenaName`. Two consequences worth knowing before you start the checklist:
there is no natural high ground here, so the mace and elytra height rows need you to pillar up with
the kit's obsidian, and the arena is a bowl 96 blocks across, which is long enough for the pearl and
long-shot rows.

**Two startup lines that look alarming in section 1 and are not.** Because this command passes no
`-PrelaySlotSecret`, the relay starts with no slot secret, prints
`#### RELAY BOUND TO LOOPBACK: NO RELAY_SLOT_SECRET ####` and then
`listening on udp/127.0.0.1:7777 in PINNED_LOOPBACK mode`. That is the gate deliberately refusing to
serve anything but loopback while it has no secret, which is exactly right for a local test. Section 2
passes a secret and you get `DERIVED` mode there instead.

Wait for both `edge ready:` lines. Then join:

- account 1 to `127.0.0.1:25566`
- account 2 to `127.0.0.1:25567`

Type `/edge` on **both**. There is no permission on it, so an ordinary non-op account can run it. The
match starts once both edges agree on the arena hash.

`/edge stop` ends a match in progress without touching the stack, which is how to get out of a match
you have finished testing and start a fresh one. `/edge metrics` prints this edge's counters in chat.

Stop the whole stack with `gradlew devStop` from another terminal. Ctrl-C is trapped by gradle and
leaves the servers running.

**Expected:** both players are caged, released together, and fight. Damage, knockback and death
happen at the same instant on both screens. A round ends, the cage returns, the next round starts.
After the last round both clients report the same winner and the same score.

**Failure looks like:** the match never starting because only one player typed `/edge`, or the arena
handshake timing out because one edge has an `arena.bin` and the other does not. A checksum mismatch
here is a real sim bug rather than a deploy problem, because both edges came out of one provisioning
pass.

If either edge prints `STALE EDGE PLUGIN - REFUSING TO ENABLE` and disables itself, do not just re-run
`devSetup`: `devRun` already ran it, and `devVerifyEdgeDeploy` would have failed the build first. That
task no longer just hashes the jar on disk - it resolves what Paper will actually LOAD, which means the
jar under `plugins/`, any second jar sitting beside it, and any `.paper-remapped` copy whose recorded
origin is not this build. Every ordinary `:edge:jar` also clears `plugins/.paper-remapped` now, so a
stale remapped copy is not a live explanation for that banner either. If you see it anyway, run
`gradlew devVerifyEdgeBoot`: it starts each edge for real and fails naming the node whose fence
refused. If THAT passes and a manual `devRun` still refuses, it is a fence bug rather than a stale jar.
Capture both edge logs and see RUNBOOK.md.

Work the full mechanics checklist here. This is where most of it gets done.

---

## 2. Mod vs mod

Adds the mod jar, the limbo transfer and the prediction layer. Redis is needed now, so the stack
comes up differently and matches arrive as assignments instead of `/edge`.

Install `pvphq-mod-1.21.11.jar`, plus Fabric loader and Fabric API, on **both** clients.

Two preconditions this section has that section 1 did not. **Docker must already be running** -
`devStackUp` shells out to compose against `devstack/docker-compose.yml`, and if the daemon is not up
the failure arrives as a compose error rather than as anything about the netcode. And section 1's
stack must be **stopped first** (`gradlew devStop`): both sections bind the same ports, and
`devRunBrokered` re-provisions both edge configs, which it cannot do usefully while the old pair still
holds them.

```
gradlew devStop                                      # if section 1 is still up
gradlew devLimboProbe                                # every limbo capture logs in and transfers out
gradlew devRunBrokered -PrelaySlotSecret=devsecret   # blocks. brings docker up itself
```

`devRunBrokered` is not just `devRun` plus containers: being in the task graph is what makes
`devSetup` write `broker.enabled: true` into both edge configs. An edge provisioned by a plain
`devRun` ignores assignments entirely, and `devAssign` refuses to push to a stack in that state with a
named banner rather than failing silently. That is why section 2 cannot be run on section 1's stack.

`devRunBrokered` depends on `devStackUp`, so Redis on 6380 and Mongo on 27018 come up as part of it.
That dependency alone was not enough until 2026-08-26: `devRun` blocks on the four processes it spawns
and Gradle scheduled it ahead of `devStackUp`, so the containers never started and `devAssign` then
failed to reach Redis. `devRun` now carries `mustRunAfter 'devStackUp'`, which pins the order. If you
are on an older checkout, run `gradlew devStackUp` yourself before `devRunBrokered`.
Run `devLimboProbe` first anyway: it is quick, and it tells you the limbo can accept a client before
you spend a match discovering it cannot.

Wait for `listening on udp/127.0.0.1:7777 in DERIVED mode` and both `edge ready:` lines. Join both
clients (25566 and 25567), and only **then**, from a second terminal:

```
gradlew devAssign -PplayerA=<name1> -PplayerB=<name2> -Pmod=both -ParenaName=colosseum -PgameType=CRYSTAL -Prounds=3 -PrelaySlotSecret=devsecret
```

`-ParenaName` is not optional with `-Pmod`. A modded client builds its collision from the bytes the
server ships it and has no local arena to fall back on.

`-Prounds=3` goes on `devAssign` here, not on the stack. A brokered match takes its round count from
the assignment and ignores the edge config, so the value you passed in section 1 does not carry over.

**Expected, in this order:** each client logs `match setup received`, waits out a deliberate
**40-tick (two second) transfer delay** on its edge, is transferred into the limbo, and the world
redraws as the mod's own render of the sim. Then the cage, the release, the fight. Your own actions
feel instant because the mod predicts them; the opponent is a few frames behind, which is correct.

**Failure looks like:** the client lands in the limbo and nothing happens (the mod never got
`match_setup`, or the assignment had already expired when the limbo join arrived - `DevAssignMain`
stamps every assignment 120 seconds ahead, the same number time-waster 2 quotes), or the
relay showing `hello=0` while `rollback_relay_unauthorized_total` climbs, which means the secret
given to `devRunBrokered` and the one given to `devAssign` are not the same string.

**Two refusals the edge states in words rather than by hanging, and both are worth recognising on
sight.** If the client never sent the mod's hello channel, the edge refuses the handoff saying so and
tells you to install the mod or drop `-Pmod` for that slot - which is what an unloaded Fabric mod
looks like, not a netcode fault. And if the mod IS loaded but is a different build, the edge prints a
`[mod-handoff] refusing the MOD-hosted slot` block naming **both** fence triples, the client's and the
edge's. Both should read `67 / 171 / 17336` on this tree; if the client's differs, you are running a
jar from some older tree, so rebuild, reinstall on both clients and push the assignment again. Note
what this block does **not** catch: a jar built from this tree before its last source change carries
the right triple and is waved straight through, which is why *Before anything* tells you to build
rather than to check.

**One thing on the end-of-match screen that is NOT a defect.** The mod's `MatchEndScreen` shows the
winner, the round scores and the combat stats off its own data, but the RATING and RANK panel reads
`Stats syncing…` and stays there. Those numbers arrive in a result payload that `mcleagues-core`
sends, and core is not in the dev stack, so on every dev match they never arrive. Everything else on
that screen is live. Report a wrong winner or a wrong score; do not report the syncing line.

What to watch for here that section 1 cannot show you: **visual rollback artifacts.** A hit that
lands and then un-lands, an item that appears and vanishes, a player who snaps backwards further
than a body's width. Small corrections are the design working. A visible teleport, or a health bar
that disagrees with the damage you just watched land, is not.

---

## 3. Server-sided vs mod (cross-play)

The only configuration where the two hosts are different programs. One modded client, one completely
vanilla client.

Same stack as section 2, so leave it running. One client keeps the mod; the other must be **vanilla,
no mod at all**. Then:

```
gradlew devAssign -PplayerA=<moddedName> -PplayerB=<vanillaName> -Pmod=A -ParenaName=colosseum -PgameType=CRYSTAL -Prounds=3 -PrelaySlotSecret=devsecret
```

`-Pmod=A` makes slot 0 mod-hosted and slot 1 edge-hosted. The modded name goes to `-PplayerA` and
joins `127.0.0.1:25566`; the vanilla name goes to `-PplayerB` and joins `127.0.0.1:25567`.
`devAssign` prints which name belongs on which port.

**Expected:** the modded client transfers to the limbo as in section 2. The vanilla client stays on
its edge and never notices anything unusual. Both fight, and both agree on every death and on the
final score.

**Failure looks like:** both edges logging `waiting for <name> to join THIS edge` forever, meaning
the two players are on each other's ports or a name is misspelt, or `ARENA_MISMATCH ... frame 0
state`, meaning the mod jar and the edge jar decoded one setup blob into two different opening
states. That is a stale artifact: compare the three dev-stack fence numbers (mod jar, edge plugin,
relay).

Only re-test the rows marked **X** below. Everything else was proven in 1 and 2, and the cross-play
path does not change it.

One known asymmetry, and not a bug: the modded player's aim is decided on their own machine, the
vanilla player's by the server. Cross-play stays on unranked queues in production for that reason.

---

## Mechanics checklist

Run in `CRYSTAL`, except the splash-potion block at the end, which names its own game type and its own
command. **E** means do it in section 1, **M** in section 2, **X** means repeat it in
section 3. Most rows are one line and one configuration.

Before you start: a recent pass rewrote several combat numbers to match the decompiled vanilla client,
and **nobody has felt any of them**. Sprint knockback, when a hit counts as a crit, arrow damage
falling off with distance, the mace rebound, wind burst, Breach armour maths, splash instant damage
and the attack-charge reset on a weapon switch all changed. **The rows below now test every one of
them**, including wind burst and Breach, which had no demo kit item until 2026-08-26 and now do.
Treat "this feels wrong" on any of them as a finding worth writing down even if nothing looks broken,
because a number being closer to vanilla on paper is not the same as it feeling right.

**Every row below is runnable exactly as written**, with one flagged exception: the tipped-arrow row
needs you to move a stack first, and says so where it sits. Rows that need a game type other than
CRYSTAL are in their own section with the command that starts them, and the handful of mechanics no
demo kit can reach - because the sim does not model them at all, not because the kit is short of an
item - are listed under *Deferred* after the checklist. That list is not work for this session. Read
it once so you do not go hunting the hotbar for an item that is not in it.

### The CRYSTAL kit, slot by slot

Several rows below name an item by where it sits, so here is the whole kit rather than a description
of it. Hotbar positions are **the number key you press**, 1 to 9. Storage rows are the three rows in
the inventory screen, counted left to right.

| hotbar key | item |
| --- | --- |
| 1 | netherite sword |
| 2 | 64 end crystals |
| 3 | 64 obsidian |
| 4 | 64 respawn anchors |
| 5 | 64 glowstone |
| 6 | Efficiency V netherite pickaxe |
| 7 | **elytra** - it is on the bar on purpose, see the elytra rows |
| 8 | 64 golden apples |
| 9 | 64 flight-3 fireworks |
| off-hand | totem of undying |

| storage slot | top row | middle row | bottom row |
| --- | --- | --- | --- |
| 1 | 64 end crystals | 8 ender chests | water bucket |
| 2 | 64 end crystals | shulker box of 27 totems | lava bucket |
| 3 | **netherite axe** | shulker box of 27 totems | 64 cobwebs |
| 4 | 64 obsidian | 64 arrows | totem |
| 5 | **64 wind charges** | 64 arrows of weakness | totem |
| 6 | **loaded Multishot crossbow** | 16 ender pearls | totem |
| 7 | 64 flight-1 fireworks | bow | **mace, Wind Burst III + Breach IV** |
| 8 | 64 flight-2 fireworks | **plain mace** | **16 enchanted golden apples** |
| 9 | loaded plain crossbow | shield | 64 golden apples |

Armour is worn: full plain netherite, which is 20 armour points, 12 toughness and 0.4 knockback
resistance, with no protection enchantment on any piece. Several damage rows below are quoted against
exactly that. The 0.4 matters for the knockback rows and is worth knowing before you judge one:
`Combat.knockback` scales every MELEE knockback by `1 - kbResistance`, so a sprint hit moves this kit
40% less than it would move an unarmoured player. Explosions and wind charges do **not** read it -
they read Blast Protection, which no piece of this kit has - so a crystal or a wind charge throws you
the full distance. If melee knockback feels short and blast knockback does not, that is the kit, not
a bug.

**Read this before the melee rows.** CHECKSUM_REV 165 is the first revision that ever REFUSES a melee
claim on the strength of where you were looking. It refuses one thing and one thing only: a hit whose
victim is more than 120 degrees off the direction your own input frame says you were facing, and only
while your hitboxes are not overlapping. Nothing a hand can do inside one 50 ms tick reaches 120
degrees - it needs 2400 deg/s, five full spins a second - so if you ever land a hit that does not
register while you were turning, that IS a finding and it is worth writing down with what you were
doing. Spin-clicking, flick-hitting, jump crits and hits taken while pressed into a corner are all
supposed to be unaffected, and two of those (the flick and the corner scramble) are exactly the cases
that would show a threshold set too tight. The counterpart to watch on the other side: swinging at
someone who is genuinely behind you should now do nothing at all, where before this revision it hit
them.

**Melee, crits, cooldown** (E, X)
- Spam-click against timed hits: timed hits must do visibly more damage.
- Jump and hit on the way down: crit particles, more damage. Hitting at the top of the jump must
  NOT crit.
- Sprint into a hit: extra knockback, and the sprint drops.
- Switch weapon mid-fight and swing instantly: that swing must be weak, not full power.
- **X:** both players confirm the same damage from the same hits.

**Shields** (E, X)
- Block from the front: no damage. Get hit from behind while blocking: full damage.
- Hit a raised shield with the **netherite axe** (top storage row, third slot along). The
  shield must be disabled for five seconds - `Combat.SHIELD_DISABLE_TICKS` is 100 - and every hit
  during those five seconds must land in full however hard the victim holds right click.
- **X:** both players agree on which hits the shield stopped, and the axe disable must last the same
  five seconds for the modded and the unmodded player. This is a host-divergence question and those
  have been the most productive bug class in this project, so do it deliberately rather than in
  passing.

**Crystals and anchors** (E, X)
- Place a crystal on obsidian and hit it: both players take damage and are thrown.
- Place, charge twice, detonate a respawn anchor.
- Blow yourself up standing next to one.
- **X:** the knockback both players receive must match. This is the most likely place for a
  cross-play disagreement to be visible.

**Wind charges and wind burst** (E)

Neither of these had ever been reachable before 2026-08-26 and the numbers behind both were rewritten
in the vanilla parity pass, so nobody has felt either. A wind charge is a thrown item; Wind Burst is
an enchantment on the mace and fires when the smash lands.

- The 64 wind charges are in the top storage row, fifth slot along. Throw one at your own feet: you
  are launched, and you take **no damage at all** from it - `Combat.windBurst` applies knockback and
  nothing else, so a wind charge that hurts anybody is a finding.
- Throw one at the opponent's feet: they are launched, and the two screens must agree how far.
- Throw one into a wall next to you at point-blank range.
- Gliding changes the numbers on purpose: the sim uses a different burst radius and knockback for a
  gliding target. Deploy the elytra and have the other player wind-charge you, if you get that far.
- Smash down onto the opponent with the **Wind Burst III mace** (bottom storage row, seventh slot
  along). The plain mace is the middle storage row, eighth slot - one row up and one slot right, not
  directly above, which is worth knowing before you go looking for it. The smash must launch both of
  you, which the plain mace does not do.
- The same mace carries **Breach IV**, and that is a much bigger difference than the worked example an
  older draft of this page quoted. That example - 20 armour, 0 toughness, a 10-damage hit, vanilla
  7.0 against the old sim's 6.4 - is `HANDOVER.md`'s **Breach II** case and does not describe this
  kit. What this kit actually does: full plain netherite is 20 armour and **12** toughness, so a raw
  10-damage hit lands as about **2.8** through plain armour and about **8.8** through Breach IV, which
  is close to triple. Breach applies to every hit with that mace, not only to the smash. Hit an
  armoured opponent with each mace in turn and say whether the gap reads as that large.

**Pearls** (E)
- Pearl across the arena: 5 damage on arrival BEFORE protection and feather falling (a full-netherite
  kit will see less), 20-tick cooldown, no fall damage on landing.
- Pearl into a wall or through a floor: you must not end up inside a block.

**Bow, crossbow, tipped arrows** (E, M)
- Full draw against a tap: full draw hurts much more and shows crit particles.
- Shoot from across the arena: a long shot must do less damage than a point-blank one.
- The plain crossbow is the **top storage row, ninth slot along**, and it starts LOADED, so the first
  right-click fires it. Fire it, then charge it again from the loose arrows and fire the reload.
- The kit carries a **second crossbow with Multishot** (top storage row, sixth slot along), also
  starting loaded. One shot from it must put THREE arrows in the air, and all three must exist on
  both screens.
- **The tipped-arrow row needs one setup step, and without it the row is untestable.**
  `Loadout.activeArrowSlot` picks the off-hand if it holds arrows, and otherwise the **first** arrow
  stack in slot order. The kit's 64 plain arrows sit at middle-row slot 4 and the arrows of weakness
  at middle-row slot 5, so every bow and every crossbow reload will take a plain arrow until all 64
  are gone. Before this row, **move the arrows of weakness into your off-hand** (the totem there is
  the least valuable thing to displace, and the kit carries five more plus two shulkers of them).
  Then every shot is a weakness arrow. Dropping the plain stack works too, but the off-hand is the
  path the sim actually prefers and it exercises the swap at the same time.
  With that done: hit the opponent and confirm their next melee hit on you does visibly less damage.
  If a weakness arrow ever fires while a plain stack is still in the inventory and the off-hand is
  empty, *that* is a finding - it means the two hosts could pick different arrows.
- **M:** watch an arrow in flight while a rollback happens. It may re-path; it must not duplicate.

**Mace** (E)
- You need height, and section 1's generated arena has none: the wall is only 8 blocks. Pillar up with
  the kit's obsidian first, or run this row in section 2 on `colosseum`.
- Fall onto the opponent from height: large damage, you stop falling, no fall damage on you.
- Mace from the ground: normal weapon damage only.

**Elytra and fireworks** (M)
- Deploy in the air, glide, dive, pull up. The elytra must be on the HOTBAR: right-clicking one sitting
  in storage does nothing and is not a bug, which is why the CRYSTAL kit puts it in a hotbar slot.
- Hold the right button down while deploying. One hold is one equip, not a swap that flickers.
- Boost with a firework.
- Land without dying.
- Mod-hosted, because this is where prediction is hardest and where an artifact shows first.

**Golden apples** (E, M)
- Eat a golden apple: absorption hearts appear. The CRYSTAL kit carries 128 of them, 64 on **hotbar
  key 8** and 64 more in the bottom storage row, ninth slot.
- Eat an **enchanted golden apple** (16 of them, bottom storage row, eighth slot along). It must give more
  absorption than a plain one, longer regeneration, and resistance and fire resistance on top - four
  effects against the plain apple's two.
- **M:** eating must survive a rollback. If one apple is consumed twice, that is a bug.

**Inventory, shulkers, ender chests** (E, M)
- Move armour on and off mid-fight, swap hands, drop a stack, pick it back up.
- Place a shulker, open it, take five items, close it, break it.
- Place an ender chest and open it. **It is empty, and that is correct** - nothing seeds an ender
  chest on this build, so put something in, close it, break the chest, place a second one somewhere
  else and open that: what you stashed must still be there, because both chests are the same
  container. Your opponent's ender chest is a different one and must not show your items.
- An ender chest is **not** carried across a round. `Simulation.resetForNextRound` reseeds only the
  containers that came in the match setup, and a player's ender container is created the first time
  they open one, so whatever you stashed is gone next round. Deterministic and the same on both
  screens; it is not vanilla, and it is worth writing down if it reads as a bug in play.
- **M:** both players open the same shulker in one round and check the contents agree.

**Blocks, buckets, cobwebs** (E)
- Place and break obsidian mid-fight.
- Water bucket, then pick it back up. Lava bucket.
- Walk into a cobweb: you crawl, and you stop the instant you stop pressing forward.
- Mine a block while the opponent is hitting you.

**Miss penalty** (E)

This is the one mechanic in the tree that is new behaviour rather than a repair, it changes combat
feel for the modded and the unmodded player alike, and nobody has felt it. It is also a deliberate
departure from vanilla in one respect: vanilla clears the penalty when the attack BUTTON comes up, and
this sim cannot see a button, only a bit a client chooses to set, so the clear was removed rather than
left as something a tampered client could withhold. Write down how it feels even if nothing looks
broken.

- Swing at nothing. For the next 10 ticks both a new swing and a block break must be refused.
- Whiff, then **let go of left click**. Nothing happens: releasing does NOT clear the penalty and
  never did on this build. Ten ticks is ten ticks whatever the button does.
- Whiff, then **keep holding** left click. It must not re-swing on its own, and it must not mine.
  Holding does not pay the penalty off any faster either; the ten ticks run out on their own.
- Whiff, release, press again inside the ten ticks. That press must buy nothing - no swing, no dig.
- Whiff, then immediately hold a dig on a mineable block. The dig must not start for ten ticks. If
  that reads as input lag rather than as vanilla, say so: it is the likeliest thing in this list to
  be wrong.

**Round boundaries** (E, M, X)

This is the block `-Prounds=3` exists for. `Simulation.resetForNextRound` restores both players from
`roundInitial` and then clears the whole world: projectiles, placed blocks, crystals, anchors, dropped
items, broken arena cells and their damage, fluids, cobwebs, fires, and every binding from a block to
a container. The containers themselves are cleared too and then re-seeded from
`roundInitialContainers`, which means a shulker that arrived in the match setup comes back holding
what it started with, and a container that did not arrive in the match setup - an ender chest, which
is created the first time somebody opens one - does not come back at all.

- Die, and watch the reset. Both players go back to full health, food and spawn, the cage returns,
  and the countdown runs. Both screens must show the same thing at the same time.
- **Keep your hands off the mouse for that first one.** Attacking during the countdown sets your ready
  flag, and once both players are ready the sim cuts the countdown to its last tick. A pair of
  spam-clickers will never see the countdown they were told to watch, and will report it missing. Do
  the row once passively, then do it again with both of you clicking and confirm the skip is instant
  and identical on both screens - that is a row in its own right, because readiness is replicated
  state.
- Before dying, place blocks, drop items, leave a crystal standing and set something on fire. After
  the reset, none of it is there on either screen.
- Break part of the arena, then die. The broken terrain must come back.
- Take five items out of a shulker, then die. In the next round the shulker holds what it started
  with, not what you left it holding.
- Have an arrow in flight at the moment somebody dies. It must not survive into the next round.

**Splash potions** (one extra brokered match, no mod on either client)

The only demo kits with a potion in them are POT and NETHERITE_POT. Both hold the same potions; the
difference is a diamond sword and diamond armour against a netherite sword and netherite armour. The
layout, so you are not hunting: **hotbar key 1** is the sword, **key 2** a single golden apple, and
**keys 3 to 9 plus the top and middle storage rows** are 25 splash STRONG_HEALING. The bottom storage
row is the new part - slots 1 to 3 are splash **harming**, 4 and 5 **speed**, 6 and 7 **strength**,
8 and 9 **fire resistance**. This is one extra short match, not a whole configuration. **The game type is a flag on `devAssign` only** - `devRun` and `devSetup` take no
`-PgameType`, and the edge config they write carries no `game-type` key, which is why section 1 is
always CRYSTAL. So run this on the section 2 stack, which is already up, with no `-Pmod` at all, so
both slots stay on their edges and no client needs the mod:

```
gradlew devAssign -PplayerA=<name1> -PplayerB=<name2> -PgameType=POT -Prounds=3 -PrelaySlotSecret=devsecret
```

`devAssign` will print a disabled-mechanics banner headed `POT RUNS WITH EXPLOSIONS AND BUCKETS AND
BUILDING OFF`, naming **three** blocks: EXPLOSIONS (END_CRYSTAL, RESPAWN_ANCHOR, GLOWSTONE), BUCKETS
(WATER_BUCKET, LAVA_BUCKET) and BUILDING (OBSIDIAN, COBWEB, COBBLESTONE, GLOWSTONE). Ignore all of
it: `devAssign` computes that block without knowing the kit, so it names every material the rule set
would disable rather than the ones you were handed. The banner each EDGE prints at match start is
computed from the kit's own materials, and for POT it should name nothing at all, because the POT kit
contains none of those eight.

- Splash a healing potion at your own feet: you heal, and the closer to the centre of the cloud the
  more you heal.
- Splash one between both players: both heal, and both screens agree how much.
- Splash one and take a hit on the same tick.
- Splash a **harming** potion at the opponent: they take damage, and it goes through armour and
  through i-frames the way a hit does rather than around them. Splash a second one at them one tick
  after a sword hit and confirm the i-frames swallow it - that path was rewritten in the vanilla
  parity pass and has never been felt.
- Splash a **speed** potion on yourself and confirm you actually move faster on both screens, not
  just on yours.
- Splash **strength** on yourself and hit the opponent: the same weapon must do visibly more.
- Splash **fire resistance** on yourself. There is no fire source in a POT match, so all this row
  proves here is that the effect arrives and both screens show it. The real check is the lava bucket,
  and it belongs to the CRYSTAL match: set yourself alight there, then note that you cannot repeat
  this potion in that game type because the CRYSTAL kit has no potions.

**Round boundaries, again in POT** is not needed - it is proven in CRYSTAL and the round reset does
not read the kit.

Finish each configuration with one full match played normally, with no scripted testing. That is what
catches the things a checklist does not.

---

## Deferred: what the demo kits still cannot reach

**This list used to have six rows and now has none of them.** Every one - the axe shield disable,
the wind charge, crossbow Multishot, splash harming, the enchanted golden apple and the speed,
strength and fire resistance potions - was already modelled by the sim and already encoded by
`EdgeKitTables`; the only thing missing was an item in a demo kit. On 2026-08-26 they were added
to `EdgeDemoKits` and every one of them is now a row in the checklist above. What they cost, so
nobody is surprised by a hotbar that does not match an older document:

| slot | was | is |
| --- | --- | --- |
| CRYSTAL top storage row, 3rd | a fourth stack of end crystals (256 total) | a netherite axe (crystals now 192) |
| CRYSTAL top storage row, 5th | a third stack of obsidian (192 total) | 64 wind charges (obsidian now 128) |
| CRYSTAL top storage row, 6th | a second stack of glowstone (128 total) | a loaded Multishot crossbow (glowstone now 64) |
| CRYSTAL bottom storage row, 7th | the fourth of five loose totems | a mace with Wind Burst III and Breach IV |
| CRYSTAL bottom storage row, 8th | the fifth of five loose totems | 16 enchanted golden apples |
| POT and NETHERITE_POT bottom storage row | empty | 3 splash harming, 2 speed, 2 strength, 2 fire resistance |

Three loose totems, one in the off-hand and two shulkers of 27 remain, which is still more totems
than a match uses.

What is genuinely still out of reach, and why:

| mechanic | why |
| --- | --- |
| thorns | not modelled at all. HANDOVER section 1B: no `thorns` token anywhere in sim-core. An item in a kit would do nothing |
| crossbow piercing | `ItemDict.piercing` is replicated over the wire and read by nothing, so a Piercing crossbow would shoot exactly like a plain one. Deliberately NOT put in the kit for that reason - it would look like a bug |
| ladders, scaffolding and climbing | not modelled. Both hosts treat a ladder as decor and agree, so you fall where you expect to climb. Of the nine shipped arenas only `courtyard` contains anything climbable (cave vines), which is why sections 2 and 3 name `colosseum` instead. Section 1's generated arena has nothing climbable either. If you do try `courtyard`, expect to slide down the vines on both screens equally - a fidelity gap, never a desync |
| lingering potions | no `AreaEffectCloud` model, and no shipped kit carries one |
| haste and mining fatigue | no entry in `Effects.java` |
| a real per-player kit from `mcleagues-core` | `devAssign` has no server to serialize an inventory with. This is phase 5, not a missing mechanic - the demo kit now reaches every mechanic the sim implements |

---

## What a desync looks like

A desync means the two peers computed different game states from the same inputs. It is the failure
that matters, and the logs are the only evidence, so capture before restarting anything.

**How it presents:** the match ends abruptly, mid-round, with no winner. Both clients are returned
to the lobby. Neither player did anything unusual immediately before. It is not a lag spike and it
is not a disconnect message.

**Where it is stated plainly:**

- the gradle console running `devRun` or `devRunBrokered`, in the `[edge-a]` or `[edge-b]` stream:
  `################ DESYNC ABORT ALERT ################`
- the same banner in `build/devenv/edge-a/logs/latest.log` and `build/devenv/edge-b/logs/latest.log`
- the frame number, as `checksum mismatch at frame N`

**Capture all of this before restarting:**

1. The whole gradle console scrollback. All four tags (`[relay]`, `[limbo]`, `[edge-a]`, `[edge-b]`)
   are interleaved there and nowhere else. Copy it to a file.
2. `build/devenv/edge-a/logs/latest.log` and `build/devenv/edge-b/logs/latest.log`.
3. The modded client's `logs/latest.log`, for sections 2 and 3.
4. The **frame number** off the mismatch line.
5. The fence triple from every artifact involved:
   ```
   curl -s http://127.0.0.1:7797/metrics | grep rollback_edge_build_info    # edge-a
   curl -s http://127.0.0.1:7798/metrics | grep rollback_edge_build_info    # edge-b
   curl -s http://127.0.0.1:7779/metrics | grep rollback_relay_build_info
   ```
   plus `gradlew verifyModJars` in the mod checkout.
6. One sentence each on what both players were doing in the second before it happened, plus the
   `gameType` and the arena name.

**Triage in that order.** If the four fence numbers are not identical you have a stale artifact and
nothing else needs investigating. If they are identical, it is a real sim divergence, which is the
finding, and item 6 is what makes it reproducible.

---

## The four things most likely to waste your time

**1. A stale artifact, and on this checkout it is not hypothetical.** The mod jar and the edge plugin
must come out of the same tree. The jars in `versions/<mc>/build/libs/` were built before this tree's
last two source changes; *Before anything* says which, and the three `:build` lines are the fix.
Rebuild both and confirm one number across the **three** dev-stack artifacts before you touch a
client - mod jar, edge plugin, relay. In production there is a fourth, `mcleagues-core`, and it is
checked by `gradlew verifyRollbackFence` there rather than by anything in the dev stack.

**The trap in the ordering is that section 1 will not catch it.** The edge only compares fences when
it hands a slot over to a mod, so an edge-versus-edge match runs perfectly happily with a stale mod
jar installed on both clients. You can finish the whole of section 1 and the whole mechanics
checklist, then hit the wall on the first `-Pmod` assignment an hour later. Build the jars first.

**The second trap is that the fence only catches a jar from a DIFFERENT revision.** A jar built from
this tree before its last commit carries today's triple and is admitted. Right now that is the shape
the tree is actually in, so do not treat a passing `verifyModJars` as a reason to skip the build.

Beyond that refusal a skew presents as an abort at frame 0, an `ARENA_MISMATCH`, or a desync a few
seconds in, and never as a message saying the jar is old.

There is a second stale-artifact shape that carries the right version number and is still wrong: an
unremapped `devlibs` jar. It has a `fabric.mod.json`, so a client will happily install it, and it
nests no sim-core, so it dies on the first mapped name. Each version build now deletes its own as soon
as the remap that reads it has run, so a normal build leaves none and `verifyModJars` passes; it fails
on one if an interrupted build left it behind, and `gradlew clearDevlibs` removes it. **Install only
from `versions/<mc>/build/libs/`.**

**2. The join choreography.** An assignment expires **120 seconds** after `devAssign` prints it, the
player names are matched exactly as typed, and each name must be on the port `devAssign` names. Have
both clients logged in and standing on their servers first, then push the assignment. If both edges
log `waiting for <name> to join THIS edge`, the names are swapped or misspelt; re-push rather than
wait.

**3. The stack not being where you think it is.** Ctrl-C does not stop `devRun`. Gradle traps it and
the servers keep holding their ports, so the next `devRun` fights the old one. Always
`gradlew devStop`, then `gradlew devStackDown`. And the dev edges are Paper 1.21.11: a 26.x client
with a 26.x mod jar cannot join them at all, however correct that jar is.

**4. A build that fails in a way the source does not explain.** This is the single biggest time sink
in this repository and it is not always your fault. The same command on this tree has produced 201
failures, then a compile-ordering violation, then 170 failures, then a clean run. It was blamed on
extra Gradle daemons, and that attribution is **wrong**: on 2026-08-26 three sequential
`gradlew clean build --no-build-cache` runs, after `gradlew --stop`, with one daemon and nothing else
running, produced one green run and two red ones - and one of the reds was
`package ControlProtocol does not exist` in `relay/src/test`, in a module that declares the dependency
and had already compiled its main sources against it in the same build. Re-running fixed it. The rule
is therefore: **`gradlew --stop` first, and if a build fails naming a class or a package you can see in
the source, re-run before you read any code.** A second failure in the same place is a real fault; a
first one very often is not.

## Simulating a laggy player (dev only)

Add `-PlagA=<ms>` and/or `-PlagB=<ms>` to `devAssign` to hold that slot's packets in BOTH directions
on its edge. The number is ONE WAY, so `-PlagA=80` is +160ms round trip on top of the real link.
Range 0-2000, clamped.

```
gradlew devAssign -PplayerA=<name1> -PplayerB=<name2> -Pmod=none -ParenaName=colosseum -PlagA=80 -PrelaySlotSecret=devsecret
```

In game, `/ping` reports what is being injected for you and how far ahead the sim is predicting.
It needs no op: the dev servers op nobody (`ops.json` is `[]`), so a permission-gated command would
silently do nothing, which is exactly what happened the first time. The `edge.ping` node defaults to
everyone and can be revoked on a production edge.

```
simulated lag: +80ms one way, so +160ms round trip on top of your real latency.
head=1240 confirmed=1236 (4 frames unconfirmed, about 200ms of prediction). rollbacks=311 ...
```

Watch the unconfirmed-frame count grow as you raise the lag - that IS the rollback window, and it is
the number that decides how much the opponent rubber-bands for you. `EDGE_SIMULATED_LAG_MS` (or
`dev.simulated-lag-ms` in config.yml) applies the same delay to every `/edge` match on that server.

**This only lags an EDGE-hosted (unmodded) slot.** A modded client hosts its own sim and talks to the
relay directly, so it does not read the assignment's lag field. To test a laggy modded player, set
`EDGE_SIMULATED_LAG_MS` on the edge its OPPONENT is on - the added path delay is symmetric, so both
sides feel the same rollback window either way.
