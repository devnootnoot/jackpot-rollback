package me.nootnoot.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.List;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.UdpTransport;
import org.junit.jupiter.api.Test;

class RelayUdpTest {
    @Test
    void relayForwardsPacketsBetweenPairedPeers() throws Exception {
        RelayServer relay = new RelayServer(0, false);
        relay.start();
        try {
            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            long session = 42L;
            UdpTransport t0 = new UdpTransport(relayAddr, session, 0);
            UdpTransport t1 = new UdpTransport(relayAddr, session, 1);
            try {
                Thread.sleep(150);

                t0.send(Protocol.encode(new Message.Heartbeat(0xABCDEFL)));

                Message received = pollFor(t1, 2000);
                assertNotNull(received, "peer never received the forwarded packet");
                assertEquals(new Message.Heartbeat(0xABCDEFL), received);
            } finally {
                t0.close();
                t1.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void relayForwardsAFullCatchUpInputPacket() throws Exception {
        RelayServer relay = new RelayServer(0, false);
        relay.start();
        try {
            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            long session = 77L;
            UdpTransport t0 = new UdpTransport(relayAddr, session, 0);
            UdpTransport t1 = new UdpTransport(relayAddr, session, 1);
            try {
                Thread.sleep(150);
                java.util.List<me.nootnoot.sim.state.Input> run = new java.util.ArrayList<>();
                for (int i = 0; i < Protocol.MAX_INPUTS_PER_PACKET; i++) {
                    run.add(me.nootnoot.sim.state.Input.NONE);
                }
                byte[] big = Protocol.encode(new Message.InputFrames(0, run, -1, 0));
                assertTrue(big.length > 2048,
                        "a max catch-up input run must exceed the old 2048 receive buffer");
                t0.send(big);

                byte[] got = pollForType(t1, (byte) 2, 2000);
                assertNotNull(got, "relay dropped a full-size catch-up input packet (buffer truncation)");
                assertEquals(big.length, got.length, "forwarded packet must be byte-complete, not truncated");
            } finally {
                t0.close();
                t1.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void relayAbortsMismatchedProtocolVersions() throws Exception {
        RelayServer relay = new RelayServer(0, false);
        relay.start();
        try {
            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            long session = 99L;
            UdpTransport t0 = new UdpTransport(relayAddr, session, 0);
            DatagramSocket raw = new DatagramSocket();
            try {
                Thread.sleep(100);

                byte[] hello = Protocol.encode(new Message.Hello(session, 1, Protocol.VERSION + 1));
                raw.send(new DatagramPacket(hello, hello.length, relayAddr));

                Message received = pollForAbort(t0, 2000);
                assertNotNull(received, "peer never received the version-mismatch abort");
                assertEquals(Protocol.ABORT_VERSION_MISMATCH, ((Message.Abort) received).code());
            } finally {
                t0.close();
                raw.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void protocolRejectsHostilePackets() {
        byte[] snap = new byte[13];
        snap[0] = 5;
        putInt(snap, 9, Integer.MAX_VALUE);
        assertFalse(Protocol.isWellFormed(snap, snap.length));
        assertThrows(RuntimeException.class, () -> Protocol.decode(snap));

        byte[] chat = new byte[5];
        chat[0] = 8;
        putInt(chat, 1, Integer.MAX_VALUE);
        assertFalse(Protocol.isWellFormed(chat, chat.length));
        assertThrows(RuntimeException.class, () -> Protocol.decode(chat));

        byte[] input = new byte[13];
        input[0] = 2;
        input[11] = (byte) 0xEA;
        input[12] = (byte) 0x60;
        assertFalse(Protocol.isWellFormed(input, input.length));
        assertThrows(RuntimeException.class, () -> Protocol.decode(input));

        assertFalse(Protocol.isWellFormed(new byte[]{99, 0, 0}, 3));
        assertFalse(Protocol.isWellFormed(new byte[0], 0));

        byte[] hb = Protocol.encode(new Message.Heartbeat(1L));
        assertTrue(Protocol.isWellFormed(hb, hb.length));
    }

    @Test
    void relayDropsGarbageAndUnknownSourcesButForwardsLegit() throws Exception {
        RelayServer relay = new RelayServer(0, false);
        relay.start();
        try {
            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            long session = 7L;
            UdpTransport t0 = new UdpTransport(relayAddr, session, 0);
            UdpTransport t1 = new UdpTransport(relayAddr, session, 1);
            DatagramSocket stranger = new DatagramSocket();
            try {
                Thread.sleep(150);

                t0.send(new byte[]{99, 1, 2, 3, 4, 5});
                byte[] badInput = new byte[13];
                badInput[0] = 2;
                badInput[11] = (byte) 0xEA;
                badInput[12] = (byte) 0x60;
                t0.send(badInput);

                byte[] hb = Protocol.encode(new Message.Heartbeat(1L));
                stranger.send(new DatagramPacket(hb, hb.length, relayAddr));

                t0.send(Protocol.encode(new Message.Heartbeat(0xABCDEFL)));

                Message received = pollFor(t1, 2000);
                assertNotNull(received, "legit packet must still forward");
                assertEquals(new Message.Heartbeat(0xABCDEFL), received);

                assertTrue(relay.metrics().invalid.get() >= 2, "garbage must count invalid");
                assertTrue(relay.metrics().unknownSource.get() >= 1, "stranger must count unknownSource");
            } finally {
                t0.close();
                t1.close();
                stranger.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void relayRateLimitsAFloodFromOneSource() throws Exception {
        RelayServer relay = new RelayServer(0, false);
        relay.start();
        try {
            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            long session = 13L;
            UdpTransport t0 = new UdpTransport(relayAddr, session, 0);
            UdpTransport t1 = new UdpTransport(relayAddr, session, 1);
            try {
                Thread.sleep(150);
                byte[] hb = Protocol.encode(new Message.Heartbeat(5L));
                for (int i = 0; i < 3000; i++) {
                    t0.send(hb);
                }
                Thread.sleep(400);
                long forwarded = relay.metrics().forwarded.get();
                long rateLimited = relay.metrics().rateLimited.get();
                assertTrue(rateLimited > 0, "a 3000-packet burst must hit the per-source cap");
                assertTrue(forwarded < 3000, "the relay must not forward more than it received (no amplification)");
            } finally {
                t0.close();
                t1.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void defaultRelayRefusesATokenlessSlotBind() throws Exception {
        RelayServer relay = new RelayServer(0);
        assertTrue(relay.requiresSlotAuthentication(), "authentication must be the shipped default");
        relay.start();
        try {
            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            long session = 4242L;
            UdpTransport t0 = new UdpTransport(relayAddr, session, 0);
            UdpTransport t1 = new UdpTransport(relayAddr, session, 1);
            try {
                Thread.sleep(200);
                t0.send(Protocol.encode(new Message.Heartbeat(0xABCDEFL)));

                assertNull(pollFor(t1, 600),
                        "an unauthenticated slot bind must not forward a single packet");
                assertTrue(relay.metrics().unauthorized.get() > 0, "the refused HELLO must be counted");
                assertEquals(0L, relay.metrics().hello.get(),
                        "no slot may be bound by a HELLO carrying no slot token");
            } finally {
                t0.close();
                t1.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void defaultRelayPinsTheSlotTokenAgainstAThiefWhoKnowsTheSessionId() throws Exception {
        RelayServer relay = new RelayServer(0);
        relay.start();
        try {
            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            long session = 8125L;
            UdpTransport t0 = new UdpTransport(relayAddr, session, 0, token((byte) 0x11));
            UdpTransport t1 = new UdpTransport(relayAddr, session, 1, token((byte) 0x22));
            DatagramSocket thief = new DatagramSocket();
            try {
                Thread.sleep(200);
                t0.send(Protocol.encode(new Message.Heartbeat(0xABCDEFL)));
                assertNotNull(pollFor(t1, 1500), "the legitimate slot bind must forward");

                byte[] forged = Protocol.encode(
                        new Message.Hello(session, 0, Protocol.VERSION, token((byte) 0x99)));
                thief.send(new DatagramPacket(forged, forged.length, relayAddr));
                Thread.sleep(200);
                byte[] injected = Protocol.encode(new Message.Heartbeat(0xBADBADL));
                thief.send(new DatagramPacket(injected, injected.length, relayAddr));

                assertNull(pollFor(t1, 600), "a wrong slot token must never bind or inject");
                assertTrue(relay.metrics().unauthorized.get() > 0, "the forged HELLO must be counted");
                assertTrue(relay.metrics().unknownSource.get() > 0,
                        "the thief's input must be dropped as an unbound source");
            } finally {
                t0.close();
                t1.close();
                thief.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void aSessionRefusedByTheCapLeavesNoPinnedTokenBehind() throws Exception {
        RelayServer relay = new RelayServer(0, true, 1);
        relay.start();
        try {
            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            UdpTransport t0 = new UdpTransport(relayAddr, 5001L, 0, token((byte) 0x11));
            UdpTransport t1 = new UdpTransport(relayAddr, 5001L, 1, token((byte) 0x22));
            DatagramSocket probe = new DatagramSocket();
            try {
                Thread.sleep(300);
                assertEquals(1, relay.sessionCount());

                byte[] hello = Protocol.encode(
                        new Message.Hello(5002L, 0, Protocol.VERSION, token((byte) 0x33)));
                probe.send(new DatagramPacket(hello, hello.length, relayAddr));
                Thread.sleep(300);

                assertTrue(relay.metrics().sessionCap.get() > 0, "the capped HELLO must be counted");
                assertEquals(1, relay.pinnedTokenCount(),
                        "a session the cap refused must not leave a token pinned forever");
                assertEquals(relay.sessionCount(), relay.pinnedTokenCount(),
                        "the pin table may never outgrow the session table");

                t0.send(Protocol.encode(new Message.Heartbeat(0xABCDEFL)));
                assertNotNull(pollFor(t1, 1500), "the live session must keep forwarding");
            } finally {
                t0.close();
                t1.close();
                probe.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void theCapReclaimsHalfOpenSessionsAndNeverThePairedOne() throws Exception {
        RelayServer relay = new RelayServer(0, true, 2);
        relay.start();
        try {
            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            UdpTransport live0 = new UdpTransport(relayAddr, 6001L, 0, token((byte) 0x11));
            UdpTransport live1 = new UdpTransport(relayAddr, 6001L, 1, token((byte) 0x22));
            DatagramSocket probe = new DatagramSocket();
            try {
                Thread.sleep(300);
                byte[] hello = Protocol.encode(
                        new Message.Hello(6002L, 0, Protocol.VERSION, token((byte) 0x33)));
                probe.send(new DatagramPacket(hello, hello.length, relayAddr));
                Thread.sleep(300);
                assertEquals(2, relay.sessionCount(), "the relay must now be at its session cap");

                Thread.sleep(3500);

                UdpTransport fresh0 = new UdpTransport(relayAddr, 6003L, 0, token((byte) 0x44));
                UdpTransport fresh1 = new UdpTransport(relayAddr, 6003L, 1, token((byte) 0x55));
                try {
                    Thread.sleep(300);
                    fresh0.send(Protocol.encode(new Message.Heartbeat(0xFEEDL)));
                    assertNotNull(pollFor(fresh1, 2000),
                            "an honest session must still get in at the cap by reclaiming a half-open one");
                    assertTrue(relay.metrics().reclaimed.get() > 0, "the half-open probe must be reclaimed");

                    live0.send(Protocol.encode(new Message.Heartbeat(0xABCDEFL)));
                    assertNotNull(pollFor(live1, 2000),
                            "the paired duel must never be the reclaim victim");
                    assertEquals(relay.sessionCount(), relay.pinnedTokenCount(),
                            "the pin table may never outgrow the session table");
                } finally {
                    fresh0.close();
                    fresh1.close();
                }
            } finally {
                live0.close();
                live1.close();
                probe.close();
            }
        } finally {
            relay.stop();
        }
    }

    private static byte[] token(byte fill) {
        byte[] t = new byte[32];
        Arrays.fill(t, fill);
        return t;
    }

    private static void putInt(byte[] d, int o, int v) {
        d[o] = (byte) (v >>> 24);
        d[o + 1] = (byte) (v >>> 16);
        d[o + 2] = (byte) (v >>> 8);
        d[o + 3] = (byte) v;
    }

    private static Message pollForAbort(UdpTransport t, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (byte[] p : t.receive()) {
                Message m = Protocol.decode(p);
                if (m instanceof Message.Abort) {
                    return m;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static byte[] pollForType(UdpTransport t, byte type, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (byte[] p : t.receive()) {
                if (p.length > 0 && p[0] == type) {
                    return p;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static Message pollFor(UdpTransport t, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            List<byte[]> packets = t.receive();
            for (byte[] p : packets) {
                Message m = Protocol.decode(p);
                if (m instanceof Message.Heartbeat) {
                    return m;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }
}
