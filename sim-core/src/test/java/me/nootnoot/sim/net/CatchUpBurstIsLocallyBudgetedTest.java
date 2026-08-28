package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class CatchUpBurstIsLocallyBudgetedTest {
    private static final double GROUND_Y = 64.0;
    private static final int RING = 512;
    private static final int TICKS = 300;

    private static final class RunsAhead implements Transport {
        private final Deque<byte[]> inbox = new ArrayDeque<>();
        private int fed;

        @Override
        public void send(byte[] packet) {
        }

        @Override
        public List<byte[]> receive() {
            List<byte[]> out = new ArrayList<>(inbox);
            inbox.clear();
            return out;
        }

        void feedThrough(int frame) {
            while (fed <= frame) {
                int end = Math.min(frame, fed + Protocol.MAX_INPUTS_PER_PACKET - 1);
                List<Input> run = new ArrayList<>(end - fed + 1);
                for (int f = fed; f <= end; f++) {
                    run.add(Input.NONE);
                }
                inbox.add(Protocol.encode(new Message.InputFrames(fed, run, -1, 0)));
                fed = end + 1;
            }
        }
    }

    @Test
    void aPeerCannotDecideHowMuchSimulationThisSideRunsPerTick() {
        Arena arena = Arena.flat(GROUND_Y);
        RunsAhead peer = new RunsAhead();
        NetSession s = new NetSession(peer, 0, arena, HarnessScenarios.duel(arena), RING, 1);

        int worstTick = 0;
        int start = s.head();
        for (int t = 0; t < TICKS; t++) {
            peer.feedThrough(s.head() + RING / 2);
            int before = s.head();
            s.update(Input.NONE);
            worstTick = Math.max(worstTick, s.head() - before);
        }
        int total = s.head() - start;

        assertFalse(s.aborted(), "the flood must be absorbed, not fatal: " + s.abortReason());

        int ceiling = TICKS * (1 + NetSession.CATCHUP_BUDGET_REFILL) + s.freeRunCeiling();
        assertTrue(total <= ceiling,
                "the peer ran " + TICKS + " frames ahead of this side on every tick and this side"
                        + " simulated " + total + " frames to chase it, over a ceiling of " + ceiling
                        + ". catchUpTarget sized the burst off controller.knownRemoteFrames, which"
                        + " is a number the PEER produces, so the peer decided how much CPU the"
                        + " honest client spent per tick and could hold it there for the whole"
                        + " match. The burst has to be paid for out of a budget this side refills"
                        + " on its OWN clock");
        assertTrue(total > TICKS,
                "the fixture has to make this side catch up at all, or it is measuring nothing;"
                        + " it ran " + total + " frames over " + TICKS + " ticks");
        assertTrue(worstTick <= 1 + NetSession.CATCHUP_BURST_MAX,
                "no single tick may exceed the standing burst cap; the worst ran " + worstTick);
    }

    @Test
    void theBudgetRefillsOnLocalTicksSoAnHonestHitchStillRecovers() {
        Arena arena = Arena.flat(GROUND_Y);
        RunsAhead peer = new RunsAhead();
        NetSession s = new NetSession(peer, 0, arena, HarnessScenarios.duel(arena), RING, 1);

        peer.feedThrough(0);
        s.update(Input.NONE);
        for (int t = 0; t < 40; t++) {
            peer.feedThrough(s.head() + t);
            s.flush();
        }

        int before = s.head();
        peer.feedThrough(before + 80);
        s.update(Input.NONE);

        assertTrue(s.head() - before >= 20,
                "a session that spent forty ticks flushing without advancing has a full budget"
                        + " waiting, so the tick it resumes on still gets a real snap burst; it ran "
                        + (s.head() - before));
    }
}
