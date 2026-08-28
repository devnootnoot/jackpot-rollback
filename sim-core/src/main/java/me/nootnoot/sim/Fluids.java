package me.nootnoot.sim;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;

public final class Fluids {
    public static final int WATER = 0;
    public static final int LAVA = 1;

    private static final int WATER_RATE = 5;
    private static final int LAVA_RATE = 30;

    private static final int WATER_DECAY = 1;
    private static final int LAVA_DECAY = 2;
    private static final int FULL = 8;

    public static final int MAX_CELLS = 8192;

    public static final int MAX_CELLS_PER_OWNER = MAX_CELLS / 2;

    public static final int NEUTRAL_OWNER = ItemEntities.NEUTRAL_OWNER;

    private static final int OWNER_SHIFT = 7;

    private static final int OWNER_BITS = 3;

    private static final int WATER_SLOPE_FIND = 4;
    private static final int LAVA_SLOPE_FIND = 2;

    private static final int[][] HORIZ = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final class FlowScan {
        private final java.util.HashMap<Long, Boolean> solid = new java.util.HashMap<>();
        private final java.util.HashMap<Long, Boolean> down = new java.util.HashMap<>();
        private final java.util.HashMap<Long, int[]> slopes = new java.util.HashMap<>();
    }

    private Fluids() {
    }

    static int pack(int type, int amount, boolean source, boolean falling) {
        return pack(type, amount, source, falling, NEUTRAL_OWNER);
    }

    static int pack(int type, int amount, boolean source, boolean falling, int owner) {
        return (amount & 0xF) | (source ? 1 << 4 : 0) | (falling ? 1 << 5 : 0) | (type << 6)
                | (ownerCode(owner) << OWNER_SHIFT);
    }

    static int ownerCode(int owner) {
        if (owner == 0) {
            return 1;
        }
        if (owner == 1) {
            return 2;
        }
        return 0;
    }

    private static int codeOf(int v) {
        return (v >> OWNER_SHIFT) & OWNER_BITS;
    }

    public static int owner(int v) {
        int code = codeOf(v);
        return code == 0 ? NEUTRAL_OWNER : code - 1;
    }

    public static int cellsOwnedBy(GameState s, int owner) {
        int code = ownerCode(owner);
        int n = 0;
        for (int v : s.fluids.values()) {
            if (codeOf(v) == code) {
                n++;
            }
        }
        return n;
    }

    public static boolean roomFor(GameState s, int owner) {
        return s.fluids.size() < MAX_CELLS && cellsOwnedBy(s, owner) < MAX_CELLS_PER_OWNER;
    }

    public static int amount(int v) {
        return v & 0xF;
    }

    public static int type(int v) {
        return (v >> 6) & 1;
    }

    public static boolean isSource(int v) {
        return (v & (1 << 4)) != 0;
    }

    static boolean isFalling(int v) {
        return (v & (1 << 5)) != 0;
    }

    public static boolean place(GameState s, Arena arena, int owner, int type, int x, int y, int z) {
        if (solidAt(s, arena, x, y, z)) {
            return false;
        }
        long k = BlockStore.key(x, y, z);
        Integer cur = s.fluids.get(k);
        if (cur != null && isSource(cur) && type(cur) == type) {
            return false;
        }
        if (!roomFor(s, owner)) {
            return false;
        }
        s.fluids.put(k, pack(type, FULL, true, false, owner));
        breakCobwebAt(s, owner, x, y, z);
        return true;
    }

    private static void breakCobwebAt(GameState s, int owner, int x, int y, int z) {
        long k = BlockStore.key(x, y, z);
        if (s.cobwebs.remove(k) != null && s.stringItemId != 0) {
            ItemEntities.spawn(s, owner, x + 0.5, y + 0.25, z + 0.5, 0.0, 0.15, 0.0,
                    s.stringItemId, 1, 10);
        }
        s.fires.remove(k);
    }

    public static int pickup(GameState s, int x, int y, int z) {
        long k = BlockStore.key(x, y, z);
        Integer cur = s.fluids.get(k);
        if (cur == null || !isSource(cur)) {
            return -1;
        }
        s.fluids.remove(k);
        return type(cur);
    }

    public static void flow(GameState s, Arena arena) {
        if (s.fluids.isEmpty()) {
            return;
        }
        boolean waterTick = s.tick % WATER_RATE == 0;
        boolean lavaTick = s.tick % LAVA_RATE == 0;
        if (!waterTick && !lavaTick) {
            return;
        }
        java.util.HashMap<Long, Integer> next = new java.util.HashMap<>(s.fluids);
        int[] counts = ownerCounts(next);
        if (waterTick) {
            flowType(s, arena, next, counts, WATER);
        }
        if (lavaTick) {
            flowType(s, arena, next, counts, LAVA);
        }
        s.fluids = next;
        resolveLavaWater(s);
    }

    private static void resolveLavaWater(GameState s) {
        if (s.fluids.isEmpty()) {
            return;
        }
        long[] convert = new long[s.fluids.size()];
        int converted = 0;
        for (var e : s.fluids.entrySet()) {
            int v = e.getValue();
            if (type(v) != LAVA) {
                continue;
            }
            long k = e.getKey();
            int x = BlockStore.unpackX(k);
            int y = BlockStore.unpackY(k);
            int z = BlockStore.unpackZ(k);
            if (touchesWater(s, x, y, z)) {
                convert[converted++] = k;
            }
        }
        if (converted == 0) {
            return;
        }
        java.util.Arrays.sort(convert, 0, converted);
        for (int ci = 0; ci < converted; ci++) {
            long k = convert[ci];
            Integer was = s.fluids.remove(k);
            boolean source = was != null && isSource(was);
            int id = source ? s.obsidianItemId : s.cobblestoneItemId;
            if (id == 0) {
                continue;
            }
            int x = BlockStore.unpackX(k);
            int y = BlockStore.unpackY(k);
            int z = BlockStore.unpackZ(k);
            s.blocks.place(x, y, z, id);
            s.blockResistance.put(k, source ? 1200.0f : 6.0f);

            s.events.add(new me.nootnoot.sim.state.CombatEvent(
                    me.nootnoot.sim.state.CombatEvent.FLUID_SOLIDIFY, x, y, false, z));
        }
    }

    private static boolean touchesWater(GameState s, int x, int y, int z) {
        return isWaterCell(s, x + 1, y, z) || isWaterCell(s, x - 1, y, z)
                || isWaterCell(s, x, y, z + 1) || isWaterCell(s, x, y, z - 1)
                || isWaterCell(s, x, y + 1, z) || isWaterCell(s, x, y - 1, z);
    }

    private static boolean isWaterCell(GameState s, int x, int y, int z) {
        Integer v = s.fluids.get(BlockStore.key(x, y, z));
        return v != null && type(v) == WATER;
    }

    private static int[] ownerCounts(java.util.HashMap<Long, Integer> cells) {
        int[] counts = new int[OWNER_BITS + 1];
        for (int v : cells.values()) {
            counts[codeOf(v)]++;
        }
        return counts;
    }

    private static void flowType(GameState s, Arena arena, java.util.HashMap<Long, Integer> next,
                                 int[] counts, int type) {
        java.util.HashSet<Long> active = new java.util.HashSet<>();
        for (var e : s.fluids.entrySet()) {
            if (type(e.getValue()) != type) {
                continue;
            }
            long k = e.getKey();
            int x = BlockStore.unpackX(k);
            int y = BlockStore.unpackY(k);
            int z = BlockStore.unpackZ(k);
            active.add(k);
            active.add(BlockStore.key(x, y - 1, z));
            active.add(BlockStore.key(x, y + 1, z));
            active.add(BlockStore.key(x + 1, y, z));
            active.add(BlockStore.key(x - 1, y, z));
            active.add(BlockStore.key(x, y, z + 1));
            active.add(BlockStore.key(x, y, z - 1));
        }

        long[] order = new long[active.size()];
        int oi = 0;
        for (long ak : active) {
            order[oi++] = ak;
        }
        java.util.Arrays.sort(order);
        FlowScan scan = new FlowScan();
        for (long k : order) {
            int x = BlockStore.unpackX(k);
            int y = BlockStore.unpackY(k);
            int z = BlockStore.unpackZ(k);
            int nv = compute(scan, s, arena, type, x, y, z);
            if (nv == 0) {
                Integer was = s.fluids.get(k);
                if (was != null && type(was) == type) {
                    Integer gone = next.remove(k);
                    if (gone != null) {
                        counts[codeOf(gone)]--;
                    }
                }
            } else if (next.containsKey(k)
                    || (next.size() < MAX_CELLS && counts[codeOf(nv)] < MAX_CELLS_PER_OWNER)) {
                Integer prev = next.put(k, nv);
                if (prev != null) {
                    counts[codeOf(prev)]--;
                }
                counts[codeOf(nv)]++;
                breakCobwebAt(s, owner(nv), x, y, z);
            }
        }
    }

    private static int compute(FlowScan scan, GameState s, Arena arena, int type, int x, int y, int z) {
        if (solidAt(scan, s, arena, x, y, z)) {
            return 0;
        }
        long k = BlockStore.key(x, y, z);
        Integer cur = s.fluids.get(k);
        if (cur != null && type(cur) != type) {
            return 0;
        }
        if (cur != null && isSource(cur) && type(cur) == type) {
            return cur;
        }

        Integer above = s.fluids.get(BlockStore.key(x, y + 1, z));
        if (above != null && type(above) == type && amount(above) > 0) {
            boolean solidBelow = solidAt(scan, s, arena, x, y - 1, z);
            return pack(type, FULL, false, !solidBelow, owner(above));
        }

        int best = 0;
        int bestOwner = NEUTRAL_OWNER;
        int sourceNeighbours = 0;
        int sourceOwner = NEUTRAL_OWNER;
        int slopeFind = type == LAVA ? LAVA_SLOPE_FIND : WATER_SLOPE_FIND;
        for (int[] d : HORIZ) {
            int nx = x + d[0];
            int nz = z + d[1];
            Integer nv = s.fluids.get(BlockStore.key(nx, y, nz));
            if (nv == null || type(nv) != type || isFalling(nv)) {
                continue;
            }

            if (canFlowDownAt(scan, s, arena, nx, y, nz, type)) {
                continue;
            }

            if (!feedsTowardUs(scan, s, arena, type, nx, y, nz, -d[0], -d[1], slopeFind)) {
                continue;
            }
            int amt = isSource(nv) ? FULL : amount(nv);
            if (amt > best) {
                best = amt;
                bestOwner = owner(nv);
            }
            if (isSource(nv)) {
                if (sourceNeighbours == 0) {
                    sourceOwner = owner(nv);
                }
                sourceNeighbours++;
            }
        }

        if (type == WATER && sourceNeighbours >= 2) {
            Integer below = s.fluids.get(BlockStore.key(x, y - 1, z));
            boolean stillBelow = solidAt(scan, s, arena, x, y - 1, z)
                    || (below != null && isSource(below) && type(below) == WATER);
            if (stillBelow) {
                return pack(WATER, FULL, true, false, sourceOwner);
            }
        }
        int decay = type == LAVA ? LAVA_DECAY : WATER_DECAY;
        int amt = best - decay;
        if (amt <= 0) {
            return 0;
        }
        return pack(type, amt, false, false, bestOwner);
    }

    private static int[] slopeDistances(FlowScan scan, GameState s, Arena arena, int type,
                                        int fx, int fy, int fz, int slopeFind) {
        long key = BlockStore.key(fx, fy, fz);
        int[] cached = scan.slopes.get(key);
        if (cached != null) {
            return cached;
        }
        int[] dist = new int[HORIZ.length];
        for (int i = 0; i < HORIZ.length; i++) {
            int nx = fx + HORIZ[i][0];
            int nz = fz + HORIZ[i][1];
            dist[i] = solidAt(scan, s, arena, nx, fy, nz)
                    ? Integer.MAX_VALUE
                    : slopeDistance(scan, s, arena, type, nx, fy, nz,
                            -HORIZ[i][0], -HORIZ[i][1], 1, slopeFind);
        }
        scan.slopes.put(key, dist);
        return dist;
    }

    private static boolean feedsTowardUs(FlowScan scan, GameState s, Arena arena, int type,
                                         int fx, int fy, int fz, int dirX, int dirZ, int slopeFind) {
        int[] dist = slopeDistances(scan, s, arena, type, fx, fy, fz, slopeFind);
        int min = Integer.MAX_VALUE;
        int ours = Integer.MAX_VALUE;
        for (int i = 0; i < HORIZ.length; i++) {
            if (HORIZ[i][0] == dirX && HORIZ[i][1] == dirZ) {
                ours = dist[i];
            }
            if (dist[i] < min) {
                min = dist[i];
            }
        }
        return ours != Integer.MAX_VALUE && ours <= min;
    }

    private static int slopeDistance(FlowScan scan, GameState s, Arena arena, int type,
                                     int x, int y, int z,
                                     int fromX, int fromZ, int distance, int maxFind) {
        if (solidAt(scan, s, arena, x, y, z)) {
            return maxFind;
        }
        if (canFlowDownAt(scan, s, arena, x, y, z, type)) {
            return distance;
        }
        if (distance >= maxFind) {
            return maxFind;
        }
        int min = maxFind;
        for (int[] d : HORIZ) {
            if (d[0] == fromX && d[1] == fromZ) {
                continue;
            }
            int sub = slopeDistance(scan, s, arena, type, x + d[0], y, z + d[1],
                    -d[0], -d[1], distance + 1, maxFind);
            if (sub < min) {
                min = sub;
            }
        }
        return min;
    }

    private static boolean canFlowDownAt(FlowScan scan, GameState s, Arena arena,
                                         int x, int y, int z, int type) {
        long key = BlockStore.key(x, y, z);
        Boolean cached = scan.down.get(key);
        if (cached != null) {
            return cached;
        }
        boolean can;
        if (solidAt(scan, s, arena, x, y - 1, z)) {
            can = false;
        } else {
            Integer below = s.fluids.get(BlockStore.key(x, y - 1, z));
            can = below == null || type(below) == type;
        }
        scan.down.put(key, can);
        return can;
    }

    private static boolean solidAt(FlowScan scan, GameState s, Arena arena, int x, int y, int z) {
        long key = BlockStore.key(x, y, z);
        Boolean cached = scan.solid.get(key);
        if (cached != null) {
            return cached;
        }
        boolean solid = solidAt(s, arena, x, y, z);
        scan.solid.put(key, solid);
        return solid;
    }

    private static boolean solidAt(GameState s, Arena arena, int x, int y, int z) {
        if (s.blocks.contains(x, y, z)) {
            return true;
        }
        if (arena.staticFillsCell(x, y, z)) {
            return true;
        }
        long k = BlockStore.key(x, y, z);
        return !s.brokenArena.contains(k) && arena.isSolidVoxel(x, y, z);
    }

    private static double height(int v) {
        return amount(v) / 8.0;
    }

    public static double heightOf(int v) {
        return height(v);
    }

    public static Integer at(GameState s, int x, int y, int z) {
        return s.fluids.get(BlockStore.key(x, y, z));
    }

    public static double[] flowVector(GameState s, Arena arena, int type, int x, int y, int z) {
        Integer cur = s.fluids.get(BlockStore.key(x, y, z));
        if (cur == null || type(cur) != type) {
            return null;
        }
        double h = height(cur);
        double fx = 0.0;
        double fz = 0.0;
        for (int[] d : HORIZ) {
            int nx = x + d[0];
            int nz = z + d[1];
            Integer nv = s.fluids.get(BlockStore.key(nx, y, nz));
            double nh;
            if (nv != null && type(nv) == type) {
                nh = height(nv);
            } else if (solidAt(s, arena, nx, y, nz)) {
                continue;
            } else {
                nh = 0.0;
            }
            double diff = h - nh;
            fx += d[0] * diff;
            fz += d[1] * diff;
        }
        return new double[]{fx, fz};
    }

    public static boolean rangeHas(GameState s, int type, int x0, int y0, int z0, int x1, int y1, int z1) {
        if (s.fluids.isEmpty()) {
            return false;
        }
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Integer v = s.fluids.get(BlockStore.key(x, y, z));
                    if (v != null && type(v) == type) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static double maxWaterHeightInRange(GameState s, int x0, int y0, int z0, int x1, int y1, int z1) {
        return maxHeightInRange(s, WATER, x0, y0, z0, x1, y1, z1);
    }

    public static double maxHeightInRange(GameState s, int type, int x0, int y0, int z0, int x1, int y1, int z1) {
        if (s.fluids.isEmpty()) {
            return 0.0;
        }
        double max = 0.0;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    Integer v = s.fluids.get(BlockStore.key(x, y, z));
                    if (v != null && type(v) == type) {
                        double h = height(v);
                        if (h > max) {
                            max = h;
                        }
                    }
                }
            }
        }
        return max;
    }
}
