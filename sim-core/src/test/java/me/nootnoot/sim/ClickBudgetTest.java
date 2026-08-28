package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.ClickBudget;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ClickBudgetTest {
    private static final double GROUND_Y = 64.0;

    private static final int STACK = 64;

    private static final int ANCHOR_ITEM_ID = 5100;
    private static final int GLOWSTONE_ITEM_ID = 5101;
    private static final int OBSIDIAN_ITEM_ID = 5102;
    private static final int CRYSTAL_ITEM_ID = 5103;
    private static final int BLOCK_ITEM_ID = 5104;
    private static final int SUPPORT_ITEM_ID = 5105;
    private static final int SNOWBALL_ITEM_ID = 5106;
    private static final int RUBBLE_ITEM_ID = 5107;
    private static final int SPARE_ITEM_ID = 5108;
    private static final int SLOW_ITEM_ID = 5109;

    private static final int ANCHOR_X = 100;
    private static final int ANCHOR_Y = 100;
    private static final int ANCHOR_Z = 100;

    private static final float OBSIDIAN_RESISTANCE = 1200f;

    private static GameState duel(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        s.players[1].x = 40.0;
        PlayerState a = s.players[0];
        a.maxHealth = 20000f;
        a.health = 20000f;
        return s;
    }

    private static void standBy(GameState s, int x, int y, int z) {
        s.players[0].x = x + 0.5;
        s.players[0].y = y;
        s.players[0].z = z + 2.5;
    }

    private static Clicks counts(int attack, int use, int drop, int inv, int swap) {
        return new Clicks(attack, use, drop, inv, swap);
    }

    private static int swings(GameState s, int idx) {
        int n = 0;
        for (CombatEvent e : s.events) {
            if (e.type() == CombatEvent.SWING && e.attacker() == idx
                    && e.kind() == CombatEvent.HIT_WEAK) {
                n++;
            }
        }
        return n;
    }

    private static int handActions(GameState s, int idx) {
        int n = 0;
        for (CombatEvent e : s.events) {
            if (e.type() == CombatEvent.SWING && e.attacker() == idx && e.kind() == 1) {
                n++;
            }
        }
        return n;
    }

    private static int explosions(GameState s) {
        int n = 0;
        for (CombatEvent e : s.events) {
            if (e.type() == CombatEvent.EXPLOSION) {
                n++;
            }
        }
        return n;
    }

    private static Input attackLevel(boolean down) {
        return new Input(false, false, false, false, false, false, false, down, false, 0f, 0f, 0);
    }

    private static Input useLevel(boolean down) {
        return new Input(false, false, false, false, false, false, false, false, down, 0f, 0f, 0)
                .withUsePress(down);
    }

    private static GameState anchorScene(Arena arena) {
        GameState s = duel(arena);
        standBy(s, ANCHOR_X, ANCHOR_Y, ANCHOR_Z);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1, TestKit.item().itemId(ANCHOR_ITEM_ID).flags(ItemDict.FLAG_RESPAWN_ANCHOR));
        kit.give(0, 1, STACK, TestKit.item().itemId(GLOWSTONE_ITEM_ID).flags(ItemDict.FLAG_GLOWSTONE));
        s.blocks.place(ANCHOR_X, ANCHOR_Y, ANCHOR_Z, ANCHOR_ITEM_ID);
        s.anchors.put(BlockStore.key(ANCHOR_X, ANCHOR_Y, ANCHOR_Z), 0);
        return s;
    }

    private static Input chargeAnchor(int useClicks) {
        return useLevel(true).withHeldSlot(1)
                .withBlockAction(Input.BLOCK_CHARGE_ANCHOR, ANCHOR_X, ANCHOR_Y, ANCHOR_Z)
                .withClicks(counts(0, useClicks, 0, 0, 0));
    }

    private static int anchorCharge(GameState s) {
        Integer charge = s.anchors.get(BlockStore.key(ANCHOR_X, ANCHOR_Y, ANCHOR_Z));
        return charge == null ? -1 : charge;
    }

    @Test
    void toppingAnAnchorUpNeverFallsThroughIntoTheBlast() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = anchorScene(arena);

        for (int i = 0; i < Combat.ANCHOR_MAX_CHARGE; i++) {
            Simulation.tick(s, arena, chargeAnchor(1), Input.NONE);
        }
        Simulation.tick(s, arena, chargeAnchor(1), Input.NONE);

        assertEquals(Combat.ANCHOR_MAX_CHARGE, anchorCharge(s),
                "four right clicks fill a four charge anchor and the fifth finds it full");
        assertTrue(s.blocks.contains(ANCHOR_X, ANCHOR_Y, ANCHOR_Z),
                "charging and detonating used to be two arms of one branch, so the click that"
                        + " topped the anchor up fell straight through into the blast");
        assertEquals(0, explosions(s), "no click in these frames asked for a detonation");
        assertEquals(STACK - Combat.ANCHOR_MAX_CHARGE, s.players[0].slotCount[1],
                "one glowstone per charge, and the click past the fourth pays for nothing");
    }

    @Test
    void everyAnchorChargeSpendsItsOwnFrame() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = anchorScene(arena);

        Simulation.tick(s, arena, chargeAnchor(Clicks.MAX), Input.NONE);

        assertEquals(Combat.BLOCK_USE_CLICKS, anchorCharge(s),
                "a frame that names ONE cell acts on it once, however many presses ride it."
                        + " Combat.blockUseClicks is the definition and Combat.handleBlockAction"
                        + " now obeys it, so a burst of right clicks cannot charge an anchor from"
                        + " empty to full inside a single tick");
        assertEquals(STACK - Combat.BLOCK_USE_CLICKS, s.players[0].slotCount[1],
                "and the glowstone the refused presses would have eaten stays in the slot");
    }

    @Test
    void detonatingAnAnchorIsItsOwnActionAndItsOwnClick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = anchorScene(arena);
        Simulation.tick(s, arena, chargeAnchor(Clicks.MAX), Input.NONE);

        Input detonate = useLevel(true)
                .withBlockAction(Input.BLOCK_DETONATE_ANCHOR, ANCHOR_X, ANCHOR_Y, ANCHOR_Z)
                .withClicks(counts(0, 1, 0, 0, 0));
        Simulation.tick(s, arena, detonate, Input.NONE);

        assertFalse(s.blocks.contains(ANCHOR_X, ANCHOR_Y, ANCHOR_Z),
                "a charged anchor still blows, it just needs the click that asks for it");
        assertEquals(1, explosions(s));
    }

    private static GameState throwScene(Arena arena) {
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK, TestKit.item().itemId(SNOWBALL_ITEM_ID)
                .useKind(Combat.USE_SNOWBALL));
        return s;
    }

    @Test
    void aZeroCooldownThrowIsBoundedByTheCountItSpends() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = throwScene(arena);

        Simulation.tick(s, arena, useLevel(true).withClicks(counts(0, Clicks.MAX, 0, 0, 0)),
                Input.NONE);

        assertEquals(Clicks.MAX, STACK - s.players[0].slotCount[0],
                "a snowball has no cooldown to pace it, so the count is the only bound there is");
    }

    private static GameState offhandThrowScene(Arena arena) {
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, STACK, TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        kit.give(0, ItemDict.OFF_HAND, STACK, TestKit.item().itemId(SNOWBALL_ITEM_ID)
                .useKind(Combat.USE_SNOWBALL));
        return s;
    }

    @Test
    void aBlockPlacementAndAnItemUseDrawFromTheSameUseBudget() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = offhandThrowScene(arena);
        s.blocks.place(2, 63, 0, SUPPORT_ITEM_ID);

        Input frame = useLevel(true)
                .withBlockAction(Input.BLOCK_PLACE, 2, 64, 0)
                .withClicks(counts(0, Clicks.MAX, 0, 0, 0));
        Simulation.tick(s, arena, frame, Input.NONE);

        assertTrue(s.blocks.contains(2, 64, 0), "the first counted right click places the block");
        assertEquals(STACK, s.players[0].slotCount[ItemDict.OFF_HAND],
                "the block path and the item path both used to read the same count and each act on"
                        + " all of it, so one burst of right clicks placed a block AND emptied the"
                        + " off hand");
    }

    private static GameState crystalPair(Arena arena) {
        GameState s = duel(arena);
        PlayerState a = s.players[0];
        a.x = 100.0;
        a.y = 100.0;
        a.z = 101.5;
        s.obsidianItemId = OBSIDIAN_ITEM_ID;
        obsidian(s, 100, 100, 100);
        obsidian(s, 98, 100, 100);
        TestKit.of(s).give(0, 0, 4, TestKit.item().itemId(CRYSTAL_ITEM_ID)
                .flags(ItemDict.FLAG_END_CRYSTAL));
        CrystalState c = new CrystalState();
        c.id = s.nextCrystalId++;
        c.bx = 98;
        c.by = 100;
        c.bz = 100;
        s.crystals.add(c);
        return s;
    }

    private static void obsidian(GameState s, int x, int y, int z) {
        s.blocks.place(x, y, z, OBSIDIAN_ITEM_ID);
        s.blockResistance.put(BlockStore.key(x, y, z), OBSIDIAN_RESISTANCE);
    }

    @Test
    void aCrystalIsPlacedAndAnotherIsHitInsideOneTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalPair(arena);
        int held = s.players[0].slotCount[0];

        Input frame = useLevel(true)
                .withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 100, 100, 100)
                .withCrystalHit(true, 98, 100, 100)
                .withClicks(counts(1, 1, 0, 0, 0));
        Simulation.tick(s, arena, frame, Input.NONE);

        assertEquals(1, explosions(s),
                "the left click detonates, exactly as it does in vanilla where the crystal is an"
                        + " entity you attack");
        assertEquals(1, s.crystals.size(), "and the right click placed the next one");
        assertEquals(100, s.crystals.get(0).bx,
                "the surviving crystal is the newly placed one, not the one that was struck");
        assertEquals(held - 1, s.players[0].slotCount[0],
                "place is a right click and hit is a left click: vanilla drains both keys in the"
                        + " same frame, so folding them into one blockAction field made the core"
                        + " crystal loop impossible");
    }

    @Test
    void theLegacyCrystalOpcodeStillDetonatesForAClientThatSendsNoChannel() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalPair(arena);

        Input frame = attackLevel(true)
                .withBlockAction(Input.BLOCK_HIT_CRYSTAL, 98, 100, 100);
        Simulation.tick(s, arena, frame, Input.NONE);

        assertEquals(1, explosions(s), "an un-updated client keeps the old opcode working");
        assertTrue(s.crystals.isEmpty());
    }

    private static GameState swapScene(Arena arena) {
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK, TestKit.item().itemId(RUBBLE_ITEM_ID));
        return s;
    }

    @Test
    void swapOffhandHasItsOwnCounterInsteadOfRidingTheInventoryOne() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = swapScene(arena);

        Simulation.tick(s, arena,
                Input.NONE.withSwapHands(true).withClicks(counts(0, 0, 0, 0, Clicks.MAX)),
                Input.NONE);

        assertEquals(Clicks.MAX, s.players[0].invActionSeq,
                "keySwapOffhand is its own KeyMapping in handleKeybinds, so it is drained by its"
                        + " own consumeClick loop and is not held to the ten a second a rising"
                        + " edge sampled at twenty hertz can carry");
    }

    private static GameState pockets(Arena arena) {
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, STACK, TestKit.item().itemId(RUBBLE_ITEM_ID));
        kit.give(0, 2, STACK, TestKit.item().itemId(SPARE_ITEM_ID));
        kit.give(0, 3, STACK, TestKit.item().itemId(SPARE_ITEM_ID + 1));
        return s;
    }

    @Test
    void aSwapAndAContainerClickSpendTwoDifferentBudgets() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = pockets(arena);

        Input frame = Input.NONE.withSwapHands(true)
                .withInvAction(Input.INV_MOVE, 2, 3)
                .withClicks(counts(0, 0, 0, 2, 2));
        Simulation.tick(s, arena, frame, Input.NONE);

        assertEquals(4, s.players[0].invActionSeq,
                "two swaps and two moves, because the swap counter and the container click"
                        + " counter are two counters; one shared value read twice used to hand"
                        + " each consumer the whole of it");
    }

    @Test
    void theDropKeyCannotAlsoSpendTheInventoryTossBudget() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = pockets(arena);

        Input frame = Input.NONE.withDrop(true, false)
                .withInvAction(Input.INV_DROP_ONE, 2, 2)
                .withClicks(counts(0, 0, Clicks.MAX, 0, 0));
        Simulation.tick(s, arena, frame, Input.NONE);

        assertEquals(Clicks.MAX, s.players[0].dropSeq,
                "the drop key spends the drop counter and nothing else: the gui toss rides the"
                        + " container click counter, which this frame left at zero");
        assertEquals(STACK, s.players[0].slotCount[2],
                "so the slot the gui toss named was never touched");
    }

    private static GameState miningScene(Arena arena) {
        GameState s = duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = GROUND_Y;
        a.z = 0.5;
        a.onGround = true;
        s.blockProps = new BlockProps.Builder()
                .add(SLOW_ITEM_ID, 20f, 6f, SLOW_ITEM_ID, -1, ItemDict.TOOL_NONE, false)
                .build();
        s.blocks.place(2, 64, 0, SLOW_ITEM_ID);
        return s;
    }

    @Test
    void theDestroyBypassSpendsTheClickItClaimsInsteadOfPeekingAtIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = miningScene(arena);

        Input frame = attackLevel(true)
                .withBlockAction(Input.BLOCK_BREAK, 2, 64, 0)
                .withClicks(counts(Clicks.MAX, 0, 0, 0, 0));
        Simulation.tick(s, arena, frame, Input.NONE);

        assertEquals(1, handActions(s, 0),
                "one press went into startDestroyBlock and swung the arm for it,"
                        + " taken off the held-state edge rather than off a counted click");
        assertEquals(0, swings(s, 0),
                "and nothing is left to swing with: HostFrameContract.attackClicks says a mining"
                        + " frame carries no counted attack clicks, so the six an honest producer"
                        + " would never have sent buy no extra melee swings here either");
    }

    @Test
    void aMiningFrameCannotAlsoLandAMeleeHitOrDodgeTheMissPenalty() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = miningScene(arena);
        s.players[1].x = s.players[0].x + 1.0;
        s.players[1].y = s.players[0].y;
        s.players[1].z = s.players[0].z;
        float before = s.players[1].health;

        Input frame = attackLevel(true)
                .withBlockAction(Input.BLOCK_BREAK, 2, 64, 0)
                .withMeleeHit(true)
                .withClicks(counts(Clicks.MAX, 0, 0, 0, 0));
        Simulation.tick(s, arena, frame, Input.NONE);

        assertTrue(s.players[1].health < before,
                "the frame named a melee target, so HostFrameContract.minesThisFrame drops the"
                        + " mining half and the swing is resolved as the hit it claimed");
        assertEquals(0, s.players[0].miningProgress, 1.0E-6,
                "and no mining happened on the same frame, which is what an honest producer"
                        + " guarantees and what the sim now enforces on both peers");
    }

    @Test
    void aCrystalHitLeavesNoAttackClicksForTheSwingsOrTheDestroyGate() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalPair(arena);

        Input frame = attackLevel(true)
                .withBlockAction(Input.BLOCK_BREAK, 100, 100, 100)
                .withCrystalHit(true, 98, 100, 100)
                .withClicks(counts(Clicks.MAX, 0, 0, 0, 0));
        Simulation.tick(s, arena, frame, Input.NONE);

        assertEquals(1, explosions(s), "the crystal takes the first click");
        assertEquals(0, swings(s, 0),
                "and the remaining six are gone with it: three consumers each reading the same"
                        + " count turned seven presses into a detonation, a destroy press and"
                        + " seven swings");
        assertEquals(0, handActions(s, 0), "including the destroy press");
    }

    private static GameState headroom(Arena arena) {
        GameState s = duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = 64.3;
        a.z = 0.5;
        TestKit.of(s).give(0, 0, STACK,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        s.blocks.place(1, 66, 0, SUPPORT_ITEM_ID);
        return s;
    }

    @Test
    void aPlacementReadsTheReplicatedSneakStateNotTheClaimInTheFrame() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState standing = headroom(arena);
        standing.players[0].sneaking = false;

        Input claimsSneak = new Input(false, false, false, false, false, false, true, false, true,
                0f, 0f, 0).withBlockAction(Input.BLOCK_PLACE, 0, 66, 0);
        Simulation.tick(standing, arena, claimsSneak, Input.NONE);

        assertFalse(standing.blocks.contains(0, 66, 0),
                "the frame claimed a crouch the replicated pose does not have, and the placer"
                        + " hitbox is the one thing in this test the sender does not get to pick");

        GameState crouched = headroom(arena);
        crouched.players[0].sneaking = true;

        Input claimsNothing = new Input(false, false, false, false, false, false, false, false, true,
                0f, 0f, 0).withBlockAction(Input.BLOCK_PLACE, 0, 66, 0);
        Simulation.tick(crouched, arena, claimsNothing, Input.NONE);

        assertTrue(crouched.blocks.contains(0, 66, 0),
                "a player the sim already has crouched fits under the cell, whatever the frame"
                        + " says about the key");
    }

    private static int maxedSwings() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        s.players[1].x = 2.0;
        s.players[1].z = s.players[0].z;
        Simulation.tick(s, arena, attackLevel(true).withClicks(counts(Clicks.MAX, 0, 0, 0, 0))
                .withMeleeHit(true), Input.NONE);
        return swings(s, 0);
    }

    private static int maxedThrows() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = throwScene(arena);
        Simulation.tick(s, arena, useLevel(true).withClicks(counts(0, Clicks.MAX, 0, 0, 0)),
                Input.NONE);
        return STACK - s.players[0].slotCount[0];
    }

    private static int maxedDrops() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = pockets(arena);
        Simulation.tick(s, arena,
                Input.NONE.withDrop(true, false).withClicks(counts(0, 0, Clicks.MAX, 0, 0)),
                Input.NONE);
        return s.players[0].dropSeq;
    }

    private static int maxedMoves() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = pockets(arena);
        Simulation.tick(s, arena,
                Input.NONE.withInvAction(Input.INV_MOVE, 2, 3)
                        .withClicks(counts(0, 0, 0, Clicks.MAX, 0)),
                Input.NONE);
        return s.players[0].invActionSeq;
    }

    private static int maxedSwaps() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = swapScene(arena);
        Simulation.tick(s, arena,
                Input.NONE.withSwapHands(true).withClicks(counts(0, 0, 0, 0, Clicks.MAX)),
                Input.NONE);
        return s.players[0].invActionSeq;
    }

    @Test
    void aMaxedCountOnEveryCounterCannotBuyMoreActionsThanTheCountItself() {
        assertEquals(Clicks.MAX, maxedSwings(), "attack");
        assertEquals(Clicks.MAX, maxedThrows(), "use");
        assertEquals(Clicks.MAX, maxedDrops(), "drop");
        assertEquals(Clicks.MAX, maxedMoves(), "inventory");
        assertEquals(Clicks.MAX, maxedSwaps(), "swap offhand");

        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalPair(arena);
        TestKit.of(s).give(0, ItemDict.OFF_HAND, STACK, TestKit.item().itemId(SNOWBALL_ITEM_ID)
                .useKind(Combat.USE_SNOWBALL));
        int crystals = s.players[0].slotCount[0];

        Input everything = new Input(false, false, false, false, false, false, false, true, true,
                0f, 0f, 0)
                .withUsePress(true)
                .withMeleeHit(true)
                .withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 100, 100, 100)
                .withCrystalHit(true, 98, 100, 100)
                .withClicks(counts(Clicks.MAX, Clicks.MAX, 0, 0, 0));
        Simulation.tick(s, arena, everything, Input.NONE);

        PlayerState a = s.players[0];
        int attackActions = swings(s, 0) + explosions(s) + handActions(s, 0);
        int useActions = (crystals - a.slotCount[0])
                + (STACK - a.slotCount[ItemDict.OFF_HAND]);

        assertTrue(attackActions <= Clicks.MAX,
                "three consumers read the attack count and none of them drained it: "
                        + attackActions);
        assertTrue(useActions <= Clicks.MAX,
                "the block path and the item path read the same use count: " + useActions);
        assertEquals(0, a.clickBudget.attack, "the attack budget was spent, not re-read");
        assertEquals(0, a.clickBudget.use, "the use budget was spent, not re-read");
    }

    @Test
    void theBudgetRidesTheSnapshotSoAReplaySpendsTheSameClicks() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState live = throwScene(arena);
        Input frame = useLevel(true).withClicks(counts(2, 2, 0, 0, 0));

        for (int i = 0; i < 3; i++) {
            Simulation.tick(live, arena, frame, Input.NONE);
        }
        GameState replay = live.copy();
        assertEquals(Checksum.of(live), Checksum.of(replay),
                "the snapshot has to start out identical or the replay proves nothing");

        for (int i = 0; i < 4; i++) {
            Simulation.tick(live, arena, frame, Input.NONE);
            Simulation.tick(replay, arena, frame, Input.NONE);
            assertEquals(Checksum.of(live), Checksum.of(replay),
                    "the budget is per tick scratch reloaded from the frame at the top of every"
                            + " tick, so a rollback replay may never inherit a half spent one");
        }
    }

    @Test
    void aClearedBudgetIsNotLoadedForAnyTickAtAll() {
        ClickBudget b = new ClickBudget();
        assertFalse(b.loadedFor(0), "a budget nobody loaded is loaded for no tick");
        assertFalse(b.loadedFor(Integer.MIN_VALUE),
                "the unloaded marker used to be a tick number, so the one tick whose index"
                        + " equalled it read back as a full budget nobody had loaded");

        b.load(7, counts(1, 1, 1, 1, 1), Clicks.MAX, true, false);
        assertTrue(b.loadedFor(7));
        assertFalse(b.loadedFor(8));

        b.clear();
        assertFalse(b.loadedFor(7), "clear has to un-load it");
        assertFalse(b.loadedFor(0), "and not alias tick zero on the way out");
    }

    @Test
    void thereIsNoClicksConstructorThatCanLeaveACounterOutSilently() {
        assertEquals(1, Clicks.class.getConstructors().length,
                "a second constructor is how the swap counter stayed dead for a whole release:"
                        + " it compiles, it zeroes the channel it omits, and nothing says so");
        assertEquals(Clicks.class.getRecordComponents().length,
                Clicks.class.getConstructors()[0].getParameterCount(),
                "the only way to build a Clicks has to name every channel it carries");
    }
}
