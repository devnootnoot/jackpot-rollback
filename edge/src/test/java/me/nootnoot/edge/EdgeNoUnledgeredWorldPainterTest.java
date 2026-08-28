package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.host.PaintedCells;
import org.junit.jupiter.api.Test;

class EdgeNoUnledgeredWorldPainterTest {

    private static final Path MIRROR =
            Path.of("src/main/java/me/nootnoot/edge/EdgeWorldMirror.java");

    private static final Path CAGE =
            Path.of("src/main/java/me/nootnoot/edge/EdgeCageDisplay.java");

    private static final Path PLUGIN =
            Path.of("src/main/java/me/nootnoot/edge/EdgePlugin.java");

    private static final List<String> MUTATORS = List.of("set", "clear", "blank", "restore");

    private static String read(Path p) throws IOException {
        assertTrue(Files.isRegularFile(p), p + " is not where this guard expects it, so the guard"
                + " would pass without reading a painter. Fix the path, do not delete the test");
        return Files.readString(p, StandardCharsets.UTF_8);
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

    @Test
    void theSixTickAnchorDebounceIsGone() throws IOException {
        String body = read(MIRROR);
        assertTrue(!body.contains("anchorDebounce") && !body.contains("ANCHOR_DEBOUNCE"),
                "the edge's anchor debounce is back. It is the same defence the mod carried and"
                        + " the same one that failed there: it hides a picture whose writer was"
                        + " never named, and it gives up after six ticks, so on any timing slower"
                        + " than that the detonated anchor keeps its picture again. Name the"
                        + " writer through PaintedCells instead");
    }

    @Test
    void everyOverlayMutationNamesThePainterThatOwnsTheCell() throws IOException {
        List<String> raw = new ArrayList<>();
        for (Path p : List.of(MIRROR, CAGE)) {
            String[] lines = read(p).split("\n");
            for (int i = 0; i < lines.length; i++) {
                for (String method : MUTATORS) {
                    String call = "overlay." + method + "(";
                    int at = lines[i].indexOf(call);
                    if (at < 0) {
                        continue;
                    }
                    String args = lines[i].substring(at + call.length()).trim();
                    if (!args.startsWith("PaintedCells.")) {
                        raw.add(p.getFileName() + " line " + (i + 1) + ": " + lines[i].trim());
                    }
                }
            }
        }
        assertTrue(raw.isEmpty(),
                "these overlay writes do not say who is writing, so the cell has no owner: the"
                        + " other painter cannot be told it lost the cell, its cache goes on"
                        + " claiming the block is already shown, and a restore puts a sibling's"
                        + " picture back into a cell nobody owns: " + raw);
    }

    @Test
    void theTwoPaintersUseDistinctPainterIdentities() throws IOException {
        assertTrue(read(MIRROR).contains("PaintedCells.BLOCKS"),
                "the world mirror no longer paints as PaintedCells.BLOCKS");
        assertTrue(read(CAGE).contains("PaintedCells.CAGE"),
                "the cage no longer paints as PaintedCells.CAGE");
        assertTrue(PaintedCells.BLOCKS != PaintedCells.CAGE,
                "the two painter ids are equal, so the ledger cannot tell the cage and the"
                        + " placed-block mirror apart and every ownership test above is vacuous");
    }

    @Test
    void theMirrorForgetsACellAnotherPainterTook() throws IOException {
        String body = read(MIRROR);
        assertTrue(body.contains("overlay.lost(PaintedCells.BLOCKS)"),
                "the mirror never drains the cells it lost, so a cage cell painted over one of"
                        + " its blocks leaves `shown` claiming a picture the world stopped"
                        + " showing, and the descriptor comparison in reconcile() then refuses to"
                        + " redraw it for the rest of the match");
        assertTrue(body.contains("shown.remove("),
                "the drained cells are read and not acted on");
    }

    @Test
    void noReconcilerAsksTheLiveWorldWhatWasUnderneath() throws IOException {
        String body = read(MIRROR);
        assertEquals(0, count(body, "world.getBlockAt(x, y, z).getType()"),
                "the cover read must go through the overlay, which recorded the true block before"
                        + " ANY painter touched the cell. The live world is sometimes the other"
                        + " painter's paint, and memorising that as the cover is what restores a"
                        + " cage barrier - or a detonated anchor - into a cell nothing owns");
        assertTrue(body.contains("overlay.cover("),
                "there must be exactly one place that answers what was under a cell, and the"
                        + " mirror must ask it");
    }

    @Test
    void theCageAndTheMirrorPaintOnOneOverlay() throws IOException {
        String body = read(PLUGIN);
        int start = body.indexOf("private void startMatch(");
        assertTrue(start >= 0, "startMatch was renamed; this guard no longer reads it");
        int end = body.indexOf("\n    private ", start + 1);
        String startMatch = end < 0 ? body.substring(start) : body.substring(start, end);
        assertEquals(1, count(startMatch, "paster.newOverlay("),
                "startMatch builds more than one overlay for the same world. Each overlay keeps"
                        + " its own record of what was underneath, so the second one memorises"
                        + " the first one's paint as the original block and restoring either puts"
                        + " a sibling's picture back. One world, one overlay, one ledger");
    }
}
