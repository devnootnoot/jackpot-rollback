package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EdgeModSetupAddressTest {

    private static final byte[] TOKEN = new byte[]{9, 8, 7, 6, 5, 4, 3, 2, 1};

    @Test
    void theSetupHeaderCarriesTheSessionSlotTokenAndRelayTheModDialsWith() {
        ByteBuffer b = ByteBuffer.wrap(
                EdgeDevKit.address(0x0123456789ABCDEFL, 1, TOKEN, "relay.example", 7777));
        assertEquals(0x0123456789ABCDEFL, b.getLong(),
                "the mod reads the session id straight out of the setup bytes; the edge reads it"
                        + " off the assignment instead, which is why this header could be all"
                        + " zeroes while only edges consumed it");
        assertEquals(1, b.get());
        byte[] token = new byte[b.getShort() & 0xFFFF];
        b.get(token);
        assertArrayEquals(TOKEN, token,
                "the per-slot token is what the mod replays in its Hello - a zero-length one is"
                        + " rejected by the relay, so the handoff would die at the first packet");
        byte[] host = new byte[b.getShort() & 0xFFFF];
        b.get(host);
        assertEquals("relay.example", new String(host, StandardCharsets.UTF_8));
        assertEquals(7777, b.getInt());
        assertEquals(0, b.remaining(),
                "MatchSetupCodec puts the round count immediately after the relay port, so this"
                        + " header must end exactly here");
    }

    @Test
    void anUnaddressedHeaderIsStillTheAllZeroOneTheEdgePathExpects() {
        ByteBuffer b = ByteBuffer.wrap(EdgeDevKit.address(0L, 0, null, "", 0));
        assertEquals(0L, b.getLong());
        assertEquals(0, b.get());
        assertEquals(0, b.getShort());
        assertEquals(0, b.getShort());
        assertEquals(0, b.getInt());
        assertEquals(0, b.remaining());
    }
}
