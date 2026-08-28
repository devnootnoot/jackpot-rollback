package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class StallCostsExactlyOneSampleTest {
    private static final double GROUND_Y = 64.0;

    private static final class ScriptedPeer implements Transport {
        private final List<byte[]> inbox = new ArrayList<>();

        void deliver(int frame, Input in) {
            inbox.add(Protocol.encode(new Message.InputFrames(frame, List.of(in), frame - 1, 0)));
        }

        @Override
        public void send(byte[] packet) {
        }

        @Override
        public List<byte[]> receive() {
            List<byte[]> out = List.copyOf(inbox);
            inbox.clear();
            return out;
        }
    }

    private static final class SilentPeer implements Transport {
        @Override
        public void send(byte[] packet) {
        }

        @Override
        public List<byte[]> receive() {
            return List.of();
        }
    }

    private static Input pressing(int slot) {
        return new Input(false, false, false, false, true, true, false, true, true, 0f, 0f, slot)
                .withUsePress(true)
                .withClicks(new Clicks(1, 1, 0, 0, 0));
    }

    @Test
    void theStartHoldCostsOneSamplePerHeldTickAndNeverMoreThanOne() {
        Arena arena = Arena.flat(GROUND_Y);
        NetSession session = new NetSession(new SilentPeer(), 0, arena,
                CrystalKitFixture.build(GROUND_Y), 512);

        int ticks = 50;
        for (int i = 0; i < ticks; i++) {
            int stallsBefore = session.stallTicks();
            int lostBefore = session.samplesLostToStalls();
            session.update(pressing(CrystalKitFixture.SLOT_SWORD));
            assertEquals(stallsBefore + 1, session.stallTicks(),
                    "tick " + i + " held at frame 0 waiting for the peer, so it is one stall");
            assertEquals(lostBefore + 1, session.samplesLostToStalls(),
                    "tick " + i + " held before recordSample, so the input the player gave this"
                            + " tick was never written to any ledger. That is the one sample per"
                            + " stalled tick GGPO accepts - it may never be two.");
        }

        assertEquals(0, session.head(), "a held tick must not advance the head");
        assertEquals(ticks, session.stallTicks());
        assertEquals(ticks, session.samplesLostToStalls());
    }

    @Test
    void theRingSafetyStallAlsoCostsExactlyOnePerHeldTick() {
        Arena arena = Arena.flat(GROUND_Y);
        ScriptedPeer peer = new ScriptedPeer();
        NetSession session = new NetSession(peer, 0, arena, CrystalKitFixture.build(GROUND_Y), 256);

        for (int f = 0; f < 10; f++) {
            peer.deliver(f, Input.NONE);
            session.update(pressing(CrystalKitFixture.SLOT_SWORD));
        }

        int heldTicks = 0;
        int lastHead = session.head();
        for (int i = 0; i < 400 && !session.aborted(); i++) {
            peer.deliver(9, Input.NONE);
            int stallsBefore = session.stallTicks();
            int lostBefore = session.samplesLostToStalls();
            session.update(pressing(CrystalKitFixture.SLOT_SWORD));

            int stalls = session.stallTicks() - stallsBefore;
            int lost = session.samplesLostToStalls() - lostBefore;
            assertTrue(stalls <= 1,
                    "one update() call counted " + stalls + " stalls. updateOneFrame and every"
                            + " advanceQuiet in the same tick each test the same three hold"
                            + " predicates, so without a per-tick latch a single held tick books"
                            + " itself several times and the accepted cost stops being one.");
            assertTrue(lost <= 1,
                    "one update() call lost " + lost + " samples; a tick only ever has one");
            assertTrue(lost <= stalls, "a sample was booked lost on a tick that did not hold");
            heldTicks += stalls;
            if (session.head() == lastHead && stalls == 1) {
                assertEquals(1, lost,
                        "the tick held and the catch-up burst did not spend the sample either,"
                                + " so exactly one sample is gone");
            }
            lastHead = session.head();
        }

        assertTrue(heldTicks > 0,
                "the fixture never reached a ring-safety stall, so it proves nothing about it");
        assertEquals(heldTicks, session.samplesLostToStalls(),
                "a ring-safety stall has no catch-up target to spend the held sample on - the"
                        + " peer is behind, not ahead - so every held tick costs exactly its one"
                        + " sample and no tick costs two");
    }

    @Test
    void aHeldSampleThatTheCatchUpBurstSpendsIsNotCountedLost() {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x31A7L, 2, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena,
                CrystalKitFixture.build(GROUND_Y), 512);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena,
                CrystalKitFixture.build(GROUND_Y), 512);

        for (int i = 0; i < 12; i++) {
            net.step();
            s0.update(pressing(CrystalKitFixture.SLOT_SWORD));
            s1.update(pressing(CrystalKitFixture.SLOT_SWORD));
        }
        for (int i = 0; i < 40; i++) {
            net.step();
            s0.flush();
            s1.update(pressing(CrystalKitFixture.SLOT_SWORD));
        }
        for (int i = 0; i < 80; i++) {
            net.step();
            int stallsBefore = s0.stallTicks();
            int lostBefore = s0.samplesLostToStalls();
            s0.update(pressing(CrystalKitFixture.SLOT_SWORD));
            assertTrue(s0.stallTicks() - stallsBefore <= 1,
                    "a catching-up tick booked more than one stall");
            assertTrue(s0.samplesLostToStalls() - lostBefore <= 1,
                    "a catching-up tick lost more than one sample");
            s1.update(pressing(CrystalKitFixture.SLOT_SWORD));
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
        assertTrue(s0.samplesLostToStalls() <= s0.stallTicks(),
                "more samples were booked lost than there were stalled ticks");
    }
}
