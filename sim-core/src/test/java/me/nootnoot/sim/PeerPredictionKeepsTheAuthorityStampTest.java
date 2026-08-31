package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class PeerPredictionKeepsTheAuthorityStampTest {

    private static final double GROUND_Y = 64.0;

    @Test
    void aPredictedFrameCarriesNoFabricatedStamp() {
        assertFalse(Input.NONE.heldOnly().authority().present(),
                "a predicted frame must NOT invent a position for the peer. Walking the last stamp"
                        + " forward at constant velocity cannot model gravity, acceleration or a"
                        + " jump arc, and clamping it guarantees an under-prediction that has to be"
                        + " paid back - the opponent gliding backwards to compensate. Prediction"
                        + " means running their held inputs through the SIMULATION, anchored at"
                        + " their last confirmed position, which is exactly what the mod does and"
                        + " why mod-vs-mod feels right");
    }

    @Test
    void aPredictedPeerKeepsMovingUnderTheirOwnMomentum() {
        GameState s = HarnessScenarios.duel(Arena.flat(GROUND_Y));
        s.edgeHosted[0] = true;
        s.edgeHosted[1] = true;
        Arena arena = Arena.flat(GROUND_Y);
        s.players[1].vx = 0.2;
        double startX = s.players[1].x;

        Simulation.tick(s, arena, Input.NONE, Input.NONE.heldOnly());

        assertTrue(s.players[1].x - startX > 0.05,
                "with no stamp to adopt, the sim integrates the peer forward from the velocity"
                        + " their last stamp established. That is the prediction - the peer must"
                        + " not freeze while waiting for their next packet");
    }

    @Test
    void aPredictedJumpFollowsARealArcNotAStraightLine() {
        GameState s = HarnessScenarios.duel(Arena.flat(GROUND_Y));
        s.edgeHosted[0] = true;
        s.edgeHosted[1] = true;
        Arena arena = Arena.flat(GROUND_Y);
        s.players[1].vy = 0.42;
        s.players[1].onGround = false;

        double prevStep = Double.MAX_VALUE;
        double prevY = s.players[1].y;
        for (int i = 0; i < 5; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE.heldOnly());
            double step = s.players[1].y - prevY;
            assertTrue(step < prevStep,
                    "gravity has to decelerate the predicted rise. Constant-velocity extrapolation"
                            + " kept climbing at takeoff speed, which is how a jumping opponent"
                            + " ended up rendered a storey above where they were");
            prevStep = step;
            prevY = s.players[1].y;
        }
    }

    @Test
    void aRealStampStillWinsWhenItArrives() {
        GameState s = HarnessScenarios.duel(Arena.flat(GROUND_Y));
        s.edgeHosted[0] = true;
        s.edgeHosted[1] = true;
        Arena arena = Arena.flat(GROUND_Y);
        double reported = s.players[1].x + 0.3;

        Simulation.tick(s, arena, Input.NONE, Input.NONE.withAuthority(
                me.nootnoot.sim.state.Authority.at(reported, GROUND_Y, s.players[1].z, true)));

        assertEquals(reported, s.players[1].x, 1.0E-9,
                "prediction is only ever a stand-in. The moment the peer's real position arrives it"
                        + " is authoritative, and resimulation replays from there");
    }
}
