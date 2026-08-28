package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.ClaimAuthority;
import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class SyntheticStripFrameSpaceTest {
    private static final double GROUND_Y = 64.0;
    private static final int RING = 512;
    private static final int WARMUP_TICKS = 12;
    private static final int RESUME_TICKS = 60;
    private static final int DELAY = ClaimAuthority.INPUT_DELAY_FRAMES;

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

    private static Input busy(int slot, boolean press) {
        return new Input(false, false, false, false, true, true, false, true, true,
                0f, 0f, slot)
                .withUsePress(press)
                .withClicks(new Clicks(press ? 1 : 0, press ? 1 : 0, 0, 0, 0));
    }

    private record Pair(NetSession stalled, NetSession live) {
    }

    private static Pair runStall(int stallTicks, int lag) {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x5711L, lag, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena,
                CrystalKitFixture.build(GROUND_Y), RING, DELAY);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena,
                CrystalKitFixture.build(GROUND_Y), RING, DELAY);

        for (int i = 0; i < WARMUP_TICKS; i++) {
            net.step();
            s0.update(busy(CrystalKitFixture.SLOT_SWORD, i == 0));
            s1.update(busy(CrystalKitFixture.SLOT_SWORD, i == 0));
        }
        for (int i = 0; i < stallTicks; i++) {
            net.step();
            s0.flush();
            s1.update(busy(CrystalKitFixture.SLOT_SWORD, false));
        }
        for (int i = 0; i < RESUME_TICKS; i++) {
            net.step();
            s0.update(busy(CrystalKitFixture.SLOT_SWORD, false));
            s1.update(busy(CrystalKitFixture.SLOT_SWORD, false));
        }
        return new Pair(s0, s1);
    }

    @Test
    void theBurstOfAnHonestPeerSurvivesTheInputDelayThisSessionActuallyRunsAt() {
        Pair p = runStall(40, 2);

        assertTrue(p.live().syntheticRemoteFrames() > 0,
                "the fixture has to put real filler frames on the wire, or it is not exercising"
                        + " the stripping rule at all");
        assertEquals(0, p.live().syntheticFramesStripped(),
                "every session in production runs at inputDelay="
                        + DELAY + " (MatchDriver passes ClaimAuthority.INPUT_DELAY_FRAMES), so a"
                        + " filler the peer sampled at its head lands on the wire at head +"
                        + " INPUT_DELAY_FRAMES while the burst limit the peer obeyed was measured"
                        + " against its head. Comparing the landed frame against a head-space"
                        + " budget steals the newest INPUT_DELAY_FRAMES frames of every honest"
                        + " burst: this side then simulates them as real input while the sender"
                        + " simulated them as filler, and the duel dies on a desync abort.");
        assertFalse(p.live().aborted(), "the honest peer was aborted: " + p.live().abortReason());
        assertFalse(p.stalled().aborted(),
                "the bursting peer was aborted: " + p.stalled().abortReason());
        assertEquals(0, p.stalled().syntheticFramesStripped());
    }

    @Test
    void itHoldsAtEveryStallLengthAndLatencyWithTheProductionInputDelay() {
        for (int stall = 8; stall <= 80; stall += 8) {
            for (int lag = 0; lag <= 6; lag += 2) {
                Pair p = runStall(stall, lag);
                assertEquals(0, p.live().syntheticFramesStripped(),
                        "a filler was stripped after a " + stall + "-tick stall at " + lag
                                + " ticks of one-way lag, at inputDelay=" + DELAY);
                assertFalse(p.live().aborted(), "stall=" + stall + " lag=" + lag
                        + " aborted the honest peer: " + p.live().abortReason());
                assertFalse(p.stalled().aborted(), "stall=" + stall + " lag=" + lag
                        + " aborted the bursting peer: " + p.stalled().abortReason());
            }
        }
    }

    @Test
    void theBoundaryIsTheLandingFrameOfTheNewestFillerTheSenderCouldHaveBuilt() {
        int produced = 100;
        int newestSenderHead = produced - Protocol.MAX_PEER_DELAY_ALLOWANCE - 1;
        int newestLanding = newestSenderHead + ClaimAuthority.INPUT_DELAY_FRAMES;

        assertTrue(NetSession.fillerFrameIsPossible(newestLanding, produced, DELAY),
                "the sender bursts while its head is below knownRemoteFrames -"
                        + " MAX_PEER_DELAY_ALLOWANCE, and knownRemoteFrames can never exceed the"
                        + " frames we have produced. The filler it built at that head is sent on"
                        + " the wire at head + INPUT_DELAY_FRAMES, so this is the newest frame an"
                        + " honest burst can carry and it has to be honoured.");
        assertFalse(NetSession.fillerFrameIsPossible(newestLanding + 1, produced, DELAY),
                "one landing frame newer than that, the sender was level enough with us to have"
                        + " sampled it for real, so a synthetic claim there is a lie");
    }

    @Test
    void aPeerKeepingPaceStillHasItsForgedSyntheticClaimRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        ScriptedPeer peer = new ScriptedPeer();
        NetSession session = new NetSession(peer, 1, arena, CrystalKitFixture.build(GROUND_Y),
                RING, DELAY);

        for (int f = 0; f < 40; f++) {
            peer.deliver(f, jump(true).withSynthetic(true), f - 1);
            session.update(Input.NONE);
        }

        assertFalse(session.aborted(), "the session aborted: " + session.abortReason());
        assertTrue(session.syntheticFramesStripped() > 0,
                "the peer is keeping perfect pace, so it has invented nothing and every synthetic"
                        + " stamp it sends is forged. Widening the rule to fit the input delay may"
                        + " not widen it far enough to let a level peer freeze this side's"
                        + " previous-input latches.");
        assertTrue(session.state().players[0].prevJump,
                "the forged filler was honoured: the sim skipped the prevJump write, so space"
                        + " reads as still up on a frame the peer had it down");
    }

    private static Input jump(boolean held) {
        return new Input(false, false, false, false, held, false, false, false, false, 0f, 0f, 0);
    }
}
