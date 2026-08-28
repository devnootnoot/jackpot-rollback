package me.nootnoot.sim;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;

public record ArenaAgreement(long arenaHash, double groundY,
                             double x0, double y0, double z0,
                             double x1, double y1, double z1,
                             long stateChecksum,
                             String source) {

    public static final int MAGIC = 0x4A454152;
    public static final byte VERSION = 2;

    public static final long NO_STATE = 0L;

    private static final int HEADER_BYTES = 4 + 1 + 8 + 8 + 48 + 8 + 2;
    private static final int MAX_SOURCE_CHARS = 64;

    public static ArenaAgreement of(Arena arena,
                                    double x0, double y0, double z0,
                                    double x1, double y1, double z1,
                                    String source) {
        return new ArenaAgreement(ArenaHash.of(arena), arena.groundY, x0, y0, z0, x1, y1, z1,
                NO_STATE, source);
    }

    public static ArenaAgreement of(Arena arena, GameState state, String source) {
        if (state == null) {
            return of(arena, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, source);
        }
        PlayerState p0 = state.players[0];
        PlayerState p1 = state.players[1];
        return new ArenaAgreement(ArenaHash.of(arena), arena.groundY,
                p0.x, p0.y, p0.z, p1.x, p1.y, p1.z, Checksum.of(state), source);
    }

    public static int peerVersion(byte[] blob) {
        if (blob == null || blob.length < 5) {
            return -1;
        }
        ByteBuffer b = ByteBuffer.wrap(blob);
        return b.getInt() == MAGIC ? b.get() & 0xFF : -1;
    }

    public byte[] encode() {
        byte[] src = utf8(source);
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES + src.length);
        b.putInt(MAGIC);
        b.put(VERSION);
        b.putLong(arenaHash);
        b.putDouble(groundY);
        b.putDouble(x0);
        b.putDouble(y0);
        b.putDouble(z0);
        b.putDouble(x1);
        b.putDouble(y1);
        b.putDouble(z1);
        b.putLong(stateChecksum);
        b.putShort((short) src.length);
        b.put(src);
        return b.array();
    }

    public static ArenaAgreement decode(byte[] blob) {
        if (blob == null || blob.length < HEADER_BYTES) {
            return null;
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(blob);
            if (b.getInt() != MAGIC || b.get() != VERSION) {
                return null;
            }
            long hash = b.getLong();
            double groundY = b.getDouble();
            double x0 = b.getDouble();
            double y0 = b.getDouble();
            double z0 = b.getDouble();
            double x1 = b.getDouble();
            double y1 = b.getDouble();
            double z1 = b.getDouble();
            long stateChecksum = b.getLong();
            int len = b.getShort() & 0xFFFF;
            if (len > b.remaining()) {
                return null;
            }
            byte[] src = new byte[len];
            b.get(src);
            return new ArenaAgreement(hash, groundY, x0, y0, z0, x1, y1, z1, stateChecksum,
                    new String(src, StandardCharsets.UTF_8));
        } catch (BufferUnderflowException ignored) {
            return null;
        }
    }

    public String disagreement(ArenaAgreement peer) {
        if (peer == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (arenaHash != peer.arenaHash()) {
            append(sb, "arena hash local=" + hex(arenaHash) + " (" + source + ")"
                    + " peer=" + hex(peer.arenaHash()) + " (" + peer.source() + ")");
        }
        if (differs(groundY, peer.groundY())) {
            append(sb, "ground-y local=" + groundY + " peer=" + peer.groundY());
        }
        if (differs(x0, peer.x0()) || differs(y0, peer.y0()) || differs(z0, peer.z0())) {
            append(sb, "spawn0 local=" + point(x0, y0, z0)
                    + " peer=" + point(peer.x0(), peer.y0(), peer.z0()));
        }
        if (differs(x1, peer.x1()) || differs(y1, peer.y1()) || differs(z1, peer.z1())) {
            append(sb, "spawn1 local=" + point(x1, y1, z1)
                    + " peer=" + point(peer.x1(), peer.y1(), peer.z1()));
        }
        if (statedState() != peer.statedState()) {
            append(sb, "frame 0 state local=" + statedOrNot(this)
                    + " peer=" + statedOrNot(peer)
                    + ", so one host opened on a setup state it can name and the other opened on"
                    + " one it will not, which means they did not decode the same setup blob and"
                    + " the only check that catches a frame-0 divergence cannot be run at all");
        } else if (statedState() && stateChecksum != peer.stateChecksum()) {
            append(sb, "frame 0 state local=" + hex(stateChecksum)
                    + " peer=" + hex(peer.stateChecksum())
                    + ", so the two hosts decoded the same setup blob into different opening"
                    + " states and every later checksum would disagree for a reason no tick could"
                    + " explain");
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String statedOrNot(ArenaAgreement a) {
        return a.statedState() ? hex(a.stateChecksum()) : "not stated";
    }

    public boolean statedState() {
        return stateChecksum != NO_STATE;
    }

    public static String hex(long value) {
        return "0x" + Long.toHexString(value);
    }

    private static void append(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append("; ");
        }
        sb.append(part);
    }

    private static String point(double x, double y, double z) {
        return "(" + x + "," + y + "," + z + ")";
    }

    private static boolean differs(double a, double b) {
        return Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b);
    }

    private static byte[] utf8(String s) {
        if (s == null || s.isEmpty()) {
            return new byte[0];
        }
        String trimmed = s.length() > MAX_SOURCE_CHARS ? s.substring(0, MAX_SOURCE_CHARS) : s;
        return trimmed.getBytes(StandardCharsets.UTF_8);
    }
}
