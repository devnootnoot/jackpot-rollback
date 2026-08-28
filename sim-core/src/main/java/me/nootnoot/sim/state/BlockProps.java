package me.nootnoot.sim.state;

public final class BlockProps {
    public static final float DEFAULT_HARDNESS = 1.5f;
    public static final int MAX_ROWS = 4096;
    public static final int ROW_BYTES = 19;

    private final java.util.HashMap<Integer, int[]> ints;
    private final java.util.HashMap<Integer, float[]> floats;
    private final long digest;

    public static final class Builder {
        private final java.util.LinkedHashMap<Integer, int[]> ints = new java.util.LinkedHashMap<>();
        private final java.util.LinkedHashMap<Integer, float[]> floats = new java.util.LinkedHashMap<>();

        public Builder add(int blockItemId, float hardness, float blastResistance, int dropItemId,
                           int harvestTier, int toolClass, boolean requiresTool) {
            ints.put(blockItemId, new int[]{dropItemId, harvestTier, toolClass, requiresTool ? 1 : 0});
            floats.put(blockItemId, new float[]{hardness, blastResistance});
            return this;
        }

        public int size() {
            return ints.size();
        }

        public BlockProps build() {
            return new BlockProps(ints, floats);
        }
    }

    private BlockProps(java.util.Map<Integer, int[]> ints, java.util.Map<Integer, float[]> floats) {
        this.ints = new java.util.HashMap<>(ints);
        this.floats = new java.util.HashMap<>(floats);
        this.digest = computeDigest();
    }

    public static BlockProps empty() {
        return new Builder().build();
    }

    public int size() {
        return ints.size();
    }

    public boolean known(int blockItemId) {
        return floats.containsKey(blockItemId);
    }

    public int[] sortedKeys() {
        int[] keys = new int[ints.size()];
        int i = 0;
        for (int k : ints.keySet()) {
            keys[i++] = k;
        }
        java.util.Arrays.sort(keys);
        return keys;
    }

    public float hardness(int blockItemId) {
        float[] f = floats.get(blockItemId);
        return f == null ? DEFAULT_HARDNESS : f[0];
    }

    public float blastResistance(int blockItemId) {
        float[] f = floats.get(blockItemId);
        return f == null ? Arena.DEFAULT_VOXEL_RESISTANCE : f[1];
    }

    public int dropItemId(int blockItemId) {
        int[] a = ints.get(blockItemId);
        return a == null ? blockItemId : a[0];
    }

    public int harvestTier(int blockItemId) {
        int[] a = ints.get(blockItemId);
        return a == null ? -1 : a[1];
    }

    public int toolClass(int blockItemId) {
        int[] a = ints.get(blockItemId);
        return a == null ? ItemDict.TOOL_NONE : a[2];
    }

    public boolean requiresTool(int blockItemId) {
        int[] a = ints.get(blockItemId);
        return a != null && a[3] != 0;
    }

    public long digest() {
        return digest;
    }

    private long computeDigest() {
        long h = 0xcbf29ce484222325L;
        int[] keys = sortedKeys();
        h = mix(h, keys.length);
        for (int k : keys) {
            int[] a = ints.get(k);
            float[] f = floats.get(k);
            h = mix(h, k);
            h = mix(h, a[0]);
            h = mix(h, a[1]);
            h = mix(h, a[2]);
            h = mix(h, a[3]);
            h = mix(h, Float.floatToRawIntBits(f[0]));
            h = mix(h, Float.floatToRawIntBits(f[1]));
        }
        return h;
    }

    private static long mix(long h, int v) {
        long x = v & 0xFFFFFFFFL;
        for (int i = 0; i < 8; i++) {
            h ^= (x & 0xFF);
            h *= 0x100000001b3L;
            x >>>= 8;
        }
        return h;
    }
}
