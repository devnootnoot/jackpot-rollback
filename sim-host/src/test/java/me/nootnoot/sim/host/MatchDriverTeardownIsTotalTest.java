package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.net.Transport;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class MatchDriverTeardownIsTotalTest {

    private static final int RING = 64;

    private static final class DeadTransport implements Transport {
        private final boolean throwOnClose;
        private int closes;

        DeadTransport(boolean throwOnClose) {
            this.throwOnClose = throwOnClose;
        }

        @Override
        public void send(byte[] packet) {
        }

        @Override
        public List<byte[]> receive() {
            return List.of();
        }

        @Override
        public void close() {
            closes++;
            if (throwOnClose) {
                throw new IllegalStateException("the relay socket was already gone");
            }
        }
    }

    private static final class Renderer implements SimRenderer {
        private final RuntimeException onClear;
        private int clears;

        Renderer(RuntimeException onClear) {
            this.onClear = onClear;
        }

        @Override
        public void render(GameState head, GameState confirmed) {
        }

        @Override
        public void playEvents(List<CombatEvent> events, GameState state) {
        }

        @Override
        public void clear() {
            clears++;
            if (onClear != null) {
                throw onClear;
            }
        }
    }

    private static final class ErrorRenderer implements SimRenderer {
        private int clears;

        @Override
        public void render(GameState head, GameState confirmed) {
        }

        @Override
        public void playEvents(List<CombatEvent> events, GameState state) {
        }

        @Override
        public void clear() {
            clears++;
            throw new NoSuchMethodError("the server changed under this build");
        }
    }

    private static final class Reports implements MatchTelemetry {
        private final List<String> steps = new ArrayList<>();
        private final List<Throwable> errors = new ArrayList<>();

        @Override
        public void teardownFailed(String step, Throwable error) {
            steps.add(step);
            errors.add(error);
        }
    }

    private static final class Still implements InputSource {
        @Override
        public Input sample() {
            return Input.NONE;
        }
    }

    private static GameState seed() {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        PlayerState b = g.players[1];
        a.x = -3.0;
        a.y = 64.0;
        a.health = 20f;
        a.maxHealth = 20f;
        b.x = 3.0;
        b.y = 64.0;
        b.health = 20f;
        b.maxHealth = 20f;
        return g;
    }

    private static MatchDriver driver(Transport transport, SimRenderer renderer,
                                      MatchTelemetry telemetry) {
        return new MatchDriver(transport, 0, Arena.flat(64.0), seed(), RING, new Still(), renderer,
                telemetry);
    }

    @Test
    void aRendererThatThrowsStillLetsTheTransportClose() {
        DeadTransport transport = new DeadTransport(false);
        RuntimeException boom = new IllegalStateException("the player logged out mid-despawn");
        Renderer renderer = new Renderer(boom);
        Reports reports = new Reports();

        driver(transport, renderer, reports).end();

        assertEquals(1, renderer.clears, "the renderer clear was never attempted");
        assertEquals(1, transport.closes,
                "transport.close() was skipped because renderer.clear() threw. The relay socket and"
                        + " its receive thread then outlive the match, and every ended match leaks"
                        + " one more. Teardown has to be total: a failure in one step is a report,"
                        + " not a reason to abandon the steps after it.");
        assertEquals(List.of("renderer clear"), reports.steps,
                "the renderer failure was swallowed silently - nothing in the log would ever say"
                        + " that this player's ATTACK_SPEED restore did not finish");
        assertSame(boom, reports.errors.get(0), "the report dropped the original cause");
    }

    @Test
    void aTransportThatThrowsDoesNotEscapeEnd() {
        DeadTransport transport = new DeadTransport(true);
        Renderer renderer = new Renderer(null);
        Reports reports = new Reports();

        driver(transport, renderer, reports).end();

        assertEquals(1, renderer.clears, "the renderer clear must run before the socket goes");
        assertEquals(List.of("transport close"), reports.steps);
    }

    @Test
    void bothStepsFailingStillEndsCleanlyAndReportsBoth() {
        DeadTransport transport = new DeadTransport(true);
        Renderer renderer = new Renderer(new IllegalStateException("no world"));
        Reports reports = new Reports();

        driver(transport, renderer, reports).end();

        assertEquals(List.of("renderer clear", "transport close"), reports.steps,
                "end() must attempt every step and report every failure, in order");
        assertEquals(1, transport.closes);
    }

    @Test
    void anErrorOutOfTheRendererIsAlsoContained() {
        DeadTransport transport = new DeadTransport(false);
        ErrorRenderer renderer = new ErrorRenderer();
        Reports reports = new Reports();

        driver(transport, renderer, reports).end();

        assertEquals(1, renderer.clears);
        assertEquals(1, transport.closes,
                "a LinkageError out of a renderer built against a different server build is exactly"
                        + " how a teardown dies without a RuntimeException. Catching only"
                        + " RuntimeException here leaks the socket on the one failure shape the"
                        + " version fence exists to survive.");
        assertEquals(List.of("renderer clear"), reports.steps);
    }

    @Test
    void aCleanTeardownReportsNothing() {
        DeadTransport transport = new DeadTransport(false);
        Renderer renderer = new Renderer(null);
        Reports reports = new Reports();

        MatchDriver driver = driver(transport, renderer, reports);
        assertFalse(driver.ended());
        driver.end();

        assertTrue(reports.steps.isEmpty(), "a clean teardown must not log a failure");
        assertEquals(1, renderer.clears);
        assertEquals(1, transport.closes);
        assertTrue(driver.ended());
    }

    @Test
    void endingTwiceClosesTheTransportOnce() {
        DeadTransport transport = new DeadTransport(false);
        Renderer renderer = new Renderer(null);
        Reports reports = new Reports();

        MatchDriver driver = driver(transport, renderer, reports);
        driver.end();
        driver.end();

        assertEquals(1, transport.closes,
                "end() is reachable from more than one exit path now that every teardown route is"
                        + " funnelled through one method, so a second call has to be a no-op rather"
                        + " than a second close of a socket another match may already have taken");
        assertEquals(1, renderer.clears);
        assertTrue(reports.steps.isEmpty());
    }
}
