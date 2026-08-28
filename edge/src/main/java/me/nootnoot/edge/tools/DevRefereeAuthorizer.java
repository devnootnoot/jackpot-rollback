package me.nootnoot.edge.tools;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import me.nootnoot.sim.net.ControlProtocol;

public final class DevRefereeAuthorizer {

    public static final String CONTROL_SECRET_ENV = "RELAY_CONTROL_SECRET";

    public static final String DEV_CONTROL_SECRET = "dev-referee-secret";

    public static final int DEFAULT_CONTROL_PORT = 7778;

    public static final long SESSION_TTL_MS = 1_800_000L;

    public static final int TAG_BYTES = 32;

    private static final int CONNECT_TIMEOUT_MS = 3_000;

    private static final long SETTLE_MS = 250L;

    private DevRefereeAuthorizer() {
    }

    public static byte[] message(long sessionId, long expiryEpochMs, byte[] slot0Token,
                                 byte[] slot1Token, byte[] setup, byte[] arenaBlob) {
        return ControlProtocol.encode(new ControlProtocol.AuthorizeSetup(sessionId, expiryEpochMs,
                slot0Token, slot1Token, setup, arenaBlob == null ? new byte[0] : arenaBlob));
    }

    public static byte[] tag(String secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] tag = mac.doFinal(message);
            if (tag.length != TAG_BYTES) {
                throw new IllegalStateException("HmacSHA256 produced " + tag.length + " bytes, the"
                        + " relay control endpoint reads exactly " + TAG_BYTES);
            }
            return tag;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable on this JVM", e);
        }
    }

    public static void send(String host, int port, String secret, byte[] message)
            throws IOException, InterruptedException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeInt(message.length);
            out.write(message);
            out.write(tag(secret, message));
            out.flush();
            Thread.sleep(SETTLE_MS);
        }
    }
}
