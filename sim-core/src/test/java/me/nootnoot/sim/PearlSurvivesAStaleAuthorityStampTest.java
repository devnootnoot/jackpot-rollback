package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class PearlSurvivesAStaleAuthorityStampTest {

    private static final double GROUND_Y = 64.0;

    private static GameState edgeHostedDuel() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.edgeHosted[0] = true;
        s.edgeHosted[1] = true;
        return s;
    }

    private static Input stampedAt(double x, double y, double z) {
        return Input.NONE.withAuthority(Authority.at(x, y, z, true));
    }

    @Test
    void aStaleStampFromBeforeTheTeleportDoesNotDragThePlayerBack() {
        GameState s = edgeHostedDuel();
        Arena arena = Arena.flat(GROUND_Y);
        PlayerState a = s.players[0];
        double fromX = a.x;
        double fromZ = a.z;

        a.x = fromX + 15.0;
        a.z = fromZ + 15.0;
        a.authoritySuspendTicks = Simulation.AUTHORITY_SUSPEND_TICKS;

        for (int i = 0; i < 5; i++) {
            Simulation.tick(s, arena, stampedAt(fromX, GROUND_Y, fromZ), Input.NONE);
        }

        assertTrue(Math.abs(s.players[0].x - fromX) > 10.0,
                "the client's in-flight position reports still name the pre-pearl spot, and it is"
                        + " well inside MAX_AUTHORITY_STEP, so without the suspension the very next"
                        + " stamp yanks the sim straight back and the pearl never lands. This is"
                        + " edge-only because stamps() returns early for a mod-hosted slot");
    }

    @Test
    void controlReturnsAsSoonAsTheClientReportsTheNewPlace() {
        GameState s = edgeHostedDuel();
        Arena arena = Arena.flat(GROUND_Y);
        PlayerState a = s.players[0];
        a.x += 15.0;
        a.authoritySuspendTicks = Simulation.AUTHORITY_SUSPEND_TICKS;
        double landedX = a.x;

        Simulation.tick(s, arena, stampedAt(landedX, s.players[0].y, s.players[0].z), Input.NONE);

        assertEquals(0, s.players[0].authoritySuspendTicks,
                "once the client's own report agrees with where the sim put them the suspension has"
                        + " to lift immediately, or the player loses movement control for a second");
    }

    @Test
    void theSuspensionCannotLatchForever() {
        GameState s = edgeHostedDuel();
        Arena arena = Arena.flat(GROUND_Y);
        PlayerState a = s.players[0];
        a.authoritySuspendTicks = Simulation.AUTHORITY_SUSPEND_TICKS;
        double farX = a.x + 20.0;

        for (int i = 0; i < Simulation.AUTHORITY_SUSPEND_TICKS + 5; i++) {
            Simulation.tick(s, arena, stampedAt(farX, GROUND_Y, s.players[0].z), Input.NONE);
        }

        assertEquals(0, s.players[0].authoritySuspendTicks,
                "a client that never converges must not be able to hold its own movement authority"
                        + " suspended indefinitely");
    }
}
