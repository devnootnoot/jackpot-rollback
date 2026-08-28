package me.nootnoot.edge;

public final class EdgeLiveSnap {

    public static final double SNAP_BLOCKS = 1.25;

    private EdgeLiveSnap() {
    }

    public static boolean needsSnap(int authoritySuspendTicks, double gapBlocks) {
        return authoritySuspendTicks > 0 && gapBlocks >= SNAP_BLOCKS;
    }

    public static double distance(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
