package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class RoundResetContainerTest {
    private static final double GROUND_Y = 64.0;

    private static final int CONTAINER_ID = 1;
    private static final int CELL_X = 0;
    private static final int CELL_Y = 64;
    private static final int CELL_Z = 2;

    private static final int FULL_CELL = 0;
    private static final int SPARE_CELL = 3;
    private static final int FULL_COUNT = 5;
    private static final int SPARE_COUNT = 2;

    private static final int SHULKER_ITEM_ID = 9002;
    private static final int LOOSE_CONTAINER_ID = 2;
    private static final int SHULKER_SLOT = 1;
    private static final int SHULKER_X = 1;
    private static final int SHULKER_Y = 64;
    private static final int SHULKER_Z = 2;
    private static final int SHULKER_FRAME = 3;

    private static final int KILL_FRAME = 6;
    private static final int FRAMES = 200;
    private static final int DELAY = 8;

    private static GameState scenario(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        s.roundsTarget = 2;

        PlayerState a = s.players[0];
        a.x = 0.0;
        a.z = 0.0;
        a.yaw = -90f;

        PlayerState v = s.players[1];
        v.x = 2.0;
        v.z = 0.0;
        v.yaw = 90f;
        v.health = 1f;

        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1, TestKit.item().itemId(9000).maxStack(1).melee(100f, 1.6f)
                .flags(ItemDict.FLAG_SWORD));
        int cargo = kit.add(TestKit.item().itemId(9001).maxStack(64));

        Container c = new Container();
        c.entry[FULL_CELL] = cargo;
        c.count[FULL_CELL] = FULL_COUNT;
        c.entry[SPARE_CELL] = cargo;
        c.count[SPARE_CELL] = SPARE_COUNT;
        s.containers.put(CONTAINER_ID, c);
        s.blockContainers.put(BlockStore.key(CELL_X, CELL_Y, CELL_Z), CONTAINER_ID);
        s.nextContainerId = CONTAINER_ID + 1;
        for (java.util.Map.Entry<Integer, Container> e : s.containers.entrySet()) {
            s.roundInitialContainers.put(e.getKey(), e.getValue().copy());
        }
        s.roundInitial = new PlayerState[]{s.players[0].copy(), s.players[1].copy()};
        return s;
    }

    private static Input local(int frame) {
        return switch (frame) {
            case 0 -> Input.NONE.withBlockAction(Input.BLOCK_OPEN_CONTAINER, CELL_X, CELL_Y, CELL_Z)
                    .withClicks(new Clicks(0, 1, 0, 0, 0));
            case 1 -> Input.NONE.withInvAction(Input.INV_CONTAINER_TAKE, FULL_CELL, 5);
            case 2 -> Input.NONE.withInvAction(Input.INV_CONTAINER_TAKE, SPARE_CELL, 6);
            case KILL_FRAME -> new Input(false, false, false, false, false, false, false, true,
                    false, -90f, 0f, 0).withMeleeHit(true);
            default -> Input.NONE;
        };
    }

    private static Input remote(int frame) {
        boolean forward = frame % 3 == 0;
        boolean jump = frame % 5 == 0;
        return new Input(forward, false, false, false, jump, false, false, false, false,
                90f + (frame % 7) * 3f, 0f, 0);
    }

    private static Container seedOf(Arena arena) {
        return scenario(arena).roundInitialContainers.get(CONTAINER_ID);
    }

    private static void assertMatches(Container expected, Container actual, String why) {
        assertNotNull(actual, why);
        assertArrayEquals(expected.entry, actual.entry, why);
        assertArrayEquals(expected.count, actual.count, why);
        assertArrayEquals(expected.damage, actual.damage, why);
    }

    @Test
    void aRoundResetRestoresContainerContentsIdenticallyAfterRollbacks() {
        Arena arena = Arena.flat(GROUND_Y);
        Container seed = seedOf(arena);

        GameState truth = scenario(arena);
        List<Long> checksums = new ArrayList<>(FRAMES);
        boolean emptiedInRoundOne = false;
        for (int frame = 0; frame < FRAMES; frame++) {
            Simulation.tick(truth, arena, local(frame), remote(frame));
            if (frame == KILL_FRAME) {
                emptiedInRoundOne =
                        truth.containers.get(CONTAINER_ID).entry[FULL_CELL] == ItemDict.NONE;
            }
            checksums.add(Checksum.of(truth));
        }

        assertTrue(emptiedInRoundOne,
                "the scenario must actually drain the container during round one,"
                        + " otherwise the reset has nothing to restore");
        assertEquals(1, truth.roundWinsP0, "round one must have been won");
        assertEquals(0, truth.roundResetCountdown, "the reset must have completed inside the window");
        assertMatches(seed, truth.containers.get(CONTAINER_ID),
                "a round reset must restore the container to its frame-0 contents");

        RollbackController c = new RollbackController(arena, 0, scenario(arena), 256);
        for (int frame = 0; frame < FRAMES; frame++) {
            int deliver = frame - DELAY;
            if (deliver >= 0) {
                c.onRemoteInput(deliver, remote(deliver));
            }
            c.advance(local(frame));
        }
        for (int frame = Math.max(0, FRAMES - DELAY); frame < FRAMES; frame++) {
            c.onRemoteInput(frame, remote(frame));
        }

        assertEquals(FRAMES, c.confirmedFrame(), "not all remote frames were confirmed");
        assertTrue(c.rollbackCount() > 0, "the run must have actually rolled back");
        assertMatches(seed, c.state().containers.get(CONTAINER_ID),
                "rolling back across a round reset must land on the same restored contents");
        assertMatches(seed, c.state().roundInitialContainers.get(CONTAINER_ID),
                "the round seed itself must survive a rollback unchanged");
        assertEquals(checksums.get(FRAMES - 1).longValue(), c.checksum(),
                "the rolled-back state diverged from ground truth across the round reset");
    }

    @Test
    void aPeerThatSkippedTheRoundSeedDivergesAtTheFirstReset() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState seeded = scenario(arena);
        GameState unseeded = scenario(arena);
        unseeded.roundInitialContainers.clear();

        assertNotEquals(Checksum.of(seeded), Checksum.of(unseeded),
                "a missing round seed must be visible to the checksum at frame 0,"
                        + " not only once round two opens the container");

        for (int frame = 0; frame < FRAMES; frame++) {
            Simulation.tick(seeded, arena, local(frame), remote(frame));
            Simulation.tick(unseeded, arena, local(frame), remote(frame));
        }

        assertMatches(seedOf(arena), seeded.containers.get(CONTAINER_ID),
                "the seeded peer must restore the contents");
        assertNull(unseeded.containers.get(CONTAINER_ID),
                "the unseeded peer resets to an empty table: this is the cross-play desync");
        assertNotEquals(Checksum.of(seeded), Checksum.of(unseeded),
                "the two peers must not agree on a checksum while their containers differ");
    }

    @Test
    void aContainerCreatedDuringARoundDoesNotSurviveTheReset() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = scenario(arena);
        TestKit.of(s).give(0, SHULKER_SLOT, 1, TestKit.item().itemId(SHULKER_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER)
                .containerSeed(LOOSE_CONTAINER_ID));
        int seededRows = s.roundInitialContainers.size();

        boolean createdInRoundOne = false;
        for (int frame = 0; frame < FRAMES; frame++) {
            Simulation.tick(s, arena,
                    frame == SHULKER_FRAME ? placeShulker() : local(frame), remote(frame));
            if (frame == SHULKER_FRAME) {
                createdInRoundOne = s.containers.containsKey(LOOSE_CONTAINER_ID);
            }
        }

        assertTrue(createdInRoundOne,
                "the scenario must actually create a container mid-round,"
                        + " otherwise the reset has nothing to drop");
        assertEquals(1, s.roundWinsP0, "round one must have been won");
        assertEquals(0, s.roundResetCountdown, "the reset must have completed inside the window");
        assertFalse(s.containers.containsKey(LOOSE_CONTAINER_ID),
                "a container built during round one must not still exist in round two");
        assertEquals(seededRows, s.containers.size(),
                "after a reset the container table must be exactly the round seed");
        assertMatches(seedOf(arena), s.containers.get(CONTAINER_ID),
                "clearing the table must not cost the seeded container its contents");
    }

    private static Input placeShulker() {
        return Input.NONE.withHeldSlot(SHULKER_SLOT)
                .withBlockAction(Input.BLOCK_PLACE, SHULKER_X, SHULKER_Y, SHULKER_Z)
                .withClicks(new Clicks(0, 1, 0, 0, 0));
    }

    @Test
    void copyDoesNotAliasTheRoundSeed() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = scenario(arena);
        GameState g = s.copy();

        assertNotSame(s.roundInitialContainers, g.roundInitialContainers);
        assertNotSame(s.roundInitialContainers.get(CONTAINER_ID),
                g.roundInitialContainers.get(CONTAINER_ID));

        g.roundInitialContainers.get(CONTAINER_ID).count[FULL_CELL] = 99;
        g.roundInitialContainers.put(CONTAINER_ID + 7, new Container());

        assertEquals(FULL_COUNT, s.roundInitialContainers.get(CONTAINER_ID).count[FULL_CELL],
                "writing through a snapshot must not reach the state it was taken from");
        assertEquals(1, s.roundInitialContainers.size(),
                "a snapshot must not be able to add rows to the state it was taken from");
    }

    @Test
    void checksumCoversTheRoundSeed() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = scenario(arena);
        GameState g = s.copy();
        assertEquals(Checksum.of(s), Checksum.of(g));

        g.roundInitialContainers.get(CONTAINER_ID).count[FULL_CELL] = FULL_COUNT + 1;
        assertNotEquals(Checksum.of(s), Checksum.of(g),
                "two peers that seeded different round-reset contents must not agree on a checksum");
    }
}
