package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class PlacementLegalityTest {
    private static final double GROUND_Y = 64.0;

    private static final int BLOCK_ITEM_ID = 700;
    private static final int OBSIDIAN_ITEM_ID = 701;
    private static final int STONE_ITEM_ID = 702;
    private static final int ANCHOR_ITEM_ID = 703;
    private static final int CRYSTAL_ITEM_ID = 704;
    private static final int ORE_ITEM_ID = 705;
    private static final int PICKAXE_ITEM_ID = 706;
    private static final int COBWEB_ITEM_ID = 707;

    private static final float SOFT_HARDNESS = 1f / 40f;

    private static GameState duel(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = arena.groundY;
        a.z = 0.5;
        a.onGround = true;
        s.players[1].x = 40.0;
        return s;
    }

    private static Input place(int x, int y, int z) {
        return Input.NONE.withBlockAction(Input.BLOCK_PLACE, x, y, z);
    }

    private static int giveBlock(GameState s, int slot, int count) {
        return TestKit.of(s).give(0, slot, count,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
    }

    @Test
    void aBlockCannotBePlacedInMidAirButLandsOnceSomethingTouchesTheCell() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        giveBlock(s, 0, 8);

        Simulation.tick(s, arena, place(1, 66, 0), Input.NONE);
        assertFalse(s.blocks.contains(1, 66, 0), "a cell touching nothing must refuse the placement");
        assertEquals(8, s.players[0].slotCount[0], "a refused placement must not consume the stack");

        s.blocks.place(1, 65, 0, BLOCK_ITEM_ID);
        Simulation.tick(s, arena, place(1, 66, 0).withUsePress(true), Input.NONE);
        assertTrue(s.blocks.contains(1, 66, 0), "a cell with a solid neighbour must accept the placement");
        assertEquals(7, s.players[0].slotCount[0]);
    }

    @Test
    void theOffHandGetsNoMidAirExemption() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        giveBlock(s, ItemDict.OFF_HAND, 4);

        Input offhandPlace = Input.NONE.withBlockAction(Input.BLOCK_PLACE_OFFHAND, 1, 66, 0);
        Simulation.tick(s, arena, offhandPlace, Input.NONE);
        assertFalse(s.blocks.contains(1, 66, 0), "the off-hand branch must obey the same support rule");

        s.blocks.place(1, 65, 0, BLOCK_ITEM_ID);
        Simulation.tick(s, arena, offhandPlace.withUsePress(true), Input.NONE);
        assertTrue(s.blocks.contains(1, 66, 0));
    }

    @Test
    void aRespawnAnchorCannotBePlacedInMidAir() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, 2,
                TestKit.item().itemId(ANCHOR_ITEM_ID).flags(ItemDict.FLAG_RESPAWN_ANCHOR));

        Input anchor = Input.NONE.withBlockAction(Input.BLOCK_PLACE_ANCHOR, 1, 66, 0);
        Simulation.tick(s, arena, anchor, Input.NONE);
        assertFalse(s.blocks.contains(1, 66, 0), "an anchor in mid-air is not a placement");
        assertTrue(s.anchors.isEmpty());

        s.blocks.place(1, 65, 0, BLOCK_ITEM_ID);
        Simulation.tick(s, arena, anchor.withUsePress(true), Input.NONE);
        assertTrue(s.blocks.contains(1, 66, 0));
        assertEquals(0, (int) s.anchors.get(BlockStore.key(1, 66, 0)));
    }

    @Test
    void arenaGeometryThatOnlyPartlyFillsACellStillSupportsAPlacement() {
        Arena arena = new Arena(GROUND_Y, new double[][]{{1.0, 65.0, 0.0, 2.0, 65.5, 1.0}});
        GameState s = duel(arena);
        giveBlock(s, 0, 8);

        Simulation.tick(s, arena, place(2, 66, 0), Input.NONE);
        assertFalse(s.blocks.contains(2, 66, 0), "that cell touches nothing at all");

        Simulation.tick(s, arena, place(1, 66, 0).withUsePress(true), Input.NONE);
        assertTrue(s.blocks.contains(1, 66, 0),
                "a slab-shaped arena box under the cell is still something to build against");
    }

    @Test
    void aCellFilledByUnbrokenArenaTerrainRefusesThePlacement() {
        boolean[] grid = new boolean[3 * 1 * 3];
        java.util.Arrays.fill(grid, true);
        Arena arena = new Arena(0.0, grid, 0, 64, 0, 3, 1, 3, new double[0][], java.util.Map.of());
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = 65.0;
        a.z = 1.5;
        a.onGround = true;
        s.players[1].x = 40.0;
        giveBlock(s, 0, 8);

        Simulation.tick(s, arena, place(1, 64, 1), Input.NONE);
        assertFalse(s.blocks.contains(1, 64, 1), "an intact arena voxel is not an empty cell");
        assertEquals(8, s.players[0].slotCount[0]);

        s.brokenArena.add(BlockStore.key(1, 64, 1));
        Simulation.tick(s, arena, place(1, 64, 1).withUsePress(true), Input.NONE);
        assertTrue(s.blocks.contains(1, 64, 1), "the mined-out hole must be fillable again");
    }

    private static Input place(int slot, int x, int y, int z) {
        return Input.NONE.withHeldSlot(slot).withBlockAction(Input.BLOCK_PLACE, x, y, z);
    }

    private static GameState cobwebState(Arena arena) {
        GameState s = duel(arena);
        s.cobwebItemId = COBWEB_ITEM_ID;
        TestKit.of(s).give(0, 0, 4,
                TestKit.item().itemId(COBWEB_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        giveBlock(s, 1, 4);
        return s;
    }

    @Test
    void aSolidBlockCannotBePlacedIntoACellACobwebAlreadyFills() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = cobwebState(arena);
        long cell = BlockStore.key(1, 64, 0);

        Simulation.tick(s, arena, place(0, 1, 64, 0), Input.NONE);
        assertTrue(s.cobwebs.containsKey(cell), "the cobweb should have gone down");
        assertEquals(3, s.players[0].slotCount[0]);

        Simulation.tick(s, arena, place(1, 1, 64, 0), Input.NONE);
        assertFalse(s.blocks.contains(1, 64, 0),
                "a solid block must obey the same occupancy rule the cobweb arm does");
        assertEquals(4, s.players[0].slotCount[1], "a refused placement must not consume the stack");
        assertTrue(s.cobwebs.containsKey(cell), "the cobweb must survive the refused placement");
    }

    @Test
    void aCobwebCannotBePlacedIntoACellASolidBlockAlreadyFills() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = cobwebState(arena);

        Simulation.tick(s, arena, place(1, 1, 64, 0), Input.NONE);
        assertTrue(s.blocks.contains(1, 64, 0), "the solid block should have gone down");

        Simulation.tick(s, arena, place(0, 1, 64, 0), Input.NONE);
        assertFalse(s.cobwebs.containsKey(BlockStore.key(1, 64, 0)),
                "the cobweb arm already refuses an occupied cell, and must keep doing so");
        assertEquals(4, s.players[0].slotCount[0], "a refused placement must not consume the stack");
    }

    private static GameState crystalState(Arena arena) {
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, 4,
                TestKit.item().itemId(CRYSTAL_ITEM_ID).flags(ItemDict.FLAG_END_CRYSTAL));
        s.obsidianItemId = OBSIDIAN_ITEM_ID;
        return s;
    }

    private static Input placeCrystal(int x, int y, int z) {
        return Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, x, y, z);
    }

    @Test
    void aCrystalNeedsSomethingUnderneathIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalState(arena);

        Simulation.tick(s, arena, placeCrystal(1, 65, 0), Input.NONE);
        assertTrue(s.crystals.isEmpty(), "a crystal in mid-air must be refused");
        assertEquals(4, s.players[0].slotCount[0], "a refused crystal must not be consumed");
    }

    @Test
    void aCrystalOnlySitsOnAnAllowedBase() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalState(arena);
        s.blocks.place(1, 64, 0, STONE_ITEM_ID);

        Simulation.tick(s, arena, placeCrystal(1, 64, 0), Input.NONE);
        assertTrue(s.crystals.isEmpty(), "stone is not a crystal base");

        s.blocks.removeAt(1, 64, 0);
        s.blocks.place(1, 64, 0, OBSIDIAN_ITEM_ID);
        Simulation.tick(s, arena, placeCrystal(1, 64, 0).withUsePress(true), Input.NONE);
        assertEquals(1, s.crystals.size(), "obsidian is a crystal base");
        assertEquals(3, s.players[0].slotCount[0]);
    }

    @Test
    void anUnbreakableBaseCountsAsBedrock() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalState(arena);
        BlockProps.Builder props = new BlockProps.Builder();
        props.add(STONE_ITEM_ID, -1f, 3.6e6f, 0, -1, ItemDict.TOOL_NONE, false);
        s.blockProps = props.build();
        s.blocks.place(1, 64, 0, STONE_ITEM_ID);

        Simulation.tick(s, arena, placeCrystal(1, 64, 0), Input.NONE);
        assertEquals(1, s.crystals.size(), "an unbreakable block is the bedrock case");
    }

    @Test
    void twoCrystalHullsCannotOccupyTheSameCell() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalState(arena);
        s.blocks.place(1, 64, 0, OBSIDIAN_ITEM_ID);

        Simulation.tick(s, arena, placeCrystal(1, 64, 0), Input.NONE);
        assertEquals(1, s.crystals.size());

        s.blocks.place(1, 65, 0, OBSIDIAN_ITEM_ID);
        Simulation.tick(s, arena, placeCrystal(1, 65, 0), Input.NONE);
        assertEquals(1, s.crystals.size(),
                "the second crystal would stand inside the first one's hull");
        assertEquals(3, s.players[0].slotCount[0], "the refused crystal must stay in the slot");
    }

    @Test
    void aCrystalNeedsTheCellAboveItsBaseToBeEmpty() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalState(arena);
        s.blocks.place(1, 64, 0, OBSIDIAN_ITEM_ID);
        s.blocks.place(1, 65, 0, STONE_ITEM_ID);

        Simulation.tick(s, arena, placeCrystal(1, 64, 0), Input.NONE);
        assertTrue(s.crystals.isEmpty(), "a placed block fills the cell the crystal stands in");
        assertEquals(4, s.players[0].slotCount[0], "a refused crystal must not be consumed");

        s.blocks.removeAt(1, 65, 0);
        s.blocks.place(1, 66, 0, STONE_ITEM_ID);
        Simulation.tick(s, arena, placeCrystal(1, 64, 0).withUsePress(true), Input.NONE);
        assertEquals(1, s.crystals.size(),
                "vanilla only asks for the ONE cell above the base, so a block two up is fine");
    }

    @Test
    void anIntactArenaVoxelAlsoFillsTheCellAboveTheBase() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalState(arena);

        Simulation.tick(s, arena, placeCrystal(1, 62, 0), Input.NONE);
        assertTrue(s.crystals.isEmpty(), "the arena floor stands in the crystal's cell");
        assertEquals(4, s.players[0].slotCount[0]);

        s.brokenArena.add(BlockStore.key(1, 63, 0));
        Simulation.tick(s, arena, placeCrystal(1, 62, 0).withUsePress(true), Input.NONE);
        assertEquals(1, s.crystals.size(), "mining that voxel out frees the cell");
    }

    private static GameState oreState(Arena arena) {
        GameState s = duel(arena);
        BlockProps.Builder props = new BlockProps.Builder();
        props.add(ORE_ITEM_ID, SOFT_HARDNESS, 6f, ORE_ITEM_ID, Loadout.TIER_IRON,
                ItemDict.TOOL_PICKAXE, true);
        s.blockProps = props.build();
        s.blocks.place(1, 64, 0, ORE_ITEM_ID);
        return s;
    }

    private static void mineOut(GameState s, Arena arena, Input in) {
        for (int i = 0; i < 200 && s.blocks.contains(1, 64, 0); i++) {
            Simulation.tick(s, arena, in, Input.NONE);
        }
        assertFalse(s.blocks.contains(1, 64, 0), "the block should have been mined out");
    }

    private static int drops(GameState s) {
        int n = 0;
        for (ItemEntityState e : s.items) {
            if (e.itemId == ORE_ITEM_ID) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aBlockThatNeedsAToolDropsNothingForAFist() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = oreState(arena);

        mineOut(s, arena, Input.NONE.withHeldSlot(4).withBlockAction(Input.BLOCK_BREAK, 1, 64, 0));
        assertEquals(0, drops(s), "a bare hand must not harvest a tool-only block");
    }

    private static GameState anchorState(Arena arena) {
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, 4,
                TestKit.item().itemId(ANCHOR_ITEM_ID).flags(ItemDict.FLAG_RESPAWN_ANCHOR));
        return s;
    }

    private static Input placeAnchor(int x, int y, int z) {
        return Input.NONE.withBlockAction(Input.BLOCK_PLACE_ANCHOR, x, y, z);
    }

    @Test
    void anAnchorCannotBePlacedIntoACellACobwebAlreadyFills() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = anchorState(arena);
        s.cobwebItemId = COBWEB_ITEM_ID;
        s.cobwebs.put(BlockStore.key(1, 64, 0), COBWEB_ITEM_ID);

        Simulation.tick(s, arena, placeAnchor(1, 64, 0), Input.NONE);
        assertFalse(s.blocks.contains(1, 64, 0),
                "the anchor arm must obey the same occupancy rule the block arms do");
        assertTrue(s.anchors.isEmpty(), "a refused anchor must not register a charge cell");
        assertEquals(4, s.players[0].slotCount[0], "a refused placement must not consume the stack");
        assertTrue(s.cobwebs.containsKey(BlockStore.key(1, 64, 0)),
                "the cobweb must survive the refused placement");
    }

    @Test
    void anAnchorCannotBePlacedIntoACellACrystalHullAlreadyFills() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = anchorState(arena);
        TestKit.of(s).give(0, 1, 1,
                TestKit.item().itemId(CRYSTAL_ITEM_ID).flags(ItemDict.FLAG_END_CRYSTAL));
        s.obsidianItemId = OBSIDIAN_ITEM_ID;
        s.blocks.place(1, 64, 0, OBSIDIAN_ITEM_ID);

        Simulation.tick(s, arena, Input.NONE.withHeldSlot(1)
                .withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 1, 64, 0), Input.NONE);
        assertEquals(1, s.crystals.size(), "the crystal has to be standing there first");

        Simulation.tick(s, arena, placeAnchor(1, 65, 0), Input.NONE);
        assertFalse(s.blocks.contains(1, 65, 0),
                "the cell the crystal hull stands in is not an empty cell");
        assertEquals(1, s.crystals.size(), "the crystal must survive the refused placement");
        assertEquals(4, s.players[0].slotCount[0], "a refused placement must not consume the stack");
    }

    @Test
    void anAnchorCannotBePlacedIntoACellADroppedItemAlreadyFills() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = anchorState(arena);
        int cargo = TestKit.of(s).add(
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        ItemEntities.spawn(s, 0, 1.5, 64.5, 0.5, 0.0, 0.0, 0.0, cargo, 0, BLOCK_ITEM_ID, 1, 100);

        Simulation.tick(s, arena, placeAnchor(1, 64, 0), Input.NONE);
        assertFalse(s.blocks.contains(1, 64, 0),
                "a dropped item occupies the cell exactly as it does for a normal placement");
        assertEquals(4, s.players[0].slotCount[0], "a refused placement must not consume the stack");
    }

    @Test
    void anAnchorStillGoesDownInACellNothingElseOccupies() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = anchorState(arena);

        Simulation.tick(s, arena, placeAnchor(1, 64, 0), Input.NONE);
        assertTrue(s.blocks.contains(1, 64, 0),
                "the tightened guard must not cost a legitimate anchor placement");
        assertEquals(0, (int) s.anchors.get(BlockStore.key(1, 64, 0)));
        assertEquals(3, s.players[0].slotCount[0]);
    }

    @Test
    void theRightToolStillHarvestsTheBlock() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = oreState(arena);
        TestKit.of(s).give(0, 4, 1, TestKit.item().itemId(PICKAXE_ITEM_ID).maxDamage(250)
                .tool(Loadout.TIER_DIAMOND, 0, ItemDict.TOOL_PICKAXE, false));

        mineOut(s, arena, Input.NONE.withHeldSlot(4).withBlockAction(Input.BLOCK_BREAK, 1, 64, 0));
        assertEquals(1, drops(s), "a pickaxe above the harvest tier must still drop the block");
    }
}
