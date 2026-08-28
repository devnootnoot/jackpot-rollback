package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class DiscreteClickPacingTest {
    private static final double GROUND_Y = 64.0;

    private static final int TICKS = 20;

    private static final int BLOCK_ITEM_ID = 4300;
    private static final int RUBBLE_ITEM_ID = 4301;

    private static final int STACK = 64;

    private static final int[] CRYSTAL_BASE = {2, 64, 0};

    private static final int[][] PLACE_CELLS = {
            {1, 64, 1}, {2, 64, 1}, {3, 64, 1}, {1, 64, 2}, {2, 64, 2},
            {3, 64, 2}, {1, 64, 3}, {2, 64, 3}, {3, 64, 3}, {1, 64, 4},
    };

    private static final int PLACE_TICKS = PLACE_CELLS.length;

    private static GameState duel(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        s.players[1].x = 40.0;
        return s;
    }

    private static GameState blockPockets(Arena arena) {
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        return s;
    }

    private static Input placeClick(int[] cell, boolean levelDown) {
        return new Input(false, false, false, false, false, false, false, false, levelDown,
                0f, 0f, 0)
                .withBlockAction(Input.BLOCK_PLACE, cell[0], cell[1], cell[2])
                .withUsePress(true);
    }

    @Test
    void aSubTickClickStreamPlacesOnEveryPressItActuallyMade() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = blockPockets(arena);

        for (int frame = 0; frame < PLACE_TICKS; frame++) {
            Simulation.tick(s, arena, placeClick(PLACE_CELLS[frame], frame % 2 == 0), Input.NONE);
        }

        assertEquals(PLACE_TICKS, STACK - s.players[0].slotCount[0],
                "a click faster than the 20Hz sample lands a press on consecutive ticks with the"
                        + " button level moving under it, and Minecraft.handleKeybinds runs"
                        + " while (keyUse.consumeClick()) startUseItem() unconditionally, above and"
                        + " independent of the rightClickDelay gate: every one of those presses"
                        + " places. Re-deriving an edge out of usePress, which is already the"
                        + " client's press edge, is what collapsed this to the held cadence");
    }

    @Test
    void aPressBitPinnedHighWithTheButtonNeverReleasedFallsBackToTheHeldCadence() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = blockPockets(arena);

        for (int frame = 0; frame < PLACE_TICKS; frame++) {
            Simulation.tick(s, arena, placeClick(PLACE_CELLS[frame], true), Input.NONE);
        }

        assertEquals(1 + (PLACE_TICKS - 1) / Combat.USE_REPEAT_DELAY,
                STACK - s.players[0].slotCount[0],
                "a client claiming a fresh press on every tick while never once showing the button"
                        + " back up is claiming a press with no release in front of it, which"
                        + " KeyMapping.consumeClick cannot produce: it buys the one honest click it"
                        + " looks like and then pays rightClickDelay like a held button");
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

    private static Input hitCrystal(boolean attack) {
        return new Input(false, false, false, false, false, false, false, attack, false, 0f, 0f, 0)
                .withBlockAction(Input.BLOCK_HIT_CRYSTAL, CRYSTAL_BASE[0], CRYSTAL_BASE[1],
                        CRYSTAL_BASE[2]);
    }

    private static Input attackOnly() {
        return new Input(false, false, false, false, false, false, false, true, false, 0f, 0f, 0);
    }

    @Test
    void anHonestCrystalClickStreamDetonatesOnEveryPress() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalField(arena);

        int popped = 0;
        for (int frame = 0; frame < TICKS; frame++) {
            restock(s);
            Simulation.tick(s, arena, frame % 2 == 0 ? hitCrystal(true) : Input.NONE, Input.NONE);
            if (s.crystals.isEmpty()) {
                popped++;
            }
        }

        assertEquals(TICKS / 2, popped,
                "Minecraft.startAttack is reached only from while (keyAttack.consumeClick()) and"
                        + " the continueAttack call below it never attacks an entity, so nothing"
                        + " paces a crystal hit but the click and every press a crystal player"
                        + " actually makes has to detonate");
    }

    @Test
    void aPinnedAttackBitCannotBuyASecondCrystalByChurningTheBlockAction() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalField(arena);

        int popped = 0;
        for (int frame = 0; frame < TICKS; frame++) {
            restock(s);
            Simulation.tick(s, arena, frame % 2 == 0 ? hitCrystal(true) : attackOnly(),
                    Input.NONE);
            if (s.crystals.isEmpty()) {
                popped++;
            }
        }

        assertEquals(1, popped,
                "the latch used to key on whether last tick's blockAction was a crystal hit, which"
                        + " a client clears for free by sending one other action and which ordinary"
                        + " place-hit-place crystal play clears every other tick anyway: the gate"
                        + " is the attack LEVEL's own rising edge, the one thing a client holding"
                        + " the button down cannot cycle");
    }

    private static GameState fullPockets(Arena arena) {
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, STACK, TestKit.item().itemId(RUBBLE_ITEM_ID));
        kit.give(0, 2, STACK, TestKit.item().itemId(RUBBLE_ITEM_ID + 1));
        return s;
    }

    private static Input invToss() {
        return Input.NONE.withInvAction(Input.INV_DROP_ONE, 0, 0);
    }

    @Test
    void anHonestDropStreamTossesOnePerPressDownEitherPath() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState keyed = fullPockets(arena);
        GameState guied = fullPockets(arena);

        for (int frame = 0; frame < TICKS; frame++) {
            boolean down = frame % 2 == 0;
            Simulation.tick(keyed, arena, Input.NONE.withDrop(down, false), Input.NONE);
            Simulation.tick(guied, arena, down ? invToss() : Input.NONE, Input.NONE);
        }

        assertEquals(TICKS / 2, keyed.players[0].dropSeq,
                "Minecraft.handleKeybinds tosses inside while (keyDrop.consumeClick()), one item"
                        + " per press with nothing pacing it");
        assertEquals(TICKS / 2, guied.players[0].dropSeq,
                "and a throw out of an open inventory is one AbstractContainerScreen.keyPressed"
                        + " ClickType.THROW per press, so it must cost the player nothing either");
    }

    @Test
    void aPinnedInventoryTossCannotWalkPastTheDropKeyEdge() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, invToss(), Input.NONE);
        }

        assertEquals(1, s.players[0].dropSeq,
                "the drop key edge was the only gate and the INV_DROP_ONE path walked straight"
                        + " past it, so a client pinning invAction emptied a stack at one item a"
                        + " tick: both paths are one claim through one gate now");
    }

    @Test
    void pinningBothDropPathsAtOnceIsStillOneToss() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, invToss().withDrop(true, false), Input.NONE);
        }

        assertEquals(1, s.players[0].dropSeq,
                "one gate means the two paths cannot take turns re-arming each other");
    }

    private static Input invMove(GameState s, int slotA, int slotB) {
        int src = s.players[0].slotEntry[slotA] != ItemDict.NONE ? slotA : slotB;
        return Input.NONE.withInvAction(Input.INV_MOVE, src, src == slotA ? slotB : slotA);
    }

    @Test
    void anHonestInventoryClickStreamMovesOnEveryClick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, frame % 2 == 0 ? invMove(s, 2, 3) : Input.NONE, Input.NONE);
        }

        assertEquals(TICKS / 2, s.players[0].invActionSeq,
                "AbstractContainerScreen.mouseClicked sends one ServerboundContainerClickPacket per"
                        + " press and handleContainerClick paces it with nothing at all, so a real"
                        + " click stream must not lose a single move");
    }

    @Test
    void aPinnedInventoryActionMovesOnceNotOncePerTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, invMove(s, 2, 3), Input.NONE);
        }

        assertEquals(1, s.players[0].invActionSeq,
                "swapHands and invAction were read raw off the wire every tick, so a pinned field"
                        + " re-ran the same container click twenty times a second: a click is one"
                        + " per press, and a press needs the button back up in front of it");
    }

    @Test
    void aPinnedSwapHandsSwapsOnceAndAnHonestOneSwapsEveryPress() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState pinned = fullPockets(arena);
        GameState honest = fullPockets(arena);

        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(pinned, arena, Input.NONE.withSwapHands(true), Input.NONE);
            Simulation.tick(honest, arena, Input.NONE.withSwapHands(frame % 2 == 0), Input.NONE);
        }

        assertEquals(1, pinned.players[0].invActionSeq,
                "Minecraft.handleKeybinds swaps inside while (keySwapOffhand.consumeClick()), so a"
                        + " held F key swaps exactly once");
        assertEquals(TICKS / 2, honest.players[0].invActionSeq,
                "and every press the player actually makes still swaps");
    }

    @Test
    void aContainerClickDoesNotSwallowTheDropThatFollowsIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = fullPockets(arena);

        Simulation.tick(s, arena, Input.NONE.withInvAction(Input.INV_MOVE, 2, 3), Input.NONE);
        Simulation.tick(s, arena, Input.NONE.withDrop(true, false), Input.NONE);

        assertEquals(1, s.players[0].invActionSeq, "the move has to land, or this proves nothing");
        assertEquals(1, s.players[0].dropSeq,
                "the container click and the drop key are different keys whose clicks vanilla"
                        + " consumes in separate loops, so they carry separate gates");
    }

    @Test
    void oneBlockActionPerTickIsTheCeilingTheWireItselfImposes() {
        assertTrue(Input.BLOCK_CLOSE_CONTAINER < 16,
                "blockAction is one small enum field on the frame, not a list, so a tick carries"
                        + " exactly one of them and 20 a second is the hard ceiling on placements,"
                        + " breaks and detonations alike. Vanilla's own ceiling is the mouse and is"
                        + " higher, so this bound only bites a player already past 20 clicks a"
                        + " second; lifting it means widening the frame, which is a wire change"
                        + " and not a pacing one");
    }
}
