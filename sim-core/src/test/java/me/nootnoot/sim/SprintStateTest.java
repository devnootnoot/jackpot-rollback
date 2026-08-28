package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class SprintStateTest {

    private static final double GROUND_Y = 64.0;

    private static GameState grounded() {
        GameState g = new GameState();
        for (int i = 0; i < 2; i++) {
            PlayerState p = g.players[i];
            p.x = i * 8.0;
            p.y = GROUND_Y;
            p.z = 0;
            p.onGround = true;
            p.vy = -0.0784;
            p.health = 20f;
            p.attackTicker = 100;
        }
        return g;
    }

    private static Input keys(boolean forward, boolean back, boolean sprint, boolean sneak,
                              double x, double y, double z, boolean onGround) {
        return new Input(forward, back, false, false, false, sprint, sneak, false, false, 0f, 0f, 0)
                .withAuthority(Authority.at(x, y, z, onGround));
    }

    private static Input idle() {
        return new Input(false, false, false, false, false, false, false, false, false, 0f, 0f, 0)
                .withAuthority(Authority.at(8.0, GROUND_Y, 0.0, true));
    }

    private static boolean sprintingAfter(Input mine, int ticks) {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = grounded();
        for (int i = 0; i < ticks; i++) {
            Simulation.tick(g, arena, mine, idle());
        }
        return g.players[0].sprinting;
    }

    @Test
    void holdingTheSprintKeyWhileStandingStillIsNotSprinting() {
        assertFalse(sprintingAfter(keys(false, false, true, false, 0, GROUND_Y, 0, true), 5),
                "a stationary player with the sprint flag set must not be sprinting - the opponent's"
                        + " client renders vanilla sprint dust off this flag");
    }

    @Test
    void walkingBackwardWithTheSprintKeyIsNotSprinting() {
        assertFalse(sprintingAfter(keys(false, true, true, false, 0, GROUND_Y, 0, true), 5),
                "backward movement has no forward impulse, so vanilla would not be sprinting");
    }

    @Test
    void sneakingWithTheSprintKeyDoesNotStartASprint() {
        assertFalse(sprintingAfter(keys(true, false, true, true, 0, GROUND_Y, 0, true), 5),
                "vanilla canStartSprinting is gated on !isMovingSlowly()");
    }

    @Test
    void forwardWithTheSprintKeyIsSprinting() {
        assertTrue(sprintingAfter(keys(true, false, true, false, 0, GROUND_Y, 0, true), 5),
                "forward + sprint must still sprint");
    }

    @Test
    void releasingForwardStopsAnActiveSprint() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = grounded();
        Input run = keys(true, false, true, false, 0, GROUND_Y, 0, true);
        for (int i = 0; i < 5; i++) {
            Simulation.tick(g, arena, run, idle());
        }
        assertTrue(g.players[0].sprinting, "precondition: the player is sprinting");

        Simulation.tick(g, arena, keys(false, false, true, false, 0, GROUND_Y, 0, true), idle());
        assertFalse(g.players[0].sprinting,
                "vanilla shouldStopRunSprinting drops the sprint the tick forward is released,"
                        + " even while the sprint key is still down");
    }

    @Test
    void starvingStopsAnActiveSprint() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = grounded();
        Input run = keys(true, false, true, false, 0, GROUND_Y, 0, true);
        for (int i = 0; i < 5; i++) {
            Simulation.tick(g, arena, run, idle());
        }
        assertTrue(g.players[0].sprinting, "precondition: the player is sprinting");

        g.players[0].food = 6.0f;
        Simulation.tick(g, arena, run, idle());
        assertFalse(g.players[0].sprinting, "food <= 6 must end the sprint");
    }
}
