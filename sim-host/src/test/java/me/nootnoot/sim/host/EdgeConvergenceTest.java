package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.net.LoopbackNetwork;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class EdgeConvergenceTest {

    private static final int ONE_WAY_FRAMES = 1;
    private static final int RING = 1024;
    private static final int FRAMES = 400;

    private static final class NoRender implements SimRenderer {
        @Override
        public void render(GameState head, GameState confirmed) {
        }

        @Override
        public void playEvents(List<CombatEvent> events, GameState state) {
        }

        @Override
        public void clear() {
        }
    }

    private static final class ScriptedEdge implements InputSource {
        private final double startX;
        private final double startZ;
        private final double y;
        private final int phase;
        private int frame;

        ScriptedEdge(double startX, double y, double startZ, int phase) {
            this.startX = startX;
            this.y = y;
            this.startZ = startZ;
            this.phase = phase;
        }

        @Override
        public Input sample() {
            int f = frame++;
            boolean forward = ((f + phase) / 20) % 2 == 0;
            double travelled = 0.2806 * (((f + phase) / 20) % 2 == 0 ? f % 20 : 20 - (f % 20));
            double x = startX + (phase == 0 ? travelled : -travelled);
            Input in = new Input(forward, false, false, false, false, forward, false,
                    f % 37 == 0, false, 90f * phase, 0f, 0);
            return in.withAuthority(Authority.at(x, y, startZ, true));
        }
    }

    private static GameState seed() {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        PlayerState b = g.players[1];
        a.x = -3.0;
        a.y = 64.0;
        a.z = 0.0;
        a.health = 20f;
        a.maxHealth = 20f;
        b.x = 3.0;
        b.y = 64.0;
        b.z = 0.0;
        b.health = 20f;
        b.maxHealth = 20f;
        g.edgeHosted[0] = true;
        g.edgeHosted[1] = true;
        return g;
    }

    @Test
    void twoEdgesConvergeAcrossAnEightyMillisecondLink() {
        Arena arena = Arena.flat(64.0);
        LoopbackNetwork net = new LoopbackNetwork(0xED6EL, ONE_WAY_FRAMES, 1, 0.02);

        MatchDriver london = new MatchDriver(net.endpoint(0), 0, arena, seed(), RING,
                new ScriptedEdge(-3.0, 64.0, 0.0, 0), new NoRender());
        MatchDriver ashburn = new MatchDriver(net.endpoint(1), 1, arena, seed(), RING,
                new ScriptedEdge(3.0, 64.0, 0.0, 1), new NoRender());

        for (int i = 0; i < FRAMES; i++) {
            london.tick();
            ashburn.tick();
            net.step();
        }

        assertFalse(london.aborted(), "london aborted: " + london.abortReason());
        assertFalse(ashburn.aborted(), "ashburn aborted: " + ashburn.abortReason());

        int common = Math.min(london.confirmedFrame(), ashburn.confirmedFrame());
        assertTrue(common > FRAMES / 2,
                "expected both edges to confirm past half the run, got " + common);
        assertEquals(-1, london.desyncFrame(), "london reported a desync");
        assertEquals(-1, ashburn.desyncFrame(), "ashburn reported a desync");
    }

    @Test
    void bothEdgesApplyTheSameReportedPositionSoTheStateMatches() {
        Arena arena = Arena.flat(64.0);
        GameState a = seed();
        GameState b = seed();

        Input p0 = new Input(true, false, false, false, false, true, false, false, false, 0f, 0f, 0)
                .withAuthority(Authority.at(-2.5, 64.0, 0.25, true));
        Input p1 = new Input(false, true, false, false, false, false, false, false, false, 180f, 0f, 0)
                .withAuthority(Authority.at(2.5, 64.0, -0.25, true));

        for (int i = 0; i < 60; i++) {
            me.nootnoot.sim.Simulation.tick(a, arena, p0, p1);
            me.nootnoot.sim.Simulation.tick(b, arena, p0, p1);
        }

        assertEquals(Checksum.of(a), Checksum.of(b), "identical authority must produce identical state");
        assertEquals(-2.5, a.players[0].x, 0.0, "player 0 must be pinned to the reported position");
        assertEquals(2.5, a.players[1].x, 0.0, "player 1 must be pinned to the reported position");
    }

    @Test
    void anInputWithoutAuthorityStillIntegratesNormally() {
        Arena arena = Arena.flat(64.0);
        GameState g = seed();
        Input walk = new Input(true, false, false, false, false, false, false, false, false, 0f, 0f, 0);

        double before = g.players[0].z;
        for (int i = 0; i < 20; i++) {
            me.nootnoot.sim.Simulation.tick(g, arena, walk, Input.NONE);
        }

        assertTrue(g.players[0].z > before + 0.5,
                "a non-authoritative input must still move the player, got dz="
                        + (g.players[0].z - before));
    }

    @Test
    void predictedInputsDropAuthoritySoAnUnarrivedFrameIntegrates() {
        Input authoritative = new Input(true, false, false, false, false, false, false, false, false,
                0f, 0f, 0).withAuthority(Authority.at(1.0, 2.0, 3.0, true));

        assertTrue(authoritative.authority().present());
        assertFalse(authoritative.heldOnly().authority().present(),
                "heldOnly() is the prediction path and must not pin the player");
        assertFalse(authoritative.released().authority().present(),
                "released() is the decay path and must not pin the player");
    }
}
