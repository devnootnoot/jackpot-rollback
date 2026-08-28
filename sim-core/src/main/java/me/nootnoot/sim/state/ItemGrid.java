package me.nootnoot.sim.state;

import java.util.Arrays;
import java.util.List;

public final class ItemGrid {
    public static final double CELL_XZ = 2.5;

    private static final int MIN_TABLE = 16;

    static final int MAX_TABLE = 2048;

    private long[] cellKey = new long[0];
    private int[] cellHead = new int[0];
    private int[] nextIndex = new int[0];
    private int mask;

    private boolean dirty = true;
    private int builtSize = -1;

    public void markDirty() {
        dirty = true;
    }

    public void insert(List<ItemEntityState> items, int index) {
        if (dirty || builtSize != index || index + 1 > cellKey.length / 2) {
            dirty = true;
            return;
        }
        if (index >= nextIndex.length) {
            nextIndex = Arrays.copyOf(nextIndex, Math.max(MIN_TABLE, nextIndex.length * 2));
        }
        put(index, items.get(index));
        builtSize = index + 1;
    }

    public boolean occupiedCell(List<ItemEntityState> items, int x, int y, int z) {
        ensure(items);
        int cx0 = cellOf(x);
        int cx1 = cellOf(x + 1.0);
        int cz0 = cellOf(z);
        int cz1 = cellOf(z + 1.0);
        for (int bx = cx0; bx <= cx1; bx++) {
            for (int bz = cz0; bz <= cz1; bz++) {
                int slot = find(key(bx, bz));
                if (slot < 0) {
                    continue;
                }
                for (int i = cellHead[slot]; i >= 0; i = nextIndex[i]) {
                    ItemEntityState e = items.get(i);
                    if (e.dead) {
                        continue;
                    }
                    if (e.x >= x && e.x < x + 1.0
                            && e.y >= y && e.y < y + 1.0
                            && e.z >= z && e.z < z + 1.0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public int collectNear(List<ItemEntityState> items, double x, double z, int[] out) {
        ensure(items);
        int cx = cellOf(x);
        int cz = cellOf(z);
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int slot = find(key(cx + dx, cz + dz));
                if (slot < 0) {
                    continue;
                }
                for (int i = cellHead[slot]; i >= 0 && n < out.length; i = nextIndex[i]) {
                    out[n++] = i;
                }
            }
        }
        return n;
    }

    private void ensure(List<ItemEntityState> items) {
        if (dirty || builtSize != items.size()) {
            rebuild(items);
        }
    }

    private void rebuild(List<ItemEntityState> items) {
        int n = items.size();
        int want = MIN_TABLE;
        while (want < n * 4 && want < MAX_TABLE) {
            want <<= 1;
        }
        if (want > cellKey.length) {
            cellKey = new long[want];
            cellHead = new int[want];
        }
        mask = cellHead.length - 1;
        Arrays.fill(cellHead, -1);
        if (nextIndex.length < n) {
            nextIndex = new int[Math.max(MIN_TABLE, n * 2)];
        }
        for (int i = 0; i < n; i++) {
            put(i, items.get(i));
        }
        builtSize = n;
        dirty = false;
    }

    private void put(int index, ItemEntityState e) {
        long k = key(cellOf(e.x), cellOf(e.z));
        int slot = hash(k) & mask;
        for (int probes = cellHead.length; cellHead[slot] >= 0 && cellKey[slot] != k; probes--) {
            if (probes <= 0) {
                return;
            }
            slot = (slot + 1) & mask;
        }
        cellKey[slot] = k;
        nextIndex[index] = cellHead[slot];
        cellHead[slot] = index;
    }

    private int find(long k) {
        int slot = hash(k) & mask;
        for (int probes = cellHead.length; cellHead[slot] >= 0; probes--) {
            if (cellKey[slot] == k) {
                return slot;
            }
            if (probes <= 0) {
                return -1;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    static int cellOf(double v) {
        return (int) Math.floor(v / CELL_XZ);
    }

    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int hash(long k) {
        long h = k * 0xff51afd7ed558ccdL;
        h ^= h >>> 29;
        return (int) (h ^ (h >>> 32));
    }
}
