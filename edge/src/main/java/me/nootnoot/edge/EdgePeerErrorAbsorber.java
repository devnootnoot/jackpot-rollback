package me.nootnoot.edge;

import me.nootnoot.sim.state.PlayerState;

public final class EdgePeerErrorAbsorber {

    public static final double DECAY = 0.6;
    public static final double MAX_ERROR = 0.5;

    public static final double PASS_THROUGH = 0.75;
    public static final double SNAP_ERROR = 6.0;

    public static final double PACE_CATCHUP = 1.15;

    public static final double PACE_FLOOR = 0.02;

    public static final double PACE_SNAP = 3.0;

    private boolean started;
    private double errX;
    private double errY;
    private double errZ;
    private double x;
    private double y;
    private double z;

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double error() {
        return Math.sqrt(errX * errX + errY * errY + errZ * errZ);
    }

    public void reset() {
        started = false;
        errX = 0.0;
        errY = 0.0;
        errZ = 0.0;
    }

    public void correction(double dx, double dy, double dz) {
        if (dx * dx + dy * dy + dz * dz > PASS_THROUGH * PASS_THROUGH) {
            return;
        }
        errX += dx;
        errY += dy;
        errZ += dz;
    }

    public void follow(PlayerState p, boolean snap) {
        if (!started || snap) {
            started = true;
            errX = 0.0;
            errY = 0.0;
            errZ = 0.0;
            x = p.x;
            y = p.y;
            z = p.z;
            return;
        }
        double mag = Math.sqrt(errX * errX + errY * errY + errZ * errZ);
        if (mag > SNAP_ERROR) {
            errX = 0.0;
            errY = 0.0;
            errZ = 0.0;
        } else if (mag > MAX_ERROR) {
            double scale = MAX_ERROR / mag;
            errX *= scale;
            errY *= scale;
            errZ *= scale;
        }

        double targetX = p.x - errX;
        double targetY = p.y - errY;
        double targetZ = p.z - errZ;

        double gx = targetX - x;
        double gy = targetY - y;
        double gz = targetZ - z;
        double gap = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double speed = Math.sqrt(p.vx * p.vx + p.vy * p.vy + p.vz * p.vz);
        double allowed = speed * PACE_CATCHUP + PACE_FLOOR;
        if (gap > PACE_SNAP || gap <= allowed || gap <= 0.0) {
            x = targetX;
            y = targetY;
            z = targetZ;
        } else {
            double scale = allowed / gap;
            x += gx * scale;
            y += gy * scale;
            z += gz * scale;
        }

        errX *= DECAY;
        errY *= DECAY;
        errZ *= DECAY;
    }

}
