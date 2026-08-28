package me.nootnoot.sim.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public final class ControlProtocol {
    public static final byte AUTHORIZE = 1;
    public static final byte RESULT = 2;
    public static final byte AUTHORIZE_SETUP = 3;

    private static final int MAX_TOKEN = 256;
    private static final int MAX_BOXES = 1 << 20;
    private static final int MAX_FRAME0 = 1 << 20;
    private static final int MAX_SETUP = 1 << 22;
    private static final int MAX_ARENA = 1 << 24;

    public sealed interface ControlMessage permits Authorize, AuthorizeSetup, Result {
    }

    public record Authorize(long sessionId, long expiryEpochMs, byte[] slot0Token, byte[] slot1Token,
                            double arenaGroundY, double[][] arenaBoxes, byte[] frame0) implements ControlMessage {
    }

    public record AuthorizeSetup(long sessionId, long expiryEpochMs, byte[] slot0Token,
                                 byte[] slot1Token, byte[] matchSetup, byte[] arenaBlob)
            implements ControlMessage {
    }

    public record Result(long sessionId, boolean decided, int winnerSlot, int winsP0, int winsP1,
                         int confirmedFrame, boolean violation) implements ControlMessage {
    }

    private ControlProtocol() {
    }

    public static byte[] encode(ControlMessage m) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(256);
        try (DataOutputStream o = new DataOutputStream(bos)) {
            if (m instanceof Authorize a) {
                o.writeByte(AUTHORIZE);
                o.writeLong(a.sessionId());
                o.writeLong(a.expiryEpochMs());
                writeBytes(o, a.slot0Token());
                writeBytes(o, a.slot1Token());
                o.writeDouble(a.arenaGroundY());
                o.writeInt(a.arenaBoxes().length);
                for (double[] box : a.arenaBoxes()) {
                    for (int k = 0; k < 6; k++) {
                        o.writeDouble(box[k]);
                    }
                }
                writeBytes(o, a.frame0());
            } else if (m instanceof AuthorizeSetup a) {
                o.writeByte(AUTHORIZE_SETUP);
                o.writeLong(a.sessionId());
                o.writeLong(a.expiryEpochMs());
                writeBytes(o, a.slot0Token());
                writeBytes(o, a.slot1Token());
                writeBytes(o, a.matchSetup());
                writeBytes(o, a.arenaBlob());
            } else if (m instanceof Result r) {
                o.writeByte(RESULT);
                o.writeLong(r.sessionId());
                o.writeBoolean(r.decided());
                o.writeInt(r.winnerSlot());
                o.writeInt(r.winsP0());
                o.writeInt(r.winsP1());
                o.writeInt(r.confirmedFrame());
                o.writeBoolean(r.violation());
            } else {
                throw new IllegalArgumentException("unknown control message");
            }
            o.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public static ControlMessage decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            byte type = in.readByte();
            switch (type) {
                case AUTHORIZE -> {
                    long sessionId = in.readLong();
                    long expiry = in.readLong();
                    byte[] t0 = readBytes(in, MAX_TOKEN);
                    byte[] t1 = readBytes(in, MAX_TOKEN);
                    double groundY = in.readDouble();
                    int n = in.readInt();
                    if (n < 0 || n > MAX_BOXES || (long) n * 6 * 8 > in.available()) {
                        throw new IllegalArgumentException("arena box count out of bounds: " + n);
                    }
                    double[][] boxes = new double[n][6];
                    for (int i = 0; i < n; i++) {
                        for (int k = 0; k < 6; k++) {
                            boxes[i][k] = in.readDouble();
                        }
                    }
                    byte[] frame0 = readBytes(in, MAX_FRAME0);
                    return new Authorize(sessionId, expiry, t0, t1, groundY, boxes, frame0);
                }
                case AUTHORIZE_SETUP -> {
                    long sessionId = in.readLong();
                    long expiry = in.readLong();
                    byte[] t0 = readBytes(in, MAX_TOKEN);
                    byte[] t1 = readBytes(in, MAX_TOKEN);
                    byte[] setup = readBytes(in, MAX_SETUP);
                    byte[] arena = readBytes(in, MAX_ARENA);
                    return new AuthorizeSetup(sessionId, expiry, t0, t1, setup, arena);
                }
                case RESULT -> {
                    return new Result(in.readLong(), in.readBoolean(), in.readInt(), in.readInt(),
                            in.readInt(), in.readInt(), in.readBoolean());
                }
                default -> throw new IllegalArgumentException("unknown control message type: " + type);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeBytes(DataOutputStream o, byte[] b) throws IOException {
        o.writeInt(b.length);
        o.write(b);
    }

    private static byte[] readBytes(DataInputStream in, int max) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > max || len > in.available()) {
            throw new IllegalArgumentException("length out of bounds: " + len);
        }
        byte[] b = new byte[len];
        in.readFully(b);
        return b;
    }
}
