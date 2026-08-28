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

class KnockbackSurvivesAStaleAuthorityStampTest {

    private static final double GROUND_Y = 64.0;

    private static GameState edgeHostedDuel() {
        GameState s = HarnessScenarios.duel(Arena.flat(GROUND_Y));
        s.edgeHosted[0] = true;
        s.edgeHosted[1] = true;
        return s;
    }

    private static Input stampedAt(double x, double y, double z) {
        return Input.NONE.withAuthority(Authority.at(x, y, z, true));
    }

    @Test
    void theImpulseIsCapturedForTheEdgeBeforeTheStampErasesIt() {
        GameState s = edgeHostedDuel();
        Arena arena = Arena.flat(GROUND_Y);
        PlayerState victim = s.players[1];
        double stoodAtX = victim.x;
        double stoodAtZ = victim.z;
        int seqBefore = victim.impulseSeq;

        Combat.knockback(victim, Combat.BASE_KNOCKBACK, 1.0, 0.0);

        assertTrue(Math.abs(victim.vx) > 0.0, "the fixture must actually impart knockback");
        assertEquals(seqBefore + 1, victim.impulseSeq,
                "the edge dedupes its one velocity packet on this counter, so every impulse"
                        + " has to bump it");
        assertEquals(victim.vx, victim.impulseVx,
                "the edge sends the impulse AS APPLIED, the way vanilla knockbackTarget sends"
                        + " EntityVelocityUpdateS2CPacket in the same tick takeKnockback ran."
                        + " Sending the head velocity instead hands the client impulse * 0.91^K"
                        + " once a rollback has replayed K frames of friction, which is why the"
                        + " shove felt right at 0ms and wrong at 80ms");

        Simulation.tick(s, arena, Input.NONE, stampedAt(stoodAtX, GROUND_Y, stoodAtZ));

        assertEquals(seqBefore + 1, s.players[1].impulseSeq,
                "the stamp overwrites vx, but it must not touch the impulse the edge still"
                        + " owes the client");
        assertTrue(Math.abs(s.players[1].impulseVx) > 0.0,
                "the recorded impulse outlives the stamp that erased the live velocity");
    }

    @Test
    void theSimDoesNotOwnAnEdgeHostedVictimsArc() {
        GameState s = edgeHostedDuel();
        Arena arena = Arena.flat(GROUND_Y);
        PlayerState victim = s.players[1];
        double stoodAtX = victim.x;
        double stoodAtZ = victim.z;

        Combat.knockback(victim, Combat.BASE_KNOCKBACK, 1.0, 0.0);
        Simulation.tick(s, arena, Input.NONE, stampedAt(stoodAtX, GROUND_Y, stoodAtZ));

        assertEquals(stoodAtX, s.players[1].x, 1.0E-9,
                "the victim's client owns the arc and reports it back, exactly as vanilla"
                        + " restores playerTargetVelocity so the server never integrates the"
                        + " knockback. If the sim ran the arc too it would be a round trip ahead"
                        + " of the client, and adopting the next stamp would drag the victim back"
                        + " to a midpoint - the choppy snap-back at latency");
        assertEquals(stoodAtZ, s.players[1].z, 1.0E-9,
                "same on the other horizontal axis");
    }
}
