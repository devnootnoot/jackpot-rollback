package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import me.nootnoot.edge.tools.DevRefereeAuthorizer;
import me.nootnoot.sim.net.ControlProtocol;
import org.junit.jupiter.api.Test;

class DevRefereeAuthorizeTest {

    private static final Path DEVENV = Path.of("../devenv.gradle");

    private static final long SESSION = 0x0123456789ABCDEFL;

    private static final byte[] TOKEN_0 = {1, 2, 3, 4};

    private static final byte[] TOKEN_1 = {5, 6, 7, 8};

    private static final byte[] SETUP = {0x10, 0x11, 0x12, 0x13};

    @Test
    void theDevRelayAndDevAssignAgreeOnTheControlSecret() throws IOException {
        String devenv = Files.readString(DEVENV);

        assertTrue(devenv.contains("RELAY_CONTROL_SECRET"),
                "devRun has to start the relay with a control secret or the control endpoint never"
                        + " opens and the dev stack exercises no referee at all");
        assertTrue(devenv.contains("'" + DevRefereeAuthorizer.DEV_CONTROL_SECRET + "'"),
                "devenv.gradle hands the relay one default secret and devAssign signs with"
                        + " DevRefereeAuthorizer.DEV_CONTROL_SECRET. When those drift the relay"
                        + " discards every authorize on an HMAC it cannot verify, and the only"
                        + " symptom is a referee that quietly witnesses nothing");
        assertTrue(devenv.contains("relayControlSecret") && devenv.contains("relayControlPort"),
                "both sides must read the same -P overrides, or pointing the dev stack at a"
                        + " differently configured relay silently drops the third witness");
    }

    @Test
    void theAuthorizeFrameIsTheOneTheRelayControlEndpointReads() {
        byte[] arena = {1, 2, 3};
        byte[] message = DevRefereeAuthorizer.message(SESSION, 4242L, TOKEN_0, TOKEN_1, SETUP,
                arena);

        ControlProtocol.AuthorizeSetup authorize = assertInstanceOf(
                ControlProtocol.AuthorizeSetup.class, ControlProtocol.decode(message),
                "the relay registers a referee for AUTHORIZE and AUTHORIZE_SETUP only; any other"
                        + " opcode is dropped and the session goes unwatched");
        assertEquals(SESSION, authorize.sessionId());
        assertEquals(4242L, authorize.expiryEpochMs());
        assertArrayEquals(TOKEN_0, authorize.slot0Token(),
                "the relay checks every HELLO against these tokens once a session is authorized,"
                        + " so a wrong one here drops both peers of the duel");
        assertArrayEquals(TOKEN_1, authorize.slot1Token());
        assertArrayEquals(SETUP, authorize.matchSetup());
        assertArrayEquals(arena, authorize.arenaBlob());
    }

    @Test
    void aFrameSignedWithTheWrongSecretIsNotTheOneTheRelayWouldAccept() {
        byte[] message = DevRefereeAuthorizer.message(SESSION, 4242L, TOKEN_0, TOKEN_1, SETUP,
                new byte[0]);
        byte[] tag = DevRefereeAuthorizer.tag(DevRefereeAuthorizer.DEV_CONTROL_SECRET, message);

        assertEquals(DevRefereeAuthorizer.TAG_BYTES, tag.length,
                "the control endpoint reads a fixed-width tag straight off the stream, so a"
                        + " different width desynchronises every later frame on the link");
        assertTrue(MessageDigest.isEqual(tag,
                DevRefereeAuthorizer.tag(DevRefereeAuthorizer.DEV_CONTROL_SECRET, message)));
        assertFalse(MessageDigest.isEqual(tag,
                DevRefereeAuthorizer.tag("not-the-dev-secret", message)),
                "devRun and devAssign must be handed the same secret or the relay discards the"
                        + " authorize and the dev stack runs with no third witness at all");
    }

    @Test
    void anAbsentArenaBlobStillAuthorizes() {
        byte[] message = DevRefereeAuthorizer.message(SESSION, 4242L, TOKEN_0, TOKEN_1, SETUP,
                null);

        ControlProtocol.AuthorizeSetup authorize = assertInstanceOf(
                ControlProtocol.AuthorizeSetup.class, ControlProtocol.decode(message));
        assertEquals(0, authorize.arenaBlob().length,
                "with no arena bytes the relay rebuilds collision from the setup's own box list"
                        + " rather than refusing the session, so a flat dev match is still watched");
    }
}
