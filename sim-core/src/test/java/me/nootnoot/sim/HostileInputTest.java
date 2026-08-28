package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.net.InputCodec;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;
import org.junit.jupiter.api.Test;

class HostileInputTest {
    private static final double GROUND_Y = 64.0;

    private static GameState faceOff(double gap) {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        a.x = 0;
        a.y = GROUND_Y;
        a.z = 0;
        a.yaw = -90f;
        a.onGround = true;
        a.vy = -0.0784;
        a.health = 20f;
        a.attackTicker = 100;

        PlayerState v = g.players[1];
        v.x = gap;
        v.y = GROUND_Y;
        v.z = 0;
        v.yaw = 90f;
        v.onGround = true;
        v.vy = -0.0784;
        v.health = 20f;
        v.attackTicker = 100;
        return g;
    }

    private static Input claimedHit() {
        return new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                .withMeleeHit(true);
    }

    @Test
    void meleeHitFromAcrossTheMapIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(60.0);
        float before = s.players[1].health;

        for (int i = 0; i < 20; i++) {
            Simulation.tick(s, arena, claimedHit(), Input.NONE);
        }

        assertEquals(before, s.players[1].health, 0f, "a hit claimed from 60 blocks must not land");
    }

    @Test
    void meleeHitAtHonestRangeStillLands() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);
        float before = s.players[1].health;

        Simulation.tick(s, arena, claimedHit(), Input.NONE);

        assertTrue(s.players[1].health < before, "a normal in-reach swing must still connect");
    }

    private static void idleAt(GameState s, Arena arena, double victimX) {
        s.players[1].x = victimX;
        Simulation.tick(s, arena, Input.NONE, Input.NONE);
    }

    @Test
    void rewindWindowIsTheProtocolsOwnPredictionBound() {
        assertEquals(Protocol.MAX_PEER_DELAY_ALLOWANCE + ClaimAuthority.INPUT_DELAY_FRAMES,
                ClaimAuthority.WINDOW_FRAMES,
                "the rewind window covers the frames between the attacker SAMPLING the claim and"
                        + " the frame it resolves on: their input delay, whose upper bound the"
                        + " protocol fixes at MAX_PEER_DELAY_ALLOWANCE, plus the one frame between"
                        + " ClaimAuthority.record and the resolve that reads it."
                        + " RollbackController.PREDICTION_DECAY_FRAMES used to be added on top,"
                        + " and it does not belong: it is how long NetSession repeats a PREDICTED"
                        + " input before decaying it to gestureOnly, which says nothing about how"
                        + " stale the attacker's view of the victim can honestly be. Folding it in"
                        + " multiplied the window by five and handed every attacker a 1.25s trail"
                        + " of the victim's past positions, any one of which satisfies a claim");
        assertTrue(ClaimAuthority.WINDOW_FRAMES <= PlayerState.REWIND_FRAMES,
                "the window may never read deeper than the history that is recorded");
    }

    @Test
    void maxReachHitAtHighLatencyStillLands() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(3.3);
        float before = s.players[1].health;

        for (int i = 0; i < ClaimAuthority.WINDOW_FRAMES - 2; i++) {
            idleAt(s, arena, 3.3);
        }
        s.players[1].x = 9.0;

        Simulation.tick(s, arena, claimedHit(), Input.NONE);

        assertTrue(s.players[1].health < before,
                "a max-reach swing from the far edge of the rewind window must still land");
    }

    @Test
    void hitAgainstAPositionThreeFramesAgoStillLands() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);
        float before = s.players[1].health;

        idleAt(s, arena, 2.0);
        idleAt(s, arena, 2.0);
        idleAt(s, arena, 2.0);
        s.players[1].x = 12.0;

        Simulation.tick(s, arena, claimedHit(), Input.NONE);

        assertTrue(s.players[1].health < before,
                "a hit on where the victim stood three frames ago must be honoured");
    }

    @Test
    void hitAgainstAPositionNeverOccupiedIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(7.0);
        float before = s.players[1].health;

        for (int i = 0; i < 10; i++) {
            idleAt(s, arena, 7.0);
        }

        Simulation.tick(s, arena, claimedHit(), Input.NONE);

        assertEquals(before, s.players[1].health, 0f,
                "the victim stood still 6.7 blocks from the eye for the whole window, which is past"
                        + " the 3.0 + 0.0 + 3.0 handleAttack bound, so no candidate is claimable");
    }

    @Test
    void aHitOlderThanTheRewindWindowIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);

        idleAt(s, arena, 2.0);
        float before = s.players[1].health;
        for (int i = 0; i < ClaimAuthority.WINDOW_FRAMES + 2; i++) {
            idleAt(s, arena, 30.0);
        }

        Simulation.tick(s, arena, claimedHit(), Input.NONE);

        assertEquals(before, s.players[1].health, 0f,
                "the window must expire: a swing cannot reach back past the prediction bound");
    }

    @Test
    void arrowHitFromAcrossTheMapIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);
        float before = s.players[1].health;

        s.projectiles.add(arrowAt(s, 40.0, GROUND_Y + 1.0, 0.0));
        Simulation.tick(s, arena, claimedArrowHit(), Input.NONE);

        assertEquals(before, s.players[1].health, 1.0E-6,
                "an arrow nowhere near the victim must not be allowed to claim a hit");
    }

    @Test
    void anHonestArrowHitStillLands() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);
        float before = s.players[1].health;

        s.projectiles.add(arrowAt(s, 2.0, GROUND_Y + 1.0, 0.0));
        Simulation.tick(s, arena, claimedArrowHit(), Input.NONE);

        assertTrue(s.players[1].health < before, "the bound must not refuse an honest arrow hit");
    }

    @Test
    void arrowHitAgainstAPositionThreeFramesAgoStillLands() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);
        float before = s.players[1].health;

        idleAt(s, arena, 2.0);
        idleAt(s, arena, 2.0);
        idleAt(s, arena, 2.0);
        s.players[1].x = 12.0;

        s.projectiles.add(arrowAt(s, 2.0, GROUND_Y + 1.0, 0.0));
        Simulation.tick(s, arena, claimedArrowHit(), Input.NONE);

        assertTrue(s.players[1].health < before,
                "an arrow that crossed where the victim stood three frames ago must still land");
    }

    @Test
    void arrowHitAgainstAPositionNeverOccupiedIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);
        float before = s.players[1].health;

        idleAt(s, arena, 2.0);
        idleAt(s, arena, 2.0);
        idleAt(s, arena, 2.0);
        s.players[1].x = 12.0;

        s.projectiles.add(arrowAt(s, 6.0, GROUND_Y + 1.0, 0.0));
        Simulation.tick(s, arena, claimedArrowHit(), Input.NONE);

        assertEquals(before, s.players[1].health, 1.0E-6,
                "an arrow may only claim a position the victim actually occupied");
    }


    private static TestKit.Item sword(float damage) {
        return TestKit.item().itemId(2001).melee(damage, 1.6f).flags(ItemDict.FLAG_SWORD);
    }

    private static float dealtBySingleSwing(GameState s, Arena arena) {
        float before = s.players[1].health;
        Simulation.tick(s, arena, claimedHit(), Input.NONE);
        return before - s.players[1].health;
    }

    @Test
    void sharpnessComesFromTheTableAndIsCappedThere() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState plain = faceOff(2.0);
        TestKit.of(plain).give(0, 0, 1, sword(8f));

        GameState forged = faceOff(2.0);
        TestKit.of(forged).give(0, 0, 1, sword(8f).weapon(31, 0, 0, 0));

        assertEquals(ItemDict.MAX_SHARPNESS, forged.dict.sharpness(1),
                "a claimed Sharpness level must be clamped in the dictionary itself");

        float plainDealt = dealtBySingleSwing(plain, arena);
        float forgedDealt = dealtBySingleSwing(forged, arena);

        assertEquals(8.0f, plainDealt, 1.0E-3f, "an unenchanted swing must deal exactly its table damage");
        assertEquals(3.0f, forgedDealt - plainDealt, 1.0E-3f,
                "Sharpness 99 must buy no more than Sharpness 5 is worth");
    }

    @Test
    void aForgedPowerLevelCannotProduceAOneShotArrow() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(60.0);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1, TestKit.item().itemId(2002).useKind(Combat.USE_BOW)
                .flags(ItemDict.FLAG_BOW).ranged(7, 0, 0, false, 0));
        kit.give(0, 1, 8, TestKit.item().itemId(2003).flags(ItemDict.FLAG_ARROW_PLAIN));

        assertEquals(ItemDict.MAX_POWER, s.dict.bowPower(1),
                "a claimed Power level must be clamped in the dictionary itself");

        Input draw = new Input(false, false, false, false, false, false, false, false, true,
                -90f, 0f, 0);
        for (int i = 0; i < 24; i++) {
            Simulation.tick(s, arena, draw, Input.NONE);
        }
        Simulation.tick(s, arena, Input.NONE, Input.NONE);

        ProjectileState arrow = null;
        for (ProjectileState p : s.projectiles) {
            if (p.type == ProjectileState.TYPE_ARROW) {
                arrow = p;
            }
        }
        assertTrue(arrow != null, "a full-draw release must produce an arrow");
        assertEquals(Combat.arrowBaseDamage(ItemDict.MAX_POWER), arrow.damage, 1.0E-3f,
                "arrow damage must be the table's Power 5 value, not a client-supplied one");
        assertTrue(Projectiles.arrowImpactDamage(s, arrow) <= 26,
                "AbstractArrow.onHitEntity ceils deltaMovement.length() * baseDamage and then adds"
                        + " at most damage / 2 + 1 for a crit arrow, so even a forged Power level"
                        + " that survived the dictionary clamp cannot one-shot a full-health"
                        + " player through the table");
    }

    @Test
    void aForgedBreachLevelCannotStripArmourEntirely() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1, TestKit.item().itemId(2004).melee(8f, 1.6f)
                .mace(true, 0, 0, 15));
        for (int slot = ItemDict.ARMOR_FEET; slot <= ItemDict.ARMOR_HEAD; slot++) {
            kit.give(1, slot, 1, TestKit.item().itemId(2100 + slot)
                    .armor(8, 3f, 0f, slot - ItemDict.ARMOR_FEET + 1));
        }

        assertEquals(ItemDict.MAX_BREACH, s.dict.breach(1),
                "a claimed Breach level must be clamped in the dictionary itself");

        float dealt = dealtBySingleSwing(s, arena);

        assertEquals(6.4f, dealt, 1.0E-2f,
                "Breach is an armor_effectiveness effect: CombatRules.getDamageAfterAbsorb"
                        + " subtracts 0.15 per level from realArmor / 25 and clamps that fraction"
                        + " to [0, 1], so it cannot strip more than the whole armour fraction");
        assertTrue(dealt < 8.0f, "armour must still absorb part of a Breach hit");
    }

    @Test
    void offhandSplashPotionsRunOut() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(60.0);
        TestKit.of(s).give(0, ItemDict.OFF_HAND, 2, TestKit.item().itemId(2005)
                .useKind(Combat.USE_SPLASH_POTION));

        Input throwOffhand = Input.NONE.withOffhandUse(true);
        for (int i = 0; i < 80; i++) {
            Simulation.tick(s, arena, throwOffhand, Input.NONE);
        }

        assertEquals(2, s.players[0].potionsThrown,
                "an off-hand stack of two must yield exactly two splash potions");
        assertEquals(0, s.players[0].slotCount[ItemDict.OFF_HAND],
                "the off-hand slot must actually be emptied by throwing");
    }

    @Test
    void crystalsCannotBePlacedFromAnEmptySlot() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(60.0);
        s.players[0].x = 2.5;
        s.players[0].z = 2.5;

        for (int i = 0; i < 20; i++) {
            Simulation.tick(s, arena,
                    Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 2, 64, 0), Input.NONE);
        }
        assertTrue(s.crystals.isEmpty(), "an empty hand cannot place an end crystal");

        TestKit.of(s).give(0, 0, 2, TestKit.item().itemId(2006)
                .flags(ItemDict.FLAG_END_CRYSTAL));
        s.obsidianItemId = 2005;
        for (int x = 2; x <= 4; x++) {
            s.blocks.place(x, 64, 0, 2005);
        }
        for (int i = 0; i < 20; i++) {
            Simulation.tick(s, arena,
                    Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 2 + i % 3, 64, 0),
                    Input.NONE);
        }
        assertEquals(2, s.crystals.size(), "a stack of two crystals must place exactly two");
        assertEquals(0, s.players[0].slotCount[0], "placing a crystal must decrement the stack");
    }

    @Test
    void aHardBlockTakesTheTablesTimeNoMatterWhatTheClientWants() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.players[0].x = 2.5;
        s.players[0].y = GROUND_Y;
        s.players[0].z = 2.5;
        s.players[0].onGround = true;
        me.nootnoot.sim.state.BlockProps.Builder props = new me.nootnoot.sim.state.BlockProps.Builder();
        props.add(77, 50f, 1200f, 77, -1, ItemDict.TOOL_NONE, false);
        s.blockProps = props.build();
        s.blocks.place(2, 65, 2, 77);

        for (int i = 0; i < 300; i++) {
            Simulation.tick(s, arena,
                    Input.NONE.withBlockAction(Input.BLOCK_BREAK, 2, 65, 2), Input.NONE);
        }

        assertTrue(s.blocks.contains(2, 65, 2),
                "a hardness-50 block must obey the block table, not a claimed mine rate");
    }

    @Test
    void aStrongerOpponentKitDoesNotRaiseThisPlayersCeiling() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1, sword(2f));
        kit.give(1, 0, 1, TestKit.item().itemId(2007).melee(20f, 4f).flags(ItemDict.FLAG_SWORD));
        LoadoutCaps.seal(s);

        assertEquals(2f, s.players[0].attackDamage, 0f,
                "the weaker kit must keep its own ceiling");
        assertEquals(20f, s.players[1].attackDamage, 0f);
        assertEquals(2f, dealtBySingleSwing(s, arena), 1.0E-3f,
                "the swing must land for this player's own table damage");

        for (java.lang.reflect.Field f : PlayerState.class.getDeclaredFields()) {
            assertFalse(f.getName().startsWith("cap"),
                    "a per-player cap field is exactly what let one kit raise the other: " + f.getName());
        }
    }

    private static me.nootnoot.sim.state.ProjectileState arrowAt(GameState s, double x, double y, double z) {
        me.nootnoot.sim.state.ProjectileState p = new me.nootnoot.sim.state.ProjectileState();
        p.id = 1;
        p.type = me.nootnoot.sim.state.ProjectileState.TYPE_ARROW;
        p.owner = 0;
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = 1.0;
        p.damage = 6.0f;
        p.fresh = false;
        return p;
    }

    private static Input claimedArrowHit() {
        return Input.NONE.withProjectileHit(1);
    }

    @Test
    void blockActionsOutOfReachAreRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);

        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_PLACE, 900, 70, 900), Input.NONE);

        assertFalse(s.blocks.contains(900, 70, 900), "a client cannot build across the arena");
    }

    @Test
    void mineSpeedIsNotOnTheWireAtAll() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.players[0].x = 2.5;
        s.players[0].y = GROUND_Y;
        s.players[0].z = 2.5;
        s.blocks.place(2, 65, 2, 1);

        for (int i = 0; i < 4; i++) {
            Simulation.tick(s, arena,
                    Input.NONE.withBlockAction(Input.BLOCK_BREAK, 2, 65, 2), Input.NONE);
        }

        assertTrue(s.blocks.contains(2, 65, 2),
                "a bare hand cannot break a block in four ticks once the rate comes from the table");
    }

    @Test
    void nonFiniteWireFloatsNeverReachTheSim() {
        Input poisoned = new Input(false, false, false, false, false, false, false, false, false,
                Float.NaN, Float.POSITIVE_INFINITY, 0);
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, poisoned);
        b.flip();

        Input decoded = InputCodec.read(b);

        assertTrue(Float.isFinite(decoded.yaw()), "NaN yaw would poison every position it touches");
        assertTrue(Float.isFinite(decoded.pitch()));
    }

    @Test
    void anAuthorityPositionTheArenaCouldNeverHoldIsBoundedNotJustCheckedForNaN() {
        Input absurd = Input.NONE.withAuthority(Authority.at(1.0e300, -9.0e18, 4.5e120, true));

        assertEquals(Input.MAX_WORLD_COORD, absurd.authority().x(),
                "finite is not the same as reachable: 1e300 survives an isFinite check and then"
                        + " every distance, every AABB and every block key derived from it"
                        + " overflows into a different answer on the two peers");
        assertEquals(-Input.MAX_WORLD_COORD, absurd.authority().y());
        assertEquals(Input.MAX_WORLD_COORD, absurd.authority().z());
        assertTrue(absurd.authority().onGround(), "the bound clamps the position, not the flag");
    }

    @Test
    void theBoundSurvivesTheWireInBothDirections() {
        Input absurd = Input.NONE.withAuthority(
                Authority.at(Double.MAX_VALUE, Double.NaN, -Double.MAX_VALUE, false));
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, absurd);
        b.flip();
        Input decoded = InputCodec.read(b);

        assertEquals(Input.MAX_WORLD_COORD, decoded.authority().x());
        assertEquals(0.0, decoded.authority().y(), "NaN still lands on zero");
        assertEquals(-Input.MAX_WORLD_COORD, decoded.authority().z());
        assertEquals(InputCodec.BYTES, b.position(), "and the frame is still the same width");
    }

    @Test
    void aPositionInsideTheArenaIsCarriedThroughUntouched() {
        Authority honest = Authority.at(128.5, 64.0, -73.25, true);
        Input in = Input.NONE.withAuthority(honest);
        assertEquals(honest, in.authority(), "the bound may not round a legal position");

        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, in);
        b.flip();
        assertEquals(honest, InputCodec.read(b).authority());
    }

    @Test
    void anAbsurdBaseFrameIsRefusedInsteadOfAllocated() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        RollbackController controller = new RollbackController(arena, 0, s, 512);

        controller.onRemoteInput(100_000_000, Input.NONE);
        controller.onRemoteInput(Integer.MAX_VALUE, Input.NONE);
        controller.onRemoteInput(-1, Input.NONE);

        assertEquals(0, controller.head(), "no frame should have been accepted");
    }

    @Test
    void refereeIgnoresFramesFarBeyondItsConfirmedPoint() {
        Arena arena = Arena.flat(GROUND_Y);
        RefereeSession referee = new RefereeSession(arena, HarnessScenarios.duel(arena));

        referee.ingest(0, Integer.MAX_VALUE - 8, List.of(Input.NONE));
        referee.ingest(1, 100_000_000, List.of(Input.NONE));

        assertEquals(0, referee.confirmedFrame(), "a forged index must not grow the referee's buffers");
    }

    private static final int RATE_PROBE_TICKS = 40;

    private static int cooldownRateCeiling(int ticks) {
        return ticks / Combat.USE_REPEAT_DELAY + 1;
    }

    @Test
    void anOffHandUseBitHeldEveryTickCannotOutrunTheHeldUseCadence() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(40.0);
        TestKit.of(s).give(0, ItemDict.OFF_HAND, 64,
                TestKit.item().itemId(4101).useKind(Combat.USE_SNOWBALL));

        Input held = Input.NONE.withOffhandUse(true);
        for (int i = 0; i < RATE_PROBE_TICKS; i++) {
            Simulation.tick(s, arena, held, Input.NONE);
        }

        assertTrue(s.nextProjectileId > 0, "an honest off-hand press must still throw");
        assertTrue(s.nextProjectileId <= cooldownRateCeiling(RATE_PROBE_TICKS),
                "the off-hand rising edge must be read from the off-hand bit, not the main-hand one: "
                        + s.nextProjectileId + " throws in " + RATE_PROBE_TICKS + " ticks");
    }

    @Test
    void aUsePressBitAssertedEveryTickThrowsAtMostOneBottlePerTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(40.0);
        TestKit.of(s).give(0, 0, 64,
                TestKit.item().itemId(4102).useKind(Combat.USE_XP_BOTTLE));

        Input pressed = Input.NONE.withUsePress(true);
        for (int i = 0; i < RATE_PROBE_TICKS; i++) {
            Simulation.tick(s, arena, pressed, Input.NONE);
        }

        assertEquals(0, Combat.XP_BOTTLE_COOLDOWN_TICKS,
                "Items.EXPERIENCE_BOTTLE ships no useCooldown component, so the right click repeat"
                        + " delay is the only thing left pacing a bottle");
        assertTrue(s.nextProjectileId <= cooldownRateCeiling(RATE_PROBE_TICKS),
                "a press bit pinned high every tick may not outrun the repeat delay: "
                        + s.nextProjectileId + " throws in " + RATE_PROBE_TICKS + " ticks");
    }

    @Test
    void aUsePressBitAssertedEveryTickStillCannotOutrunTheVanillaPearlCooldown() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(40.0);
        TestKit.of(s).give(0, 0, 64,
                TestKit.item().itemId(4103).useKind(Combat.USE_PEARL));

        Input pressed = Input.NONE.withUsePress(true);
        for (int i = 0; i < RATE_PROBE_TICKS; i++) {
            Simulation.tick(s, arena, pressed, Input.NONE);
        }

        assertTrue(s.nextProjectileId <= RATE_PROBE_TICKS / Combat.PEARL_COOLDOWN_TICKS + 1,
                "ender_pearl carries useCooldown(1.0F), so the pressed path is still gated at 20"
                        + " ticks: " + s.nextProjectileId + " throws in " + RATE_PROBE_TICKS + " ticks");
    }

    @Test
    void anHonestXpBottlePressStillThrowsOnce() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(40.0);
        TestKit.of(s).give(0, 0, 64,
                TestKit.item().itemId(4104).useKind(Combat.USE_XP_BOTTLE));

        Simulation.tick(s, arena, Input.NONE.withUsePress(true), Input.NONE);

        assertEquals(1, s.nextProjectileId, "a single honest press must still throw exactly one bottle");
    }

    @Test
    void anAuthorityStampNoLegalMoveCouldReachIsIgnoredInsteadOfTeleporting() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(40.0);
        s.edgeHosted[0] = true;
        PlayerState a = s.players[0];
        double startX = a.x;
        double startZ = a.z;

        double reach = Simulation.MAX_AUTHORITY_STEP * 40.0;
        Simulation.tick(s, arena,
                Input.NONE.withAuthority(Authority.at(a.x + reach, a.y, a.z, true)), Input.NONE);

        assertEquals(startX, a.x, 1.0E-9,
                "the stamp is written straight into position AND velocity, so an unbounded one is"
                        + " a free teleport and a free launch; a stamp this far out is not"
                        + " something a legal move produced, so the sim integrates instead");
        assertEquals(startZ, a.z, 1.0E-9, "and nothing leaks onto the other axes either");
        assertEquals(0.0, a.vx, 1.0E-9, "the refused displacement must not become velocity");
    }

    @Test
    void anAuthorityStampInsideTheStepBoundStillPinsThePlayer() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(40.0);
        s.edgeHosted[0] = true;
        PlayerState a = s.players[0];
        double target = a.x + 0.25;

        Simulation.tick(s, arena,
                Input.NONE.withAuthority(Authority.at(target, a.y, a.z, true)), Input.NONE);

        assertEquals(target, a.x, 1.0E-9,
                "the bound may not cost an honest edge sample its authority");
    }

    @Test
    void anAuthorityStampCarryingNotANumberIsIgnoredRatherThanPoisoningTheChecksum() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(40.0);
        s.edgeHosted[0] = true;
        PlayerState a = s.players[0];
        double startX = a.x;

        Simulation.tick(s, arena,
                Input.NONE.withAuthority(Authority.at(Double.NaN, Double.NaN, Double.NaN, true)),
                Input.NONE);

        assertEquals(startX, a.x, 1.0E-9,
                "one NaN written into a position spreads through every later tick and both peers"
                        + " then disagree with a checksum that can never match again");
    }
}
