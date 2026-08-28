package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.ResimulationBudgetException;
import me.nootnoot.sim.RollbackController;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class PeerDrivenResimulationIsBudgetedTest {
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

    @Test
    void theDepthOfOneCorrectionIsStillWhateverThePeerNeeds() {
        Arena arena = Arena.flat(GROUND_Y);
        RollbackController c = new RollbackController(arena, 0, HarnessScenarios.duel(arena), RING);

        for (int f = 0; f < RING - 1; f++) {
            c.advance(Input.NONE);
        }
        int deepest = c.head() - 1;
        c.onRemoteInput(1, jump(true));

        assertEquals(c.head(), RING - 1, "the correction has to resimulate back up to head");
        assertTrue(deepest > RollbackController.RESIM_FRAMES_PER_ADVANCE,
                "the point of the case is that one honest correction is far deeper than a single"
                        + " tick's refill, and the budget must still have paid for it");
    }

    @Test
    void aPeerThatKeepsCorrectingDeepFramesRunsOutRatherThanTaxingUsForever() {
        Arena arena = Arena.flat(GROUND_Y);
        RollbackController c = new RollbackController(arena, 0, HarnessScenarios.duel(arena), RING);

        for (int f = 0; f < RING - 1; f++) {
            c.advance(Input.NONE);
        }

        long spentBefore = c.resimulatedFrames();
        int corrections = 0;
        try {
            for (int frame = 1; frame < RING - 1; frame++) {
                c.onRemoteInput(frame, jump(frame % 2 == 0));
                corrections++;
            }
        } catch (ResimulationBudgetException expected) {
            long spent = c.resimulatedFrames() - spentBefore;
            assertTrue(corrections < RING - 3,
                    "every one of the peer's corrections was honoured, so the budget bounded"
                            + " nothing");
            assertTrue(spent <= (long) c.resimBudgetCapacity(),
                    "the peer drew " + spent + " frames of resimulation out of a "
                            + c.resimBudgetCapacity() + " frame budget without advancing us once");
            return;
        }
        throw new AssertionError("the peer corrected " + corrections + " deep frames back to back"
                + " and this side resimulated every one of them. That is the whole attack: one"
                + " datagram per tick buys a full prediction window of resimulation, forever.");
    }

    @Test
    void theBudgetRefillsWithLocalProgressSoAnHonestLinkNeverReachesIt() {
        Arena arena = Arena.flat(GROUND_Y);
        RollbackController c = new RollbackController(arena, 0, HarnessScenarios.duel(arena), RING);

        int lag = 20;
        for (int f = 0; f < 4000; f++) {
            c.advance(Input.NONE);
            int late = f - lag;
            if (late >= 0) {
                c.onRemoteInput(late, jump(late % 3 == 0));
            }
        }

        assertEquals(4000, c.head());
        assertTrue(c.resimBudget() > 0,
                "a peer running twenty frames behind for two hundred seconds is the ordinary case"
                        + " and must never be able to spend the budget down");
    }

    @Test
    void aDeficitInsideThePlayableEnvelopeIsCarriedForever() {
        Arena arena = Arena.flat(GROUND_Y);
        RollbackController c = new RollbackController(arena, 0, HarnessScenarios.duel(arena), RING);

        int lag = RollbackController.RESIM_FRAMES_PER_ADVANCE;
        for (int f = 0; f < 6000; f++) {
            c.advance(Input.NONE);
            int late = f - lag;
            if (late >= 0) {
                c.onRemoteInput(late, jump(late % 3 == 0));
            }
        }

        assertEquals(6000, c.head(), "a peer steadily "
                + RollbackController.RESIM_FRAMES_PER_ADVANCE + " frames behind is the far end of"
                + " an ordinary link and must be carried indefinitely");
    }

    @Test
    void aPeerTwiceAsFarBehindAsAnHonestOneRunsOutInsteadOfBeingCarried() {
        Arena arena = Arena.flat(GROUND_Y);
        RollbackController c = new RollbackController(arena, 0, HarnessScenarios.duel(arena), RING);

        int lag = 2 * RollbackController.HONEST_PEER_LAG_FRAMES;
        try {
            for (int f = 0; f < 2000; f++) {
                c.advance(Input.NONE);
                int late = f - lag;
                if (late >= 0) {
                    c.onRemoteInput(late, jump(late % 2 == 0));
                }
            }
        } catch (ResimulationBudgetException expected) {
            return;
        }
        throw new AssertionError("a peer sitting " + lag + " frames behind costs this side " + lag
                + " frames of resimulation for every one frame it advances us, and the refill of "
                + RollbackController.RESIM_FRAMES_PER_ADVANCE + " paid for all of it. The refill"
                + " has to be what an honest peer needs ("
                + RollbackController.HONEST_PEER_LAG_FRAMES + " plus "
                + RollbackController.PEER_JITTER_FRAMES + " of jitter), not a multiple of it,"
                + " or the budget never binds");
    }

    @Test
    void aSustainedDeficitFarPastTheEnvelopeEndsAsThePEERSOverrunAndNeverAsOurOwnFault() {
        Arena arena = Arena.flat(GROUND_Y);
        ScriptedPeer peer = new ScriptedPeer();
        NetSession session =
                new NetSession(peer, 0, arena, CrystalKitFixture.build(GROUND_Y), 1024);

        int deficit = 300;
        peer.deliver(0, jump(false), -1);
        session.update(Input.NONE);
        for (int t = 1; t < deficit; t++) {
            session.update(Input.NONE);
        }

        int delivered = 1;
        for (int t = 0; t < 600 && !session.aborted(); t++) {
            peer.deliver(delivered, jump(delivered % 2 == 0), -1);
            delivered++;
            session.update(Input.NONE);
        }

        assertTrue(session.aborted(),
                "a peer holding a " + deficit + " frame deficit costs this side " + (deficit - 1)
                        + " frames of resimulation every tick, forever, and nothing stopped it");
        assertTrue(session.peerFaulted(),
                "the abort has to be attributable to the peer. Falling through to the generic"
                        + " RuntimeException catch files it as SELF_FAULT, which CONCEDES the match"
                        + " to whoever caused it: " + session.abortReason());
        assertFalse(session.selfFaulted(), "our own sim did not fault");
        assertFalse(session.peerDisconnected(),
                "the peer was talking the whole time, so this must not be filed as a"
                        + " disconnection, which is the one cause a lone report converts into a"
                        + " win");
    }
}
