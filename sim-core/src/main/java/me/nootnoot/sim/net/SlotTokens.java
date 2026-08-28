package me.nootnoot.sim.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SlotTokens {

    public static final int BYTES = 32;

    public static final String LABEL = "rollback-slot-v1";

    private static final byte[] LABEL_BYTES = LABEL.getBytes(StandardCharsets.US_ASCII);

    private SlotTokens() {
    }

    public static byte[] derive(byte[] secret, long sessionId, int slot) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("the slot secret is empty");
        }
        if (slot != 0 && slot != 1) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        ByteBuffer message = ByteBuffer.allocate(LABEL_BYTES.length + 8 + 1);
        message.put(LABEL_BYTES).putLong(sessionId).put((byte) slot);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(message.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable on this JVM", e);
        }
    }

    public static boolean matches(byte[] secret, long sessionId, int slot, byte[] presented) {
        if (presented == null || presented.length != BYTES || slot < 0 || slot > 1) {
            return false;
        }
        return MessageDigest.isEqual(derive(secret, sessionId, slot), presented);
    }

    public static String hex(byte[] token) {
        StringBuilder b = new StringBuilder(token.length * 2);
        for (byte v : token) {
            b.append(Character.forDigit((v >> 4) & 0xF, 16)).append(Character.forDigit(v & 0xF, 16));
        }
        return b.toString();
    }
}
