package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import me.nootnoot.sim.state.Arena;
import org.junit.jupiter.api.Test;

class ArenaHashTest {

    private static Arena build(ArenaCodec.Snapshot s) {
        return ArenaCodec.toArena(ArenaCodec.decode(ArenaCodec.encode(s)));
    }

    @Test
    void aDecodedArenaHashesEqualToItself() {
        Arena arena = build(ArenaFixtures.full());
        assertEquals(ArenaHash.of(arena), ArenaHash.of(arena));
    }

    @Test
    void twoIndependentDecodesOfTheSameBytesHashEqual() {
        byte[] blob = ArenaCodec.encode(ArenaFixtures.full());
        Arena left = ArenaCodec.toArena(ArenaCodec.decode(blob));
        Arena right = ArenaCodec.toArena(ArenaCodec.decode(blob));
        assertEquals(ArenaHash.of(left), ArenaHash.of(right));
    }

    @Test
    void reEncodedBytesStillHashTheSameArena() {
        byte[] blob = ArenaCodec.encode(ArenaFixtures.full());
        byte[] again = ArenaCodec.encode(ArenaCodec.decode(blob));
        assertEquals(ArenaHash.of(ArenaCodec.toArena(ArenaCodec.decode(blob))),
                ArenaHash.of(ArenaCodec.toArena(ArenaCodec.decode(again))));
    }

    @Test
    void oneFlippedVoxelChangesTheHash() {
        long base = ArenaHash.of(build(ArenaFixtures.full()));

        int[][] moved = ArenaFixtures.copyBlocks();
        moved[0][1] = 1;
        assertNotEquals(base, ArenaHash.of(build(ArenaFixtures.withBlocks(moved))));

        int[][] removed = new int[ArenaFixtures.blocks().length - 1][];
        int[][] src = ArenaFixtures.copyBlocks();
        System.arraycopy(src, 1, removed, 0, removed.length);
        assertNotEquals(base, ArenaHash.of(build(ArenaFixtures.withBlocks(removed))));

        int[][] added = new int[src.length + 1][];
        System.arraycopy(src, 0, added, 0, src.length);
        added[src.length] = new int[]{3, 2, 3, ArenaFixtures.STONE};
        assertNotEquals(base, ArenaHash.of(build(ArenaFixtures.withBlocks(added))));
    }

    @Test
    void aMovedPartialBoxChangesTheHash() {
        long base = ArenaHash.of(build(ArenaFixtures.full()));
        int[][] blocks = ArenaFixtures.copyBlocks();
        for (int[] b : blocks) {
            if (b[3] == ArenaFixtures.SLAB) {
                b[0] = 3;
            }
        }
        assertNotEquals(base, ArenaHash.of(build(ArenaFixtures.withBlocks(blocks))));
    }

    @Test
    void aMovedDecorVoxelChangesTheHash() {
        long base = ArenaHash.of(build(ArenaFixtures.full()));
        int[][] blocks = ArenaFixtures.copyBlocks();
        for (int[] b : blocks) {
            if (b[3] == ArenaFixtures.TORCH) {
                b[2] = 3;
            }
        }
        assertNotEquals(base, ArenaHash.of(build(ArenaFixtures.withBlocks(blocks))));
    }

    @Test
    void aDifferentGroundYChangesTheHash() {
        ArenaCodec.Snapshot s = ArenaFixtures.full();
        ArenaCodec.Snapshot lifted = new ArenaCodec.Snapshot(s.sessionId(), s.baseX(), s.baseY(), s.baseZ(),
                s.sizeX(), s.sizeY(), s.sizeZ(), s.palette(), s.blocks(), s.geometry(), s.baseY() + 1.0);
        assertNotEquals(ArenaHash.of(build(s)), ArenaHash.of(build(lifted)));
    }

    @Test
    void aDifferentBaseOriginChangesTheHash() {
        ArenaCodec.Snapshot s = ArenaFixtures.full();
        ArenaCodec.Snapshot shifted = new ArenaCodec.Snapshot(s.sessionId(), s.baseX() + 1, s.baseY(),
                s.baseZ(), s.sizeX(), s.sizeY(), s.sizeZ(), s.palette(), s.blocks(), s.geometry(), s.groundY());
        assertNotEquals(ArenaHash.of(build(s)), ArenaHash.of(build(shifted)));
    }

    @Test
    void theHashIsAPureFunctionOfTheArenaBytes() {
        byte[] blob = ArenaCodec.encode(ArenaFixtures.full());
        assertEquals(ArenaHash.of(ArenaCodec.toArena(ArenaCodec.decode(blob))),
                ArenaHash.of(ArenaCodec.toArena(ArenaCodec.decode(blob))),
                "nothing outside the blob may reach the arena hash. The two peers agree on the"
                        + " arena by comparing this number, and the edge keys its arena cache by"
                        + " the sha256 of the blob - a hash that also depended on the match's"
                        + " game type would break both of those at once");
    }

    @Test
    void theSessionIdDoesNotAffectTheHash() {
        ArenaCodec.Snapshot s = ArenaFixtures.full();
        ArenaCodec.Snapshot other = new ArenaCodec.Snapshot(s.sessionId() + 1, s.baseX(), s.baseY(),
                s.baseZ(), s.sizeX(), s.sizeY(), s.sizeZ(), s.palette(), s.blocks(), s.geometry(), s.groundY());
        assertEquals(ArenaHash.of(build(s)), ArenaHash.of(build(other)));
    }

    @Test
    void theFlatArenaHashesDeterministically() {
        assertEquals(ArenaHash.of(Arena.flat(64.0)), ArenaHash.of(Arena.flat(64.0)));
        assertNotEquals(ArenaHash.of(Arena.flat(64.0)), ArenaHash.of(Arena.flat(65.0)));
    }
}
