package me.nootnoot.sim.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.state.Input;

public final class Protocol {
    private static final byte HELLO = 1;
    private static final byte INPUT = 2;
    private static final byte CHECKSUM = 3;
    private static final byte ABORT = 4;
    private static final byte SNAPSHOT = 5;
    private static final byte HEARTBEAT = 6;
    private static final byte FINISH = 7;
    private static final byte CHAT = 8;
    private static final byte CONTAINER = 9;

    public static final int MAX_INPUTS_PER_PACKET = 48;
    public static final int MAX_SNAPSHOT_BOXES = 16384;
    public static final int MAX_CHAT_BYTES = 512;
    public static final int MAX_CONTAINER_BYTES = 8192;

    public static final int MAX_HELLO_TOKEN = 64;

    public static final int MIN_HELLO_TOKEN = 8;

    public static final int MAX_PEER_DELAY_ALLOWANCE = 4;

    public static final int CHECKSUM_REV = 184;

    public static final int VERSION = (InputCodec.BYTES << 8) | (CHECKSUM_REV & 0xFF);

    public static final int ABORT_VERSION_MISMATCH = 1001;

    private Protocol() {
    }

    public static byte[] encode(Message m) {
        if (m instanceof Message.Hello h) {
            byte[] token = h.token() == null ? Message.EMPTY_TOKEN : h.token();
            int tokenLen = Math.min(token.length, MAX_HELLO_TOKEN);
            ByteBuffer b = ByteBuffer.allocate(1 + 8 + 4 + 4 + 2 + tokenLen);
            b.put(HELLO).putLong(h.sessionId()).putInt(h.slot()).putInt(h.protocolVersion())
                    .putShort((short) tokenLen).put(token, 0, tokenLen);
            return b.array();
        } else if (m instanceof Message.InputFrames in) {
            int count = in.inputs().size();
            ByteBuffer b = ByteBuffer.allocate(1 + 4 + 4 + 2 + 2 + count * InputCodec.BYTES);
            b.put(INPUT).putInt(in.baseFrame()).putInt(in.ack())
                    .putShort((short) in.frameAdvantage()).putShort((short) count);
            for (Input i : in.inputs()) {
                InputCodec.write(b, i);
            }
            return b.array();
        } else if (m instanceof Message.Checksum c) {
            ByteBuffer b = ByteBuffer.allocate(1 + 4 + 8);
            b.put(CHECKSUM).putInt(c.frame()).putLong(c.value());
            return b.array();
        } else if (m instanceof Message.Abort a) {
            ByteBuffer b = ByteBuffer.allocate(1 + 4);
            b.put(ABORT).putInt(a.code());
            return b.array();
        } else if (m instanceof Message.Snapshot s) {
            int n = s.boxes().length;
            ByteBuffer b = ByteBuffer.allocate(1 + 8 + 4 + n * 6 * 8);
            b.put(SNAPSHOT).putDouble(s.groundY()).putInt(n);
            for (double[] box : s.boxes()) {
                for (int k = 0; k < 6; k++) {
                    b.putDouble(box[k]);
                }
            }
            return b.array();
        } else if (m instanceof Message.Heartbeat h) {
            ByteBuffer b = ByteBuffer.allocate(1 + 8);
            b.put(HEARTBEAT).putLong(h.nonce());
            return b.array();
        } else if (m instanceof Message.Finish f) {
            ByteBuffer b = ByteBuffer.allocate(1 + 4 + 4 + 4);
            b.put(FINISH).putInt(f.winnerSlot()).putInt(f.winsP0()).putInt(f.winsP1());
            return b.array();
        } else if (m instanceof Message.Chat c) {
            byte[] text = c.text().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocate(1 + 4 + text.length);
            b.put(CHAT).putInt(text.length).put(text);
            return b.array();
        } else if (m instanceof Message.Container c) {
            byte[] data = c.data();
            ByteBuffer b = ByteBuffer.allocate(1 + 4 + data.length);
            b.put(CONTAINER).putInt(data.length).put(data);
            return b.array();
        }
        throw new IllegalArgumentException("unknown message: " + m);
    }

    public static Message decode(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data);
        byte type = b.get();
        switch (type) {
            case HELLO: {
                long sessionId = b.getLong();
                int slot = b.getInt();
                int version = b.getInt();
                int tokenLen = b.getShort() & 0xFFFF;
                if (tokenLen > MAX_HELLO_TOKEN || tokenLen > b.remaining()) {
                    throw new IllegalArgumentException("hello token length out of bounds: " + tokenLen);
                }
                byte[] token = new byte[tokenLen];
                b.get(token);
                return new Message.Hello(sessionId, slot, version, token);
            }
            case INPUT: {
                int base = b.getInt();
                int ack = b.getInt();
                int frameAdvantage = b.getShort();
                int count = b.getShort() & 0xFFFF;
                if (count > MAX_INPUTS_PER_PACKET || (long) count * InputCodec.BYTES > b.remaining()) {
                    throw new IllegalArgumentException("input count out of bounds: " + count);
                }
                List<Input> inputs = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    inputs.add(InputCodec.read(b));
                }
                return new Message.InputFrames(base, inputs, ack, frameAdvantage);
            }
            case CHECKSUM:
                return new Message.Checksum(b.getInt(), b.getLong());
            case ABORT:
                return new Message.Abort(b.getInt());
            case SNAPSHOT: {
                double groundY = b.getDouble();
                int n = b.getInt();
                if (n < 0 || n > MAX_SNAPSHOT_BOXES || (long) n * 6 * 8 > b.remaining()) {
                    throw new IllegalArgumentException("snapshot box count out of bounds: " + n);
                }
                double[][] boxes = new double[n][6];
                for (int i = 0; i < n; i++) {
                    for (int k = 0; k < 6; k++) {
                        boxes[i][k] = b.getDouble();
                    }
                }
                return new Message.Snapshot(groundY, boxes);
            }
            case HEARTBEAT:
                return new Message.Heartbeat(b.getLong());
            case FINISH:
                return new Message.Finish(b.getInt(), b.getInt(), b.getInt());
            case CHAT: {
                int len = b.getInt();
                if (len < 0 || len > MAX_CHAT_BYTES || len > b.remaining()) {
                    throw new IllegalArgumentException("chat length out of bounds: " + len);
                }
                byte[] text = new byte[len];
                b.get(text);
                return new Message.Chat(new String(text, java.nio.charset.StandardCharsets.UTF_8));
            }
            case CONTAINER: {
                int len = b.getInt();
                if (len < 0 || len > MAX_CONTAINER_BYTES || len > b.remaining()) {
                    throw new IllegalArgumentException("container length out of bounds: " + len);
                }
                byte[] body = new byte[len];
                b.get(body);
                return new Message.Container(body);
            }
            default:
                throw new IllegalArgumentException("unknown packet type: " + type);
        }
    }

    public static boolean isHello(byte[] data, int len) {
        return len >= 1 && data[0] == HELLO;
    }

    public static boolean isWellFormed(byte[] data, int len) {
        if (len < 1) {
            return false;
        }
        switch (data[0]) {
            case HELLO: {
                int fixed = 1 + 8 + 4 + 4 + 2;
                if (len < fixed) {
                    return false;
                }
                int tokenLen = ((data[17] & 0xFF) << 8) | (data[18] & 0xFF);
                return tokenLen <= MAX_HELLO_TOKEN && len == fixed + tokenLen;
            }
            case CHECKSUM:
                return len == 1 + 4 + 8;
            case ABORT:
                return len == 1 + 4;
            case HEARTBEAT:
                return len == 1 + 8;
            case FINISH:
                return len == 1 + 4 + 4 + 4;
            case INPUT: {
                if (len < 1 + 4 + 4 + 2 + 2) {
                    return false;
                }
                int count = ((data[11] & 0xFF) << 8) | (data[12] & 0xFF);
                return count <= MAX_INPUTS_PER_PACKET && len == 13 + count * InputCodec.BYTES;
            }
            case SNAPSHOT: {
                if (len < 1 + 8 + 4) {
                    return false;
                }
                int n = intAt(data, 9);
                return n >= 0 && n <= MAX_SNAPSHOT_BOXES && len == 13 + n * 48;
            }
            case CHAT:
            case CONTAINER: {
                if (len < 1 + 4) {
                    return false;
                }
                int l = intAt(data, 1);
                int max = data[0] == CHAT ? MAX_CHAT_BYTES : MAX_CONTAINER_BYTES;
                return l >= 0 && l <= max && len == 5 + l;
            }
            default:
                return false;
        }
    }

    private static int intAt(byte[] d, int o) {
        return ((d[o] & 0xFF) << 24) | ((d[o + 1] & 0xFF) << 16) | ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }
}
