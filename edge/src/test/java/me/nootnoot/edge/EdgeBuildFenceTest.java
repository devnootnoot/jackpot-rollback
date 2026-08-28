package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EdgeBuildFenceTest {

    private static final Path DEVENV = Path.of("../devenv.gradle");

    private static final Path PLUGIN = Path.of("src/main/java/me/nootnoot/edge/EdgePlugin.java");

    private static File jar(Path dir, String content) throws IOException {
        Path file = dir.resolve("jackpot-edge.jar");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file.toFile();
    }

    private static File stamp(Path dir, String hash) throws IOException {
        Path data = dir.resolve("JackpotEdge");
        Files.createDirectories(data);
        Files.writeString(data.resolve(EdgeBuildFence.EXPECTED_FILE),
                "# written by the build\n" + hash + "\n", StandardCharsets.UTF_8);
        return data.toFile();
    }

    @Test
    void thePluginTheBuildJustDeployedIsAccepted(@TempDir Path dir) throws IOException {
        File built = jar(dir, "the current build");
        File data = stamp(dir, EdgeBuildFence.sha256(built));

        EdgeBuildFence.Verdict verdict = EdgeBuildFence.check(built, data);

        assertTrue(verdict.armed());
        assertTrue(verdict.fresh(), verdict.lines().toString());
    }

    @Test
    void aPluginOlderThanTheStampIsRefusedWithTheReasonTheProtocolFenceCannotGive(@TempDir Path dir)
            throws IOException {
        File data = stamp(dir, EdgeBuildFence.sha256(jar(dir, "the current build")));
        File stale = jar(dir, "yesterday's build");

        EdgeBuildFence.Verdict verdict = EdgeBuildFence.check(stale, data);

        assertFalse(verdict.fresh(), "the deployed jar is not the built jar and the fence let it"
                + " through. This is the whole defect: a local match then runs behaviour that"
                + " contradicts the source the owner is reading");
        String banner = String.join(" | ", verdict.lines());
        assertTrue(banner.contains("STALE EDGE PLUGIN"), banner);
        assertTrue(banner.contains("Protocol.VERSION"), "the banner has to say why the existing"
                + " fence did not catch this, or the next reader will assume it did: " + banner);
        assertTrue(banner.contains("gradlew devSetup"), banner);
    }

    @Test
    void aRealDeploymentWithNoStampIsNotFenced(@TempDir Path dir) throws IOException {
        File built = jar(dir, "shipped by hand");
        Path data = dir.resolve("JackpotEdge");
        Files.createDirectories(data);

        EdgeBuildFence.Verdict verdict = EdgeBuildFence.check(built, data.toFile());

        assertFalse(verdict.armed(), "only build/devenv stamps a plugin. A production edge has no"
                + " gradle build next to it and must still start");
        assertTrue(verdict.fresh());
    }

    @Test
    void aStampThatNamesNothingIsAFailureNotAPass(@TempDir Path dir) throws IOException {
        File built = jar(dir, "the current build");
        Path data = dir.resolve("JackpotEdge");
        Files.createDirectories(data);
        Files.writeString(data.resolve(EdgeBuildFence.EXPECTED_FILE),
                "# nothing but commentary\n\n", StandardCharsets.UTF_8);

        EdgeBuildFence.Verdict verdict = EdgeBuildFence.check(built, data.toFile());

        assertTrue(verdict.armed());
        assertFalse(verdict.fresh(), "an armed fence that compares against nothing proves nothing,"
                + " and a check that proves nothing must not read as a pass");
    }

    @Test
    void theBuildWritesTheFileTheFenceReads() throws IOException {
        String devenv = Files.readString(DEVENV, StandardCharsets.UTF_8);
        assertTrue(devenv.contains("'" + EdgeBuildFence.EXPECTED_FILE + "'"),
                "devenv.gradle no longer writes " + EdgeBuildFence.EXPECTED_FILE + ", so every dev"
                        + " edge would start unfenced while this class still believes it is"
                        + " armed");
        assertTrue(devenv.contains("tasks.register('devStampEdgePlugin')"),
                "the stamp has to be rewritten whenever :edge:jar runs, not only by devSetup."
                        + " Otherwise a plain gradlew build leaves a fresh jar in edge/build/libs"
                        + " and yesterday's plugin under build/devenv, agreeing with a stamp"
                        + " written at the same time as itself - which is exactly how the stale"
                        + " deploy survived");
        assertTrue(devenv.contains("finalizedBy rootProject.tasks.named('devStampEdgePlugin')"),
                "devStampEdgePlugin must hang off :edge:jar itself");
        assertTrue(devenv.contains("tasks.register('devVerifyEdgeDeploy')")
                        && devenv.contains("finalizedBy 'devVerifyEdgeDeploy'"),
                "devSetup has to prove the deploy landed rather than assume it");
    }

    @Test
    void theEdgeRefusesBeforeItDoesAnythingElse() throws IOException {
        String source = Files.readString(PLUGIN, StandardCharsets.UTF_8);
        int enable = source.indexOf("public void onEnable()");
        int fence = source.indexOf("EdgeBuildFence.check(", enable);
        int packets = source.indexOf("PacketEvents.getAPI().init()", enable);
        assertTrue(fence > enable && fence < packets,
                "the build fence must be the first thing onEnable does. A stale plugin that has"
                        + " already registered its packet listeners is a stale plugin that is"
                        + " already deciding what the sim sees");
        assertTrue(source.indexOf("disablePlugin(this)", fence) < packets,
                "refusing means the plugin does not enable, not that it logs and carries on");
    }
}
