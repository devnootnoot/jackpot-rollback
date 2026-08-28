package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class NoStaleArtifactSurvivesABuildTest {

    private static Path rollbackRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("devenv.gradle"))
                    && Files.isDirectory(p.resolve("sim-core"))) {
                return p;
            }
            p = p.getParent();
        }
        return Path.of("nowhere");
    }

    private static Path sibling(String rel) {
        Path jackpot = rollbackRoot().getParent();
        return jackpot == null ? Path.of("nowhere") : jackpot.resolve(rel);
    }

    private static Path core(String rel) {
        Path jackpot = rollbackRoot().getParent();
        Path dev = jackpot == null ? null : jackpot.getParent();
        return dev == null ? Path.of("nowhere")
                : dev.resolve("mcleagues/mcleagues-core").resolve(rel);
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    static boolean modPresent() {
        return Files.isRegularFile(sibling("pvphq-rollback-mod/build.gradle"))
                && Files.isRegularFile(sibling("pvphq-rollback-mod/stonecutter.gradle"));
    }

    static boolean corePresent() {
        return Files.isRegularFile(core("build.gradle.kts"));
    }

    @Test
    void devSetupNeverLeavesAServerHoldingAPluginThisBuildDidNotProduce() throws IOException {
        String devenv = read(rollbackRoot().resolve("devenv.gradle"));

        assertFalse(devenv.contains("jackpot-edge.jar').bytes = pluginJar.bytes"),
                "an unchecked byte copy is how a run silently starts on the last jar that was"
                        + " there - the copy has to be verified and has to be able to fail");
        assertTrue(devenv.contains("ext.deployEdgePlugin"),
                "the deploy has to be one named step, not a line buried in the provisioning loop");
        assertTrue(devenv.contains("could not clear the Paper remap cache"),
                "the remap cache is keyed by plugin FILE NAME, not by content. A stale entry has"
                        + " previously made a dev server start with no plugin at all, so failing"
                        + " to clear it has to fail the task rather than pass silently");
        assertTrue(devenv.contains("SHA-256"),
                "a length check alone cannot tell two builds of the same jar apart");
        assertTrue(devenv.contains("simFenceOf"),
                "devSetup should print the protocol version it just deployed, so an operator can"
                        + " compare a client against it without opening a jar");
        assertTrue(devenv.contains("verifyEmbeddedSimVersion"),
                "devSetup must not be able to deploy a plugin whose embedded sim-core is stale");

        int deploy = devenv.indexOf("ext.deployEdgePlugin");
        int setup = devenv.indexOf("tasks.register('devSetup')");
        assertTrue(deploy >= 0 && setup > deploy);
        String body = devenv.substring(deploy, setup);
        String[] refusals = {"does not exist or is", "could not delete the stray plugin jar",
                "could not replace ", "did not land", "byte for byte"};
        for (String refusal : refusals) {
            assertTrue(body.contains(refusal),
                    "deployEdgePlugin has to refuse on [" + refusal + "] rather than carry on: a"
                            + " half-deployed dev server aborts every match at the fence and says"
                            + " nothing about why");
        }
    }

    @Test
    void everyFenceRefusalInThisRepoNamesBothSidesAndAnArtifact() throws IOException {
        Path root = rollbackRoot();

        String relay = read(root.resolve("relay/src/main/java/me/nootnoot/relay/RelayServer.java"));
        assertTrue(relay.contains("VersionFence.report("),
                "the relay is the ONLY place that sees both peers versions, so it is the one that"
                        + " must print the full diagnosis");
        assertFalse(relay.contains("PROTOCOL VERSION MISMATCH for session"),
                "the old line printed two raw ints and named no artifact");

        String handoff = read(root.resolve(
                "edge/src/main/java/me/nootnoot/edge/EdgeModHandoff.java"));
        assertTrue(handoff.contains("VersionFence.report("));
        int at = handoff.indexOf("modVersion != Protocol.VERSION");
        assertTrue(at > 0);
        String branch = handoff.substring(at, Math.min(handoff.length(), at + 1400));
        assertTrue(branch.contains("refuse(player"),
                "warning and then handing the match over anyway trades a clean refusal for a"
                        + " mid-match desync, which is strictly harder to diagnose");
        assertTrue(branch.contains("return;"), "and the refusal has to actually stop the handoff");

        String link = read(root.resolve(
                "sim-core/src/main/java/me/nootnoot/sim/net/DirectLink.java"));
        assertTrue(link.contains("versionMismatch.incrementAndGet()"),
                "a direct link that silently drops every mismatched hello looks exactly like a"
                        + " firewall problem");
        assertTrue(link.contains("versionMismatchDiagnosis"),
                "and the count is useless unless the peer version is kept with it");

        String plugin = read(root.resolve("edge/src/main/java/me/nootnoot/edge/EdgePlugin.java"));
        assertTrue(plugin.contains("versionMismatchDiagnosis()"),
                "something has to actually read that diagnosis out to the log");
    }

    @Test
    @EnabledIf("corePresent")
    void mcleaguesCoreLeavesExactlyOneInstallablePluginJar() throws IOException {
        String build = read(core("build.gradle.kts"));
        assertTrue(build.contains("verifyOnePluginJar"),
                "two jars side by side in build/libs is how the un-shaded one gets installed");
        assertTrue(build.contains("thinlibs"),
                "the thin jar is a by-product; it must not sit in build/libs beside mcleagues.jar");
        assertTrue(build.contains("could not delete"),
                "if the stray jar cannot be removed the build has to refuse, not shrug");
        assertTrue(build.contains("walkTopDown"),
                "moving the thin jar to build/thinlibs took it out of a build/libs-only check"
                        + " without making it any less installable, so the check reported one"
                        + " installable plugin while a second one sat one directory over. The"
                        + " verifier has to read the whole build directory");
        assertTrue(build.contains("paper-plugin.yml"),
                "and it has to decide what a server would load by the plugin descriptor, not by"
                        + " which directory the jar happens to be in");
        assertTrue(build.contains("exclude(\"plugin.yml\", \"paper-plugin.yml\")"),
                "the by-product itself has to stop being a plugin. Relocating it only moves the"
                        + " trap; a jar with no descriptor is not loadable wherever it ends up");
        assertTrue(build.contains("verifyRollbackFence"),
                "core carries EXPECTED_VERSION by hand, so it needs a check that compares it with"
                        + " the sim-core this tree compiles to");
        assertTrue(build.contains("A check that proves nothing is a"),
                "verifyRollbackFence must fail when jackpot-rollback is not beside it, not pass");
    }

    @Test
    @EnabledIf("corePresent")
    void coreNamesTheStaleArtifactRatherThanAlwaysBlamingThePlayer() throws IOException {
        String registry = read(core(
                "src/main/java/me/nootnoot/modules/practice/managers/RollbackModRegistry.java"));
        assertTrue(registry.contains("clientIsBehind"));
        assertTrue(registry.contains("Do not tell the player to update."),
                "a client AHEAD of the server means the SERVER was not redeployed; telling that"
                        + " player to update sends them in a circle");

        String handoff = read(core(
                "src/main/java/me/nootnoot/modules/practice/managers/RollbackHandoffManager.java"));
        assertTrue(handoff.contains("RollbackModRegistry.report("),
                "the mod gate logged three bare ints and no artifact");
        assertTrue(handoff.contains("clientIsBehind(reported)"),
                "and the player-facing message has to branch on the same rule as the log");
    }

    @Test
    @EnabledIf("modPresent")
    void aModVersionBuildLearnsItsOwnLiveJarFromTheTaskThatWritesIt() throws IOException {
        String build = read(sibling("pvphq-rollback-mod/build.gradle"));
        assertTrue(build.contains("nestedSimFenceOf"),
                "a name-only sweep cannot see the trap: versions/26.2/build/libs holds a jar with"
                        + " exactly the right NAME and an out-of-date sim-core inside it");
        assertTrue(build.contains("it nests no sim-core at all"),
                "a mod jar with no nested sim-core hosts nothing and must not survive either");
        assertTrue(build.contains("could not delete the stale jar"),
                "the sweep has to refuse rather than leave an installable stale jar on disk");

        assertTrue(build.contains("loomx.modJar.get().archiveFile.get().asFile"),
                "the jar the sweep spares has to be the FILE the mod-jar task writes. Rebuilding"
                        + " that name from the version directory is a guess: stonecutter lets a"
                        + " version directory be named something other than its Minecraft version,"
                        + " and the moment it is, the guess spares the stale pvphq-mod-<dir>.jar"
                        + " and deletes the current jar sitting beside it");
        assertFalse(build.contains("${target.name}.jar"),
                "which is exactly the guess that has to be gone");
        assertTrue(build.contains("ownPrefix"),
                "and the sweep has to know which files are its own before it deletes anything");
    }

    @Test
    @EnabledIf("modPresent")
    void aVersionBuildReportsAnotherVersionsJarsInsteadOfDeletingThem() throws IOException {
        String build = read(sibling("pvphq-rollback-mod/build.gradle"));
        assertTrue(build.contains("build/devlibs"),
                "a devlibs jar carries fabric.mod.json and the mixin config, so a client loads it,"
                        + " and it is unremapped and nests no sim-core. It has to be accounted for"
                        + " somewhere rather than be invisible to every listing and fence");
        assertFalse(build.contains("liveDevlibs"),
                "versions/<mc>/build/devlibs holds exactly one jar and it is always that version's"
                        + " LIVE remapJar input, so the old cross-version devlibs sweep could only"
                        + " ever delete a current file. There is nothing left for a sibling to"
                        + " tell apart");
        assertTrue(build.contains("ANOTHER VERSION OWNS THIS"),
                "a build that finds a stale or non-installable jar under a version it is not"
                        + " producing has to say so. Deleting it instead reaches into another"
                        + " project's output directory - devlibs is remapJar INPUT and"
                        + " processIncludeJars is loom staging that may be filling right now -"
                        + " which is how a current jar disappears in the middle of a build");
        assertTrue(build.contains("gradlew verifyModJars"),
                "and the report is only useful if it names the check that turns it into a"
                        + " failure");

        String stonecutter = read(sibling("pvphq-rollback-mod/stonecutter.gradle"));
        assertTrue(stonecutter.contains("protocolVersion "),
                "gradlew modJars lists the jars a human will pick from, so each line has to carry"
                        + " the number that jar will actually send at the handshake");
        assertTrue(stonecutter.contains("NOT INSTALLABLE"),
                "gradlew modJars is the list a human picks from, so a devlibs jar that is on disk"
                        + " has to appear on it - marked as the thing you must never install"
                        + " - rather than be invisible there and installable in the file browser");
        int listing = stonecutter.indexOf("tasks.register('modJars')");
        int verify = stonecutter.indexOf("tasks.register('verifyModJars')");
        assertTrue(listing >= 0 && verify > listing);
        assertTrue(stonecutter.substring(listing, verify).contains("build/devlibs"),
                "the listing task specifically, not just the verify task");
        assertTrue(stonecutter.substring(verify).contains("build/devlibs"),
                "and the verify task too, so a CI run that never lists still says something");

        String verifyBody = stonecutter.substring(verify);
        assertTrue(verifyBody.contains("notInstallable")
                        && verifyBody.contains("jars that are NOT INSTALLABLE and are still on"),
                "verifyModJars used to log NOT INSTALLABLE and then pass, so the trap survived"
                        + " every green run and got recorded as known and harmless. Naming a"
                        + " client-loadable jar and then reporting success is worse than not"
                        + " looking: the jar has to fail the check");
        assertTrue(verifyBody.contains("gradlew clearDevlibs"),
                "and the failure has to name the step that clears it. A devlibs jar is remapJar"
                        + " INPUT, so no version build can delete its own without losing the file"
                        + " the next build reads and none may reach into a sibling's output"
                        + " directory - which makes removing them a deliberate operator step, and"
                        + " a failure that does not name that step is a dead end");

        int clear = stonecutter.indexOf("tasks.register('clearDevlibs')");
        assertTrue(clear >= 0 && clear < verify,
                "clearDevlibs has to exist, or verifyModJars refuses a tree with no way out of it");
        assertTrue(stonecutter.substring(clear, verify).contains("could not delete the devlibs jar"),
                "and if the jar cannot be removed the clear step has to refuse rather than report"
                        + " success over a file that is still on disk");
    }

    @Test
    void theRollbackJarCheckRemovesWhatItRejects() throws IOException {
        String jarcheck = read(rollbackRoot().resolve("jarcheck.gradle"));
        assertTrue(jarcheck.contains("DELETED, because a jar a human can still find is a jar a"),
                "reporting a stale jar and leaving it in build/libs still lets someone install it");
        assertTrue(jarcheck.contains("AND IT COULD NOT BE DELETED"),
                "and when it cannot be removed the operator has to be told which file is stuck");
        assertTrue(jarcheck.contains("it.name == 'check'"),
                "the check only helps if an ordinary build runs it");
    }
}
