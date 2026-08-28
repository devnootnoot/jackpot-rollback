package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ContainerReachTest {
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
        s.players[1].x = 40.0;
    }

    private static Input place() {
        return Input.NONE.withBlockAction(Input.BLOCK_PLACE, CELL_X, CELL_Y, CELL_Z);
    }

    private static Input open() {
        return Input.NONE.withBlockAction(Input.BLOCK_OPEN_CONTAINER, CELL_X, CELL_Y, CELL_Z)
                .withClicks(new Clicks(0, 1, 0, 0, 0));
    }

    private static Input breaking() {
        return Input.NONE.withBlockAction(Input.BLOCK_BREAK, CELL_X, CELL_Y, CELL_Z);
    }

    private static Input take() {
        return Input.NONE.withInvAction(Input.INV_CONTAINER_TAKE, 0, 5);
    }

    private static int totemEntry(TestKit kit) {
        return kit.add(TestKit.item().itemId(TOTEM_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_TOTEM));
    }

    private static GameState withOpenShulker(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        standBy(s);
        softBlocks(s, SHULKER_ITEM_ID);
        TestKit kit = TestKit.of(s);
        int totem = totemEntry(kit);
        int shulker = kit.add(TestKit.item().itemId(SHULKER_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER).containerSeed(SHULKER_CONTAINER_ID));
        Container box = new Container();
        box.entry[0] = totem;
        box.count[0] = 1;
        s.containers.put(SHULKER_CONTAINER_ID, box);
        s.nextContainerId = SHULKER_CONTAINER_ID + 1;
        kit.put(0, 0, shulker, 1);
        Simulation.tick(s, arena, place(), Input.NONE);
        Simulation.tick(s, arena, open(), Input.NONE);
        assertEquals(SHULKER_CONTAINER_ID, s.players[0].openContainer, "the box must be open to start with");
        return s;
    }

    private static GameState withOpenEnderChest(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        standBy(s);
        softBlocks(s, ENDER_CHEST_ITEM_ID);
        TestKit kit = TestKit.of(s);
        int totem = totemEntry(kit);
        int chest = kit.add(TestKit.item().itemId(ENDER_CHEST_ITEM_ID).maxStack(64)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_ENDER_CHEST));
        kit.put(0, 0, chest, 1);
        kit.put(0, 5, totem, 1);
        Simulation.tick(s, arena, place(), Input.NONE);
        Simulation.tick(s, arena, open(), Input.NONE);
        assertTrue(s.players[0].openContainer >= 0, "the chest must be open to start with");
        Simulation.tick(s, arena, Input.NONE.withInvAction(Input.INV_CONTAINER_PUT, 5, 0), Input.NONE);
        assertNotEquals(ItemDict.NONE, s.containers.get(s.players[0].openContainer).entry[0]);
        return s;
    }

    private static double eyeToCell(PlayerState a) {
        double eye = Combat.eyeHeight(a);
        double cx = Math.max(CELL_X, Math.min(a.x, CELL_X + 1.0));
        double cy = Math.max(CELL_Y, Math.min(a.y + eye, CELL_Y + 1.0));
        double cz = Math.max(CELL_Z, Math.min(a.z, CELL_Z + 1.0));
        double dx = a.x - cx;
        double dy = a.y + eye - cy;
        double dz = a.z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void anOpenBoxSurvivesPastTheBlockInteractionGate() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withOpenShulker(arena);
        PlayerState a = s.players[0];

        a.z = CELL_Z + 8.0;
        double distance = eyeToCell(a);
        assertTrue(distance > Combat.blockReachLimit(),
                "this stance has to be outside the block interaction gate or it proves nothing");
        assertTrue(distance < Combat.containerReachLimit(),
                "and inside the 8.5 vanilla keeps a menu open to");

        Simulation.tick(s, arena, take(), Input.NONE);

        assertEquals(SHULKER_CONTAINER_ID, a.openContainer,
                "vanilla revalidates an open menu at 4.5 + 4.0, not at the block interaction range");
        assertNotEquals(ItemDict.NONE, a.slotEntry[5], "and the take resolves while it is open");
    }

    @Test
    void anOpenBoxStillSnapsShutPastTheContainerRange() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withOpenShulker(arena);
        PlayerState a = s.players[0];

        a.z = CELL_Z + 11.0;
        assertTrue(eyeToCell(a) > Combat.containerReachLimit(),
                "this stance has to be outside 8.5 or it proves nothing");

        Simulation.tick(s, arena, take(), Input.NONE);

        assertEquals(-1, a.openContainer, "past 8.5 vanilla force-closes the menu every tick");
        assertEquals(ItemDict.NONE, a.slotEntry[5], "and the take must not resolve");
    }

    @Test
    void walkingOutOfBlockReachClosesTheBoxAndRefusesFurtherTakes() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withOpenShulker(arena);
        PlayerState a = s.players[0];

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        assertEquals(SHULKER_CONTAINER_ID, a.openContainer, "standing next to it must keep it open");

        a.z = CELL_Z + 30.0;
        Simulation.tick(s, arena, take(), Input.NONE);

        assertEquals(-1, a.openContainer, "walking away must close the box");
        assertEquals(ItemDict.NONE, a.slotEntry[5], "the take must not resolve from across the arena");
        assertNotEquals(ItemDict.NONE, s.containers.get(SHULKER_CONTAINER_ID).entry[0],
                "the contents must still be in the box");
    }

    @Test
    void steppingBackIntoReachDoesNotSilentlyReopenTheBox() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withOpenShulker(arena);
        PlayerState a = s.players[0];

        a.z = CELL_Z + 30.0;
        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        assertEquals(-1, a.openContainer);

        a.z = CELL_Z + 2.5;
        Simulation.tick(s, arena, take(), Input.NONE);
        assertEquals(-1, a.openContainer, "the handle is gone until the player opens the box again");
        assertEquals(ItemDict.NONE, a.slotEntry[5]);

        Simulation.tick(s, arena, open(), Input.NONE);
        Simulation.tick(s, arena, take(), Input.NONE);
        assertEquals(SHULKER_CONTAINER_ID, a.openContainer, "re-opening in reach must work as before");
        assertNotEquals(ItemDict.NONE, a.slotEntry[5], "and the take must resolve again");
    }

    @Test
    void miningAnEnderChestOutFromUnderTheOwnerClosesIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withOpenEnderChest(arena);
        PlayerState a = s.players[0];
        int ender = a.openContainer;

        for (int i = 0; i < 60 && s.blocks.contains(CELL_X, CELL_Y, CELL_Z); i++) {
            Simulation.tick(s, arena, breaking(), Input.NONE);
        }
        assertFalse(s.blocks.contains(CELL_X, CELL_Y, CELL_Z), "the chest should have been mined out");

        Simulation.tick(s, arena, take(), Input.NONE);
        assertEquals(-1, a.openContainer, "an ender chest holds no cell binding, so only the reach"
                + " revalidation can drop the handle");
        assertEquals(ItemDict.NONE, a.slotEntry[5], "the take must not resolve through a broken chest");
        assertNotNull(s.containers.get(ender), "the per-player container itself outlives the block");
        assertNotEquals(ItemDict.NONE, s.containers.get(ender).entry[0]);
    }

    @Test
    void theOpponentCannotKeepTakingFromABoxTheyLeftBehind() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withOpenShulker(arena);
        PlayerState a = s.players[0];

        GameState ghost = s.copy();
        ghost.players[0].x = CELL_X + 60.0;
        Simulation.tick(ghost, arena, take(), Input.NONE);
        assertEquals(-1, ghost.players[0].openContainer);

        a.x = CELL_X + 60.0;
        Simulation.tick(s, arena, take(), Input.NONE);

        assertEquals(Checksum.of(ghost), Checksum.of(s),
                "both peers must close the box on the very same tick");
    }

    @Test
    void theOpenHandleAndItsCellSurviveACopy() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withOpenShulker(arena);
        GameState copy = s.copy();

        assertEquals(s.players[0].openContainerKey, copy.players[0].openContainerKey);
        assertEquals(BlockStore.key(CELL_X, CELL_Y, CELL_Z), copy.players[0].openContainerKey,
                "the cell the box was opened at is what the reach check re-reads");
        assertEquals(Checksum.of(s), Checksum.of(copy));

        copy.players[0].openContainerKey = BlockStore.key(CELL_X + 8, CELL_Y, CELL_Z);
        assertNotEquals(Checksum.of(s), Checksum.of(copy),
                "the cell is part of the state both peers have to agree on");
    }
}
