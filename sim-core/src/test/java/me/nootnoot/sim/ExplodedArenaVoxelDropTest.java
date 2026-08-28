package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemEntityState;
import org.junit.jupiter.api.Test;

class ExplodedArenaVoxelDropTest {
    private static final double GROUND_Y = 64.0;

    private static final int BASE_X = 99;
    private static final int BASE_Y = 101;
    private static final int BASE_Z = 100;
    private static final int SX = 3;
    private static final int SY = 1;
    private static final int SZ = 2;

    private static final int AX = 99,  AY = 101, AZ = 100;
    private static final int BX = 101, BY = 101, BZ = 100;
    private static final int CX = 100, CY = 101, CZ = 101;

    private static final int DROP_ITEM_ID = 5;
    private static final float OBSIDIAN_RESISTANCE = 1200f;

    private static final double CX_BLAST = 100.5;
    private static final double CY_BLAST = 101.0;
    private static final double CZ_BLAST = 100.5;

    private record Scenario(Arena arena, GameState state) {
    }

    private static Arena makeArena() {
        boolean[] grid = new boolean[SX * SY * SZ];
        grid[idx(AX, AY, AZ)] = true;
        grid[idx(BX, BY, BZ)] = true;
        grid[idx(CX, CY, CZ)] = true;

        Map<Long, Float> resistance = new HashMap<>();
        resistance.put(BlockStore.key(CX, CY, CZ), OBSIDIAN_RESISTANCE);

        Map<Long, Integer> dropItem = new HashMap<>();
        dropItem.put(BlockStore.key(AX, AY, AZ), DROP_ITEM_ID);

        return new Arena(GROUND_Y, grid, BASE_X, BASE_Y, BASE_Z, SX, SY, SZ,
                new double[0][], resistance, dropItem);
    }

    private static Scenario makeScenario() {
        Arena arena = makeArena();
        GameState s = HarnessScenarios.duel(arena);
        s.vanillaBuild = true;
        s.players[0].x = -50;
        s.players[1].x = -50;
        return new Scenario(arena, s);
    }

    private static int idx(int x, int y, int z) {
        return ((z - BASE_Z) * SY + (y - BASE_Y)) * SX + (x - BASE_X);
    }

    private static int liveItemsWithId(GameState s, int itemId) {
        int n = 0;
        for (ItemEntityState e : s.items) {
            if (!e.dead && e.itemId == itemId) {
                n++;
            }
        }
        return n;
    }

    private static GameState blastedUntilItDropped() {
        for (int t = 0; t < 400; t++) {
            Scenario sc = makeScenario();
            GameState s = sc.state();
            s.tick = t;
            Combat.explode(s, sc.arena(), CX_BLAST, CY_BLAST, CZ_BLAST, Combat.CRYSTAL_POWER, 0, false);
            if (liveItemsWithId(s, DROP_ITEM_ID) == 1) {
                return s;
            }
        }
        throw new AssertionError("400 consecutive blasts all lost the 1-in-6 decay roll,"
                + " which is not a probability, it is a broken drop path");
    }

    private static int blastTickThatDropped() {
        for (int t = 0; t < 400; t++) {
            Scenario sc = makeScenario();
            GameState s = sc.state();
            s.tick = t;
            Combat.explode(s, sc.arena(), CX_BLAST, CY_BLAST, CZ_BLAST, Combat.CRYSTAL_POWER, 0, false);
            if (liveItemsWithId(s, DROP_ITEM_ID) == 1) {
                return t;
            }
        }
        throw new AssertionError("no blast tick in 400 tries dropped the mapped voxel item");
    }

    @Test
    void explodedVoxelBreaksAndDropsMappedItem() {
        GameState s = blastedUntilItDropped();

        assertTrue(s.brokenArena.contains(BlockStore.key(AX, AY, AZ)), "voxel A should be blasted away");
        assertEquals(1, liveItemsWithId(s, DROP_ITEM_ID),
                "voxel A must spawn exactly one drop with its mapped item id");
        ItemEntityState drop = null;
        for (ItemEntityState e : s.items) {
            if (e.itemId == DROP_ITEM_ID) {
                drop = e;
            }
        }
        assertNotNull(drop, "the mapped drop entity must exist");
        assertFalse(drop.dead, "the mapped drop must be a live entity");

        assertEquals(AX + 0.5, drop.x, 1.0E-9, "drop X is at the voxel cell centre");
        assertEquals(AY + 0.25, drop.y, 1.0E-9, "drop Y is at the voxel cell");
        assertEquals(AZ + 0.5, drop.z, 1.0E-9, "drop Z is at the voxel cell centre");
        assertEquals(DROP_ITEM_ID, drop.itemId, "drop carries the mapped raw item id");
        assertEquals(1, drop.count, "a single block drops one item");

        assertTrue(s.brokenArena.contains(BlockStore.key(BX, BY, BZ)), "voxel B should also be blasted away");

        assertEquals(1, s.items.size(), "exactly one item total: only the mapped voxel drops");

        assertFalse(s.brokenArena.contains(BlockStore.key(CX, CY, CZ)),
                "obsidian-resistance voxel must survive the blast");
    }

    @Test
    void theDropIsWhatMovesTheChecksum() {
        Scenario sc = makeScenario();
        GameState s = sc.state();
        s.tick = blastTickThatDropped();
        long before = Checksum.of(s);
        Combat.explode(s, sc.arena(), CX_BLAST, CY_BLAST, CZ_BLAST, Combat.CRYSTAL_POWER, 0, false);
        assertNotEquals(before, Checksum.of(s), "the spawned drop must change the state checksum");
    }

    @Test
    void aBlastThatLosesTheDecayRollStillBreaksTheVoxel() {
        int lost = -1;
        for (int t = 0; t < 400 && lost < 0; t++) {
            Scenario sc = makeScenario();
            GameState s = sc.state();
            s.tick = t;
            Combat.explode(s, sc.arena(), CX_BLAST, CY_BLAST, CZ_BLAST, Combat.CRYSTAL_POWER, 0, false);
            if (liveItemsWithId(s, DROP_ITEM_ID) == 0) {
                assertTrue(s.brokenArena.contains(BlockStore.key(AX, AY, AZ)),
                        "the decay roll decides whether the block DROPS, never whether it breaks");
                lost = t;
            }
        }
        assertTrue(lost >= 0, "no blast in 400 tries lost the roll, so the roll is not being made");
    }

    @Test
    void droppedVoxelItemIsChecksummedAndCopiesIndependently() {
        GameState s = blastedUntilItDropped();
        assertEquals(1, s.items.size(), "precondition: the blast left one drop");

        GameState c = s.copy();
        assertEquals(Checksum.of(s), Checksum.of(c), "copy must checksum-equal the original (items included)");

        long sBefore = Checksum.of(s);
        c.items.clear();
        assertEquals(1, s.items.size(), "copy must be independent of the original");
        assertEquals(sBefore, Checksum.of(s), "mutating the copy must not change the original's checksum");
        assertNotEquals(Checksum.of(s), Checksum.of(c), "differing item lists must checksum differently");
    }

    @Test
    void bothPeersAgreeOnTheDropFromIdenticalInputs() {
        int tick = blastTickThatDropped();
        Scenario pa = makeScenario();
        Scenario pb = makeScenario();
        pa.state().tick = tick;
        pb.state().tick = tick;
        Combat.explode(pa.state(), pa.arena(), CX_BLAST, CY_BLAST, CZ_BLAST, Combat.CRYSTAL_POWER, 0, false);
        Combat.explode(pb.state(), pb.arena(), CX_BLAST, CY_BLAST, CZ_BLAST, Combat.CRYSTAL_POWER, 0, false);
        assertEquals(Checksum.of(pa.state()), Checksum.of(pb.state()),
                "same blast on the same arena must converge (drop included)");
    }
}
