package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class AttackerSeesKnockbackImmediatelyTest {

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
    void anEdgeHostedVictimStartsMovingOnTheHitFrame() {
        GameState s = duelInRange(true);
        Arena arena = Arena.flat(GROUND_Y);
        Simulation.tick(s, arena, landedHit(), Input.NONE.heldOnly());

        assertTrue(Math.abs(s.players[1].vx) > 1.0E-6,
                "the whole point of rollback is that the attacker sees the hit land NOW, not after"
                        + " a round trip. The sim must predict the victim's arc on the attacking"
                        + " host immediately. Suppressing it made the victim sit still until their"
                        + " own position stamps came back, which is exactly the latency the system"
                        + " exists to hide. The reconciliation when the real stamps arrive is the"
                        + " renderer's job (EdgePeerSmoother), not the simulation's");
    }

    @Test
    void theVictimStillOwesTheClientExactlyOneVelocityPacket() {
        GameState s = duelInRange(true);
        Arena arena = Arena.flat(GROUND_Y);
        int seqBefore = s.players[1].impulseSeq;

        Simulation.tick(s, arena, landedHit(), Input.NONE.heldOnly());

        assertEquals(seqBefore + 1, s.players[1].impulseSeq,
                "predicting the arc must not replace the impulse channel - the victim's own client"
                        + " still has to be shoved by a velocity packet, the way vanilla"
                        + " knockbackTarget sends EntityVelocityUpdateS2CPacket, or the victim"
                        + " feels nothing on their own screen");
    }

    @Test
    void aModHostedVictimIsUnaffected() {
        GameState s = duelInRange(false);
        Arena arena = Arena.flat(GROUND_Y);
        Simulation.tick(s, arena, landedHit(), Input.NONE);

        assertTrue(Math.abs(s.players[1].vx) > 1.0E-6,
                "mod-vs-mod already felt right and must stay bit-identical");
    }
}
