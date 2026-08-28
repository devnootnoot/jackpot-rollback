package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class UdpTransportInboxIsByteBoundedTest {

    private static final int BOXES = 340;

    private static final int PACKET_BYTES = 13 + BOXES * 48;

    private static final long FLOOD_DEADLINE_MILLIS = 20_000L;

    @Test
    void aFloodOfLargePacketsIsBoundedByBytesNotByCount() throws Exception {
        try (DatagramSocket fakeRelay = new DatagramSocket()) {
            InetSocketAddress relayAddress =
                    new InetSocketAddress("127.0.0.1", fakeRelay.getLocalPort());
            UdpTransport t = new UdpTransport(relayAddress, 7L, 0);
            try {
                InetSocketAddress client = awaitHello(fakeRelay);
                byte[] big = bigSnapshot();

                long deadline = System.currentTimeMillis() + FLOOD_DEADLINE_MILLIS;
                while (t.inboxOverflowPackets() == 0 && System.currentTimeMillis() < deadline) {
                    for (int i = 0; i < 50; i++) {
                        fakeRelay.send(new DatagramPacket(big, big.length, client));
                    }
                    Thread.sleep(20);
                }

                assertTrue(t.inboxOverflowPackets() > 0,
                        "the flood never filled the inbox, so the cap was not exercised");

                List<byte[]> drained = t.receive();
                long held = 0;
                for (byte[] b : drained) {
                    held += b.length;
                }
                assertTrue(held <= UdpTransport.MAX_INBOX_BYTES + PACKET_BYTES,
                        "UdpTransport is the transport production actually uses, and it bounded its"
                                + " inbox by PACKET COUNT only. At MAX_INBOX (" + UdpTransport.MAX_INBOX
                                + ") packets of " + PACKET_BYTES + " bytes that is "
                                + (UdpTransport.MAX_INBOX * (long) PACKET_BYTES / (1024 * 1024))
                                + " MB of heap a peer chooses the size of, while the direct path had"
                                + " capped the same queue in bytes all along. It held " + held
                                + " bytes.");
                assertTrue(drained.size() < UdpTransport.MAX_INBOX,
                        "the byte cap has to bind before the packet cap for packets this large,"
                                + " otherwise it is not doing anything");
            } finally {
                t.close();
            }
        }
    }

    private static InetSocketAddress awaitHello(DatagramSocket relay) throws Exception {
        relay.setSoTimeout(4000);
        byte[] buf = new byte[2048];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        relay.receive(dp);
        return (InetSocketAddress) dp.getSocketAddress();
    }

    private static byte[] bigSnapshot() {
        double[][] boxes = new double[BOXES][6];
        return Protocol.encode(new Message.Snapshot(0.0, boxes));
    }
}
