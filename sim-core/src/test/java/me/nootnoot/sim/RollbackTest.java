package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class RollbackTest {
    private static final double GROUND_Y = 64.0;
    private static final int N = 500;

    private record Truth(List<Input> local, List<Input> remote, List<Long> checksums) {
    }

    private static Truth groundTruth(int localPlayer, long seed) {
        InputLog log = InputLog.generated(seed, N);
        Arena arena = Arena.flat(GROUND_Y);
        GameState gt = HarnessScenarios.duel(arena);
        List<Input> local = new ArrayList<>();
        List<Input> remote = new ArrayList<>();
        List<Long> checksums = new ArrayList<>();
        for (Input[] frame : log.frames) {
            Input in0 = frame[0];
            Input in1 = frame[1];
            local.add(localPlayer == 0 ? in0 : in1);
            remote.add(localPlayer == 0 ? in1 : in0);
            Simulation.tick(gt, arena, in0, in1);
            checksums.add(Checksum.of(gt));
        }
        return new Truth(local, remote, checksums);
    }

    private static RollbackController runWithDelay(int localPlayer, long seed, int delay,
                                                   List<Input> remoteOverride) {
        Truth truth = groundTruth(localPlayer, seed);
        List<Input> remoteStream = remoteOverride != null ? remoteOverride : truth.remote;

        Arena arena = Arena.flat(GROUND_Y);
        RollbackController c = new RollbackController(arena, localPlayer, HarnessScenarios.duel(arena), 256);

        for (int step = 0; step < N; step++) {
            int deliverFrame = step - delay;
            if (deliverFrame >= 0) {
                c.onRemoteInput(deliverFrame, remoteStream.get(deliverFrame));
            }
            c.advance(truth.local.get(step));
        }

        for (int frame = Math.max(0, N - delay); frame < N; frame++) {
            c.onRemoteInput(frame, remoteStream.get(frame));
        }

        assertEquals(N, c.head());
        assertEquals(N, c.confirmedFrame(), "not all remote frames were confirmed");
        return c;
    }

    @Test
    void rollbackConvergesToGroundTruthPlayer0() {
        Truth truth = groundTruth(0, 0xC0FFEEL);
        RollbackController c = runWithDelay(0, 0xC0FFEEL, 8, null);
        assertEquals(truth.checksums.get(N - 1).longValue(), c.checksum(),
                "rolled-back state did not match ground truth");
        assertTrue(c.rollbackCount() > 0, "expected rollbacks to occur with delayed, varying remote input");
    }

    @Test
    void rollbackConvergesToGroundTruthPlayer1() {
        Truth truth = groundTruth(1, 0xBEEFL);
        RollbackController c = runWithDelay(1, 0xBEEFL, 12, null);
        assertEquals(truth.checksums.get(N - 1).longValue(), c.checksum(),
                "rolled-back state did not match ground truth (as player 1)");
        assertTrue(c.rollbackCount() > 0);
    }

    @Test
    void deepPredictionWindowStillConverges() {
        Truth truth = groundTruth(0, 0x1234L);
        int deeperThanTheRefillPaysFor = RollbackController.RESIM_FRAMES_PER_ADVANCE + 3;
        RollbackController c = runWithDelay(0, 0x1234L, deeperThanTheRefillPaysFor, null);
        assertEquals(truth.checksums.get(N - 1).longValue(), c.checksum());
        assertTrue(c.rollbackCount() > 0);
    }

    @Test
    void correctPredictionsCauseNoRollback() {
        List<Input> constantNone = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            constantNone.add(Input.NONE);
        }

        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0x55L, N);
        GameState gt = HarnessScenarios.duel(arena);
        List<Input> local = new ArrayList<>();
        for (Input[] frame : log.frames) {
            local.add(frame[0]);
            Simulation.tick(gt, arena, frame[0], Input.NONE);
        }
        long truthFinal = Checksum.of(gt);

        RollbackController c = new RollbackController(arena, 0, HarnessScenarios.duel(arena), 256);
        for (int step = 0; step < N; step++) {
            int deliver = step - 10;
            if (deliver >= 0) {
                c.onRemoteInput(deliver, constantNone.get(deliver));
            }
            c.advance(local.get(step));
        }
        for (int frame = N - 10; frame < N; frame++) {
            c.onRemoteInput(frame, Input.NONE);
        }

        assertEquals(truthFinal, c.checksum());
        assertEquals(0, c.rollbackCount(), "perfect predictions should never roll back");
    }

    @Test
    void zeroDelayNeverPredicts() {
        Truth truth = groundTruth(0, 0x99L);
        Arena arena = Arena.flat(GROUND_Y);
        RollbackController c = new RollbackController(arena, 0, HarnessScenarios.duel(arena), 256);
        for (int step = 0; step < N; step++) {
            c.onRemoteInput(step, truth.remote.get(step));
            c.advance(truth.local.get(step));
        }
        assertEquals(truth.checksums.get(N - 1).longValue(), c.checksum());
        assertEquals(0, c.rollbackCount(), "with zero delay there is nothing to predict");
    }

    @Test
    void aBacklogCoalescesIntoASingleRollback() {
        Arena arena = Arena.flat(GROUND_Y);
        RollbackController c = new RollbackController(arena, 0, HarnessScenarios.duel(arena), 512);
        InputLog log = InputLog.generated(0xBACC1067L, 200);

        for (int f = 0; f < 120; f++) {
            c.advance(log.frames.get(f)[0]);
        }
        int before = c.rollbackCount();

        c.beginInputBatch();
        for (int f = 0; f < 120; f++) {
            c.onRemoteInput(f, log.frames.get(f)[1]);
        }
        c.endInputBatch();

        assertEquals(before + 1, c.rollbackCount(),
                "a 120-frame backlog must roll back once, not once per frame");
        assertEquals(120, c.confirmedFrame(), "every delivered frame should now be confirmed");
    }

    @Test
    void coalescedRollbackMatchesPerFrameDelivery() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0x5EED1L, 160);

        RollbackController batched = new RollbackController(arena, 0, HarnessScenarios.duel(arena), 512);
        RollbackController perFrame = new RollbackController(arena, 0, HarnessScenarios.duel(arena), 512);

        for (int f = 0; f < 80; f++) {
            batched.advance(log.frames.get(f)[0]);
            perFrame.advance(log.frames.get(f)[0]);
        }

        batched.beginInputBatch();
        for (int f = 0; f < 80; f++) {
            batched.onRemoteInput(f, log.frames.get(f)[1]);
        }
        batched.endInputBatch();

        for (int f = 0; f < 80; f++) {
            perFrame.onRemoteInput(f, log.frames.get(f)[1]);
        }

        assertEquals(perFrame.checksum(), batched.checksum(),
                "coalescing changes only which frames were predicted, never a confirmed state");
        assertTrue(batched.rollbackCount() < perFrame.rollbackCount(),
                "the batched path should do strictly less re-simulation");
    }
}
