package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LiveSnapDoesNotFightAFallTest {

    private static final int NO_TELEPORT = 0;
    private static final int PEARL_IN_FLIGHT = 40;

    @Test
    void aRollbackCorrectionIsNeverSnapped() {
        assertFalse(EdgeLiveSnap.needsSnap(NO_TELEPORT, 30.0),
                "a deep rollback can move the sim a long way in one render - that is the whole point"
                        + " of rollback, and it happens constantly once latency is high. Snapping"
                        + " there teleports the player backwards mid-fight, which is what a lagback"
                        + " feels like. Only a teleport the SIM itself authored may snap, and the"
                        + " sim says so by suspending authority");
    }

    @Test
    void freeFallIsNeverSnapped() {
        for (int gap = 0; gap <= 200; gap += 5) {
            assertFalse(EdgeLiveSnap.needsSnap(NO_TELEPORT, gap),
                    "falling opens a large sim-to-client gap and is not a teleport");
        }
    }

    @Test
    void knockbackIsNeverSnapped() {
        assertFalse(EdgeLiveSnap.needsSnap(NO_TELEPORT, 3.9),
                "a crystal blast moves the sim several blocks before the client has reacted, and its"
                        + " knockback arrives as a velocity packet the client integrates itself");
    }

    @Test
    void aPearlSnapsOnceTheClientIsActuallyFarAway() {
        assertTrue(EdgeLiveSnap.needsSnap(PEARL_IN_FLIGHT, 28.4),
                "a pearl sets authoritySuspendTicks, so this is the one case where the sim really did"
                        + " move the player and the client has to be brought to it");
    }

    @Test
    void aPearlThatHasAlreadyConvergedDoesNotSnapAgain() {
        assertFalse(EdgeLiveSnap.needsSnap(PEARL_IN_FLIGHT, 0.2),
                "once the client has arrived there is nothing to correct, and re-teleporting it"
                        + " every tick would stutter for the whole suspension window");
    }

    @Test
    void standingStillIsNeverSnapped() {
        assertFalse(EdgeLiveSnap.needsSnap(NO_TELEPORT, 0.0));
        assertFalse(EdgeLiveSnap.needsSnap(PEARL_IN_FLIGHT, 0.0));
    }
}
