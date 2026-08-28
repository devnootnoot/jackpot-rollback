package me.nootnoot.relay;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.nootnoot.sim.GameStateFrame0Codec;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.net.ControlProtocol;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class LateAuthorizeDoesNotResetALiveRefereeTest {

    private static final double GROUND_Y = 64.0;

    private static final byte[] FIRST_SLOT0 = {1, 2, 3, 4};
    private static final byte[] FIRST_SLOT1 = {5, 6, 7, 8};
    private static final byte[] SECOND_SLOT0 = {9, 9, 9, 9};
    private static final byte[] SECOND_SLOT1 = {8, 8, 8, 8};

    @Test
    void aSecondAuthorizeAfterFramesHaveFlowedKeepsTheOriginalBinding() throws Exception {
        RefereeManager referees = new RefereeManager();
        long session = 4242L;
        try {
            referees.authorize(authorize(session, FIRST_SLOT0, FIRST_SLOT1), result -> {
            });
            assertTrue(referees.tokenMatches(session, 0, FIRST_SLOT0));

            referees.tee(session, 0, Protocol.encode(
                    new Message.InputFrames(0, List.of(Input.NONE, Input.NONE), 0)));
            waitForIngest(referees, session);

            referees.authorize(authorize(session, SECOND_SLOT0, SECOND_SLOT1), result -> {
            });

            assertTrue(referees.tokenMatches(session, 0, FIRST_SLOT0),
                    "a referee that has already taken frames must keep the slot binding it was"
                            + " authorized with. Both edges of a cross-play duel authorize the same"
                            + " session, so a later authorize arriving mid-match must not be able"
                            + " to re-bind who is allowed to play it, nor silently restart the"
                            + " re-simulation from frame 0 and lose every frame already judged");
            assertFalse(referees.tokenMatches(session, 0, SECOND_SLOT0),
                    "the late authorize must not have taken effect at all");
        } finally {
            referees.stop();
        }
    }

    @Test
    void aSecondAuthorizeBeforeAnyFramesStillReplacesTheSession() {
        RefereeManager referees = new RefereeManager();
        long session = 4343L;
        try {
            referees.authorize(authorize(session, FIRST_SLOT0, FIRST_SLOT1), result -> {
            });
            referees.authorize(authorize(session, SECOND_SLOT0, SECOND_SLOT1), result -> {
            });

            assertTrue(referees.tokenMatches(session, 0, SECOND_SLOT0),
                    "before any frame has been judged there is nothing to protect, so a re-authorize"
                            + " has to be allowed. Both edges race to authorize and a retry after a"
                            + " dropped control connection has to be able to win");
        } finally {
            referees.stop();
        }
    }

    private static void waitForIngest(RefereeManager referees, long session) throws Exception {
        for (int i = 0; i < 400 && !referees.hasJudgedFrames(session); i++) {
            Thread.sleep(5);
        }
        assertTrue(referees.hasJudgedFrames(session),
                "the referee never ingested the teed frame, so this test would prove nothing");
    }

    private static ControlProtocol.Authorize authorize(long session, byte[] slot0, byte[] slot1) {
        GameState frame0 = HarnessScenarios.duel(Arena.flat(GROUND_Y));
        return new ControlProtocol.Authorize(session, Long.MAX_VALUE, slot0, slot1,
                GROUND_Y, new double[0][], GameStateFrame0Codec.encode(frame0));
    }
}
