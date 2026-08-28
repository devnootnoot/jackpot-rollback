package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.ItemGrid;
import me.nootnoot.sim.state.Loadout;
import org.junit.jupiter.api.Test;

class ItemEntityBoundsTest {
    private static final double GROUND_Y = 64.0;
    private static final int ITEM_ID = 4001;

    private static GameState duel(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        s.players[0].x = -80.0;
        s.players[1].x = 80.0;
        return s;
    }

    private static ItemEntityState drop(GameState s, double x, double y, double z, int itemId) {
        return drop(s, 0, x, y, z, itemId);
    }

    private static ItemEntityState drop(GameState s, int owner, double x, double y, double z, int itemId) {
        return ItemEntities.spawn(s, owner, x, y, z, 0.0, 0.0, 0.0, itemId, 1, 40);
    }

    private static int fill(GameState s, int owner, int n) {
        int made = 0;
        for (int i = 0; i < n; i++) {
            if (drop(s, owner, i * 0.01, GROUND_Y + 1.0, 0.0, ITEM_ID) != null) {
                made++;
            }
        }
        return made;
    }

    @Test
    void theItemListCannotGrowWithoutBound() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        fill(s, 0, ItemEntities.MAX_ITEMS * 4);
        fill(s, 1, ItemEntities.MAX_ITEMS * 4);
        fill(s, ItemEntities.NEUTRAL_OWNER, ItemEntities.MAX_ITEMS * 4);
        assertEquals(ItemEntities.MAX_ITEMS, s.items.size(),
                "an unbounded item list is re-simulated on every rollback, so it has to have a lid");
        assertEquals(ItemEntities.MAX_ITEMS,
                2 * ItemEntities.MAX_ITEMS_PER_OWNER + ItemEntities.MAX_NEUTRAL_ITEMS,
                "the three shares have to add up to the lid, or a share is a reservation nobody"
                        + " can spend and the global cap is reachable by one owner alone");
    }

    @Test
    void oneSideCannotSpendTheOtherSidesShareOfTheItemList() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);

        assertEquals(ItemEntities.MAX_ITEMS_PER_OWNER, fill(s, 0, ItemEntities.MAX_ITEMS * 4),
                "player 0 gets its own share and not one drop more");
        assertEquals(ItemEntities.MAX_ITEMS_PER_OWNER, fill(s, 1, ItemEntities.MAX_ITEMS_PER_OWNER),
                "and player 1's share is still there after player 0 spammed the world full."
                        + " A single first-come cap of " + ItemEntities.MAX_ITEMS + " is a denial"
                        + " of service: whoever drops fastest decides whether the opponent's death"
                        + " drops, block drops and tosses exist at all");
        assertEquals(ItemEntities.MAX_NEUTRAL_ITEMS,
                fill(s, ItemEntities.NEUTRAL_OWNER, ItemEntities.MAX_NEUTRAL_ITEMS),
                "and the unattributed drops keep theirs too");
    }

    @Test
    void theCapRefusesTheNewSpawnInsteadOfDeletingOneAlreadyInTheWorld() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < ItemEntities.MAX_ITEMS_PER_OWNER; i++) {
            ids.add(drop(s, i * 0.01, GROUND_Y + 1.0, 0.0, ITEM_ID).id);
        }
        assertEquals(0, s.itemsRefused, "nothing should have been refused yet");
        int oldest = ids.get(0);
        int newest = ids.get(ids.size() - 1);

        assertNull(drop(s, 1.0, GROUND_Y + 1.0, 0.0, ITEM_ID),
                "the over cap spawn has to say it did not happen, so the caller can keep the item");

        assertEquals(1, s.itemsRefused, "the refusal has to be visible in the state, not silent");
        assertEquals(ItemEntities.MAX_ITEMS_PER_OWNER, s.items.size(), "the cap must hold exactly");
        assertEquals(oldest, s.items.get(0).id,
                "the list is shared, so evicting the head deletes whatever the OPPONENT dropped"
                        + " first; a player who spams drops must not be able to delete it");
        assertEquals(newest, s.items.get(s.items.size() - 1).id,
                "and nothing at the tail moves either");
    }

    @Test
    void aRefusedDropStaysInTheInventoryInsteadOfVanishing() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, 5, TestKit.item().itemId(ITEM_ID));
        for (int i = 0; i < ItemEntities.MAX_ITEMS_PER_OWNER; i++) {
            drop(s, 60.0 + i * 3.0, GROUND_Y + 1.0, 0.0, ITEM_ID);
        }
        int held = Loadout.countAt(s.players[0], 0);
        assertTrue(held > 0, "precondition: player 0 is holding something to drop");

        for (int i = 0; i < 20; i++) {
            Simulation.tick(s, arena, Input.NONE.withDrop(i % 2 == 0, false), Input.NONE);
        }

        assertEquals(0, s.players[0].dropSeq, "no toss can have landed");
        assertEquals(held, Loadout.countAt(s.players[0], 0),
                "the world was full, so the drop is refused; consuming the stack first and then"
                        + " refusing the spawn would delete the item outright");
        assertEquals(ItemEntities.MAX_ITEMS_PER_OWNER, s.items.size(), "and the cap still holds");
    }

    @Test
    void theRefusalCounterIsChecksummedAndCarriedByCopyAndTheCodec() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        long before = Checksum.of(s);
        s.itemsRefused = 7;
        assertNotEquals(before, Checksum.of(s),
                "a cap that the checksum cannot see lets two peers refuse different spawns and agree");

        GameState c = s.copy();
        assertEquals(7, c.itemsRefused, "copy must carry the counter");
        assertEquals(Checksum.of(s), Checksum.of(c), "and the copy must hash the same");

        GameState round = GameStateFrame0Codec.decode(GameStateFrame0Codec.encode(s));
        assertEquals(7, round.itemsRefused, "the frame zero codec must round trip the counter");
    }

    @Test
    void twoPeersRefuseTheSameSpawns() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState a = duel(arena);
        GameState b = duel(arena);
        for (int i = 0; i < ItemEntities.MAX_ITEMS_PER_OWNER + 40; i++) {
            drop(a, i * 0.01, GROUND_Y + 1.0, 0.0, ITEM_ID);
            drop(b, i * 0.01, GROUND_Y + 1.0, 0.0, ITEM_ID);
        }
        assertEquals(40, a.itemsRefused, "the overflow count is a pure function of the spawns");
        assertEquals(Checksum.of(a), Checksum.of(b),
                "the cap has to be deterministic about WHICH spawns it refuses or it is a desync");
    }

    private static java.util.TreeSet<Long> keys(List<int[]> pairs) {
        java.util.TreeSet<Long> out = new java.util.TreeSet<>();
        for (int[] p : pairs) {
            out.add(((long) p[0] << 32) | p[1]);
        }
        return out;
    }

    private static List<int[]> quadraticPairs(GameState s) {
        List<int[]> out = new ArrayList<>();
        int n = s.items.size();
        for (int i = 0; i < n; i++) {
            ItemEntityState a = s.items.get(i);
            if (a.dead) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                ItemEntityState b = s.items.get(j);
                if (b.dead || b.entry != a.entry || b.damage != a.damage || b.itemId != a.itemId) {
                    continue;
                }
                double dx = a.x - b.x;
                double dz = a.z - b.z;
                if (dx * dx + dz * dz < 2.5 * 2.5 && Math.abs(a.y - b.y) < 0.5) {
                    out.add(new int[]{i, j});
                }
            }
        }
        return out;
    }

    @Test
    void theGridFindsEveryPairTheWholeListScanUsedTo() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        java.util.Random rng = new java.util.Random(0xC0FFEEL);
        for (int i = 0; i < 200; i++) {
            drop(s, i % 2, rng.nextDouble() * 12.0 - 6.0, GROUND_Y + rng.nextDouble() * 4.0,
                    rng.nextDouble() * 12.0 - 6.0, ITEM_ID);
        }
        ItemGrid grid = s.itemGrid;
        grid.markDirty();
        int[] near = new int[s.items.size()];
        List<int[]> found = new ArrayList<>();
        for (int i = 0; i < s.items.size(); i++) {
            ItemEntityState a = s.items.get(i);
            int m = grid.collectNear(s.items, a.x, a.z, near);
            for (int q = 0; q < m; q++) {
                int j = near[q];
                if (j <= i) {
                    continue;
                }
                ItemEntityState b = s.items.get(j);
                double dx = a.x - b.x;
                double dz = a.z - b.z;
                if (dx * dx + dz * dz < 2.5 * 2.5 && Math.abs(a.y - b.y) < 0.5) {
                    found.add(new int[]{i, j});
                }
            }
        }
        List<int[]> expected = quadraticPairs(s);
        assertTrue(expected.size() > 200,
                "the fixture has to be dense enough to have merge pairs at all: " + expected.size());
        assertEquals(keys(expected), keys(found),
                "the neighbourhood the grid walks must cover the whole merge radius, or the"
                        + " sub-quadratic scan silently stops merging stacks. The ORDER differs on"
                        + " purpose: the grid yields a bucket at a time, and union() always keeps"
                        + " the lower index as the root, so the partition it builds does not"
                        + " depend on the order the pairs arrive in");
    }

    @Test
    void stacksStillMergeAndTheMergeStillCentresThem() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        drop(s, 0.0, GROUND_Y, 0.0, ITEM_ID);
        drop(s, 1.0, GROUND_Y, 0.0, ITEM_ID);
        drop(s, 2.0, GROUND_Y, 0.0, ITEM_ID);
        drop(s, 40.0, GROUND_Y, 0.0, ITEM_ID);

        ItemEntities.tick(s, arena, Input.NONE, Input.NONE);

        int live = 0;
        int merged = 0;
        for (ItemEntityState e : s.items) {
            if (e.dead) {
                continue;
            }
            live++;
            if (e.count == 3) {
                merged++;
            }
        }
        assertEquals(2, live, "three neighbours become one stack, the far one stays on its own");
        assertEquals(1, merged, "the survivor carries all three counts");
    }

    @Test
    void aCellQueryUsesTheIndexAndStillAnswersExactly() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        drop(s, 3.5, GROUND_Y + 2.0, -4.5, ITEM_ID);
        drop(s, 30.25, GROUND_Y, 10.75, ITEM_ID);

        assertTrue(s.itemGrid.occupiedCell(s.items, 3, (int) (GROUND_Y + 2.0), -5),
                "the cell that holds the item has to read as occupied");
        assertTrue(s.itemGrid.occupiedCell(s.items, 30, (int) GROUND_Y, 10),
                "and so does the far one");
        assertFalse(s.itemGrid.occupiedCell(s.items, 4, (int) (GROUND_Y + 2.0), -5),
                "the neighbouring cell is empty, and a grid cell is wider than a block cell, so"
                        + " this is exactly where a bucket lookup without the exact re-test lies");
        assertFalse(s.itemGrid.occupiedCell(s.items, 3, (int) (GROUND_Y + 3.0), -5),
                "one block up is empty too");
    }

    @Test
    void theIndexSeesAnItemThatSpawnedAfterItWasBuilt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        drop(s, 0.5, GROUND_Y, 0.5, ITEM_ID);
        assertFalse(s.itemGrid.occupiedCell(s.items, 8, (int) GROUND_Y, 8),
                "precondition: nothing there yet, and the query builds the index");

        drop(s, 8.5, GROUND_Y, 8.5, ITEM_ID);
        assertTrue(s.itemGrid.occupiedCell(s.items, 8, (int) GROUND_Y, 8),
                "a drop that lands after the index was built must still be found");
    }

    @Test
    void theIndexIsRebuiltAfterTheItemsMove() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        ItemEntityState e = drop(s, 0.5, GROUND_Y + 6.0, 0.5, ITEM_ID);
        assertTrue(s.itemGrid.occupiedCell(s.items, 0, (int) (GROUND_Y + 6.0), 0),
                "precondition: the index is built and holds the item where it started");

        for (int i = 0; i < 10; i++) {
            ItemEntities.tick(s, arena, Input.NONE, Input.NONE);
        }
        assertTrue(e.y < GROUND_Y + 6.0, "the item has to have actually fallen");

        assertFalse(s.itemGrid.occupiedCell(s.items, 0, (int) (GROUND_Y + 6.0), 0),
                "a stale index would still be answering from where the item used to be");
        assertTrue(s.itemGrid.occupiedCell(s.items, 0, (int) Math.floor(e.y), 0),
                "and it has to be found where it now is");
    }

    @Test
    void aDeadEntityIsNeverReportedByTheIndex() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        ItemEntityState e = drop(s, 5.5, GROUND_Y, 5.5, ITEM_ID);
        assertTrue(s.itemGrid.occupiedCell(s.items, 5, (int) GROUND_Y, 5), "precondition");
        e.dead = true;
        assertFalse(s.itemGrid.occupiedCell(s.items, 5, (int) GROUND_Y, 5),
                "blasts kill items in place and only sweep them at the end of the tick, so the"
                        + " index must re-test dead rather than trust its buckets");
    }
}
