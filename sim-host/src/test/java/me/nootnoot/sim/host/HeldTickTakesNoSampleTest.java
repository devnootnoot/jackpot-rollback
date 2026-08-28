package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.Transport;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class HeldTickTakesNoSampleTest {

    private static final int RING = 512;

    private static final double GROUND_Y = 64.0;

    private static final int HELD_TICKS = 6;

    private static final class Peer implements Transport {

        private final Deque<byte[]> inbox = new ArrayDeque<>();
        private final List<Input> sent = new ArrayList<>();

        void deliver(int frame, Input in) {
            inbox.add(Protocol.encode(new Message.InputFrames(frame, List.of(in), frame - 1, 0)));
        }

        @Override
        public void send(byte[] packet) {
            if (Protocol.decode(packet) instanceof Message.InputFrames frames) {
                sent.addAll(frames.inputs());
            }
        }

        @Override
        public List<byte[]> receive() {
            List<byte[]> out = new ArrayList<>(inbox);
            inbox.clear();
            return out;
        }

        boolean sawTheClick() {
            for (Input in : sent) {
                if (in.usePress() && in.clicks().use() > 0 && in.dropItem()) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Blind implements SimRenderer {

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

    private static final class OneClick implements InputSource {

        private int samples;
        private boolean spent;

        @Override
        public Input sample() {
            samples++;
            if (spent) {
                return Input.NONE;
            }
            spent = true;
            return new Input(false, false, false, false, false, false, false, false, true,
                    0f, 0f, 0)
                    .withUsePress(true)
                    .withDrop(true, false)
                    .withClicks(new Clicks(0, 1, 1, 0, 0));
        }
    }

    private static GameState seed() {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        PlayerState b = g.players[1];
        a.x = -3.0;
        a.y = GROUND_Y;
        a.health = 20f;
        a.maxHealth = 20f;
        b.x = 3.0;
        b.y = GROUND_Y;
        b.health = 20f;
        b.maxHealth = 20f;
        return g;
    }

    private static MatchDriver driver(Peer peer, InputSource source) {
        return new MatchDriver(peer, 0, Arena.flat(GROUND_Y), seed(), RING, source, new Blind());
    }

    @Test
    void aTickTheSessionHoldsNeverAsksTheInputSourceForASample() {
        Peer peer = new Peer();
        OneClick source = new OneClick();
        MatchDriver driver = driver(peer, source);

        for (int t = 0; t < HELD_TICKS; t++) {
            assertTrue(driver.tick(), "tick " + t + " ended the match");
        }

        assertEquals(0, driver.head(),
                "the pre-start hold ran no frame, so the fixture is not exercising a hold");
        assertEquals(0, source.samples,
                "the driver took " + source.samples + " samples for frames that never ran. A"
                        + " sample is a DESTRUCTIVE read of the tick's discrete channels"
                        + " (usePress, clicks, dropItem, invAction, blockAction, crystalHit) - the"
                        + " press counters are drained and the intent queues polled - so every"
                        + " sample handed to a session that holds is a click the player made and"
                        + " the match never saw. Ask the session to run the frame and let it pull"
                        + " the sample only when it will.");
    }

    @Test
    void theClickMadeDuringTheHoldIsTheOneTheFirstRunningFrameCarries() {
        Peer peer = new Peer();
        OneClick source = new OneClick();
        MatchDriver driver = driver(peer, source);

        for (int t = 0; t < HELD_TICKS; t++) {
            assertTrue(driver.tick(), "tick " + t + " ended the match");
        }

        int running = 12;
        for (int t = 0; t < running; t++) {
            peer.deliver(t, Input.NONE);
            assertTrue(driver.tick(), "tick " + t + " after the peer arrived ended the match");
        }

        assertTrue(driver.head() > 0, "the peer arrived but the session never started");
        assertEquals(running, source.samples,
                "one sample per tick that runs a frame, and not one for any of the "
                        + HELD_TICKS + " ticks that held");
        assertTrue(peer.sawTheClick(),
                "the player's right-click and drop never reached the wire. It was sampled on a"
                        + " tick the session held, and a held tick runs no frame, so the sample"
                        + " went nowhere: the discrete channels are edge-triggered and cannot be"
                        + " re-read on the next tick.");
    }
}
