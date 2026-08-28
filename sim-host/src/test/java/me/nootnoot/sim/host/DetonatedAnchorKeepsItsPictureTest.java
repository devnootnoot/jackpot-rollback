package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DetonatedAnchorKeepsItsPictureTest {

    private static final String AIR = "air";

    private static final String ANCHOR = "respawn_anchor";

    private static final String OBSIDIAN = "obsidian";

    private static final String CAGE_WALL = "cage_wall";

    private static final long CELL = 0x1234L;

    private static final class Canvas {

        private final Map<Long, String> shown = new HashMap<>();

        String at(long k) {
            return shown.getOrDefault(k, AIR);
        }

        void set(long k, String state) {
            shown.put(k, state);
        }
    }

    private static final class BlockDiff {

        private final Map<Long, String> rendered = new HashMap<>();

        private final Map<Long, String> cover = new HashMap<>();

        void place(Canvas canvas, long k, String state, String capturedCover) {
            if (!rendered.containsKey(k)) {
                cover.putIfAbsent(k, capturedCover);
            }
            canvas.set(k, state);
            rendered.put(k, state);
        }

        boolean wouldRepaint(long k, String want) {
            return !want.equals(rendered.get(k));
        }

        void forget(long k) {
            rendered.remove(k);
        }

        String coverOf(long k) {
            return cover.getOrDefault(k, AIR);
        }

        boolean owns(long k) {
            return rendered.containsKey(k);
        }
    }

    private static final class ArenaBreakDiff {

        private final Map<Long, String> restoreTo = new HashMap<>();

        void broke(long k, String capturedCover, Canvas canvas) {
            restoreTo.put(k, capturedCover);
            canvas.set(k, AIR);
        }

        void restored(long k, Canvas canvas) {
            String was = restoreTo.remove(k);
            if (was != null) {
                canvas.set(k, was);
            }
        }
    }

    @Test
    void theOldPipelineLetsTheCageStompAnAnchorTheBlockDiffStillBelievesItOwns() {
        Canvas canvas = new Canvas();
        BlockDiff blocks = new BlockDiff();

        blocks.place(canvas, CELL, ANCHOR, canvas.at(CELL));
        assertEquals(ANCHOR, canvas.at(CELL));

        canvas.set(CELL, CAGE_WALL);

        assertFalse(blocks.wouldRepaint(CELL, ANCHOR),
                "the cage wrote the cell without telling the placed-block cache, so the diff is still "
                        + "convinced the anchor is shown and will never draw it again");
        assertNotEquals(ANCHOR, canvas.at(CELL),
                "the player is looking at a cage wall while the sim still has a charged anchor here - "
                        + "a right-click reads the WORLD, sees no anchor, and sends no detonate, so the "
                        + "anchor neither explodes nor vanishes");
    }

    @Test
    void ledgerNoticesTheCellWasTakenAndRepaintsIt() {
        Canvas canvas = new Canvas();
        BlockDiff blocks = new BlockDiff();
        PaintedCells cells = new PaintedCells();

        blocks.place(canvas, CELL, ANCHOR, canvas.at(CELL));
        cells.paint(CELL, PaintedCells.BLOCKS);

        canvas.set(CELL, CAGE_WALL);
        cells.paint(CELL, PaintedCells.CAGE);

        assertTrue(cells.ownedByAnyoneElse(CELL, PaintedCells.BLOCKS),
                "the ledger is what turns an invisible stomp into something the owner can test for");

        if (cells.ownedByAnyoneElse(CELL, PaintedCells.BLOCKS)) {
            blocks.place(canvas, CELL, ANCHOR, canvas.at(CELL));
            cells.paint(CELL, PaintedCells.BLOCKS);
        }
        assertEquals(ANCHOR, canvas.at(CELL));
    }

    @Test
    void theOldPipelineMemorisesItsOwnPaintAsTheWorldUnderneathAndLeavesAnOrphan() {
        Canvas canvas = new Canvas();
        BlockDiff blocks = new BlockDiff();
        ArenaBreakDiff arena = new ArenaBreakDiff();

        canvas.set(CELL, OBSIDIAN);

        blocks.place(canvas, CELL, ANCHOR, canvas.at(CELL));
        assertEquals(OBSIDIAN, blocks.coverOf(CELL));

        blocks.forget(CELL);

        arena.broke(CELL, canvas.at(CELL), canvas);
        assertEquals(AIR, canvas.at(CELL));

        arena.restored(CELL, canvas);

        assertEquals(ANCHOR, canvas.at(CELL),
                "the arena reconciler read the live world to decide what was underneath, and the live "
                        + "world was the placed-block reconciler's own anchor. It has now restored an "
                        + "anchor into the arena.");
        assertFalse(blocks.owns(CELL),
                "and NO cache owns that anchor, so no diff will ever clear it - which is exactly the "
                        + "orphan the six-tick debounce was written to hunt, and exactly what it stops "
                        + "hunting once the bound expires");
    }

    @Test
    void ledgerRefusesToMemoriseASiblingsPaintAsTheWorldUnderneath() {
        Canvas canvas = new Canvas();
        BlockDiff blocks = new BlockDiff();
        ArenaBreakDiff arena = new ArenaBreakDiff();
        PaintedCells cells = new PaintedCells();

        canvas.set(CELL, OBSIDIAN);

        blocks.place(canvas, CELL, ANCHOR, cells.cover(CELL, canvas.at(CELL), null, AIR));
        cells.paint(CELL, PaintedCells.BLOCKS);
        assertEquals(OBSIDIAN, blocks.coverOf(CELL));

        blocks.forget(CELL);

        String captured = cells.cover(CELL, canvas.at(CELL), blocks.coverOf(CELL), AIR);
        assertEquals(OBSIDIAN, captured,
                "the cell is painted, so the live read is refused and the recorded cover is used");
        arena.broke(CELL, captured, canvas);
        cells.paint(CELL, PaintedCells.ARENA_BREAK);

        arena.restored(CELL, canvas);
        cells.release(CELL);

        assertEquals(OBSIDIAN, canvas.at(CELL), "the arena comes back as arena, not as an anchor");
        assertEquals(0, cells.size(), "and nothing is left owning a cell it did not draw");
    }

    @Test
    void anUnguardedFireAddCannotSilentlyStealACellAnyMore() {
        Canvas canvas = new Canvas();
        PaintedCells cells = new PaintedCells();
        Set<Long> renderedFires = new HashSet<>();

        canvas.set(CELL, ANCHOR);
        cells.paint(CELL, PaintedCells.BLOCKS);

        boolean fresh = renderedFires.add(CELL);
        assertTrue(fresh);
        boolean stealing = cells.ownedByAnyoneElse(CELL, PaintedCells.FIRE);
        assertTrue(stealing,
                "the fire add had no guard at all - it wrote whatever cell the sim listed. The ledger "
                        + "does not stop it writing; it makes the write VISIBLE to the cell's owner, "
                        + "which is the part that was missing");
    }

    @Test
    void aCellNobodyPaintedStillReadsFromTheLiveWorld() {
        PaintedCells cells = new PaintedCells();
        assertEquals("live", cells.cover(CELL, "live", "recorded", AIR));
        cells.paint(CELL, PaintedCells.FLUIDS);
        assertEquals("recorded", cells.cover(CELL, "live", "recorded", AIR));
        assertEquals(AIR, cells.cover(CELL, "live", null, AIR));
        cells.release(CELL);
        assertEquals("live", cells.cover(CELL, "live", "recorded", AIR));
    }
}
