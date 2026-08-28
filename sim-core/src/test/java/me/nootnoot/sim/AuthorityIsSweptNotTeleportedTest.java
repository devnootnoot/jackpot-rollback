package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class AuthorityIsSweptNotTeleportedTest {
    private static final double GROUND_Y = 64.0;
    private static final double EXACT = 1.0E-9;

    private static final Input IDLE =
            new Input(false, false, false, false, false, false, false, false, false, 0f, 0f, 0);

    private static GameState hosted() {
        GameState g = new GameState();
        for (PlayerState p : g.players) {
            p.y = GROUND_Y;
            p.onGround = true;
            p.health = 20f;
            p.maxHealth = 20f;
        }
        g.players[0].x = -4.0;
        g.players[1].x = 4.0;
        g.roundsTarget = 1;
        g.edgeHosted[1] = true;
        return g;
    }

    private static Input stamp(double x, double y, double z, boolean onGround) {
        return IDLE.withAuthority(Authority.at(x, y, z, onGround));
    }

    private static void wall(GameState g, int x) {
        for (int y = 64; y < 68; y++) {
            for (int z = -3; z <= 3; z++) {
                g.blocks.place(x, y, z, 920);
            }
        }
    }

    @Test
    void aClearStampIsCarriedThroughExactlyAsBefore() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = hosted();
        Simulation.tick(g, arena, IDLE, stamp(4.4, GROUND_Y, 0.25, true));

        assertEquals(4.4, g.players[1].x, EXACT,
                "sweeping a clear path must return the whole delta, or every honest stamp pays for"
                        + " the check");
        assertEquals(0.25, g.players[1].z, EXACT);
        assertEquals(GROUND_Y, g.players[1].y, EXACT);
        assertTrue(g.players[1].onGround);
    }

    @Test
    void aStampCannotWalkAPlayerThroughAWall() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = hosted();
        wall(g, 6);

        Simulation.tick(g, arena, IDLE, stamp(9.5, GROUND_Y, 0.0, true));

        assertTrue(g.players[1].x < 6.0,
                "the magnitude clamp bounded how FAR the authority word may move a player and said"
                        + " nothing about WHERE. 24 blocks a tick of raw position write with no"
                        + " collision test is noclip in one field, and it landed at x="
                        + g.players[1].x);
    }

    @Test
    void aStampCannotDropAPlayerThroughTheFloor() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = hosted();

        Simulation.tick(g, arena, IDLE, stamp(4.0, GROUND_Y - 8.0, 0.0, false));

        assertTrue(g.players[1].y >= GROUND_Y - EXACT,
                "the arena floor is replicated on both hosts and the sweep is the same sweep the"
                        + " simulated move uses, so a claim to be under it is refusable without"
                        + " either side guessing: y=" + g.players[1].y);
    }

    @Test
    void aGroundClaimInMidAirIsNotBelieved() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = hosted();
        g.players[1].y = GROUND_Y + 6.0;
        g.players[1].onGround = false;

        Simulation.tick(g, arena, IDLE, stamp(4.0, GROUND_Y + 6.0, 0.0, true));

        assertFalse(g.players[1].onGround,
                "onGround was a free-standing boolean nobody checked, and it decides jumping, step"
                        + " behaviour and whether a fall is ever collected");
    }

    @Test
    void refusingToEverLandDoesNotCancelTheFall() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = hosted();
        PlayerState p = g.players[1];
        p.y = GROUND_Y + 20.0;
        p.onGround = false;

        double y = p.y;
        for (int i = 0; i < 60 && p.y > GROUND_Y + EXACT; i++) {
            y = Math.max(GROUND_Y, y - 1.0);
            Simulation.tick(g, arena, IDLE, stamp(4.0, y, 0.0, false));
        }

        assertEquals(GROUND_Y, p.y, EXACT, "the fall never reached the floor");
        assertTrue(p.onGround,
                "claiming never to have landed while standing on the arena floor was a permanent"
                        + " fall-damage cancel, because damage is only collected on the tick"
                        + " onGround turns true");
        assertTrue(p.health < 20f,
                "and so the fall cost nothing: health=" + p.health);
    }

    @Test
    void aSlotThatIsNotEdgeHostedIsStillNotStampableAtAll() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = hosted();
        g.edgeHosted[1] = false;

        Simulation.tick(g, arena, IDLE, stamp(4.4, GROUND_Y, 0.25, true));

        assertEquals(4.0, g.players[1].x, EXACT,
                "one replicated boolean is the outer gate on the whole authority word and it has to"
                        + " keep being the outer gate");
    }
}
