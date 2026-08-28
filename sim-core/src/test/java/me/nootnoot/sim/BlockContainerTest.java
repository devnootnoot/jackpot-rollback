package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class BlockContainerTest {
    private static final double GROUND_Y = 64.0;

    private static final int SHULKER_ITEM_ID = 8100;
    private static final int ENDER_CHEST_ITEM_ID = 8200;
    private static final int TOTEM_ITEM_ID = 8300;

    private static final int SHULKER_CONTAINER_ID = 1;

    private static final int CELL_X = 2;
    private static final int CELL_Y = 64;
    private static final int CELL_Z = 2;

    private static final float SOFT_HARDNESS = 1f / 40f;

    private static void softBlocks(GameState s, int... ids) {
        BlockProps.Builder b = new BlockProps.Builder();
        for (int id : ids) {
            b.add(id, SOFT_HARDNESS, 6f, id, -1, ItemDict.TOOL_NONE, false);
        }
        s.blockProps = b.build();
    }

    private static void standBy(GameState s) {
        PlayerState a = s.players[0];
        a.x = CELL_X + 0.5;
        a.y = CELL_Y;
        a.z = CELL_Z + 2.5;
        a.onGround = false;
    }

    private static int totemEntry(TestKit kit) {
        return kit.add(TestKit.item().itemId(TOTEM_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_TOTEM));
    }

    private static int shulkerEntry(TestKit kit, int containerId) {
        return kit.add(TestKit.item().itemId(SHULKER_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER).containerSeed(containerId));
    }

    private static Container filled(GameState s, int cell, int entry, int count) {
        Container c = new Container();
        c.entry[cell] = entry;
        c.count[cell] = count;
        return c;
    }

    private static Input place() {
        return Input.NONE.withBlockAction(Input.BLOCK_PLACE, CELL_X, CELL_Y, CELL_Z);
    }

    private static Input open() {
        return Input.NONE.withBlockAction(Input.BLOCK_OPEN_CONTAINER, CELL_X, CELL_Y, CELL_Z)
                .withClicks(new Clicks(0, 1, 0, 0, 0));
    }

    private static Input close() {
        return Input.NONE.withBlockAction(Input.BLOCK_CLOSE_CONTAINER, 0, 0, 0);
    }

    private static Input breaking() {
        return Input.NONE.withBlockAction(Input.BLOCK_BREAK, CELL_X, CELL_Y, CELL_Z);
    }

    private static GameState withPlacedShulker(Arena arena, int contentEntryCount) {
        GameState s = HarnessScenarios.duel(arena);
        standBy(s);
        softBlocks(s, SHULKER_ITEM_ID);
        TestKit kit = TestKit.of(s);
        int totem = totemEntry(kit);
        int shulker = shulkerEntry(kit, SHULKER_CONTAINER_ID);
        s.containers.put(SHULKER_CONTAINER_ID, filled(s, 0, totem, contentEntryCount));
        s.nextContainerId = SHULKER_CONTAINER_ID + 1;
        kit.put(0, 0, shulker, 1);
        Simulation.tick(s, arena, place(), Input.NONE);
        return s;
    }

    @Test
    void placingAShulkerBindsItsSeededContainerToTheCell() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withPlacedShulker(arena, 1);

        assertTrue(s.blocks.contains(CELL_X, CELL_Y, CELL_Z), "the shulker must actually place");
        assertEquals(SHULKER_CONTAINER_ID,
                s.blockContainers.get(BlockStore.key(CELL_X, CELL_Y, CELL_Z)),
                "the entry's containerSeed must move onto the placed cell");
    }

    @Test
    void openingAPlacedShulkerExposesItsContentsAndTakingMovesThemIntoTheInventory() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withPlacedShulker(arena, 1);
        PlayerState a = s.players[0];
        int totem = s.containers.get(SHULKER_CONTAINER_ID).entry[0];

        Simulation.tick(s, arena, open(), Input.NONE);
        assertEquals(SHULKER_CONTAINER_ID, a.openContainer, "opening must resolve the bound container");

        Simulation.tick(s, arena, Input.NONE.withInvAction(Input.INV_CONTAINER_TAKE, 0, 5), Input.NONE);
        assertEquals(totem, a.slotEntry[5], "the take must land the totem in the named slot");
        assertEquals(1, a.slotCount[5]);
        assertEquals(ItemDict.NONE, s.containers.get(SHULKER_CONTAINER_ID).entry[0],
                "the cell it came from must be emptied");

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        Simulation.tick(s, arena, Input.NONE.withInvAction(Input.INV_CONTAINER_PUT, 5, 3), Input.NONE);
        assertEquals(totem, s.containers.get(SHULKER_CONTAINER_ID).entry[3],
                "the put must land it back in the named cell");
        assertEquals(ItemDict.NONE, a.slotEntry[5]);

        Simulation.tick(s, arena, close(), Input.NONE);
        assertEquals(-1, a.openContainer, "closing must drop the handle");
    }

    @Test
    void anUnboundCellOpensNothing() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        standBy(s);

        Simulation.tick(s, arena, open(), Input.NONE);
        assertEquals(-1, s.players[0].openContainer, "there is no container at an empty cell");
    }

    @Test
    void aTakeIsRefusedWhileNothingIsOpen() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withPlacedShulker(arena, 1);

        Simulation.tick(s, arena, Input.NONE.withInvAction(Input.INV_CONTAINER_TAKE, 0, 5), Input.NONE);
        assertEquals(ItemDict.NONE, s.players[0].slotEntry[5],
                "a container move must not resolve without an open container");
        assertNotEquals(ItemDict.NONE, s.containers.get(SHULKER_CONTAINER_ID).entry[0]);
    }

    @Test
    void breakingAShulkerDropsTheSameEntrySoItsContentsSurvive() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withPlacedShulker(arena, 1);
        int shulker = s.dict.entryForContainer(SHULKER_CONTAINER_ID);
        assertNotEquals(ItemDict.NONE, shulker, "the dictionary must resolve the container back to its entry");
        assertEquals(SHULKER_CONTAINER_ID,
                s.blockContainers.get(BlockStore.key(CELL_X, CELL_Y, CELL_Z)),
                "the placed cell must be bound before we mine it");

        for (int i = 0; i < 60 && s.blocks.contains(CELL_X, CELL_Y, CELL_Z); i++) {
            Simulation.tick(s, arena, breaking(), Input.NONE);
        }
        assertFalse(s.blocks.contains(CELL_X, CELL_Y, CELL_Z), "the shulker should have been mined out");
        assertNull(s.blockContainers.get(BlockStore.key(CELL_X, CELL_Y, CELL_Z)),
                "the cell binding must be released when the block goes");

        ItemEntityState dropped = null;
        for (ItemEntityState e : s.items) {
            if (e.itemId == SHULKER_ITEM_ID) {
                dropped = e;
            }
        }
        assertNotNull(dropped, "breaking a shulker must drop a shulker");
        assertEquals(shulker, dropped.entry,
                "the drop must carry the SAME entry, which is what still points at the contents");
        assertNotEquals(ItemDict.NONE, s.containers.get(SHULKER_CONTAINER_ID).entry[0],
                "the contents must survive the block being broken");
    }

    @Test
    void breakingAnOpenShulkerClosesIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withPlacedShulker(arena, 1);
        Simulation.tick(s, arena, open(), Input.NONE);
        assertEquals(SHULKER_CONTAINER_ID, s.players[0].openContainer);

        for (int i = 0; i < 60 && s.blocks.contains(CELL_X, CELL_Y, CELL_Z); i++) {
            Simulation.tick(s, arena, breaking(), Input.NONE);
        }
        assertEquals(-1, s.players[0].openContainer,
                "the open handle must be dropped when the block under it is destroyed");
    }

    @Test
    void anEnderChestAllocatesOnePerPlayerContainerThatOutlivesTheBlock() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        standBy(s);
        softBlocks(s, ENDER_CHEST_ITEM_ID);
        TestKit kit = TestKit.of(s);
        int totem = totemEntry(kit);
        int chest = kit.add(TestKit.item().itemId(ENDER_CHEST_ITEM_ID).maxStack(64)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_ENDER_CHEST));
        kit.put(0, 0, chest, 2);
        kit.put(0, 5, totem, 1);

        Simulation.tick(s, arena, place(), Input.NONE);
        assertTrue(s.blocks.contains(CELL_X, CELL_Y, CELL_Z));
        assertNull(s.blockContainers.get(BlockStore.key(CELL_X, CELL_Y, CELL_Z)),
                "an ender chest is per-player, so the cell itself holds no binding");

        Simulation.tick(s, arena, open(), Input.NONE);
        PlayerState a = s.players[0];
        int ender = a.openContainer;
        assertTrue(ender >= 0, "opening an ender chest must allocate a container");
        assertEquals(ender, a.enderContainer);
        assertNotNull(s.containers.get(ender));

        Simulation.tick(s, arena, Input.NONE.withInvAction(Input.INV_CONTAINER_PUT, 5, 0), Input.NONE);
        assertEquals(totem, s.containers.get(ender).entry[0]);

        Simulation.tick(s, arena, close(), Input.NONE);
        Simulation.tick(s, arena, open(), Input.NONE);
        assertEquals(ender, a.openContainer, "re-opening must resolve the SAME per-player container");
        assertEquals(totem, s.containers.get(ender).entry[0], "its contents must persist");
    }

    @Test
    void eachPlayerGetsTheirOwnEnderContainer() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        standBy(s);
        softBlocks(s, ENDER_CHEST_ITEM_ID);
        TestKit kit = TestKit.of(s);
        int chest = kit.add(TestKit.item().itemId(ENDER_CHEST_ITEM_ID).maxStack(64)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_ENDER_CHEST));
        kit.put(0, 0, chest, 1);
        Simulation.tick(s, arena, place(), Input.NONE);

        PlayerState b = s.players[1];
        b.x = CELL_X + 0.5;
        b.y = CELL_Y;
        b.z = CELL_Z - 1.5;

        Simulation.tick(s, arena, open(), open());
        assertTrue(s.players[0].openContainer >= 0);
        assertTrue(b.openContainer >= 0);
        assertNotEquals(s.players[0].openContainer, b.openContainer,
                "an ender chest must never share one inventory between the two duellists");
    }

    @Test
    void containerStateSurvivesAFrameZeroRoundTrip() {
        GameState s = new GameState();
        TestKit kit = TestKit.of(s);
        int totem = totemEntry(kit);
        shulkerEntry(kit, SHULKER_CONTAINER_ID);
        s.containers.put(SHULKER_CONTAINER_ID, filled(s, 0, totem, 1));
        s.nextContainerId = SHULKER_CONTAINER_ID + 1;
        s.blockContainers.put(BlockStore.key(CELL_X, CELL_Y, CELL_Z), SHULKER_CONTAINER_ID);
        s.players[0].enderContainer = 9;
        s.containers.put(9, new Container());
        s.roundInitialContainers.put(SHULKER_CONTAINER_ID,
                s.containers.get(SHULKER_CONTAINER_ID).copy());

        GameState back = GameStateFrame0Codec.decode(GameStateFrame0Codec.encode(s));
        assertEquals(SHULKER_CONTAINER_ID,
                back.blockContainers.get(BlockStore.key(CELL_X, CELL_Y, CELL_Z)));
        assertEquals(9, back.players[0].enderContainer);
        assertEquals(s.containers.get(SHULKER_CONTAINER_ID).entry[0],
                back.roundInitialContainers.get(SHULKER_CONTAINER_ID).entry[0]);
        assertEquals(Checksum.of(s), Checksum.of(back));
    }
}
