package me.nootnoot.sim.state;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import me.nootnoot.sim.SimProbe;
import me.nootnoot.sim.math.Aabb;

public final class BlockStore {
    private static final int BITS = 21;
    private static final long MASK = (1L << BITS) - 1;

    private final HashMap<Long, Integer> blocks;
    private Aabb[] solidsCache;
    private boolean dirty = true;

    public BlockStore() {
        this.blocks = new HashMap<>();
    }

    private BlockStore(HashMap<Long, Integer> blocks, Aabb[] solidsCache, boolean dirty) {
        this.blocks = blocks;
        this.solidsCache = solidsCache;
        this.dirty = dirty;
    }

    public static long key(int x, int y, int z) {
        return ((x & MASK) << (BITS * 2)) | ((y & MASK) << BITS) | (z & MASK);
    }

    public static int unpackX(long k) {
        return signExtend((int) ((k >> (BITS * 2)) & MASK));
    }

    public static int unpackY(long k) {
        return signExtend((int) ((k >> BITS) & MASK));
    }

    public static int unpackZ(long k) {
        return signExtend((int) (k & MASK));
    }

    private static int signExtend(int v) {
        return (v << (32 - BITS)) >> (32 - BITS);
    }

    public void place(int x, int y, int z, int blockId) {
        SimProbe.hit(SimProbe.PLACED_BLOCK_ADDED);
        blocks.put(key(x, y, z), blockId);
        dirty = true;
    }

    public int removeAt(int x, int y, int z) {
        Integer prev = blocks.remove(key(x, y, z));
        if (prev != null) {
            SimProbe.hit(SimProbe.PLACED_BLOCK_REMOVED);
            dirty = true;
            return prev;
        }
        return 0;
    }

    public int idAt(int x, int y, int z) {
        Integer v = blocks.get(key(x, y, z));
        return v != null ? v : 0;
    }

    public boolean contains(int x, int y, int z) {
        return blocks.containsKey(key(x, y, z));
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int size() {
        return blocks.size();
    }

    public void clear() {
        if (!blocks.isEmpty()) {
            blocks.clear();
            dirty = true;
        }
    }

    public Aabb[] solids() {
        if (dirty || solidsCache == null) {
            long[] keys = sortedKeys();
            Aabb[] s = new Aabb[keys.length];
            for (int i = 0; i < keys.length; i++) {
                int x = unpackX(keys[i]);
                int y = unpackY(keys[i]);
                int z = unpackZ(keys[i]);
                s[i] = new Aabb(x, y, z, x + 1.0, y + 1.0, z + 1.0);
            }
            solidsCache = s;
            dirty = false;
        }
        return solidsCache;
    }

    public BlockStore copy() {
        return new BlockStore(new HashMap<>(blocks), solidsCache, dirty);
    }

    public long[] sortedKeys() {
        long[] keys = new long[blocks.size()];
        int i = 0;
        for (long k : blocks.keySet()) {
            keys[i++] = k;
        }
        Arrays.sort(keys);
        return keys;
    }

    public int idAtKey(long key) {
        Integer v = blocks.get(key);
        return v != null ? v : 0;
    }
}
