package me.nootnoot.sim;

import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.state.Arena;

public final class ArenaHash {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private ArenaHash() {
    }

    public static long of(Arena a) {
        long h = FNV_OFFSET;
        h = mixDouble(h, a.groundY);
        h = mixInt(h, a.hasVoxelGrid() ? 1 : 0);
        h = mixInt(h, a.baseX());
        h = mixInt(h, a.baseY());
        h = mixInt(h, a.baseZ());
        h = mixInt(h, a.sizeX());
        h = mixInt(h, a.sizeY());
        h = mixInt(h, a.sizeZ());

        int cells = a.voxelCellCount();
        h = mixInt(h, cells);
        long word = 0L;
        int bit = 0;
        for (int i = 0; i < cells; i++) {
            if (a.voxelAtIndex(i)) {
                word |= 1L << bit;
            }
            bit++;
            if (bit == 64) {
                h = mixLong(h, word);
                word = 0L;
                bit = 0;
            }
        }
        if (bit != 0) {
            h = mixLong(h, word);
        }

        Aabb[] boxes = a.partialBoxes();
        h = mixInt(h, boxes.length);
        for (Aabb b : boxes) {
            h = mixDouble(h, b.minX);
            h = mixDouble(h, b.minY);
            h = mixDouble(h, b.minZ);
            h = mixDouble(h, b.maxX);
            h = mixDouble(h, b.maxY);
            h = mixDouble(h, b.maxZ);
        }

        long[] resKeys = a.sortedVoxelResistanceKeys();
        h = mixInt(h, resKeys.length);
        for (long k : resKeys) {
            h = mixLong(h, k);
            h = mixFloat(h, a.voxelResistanceAtKey(k));
        }

        long[] dropKeys = a.sortedVoxelDropKeys();
        h = mixInt(h, dropKeys.length);
        for (long k : dropKeys) {
            h = mixLong(h, k);
            h = mixInt(h, a.voxelDropAtKey(k));
        }

        long[] decorKeys = a.sortedDecorVoxelKeys();
        h = mixInt(h, decorKeys.length);
        for (long k : decorKeys) {
            h = mixLong(h, k);
        }
        return h;
    }

    private static long mixDouble(long h, double v) {
        return mixLong(h, Double.doubleToRawLongBits(v));
    }

    private static long mixFloat(long h, float v) {
        return mixInt(h, Float.floatToRawIntBits(v));
    }

    private static long mixInt(long h, int v) {
        return mixLong(h, v & 0xFFFFFFFFL);
    }

    private static long mixLong(long h, long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v & 0xFF);
            h *= FNV_PRIME;
            v >>>= 8;
        }
        return h;
    }
}
