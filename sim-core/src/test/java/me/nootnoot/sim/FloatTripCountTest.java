package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FloatTripCountTest {
    private static final float STEP = Combat.BLAST_RAY_STEP_ENERGY;

    private static int vanillaAccumulatedTrips(float energy) {
        int n = 0;
        for (float f = energy; f > 0.0f; f -= STEP) {
            n++;
            if (n > 100_000) {
                return n;
            }
        }
        return n;
    }

    @Test
    void theBlastRayTripCountIsDerivedFromTheEnergyNotFromARunningSubtraction() {
        float energy = Combat.CRYSTAL_POWER * 1.3f;
        int steps = Combat.blastRaySteps(energy);

        assertTrue((steps - 1) * STEP < energy,
                "the count is ceil(energy / step), so one before the end still has energy left");
        assertFalse(steps * STEP < energy,
                "and the count itself does not. Both tests are index * step, never a running"
                        + " difference, so the number of cells a ray visits cannot depend on"
                        + " accumulated rounding");
        assertEquals(35, steps,
                "a crystal at its luckiest roll is 6.0 * 1.3 = 7.8 of energy, which is 35 cells");
    }

    @Test
    void anEnergyThatCannotMarchAtAllIsZeroSteps() {
        assertEquals(0, Combat.blastRaySteps(0.0f), "no energy, no cells");
        assertEquals(0, Combat.blastRaySteps(-1.0f), "and a negative roll cannot march backwards");
        assertEquals(0, Combat.blastRaySteps(Float.NaN),
                "NaN never satisfies energy > 0.0f, so it takes the same exit");
    }

    @Test
    void theIndexedCountTracksVanillaDecayToWithinOneCellAcrossEveryReachableEnergy() {
        int diverged = 0;
        int samples = 0;
        for (float power : new float[]{Combat.ANCHOR_POWER, Combat.CRYSTAL_POWER}) {
            for (int i = 0; i <= 200_000; i++) {
                float energy = power * (0.7f + (i / 200_000.0f) * 0.6f);
                int steps = Combat.blastRaySteps(energy);
                int accumulated = vanillaAccumulatedTrips(energy);
                samples++;
                if (steps != accumulated) {
                    diverged++;
                    assertEquals(1, Math.abs(steps - accumulated),
                            "the two formulations may never part by more than a single cell,"
                                    + " energy " + energy);
                }
            }
        }
        assertTrue(diverged * 10_000L < samples,
                "and they must agree on all but a vanishing slice of the roll: " + diverged
                        + " of " + samples);
    }

    @Test
    void theOneCellDivergenceIsPinnedInBothDirections() {
        float low = 9 * STEP;
        assertEquals(10, Combat.blastRaySteps(low),
                "indexing marches one cell further than vanilla here");
        assertEquals(9, vanillaAccumulatedTrips(low), "vanilla stops at nine");

        float high = 23 * STEP;
        assertEquals(23, Combat.blastRaySteps(high),
                "and one cell short of vanilla here");
        assertEquals(24, vanillaAccumulatedTrips(high), "vanilla takes a twenty-fourth trip");

        assertTrue(Combat.blastRaySteps(low) != vanillaAccumulatedTrips(low)
                        && Combat.blastRaySteps(high) != vanillaAccumulatedTrips(high),
                "Explosion.explode marches with `for (; f > 0.0F; f -= 0.22500001F)` and advances"
                        + " the ray with `d4 += d0 * 0.30000001192092896`. The sim now derives the"
                        + " trip count from the energy once and indexes both the energy and the"
                        + " position off i, which is merely equivalent in intent, not bit-identical."
                        + " It parts from vanilla only where the energy lands within a rounding"
                        + " whisker of an exact multiple of the step, and then by exactly one cell"
                        + " at the tail of one ray. That tail cell can never break a block: the"
                        + " energy left there is under a millionth, and any block at all takes"
                        + " (resistance + 0.3) * 0.3 off it, which drives it negative before the"
                        + " break is recorded. The one thing it can change is whether a fire in"
                        + " that last cell is put out.");
    }

    private static int marchTripsUnderDrain(float energy, float[] bites) {
        int steps = Combat.blastRaySteps(energy);
        float drain = 0.0f;
        int marched = 0;
        for (int i = 0; i < steps; i++) {
            float f = energy - i * STEP - drain;
            if (f <= 0.0f) {
                break;
            }
            marched++;
            if (i < bites.length) {
                drain += bites[i];
            }
        }
        return marched;
    }

    @Test
    void theOnlyAccumulatorLeftInTheMarchIsTheBlockDrainAndItCanNeverLengthenTheRay() {
        float energy = Combat.CRYSTAL_POWER * 1.3f;
        int bound = Combat.blastRaySteps(energy);

        assertEquals(bound, marchTripsUnderDrain(energy, new float[0]),
                "with nothing in the way the ray walks exactly the count derived from the energy");

        float[] obsidian = new float[64];
        Arrays.fill(obsidian, (1200.0f + 0.3f) * 0.3f);
        assertEquals(1, marchTripsUnderDrain(energy, obsidian),
                "one obsidian cell eats the whole roll, which is a physical stop, not a rounding"
                        + " one");

        float[] poisoned = new float[64];
        Arrays.fill(poisoned, Float.NaN);
        assertEquals(bound, marchTripsUnderDrain(energy, poisoned),
                "even a drain that has gone NaN cannot walk past the integer bound: NaN fails"
                        + " f <= 0.0f, so the ray runs to the end of the count and stops there");

        float[] negative = new float[64];
        Arrays.fill(negative, -1000.0f);
        assertEquals(bound, marchTripsUnderDrain(energy, negative),
                "and a drain that somehow paid energy back still cannot buy a step the count"
                        + " never allotted");
    }

    @Test
    void theStepItselfIsIndexedNotAccumulatedSoTheDrainIsTheWholeResidual() {
        float energy = Combat.ANCHOR_POWER * 1.1f;
        int steps = Combat.blastRaySteps(energy);
        for (int i = 0; i < steps; i++) {
            float indexed = energy - i * STEP;
            assertTrue(indexed <= energy, "the energy at index " + i + " never grows");
        }
        assertEquals(Float.floatToRawIntBits(energy - 0 * STEP), Float.floatToRawIntBits(energy),
                "index zero is the untouched roll, exactly as vanilla's first pass is");
        assertTrue(energy - steps * STEP <= 0.0f,
                "and the count is the first index at which the ray is out of energy, so the loop"
                        + " bound and the loop body agree on where the ray ends. The drain a block"
                        + " takes off it is vanilla's own `f -= (resistance + 0.3F) * 0.3F` and"
                        + " cannot be precomputed: each bite is discovered by the march. It is a"
                        + " dependent recurrence over a fixed sequence of IEEE-754 float"
                        + " operations, which the JLS makes bit-identical on every JVM, and it is"
                        + " bounded above by the integer count either way");
    }

    @Test
    void thePearlPushBackWalksTheSameOffsetsItAlwaysDid() {
        double stepSize = 0.25;
        double maxPushBack = 3.0;
        int steps = (int) (maxPushBack / stepSize);
        assertEquals(12, steps, "3.0 of push-back in quarter-block bites is twelve probes");

        double accumulated = 0.0;
        for (int i = 1; i <= steps; i++) {
            accumulated += stepSize;
            assertEquals(Double.doubleToRawLongBits(accumulated),
                    Double.doubleToRawLongBits(stepSize * i),
                    "0.25 and 3.0 are both exact in binary, so lifting the pearl probe out of"
                            + " `for (double step = 0.25; step <= 3.0; step += 0.25)` into an"
                            + " indexed 0.25 * i is bit-identical, not merely equivalent: probe "
                            + i);
        }
        assertEquals(Double.doubleToRawLongBits(maxPushBack),
                Double.doubleToRawLongBits(stepSize * steps),
                "and the last probe lands exactly on the limit, so no probe was lost or gained");
    }
}
