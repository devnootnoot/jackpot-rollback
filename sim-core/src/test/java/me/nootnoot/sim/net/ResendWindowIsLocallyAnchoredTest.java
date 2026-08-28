package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class ResendWindowIsLocallyAnchoredTest {
    private static final double GROUND_Y = 64.0;
    private static final int RING = 512;
    private static final int WINDOW = NetSession.RESEND_WINDOW_FRAMES;

    private static final class GreedyAckPeer implements Transport {
        private final List<byte[]> inbox = new ArrayList<>();
        private final Map<Integer, Integer> shipped = new HashMap<>();
        private int newestSeen = -1;

        @Override
        public void send(byte[] packet) {
            Message m = Protocol.decode(packet);
            if (!(m instanceof Message.InputFrames inf)) {
                return;
            }
            int f = inf.baseFrame();
            for (int i = 0; i < inf.inputs().size(); i++, f++) {
                shipped.merge(f, 1, Integer::sum);
                newestSeen = Math.max(newestSeen, f);
            }
        }

        void replyAckingEverything(int frame) {
            inbox.add(Protocol.encode(
                    new Message.InputFrames(frame, List.of(Input.NONE), newestSeen, 0)));
        }

        @Override
        public List<byte[]> receive() {
            List<byte[]> out = List.copyOf(inbox);
            inbox.clear();
            return out;
        }
    }

    @Test
    void anAckAtOurNewestFrameCannotSwitchOffRetransmission() {
        assertEquals(1000 - WINDOW + 1, NetSession.resendFrom(1000, 0, 1000),
                "a peer that acks our newest frame every tick used to leave from = to + 1, so the"
                        + " run was empty and nothing was ever resent. One lost datagram then never"
                        + " came back, the peer starved, and this side eventually declared the peer"
                        + " dead for a loss the peer caused deliberately.");
        assertEquals(1000 - WINDOW + 1, NetSession.resendFrom(1000, 0, Integer.MAX_VALUE),
                "peerAckedThrough + 1 must not overflow past the local floor");
        assertEquals(501, NetSession.resendFrom(1000, 0, 500),
                "an honest ack that is genuinely behind still sets the start of the run, so a peer"
                        + " catching up is still caught up as fast as before");
        assertEquals(700, NetSession.resendFrom(1000, 700, 500),
                "the prune floor still wins over both, because inputs below it are gone");
    }

    @Test
    void theWindowIsAlwaysResentEvenWhenThePeerClaimsToHaveEverything() {
        Arena arena = Arena.flat(GROUND_Y);
        GreedyAckPeer peer = new GreedyAckPeer();
        NetSession s = new NetSession(peer, 0, arena, HarnessScenarios.duel(arena), RING, 1);

        for (int f = 0; f < 120; f++) {
            peer.replyAckingEverything(f);
            s.update(Input.NONE);
        }

        assertFalse(s.aborted(), "the session aborted: " + s.abortReason());
        int resent = 0;
        for (int count : peer.shipped.values()) {
            if (count > 1) {
                resent++;
            }
        }
        assertTrue(resent >= WINDOW,
                "only " + resent + " frames were ever put on the wire more than once. The"
                        + " retransmission run has to be floored at our own newest frame minus the"
                        + " window, so redundancy is a property of what WE produced rather than of"
                        + " a number the peer chooses.");
        assertTrue(s.confirmedFrame() > 0, "no frame was confirmed at all");
    }

    @Test
    void theWindowCostsNoExtraDatagram() {
        assertTrue(WINDOW <= NetSession.INPUTS_PER_DATAGRAM,
                "the redundant run is sized to the datagram we were going to send anyway; if it"
                        + " ever exceeds INPUTS_PER_DATAGRAM the resend stops being free and starts"
                        + " being a second packet on every tick");
    }
}
