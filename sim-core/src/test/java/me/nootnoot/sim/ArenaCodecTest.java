package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import me.nootnoot.sim.state.Arena;
import org.junit.jupiter.api.Test;

class ArenaCodecTest {

    @Test
    void roundTripPreservesEveryField() {
        ArenaCodec.Snapshot src = ArenaFixtures.full();
        ArenaCodec.Snapshot back = ArenaCodec.decode(ArenaCodec.encode(src));

        assertEquals(src.sessionId(), back.sessionId());
        assertEquals(src.baseX(), back.baseX());
        assertEquals(src.baseY(), back.baseY());
        assertEquals(src.baseZ(), back.baseZ());
        assertEquals(src.sizeX(), back.sizeX());
        assertEquals(src.sizeY(), back.sizeY());
        assertEquals(src.sizeZ(), back.sizeZ());
        assertArrayEquals(src.palette(), back.palette());
        assertArrayEquals(src.blocks(), back.blocks());
        assertEquals(src.groundY(), back.groundY());
        assertEquals(src.geometry().length, back.geometry().length);
        for (int i = 0; i < src.geometry().length; i++) {
            ArenaCodec.PaletteEntry a = src.geometry()[i];
            ArenaCodec.PaletteEntry b = back.geometry()[i];
            assertEquals(a.kind(), b.kind());
            assertEquals(a.resistance(), b.resistance());
            assertEquals(a.blockItemId(), b.blockItemId());
            assertEquals(a.dropItemId(), b.dropItemId());
            assertEquals(a.partialBoxes().length, b.partialBoxes().length);
            for (int k = 0; k < a.partialBoxes().length; k++) {
                assertArrayEquals(a.partialBoxes()[k], b.partialBoxes()[k]);
            }
        }
    }

    @Test
    void reEncodingADecodedBlobIsByteStable() {
        byte[] first = ArenaCodec.encode(ArenaFixtures.full());
        byte[] second = ArenaCodec.encode(ArenaCodec.decode(first));
        assertArrayEquals(ArenaFixtures.inflate(first), ArenaFixtures.inflate(second));
    }

    @Test
    void baseFormatIsByteIdenticalToTheLegacyEncoder() {
        byte[] legacy = ArenaFixtures.legacyEncode(ArenaFixtures.SESSION_ID,
                ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y, ArenaFixtures.BASE_Z,
                ArenaFixtures.SIZE_X, ArenaFixtures.SIZE_Y, ArenaFixtures.SIZE_Z,
                ArenaFixtures.palette(), ArenaFixtures.blocks());
        byte[] shared = ArenaCodec.encode(ArenaFixtures.legacyOnly());
        assertArrayEquals(ArenaFixtures.inflate(legacy), ArenaFixtures.inflate(shared));
    }

    @Test
    void legacyBytesDecodeThroughTheSharedCodec() {
        byte[] legacy = ArenaFixtures.legacyEncode(ArenaFixtures.SESSION_ID,
                ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y, ArenaFixtures.BASE_Z,
                ArenaFixtures.SIZE_X, ArenaFixtures.SIZE_Y, ArenaFixtures.SIZE_Z,
                ArenaFixtures.palette(), ArenaFixtures.blocks());
        ArenaCodec.Snapshot s = ArenaCodec.decode(legacy);

        assertEquals(ArenaFixtures.SESSION_ID, s.sessionId());
        assertArrayEquals(ArenaFixtures.palette(), s.palette());
        assertArrayEquals(ArenaFixtures.blocks(), s.blocks());
        assertNull(s.groundY());
        assertNull(s.geometry());
        assertFalse(s.hasGeometry());
        assertEquals(ArenaFixtures.BASE_Y, s.resolvedGroundY());
    }

    @Test
    void theTrailingExtensionIsInvisibleToLegacyReaders() {
        byte[] shared = ArenaCodec.encode(ArenaFixtures.full());
        ArenaFixtures.LegacyBlocks legacy = ArenaFixtures.legacyDecode(shared);

        assertEquals(ArenaFixtures.SESSION_ID, legacy.sessionId());
        assertEquals(ArenaFixtures.BASE_X, legacy.baseX());
        assertEquals(ArenaFixtures.BASE_Y, legacy.baseY());
        assertEquals(ArenaFixtures.BASE_Z, legacy.baseZ());
        assertEquals(ArenaFixtures.SIZE_X, legacy.sizeX());
        assertEquals(ArenaFixtures.SIZE_Y, legacy.sizeY());
        assertEquals(ArenaFixtures.SIZE_Z, legacy.sizeZ());
        assertArrayEquals(ArenaFixtures.palette(), legacy.palette());
        assertArrayEquals(ArenaFixtures.blocks(), legacy.blocks());
    }

    @Test
    void listOverloadMatchesTheSnapshotOverload() {
        byte[] fromLists = ArenaCodec.encode(ArenaFixtures.SESSION_ID,
                ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y, ArenaFixtures.BASE_Z,
                ArenaFixtures.SIZE_X, ArenaFixtures.SIZE_Y, ArenaFixtures.SIZE_Z,
                Arrays.asList(ArenaFixtures.palette()), Arrays.asList(ArenaFixtures.blocks()));
        byte[] fromSnapshot = ArenaCodec.encode(ArenaFixtures.legacyOnly());
        assertArrayEquals(ArenaFixtures.inflate(fromSnapshot), ArenaFixtures.inflate(fromLists));
    }

    @Test
    void toArenaBuildsTheVoxelGridAndPartialBoxes() {
        Arena arena = ArenaCodec.toArena(ArenaCodec.decode(ArenaCodec.encode(ArenaFixtures.full())));

        assertTrue(arena.hasVoxelGrid());
        assertEquals(ArenaFixtures.BASE_Y, arena.groundY);
        assertEquals(ArenaFixtures.BASE_X, arena.baseX());
        assertEquals(ArenaFixtures.SIZE_X * ArenaFixtures.SIZE_Y * ArenaFixtures.SIZE_Z,
                arena.voxelCellCount());

        assertTrue(arena.isSolidVoxel(ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y, ArenaFixtures.BASE_Z));
        assertTrue(arena.isSolidVoxel(ArenaFixtures.BASE_X + 1, ArenaFixtures.BASE_Y + 1,
                ArenaFixtures.BASE_Z + 1));
        assertFalse(arena.isSolidVoxel(ArenaFixtures.BASE_X + 2, ArenaFixtures.BASE_Y + 1,
                ArenaFixtures.BASE_Z + 2));
        assertTrue(arena.isDecorVoxel(ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y + 1, ArenaFixtures.BASE_Z));

        assertEquals(1, arena.partialBoxes().length);
        assertEquals(ArenaFixtures.BASE_Y + 1 + 0.5, arena.partialBoxes()[0].maxY);

        assertEquals(1200.0f, arena.voxelResistance(ArenaFixtures.BASE_X + 1,
                ArenaFixtures.BASE_Y + 1, ArenaFixtures.BASE_Z + 1));
        assertEquals(Arena.DEFAULT_VOXEL_RESISTANCE,
                arena.voxelResistance(ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y, ArenaFixtures.BASE_Z));
        assertEquals(11, arena.voxelDropItem(ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y, ArenaFixtures.BASE_Z));
        assertEquals(14, arena.voxelDropItem(ArenaFixtures.BASE_X + 1, ArenaFixtures.BASE_Y + 1,
                ArenaFixtures.BASE_Z + 1));
    }

    @Test
    void theVoxelGridIsSolidAtTheCoordinateTheEdgePastesEachBlockTo() {
        int baseX = -50;
        int baseY = -66;
        int baseZ = 17;
        int sizeX = 7;
        int sizeY = 3;
        int sizeZ = 5;
        int[][] blocks = {{6, 0, 0, 0}, {0, 2, 4, 0}, {1, 1, 3, 0}};
        ArenaCodec.Snapshot src = new ArenaCodec.Snapshot(ArenaFixtures.SESSION_ID,
                baseX, baseY, baseZ, sizeX, sizeY, sizeZ,
                new String[]{"minecraft:stone"}, blocks,
                new ArenaCodec.PaletteEntry[]{new ArenaCodec.PaletteEntry(
                        ArenaCodec.KIND_FULL_CUBE, 6.0f, ArenaFixtures.STONE_ITEM, 11,
                        new double[0][])},
                (double) baseY);
        Arena arena = ArenaCodec.toArena(ArenaCodec.decode(ArenaCodec.encode(src)));

        assertEquals(sizeX * sizeY * sizeZ, arena.voxelCellCount());
        for (int[] b : blocks) {
            assertTrue(arena.isSolidVoxel(baseX + b[0], baseY + b[1], baseZ + b[2]),
                    "EdgeArenaPaster writes this block to baseX+dx, baseY+dy, baseZ+dz");
        }
        assertTrue(arena.isSolidVoxel(-44, -66, 17));
        assertTrue(arena.isSolidVoxel(-50, -64, 21));
        assertTrue(arena.isSolidVoxel(-49, -65, 20));

        assertFalse(arena.isSolidVoxel(-43, -66, 17));
        assertFalse(arena.isSolidVoxel(-44, -65, 17));
        assertFalse(arena.isSolidVoxel(-44, -66, 18));
        assertFalse(arena.isSolidVoxel(baseX + 4, baseY + 2, baseZ));
        assertFalse(arena.isSolidVoxel(baseX, baseY + 4, baseZ + 2));

        int solid = 0;
        for (int x = 0; x < sizeX; x++) {
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    if (arena.isSolidVoxel(baseX + x, baseY + y, baseZ + z)) {
                        solid++;
                    }
                }
            }
        }
        assertEquals(blocks.length, solid, "a stride or base mismatch would light up other cells");
    }

    @Test
    void everyVoxelCarriesItsOwnPaletteBlockItemNotJustItsDrop() {
        ArenaCodec.Snapshot s = ArenaCodec.decode(ArenaCodec.encode(ArenaFixtures.full()));
        Arena arena = ArenaCodec.toArena(s);

        assertEquals(ArenaFixtures.STONE_ITEM,
                arena.voxelBlockItem(ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y, ArenaFixtures.BASE_Z),
                "the sim gates arena mining and blasting on the block's OWN item id. Reading the"
                        + " drop id instead (11 here) would compare a cobblestone against a"
                        + " stone whitelist and refuse the one block the game type allows");
        assertEquals(11, arena.voxelDropItem(ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y,
                ArenaFixtures.BASE_Z), "and the drop id stays what it was");
        assertEquals(ArenaFixtures.OBSIDIAN_ITEM, arena.voxelBlockItem(
                ArenaFixtures.BASE_X + 1, ArenaFixtures.BASE_Y + 1, ArenaFixtures.BASE_Z + 1));
    }

    @Test
    void noGameTypeRuleIsBakedIntoTheDecodedArena() {
        ArenaCodec.Snapshot s = ArenaCodec.decode(ArenaCodec.encode(ArenaFixtures.full()));
        Arena arena = ArenaCodec.toArena(s);

        assertEquals(1200.0f, arena.voxelResistance(ArenaFixtures.BASE_X + 1,
                ArenaFixtures.BASE_Y + 1, ArenaFixtures.BASE_Z + 1),
                "the arena is content-addressed: the edge caches it by the sha256 of these very"
                        + " bytes and both peers agree on it by ArenaHash. A per-match GameType"
                        + " rule folded in here would make one cached arena serve two matches that"
                        + " need different numbers, and would make the peer hash depend on"
                        + " something that is not in the blob");
        assertEquals(14, arena.voxelDropItem(ArenaFixtures.BASE_X + 1, ArenaFixtures.BASE_Y + 1,
                ArenaFixtures.BASE_Z + 1));
    }

    @Test
    void anOversizedVolumeFallsBackToTheFlatBoxArena() {
        ArenaCodec.Snapshot s = new ArenaCodec.Snapshot(ArenaFixtures.SESSION_ID,
                ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y, ArenaFixtures.BASE_Z,
                65535, 65535, 65535, ArenaFixtures.palette(), ArenaFixtures.blocks(),
                ArenaFixtures.geometry(), (double) ArenaFixtures.BASE_Y);
        Arena arena = ArenaCodec.toArena(ArenaCodec.decode(ArenaCodec.encode(s)));

        assertFalse(arena.hasVoxelGrid());
        assertEquals(0, arena.voxelCellCount());
        assertEquals(18, arena.partialBoxes().length);
    }

    @Test
    void toArenaRefusesASnapshotWithoutGeometry() {
        ArenaCodec.Snapshot s = ArenaCodec.decode(ArenaCodec.encode(ArenaFixtures.legacyOnly()));
        assertThrows(IllegalArgumentException.class, () -> ArenaCodec.toArena(s));
    }

    @Test
    void toArenaRejectsABlockOutsideTheDeclaredGrid() {
        int[][] blocks = ArenaFixtures.copyBlocks();
        blocks[0][1] = ArenaFixtures.SIZE_Y + 5;
        ArenaCodec.Snapshot s = ArenaFixtures.withBlocks(blocks);
        assertThrows(IllegalArgumentException.class, () -> ArenaCodec.toArena(s));
    }

    @Test
    void encodeRejectsCoordinatesThatDoNotFitSixteenBits() {
        int[][] blocks = ArenaFixtures.copyBlocks();
        blocks[0][0] = 70000;
        ArenaCodec.Snapshot s = ArenaFixtures.withBlocks(blocks);
        assertThrows(IllegalArgumentException.class, () -> ArenaCodec.encode(s));
    }

    @Test
    void encodeRejectsAPaletteIndexOutOfRange() {
        int[][] blocks = ArenaFixtures.copyBlocks();
        blocks[0][3] = 99;
        ArenaCodec.Snapshot s = ArenaFixtures.withBlocks(blocks);
        assertThrows(IllegalArgumentException.class, () -> ArenaCodec.encode(s));
    }

    @Test
    void aTruncatedBlobIsRejectedCleanly() {
        byte[] blob = ArenaCodec.encode(ArenaFixtures.full());
        for (int cut : new int[]{1, 8, blob.length / 3, blob.length / 2, blob.length - 4}) {
            byte[] shortened = Arrays.copyOf(blob, cut);
            assertThrows(IllegalArgumentException.class, () -> ArenaCodec.decode(shortened),
                    "truncation to " + cut + " bytes must be rejected");
        }
    }

    @Test
    void garbageAndEmptyBlobsAreRejectedCleanly() {
        assertThrows(IllegalArgumentException.class, () -> ArenaCodec.decode(null));
        assertThrows(IllegalArgumentException.class, () -> ArenaCodec.decode(new byte[0]));
        byte[] junk = new byte[256];
        for (int i = 0; i < junk.length; i++) {
            junk[i] = (byte) (i * 31 + 7);
        }
        assertThrows(IllegalArgumentException.class, () -> ArenaCodec.decode(junk));
    }

    @Test
    void anUnknownTrailingSectionIsRejectedCleanly() {
        byte[] payload = ArenaFixtures.inflate(ArenaCodec.encode(ArenaFixtures.legacyOnly()));
        byte[] withJunk = Arrays.copyOf(payload, payload.length + 4);
        withJunk[payload.length] = (byte) 0xDE;
        withJunk[payload.length + 1] = (byte) 0xAD;
        withJunk[payload.length + 2] = (byte) 0xBE;
        withJunk[payload.length + 3] = (byte) 0xEF;
        byte[] blob = ArenaFixtures.gzip(withJunk);
        assertThrows(IllegalArgumentException.class, () -> ArenaCodec.decode(blob));
    }

    @Test
    void aGeometrySectionThatDisagreesWithThePaletteIsRejected() {
        String[] shortPalette = Arrays.copyOf(ArenaFixtures.palette(), 4);
        ArenaCodec.Snapshot bad = new ArenaCodec.Snapshot(ArenaFixtures.SESSION_ID,
                ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y, ArenaFixtures.BASE_Z,
                ArenaFixtures.SIZE_X, ArenaFixtures.SIZE_Y, ArenaFixtures.SIZE_Z,
                shortPalette, new int[0][], ArenaFixtures.geometry(), null);
        assertThrows(IllegalArgumentException.class, () -> ArenaCodec.encode(bad));
    }

    @Test
    void anExplicitGroundYOverridesBaseY() {
        ArenaCodec.Snapshot s = new ArenaCodec.Snapshot(ArenaFixtures.SESSION_ID,
                ArenaFixtures.BASE_X, ArenaFixtures.BASE_Y, ArenaFixtures.BASE_Z,
                ArenaFixtures.SIZE_X, ArenaFixtures.SIZE_Y, ArenaFixtures.SIZE_Z,
                ArenaFixtures.palette(), ArenaFixtures.blocks(), ArenaFixtures.geometry(), 12.5);
        ArenaCodec.Snapshot back = ArenaCodec.decode(ArenaCodec.encode(s));
        assertNotNull(back.groundY());
        assertEquals(12.5, back.resolvedGroundY());
        assertEquals(12.5, ArenaCodec.toArena(back).groundY);
    }
}
