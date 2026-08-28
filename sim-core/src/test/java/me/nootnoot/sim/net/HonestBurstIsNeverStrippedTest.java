package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.ClaimAuthority;
import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class HonestBurstIsNeverStrippedTest {
    private static final double GROUND_Y = 64.0;
    private static final int WARMUP_TICKS = 12;
    private static final int RESUME_TICKS = 60;

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
                CrystalKitFixture.build(GROUND_Y), 512);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena,
                CrystalKitFixture.build(GROUND_Y), 512);

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
    void theCatchUpBurstOfAnHonestPeerIsHonouredFrameForFrame() {
        Pair p = runStall(40, 2);

        assertTrue(p.live().syntheticRemoteFrames() > 0,
                "the fixture has to put real filler frames on the wire, or it is not exercising the"
                        + " stripping rule at all");
        assertEquals(0, p.live().syntheticFramesStripped(),
                "the peer stalled and then caught up exactly the way NetSession.catchUpFiller"
                        + " catches up, so every frame it marked synthetic is a frame it could not"
                        + " have sampled. Stripping even one of them makes this side simulate that"
                        + " frame as a real input while the peer simulated it as a filler, the"
                        + " checksums part, and an honest duel dies on a desync abort. The rule"
                        + " fails closed, so it may only ever fire on a frame the sender could have"
                        + " sampled for real.");
        assertFalse(p.live().aborted(), "the honest peer was aborted: " + p.live().abortReason());
        assertFalse(p.stalled().aborted(),
                "the bursting peer was aborted: " + p.stalled().abortReason());
        assertEquals(0, p.stalled().syntheticFramesStripped());
    }

    @Test
    void itHoldsAcrossEveryStallLengthAndLatencyThisSessionWillRunAt() {
        for (int stall = 8; stall <= 80; stall += 8) {
            for (int lag = 0; lag <= 6; lag += 2) {
                Pair p = runStall(stall, lag);
                assertEquals(0, p.live().syntheticFramesStripped(),
                        "a filler was stripped after a " + stall + "-tick stall at " + lag
                                + " ticks of one-way lag. The margin the rule leaves an honest"
                                + " burst is Protocol.MAX_PEER_DELAY_ALLOWANCE ("
                                + Protocol.MAX_PEER_DELAY_ALLOWANCE + " frames), and this pairing"
                                + " has just eaten it.");
                assertFalse(p.live().aborted(), "stall=" + stall + " lag=" + lag
                        + " aborted the honest peer: " + p.live().abortReason());
                assertFalse(p.stalled().aborted(), "stall=" + stall + " lag=" + lag
                        + " aborted the bursting peer: " + p.stalled().abortReason());
            }
        }
    }

    @Test
    void theMarginTheRuleLeavesAnHonestBurstIsTheDelayAllowance() {
        int produced = 100;
        int newestHeadAPeerCanFill = produced - Protocol.MAX_PEER_DELAY_ALLOWANCE - 1;
        int newestFillerAPeerCanClaim =
                newestHeadAPeerCanFill + ClaimAuthority.INPUT_DELAY_FRAMES;

        assertTrue(NetSession.fillerFrameIsPossible(newestFillerAPeerCanClaim, produced, ClaimAuthority.INPUT_DELAY_FRAMES),
                "a peer bursts up to knownRemoteFrames - MAX_PEER_DELAY_ALLOWANCE, and"
                        + " knownRemoteFrames can never exceed the frames we have produced. The"
                        + " filler it builds at that head is sent on the wire at head +"
                        + " INPUT_DELAY_FRAMES, so this is the newest frame an honest burst can"
                        + " invent. It has to be honoured.");
        assertFalse(NetSession.fillerFrameIsPossible(newestFillerAPeerCanClaim + 1, produced, ClaimAuthority.INPUT_DELAY_FRAMES),
                "one frame newer than that, the sender was level enough with us to have sampled it,"
                        + " so a synthetic claim there is a lie and the rule takes it away");
    }
}
