package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class LateHandoffIsNotADeadPeerTest {
    private static final double GROUND_Y = 64.0;
    private static final int RING = 512;

    private static final class ScriptedPeer implements Transport {
        private final List<byte[]> inbox = new ArrayList<>();

        void deliver(Message m) {
            inbox.add(Protocol.encode(m));
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

    private static int silentTicks(NetSession s) {
        return s.peerTimeoutTicks() + 100;
    }

    @Test
    void aPeerThatFinishesItsHandoffLateIsNotDeclaredToHaveStoppedSimulating() {
        Arena arena = Arena.flat(GROUND_Y);
        ScriptedPeer peer = new ScriptedPeer();
        NetSession s = new NetSession(peer, 0, arena, HarnessScenarios.duel(arena), RING, 1);

        int quiet = silentTicks(s);
        assertTrue(quiet < NetSession.START_TIMEOUT_TICKS,
                "the fixture only means anything inside the window the handoff is allowed to take");
        for (int t = 0; t < quiet; t++) {
            s.update(Input.NONE);
        }
        assertFalse(s.aborted(), "nothing has timed out yet: " + s.abortReason());

        peer.deliver(new Message.InputFrames(0, List.of(), -1, 0));
        s.update(Input.NONE);

        assertFalse(s.aborted(),
                "the peer's FIRST packet is the empty InputFrames sendInputs emits while it is"
                        + " itself holding at frame zero, so first contact carries no input frame."
                        + " ticksSinceRemoteProgress was counting from frame zero rather than from"
                        + " first contact, so it was already past peerTimeoutTicks the moment the"
                        + " peer arrived and this side killed a healthy opponent on its opening"
                        + " packet - silently cutting START_TIMEOUT_TICKS down to peerTimeoutTicks."
                        + " It aborted with: " + s.abortReason());

        int frame = 0;
        for (int t = 0; t < 200; t++) {
            peer.deliver(new Message.InputFrames(frame, List.of(Input.NONE), frame - 1, 0));
            frame++;
            s.update(Input.NONE);
        }

        assertFalse(s.aborted(), "and the match runs on normally: " + s.abortReason());
        assertTrue(s.head() > 0, "with this side actually simulating");
    }

    @Test
    void butTheProgressTimerIsArmedAtFirstContactRatherThanDisabled() {
        Arena arena = Arena.flat(GROUND_Y);
        ScriptedPeer peer = new ScriptedPeer();
        NetSession s = new NetSession(peer, 0, arena, HarnessScenarios.duel(arena), RING, 1);

        for (int t = 0; t < silentTicks(s); t++) {
            s.update(Input.NONE);
        }
        peer.deliver(new Message.InputFrames(0, List.of(Input.NONE), -1, 0));
        s.update(Input.NONE);
        assertFalse(s.aborted(), "the peer did simulate one frame");

        int ticks = 0;
        for (; ticks < 4000 && !s.aborted(); ticks++) {
            peer.deliver(new Message.Heartbeat(ticks));
            s.update(Input.NONE);
        }

        assertTrue(s.aborted(),
                "moving the timer's zero to first contact must not turn it off: a peer that goes"
                        + " on holding the link open without simulating still has to resolve");
        assertTrue(s.peerDisconnected(), "and it still resolves as a disconnect");
        assertTrue(ticks <= s.peerTimeoutTicks() + 8,
                "and it resolves on the timer's own length measured from the last input frame,"
                        + " not from frame zero; it took " + ticks + " ticks");
    }
}
