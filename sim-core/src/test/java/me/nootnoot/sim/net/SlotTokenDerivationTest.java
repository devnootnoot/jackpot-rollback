package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SlotTokenDerivationTest {

    static final String SECRET = "relay-slot-secret-fixture";

    static final long SESSION = 0x0123456789ABCDEFL;

    static final String SLOT0 = "c70f9e910029d559c32eea2be0d70b05e0a8060cb88b515ced539183614e1969";

    static final String SLOT1 = "a4ea22d84169c9fe256b18765f4f2707bd3cfd207d4144f4fbb1374e795fcdec";

    private static byte[] key() {
        return SECRET.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theDerivationIsPinnedToAKnownAnswer() {
        assertEquals(SLOT0, SlotTokens.hex(SlotTokens.derive(key(), SESSION, 0)),
                "this vector is the contract between the relay and mcleagues-core, which derives the"
                        + " same token from its own transcribed copy. Changing the label, the field"
                        + " order or the hash silently stops every minted token from verifying and"
                        + " no duel can bind a slot.");
        assertEquals(SLOT1, SlotTokens.hex(SlotTokens.derive(key(), SESSION, 1)));
        assertEquals(SlotTokens.BYTES, SlotTokens.derive(key(), SESSION, 0).length);
    }

    @Test
    void aTokenIsBoundToItsSessionAndItsSlot() {
        byte[] slot0 = SlotTokens.derive(key(), SESSION, 0);

        assertTrue(SlotTokens.matches(key(), SESSION, 0, slot0));
        assertFalse(SlotTokens.matches(key(), SESSION, 1, slot0),
                "slot 0's token must not bind slot 1, or a duelist can take over the seat that"
                        + " sends their opponent's input");
        assertFalse(SlotTokens.matches(key(), SESSION + 1, 0, slot0),
                "a token from one session must not bind the next one");
        assertFalse(SlotTokens.matches("other-secret".getBytes(StandardCharsets.UTF_8),
                        SESSION, 0, slot0),
                "a relay holding a different secret must reject it outright");
        assertNotEquals(SlotTokens.hex(slot0),
                SlotTokens.hex(SlotTokens.derive(key(), SESSION, 1)));
    }

    @Test
    void nothingShortOrAbsentCanPassVerification() {
        assertFalse(SlotTokens.matches(key(), SESSION, 0, new byte[0]));
        assertFalse(SlotTokens.matches(key(), SESSION, 0, null));
        assertFalse(SlotTokens.matches(key(), SESSION, 0, new byte[SlotTokens.BYTES]));
        assertFalse(SlotTokens.matches(key(), SESSION, 2,
                SlotTokens.derive(key(), SESSION, 0)));
        assertThrows(IllegalArgumentException.class, () -> SlotTokens.derive(new byte[0], SESSION, 0));
        assertThrows(IllegalArgumentException.class, () -> SlotTokens.derive(key(), SESSION, 2));
    }
}
