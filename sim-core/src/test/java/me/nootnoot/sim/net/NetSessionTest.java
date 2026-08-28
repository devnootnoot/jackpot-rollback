package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.Combat;
import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class NetSessionTest {
    private static final double GROUND_Y = 64.0;
    private static final int N = 400;

    private static long groundTruthFinal(long seed) {
        InputLog log = InputLog.generated(seed, N);
        Arena arena = Arena.flat(GROUND_Y);
        GameState gt = HarnessScenarios.duel(arena);
        for (Input[] f : log.frames) {
            Simulation.tick(gt, arena, f[0], f[1]);
        }
        return Checksum.of(gt);
    }

    @Test
    void convergesToGroundTruthOverLossyJitteryLink() {
        long seed = 0xC0FFEEL;
        long truth = groundTruthFinal(seed);
        InputLog log = InputLog.generated(seed, N);
        Arena arena = Arena.flat(GROUND_Y);

        LoopbackNetwork net = new LoopbackNetwork(0x5151L, 3, 4, 0.2);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 256);

        for (int iter = 0; iter < N * 3 && (s0.head() < N || s1.head() < N); iter++) {
            net.step();
            if (s0.head() < N) s0.update(log.frames.get(s0.head())[0]); else s0.flush();
            if (s1.head() < N) s1.update(log.frames.get(s1.head())[1]); else s1.flush();
        }

        for (int i = 0; i < 5000 && (s0.confirmedFrame() < N || s1.confirmedFrame() < N); i++) {
            net.step();
            s0.flush();
            s1.flush();
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
        assertEquals(N, s0.confirmedFrame(), "s0 did not confirm all frames");
        assertEquals(N, s1.confirmedFrame(), "s1 did not confirm all frames");
        assertEquals(truth, s0.checksum(), "s0 did not converge to ground truth");
        assertEquals(truth, s1.checksum(), "s1 did not converge to ground truth");
        assertTrue(s0.rollbackCount() > 0, "expected s0 to roll back under prediction");
        assertTrue(s1.rollbackCount() > 0, "expected s1 to roll back under prediction");
    }

    @Test
    void timeSyncCapsDriftWhenOnePeerTicksFaster() {
        long seed = 0xC0FFEEL;
        long truth = groundTruthFinal(seed);
        InputLog log = InputLog.generated(seed, N);
        Arena arena = Arena.flat(GROUND_Y);

        LoopbackNetwork net = new LoopbackNetwork(0x55L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 256);

        int maxDrift = 0;
        int s0Drives = 0;
        for (int iter = 0; iter < N * 4 && (s0.head() < N || s1.head() < N); iter++) {
            net.step();
            if (s0.head() < N) { s0.update(log.frames.get(s0.head())[0]); s0Drives++; } else s0.flush();
            if (iter % 2 == 0 && s0.head() < N) { s0.update(log.frames.get(s0.head())[0]); s0Drives++; }
            if (s1.head() < N) s1.update(log.frames.get(s1.head())[1]); else s1.flush();
            maxDrift = Math.max(maxDrift, Math.abs(s0.head() - s1.head()));
        }
        for (int i = 0; i < 5000 && (s0.confirmedFrame() < N || s1.confirmedFrame() < N); i++) {
            net.step();
            s0.flush();
            s1.flush();
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());

        assertTrue(maxDrift < 400,
                "drift grew toward the ring ceiling — time-sync not correcting at all: maxDrift=" + maxDrift);
        assertTrue(s0.head() >= 0.90 * s0Drives,
                "the faster peer was held below 90% of its drive rate: head=" + s0.head() + " drives=" + s0Drives);

        assertEquals(s0.confirmedFrame(), s1.confirmedFrame(), "peers disagree on how much is confirmed");
        assertEquals(Checksum.of(s0.confirmedState()), Checksum.of(s1.confirmedState()),
                "the two peers diverged while the slower one caught up");
    }

    @Test
    void aClientThatFreezesCatchesBackUpInsteadOfStayingBehindForever() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0xF0F0L, N);

        LoopbackNetwork net = new LoopbackNetwork(0xF0F0L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 60; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
        }

        for (int i = 0; i < 100; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
        }
        int gapAtThaw = s0.head() - s1.head();
        assertTrue(gapAtThaw > 80, "the freeze did not actually open a gap: " + gapAtThaw);

        for (int i = 0; i < 200; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
        int gapAfter = Math.abs(s0.head() - s1.head());
        assertTrue(gapAfter < 16,
                "the frozen peer never caught up: gap was " + gapAtThaw + " at thaw, still " + gapAfter);
    }

    @Test
    void aFreezeIsClosedAlmostImmediatelyNotOverSeconds() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0xB0B0L, N);

        LoopbackNetwork net = new LoopbackNetwork(0xB0B0L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 60; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
        }
        for (int i = 0; i < 100; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
        }

        int gapBefore = s0.head() - s1.head();
        assertTrue(gapBefore > 80, "the freeze did not open a gap: " + gapBefore);

        net.step();
        int headBefore = s1.head();
        s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
        int firstJump = s1.head() - headBefore;

        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
        assertTrue(firstJump > 20,
                "one tick should burst tens of frames, not ramp; it advanced " + firstJump);
        assertTrue(s1.drainCatchUpFrames() > 0, "the burst was not reported to the renderer");

        int ticks = 1;
        while (ticks < 8 && s0.head() - s1.head() > 16) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
            ticks++;
        }
        assertTrue(s0.head() - s1.head() <= 16,
                "the gap was still " + (s0.head() - s1.head()) + " after " + ticks + " ticks");
    }

    @Test
    void aBurstAgainstAHoleyInputStreamDoesNotGrowThePredictionWindow() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0xD0D0L, N);

        LoopbackNetwork net = new LoopbackNetwork(0xD0D0L, 2, 3, 0.30);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 40; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
        }
        for (int i = 0; i < 120; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
        }

        int maxWindow = 0;
        for (int i = 0; i < 200; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
            maxWindow = Math.max(maxWindow, s1.head() - s1.confirmedFrame());
        }

        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
        assertTrue(maxWindow < 200,
                "the burst ran deep into prediction instead of stopping at known input: window=" + maxWindow);
    }

    @Test
    void bothPeersAgreeAcrossACatchUpBurst() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0xE0E0L, N);

        LoopbackNetwork net = new LoopbackNetwork(0xE0E0L, 1, 1, 0.05);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 40; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
        }
        for (int i = 0; i < 120; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
        }
        for (int i = 0; i < 400; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
        }
        for (int i = 0; i < 5000 && s0.confirmedFrame() != s1.confirmedFrame(); i++) {
            net.step();
            s0.flush();
            s1.flush();
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
        assertEquals(s0.confirmedFrame(), s1.confirmedFrame(), "peers disagree on how much is confirmed");
        assertEquals(Checksum.of(s0.confirmedState()), Checksum.of(s1.confirmedState()),
                "the peers diverged across the catch-up burst");
    }

    @Test
    void theBurstFillerDoesNotSprintThePlayerAcrossTheArena() {
        Arena arena = Arena.flat(GROUND_Y);
        Input sprintForward = new Input(true, false, false, false, false, true, false, false, false,
                0f, 0f, 0);

        LoopbackNetwork net = new LoopbackNetwork(0xA1A1L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 40; i++) {
            net.step();
            s0.update(sprintForward);
            s1.update(sprintForward);
        }
        double frozenAtX = s1.state().players[1].x;
        double frozenAtZ = s1.state().players[1].z;
        int headAtFreeze = s1.head();

        for (int i = 0; i < 100; i++) {
            net.step();
            s0.update(sprintForward);
        }

        for (int i = 0; i < 40; i++) {
            net.step();
            s0.update(sprintForward);
            s1.update(sprintForward);
        }

        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
        double dx = s1.state().players[1].x - frozenAtX;
        double dz = s1.state().players[1].z - frozenAtZ;
        double travelled = Math.sqrt(dx * dx + dz * dz);
        int framesRun = s1.head() - headAtFreeze;

        double ifAllSprint = framesRun * 0.28;
        assertTrue(travelled < ifAllSprint * 0.75,
                "the filler sprinted the frozen player " + String.format("%.1f", travelled)
                        + " blocks over " + framesRun + " frames; full sprint would be "
                        + String.format("%.1f", ifAllSprint));
    }

    @Test
    void aFrozenPlayerCorrectsFarLessThanTheOutageOnTheOpponentScreen() {
        Arena arena = Arena.flat(GROUND_Y);

        Input pressingUse = new Input(true, false, false, false, false, true, false, false, true,
                0f, 0f, 0);

        LoopbackNetwork net = new LoopbackNetwork(0xF1F1L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 40; i++) {
            net.step();
            s0.update(Input.NONE);
            s1.update(pressingUse);
        }

        for (int i = 0; i < 100; i++) {
            net.step();
            s0.update(Input.NONE);
        }

        double predictedX = s0.state().players[1].x;
        double predictedZ = s0.state().players[1].z;

        for (int i = 0; i < 60; i++) {
            net.step();
            s0.update(Input.NONE);
            s1.update(Input.NONE);
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
        double dx = s0.state().players[1].x - predictedX;
        double dz = s0.state().players[1].z - predictedZ;
        double correction = Math.sqrt(dx * dx + dz * dz);

        assertTrue(correction < 15.0,
                "the opponent had to correct the frozen player by "
                        + String.format("%.1f", correction) + " blocks, against a ~28 block outage");

        for (int i = 0; i < 5000 && s0.confirmedFrame() != s1.confirmedFrame(); i++) {
            net.step();
            s0.flush();
            s1.flush();
        }
        assertEquals(s0.confirmedFrame(), s1.confirmedFrame(), "peers never reached a common confirmed frame");
        assertEquals(Checksum.of(s0.confirmedState()), Checksum.of(s1.confirmedState()),
                "the peers diverged across the freeze");
    }

    @Test
    void predictionDoesNotRepeatTheOneShotActionsOfASilentPeer() {
        Input withOneShots = new Input(true, false, false, false, false, true, false, true, true,
                0f, 0f, 0)
                .withBlockAction(Input.BLOCK_PLACE, 1, 2, 3)
                .withUsePress(true)
                .withOffhandUsePress(true)
                .withDrop(true, true);

        Input predicted = withOneShots.heldOnly();
        assertEquals(Input.BLOCK_NONE, predicted.blockAction(), "a repeated prediction must not place blocks");
        assertFalse(predicted.dropItem(), "a repeated prediction must not drop items");
        assertFalse(predicted.dropStack(), "a repeated prediction must not drop stacks");
        assertFalse(predicted.usePress(), "a repeated prediction must not re-press use");
        assertFalse(predicted.offhandUsePress(),
                "the off-hand press is a one-shot exactly as the main-hand press is, so a silent"
                        + " peer must not have it repeated into a second throw");

        assertTrue(predicted.forward() && predicted.sprint() && predicted.attack() && predicted.use(),
                "held controls must survive the prediction");
    }

    @Test
    void theHealthyPeerIsNeverHeldWhileTheOtherCatchesUp() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0xC0C0L, N);

        LoopbackNetwork net = new LoopbackNetwork(0xC0C0L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 40; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
        }
        for (int i = 0; i < 100; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
        }

        int before = s0.head();
        int drives = 0;
        for (int i = 0; i < 200; i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            drives++;
            s1.update(log.frames.get(Math.min(s1.head(), N - 1))[1]);
        }
        int advanced = s0.head() - before;

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        assertTrue(advanced >= 0.90 * drives,
                "the healthy peer was held while the other recovered: advanced " + advanced + " of " + drives);
    }

    private static double distance(me.nootnoot.sim.RollbackController c, double fromX, double fromZ) {
        double dx = c.state().players[1].x - fromX;
        double dz = c.state().players[1].z - fromZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static Input varyingInput(int k) {
        boolean fwd = (k & 1) == 0;
        boolean sprint = (k & 2) == 0;
        boolean jump = (k % 5) == 0;
        boolean attack = (k % 3) == 0;
        float yaw = (k * 13 % 360) - 180f;
        return new Input(fwd, false, false, false, jump, sprint, false, attack, false, yaw, 0f, 0);
    }

    @Test
    void stallDoesNotResampleInputAndDesync() {
        Arena arena = Arena.flat(GROUND_Y);

        LoopbackNetwork net = new LoopbackNetwork(0x77L, 3, 3, 0.05);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 256);

        for (int iter = 0; iter < N * 6 && (s0.head() < N || s1.head() < N); iter++) {
            net.step();
            if (s0.head() < N) {
                s0.update(varyingInput(iter));
            } else {
                s0.flush();
            }
            if (iter % 2 == 0 && s0.head() < N) {
                s0.update(varyingInput(iter * 7 + 3));
            }
            if (s1.head() < N) {
                s1.update(varyingInput(iter * 3 + 1));
            } else {
                s1.flush();
            }
            if (iter % 3 == 0 && s1.head() < N) {
                s1.update(varyingInput(iter * 5 + 2));
            }
        }
        for (int i = 0; i < 5000 && (s0.confirmedFrame() < N || s1.confirmedFrame() < N)
                && !s0.aborted() && !s1.aborted(); i++) {
            net.step();
            s0.flush();
            s1.flush();
        }

        assertFalse(s0.aborted(), "s0 desynced/aborted: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 desynced/aborted: " + s1.abortReason());
        assertEquals(s0.checksum(), s1.checksum(), "peers diverged despite a clean link");
    }

    @Test
    void cleanLinkConvergesWithNoFalseDesync() {
        long seed = 0x1234L;
        long truth = groundTruthFinal(seed);
        InputLog log = InputLog.generated(seed, N);
        Arena arena = Arena.flat(GROUND_Y);

        LoopbackNetwork net = new LoopbackNetwork(0x7L, 2, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 256);

        for (int iter = 0; iter < N * 3 && (s0.head() < N || s1.head() < N); iter++) {
            net.step();
            if (s0.head() < N) s0.update(log.frames.get(s0.head())[0]); else s0.flush();
            if (s1.head() < N) s1.update(log.frames.get(s1.head())[1]); else s1.flush();
        }
        for (int i = 0; i < 2000 && (s0.confirmedFrame() < N || s1.confirmedFrame() < N); i++) {
            net.step();
            s0.flush();
            s1.flush();
        }

        assertFalse(s0.aborted());
        assertFalse(s1.aborted());
        assertEquals(truth, s0.checksum());
        assertEquals(truth, s1.checksum());
    }

    @Test
    void desyncIsDetectedAndBothPeersAbort() {
        long seed = 0xBEEFL;
        InputLog log = InputLog.generated(seed, N);
        Arena arena = Arena.flat(GROUND_Y);

        LoopbackNetwork net = new LoopbackNetwork(0x9L, 2, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256);

        GameState tampered = HarnessScenarios.duel(arena);
        tampered.players[0].x += 0.001;
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, tampered, 256);

        boolean aborted = false;
        for (int iter = 0; iter < N * 3 && (s0.head() < N || s1.head() < N) && !aborted; iter++) {
            net.step();
            if (s0.head() < N) s0.update(log.frames.get(s0.head())[0]); else s0.flush();
            if (s1.head() < N) s1.update(log.frames.get(s1.head())[1]); else s1.flush();
            aborted = s0.aborted() || s1.aborted();
        }

        for (int i = 0; i < 50; i++) {
            net.step();
            s0.flush();
            s1.flush();
        }

        assertTrue(s0.aborted(), "s0 should detect/receive the desync abort");
        assertTrue(s1.aborted(), "s1 should detect/receive the desync abort");
        assertTrue(s0.desyncFrame() >= 0 || s1.desyncFrame() >= 0, "a desync frame should be flagged");
    }

    @Test
    void abortsCleanlyWhenPeerNeverConnects() {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x1L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256);

        for (int i = 0; i < 1000 && !s0.aborted(); i++) {
            net.step();
            s0.update(Input.NONE);
        }
        assertTrue(s0.aborted(), "s0 should abort when the peer never connects");
        assertTrue(s0.abortReason().contains("never connected"), "wrong reason: " + s0.abortReason());
    }

    @Test
    void abortsCleanlyWhenPeerDropsMidMatch() {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x2L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 256);

        for (int i = 0; i < 50; i++) {
            net.step();
            s0.update(Input.NONE);
            s1.update(Input.NONE);
        }
        assertFalse(s0.aborted(), "s0 aborted early: " + s0.abortReason());

        for (int i = 0; i < 700 && !s0.aborted(); i++) {
            net.step();
            s0.update(Input.NONE);
        }
        assertTrue(s0.aborted(), "s0 should abort when the peer drops mid-match");
        assertTrue(s0.abortReason().contains("disconnected"), "wrong reason: " + s0.abortReason());
    }

    @Test
    void localFreeRunsWhilePeerFreezesThenBothRecover() {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x3L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 30; i++) {
            net.step();
            s0.update(Input.NONE);
            s1.update(Input.NONE);
        }
        int headBeforeFreeze = s0.head();

        for (int i = 0; i < 100; i++) {
            net.step();
            s0.update(Input.NONE);
        }
        assertFalse(s0.aborted(), "s0 aborted during a brief peer freeze: " + s0.abortReason());
        assertTrue(s0.head() - headBeforeFreeze >= 90,
                "s0 stalled during the peer freeze instead of free-running: advanced only "
                        + (s0.head() - headBeforeFreeze) + " frames");

        for (int i = 0; i < 600; i++) {
            net.step();
            s0.update(Input.NONE);
            s1.update(Input.NONE);
        }
        for (int i = 0; i < 5000 && s0.confirmedFrame() != s1.confirmedFrame(); i++) {
            net.step();
            s0.flush();
            s1.flush();
        }
        assertFalse(s0.aborted(), "s0 aborted after the peer recovered: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 aborted after recovering: " + s1.abortReason());
        assertEquals(s0.confirmedFrame(), s1.confirmedFrame(), "peers did not re-align after a freeze/recover");

        assertEquals(Checksum.of(s0.confirmedState()), Checksum.of(s1.confirmedState()),
                "peers diverged after a freeze/recover");
    }

    @Test
    void inputDelayCutsRollbacksAndStillConverges() {
        final int delay = 3;
        long seed = 0xC0FFEEL;
        InputLog log = InputLog.generated(seed, N);
        Arena arena = Arena.flat(GROUND_Y);

        GameState gt = HarnessScenarios.duel(arena);
        for (int f = 0; f < N; f++) {
            Input in0 = f >= delay ? log.frames.get(f - delay)[0] : Input.NONE;
            Input in1 = f >= delay ? log.frames.get(f - delay)[1] : Input.NONE;
            Simulation.tick(gt, arena, in0, in1);
        }
        long delayedTruth = Checksum.of(gt);

        int rollbacksNoDelay = runPair(arena, log, 0xABCL, 3, 0, 0.0, 0)[0].rollbackCount();
        NetSession[] delayed = runPair(arena, log, 0xABCL, 3, 0, 0.0, delay);

        assertFalse(delayed[0].aborted(), "s0 aborted: " + delayed[0].abortReason());
        assertFalse(delayed[1].aborted(), "s1 aborted: " + delayed[1].abortReason());
        assertEquals(delayedTruth, delayed[0].checksum(), "delayed s0 did not match the delayed-schedule truth");
        assertEquals(delayedTruth, delayed[1].checksum(), "delayed s1 did not match the delayed-schedule truth");
        assertTrue(rollbacksNoDelay > 0, "baseline (no delay) should have rollbacks to improve on");
        assertTrue(delayed[0].rollbackCount() < rollbacksNoDelay,
                "input delay should cut rollbacks: noDelay=" + rollbacksNoDelay + " delayed=" + delayed[0].rollbackCount());
    }

    private static NetSession[] runPair(Arena arena, InputLog log, long netSeed,
                                        int baseDelay, int jitter, double loss, int inputDelay) {
        LoopbackNetwork net = new LoopbackNetwork(netSeed, baseDelay, jitter, loss);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256, inputDelay);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 256, inputDelay);
        for (int iter = 0; iter < N * 3 && (s0.head() < N || s1.head() < N)
                && !s0.aborted() && !s1.aborted(); iter++) {
            net.step();
            if (s0.head() < N) s0.update(log.frames.get(s0.head())[0]); else s0.flush();
            if (s1.head() < N) s1.update(log.frames.get(s1.head())[1]); else s1.flush();
        }
        for (int i = 0; i < 5000
                && (s0.confirmedFrame() < N || s1.confirmedFrame() < N)
                && !s0.aborted() && !s1.aborted(); i++) {
            net.step();
            s0.flush();
            s1.flush();
        }
        return new NetSession[]{s0, s1};
    }

    @Test
    void asymmetricInputDelayStillConvergesBitIdentically() {
        long seed = 0xDE1A7L;
        InputLog log = InputLog.generated(seed, N);
        Arena arena = Arena.flat(GROUND_Y);

        LoopbackNetwork net = new LoopbackNetwork(0x9A9AL, 2, 3, 0.1);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256, 0);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 256, 3);

        for (int iter = 0; iter < N * 4 && (s0.head() < N || s1.head() < N); iter++) {
            net.step();
            if (s0.head() < N) {
                s0.update(log.frames.get(s0.head())[0]);
            } else {
                s0.flush();
            }
            if (s1.head() < N) {
                s1.update(log.frames.get(s1.head())[1]);
            } else {
                s1.flush();
            }
        }
        for (int i = 0; i < 5000 && (s0.confirmedFrame() < N || s1.confirmedFrame() < N); i++) {
            net.step();
            s0.flush();
            s1.flush();
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
        assertEquals(N, s0.confirmedFrame(), "s0 did not confirm all frames");
        assertEquals(N, s1.confirmedFrame(), "s1 did not confirm all frames");
        assertEquals(s0.checksum(), s1.checksum(),
                "peers running different input delays must still confirm identical states");
    }

    @Test
    void asymmetricInputDelayDoesNotBiasTimeSync() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0x9B9BL, N);

        LoopbackNetwork net = new LoopbackNetwork(0x9B9BL, 2, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 256, 0);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 256, 3);

        int maxGap = 0;
        for (int iter = 0; iter < 600; iter++) {
            net.step();
            if (s0.head() < N) {
                s0.update(log.frames.get(s0.head())[0]);
            } else {
                s0.flush();
            }
            if (s1.head() < N) {
                s1.update(log.frames.get(s1.head())[1]);
            } else {
                s1.flush();
            }
            maxGap = Math.max(maxGap, Math.abs(s0.head() - s1.head()));
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
        assertTrue(s0.head() > 200, "the delay-0 peer was starved by the delay bias: head=" + s0.head());
        assertTrue(s1.head() > 200, "the delay-3 peer was starved: head=" + s1.head());
        assertTrue(maxGap < 32, "heads drifted apart on a clean link: " + maxGap);
    }

    @Test
    void aStarvedPeerNeverFreezesTheLocalPlayer() {
        InputLog log = InputLog.generated(0xF0F0L, N);
        Arena arena = Arena.flat(GROUND_Y);

        LoopbackNetwork net = new LoopbackNetwork(0x77L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 10; i++) {
            net.step();
            s0.update(log.frames.get(s0.head())[0]);
            s1.update(log.frames.get(s1.head())[1]);
        }

        int drives = 0;
        int startHead = s0.head();
        int maxWindow = 0;
        for (int iter = 0; iter < 360 && s0.head() < N - 1; iter++) {
            net.step();
            s0.update(log.frames.get(s0.head())[0]);
            drives++;
            maxWindow = Math.max(maxWindow, s0.head() - s0.confirmedFrame());
            if (iter % 12 == 0 && s1.head() < N - 1) {
                s1.update(log.frames.get(s1.head())[1]);
            }
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());

        assertTrue(maxWindow > 32,
                "the scenario never reached the regime the old escape hatch fired in: maxWindow=" + maxWindow);
        int advanced = s0.head() - startHead;
        assertTrue(advanced >= 0.90 * drives,
                "the good-connection peer was frozen by a starved opponent: advanced=" + advanced
                        + " of " + drives + " drives");
    }

    @Test
    void aFrozenPeerIsForfeitedAndTheSurvivorNeverStalls() {
        InputLog log = InputLog.generated(0xBEEFL, N);
        Arena arena = Arena.flat(GROUND_Y);

        LoopbackNetwork net = new LoopbackNetwork(0x33L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 20; i++) {
            net.step();
            s0.update(log.frames.get(s0.head())[0]);
            s1.update(log.frames.get(s1.head())[1]);
        }

        int startHead = s0.head();
        int drives = 0;
        for (int i = 0; i < 500 && !s0.aborted(); i++) {
            net.step();
            s0.update(log.frames.get(Math.min(s0.head(), N - 1))[0]);
            drives++;
        }

        assertTrue(s0.aborted(), "s0 never forfeited a peer that went silent");
        assertTrue(s0.peerDisconnected(), "the freeze must resolve as a disconnect, not a no-contest");
        int advanced = s0.head() - startHead;
        assertTrue(advanced >= 0.95 * drives,
                "the survivor stalled while waiting out the forfeit: advanced=" + advanced
                        + " of " + drives + " drives");
    }

    @Test
    void predictionDecaysToNeutralWhileThePeerIsSilent() {
        Arena arena = Arena.flat(GROUND_Y);
        Input sprintForward = new Input(true, false, false, false, false, true, false, false, false,
                0f, 0f, 0);

        me.nootnoot.sim.RollbackController c =
                new me.nootnoot.sim.RollbackController(arena, 0, HarnessScenarios.duel(arena), 512);
        for (int f = 0; f < 4; f++) {
            c.onRemoteInput(f, sprintForward);
            c.advance(Input.NONE);
        }

        double beforeX = c.state().players[1].x;
        double beforeZ = c.state().players[1].z;
        for (int f = 0; f < 4; f++) {
            c.advance(Input.NONE);
        }
        double movedWhilePredicting = distance(c, beforeX, beforeZ);
        assertTrue(movedWhilePredicting > 0.05,
                "repeat-last should still be carrying them forward inside the decay window: moved "
                        + movedWhilePredicting);

        for (int f = 0; f < 60; f++) {
            c.advance(Input.NONE);
        }
        double settledX = c.state().players[1].x;
        double settledZ = c.state().players[1].z;
        c.advance(Input.NONE);
        c.advance(Input.NONE);
        double movedAfterDecay = distance(c, settledX, settledZ);

        assertTrue(movedAfterDecay < 0.02,
                "a long-silent peer should coast to a stop, not keep sprinting: moved " + movedAfterDecay);
    }
}
