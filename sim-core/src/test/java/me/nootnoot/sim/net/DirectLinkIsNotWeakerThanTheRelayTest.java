package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class DirectLinkIsNotWeakerThanTheRelayTest {

    private static final byte[] TOKEN = "a-shared-per-session-link-token!".getBytes();

    private static final long SESSION = 4242L;

    @Test
    void aFloodAtASessionNobodyOpenedIsRateLimitedRatherThanMerelyCounted() throws Exception {
        try (DirectLink link = new DirectLink(0);
             DatagramSocket flood = new DatagramSocket()) {

            InetSocketAddress to = new InetSocketAddress("127.0.0.1", link.port());
            byte[] wire = frame(SESSION + 1, Protocol.encode(new Message.Heartbeat(7L)));
            int sent = (int) DirectLink.SRC_PKT_BURST + 4000;
            for (int i = 0; i < sent; i++) {
                flood.send(new DatagramPacket(wire, wire.length, to));
            }
            waitForQuiet(link, sent);

            assertTrue(link.rateLimitedPackets() > 0,
                    "the relay runs allowGlobal and allowSource BEFORE it looks a session up, so a"
                            + " source that names a session nobody opened is throttled. The direct"
                            + " path counted such packets as unknownSession and did no throttling"
                            + " at all, which made the whole endpoint free to hammer from one"
                            + " socket. rateLimited=" + link.rateLimitedPackets()
                            + " unknownSession=" + link.unknownSessionPackets());
            assertTrue(link.rateLimitedPackets() > link.unknownSessionPackets(),
                    "and the throttle has to be the thing that stopped most of them, or it is"
                            + " sitting behind the work it exists to prevent");
        }
    }

    @Test
    void anHonestPeerIsNeverThrottledOffTheLink() throws Exception {
        try (DirectLink link = new DirectLink(0);
             DatagramSocket peer = new DatagramSocket()) {

            Transport local = link.open(SESSION, 0,
                    new InetSocketAddress("127.0.0.1", peer.getLocalPort()), TOKEN);
            try {
                InetSocketAddress to = new InetSocketAddress("127.0.0.1", link.port());
                byte[] helloPayload = Protocol.encode(new Message.Hello(
                        SESSION, 1, Protocol.VERSION, SlotTokens.derive(TOKEN, SESSION, 1)));
                for (int i = 0; i < 40 && link.acceptedPackets() == 0; i++) {
                    byte[] hello = frame(SESSION, helloPayload);
                    peer.send(new DatagramPacket(hello, hello.length, to));
                    Thread.sleep(25);
                }
                assertTrue(link.acceptedPackets() > 0, "the scripted peer never bound");
                local.receive();

                long limitedBefore = link.rateLimitedPackets();
                byte[] beatPayload = Protocol.encode(new Message.Heartbeat(11L));
                for (int i = 0; i < 40; i++) {
                    byte[] beat = frame(SESSION, beatPayload);
                    peer.send(new DatagramPacket(beat, beat.length, to));
                    Thread.sleep(5);
                }
                Thread.sleep(300);

                assertEquals(limitedBefore, link.rateLimitedPackets(),
                        "a duel is two peers at 20 Hz plus keepalives; a bucket that clips that is"
                            + " a self-inflicted desync and worse than no bucket at all");
                assertNotNull(pollFor(local), "and the traffic actually arrived");
            } finally {
                local.close();
            }
        }
    }

    @Test
    void aPeerOnADifferentBuildFailsTheSessionInsteadOfLettingItTimeOut() throws Exception {
        try (DirectLink link = new DirectLink(0);
             DatagramSocket peer = new DatagramSocket()) {

            Transport local = link.open(SESSION, 0,
                    new InetSocketAddress("127.0.0.1", peer.getLocalPort()), TOKEN);
            try {
                InetSocketAddress to = new InetSocketAddress("127.0.0.1", link.port());
                byte[] stalePayload = Protocol.encode(new Message.Hello(
                        SESSION, 1, Protocol.VERSION - 1, SlotTokens.derive(TOKEN, SESSION, 1)));
                for (int i = 0; i < 40 && link.versionMismatchedHellos() == 0; i++) {
                    byte[] stale = frame(SESSION, stalePayload);
                    peer.send(new DatagramPacket(stale, stale.length, to));
                    Thread.sleep(25);
                }
                assertTrue(link.versionMismatchedHellos() > 0, "the stale hello never landed");

                Message m = pollFor(local);
                assertEquals(new Message.Abort(Protocol.ABORT_VERSION_MISMATCH), m,
                        "the relay aborts both peers of a version-mismatched session so the"
                                + " operator gets a named partial deploy. The direct path dropped"
                                + " the hello silently and both edges then sat in a match that"
                                + " never started until a peer timeout blamed the network. The"
                                + " abort is raised locally rather than sent, so an unauthenticated"
                                + " source can never turn this into a reflector.");
                assertEquals(1, countAborts(local) + 1,
                        "and it is raised once, not once per retried hello");
                assertNotNull(link.versionMismatchDiagnosis(),
                        "the diagnosis string is what the operator actually reads");
            } finally {
                local.close();
            }
        }
    }

    private static int countAborts(Transport t) throws InterruptedException {
        Thread.sleep(300);
        int n = 0;
        for (byte[] b : t.receive()) {
            if (Protocol.decode(b) instanceof Message.Abort) {
                n++;
            }
        }
        return n;
    }

    private static void waitForQuiet(DirectLink link, int sent) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            long seen = link.rateLimitedPackets() + link.unknownSessionPackets()
                    + link.rejectedPackets() + link.acceptedPackets();
            if (seen >= sent * 0.5) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private static long counter;

    private static byte[] frame(long session, byte[] payload) {
        return LinkFrame.encode(session, 1, counter++, TOKEN, payload);
    }

    private static Message pollFor(Transport t) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            List<byte[]> got = t.receive();
            if (!got.isEmpty()) {
                return Protocol.decode(got.get(0));
            }
            Thread.sleep(20);
        }
        return null;
    }
}
