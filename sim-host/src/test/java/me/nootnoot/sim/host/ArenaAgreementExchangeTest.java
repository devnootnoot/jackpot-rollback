package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.nootnoot.sim.ArenaAgreement;
import me.nootnoot.sim.net.LoopbackNetwork;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.Transport;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ArenaAgreementExchangeTest {

    private static final int RING = 256;
    private static final int FRAMES = 80;

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

    private static final class Idle implements InputSource {
        @Override
        public Input sample() {
            return Input.NONE;
        }
    }

    @Test
    void twoHostsWithTheSameArenaNeverDisagree() {
        Exchange run = run(agreement(99L, "arena.bin"), agreement(99L, "arena.bin"));
        assertTrue(run.a.peerSeen(), "slot 0 never saw the peer agreement");
        assertTrue(run.b.peerSeen(), "slot 1 never saw the peer agreement");
        assertNull(run.a.abortReason());
        assertNull(run.b.abortReason());
        assertNotNull(run.a.peer());
    }

    @Test
    void aDifferentArenaIsDetectedByBothHosts() {
        Exchange run = run(agreement(99L, "arena.bin"), agreement(100L, "other.bin"));
        assertNotNull(run.a.abortReason());
        assertNotNull(run.b.abortReason());
        assertTrue(run.a.abortReason().startsWith("arena mismatch: "), run.a.abortReason());
        assertTrue(run.a.abortReason().contains("arena.bin"), run.a.abortReason());
        assertTrue(run.a.abortReason().contains("other.bin"), run.a.abortReason());
    }

    @Test
    void anUnrelatedContainerBlobIsLeftForTheHostToHandle() {
        ArenaAgreementExchange exchange = new ArenaAgreementExchange(agreement(99L, "arena.bin"));
        assertFalse(exchange.offer(new byte[]{9, 9, 9, 9}));
        assertFalse(exchange.peerSeen());
        assertTrue(exchange.offer(agreement(99L, "arena.bin").encode()));
        assertTrue(exchange.peerSeen());
    }

    @Test
    void theAgreementIsResentOnItsIntervalNotEveryTick() {
        LoopbackNetwork net = new LoopbackNetwork(11L, 0, 0, 0.0);
        MatchDriver driver = driver(net, 0);
        ArenaAgreementExchange exchange = new ArenaAgreementExchange(agreement(99L, "arena.bin"),
                20, 40, 3);
        Transport peer = net.endpoint(1);
        int sent = 0;
        for (int i = 0; i < 200; i++) {
            exchange.pump(driver);
            sent += countAgreements(peer.receive());
            driver.tick();
            net.step();
        }
        assertEquals(10, sent, "expected one agreement every 20 ticks over 200 ticks");
        assertFalse(exchange.peerSeen());
        assertNull(exchange.abortReason());
    }

    @Test
    void aPeerThatNeverSendsAnAgreementFailsClosed() {
        LoopbackNetwork net = new LoopbackNetwork(11L, 0, 0, 0.0);
        MatchDriver driver = driver(net, 0);
        ArenaAgreementExchange exchange = new ArenaAgreementExchange(agreement(99L, "arena.bin"),
                20, 40, 3, 60);
        for (int i = 0; i < 60; i++) {
            exchange.pump(driver);
            driver.tick();
            net.step();
        }
        assertFalse(exchange.agreementMissing(), "the window must not close early");
        assertNull(exchange.abortReason());

        exchange.pump(driver);
        assertTrue(exchange.agreementMissing(), "silence past the window must fail closed");
        assertNotNull(exchange.abortReason());
        assertTrue(exchange.abortReason().startsWith("arena agreement missing"), exchange.abortReason());
    }

    @Test
    void anAgreementInsideTheWindowKeepsTheMatchAlive() {
        LoopbackNetwork net = new LoopbackNetwork(11L, 0, 0, 0.0);
        MatchDriver driver = driver(net, 0);
        ArenaAgreementExchange exchange = new ArenaAgreementExchange(agreement(99L, "arena.bin"),
                20, 40, 3, 60);
        for (int i = 0; i < 200; i++) {
            if (i == 10) {
                assertTrue(exchange.offer(agreement(99L, "arena.bin").encode()));
            }
            exchange.pump(driver);
            driver.tick();
            net.step();
        }
        assertFalse(exchange.agreementMissing());
        assertNull(exchange.abortReason());
    }

    @Test
    void aRefusedBlobDoesNotCountAsAnAgreement() {
        LoopbackNetwork net = new LoopbackNetwork(11L, 0, 0, 0.0);
        MatchDriver driver = driver(net, 0);
        ArenaAgreementExchange exchange = new ArenaAgreementExchange(agreement(99L, "arena.bin"),
                20, 40, 3, 30);
        for (int i = 0; i < 31; i++) {
            assertFalse(exchange.offer(new byte[]{9, 9, 9, 9}));
            exchange.pump(driver);
            driver.tick();
            net.step();
        }
        assertTrue(exchange.agreementMissing(),
                "junk blobs must not satisfy the agreement window");
        assertNotNull(exchange.abortReason());
    }

    private static int countAgreements(List<byte[]> packets) {
        int found = 0;
        for (byte[] raw : packets) {
            if (!Protocol.isWellFormed(raw, raw.length)) {
                continue;
            }
            if (Protocol.decode(raw) instanceof Message.Container c
                    && ArenaAgreement.decode(c.data()) != null) {
                found++;
            }
        }
        return found;
    }

    private record Exchange(ArenaAgreementExchange a, ArenaAgreementExchange b) {
    }

    private static Exchange run(ArenaAgreement localA, ArenaAgreement localB) {
        LoopbackNetwork net = new LoopbackNetwork(7L, 0, 0, 0.0);
        MatchDriver a = driver(net, 0);
        MatchDriver b = driver(net, 1);
        ArenaAgreementExchange exA = new ArenaAgreementExchange(localA);
        ArenaAgreementExchange exB = new ArenaAgreementExchange(localB);
        for (int i = 0; i < FRAMES; i++) {
            for (byte[] blob : a.pollContainer()) {
                exA.offer(blob);
            }
            for (byte[] blob : b.pollContainer()) {
                exB.offer(blob);
            }
            exA.pump(a);
            exB.pump(b);
            a.tick();
            b.tick();
            net.step();
        }
        return new Exchange(exA, exB);
    }

    private static MatchDriver driver(LoopbackNetwork net, int slot) {
        return new MatchDriver(net.endpoint(slot), slot, Arena.flat(64.0), seed(), RING,
                new Idle(), new NoRender());
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
        return g;
    }

    @Test
    void anAgreementFromAnOlderJarIsNamedAsAJarSkewNotAsASilentPeer() {
        ArenaAgreementExchange exchange = new ArenaAgreementExchange(agreement(99L, "arena.bin"));
        byte[] older = agreement(99L, "arena.bin").encode();
        older[4] = 1;

        assertTrue(exchange.offer(older),
                "the blob IS an arena agreement and must be consumed, not handed on to the"
                        + " container channel as an unknown op");
        assertTrue(exchange.peerSeen());
        assertNotNull(exchange.abortReason());
        assertTrue(exchange.abortReason().contains("version 1"), exchange.abortReason());
        assertTrue(exchange.abortReason().contains("not the same build"), exchange.abortReason());
        assertNull(exchange.peer(),
                "there is no comparable peer agreement to show; only its version is known");
        assertFalse(exchange.agreementMissing(),
                "a peer that answered with the wrong version did not go silent, and reporting a"
                        + " timeout instead would send an operator hunting a network fault");
    }

    @Test
    void aBlobThatIsNotAnAgreementAtAllIsStillDeclined() {
        ArenaAgreementExchange exchange = new ArenaAgreementExchange(agreement(99L, "arena.bin"));
        assertFalse(exchange.offer(new byte[]{9, 9, 9, 9, 9}));
        assertFalse(exchange.peerSeen());
        assertNull(exchange.mismatch());
    }

    private static ArenaAgreement agreement(long hash, String source) {
        return new ArenaAgreement(hash, -66.0, -4.0, -60.0, 0.0, 4.0, -60.0, 0.0,
                ArenaAgreement.NO_STATE, source);
    }
}
