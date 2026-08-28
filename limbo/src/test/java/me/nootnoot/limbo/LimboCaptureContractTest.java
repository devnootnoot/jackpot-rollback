package me.nootnoot.limbo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class LimboCaptureContractTest {

    private static final Path CONFIG = Path.of("limbo.properties");

    private static LimboConfig config() {
        return LimboConfig.load(CONFIG.toString());
    }

    @Test
    void everyMinecraftVersionTheModShipsHasACaptureTheLimboCanServe() {
        List<Capture> captures = LimboServer.loadCaptures(config());
        assertEquals(List.of(774, 775, 776),
                captures.stream().map(c -> c.protocolVersion).toList(),
                "limbo.properties must declare a capture for every version pvphq-rollback-mod builds"
                        + " (1.21.11=774, 26.1.2=775, 26.2=776). A version with no capture cannot be"
                        + " transferred to the limbo at all, so every MOD-hosted slot on it is dead"
                        + " before the match starts. Record one with"
                        + " gradlew devLimboCapture -PcaptureVersion=<version>.");
    }

    @Test
    void theVoidChunkShapeMatchesWhatTheRealServerSentInEachCapture() {
        LimboConfig config = config();
        for (Capture capture : LimboServer.loadCaptures(config)) {
            Boolean captured = CaptureAudit.detectSectionFluidCount(capture.play, capture.chunkId,
                    config.worldSectionCount);
            assertNotNull(captured, "could not read the section layout out of the "
                    + capture.versionName + " capture - play.world.sections="
                    + config.worldSectionCount + " may not match its dimension height");
            assertEquals(captured, capture.sectionFluidCount,
                    capture.versionName + " (protocol " + capture.protocolVersion + "): the real"
                            + " server writes chunk sections " + (captured ? "with" : "without")
                            + " the fluid-count short, so the empty chunks the limbo builds itself"
                            + " must use the same shape or the client fails to decode them");
        }
    }

    @Test
    void everyCaptureCarriesEnoughChunksToDismissLoadingTerrain() {
        LimboConfig config = config();
        for (Capture capture : LimboServer.loadCaptures(config)) {
            int chunks = CaptureAudit.countChunks(capture.play, capture.chunkId);
            assertTrue(chunks >= 100, capture.versionName + " only has " + chunks
                    + " chunk packets; the limbo voids exactly the captured columns, so a thin"
                    + " capture leaves the player in an unloaded pocket");
        }
    }

    @Test
    void theOneToOneAlternativeToAMissingCaptureIsAHardFailure() throws IOException {
        Properties props = new Properties();
        try (var in = Files.newInputStream(CONFIG)) {
            props.load(in);
        }
        props.setProperty("packets.config.alt", "no-such-config.bin");
        props.setProperty("packets.play.alt", "no-such-play.bin");
        Path temp = Files.createTempFile("limbo-missing", ".properties");
        try (var out = Files.newOutputStream(temp)) {
            props.store(out, null);
        }
        LimboConfig broken = LimboConfig.load(temp.toString());
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LimboServer.loadCaptures(broken));
        assertTrue(thrown.getMessage().contains("775"),
                "a declared-but-missing capture has to name the protocol it leaves unserved,"
                        + " otherwise the limbo starts, looks healthy and drops those clients"
                        + " mid-handshake: " + thrown.getMessage());
        Files.deleteIfExists(temp);
    }

    @Test
    void theProtocolNumbersAreTheOnesTheServersThemselvesReport() {
        LimboConfig config = config();
        assertEquals(774, config.protocolVersion,
                "1.21.11 is protocol 774 (its version.json says so); the handshake is matched on"
                        + " this number, so a wrong one refuses every 1.21.11 client");
        assertEquals(775, config.protocolVersionAlt, "26.1.2 is protocol 775");
        assertEquals(1, config.extraCaptures.size());
        assertEquals(776, config.extraCaptures.get(0).protocolVersion, "26.2 is protocol 776");
        assertEquals(LimboConfig.FLUID_COUNT_MIN_PROTOCOL, 775,
                "the fluid-count short arrives in 26.1.2 (775), not in 1.21.11 (774)");
    }
}
