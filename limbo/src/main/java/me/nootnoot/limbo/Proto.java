package me.nootnoot.limbo;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Minecraft wire primitives on a Netty {@link ByteBuf}: VarInt, String, UUID. */
final class Proto {

    private Proto() {
    }

    static int readVarInt(ByteBuf buf) {
        int value = 0;
        int pos = 0;
        byte b;
        do {
            b = buf.readByte();
            value |= (b & 0x7F) << pos;
            pos += 7;
            if (pos >= 35) {
                throw new IllegalStateException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return value;
    }

    static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    static final int MAX_STRING_BYTES = 32767;

    static String readString(ByteBuf buf) {
        int len = readVarInt(buf);
        if (len < 0 || len > MAX_STRING_BYTES || len > buf.readableBytes()) {
            throw new IllegalStateException("string length out of bounds: " + len);
        }
        byte[] bytes = new byte[len];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    static void writeString(ByteBuf buf, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    static UUID readUuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    static void writeUuid(ByteBuf buf, UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
    }
}
