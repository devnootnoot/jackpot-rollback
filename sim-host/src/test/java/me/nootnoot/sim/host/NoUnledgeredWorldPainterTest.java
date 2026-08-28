package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class NoUnledgeredWorldPainterTest {

    private static Path renderer() {
        Path here = Path.of("").toAbsolutePath();
        Path jackpot = here.getParent() == null ? null : here.getParent().getParent();
        return jackpot == null ? Path.of("nowhere")
                : jackpot.resolve("pvphq-rollback-mod/src/main/java/me/nootnoot/rollback/client/McSimRenderer.java");
    }

    static boolean rendererPresent() {
        return Files.exists(renderer());
    }

    @Test
    @EnabledIf("rendererPresent")
    void theHardBoundedAnchorDebounceIsGone() throws IOException {
        String body = Files.readString(renderer());
        assertTrue(!body.contains("detonatedAnchorDebounce") && !body.contains("ANCHOR_GLITCH_DEBOUNCE"),
                "the anchor debounce is back. It hid a picture whose writer was never named, and it gave "
                        + "up after six ticks - so on any timing slower than that the anchor kept its "
                        + "picture again. Name the writer through PaintedCells instead.");
    }

    @Test
    @EnabledIf("rendererPresent")
    void everyWorldWriteGoesThroughTheOneLedgeredSeam() throws IOException {
        List<String> raw = new ArrayList<>();
        String[] lines = Files.readString(renderer()).split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains("client.level.setBlock(")) {
                continue;
            }
            boolean insideSeam = i > 0 && lines[i - 1].contains("private void paint(long key");
            for (int back = 1; back <= 4 && !insideSeam; back++) {
                if (i - back >= 0 && lines[i - back].contains("int painter")) {
                    insideSeam = true;
                }
            }
            boolean sweepFallback = false;
            for (int back = 1; back <= 8 && !sweepFallback; back++) {
                if (i - back >= 0 && lines[i - back].contains("paintedCells.owned(ck)")) {
                    sweepFallback = true;
                }
            }
            if (!insideSeam && !sweepFallback) {
                raw.add("line " + (i + 1) + ": " + lines[i].trim());
            }
        }
        assertTrue(raw.isEmpty(),
                "these writes bypass the ledger, so the cell's owner cannot see them and its cache will "
                        + "keep saying the cell is already shown: " + raw);
    }

    @Test
    @EnabledIf("rendererPresent")
    void noReconcilerStillDecidesWhatWasUnderneathByReadingTheLiveWorld() throws IOException {
        String body = Files.readString(renderer());
        assertEquals(0, count(body, "placedCover.putIfAbsent(k, client.level.getBlockState("),
                "the placed-block cover must go through coverAt, or it memorises a sibling's paint as the "
                        + "world underneath and restores it into a cell nothing owns");
        assertTrue(body.contains("private BlockState coverAt(long key, BlockPos pos)"),
                "there must be exactly one place that answers what was under a cell");
    }

    private static int count(String body, String needle) {
        int n = 0;
        int at = body.indexOf(needle);
        while (at >= 0) {
            n++;
            at = body.indexOf(needle, at + needle.length());
        }
        return n;
    }
}
