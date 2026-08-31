# Handover

## The one page

Everything an owner needs is on this page. Everything after it is the evidence and the detail, and
section 0 is where the numbers are re-measured rather than remembered.

**Measured on this machine on 2026-08-26, and re-checked against the tree at the end of that day.**
The re-check mattered: the version-fence row below had already gone stale within the session, claiming
all four artifacts agreed when the mod jars did not. Treat any number here as a claim with a command
next to it, and run the command.

| what | value | the command that produced it |
| --- | --- | --- |
| tests | **0 failures across five gates.** Read the count itself off the build's own verdict block, never off this page - it moved three times on 2026-08-26 alone | `gradlew --stop` then `gradlew clean build --no-build-cache` |
| determinism gate | **current.** `reference-digest.txt` is recorded at `checksum-rev=165` with `final-checksum=98b78a5d0629ac15`, `stream-digest=218bc25a4228a544`, `rollback-digest=9b7a69a1f8f87dba`, `arena-hash=fe21a2f81e31bc99`. Nothing to re-record | `gradlew :sim-core:checkHarnessDigest` |
| version fence | **all four agree.** Source, `mcleagues-core`, `RUNBOOK.md` and the three built mod jars all read **67 / 171 / 17336** - the jars re-read out of their nested `sim-core`, not inferred | `Protocol.java`, `verifyEmbeddedSimVersion`, `verifyRollbackFence`, `verifyModJars` |
| mod jars vs source | **current.** All three targets and their three jar-in-jar staging copies were rebuilt from this tree after `RollbackController.RESIM_FRAMES_PER_ADVANCE` moved 64 -> 24 and after the `Containers.decodePeerStack` window landed, and re-verified at 67 / 171 / 17336. Rebuild again after any further sim change | `gradlew 1.21.11:build 26.1.2:build 26.2:build`, then `gradlew verifyModJars` |
| played by a human | **never. Not one match, not one round, not once.** | - |

That last row governs every other row. Every verdict in this document rests on tests and on reading
the source. That is the strongest evidence available without a person at a keyboard, and it is not the
same kind of thing as evidence.

**One warning about that first row before you use it.** The build on this machine is flaky in a way
that produces false REDS, never false greens. Measuring it for this refresh took several runs: two
distinct defects in the build's own verdict mechanism were found and fixed (section 6A), and one run
failed with a phantom `package ... does not exist` in a module that declares the dependency and had
already compiled against it (section 0, the phantom-failure family). If your first run is red, re-run
it once before you read any code, and never write a build number down from a single run.

**1. Are all vanilla PvP mechanics implemented?** No, and the gap is small but real. Five are missing
outright: thorns (no `thorns` token anywhere in sim-core), crossbow piercing (`ItemDict.piercing` is
replicated over the wire and read by nothing), ladders and climbing (no climbable concept at all),
lingering potions (no `AreaEffectCloud`), and haste / mining fatigue (no entry in `Effects`). Four more
are deliberately deferred with reasons: `EXPLOSION_EXTRA_LIFT`, arrow tick order, shield durability wear,
and attack exhaustion on a refused swing. In a duel: piercing matters the moment anyone queues a
crossbow kit and is the cheapest fix on the list; thorns matters in any full-armour SMP-style kit;
climbing costs fidelity rather than fairness, because both hosts read the same shipped palette and
classify a ladder as decor, so you FALL where you expected to climb and you fall on both screens
equally. Lingering and haste are unreachable until a kit ships one. Sweep attack is not modelled and
correctly so: in a strict 1v1 vanilla's sweep set is always empty. Section 1B has the table.

**2. Is the system secure against modified clients?** Substantially, and it is now three-witness on the
modded path rather than two. The core argument holds: both peers simulate the same 67-byte frames and
abort on a checksum mismatch, so a field an attacker sets is applied identically on both sides. Five
out-of-band holes that argument did not cover were closed at revisions 160 through 164, including a
single forged `Message.Finish` that won any ranked duel outright. The relay referee is now wired end to
end - core sends `AUTHORIZE_SETUP` at handoff, the relay re-simulates the teed input streams, and a
signed verdict outranks either client. The dev stack now brings it up too: `devRun` starts the relay
with `RELAY_CONTROL_SECRET`, `devAssign` authorizes every session it pushes, and the relay prints its
signed verdict in the relay window. **The residual, honestly:** nothing in the dev stack *consumes*
that verdict, because the consumer is `mcleagues-core` and core is not in the dev stack. GUI inventory
ops on a moving
frame (the auto-totem shape) are still unrefused and are the largest open item. Aim is measured, not
gated, and deliberately so. Cross-play **is** now watched: `finishCrossPlayHandoff` authorizes the
session and `EdgeSessionBroker` runs the same `RefereeArbiter` the mod path runs. What is still
unwatched is edge-vs-edge over a relay (`completeEdgeHandoff` never authorizes) and direct
edge-to-edge links, which cannot have a third witness by construction. The relay path itself
authenticates a handshake rather than a channel, which is its own open row. And a client can still
turn a loss into a no-contest by forging a desync on any path the referee does not watch. Section 1B
has all of it, row by row.

**3. Is cross-play ready to test, and can mixed queues ship?** Ready to test: **yes.** All five
quantified fairness channels are at zero, each held there by a gate that fails the build; both hosts
build their frames through one arbitration contract with 21 arbitrated channels; and two tests drive a
full mixed CRYSTAL match, one over a lossy loopback at first-to-9 and one over a real `RelayServer` on
real UDP. Ship mixed queues: **not yet.** No human has played a mixed match; two host asymmetries are
unmeasured rather than measured-small (the empty-main-hand use bit and the melee half of the left-click
tie-break); and the latency asymmetry is not a bug that will be fixed - one side is playing rollback and
the other is playing client-server. When it opens, open it on unranked only. Section 5.

**4. Is `TESTPLAN.md` runnable exactly as written by a human with two clients?** Yes, after a
correctness pass on 2026-08-26 that found five things a tester would have hit. Every gradle task it
names exists and every flag it passes is read - that part was already true and was re-audited. What
was not true: it told the reader to re-record the harness digest first and to expect a red build,
which stopped being right the moment the reference was re-recorded; it gave the Breach worked example
at 20 armour and **0** toughness, which is this document's Breach II case and not what the CRYSTAL kit
produces (full netherite is 20 / 12, and Breach IV there is roughly 2.8 damage against 8.8, not 6.4
against 7.0); its tipped-arrow row was unreachable, because `Loadout.activeArrowSlot` takes the
off-hand or else the first arrow stack in slot order and the kit's plain arrows sit ahead of the
weakness ones, so the row now tells the tester to move them into the off-hand first; it promised a
referee verdict on every relayed duel, when in fact section 1's `/edge` matches are never authorized
and therefore never refereed at all; and it identified kit items by slot index in a way that did not
survive contact with an inventory screen, so the CRYSTAL kit is now printed slot by slot.

Three things it still cannot do, and now says so in place rather than in a footnote: nothing in the
dev stack *consumes* a referee verdict, because the consumer is `mcleagues-core`; the mod's end-of-
match RATING panel reads `Stats syncing…` forever for the same reason; and `devAssign` cannot carry a
real per-player kit. That last one is no longer a mechanics gap - the demo kits were extended to reach
every mechanic the sim models, so the *Deferred* list is now only mechanics the sim does not implement
at all.

**5. The single most important thing still not done.** **Phase 4: a human playing a match.** Not more
code. The dev stack now comes all the way up to the join prompt, the fence agrees across every
artifact, and the determinism gate replays clean, so the thing standing between this project and its
first played duel is a person with two Minecraft accounts and two hours. Everything else on the open
list - piercing, the auto-totem gate, the arm64 axis - is smaller than the risk of shipping a netcode
nobody has felt.

**What to run before you touch anything:**

```
cd jackpot-rollback      && gradlew --stop && gradlew clean build --no-build-cache
cd ../pvphq-rollback-mod && gradlew 1.21.11:build 26.1.2:build 26.2:build
cd ../pvphq-rollback-mod && gradlew verifyModJars
```

The three `:build` lines are the part that is not optional, and the reason is **not** the version
fence any more. The jars in the tree embed 171 / 17336, read out of the nested `sim-core` inside
`versions/1.21.11/build/libs/pvphq-mod-1.21.11.jar` rather than inferred, so `verifyModJars` prints
`VERSION 17336 OK` six times on them before you build a thing. What it cannot see is that they were
built before this tree's last two source changes - the `RESIM_FRAMES_PER_ADVANCE` re-derivation in
`sim-core` and the `Containers.decodePeerStack` window in the mod - neither of which moves the fence,
because neither touches simulated state or the checksum function. Build the jars; do not read a
passing `verifyModJars` as permission to skip it. And note where a genuinely stale jar would bite:
the edge only compares fences when it hands a slot over to a mod, so an edge-versus-edge match runs
fine on one and TESTPLAN's whole section 1 will pass before you find out.

There is **no** `updateHarnessDigest` step. The reference was re-recorded at 165 and matches; if
`checkHarnessDigest` fails on your machine, treat it as a finding and read section 0 rather than
re-recording over it. Read the test count off the build's own verdict block, not off this page. Then
open `TESTPLAN.md`.

**Where things are in this document.** 0 the measured state of the tree - 1 the seven reported symptoms
- 1A the miss penalty, the one piece of new behaviour - 1B the three audits, and the security table -
2 the commands in order - 3 what is reachable per game type - 4 the eight production phases - 5
cross-play fairness in blocks - 6 the four time-wasters - 6A what a green build guarantees - 7 what is
genuinely not done - 8 the arm64 gap.

---

Refreshed at the end of the session of 2026-08-26, after the two hardening passes, the referee pass and
the build-hygiene work that closed it. Read section 0 before you launch anything, and read section 6
before you conclude that something is broken.

Earlier handovers in this project told you a bug was fixed when it was not, and one told you the test
suite was in a state it was not in. Because of that, every number in section 0 came out of a command
run on this machine today, not out of the previous handover, and every verdict below was re-checked
against the code in this checkout. Where a fix covers part of the ground I say which part it does not
cover rather than rounding up.

This document has drifted out of date three times now in the same way, and this refresh found the same
drift again: the last refresh opened by saying the fence was 163 / 17315, while the tree it described
had already moved to 164 / 17316. The numbers on the one page above were therefore all re-run rather
than read out of the prose. A number in a document is a claim; anything below that I could not check
against the code says so in the line itself.

The single most important sentence in this document has not changed: **nothing in this project has ever
been played by a human.** Every verdict in section 1, every zero in section 5's fairness table and
every "closed" in section 1B rests on tests and on source reading. Sections 4 and 5 are blunt about
what that is worth and what it is not.

One finding from an earlier session is worth keeping, because it reframes the three handovers
before it. The dev stack could not start a match at all. `EdgeBuildFence` hashed the jar Paper hands
`getPlugin().getFile()`, which on any modern Paper is the remapped copy under `plugins/.paper-remapped/`
and never equals the jar gradle wrote, so both edges hit `STALE EDGE PLUGIN - REFUSING TO ENABLE` and
disabled themselves. It means phase 4 was blocked by a defect nobody had hit, because nobody had ever run
the stack far enough to hit it. The fix and `EdgeBuildFenceRemapTest` are both in this checkout, and as of this refresh the
live half is no longer inherited: both Paper edges were started headlessly for this document and both
printed `build fence: this plugin is the jar the last gradle build produced` and then `edge ready:`.
The same session found that the deploy side of that trap was still open - `devStampEdgePlugin`
overwrote the jar on every `:edge:jar` without clearing `plugins/.paper-remapped`, and
`devVerifyEdgeDeploy` hashed the jar it had just written rather than what Paper resolves. Both are
closed; section 6, item three has the detail and the reproduction.

## 0. The state of the tree, measured rather than remembered

**READ THIS FIRST: the determinism gate is current, and so is every version fence.** An earlier
version of this section opened by saying the harness reference was a revision behind, and the version
after that said the mod jars were. Neither is true now.
`reference-digest.txt` reads `checksum-rev=165` with `final-checksum=98b78a5d0629ac15`,
`stream-digest=218bc25a4228a544`, `rollback-digest=9b7a69a1f8f87dba` and the unchanged
`arena-hash=fe21a2f81e31bc99`, which is exactly what 165 replays to. **Do not run
`:sim-core:updateHarnessDigest`.** If `checkHarnessDigest` disagrees on your machine, that is a
finding - either a simulated rule moved without the rev moving, or this machine does not agree with
the amd64 run that recorded it, which is the open question in section 8 - and re-recording over it
destroys the evidence.

The three mod jars are no longer outstanding **on the fence**: they embed 171 / 17336, verified by
reading the nested `sim-core` out of the built jar rather than by inference, so all four artifacts
agree. They are still outstanding on **content**. Both changes that landed after they were built -
`RollbackController.RESIM_FRAMES_PER_ADVANCE` 64 -> 24 in `sim-core`, and the per-stack decode window
in the mod's `Containers` - are deliberately fence-neutral, so nothing in the build or in
`verifyModJars` will tell you the jars are missing them. Rebuild them before any match; the two-line
command is at the end of the one page.

**This is the failure mode the fence was never designed to catch, and it is worth stating once in
full.** `Protocol.VERSION` moves only when simulated behaviour or the checksum function moves, which
is exactly right - it exists to stop two peers simulating different rules, not to date a jar. Any
change that is local, receive-side or non-replicated is invisible to it by construction. So the fence
answers "would these two sims diverge", never "is this jar current", and only the second question
matters when you are deciding whether to run a build.

Some numbers below were measured at 164 and have not been re-measured since; each says so where it
matters. The rev moved from 163 to 164 for two simulated rules: the authority stamp is now WALKED in
sub-steps rather than swept in one call, and every granted melee claim now lands on a replicated
per-slot ledger (`meleeClaimsGranted` / `meleeClaimsOffAim`). Only the second moved the digest, because
the scripted scenario never sets `edgeHosted` and so never exercises an authority stamp at all. 165
then moved it again for `ClaimAuthority.aimAhead`, the melee claim aimed more than 120 degrees behind
the attacker. **A re-record whose numbers do not move is not evidence that a sim change was inert** -
it is evidence the scenario does not reach it. Bump the rev on the rule, not on the digest.

The per-gate block below is from a single `gradlew --stop`, then one
`gradlew clean build --no-build-cache` with one Gradle daemon and nothing else running: 1 minute 25
seconds, 33 actionable tasks, all 33 executed, `BUILD SUCCESSFUL`. **The counts in it are a snapshot
and are already out of date** - 165 added five sim-core cases and two relay cases, and the demo-kit
pass added three edge cases, so a current run reports more than these. What has not changed, and what
the row on the one page actually asserts, is that every gate reports EXECUTED with zero failed:

```
1361 tests, 0 failures, 0 errors, 0 skipped    <- as measured at 164. the totals have since moved up
  sim-core 1069   sim-host 125   edge 127   relay 31   limbo 9
```

`--no-build-cache` on a `clean` tree is what makes that count mean something. A `FROM-CACHE` or
`UP-TO-DATE` test task proves nothing, which is the whole of section 6A; the per-gate block below is
the build stating for itself that every task really ran.

**One defect in that gate was found and fixed while measuring it, and it is worth knowing because it
made a green tree read as red.** `:verifyGatesRan` is a finalizer of every `Test` task and every
`check`, but nothing ORDERED it after them, so on a run where Gradle happened to schedule
`:sim-host:check` ahead of `:edge:test` the finalizer fired early, found no stamp for the tests that
had not run yet, and failed the build with `these gates did not execute in this invocation`. It
reproduced on two of three runs of the documented command this session, and both times the tests
themselves were fine - run directly, `:edge:test` and `:limbo:test` were green. The finalizer now also
carries a `mustRunAfter` for every task it finalizes, which puts it last in the plan. If you ever see
that failure again, run the named test task on its own before you believe it.

You no longer have to count out of the JUnit XML. The build prints its own verdict block naming every
gate and whether it really ran, and test results in this repository can no longer be restored from the
build cache at all. Section 6A is the whole of what that does and does not buy you.

```
=== what this build actually verified ===
  :edge:test                             EXECUTED    127 tests, 0 failed, 0 skipped
  :limbo:test                            EXECUTED    9 tests, 0 failed, 0 skipped
  :relay:test                            EXECUTED    31 tests, 0 failed, 0 skipped
  :sim-core:checkHarnessDigest           EXECUTED    harness replayed against reference-digest.txt
  :sim-core:test                         EXECUTED    1069 tests, 0 failed, 0 skipped
  :sim-host:test                         EXECUTED    125 tests, 0 failed, 0 skipped
  :verifyEmbeddedSimVersion              EXECUTED    3 embedded sim-core copies checked
```

The harness, from `gradlew :sim-core:checkHarnessDigest`. **The block below was captured at 164 and is
kept for its shape, not its digits** - the four checksum lines all moved at 165. The committed
reference now reads `checksum-rev=165`, `final-checksum=98b78a5d0629ac15`,
`stream-digest=218bc25a4228a544`, `rollback-digest=9b7a69a1f8f87dba`; the arena hash and every
coverage count are unchanged.

```
=== sim-core determinism harness ===
os=Windows_11 arch=amd64 java=21.0.6 (Oracle Corporation)
ticks=11740 seed=c0ffee checksum-rev=164
intra-JVM converged: true
arena hash    : fe21a2f81e31bc99 (two independently extracted arenas, distinct object graphs)
rollback pass : 3341 rollbacks over 11740 frames, 23661 resimulated frames, deepest 603, 90 batched,
                4245 scripted arrivals
rollback cover: 9/9 rollback shapes exercised
rollback dgst : 7a0a3598cf9a7bf2
scenario cover: 50/50 behaviours exercised
final checksum: 5683242635fc33a9
stream digest : 2e82fca52e040796
reference     : 2e82fca52e040796 (recorded at checksum-rev 164)
OK: digest matches the committed reference
CROSS-MACHINE GATE: this only proves amd64; the gate is green when an x64 and an arm64 run agree.
```

Read what is and is not moving there. The run converges intra-JVM, all 9 rollback shapes and all 50
scenario behaviours are still exercised, and the arena hash `fe21a2f81e31bc99` is unchanged - as it has
been across every revision this project has had. If the arena hash ever moves without somebody
deliberately changing arena extraction, stop and find out why before anything else.

### The version fence: three of the four artifacts agree, and the fourth is stale

`InputCodec.BYTES` is 67 and `Protocol.CHECKSUM_REV` is 170, so `Protocol.VERSION` is ` 170`
= **17336**.

| artifact | how it was read | value |
| --- | --- | --- |
| jackpot-rollback | `Protocol.java` | 67 / 171 / 17336 |
| mcleagues-core | `RollbackModRegistry.EXPECTED_VERSION`, gated by `gradlew verifyRollbackFence` | ` 170\| 170` |
| RUNBOOK.md | `RunbookVersionFenceTest` reads it | 67 / 171 / 17336 |
| **three mod jars + three jar-in-jar staging copies** | the nested `sim-core` unpacked out of each `pvphq-mod-<mc>.jar` and read directly with `javap` | 67 / 171 / 17336 - `verifyModJars` prints `VERSION 17336 OK` six times |

All four agree, and this time that was read rather than asserted: the previous two drafts of this
table each got it wrong in opposite directions, one claiming agreement that did not exist and one
claiming a 164 / 17316 skew that had since been rebuilt away. What the fourth row does **not** say,
because the fence cannot say it, is that the jars contain everything in the source. They do not - see
the content note at the top of section 0 - and the three `:build` lines in `pvphq-rollback-mod` are
still required before any mod-hosted match.

Three more, run for this handover rather than assumed:

- `gradlew verifyGameTypeRules`: `EdgeGameTypes matches all 20 mcleagues-core game types`.
- `gradlew devVerifyEdgeDeploy`: `every provisioned edge would LOAD this build (sha256
  d529457ad9a8c931)`. That hash is the jar this tree currently builds and it moves on any edge change;
  the task's job is to prove the copy Paper RESOLVES equals the built one, not to hold any particular
  value. The wording changed with the task: it now also refuses a second jar under `plugins/` and a
  `.paper-remapped` copy of any other build. Section 6, item three.
- `gradlew devVerifyEdgeBoot`, new this refresh and the strongest of the three: it starts both Paper
  edges for real, fails unless each one's own `EdgeBuildFence` says on the way up that the plugin it
  LOADED is this build, fails unless each reports this tree's `protocolVersion`, and stops them again.
  Run for this handover: `every edge STARTED, loaded sha256 d529457ad9a8c931 and reported
  protocolVersion 17315 (checksumRev 163, inputBytes 67), then stopped again`.
- `mcleagues-core gradlew build`: `BUILD SUCCESSFUL`, and `verifyOnePluginJar` reports
  `exactly one jar a server would load, build/libs/mcleagues.jar`.

The devlibs story closed this session, in three steps across three refreshes. First `verifyModJars`
named a loadable, unremapped `devlibs` jar and then exited 0. Then it was made to FAIL on one, which was
right and immediately produced a second problem: the 1.21.11 target regenerates its devlibs jar on every
build, so the gate could never pass on a correct build and clearing it was a manual step somebody had to
remember. Now each version build deletes its own the moment the remap that reads it has run
(`dropUnremappedIntermediates`, a finalizer of the mod jar task in `pvphq-rollback-mod/build.gradle`),
so `1.21.11:build 26.1.2:build 26.2:build` leaves a tree `verifyModJars` passes with no extra command.

**One thing that fix needs and that is worth knowing before somebody removes it.** Loom declares the
devlibs jar as `remapJar`'s input by PATH, with no producing-task link, so deleting it does not make
Gradle re-run `jar` - the next build fails input validation instead, which is exactly what the
"a version build cannot delete its own" note in the old design was reaching for. The `jar` task
therefore has its up-to-date check disabled, and only on targets that actually remap: 1.21.11 is the
only one, because the two 26.x targets are unobfuscated and their `jar` IS the mod jar. The cost is one
re-zip of already-compiled classes per build.

Verified three ways this session: green on a clean rebuild, green on two further builds with no clean
in between, and still red when a jar is planted in `versions/1.21.11/build/devlibs` by hand.
`gradlew clearDevlibs` remains as the operator backstop for a tree an interrupted build left dirty.
Section 6, item three.

### The phantom-failure family, and a correction to what causes it

An earlier session recorded three consecutive runs of the same command producing 201 failures, then a
compile-ordering violation where Gradle compiled a downstream module against a jar its own task graph
had not produced yet, then 170 failures, and then a clean run. That session blamed other Gradle daemons
alive on the machine, and every document since has repeated it.

**That attribution is wrong, and this refresh has the counter-example.** Three sequential
`gradlew clean build --no-build-cache` runs, after `gradlew --stop`, one daemon, nothing else running:
one green, and two red. One red was the gate-ordering defect described above. The other was
`package ControlProtocol does not exist`, 100 errors, in `relay/src/test` - in a module that declares
the dependency and had compiled its own main sources against the same package earlier in the same
build. Re-running cleared it. So the family is real, it is not caused by extra daemons, and today it
reproduced at roughly one run in three.

I did not find the mechanism, and I am not going to claim one I cannot show. What I can show is the
symptom set - `NoClassDefFoundError` on a class you can plainly see, `package ... does not exist` in a
module that declares the dependency, build outputs vanishing mid-run - and the response:
`gradlew --stop`, re-run once, and only then read code. A failure that survives a second run in the
same place is real. **Do not report a build number from a single run**: this document has been wrong
about the state of the tree three times, and at one red run in three, a single measurement is not
enough to call the tree either way.

## 1. The seven reported symptoms, one verdict each

I re-read all seven against the code and none of the verdicts moved. Every pinning test named below
exists in the tree and passed in today's run. Every one of these still rests on tests. **No human has
played any of these mechanics.**

### 1. The elytra never glided. Root cause removed.

Two faults stacked, and the second is what made it look random. `McInputSource` fed a held LEVEL into a
contract parameter that means an EDGE, and the chest-equip path took `useHeld || rightPress`, so with a
5-tick cooldown it re-armed while the right button was merely held. Because the equip swap puts the
displaced chestplate back into the same hotbar slot, the worn chest slot oscillated between elytra and
chestplate several times a second, and the single jump edge that opens the wings landed inside a
chestplate window on a coin flip.

Both sides are real edges now. `HostFrameContract.elytraDeploy(...)` gets `jumpHeld && !prevJumpHeld`
and reads `hasElytra` and `gliding` from the sim's confirmed state, not from the client's item. The
equip path is `InputFrameRules.chestEquipPress(usePress, use, prevUse)` = `usePress || (use &&
!prevUse)`, an edge no matter which of the two facts a host actually holds.

What you will NOT see, and it is not a bug: right-clicking an elytra sitting in STORAGE does nothing.
`InventoryIntents.chestEquip` returns `NONE` for any slot at or past the hotbar. This is why the CRYSTAL
demo kit puts the elytra on the hotbar. Pinned by `ElytraDeployTest`.

### 2. The hotbar swap cancelled itself. Root cause removed.

Both inventory mirrors folded the whole 41-slot table plus the cursor into one FNV signature and
repainted everything when it moved. It moved constantly for reasons unrelated to the click, and the
absolute repaint came from CONFIRMED state, which predates an in-flight click by a full round trip, so
it drew the pre-click inventory back over the swap the player had just made.

Replaced by `InventoryPaintPlan`, a per-cell plan shared by both hosts. Ownership is released by the
LANDING FRAME (`head + ClaimAuthority.INPUT_DELAY_FRAMES`), not a wall clock, so the old two-second
cliff is gone. Pinned by `HotbarSwapSurvivesUnrelatedChangeTest`, which keeps a working model of the OLD
rule and asserts it fails, and by `EdgeAndModPaintInventoryTheSameWayTest`.

### 3. The cursor item flickered and read as a dupe. Root cause removed.

Three faults. The cursor was not a paintable cell, so a picked-up item appeared a round trip late; it is
cell 41 now. Cursor tosses shrank the carried stack whether or not the sim accepted the intent, so a
refused queue made the item vanish from screen while the sim still held it, and the next repaint put it
back. And the drop key wrote one ledger record per press while a ctrl-drop empties the slot on the first
press, so the surplus records sat in front of every later drop for the rest of the match, handing each
thrown item the previous one's enchantments. Pinned by `EveryCursorTossIsLedgeredTest`.

### 4. Crossbows insta-fired the moment they finished charging. Root cause removed, upstream of the rule.

Four rounds of code-reading missed this because the rule was innocent. The frame it was asked about was
one the player never sent: `NetSession.catchUpFiller` synthesised a `released()` frame once a snap burst
ran past `PREDICTION_DECAY_FRAMES`, which cleared `use`, dropped the held-use latch, and the next
genuinely held frame found a loaded crossbow with no latch and shot it. `catchUpFiller` now produces
only `heldOnly()` and `gestureOnly()` frames, both of which preserve `use`.

The honest caveat: there is no guard at the rule level. The third test in `CrossbowCatchUpReleaseTest`
deliberately documents that a single synthetic release inside a physically unbroken hold still fires the
crossbow, and states that it is the frame source's job not to synthesise one. Today `catchUpFiller` is
the only synthesiser and it is pinned. A new one would reopen this.

### 5a. A detonated anchor kept its picture. Root cause removed on BOTH hosts.

On the mod, three reconcilers wrote the same world cell with no shared notion of who owned it, so a
player could look at a cage wall while the sim still held a charged anchor there, and a right-click read
the WORLD, saw no anchor, and sent no detonate. The previous defence was a six-tick debounce, which hid
the picture without naming the writer and gave up after six ticks. That is very likely why this was
reported fixed and then was not.

It is now `PaintedCells`, a per-cell ledger of which painter owns a cell, plus exactly one function
(`coverAt`) that answers what was underneath. On the edge, `EdgePlugin.startMatch` was calling
`paster.newOverlay(world)` twice, so the second painter's `putIfAbsent` captured the FIRST painter's
paint as "the block underneath". It is now one overlay per match carrying the same ledger.
`NoUnledgeredWorldPainterTest` and `EdgeNoUnledgeredWorldPainterTest` fail the build on every one of
those regressions.

### 5b. An anchor did not break terrain. NOT A BUG. Two correct behaviours reported as one defect.

This is the verdict most likely to be re-reported, so here is the whole of it.

Obsidian surviving an anchor blast is vanilla. Obsidian stops `(1200 + 0.3) * 0.3` = 360.09 on the first
cell a ray meets it in, and an anchor ray never carries more than `5 * 1.3` = 6.5.

The floor not cratering is the FLAT FALLBACK ARENA. `Arena.flat` carries no voxel grid, only an infinite
ground slab, and `Combat.blastResistanceAt` and `Combat.scatterFire` consult placed blocks, never the
slab. On the flat plane a blast breaks nothing, craters nothing, starts no fire, and an efficiency V
pickaxe mines nothing.

`devRun` writes `arena.bin` into both edges by default (it `dependsOn 'devSampleArena'` unless you pass
`-PflatArena`), so this only bites you if you passed `-PflatArena`, deleted `arena.bin`, or pointed a
mod slot at no shipped arena. `devAssign` refuses the last case outright. Pinned by
`AnchorBlastTerrainReachTest`.

### 6. Spawns were 180 degrees out. Root cause removed.

`EdgeMatch.seed` had two hardcoded yaws and they were exactly backwards. Both are now derived from the
spawn PAIR, and the derived value lands within one degree of the yaw the real colosseum arena file
authors for that spawn.

One mechanic that will confuse you: the sim throws the reset yaw away on the same tick, because
`tickPlayer` assigns `p.yaw = in.yaw()`. It does this SYMMETRICALLY, so a wrong spawn facing is never
the sim favouring one player. The facing you see comes from each HOST re-facing its own player from
`roundInitial[slot].yaw`. Pinned by `SpawnFacingTest` and `SpawnPairFacingTest`.

## 1A. The vanilla miss penalty

Still the one mechanic in the tree that is **new behaviour rather than a repair**, and it changes how
combat feels for **both** players, modded and unmodded alike. Nobody has felt it.

Read out of the decompiled 26.2 client (`net/minecraft/client/Minecraft.java`), not out of memory:
`startAttack()` on the MISS arm sets `missTime = 10` and resets the attack-strength ticker; it then
returns false outright while `missTime > 0`, which covers entity attacks AND block breaking, and an end
crystal is an entity, so it covers detonations too. `continueAttack(down)` clears `missTime` on release
and gates the destroy continuation on `missTime <= 0`, so a HELD button cannot mine during the penalty.

`Combat.MISS_PENALTY_TICKS` is 10, backed by a checksummed `PlayerState.missTicks`. A miss is
`Combat.leftClickNamedNothing(in)` = `!meleeHit && !crystalHit && blockAction == BLOCK_NONE`, which
arms only on the MISS arm exactly as vanilla does.

The one place the model has to differ, and why: **the frame carries no physical button.** Vanilla does
two separate things with `missTime`, and only one of them survives that. `Minecraft.tick` decrements it
unconditionally every tick without consulting the button at all, which transcribes directly.
`Minecraft.continueAttack(down)` opens with `if (!down) this.missTime = 0;`, and `down` is the attack
keybind's LEVEL - a real button coming up, which this sim has no server-observable stand-in for. The
only candidate is the client-authored `attack` bit, and that is the attacker's own bit. So
`Combat.tickMissPenalty(p)` is now the unconditional decrement and nothing else: ten ticks is ten ticks,
whatever the button does. Withholding the bit gains nothing and costs nothing, and a synthetic
(predicted) frame and a real one are no longer different on this field, which also removes a rollback
prediction hazard.

**This has been tightened twice, and the second tightening is the one that matters.** The model
originally had a second clear: the FIRST counted attack drain of a sample also zeroed the penalty, on
the argument that a second counted press implies a release before it. That is fine for the second press
and wrong for the first, and the click count is a number the CLIENT puts on the wire, so a client
sending `attack=1` every tick cleared, every tick, the penalty it had just earned. The identical clear
existed on the mining path. Both went at `CHECKSUM_REV` 161. What was left was
`if (!in.synthetic() && !in.attack()) p.missTicks = 0;` - a clear keyed on the attack bit being DOWN,
which is a bit the same tampered client simply never sets. One frame with the bit down repealed the
whole ten ticks, so the penalty cost a cheating client nothing at all. That clear went at
`CHECKSUM_REV` 162, and the penalty now has no client-withholdable escape.

**What this feels like, restated for the model as it now is.** A burst batched into one tick still
collapses correctly: three counted clicks AT the opponent are three swings, and the same three at AIR
are one swing and two refusals, which is what vanilla does with no `continueAttack` between them. An air
clicker who names no target arms the penalty on the first click and stays under it whether or not the
button comes up. And a whiff followed by a HELD dig cannot mine for ten ticks, which is the canonical
vanilla symptom. If a tester says "mining feels sticky after I miss", that is this, working. Pinned by
`MissPenaltyTest`, whose five cases include `theButtonComingUpNoLongerPaysTheWhiffEarly` (rewritten from
`theButtonComingUpIsWhatClearsTheWhiff`, which asserted exactly the exploit) and
`aSecondPressWithTheButtonStillDownIsPunished`.

**Coverage gap, counted again for this handover.** `HarnessCoverageFloorTest.KNOWN_UNCOVERED` holds
**fifteen** entries, counted in the file rather than carried forward:
`melee-claim-refused-inside-min-reach`, `blast-cell-budget-exhausted`, `item-entity-refused`, six
`inv-action-*` arms (`none`, `drop-one`, `drop-stack`, `drop-cursor-one`, `drop-cursor-all`,
`cursor-resolve`), four `use-fired-*` arms (`none`, `food`, `bow`, `shield`) and, added at CHECKSUM_REV
164, `authority-stamp-walked` and `authority-stamp-clipped`. It was fourteen two refreshes ago;
`mining-refused-by-miss-penalty` moved out of the set and into `FLOORS` at 67, because
removing the miss penalty's early clear means the scripted scenario now reaches a held dig under an
unexpired penalty. The two authority arms went in as uncovered rather than getting a floor, because the
scripted scenario never sets `edgeHosted` and so never stamps a position at all - which is the same
blindness section 5 records for the walked sweep itself. Unit tests cover several of the remaining
fifteen; the determinism digest sees none
of them, and the digest is what catches an arm behaving differently on another machine.

Two floors were ADDED at CHECKSUM_REV 163 rather than lowered, and they are the measurements the two
security rows in section 1B decided not to turn into refusals: `melee-claim-granted-off-aim` at 186 and
`inv-op-on-a-moving-frame` at 266. Both were read back out of `HarnessCoverageFloorTest` for this
refresh, as was the fifteen above.

Eighteen floors in that same test have been lowered across the last two passes - ten with the rewind
window, then eight more with the miss-penalty change, which lets fewer attacks through a whiff and so
attempts fewer claims. That is a real narrowing of what the digest sweeps and is explained where it
belongs, in section 5 under the rewind window.

## 1B. The audits, and what each one found

None of these were bug reports from a player. Every one was an audit, and every one found real things,
which is the argument for doing more of them. Four are recorded here: vanilla mechanics parity,
security, cross-play, and the relay referee that closed the largest of the security arguments on the
modded path.

### Vanilla mechanics parity: the "everything is implemented" claim was false

A class-by-class comparison against the decompiled Mojmap 26.2 and Yarn 1.21.11 sources, plus the
vanilla datapack JSONs for damage-type tags and enchantment definitions, found a batch of mechanics
outright missing or numerically wrong and a further batch with no model at all. The previous handover
put a count on each batch; I could not reconstruct either count from anything in the tree, so they are
gone rather than repeated. What IS checkable is the two lists below, and I re-checked every line of
both. The ones fixed, each verified in `Combat.java` or `Simulation.java` in this checkout:

- attack charge no longer survives a main-hand item change, which was a free full-power hit on every
  weapon switch (vanilla `Player.tick`)
- the crit multiplier now wraps the mace smash bonus instead of being applied before it, so a critical
  smash no longer loses half the fall bonus
- crit condition is `fallDistance > 0` rather than `vy < 0`, so it no longer crits at the jump apex
- `Combat.SPRINT_KNOCKBACK` is 0.5, not 0.4, and is gated on full charge; the sprint reset moved inside
  the `knockbackAmount > 0` branch
- `reduceByDefenseBreach` subtracts 0.15 per level from the armour FRACTION instead of scaling the
  armour POINTS. Worked example at A=20, T=0, dmg=10, Breach II: vanilla 7.0, old sim 6.4
- `MACE_SMASH_REBOUND_VY` = 0.01, so a smashing player hangs instead of continuing to fall
- wind burst is a real explosion at the vanilla doubled radius with the `[1.2, 1.75, 2.2]` multiplier
  table, instead of a `vy` hack that pushed nobody else
- explosion knockback resistance uses the `EXPLOSION_KNOCKBACK_RESISTANCE` attribute model rather than
  the pre-1.20.5 EPF formula
- arrow damage is `ceil(impact speed * baseDamage)` with additive Power and a deterministic crit roll,
  instead of being frozen at launch, so a long-range arrow no longer does point-blank damage
- lava and in-fire damage go through armour absorb, and in-fire lands every 10 ticks not 20
- splash instant damage goes through i-frames and protection, instead of bypassing i-frames entirely
- pearl arrival resets `fallDistance`; cobweb zeroes velocity after the move; jump boost folds into
  `jumpPower` before the `max()`; sprint exhaustion is gated on `onGround` with vanilla centimetre
  quantisation

**Still missing, verified by grep in this checkout, not assumed:**

| mechanic | state | what it needs |
| --- | --- | --- |
| thorns | no model at all, no `thorns` token anywhere in sim-core | an ItemDict armour field plus encoder/decoder (protocol surface), a deterministic per-hit RNG, a LoadoutCaps clamp |
| crossbow piercing | `ItemDict.piercing` is replicated over the wire and **read by nothing**; grep for `piercing(` outside `ItemDict.java` returns zero hits | a pierce counter on `ProjectileState` and a bypass in `Combat.blocksProjectile`. Self-contained, good next task |
| ladders and climbing | no climbable concept anywhere: no `climb`, `ladder` or `scaffold` token in any sim-core main source except a palette name list | a `PaletteEntry` kind plus a movement rule. **Corrected:** the previous handover said an arena with a ladder would DESYNC. It would not, and this matters because it changes the priority. `DevPaletteGeometry` classifies `ladder` and every `vine` as `KIND_DECOR`, `FarenaArena` builds every real arena through it, and the mod decodes the very same `ArenaCodec.Snapshot` off the wire rather than reading its own client world - so both hosts agree the block is not there. The symptom is that you fall instead of climbing, on both screens equally. `scaffolding` is in neither the decor list nor the partial-box list and falls through to `KIND_FULL_CUBE`, so it is a solid block to both hosts; whether that is what a player expects is untested |
| lingering potions | no `AreaEffectCloud` model | out of scope while no shipped kit carries one; required the moment one does |
| haste and mining fatigue | no entry in `Effects.java` | two new effect ids, which widens the replicated effect table |

Deliberately deferred with reasons: removing `Combat.EXPLOSION_EXTRA_LIFT` (vanilla-correct, but it
collapsed two high-volume determinism probes because the scripted crystal scenario depends on it, and it
visibly changes crystal PvP feel, so it wants owner sign-off); arrow tick order (`move`, then drag, then
gravity) as a pure trajectory change wanting a mod-side prediction pass first; shield durability wear on
block; attack exhaustion only applying when the hit landed.

Also worth recording so nobody re-opens it: **sweep attack is not modelled, deliberately.** In a strict
1v1 the vanilla sweep set is always empty, because `doSweepAttack` excludes both the attacker and the
target. Only the sound differs. The same reasoning makes mace AoE knockback and looting no-ops.

### Security: the checksum-agreement argument is sound and it protected only the sim

The threat model is a tampered mod-hosted client that controls every byte it puts on the wire. The peer
runs its own sim over the same inputs and aborts on a checksum mismatch, so any field the attacker sets
is applied identically on both sides. That argument is correct, and it genuinely bounds most of the
67-byte input frame. It says nothing at all about the out-of-band messages, and that is where the two
worst holes were. All five below were live; the first two are the two worst.

**Closed in an earlier pass:**

- **A single forged `Message.Finish` won any ranked duel at any moment.** Any Finish set
  `remoteFinished`, the match ended with `aborted() == false`, `localWon()` found no confirmed loser and
  returned false, and the honest client reported `winnerSlot = 1 - activeSlot`, naming the attacker. That
  is exactly the opponent concession `RollbackHandoffManager.resolveResult` requires before honouring a
  self-declared win, with the attacker's fabricated round scores read straight off the forged packet.
  A Finish is now only a result when it names the LOCAL slot, which makes it a concession. A Finish
  naming the peer sets `peerClaimedWin`, which ends nothing; if the result is still unverifiable after
  `PEER_WIN_CLAIM_GRACE_TICKS` (60 ticks) the session aborts as a forfeit by the claimer. Pinned by
  `ForgedFinishTest`.
- **A peer sending only heartbeats froze the honest client's match forever.** Every decodable packet
  reset the liveness timer before the type dispatch, so a peer that sent no input frames kept the
  receiver below the disconnect timeout while its prediction window filled and `ringSafetyStall()` held
  every tick. No abort, no result, no timeout. Liveness became input PROGRESS rather than packet
  arrival. **That first fix keyed progress on `highestRemoteFrame`, which was itself peer-authored, and
  it has since been re-keyed** - see the liveness entry two lists down. Pinned by
  `HeartbeatCannotHoldAMatchOpenTest`.
- A mining frame carrying counted attack clicks gave one block-destroy plus six melee swings and never
  armed the miss penalty; and a frame could claim `crystalHit` or `meleeHit` AND be a `BLOCK_BREAK`.
  `Combat.contractFiltered` now enforces `HostFrameContract.attackClicks` and
  `HostFrameContract.minesThisFrame` on both peers. These two forced the bump to CHECKSUM_REV 160.
- A forged Container DROP blob could pre-seed the ItemStack the victim's own pickup paints, because the
  drop uid was predictable. Now bound to the sender's slot through `Combat.dropUidOwner`.
- `InputFrames.frameAdvantage` was an unchecked signed short that armed the receiver's time-sync stall
  unconditionally, even while the receiver was itself behind. Clamped to `MAX_PEER_FRAME_ADVANTAGE`
  (600), and the stall is now gated on the receiver's own measured advantage.

**Closed at CHECKSUM_REV 161.** The question that pass asked was narrower and better than "is anything
unvalidated": *is any LOCAL resource bound, timeout, retention window or work budget computed from a
number the PEER chose?* That is a question the checksum argument cannot answer, because agreeing on a
number says nothing about what that number COSTS the side reading it. It found four.

- **The peer decided how much CPU the honest client spent.** The per-tick catch-up burst was ceilinged
  at 60 frames, which is local, but nothing bounded the SUSTAINED rate. A peer producing input frames
  faster than real time keeps `knownRemoteFrames` ahead forever, so the victim simulated about 61 frames
  every tick for the whole match. `NetSession` now spends catch-up out of a local token bucket: capacity
  is the free-run ceiling derived from this side's own ring size, and it refills 2 per LOCAL tick. Total
  catch-up over T local ticks is bounded by `freeRunCeiling + 2T` with the peer appearing nowhere in the
  bound, while a genuine local freeze still closes in a tick or two out of the one-shot reservoir.
  Pinned by `CatchUpBurstIsLocallyBudgetedTest`.
- **A slow handoff killed a healthy opponent.** The input-progress liveness timer counted from FRAME
  ZERO while the abort it feeds only goes live once the peer is seen, and a peer's first packet is an
  empty `InputFrames` carrying no input frame. Any handoff that took longer than `peerTimeoutTicks`
  (400) therefore tripped on the opening packet and reported `peerDisconnected`, silently halving the
  800-tick window the handoff is explicitly allowed. The timer's zero point is now first contact.
  Pinned by `LateHandoffIsNotADeadPeerTest`, and `HeartbeatCannotHoldAMatchOpenTest` still passes.
- **One side could spend the other side's share of the item list.** `ItemEntities` had a single
  first-come cap of 256 for both players, so whoever dropped fastest decided whether the opponent's
  tosses, block drops and death drops existed at all. Now scoped per owner the way `Projectiles` already
  was: 112 each plus 32 neutral, summing to exactly the old lid. `ItemEntityState.owner` is copied and
  checksummed, which is the part that moved the revision.
- **The limbo would allocate up to 2 GB on one handshake packet.** `Proto.readString` sized
  `new byte[len]` straight off a VarInt from an unauthenticated client. Now checked against
  `MAX_STRING_BYTES` and the readable bytes before allocating. Pinned by `ProtoStringBoundsTest`.

Two of the section 1B fairness items moved the same revision: the first miss-penalty repeal in section
1A, and the rewind window in section 5.

**Closed at CHECKSUM_REV 162.** Two passes, and the second changed no simulated value at all.
(The tree now stands at 163; the rows that moved it are marked in the still-trusted table below and in
the authority-position note under it.)

The first took the same per-owner argument the `ItemEntities` fix used and applied it to the two
collections that still had a shared first-come lid, plus the last client-withholdable escape from the
miss penalty:

- **`GameState.crystals` was one pool of 128 for both players.** The CRYSTAL kit shipped 256 end
  crystals at the time (192 since the kit was extended on 2026-08-26, which does not change the
  argument),
  so one player could place 128 and the opponent could never place one - in the flagship mode, which is
  made of crystals. `CrystalState` gained an `owner` int, exactly the field `ProjectileState` already
  carries and for the same reason, and `Combat.crystalRoomFor` gates on the total AND on the placer's
  half (`MAX_CRYSTALS_PER_OWNER` = 64). The owner is checksummed, so a disagreement about it is a desync
  rather than a silent difference. Nothing crosses the frame-0 wire: `GameStateFrame0Codec` refuses to
  encode a frame with a non-empty crystal list at all.
- **`GameState.fluids` was one pool of 8192 cells for both players.** A bucket is infinitely reusable,
  so one player could flood 8192 cells and the opponent's bucket became inert. The owner rides in two
  spare bits of the int `s.fluids` already stores per cell, so there is no new field, no new map and no
  codec change. The part that makes it a real cap is that `Fluids.compute` INHERITS the owner during
  spread - a falling cell from the cell above, a horizontally-fed cell from the neighbour that supplied
  the winning amount - because counting only source cells would be no cap at all.
- **The miss penalty's release clear.** Section 1A has it in full.
- Covered by `SharedPoolOwnershipTest`, six cases, including a 200-tick spread that reads every
  resulting cell back as the pourer.

The second asked the CHECKSUM_REV-161 question again over the whole net layer, and found that the first
pass had answered it in two places with numbers that were themselves peer-authored:

- **The liveness timer was keyed on a dial the peer holds.** `ticksSinceRemoteProgress` reset inside
  `if (frame > highestRemoteFrame)`, and `highestRemoteFrame` is the highest frame NUMBER the peer got
  accepted. That failed in both directions. A peer that skipped frame 1 and streamed 2, 3, 4, ... reset
  the timer on every packet while confirming nothing, freezing the match for about 45 seconds on demand;
  and a peer recovering from burst loss fills frames BELOW its highest accepted number, so a peer making
  progress on every tick was declared dead after 400. It now resets on `controller.confirmedFrame()`,
  which is `min(knownContiguous, head)` - a number this side computes by walking its own ledger, that
  the peer can advance only by supplying the next frame in sequence. Pinned by
  `LivenessIsLocallyConfirmedProgressTest`.
- **The retransmission window was anchored on the peer's ack**, which is the still-trusted row the last
  refresh listed. `sendInputs` started at `peerAckedThrough + 1`, so a peer acking our newest frame every
  tick switched retransmission off entirely and one dropped datagram was then never resent. The run start
  is now `max(pruneFloor, min(peerAckedThrough + 1, newestFrame - RESEND_WINDOW_FRAMES + 1))`: the ack
  can pull it DOWN but never above the local floor. `RESEND_WINDOW_FRAMES` is `INPUTS_PER_DATAGRAM` (20),
  derived from the MTU and the codec width, so the trailing resend costs no extra packet. The packet cap
  used to truncate the NEWEST end of the run, which is now sent as a second run when the cap binds.
  Pinned by `ResendWindowIsLocallyAnchoredTest`.
- **`DirectLink` admitted any datagram from the peer's IP**, port ignored, on the path that skips the
  relay. `RelayServer` keys on the full socket that presented a valid HMAC token; the direct path was
  strictly weaker on the same trust boundary. A `Channel` now pins the exact source of the Hello that
  passed the token check and admits only that socket, with a `peerRebinds()` counter so a NAT rebind is
  diagnosable rather than silent. Pinned by `DirectLinkPinsTheProvenSocketTest`. At CHECKSUM_REV 163
  the direct path's slot binding was brought up to the relay's: the shared per-session secret is no
  longer the thing on the wire. Each side presents `SlotTokens.derive(secret, sessionId, ownSlot)` and
  verifies the peer's against the derivation for the OTHER slot - the same rule `RelayServer` enforces
  under `RELAY_SLOT_SECRET` - so observing one HELLO no longer yields anything that binds the other
  slot, and the raw secret never travels at all. A secret shorter than `MIN_LINK_SECRET_BYTES` (16) is
  now refused by `DirectLink.open` instead of degrading to `Message.EMPTY_TOKEN`, which compared equal
  to the empty token every stranger sends; `EdgeAssignment.hasPeerEndpoint` applies the same floor so
  a short secret falls back to the relay rather than failing the match. A proven socket is also only
  given up to the same host, or after `REBIND_GRACE_NANOS` of silence, which is the relay's rebind
  rule; refusals are counted as `refusedRebinds`. Pinned by `DirectLinkSlotBindingTest`.
- **`UdpTransport` bounded its inbox in PACKETS, not bytes**, so 4096 queued packets at `MAX_PACKET`
  16384 is 64 MB of heap at a size the sender picks. `DirectLink` had capped the same queue in bytes all
  along; `UdpTransport` is the one production uses. Now `MAX_INBOX_BYTES` 4 MB alongside the count.
  Pinned by `UdpTransportInboxIsByteBoundedTest`.
- **`ClaimAuthority.WINDOW_FRAMES` was derived from a transport tolerance.** Section 5 has it.
- **`MatchSetupFrame0Decoder` allocated before validating** a claimed box count, so a 1 MB blob claiming
  a million boxes allocated about 70 MB before the buffer underflowed. Both sites now go through the
  file's own `require()` helper, which every other length in that decoder already used.

**Still TRUSTED, named rather than buried:**

| row | what remains | why it is still open |
| --- | --- | --- |
| **the relay path authenticates a handshake, not a channel** | `UdpTransport` admits any well-formed datagram whose source address is the relay's, and `RelayServer.handle` forwards any well-formed datagram whose source address is a bound peer's. Only the `Hello` ever carried a slot token. This is the exact weakness `DirectLink` closed at CHECKSUM_REV 165, one path over | **open, and deliberately not closed hours before the two-client test.** The attack is off-path source forgery: spoof the relay's address and blast a client's ephemeral port with a `Finish` or an input burst (65k ports is cheap), or spoof a bound peer's address at the relay and have the forgery forwarded AND teed into the referee. `connect()` on the client socket and the `peers` map at the relay are address filters, and an address is the one field an off-path attacker forges for free. The fix is `LinkFrame` on this path too, but it cannot be the direct path's fix verbatim: the two clients share no secret (slot tokens are per-slot), so the keyed channel is client-to-RELAY, keyed by that slot's token, with the relay verifying on ingress and RE-FRAMING on egress under the destination's token, its own per-destination counter and a 64-frame replay window, and `RefereeManager.tee` fed the unframed body. The relay holds both tokens in every deployment that authenticates at all (derived from `RELAY_SLOT_SECRET`, held by `RefereeManager` for an authorized session, or pinned on first use); the fully open relay has no key and must stay unframed. Once framed, the raw token no longer needs to travel in the `Hello` on a keyed session, which makes this path as strong as the direct one rather than merely closer. What makes it a deliberate hold and not an oversight: `Protocol.VERSION` is `(InputCodec.BYTES << 8) | CHECKSUM_REV`, so the only knob that fences a framing break is `CHECKSUM_REV`. Landing it means 165 -> 170, `EXPECTED_VERSION` 17336, the fence triple in `RUNBOOK.md`, `DEPLOY.md` and `TESTPLAN.md`, a re-recorded harness digest, and a lockstep redeploy of six mod jars, `jackpot-edge.jar`, `relay.jar` and `mcleagues.jar` - a total-failure blast radius (no duel starts at all if one byte is wrong) on the one path both test configurations use. It also rewrites roughly fifteen relay tests that build a `UdpTransport` with no token at all (`RelayUdpTest`, `RefereeIntegrationTest`, `UdpTransportInboxIsByteBoundedTest`), which is where a mistake would actually be caught. Land it as its own change, with its own build and its own soak |
| **GUI inventory ops on a moving frame** | nothing checks that a frame carrying a GUI inventory verb looks like a frame a GUI was open on. This is the shape of auto-totem and auto-armour | **the largest open item, and still open. Re-examined again at CHECKSUM_REV 165 and deliberately left open, with two concrete false-positive producers named rather than suspected: `InventoryIntents.chestEquip` emits `INV_MOVE` from a right-click with NO screen open and the player possibly sprinting, on BOTH hosts, so the whole `guiOnlyInvAction` set cannot be gated on `screenQuietFrame`; and the edge's `invQueue` drains one intent per tick, so a burst of honest clicks can spill onto the frame after the screen closed and the keys came back. A refusal written on today's predicate would eat both.** A receive-side refusal needs a matching producer-side HOLD on both hosts, and the edge's queue is fed by an unmodded client that can legitimately click a slot and be running the next tick. The clean design is a one-slot pending-op field in `PlayerState`, drained on the first frame that passes the gate, but that is replicated sim state and a visible meta change that wants owner sign-off. Two predicates now measure it rather than one: `inv-op-on-a-moving-frame` counts GUI-only verbs on frames that fail `HostFrameContract.screenQuietFrame` (274 in the scripted scenario - movement keys included, which is why it is too wide to refuse on), and `inv-op-on-a-combat-frame`, added at 165, counts the narrower and unambiguous case: a real container or inventory verb on a frame that ALSO claims a melee hit, a crystal hit or a block action (110 in the scripted scenario). The second is the one a refusal should eventually be written against, because a latched movement key can survive a screen opening but a left click cannot be spent in two places. Still measured, still not refused |
| abort to no-contest | a losing client can send `Message.Abort`, or a deliberately wrong `Message.Checksum`, and turn a loss into a no-contest | inherent, and narrowed rather than closed. A genuine desync MUST be a no-contest, so the two are indistinguishable by construction. What WAS closed at CHECKSUM_REV 163: the announcement no longer favours the announcer. Receiving `Abort(ABORT_DESYNC)` used to fall through to the generic peer-abort branch, which the edge files as `PEER_GONE` - the one cause a lone report is arbitrated INTO A WIN with - so a hostile client that forged a checksum and then made sure the victim's report went missing collected the match. It is now its own cause, `DESYNC_ANNOUNCED`, void from either side and on its own (`PeerChecksumIsAnAccusationTest`, `PeerDrivenNoContestTest`). A `Message.Checksum` for a frame past `localInputs.end()` is also refused outright and counted as `peerChecksumOverreach`: the sender provably could not have simulated a frame this side has not produced an input for, and that was the only unbounded growth path into `pendingRemoteChecksums`. **Closed on the modded relay path at this refresh**, and only there: a decided referee verdict outranks a fabricated `DESYNC` (`RefereeArbiter` precedence rule 2, `RefereeArbiterTest`), so on a mod duel through a relay with a control secret the forged no-contest no longer pays. It still pays on every path the referee does not watch - direct edge-to-edge links, edge-vs-edge relay sessions, and any relay started without `RELAY_CONTROL_SECRET`, which no longer includes the dev stack. Cross-play is no longer on that list: `RollbackHandoffManager.finishCrossPlayHandoff` now authorizes the session with the same `AUTHORIZE_SETUP` control message the mod path uses (the cross-play arena blob is already `encodeForSim`, so the relay can rebuild it), `resolveResult` hands the mod's report to `EdgeSessionBroker` without wiping the verdict state, and `EdgeSessionBroker.evaluate` runs the same `RefereeArbiter` the mod path runs. Edge-vs-edge over a relay is the one line left: `completeEdgeHandoff` never calls `authorizeReferee`. The cheap complement is still worth having: a policy counter in mcleagues on unexplained no-contests per account |
| aim, and melee kill-aura within reach | yaw and pitch are whatever the client says, and `ClaimAuthority.meleeClaim` does not raycast the CROSSHAIR | **narrowed at CHECKSUM_REV 165, still open for aim itself.** What is now REFUSED: `ClaimAuthority.aimAhead` throws out a claim when every candidate hull in reach lies more than 120 degrees off the frame's own look vector, measured from each candidate eye in the rewind window. That is the case the old note did not cover - a client setting `meleeHit` on a frame whose own yaw puts the victim squarely behind it - and the project's own determinism scenario was granting exactly that 199 times in a row, from tick 2522, with the victim pinned at x=-0.08 behind an attacker at x=0.50 looking at +x. Interpenetrating hulls are exempt: at 0.58 blocks apart which side of a 0.6-wide box someone is on is sub-block noise, and a claimant already inside their opponent gains nothing from the exemption. 120 degrees rather than a crosshair test because a human cannot rotate that far inside one 50 ms tick - it needs 2400 deg/s - so no flick, spin-click or jump crit is refused, and because the three old arguments against a CROSSHAIR gate all still hold: the claim carries no aim (`meleeHit` is one bit), vanilla's own `ServerGamePacketListenerImpl` gates on distance alone, and `McInputSource` raycasts the RENDERED opponent box which trails the sim head, so on a bad link the box the client tested is not in the candidate set and a crosshair gate would refuse the laggy honest hits. What is still TRUSTED: the yaw itself. A kill-aura that snaps yaw onto the target passes `aimAhead` exactly as it passes vanilla, and that is irreducible - the client decides where it aims. It is measured rather than refused: `PlayerState.meleeClaimsGranted` and `meleeClaimsOffAim` are per-slot, replicated, checksummed and survive a round reset, so the ratio is identical on both clients and on the relay referee and a disagreement about it IS a desync; `ClaimAuthority.offAimRateExceeds` is the predicate a policy layer reads and NOTHING reads it yet. The scripted scenario produces 188 off-aim grants against 827, which is how large the honest population of jump crits and mid-turn hits is. `ClaimMarginTest` owns the decision (both the refusal and the interpenetration exemption), `MeleeAimIsMeasuredNotGatedTest` pins that the CROSSHAIR still may not decide, `MeleeOffAimIsAReplicatedRateTest` pins the ledger |
| the direct edge-to-edge link has no third witness | `DirectLink` (port 7787, `rollback.edge.direct-links`, off by default) carries a session with no relay in the middle, so nothing tees the two input streams and no `RefereeSession` can be built from them | **irreducible for a two-party socket** and named rather than left implied. A referee is a third party; a link whose whole purpose is that there is no third party cannot have one. Two things bound it: both endpoints are edge plugins we run, not players, so the trusted party is our own infrastructure rather than a client, and the path is opt-in. If a verdict is wanted for these sessions the fix is not in `DirectLink` - it is `EdgeSessionBroker` opening the same `AUTHORIZE_SETUP` control connection mcleagues-core already opens for the mod path, which `EDGE-PROTOCOL.md` already describes. What CHECKSUM_REV 164 closed: an endpoint-wide and a per-source token bucket BEFORE the session lookup, so a flood naming a session nobody opened is throttled instead of merely counted as `unknownSession`, and a version-mismatched `Hello` raises `Abort(ABORT_VERSION_MISMATCH)` into the LOCAL inbox so a partial deploy fails named instead of timing out - raised locally and never sent, so an unauthenticated source cannot turn it into a reflector, which is better than the relay rather than merely equal. What CHECKSUM_REV 165 closed: authentication was a handshake, not a channel. Only the `Hello` carried a slot token; every packet after it was admitted on SOURCE ADDRESS alone, and an address is the one field an off-path attacker can forge for free - the broker hands the peer's host:port to the other edge in clear. Every frame now carries `LinkFrame`, a 16-byte HMAC-SHA256 tag over magic, session, slot, an 8-byte counter and the body, keyed by the same per-session secret the slot tokens derive from and which never goes on the wire, plus a 64-frame replay window so a captured Finish or input burst is worth nothing twice. The pinned address stays as a cheap pre-filter, so the direct path now requires BOTH what the relay requires and a per-packet signature the relay does not have. Counters `badFrameTags`, `replayedFrames` and `untaggedFrames` are read out by `EdgePlugin.reportDirectLinkFence`. The outer magic moved to `0x4A444C32`, so a peer edge on an older jar is refused loudly instead of misparsed - deploy both edges together. `DirectLinkIsNotWeakerThanTheRelayTest`, `DirectLinkPinsTheProvenSocketTest` |
| container CONTENTS attribution | a peer may paint any in-bounds shulker's contents, not only ones it placed | bounded to what the victim SEES; the sim owns what they can take. Needs a per-cell placer record on both hosts |
| click rates no hand produces | 7 attack, 7 use, 7 drop, 7 inv, 7 swap presses per tick remain representable | each is bounded downstream to the outcome a human reaches. Worth telemetry, not a refusal, since a 200 Hz mouse genuinely lands several inside one 20 Hz sample |
| a left click naming two targets | a frame may set `meleeHit` and `crystalHit` together, which `HostFrameContract.leftClickTarget` says is not a thing a host produces | re-examined at CHECKSUM_REV 165 and NOT a hole: `Combat.resolveBlockActions` runs before `Combat.resolve` and drains the whole shared attack `ClickBudget` on the crystal, and `hitCrystalOnce` sets `meleeClaimTick` to the current tick, which `swingOnce` then refuses on. The crystal wins and the melee is already dropped - one click buys one action. Filtering it in `contractFiltered` would have inverted that resolution and broken the scripted scenario's own counted-click segment, which sets both deliberately. Left as a measurement, `left-click-claimed-two-targets`, 66 in the scripted scenario |
| the authority stamp | an edge-hosted player's position is written from `Input.authority` rather than simulated | **bounded, and the trusted party is the edge, not the client.** `Simulation.stamps` requires `s.edgeHosted[idx]`, so a modded client's stamp is ignored outright; the trust is in our own plugin relaying a vanilla server's position, which is the only correct authority for a player who is not running the sim. The 24-block ceiling is not a raw teleport: `authorityMove` walks the delta in 0.5-block legs through `collideArena`, so the move is swept and clipped exactly like a simulated one and a stamp aimed through a wall lands against the wall. Not clamped tighter than 24 on purpose - a pearl, a knockback or a server-side teleport legitimately moves a vanilla player that far in one tick, and clamping would desync us from the server that owns them. What 165 added is visibility: `authority-stamp-unowned` counts a stamp arriving from a slot that is not edge-hosted and `authority-stamp-too-far` one past the ceiling, both of which were previously ignored in silence. Both read 0 in the scripted scenario |
| dead SNAPSHOT wire surface | `Protocol` still decodes a message type nothing produces or consumes | garbage pressure, not damage. Delete on the next wire change |
| `s.blocks`, `s.cobwebs`, `s.fires`, `s.anchors` have no explicit cap at all | bounded only indirectly, by what a player can be holding (itself capped by `LoadoutCaps`) and by the per-tick blast budget | re-examined in the per-owner sweep and left alone deliberately. No cap means no first-come pool and therefore no denial: nothing one player does can make the other's next placement fail. The bound is inferred rather than stated, which is a memory question rather than a griefing one, and an explicit lid would be worth having so a future item change cannot quietly remove it |
| `GameState.blastCellBudget` / `blastMarchBudget` | 2048 and 262144 cells per TICK, shared between both players, and `Combat.resolveBlockActions` always resolves player 0 first, so in a tick where both detonate player 0 spends first | the one shared first-come pool left after the CHECKSUM_REV 162 pass, and deliberately not split. Exhausting it truncates block destruction for ONE tick - the explosion still fires, still damages, still knocks back - and it refills unconditionally. Splitting it means turning two ints into per-owner pairs, which changes the frame-0 codec layout `GameStateFrame0Codec` writes: a wire change for a one-tick truncation. Fold it in if that codec is revised for another reason |
| rollback depth is peer-positioned | `RollbackController.onRemoteInput` accepts a correction at any depth and resimulates from there, and depth is entirely the peer's to choose | BOUNDED at CHECKSUM_REV 163, not eliminated - it cannot be, because a legitimate deep correction must still be honoured or the two sims diverge. A token bucket refilling `RESIM_FRAMES_PER_ADVANCE` frames per LOCAL frame produced, capped at `RESIM_BUDGET_RINGS` (8) rings, so any single correction is absorbed and only a sustained demand past the playable envelope drains it. The refill was 64 until it was re-derived: an honest peer costs this side one rollback of its own arrival lag per frame we advance, so the sustainable rate IS that lag, and 64 meant carrying a peer 3.2 seconds behind for ever - a rate no honest link asks for and one a hostile peer could sit on. It is now `HONEST_PEER_LAG_FRAMES` (20, the one-way arrival lag `PeerDrivenResimulationIsBudgetedTest` already pinned as the ordinary case) plus `PEER_JITTER_FRAMES` (4), so 24. A peer at twice that is now cut off after roughly 240 frames instead of never (`aPeerTwiceAsFarBehindAsAnHonestOneRunsOutInsteadOfBeingCarried`); capacity is untouched, so a single deep correction is still absorbed whole. Overrun throws `ResimulationBudgetException`, which `NetSession` catches AHEAD of the generic `RuntimeException` branch - that branch is `abortSelfFault`, which CONCEDES, so routing it there would have handed the match to whoever caused it. It aborts as `PEER_OVERRUN` instead: peer-attributable, void from either side, and not `PEER_GONE`. The honest cost of a false positive is a no contest on a link that has held more than 1.2 seconds of one-way arrival lag long enough to drain a full budget, which is minutes at the edge of the envelope and seconds only once the lag is several times playable. `PeerDrivenResimulationIsBudgetedTest` |
| `RefereeSession.advance()` | can run up to `MAX_FRAME_LEAD` (4096) `Simulation.tick` calls inside one `ingest()` when a peer supplies a long prefix at once | the window is anchored on the referee's own frame, which is the right shape, and this is off the live match path. Recorded rather than changed; it wants a per-call frame budget if the referee ever runs on a latency-sensitive thread |

One field deserved naming on its own because it was the most dangerous in the frame: the **authority
position word**. It used to be a raw position write, up to 24 blocks per tick with no collision test,
plus a free-standing `onGround` claim - flight, noclip and blink in one field, behind the single
replicated boolean `s.edgeHosted[idx]`.

At CHECKSUM_REV 163 the magnitude clamp stopped being the only thing checking it. The claimed delta is
swept through `collideArena`, the same collider the simulated move uses, against the same replicated
arena, placed blocks and broken voxels; a clear path returns the whole delta unchanged, so an honest
stamp costs exactly what it did before, and a claim that crosses geometry lands the player against the
surface instead of through it.

At CHECKSUM_REV 164 that sweep is WALKED rather than taken in one call, which is what makes it hold at
the top of the magnitude clamp. `Collision.collide` is vanilla's axis-clamp collider: each axis is
clipped against the boxes the box currently overlaps on the OTHER two axes, which is exact for the
sub-block deltas vanilla ever hands it and corner-cuts at 24 blocks, so a single 24-block sweep could
still finish on the far side of a wall neither the entry nor the exit box overlapped. `authorityMove`
now splits the delta into legs of at most `AUTHORITY_WALK_STEP` (0.5, under `PLAYER_WIDTH` so no
one-block wall can sit between two legs), applies each through the same collider, and re-derives the
step-up ground flag per leg. `authorityWalkSteps` returns 1 for every ordinary 20 Hz sample, so the
common path is bit-identical to what it was, and the worst case the clamp admits is bounded by
`AUTHORITY_WALK_MAX_STEPS` (64) so a hostile stamp cannot buy unbounded collision work.
`AuthorityStampIsWalkedNotTeleportedTest` pins the wall, the boundary, the bound and the open lane.
Note what this does NOT say: the digest is blind to all of it, because the scripted scenario never sets
`edgeHosted`. `onGround` is no longer believed on its own word either: it is honoured
only where `supportedBelow` agrees, and a claim of NOT having landed is overridden when the player is
demonstrably standing on something and not moving up, which was a permanent fall-damage cancel.
`AuthorityIsSweptNotTeleportedTest` pins the wall, the floor, the mid-air ground claim, the fall, and
that `edgeHosted` is still the outer gate. What is still trusted: WHERE inside a clear path the edge
puts the player, which is the whole point of the word.

### The relay referee: a third witness, on one path, never yet run for real

The security argument above bounds what a tampered client can make the SIM do. It says nothing about
who decides the RESULT, and the result path had only two witnesses: the two clients. That is why
several rows in the table above used to end "only the referee closes this".

The referee is now wired end to end on the modded path. `RollbackHandoffManager.startModHandoff` opens
one authenticated TCP link to the relay and sends `ControlProtocol.AuthorizeSetup` carrying the session
id, an expiry, both slot tokens, the match-setup wire and the arena blob. `RelayServer.handle` tees
every forwarded packet into a `SessionReferee`, which re-simulates both input streams; when it reaches
a decided match end `ControlEndpoint` ships an HMAC-signed `Result`, and `RefereeArbiter` applies one
precedence rule: a violation VOIDs, a decided verdict SETTLES and outranks a contested double claim, a
fabricated `DESYNC` and a concession naming someone else, an undecided verdict DEFERS for
`referee.grace-ms` (4000 by default), and anything else FOLLOWS the pre-existing two-witness logic. A
verdict cannot be produced by a client, because it exists only when the relay's own re-simulation
reaches `state.roundMatchOver`.

**Degradation is loud and never silent.** Referee off, secret empty, relay unreachable, link dropped,
authorize refused, or an arena with no sim-canonical geometry all make `refereeExpected(sessionId)`
false, and the arbiter returns FOLLOW_CLIENTS - the same two-witness rule as before, never trust in a
client. `announceConfigOnce` logs the endpoint and the trust rule at INFO when it is configured, and
`#### THE RELAY REFEREE IS OFF ####` at SEVERE when it is enabled without a secret. A verdict that
disagrees with a settled client report logs at SEVERE naming both winners and raises
`RollbackDesyncAlert` with cause `REFEREE_DISAGREEMENT`.

**Three things to hold against all of that.** The dev stack exercises the *producing* half only:
`devRun` starts the relay with `RELAY_CONTROL_SECRET` and `devAssign` sends `AUTHORIZE_SETUP`, so a
TESTPLAN duel really is re-simulated and really does print a signed verdict, but nothing there acts on
it - the consumer is core, and core is not in the dev stack. `RefereeSetupAuthorizeTest` now drives a
relayed duel all the way to a signed DECIDED verdict off the input streams alone, on the modded path.
Cross-play is authorized too now - `finishCrossPlayHandoff` opens the same `AUTHORIZE_SETUP` control
connection, and the arbitration runs inside `EdgeSessionBroker`, which is the only state machine that
sees both reports - so the paths still on two witnesses are edge-vs-edge over a relay, and direct
links, which have no third witness by construction.
And turning it on changes what core ships as the arena: `ArenaBlocksCodec.encodeForSim` palette
geometry instead of the plain block snapshot, which is strictly more accurate than the client-derived
full-cube approximation the mod fell back to, and which nobody has played on. Section 7 has the
remainder; DEPLOY.md 5.2a has the config and RUNBOOK.md 2.6a the ten counters.

### Cross-play: it could not start, and the reason was not the netcode

Three defects, all fixed. **Provenance note:** the "verified live" in this subsection and in *How far
the live run got* below is inherited from the session that did the work, with ONE exception that was
run live at all: both Paper edges were started headlessly (`gradlew devVerifyEdgeBoot`), both passed
their build fence and both reached `edge ready:` and reported `inputBytes=67 checksumRev=163
protocolVersion=17315`. **That boot was at CHECKSUM_REV 163 and has not been repeated since**, so the
triple in it is two revisions old and is evidence that the fence PRINTS and that the edges start, not
evidence of today's number - re-run `devVerifyEdgeBoot` if you want the current one, and expect
`checksumRev=171 protocolVersion=17336`. Nothing else was brought up on that run - no Redis, no
Mongo, no relay, no limbo, no assignment - so defects 2 and 3 below and everything in *How far the
live run got* remain one session's report. What was re-checked by reading rather than running:
`EdgeBuildFenceRemapTest`, `SlotTokens.derive`, the two metrics ports in `devenv.gradle` and the four
`config.yml` keys are all present and read as described.

1. **BLOCKER, and it stopped every match, not only cross-play.** `EdgeBuildFence` compared the stamp
   against `sha256(getFile())`, which Paper hands as the remapped copy. `deployedHash` now resolves the
   origin through `.paper-remapped/index.json`, whose `hashes` map is keyed by the pre-remap sha256, and
   REFUSES when neither the index nor the source jar is readable, because an unreadable check must stay
   a failed check. `EdgeBuildFenceRemapTest` pins four cases including that a genuinely stale jar is
   still refused through the remap path. `devVerifyEdgeDeploy` passed the whole time this was broken,
   because it inspected the file on disk rather than what the JVM loaded. That half is closed too, in
   the refresh that wrote this line: section 6, item three.
2. `DevAssignMain` minted a random per-slot token, which binds a trust-on-first-use relay and nothing
   else. A relay started with `RELAY_SLOT_SECRET` drops anything else silently. It now derives through
   `SlotTokens.derive`, the same rule core uses.
3. Both dev edges defaulted to metrics port 7788, so edge-a bound nothing and half the fleet was
   invisible to the exact check the runbook tells you to make. They are 7797 and 7798 now.

Also fixed, and it is the difference between "refused" and "works": `rollback.edge.enabled` and
`rollback.cross-play` both default to false and **neither key existed in the shipped `config.yml`**.
The file is `mcleagues-core`'s `src/main/resources/config.yml`, under the `rollback:` block, not the
edge plugin's `config.yml` in this repo - the edge has no say in whether a mixed pair is allowed, only
in how it hosts the half it is given. With `edge.enabled` false a mixed pair is refused outright; with
`cross-play` false a mixed pair is hosted entirely on the edge, which works and is not cross-play.
Both, plus `relay-slot-secret` and `edge.require-real-arena`, are now in that file at their real
defaults.

**How far the live run got.** Redis and mongo up, all three limbo protocols probing green, relay bound
in DERIVED mode, both Paper edges reaching `edge ready:`, one assignment pushed, both edges accepting
their half of ONE session id, both loading the same arena key and printing the same
`hash=0xe75b5c4698e28cc8`, both per-slot HMAC tokens independently recomputed and matched, and all three
running services reporting 67/160/17312 off their own `/metrics` - which was the correct fence at that
moment and is now 67/166/17336, so re-run it rather than comparing against those digits. Then both edges
logged `waiting for <name> to join THIS edge`, which is the correct and only possible state with nobody
connected.

**What that leaves unproven.** The next event in the sequence is a Minecraft client connecting, and that
is not scriptable from a headless box. The two clients dialling the relay and pairing, the arena and
frame-0 agreement exchange between a real mod jar and a real edge, and the round trip of the RESULT
plugin message are covered by headless tests and by construction, not by a played match.
`CrossPlayRelayMatchTest` does run a real cross-play match over a real `RelayServer` on real UDP with
derived tokens and asserts per-frame confirmed-checksum equality, `unauthorized == 0` and
`sessionCount == 1`, which is the strongest evidence available without a human.

## 2. The commands, in the order to run them

**`TESTPLAN.md` is now the document for this.** It carries the three configurations, the mechanics
checklist, the round-boundary block, the desync capture procedure and its own four time-wasters, and it
is written for somebody with two clients and a couple of hours. This section is the short version and the
reasons behind the order.

The order matters because each configuration adds exactly one unproven thing. If you start at cross-play
and something desyncs, you cannot tell whether the fault is the sim, the arena bytes, the limbo handoff
or the two hosts disagreeing.

**Step 0. Prove the build.** `gradlew --stop` first, always, for the reason in section 0.

```
cd jackpot-rollback
gradlew --stop
gradlew clean build --no-build-cache
cd ../pvphq-rollback-mod && gradlew 1.21.11:build 26.1.2:build 26.2:build verifyModJars
```

There is no `updateHarnessDigest` line here any more, because the reference is current. Run it, ALONE
and never chained with `build`, only when you have deliberately changed simulated behaviour and the gate
says so in its own words; section 7 has the reason it cannot be chained. If `verifyModJars` does not
report `VERSION 17336 OK` six times, stop. Every match will abort at HELLO and the client will tell you
nothing useful about why. If it does report six, that means the jars would shake hands - it does not
mean they were built from this tree, so run the `:build` line regardless of what the check says.

**Step 1. EDGE vs EDGE, no broker.** `gradlew devRun`, then `/edge` on both clients. No mod, no Redis,
no limbo, no Docker. Both halves run the same host code, so a failure here is the sim or the edge and
nothing else. `devRun` already depends on `devSetup`, `devVerifyEdgeDeploy`, `devLimboConfig` and
`devSampleArena`, so it provisions and fence-checks the edges for you.

**Step 2. MOD vs MOD.** `-Pmod=both`. Adds the mod, the limbo and shipped arena bytes. Both hosts are
again the same code, so a desync here is a sim or arena problem, not host divergence, and you want that
class eliminated before step 3.

**Step 3. CROSS-PLAY.** `-Pmod=A`. One modded client, one vanilla client on an edge. This is the
configuration that finds host divergence, and host divergence has been the single most productive bug
class in this project. Everything else is a rehearsal for this.

Run each step as a full multi-round match before moving on, and in CRYSTAL, because CRYSTAL is the only
rule set with every mechanic switched on. Rounds default to **1** everywhere, so without a flag the match
ends at the first death and the whole round-reset path goes untested. `-Prounds` is first-to-N, not
best-of-N, and where it goes differs between `/edge` and a brokered match; TESTPLAN says which at each
step.

Two flags that decide whether anything happens at all. `-ParenaName` is mandatory for any mod slot and
`devAssign` refuses without it, because a modded client builds its collision from the bytes the server
sends and has no local arena file. And `-PrelaySlotSecret` must be the SAME string on `devRunBrokered`
and on `devAssign`; omit it on both and the relay runs trust-on-first-use on loopback, which also works
but is not the shape production runs in.

## 3. What is actually reachable, per game type

`EdgeGameTypes.rules` decides them, one row per production game type. Re-read against the source for
this handover; unchanged, and `verifyGameTypeRules` confirms all 20 today.

| game type | arena mineable / blastable | buckets | explosions | pot sword boost |
| --- | --- | --- | --- | --- |
| VANILLA, HT_VANILLA, LT_CART, HT_CART, CREEPER, CUSTOM | on | on | on | off |
| MONEY_SMP | on | on | off | off |
| MACE, SPEAR_MACE, SPEAR_ELYTRA | off | on | on | off |
| UHC, SHIELDLESS_UHC, DIAMOND_SMP, DIAMOND_SMP_NEW | off | on | off | off |
| SWORD, BOW, AXE, SMP, NETHERITE_POT | off | off | off | off |
| POT | off | off | off | on |

The five demo-kit names are not game types: IRON, DIAMOND, POT, NETHERITE_POT and CRYSTAL pick which kit
the dev stack hands out and decide no rule of their own. Each borrows the rules of the production type it
stands for, and `EdgeCoreGameTypeRulesTest` pins that.

### Mechanics that silently do nothing

The kits are handed out in full regardless of the rules, so the hotbar looks complete while part of it is
inert. With explosions off, every END_CRYSTAL, RESPAWN_ANCHOR and GLOWSTONE never places, charges or
detonates. With buckets off, WATER_BUCKET and LAVA_BUCKET never place and never pick up. With building
off, blocks still place but the arena is never mineable and never breaks to a blast.

**Which of those you can actually hit with the DEV demo kits is narrower than that, and it is worth
knowing before you go hunting.** `EdgeDemoKits.forGameType` normalises to one of five kits, and only the
CRYSTAL kit carries crystals, anchors, glowstone, buckets and the efficiency V pickaxe. Every type that
normalises to the CRYSTAL kit has explosions and buckets ON. So on the dev stack the only reachable
dead-item case is the MACE family, which gets the whole CRYSTAL kit against a rule row with
`vanillaBuild` off: the pickaxe mines nothing and no blast breaks terrain. The explosion and bucket rows
above are real rules and matter for a production kit that core builds; they cannot be reached with a demo
kit.

The edge prints a banner naming every disabled item that is actually in the kit, at match start, on both
edges. `EdgeGameTypes.warningLines` computes it from the kit's own materials, so it never names an item
you were not given. Read it. This confusion has already cost time.

### The demo kits now reach every mechanic the sim models

On 2026-08-26 an unimplemented-sweep found that six mechanics TESTPLAN listed as *deferred, needs a
real kit* were fully modelled by the sim and fully encoded by `EdgeKitTables` - `Loadout.isAxe`,
`Loadout.multishot`, `Loadout.windBurst`, `Combat.USE_WIND_CHARGE`, the notch-apple branch of
`EdgeKitTables.effectsOf` and the generic `PotionMeta.getAllEffects` path - and were unreachable only
because no demo kit carried the item. `EdgeDemoKits` now carries all of them: the CRYSTAL kit gains a
netherite axe, 64 wind charges, a second loaded crossbow with Multishot, a mace with Wind Burst III
and Breach IV, and 16 enchanted golden apples, paid for out of duplicate stacks (crystals 256 to 192,
obsidian 192 to 128, glowstone 128 to 64, loose totems 5 to 3); the POT and NETHERITE_POT kits gain
3 splash harming, 2 speed, 2 strength and 2 fire resistance in storage slots that were empty.

This is a **kit** change, not a sim change: no rule moved, `Protocol.CHECKSUM_REV` does not move, and
the harness digest does not move. It does change the match-setup bytes both edges compute, so both
edges have to be on the same jar - which they already had to be. What is genuinely still unreachable
is in TESTPLAN's *Deferred* table and every row of it is unmodelled rather than unequipped: thorns,
crossbow piercing (`ItemDict.piercing` is replicated and read by nothing, so a Piercing crossbow would
look like a bug), climbing, lingering potions, haste and mining fatigue.

**One consequence of "reachable" that reachability alone does not give you.** Having the item in the
kit is not the same as being able to fire it. `Loadout.activeArrowSlot` takes the off-hand if it holds
arrows, and otherwise the **first** arrow stack in slot order; the CRYSTAL kit's 64 plain arrows sit
one slot ahead of the 64 arrows of weakness, so every bow shot and every crossbow reload consumes a
plain arrow until the plain stack is empty. The tipped-arrow row in TESTPLAN was therefore untestable
as written, and now tells the tester to move the weakness arrows into the off-hand first. It is not a
defect - the ordering is deterministic and replicated, so both hosts pick the same arrow, which is the
property that matters - but it is the kind of thing that reads as "tipped arrows do nothing" from a
keyboard. If a future kit wants a tipped arrow to be the default ammunition, the place to put it is
the off-hand, not a later slot.

`explosionParticles` and `totemParticles` are per-recipient RENDER settings and provably never reach
`GameState`, so the two peers are allowed to disagree about them. This was chased again during the
cross-play pass, because `MatchSetupCodec.readdress` hands the edge the MOD player's copy of those
flags. It is correct: `MatchRules.applyTo` never writes them into `GameState`, and the only readers
anywhere are two lines in the mod's own renderer. Recorded here so the next reader does not re-open it.

### The dev table and production agree, and a fence says so

`EdgeGameTypes` transcribes production row by row, because `mcleagues-core` has no sim-core on its
compile classpath. `verifyGameTypeRules` reads `GameType.java`, resolves the constructor's parameter
NAMES so a reordering cannot slip past, and fails on any disagreement or on a production game type the
dev stack has never heard of. Fatal under `-PcoreDir` or `CI`, a loud warning otherwise.

What the fence deliberately does not cover: production rules are still modified per match by the arena
(`isBypassBlockInteractLimits` can turn building on for a type that has it off) and by a CUSTOM game's
custom kits. The dev stack has no ArenaType. The fence proves the two tables agree; it does not prove
any individual production match runs what the table says.

## 4. The eight production phases, plus cross-play, stated honestly

**Phase 1, close the open defects. Believed done, spot-checked not audited.** The seven reported symptoms
are resolved as described in section 1 and that part I verified line by line, plus the five cross-play
channels in section 5. An older handover spoke of an original list of blocking findings that was never
re-audited in full. That list is not in this checkout and I could not find it, so I cannot tell you how
long it was or what is left on it - treat any remainder as claimed rather than confirmed, and treat
"phase 1 is done" as unproven for anything not named in section 1. The gaps in section 7 are open
regardless.

**Phase 2, a harness that proves something. Done, and its reference is current.** 11740 ticks, two
independently extracted arenas with distinct object graphs and a committed arena hash, a real rollback
pass with 3341 rollbacks and 23661 resimulated frames at maximum depth 603 over 4245 scripted arrivals,
9 of 9 rollback shapes and 50 of 50 scenario behaviours exercised. The reference digest is recorded at
CHECKSUM_REV **166** and the tree is at 170, so `checkHarnessDigest` replays clean - section 0 is the
authority on this and not the paragraph you are reading. Note what that does and
does not mean: the harness earned its keep by going RED on each deliberate behaviour change and green
again only after a re-record, and it stayed green through the CHECKSUM_REV-162 net-layer pass, which is
how that pass proved it changed no simulated value. **One qualification, and it is a limit of the
harness rather than a fault in it.** At CHECKSUM_REV 163 the re-recorded digests came back
byte-identical to the 162 ones (`804d789781fe6675` / `30568c9bf64fd7e2` / `e7719629c29e3543`), because
the scripted scenario never sets `edgeHosted` and so never exercises an authority stamp, which is what
that pass changed the rules for. The rev still had to move - a mixed fleet would diverge on the first
stamped frame - so a re-record whose numbers do not move is NOT evidence that a sim change was inert.
CHECKSUM_REV 164 changed two rules and only one of them is visible here: all three digests moved
(`7a0a3598cf9a7bf2` / `5683242635fc33a9` / `2e82fca52e040796`) because the melee off-aim ledger is in
`Checksum` and the scenario grants 557 melee claims, while the walked authority sweep in the same
revision moved nothing at all, for the same reason 163 moved nothing.
The fifteen `KNOWN_UNCOVERED` arms in section 1A, and the eighteen floors lowered in section 5, are the
other standing qualifications.

**Phase 3, cross-machine determinism. OS axis closed, architecture axis RED.** Section 8. Unchanged.

**Phase 4, first real duels. NOT STARTED, and now genuinely unblocked.** Nothing in this project has ever
played a match with a human at the keyboard. What is new is that the dev stack now comes all the way up:
before this session it could not, because of the `EdgeBuildFence` remap defect, so anyone who had tried
would have got a disabled plugin and no explanation. Everything except two Minecraft clients has been
driven live - that sentence is inherited from the session that did it; what was re-run for this refresh
is only the two Paper edges booting clean and passing their build fence. Until a human plays it, the correct summary of this project is "a full suite with zero failures, a
determinism digest that replays clean, and a dev stack that reaches the join prompt", not "it works".

**Phase 5, match lifecycle end to end. NOT STARTED.** Brokered assignments against real core, real
`.farena` arenas, kits from core rather than the demo kit, ELO and stats, `/matchhistory`,
disconnect/rejoin/forfeit, both-concede voiding. Note the specific limit found this session:
`devAssign` cannot carry a real per-player kit, because it has no server to serialize inventories with,
so both edges build the DEMO kit. The dev stack proves the wiring, not the kit encoding.

**Phase 6, load and soak. NOT STARTED.** The sim is fixed-timestep, so a long GC pause is a rollback
storm. Nobody has watched heap under `-Xms2G -Xmx4G` G1, Redis churn, or rollback counts at real
cross-region latency.

**Phase 7, observability. Partly built, first real numbers seen.** The metrics, the desync-abort alert
and the counters exist and are documented in RUNBOOK.md. Three `/metrics` endpoints were curled live
this session and answered, which is more than had ever happened before, but that only proved
`build_info`. No dashboard has displayed a match's numbers, and the desync-abort rate has never had a
non-zero baseline to alert against.

**Phase 8, staged rollout. NOT STARTED, mechanism in place.** `Protocol.VERSION` is validated at HELLO so
mod, edge, relay and core ship in lockstep and an old jar aborts every match with
`ABORT_VERSION_MISMATCH`. `rollback.edge.enabled` in core is the kill switch, and it is now actually
present in `config.yml`. DEPLOY.md carries the staged plan with per-stage abort criteria. None of it has
been executed. The wire fence is 17336, and `mcleagues.jar`, `relay.jar` and all three mod jars deploy
LOCKSTEP at that number. Every artifact in the tree has been rebuilt to it and `verifyModJars` agrees,
so for the first time the tree has a shippable set - which is a precondition of phase 8, not the phase.

**Cross-play specifically.** Still the riskiest axis and still the least evidenced by a human, but it is
no longer the least evidenced by machine. Both hosts build their frames through one shared arbitration
contract (`HostFrameContract`, 21 arbitrated channels in the parity ledger), `HostProducerParityGateTest`
turns a producer that stops calling a shared rule into a red build, and two sim-host tests drive a full
CRYSTAL cross-play match: `CrossPlayCrystalMatchTest` over a lossy loopback at `ROUNDS_TARGET = 9`, and
`CrossPlayRelayMatchTest` over a real `RelayServer` on real UDP. Both compare confirmed-frame checksums
rather than only the final state, and the relay one additionally asserts `unauthorized == 0`,
`versionMismatch == 0`, `sessionCount == 1`, no desync frame on either host, and that the two hosts agree
on each slot's round wins.

## 5. Cross-play: what an unmodded player gives up, measured in blocks

No mixed match has ever been played by a human. Everything in this section is read out of the code and
out of the parity ledger (`HostParityDecisions`), which is the best confidence available and is not the
same thing as evidence.

### The fairness table

Units: **blocks** is the distance an unmodded player could not claim, or over which a modded player could
claim something an unmodded one could not. Reference speeds: vanilla sprint 0.2806 blocks/tick,
sprint-jump 0.3568 blocks/tick, 20 ticks/s.

| channel | site | favoured | gap, blocks | gap, actions/s | now |
| --- | --- | --- | --- | --- | --- |
| arrow rewind claim (`projectileHit`) | `McInputSource` | modded | 1.40 sprinting, 1.78 sprint-jumping (was 7.02 / 8.92 - see below) | ~1.0 (one per loosed arrow) | **0** |
| spear maximum reach | `ItemDict` / producer pick | modded | 1.625 | 1.0 | **0** |
| spear minimum reach (dead zone) | `ClaimAuthority` | unmodded | 2.0 | 1.0 charged, up to 20 spammed | **0** |
| anchor charge classification | `McInputSource` | neither, both wrong | 0 (temporal, >= 1 tick) | up to 5.0 | **0** |
| crystal pick reach and metric | `EdgeInputSource` | unmodded | 1.5 | 5.0 | **0** |
| shulker mine refusal | `McInputSource` | neither | 0 | 0 | 0, was always 0 |

I re-read the code behind every row. Four of the five derivations are unchanged and the constants still
read as stated: 4.5 + 0.125 = 4.625 against a vanilla 3.0 for the spear maximum,
`ItemDict.SPEAR_ATTACK_MIN_RANGE` = 2.0 for the dead zone, and `crossPlayReachLedger` asserts both bands
live. The **arrow** row changed and its number is now different from the previous handover; that is the
first entry below. The short forms:

**Arrow rewind claim. It was the largest single item there has ever been, and the window it is measured
against shrank by five this session.**

When it was open, `ClaimAuthority.WINDOW_FRAMES` was 25 ticks - 1.25 seconds of victim hull rewind, hence
the 7.02 and 8.92 an older handover quoted. It is now **5**. The old constant folded in
`RollbackController.PREDICTION_DECAY_FRAMES` (20), which does not belong: that is how long `NetSession`
repeats a PREDICTED remote input before decaying it to `gestureOnly`, a property of filler input, and it
says nothing about how stale an attacker's view of the victim may honestly be. Folding it in multiplied
the window by five and handed every attacker a 1.25 second trail of the victim's past positions, any one
of which satisfies a melee or arrow claim.

**The value has been 5 for two revisions; the DERIVATION changed in the second and that is worth
reading.** It was `Protocol.MAX_PEER_DELAY_ALLOWANCE (4) + INPUT_DELAY_FRAMES (1)`, which is wrong in
kind. `MAX_PEER_DELAY_ALLOWANCE` is a NETWORK tolerance on how far ahead of its own head a peer may
author inputs; its honest consumers are `fillerFrameIsPossible` and the catch-up target. Wiring it into
a hitreg window means tuning the transport silently moves how far back in time a sword reaches, and the
thing it bounds is the ATTACKER's input scheduling, not the interval the VICTIM rewind has to cover.
sim-core's own belief about the peer's input delay is `peerDelayUpperBound()` = 1, not 4, so the two
numbers did not even agree. It is now three terms and only three:

```
WINDOW_FRAMES = INPUT_DELAY_FRAMES (1) + RECORD_OFFSET_FRAMES (1) + PRESENTATION_LAG_FRAMES (3)
```

`INPUT_DELAY_FRAMES` is the sample-to-apply delay, a sim constant both hosts compile in - a host with a
different value is a different VERSION and cannot connect. `RECORD_OFFSET_FRAMES` is 1 even at zero
input delay, because `ClaimAuthority.record()` runs at the TOP of `Simulation.tick`, before movement.
`PRESENTATION_LAG_FRAMES` exists because the mod does not draw the opponent at the sim head at all:
`McSimRenderer` eases toward it under a correction budget and only snaps at `OPP_DESYNC_SNAP_DIST` = 1.5
blocks, which at vanilla sprint is 5.3 ticks of legitimate render trail, so 3 is a conservative fraction
of the renderer's own worst case. Two frames are the pipeline, exactly and provably; the third is the
one judgement in the derivation and it should be tuned against `McSimRenderer`'s own constants rather
than guessed. `ClaimWindowIsNotPeerDerivedTest` reads `ClaimAuthority.java` as text and fails if
`MAX_PEER_DELAY_ALLOWANCE` or any `me.nootnoot.sim.net` import reappears in it - the coupling, not just
today's number.

What the window must NOT cover, which is what the old constant was reaching for: rollback prediction
depth. Prediction error is a DIVERGENCE, not a time shift - the predicted opponent extrapolates FORWARD,
so when the victim stops or turns the prediction is ahead of truth and rewinding, which only ever moves
the victim further back, reproduces nothing.

So the numbers in the table are what the channel would be worth if it reopened today: 5 ticks at vanilla
sprint is 1.40 blocks, at sprint-jump 1.78. It is closed either way, and it was closed on the real
argument rather than the arithmetic: the edge could not MIRROR a client claim (a vanilla client sends no
such packet and `EdgeIntentListener` has no channel for one), but the mod was never reading it off the
client either. It swept its own live sim arrows out of replicated state BOTH hosts hold, so the edge
could COMPUTE the same claim all along, and now does through `Combat.projectileClaim`. Pinned by
`ProjectileClaimParityTest`.

Two consequences of the smaller window that you will meet elsewhere and should not mistake for
regressions. `CANDIDATE_CAPACITY` falls with it, from 51 hulls to 11, so per-claim work drops sharply -
melee candidate hulls tested across the harness scenario went from 60430 to 28319 and sight tests from
22807 to 13488. Ten `HarnessCoverageFloorTest` floors were lowered to match, deliberately, and nine of
the ten are directly this: fewer candidate hulls means fewer sight tests and fewer grants against a
rewound hull. The tenth, `miss-penalty-armed`, falls because refused attacks no longer reach `swingOnce`
and so no longer arm a fresh penalty. `attack-refused-by-miss-penalty` rose.

Eight more floors were lowered a revision later, for the same reason one step removed: with the miss
penalty's early clear gone, fewer attacks get through a whiff and fewer claims are attempted at all. The
current floors are `melee-candidate-hull-tested` 21285, `sight-test` 13371, `sight-blocked-by-arena`
10602, `melee-candidate-hull-in-reach` 12217, `melee-claim-granted-rewound-hull` 73,
`melee-claim-granted-outside-live-reach` 2, `miss-penalty-armed` 1274 and `event-swing` 9526. Lowering a
coverage floor is a real narrowing of what the digest sweeps, which is why the numbers are written down
here rather than only in the test file. `PlayerState.REWIND_FRAMES` stays at 25: the ring is checksummed
and frame-0 encoded, so shrinking it is a wire-format change, and the invariant the tests hold is
`WINDOW_FRAMES <= REWIND_FRAMES`.

**Spear maximum reach, 1.625 blocks.** The sim gives a spear a 4.625 pick and the mod raycast that
itself, while an unmodded player's claim is an INTERACT_ENTITY packet a vanilla client only sends inside
its 3.0 `entity_interaction_range`. The modded usable band was 2.625 blocks, the unmodded 1.0, so an
unmodded spear user had 38 percent of the reach. Closed by `EdgeStatusMirror.mirrorAttackRange`, which
writes the attribute from the replicated dict entry. The plain-weapon row is 3.0 exactly, so **only the
spear was ever affected**, and `crossPlayReachLedger` asserts both bands live against `ItemDict`.

**Spear minimum reach, 2.0 blocks, favouring the UNMODDED player.** A vanilla client applies the floor in
its own pick and just never sends the packet; the vanilla SERVER has no floor and `ClaimAuthority`
carried none, so any client that simply sent the packet kept the whole point-blank band.
`ClaimAuthority.meleeClaim` now carries the floor, measured on the aim point.

**Crystal pick, 1.5 blocks, favouring the unmodded player.** Different reach, different hitbox and
different METRIC on the two hosts, and the metric is what `HostFrameContract.leftClickTarget` arbitrates
crystal-versus-melee with. One shared `Combat.crystalPickDistanceSq` now.

**Anchor charge classification, temporal.** The mod read the charge off the LIVE CLIENT BLOCK, which its
own renderer paints from `head.anchors`, so it read a lagged picture of the map the edge read directly.
For the frames in between, one right click became BLOCK_CHARGE_ANCHOR on one host and
BLOCK_DETONATE_ANCHOR on the other, at up to 5 clicks per second.

**Every one of these is held at zero by a build-failing gate**, which is what makes the table worth
anything a month from now: `arbitratedChannels()` entries, banned tokens so EDGE may not name
`CRYSTAL_INFLATE` again and MOD may not name `RespawnAnchorBlock.CHARGE`, `armedArrows` or
`breakRefused`, and the reach ledger assertion.

### What they still decide separately

- **The melee half of the left-click tie-break. Narrowed, not closed. Small.** The edge passes
  `CLAIMED_DISTANCE_SQ` = 0 for a claimed melee, so on a frame claiming both it always resolves to MELEE
  while the mod compares two real distances. Each frame is authoritative, so this is not a desync; it is
  the same click meaning two things.
- **Use-click counting. Mildly favours the unmodded player.** The mod counts physical presses, the edge
  counts queued intents including vanilla's 4-tick auto-repeat. Raising the mod's count would raise its
  throw rate, so this wants a decision rather than a quiet fix.
- **The main-hand use bit with an EMPTY main hand. Favours the modded player. Not closed and not
  measured.**
- **An off-hand click lands a tick later on the edge.** Queue behaviour, costs the unmodded player one
  tick.
- **`McInputSource.firstFluidSourceAlongLook`** is the last world question a producer answers out of its
  own host world.
- **Two predicates for one question:** `EdgeHeldItems.interactsWithWorld` and
  `McInputSource.placesIntoWorld` agree for every kit that exists today; nothing mechanically proves
  they agree for an item nobody has added yet.
- **Authority. Edge only, and correct.**
- **Latency, which is not a divergence but is the biggest difference of all.** A modded client runs the
  sim locally at one tick of input delay and rolls back. An unmodded player's input does not exist until
  it has crossed the wire, and the world they see is the edge repainting the sim into a Bukkit world, so
  they pay their ping in both directions. No parity work removes this: it is what the mod is FOR. At
  30 ms it is a difference in feel. At 120 ms the two players are playing different games.

### Should mixed queues be enabled?

**Not yet, and the reasons have narrowed rather than changed.** Every quantified channel in the table is
zero, each held there by a gate that fails the build. On the five channels that table covers, mixed
queues are shippable, and that is a real change from two handovers ago when the arrow claim was an open,
one-sided, unmeasured advantage. Since then the cross-play path has also been driven live as far as a
headless box allows, a first-to-9 mixed match runs on a lossy loopback and another runs over a real
relay, both in CI.

Three things still argue against opening it.

One: phase 4 has not happened. A table of zeroes derived from source is a strong prior, not a result,
and there is no human baseline against which a mixed match could be called fair or unfair.

Two: the residual list above is not empty, and two of its items, the empty-main-hand use bit and the
melee half of the tie-break, are unmeasured rather than measured-small. "Looks small" is what was said
about several things that later turned out to be bugs 1 through 6.

Three: the latency item is not a bug that will be fixed. Even with every listed divergence closed, one
side is playing rollback and the other is playing client-server.

**The recommendation.** Run mod-vs-mod and edge-vs-edge as separate queues first; both are
self-consistent and both are worth shipping on their own. When a mixed queue is opened, open it on
**unranked only**, which is what DEPLOY.md Stage 4 already says and what `TESTPLAN.md` repeats to the
tester. There is no longer a principled reason to hold spear or bow types back specifically, since all
three rows that used to bite are closed. Open it narrowly anyway, watch the desync-abort counter, and
treat step 3 in section 2 as the thing that produces the evidence this section is still missing.

## 6. The four things most likely to waste your time

**One: a digest mismatch that is not a determinism bug.** It does not match today - the reference and the
tree are both at CHECKSUM_REV 170 - but it will the next time somebody changes simulated behaviour, and
it costs an hour if you take it for a determinism bug. It costs nothing if you read what the gate
printed: `:sim-core:test` fails the `HarnessDigestStabilityTest` cases that compare this run against the
committed reference, `:sim-core:checkHarnessDigest` fails, and the task itself names the two CHECKSUM_REV
numbers and the fix, `updateHarnessDigest`. Section 0. Only treat it as a real divergence if the
CHECKSUM_REV numbers in that message are the SAME and the digests still differ - that is the case where
something is genuinely non-deterministic. What is left after that:

**Two: a build failure the source does not explain.** Section 0 has the history and the correction:
this was blamed on extra Gradle daemons and it is not that, because it reproduced this refresh on one
daemon with nothing else running, at about one run in three. `gradlew --stop`, one invocation,
`--no-build-cache`, re-run once, and only then read code. Two of the three runs measured for this
document were red and neither red was a real fault - one was the `:verifyGatesRan` ordering defect, one
was a phantom `package ... does not exist`. Measure the tree more than once before you write a number
down.

The build cache is deliberately left ON for everything that is a pure function of its declared inputs,
and `CachedTestsDeclareWhatTheyFenceTest` asserts that nobody switches it off wholesale to paper over a
key bug. Test results are the exception and are now never restored at all. Section 6A is why, and it is
worth reading before you trust any green run.

**Three: a jar that is not what you think it is.** Four things must carry 17336 simultaneously: the mod
jar on each client, the edge plugin, the relay, and core's `RollbackModRegistry`. A skew aborts every
match at HELLO, and what the player sees is a duel that refuses to start. All four agree today, which is
the table in section 0; they stop agreeing the moment the rev moves and one half of the rebuild is
skipped. The subtler shape, and the one the tree is in right now, is a jar that agrees on 17336 and
is still not this tree's: a change that touches no simulated rule and no wire format leaves the fence
where it was, so the check passes and the jar is a build behind. The fence is a divergence test, not
a freshness test, and there is nothing in the build that tests freshness.

The other shape of this is the unremapped `devlibs` jar. It carries `fabric.mod.json` and the mixin
config, so a client WILL load it, and it nests no sim-core, so it dies on the first mapped name.
`verifyModJars` used to name one and then exit 0 - naming a loadable jar and reporting success is worse
than not looking, because it puts the file on the record as known and harmless. It now FAILS on one. It
is also no longer your job to clear it: the devlibs jar is `remapJar`'s own INPUT, so it cannot be swept
BEFORE the remap, but once the remap has read it the file is spent, and each version build now deletes
its own right there through `dropUnremappedIntermediates`. A version still never touches a sibling's,
because that directory is another project's task output and its build may be running. So a clean build
leaves the tree green, and a `NOT INSTALLABLE` line now means an interrupted build left one behind -
clear it with `gradlew clearDevlibs`. CI runs `verifyModJars` before it uploads the jars, so the
artifact upload cannot ship one alongside the real ones - **but only if the repository variable
`MOD_REPO` is set.** The `mod_jars` job in `.github/workflows/determinism.yml` is guarded by
`if: vars.MOD_REPO != ''` and the `gate` job emits a warning saying the mod was not built when it is
not. Whether that variable is set is a GitHub setting rather than a fact in this tree, so I could not
check it; treat the CI half of this as conditional and the local `verifyModJars` as the one you can
rely on. There is none in the tree today. **Install only from
`versions/<mc>/build/libs/`.**

For the dev stack there is a fifth thing to carry and it is not a version number: WHICH FILE the dev
edge loads. An edge can be badly stale without `Protocol.VERSION` moving at all, and Paper does not
load the jar you deployed - it loads a remapped copy of it under `plugins/.paper-remapped/`, keyed by
the pre-remap sha256 in that directory's `index.json`.

**Both halves of that were broken until this refresh, and this is the trap that has now cost two
sessions.** `devSetup` cleared the remap cache; `devStampEdgePlugin`, which is a finalizer of
`:edge:jar` and therefore the path EVERY ordinary build takes, did not. It overwrote
`plugins/jackpot-edge.jar` and left the cache alone. And `devVerifyEdgeDeploy` then hashed that same
freshly written jar and the stamp written beside it from the same variable, so on the exact state that
breaks a server both of its inputs matched and it could not fail. A verification that cannot fail is
worse than none.

What they do now:

- `devStampEdgePlugin` clears `plugins/.paper-remapped` before it writes the jar, on every build, and
  says so (`Paper remap cache cleared`). If the cache cannot be cleared it logs an error at the same
  volume as a failed copy rather than reporting a refresh, because the stamp it writes will make that
  server refuse to enable.
- `devVerifyEdgeDeploy` resolves what Paper will actually load: the jar under `plugins/`, **every other
  jar in that directory** (Paper loads all of them), and every `.jar` in `.paper-remapped` checked
  against the origin sha256 `index.json` records for it. A remapped copy of a different build fails.
  So does a remapped copy the index does not account for, because in that state `EdgeBuildFence` falls
  back to hashing the source jar and reads fresh while the server loads the stale copy.
- `devVerifyEdgeBoot` is new and is the one that reads the server rather than the disk. It starts each
  edge for real, requires its own build fence to name this build's sha256 and its own
  `protocolVersion` line to match this tree, then stops it.

Reproduced end to end for this refresh, with two genuinely different edge jars. Deploying the new one
the way the old code did - jar overwritten, cache untouched - made edge-a come up with **no plugin at
all** (`STALE EDGE PLUGIN - REFUSING TO ENABLE`, then `Disabling JackpotEdge`), which is the exact
symptom that cost the earlier session. On that state the old check's two inputs both equalled the new
build's hash. `devVerifyEdgeDeploy` now names the shadowing copy and `devVerifyEdgeBoot` fails with
`edge-a refused to enable its plugin`. After a `devSetup`, both pass and both edges boot clean.

One empirical fact that fell out of the reproduction and is worth keeping, because it is not
documented anywhere in Paper: **`.paper-remapped/index.json` is written AFTER plugins are enabled, not
before.** On the first boot following a jar change with a surviving cache, `EdgeBuildFence` therefore
reads the PREVIOUS build's origin hash out of the index and refuses a correct deployment. With the
cache now cleared on every deploy the index is simply absent at that first enable, the fence falls back
to hashing the jar next to it, and it is right. That is why clearing the cache is what makes the
runtime fence sound, rather than merely tidy.

The three checks are complementary and none replaces the others: the deploy makes the state right, the
disk check is fast enough to run on every `devSetup`, and the boot check is the only one that observes
a JVM.

**Four: judging any blast, fire or mining on the flat fallback arena, or testing a mechanic in a game
type that has it switched off.** Both are covered above (5b and section 3) and both are total and silent.
The anchor detonates, the crystal explodes, the player takes damage, and the world is untouched. The
worked example, checked against `EdgeGameTypes` and `EdgeDemoKits` rather than invented: a MACE,
SPEAR_MACE or SPEAR_ELYTRA duel is handed the whole CRYSTAL demo kit, efficiency V netherite pickaxe
included, and its rule row has `vanillaBuild` off, so that pickaxe mines nothing and no blast breaks
terrain all match. (The previous handover's example here, 256 end crystals in a POT duel, was wrong: a
POT duel is handed the POT kit, which is a diamond sword, splash healing and one golden apple, and has no
crystals in it at all.) If terrain will not break, check which arena you are on before checking anything
else; if a crystal does nothing, check whether you saw the disabled-items banner at match start.

## 6A. What a green build now guarantees

Until tonight, a green build could mean nothing had run. `gradlew build` on an already-built tree
finished in 4 seconds with `:sim-core:test UP-TO-DATE`, `:edge:test UP-TO-DATE`, `:limbo:test
UP-TO-DATE` and `:relay:test UP-TO-DATE`, and printed a pass. CI was worse than a laptop, because
`gradle/actions/setup-gradle` restores this repository's build cache between runs, so a hosted runner
did the same thing. Every "the tests pass" in every handover before this one was written against a
build that may or may not have executed a single test.

**What BUILD SUCCESSFUL means now:**

- Every test task in the graph actually executed in that invocation. Test results are declared
  non-restorable two ways, `outputs.cacheIf { false }` and `outputs.upToDateWhen { false }`, and both
  are needed: a cache lookup and an up-to-date check are separate gates, so a task that is only
  not-cacheable still gets skipped as UP-TO-DATE and one that is only not-up-to-date still comes back
  FROM-CACHE. Either one alone leaves the hole.
- That claim is proved independently of the policy, not just asserted by it. `build/gate-evidence` is
  emptied once per build from `gradle.taskGraph.whenReady`, before any task runs; each gate then drops
  a stamp there at the moment it really runs; a `Test` task stamps from `doFirst` as well as from
  `afterSuite`, so a task that ran and then FAILED still counts as having run; and `:verifyGatesRan`
  finalizes every test task and every `check`, reads the stamps and throws on any gate that left none.
  Undoing the policy turns the build red rather than green. That was checked by flipping it back
  deliberately and watching `:relay:test UP-TO-DATE` produce `:verifyGatesRan FAILED`.
- **Two defects in that mechanism were found and fixed while measuring the tree for this refresh, and
  both of them turned a green tree red rather than the other way round.** First, `:verifyGatesRan` was
  only a FINALIZER of the test tasks and nothing ORDERED it after them, so on a run where Gradle
  scheduled `:sim-host:check` early the check fired before `:edge:test` and `:limbo:test` had run and
  failed the build; it now carries a `mustRunAfter` for every task it finalizes. Second, the stamp
  carried a per-build random id and the comparison was against that id - and the id was observed taking
  MORE THAN ONE value inside a single build, so gates that had genuinely run were reported as having
  recorded nothing. I could not explain how one build produced two ids, so the id was removed instead
  of trusted: the directory is emptied once at graph-ready and a stamp's mere presence is the evidence.
  That is strictly stronger, because it cannot be satisfied by anything a previous build left behind.
  Neither defect could ever have produced a false GREEN.
- You are told, per gate, what happened: EXECUTED with test counts, FAILED, NOT REACHED because the
  build stopped earlier, NO TESTS, or DID NOT RUN with Gradle's own skip reason printed. That is the
  block quoted in section 0.
- The two always-run determinism gates are on the same ledger: `:sim-core:checkHarnessDigest` and
  `:verifyEmbeddedSimVersion`.
- In `pvphq-rollback-mod`, a green `verifyModJars` means no unremapped, client-loadable `devlibs` jar
  is left anywhere in the tree. It used to mean that too, except for the one case it logged and passed.
  It is now also a gate on the build sweeping after itself, because nothing clears devlibs by hand any
  more: `verifyModJars` run alone turns a regression in `dropUnremappedIntermediates` red. CI runs it
  that way only when the repository variable `MOD_REPO` is set - see section 6, item three - so on a
  run without it the mod is not built and not fenced at all.

**What green still does NOT mean**, and this list has not shrunk:

- No arm64 anything. Section 8.
- No `mcleagues-core` compile - the cross-repo fence downgrades to a warning when the sibling checkout
  is absent, so a green run may have compared nothing. `CI=true` or an explicit `-PcoreDir` makes it
  fatal, and CI has a separate `version_fence` job for it. The harness jobs themselves run with
  `-Drollback.versionFence=skip`.
- No Minecraft client was launched and no duel was played. Section 4, phase 4.
- Nothing about whether a rule is the RIGHT rule. A test pins the behaviour somebody wrote down; the
  vanilla sweep in section 1B is the record of how often that was wrong.

RUNBOOK.md section 7 carries the same list next to the commands.

## 7. What is genuinely not done

Ordered by what I would fix first.

**The auto-totem hole (`gui_inventory_ops_on_a_moving_frame`).** The largest open security item, with a
clean design already worked out, blocked on a decision rather than on engineering. See the table in
section 1B. It changes the meta, so it wants sign-off rather than appearing in a hardening pass.

**Crossbow piercing.** `ItemDict.piercing` is clamped, replicated and read by nothing. Self-contained and
a good next task: a pierce counter on `ProjectileState` and a shield bypass in `Combat.blocksProjectile`.

**The fifteen uncovered harness arms, and the eighteen lowered floors.** `HarnessCoverageFloorTest`,
listed in section 1A and explained in section 5. The determinism digest sees none of the fifteen, and
the digest is what catches an arm behaving differently on another machine. The mining-miss-penalty arm
came off this list without anyone writing a segment for it: removing the penalty's early clear made the
existing scenario reach it, and it now carries a floor of 67.

The min-reach one is harder, and the reason recorded in the previous handover was wrong in its numbers,
so here it is off the constants. `HarnessScenarios.ATTACKER_X` is 0.5 and `VICTIM_X` is 3.5, so the pair
open the scripted duel 3.0 blocks apart, and `spearScript` never closes that: it swaps the spear in on
its first tick and then swings from where it stands. The spear dead zone is
`ItemDict.SPEAR_ATTACK_MIN_RANGE` = 2.0, so nothing in that segment ever gets inside it and
`melee-claim-refused-inside-min-reach` is never reached. What the previous handover ALSO said - that a
closing stretch would not work because 25 frames of rewind grant on a rewound hull anyway - was written
when `WINDOW_FRAMES` was 25. It is 5 now, so that objection may simply have gone away and a closing
stretch may be all this needs. I did not test it. Treat "it needs a NEW segment in a wide part of the
arena" as the previous, larger estimate rather than as a finding.

**The vanilla mechanics still missing.** Thorns, ladders and climbing, lingering potions, haste and
mining fatigue, plus the deferred batch (explosion lift, arrow tick order, shield durability, attack
exhaustion on a refused swing, the fire and splash details). Section 1B has the table with what each
costs. The ladder row in that table used to be flagged here as the sharp edge, on the grounds that an
arena with a ladder would desync. It would not, and the table now says why: `DevPaletteGeometry` makes
ladders and vines `KIND_DECOR`, and both hosts read the same shipped palette. The real cost is fidelity,
not divergence - you fall where a player expects to climb - which moves it down this list, not off it.

**The host asymmetries that are still open.** Section 5 lists them with who each favours. Worth closing:
the empty-main-hand use bit, the melee half of the tie-break, and `firstFluidSourceAlongLook`. Worth a
decision rather than a fix: use-click counting.

**`updateHarnessDigest` cannot declare its own output.** The task writes into
`sim-core/src/main/resources`, which is an INPUT of `processResources`. The textbook fix,
`outputs.file(...)`, breaks the task outright, because `processResources` is a dependency of
`updateHarnessDigest` itself and Gradle refuses the graph. The safe fix is a classpath that excludes main
resources. Until then, run it ALONE, never chained with `build`.

**The shulker opening box is approximated as the cell above.** Vanilla tests the lid's swing box in the
box's FACING direction and the sim carries no facing for a placed shulker, so a side-facing box against a
wall is judged openable by both hosts where vanilla would refuse. Both hosts are wrong in the same
direction, which is the property that matters; making it exact is sim state and a CHECKSUM_REV move.

**`GameState.anchors` only holds anchors PLACED during the match**, so an arena-terrain respawn anchor
reads NO_ANCHOR on both hosts.

**`MatchSetupCodec` is a fourth writer of the eight-flag rule block**, because `mcleagues-core` has no
sim-core dependency and verifies the fence by parsing `Protocol.java` as text. It is pinned by a source
scan that compares its flag order with the record's components, so it cannot silently drift, but it is
still a copy.

**Two cross-repo fences pass by absence, on purpose.** `verifyGameTypeRules` here and
`verifyRollbackFence` in `mcleagues-core` both read the sibling checkout's SOURCE and downgrade to a
warning when it is not there, so a standalone clone can still build. The warning path is a real hole:
nothing has compared anything and the build is green. Both are fatal under `CI=true` and both take an
explicit path that makes absence fatal. Putting sim-core on core's compile classpath would retire the
text parsing in both directions, and the codec copy above with it.

**The edge build fence still only guards the dev stack.** The three dev-stack checks in section 6,
item three - cache-clearing deploy, resolved-target verification, real boot - are all `devenv.gradle`
tasks and none of them exists on a production edge. Only gradle writes
`plugins/JackpotEdge/expected-plugin.sha256`, so a real edge deployed by any other route starts unfenced
and logs one line saying so. Production skew is still covered only by `Protocol.VERSION`, which does not
move for most edge changes. What did change is that the fence now works at all on Paper; before the
session that fixed it, it refused every enable.

**The relay referee is wired, and has never run outside a unit test.** The previous refresh said it was
"built and wired to nothing", and that is no longer true: `mcleagues-core` sends
`ControlProtocol.AuthorizeSetup` (opcode 3) from `RollbackHandoffManager.startModHandoff` over an
HMAC-authenticated TCP link, `RelayServer.handle` tees every forwarded packet into the matching
`SessionReferee`, and `ControlEndpoint` ships a signed `Result` that `RefereeArbiter` treats as a third
witness outranking either client. The precedence rule is in DEPLOY.md 5.2a and the ten counters are in
RUNBOOK.md 2.6a. What is left is the part that matters most:

- **The producing half is exercised end to end; the consuming half is not.** `devRun` starts the dev
  relay with `RELAY_CONTROL_SECRET` (default `dev-referee-secret`, `-PrelayControlSecret` to change
  it, `-PnoReferee` to run the degraded shape) and hands both secrets to the edges, which send
  `AUTHORIZE_SETUP` at assignment pickup with tokens derived from `RELAY_SLOT_SECRET` and the very
  frame 0 they hand the host, so every TESTPLAN duel through the relay really is re-simulated and
  really does print a signed verdict. `devAssign` itself cannot authorize: it has no server, so
  `EdgeDevKit` (Bukkit `ItemStack`s) throws `NoClassDefFoundError` in that JVM. Both edges authorize
  the same session; `AddressHeaderIsNotFrameZeroTest` proves the per-slot address header never
  reaches the simulated state, and `LateAuthorizeDoesNotResetALiveRefereeTest` proves the relay keeps
  the first binding once frames have been judged. `RefereeSetupAuthorizeTest`
  now plays a relayed duel all the way to a signed DECIDED verdict rather than asserting the seam:
  slot 1 starts frame 0 on 0 health, both peers send input over real UDP, and the relay ships
  `decided=true, winnerSlot=0, 1-0` off its own re-simulation with nothing finalizing the session by
  hand. What the dev stack still cannot show is a verdict being *weighed*: the consumer is
  `mcleagues-core` and core is not in the dev stack, so the nine `RefereeArbiterTest` cases remain the
  only coverage of the precedence rule.
- **One bad authorize used to take the whole control link down with it.** `ControlEndpoint` decoded
  and registered inside the connection's read loop, so a single arena the relay could not rebuild
  threw out of the loop, closed the socket and took every other session's verdict channel with it -
  in exactly the failure mode where you most want the referee. Each frame is now isolated, the
  refusal is logged as `#### THE REFEREE COULD NOT TAKE SESSION <id> ####`, and the link stays up
  (`anAuthorizeTheRefereeCannotTakeDoesNotKillTheControlLink`).
- **The `rollback.referee.*` keys were missing from core's shipped `config.yml`**, exactly the way
  `edge.enabled` and `cross-play` were two refreshes ago. They are in it now at their real defaults, so
  an operator can see the switch exists. Note the default shape: `referee.enabled` is **true** and
  `referee.control-secret` is **empty**, which means a default deploy has no referee and says so at
  SEVERE on the first handoff (`#### THE RELAY REFEREE IS OFF ####`).
- **It covers the modded path and cross-play.** `finishCrossPlayHandoff` now authorizes the session
  with the mod slot's own setup bytes and the cross-play arena blob, `resolveResult` hands the mod's
  report on without wiping the verdict state (`forgetHostSession`), and `EdgeSessionBroker.evaluate`
  applies `RefereeArbiter` where both reports land. **Edge-vs-edge over a relay is the one path
  left**: `completeEdgeHandoff` never calls `authorizeReferee`, and it has the same two ingredients
  the other two paths use, so it is one call rather than a design problem. Defensible for now,
  because an edge is our own infrastructure rather than a player's machine, but it is still a hole in
  the sentence "the referee closes this" wherever that sentence appears above.
- **The referee and direct edge-to-edge links are mutually exclusive per match**, because the relay is
  the referee's only vantage point. A fleet running direct links is a fleet the referee cannot see.
- `ControlProtocol.Result.violation` is still always false on the relay side. `RefereeArbiter` honours
  it (it VOIDs), so the anti-tamper phases can start setting it without touching core.

The arena question the previous handover called the blocker is settled and was settled the wrong way
round: core does not ship a box list at all. With the referee on it ships
`ArenaBlocksCodec.encodeForSim` palette geometry, the relay rebuilds frame 0 with the canonical
`MatchSetupFrame0Decoder` and the arena with `ArenaCodec.toArena`, and the mod already prefers that same
branch. That is strictly more accurate than the client-derived full-cube approximation the mod fell back
to before - and it changes collision for modded duels, and **has not been playtested**. Without a
control secret the wire is byte-for-byte what it was.

### What the next session should start with

**Phase 4. Not more code, and this time not two commands either.**

**The tree needs one thing from you before you start: build the mod jars.** The harness reference has
since been re-recorded at 165 and is current, so the `updateHarnessDigest` line an earlier draft of
this block carried is gone and should not be put back. The mod jars are current on the fence now too,
so the third line below will pass whether or not you run the second - run the second anyway, because
the jars predate two fence-neutral source changes and nothing in this build will tell you so:

```
cd jackpot-rollback      && gradlew --stop && gradlew clean build --no-build-cache    (expect 0 failures)
cd ../pvphq-rollback-mod && gradlew 1.21.11:build 26.1.2:build 26.2:build
cd ../pvphq-rollback-mod && gradlew verifyModJars                                     (expect VERSION 17336 OK, six times)
```

Read the test count off the build's own `=== what this build actually verified ===` block rather than
off a number written here; the totals move whenever tests are added and the assertion that matters is
that every gate reports EXECUTED with zero failed. If anything else disagrees with section 0, somebody
has changed something since this was written and section 0 is the place to reconcile it.

Then open `TESTPLAN.md` and work its three configurations in order with two clients, a full match each,
in CRYSTAL, on a real arena. The dev stack reaches the join prompt, so the thing standing between this
project and its first played match is a person with two Minecraft accounts.

Bring back six measurements, because they are the ones this document cannot make for itself.

1. Whether a duel is playable at all.
2. Whether reach feels right in a normal kit, and in a spear kit specifically, for BOTH players now that
   the unmodded side is handed the sim's own attack range through an attribute.
3. **Whether the miss penalty feels like Minecraft.** Section 1A is new behaviour derived from the
   decompiled client rather than from anyone's memory of playing, and it changes combat feel for modded
   and unmodded players alike. A whiff followed by a held dig is locked out for ten ticks. If that reads
   as input lag rather than as vanilla, we got the model wrong. This is the row most in need of a human,
   because the model was tightened twice and the second tightening removed the release clear entirely:
   **nothing a player does pays the ten ticks off early any more.** Vanilla clears on the physical
   button coming up and this sim cannot see that button, only a bit the client chooses to set, so the
   clear had to go. It is a deliberate departure from vanilla, made for a fairness reason, and a player
   who habitually holds left click will feel it. Nobody has checked whether that reads as Minecraft or
   just as punishing.
4. **Whether combat still feels right after the vanilla sweep.** Section 1B changed sprint knockback,
   crit timing, breach, arrow damage falloff, wind burst and the mace rebound. Every one is closer to
   vanilla on paper. None has been felt, and several change numbers a practised player will notice
   immediately.
5. Whether an archer's arrows land the same way for both players now that both hosts compute the claim.
6. Whether an unmodded player can tell they are the unmodded one, which is the question section 5 exists
   to ask and cannot answer.

If a symptom does show up, check these four defect classes before reading any rule, because between them
they account for nearly every bug found in this project. Modded versus unmodded divergence: any rule
enforced on one host only is a live fairness hole, and rules belong in the sim. Input being eaten: five
distinct mechanisms have done it, including catch-up bursts deleting discrete actions, which is what bug
4 turned out to be, and the miss penalty is a legitimate sixth. Frame-space confusion: sample tick versus
head versus landing frame. And "the fix only runs on the happy path": a restore reachable by one teardown
route, a gate nothing ever opens.

## 8. The arm64 determinism gap

**What is proven.** Windows and Linux produce bit-identical results. CI runs the full harness on
`ubuntu-latest` and `windows-latest`, both amd64, JDK 21 Temurin, and compares the complete per-tick
checksum streams byte for byte across all 11740 ticks, including the rollback pass. All three digests
agree: stream, arena hash and rollback. Each job asserts that its stream header really reports the OS and
architecture it claims, so a misconfigured runner cannot quietly turn the comparison into a
self-comparison. That is a real result and it is all the workflow claims.

**What is not proven.** That any two CPU ARCHITECTURES agree. Both runners are x64. No aarch64 machine
has ever run this harness. The `harness_arm64` and `cross_architecture` jobs are written, correct and
skipped, gated on a repository variable `ARM64_RUNNER`; set it to an aarch64 runner label and they become
real gates, including a check that the runner is genuinely aarch64. The `gate` job writes into every run
summary which axes were proven, so read that rather than the badge.

The risk is specific rather than vague. Java is bit-exact for IEEE arithmetic, so ordinary float and
double work is safe by specification. The exposure is `java.lang.Math` transcendentals, which are
permitted a 1-2 ulp licence and are backed by platform intrinsics that differ between architectures. The
sim uses `StrictMath` and `MathTables` throughout and a bytecode gate bans the rest, which is why the
risk is believed low. Believed is not proven, and the harness running on x64 twice cannot raise it.

**The deployment constraint that follows, and it is not optional.** Every edge server in the fleet must be
pinned to ONE CPU architecture. Two edges on different architectures could desync every match between
their players, and nothing in the codebase would tell you why: the symptom is a desync abort in
production with two builds that both pass CI. Pin the fleet, or get an arm64 runner and close the gate.
Do not do neither.
