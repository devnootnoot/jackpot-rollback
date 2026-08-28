package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.Simulation;
import org.junit.jupiter.api.Test;

class EdgeAuthorityStepBoundTest {
    @Test
    void theWorstSampleTheValidatorWillAcceptFitsInsideTheSimulationStepBound() {
        EdgeMovementValidator.Limits limits = EdgeMovementValidator.Limits.DEFAULTS.normalized();
        int ticks = EdgeMovementValidator.LAG_TICK_CLAMP;
        double horizontal = limits.perTickCeiling() * ticks;
        double vertical = Math.max(limits.verticalUpCeiling(), limits.verticalDownCeiling()) * ticks;
        double worst = Math.sqrt(horizontal * horizontal + vertical * vertical);

        assertTrue(worst <= Simulation.MAX_AUTHORITY_STEP,
                "the sim ignores an authority stamp further than " + Simulation.MAX_AUTHORITY_STEP
                        + " blocks from where the player already is, so the furthest sample this"
                        + " validator will ever hand it has to stay inside that: worst is " + worst);
    }

    @Test
    void theStepBoundIsTightEnoughToBeWorthHaving() {
        assertTrue(Simulation.MAX_AUTHORITY_STEP < 64.0,
                "an unbounded stamp is written straight into velocity, so a peer that forges one"
                        + " gets an arbitrary teleport and an arbitrary knockback out of it; the"
                        + " bound only earns its keep if it is near what a legal move can produce");
    }
}
