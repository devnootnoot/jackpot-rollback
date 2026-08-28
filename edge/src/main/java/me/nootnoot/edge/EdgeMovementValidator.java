package me.nootnoot.edge;

import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.PlayerState;

public final class EdgeMovementValidator {

    public static final double SPRINT_JUMP_PEAK_PER_TICK = 0.4838;
    public static final double SPRINT_JUMP_SUSTAINED_PER_TICK = 0.3955;
    public static final double MIN_BURST_CREDIT_BLOCKS = 2.0;
    public static final double MIN_CORRECTION_DIVERGENCE = 1.0;
    public static final double GROUND_ACCEL_PER_TICK = 0.13;
    public static final double SPRINT_JUMP_BOOST = 0.2;
    public static final double SPEED_SCALE_PER_LEVEL = 0.2;
    public static final double ELYTRA_GLIDE_PER_TICK = 1.6663;

    public static final double STEP_UP_PER_TICK = 0.6;
    public static final double STEP_UP_MIN_HORIZONTAL = 0.05;
    public static final double JUMP_UP_PER_TICK = 0.42;
    public static final double JUMP_BOOST_PER_LEVEL = 0.1;
    public static final double VERT_UP_LOCOMOTION_MAX = 0.62;
    public static final double TERMINAL_FALL_PER_TICK = 3.92;

    public static final double AIR_FRICTION = 0.91;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double GRAVITY = 0.08;

    public static final double JITTER_TOLERANCE = 0.03;
    public static final double CREDIT_REFILL_PER_TICK = 0.25;
    public static final double CLIMB_SUSTAINED_PER_TICK = 0.25;
    public static final double CLIMB_CREDIT_BLOCKS = 2.0;
    public static final double VIOLATION_EPSILON = 0.05;

    public static final int TELEPORT_GRACE_TICKS = 20;
    public static final int LAG_TICK_CLAMP = 4;
    public static final long TICK_NANOS = 50_000_000L;

    public record Limits(boolean enabled,
                         double perTickCeiling,
                         double verticalUpCeiling,
                         double verticalDownCeiling,
                         double sustainedPerTick,
                         double burstCreditBlocks,
                         double correctionDivergence,
                         int correctionHoldTicks,
                         int violationThreshold) {

        public static final Limits DEFAULTS =
                new Limits(true, 3.0, 3.0, 5.0, 0.45, 4.0, 2.0, 6, 20);

        public Limits normalized() {
            return new Limits(enabled,
                    atLeast(perTickCeiling, SPRINT_JUMP_PEAK_PER_TICK),
                    atLeast(verticalUpCeiling, VERT_UP_LOCOMOTION_MAX),
                    atLeast(verticalDownCeiling, TERMINAL_FALL_PER_TICK),
                    atLeast(sustainedPerTick, SPRINT_JUMP_SUSTAINED_PER_TICK + JITTER_TOLERANCE),
                    atLeast(burstCreditBlocks, MIN_BURST_CREDIT_BLOCKS),
                    atLeast(correctionDivergence, MIN_CORRECTION_DIVERGENCE),
                    Math.max(0, correctionHoldTicks),
                    Math.max(1, violationThreshold));
        }

        private static double atLeast(double value, double floor) {
            return Double.isFinite(value) && value > floor ? value : floor;
        }
    }

    public record Verdict(double x, double y, double z, boolean clamped, boolean correction,
                          boolean warn, int violations) {
    }

    private final Limits limits;

    private boolean seeded;
    private boolean timed;
    private long lastSampleNanos;

    private double acceptedX;
    private double acceptedY;
    private double acceptedZ;

    private double envelopeH;
    private double envelopeUp;
    private double recentEnvelopeH1;
    private double recentEnvelopeH2;
    private double recentEnvelopeUp1;
    private double recentEnvelopeUp2;
    private double impulseH;
    private double impulseUp;
    private int impulseSeq;

    private double credit;
    private double climbCredit;

    private int exemptTicks;
    private int holdTicks;
    private int violations;

    public EdgeMovementValidator(Limits limits) {
        this.limits = limits == null ? Limits.DEFAULTS : limits.normalized();
    }

    public Limits limits() {
        return limits;
    }

    public int violations() {
        return violations;
    }

    public double acceptedX() {
        return acceptedX;
    }

    public double acceptedY() {
        return acceptedY;
    }

    public double acceptedZ() {
        return acceptedZ;
    }

    public void markServerTeleport() {
        exemptTicks = TELEPORT_GRACE_TICKS;
        holdTicks = 0;
        seeded = false;
    }

    public Verdict validate(PlayerState head, boolean roundLocked, double reportedX, double reportedY,
                            double reportedZ, long nowNanos) {
        int ticks = ticksElapsed(nowNanos);
        if (!finite(reportedX) || !finite(reportedY) || !finite(reportedZ)) {
            return seeded
                    ? new Verdict(acceptedX, acceptedY, acceptedZ, true, false, false, violations)
                    : new Verdict(reportedX, reportedY, reportedZ, false, false, false, violations);
        }
        if (!limits.enabled() || head == null || !seeded || roundLocked || head.dead || exemptTicks > 0) {
            if (exemptTicks > 0) {
                exemptTicks--;
            }
            return reseed(reportedX, reportedY, reportedZ);
        }
        if (holdTicks > 0) {
            holdTicks--;
            pushEnvelopeHistory();
            return new Verdict(acceptedX, acceptedY, acceptedZ, true, false, false, violations);
        }

        int speedLevel = head.effectTicks[Effects.SPEED] > 0 ? head.effectAmp[Effects.SPEED] + 1 : 0;
        double effectScale = 1.0 + SPEED_SCALE_PER_LEVEL * speedLevel;
        boolean glide = head.gliding || head.fireworkTicks > 0;

        double dx = reportedX - acceptedX;
        double dy = reportedY - acceptedY;
        double dz = reportedZ - acceptedZ;
        double reportedH = Math.sqrt(dx * dx + dz * dz);

        double simH = Math.sqrt(head.vx * head.vx + head.vz * head.vz);
        double horizontalReference = maxRecentEnvelopeH() + GROUND_ACCEL_PER_TICK * effectScale
                + SPRINT_JUMP_BOOST + JITTER_TOLERANCE;
        impulseH *= AIR_FRICTION;
        if (simH > horizontalReference) {
            impulseH = Math.max(impulseH, Math.min(simH, limits.perTickCeiling()));
        }

        double verticalReference = maxRecentEnvelopeUp() + JITTER_TOLERANCE;
        impulseUp = Math.max(0.0, (impulseUp - GRAVITY) * VERTICAL_DRAG);
        if (head.vy > verticalReference) {
            impulseUp = Math.max(impulseUp, Math.min(head.vy, limits.verticalUpCeiling()));
        }

        if (head.impulseSeq != impulseSeq) {
            impulseSeq = head.impulseSeq;
            double push = Math.sqrt(head.impulseVx * head.impulseVx
                    + head.impulseVz * head.impulseVz);
            impulseH = Math.max(impulseH, Math.min(push, limits.perTickCeiling()));
            impulseUp = Math.max(impulseUp,
                    Math.min(head.impulseVy, limits.verticalUpCeiling()));
            credit = limits.burstCreditBlocks();
            climbCredit = CLIMB_CREDIT_BLOCKS;
        }

        double locomotionH = glide ? ELYTRA_GLIDE_PER_TICK : SPRINT_JUMP_PEAK_PER_TICK * effectScale;
        double carriedH = maxRecentEnvelopeH() * AIR_FRICTION;
        double previousEnvelopeH = envelopeH;
        double previousEnvelopeUp = envelopeUp;
        envelopeH = Math.max(locomotionH, Math.max(impulseH, carriedH));

        double jumpAllowance = head.onGround && head.jumpCooldown == 0 ? SPRINT_JUMP_BOOST : 0.0;
        double allowanceH = (envelopeH + GROUND_ACCEL_PER_TICK * effectScale + jumpAllowance
                + JITTER_TOLERANCE) * ticks;
        double sustainedH = Math.max(glide ? ELYTRA_GLIDE_PER_TICK : limits.sustainedPerTick() * effectScale,
                impulseH);
        allowanceH = Math.min(allowanceH, sustainedH * ticks + credit);
        allowanceH = Math.min(allowanceH, limits.perTickCeiling() * ticks);

        double locomotionUp = 0.0;
        if (head.onGround) {
            if (reportedH > STEP_UP_MIN_HORIZONTAL) {
                locomotionUp = STEP_UP_PER_TICK;
            }
            if (head.jumpCooldown == 0) {
                locomotionUp = Math.max(locomotionUp, JUMP_UP_PER_TICK + jumpBoost(head));
            }
        }
        if (glide) {
            locomotionUp = Math.max(locomotionUp, ELYTRA_GLIDE_PER_TICK);
        }
        double carriedUp = Math.max(0.0, (maxRecentEnvelopeUp() - GRAVITY) * VERTICAL_DRAG);
        envelopeUp = Math.max(locomotionUp, Math.max(impulseUp, carriedUp));

        double allowanceUp = (envelopeUp + JITTER_TOLERANCE) * ticks;
        double sustainedUp = Math.max(CLIMB_SUSTAINED_PER_TICK, Math.max(impulseUp, glide
                ? ELYTRA_GLIDE_PER_TICK : 0.0));
        allowanceUp = Math.min(allowanceUp, sustainedUp * ticks + climbCredit);
        allowanceUp = Math.min(allowanceUp, limits.verticalUpCeiling() * ticks);

        double fall = Math.min(Math.max(TERMINAL_FALL_PER_TICK, -head.vy), limits.verticalDownCeiling());
        double allowanceDown = (fall + JITTER_TOLERANCE) * ticks;

        double overage = 0.0;
        if (reportedH > allowanceH) {
            overage = reportedH - allowanceH;
            double scale = allowanceH / reportedH;
            dx *= scale;
            dz *= scale;
        }
        if (dy > allowanceUp) {
            overage = Math.max(overage, dy - allowanceUp);
            dy = allowanceUp;
        } else if (dy < -allowanceDown) {
            overage = Math.max(overage, -allowanceDown - dy);
            dy = -allowanceDown;
        }

        double acceptedH = Math.sqrt(dx * dx + dz * dz);
        credit = clamp(credit + Math.min(sustainedH * ticks - acceptedH, CREDIT_REFILL_PER_TICK * ticks),
                0.0, limits.burstCreditBlocks());
        climbCredit = clamp(climbCredit + sustainedUp * ticks - Math.max(0.0, dy),
                0.0, CLIMB_CREDIT_BLOCKS);

        recentEnvelopeH2 = recentEnvelopeH1;
        recentEnvelopeH1 = previousEnvelopeH;
        recentEnvelopeUp2 = recentEnvelopeUp1;
        recentEnvelopeUp1 = previousEnvelopeUp;

        acceptedX += dx;
        acceptedY += dy;
        acceptedZ += dz;

        boolean clamped = overage > 0.0;
        boolean warn = false;
        if (overage > VIOLATION_EPSILON) {
            violations++;
            warn = violations % limits.violationThreshold() == 0;
        }

        boolean correction = false;
        double gap = distance(reportedX - acceptedX, reportedY - acceptedY, reportedZ - acceptedZ);
        if (overage > VIOLATION_EPSILON && gap > limits.correctionDivergence()) {
            correction = true;
            holdTicks = limits.correctionHoldTicks();
        }
        return new Verdict(acceptedX, acceptedY, acceptedZ, clamped, correction, warn, violations);
    }

    private Verdict reseed(double x, double y, double z) {
        seeded = true;
        holdTicks = 0;
        acceptedX = x;
        acceptedY = y;
        acceptedZ = z;
        envelopeH = SPRINT_JUMP_PEAK_PER_TICK;
        envelopeUp = VERT_UP_LOCOMOTION_MAX;
        recentEnvelopeH1 = envelopeH;
        recentEnvelopeH2 = envelopeH;
        recentEnvelopeUp1 = envelopeUp;
        recentEnvelopeUp2 = envelopeUp;
        impulseH = 0.0;
        impulseUp = 0.0;
        credit = limits.burstCreditBlocks();
        climbCredit = CLIMB_CREDIT_BLOCKS;
        return new Verdict(x, y, z, false, false, false, violations);
    }

    private void pushEnvelopeHistory() {
        recentEnvelopeH2 = recentEnvelopeH1;
        recentEnvelopeH1 = envelopeH;
        recentEnvelopeUp2 = recentEnvelopeUp1;
        recentEnvelopeUp1 = envelopeUp;
    }

    private double maxRecentEnvelopeH() {
        return Math.max(envelopeH, Math.max(recentEnvelopeH1, recentEnvelopeH2));
    }

    private double maxRecentEnvelopeUp() {
        return Math.max(envelopeUp, Math.max(recentEnvelopeUp1, recentEnvelopeUp2));
    }

    private int ticksElapsed(long nowNanos) {
        if (!timed) {
            timed = true;
            lastSampleNanos = nowNanos;
            return 1;
        }
        long elapsed = nowNanos - lastSampleNanos;
        lastSampleNanos = nowNanos;
        if (elapsed <= TICK_NANOS) {
            return 1;
        }
        long ticks = (elapsed + TICK_NANOS / 2) / TICK_NANOS;
        return (int) Math.min(LAG_TICK_CLAMP, Math.max(1L, ticks));
    }

    private static double jumpBoost(PlayerState head) {
        if (head.effectTicks[Effects.JUMP_BOOST] <= 0) {
            return 0.0;
        }
        return JUMP_BOOST_PER_LEVEL * (head.effectAmp[Effects.JUMP_BOOST] + 1);
    }

    private static double distance(double dx, double dy, double dz) {
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    private static boolean finite(double value) {
        return Double.isFinite(value);
    }
}
