package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class RoundSpawnFacingOwnershipTest {

    private static final float SPAWN_YAW_0 = -90.0f;

    private static final float SPAWN_YAW_1 = 90.0f;

    private static final float LOOKING_ELSEWHERE = 17.5f;

    @Test
    void theSimRestoresTheSpawnYawAndThenTheSameTickThrowsItAwayForBothSlots() {
        GameState s = seeded();
        Arena arena = Arena.flat(64.0);
        Input away = looking(LOOKING_ELSEWHERE);

        s.players[1].dead = true;
        for (int i = 0; i < Simulation.ROUND_RESET_TOTAL + 2; i++) {
            Simulation.tick(s, arena, away, away);
        }

        assertEquals(SPAWN_YAW_0, s.roundInitial[0].yaw, 1.0E-4f,
                "slot 0's spawn snapshot must survive the round, untouched by play");
        assertEquals(SPAWN_YAW_1, s.roundInitial[1].yaw, 1.0E-4f,
                "slot 1's spawn snapshot must survive the round, untouched by play");

        assertEquals(LOOKING_ELSEWHERE, s.players[0].yaw, 1.0E-4f,
                "the reset yaw does not survive a single tick: tickPlayer assigns p.yaw = in.yaw()");
        assertEquals(LOOKING_ELSEWHERE, s.players[1].yaw, 1.0E-4f,
                "and it does not survive for the other slot either - the loss is SYMMETRIC, so a spawn"
                        + " facing that comes out wrong is never the sim favouring one slot");
        assertNotEquals(SPAWN_YAW_0, s.players[0].yaw,
                "so nothing downstream may read players[slot].yaw as the spawn facing");
    }

    @Test
    void aHostThatRefacesItsOwnPlayerIsWhatMakesTheSpawnFacingReal() {
        GameState s = seeded();
        Arena arena = Arena.flat(64.0);

        s.players[1].dead = true;
        for (int i = 0; i < Simulation.ROUND_RESET_TOTAL + 2; i++) {
            Input host0 = looking(s.roundInitial[0].yaw);
            Input host1 = looking(s.roundInitial[1].yaw);
            Simulation.tick(s, arena, host0, host1);
        }

        assertEquals(SPAWN_YAW_0, s.players[0].yaw, 1.0E-4f);
        assertEquals(SPAWN_YAW_1, s.players[1].yaw, 1.0E-4f,
                "both players end the reset facing their own spawn yaw, and both get there the same"
                        + " way: their own host sampled the snapshot back into the input stream");
    }

    private static Input looking(float yaw) {
        return new Input(false, false, false, false, false, false, false, false, false, yaw, 0f, 0);
    }

    private static GameState seeded() {
        GameState s = new GameState();
        s.roundsTarget = 3;
        seed(s.players[0], -4.0, 0.0, SPAWN_YAW_0);
        seed(s.players[1], 4.0, 0.0, SPAWN_YAW_1);
        s.roundInitial = new PlayerState[]{s.players[0].copy(), s.players[1].copy()};
        return s;
    }

    private static void seed(PlayerState p, double x, double z, float yaw) {
        p.x = x;
        p.y = 64.0;
        p.z = z;
        p.yaw = yaw;
        p.pitch = 0f;
        p.onGround = true;
        p.health = 20f;
        p.maxHealth = 20f;
    }
}
