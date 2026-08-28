package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class EdgeMovementValidatorTest {

    private static final long TICK = EdgeMovementValidator.TICK_NANOS;

    private final EdgeMovementValidator validator =
            new EdgeMovementValidator(EdgeMovementValidator.Limits.DEFAULTS);
    private final PlayerState head = new PlayerState();

    private long now;
    private double reportedX;
    private double reportedY;
    private double reportedZ;
    private double acceptedX;
    private double acceptedY;
    private double acceptedZ;
    private double lastDx;
    private double lastDy;
    private double lastDz;

    @Test
    void legitimateSprintJumpIsNeverClamped() {
        seed();
        double[] rise = {0.42, 0.3332, 0.24814, 0.16478, 0.08308, 0.00142,
                -0.07701, -0.15347, -0.22800, -0.30064, -0.37143, -0.44040};
        double[] run = {0.50104, 0.47555, 0.45235, 0.43124, 0.41202, 0.39454,
                0.37863, 0.36416, 0.35098, 0.33899, 0.32808, 0.31816};
        for (int cycle = 0; cycle < 20; cycle++) {
            for (int t = 0; t < rise.length; t++) {
                head.onGround = t == 0;
                head.jumpCooldown = t == 0 ? 0 : Math.max(0, 10 - t);
                EdgeMovementValidator.Verdict v = step(run[t], rise[t], 0.0);
                assertFalse(v.clamped(), "clamped legitimate sprint-jump cycle " + cycle + " tick " + t);
                assertFalse(v.correction(), "corrected legitimate sprint-jump cycle " + cycle + " tick " + t);
            }
        }
        assertEquals(0, validator.violations());
    }

    @Test
    void fiftyBlockTeleportIsClamped() {
        seed();
        head.onGround = true;
        EdgeMovementValidator.Verdict v = step(50.0, 0.0, 0.0);
        assertTrue(v.clamped(), "a 50 block jump must clamp");
        assertTrue(v.correction(), "a 50 block jump must ask for a position correction");
        assertEquals(1, validator.violations());
        assertTrue(v.x() > 0.4 && v.x() < 1.0, "clamped toward the report but not near it: " + v.x());
    }

    @Test
    void sustainedDoubleSpeedIsClamped() {
        seed();
        head.onGround = true;
        double reported = 0.0;
        double accepted = 0.0;
        for (int t = 0; t < 40; t++) {
            double before = acceptedX;
            step(0.79, 0.0, 0.0);
            reported += 0.79;
            accepted += acceptedX - before;
        }
        assertTrue(validator.violations() > 5, "sustained 2x speed must trip violations");
        assertTrue(accepted < reported * 0.8,
                "sustained 2x speed must be held back: accepted=" + accepted + " reported=" + reported);
    }

    @Test
    void knockbackIsNotFlagged() {
        seed();
        head.onGround = false;
        head.hurtTime = 10;
        double speed = 1.6;
        double lift = 0.4;
        for (int t = 0; t < 10; t++) {
            EdgeMovementValidator.Verdict v = stepWithVelocity(speed, lift, 0.0, speed, lift, 0.0);
            assertFalse(v.clamped(), "clamped a knockback tick " + t);
            speed *= EdgeMovementValidator.AIR_FRICTION;
            lift = (lift - EdgeMovementValidator.GRAVITY) * EdgeMovementValidator.VERTICAL_DRAG;
            head.hurtTime--;
        }
        assertEquals(0, validator.violations());
    }

    @Test
    void exemptedTeleportIsAccepted() {
        seed();
        validator.markServerTeleport();
        EdgeMovementValidator.Verdict v = step(50.0, 0.0, 0.0);
        assertFalse(v.clamped(), "a marked server teleport must pass through");
        assertEquals(50.0, v.x(), 1.0E-9);
        assertEquals(0, validator.violations());
    }

    @Test
    void disabledValidatorPublishesTheReport() {
        EdgeMovementValidator off = new EdgeMovementValidator(new EdgeMovementValidator.Limits(
                false, 3.0, 3.0, 5.0, 0.32, 1.0, 0.75, 6, 20));
        off.validate(head, false, 0.0, 64.0, 0.0, TICK);
        EdgeMovementValidator.Verdict v = off.validate(head, false, 900.0, 64.0, 0.0, TICK * 2);
        assertEquals(900.0, v.x(), 1.0E-9);
        assertFalse(v.clamped());
    }

    private void seed() {
        head.onGround = true;
        head.health = 20.0f;
        head.maxHealth = 20.0f;
        step(0.0, 0.0, 0.0);
        step(0.0, 0.0, 0.0);
    }

    private EdgeMovementValidator.Verdict step(double dx, double dy, double dz) {
        return stepWithVelocity(lastDx, lastDy, lastDz, dx, dy, dz);
    }

    private EdgeMovementValidator.Verdict stepWithVelocity(double vx, double vy, double vz,
                                                           double dx, double dy, double dz) {
        head.vx = vx;
        head.vy = vy;
        head.vz = vz;
        reportedX += dx;
        reportedY += dy;
        reportedZ += dz;
        now += TICK;
        EdgeMovementValidator.Verdict v =
                validator.validate(head, false, reportedX, reportedY, reportedZ, now);
        lastDx = v.x() - acceptedX;
        lastDy = v.y() - acceptedY;
        lastDz = v.z() - acceptedZ;
        acceptedX = v.x();
        acceptedY = v.y();
        acceptedZ = v.z();
        return v;
    }
}
