package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class PeerAckCannotMoveRetentionTest {
    private static final double GROUND_Y = 64.0;
    private static final int RING = 512;
    private static final int RETENTION = 1200;

    private static final class AckPeer implements Transport {
        private final List<byte[]> inbox = new ArrayList<>();
        private final List<Message.InputFrames> sent = new ArrayList<>();

        void deliver(int frame, Input in, int ack) {
            inbox.add(Protocol.encode(new Message.InputFrames(frame, List.of(in), ack, 0)));
        }

        @Override
        public void send(byte[] packet) {
            Message m = Protocol.decode(packet);
            if (m instanceof Message.InputFrames inf) {
                sent.add(inf);
            }
        }

        @Override
        public List<byte[]> receive() {
            List<byte[]> out = List.copyOf(inbox);
            inbox.clear();
            return out;
        }
    }

    @Test
    void anHonestAckPrunesExactlyWhereTheOldFormulaDid() {
        assertEquals(5000 - RETENTION, NetSession.inputRetentionFloor(5000, 4999),
                "with a peer acking one frame behind our confirmed frame the retention floor is"
                        + " the plain confirmed-frame floor, the same number the peer-driven"
                        + " formula produced. The hardening may not move the honest case.");
        assertEquals(5000 - RETENTION, NetSession.inputRetentionFloor(5000, 6000),
                "a peer that is AHEAD of our confirmed frame still cannot pull the floor past"
                        + " what our own confirmed frame licenses");
    }

    @Test
    void anAckPastEverythingWeHaveProducedCannotReleaseWhatWeStillNeed() {
        int hostile = NetSession.inputRetentionFloor(5000, Integer.MAX_VALUE);

        assertEquals(5000 - RETENTION, hostile,
                "peerAckedThrough + 1 overflows on Integer.MAX_VALUE and the old expression"
                        + " Math.min(confirmedFrame, peerAckedThrough + 1) - INPUT_RETENTION_FRAMES"
                        + " wrapped to a floor near Integer.MAX_VALUE, which releaseBelow() clamps"
                        + " to the whole ledger - one forged ack field and every local input the"
                        + " session still needs to resimulate is gone. The floor has to come off"
                        + " our own confirmed frame.");
        assertTrue(hostile <= 5000,
                "a retention floor above the confirmed frame releases inputs the controller can"
                        + " still be asked to replay");
    }

    @Test
    void anAckThatNeverAdvancesCannotHoldTheLedgerOpenForever() {
        int floor = NetSession.inputRetentionFloor(100_000, -1);

        assertTrue(floor > 0,
                "a peer that reports ack=-1 for the whole match pinned the old floor at"
                        + " -1 - INPUT_RETENTION_FRAMES forever, so rawInputs and localInputs grew"
                        + " without bound for as long as the peer cared to keep lying. Retention"
                        + " has to be bounded from local state.");
        assertEquals(100_000 - 2 * RETENTION, floor,
                "the peer may hold retention open by at most one extra retention window");
        assertTrue(100_000 - floor <= 2 * RETENTION,
                "the window a hostile ack can open is not bounded");
    }

    @Test
    void theFloorIsMonotoneInTheAckAndNeverEscapesTheSafeWindow() {
        int confirmed = 40_000;
        int previous = Integer.MIN_VALUE;
        for (long ack = -1; ack <= confirmed + 5000L; ack += 97) {
            int floor = NetSession.inputRetentionFloor(confirmed, (int) ack);
            assertTrue(floor >= confirmed - 2 * RETENTION,
                    "ack=" + ack + " retained more than the two-window bound");
            assertTrue(floor <= confirmed - RETENTION,
                    "ack=" + ack + " pruned inside the window our own confirmed frame needs");
            assertTrue(floor >= previous, "the floor moved backwards at ack=" + ack);
            previous = floor;
        }
    }

    @Test
    void anAckPastOurNewestFrameDoesNotSilenceOurOwnInputStream() {
        Arena arena = Arena.flat(GROUND_Y);
        AckPeer peer = new AckPeer();
        NetSession session = new NetSession(peer, 1, arena, CrystalKitFixture.build(GROUND_Y), RING);

        for (int f = 0; f < 40; f++) {
            peer.deliver(f, Input.NONE, Integer.MAX_VALUE);
            session.update(Input.NONE);
        }

        assertFalse(session.aborted(), "the session aborted: " + session.abortReason());
        int framesOnTheWire = 0;
        for (Message.InputFrames inf : peer.sent) {
            framesOnTheWire += inf.inputs().size();
        }
        assertTrue(framesOnTheWire > 0,
                "sendInputs() starts its run at Math.max(inputPruneFloor, peerAckedThrough + 1),"
                        + " so an ack past everything we have produced left `to < from` on every"
                        + " tick and this session shipped nothing but empty packets forever. The"
                        + " peer then starves for our inputs and the duel dies - a forged ack must"
                        + " not be able to switch off our own transmit path.");
        assertTrue(session.peerAckOverreach() > 0,
                "an ack past our newest produced frame is not something an honest peer can send"
                        + " (its confirmedFrame is bounded by the frames of ours it holds), so the"
                        + " clamp has to be observable rather than silent");
    }
}
