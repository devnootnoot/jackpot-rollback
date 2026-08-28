package me.nootnoot.limbo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

final class ArenaChunkTranscoder {

    private ArenaChunkTranscoder() {
    }

    static byte[] transcode(byte[] in, int sectionCount, int targetProtocol, BlockStateRemap remap) {
        boolean targetHasFluid = targetProtocol >= 775;
        ByteBuf out = Unpooled.buffer(in.length + 16);
        int[] pos = {0};
        try {
            for (int s = 0; s < sectionCount; s++) {
                int blockCount = readShort(in, pos);
                readShort(in, pos);
                out.writeShort(blockCount);
                if (targetHasFluid) {
                    out.writeShort(0);
                }
                remapContainer(in, pos, out, remap, 4096);
                skipContainer(in, pos, 64);
                out.writeByte(0);
                writeVarInt(out, 0);
            }
            byte[] result = new byte[out.readableBytes()];
            out.readBytes(result);
            return result;
        } catch (RuntimeException e) {
            return null;
        } finally {
            out.release();
        }
    }

    private static void remapContainer(byte[] in, int[] pos, ByteBuf out, BlockStateRemap remap, int entries) {
        int bits = in[pos[0]++] & 0xFF;
        out.writeByte(bits);
        if (bits == 0) {
            writeVarInt(out, remap.map(readVarInt(in, pos)));
            return;
        }
        int indirectMax = entries == 4096 ? 8 : 3;
        if (bits <= indirectMax) {
            int count = readVarInt(in, pos);
            writeVarInt(out, count);
            for (int i = 0; i < count; i++) {
                writeVarInt(out, remap.map(readVarInt(in, pos)));
            }
            int longs = longsFor(bits, entries);
            for (int i = 0; i < longs; i++) {
                out.writeLong(readLong(in, pos));
            }
        } else {
            int longs = longsFor(bits, entries);
            long[] data = new long[longs];
            for (int i = 0; i < longs; i++) {
                data[i] = readLong(in, pos);
            }
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1L;
            long[] outData = new long[longs];
            for (int idx = 0; idx < entries; idx++) {
                int li = idx / perLong;
                int off = (idx % perLong) * bits;
                int v = (int) ((data[li] >>> off) & mask);
                outData[li] |= ((long) remap.map(v) & mask) << off;
            }
            for (long v : outData) {
                out.writeLong(v);
            }
        }
    }

    private static void skipContainer(byte[] in, int[] pos, int entries) {
        int bits = in[pos[0]++] & 0xFF;
        if (bits == 0) {
            readVarInt(in, pos);
            return;
        }
        int indirectMax = entries == 4096 ? 8 : 3;
        if (bits <= indirectMax) {
            int count = readVarInt(in, pos);
            for (int i = 0; i < count; i++) {
                readVarInt(in, pos);
            }
        }
        pos[0] += longsFor(bits, entries) * 8;
    }

    private static int longsFor(int bits, int entries) {
        if (bits == 0) {
            return 0;
        }
        int perLong = 64 / bits;
        return (entries + perLong - 1) / perLong;
    }

    private static int readShort(byte[] b, int[] p) {
        int v = ((b[p[0]] & 0xFF) << 8) | (b[p[0] + 1] & 0xFF);
        p[0] += 2;
        return v;
    }

    private static long readLong(byte[] b, int[] p) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[p[0]++] & 0xFF);
        }
        return v;
    }

    private static int readVarInt(byte[] b, int[] p) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int x = b[p[0]++] & 0xFF;
            value |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new RuntimeException("varint too long");
    }

    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }
}
