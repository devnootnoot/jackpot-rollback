package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class VersionFenceTest {

    private static final String SIBLING_CORE_DIR = "../../../mcleagues/mcleagues-core";

    private static final String REGISTRY_WITHIN_CORE =
            "src/main/java/me/nootnoot/modules/practice/managers/RollbackModRegistry.java";

    private static final String DIR_PROPERTY = "mcleagues.core.dir";

    private static final String MODE_PROPERTY = "rollback.versionFence";

    private static final String MARKER = "EXPECTED_VERSION";

    static boolean absenceIsFatal(String mode, String ci) {
        if (mode != null && mode.equalsIgnoreCase("require")) {
            return true;
        }
        if (mode != null && mode.equalsIgnoreCase("skip")) {
            return false;
        }
        return ci != null && (ci.equalsIgnoreCase("true") || ci.equals("1"));
    }

    static Path registryFile(String configuredDir) {
        String dir = configuredDir == null || configuredDir.isBlank()
                ? SIBLING_CORE_DIR
                : configuredDir.trim();
        return Path.of(dir, REGISTRY_WITHIN_CORE).normalize();
    }

    @Test
    void protocolVersionPacksTheCodecWidthWithTheChecksumRev() {
        assertEquals((InputCodec.BYTES << 8) | Protocol.CHECKSUM_REV, Protocol.VERSION);
    }

    @Test
    void mcleaguesCoreCarriesTheSameVersionThisBuildWillSendAtHello() throws IOException {
        String configuredDir = System.getProperty(DIR_PROPERTY);
        Path core = registryFile(configuredDir);

        if (!Files.isRegularFile(core)) {
            String where = core.toAbsolutePath().toString();
            String how = configuredDir == null || configuredDir.isBlank()
                    ? "looked beside this repo at " + SIBLING_CORE_DIR
                    : "-D" + DIR_PROPERTY + "=" + configuredDir + " points at no such checkout";
            if (absenceIsFatal(System.getProperty(MODE_PROPERTY), System.getenv("CI"))) {
                fail("the version fence could not be compared and this environment requires it."
                        + " " + how + ", so " + where + " does not exist."
                        + " Check mcleagues-core out and pass -D" + DIR_PROPERTY + "=<its directory>,"
                        + " or waive the check on purpose with -D" + MODE_PROPERTY + "=skip."
                        + " Skipping silently is what let core and sim-core drift apart before:"
                        + " both sides then build, both sides shake hands, and they run different"
                        + " physics.");
            }
            Assumptions.abort("mcleagues-core is not checked out beside this repo (" + how + "),"
                    + " so the fence cannot be compared here. This is allowed for a standalone"
                    + " checkout only; set CI=true or -D" + MODE_PROPERTY + "=require to make the"
                    + " same absence a failure.");
        }

        String declaration = null;
        for (String line : Files.readAllLines(core, StandardCharsets.UTF_8)) {
            if (line.contains(MARKER) && line.contains("=")) {
                declaration = line;
                break;
            }
        }
        assertTrue(declaration != null, "could not find " + MARKER + " in " + core);

        String expected = "(" + InputCodec.BYTES + " << 8) | " + Protocol.CHECKSUM_REV;
        String actual = declaration.substring(declaration.indexOf('=') + 1).replace(";", "").trim();
        assertEquals(expected, actual,
                "mcleagues-core RollbackModRegistry." + MARKER + " is out of step with this build."
                        + " A mod or edge built from this sim-core sends Protocol.VERSION "
                        + Protocol.VERSION + ", so core must expect " + expected + "."
                        + " Two builds that disagree here run different physics but still shake hands.");
    }

    @Test
    void theFenceIsAHardFailureWhereverItIsMeantToGuardSomething() {
        assertTrue(absenceIsFatal(null, "true"),
                "on CI a missing mcleagues-core has to fail, not skip - CI is the one environment"
                        + " where nobody reads the skipped-test list");
        assertTrue(absenceIsFatal(null, "1"), "some runners spell CI as 1");
        assertTrue(absenceIsFatal("require", null),
                "-D" + MODE_PROPERTY + "=require has to bite off CI too, so the CI wiring can be"
                        + " tested from a developer machine");
        assertTrue(absenceIsFatal("REQUIRE", null), "the mode is not case sensitive");
    }

    @Test
    void onlyAStandaloneCheckoutOrAnExplicitWaiverIsAllowedToSkip() {
        assertFalse(absenceIsFatal(null, null),
                "a standalone checkout with no mcleagues-core beside it may still skip");
        assertFalse(absenceIsFatal(null, ""), "an empty CI variable is not CI");
        assertFalse(absenceIsFatal(null, "false"), "CI=false is not CI");
        assertFalse(absenceIsFatal("skip", "true"),
                "-D" + MODE_PROPERTY + "=skip is the only way to waive the fence on CI, and it has"
                        + " to be written down in the workflow where a reviewer can see it");
    }

    @Test
    void theCoreCheckoutCanBePointedAtFromOutsideThisRepository() {
        assertEquals(Path.of(SIBLING_CORE_DIR, REGISTRY_WITHIN_CORE).normalize(),
                registryFile(null),
                "with nothing configured the fence still looks for the sibling checkout it always did");
        assertEquals(Path.of(SIBLING_CORE_DIR, REGISTRY_WITHIN_CORE).normalize(),
                registryFile("   "),
                "a blank property is not a configured path");
        assertEquals(Path.of("/somewhere/mcleagues-core", REGISTRY_WITHIN_CORE).normalize(),
                registryFile("/somewhere/mcleagues-core"),
                "CI checks core out wherever it likes and passes -D" + DIR_PROPERTY);
    }
}
