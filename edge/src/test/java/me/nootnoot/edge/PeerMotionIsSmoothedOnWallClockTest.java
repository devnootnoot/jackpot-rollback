package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class PeerMotionIsSmoothedOnWallClockTest {

    private static final long TICK = 50_000_000L;

    private static PlayerState peerAt(double x, double vx) {
        PlayerState p = new PlayerState();
        p.x = x;
        p.y = 64.0;
        p.z = 0.0;
        p.vx = vx;
        p.onGround = true;
        return p;
    }

    @Test
    void aBurstOfSimFramesInOneHostTickDoesNotPlayBackFast() {
        EdgePeerSmoother s = new EdgePeerSmoother();
        long t = 0L;
        s.follow(peerAt(0.0, 0.4), false, t);

        t += TICK;
        s.follow(peerAt(0.4, 0.4), false, t);
        double oneTick = s.x();

        EdgePeerSmoother burst = new EdgePeerSmoother();
        long bt = 0L;
        burst.follow(peerAt(0.0, 0.4), false, bt);
        bt += TICK;
        burst.follow(peerAt(1.2, 0.4), false, bt);

        assertTrue(burst.x() < 1.2 - 0.1,
                "NetSession can advance up to CATCHUP_BURST_MAX frames inside ONE host tick while"
                        + " MatchDriver renders exactly once, so a single packet would otherwise"
                        + " carry three ticks of travel and the client would play the opponent's"
                        + " knockback back at several times real speed. Easing on WALL CLOCK is"
                        + " what the mod does (McSimRenderer) and is why the mod looks right at"
                        + " 80ms while the edge did not. Past DESYNC_SNAP_DIST it still snaps,"
                        + " exactly as the mod does - this covers the easing band below it");
    }

    @Test
    void aStalledHostTickDoesNotFreezeThePeerMidAir() {
        EdgePeerSmoother s = new EdgePeerSmoother();
        PlayerState airborne = peerAt(0.0, 0.4);
        airborne.onGround = false;
        airborne.vy = 0.3;
        s.follow(airborne, false, 0L);

        PlayerState same = peerAt(0.0, 0.4);
        same.onGround = false;
        same.vy = 0.3;
        s.follow(same, false, TICK);

        assertTrue(s.x() > 0.0,
                "when the sim produces no frame the head does not move, but real time still passed."
                        + " Dead reckoning on the peer's own velocity keeps them travelling instead"
                        + " of stopping dead and lurching on the next tick");
        assertTrue(s.y() > 64.0, "the vertical component dead reckons too while airborne");
    }

    @Test
    void aGroundedPeerIsNeverSunkBelowTheSimPosition() {
        EdgePeerSmoother s = new EdgePeerSmoother();
        s.follow(peerAt(0.0, 0.0), false, 0L);
        PlayerState grounded = peerAt(0.0, 0.0);
        grounded.vy = -0.5;
        s.follow(grounded, false, TICK);

        assertEquals(64.0, s.y(), 1.0E-9,
                "a grounded peer must never be eased BELOW the sim's y or the opponent renders"
                        + " sunk into the floor");
    }

    @Test
    void aTeleportSnapsInsteadOfSliding() {
        EdgePeerSmoother s = new EdgePeerSmoother();
        s.follow(peerAt(0.0, 0.0), false, 0L);
        s.follow(peerAt(40.0, 0.0), false, TICK);

        assertEquals(40.0, s.x(), 1.0E-9,
                "a pearl or a round reset is a real teleport. Easing across it would show the"
                        + " opponent gliding through the arena");
    }

    @Test
    void theSmootherConvergesRatherThanTrailingForever() {
        EdgePeerSmoother s = new EdgePeerSmoother();
        s.follow(peerAt(0.0, 0.0), false, 0L);
        long t = 0L;
        for (int i = 0; i < 200; i++) {
            t += TICK;
            s.follow(peerAt(5.0, 0.0), false, t);
        }
        assertEquals(5.0, s.x(), 0.05,
                "the correction budget has to actually retire the error, or the rendered opponent"
                        + " sits permanently behind where hits register against them");
    }

    @Test
    void aJumpingPeerRisesEvenIfTheirGroundFlagIsStale() {
        EdgePeerSmoother s = new EdgePeerSmoother();
        PlayerState takeoff = peerAt(0.0, 0.0);
        takeoff.vy = 0.42;
        takeoff.onGround = true;
        s.follow(takeoff, false, 0L);

        PlayerState rising = peerAt(0.0, 0.0);
        rising.vy = 0.42;
        rising.onGround = true;
        rising.y = 64.42;
        s.follow(rising, false, TICK);

        assertTrue(s.y() > 64.2,
                "an edge-hosted peer's onGround is the CLIENT's self-reported flag consumed a frame"
                        + " late, so it can still read true at jump takeoff. Gating vertical dead"
                        + " reckoning on it suppressed the rise exactly when vertical velocity was"
                        + " highest, and the opponent appeared to walk along the floor while they"
                        + " were actually jumping");
    }
}
