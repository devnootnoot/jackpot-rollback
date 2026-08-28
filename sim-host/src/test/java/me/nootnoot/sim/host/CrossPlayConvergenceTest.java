package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.net.LoopbackNetwork;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CrossPlayConvergenceTest {

    private static final int ONE_WAY_FRAMES = 2;
    private static final int JITTER_FRAMES = 1;
    private static final double LOSS = 0.03;
    private static final int RING = 1024;
    private static final int FRAMES = 600;

    private static final double EDGE_START_X = -3.0;
    private static final double MOD_START_X = 3.0;
    private static final double GROUND_Y = 64.0;

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

    private static final class EdgeHosted implements InputSource {
        private int frame;

        @Override
        public Input sample() {
            int f = frame++;
            boolean forward = (f / 25) % 2 == 0;
            double travelled = 0.2806 * (forward ? f % 25 : 25 - (f % 25));
            double x = EDGE_START_X + travelled;
            Input in = new Input(forward, false, false, false, f % 41 == 0, forward, false,
                    f % 13 == 0, false, 90f, 0f, 0);
            return in.withMeleeHit(f % 13 == 0)
                    .withAuthority(Authority.at(x, GROUND_Y, 0.0, true));
        }
    }

    private static final class ModHosted implements InputSource {
        private int frame;

        @Override
        public Input sample() {
            int f = frame++;
            boolean forward = (f / 25) % 2 == 0;
            boolean jump = f % 37 == 0;
            Input in = new Input(forward, false, false, false, jump, forward, false,
                    f % 17 == 0, false, -90f, 0f, 0);
            return in.withMeleeHit(f % 17 == 0);
        }
    }

    private static GameState seed() {
        GameState g = new GameState();
        PlayerState edge = g.players[0];
        PlayerState mod = g.players[1];
        edge.x = EDGE_START_X;
        edge.y = GROUND_Y;
        edge.z = 0.0;
        edge.health = 20f;
        edge.maxHealth = 20f;
        edge.attackDamage = 7f;
        edge.attackSpeed = 1.6f;
        mod.x = MOD_START_X;
        mod.y = GROUND_Y;
        mod.z = 0.0;
        mod.health = 20f;
        mod.maxHealth = 20f;
        mod.attackDamage = 7f;
        mod.attackSpeed = 1.6f;
        g.roundsTarget = 30;
        g.edgeHosted[0] = true;
        return g;
    }

    @Test
    void anEdgeHostAndAModHostConvergeInTheSameMatch() {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0xC0551A4L, ONE_WAY_FRAMES, JITTER_FRAMES, LOSS);

        MatchDriver edge = new MatchDriver(net.endpoint(0), 0, arena, seed(), RING,
                new EdgeHosted(), new NoRender());
        MatchDriver mod = new MatchDriver(net.endpoint(1), 1, arena, seed(), RING,
                new ModHosted(), new NoRender());

        boolean edgeLive = true;
        boolean modLive = true;
        for (int i = 0; i < FRAMES && edgeLive && modLive; i++) {
            edgeLive = edge.tick();
            modLive = mod.tick();
            net.step();
        }

        assertFalse(edge.aborted(), "the edge host aborted: " + edge.abortReason());
        assertFalse(mod.aborted(), "the mod host aborted: " + mod.abortReason());
        assertEquals(-1, edge.desyncFrame(), "the edge host saw a checksum desync");
        assertEquals(-1, mod.desyncFrame(), "the mod host saw a checksum desync");

        int common = Math.min(edge.confirmedFrame(), mod.confirmedFrame());
        assertTrue(common > FRAMES / 2,
                "expected both hosts to confirm past half the run, got " + common);
    }

    @Test
    void oneStampedPlayerAndOneSimulatedPlayerTickIdenticallyOnBothHosts() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState onTheEdge = seed();
        GameState onTheMod = seed();
        EdgeHosted edgeScript = new EdgeHosted();
        ModHosted modScript = new ModHosted();

        for (int i = 0; i < 300; i++) {
            Input stamped = edgeScript.sample();
            Input simulated = modScript.sample();
            Simulation.tick(onTheEdge, arena, stamped, simulated);
            Simulation.tick(onTheMod, arena, stamped, simulated);
        }

        assertEquals(Checksum.of(onTheEdge), Checksum.of(onTheMod),
                "a mixed-authority input stream must produce one state on both hosts");
        assertTrue(onTheEdge.players[1].x != MOD_START_X,
                "the unstamped player must have been simulated, not pinned");
    }

    @Test
    void theStampedPlayerIsPinnedWhileTheUnstampedOneIntegrates() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = seed();
        Input stamped = new Input(true, false, false, false, false, true, false, false, false,
                0f, 0f, 0).withAuthority(Authority.at(-2.5, GROUND_Y, 0.75, true));
        Input free = new Input(true, false, false, false, false, true, false, false, false,
                0f, 0f, 0);

        double freeStartZ = g.players[1].z;
        for (int i = 0; i < 40; i++) {
            Simulation.tick(g, arena, stamped, free);
        }

        assertEquals(-2.5, g.players[0].x, 0.0, "the stamped player must sit on the reported x");
        assertEquals(0.75, g.players[0].z, 0.0, "the stamped player must sit on the reported z");
        assertNotEquals(freeStartZ, g.players[1].z,
                "the unstamped player must integrate its own movement");
    }
}
