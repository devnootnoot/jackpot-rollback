package me.nootnoot.limbo;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads a capture file into a list of packet bodies (id + payload, WITHOUT the length prefix). The
 * file is a sequence of wire frames — {@code VarInt(length)} then {@code length} bytes — exactly as
 * captured off the socket. Replayed verbatim during the matching connection phase, so the version-
 * specific bytes are real, not reconstructed.
 *
 * <p>Capture tip: connect a real 1.21.11 client to a real server (offline mode, no compression) through
 * a logging TCP proxy, and split the clientbound stream into the configuration-phase frames
 * ({@code config.bin}) and the initial play-phase frames up to spawn ({@code play.bin}).
 */
public final class CapturedPhase {

    private CapturedPhase() {
    }

    public static List<byte[]> load(Path path) throws IOException {
        List<byte[]> out = new ArrayList<>();
        if (!Files.exists(path)) {
            return out;
        }
        ByteBuf buf = Unpooled.wrappedBuffer(Files.readAllBytes(path));
        try {
            while (buf.isReadable()) {
                int length = Proto.readVarInt(buf);
                byte[] body = new byte[length];
                buf.readBytes(body);
                out.add(body);
            }
        } finally {
            buf.release();
        }
        return out;
    }
}
