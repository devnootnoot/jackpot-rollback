package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class SyntheticFrameLatchTest {
    private static final double GROUND_Y = 64.0;
    private static final int ELYTRA_ITEM_ID = 7301;

    private static GameState standing() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        p.x = 0.5;
        p.y = GROUND_Y;
        p.z = 0.5;
        p.onGround = true;
        p.health = 20f;
        PlayerState v = s.players[1];
        v.x = 10_000.0;
        v.y = GROUND_Y;
        v.health = 20f;
        TestKit kit = TestKit.of(s);
        int elytra = kit.add(TestKit.item().itemId(ELYTRA_ITEM_ID).maxStack(1).maxDamage(432)
                .flags(ItemDict.FLAG_ELYTRA).armor(0, 0f, 0f, ItemDict.EQUIP_CHEST));
        kit.put(0, ItemDict.ARMOR_CHEST, elytra, 1);
        return s;
    }

    private static Input jump(boolean held) {
        return new Input(false, false, false, false, held, false, false, false, false, 0f, 0f, 0);
    }

    private static Input filler(Input raw) {
        return raw.withSynthetic(true).gestureOnly();
    }

    private static PlayerState airborneHolding(GameState s, Arena arena) {
        PlayerState p = s.players[0];
        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        for (int i = 0; i < 4; i++) {
            Simulation.tick(s, arena, jump(true), Input.NONE);
        }
        assertFalse(p.onGround, "the jump has to have left the floor");
        assertFalse(p.gliding, "space went down on the floor, so there is no rising edge in the air");
        return p;
    }

    @Test
    void aFillerFrameMustNotManufactureAnElytraDeploy() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = standing();
        PlayerState p = airborneHolding(s, arena);

        Simulation.tick(s, arena, filler(jump(true)), Input.NONE);
        Simulation.tick(s, arena, jump(true), Input.NONE);

        assertFalse(p.gliding,
                "the player never let go of space. A SNAP catch-up filler past"
                        + " PREDICTION_DECAY_FRAMES is gestureOnly(), which zeroes the jump bit,"
                        + " and Simulation wrote prevJump from it anyway. That clears the latch, so"
                        + " the first REAL frame after the burst reads as a fresh press and opens"
                        + " the wings. A frame the session invented may neither start nor end a"
                        + " gesture, and it may not clear the latch that decides where the next"
                        + " gesture starts either.");
    }

    @Test
    void aWholeFillerBurstLeavesTheJumpLatchWhereTheRealFrameLeftIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = standing();
        PlayerState p = airborneHolding(s, arena);

        for (int i = 0; i < 6; i++) {
            Simulation.tick(s, arena, filler(jump(true)), Input.NONE);
            assertTrue(p.prevJump,
                    "the last real frame had space down, so every synthetic frame in the burst has"
                            + " to leave prevJump exactly as that frame left it");
            assertFalse(p.gliding, "a filler frame may not open the wings on its own");
        }

        Simulation.tick(s, arena, jump(true), Input.NONE);
        assertFalse(p.gliding, "and the resume frame is still the same unbroken hold");
    }

    @Test
    void aFillerFrameMustNotOpenTheWingsItself() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = standing();
        PlayerState p = s.players[0];

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        Simulation.tick(s, arena, jump(true), Input.NONE);
        Simulation.tick(s, arena, jump(false), Input.NONE);
        Simulation.tick(s, arena, jump(false), Input.NONE);
        assertFalse(p.onGround, "the jump has to have left the floor");
        assertFalse(p.prevJump, "and space has to be up again before the burst");

        Simulation.tick(s, arena, jump(true).withSynthetic(true), Input.NONE);

        assertFalse(p.gliding,
                "a small catch-up runs heldOnly() frames built from the CURRENT raw input, so a"
                        + " press the player made this tick can land on a frame the session"
                        + " invented several frames earlier. The deploy is a gesture start and a"
                        + " synthetic frame may not start one.");
    }

    @Test
    void aFillerFrameMustNotClearTheDropLatch() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = CrystalKitFixture.build(GROUND_Y);
        PlayerState p = s.players[0];
        Input drop = new Input(false, false, false, false, false, false, false, false, false,
                0f, 0f, CrystalKitFixture.SLOT_GAPPLE).withDrop(true, false);

        Simulation.tick(s, arena, drop, Input.NONE);
        assertEquals(1, p.dropSeq, "the first frame of a held Q is the one that drops");

        Simulation.tick(s, arena, drop.withSynthetic(true).heldOnly(), Input.NONE);
        Simulation.tick(s, arena, drop, Input.NONE);

        assertEquals(1, p.dropSeq,
                "heldOnly() clears dropItem, so the filler frame reads as Q coming back up."
                        + " handleDrop wrote prevDrop from it, and the next real frame of the same"
                        + " unbroken hold then counted as a second press and threw a second item.");
    }

    @Test
    void aFillerFrameMustNotClearTheInventoryClickLatch() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = CrystalKitFixture.build(GROUND_Y);
        PlayerState p = s.players[0];
        Input swap = Input.NONE.withSwapHands(true);

        Simulation.tick(s, arena, swap, Input.NONE);
        assertTrue(p.prevInvClick, "the swap key is down, so the latch has to be set");

        Simulation.tick(s, arena, swap.withSynthetic(true).heldOnly(), Input.NONE);

        assertTrue(p.prevInvClick,
                "heldOnly() clears swapHands and invAction, so an invented frame reads as the key"
                        + " coming up. Writing prevInvClick from it re-arms the fresh-click path"
                        + " and the next real frame of one unbroken hold swaps a second time.");
    }
}
