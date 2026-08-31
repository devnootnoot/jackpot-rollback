package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class PeerErrorAbsorberAddsNoLagToSmoothMotionTest {

    private static final double GRAVITY = 0.08;

    private static PlayerState at(double x, double y, double vx, double vy) {
        PlayerState p = new PlayerState();
        p.x = x;
        p.y = y;
        p.z = 0.0;
        p.vx = vx;
        p.vy = vy;
        return p;
    }

    @Test
    void motionWithNoRollbackIsPassedThroughExactly() {
        EdgePeerErrorAbsorber a = new EdgePeerErrorAbsorber();
        double x = 0.0;
        a.follow(at(x, 64.0, 0.25, 0.0), false);
        for (int i = 0; i < 40; i++) {
            x += 0.25;
            a.follow(at(x, 64.0, 0.25, 0.0), false);
            assertEquals(x, a.x(), 1.0E-12, "no rollback means nothing to absorb");
        }
    }

    @Test
    void aBallisticJumpIsNotMistakenForError() {
        EdgePeerErrorAbsorber a = new EdgePeerErrorAbsorber();
        double y = 64.0;
        double vy = 0.42;
        a.follow(at(0.0, y, 0.0, vy), false);
        for (int i = 0; i < 20; i++) {
            vy = (vy - GRAVITY) * 0.98;
            y += vy;
            a.follow(at(0.0, y, 0.0, vy), false);
            assertEquals(y, a.y(), 1.0E-12,
                    "the earlier version predicted each step as position + PREVIOUS velocity, but"
                            + " the sim applies gravity BEFORE moving, so every accelerating tick"
                            + " left a residue of about -0.078 that was accumulated as if it were a"
                            + " rollback. Decayed at 0.6 that settled into roughly a fifth of a"
                            + " block of permanent upward bias - the jump that went far too high,"
                            + " and the landing that pushed the render into the ground. Corrections"
                            + " must be MEASURED from the rollback, never inferred from physics");
        }
    }

    @Test
    void aMeasuredRollbackIsHiddenThenBledOff() {
        EdgePeerErrorAbsorber a = new EdgePeerErrorAbsorber();
        a.follow(at(0.0, 64.0, 0.0, 0.0), false);
        a.correction(0.4, 0.0, 0.0);
        a.follow(at(0.4, 64.0, 0.0, 0.0), false);

        assertTrue(Math.abs(a.x()) < 0.01,
                "a rollback of this size is jitter, not travel; none of that jump may reach the"
                        + " screen on the tick it lands");

        for (int i = 0; i < 20; i++) {
            a.follow(at(0.4, 64.0, 0.0, 0.0), false);
        }
        assertEquals(0.4, a.x(), 0.01,
                "and it must converge, or the rendered opponent sits permanently offset from where"
                        + " hits are adjudicated");
    }

    @Test
    void aTeleportSnapsInsteadOfBeingAbsorbed() {
        EdgePeerErrorAbsorber a = new EdgePeerErrorAbsorber();
        a.follow(at(0.0, 64.0, 0.0, 0.0), false);
        a.follow(at(40.0, 64.0, 0.0, 0.0), true);
        assertEquals(40.0, a.x(), 1.0E-9,
                "a pearl or round reset is real motion, not error");
    }

    @Test
    void aLargeCorrectionIsNeverAbsorbed() {
        EdgePeerErrorAbsorber a = new EdgePeerErrorAbsorber();
        a.follow(at(0.0, 64.0, 0.0, 0.0), false);
        a.correction(0.0, 2.5, 0.0);
        a.follow(at(0.0, 61.5, 0.0, -2.5), false);

        assertEquals(61.5, a.y(), 1.0E-9,
                "the sim runs one tick behind the client by INPUT_DELAY_FRAMES, and at fall speed"
                        + " one tick is over two blocks - traced live at gap=2.553. Feeding that to"
                        + " the absorber let it clamp and HOLD the peer up to its limit above where"
                        + " they really were, which during a fall reads as hanging in the sky."
                        + " Only small jitter may ever be hidden");
    }

    @Test
    void theMostThatCanEverBeHiddenIsSmall() {
        EdgePeerErrorAbsorber a = new EdgePeerErrorAbsorber();
        a.follow(at(0.0, 64.0, 0.0, 0.0), false);
        for (int i = 0; i < 50; i++) {
            a.correction(0.0, -0.4, 0.0);
            a.follow(at(0.0, 64.0, 0.0, 0.0), false);
            assertTrue(Math.abs(a.y() - 64.0) <= EdgePeerErrorAbsorber.MAX_ERROR + 1.0E-9,
                    "repeated corrections must not be able to accumulate into a large offset");
        }
    }

    @Test
    void anAlternatingStallAndDoubleStepIsPacedIntoSteadyMotion() {
        EdgePeerErrorAbsorber a = new EdgePeerErrorAbsorber();
        double step = 0.25;
        double x = 0.0;
        a.follow(at(x, 64.0, step, 0.0), false);

        double prevShown = a.x();
        double worst = 0.0;
        double prevOut = 0.0;
        for (int i = 0; i < 30; i++) {
            if (i % 2 == 1) {
                x += step * 2.0;
            }
            a.follow(at(x, 64.0, step, 0.0), false);
            double out = a.x() - prevShown;
            if (i > 2) {
                worst = Math.max(worst, Math.abs(out - prevOut));
            }
            prevOut = out;
            prevShown = a.x();
        }

        assertTrue(worst < step,
                "traced live at 150ms: the sim advanced ZERO frames on one tick and two on the"
                        + " next, because time-sync stalls the leading side. Rendered raw that is"
                        + " move-freeze-move at 10Hz with double sized steps, which is the glitchy"
                        + " movement. The rendered peer has to be paced so it never takes two"
                        + " frames of travel in a single tick, and catches up during the stall");
    }

    @Test
    void pacingStillConvergesRatherThanTrailing() {
        EdgePeerErrorAbsorber a = new EdgePeerErrorAbsorber();
        a.follow(at(0.0, 64.0, 0.25, 0.0), false);
        double x = 0.0;
        for (int i = 0; i < 60; i++) {
            x += 0.25;
            a.follow(at(x, 64.0, 0.25, 0.0), false);
        }
        assertEquals(x, a.x(), 0.26,
                "pacing may delay the peer by at most about one frame of travel, never accumulate"
                        + " into a growing lag");
    }
}
