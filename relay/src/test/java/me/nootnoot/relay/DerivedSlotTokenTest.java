package me.nootnoot.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.SlotTokens;
import me.nootnoot.sim.net.UdpTransport;
import org.junit.jupiter.api.Test;

class DerivedSlotTokenTest {

    private static final byte[] SECRET = "a-relay-and-core-share-this".getBytes(StandardCharsets.UTF_8);

    @Test
    void aTokenTheBrokerDerivedBindsItsSlot() throws Exception {
        RelayServer relay = new RelayServer(0, true, SECRET);
        assertTrue(relay.verifiesDerivedSlotTokens(),
                "a relay handed a slot secret must verify, not pin");
        relay.start();
        try {
            SocketAddress addr = new InetSocketAddress("127.0.0.1", relay.port());
            long session = 0x51D2A77CL;
            UdpTransport t0 = new UdpTransport(addr, session, 0,
                    SlotTokens.derive(SECRET, session, 0));
            UdpTransport t1 = new UdpTransport(addr, session, 1,
                    SlotTokens.derive(SECRET, session, 1));
            try {
                Thread.sleep(200);
                t0.send(Protocol.encode(new Message.Heartbeat(0xABCDEFL)));

                assertNotNull(pollForHeartbeat(t1, 1500),
                        "both peers presented the token core derives for their slot, so the relay"
                                + " must pair them with no control connection and no first-arrival"
                                + " pin at all");
                assertEquals(0L, relay.metrics().unauthorized.get());
                assertEquals(0, relay.pinnedTokenCount(),
                        "verification replaces pinning; a relay that still pins is still deciding"
                                + " who owns a slot by who arrived first");
            } finally {
                t0.close();
                t1.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void firstArrivalNoLongerOwnsTheSlot() throws Exception {
        RelayServer relay = new RelayServer(0, true, SECRET);
        relay.start();
        try {
            SocketAddress addr = new InetSocketAddress("127.0.0.1", relay.port());
            long session = 0x7E57ED10L;
            byte[] invented = new byte[SlotTokens.BYTES];
            Arrays.fill(invented, (byte) 0x5A);

            DatagramSocket impostor = new DatagramSocket();
            UdpTransport real = null;
            try {
                byte[] hello = Protocol.encode(
                        new Message.Hello(session, 0, Protocol.VERSION, invented));
                impostor.send(new DatagramPacket(hello, hello.length, addr));
                Thread.sleep(250);

                assertEquals(0, relay.sessionCount(),
                        "under trust on first use this HELLO would have opened the session and"
                                + " pinned slot 0 to whoever sent it. Nothing about it is derivable"
                                + " from the secret, so it may not exist at all.");
                assertTrue(relay.metrics().unauthorized.get() > 0,
                        "the refused bind must be counted so the runbook alert can see it");
                assertEquals(0, relay.pinnedTokenCount());

                real = new UdpTransport(addr, session, 1, SlotTokens.derive(SECRET, session, 1));
                Thread.sleep(200);
                byte[] injected = Protocol.encode(new Message.Heartbeat(0xBADBADL));
                impostor.send(new DatagramPacket(injected, injected.length, addr));
                assertNull(pollForHeartbeat(real, 500),
                        "and having failed to bind, it cannot inject a frame for the player either");
            } finally {
                if (real != null) {
                    real.close();
                }
                impostor.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void aTokenMintedForTheOtherSlotIsRefused() throws Exception {
        RelayServer relay = new RelayServer(0, true, SECRET);
        relay.start();
        try {
            SocketAddress addr = new InetSocketAddress("127.0.0.1", relay.port());
            long session = 0x0FF5107L;
            DatagramSocket swapped = new DatagramSocket();
            try {
                byte[] hello = Protocol.encode(new Message.Hello(session, 0, Protocol.VERSION,
                        SlotTokens.derive(SECRET, session, 1)));
                swapped.send(new DatagramPacket(hello, hello.length, addr));
                Thread.sleep(250);

                assertEquals(0, relay.sessionCount(),
                        "a duelist holding their own honest token must not be able to seat"
                                + " themselves in the slot that speaks for their opponent");
                assertTrue(relay.metrics().unauthorized.get() > 0);
            } finally {
                swapped.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void withoutASecretTheRelayIsStillTrustOnFirstUse() throws Exception {
        RelayServer relay = new RelayServer(0, true, (byte[]) null);
        assertTrue(relay.requiresSlotAuthentication());
        assertTrue(!relay.verifiesDerivedSlotTokens(),
                "no secret configured means the old pinning path, which the startup banner warns"
                        + " about; this test exists so that fallback stays a deliberate choice");
        relay.stop();
    }

    private static Message pollForHeartbeat(UdpTransport t, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<byte[]> packets = t.receive();
            for (byte[] p : packets) {
                if (Protocol.decode(p) instanceof Message.Heartbeat m) {
                    return m;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }
}
