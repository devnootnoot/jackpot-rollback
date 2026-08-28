package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ClickCountDrainTest {
    private static final double GROUND_Y = 64.0;

    private static final int TICKS = 20;

    private static final int CPS = 15;

    private static final int STACK = 64;

    private static final int BLOCK_ITEM_ID = 4400;
    private static final int RUBBLE_ITEM_ID = 4401;

    private static final int[] CRYSTAL_BASE = {2, 64, 0};

    private static final int[][] PLACE_CELLS = {
            {1, 64, 1}, {2, 64, 1}, {3, 64, 1},
            {1, 64, 2}, {2, 64, 2}, {3, 64, 2},
            {1, 64, 3}, {2, 64, 3}, {3, 64, 3},
            {1, 64, 4}, {2, 64, 4}, {3, 64, 4},
            {-1, 64, 1}, {-2, 64, 1}, {-3, 64, 1},
    };

    private static boolean clickTick(int frame) {
        return frame % 4 != 3;
    }

    private static Clicks one(int attack, int use, int drop, int inv) {
        return new Clicks(attack, use, drop, inv, 0);
    }

    private static GameState duel(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        s.players[1].x = 40.0;
        return s;
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

    @Test
    void anHonestFifteenCpsMeleeStreamSwingsFifteenTimesASecond() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);

        int total = 0;
        for (int frame = 0; frame < TICKS; frame++) {
            Input in = attackLevel(true).withMeleeHit(true)
                    .withClicks(clickTick(frame) ? one(1, 0, 0, 0) : Clicks.NONE);
            Simulation.tick(s, arena, in, Input.NONE);
            total += swings(s, 0);
        }

        assertEquals(CPS, total,
                "Minecraft.handleKeybinds swings inside while (keyAttack.consumeClick()), which"
                        + " drains a counter the input handler raises at frame rate: fifteen clicks"
                        + " in a second are fifteen swings, whatever the button level looked like"
                        + " at the twenty tick boundaries that sampled it. The stream NAMES the"
                        + " opponent because a fifteen-per-second stream at AIR is not fifteen"
                        + " swings in vanilla either: the first one arms Minecraft.missTime and,"
                        + " with the button never coming up, nothing zeroes it - see"
                        + " MissPenaltyTest. This test is about the drain, not the whiff");
    }

    @Test
    void theBooleanEdgeAloneIsStillCappedAtTenSwingsASecond() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);

        int total = 0;
        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, attackLevel(frame % 2 == 0).withMeleeHit(true), Input.NONE);
            total += swings(s, 0);
        }

        assertEquals(TICKS / 2, total,
                "a rising edge of a boolean sampled once per tick needs a down tick and an up tick,"
                        + " so a client that sends no counts is still bounded at half the tick rate"
                        + " and the count path is the only way past it. The stream NAMES the"
                        + " opponent for the same reason the counted one does: an alternating"
                        + " stream at AIR whiffs on its first edge and the penalty no longer stops"
                        + " running when the bit goes down, so it would measure the whiff instead"
                        + " of the drain - see MissPenaltyTest");
    }

    @Test
    void aBurstOfClicksInsideOneTickSwingsOncePerClick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);

        s.players[1].x = 2.0;
        s.players[1].z = s.players[0].z;
        Simulation.tick(s, arena, attackLevel(true).withClicks(one(3, 0, 0, 0))
                .withMeleeHit(true), Input.NONE);

        assertEquals(3, swings(s, 0),
                "three clicks landing between two tick boundaries are three swings, exactly as"
                        + " three consumeClick drains would be. They are aimed AT the opponent"
                        + " because that is the only way vanilla lets all three through: the same"
                        + " three presses at AIR are one swing and two refusals, since the first"
                        + " arms Minecraft.missTime and the loop that drains them has no"
                        + " continueAttack between them to zero it - see MissPenaltyTest");
    }

    @Test
    void aSaturatedAttackCountCannotOutrunTheAttackCooldown() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        s.players[1].x = 2.0;
        s.players[1].z = s.players[0].z;
        PlayerState a = s.players[0];
        a.attackTicker = 100;
        TestKit.of(s).give(0, 0, 1, TestKit.item().melee(6f, 1.6f).flags(ItemDict.FLAG_SWORD));

        float before = s.players[1].health;
        Input maxed = attackLevel(true)
                .withClicks(one(Clicks.MAX, 0, 0, 0))
                .withMeleeHit(true);
        Simulation.tick(s, arena, maxed, Input.NONE);

        assertEquals(Clicks.MAX, swings(s, 0), "every counted click still swings");
        assertTrue(s.players[1].health < before, "the first swing of the burst still lands");
        assertEquals(s.tick - 1, a.meleeClaimTick,
                "the melee claim is spent once per tick, so the other six swings of a maxed count"
                        + " buy an attacker nothing but six animations and a reset attack cooldown");
    }

    private static GameState crystalField(Arena arena) {
        GameState s = duel(arena);
        PlayerState a = s.players[0];
        a.maxHealth = 20000f;
        a.health = 20000f;
        return s;
    }

    private static void restock(GameState s) {
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.y = GROUND_Y;
        a.z = 0.0;
        a.vx = 0.0;
        a.vy = 0.0;
        a.vz = 0.0;
        a.health = a.maxHealth;
        if (s.crystals.isEmpty()) {
            CrystalState c = new CrystalState();
            c.id = s.nextCrystalId++;
            c.bx = CRYSTAL_BASE[0];
            c.by = CRYSTAL_BASE[1];
            c.bz = CRYSTAL_BASE[2];
            s.crystals.add(c);
        }
    }

    private static Input hitCrystal() {
        return attackLevel(true)
                .withBlockAction(Input.BLOCK_HIT_CRYSTAL, CRYSTAL_BASE[0], CRYSTAL_BASE[1],
                        CRYSTAL_BASE[2]);
    }

    @Test
    void anHonestFifteenCpsCrystalStreamDetonatesFifteenTimesASecond() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalField(arena);

        int popped = 0;
        for (int frame = 0; frame < TICKS; frame++) {
            restock(s);
            Input in = hitCrystal().withClicks(clickTick(frame) ? one(1, 0, 0, 0) : Clicks.NONE);
            Simulation.tick(s, arena, in, Input.NONE);
            if (s.crystals.isEmpty()) {
                popped++;
            }
        }

        assertEquals(CPS, popped,
                "nothing paces a crystal hit but the click itself, and the attack level is pinned"
                        + " down across the whole stream here: only the count can tell the fifteen"
                        + " presses apart");
    }

    @Test
    void aCountedCrystalClickCannotDetonateTheSameCrystalTwice() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalField(arena);
        restock(s);

        Simulation.tick(s, arena, hitCrystal().withClicks(one(Clicks.MAX, 0, 0, 0)), Input.NONE);

        assertTrue(s.crystals.isEmpty(), "the first counted click detonates");
        assertEquals(1, explosions(s),
                "a frame names one cell, so the drain re-runs the same lookup and finds nothing:"
                        + " the wire itself bounds detonations at one a tick");
    }

    private static GameState blockPockets(Arena arena) {
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        return s;
    }

    private static Input placeClick(int[] cell) {
        return new Input(false, false, false, false, false, false, false, false, true, 0f, 0f, 0)
                .withBlockAction(Input.BLOCK_PLACE, cell[0], cell[1], cell[2]);
    }

    @Test
    void anHonestFifteenCpsPlaceStreamPlacesFifteenBlocksASecond() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = blockPockets(arena);

        int placed = 0;
        for (int frame = 0; frame < TICKS; frame++) {
            if (!clickTick(frame)) {
                Simulation.tick(s, arena, Input.NONE, Input.NONE);
                continue;
            }
            Simulation.tick(s, arena, placeClick(PLACE_CELLS[placed]).withClicks(one(0, 1, 0, 0)),
                    Input.NONE);
            placed++;
        }

        assertEquals(CPS, STACK - s.players[0].slotCount[0],
                "the click path in Minecraft.handleKeybinds runs above and independent of the"
                        + " rightClickDelay gate, so a counted press places on consecutive ticks;"
                        + " the four tick repeat delay is the held path's pacing and only the held"
                        + " path's");
    }

    @Test
    void theHeldPathStillPaysTheFourTickRepeatDelay() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = blockPockets(arena);

        for (int frame = 0; frame < PLACE_CELLS.length; frame++) {
            Simulation.tick(s, arena, placeClick(PLACE_CELLS[frame]), Input.NONE);
        }

        assertEquals(1 + (PLACE_CELLS.length - 1) / Combat.USE_REPEAT_DELAY,
                STACK - s.players[0].slotCount[0],
                "a held button with no counts behind it is still Minecraft.rightClickDelay,"
                        + " one use every four ticks");
    }

    private static GameState fullPockets(Arena arena) {
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, STACK, TestKit.item().itemId(RUBBLE_ITEM_ID));
        kit.give(0, 2, STACK, TestKit.item().itemId(RUBBLE_ITEM_ID + 1));
        return s;
    }

    @Test
    void anHonestFifteenCpsDropStreamTossesFifteenItemsASecond() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        for (int frame = 0; frame < TICKS; frame++) {
            Input in = Input.NONE.withDrop(true, false)
                    .withClicks(clickTick(frame) ? one(0, 0, 1, 0) : Clicks.NONE);
            Simulation.tick(s, arena, in, Input.NONE);
        }

        assertEquals(CPS, s.players[0].dropSeq,
                "while (keyDrop.consumeClick()) player.drop(...) has nothing pacing it but the"
                        + " click, and the key is held down for the whole stream here");
    }

    @Test
    void aBurstOfDropClicksInsideOneTickTossesOncePerClick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        Simulation.tick(s, arena, Input.NONE.withDrop(true, false).withClicks(one(0, 0, 4, 0)),
                Input.NONE);

        assertEquals(4, s.players[0].dropSeq, "four presses are four tosses");
        assertEquals(STACK - 4, s.players[0].slotCount[0],
                "and each toss paid for itself out of the stack");
    }

    @Test
    void aDropCountCannotTossItemsThePlayerDoesNotHave() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, 2, TestKit.item().itemId(RUBBLE_ITEM_ID));

        Simulation.tick(s, arena,
                Input.NONE.withDrop(true, false).withClicks(one(0, 0, Clicks.MAX, 0)), Input.NONE);

        assertEquals(2, s.players[0].dropSeq,
                "the drain stops at the first press that cannot pay: a maxed count buys an"
                        + " attacker nothing but the items already in the slot");
    }

    @Test
    void theDropKeyAndTheGuiTossNoLongerShareOneLatch() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        Input both = Input.NONE.withDrop(true, false)
                .withInvAction(Input.INV_DROP_ONE, 2, 2)
                .withClicks(one(0, 0, 1, 1));
        Simulation.tick(s, arena, both, Input.NONE);

        assertEquals(2, s.players[0].dropSeq,
                "vanilla drains keyDrop in handleKeybinds and throws out of the container screen"
                        + " through ClickType.THROW: two different keys, two different counters,"
                        + " and one latch between them used to swallow whichever came second");
    }

    private static Input invMove(GameState s, int slotA, int slotB) {
        int src = s.players[0].slotEntry[slotA] != ItemDict.NONE ? slotA : slotB;
        return Input.NONE.withInvAction(Input.INV_MOVE, src, src == slotA ? slotB : slotA);
    }

    @Test
    void anHonestFifteenCpsInventoryStreamMovesFifteenTimesASecond() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        for (int frame = 0; frame < TICKS; frame++) {
            Input in = invMove(s, 2, 3)
                    .withClicks(clickTick(frame) ? one(0, 0, 0, 1) : Clicks.NONE);
            Simulation.tick(s, arena, in, Input.NONE);
        }

        assertEquals(CPS, s.players[0].invActionSeq,
                "handleContainerClick paces a container click with nothing at all, and the"
                        + " invAction field is pinned across this whole stream: the count is what"
                        + " separates fifteen presses from one");
    }

    @Test
    void aPinnedInventoryActionWithNoCountStillMovesOnlyOnce() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, invMove(s, 2, 3), Input.NONE);
        }

        assertEquals(1, s.players[0].invActionSeq,
                "a client that sends no counts is still held to one action per rising edge");
    }

    @Test
    void aSwapDoesNotSwallowTheContainerClickOnTheNextTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        Simulation.tick(s, arena, Input.NONE.withSwapHands(true).withClicks(one(0, 0, 0, 1)),
                Input.NONE);
        Simulation.tick(s, arena, invMove(s, 2, 3).withClicks(one(0, 0, 0, 1)), Input.NONE);

        assertEquals(2, s.players[0].invActionSeq,
                "keySwapOffhand and a container click are drained by two different loops in"
                        + " vanilla, so folding them into one claim made a swap eat the move that"
                        + " followed it");
    }

    @Test
    void aSwapAndAContainerClickInTheSameTickBothLand() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        Simulation.tick(s, arena,
                invMove(s, 2, 3).withSwapHands(true).withClicks(one(0, 0, 0, 1)), Input.NONE);

        assertEquals(2, s.players[0].invActionSeq,
                "both loops run in the same tick in Minecraft.handleKeybinds");
    }

    @Test
    void aChargedAnchorDoesNotBlockThePlacementThatFollowsIt() {
        assertEquals(Combat.USE_REPEAT_DELAY, 4,
                "the held path's repeat delay is unchanged");
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = blockPockets(arena);

        Simulation.tick(s, arena, placeClick(PLACE_CELLS[0]).withClicks(one(0, 1, 0, 0)),
                Input.NONE);
        Simulation.tick(s, arena, placeClick(PLACE_CELLS[1]).withClicks(one(0, 1, 0, 0)),
                Input.NONE);

        assertEquals(2, STACK - s.players[0].slotCount[0],
                "every block use shared one useDelay, so any one of them locked out the next three"
                        + " ticks of the other two; a counted click bypasses that delay the way"
                        + " vanilla's click loop bypasses rightClickDelay");
    }
}
