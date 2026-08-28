package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class ForgedFinishTest {
    private static final double GROUND_Y = 64.0;
    private static final int RING = 512;

    private static final class ScriptedPeer implements Transport {
        private final List<byte[]> inbox = new ArrayList<>();

        void deliver(Message m) {
            inbox.add(Protocol.encode(m));
        }

        void inputs(int frame, int count, int ack) {
            for (int i = 0; i < count; i++) {
                inbox.add(Protocol.encode(
                        new Message.InputFrames(frame + i, List.of(Input.NONE), ack, 0)));
            }
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

    private static NetSession session(ScriptedPeer peer, Arena arena) {
        return new NetSession(peer, 0, arena, HarnessScenarios.duel(arena), RING, 1);
    }

    @Test
    void aPeerNamingItselfTheWinnerNeverEndsTheMatchHere() {
        Arena arena = Arena.flat(GROUND_Y);
        ScriptedPeer peer = new ScriptedPeer();
        NetSession s = session(peer, arena);

        peer.inputs(0, 20, -1);
        for (int i = 0; i < 20; i++) {
            s.update(Input.NONE);
        }
        peer.deliver(new Message.Finish(1, 3, 0));
        s.update(Input.NONE);

        assertTrue(s.peerClaimedWin(), "the claim is observed");
        assertFalse(s.remoteFinished(),
                "but it is not a result: remoteFinished is the CONCESSION flag, and taking a"
                        + " self-declared win here is what let a tampered client end a live match"
                        + " and make this honest host report itself the loser");
        assertFalse(s.remoteConceded(), "nobody conceded");
        assertEquals(-1, s.remoteWinnerSlot(), "and no winner is carried off the peer's word");
        assertFalse(s.aborted(), "the match is still live inside the grace window");
    }

    @Test
    void anUnverifiableWinClaimResolvesAsAForfeitByWhoeverMadeIt() {
        Arena arena = Arena.flat(GROUND_Y);
        ScriptedPeer peer = new ScriptedPeer();
        NetSession s = session(peer, arena);

        peer.inputs(0, 20, -1);
        for (int i = 0; i < 20; i++) {
            s.update(Input.NONE);
        }
        peer.deliver(new Message.Finish(1, 3, 0));

        for (int i = 0; i <= NetSession.PEER_WIN_CLAIM_GRACE_TICKS && !s.aborted(); i++) {
            peer.inputs(20 + i, 1, -1);
            s.update(Input.NONE);
        }

        assertTrue(s.aborted(), "the grace ran out with nothing confirmed");
        assertTrue(s.peerDisconnected(),
                "and it resolves the way leaving does - a forfeit by the claimer - so announcing a"
                        + " win this side cannot reproduce is worth exactly as much as quitting,"
                        + " never more");
    }

    @Test
    void aConcessionIsStillTakenImmediately() {
        Arena arena = Arena.flat(GROUND_Y);
        ScriptedPeer peer = new ScriptedPeer();
        NetSession s = session(peer, arena);

        peer.inputs(0, 20, -1);
        for (int i = 0; i < 20; i++) {
            s.update(Input.NONE);
        }
        peer.deliver(new Message.Finish(0, 1, 2));
        s.update(Input.NONE);

        assertTrue(s.remoteFinished(), "the peer named US, which is a concession");
        assertTrue(s.remoteConceded(), "and a concession is authoritative - it costs the sender");
        assertEquals(0, s.remoteWinnerSlot());
        assertEquals(1, s.remoteWinsP0());
        assertEquals(2, s.remoteWinsP1());
    }
}
