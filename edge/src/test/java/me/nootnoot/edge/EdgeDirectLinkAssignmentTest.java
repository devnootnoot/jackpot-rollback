package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.UUID;
import me.nootnoot.edge.tools.DevAssignMain;
import org.junit.jupiter.api.Test;

class EdgeDirectLinkAssignmentTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final byte[] LINK = "a-shared-per-session-link-token!".getBytes();

    private static final byte[] SHORT_LINK = new byte[]{7, 7, 8, 8, 1, 2, 3, 4};

    private static String withPeer(String peerHost, int peerPort, byte[] linkToken) {
        return DevAssignMain.assignment(4242L, 0, A, "playerA", B, "playerB",
                "relay.example", 7777, 1, System.currentTimeMillis() + 60_000L, true,
                EdgeGameTypes.IRON, DevAssignMain.ArenaRef.dev(-60.0),
                peerHost, peerPort,
                linkToken == null ? null : Base64.getEncoder().encodeToString(linkToken));
    }

    @Test
    void anAssignmentWithAPeerBlockCarriesTheEndpointAndTheLinkToken() {
        EdgeAssignment a = EdgeAssignment.parse(withPeer("10.0.0.9", 7788, LINK));
        assertNotNull(a);
        assertTrue(a.hasPeerEndpoint());
        assertEquals("10.0.0.9", a.peerHost());
        assertEquals(7788, a.peerPort());
        assertArrayEquals(LINK, a.linkToken());
    }

    @Test
    void anAssignmentWithNoPeerBlockKeepsTheRelay() {
        EdgeAssignment a = EdgeAssignment.parse(withPeer(null, 0, null));
        assertNotNull(a);
        assertFalse(a.hasPeerEndpoint());
        assertEquals("relay.example", a.relayHost());
        assertEquals(7777, a.relayPort());
    }

    @Test
    void aPeerEndpointWithoutATokenIsNotUsable() {
        EdgeAssignment a = EdgeAssignment.parse(withPeer("10.0.0.9", 7788, null));
        assertNotNull(a);
        assertFalse(a.hasPeerEndpoint(),
                "an endpoint with no shared secret would let the session bind to whoever spoke"
                        + " first, so it must fall back to the relay");
    }

    @Test
    void aPeerSecretTooShortToVouchForASlotIsNotUsable() {
        EdgeAssignment a = EdgeAssignment.parse(withPeer("10.0.0.9", 7788, SHORT_LINK));
        assertNotNull(a);
        assertFalse(a.hasPeerEndpoint(),
                "the direct link derives a per-slot token from this secret, so a secret short"
                        + " enough to guess is the same hole as no secret at all. Fall back to the"
                        + " relay rather than opening a link nobody can vouch for");
    }

    @Test
    void aPeerBlockWithNoPortIsNotUsable() {
        EdgeAssignment a = EdgeAssignment.parse(withPeer("10.0.0.9", 0, LINK));
        assertNotNull(a);
        assertFalse(a.hasPeerEndpoint());
    }

    @Test
    void theRelayIsStillDescribedWhenADirectPeerIsPresent() {
        EdgeAssignment a = EdgeAssignment.parse(withPeer("10.0.0.9", 7788, LINK));
        assertNotNull(a);
        assertEquals("relay.example", a.relayHost());
        assertTrue(a.describe().contains("direct 10.0.0.9:7788"));
    }

    @Test
    void anOlderCoreWithoutThePeerFieldStillParses() {
        String json = DevAssignMain.assignment(1L, 1, B, "playerB", A, "playerA",
                "127.0.0.1", 7777, -60.0, 1, System.currentTimeMillis() + 60_000L, true);
        EdgeAssignment a = EdgeAssignment.parse(json);
        assertNotNull(a, "an assignment from a core that knows nothing about direct links must"
                + " still be accepted, it just keeps the relay");
        assertFalse(a.hasPeerEndpoint());
        assertEquals(0, a.linkToken().length);
    }
}
