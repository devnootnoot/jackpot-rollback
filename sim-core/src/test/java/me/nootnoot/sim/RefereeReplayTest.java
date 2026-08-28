package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class RefereeReplayTest {
    private static final double GROUND_Y = 64.0;
    private static final int N = 500;

    @Test
    void refereeReproducesGroundTruthFromRedundantInterleavedStreams() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0xC0DECAFEL, N);

        GameState gt = HarnessScenarios.duel(arena);
        List<Long> truth = new ArrayList<>(N);
        List<Input> s0 = new ArrayList<>(N);
        List<Input> s1 = new ArrayList<>(N);
        for (Input[] f : log.frames) {
            Simulation.tick(gt, arena, f[0], f[1]);
            truth.add(Checksum.of(gt));
            s0.add(f[0]);
            s1.add(f[1]);
        }

        RefereeSession ref = new RefereeSession(arena, HarnessScenarios.duel(arena));
        feedInterleavedWithRedundancy(ref, s0, s1);

        assertEquals(N, ref.confirmedFrame(), "referee must confirm every frame");
        assertEquals(truth.get(N - 1).longValue(), ref.checksum(),
                "referee final state must match ground truth bit-for-bit");
        for (int n = 1; n <= N; n++) {
            assertEquals(truth.get(n - 1).longValue(), ref.checksumAt(n),
                    "referee checksum diverged at frame " + n);
        }
    }

    @Test
    void refereeWaitsForGapsAndIsIdempotentToResends() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0x1234ABCDL, 120);
        GameState gt = HarnessScenarios.duel(arena);
        List<Input> s0 = new ArrayList<>();
        List<Input> s1 = new ArrayList<>();
        for (Input[] f : log.frames) {
            Simulation.tick(gt, arena, f[0], f[1]);
            s0.add(f[0]);
            s1.add(f[1]);
        }
        long truthFinal = Checksum.of(gt);

        RefereeSession ref = new RefereeSession(arena, HarnessScenarios.duel(arena));
        ref.ingest(0, 60, new ArrayList<>(s0.subList(60, 120)));
        ref.ingest(1, 60, new ArrayList<>(s1.subList(60, 120)));
        assertEquals(0, ref.confirmedFrame(), "no frame can confirm while [0,60) is missing");

        ref.ingest(0, 60, new ArrayList<>(s0.subList(60, 120)));
        ref.ingest(0, 0, new ArrayList<>(s0.subList(0, 60)));
        assertEquals(0, ref.confirmedFrame(), "slot 1 still missing [0,60)");

        ref.ingest(1, 0, new ArrayList<>(s1.subList(0, 60)));
        assertEquals(120, ref.confirmedFrame());
        assertEquals(truthFinal, ref.checksum());
    }

    @Test
    void theFrameLeadWindowDoesNotWrapAtIntegerOverflow() {
        assertTrue(RefereeSession.withinLead(0, 0), "the confirmed frame itself is in the window");
        assertTrue(RefereeSession.withinLead(0, RefereeSession.MAX_FRAME_LEAD),
                "and so is the last frame of the lead the referee allows");
        assertFalse(RefereeSession.withinLead(0, RefereeSession.MAX_FRAME_LEAD + 1),
                "one past the lead is a forged index");
        assertFalse(RefereeSession.withinLead(0, -1), "and so is a negative one");

        int late = Integer.MAX_VALUE - 1;
        assertTrue(RefereeSession.withinLead(late, late),
                "computed in int, confirmed + MAX_FRAME_LEAD overflows negative this far out and"
                        + " the window silently rejects every honest frame the session has left");
        assertTrue(RefereeSession.withinLead(late, Integer.MAX_VALUE),
                "the frame right after it is still inside the lead");
    }

    @Test
    void theRefereeKeepsABoundedChecksumHistoryRatherThanOnePerConfirmedFrame() throws Exception {
        java.lang.reflect.Field f = RefereeSession.class.getDeclaredField("checksums");
        assertEquals(long[].class, f.getType(),
                "a List<Long> here is one boxed Long per confirmed frame for the life of the"
                        + " session, and a session is only over when the match is");

        RefereeSession ref = new RefereeSession(Arena.flat(GROUND_Y),
                HarnessScenarios.duel(Arena.flat(GROUND_Y)));
        f.setAccessible(true);
        assertEquals(RefereeSession.CHECKSUM_HISTORY, ((long[]) f.get(ref)).length,
                "the history is a fixed ring, so its size cannot depend on how long the match ran");
        assertTrue(RefereeSession.CHECKSUM_HISTORY > RefereeSession.MAX_FRAME_LEAD,
                "the ring has to outlast the furthest frame a peer is allowed to be ahead by, or"
                        + " a checksum can age out before the frame that produced it is confirmed");
    }

    private static void feedInterleavedWithRedundancy(RefereeSession ref, List<Input> s0, List<Input> s1) {
        int max = Protocol.MAX_INPUTS_PER_PACKET;
        int step = Math.max(1, max / 2);
        int n = Math.max(s0.size(), s1.size());
        for (int base = 0; base < n; base += step) {
            sendRun(ref, 0, s0, base, max);
            sendRun(ref, 1, s1, base, max);
        }
        sendRun(ref, 0, s0, Math.max(0, s0.size() - max), max);
        sendRun(ref, 1, s1, Math.max(0, s1.size() - max), max);
    }

    private static void sendRun(RefereeSession ref, int slot, List<Input> stream, int base, int max) {
        if (base >= stream.size()) {
            return;
        }
        int to = Math.min(stream.size(), base + max);
        ref.ingest(slot, base, new ArrayList<>(stream.subList(base, to)));
    }
}
