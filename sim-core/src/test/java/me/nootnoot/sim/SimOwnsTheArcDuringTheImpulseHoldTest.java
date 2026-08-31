package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class SimOwnsTheArcDuringTheImpulseHoldTest {

    private static final double GROUND_Y = 64.0;

    private static Input landedHit() {
        return new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                .withMeleeHit(true);
    }

    private static GameState duelInRange(boolean edgeHosted) {
        GameState s = HarnessScenarios.duel(Arena.flat(GROUND_Y));
        s.edgeHosted[0] = edgeHosted;
        s.edgeHosted[1] = edgeHosted;
        s.players[1].x = s.players[0].x + 2.0;
        s.players[1].y = s.players[0].y;
        s.players[1].z = s.players[0].z;
        return s;
    }

    @Test
    void aHitArmsTheHoldOnAnEdgeHostedVictim() {
        GameState s = duelInRange(true);
        Simulation.tick(s, Arena.flat(GROUND_Y), landedHit(), Input.NONE);
        assertEquals(Simulation.IMPULSE_HOLD_TICKS, s.players[1].impulseHoldTicks,
                "the sim has to OWN the victim's arc for a moment. Predicting it instead is"
                        + " unfixable: the victim's client cannot report the knockback for a full"
                        + " round trip, so every predicted frame is contradicted by a stamp showing"
                        + " the pre-impulse path, and the arc plays forward then snaps back");
    }

    @Test
    void aStaleStampCannotCancelTheArcWhileTheHoldIsUp() {
        GameState s = duelInRange(true);
        Arena arena = Arena.flat(GROUND_Y);
        double stoodAtX = s.players[1].x;
        Simulation.tick(s, arena, landedHit(), Input.NONE);

        Simulation.tick(s, arena, Input.NONE,
                Input.NONE.withAuthority(Authority.at(stoodAtX, GROUND_Y, s.players[1].z, true)));

        assertTrue(Math.abs(s.players[1].x - stoodAtX) > 1.0E-6,
                "the victim's client is still reporting where it stood, because the velocity packet"
                        + " has not reached it yet. Adopting that stamp erases the knockback, which"
                        + " is what made the arc look delayed");
    }

    @Test
    void theHoldExpiresSoTheClientGetsAuthorityBack() {
        GameState s = duelInRange(true);
        Arena arena = Arena.flat(GROUND_Y);
        Simulation.tick(s, arena, landedHit(), Input.NONE);
        for (int i = 0; i < Simulation.IMPULSE_HOLD_TICKS + 2; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
        assertEquals(0, s.players[1].impulseHoldTicks,
                "an unbounded hold would take movement away from the player permanently");
    }

    @Test
    void aModHostedVictimNeverArmsTheHold() {
        GameState s = duelInRange(false);
        Simulation.tick(s, Arena.flat(GROUND_Y), landedHit(), Input.NONE);
        assertEquals(0, s.players[1].impulseHoldTicks,
                "a modded client's movement is already sim-owned, so this must stay inert there and"
                        + " leave mod-vs-mod behaviour untouched");
    }

    @Test
    void theHoldWaitsForTheCLIENTToLandNotJustTheSim() {
        GameState s = duelInRange(true);
        Arena arena = Arena.flat(GROUND_Y);
        Simulation.tick(s, arena, landedHit(), Input.NONE);

        for (int i = 0; i < Simulation.IMPULSE_HOLD_TICKS - 1; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE.withAuthority(Authority.at(
                    s.players[1].x, s.players[1].y + 0.5, s.players[1].z, false)));
        }

        assertTrue(s.players[1].impulseHoldTicks > 0,
                "the sim's arc runs AHEAD of the client's by the network delay, so the sim lands"
                        + " first. Traced live: the sim reached the ground at t=12 while the client"
                        + " was still at y+0.54, and handing over there yanked the victim UP half a"
                        + " block and dropped them again. On screen they descend to the floor while"
                        + " still airborne - the shadow going too far down - then pop back up."
                        + " Release needs BOTH to report grounded");
    }

    @Test
    void bothGroundedEndsTheHold() {
        GameState s = duelInRange(true);
        Arena arena = Arena.flat(GROUND_Y);
        Simulation.tick(s, arena, landedHit(), Input.NONE);
        for (int i = 0; i < Simulation.IMPULSE_HOLD_TICKS; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE.withAuthority(Authority.at(
                    s.players[1].x, s.players[1].y, s.players[1].z, s.players[1].onGround)));
        }
        assertEquals(0, s.players[1].impulseHoldTicks,
                "once both agree the flight is over the client owns its position again");
    }

    @Test
    void aClientStillBehindTheArcDoesNotEndTheHoldEarly() {
        GameState s = duelInRange(true);
        Arena arena = Arena.flat(GROUND_Y);
        double stoodAtX = s.players[1].x;
        Simulation.tick(s, arena, landedHit(), Input.NONE);
        for (int i = 0; i < 3; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE.withAuthority(
                    Authority.at(stoodAtX, GROUND_Y, s.players[1].z, true)));
        }

        assertTrue(s.players[1].impulseHoldTicks > 0,
                "a FIXED hold expires while the victim's client is still a round trip behind the"
                        + " arc, so authority resumes onto a stale position and yanks them"
                        + " backwards - both the jerk and the shortened knockback distance."
                        + " The hold has to wait for the client to actually catch up");
        assertTrue(Math.abs(s.players[1].x - stoodAtX) > 1.0E-6,
                "and the arc must still be running while it waits");
    }

    @Test
    void theHoldCapIsBoundedSoTheSimNeverOwnsAPlayerForLong() {
        assertTrue(Simulation.IMPULSE_HOLD_TICKS <= 24,
                "while the hold is up the sim owns the player's position and ignores their client."
                        + " Raising the cap to 40 to cover a 300ms round trip meant two full"
                        + " seconds of a client being told where it is, which is far worse than an"
                        + " early handback");
    }
}
