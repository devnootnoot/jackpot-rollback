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

class PeerChecksumIsAnAccusationTest {
    private static final double GROUND_Y = 64.0;
    private static final int RING = 512;

    private static final class ScriptedPeer implements Transport {
        private final List<byte[]> inbox = new ArrayList<>();
        private final List<Message> sent = new ArrayList<>();

        void deliver(byte[] packet) {
            inbox.add(packet);
        }

        void inputs(int frame, Input in, int ack) {
            inbox.add(Protocol.encode(new Message.InputFrames(frame, List.of(in), ack, 0)));
        }

        @Override
        public void send(byte[] packet) {
            sent.add(Protocol.decode(packet));
        }

        @Override
        public List<byte[]> receive() {
            List<byte[]> out = List.copyOf(inbox);
            inbox.clear();
            return out;
        }

        boolean sentAbort(int code) {
            for (Message m : sent) {
                if (m instanceof Message.Abort a && a.code() == code) {
                    return true;
                }
            }
            return false;
        }
    }

    private static NetSession run(ScriptedPeer peer, int frames) {
        Arena arena = Arena.flat(GROUND_Y);
        NetSession session = new NetSession(peer, 0, arena, CrystalKitFixture.build(GROUND_Y), RING);
        for (int f = 0; f < frames && !session.aborted(); f++) {
            peer.inputs(f, Input.NONE, f - 1);
            session.update(Input.NONE);
        }
        return session;
    }

    @Test
    void aForgedChecksumStillEndsTheMatchAndStillAwardsTheAccuserNothing() {
        ScriptedPeer peer = new ScriptedPeer();
        NetSession session = run(peer, 20);
        assertFalse(session.aborted(), "setup aborted: " + session.abortReason());

        peer.deliver(Protocol.encode(new Message.Checksum(4, 0xDEADBEEFL)));
        session.update(Input.NONE);

        assertTrue(session.aborted(), "a checksum mismatch is unrecoverable and must end the match");
        assertEquals(4, session.desyncFrame());
        assertEquals(1, session.peerChecksumMismatches());
        assertFalse(session.peerAnnouncedDesync(),
                "this side computed the disagreement itself, so it is a DESYNC and not a"
                        + " peer-announced one");
        assertFalse(session.peerDisconnected(),
                "PEER_GONE is the one cause a lone report can be arbitrated into a win. A forged"
                        + " checksum must never reach it");
        assertTrue(peer.sentAbort(1), "the peer has to be told, or it sits there until it times out");
    }

    @Test
    void beingTOLDWeDisagreeIsNotTheSameAsDisagreeing() {
        ScriptedPeer peer = new ScriptedPeer();
        NetSession session = run(peer, 20);
        assertFalse(session.aborted(), "setup aborted: " + session.abortReason());

        peer.deliver(Protocol.encode(new Message.Abort(1)));
        session.update(Input.NONE);

        assertTrue(session.aborted());
        assertTrue(session.peerAnnouncedDesync(),
                "nothing here disagreed with the peer. This side is repeating their claim, and the"
                        + " report has to say so");
        assertEquals(-1, session.desyncFrame(),
                "there is no frame, because this side never found one");
        assertFalse(session.peerDisconnected(),
                "the announcement used to fall through to the generic peer-abort branch, which the"
                        + " edge files as PEER_GONE. That hands the accuser a win whenever this"
                        + " side's own report is lost");
        assertEquals(0, session.peerChecksumMismatches(),
                "no checksum of theirs ever disagreed with one of ours");
    }

    @Test
    void aChecksumForAFrameWeNeverProducedAnInputForIsRefusedOutright() {
        ScriptedPeer peer = new ScriptedPeer();
        NetSession session = run(peer, 20);
        assertFalse(session.aborted(), "setup aborted: " + session.abortReason());

        int past = session.head() + 200;
        peer.deliver(Protocol.encode(new Message.Checksum(past, 1L)));
        peer.deliver(Protocol.encode(new Message.Checksum(past + 1, 2L)));
        session.update(Input.NONE);

        assertFalse(session.aborted(), "aborted on a value it should never have looked at: "
                + session.abortReason());
        assertEquals(2, session.peerChecksumOverreach(),
                "a peer confirms a frame only once it holds BOTH inputs for it, and the input this"
                        + " side contributed to that frame does not exist yet. A value for it is"
                        + " provably manufactured, and it was also the only unbounded growth path"
                        + " into the pending-checksum map");
        assertEquals(0, session.retention().pendingRemoteChecksums(),
                "nothing manufactured may be parked");
    }
}
