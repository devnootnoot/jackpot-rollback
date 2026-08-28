package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.InetSocketAddress;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class DirectLinkTest {

    private static final byte[] TOKEN = "a-shared-per-session-link-token!".getBytes();
    private static final byte[] WRONG = "a-different-per-session-token!!!".getBytes();

    private static final long DELIVERY_TIMEOUT_MILLIS = 3000L;

    private static final long BINDING_MILLIS = 800L;

    @Test
    void twoEdgesExchangePacketsWithNoRelayInBetween() throws Exception {
        try (DirectLink a = new DirectLink(0); DirectLink b = new DirectLink(0)) {
            Transport ta = a.open(77L, 0, loopback(b.port()), TOKEN);
            Transport tb = b.open(77L, 1, loopback(a.port()), TOKEN);
            try {
                awaitBinding();
                ta.send(Protocol.encode(new Message.Heartbeat(0xFEEDL)));
                assertEquals(new Message.Heartbeat(0xFEEDL), pollFor(tb));

                tb.send(Protocol.encode(new Message.Checksum(9, 0xABCDL)));
                assertEquals(new Message.Checksum(9, 0xABCDL), pollFor(ta));
            } finally {
                ta.close();
                tb.close();
            }
        }
    }

    @Test
    void aFullNetSessionRunsOverTheDirectLink() throws Exception {
        try (DirectLink a = new DirectLink(0); DirectLink b = new DirectLink(0)) {
            Transport ta = a.open(5L, 0, loopback(b.port()), TOKEN);
            Transport tb = b.open(5L, 1, loopback(a.port()), TOKEN);
            try {
                Thread.sleep(200);
                NetSession sa = session(ta, 0);
                NetSession sb = session(tb, 1);
                for (int i = 0; i < 120; i++) {
                    sa.update(Input.NONE);
                    sb.update(Input.NONE);
                    Thread.sleep(5);
                }
                assertFalse(sa.aborted(), "slot 0 aborted: " + sa.abortReason());
                assertFalse(sb.aborted(), "slot 1 aborted: " + sb.abortReason());
                assertTrue(sa.confirmedFrame() > 0, "no frame was ever confirmed over the link");
                assertTrue(sb.confirmedFrame() > 0, "no frame was ever confirmed over the link");
            } finally {
                ta.close();
                tb.close();
            }
        }
    }

    @Test
    void aPeerWithTheWrongLinkTokenNeverBinds() throws Exception {
        try (DirectLink a = new DirectLink(0); DirectLink b = new DirectLink(0)) {
            Transport ta = a.open(31L, 0, loopback(b.port()), TOKEN);
            Transport tb = b.open(31L, 1, loopback(a.port()), WRONG);
            try {
                for (int i = 0; i < 20; i++) {
                    tb.send(Protocol.encode(new Message.Heartbeat(1L)));
                    Thread.sleep(50);
                }
                assertNull(poll(ta), "a peer that cannot prove the broker introduced it was let in");
            } finally {
                ta.close();
                tb.close();
            }
        }
    }

    @Test
    void aHelloIsConsumedByTheLinkAndNeverReachesTheSession() throws Exception {
        try (DirectLink a = new DirectLink(0); DirectLink b = new DirectLink(0)) {
            Transport ta = a.open(12L, 0, loopback(b.port()), TOKEN);
            Transport tb = b.open(12L, 1, loopback(a.port()), TOKEN);
            try {
                Thread.sleep(1200);
                assertNull(poll(ta), "a keepalive Hello reached the session and would have held"
                        + " NetSession.ticksSincePeerPacket at zero forever");
                assertNull(poll(tb));
                assertTrue(a.acceptedPackets() > 0, "no keepalive was accepted at all");
            } finally {
                ta.close();
                tb.close();
            }
        }
    }

    @Test
    void aSecondMatchOnTheSamePortIsADistinctChannel() throws Exception {
        try (DirectLink a = new DirectLink(0); DirectLink b = new DirectLink(0)) {
            Transport a1 = a.open(100L, 0, loopback(b.port()), TOKEN);
            Transport a2 = a.open(200L, 0, loopback(b.port()), TOKEN);
            Transport b1 = b.open(100L, 1, loopback(a.port()), TOKEN);
            Transport b2 = b.open(200L, 1, loopback(a.port()), TOKEN);
            try {
                awaitBinding();
                a1.send(Protocol.encode(new Message.Heartbeat(1L)));
                a2.send(Protocol.encode(new Message.Heartbeat(2L)));
                assertEquals(new Message.Heartbeat(1L), pollFor(b1));
                assertEquals(new Message.Heartbeat(2L), pollFor(b2));
                assertNull(poll(b1));
                assertNull(poll(b2));
            } finally {
                a1.close();
                a2.close();
                b1.close();
                b2.close();
            }
        }
    }

    @Test
    void aSessionCannotBeOpenedTwiceOnOneEndpoint() throws Exception {
        try (DirectLink a = new DirectLink(0)) {
            Transport first = a.open(8L, 0, loopback(a.port() + 1), TOKEN);
            assertThrows(IllegalStateException.class,
                    () -> a.open(8L, 0, loopback(a.port() + 1), TOKEN));
            first.close();
            assertEquals(0, a.openSessions());
        }
    }

    @Test
    void aFrameForAnUnopenedSessionIsCounted() throws Exception {
        try (DirectLink a = new DirectLink(0); DirectLink b = new DirectLink(0)) {
            Transport tb = b.open(404L, 1, loopback(a.port()), TOKEN);
            try {
                Thread.sleep(300);
                assertTrue(a.unknownSessionPackets() > 0);
                assertEquals(0, a.openSessions());
            } finally {
                tb.close();
            }
        }
    }

    private static NetSession session(Transport t, int slot) {
        Arena arena = Arena.flat(64.0);
        return new NetSession(t, slot, arena, HarnessScenarios.duel(arena), 512, 1);
    }

    private static void awaitBinding() throws InterruptedException {
        Thread.sleep(BINDING_MILLIS);
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress("127.0.0.1", port);
    }

    private static Message poll(Transport t) {
        List<byte[]> got = t.receive();
        return got.isEmpty() ? null : Protocol.decode(got.get(0));
    }

    private static Message pollFor(Transport t) throws InterruptedException {
        long deadline = System.currentTimeMillis() + DELIVERY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Message m = poll(t);
            if (m != null) {
                return m;
            }
            Thread.sleep(20);
        }
        return fail("nothing arrived over the direct link within "
                + DELIVERY_TIMEOUT_MILLIS + "ms");
    }
}
