package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EdgeBuildFenceRemapTest {

    private static final String JAR = "jackpot-edge.jar";

    private record Deploy(File source, File loaded, File dataFolder) {
    }

    private static Deploy deploy(Path server, String built, String remappedBytes, boolean index,
                                 boolean keepSource) throws IOException {
        Path plugins = server.resolve("plugins");
        Files.createDirectories(plugins);
        Path source = plugins.resolve(JAR);
        Files.writeString(source, built, StandardCharsets.UTF_8);
        String hash = EdgeBuildFence.sha256(source.toFile());

        Path data = plugins.resolve("JackpotEdge");
        Files.createDirectories(data);
        Files.writeString(data.resolve(EdgeBuildFence.EXPECTED_FILE),
                "# written by the build\n" + hash + "\n", StandardCharsets.UTF_8);

        Path remapDir = plugins.resolve(EdgeBuildFence.REMAP_DIR);
        Files.createDirectories(remapDir);
        Path loaded = remapDir.resolve(JAR);
        Files.writeString(loaded, remappedBytes, StandardCharsets.UTF_8);
        if (index) {
            Files.writeString(remapDir.resolve(EdgeBuildFence.REMAP_INDEX),
                    "{\"hashes\":{\"" + hash.toUpperCase(Locale.ROOT) + "\":\"" + JAR + "\"},"
                            + "\"skippedHashes\":[],\"mappingsHash\":\"ABCD\"}",
                    StandardCharsets.UTF_8);
        }
        if (!keepSource) {
            Files.delete(source);
        }
        return new Deploy(source.toFile(), loaded.toFile(), data.toFile());
    }

    @Test
    void aPluginPaperRemappedOnLoadIsStillTheJarTheBuildProduced(@TempDir Path server)
            throws IOException {
        Deploy d = deploy(server, "the current build", "remapped by paper", true, true);

        EdgeBuildFence.Verdict verdict = EdgeBuildFence.check(d.loaded(), d.dataFolder());

        assertTrue(verdict.armed());
        assertTrue(verdict.fresh(), "Paper hands getFile() the REMAPPED copy under "
                + EdgeBuildFence.REMAP_DIR + ", which never hashes to the jar gradle wrote. Hashing"
                + " it directly refused every correctly deployed edge and stopped the whole dev"
                + " stack - cross-play included - from starting: " + verdict.lines());
    }

    @Test
    void theRemapIndexIsWhatSaysWhichSourceJarWasRemapped(@TempDir Path server) throws IOException {
        Deploy d = deploy(server, "the current build", "remapped by paper", true, false);

        assertFalse(d.source().isFile(), "this case is the one where only the index survives");
        assertEquals(EdgeBuildFence.readExpected(
                        new File(d.dataFolder(), EdgeBuildFence.EXPECTED_FILE)),
                EdgeBuildFence.deployedHash(d.loaded()),
                "with the source jar gone, " + EdgeBuildFence.REMAP_INDEX + " is the only record"
                        + " of which jar was remapped, and the fence has to read it");
        assertTrue(EdgeBuildFence.check(d.loaded(), d.dataFolder()).fresh());
    }

    @Test
    void aStaleJarIsStillRefusedThroughTheRemapPath(@TempDir Path server) throws IOException {
        Deploy d = deploy(server, "the current build", "remapped by paper", true, true);
        Files.writeString(d.source().toPath(), "yesterday's build", StandardCharsets.UTF_8);
        Path remapDir = d.loaded().getParentFile().toPath();
        Files.writeString(remapDir.resolve(EdgeBuildFence.REMAP_INDEX),
                "{\"hashes\":{\"" + EdgeBuildFence.sha256(d.source()).toUpperCase(Locale.ROOT)
                        + "\":\"" + JAR + "\"}}", StandardCharsets.UTF_8);

        EdgeBuildFence.Verdict verdict = EdgeBuildFence.check(d.loaded(), d.dataFolder());

        assertFalse(verdict.fresh(), "resolving through the remap index must not become a way for"
                + " a stale jar to pass: " + verdict.lines());
        assertTrue(String.join(" | ", verdict.lines()).contains("STALE EDGE PLUGIN"));
    }

    @Test
    void aRemappedJarWithNothingToCompareAgainstIsARefusal(@TempDir Path server)
            throws IOException {
        Deploy d = deploy(server, "the current build", "remapped by paper", false, false);

        EdgeBuildFence.Verdict verdict = EdgeBuildFence.check(d.loaded(), d.dataFolder());

        assertTrue(verdict.armed());
        assertFalse(verdict.fresh(), "no index and no source jar means the fence cannot tell what"
                + " it is looking at, and a check that proves nothing must not read as a pass");
    }
}
