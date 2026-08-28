package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemEntityState;
import org.junit.jupiter.api.Test;

class BlastDropDecayTest {
    private static final double GROUND_Y = 64.0;

    private static final int BASE_X = 90;
    private static final int BASE_Y = 101;
    private static final int BASE_Z = 90;
    private static final int SX = 21;
    private static final int SY = 1;
    private static final int SZ = 21;

    private static final int DROP_ITEM_ID = 5;

    private static final double CX_BLAST = 100.5;
    private static final double CY_BLAST = 101.5;
    private static final double CZ_BLAST = 100.5;

    private static final int SAMPLES = 300;

    private static final float SOFT_RESISTANCE = 0.5f;

    private static Arena arena() {
        boolean[] grid = new boolean[SX * SY * SZ];
        Map<Long, Integer> dropItem = new HashMap<>();
        Map<Long, Float> resistance = new HashMap<>();
        for (int x = BASE_X; x < BASE_X + SX; x++) {
            for (int z = BASE_Z; z < BASE_Z + SZ; z++) {
                grid[idx(x, BASE_Y, z)] = true;
                dropItem.put(BlockStore.key(x, BASE_Y, z), DROP_ITEM_ID);
                resistance.put(BlockStore.key(x, BASE_Y, z), SOFT_RESISTANCE);
            }
        }
        return new Arena(GROUND_Y, grid, BASE_X, BASE_Y, BASE_Z, SX, SY, SZ,
                new double[0][], resistance, dropItem);
    }

    private static int idx(int x, int y, int z) {
        return ((z - BASE_Z) * SY + (y - BASE_Y)) * SX + (x - BASE_X);
    }

    private record Sample(int broken, int dropped) {
    }

    private static Sample blastAt(Arena arena, int tick, float power) {
        GameState s = HarnessScenarios.duel(arena);
        s.vanillaBuild = true;
        s.players[0].x = -50;
        s.players[1].x = -50;
        s.tick = tick;
        Combat.explode(s, arena, CX_BLAST, CY_BLAST, CZ_BLAST, power, 0, false);
        int dropped = 0;
        for (ItemEntityState e : s.items) {
            if (!e.dead && e.itemId == DROP_ITEM_ID) {
                dropped++;
            }
        }
        return new Sample(s.brokenArena.size(), dropped);
    }

    private static double measuredRate(float power) {
        Arena arena = arena();
        long broken = 0;
        long dropped = 0;
        for (int t = 0; t < SAMPLES; t++) {
            Sample sample = blastAt(arena, t, power);
            broken += sample.broken();
            dropped += sample.dropped();
        }
        assertTrue(broken > 8000, "the sample is too small to say anything: broken=" + broken);
        return (double) dropped / (double) broken;
    }

    @Test
    void aCrystalBlastDropsOneCellInSixJustLikeVanillasExplosionDecay() {
        double rate = measuredRate(Combat.CRYSTAL_POWER);
        double expected = 1.0 / Combat.CRYSTAL_POWER;
        assertEquals(expected, rate, 0.02,
                "ApplyExplosionDecay and ExplosionCondition both roll nextFloat() <= 1.0F / radius"
                        + " per dropped item, and an end crystal explodes at radius 6.0, so a"
                        + " blasted cell drops its block one time in six: measured " + rate);
    }

    @Test
    void anAnchorBlastDropsOneCellInFiveBecauseItsRadiusIsFive() {
        double rate = measuredRate(Combat.ANCHOR_POWER);
        double expected = 1.0 / Combat.ANCHOR_POWER;
        assertEquals(expected, rate, 0.02,
                "the decay probability is 1/radius, not a constant, so the anchor's 5.0 drops more"
                        + " of what it breaks than the crystal's 6.0: measured " + rate);
    }

    @Test
    void theRollNeverDecidesWhetherTheBlockBreaks() {
        Arena arena = arena();
        Sample first = blastAt(arena, 0, Combat.CRYSTAL_POWER);
        for (int t = 1; t < 40; t++) {
            Sample sample = blastAt(arena, t, Combat.CRYSTAL_POWER);
            assertTrue(sample.dropped() <= sample.broken(),
                    "more items dropped than cells broke at tick " + t);
        }
        assertTrue(first.broken() > 0, "the fixture never broke anything, so it tests nothing");
    }

    @Test
    void bothPeersRollTheSameDecayFromTheSameState() {
        Arena arena = arena();
        for (int t = 0; t < 20; t++) {
            Sample a = blastAt(arena, t, Combat.CRYSTAL_POWER);
            Sample b = blastAt(arena, t, Combat.CRYSTAL_POWER);
            assertEquals(a.dropped(), b.dropped(),
                    "the decay stream is seeded from the blast site, the tick and the power, so two"
                            + " peers running the same frame must lose the same rolls: tick " + t);
        }
    }

    @Test
    void theDecayRollsComeOutOfTheirOwnSaltedStream() {
        assertNotEquals(0L, Combat.BLAST_DROP_SEED_SALT,
                "an unsalted decay stream would be the same draws the ray energies and the fire"
                        + " scatter already spend, so adding drops would have moved both");
        java.util.Random shape = new java.util.Random(1234L);
        java.util.Random decay = new java.util.Random(1234L ^ Combat.BLAST_DROP_SEED_SALT);
        assertNotEquals(shape.nextFloat(), decay.nextFloat(),
                "the salted stream must not open on the same value the blast stream does");
    }

    @Test
    void theDecayGateIsExactlyOneOverTheRadius() {
        for (float power : new float[]{Combat.CRYSTAL_POWER, Combat.ANCHOR_POWER, 4.0f}) {
            int survived = 0;
            java.util.Random rng = new java.util.Random(99L);
            for (int i = 0; i < 60000; i++) {
                if (Combat.blastDropSurvives(rng, power)) {
                    survived++;
                }
            }
            assertEquals(1.0 / power, survived / 60000.0, 0.01,
                    "the gate is nextFloat() <= 1.0F / radius for radius " + power);
        }
    }
}
