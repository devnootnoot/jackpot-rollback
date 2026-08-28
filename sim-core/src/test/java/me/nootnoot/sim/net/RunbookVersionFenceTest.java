package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunbookVersionFenceTest {

    private static final String HEADING = "inputBytes / checksumRev / protocolVersion";

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("RUNBOOK.md"))
                    && Files.isDirectory(p.resolve("sim-core"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    @Test
    void theRunbookPrintsTheTripleThisBuildActuallySends() throws IOException {
        Path root = repoRoot();
        assertTrue(root != null, "could not find RUNBOOK.md above " + Path.of("").toAbsolutePath());
        Path runbook = root.resolve("RUNBOOK.md");
        List<String> lines = Files.readAllLines(runbook, StandardCharsets.UTF_8);

        String triple = null;
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).contains(HEADING)) {
                triple = lines.get(i + 1).trim();
                break;
            }
        }
        assertTrue(triple != null, "no '" + HEADING + "' block in " + runbook);

        String[] cells = triple.split("\\s*/\\s*");
        assertEquals(3, cells.length, "the fence block is not a triple any more: " + triple);
        assertEquals(String.valueOf(InputCodec.BYTES), cells[0],
                "RUNBOOK.md quotes an inputBytes an operator will compare a live fleet against");
        assertEquals(String.valueOf(Protocol.CHECKSUM_REV), cells[1],
                "RUNBOOK.md quotes a checksumRev this build does not send");
        assertEquals(String.valueOf(Protocol.VERSION), cells[2],
                "RUNBOOK.md quotes a protocolVersion that is not"
                        + " (InputCodec.BYTES << 8) | (CHECKSUM_REV & 0xFF) = " + Protocol.VERSION
                        + ". An operator reads this triple mid-incident to decide whether two sides"
                        + " are on the same build, so a number that does not match its own stated"
                        + " formula is worse than no number at all.");
    }

    @Test
    void theRunbookAlsoSpellsTheArithmeticOutCorrectly() throws IOException {
        Path root = repoRoot();
        assertTrue(root != null);
        String text = Files.readString(root.resolve("RUNBOOK.md"), StandardCharsets.UTF_8);
        String worked = InputCodec.BYTES + " * 256 + " + Protocol.CHECKSUM_REV + " = "
                + Protocol.VERSION;
        assertTrue(text.contains(worked),
                "RUNBOOK.md should show the multiplication worked through (" + worked + ") so the"
                        + " operator can check the number instead of trusting it");
    }
}
