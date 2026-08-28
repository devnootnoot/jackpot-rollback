package me.nootnoot.sim.math;

public final class Aabb {
    public final double minX;
    public final double minY;
    public final double minZ;
    public final double maxX;
    public final double maxY;
    public final double maxZ;

    public Aabb(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public static Aabb player(double x, double y, double z, double width, double height) {
        double hw = width / 2.0;
        return new Aabb(x - hw, y, z - hw, x + hw, y + height, z + hw);
    }

    public Aabb offset(double dx, double dy, double dz) {
        return new Aabb(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public Aabb inflate(double d) {
        return new Aabb(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
    }

    public Aabb inflate(double dx, double dy, double dz) {
        return new Aabb(minX - dx, minY - dy, minZ - dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    public boolean intersects(Aabb o) {
        return minX < o.maxX && maxX > o.minX
                && minY < o.maxY && maxY > o.minY
                && minZ < o.maxZ && maxZ > o.minZ;
    }

    public Aabb expandTowards(double dx, double dy, double dz) {
        return new Aabb(
                dx < 0 ? minX + dx : minX,
                dy < 0 ? minY + dy : minY,
                dz < 0 ? minZ + dz : minZ,
                dx > 0 ? maxX + dx : maxX,
                dy > 0 ? maxY + dy : maxY,
                dz > 0 ? maxZ + dz : maxZ);
    }
}
