package me.nootnoot.limbo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToByteEncoder;
import java.util.List;

/**
 * The Minecraft framing layer: each packet on the wire is {@code VarInt(length)} followed by that many
 * bytes (the packet id + payload). No compression — fine for a localhost stub (we never send Set
 * Compression). The decoder emits one body {@link ByteBuf} per complete frame; the encoder length-
 * prefixes each outbound body.
 */
public final class VarIntFrameCodec {

    private VarIntFrameCodec() {
    }

    public static final class Decoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            in.markReaderIndex();
            int length = 0;
            int pos = 0;
            while (true) {
                if (!in.isReadable()) {
                    in.resetReaderIndex();
                    return;
                }
                byte b = in.readByte();
                length |= (b & 0x7F) << pos;
                pos += 7;
                if ((b & 0x80) == 0) {
                    break;
                }
                if (pos >= 35) {
                    throw new CorruptedFrameException("length VarInt too big");
                }
            }
            if (length < 0) {
                throw new CorruptedFrameException("negative frame length");
            }
            if (in.readableBytes() < length) {
                in.resetReaderIndex();
                return;
            }
            out.add(in.readRetainedSlice(length));
        }
    }

    public static final class Encoder extends MessageToByteEncoder<ByteBuf> {
        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
            Proto.writeVarInt(out, msg.readableBytes());
            out.writeBytes(msg);
        }
    }
}
