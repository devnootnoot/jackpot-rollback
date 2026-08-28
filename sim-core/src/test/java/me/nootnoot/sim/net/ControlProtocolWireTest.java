package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class ControlProtocolWireTest {

    private static final String SIBLING_CORE_DIR = "../../../mcleagues/mcleagues-core";

    private static final String MIRROR_WITHIN_CORE =
            "src/main/java/me/nootnoot/modules/practice/managers/RollbackRefereeProtocol.java";

    private static final String DIR_PROPERTY = "mcleagues.core.dir";

    private static final String MODE_PROPERTY = "rollback.versionFence";

    static final String AUTHORIZE_SETUP_HEX =
            "03"
            + "0102030405060708"
            + "1122334455667788"
            + "0000000401020304"
            + "0000000405060708"
            + "000000021011"
            + "0000000120";

    static final String RESULT_HEX =
            "02"
            + "0102030405060708"
            + "01"
            + "00000001"
            + "00000002"
            + "00000003"
            + "000004d2"
            + "00";

    @Test
    void authorizeSetupPinsItsByteLayout() {
        byte[] encoded = ControlProtocol.encode(new ControlProtocol.AuthorizeSetup(
                0x0102030405060708L, 0x1122334455667788L, new byte[]{1, 2, 3, 4},
                new byte[]{5, 6, 7, 8}, new byte[]{0x10, 0x11}, new byte[]{0x20}));
        assertEquals(AUTHORIZE_SETUP_HEX, hex(encoded),
                "mcleagues-core hand-transcribes this layout in RollbackRefereeProtocol."
                        + " Changing it here without changing it there means core authorizes"
                        + " sessions the relay cannot parse, and the referee silently witnesses"
                        + " nothing.");
    }

    @Test
    void resultPinsItsByteLayout() {
        byte[] encoded = ControlProtocol.encode(new ControlProtocol.Result(
                0x0102030405060708L, true, 1, 2, 3, 1234, false));
        assertEquals(RESULT_HEX, hex(encoded),
                "mcleagues-core hand-transcribes this layout in RollbackRefereeProtocol."
                        + " Changing it here without changing it there means core misreads the"
                        + " referee's verdict and would settle duels on garbage.");
    }

    @Test
    void authorizeSetupRoundTrips() {
        ControlProtocol.AuthorizeSetup sent = new ControlProtocol.AuthorizeSetup(
                77L, 99L, new byte[]{1}, new byte[]{2}, new byte[]{3, 4, 5}, new byte[]{6, 7});
        ControlProtocol.ControlMessage back = ControlProtocol.decode(ControlProtocol.encode(sent));
        assertTrue(back instanceof ControlProtocol.AuthorizeSetup);
        ControlProtocol.AuthorizeSetup got = (ControlProtocol.AuthorizeSetup) back;
        assertEquals(sent.sessionId(), got.sessionId());
        assertEquals(sent.expiryEpochMs(), got.expiryEpochMs());
        assertArrayEquals(sent.slot0Token(), got.slot0Token());
        assertArrayEquals(sent.slot1Token(), got.slot1Token());
        assertArrayEquals(sent.matchSetup(), got.matchSetup());
        assertArrayEquals(sent.arenaBlob(), got.arenaBlob());
    }

    @Test
    void mcleaguesCoreMirrorsTheSameOpcodesAndPins() throws IOException {
        String configuredDir = System.getProperty(DIR_PROPERTY);
        String dir = configuredDir == null || configuredDir.isBlank()
                ? SIBLING_CORE_DIR : configuredDir.trim();
        Path mirror = Path.of(dir, MIRROR_WITHIN_CORE).normalize();

        if (!Files.isRegularFile(mirror)) {
            if (VersionFenceTest.absenceIsFatal(System.getProperty(MODE_PROPERTY),
                    System.getenv("CI"))) {
                fail("the referee control mirror could not be compared and this environment"
                        + " requires it: " + mirror.toAbsolutePath() + " does not exist. Check"
                        + " mcleagues-core out and pass -D" + DIR_PROPERTY + "=<its directory>,"
                        + " or waive it on purpose with -D" + MODE_PROPERTY + "=skip.");
            }
            Assumptions.abort("mcleagues-core is not checked out beside this repo, so the referee"
                    + " control mirror cannot be compared here");
        }

        String source = Files.readString(mirror, StandardCharsets.UTF_8);
        assertTrue(source.contains("AUTHORIZE_SETUP = " + ControlProtocol.AUTHORIZE_SETUP + ";"),
                "mcleagues-core RollbackRefereeProtocol does not carry AUTHORIZE_SETUP = "
                        + ControlProtocol.AUTHORIZE_SETUP);
        assertTrue(source.contains("RESULT = " + ControlProtocol.RESULT + ";"),
                "mcleagues-core RollbackRefereeProtocol does not carry RESULT = "
                        + ControlProtocol.RESULT);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16));
            out.append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
