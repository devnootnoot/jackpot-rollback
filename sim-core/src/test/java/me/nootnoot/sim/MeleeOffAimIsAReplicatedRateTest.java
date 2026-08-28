package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class MeleeOffAimIsAReplicatedRateTest {
    private static final double GROUND_Y = 64.0;

    private static final float ON_CROSSHAIR_YAW = -90f;

    private static final float OFF_CROSSHAIR_YAW = -10f;

    private static GameState faceOff(double gap, float attackerYaw) {
        GameState g = new GameState();
        for (PlayerState p : g.players) {
            p.y = GROUND_Y;
            p.onGround = true;
            p.health = 20f;
            p.maxHealth = 20f;
        }
        g.players[0].x = 0.0;
        g.players[1].x = gap;
        g.players[0].yaw = attackerYaw;
        g.roundsTarget = 1;
        return g;
    }

    private static void swing(GameState g, Arena arena) {
        assertNotNull(ClaimAuthority.meleeClaim(g, arena, g.players[0], g.players[1]),
                "the claim itself must still be granted: the rate is a measurement and never a gate");
    }

    @Test
    void aGrantWithTheCrosshairOffTheBodyLandsOnTheAttackersOwnLedger() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff(2.5, OFF_CROSSHAIR_YAW);

        swing(g, arena);

        assertEquals(1, g.players[0].meleeClaimsGranted, "the attacker swung once");
        assertEquals(1, g.players[0].meleeClaimsOffAim,
                "and the crosshair was 80 degrees off the body, which is the number a"
                        + " kill-aura cannot keep low. 80 rather than 180: a claim aimed behind"
                        + " the attacker is now refused outright by ClaimAuthority.aimAhead, so"
                        + " the off-aim ledger only ever counts grants that survived that gate");
        assertEquals(0, g.players[1].meleeClaimsGranted,
                "the victim did not swing, so nothing may land on their ledger");
        assertEquals(0, g.players[1].meleeClaimsOffAim, "nor on their off-aim ledger");
    }

    @Test
    void anOrdinaryHitCountsAsAClaimAndNotAsAnOffAimOne() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff(2.5, ON_CROSSHAIR_YAW);

        swing(g, arena);

        assertEquals(1, g.players[0].meleeClaimsGranted, "an on-aim hit is still a claim");
        assertEquals(0, g.players[0].meleeClaimsOffAim, "but it must not inflate the numerator");
    }

    @Test
    void theLedgerIsInTheChecksumSoTheTwoPeersHaveToAgreeOnIt() {
        GameState a = faceOff(2.5, OFF_CROSSHAIR_YAW);
        GameState b = faceOff(2.5, OFF_CROSSHAIR_YAW);
        assertEquals(Checksum.of(a), Checksum.of(b), "control: the two states start identical");

        b.players[0].meleeClaimsOffAim = 1;
        assertNotEquals(Checksum.of(a), Checksum.of(b),
                "a number nobody checksums is a number a tampered client can report differently"
                        + " from the peer and the referee, and the whole point of putting the rate"
                        + " in state rather than in a process counter is that a disagreement about"
                        + " it is a desync");

        b.players[0].meleeClaimsOffAim = 0;
        b.players[0].meleeClaimsGranted = 1;
        assertNotEquals(Checksum.of(a), Checksum.of(b), "and the denominator is pinned too");
    }

    @Test
    void theLedgerSurvivesARoundResetBecauseTheRateIsAMatchQuestion() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff(2.5, OFF_CROSSHAIR_YAW);
        g.roundsTarget = 2;
        g.roundInitial = new PlayerState[]{g.players[0].copy(), g.players[1].copy()};

        swing(g, arena);
        assertEquals(1, g.players[0].meleeClaimsOffAim, "one off-aim grant in round one");

        g.players[1].dead = true;
        g.roundResetCountdown = Simulation.ROUND_RESET_TOTAL;
        for (int i = 0; i < Simulation.ROUND_RESET_TOTAL + 2; i++) {
            Simulation.tick(g, arena, me.nootnoot.sim.state.Input.NONE,
                    me.nootnoot.sim.state.Input.NONE);
        }

        assertEquals(1, g.players[0].meleeClaimsOffAim,
                "a kill-aura that only has to survive to the next round is not measured at all;"
                        + " the ratio is a match-long question so the ledger outlives the reset");
        assertEquals(1, g.players[0].meleeClaimsGranted, "and so does its denominator");
    }

    @Test
    void theRateNeedsASampleBeforeItSaysAnything() {
        PlayerState p = new PlayerState();
        p.meleeClaimsGranted = 3;
        p.meleeClaimsOffAim = 3;
        assertFalse(ClaimAuthority.offAimRateExceeds(p, 20, 1, 4),
                "three swings that all missed the crosshair is a jump crit or a lag spike, and"
                        + " refusing to answer under the sample floor is what keeps it from being"
                        + " read as evidence");

        p.meleeClaimsGranted = 40;
        p.meleeClaimsOffAim = 4;
        assertFalse(ClaimAuthority.offAimRateExceeds(p, 20, 1, 4),
                "one in ten is what honest mid-turn play looks like");

        p.meleeClaimsOffAim = 11;
        assertTrue(ClaimAuthority.offAimRateExceeds(p, 20, 1, 4),
                "eleven in forty is past one in four and is the shape of a client that aims"
                        + " somewhere other than where it says it is looking");
    }
}
