package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CrossArchDeterminismTest {
    private static final double GROUND_Y = 64.0;

    private static int vanillaAccumulatedCount(double step) {
        int n = 0;
        for (double v = 0.0; v <= 1.0 && n < 4096; v += step) {
            n++;
        }
        return n;
    }

    private static double[] vanillaAccumulatedPositions(double step) {
        double[] out = new double[vanillaAccumulatedCount(step)];
        int n = 0;
        for (double v = 0.0; v <= 1.0 && n < out.length; v += step) {
            out[n++] = v;
        }
        return out;
    }

    private static double stepFor(double span) {
        return 1.0 / (span * 2.0 + 1.0);
    }

    @Test
    void theExposureSampleCountIsNowAnIntegerComputedOnce() {
        assertEquals(3, Combat.exposureSampleCount(stepFor(Simulation.PLAYER_WIDTH)),
                "the hull is 0.6 wide on X and Z, so 1/(0.6*2+1) gives three columns of samples");
        assertEquals(5, Combat.exposureSampleCount(stepFor(Simulation.PLAYER_HEIGHT)),
                "a standing 1.8 hull gives five layers");
        assertEquals(5, Combat.exposureSampleCount(stepFor(Simulation.PLAYER_SNEAK_HEIGHT)),
                "a sneaking 1.5 hull also gives five");
        assertEquals(3, Combat.exposureSampleCount(stepFor(Simulation.PLAYER_SWIM_HEIGHT)),
                "a prone 0.6 hull gives three");
    }

    @Test
    void theCountItselfIsNoLongerDerivedFromAnAccumulator() {
        double step = stepFor(Simulation.PLAYER_HEIGHT);
        int n = Combat.exposureSampleCount(step);

        assertTrue((n - 1) * step <= 1.0,
                "the count is the number of i with i * step <= 1.0, so the last index is in range");
        assertFalse(n * step <= 1.0,
                "and one past the last index is out of range - both tests are index * step, never"
                        + " a running sum, so the trip count cannot depend on accumulated rounding");
    }

    @Test
    void theIntegerReformulationIsBitIdenticalForEveryHullTheSimCanProduce() {
        double[] spans = {
            Simulation.PLAYER_WIDTH,
            Simulation.PLAYER_HEIGHT,
            Simulation.PLAYER_SNEAK_HEIGHT,
            Simulation.PLAYER_SWIM_HEIGHT,
        };
        for (double span : spans) {
            double step = stepFor(span);
            double[] want = vanillaAccumulatedPositions(step);
            int got = Combat.exposureSampleCount(step);
            assertEquals(want.length, got, "sample count for span " + span);
            for (int i = 0; i < got; i++) {
                assertEquals(Double.doubleToRawLongBits(want[i]), Double.doubleToRawLongBits(i * step),
                        "Explosion.getSeenPercent walks its axis with `for (double d5 = 0.0; d5 <= 1.0;"
                                + " d5 += d0)`. The sim now indexes instead, i * step, which is only"
                                + " equivalent in intent in general - but for every span a player hull"
                                + " can actually have (width 0.6, height 1.8, 1.5 or 0.6) it comes out"
                                + " bit-identical, count and sample bits alike. Span " + span
                                + " sample " + i);
            }
        }
    }

    @Test
    void theOnlySpansWhereTheReformulationDivergesAreOnesNoHullCanReach() {
        double span = 4.0;
        double step = stepFor(span);

        assertEquals(9, vanillaAccumulatedCount(step),
                "1/9 accumulated nine times lands just above 1.0, so vanilla stops at nine samples");
        assertEquals(10, Combat.exposureSampleCount(step),
                "indexing says ten, because 9 * (1/9) is exactly 1.0 while the running sum has"
                        + " already drifted past it. This is the transcription decision, stated"
                        + " plainly: the reformulation is bit-identical to vanilla for every"
                        + " reachable hull and merely equivalent in intent elsewhere. It diverges"
                        + " exactly when 2*span+1 is an integer - 4.0, 5.0, 8.5, 12.0, 20.0 and so"
                        + " on - and poseHeight only ever returns 0.6, 1.5 or 1.8 while the width"
                        + " is fixed at 0.6, so none of those spans can occur.");

        assertNotEquals(vanillaAccumulatedCount(step), Combat.exposureSampleCount(step),
                "pinned so nobody 'restores' the accumulation later without noticing the trade");
    }

    @Test
    void aDegenerateStepNowFailsInsteadOfSilentlyClampingToTheCap() {
        assertThrows(IllegalArgumentException.class, () -> Combat.exposureSampleCount(0.0),
                "a zero step used to come back as EXPOSURE_SAMPLE_CAP, a plausible-looking 64 that"
                        + " hid the bug that produced it. It has to fail instead.");
        assertThrows(IllegalArgumentException.class, () -> Combat.exposureSampleCount(Double.NaN),
                "NaN never satisfies step > 0.0, so it takes the same exit");
        assertThrows(IllegalStateException.class, () -> Combat.exposureSampleCount(1.0 / 128.0),
                "a step that genuinely needs more than the cap is a hull 63 blocks across; that is"
                        + " a broken pose, not something to truncate the sample grid over");
    }

    @Test
    void theExposureGridIsStillBoundedSoNoInputCanSpinIt() {
        assertEquals(64, Combat.EXPOSURE_SAMPLE_CAP,
                "the counting loop tests n <= EXPOSURE_SAMPLE_CAP, so it can never run more than"
                        + " 65 times whatever step it is handed");
    }

    @Test
    void anUnobstructedBlastStillSeesTheWholeHull() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.y = 100.0;
        a.z = 0.0;
        a.health = 20f;
        s.players[1].x = 10_000.0;
        s.players[1].y = 100.0;
        s.players[1].health = 20f;

        float power = 1.0f;
        double q = power * 2.0;
        Combat.explode(s, arena, a.x + 1.0, a.y, a.z, power, 0, false);

        double t = 1.0 - 1.0 / q;
        double expected = (t * t + t) / 2.0 * 7.0 * q + 1.0;
        assertEquals(20.0 - expected, a.health, 1.0E-9,
                "nothing stands between the blast and the hull, so every one of the 3 * 5 * 3 rays"
                        + " is clear and the exposure term is exactly 1.0 - the reformulated loop"
                        + " has to reproduce that to the last bit");
    }

    private static void web(GameState s, int x, int y, int z) {
        s.cobwebs.put(BlockStore.key(x, y, z), 1);
    }

    private static long[] keysOf(List<Aabb> boxes) {
        long[] out = new long[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            Aabb b = boxes.get(i);
            out[i] = BlockStore.key((int) b.minX, (int) b.minY, (int) b.minZ);
        }
        return out;
    }

    private static GameState threeWebs() {
        GameState s = new GameState();
        web(s, 0, 64, 0);
        web(s, 1, 64, 3);
        web(s, 2, 64, 6);
        return s;
    }

    private static Aabb wideBounds() {
        return new Aabb(-40.0, 40.0, -40.0, 40.0, 90.0, 40.0);
    }

    @Test
    void theCobwebSweepComesBackInADefinedOrder() {
        GameState s = threeWebs();
        List<Aabb> out = new ArrayList<>();
        ClaimAuthority.collectNearCobwebs(s, out, wideBounds());

        assertEquals(3, out.size(), "the bounds cover every web");
        long[] got = keysOf(out);
        long[] want = got.clone();
        java.util.Arrays.sort(want);
        assertArrayEquals(want, got,
                "the wide-bounds arm walks s.cobwebs.keySet(), and HashMap iteration order is not"
                        + " replicated state, so the list has to be put into key order before it"
                        + " leaves the method");
    }

    @Test
    void aRollbackSnapshotIteratesTheCobwebMapInADifferentOrderThanTheStateItCameFrom() {
        GameState live = threeWebs();
        GameState snap = live.copy();

        List<Long> liveRaw = new ArrayList<>(live.cobwebs.keySet());
        List<Long> snapRaw = new ArrayList<>(snap.cobwebs.keySet());
        assertNotEquals(liveRaw, snapRaw,
                "GameState.copy() rebuilds the map with new HashMap<>(cobwebs), which sizes its"
                        + " table from the source size instead of growing into it. With three"
                        + " entries that is already enough to iterate in a different order, so"
                        + " 'the snapshot has the same contents' never implied 'the snapshot"
                        + " iterates the same way'. This is the divergence mechanism, and it does"
                        + " not need two architectures - one machine rolling back is enough.");

        List<Aabb> fromLive = new ArrayList<>();
        List<Aabb> fromSnap = new ArrayList<>();
        ClaimAuthority.collectNearCobwebs(live, fromLive, wideBounds());
        ClaimAuthority.collectNearCobwebs(snap, fromSnap, wideBounds());
        assertArrayEquals(keysOf(fromLive), keysOf(fromSnap),
                "and after the sort the two agree, which is the whole point");
    }

    @Test
    void everyCobwebSweepCallerReducesTheListWithOrAnywaySoTheOrderCouldNotHaveBeenSeenYet() {
        GameState s = threeWebs();
        List<Aabb> forward = new ArrayList<>();
        ClaimAuthority.collectNearCobwebs(s, forward, wideBounds());
        List<Aabb> backward = new ArrayList<>(forward);
        Collections.reverse(backward);

        double ox = -5.0;
        double oy = 64.5;
        double oz = -5.0;
        Vec3 through = new Vec3(2.5, 64.5, 6.5);
        Vec3 clear = new Vec3(-5.0, 90.0, -5.0);

        assertTrue(ClaimAuthority.cobwebCrosses(forward, ox, oy, oz, through),
                "the positive aim really does cross a web, or the pair below proves nothing");
        assertEquals(ClaimAuthority.cobwebCrosses(forward, ox, oy, oz, through),
                ClaimAuthority.cobwebCrosses(backward, ox, oy, oz, through),
                "both of today's callers, meleeClaim and the GameState overload of cobwebCrosses,"
                        + " reduce the list with an existential OR and return a boolean, so the"
                        + " order is genuinely unobservable to them. The sort is for the next"
                        + " caller, which the public List<Aabb> return type invites.");
        assertEquals(ClaimAuthority.cobwebCrosses(forward, ox, oy, oz, clear),
                ClaimAuthority.cobwebCrosses(backward, ox, oy, oz, clear),
                "same for the negative case");
        assertFalse(ClaimAuthority.cobwebCrosses(forward, ox, oy, oz, clear),
                "straight up out of the webs is clear, so the pair above is not two trues by luck");
    }

    private static GameState threeBlocks() {
        GameState s = new GameState();
        s.blocks.place(0, 64, 0, 700);
        s.blocks.place(1, 64, 3, 700);
        s.blocks.place(2, 64, 6, 700);
        return s;
    }

    private static long[] keysOf(Aabb[] boxes) {
        long[] out = new long[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            out[i] = BlockStore.key((int) boxes[i].minX, (int) boxes[i].minY, (int) boxes[i].minZ);
        }
        return out;
    }

    @Test
    void thePlacedBlockArrayComesBackInKeyOrder() {
        GameState s = threeBlocks();
        long[] got = keysOf(s.blocks.solids());
        long[] want = got.clone();
        java.util.Arrays.sort(want);
        assertArrayEquals(want, got,
                "solids() used to walk the block HashMap's keySet straight into the array");
    }

    @Test
    void aRollbackSnapshotBuildsTheSameSolidsArrayAsTheStateItCameFrom() {
        GameState live = threeBlocks();
        GameState snap = live.copy();

        assertArrayEquals(keysOf(live.blocks.solids()), keysOf(snap.blocks.solids()),
                "Projectiles.ejectFromSolids picks the box with the strictly smallest penetration"
                        + " (`pen < minPen`), so a tie - which is the normal case for a player"
                        + " embedded in a flat wall of placed blocks - was resolved by whichever"
                        + " box the array happened to list first. That decides which way the pearl"
                        + " rider is pushed out, i.e. their position, i.e. the checksum. Three"
                        + " placed blocks were already enough for a snapshot to disagree with the"
                        + " state it was copied from.");
    }

    private static long solidifyKey(CombatEvent e) {
        return BlockStore.key(e.attacker(), e.victim(), e.kind());
    }

    @Test
    void lavaMeetingWaterSolidifiesInKeyOrderNotInMapOrder() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = new GameState();
        s.tick = 0;
        s.obsidianItemId = 800;
        s.cobblestoneItemId = 801;
        s.fluids.put(BlockStore.key(0, 70, 0), Fluids.pack(Fluids.WATER, 8, true, false));
        int[][] lava = {{1, 70, 0}, {-1, 70, 0}, {0, 70, 1}, {0, 70, -1}, {0, 71, 0}};
        for (int[] c : lava) {
            s.fluids.put(BlockStore.key(c[0], c[1], c[2]), Fluids.pack(Fluids.LAVA, 8, true, false));
        }

        Fluids.flow(s, arena);

        List<Long> got = new ArrayList<>();
        for (CombatEvent e : s.events) {
            if (e.type() == CombatEvent.FLUID_SOLIDIFY) {
                got.add(solidifyKey(e));
            }
        }
        assertTrue(got.size() >= lava.length,
                "every lava source touches the water source, and the flow pass that runs first"
                        + " puts a few more lava cells against it");
        List<Long> want = new ArrayList<>(got);
        Collections.sort(want);
        assertEquals(want, got,
                "resolveLavaWater collected its victims by walking s.fluids.entrySet() and then"
                        + " emitted a FLUID_SOLIDIFY per victim in that order. The events list is"
                        + " handed to both peers by RollbackController, so the render order of the"
                        + " conversion was map order. flowType right above it already sorts its"
                        + " work list for exactly this reason; this arm now matches it.");
    }
}
