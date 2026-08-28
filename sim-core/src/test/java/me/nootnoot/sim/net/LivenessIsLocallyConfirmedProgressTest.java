package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class LivenessIsLocallyConfirmedProgressTest {
    private static final double GROUND_Y = 64.0;
    private static final int RING = 512;

    private static final int TIMEOUT = Math.max(1, Math.min(NetSession.PEER_TIMEOUT_TICKS, RING - 32 - 1));

    private static final int LEADER_FRAME = 500;

    private static final class ScriptedPeer implements Transport {
        private final List<byte[]> inbox = new ArrayList<>();

        void deliver(int frame, Input in) {
            inbox.add(Protocol.encode(new Message.InputFrames(frame, List.of(in), -1, 0)));
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

    private static NetSession session(Transport t) {
        Arena arena = Arena.flat(GROUND_Y);
        return new NetSession(t, 0, arena, HarnessScenarios.duel(arena), RING, 1);
    }

    @Test
    void aPeerThatOnlyWalksItsFrameNumberForwardCannotHoldTheMatchOpen() {
        ScriptedPeer peer = new ScriptedPeer();
        NetSession s = session(peer);

        peer.deliver(0, Input.NONE);
        s.update(Input.NONE);
        assertFalse(s.aborted(), "the peer did confirm frame 0");

        int ticks = 0;
        for (int frame = 2; ticks < TIMEOUT * 3 && !s.aborted(); ticks++, frame++) {
            peer.deliver(frame, Input.NONE);
            s.update(Input.NONE);
        }

        assertTrue(s.aborted(),
                "frame 1 was never supplied, so nothing past frame 1 can ever be confirmed - yet"
                        + " every one of these lone far-ahead frames raised the highest ACCEPTED"
                        + " frame number, which is the dial the peer holds. Keyed on that number the"
                        + " liveness timer reset on every packet and this side free-ran to the"
                        + " prediction ceiling and then held, exactly the freeze the packet timer"
                        + " was already known not to catch.");
        assertTrue(s.peerDisconnected(), "not confirming anything is a disconnect");
        assertTrue(ticks <= TIMEOUT + 4,
                "the timer has to fire on its own window (" + TIMEOUT + " ticks), not after the"
                        + " peer runs out of frame numbers to burn; it fired at " + ticks);
    }

    @Test
    void aPeerFillingAGapBelowItsHighestFrameIsMakingRealProgress() {
        ScriptedPeer peer = new ScriptedPeer();
        NetSession s = session(peer);

        peer.deliver(0, Input.NONE);
        peer.deliver(LEADER_FRAME, Input.NONE);
        s.update(Input.NONE);

        for (int frame = 1; frame < LEADER_FRAME && !s.aborted(); frame++) {
            peer.deliver(frame, Input.NONE);
            s.update(Input.NONE);
        }

        assertFalse(s.aborted(),
                "this peer confirmed a frame on every single tick. Its highest accepted frame number"
                        + " never moved after the first packet, because the frames it is supplying"
                        + " are BELOW it - which is exactly what recovery from a burst loss looks"
                        + " like. Keyed on that number the timer saw " + (LEADER_FRAME - 1)
                        + " ticks of silence and killed a healthy peer: " + s.abortReason());
        assertTrue(s.confirmedFrame() >= LEADER_FRAME - 2,
                "the gap fill should have carried the confirmed frame up to the leader");
    }
}
