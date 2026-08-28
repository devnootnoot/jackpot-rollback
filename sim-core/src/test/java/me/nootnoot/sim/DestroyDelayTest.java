package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class DestroyDelayTest {
    private static final double GROUND_Y = 64.0;

    private static final int SOFT_ITEM_ID = 980;
    private static final int SLOW_ITEM_ID = 981;

    private static final int SOFT_X = 2;
    private static final int SOFT_Y = 64;
    private static final int SOFT_Z = 0;

    private static final int SLOW_X = 2;
    private static final int SLOW_Y = 65;
    private static final int SLOW_Z = 0;

    private static final float EPSILON = 1.0e-4f;

    private static GameState scene(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = arena.groundY;
        a.z = 0.5;
        a.yaw = -90f;
        a.pitch = 0f;
        a.onGround = true;
        s.players[1].x = 40.0;

        s.blockProps = new BlockProps.Builder()
                .add(SOFT_ITEM_ID, 0f, 6f, SOFT_ITEM_ID, -1, ItemDict.TOOL_NONE, false)
                .add(SLOW_ITEM_ID, 20f, 6f, SLOW_ITEM_ID, -1, ItemDict.TOOL_NONE, false)
                .build();
        TestKit.of(s).give(0, 0, 1, TestKit.item().itemId(SOFT_ITEM_ID).maxStack(1));
        s.blocks.place(SOFT_X, SOFT_Y, SOFT_Z, SOFT_ITEM_ID);
        s.blocks.place(SLOW_X, SLOW_Y, SLOW_Z, SLOW_ITEM_ID);
        return s;
    }

    private static Input mine(int x, int y, int z, boolean attack) {
        return new Input(false, false, false, false, false, false, false, attack, false,
                -90f, 0f, 0).withBlockAction(Input.BLOCK_BREAK, x, y, z);
    }

    private static Input slow(boolean attack) {
        return mine(SLOW_X, SLOW_Y, SLOW_Z, attack);
    }

    private static void armTheDelay(GameState s, Arena arena) {
        Simulation.tick(s, arena, mine(SOFT_X, SOFT_Y, SOFT_Z, true), Input.NONE);
        assertFalse(s.blocks.contains(SOFT_X, SOFT_Y, SOFT_Z),
                "a zero hardness block breaks in one tick, which is how the fixture arms the delay");
        assertEquals(Combat.DESTROY_DELAY, s.players[0].destroyDelay,
                "a completed break arms the delay, which is the state under test");
    }

    private static float step(GameState s) {
        return Loadout.minePerTick(s, s.players[0], 0, SLOW_ITEM_ID);
    }

    @Test
    void theSlowBlockTakesManyTicksSoProgressIsReadable() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = scene(arena);

        float d = step(s);
        assertTrue(d > 0f && d < 0.2f,
                "the fixture block has to accrue in small readable steps, not instamine");
    }

    @Test
    void everyFreshClickBypassesTheDelayEvenOnTheBlockAlreadyBeingMined() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = scene(arena);
        armTheDelay(s, arena);
        float d = step(s);

        Simulation.tick(s, arena, slow(false), Input.NONE);
        Simulation.tick(s, arena, slow(true), Input.NONE);
        assertEquals(d, s.players[0].miningProgress, EPSILON,
                "a fresh click on a target the player was not already mining always bypassed"
                        + " the delay");

        Simulation.tick(s, arena, slow(false), Input.NONE);
        Simulation.tick(s, arena, slow(true), Input.NONE);

        assertEquals(2 * d, s.players[0].miningProgress, EPSILON,
                "the bypass keyed on the mining target changing, so the second click on the same"
                        + " block was swallowed while the delay ran down. Vanilla reaches"
                        + " startDestroyBlock from Minecraft.startAttack on every counted press"
                        + " and never consults destroyDelay there");
    }

    @Test
    void aHeldButtonStillPaysTheWholeDelay() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = scene(arena);
        armTheDelay(s, arena);
        float d = step(s);

        Simulation.tick(s, arena, slow(false), Input.NONE);
        Simulation.tick(s, arena, slow(true), Input.NONE);
        assertEquals(d, s.players[0].miningProgress, EPSILON);

        for (int t = 0; t < Combat.DESTROY_DELAY - 2; t++) {
            Simulation.tick(s, arena, slow(true), Input.NONE);
            assertEquals(d, s.players[0].miningProgress, EPSILON,
                    "the button never came up, so there is no new press and the delay stands");
        }
    }

    @Test
    void theDelayStillDrainsOnEveryMiningTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = scene(arena);
        armTheDelay(s, arena);

        int before = s.players[0].destroyDelay;
        Simulation.tick(s, arena, slow(true), Input.NONE);

        assertEquals(before - 1, s.players[0].destroyDelay,
                "bypassing the gate must not stop it counting down, or a spam clicker would"
                        + " leave the delay armed forever");
    }

    @Test
    void aClickCountDoesNotMultiplyMiningProgress() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = scene(arena);
        float d = step(s);

        Simulation.tick(s, arena,
                slow(true).withClicks(new Clicks(7, 0, 0, 0, 0)), Input.NONE);

        assertEquals(d, s.players[0].miningProgress, EPSILON,
                "mining is vanilla's held continueDestroyBlock path; startDestroyBlock accrues"
                        + " nothing, so a burst of presses can never be worth more than one"
                        + " tick of progress");
    }
}
