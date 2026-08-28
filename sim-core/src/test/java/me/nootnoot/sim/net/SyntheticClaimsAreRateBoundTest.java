package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.ClaimAuthority;
import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class SyntheticClaimsAreRateBoundTest {
    private static final double GROUND_Y = 64.0;
    private static final int RING = 512;

    private static final class ScriptedPeer implements Transport {
        private final List<byte[]> inbox = new ArrayList<>();

        void deliver(int frame, Input in, int ack) {
            inbox.add(Protocol.encode(new Message.InputFrames(frame, List.of(in), ack, 0)));
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

    private static Input jump(boolean held) {
        return new Input(false, false, false, false, held, false, false, false, false, 0f, 0f, 0);
    }

    private static NetSession session(ScriptedPeer peer) {
        return new NetSession(peer, 1, Arena.flat(GROUND_Y), CrystalKitFixture.build(GROUND_Y), RING);
    }

    private static final int WARMUP = 20;

    private static NetSession peerDropsBackAndForges(int lag, int ticks) {
        ScriptedPeer peer = new ScriptedPeer();
        NetSession session = session(peer);
        for (int t = 0; t < WARMUP; t++) {
            peer.deliver(t, Input.NONE, t - 1);
            session.update(Input.NONE);
        }
        for (int t = WARMUP; t < ticks; t++) {
            int frame = t - lag;
            if (frame >= WARMUP) {
                peer.deliver(frame, jump(true).withSynthetic(true), frame - 1);
            }
            session.update(Input.NONE);
        }
        return session;
    }

    @Test
    void aPeerThatSimplyStaysBehindCannotForgeFillersForever() {
        int lag = 8;
        NetSession session = peerDropsBackAndForges(lag, 300);

        assertFalse(session.aborted(), "the session aborted: " + session.abortReason());
        int believed = session.syntheticRemoteFrames() - session.syntheticFramesStripped();
        assertTrue(session.syntheticRemoteFrames() > 200,
                "the fixture has to put a long stream of synthetic claims on the wire");
        assertTrue(believed <= lag,
                "the peer sat " + lag + " frames behind and stamped synthetic on every frame it"
                        + " ever sent, and " + believed + " of them were believed. The head-space"
                        + " rule alone only binds a peer level with us: anything past"
                        + " MAX_PEER_DELAY_ALLOWANCE frames of lag reads as a legal catch-up"
                        + " filler no matter how long it goes on. Believing a filler freezes"
                        + " prevJump, prevAttack, prevUse and blockTicks for that player, so a"
                        + " peer that never catches up gets its gesture edges re-armed for free."
                        + " An honest peer only fills frames it first fell behind by, so the"
                        + " believable count is bounded by the lag it actually built.");
        assertTrue(believed >= 1,
                "falling " + lag + " frames behind is exactly what licenses a catch-up burst, so"
                        + " the frames covering that lag must still be honoured");
    }

    @Test
    void fallingFurtherBehindBuysExactlyTheFillersThatGapIsWorth() {
        for (int lag : new int[] {6, 12, 24, 48}) {
            NetSession session = peerDropsBackAndForges(lag, 400);
            int believed = session.syntheticRemoteFrames() - session.syntheticFramesStripped();
            assertTrue(believed <= lag,
                    "lag=" + lag + " bought " + believed + " believed fillers; the budget a peer"
                            + " earns is the deficit it actually opened, not the wall clock, so it"
                            + " can never be worth more than the frames it fell behind by");
            assertTrue(believed >= lag / 2,
                    "lag=" + lag + " bought only " + believed + " believed fillers. The budget has"
                            + " to track the gap the peer really opened, or an honest peer that"
                            + " falls this far behind loses fillers it is entitled to and the"
                            + " duel dies on a desync instead of a forgery.");
        }
    }

    @Test
    void anHonestStallAndBurstStillSpendsNothingItDidNotEarn() {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x77C1L, 2, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena,
                CrystalKitFixture.build(GROUND_Y), 512);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena,
                CrystalKitFixture.build(GROUND_Y), 512);

        for (int i = 0; i < 12; i++) {
            net.step();
            s0.update(jump(true));
            s1.update(jump(true));
        }
        for (int i = 0; i < 50; i++) {
            net.step();
            s0.flush();
            s1.update(jump(true));
        }
        for (int i = 0; i < 90; i++) {
            net.step();
            s0.update(jump(true));
            s1.update(jump(true));
        }

        assertTrue(s1.syntheticRemoteFrames() > 0,
                "the fixture has to make s0 actually burst, or the credit path is untested");
        assertEquals(0, s1.syntheticFramesStripped(),
                "s0 stalled for 50 ticks and then caught up exactly the way catchUpFiller does."
                        + " Every one of those fillers was paid for by a tick where s1 produced a"
                        + " frame and s0 produced none, so the credit was banked before the burst"
                        + " arrived. Stripping one of them desyncs an honest duel.");
        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        assertFalse(s1.aborted(), "s1 aborted: " + s1.abortReason());
    }

    @Test
    void aResentFrameIsNotJudgedTwice() {
        ScriptedPeer peer = new ScriptedPeer();
        NetSession session = session(peer);

        for (int t = 0; t < 30; t++) {
            peer.deliver(t, Input.NONE, t - 1);
            session.update(Input.NONE);
        }
        int countedOnce = session.syntheticRemoteFrames();
        for (int t = 0; t < 30; t++) {
            for (int f = 0; f < 30; f++) {
                peer.deliver(f, Input.NONE, f - 1);
            }
            session.update(Input.NONE);
        }

        assertEquals(countedOnce, session.syntheticRemoteFrames(),
                "sendInputs() resends every frame the peer has not acked, so the same frame index"
                        + " arrives many times. Re-judging it lets one frame spend the synthetic"
                        + " budget repeatedly, and worse, lets the same frame get two different"
                        + " verdicts as localInputs.end() grows underneath the rule - the second"
                        + " verdict rolls the sim back onto a different input than the peer used.");
        assertFalse(session.aborted(), "the session aborted: " + session.abortReason());
    }

    @Test
    void theStripRuleReadsThePeersDelayNotAHardWiredGlobal() {
        int frame = 100;
        int produced = 100;

        assertEquals(ClaimAuthority.INPUT_DELAY_FRAMES, NetSession.peerDelayUpperBound(0),
                "a session constructed with a delay of its own still has to assume a peer running"
                        + " the wire constant; MatchDriver is the only production caller and it"
                        + " passes ClaimAuthority.INPUT_DELAY_FRAMES, but NetSession takes the"
                        + " delay as a constructor argument and nothing ties the two together");
        assertEquals(7, NetSession.peerDelayUpperBound(7),
                "if this session runs a delay larger than the wire constant, the peer can be"
                        + " landing its frames that much later than the global would say");

        for (int delay = 0; delay <= 8; delay++) {
            boolean possible = NetSession.fillerFrameIsPossible(frame, produced + 40, delay);
            for (int bigger = delay; bigger <= 8; bigger++) {
                assertTrue(!possible || NetSession.fillerFrameIsPossible(frame, produced + 40, bigger),
                        "assuming a LARGER peer delay made the rule strip a frame it had accepted"
                                + " at a smaller one. Converting a landing frame back to head"
                                + " space with too small a delay overstates the sender's head and"
                                + " strips honest fillers, so the uncertainty has to be resolved"
                                + " towards the larger delay, never the smaller.");
            }
        }
    }
}
