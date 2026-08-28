package me.nootnoot.sim;

import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.math.Vec3;

public final class Collision {
    private Collision() {
    }

    private static final Aabb[] NONE = new Aabb[0];

    public static Vec3 collide(Aabb box, double dx, double dy, double dz,
                               boolean onGround, double stepHeight, Aabb[] solids) {
        return collide(box, dx, dy, dz, onGround, stepHeight, solids, NONE);
    }

    public static Vec3 collide(Aabb box, double dx, double dy, double dz,
                               boolean onGround, double stepHeight, Aabb[] solids, Aabb[] placed) {
        Vec3 vec = collideAxes(box, dx, dy, dz, solids, placed);

        boolean xBlocked = dx != vec.x();
        boolean yBlocked = dy != vec.y();
        boolean zBlocked = dz != vec.z();
        boolean onGroundNow = onGround || (yBlocked && dy < 0.0);

        if (stepHeight > 0.0 && onGroundNow && (xBlocked || zBlocked)) {
            Vec3 stepUp = collideAxes(box, dx, stepHeight, dz, solids, placed);

            Vec3 justStep = collideAxes(box.expandTowards(dx, 0.0, dz), 0.0, stepHeight, 0.0, solids, placed);
            if (justStep.y() < stepHeight) {
                Vec3 afterStep = collideAxes(
                        box.offset(justStep.x(), justStep.y(), justStep.z()), dx, 0.0, dz, solids, placed)
                        .add(justStep);
                if (afterStep.horizontalSq() > stepUp.horizontalSq()) {
                    stepUp = afterStep;
                }
            }

            if (stepUp.horizontalSq() > vec.horizontalSq()) {
                Vec3 settle = collideAxes(
                        box.offset(stepUp.x(), stepUp.y(), stepUp.z()),
                        0.0, -stepUp.y() + dy, 0.0, solids, placed);
                return stepUp.add(settle);
            }
        }
        return vec;
    }

    private static Vec3 collideAxes(Aabb box, double dx, double dy, double dz, Aabb[] solids, Aabb[] placed) {
        if (dy != 0.0) {
            dy = clampY(box, solids, placed, dy);
            if (dy != 0.0) {
                box = box.offset(0.0, dy, 0.0);
            }
        }
        boolean zFirst = Math.abs(dx) < Math.abs(dz);
        if (zFirst && dz != 0.0) {
            dz = clampZ(box, solids, placed, dz);
            if (dz != 0.0) {
                box = box.offset(0.0, 0.0, dz);
            }
        }
        if (dx != 0.0) {
            dx = clampX(box, solids, placed, dx);
            if (!zFirst && dx != 0.0) {
                box = box.offset(dx, 0.0, 0.0);
            }
        }
        if (!zFirst && dz != 0.0) {
            dz = clampZ(box, solids, placed, dz);
        }
        return new Vec3(dx, dy, dz);
    }

    private static double clampY(Aabb box, Aabb[] solids, Aabb[] placed, double dy) {
        dy = clampYIn(box, solids, dy);
        return clampYIn(box, placed, dy);
    }

    private static double clampYIn(Aabb box, Aabb[] solids, double dy) {
        for (Aabb b : solids) {
            if (box.maxX > b.minX && box.minX < b.maxX && box.maxZ > b.minZ && box.minZ < b.maxZ) {
                if (dy > 0.0 && box.maxY <= b.minY) {
                    dy = Math.min(dy, b.minY - box.maxY);
                } else if (dy < 0.0 && box.minY >= b.maxY) {
                    dy = Math.max(dy, b.maxY - box.minY);
                }
            }
        }
        return dy;
    }

    private static double clampX(Aabb box, Aabb[] solids, Aabb[] placed, double dx) {
        dx = clampXIn(box, solids, dx);
        return clampXIn(box, placed, dx);
    }

    private static double clampXIn(Aabb box, Aabb[] solids, double dx) {
        for (Aabb b : solids) {
            if (box.maxY > b.minY && box.minY < b.maxY && box.maxZ > b.minZ && box.minZ < b.maxZ) {
                if (dx > 0.0 && box.maxX <= b.minX) {
                    dx = Math.min(dx, b.minX - box.maxX);
                } else if (dx < 0.0 && box.minX >= b.maxX) {
                    dx = Math.max(dx, b.maxX - box.minX);
                }
            }
        }
        return dx;
    }

    private static double clampZ(Aabb box, Aabb[] solids, Aabb[] placed, double dz) {
        dz = clampZIn(box, solids, dz);
        return clampZIn(box, placed, dz);
    }

    private static double clampZIn(Aabb box, Aabb[] solids, double dz) {
        for (Aabb b : solids) {
            if (box.maxX > b.minX && box.minX < b.maxX && box.maxY > b.minY && box.minY < b.maxY) {
                if (dz > 0.0 && box.maxZ <= b.minZ) {
                    dz = Math.min(dz, b.minZ - box.maxZ);
                } else if (dz < 0.0 && box.minZ >= b.maxZ) {
                    dz = Math.max(dz, b.maxZ - box.minZ);
                }
            }
        }
        return dz;
    }
}
