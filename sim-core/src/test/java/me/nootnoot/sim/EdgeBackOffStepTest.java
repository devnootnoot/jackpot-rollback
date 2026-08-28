package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EdgeBackOffStepTest {
    private static final double STEP = Simulation.EDGE_BACK_OFF_STEP;

    private static int accumulatedTrips(double d) {
        int n = 0;
        while (d != 0.0 && n < 1_000_000) {
            d = Simulation.edgeBackOffStep(d);
            n++;
        }
        return n;
    }

    @Test
    void theBoundIsNeverSmallerThanTheWalkItReplaces() {
        for (int i = -4000; i <= 4000; i++) {
            double d = i * 0.0025;
            int trips = accumulatedTrips(d);
            assertTrue(trips <= Simulation.edgeBackOffSteps(d),
                    "the precomputed bound would cut the clip short at " + d + ": "
                            + trips + " trips against a bound of " + Simulation.edgeBackOffSteps(d));
        }
    }

    @Test
    void theStepChainItselfIsUntouchedSoTheClipIsStillVanillasOwn() {
        assertEquals(13, accumulatedTrips(0.6),
                "0.6 is twelve whole steps of 0.05, but 0.05 is not exact in binary, so the"
                        + " twelfth lands on 1.39e-17 instead of zero and a thirteenth turn is"
                        + " needed to snap it. That drift is exactly why the count may not come"
                        + " out of the chain, and exactly why the chain itself may not change:"
                        + " Player.maybeBackOffFromEdge accumulates the same way");
        assertTrue(Simulation.edgeBackOffSteps(0.6) >= 13,
                "so the precomputed bound has to leave room for the extra turn");

        double d = 0.6;
        for (int i = 0; i < 11; i++) {
            d = Simulation.edgeBackOffStep(d);
        }
        assertEquals(0.05, d, 1.0e-12, "eleven turns leave one step of travel");
        d = Simulation.edgeBackOffStep(d);
        assertTrue(d > 0.0 && d < 1.0e-16,
                "and the twelfth leaves a residue the guard cannot see as zero: " + d);
        assertEquals(0.0, Simulation.edgeBackOffStep(d), "the thirteenth snaps it");
    }

    @Test
    void aSnapHappensInsideTheLastStepNotAfterIt() {
        assertEquals(0.0, Simulation.edgeBackOffStep(0.049),
                "anything already inside a step of zero is snapped, never overshot into the"
                        + " opposite sign");
        assertEquals(0.0, Simulation.edgeBackOffStep(-0.049), "and the same on the way up");
        assertEquals(-0.05, Simulation.edgeBackOffStep(-0.1), 1.0e-12, "otherwise it steps");
        assertEquals(0.0, Simulation.edgeBackOffStep(-STEP),
                "minus exactly one step is inside the guard d0 >= -0.05, so it snaps");
        assertEquals(0.0, Simulation.edgeBackOffStep(STEP),
                "plus exactly one step is outside d0 < 0.05, so it subtracts instead, and 0.05"
                        + " minus 0.05 is zero either way. The asymmetry is vanilla's");
    }

    @Test
    void aMovementNoFloatCouldEverTerminateOnIsBoundedInsteadOfSpinning() {
        assertEquals(0, Simulation.edgeBackOffSteps(Double.NaN),
                "a NaN dx is never equal to 0.0, so the old while loop could not exit at all");
        assertEquals(0, Simulation.edgeBackOffSteps(Double.POSITIVE_INFINITY),
                "and an infinity walks toward zero 0.05 at a time, forever");
        assertEquals(Simulation.EDGE_BACK_OFF_STEP_CAP, Simulation.edgeBackOffSteps(1.0e9),
                "a finite but absurd movement is capped rather than trusted");
        assertTrue(Simulation.edgeBackOffSteps(0.0) >= 1, "zero still costs nothing to bound");
    }
}
