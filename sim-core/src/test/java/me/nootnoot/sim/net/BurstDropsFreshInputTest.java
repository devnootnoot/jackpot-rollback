package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.Loadout;
import org.junit.jupiter.api.Test;

class BurstDropsFreshInputTest {
    private static final double GROUND_Y = 64.0;

    private static final int WARMUP_TICKS = 96;

    private static final int SURGE_FRAMES = 60;

    private static final int PEER_CLAIMED_ADVANTAGE = -1000;

    private static final class ScriptedPeer implements Transport {
        private final Deque<byte[]> inbox = new ArrayDeque<>();

        @Override
        public void send(byte[] packet) {
        }

        @Override
        public List<byte[]> receive() {
            List<byte[]> out = new ArrayList<>(inbox);
            inbox.clear();
            return out;
        }

        void feed(int from, int to, int frameAdvantage) {
            for (int base = from; base <= to; base += Protocol.MAX_INPUTS_PER_PACKET) {
                int end = Math.min(to, base + Protocol.MAX_INPUTS_PER_PACKET - 1);
                List<Input> run = new ArrayList<>(end - base + 1);
                for (int f = base; f <= end; f++) {
                    run.add(Input.NONE);
                }
                inbox.add(Protocol.encode(
                        new Message.InputFrames(base, run, base - 1, frameAdvantage)));
            }
        }
    }

    private static Input rocket() {
        return new Input(false, false, false, false, false, false, false, false, true,
                0f, 89f, CrystalKitFixture.SLOT_FIREWORK3)
                .withUsePress(true)
                .withClicks(new Clicks(0, 1, 0, 0, 0));
    }

    private record Run(int burstFrames, int rocketsUsed) {
    }

    private static Run run(boolean clickOnTheSurgeTick) {
        Arena arena = Arena.flat(GROUND_Y);
        ScriptedPeer peer = new ScriptedPeer();
        NetSession s0 = new NetSession(peer, 0, arena, CrystalKitFixture.build(GROUND_Y), 512, 1);

        int fed = 0;
        for (int t = 0; t < WARMUP_TICKS; t++) {
            peer.feed(fed, fed, 0);
            fed++;
            s0.update(Input.NONE);
        }

        int start = Loadout.countAt(s0.state().players[0], CrystalKitFixture.SLOT_FIREWORK3);
        int head = s0.head();
        peer.feed(fed, head + SURGE_FRAMES, PEER_CLAIMED_ADVANTAGE);
        fed = head + SURGE_FRAMES + 1;
        s0.update(clickOnTheSurgeTick ? rocket() : Input.NONE);
        int burstFrames = s0.head() - head;

        for (int t = 0; t < 8; t++) {
            peer.feed(fed, fed, PEER_CLAIMED_ADVANTAGE);
            fed++;
            s0.update(Input.NONE);
        }
        return new Run(burstFrames,
                start - Loadout.countAt(s0.state().players[0], CrystalKitFixture.SLOT_FIREWORK3));
    }

    @Test
    void aCatchUpBurstMustNotSwallowTheSampleTakenOnTheTickItRunsOn() {
        Run idle = run(false);
        assertEquals(0, idle.rocketsUsed(),
                "the control run must not fire anything, otherwise the fixture is measuring a"
                        + " filler frame acting for the player rather than the player's own click");

        Run clicked = run(true);
        assertTrue(clicked.burstFrames() > 0,
                "the fixture has to make updateOneFrame hold the frame while catchUpTarget still"
                        + " asks for one, otherwise there is nothing to test; the burst ran "
                        + clicked.burstFrames() + " frames");
        assertEquals(1, clicked.rocketsUsed(),
                "the tick's real sample was thrown away. updateOneFrame held the frame for a"
                        + " time-sync sleep and so never recorded the sample, and the catch-up"
                        + " burst then advanced that very frame with a synthetic filler, taking"
                        + " the frame slot the sample was owed. A burst may DELAY the player's"
                        + " discrete actions behind the frames it invents - it may never consume"
                        + " the frame they were sampled for.");
    }
}
