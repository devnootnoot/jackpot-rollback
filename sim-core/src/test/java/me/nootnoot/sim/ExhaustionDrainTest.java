package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExhaustionDrainTest {
    private static int accumulatedRounds(float exhaustion) {
        int n = 0;
        while (exhaustion >= 4.0f && n < 1_000_000) {
            exhaustion -= 4.0f;
            n++;
        }
        return n;
    }

    private static float accumulatedResidue(float exhaustion) {
        int guard = 0;
        while (exhaustion >= 4.0f && guard++ < 1_000_000) {
            exhaustion -= 4.0f;
        }
        return exhaustion;
    }

    @Test
    void theRoundCountMatchesTheRunningSubtractionBitForBitAcrossEveryReachableExhaustion() {
        for (int i = 0; i <= 400_000; i++) {
            float exhaustion = i * (Simulation.MAX_EXHAUSTION / 400_000.0f);
            assertEquals(accumulatedRounds(exhaustion), Simulation.exhaustionRounds(exhaustion),
                    "the indexed count parts from the loop it replaced at " + exhaustion);
            assertEquals(Float.floatToRawIntBits(accumulatedResidue(exhaustion)),
                    Float.floatToRawIntBits(exhaustion - 4.0f * Simulation.exhaustionRounds(exhaustion)),
                    "and the residue it leaves behind is not the same float at " + exhaustion);
        }
    }

    @Test
    void theCountIsBoundedBecauseTheExhaustionItselfIsClamped() {
        assertEquals(10, Simulation.exhaustionRounds(Simulation.MAX_EXHAUSTION),
                "40 of exhaustion is ten drains, which is the ceiling vanilla's own"
                        + " FoodData.addExhaustion clamp imposes");
        assertEquals(Simulation.MAX_EXHAUSTION, Simulation.clampExhaustion(1.0e30f),
                "an absurd value is pulled back onto the clamp instead of spinning a while loop"
                        + " that a float can never terminate: at 1e30 the float x - 4.0f is x");
        assertEquals(0.0f, Simulation.clampExhaustion(Float.NaN), "NaN never drains, so it is reset");
        assertEquals(0.0f, Simulation.clampExhaustion(Float.POSITIVE_INFINITY),
                "and neither does an infinity");
    }

    @Test
    void aFloatThatCanNoLongerSubtractIsWhyTheOldLoopHadNoUpperBound() {
        float huge = 1.0e30f;
        assertEquals(Float.floatToRawIntBits(huge), Float.floatToRawIntBits(huge - 4.0f),
                "4.0f is far below one ulp here, so `while (e >= 4.0f) { e -= 4.0f; }` never"
                        + " terminates. That is a hang, not a rounding difference");
        assertEquals(0, Simulation.exhaustionRounds(Float.NaN), "NaN fails the >= 4.0f gate");
        assertEquals(0, Simulation.exhaustionRounds(-1.0f), "and so does a negative");
        assertTrue(Simulation.exhaustionRounds(Simulation.clampExhaustion(huge)) <= 10,
                "clamped first, the count can never exceed ten");
    }
}
