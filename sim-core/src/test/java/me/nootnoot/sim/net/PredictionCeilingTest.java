package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class PredictionCeilingTest {
    private static final double GROUND_Y = 64.0;

    private static NetSession session(int ringCapacity) {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x51L, 1, 0, 0.0);
        return new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), ringCapacity);
    }

    @Test
    void theForfeitAlwaysFiresBeforeTheSurvivorStalls() {
        for (int ring : new int[]{256, 512, 1024, 2048}) {
            NetSession s = session(ring);
            assertTrue(s.peerTimeoutTicks() < s.freeRunCeiling(),
                    "the free-run ceiling has to sit ABOVE the peer timeout or a player whose"
                            + " opponent crashed watches a frozen game until the forfeit lands."
                            + " ring=" + ring + " ceiling=" + s.freeRunCeiling()
                            + " timeout=" + s.peerTimeoutTicks());
        }
    }

    @Test
    void theCeilingLeavesTheRingItsStallMargin() {
        for (int ring : new int[]{64, 256, 512, 1024, 2048}) {
            NetSession s = session(ring);
            assertTrue(s.freeRunCeiling() <= ring - 32,
                    "head may never outrun confirmed by more than the ring can still roll back to:"
                            + " ring=" + ring + " ceiling=" + s.freeRunCeiling());
            assertTrue(s.freeRunCeiling() > 0, "a zero ceiling would stall at frame one");
        }
    }

    @Test
    void aBigRingDoesNotBuyAnUnboundedFreeRun() {
        assertEquals(session(1024).freeRunCeiling(), session(65536).freeRunCeiling(),
                "past the point the prediction cap bites, a wider ring only costs memory: every"
                        + " frame between confirmed and head is a retained GameState copy, and a"
                        + " frame free-run past the cap is a frame that has to be re-simulated"
                        + " when the peer's input finally lands");
    }

    @Test
    void aSilentPeerCostsNoRollbacksWhileTheSurvivorRunsOut() {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x52L, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 1024);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 1024);

        for (int i = 0; i < 40; i++) {
            net.step();
            s0.update(Input.NONE);
            s1.update(Input.NONE);
        }
        int settled = s0.rollbackCount();

        int drives = 0;
        for (int i = 0; i < 600 && !s0.aborted(); i++) {
            net.step();
            s0.update(Input.NONE);
            drives++;
        }

        assertTrue(s0.aborted(), "the peer went silent and was never forfeited");
        assertEquals(settled, s0.rollbackCount(),
                "nothing arrives from a dead peer, so nothing can contradict the prediction: the"
                        + " free run that the forfeit window allows costs one sim tick per host"
                        + " tick and not one re-simulation of the whole window");
        assertTrue(drives > s0.peerTimeoutTicks() - 8 && drives < s0.peerTimeoutTicks() + 8,
                "the forfeit has to land on the peer timeout, not on the prediction cap: drives="
                        + drives + " timeout=" + s0.peerTimeoutTicks());
    }
}
