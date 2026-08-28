package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class FluidHoleSeekTest {
    private static final int Y0 = 64;
    private static final int FLOW_Y = Y0 + 1;

    private static Arena platform(int n) {
        int base = -(n / 2);
        boolean[] grid = new boolean[n * 1 * n];
        java.util.Arrays.fill(grid, true);
        return new Arena(0.0, grid, base, Y0, base, n, 1, n, new double[0][], java.util.Map.of());
    }

    @Test
    void waterPouredOnTheGroundPlaneDoesNotFallThroughIt() {
        Arena arena = Arena.flat(Y0);
        GameState s = freshDuelFarAway(arena);

        Fluids.place(s, arena, 0, Fluids.WATER, 0, Y0, 0);
        tick(s, arena, 200);

        int belowFloor = 0;
        int lowest = Integer.MAX_VALUE;
        for (var e : s.fluids.entrySet()) {
            int y = BlockStore.unpackY(e.getKey());
            lowest = Math.min(lowest, y);
            if (y < Y0) {
                belowFloor++;
            }
        }
        System.out.println("[groundPlane] cells=" + s.fluids.size() + " lowest=" + lowest
                + " belowFloor=" + belowFloor);
        assertTrue(s.fluids.size() > 1, "the source should have spread across the floor");
        assertTrue(belowFloor == 0, "water leaked below the ground plane: " + belowFloor + " cells");
    }

    @Test
    void breakingArenaVoxelsCannotOpenAHoleInTheGroundPlane() {
        Arena arena = Arena.flat(Y0);
        GameState s = freshDuelFarAway(arena);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                s.brokenArena.add(BlockStore.key(x, Y0 - 1, z));
            }
        }

        Fluids.place(s, arena, 0, Fluids.WATER, 0, Y0, 0);
        tick(s, arena, 200);

        for (var e : s.fluids.entrySet()) {
            assertTrue(BlockStore.unpackY(e.getKey()) >= Y0,
                    "water passed through the unbreakable ground slab at y="
                            + BlockStore.unpackY(e.getKey()));
        }
    }

    private static GameState freshDuelFarAway(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        s.players[0].x = 10_000;
        s.players[0].z = 10_000;
        s.players[1].x = -10_000;
        s.players[1].z = -10_000;
        return s;
    }

    private static int waterCellsAtLayer(GameState s, int y) {
        int c = 0;
        for (var e : s.fluids.entrySet()) {
            if (BlockStore.unpackY(e.getKey()) == y && Fluids.type(e.getValue()) == Fluids.WATER) {
                c++;
            }
        }
        return c;
    }

    private static int totalWaterCells(GameState s) {
        int c = 0;
        for (int v : s.fluids.values()) {
            if (Fluids.type(v) == Fluids.WATER) {
                c++;
            }
        }
        return c;
    }

    private static boolean waterAt(GameState s, int x, int y, int z) {
        Integer v = s.fluids.get(BlockStore.key(x, y, z));
        return v != null && Fluids.type(v) == Fluids.WATER;
    }

    private static void tick(GameState s, Arena arena, int n) {
        for (int i = 0; i < n; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
    }

    private static String cell(Integer v) {
        if (v == null) {
            return ".";
        }
        if (Fluids.type(v) != Fluids.WATER) {
            return "L";
        }
        if (Fluids.isSource(v)) {
            return "S";
        }
        if (Fluids.isFalling(v)) {
            return "v";
        }
        return Integer.toString(Fluids.amount(v));
    }

    private static void dumpLayer(GameState s, int y, int r, String label) {
        System.out.println("  layer y=" + y + " " + label + " (S=source v=falling N=level .=empty):");
        for (int z = -r; z <= r; z++) {
            StringBuilder sb = new StringBuilder("    ");
            for (int x = -r; x <= r; x++) {
                sb.append(cell(s.fluids.get(BlockStore.key(x, y, z))));
            }
            System.out.println(sb);
        }
    }

    @Test
    void sourceOnOpenPlatformDoesNotSheetFullPool() {
        int n = 9;
        Arena arena = platform(n);
        GameState s = freshDuelFarAway(arena);

        Fluids.place(s, arena, 0, Fluids.WATER, 0, FLOW_Y, 0);
        tick(s, arena, 300);

        int topLayer = waterCellsAtLayer(s, FLOW_Y);
        int total = totalWaterCells(s);
        int fullSheet = 15 * 15;
        System.out.println("[POOL BOUND] platform " + n + "x" + n
                + "  on-top water cells = " + topLayer
                + "  total (all layers) = " + total
                + "  (radius-7 sheet = " + fullSheet + ")");
        dumpLayer(s, FLOW_Y, 7, "platform top");

        assertTrue(topLayer < n * n,
                "on-top water blanketed the WHOLE platform (" + topLayer + " of " + (n * n)
                        + " cells) — the hole-gate did not bound the pool");
    }

    @Test
    void flowIsBiasedTowardASingleHole() {
        int n = 21;
        int half = n / 2;
        int hx = 3;
        boolean[] grid = new boolean[n * n];
        java.util.Arrays.fill(grid, true);
        grid[(0 + half) * n + (hx + half)] = false;
        Arena arena = new Arena(0.0, grid, -half, Y0, -half, n, 1, n, new double[0][], java.util.Map.of());
        GameState s = freshDuelFarAway(arena);

        Fluids.place(s, arena, 0, Fluids.WATER, 0, FLOW_Y, 0);
        tick(s, arena, 200);

        boolean fellIntoHole = waterAt(s, hx, Y0, 0);
        int east = 0;
        for (int x = 1; x <= 9 && waterAt(s, x, FLOW_Y, 0); x++) {
            east = x;
        }
        int west = 0;
        for (int x = 1; x <= 9 && waterAt(s, -x, FLOW_Y, 0); x++) {
            west = x;
        }
        System.out.println("[HOLE-SEEK] hole at x=" + hx + "  fellIntoHole=" + fellIntoHole
                + "  eastReach(toward hole)=" + east + "  westReach(away)=" + west);
        dumpLayer(s, FLOW_Y, 9, "platform top — hole under (3,0)");

        assertTrue(fellIntoHole, "water must reach and pour DOWN through the hole");

        assertTrue(east > west,
                "flow is NOT biased toward the hole: eastReach " + east + " is not > westReach " + west
                        + " (the spread is a symmetric radius-7 diamond that ignores the hole direction)");
    }

    @Test
    void fallingColumnReachesBottomAndSpreadsThere() {
        int n = 15;
        int half = n / 2;
        int floorY = Y0 - 5;
        int sizeY = Y0 - floorY + 1;
        boolean[] grid = new boolean[n * sizeY * n];
        for (int rz = 0; rz < n; rz++) {
            for (int rx = 0; rx < n; rx++) {
                grid[(rz * sizeY + 0) * n + rx] = true;
            }
        }
        Arena arena = new Arena(0.0, grid, -half, floorY, -half, n, sizeY, n, new double[0][], java.util.Map.of());
        GameState s = freshDuelFarAway(arena);

        Fluids.place(s, arena, 0, Fluids.WATER, 0, Y0, 0);
        tick(s, arena, 300);

        boolean columnFilled = waterAt(s, 0, floorY + 1, 0) && waterAt(s, 0, Y0 - 1, 0);
        int bottomSpread = waterCellsAtLayer(s, floorY + 1);
        System.out.println("[FALL → BOTTOM] columnFilled=" + columnFilled
                + "  bottomSpread(layer " + (floorY + 1) + ")=" + bottomSpread);

        assertTrue(columnFilled, "water must fall straight down the column to the floor");
        assertTrue(bottomSpread > 9, "water must spread OUT once it lands; was " + bottomSpread);
    }

    @Test
    void fallingColumnDoesNotSheetMidAir() {
        int n = 15;
        int half = n / 2;
        int floorY = Y0 - 5;
        int sizeY = Y0 - floorY + 1;
        boolean[] grid = new boolean[n * sizeY * n];
        for (int rz = 0; rz < n; rz++) {
            for (int rx = 0; rx < n; rx++) {
                grid[(rz * sizeY + 0) * n + rx] = true;
            }
        }
        Arena arena = new Arena(0.0, grid, -half, floorY, -half, n, sizeY, n, new double[0][], java.util.Map.of());
        GameState s = freshDuelFarAway(arena);
        Fluids.place(s, arena, 0, Fluids.WATER, 0, Y0, 0);
        tick(s, arena, 300);

        int midMax = 0;
        for (int y = floorY + 2; y <= Y0 - 1; y++) {
            midMax = Math.max(midMax, waterCellsAtLayer(s, y));
        }
        System.out.println("[FALL MID-AIR] widest mid-air layer = " + midMax + " cells"
                + "  (a thin column would be ~1-5; a radius-7 sheet is ~113)");

        assertTrue(midMax <= 9,
                "a falling column SHEETS a full pool mid-air (widest mid-air layer = " + midMax
                        + " cells) — the 'falling' flag does not stop horizontal mid-air spread");
    }

    @Test
    void infiniteWaterFormsBetweenTwoSources() {
        Arena arena = platform(9);
        GameState s = freshDuelFarAway(arena);
        Fluids.place(s, arena, 0, Fluids.WATER, -1, FLOW_Y, 0);
        Fluids.place(s, arena, 0, Fluids.WATER, 1, FLOW_Y, 0);
        tick(s, arena, 100);

        Integer mid = s.fluids.get(BlockStore.key(0, FLOW_Y, 0));
        boolean midIsSource = mid != null && Fluids.isSource(mid) && Fluids.type(mid) == Fluids.WATER;
        System.out.println("[INFINITE] gap cell=" + cell(mid) + "  isSource=" + midIsSource
                + "  totalWater=" + totalWaterCells(s));
        assertTrue(midIsSource, "the cell between two water sources must itself become a source");
    }

    @Test
    void flatFloorWithNoHoleStillSpreadsOutward() {
        int n = 31;
        Arena arena = platform(n);
        GameState s = freshDuelFarAway(arena);
        Fluids.place(s, arena, 0, Fluids.WATER, 0, FLOW_Y, 0);
        tick(s, arena, 200);

        int top = waterCellsAtLayer(s, FLOW_Y);
        boolean e = waterAt(s, 3, FLOW_Y, 0);
        boolean w = waterAt(s, -3, FLOW_Y, 0);
        boolean nN = waterAt(s, 0, FLOW_Y, 3);
        boolean sS = waterAt(s, 0, FLOW_Y, -3);
        System.out.println("[FLAT NO-HOLE] topLayerCells=" + top + "  E=" + e + " W=" + w
                + " N=" + nN + " S=" + sS);

        assertTrue(top > 1, "flow must NOT freeze on a flat floor with no hole in range; cells=" + top);
        assertTrue(e && w && nN && sS,
                "no-hole fallback must spread outward in all four directions");
    }

    @Test
    void thousandsOfTicksTerminateAndAreDeterministic() {
        Arena arenaA = platform(13);
        Arena arenaB = platform(13);
        GameState a = freshDuelFarAway(arenaA);
        GameState b = freshDuelFarAway(arenaB);
        Fluids.place(a, arenaA, 0, Fluids.WATER, 0, FLOW_Y, 0);
        Fluids.place(b, arenaB, 0, Fluids.WATER, 0, FLOW_Y, 0);

        long start = System.nanoTime();
        tick(a, arenaA, 5000);
        tick(b, arenaB, 5000);
        double ms = (System.nanoTime() - start) / 1_000_000.0;

        long ca = Checksum.of(a);
        long cb = Checksum.of(b);
        System.out.println("[TERMINATION] 2x5000 ticks in " + String.format("%.1f", ms)
                + " ms  waterCellsA=" + totalWaterCells(a)
                + "  checksumA==checksumB ? " + (ca == cb));
        assertTrue(ca == cb, "identical setups must produce identical fluid state (determinism)");
        assertFalse(a.fluids.isEmpty(), "the source must persist (water did not vanish)");
    }
}
