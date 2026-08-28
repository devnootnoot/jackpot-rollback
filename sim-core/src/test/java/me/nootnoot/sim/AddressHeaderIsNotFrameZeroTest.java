package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import org.junit.jupiter.api.Test;

class AddressHeaderIsNotFrameZeroTest {

    @Test
    void twoEdgesAddressingTheirOwnSlotStillAuthorizeTheSameFrameZero() {
        byte[] sections = MatchSetupFrame0Encoder.sections(
                HarnessScenarios.duel(Arena.flat(64.0)));
        byte[] asSlot0 = wire(778899L, 0, new byte[] {1, 2, 3}, "10.0.0.1", 7777, sections);
        byte[] asSlot1 = wire(778899L, 1, new byte[] {9, 9, 9, 9, 9}, "10.0.0.2", 7778, sections);

        assertNotEquals(asSlot0.length, asSlot1.length,
                "the two wires must genuinely differ in their address header, or this test would"
                        + " pass on identical bytes and prove nothing");

        MatchSetupFrame0Decoder.Frame0 a = MatchSetupFrame0Decoder.decode(asSlot0);
        MatchSetupFrame0Decoder.Frame0 b = MatchSetupFrame0Decoder.decode(asSlot1);

        assertEquals(Checksum.of(a.state()), Checksum.of(b.state()),
                "the relay referee decodes whichever edge authorized first, and each edge addresses"
                        + " the setup bytes to its OWN slot with its OWN token and relay endpoint."
                        + " If any of that reached the simulated state the two edges would register"
                        + " different frame 0s for one session and the third witness would be"
                        + " re-simulating a match neither client is playing");
        assertEquals(a.arenaGroundY(), b.arenaGroundY());
        assertEquals(a.selectedSlot()[0], b.selectedSlot()[0]);
        assertEquals(a.identities()[0].name(), b.identities()[0].name());
    }

    private static byte[] wire(long sessionId, int slot, byte[] token, String relayHost,
                               int relayPort, byte[] sections) {
        ByteBuffer b = ByteBuffer.allocate(1 << 18);
        b.putLong(sessionId);
        b.put((byte) slot);
        b.putShort((short) token.length);
        b.put(token);
        putString(b, relayHost);
        b.putInt(relayPort);
        b.putInt(3);
        for (int i = 0; i < 8; i++) {
            b.put((byte) 0);
        }
        b.putInt(0);
        b.putInt(0);
        b.putDouble(64.0);
        b.putInt(0);
        b.putDouble(0.0);
        b.putDouble(0.0);
        b.putDouble(200.0);
        b.put((byte) 0);
        putPlayer(b);
        putPlayer(b);
        b.put((byte) 0);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        b.put(sections);

        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    private static void putPlayer(ByteBuffer b) {
        b.putDouble(0.0);
        b.putDouble(64.0);
        b.putDouble(0.0);
        b.putFloat(0f);
        b.putFloat(0f);
        b.putFloat(20f);
        b.putFloat(1f);
        b.putFloat(4f);
        b.putInt(0);
        b.putFloat(0f);
        b.putFloat(0f);
        b.putInt(0);
        b.putLong(0L);
        b.putLong(0L);
        putString(b, "Dev");
        putString(b, "");
        putString(b, "");
        putString(b, "");
        b.putInt(0);
        b.putFloat(20f);
        b.putFloat(5f);
        b.putInt(0);
        b.putInt(0);
        b.putFloat(0f);
        for (int s = 0; s < 9 * 41; s++) {
            b.put((byte) 0);
        }
        b.putInt(0);
        b.put((byte) 0);
        for (int s = 0; s < MatchSetupFrame0Decoder.INVENTORY_SLOTS; s++) {
            b.putInt(0);
        }
        b.putInt(0);
    }

    private static void putString(ByteBuffer b, String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        b.putShort((short) raw.length);
        b.put(raw);
    }
}
