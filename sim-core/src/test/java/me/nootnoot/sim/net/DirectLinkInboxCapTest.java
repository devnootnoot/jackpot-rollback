package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class DirectLinkInboxCapTest {

    private static final byte[] TOKEN = "a-shared-per-session-link-token!".getBytes();

    private static final long SESSION = 4242L;

    private static final int FLOOD_PACKETS = 40_000;

    @Test
    void aBoundPeerCannotGrowTheInboxWithoutBound() throws Exception {
        try (DirectLink link = new DirectLink(0); DatagramSocket peer = new DatagramSocket()) {
            Transport local = link.open(SESSION, 0,
                    new InetSocketAddress("127.0.0.1", peer.getLocalPort()), TOKEN);
            try {
                byte[] hello = frame(Protocol.encode(
                        new Message.Hello(SESSION, 1, Protocol.VERSION,
                                SlotTokens.derive(TOKEN, SESSION, 1))));
                InetSocketAddress to = new InetSocketAddress("127.0.0.1", link.port());
                for (int i = 0; i < 20 && link.acceptedPackets() == 0; i++) {
                    peer.send(new DatagramPacket(hello, hello.length, to));
                    Thread.sleep(25);
                }
                assertTrue(link.acceptedPackets() > 0, "the scripted peer never bound");

                byte[] beat = Protocol.encode(new Message.Heartbeat(1L));
                for (int i = 0; i < FLOOD_PACKETS; i++) {
                    byte[] junk = frame(beat);
                    peer.send(new DatagramPacket(junk, junk.length, to));
                }
                Thread.sleep(600);

                int depth = local.receive().size();
                assertTrue(depth <= DirectLink.MAX_INBOX_PACKETS,
                        "the direct-link inbox is an unbounded ConcurrentLinkedQueue: a bound peer"
                                + " (or anyone who can spoof its address to the open UDP port)"
                                + " queued " + depth + " packets that the sim thread had not"
                                + " drained yet, so the edge's heap grows with whatever the peer"
                                + " chooses to send. The relay path caps this at"
                                + " UdpTransport.MAX_INBOX; this path had no cap at all.");
                assertTrue(link.inboxOverflowPackets() + link.rateLimitedPackets() > 0,
                        "the flood was never actually refused, so the cap was not exercised");
            } finally {
                local.close();
            }
        }
    }

    private static long counter;

    private static byte[] frame(byte[] payload) {
        return LinkFrame.encode(SESSION, 1, counter++, TOKEN, payload);
    }
}
