package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class DirectLinkPinsTheProvenSocketTest {

    private static final byte[] TOKEN = "a-shared-per-session-link-token!".getBytes();

    private static final long SESSION = 909L;

    private static final long HONEST = 0x600DL;

    private static final long FORGED = 0xBADL;

    @Test
    void aDatagramFromAnotherPortOnTheSameHostIsNotThePeer() throws Exception {
        try (DirectLink link = new DirectLink(0);
             DatagramSocket peer = new DatagramSocket();
             DatagramSocket impostor = new DatagramSocket()) {

            Transport local = link.open(SESSION, 0,
                    new InetSocketAddress("127.0.0.1", peer.getLocalPort()), TOKEN);
            try {
                InetSocketAddress to = new InetSocketAddress("127.0.0.1", link.port());
                byte[] helloPayload = Protocol.encode(
                        new Message.Hello(SESSION, 1, Protocol.VERSION,
                                SlotTokens.derive(TOKEN, SESSION, 1)));
                for (int i = 0; i < 40 && link.acceptedPackets() == 0; i++) {
                    byte[] hello = frame(helloPayload);
                    peer.send(new DatagramPacket(hello, hello.length, to));
                    Thread.sleep(25);
                }
                assertTrue(link.acceptedPackets() > 0, "the scripted peer never bound");
                drain(local);

                byte[] forged = frame(Protocol.encode(new Message.Heartbeat(FORGED)));
                long rejectedBefore = link.rejectedPackets();
                for (int i = 0; i < 10; i++) {
                    impostor.send(new DatagramPacket(forged, forged.length, to));
                }
                Thread.sleep(300);

                assertNull(poll(local),
                        "Channel.admits authenticated a datagram by SOURCE IP alone, so any other"
                                + " socket on the peer's host - a co-tenant process, another player"
                                + " behind the same NAT, anything that can put that address in a"
                                + " source field - could inject inputs, a Finish or an Abort into a"
                                + " live match. The relay keys its forwarding table on the exact"
                                + " SocketAddress that presented a valid slot token; the direct"
                                + " path, which skips the relay entirely, must be at least that"
                                + " strong.");
                assertTrue(link.rejectedPackets() > rejectedBefore,
                        "the forged datagrams were not counted as rejected, so they went somewhere"
                                + " else rather than being refused");

                byte[] honest = frame(Protocol.encode(new Message.Heartbeat(HONEST)));
                peer.send(new DatagramPacket(honest, honest.length, to));
                assertEquals(new Message.Heartbeat(HONEST), pollFor(local),
                        "the socket that actually proved the token must still be able to talk");
            } finally {
                local.close();
            }
        }
    }

    @Test
    void onlyAValidTokenMovesThePinnedSocket() throws Exception {
        try (DirectLink link = new DirectLink(0);
             DatagramSocket peer = new DatagramSocket();
             DatagramSocket impostor = new DatagramSocket()) {

            Transport local = link.open(SESSION, 0,
                    new InetSocketAddress("127.0.0.1", peer.getLocalPort()), TOKEN);
            try {
                InetSocketAddress to = new InetSocketAddress("127.0.0.1", link.port());
                byte[] helloPayload = Protocol.encode(
                        new Message.Hello(SESSION, 1, Protocol.VERSION,
                                SlotTokens.derive(TOKEN, SESSION, 1)));
                for (int i = 0; i < 40 && link.acceptedPackets() == 0; i++) {
                    byte[] hello = frame(helloPayload);
                    peer.send(new DatagramPacket(hello, hello.length, to));
                    Thread.sleep(25);
                }
                assertTrue(link.acceptedPackets() > 0, "the scripted peer never bound");
                drain(local);

                byte[] wrongToken = frame(Protocol.encode(new Message.Hello(SESSION, 1,
                        Protocol.VERSION, SlotTokens.derive(
                                "a-different-per-session-token!!!".getBytes(), SESSION, 1))));
                for (int i = 0; i < 5; i++) {
                    impostor.send(new DatagramPacket(wrongToken, wrongToken.length, to));
                }
                byte[] forged = frame(Protocol.encode(new Message.Heartbeat(FORGED)));
                for (int i = 0; i < 5; i++) {
                    impostor.send(new DatagramPacket(forged, forged.length, to));
                }
                Thread.sleep(300);

                assertNull(poll(local),
                        "a Hello that fails the token check must not steal the pin from the socket"
                                + " that passed it");
                assertEquals(0, link.peerRebinds(),
                        "nothing legitimate rebound, so the rebind counter must be untouched");

                byte[] honest = frame(Protocol.encode(new Message.Heartbeat(HONEST)));
                peer.send(new DatagramPacket(honest, honest.length, to));
                assertNotNull(pollFor(local), "the real peer lost its channel to an impostor");
            } finally {
                local.close();
            }
        }
    }

    @Test
    void aDatagramCarryingThePinnedAddressButNoValidTagIsRefused() throws Exception {
        try (DirectLink link = new DirectLink(0);
             DatagramSocket peer = new DatagramSocket()) {

            Transport local = link.open(SESSION, 0,
                    new InetSocketAddress("127.0.0.1", peer.getLocalPort()), TOKEN);
            try {
                InetSocketAddress to = new InetSocketAddress("127.0.0.1", link.port());
                bind(link, peer, to);
                drain(local);

                byte[] forged = untaggedFrame(Protocol.encode(new Message.Heartbeat(FORGED)));
                for (int i = 0; i < 10; i++) {
                    peer.send(new DatagramPacket(forged, forged.length, to));
                }
                Thread.sleep(300);

                assertNull(poll(local),
                        "the pin is an address, and an address is the one thing an off-path"
                                + " attacker can put in a datagram for free. Once the peer's"
                                + " host:port is known - and the broker hands it to the other"
                                + " edge in clear - a spoofed source was enough to inject inputs,"
                                + " a Finish or an Abort into a live match. Every frame now"
                                + " carries HMAC(secret, magic||session||slot||counter||body),"
                                + " which is the same secret the slot tokens come from and which"
                                + " never goes on the wire.");
                assertTrue(link.badFrameTags() > 0,
                        "and the refusal has to be attributable, or an operator watching a match"
                                + " die sees only silence");

                byte[] honest = frame(Protocol.encode(new Message.Heartbeat(HONEST)));
                peer.send(new DatagramPacket(honest, honest.length, to));
                assertEquals(new Message.Heartbeat(HONEST), pollFor(local),
                        "the socket that holds the secret must still be able to talk");
            } finally {
                local.close();
            }
        }
    }

    @Test
    void aCapturedFrameReplayedBackAtTheLinkIsDeliveredOnlyOnce() throws Exception {
        try (DirectLink link = new DirectLink(0);
             DatagramSocket peer = new DatagramSocket()) {

            Transport local = link.open(SESSION, 0,
                    new InetSocketAddress("127.0.0.1", peer.getLocalPort()), TOKEN);
            try {
                InetSocketAddress to = new InetSocketAddress("127.0.0.1", link.port());
                bind(link, peer, to);
                drain(local);

                byte[] once = frame(Protocol.encode(new Message.Heartbeat(HONEST)));
                for (int i = 0; i < 8; i++) {
                    peer.send(new DatagramPacket(once, once.length, to));
                }
                Thread.sleep(300);

                assertEquals(1, count(local),
                        "a tag alone authenticates the bytes, not the moment: anyone who can"
                                + " record one datagram could hand it back to the link forever."
                                + " The counter in the header plus a "
                                + LinkFrame.REPLAY_WINDOW + "-frame window is what makes a"
                                + " captured Finish or a captured input burst worth nothing"
                                + " twice.");
                assertTrue(link.replayedFrames() >= 7,
                        "and the duplicates have to be counted as replays rather than folded into"
                                + " the generic rejected total");
            } finally {
                local.close();
            }
        }
    }

    private static void bind(DirectLink link, DatagramSocket peer, InetSocketAddress to)
            throws Exception {
        byte[] helloPayload = Protocol.encode(new Message.Hello(SESSION, 1, Protocol.VERSION,
                SlotTokens.derive(TOKEN, SESSION, 1)));
        for (int i = 0; i < 40 && link.acceptedPackets() == 0; i++) {
            byte[] hello = frame(helloPayload);
            peer.send(new DatagramPacket(hello, hello.length, to));
            Thread.sleep(25);
        }
        assertTrue(link.acceptedPackets() > 0, "the scripted peer never bound");
    }

    private static int count(Transport t) {
        return t.receive().size();
    }

    private static long counter;

    private static byte[] frame(byte[] payload) {
        return LinkFrame.encode(SESSION, 1, counter++, TOKEN, payload);
    }

    private static byte[] untaggedFrame(byte[] payload) {
        byte[] wire = LinkFrame.encode(SESSION, 1, counter++, TOKEN, payload);
        for (int i = LinkFrame.PREFIX_BYTES; i < LinkFrame.HEADER_BYTES; i++) {
            wire[i] = 0;
        }
        return wire;
    }

    private static void drain(Transport t) {
        t.receive();
    }

    private static Message poll(Transport t) {
        List<byte[]> got = t.receive();
        return got.isEmpty() ? null : Protocol.decode(got.get(0));
    }

    private static Message pollFor(Transport t) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            Message m = poll(t);
            if (m != null) {
                return m;
            }
            Thread.sleep(20);
        }
        return null;
    }
}
