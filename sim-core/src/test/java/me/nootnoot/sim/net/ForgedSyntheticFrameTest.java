package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.ClaimAuthority;
import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class ForgedSyntheticFrameTest {
    private static final double GROUND_Y = 64.0;
    private static final int RING = 512;
    private static final int LIVE_FRAMES = 40;

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

    private static Input everything() {
        return new Input(true, false, false, false, true, true, false, true, true, true,
                true, true, true, true, true, true, 30f, -20f, 4,
                Input.BLOCK_PLACE_CRYSTAL, 11, 12, 13, 0x4001,
                Input.INV_CURSOR_RESOLVE, 5, 6, Authority.at(1.5, 2.5, 3.5, true),
                new Clicks(3, 3, 3, 3, 3), true, 21, 22, 23, true, false);
    }

    private static Input overTheWire(Input in) {
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, in);
        b.flip();
        return InputCodec.read(b);
    }

    @Test
    void aLivePeerMayNotStampSyntheticOnItsOwnRealFrames() {
        Arena arena = Arena.flat(GROUND_Y);
        ScriptedPeer peer = new ScriptedPeer();
        NetSession session = new NetSession(peer, 1, arena, CrystalKitFixture.build(GROUND_Y), RING);

        for (int f = 0; f < LIVE_FRAMES; f++) {
            peer.deliver(f, jump(true).withSynthetic(true), f - 1);
            session.update(Input.NONE);
        }

        assertFalse(session.aborted(), "the session aborted: " + session.abortReason());
        assertTrue(session.state().players[0].prevJump,
                "the peer is keeping perfect pace - every frame it sends is the frame this session"
                        + " is about to simulate, so it has invented nothing and cannot be filling"
                        + " a catch-up gap. It set bit 0x80 of the heldSlot byte anyway and the sim"
                        + " honoured it: Simulation skipped the prevJump write, so space reads as"
                        + " still up on a frame the peer had it down. Every previous-input latch"
                        + " the sim keeps for that player freezes the same way, and blockTicks"
                        + " never resets, for as long as the peer keeps lying. Only the receiving"
                        + " session may decide which of a peer's frames were invented.");
    }

    @Test
    void onlyAFrameThePeerCouldNotHaveSampledCountsAsAFiller() {
        int frame = 100;
        int senderHead = frame - ClaimAuthority.INPUT_DELAY_FRAMES;
        int lag = Protocol.MAX_PEER_DELAY_ALLOWANCE;

        assertFalse(NetSession.fillerFrameIsPossible(frame, frame, ClaimAuthority.INPUT_DELAY_FRAMES),
                "a peer level with us has sampled that frame for real");
        assertFalse(NetSession.fillerFrameIsPossible(frame, senderHead + lag, ClaimAuthority.INPUT_DELAY_FRAMES),
                "inside the delay allowance the peer is not behind enough to have burst. The"
                        + " allowance is measured against the head the sender was filling, and a"
                        + " frame lands on the wire INPUT_DELAY_FRAMES later than that head, so"
                        + " the budget has to be spent in head space");
        assertTrue(NetSession.fillerFrameIsPossible(frame, senderHead + lag + 1, ClaimAuthority.INPUT_DELAY_FRAMES),
                "a peer this far behind our own input production is inside the catch-up window,"
                        + " so a filler at this frame is exactly what NetSession.catchUpFiller"
                        + " would have produced and it has to keep being honoured");
        assertFalse(NetSession.fillerFrameIsPossible(0, 0, ClaimAuthority.INPUT_DELAY_FRAMES),
                "nobody can be catching up before either side has produced a frame");
    }

    @Test
    void aFrameThatClaimsSyntheticCarriesNoActionAtAll() {
        Input forged = everything().withSynthetic(true);

        assertEquals(Clicks.NONE, forged.clicks(), "clicks");
        assertEquals(Input.INV_NONE, forged.invAction(), "invAction");
        assertEquals(0, forged.invSrc(), "invSrc");
        assertEquals(0, forged.invDst(), "invDst");
        assertEquals(Input.BLOCK_NONE, forged.blockAction(), "blockAction");
        assertEquals(Input.NO_PROJECTILE_HIT, forged.projectileHit(), "projectileHit");
        assertSame(Authority.NONE, forged.authority(), "authority");
        assertFalse(forged.usePress(), "usePress");
        assertFalse(forged.offhandUsePress(), "offhandUsePress");
        assertFalse(forged.meleeHit(), "meleeHit");
        assertFalse(forged.dropItem(), "dropItem");
        assertFalse(forged.dropStack(), "dropStack");
        assertFalse(forged.swapHands(), "swapHands");
        assertFalse(forged.crystalHit(), "crystalHit");
        assertFalse(forged.elytraStart(), "elytraStart");

        assertTrue(forged.jump() && forged.sprint() && forged.attack() && forged.use(),
                "the held bits are the whole point of a filler and have to survive");
        assertEquals(4, forged.heldSlot(), "heldSlot");
        assertEquals(forged, overTheWire(forged),
                "and the rule has to hold on the far side of the codec too, or the two peers"
                        + " disagree about a frame and desync");
    }

    @Test
    void everyFillerTheSessionBuildsIsAlreadyAFixedPointOfThatRule() {
        Input raw = everything();

        assertEquals(raw.withSynthetic(true).heldOnly(), raw.heldOnly().withSynthetic(true),
                "heldOnly");
        assertEquals(raw.withSynthetic(true).gestureOnly(), raw.gestureOnly().withSynthetic(true),
                "gestureOnly");
        assertEquals(raw.withSynthetic(true).released(), raw.released().withSynthetic(true),
                "released");
    }
}
