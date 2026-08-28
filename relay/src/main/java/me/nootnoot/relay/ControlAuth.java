package me.nootnoot.relay;

import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class ControlAuth {
    private final byte[] secret;

    ControlAuth(byte[] secret) {
        this.secret = secret.clone();
    }

    byte[] sign(byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failed", e);
        }
    }

    boolean verify(byte[] message, byte[] tag) {
        return MessageDigest.isEqual(sign(message), tag);
    }
}
