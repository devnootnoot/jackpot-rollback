package me.nootnoot.edge;

import me.nootnoot.sim.state.PlayerState;

public final class EdgePeerSmoother {

    public static final double SNAP_DIST_SQ = 64.0;
    public static final double DESYNC_SNAP_DIST = 1.5;
    public static final double CORRECTION_DEADZONE = 0.03;
    public static final double CORRECTION_FLOOR = 0.02;
    public static final double CORRECTION_SPEED_FRAC = 0.35;
    public static final double CORRECTION_ERROR_FRAC = 0.15;
    public static final double CORRECTION_MASK_MAX = 0.30;
    public static final double CORRECTION_FAST_EASE = 0.5;
    public static final double MAX_DT_TICKS = 3.0;
    public static final double NANOS_PER_TICK = 5.0e7;

    private double x;
    private double y;
    private double z;
    private long lastNanos;
    private boolean started;

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public boolean started() {
        return started;
    }

    public void reset() {
        started = false;
    }

    public void follow(PlayerState other, boolean snap, long nowNanos) {
        double tx = other.x;
        double ty = other.y;
        double tz = other.z;
        if (!started || snap) {
            snapTo(tx, ty, tz, nowNanos);
            return;
        }
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        if (dx * dx + dy * dy + dz * dz > SNAP_DIST_SQ) {
            snapTo(tx, ty, tz, nowNanos);
            return;
        }
        double dtTicks = (nowNanos - lastNanos) / NANOS_PER_TICK;
        lastNanos = nowNanos;
        dtTicks = Math.max(0.0, Math.min(MAX_DT_TICKS, dtTicks));

        double stepX = other.vx * dtTicks;
        double stepY = other.vy * dtTicks;
        double stepZ = other.vz * dtTicks;
        x += stepX;
        y += stepY;
        z += stepZ;

        double errX = dx - stepX;
        double errY = dy - stepY;
        double errZ = dz - stepZ;
        double err = Math.sqrt(errX * errX + errY * errY + errZ * errZ);
        if (err > DESYNC_SNAP_DIST) {
            x = tx;
            y = ty;
            z = tz;
        } else if (err > CORRECTION_DEADZONE) {
            double speed = Math.sqrt(other.vx * other.vx + other.vy * other.vy + other.vz * other.vz);
            double budget = (CORRECTION_FLOOR
                    + CORRECTION_SPEED_FRAC * speed
                    + CORRECTION_ERROR_FRAC * err) * dtTicks;
            double take = Math.min(1.0, budget / err);
            double over = clamp((err - CORRECTION_MASK_MAX) / CORRECTION_MASK_MAX, 0.0, 1.0);
            if (over > 0.0) {
                take = Math.max(take, 1.0 - StrictMath.pow(1.0 - CORRECTION_FAST_EASE * over, dtTicks));
            }
            x += errX * take;
            y += errY * take;
            z += errZ * take;
        }
        if (other.onGround && y < ty) {
            y = ty;
        }
    }

    private void snapTo(double tx, double ty, double tz, long nowNanos) {
        x = tx;
        y = ty;
        z = tz;
        lastNanos = nowNanos;
        started = true;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
