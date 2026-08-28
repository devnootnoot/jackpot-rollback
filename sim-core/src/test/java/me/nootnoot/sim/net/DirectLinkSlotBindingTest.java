package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DirectLinkSlotBindingTest {
    private static final long SESSION = 0x51075L;

    private static final byte[] SECRET = "a-shared-per-session-link-token!".getBytes();

    private static long counter;

    private static byte[] frame(byte[] payload) {
        return LinkFrame.encode(SESSION, 1, counter++, SECRET, payload);
    }

    @Test
    void theSharedSecretItselfIsNeverPutOnTheWire() throws Exception {
        try (DirectLink link = new DirectLink(0);
             DatagramSocket peer = new DatagramSocket()) {

            Transport local = link.open(SESSION, 0,
                    new InetSocketAddress("127.0.0.1", peer.getLocalPort()), SECRET);
            try {
                byte[] buf = new byte[DirectLink.MAX_PACKET];
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                peer.setSoTimeout(2000);
                peer.receive(dp);
                byte[] body = Arrays.copyOfRange(buf, DirectLink.HEADER_BYTES, dp.getLength());
                Message.Hello hello = (Message.Hello) Protocol.decode(body);

                assertFalse(MessageDigest.isEqual(SECRET, hello.token()),
                        "the HELLO used to carry the raw shared secret, so anyone who saw one"
                                + " datagram held the whole of the binding rule for both slots");
                assertTrue(MessageDigest.isEqual(SlotTokens.derive(SECRET, SESSION, 0),
                        hello.token()),
                        "slot 0 must present HMAC(secret, label || sessionId || 0), the same"
                                + " derivation RelayServer verifies");
            } finally {
                local.close();
            }
        }
    }

    @Test
    void aTokenMintedForTheOTHERSlotDoesNotBindThisOne() throws Exception {
        try (DirectLink link = new DirectLink(0);
             DatagramSocket impostor = new DatagramSocket()) {

            Transport local = link.open(SESSION, 0,
                    new InetSocketAddress("127.0.0.1", impostor.getLocalPort()), SECRET);
            try {
                InetSocketAddress to = new InetSocketAddress("127.0.0.1", link.port());
                byte[] wrongSlot = frame(Protocol.encode(new Message.Hello(SESSION, 1,
                        Protocol.VERSION, SlotTokens.derive(SECRET, SESSION, 0))));
                for (int i = 0; i < 5; i++) {
                    impostor.send(new DatagramPacket(wrongSlot, wrongSlot.length, to));
                }
                byte[] speech = frame(Protocol.encode(new Message.Heartbeat(9L)));
                for (int i = 0; i < 5; i++) {
                    impostor.send(new DatagramPacket(speech, speech.length, to));
                }
                Thread.sleep(300);

                assertNull(poll(local),
                        "slot 1 was bound by replaying slot 0's token. A single shared bearer token"
                                + " let either observed HELLO speak for the other player");
                assertTrue(link.badSlotTokens() > 0, "and it was not even counted");
            } finally {
                local.close();
            }
        }
    }

    @Test
    void aSecretTooShortToVouchForAnythingIsRefusedRatherThanQuietlyAcceptingEveryone() throws Exception {
        try (DirectLink link = new DirectLink(0)) {
            InetSocketAddress peer = new InetSocketAddress("127.0.0.1", 1);
            assertThrows(IllegalArgumentException.class,
                    () -> link.open(SESSION, 0, peer, null),
                    "a null secret used to become Message.EMPTY_TOKEN, which compares equal to the"
                            + " empty token every stranger sends");
            assertThrows(IllegalArgumentException.class,
                    () -> link.open(SESSION, 0, peer, new byte[]{1, 2, 3}));
            assertEquals(0, link.openSessions(), "neither refusal may leave a channel behind");
        }
    }

    private static Message poll(Transport t) {
        for (byte[] b : t.receive()) {
            return Protocol.decode(b);
        }
        return null;
    }
}
