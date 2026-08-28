package me.nootnoot.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class RelayBindGateTest {

    private static final String SECRET = "core-and-relay-share-this";

    @Test
    void theShippedDefaultListensOnLoopbackOnly() {
        RelayBindGate.Verdict v = RelayBindGate.evaluate(null, null, false, false);

        assertTrue(v.start(), "a developer who sets nothing must still get a relay");
        assertEquals(RelayBindGate.Mode.PINNED_LOOPBACK, v.mode());
        assertEquals(RelayBindGate.LOOPBACK, v.bindHost(),
                "trust on first use is only defensible when nothing off this machine can reach the"
                        + " socket, so the no-secret default must not bind every interface");
        assertTrue(v.slotAuthentication());
        assertFalse(v.severe());
        assertTrue(v.message().contains(RelayBindGate.SECRET_ENV));
    }

    @Test
    void aRoutableBindWithNoSecretRefusesToStart() {
        RelayBindGate.Verdict v = RelayBindGate.evaluate(null, RelayBindGate.WILDCARD, false, false);

        assertFalse(v.start(), "this is the shape that was shipping the weak path to the internet");
        assertEquals(RelayBindGate.Mode.REFUSED, v.mode());
        assertTrue(v.severe());
        assertTrue(v.message().contains(RelayBindGate.SECRET_ENV));
        assertTrue(v.message().contains(RelayBindGate.PUBLIC_ENV),
                "a refusal has to name the opt-in that overrides it, or an operator has no way"
                        + " forward but guessing");
    }

    @Test
    void aRoutableBindWithNoSecretIsAlsoRefusedOnARealAddress() {
        RelayBindGate.Verdict v = RelayBindGate.evaluate("   ", "10.0.0.2", false, false);

        assertFalse(v.start(), "a blank secret is no secret");
        assertEquals(RelayBindGate.Mode.REFUSED, v.mode());
    }

    @Test
    void aSecretBindsEveryInterfaceWithoutBeingAsked() {
        RelayBindGate.Verdict v = RelayBindGate.evaluate(SECRET, null, false, false);

        assertTrue(v.start());
        assertEquals(RelayBindGate.Mode.DERIVED, v.mode());
        assertEquals(RelayBindGate.WILDCARD, v.bindHost(),
                "the production configuration must reach players without a second variable, or"
                        + " setting the secret would look like it broke the relay");
        assertTrue(v.slotAuthentication());
        assertFalse(v.severe());
    }

    @Test
    void aSecretStillHonoursAnExplicitBind() {
        RelayBindGate.Verdict v = RelayBindGate.evaluate(SECRET, "10.0.0.2", false, false);

        assertEquals(RelayBindGate.Mode.DERIVED, v.mode());
        assertEquals("10.0.0.2", v.bindHost());
    }

    @Test
    void theWeakPathOnARoutableBindNeedsAnExplicitOptIn() {
        RelayBindGate.Verdict v = RelayBindGate.evaluate(null, RelayBindGate.WILDCARD, true, false);

        assertTrue(v.start());
        assertEquals(RelayBindGate.Mode.PINNED_PUBLIC, v.mode());
        assertEquals(RelayBindGate.WILDCARD, v.bindHost());
        assertTrue(v.slotAuthentication(), "the opt-in permits the reachable bind, it does not also"
                + " turn slot binding off");
        assertTrue(v.severe(), "the only way onto the weak path in production must be loud");
    }

    @Test
    void theOptInDoesNothingOnItsOwn() {
        RelayBindGate.Verdict v = RelayBindGate.evaluate(null, null, true, false);

        assertEquals(RelayBindGate.Mode.PINNED_LOOPBACK, v.mode());
        assertEquals(RelayBindGate.LOOPBACK, v.bindHost(),
                "permission to bind a routable address is not a request to bind one");
        assertFalse(v.severe());
    }

    @Test
    void theOpenFlagStillTurnsSlotAuthenticationOff() {
        RelayBindGate.Verdict v = RelayBindGate.evaluate(null, null, false, true);

        assertTrue(v.start());
        assertEquals(RelayBindGate.Mode.OPEN, v.mode());
        assertFalse(v.slotAuthentication());
        assertEquals(RelayBindGate.WILDCARD, v.bindHost());
        assertTrue(v.severe());
    }

    @Test
    void theSecretOutranksTheOpenFlag() {
        RelayBindGate.Verdict v = RelayBindGate.evaluate(SECRET, null, false, true);

        assertEquals(RelayBindGate.Mode.DERIVED, v.mode());
        assertTrue(v.slotAuthentication());
        assertTrue(v.message().contains("IGNORED"));
    }

    @Test
    void loopbackIsRecognisedByNameAndByLiteral() {
        assertTrue(RelayBindGate.isLoopback("127.0.0.1"));
        assertTrue(RelayBindGate.isLoopback("127.0.0.5"));
        assertTrue(RelayBindGate.isLoopback("localhost"));
        assertTrue(RelayBindGate.isLoopback("::1"));
        assertTrue(RelayBindGate.isLoopback("[::1]"));
        assertFalse(RelayBindGate.isLoopback("0.0.0.0"));
        assertFalse(RelayBindGate.isLoopback("10.0.0.2"));

        assertEquals(RelayBindGate.Mode.PINNED_LOOPBACK,
                RelayBindGate.evaluate(null, "localhost", false, false).mode());
    }

    @Test
    void theChosenBindActuallyReachesTheSocket() throws Exception {
        RelayServer loopbackOnly = new RelayServer(0, true, null,
                InetAddress.getByName(RelayBindGate.LOOPBACK));
        try {
            assertEquals(RelayBindGate.LOOPBACK, loopbackOnly.boundHost(),
                    "the gate's verdict is worthless if the socket ignores it");
        } finally {
            loopbackOnly.stop();
        }

        RelayServer everywhere = new RelayServer(0, true, null,
                InetAddress.getByName(RelayBindGate.WILDCARD));
        try {
            assertEquals(RelayBindGate.WILDCARD, everywhere.boundHost());
        } finally {
            everywhere.stop();
        }
    }
}
