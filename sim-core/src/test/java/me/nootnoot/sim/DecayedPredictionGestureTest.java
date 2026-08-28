package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class DecayedPredictionGestureTest {
    private static final double GROUND_Y = 64.0;
    private static final int DELIVERED = 6;
    private static final int PAST_DECAY = 5;
    private static final int GAPPLES = 64;

    private static Input eat() {
        return new Input(false, false, false, false, false, false, false, false, true,
                0f, 0f, CrystalKitFixture.SLOT_GAPPLE);
    }

    private static RollbackController peerGoesQuietMidBite() {
        Arena arena = Arena.flat(GROUND_Y);
        RollbackController c =
                new RollbackController(arena, 0, CrystalKitFixture.build(GROUND_Y), 256);
        for (int f = 0; f < DELIVERED; f++) {
            c.onRemoteInput(f, eat());
            c.advance(Input.NONE);
        }
        assertTrue(c.state().players[1].eating,
                "the peer has to be mid-bite before they go quiet, or there is no gesture to end");

        for (int f = 0; f < RollbackController.PREDICTION_DECAY_FRAMES + PAST_DECAY; f++) {
            c.advance(Input.NONE);
        }
        return c;
    }

    @Test
    void aDecayedPredictionMayNotEndAGestureThePeerNeverEnded() {
        RollbackController c = peerGoesQuietMidBite();
        PlayerState remote = c.state().players[1];

        assertTrue(remote.eating,
                "past PREDICTION_DECAY_FRAMES knownRemote() invents Input.released() for the peer."
                        + " A release is a state change nobody sent, and this is the one prediction"
                        + " that may never be corrected: it only fires once the peer has been"
                        + " silent for a second, which is exactly when their real frames may never"
                        + " arrive at all. Marked synthetic it stops the movement without ending"
                        + " the gesture; unmarked it is indistinguishable from the player letting"
                        + " go, which is the defect the catch-up filler was already fixed for.");
        assertTrue(remote.eatTicks >= DELIVERED,
                "and the bite has to keep the progress the delivered frames earned");
    }

    @Test
    void aFrozenGestureIsNotACompletedOne() {
        RollbackController c = peerGoesQuietMidBite();
        PlayerState remote = c.state().players[1];

        assertEquals(GAPPLES, Loadout.countAt(remote, CrystalKitFixture.SLOT_GAPPLE),
                "nothing may leave the peer's inventory on frames the session invented for them");
    }
}
