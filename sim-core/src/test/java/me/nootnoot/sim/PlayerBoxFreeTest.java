package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import org.junit.jupiter.api.Test;

class PlayerBoxFreeTest {

    private static final double GROUND_Y = 64.0;

    private static GameState duel() {
        return HarnessScenarios.duel(Arena.flat(GROUND_Y));
    }

    @Test
    void standingOnTheFloorIsFree() {
        assertTrue(Simulation.playerBoxFree(duel(), Arena.flat(GROUND_Y), 0.0, GROUND_Y, 0.0),
                "a legal standing position must not read as blocked, or every correction would be"
                        + " refused and the validator would stop working entirely");
    }

    @Test
    void buriedInTheFloorIsNotFree() {
        assertFalse(Simulation.playerBoxFree(duel(), Arena.flat(GROUND_Y), 0.0, GROUND_Y - 1.5, 0.0),
                "this is the check that was missing: EdgeInputSource.correct() teleported the"
                        + " vanilla client onto EdgeMovementValidator's accepted point without ever"
                        + " testing it against a block. A vanilla client cannot climb out of a"
                        + " floor - pushOutOfBlocks only searches sideways - so the player stayed"
                        + " stuck until the round ended");
    }

    @Test
    void aBuriedPointIsLiftedToAStandablePoint() {
        double y = Simulation.resolveStandingY(duel(), Arena.flat(GROUND_Y),
                0.0, GROUND_Y - 1.5, 0.0, 3.0);
        assertFalse(Double.isNaN(y), "a point just below the floor must be recoverable by lifting");
        assertTrue(y >= GROUND_Y - 1.0E-9,
                "the lifted point has to be at or above the floor, not merely less buried");
        assertTrue(Simulation.playerBoxFree(duel(), Arena.flat(GROUND_Y), 0.0, y, 0.0),
                "and the resolved point must itself be free, or the correction just moves the"
                        + " player to a different block");
    }

    @Test
    void aFreePointIsReturnedUnchanged() {
        assertEquals(GROUND_Y, Simulation.resolveStandingY(duel(), Arena.flat(GROUND_Y),
                        0.0, GROUND_Y, 0.0, 3.0), 1.0E-9,
                "a legal correction must not be nudged upward, or every clamp would slowly"
                        + " levitate the player");
    }
}
