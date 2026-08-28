package me.nootnoot.sim.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import me.nootnoot.sim.math.Aabb;

public final class Arena {
    private static final double GROUND_EXTENT = 30_000_000.0;

    private static final double CELL_EPSILON = 1.0e-9;

    public final double groundY;

    public final Aabb[] solids;

    private final Aabb groundSlab;

    private final Aabb[] partials;

    private final boolean[] voxels;
    private final int baseX;
    private final int baseY;
    private final int baseZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;

    public static final float DEFAULT_VOXEL_RESISTANCE = 6.0f;

    private final java.util.Map<Long, Float> voxelResistance;

    private final java.util.Map<Long, Integer> voxelDropItem;
    private final java.util.Map<Long, Integer> voxelBlockItem;
    private final java.util.Set<Long> decorVoxels;

    public Arena(double groundY, double[][] boxes) {
        this.groundY = groundY;
        this.groundSlab = new Aabb(-GROUND_EXTENT, groundY - 16.0, -GROUND_EXTENT,
                GROUND_EXTENT, groundY, GROUND_EXTENT);
        this.partials = toAabbs(boxes);
        this.voxels = null;
        this.baseX = this.baseY = this.baseZ = 0;
        this.sizeX = this.sizeY = this.sizeZ = 0;
        this.voxelResistance = java.util.Map.of();
        this.voxelDropItem = java.util.Map.of();
        this.voxelBlockItem = java.util.Map.of();
        this.decorVoxels = java.util.Set.of();
        this.solids = concat(groundSlab, partials, new Aabb[0]);
    }

    public Arena(double groundY, boolean[] grid, int baseX, int baseY, int baseZ,
                 int sizeX, int sizeY, int sizeZ, double[][] partialBoxes,
                 java.util.Map<Long, Float> voxelResistance) {
        this(groundY, grid, baseX, baseY, baseZ, sizeX, sizeY, sizeZ, partialBoxes, voxelResistance,
                java.util.Map.of(), java.util.Set.of());
    }

    public Arena(double groundY, boolean[] grid, int baseX, int baseY, int baseZ,
                 int sizeX, int sizeY, int sizeZ, double[][] partialBoxes,
                 java.util.Map<Long, Float> voxelResistance, java.util.Map<Long, Integer> voxelDropItem) {
        this(groundY, grid, baseX, baseY, baseZ, sizeX, sizeY, sizeZ, partialBoxes, voxelResistance,
                voxelDropItem, java.util.Set.of());
    }

    public Arena(double groundY, boolean[] grid, int baseX, int baseY, int baseZ,
                 int sizeX, int sizeY, int sizeZ, double[][] partialBoxes,
                 java.util.Map<Long, Float> voxelResistance, java.util.Map<Long, Integer> voxelDropItem,
                 java.util.Set<Long> decorVoxels) {
        this(groundY, grid, baseX, baseY, baseZ, sizeX, sizeY, sizeZ, partialBoxes, voxelResistance,
                voxelDropItem, decorVoxels, java.util.Map.of());
    }

    public Arena(double groundY, boolean[] grid, int baseX, int baseY, int baseZ,
                 int sizeX, int sizeY, int sizeZ, double[][] partialBoxes,
                 java.util.Map<Long, Float> voxelResistance, java.util.Map<Long, Integer> voxelDropItem,
                 java.util.Set<Long> decorVoxels, java.util.Map<Long, Integer> voxelBlockItem) {
        this.groundY = groundY;
        this.groundSlab = new Aabb(-GROUND_EXTENT, groundY - 16.0, -GROUND_EXTENT,
                GROUND_EXTENT, groundY, GROUND_EXTENT);
        this.partials = toAabbs(partialBoxes);
        this.voxels = grid;
        this.baseX = baseX;
        this.baseY = baseY;
        this.baseZ = baseZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.voxelResistance = voxelResistance != null ? voxelResistance : java.util.Map.of();
        this.voxelDropItem = voxelDropItem != null ? voxelDropItem : java.util.Map.of();
        this.voxelBlockItem = voxelBlockItem != null ? voxelBlockItem : java.util.Map.of();
        this.decorVoxels = decorVoxels != null ? decorVoxels : java.util.Set.of();
        this.solids = concat(groundSlab, partials, mergeGrid(grid, baseX, baseY, baseZ, sizeX, sizeY, sizeZ));
    }

    public float voxelResistance(int x, int y, int z) {
        Float r = voxelResistance.get(BlockStore.key(x, y, z));
        return r != null ? r : DEFAULT_VOXEL_RESISTANCE;
    }

    public int voxelDropItem(int x, int y, int z) {
        Integer v = voxelDropItem.get(BlockStore.key(x, y, z));
        return v != null ? v : 0;
    }

    public int voxelBlockItem(int x, int y, int z) {
        Integer v = voxelBlockItem.get(BlockStore.key(x, y, z));
        return v != null ? v : 0;
    }

    public int voxelBlockItemCount() {
        return voxelBlockItem.size();
    }

    public static Arena flat(double groundY) {
        return new Arena(groundY, new double[0][]);
    }

    public boolean hasVoxelGrid() {
        return voxels != null;
    }

    public int baseX() {
        return baseX;
    }

    public int baseY() {
        return baseY;
    }

    public int baseZ() {
        return baseZ;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int voxelCellCount() {
        return voxels != null ? voxels.length : 0;
    }

    public boolean voxelAtIndex(int index) {
        return voxels != null && voxels[index];
    }

    public Aabb[] partialBoxes() {
        return partials.clone();
    }

    public long[] sortedVoxelResistanceKeys() {
        return sortedKeys(voxelResistance.keySet());
    }

    public float voxelResistanceAtKey(long key) {
        Float r = voxelResistance.get(key);
        return r != null ? r : DEFAULT_VOXEL_RESISTANCE;
    }

    public long[] sortedVoxelDropKeys() {
        return sortedKeys(voxelDropItem.keySet());
    }

    public int voxelDropAtKey(long key) {
        Integer v = voxelDropItem.get(key);
        return v != null ? v : 0;
    }

    public long[] sortedDecorVoxelKeys() {
        return sortedKeys(decorVoxels);
    }

    private static long[] sortedKeys(Set<Long> keys) {
        long[] out = new long[keys.size()];
        int i = 0;
        for (long k : keys) {
            out[i++] = k;
        }
        Arrays.sort(out);
        return out;
    }

    public boolean isSolidVoxel(int x, int y, int z) {
        if (voxels == null) {
            return false;
        }
        int rx = x - baseX;
        int ry = y - baseY;
        int rz = z - baseZ;
        if (rx < 0 || ry < 0 || rz < 0 || rx >= sizeX || ry >= sizeY || rz >= sizeZ) {
            return false;
        }
        return voxels[(rz * sizeY + ry) * sizeX + rx];
    }

    public boolean isDecorVoxel(int x, int y, int z) {
        return !decorVoxels.isEmpty() && decorVoxels.contains(BlockStore.key(x, y, z));
    }

    public boolean staticFillsCell(int x, int y, int z) {
        if (fills(groundSlab, x, y, z)) {
            return true;
        }
        for (Aabb a : partials) {
            if (fills(a, x, y, z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean fills(Aabb a, int x, int y, int z) {
        return a.minX <= x && a.maxX >= x + 1.0
                && a.minY <= y && a.maxY >= y + 1.0
                && a.minZ <= z && a.maxZ >= z + 1.0;
    }

    public void collectNearSolids(List<Aabb> out, double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ, Set<Long> broken) {
        if (overlaps(groundSlab, minX, minY, minZ, maxX, maxY, maxZ)) {
            out.add(groundSlab);
        }
        for (Aabb a : partials) {
            if (overlaps(a, minX, minY, minZ, maxX, maxY, maxZ)) {
                out.add(a);
            }
        }
        if (voxels != null) {
            int x0 = gridLow(minX, baseX);
            int x1 = gridHigh(maxX, baseX, sizeX);
            int y0 = gridLow(minY, baseY);
            int y1 = gridHigh(maxY, baseY, sizeY);
            int z0 = gridLow(minZ, baseZ);
            int z1 = gridHigh(maxZ, baseZ, sizeZ);
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        if (isSolidVoxel(x, y, z)
                                && !(broken != null && broken.contains(BlockStore.key(x, y, z)))) {
                            out.add(new Aabb(x, y, z, x + 1.0, y + 1.0, z + 1.0));
                        }
                    }
                }
            }
        }
    }

    private static int gridLow(double v, int base) {
        if (Double.isNaN(v)) {
            return base;
        }
        double f = Math.floor(v);
        return f <= base ? base : (int) f;
    }

    private static int gridHigh(double v, int base, int size) {
        int last = base + size - 1;
        if (Double.isNaN(v)) {
            return base - 1;
        }
        double f = Math.floor(v);
        return f >= last ? last : (int) f;
    }

    public void collectNearOccluders(List<Aabb> out, double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ, Set<Long> broken) {
        int from = out.size();
        collectNearSolids(out, minX, minY, minZ, maxX, maxY, maxZ, broken);
        for (int i = from; i < out.size(); i++) {
            out.set(i, outlineOf(out.get(i)));
        }
    }

    public static Aabb outlineOf(Aabb collision) {
        double cellX = Math.floor(collision.minX);
        double cellY = Math.floor(collision.minY);
        double cellZ = Math.floor(collision.minZ);
        boolean oneCellFootprint = collision.maxX <= cellX + 1.0 + CELL_EPSILON
                && collision.maxZ <= cellZ + 1.0 + CELL_EPSILON;
        if (!oneCellFootprint || collision.maxY <= cellY + 1.0) {
            return collision;
        }
        return new Aabb(collision.minX, collision.minY, collision.minZ,
                collision.maxX, cellY + 1.0, collision.maxZ);
    }

    private static boolean overlaps(Aabb a, double minX, double minY, double minZ,
                                    double maxX, double maxY, double maxZ) {
        return a.maxX > minX && a.minX < maxX
                && a.maxY > minY && a.minY < maxY
                && a.maxZ > minZ && a.minZ < maxZ;
    }

    private static Aabb[] toAabbs(double[][] boxes) {
        Aabb[] a = new Aabb[boxes.length];
        for (int i = 0; i < boxes.length; i++) {
            double[] b = boxes[i];
            a[i] = new Aabb(b[0], b[1], b[2], b[3], b[4], b[5]);
        }
        return a;
    }

    private static Aabb[] concat(Aabb ground, Aabb[] a, Aabb[] b) {
        Aabb[] s = new Aabb[1 + a.length + b.length];
        s[0] = ground;
        System.arraycopy(a, 0, s, 1, a.length);
        System.arraycopy(b, 0, s, 1 + a.length, b.length);
        return s;
    }

    private static Aabb[] mergeGrid(boolean[] grid, int bx, int by, int bz, int sx, int sy, int sz) {
        if (grid == null) {
            return new Aabb[0];
        }
        List<Aabb> boxes = new ArrayList<>();
        for (int z = 0; z < sz; z++) {
            for (int y = 0; y < sy; y++) {
                int row = (z * sy + y) * sx;
                int x = 0;
                while (x < sx) {
                    if (!grid[row + x]) {
                        x++;
                        continue;
                    }
                    int end = x;
                    while (end + 1 < sx && grid[row + end + 1]) {
                        end++;
                    }
                    boxes.add(new Aabb(bx + x, by + y, bz + z, bx + end + 1, by + y + 1.0, bz + z + 1.0));
                    x = end + 1;
                }
            }
        }
        return boxes.toArray(new Aabb[0]);
    }
}
