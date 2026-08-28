package me.nootnoot.sim.math;

public final class Raycast {
    private Raycast() {
    }

    public static double segmentBox(Aabb box,
                                    double ax, double ay, double az,
                                    double dx, double dy, double dz) {
        double tmin = 0.0;
        double tmax = 1.0;

        if (dx != 0.0) {
            double t1 = (box.minX - ax) / dx;
            double t2 = (box.maxX - ax) / dx;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return -1.0;
            }
        } else if (ax < box.minX || ax > box.maxX) {
            return -1.0;
        }

        if (dy != 0.0) {
            double t1 = (box.minY - ay) / dy;
            double t2 = (box.maxY - ay) / dy;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return -1.0;
            }
        } else if (ay < box.minY || ay > box.maxY) {
            return -1.0;
        }

        if (dz != 0.0) {
            double t1 = (box.minZ - az) / dz;
            double t2 = (box.maxZ - az) / dz;
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return -1.0;
            }
        } else if (az < box.minZ || az > box.maxZ) {
            return -1.0;
        }

        return tmin;
    }
}
