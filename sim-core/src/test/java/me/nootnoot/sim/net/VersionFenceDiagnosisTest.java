package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class VersionFenceDiagnosisTest {

    private static final int OLD = (67 << 8) | 140;
    private static final int NEW = (67 << 8) | 156;

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("RUNBOOK.md")) && Files.isDirectory(p.resolve("sim-core"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    @Test
    void theTripleUnpacksTheSameTwoNumbersProtocolPacksIn() {
        assertEquals(InputCodec.BYTES, VersionFence.inputBytes(Protocol.VERSION));
        assertEquals(Protocol.CHECKSUM_REV, VersionFence.checksumRev(Protocol.VERSION));
        assertEquals(InputCodec.BYTES + "/" + Protocol.CHECKSUM_REV + "/" + Protocol.VERSION,
                VersionFence.local());
    }

    @Test
    void theLowerChecksumRevIsTheOneCalledStale() {
        assertEquals(0, VersionFence.likelyStale(OLD, NEW));
        assertEquals(1, VersionFence.likelyStale(NEW, OLD));
    }

    @Test
    void aSideThatReportedNothingIsTheStaleSide() {
        assertEquals(0, VersionFence.likelyStale(VersionFence.UNKNOWN, NEW));
        assertEquals(1, VersionFence.likelyStale(NEW, VersionFence.UNKNOWN));
        assertEquals("none", VersionFence.triple(VersionFence.UNKNOWN));
    }

    @Test
    void aWiderInputCodecAtTheSameRevIsStillTheNewerBuild() {
        int narrow = (60 << 8) | 156;
        int wide = (67 << 8) | 156;
        assertEquals(0, VersionFence.likelyStale(narrow, wide),
                "InputCodec.BYTES only grows, so at an equal rev the narrower frame is the older"
                        + " build - without this the report would name the wrong artifact");
    }

    @Test
    void twoSidesThatAgreeAreNotDiagnosedAsADeploySkew() {
        assertEquals(VersionFence.UNKNOWN, VersionFence.likelyStale(NEW, NEW));
        String text = joined(VersionFence.report("ctx", "a", NEW, "b", NEW));
        assertTrue(text.contains("not a version skew"),
                "sending an operator to hunt for a stale jar when both sides match wastes the one"
                        + " thing they are short of at 2am");
        assertFalse(text.contains("LIKELY STALE"));
    }

    @Test
    void theReportNamesBothSidesValuesAndTheArtifactToRebuild() {
        String text = joined(VersionFence.report(
                "session 7 aborted", "slot 0 (the edge)", OLD, "slot 1 (the client)", NEW));

        assertTrue(text.contains("session 7 aborted"));
        assertTrue(text.contains("67/140/" + OLD), "the stale side's own triple must be in the log");
        assertTrue(text.contains("67/156/" + NEW), "the other side's triple must be in the log too");
        assertTrue(text.contains("LIKELY STALE: slot 0 (the edge)"),
                "naming the mismatch without naming a side is the bug this report exists to fix");
        assertTrue(text.contains("checksumRev 140"));
        assertTrue(text.contains("against 156"));
    }

    @Test
    void everyReportListsAllThreeArtifactsAndHowToCheckEachOne() {
        String text = joined(VersionFence.report("ctx", "a", OLD, "b", NEW));
        assertTrue(text.contains(VersionFence.EDGE_ARTIFACT));
        assertTrue(text.contains(VersionFence.MOD_ARTIFACT));
        assertTrue(text.contains(VersionFence.CORE_ARTIFACT));
        assertTrue(text.contains("verifyEmbeddedSimVersion"));
        assertTrue(text.contains("verifyModJars"));
        assertTrue(text.contains("verifyRollbackFence"));
        assertTrue(text.contains(VersionFence.local()),
                "the reader also needs the number THIS build sends, or they cannot tell whether"
                        + " the log they are reading came from the stale side");
    }

    @Test
    void aMissingVersionIsReportedAsMissingRatherThanAsANumber() {
        String text = joined(VersionFence.report(
                "ctx", "the opponent", VersionFence.UNKNOWN, "this build", NEW));
        assertTrue(text.contains("reported no version at all"));
        assertFalse(text.contains("checksumRev -1"),
                "-1 decoded as a triple is a number an operator would try to look up");
    }

    @Test
    void theRunbookActuallyHasTheSectionEveryReportPointsAt() throws IOException {
        Path root = repoRoot();
        assertTrue(root != null, "could not find RUNBOOK.md above " + Path.of("").toAbsolutePath());
        String runbook = Files.readString(root.resolve("RUNBOOK.md"), StandardCharsets.UTF_8);
        String pointer = joined(VersionFence.whereToLook());
        assertTrue(pointer.contains("A version fence mismatch"));
        assertTrue(runbook.contains("A version fence mismatch"),
                "the report sends an operator to a runbook section by name, so that section has to"
                        + " exist - a dangling pointer at 2am is worse than no pointer");
        for (String command : List.of("verifyEmbeddedSimVersion", "verifyModJars",
                "verifyRollbackFence")) {
            assertTrue(runbook.contains(command),
                    "RUNBOOK.md should spell out " + command + ", since the log line names it");
        }
    }

    @Test
    void theSessionAbortTextCarriesTheLocalTripleRatherThanJustSayingMismatch() throws IOException {
        Path root = repoRoot();
        assertTrue(root != null);
        String source = Files.readString(
                root.resolve("sim-core/src/main/java/me/nootnoot/sim/net/NetSession.java"),
                StandardCharsets.UTF_8);
        int at = source.indexOf("ABORT_VERSION_MISMATCH");
        assertTrue(at > 0);
        String branch = source.substring(at, Math.min(source.length(), at + 1200));
        assertTrue(branch.contains("VersionFence.local()"),
                "the abort a player sees has to name at least this side's fence; the peer's number"
                        + " is only known to the relay, and the text has to say so");
        assertTrue(branch.contains("VersionFence.EDGE_ARTIFACT")
                        && branch.contains("VersionFence.MOD_ARTIFACT"),
                "and it has to name the artifacts that could be stale");
    }
}
