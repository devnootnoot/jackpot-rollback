package me.nootnoot.limbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ProtoStringBoundsTest {

    private static ByteBuf claiming(int length, int actualBytes) {
        ByteBuf buf = Unpooled.buffer();
        Proto.writeVarInt(buf, length);
        for (int i = 0; i < actualBytes; i++) {
            buf.writeByte('a');
        }
        return buf;
    }

    @Test
    void aLengthTheFrameCannotBackIsRefusedBeforeAnythingIsAllocated() {
        ByteBuf buf = claiming(Integer.MAX_VALUE, 4);
        try {
            assertThrows(IllegalStateException.class, () -> Proto.readString(buf),
                    "the length is a VarInt an unauthenticated client puts on the wire, and it was"
                            + " sizing the array before anything checked it against the bytes that"
                            + " actually arrived: one small handshake packet asks for a two"
                            + " gigabyte allocation");
        } finally {
            buf.release();
        }
    }

    @Test
    void aLengthPastTheProtocolsOwnCeilingIsRefused() {
        ByteBuf buf = claiming(Proto.MAX_STRING_BYTES + 1, Proto.MAX_STRING_BYTES + 1);
        try {
            assertThrows(IllegalStateException.class, () -> Proto.readString(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void anOrdinaryStringStillRoundTrips() {
        ByteBuf buf = Unpooled.buffer();
        try {
            Proto.writeString(buf, "pvphq.com");
            assertEquals("pvphq.com", Proto.readString(buf));
            assertEquals(0, buf.readableBytes(), "and it consumed exactly its own bytes");
        } finally {
            buf.release();
        }
    }

    @Test
    void aMultiByteStringIsMeasuredInBytesNotCharacters() {
        String text = "ünïcödé";
        ByteBuf buf = Unpooled.buffer();
        try {
            Proto.writeString(buf, text);
            assertEquals(text.getBytes(StandardCharsets.UTF_8).length + 1, buf.readableBytes());
            assertEquals(text, Proto.readString(buf));
        } finally {
            buf.release();
        }
    }
}
