package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class AuthorityCarryTest {
    private static final double GROUND_Y = 64.0;
    private static final double EXACT = 1.0E-9;

    private static final Input IDLE =
            new Input(false, false, false, false, false, false, false, false, false, 0f, 0f, 0);
    private static final Input SPRINT =
            new Input(true, false, false, false, false, true, false, false, false, 0f, 0f, 0);
    private static final Input JUMP =
            new Input(false, false, false, false, true, false, false, false, false, 0f, 0f, 0);

    private static GameState seeded() {
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
        return g;
    }

    private static Input stamp(Input in, PlayerState from) {
        return in.withAuthority(Authority.at(from.x, from.y, from.z, from.onGround));
    }

    private static double gap(PlayerState a, PlayerState b) {
        double dx = a.x - b.x;
        double dy = a.y - b.y;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void aStampedPlayerCarriesTheSameVelocityAFreeRunningPlayerWould() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState free = seeded();
        GameState stamped = seeded();
        for (int i = 0; i < 40; i++) {
            Simulation.tick(free, arena, IDLE, SPRINT);
            Simulation.tick(stamped, arena, IDLE, stamp(SPRINT, free.players[1]));
        }
        PlayerState a = free.players[1];
        PlayerState b = stamped.players[1];
        assertEquals(a.vx, b.vx, EXACT);
        assertEquals(a.vy, b.vy, EXACT);
        assertEquals(a.vz, b.vz, EXACT);
        assertTrue(b.onGround);
    }

    @Test
    void predictionAfterTheLastStampReproducesTheFreeRunningSprint() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState free = seeded();
        GameState stamped = seeded();
        for (int i = 0; i < 40; i++) {
            Simulation.tick(free, arena, IDLE, SPRINT);
            Simulation.tick(stamped, arena, IDLE, stamp(SPRINT, free.players[1]));
        }
        for (int i = 0; i < RollbackController.PREDICTION_DECAY_FRAMES; i++) {
            Simulation.tick(free, arena, IDLE, SPRINT);
            Simulation.tick(stamped, arena, IDLE, SPRINT.heldOnly());
            assertEquals(0.0, gap(free.players[1], stamped.players[1]), EXACT,
                    "predicted frame " + (i + 1) + " drifted from the real trajectory");
        }
    }

    @Test
    void predictionAfterTheLastStampReproducesTheFreeRunningJumpArc() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState free = seeded();
        GameState stamped = seeded();
        for (int i = 0; i < 3; i++) {
            Simulation.tick(free, arena, IDLE, JUMP);
            Simulation.tick(stamped, arena, IDLE, stamp(JUMP, free.players[1]));
        }
        for (int i = 0; i < 12; i++) {
            Simulation.tick(free, arena, IDLE, JUMP);
            Simulation.tick(stamped, arena, IDLE, JUMP.heldOnly());
            PlayerState a = free.players[1];
            PlayerState b = stamped.players[1];
            assertEquals(a.y, b.y, EXACT, "predicted frame " + (i + 1) + " left the jump arc");
            assertEquals(a.onGround, b.onGround, "predicted frame " + (i + 1) + " lost ground contact");
        }
    }

    @Test
    void aStampedPlayerStandingStillKeepsGroundContactWhenPredictionTakesOver() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = seeded();
        for (int i = 0; i < 10; i++) {
            Simulation.tick(g, arena, IDLE,
                    IDLE.withAuthority(Authority.at(4.0, GROUND_Y, 0.0, true)));
        }
        assertEquals(-0.0784, g.players[1].vy, EXACT);
        for (int i = 0; i < 6; i++) {
            Simulation.tick(g, arena, IDLE, IDLE.heldOnly());
            assertTrue(g.players[1].onGround,
                    "predicted frame " + (i + 1) + " went airborne while standing on solid ground");
            assertEquals(GROUND_Y, g.players[1].y, EXACT);
        }
    }
}
