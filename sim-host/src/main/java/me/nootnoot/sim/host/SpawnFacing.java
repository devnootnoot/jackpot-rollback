package me.nootnoot.sim.host;

public final class SpawnFacing {

    private SpawnFacing() {
    }

    public static float yaw(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (dx == 0.0 && dz == 0.0) {
            return 0f;
        }
        return (float) (StrictMath.atan2(-dx, dz) * 180.0 / StrictMath.PI);
    }
}
