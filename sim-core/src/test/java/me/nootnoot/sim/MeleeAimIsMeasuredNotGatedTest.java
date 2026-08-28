package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class MeleeAimIsMeasuredNotGatedTest {
    private static final double GROUND_Y = 64.0;

    private static final float MID_TURN_YAW = -10f;

    private static GameState faceOff(double gap) {
        GameState g = new GameState();
        for (PlayerState p : g.players) {
            p.y = GROUND_Y;
            p.onGround = true;
            p.health = 20f;
            p.maxHealth = 20f;
        }
        g.players[0].x = 0.0;
        g.players[1].x = gap;
        g.roundsTarget = 1;
        return g;
    }

    private static ClaimAuthority.Claim swing(GameState g, Arena arena) {
        return ClaimAuthority.meleeClaim(g, arena, g.players[0], g.players[1]);
    }

    private static long offAimGrants(Runnable body) {
        long[] sink = new long[SimProbe.COUNTERS];
        SimProbe.install(sink);
        try {
            body.run();
        } finally {
            SimProbe.uninstall();
        }
        return sink[SimProbe.MELEE_CLAIM_GRANTED_OFF_AIM];
    }

    @Test
    void theCrosshairCoveringTheBodyIsSomethingWeCanDecide() {
        Aabb hull = Aabb.player(3.0, GROUND_Y, 0.0, Simulation.PLAYER_WIDTH, 1.8);
        Vec3 at = Combat.lookVector(-90f, 0f);
        Vec3 away = Combat.lookVector(90f, 0f);

        assertTrue(ClaimAuthority.aimCovers(hull, 0.0, GROUND_Y + 1.62, 0.0, at, 4.0));
        assertFalse(ClaimAuthority.aimCovers(hull, 0.0, GROUND_Y + 1.62, 0.0, away, 4.0));
    }

    @Test
    void butItIsNotAllowedToDecideTheClaim() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff(2.5);
        g.players[0].yaw = MID_TURN_YAW;

        assertFalse(ClaimAuthority.aimCovers(
                        Aabb.player(2.5, GROUND_Y, 0.0, Simulation.PLAYER_WIDTH, 1.8),
                        0.0, GROUND_Y + 1.62, 0.0, Combat.lookVector(MID_TURN_YAW, 0f), 4.0),
                "control: the crosshair is 80 degrees off the body");

        assertNotNull(swing(g, arena),
                "yaw and pitch are a 20 Hz sample of a camera that moved continuously through the"
                        + " sample, so a CROSSHAIR gate is paid for by honest players mid-turn."
                        + " That decision stands. What changed at CHECKSUM_REV 165 is narrower:"
                        + " ClaimAuthority.aimAhead refuses a claim only when every candidate hull"
                        + " in reach is more than 120 degrees off the frame's own look vector,"
                        + " which no human rotation inside one tick can reach. ClaimMarginTest"
                        + " owns both halves of that decision; this test exists so nobody widens"
                        + " the gate from behind-the-back to off-crosshair while adding the"
                        + " measurement");
    }

    @Test
    void aGrantMadeWithTheCrosshairOffTheBodyIsCounted() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState away = faceOff(2.5);
        away.players[0].yaw = MID_TURN_YAW;
        GameState at = faceOff(2.5);
        at.players[0].yaw = -90f;

        assertEquals(1, offAimGrants(() -> swing(away, arena)),
                "a hit landed with the crosshair 80 degrees off the body and nothing recorded"
                        + " it."
                        + " One of those is a jump crit; a player whose ratio stays high is a"
                        + " kill-aura, and that is a rate question, so it needs a rate");
        assertEquals(0, offAimGrants(() -> swing(at, arena)),
                "and an ordinary hit must not inflate the number");
    }
}
