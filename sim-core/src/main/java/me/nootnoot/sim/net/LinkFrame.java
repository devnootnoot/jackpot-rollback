package me.nootnoot.sim.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class LinkFrame {

    public static final int MAGIC = 0x4A444C32;

    public static final int LEGACY_MAGIC = 0x4A444C31;

    public static final int TAG_BYTES = 16;

    public static final int PREFIX_BYTES = 4 + 8 + 1 + 8;

    public static final int HEADER_BYTES = PREFIX_BYTES + TAG_BYTES;

    public static final int REPLAY_WINDOW = 64;

    public static final String LABEL = "rollback-link-frame-v1";

    private static final byte[] LABEL_BYTES = LABEL.getBytes(StandardCharsets.US_ASCII);

    private LinkFrame() {
    }

    public static byte[] encode(long sessionId, int slot, long counter, byte[] secret,
                                byte[] payload) {
        if (slot != 0 && slot != 1) {
            throw new IllegalArgumentException("slot must be 0 or 1: " + slot);
        }
        byte[] wire = new byte[HEADER_BYTES + payload.length];
        ByteBuffer b = ByteBuffer.wrap(wire);
        b.putInt(MAGIC).putLong(sessionId).put((byte) slot).putLong(counter);
        System.arraycopy(payload, 0, wire, HEADER_BYTES, payload.length);
        System.arraycopy(tag(secret, wire, wire.length), 0, wire, PREFIX_BYTES, TAG_BYTES);
        return wire;
    }

    public static boolean verify(byte[] secret, byte[] wire, int len) {
        if (len < HEADER_BYTES) {
            return false;
        }
        return MessageDigest.isEqual(tag(secret, wire, len),
                Arrays.copyOfRange(wire, PREFIX_BYTES, HEADER_BYTES));
    }

    public static int magicOf(byte[] wire) {
        return ByteBuffer.wrap(wire, 0, 4).getInt();
    }

    public static long sessionOf(byte[] wire) {
        return ByteBuffer.wrap(wire, 4, 8).getLong();
    }

    public static int slotOf(byte[] wire) {
        return wire[12] & 0xFF;
    }

    public static long counterOf(byte[] wire) {
        return ByteBuffer.wrap(wire, 13, 8).getLong();
    }

    private static byte[] tag(byte[] secret, byte[] wire, int len) {
        Mac mac = mac(secret);
        mac.update(LABEL_BYTES);
        mac.update(wire, 0, PREFIX_BYTES);
        if (len > HEADER_BYTES) {
            mac.update(wire, HEADER_BYTES, len - HEADER_BYTES);
        }
        return Arrays.copyOf(mac.doFinal(), TAG_BYTES);
    }

    private static Mac mac(byte[] secret) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("the link secret is empty");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable on this JVM", e);
        }
    }

    public static final class ReplayWindow {
        private long high = -1L;
        private long mask;

        public boolean fresh(long counter) {
            if (counter < 0) {
                return false;
            }
            if (high < 0) {
                high = counter;
                mask = 1L;
                return true;
            }
            if (counter > high) {
                long shift = counter - high;
                mask = shift >= REPLAY_WINDOW ? 0L : mask << shift;
                mask |= 1L;
                high = counter;
                return true;
            }
            long back = high - counter;
            if (back >= REPLAY_WINDOW) {
                return false;
            }
            long bit = 1L << back;
            if ((mask & bit) != 0L) {
                return false;
            }
            mask |= bit;
            return true;
        }
    }
}
