package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

class PlacementSightlineTest {
    private static final double GROUND_Y = 64.0;

    private static final int WALL_ITEM_ID = 940;
    private static final int BLOCK_ITEM_ID = 941;
    private static final int COBWEB_ITEM_ID = 942;
    private static final int ANCHOR_ITEM_ID = 943;
    private static final int CRYSTAL_ITEM_ID = 944;
    private static final int OBSIDIAN_ITEM_ID = 945;
    private static final int GLOWSTONE_ITEM_ID = 946;
    private static final int SHULKER_ITEM_ID = 947;

    private static final int SHULKER_CONTAINER_ID = 7;

    private static final int WALL_CELL_X = 2;

    private static final int TARGET_X = 4;
    private static final int TARGET_Y = 64;
    private static final int TARGET_Z = 0;

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

    private static void wall(GameState s) {
        s.blocks.place(WALL_CELL_X, 64, 0, WALL_ITEM_ID);
        s.blocks.place(WALL_CELL_X, 65, 0, WALL_ITEM_ID);
    }

    private static void mineTheWallAway(GameState s) {
        s.blocks.removeAt(WALL_CELL_X, 64, 0);
        s.blocks.removeAt(WALL_CELL_X, 65, 0);
    }

    private static void give(GameState s, int slot, int count, int itemId, int flags) {
        TestKit.of(s).give(0, slot, count, TestKit.item().itemId(itemId).flags(flags));
    }

    private static Input action(int kind, int x, int y, int z) {
        return Input.NONE.withBlockAction(kind, x, y, z);
    }

    private static Input click(int kind, int x, int y, int z) {
        return action(kind, x, y, z).withUsePress(true);
    }

    private static Input charge() {
        return action(Input.BLOCK_CHARGE_ANCHOR, TARGET_X, TARGET_Y, TARGET_Z)
                .withClicks(new Clicks(0, 1, 0, 0, 0));
    }

    private static Input open() {
        return action(Input.BLOCK_OPEN_CONTAINER, TARGET_X, TARGET_Y, TARGET_Z);
    }

    private static Input openClick() {
        return open().withClicks(new Clicks(0, 1, 0, 0, 0));
    }

    @Test
    void theTargetCellIsInReachAndSupportedSoOnlyTheSightlineIsUnderTest() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        give(s, 0, 8, BLOCK_ITEM_ID, ItemDict.FLAG_BLOCK);

        Simulation.tick(s, arena, action(Input.BLOCK_PLACE, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);

        assertTrue(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z),
                "control: with nothing in the way the cell is inside blockReachLimit and the"
                        + " ground slab under it is support, so the placement is otherwise legal");
    }

    @Test
    void aBlockCannotBePlacedThroughAWallIntoASealedBox() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        give(s, 0, 8, BLOCK_ITEM_ID, ItemDict.FLAG_BLOCK);
        wall(s);

        Simulation.tick(s, arena, action(Input.BLOCK_PLACE, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);

        assertFalse(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z),
                "placementLegal was adjacency only, so a client could build inside a sealed box"
                        + " it had no line of sight into; the melee path's outline sightline is"
                        + " the same test and has to apply here too");
        assertEquals(8, s.players[0].slotCount[0], "a refused placement must not consume the stack");

        mineTheWallAway(s);
        Simulation.tick(s, arena, click(Input.BLOCK_PLACE, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertTrue(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z),
                "and taking the wall down has to hand the placement straight back, which is what"
                        + " proves the refusal was the sightline and not reach or support");
    }

    @Test
    void theOffHandCannotBuildThroughAWallEither() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        give(s, ItemDict.OFF_HAND, 4, BLOCK_ITEM_ID, ItemDict.FLAG_BLOCK);
        wall(s);

        Simulation.tick(s, arena,
                action(Input.BLOCK_PLACE_OFFHAND, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertFalse(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z),
                "the off-hand arm shares placementLegal, so it shares the sightline");

        mineTheWallAway(s);
        Simulation.tick(s, arena,
                click(Input.BLOCK_PLACE_OFFHAND, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertTrue(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z));
    }

    @Test
    void aCobwebCannotBePlacedThroughAWall() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        s.cobwebItemId = COBWEB_ITEM_ID;
        give(s, 0, 4, COBWEB_ITEM_ID, ItemDict.FLAG_BLOCK);
        wall(s);
        long target = BlockStore.key(TARGET_X, TARGET_Y, TARGET_Z);

        Simulation.tick(s, arena, action(Input.BLOCK_PLACE, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertFalse(s.cobwebs.containsKey(target),
                "the cobweb arm sits behind the same placementLegal gate");
        assertEquals(4, s.players[0].slotCount[0], "a refused cobweb must not be consumed");

        mineTheWallAway(s);
        Simulation.tick(s, arena, click(Input.BLOCK_PLACE, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertTrue(s.cobwebs.containsKey(target));
    }

    @Test
    void anAnchorCannotBePlacedThroughAWall() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        give(s, 0, 4, ANCHOR_ITEM_ID, ItemDict.FLAG_RESPAWN_ANCHOR);
        wall(s);

        Simulation.tick(s, arena,
                action(Input.BLOCK_PLACE_ANCHOR, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertFalse(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z),
                "an anchor behind a wall is the same sealed-box build, and it detonates");
        assertTrue(s.anchors.isEmpty(), "a refused anchor must not register a charge cell");

        mineTheWallAway(s);
        Simulation.tick(s, arena,
                click(Input.BLOCK_PLACE_ANCHOR, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertTrue(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z));
        assertEquals(0, (int) s.anchors.get(BlockStore.key(TARGET_X, TARGET_Y, TARGET_Z)));
    }

    @Test
    void anAnchorCannotBeChargedThroughAWall() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        give(s, 0, 4, ANCHOR_ITEM_ID, ItemDict.FLAG_RESPAWN_ANCHOR);
        give(s, 1, 8, GLOWSTONE_ITEM_ID, ItemDict.FLAG_GLOWSTONE);

        Simulation.tick(s, arena,
                action(Input.BLOCK_PLACE_ANCHOR, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertTrue(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z), "control: the anchor goes down");

        wall(s);
        Simulation.tick(s, arena, charge().withHeldSlot(1), Input.NONE);

        assertEquals(0, (int) s.anchors.get(BlockStore.key(TARGET_X, TARGET_Y, TARGET_Z)),
                "the charge branch consumed glowstone and stepped the anchor with no sightline"
                        + " test, so a wall was no defence against arming the explosive");
        assertEquals(8, s.players[0].slotCount[1], "a refused charge must not eat the glowstone");

        mineTheWallAway(s);
        Simulation.tick(s, arena, charge().withHeldSlot(1), Input.NONE);

        assertEquals(1, (int) s.anchors.get(BlockStore.key(TARGET_X, TARGET_Y, TARGET_Z)),
                "with the wall gone the same click has to charge");
        assertEquals(7, s.players[0].slotCount[1]);
    }

    @Test
    void aContainerCannotBeOpenedThroughAWall() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, 1, TestKit.item().itemId(SHULKER_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER)
                .containerSeed(SHULKER_CONTAINER_ID));
        s.containers.put(SHULKER_CONTAINER_ID, new Container());
        s.nextContainerId = SHULKER_CONTAINER_ID + 1;

        Simulation.tick(s, arena, action(Input.BLOCK_PLACE, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertTrue(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z), "control: the box goes down");

        wall(s);
        Simulation.tick(s, arena, openClick(), Input.NONE);
        assertEquals(-1, s.players[0].openContainer,
                "the open branch was the only cell-naming block action with no sightline test,"
                        + " so a sealed box could be looted from outside the wall");

        mineTheWallAway(s);
        Simulation.tick(s, arena, openClick(), Input.NONE);
        assertEquals(SHULKER_CONTAINER_ID, s.players[0].openContainer,
                "with the wall gone the same click has to open it");
    }

    @Test
    void openingAContainerPaysTheSameRightClickDelayAsEveryOtherUseOnACell() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, 1, TestKit.item().itemId(SHULKER_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER)
                .containerSeed(SHULKER_CONTAINER_ID));
        s.containers.put(SHULKER_CONTAINER_ID, new Container());
        s.nextContainerId = SHULKER_CONTAINER_ID + 1;

        assertTrue(Combat.isBlockUse(Input.BLOCK_OPEN_CONTAINER),
                "vanilla reaches a chest through Minecraft.startUseItem, which opens with"
                        + " rightClickDelay = 4 before it ever calls useItemOn");

        Simulation.tick(s, arena, open(), Input.NONE);
        assertEquals(Combat.USE_REPEAT_DELAY, s.players[0].useDelay,
                "an open has to spend the shared right-click delay");

        Simulation.tick(s, arena, action(Input.BLOCK_PLACE, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertFalse(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z),
                "an unpaced open let a held right-click place on the very next tick,"
                        + " which is one placement more per second than vanilla allows");
    }

    @Test
    void aCountedClickStillOpensAContainerInsideTheDelay() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, 1, TestKit.item().itemId(SHULKER_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER)
                .containerSeed(SHULKER_CONTAINER_ID));
        s.containers.put(SHULKER_CONTAINER_ID, new Container());
        s.nextContainerId = SHULKER_CONTAINER_ID + 1;

        Simulation.tick(s, arena, action(Input.BLOCK_PLACE, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertEquals(Combat.USE_REPEAT_DELAY, s.players[0].useDelay);

        Simulation.tick(s, arena, openClick(), Input.NONE);
        assertEquals(SHULKER_CONTAINER_ID, s.players[0].openContainer,
                "vanilla drains counted right-clicks outside the rightClickDelay gate, so a real"
                        + " click on the box it just placed still opens it");
    }

    @Test
    void aCrystalCannotBeArmedThroughAWall() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        s.obsidianItemId = OBSIDIAN_ITEM_ID;
        give(s, 0, 4, CRYSTAL_ITEM_ID, ItemDict.FLAG_END_CRYSTAL);
        s.blocks.place(TARGET_X, TARGET_Y, TARGET_Z, OBSIDIAN_ITEM_ID);
        wall(s);

        Simulation.tick(s, arena,
                action(Input.BLOCK_PLACE_CRYSTAL, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertTrue(s.crystals.isEmpty(),
                "the crystal arm is gated on the cell the crystal will stand in, so a base the"
                        + " client cannot see does not arm one");
        assertEquals(4, s.players[0].slotCount[0], "a refused crystal must not be consumed");

        mineTheWallAway(s);
        Simulation.tick(s, arena,
                click(Input.BLOCK_PLACE_CRYSTAL, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertEquals(1, s.crystals.size(),
                "and with the wall gone the very same placement has to go through");
    }

    @Test
    void aCobwebStillGoesDownInsideAPlayerBecauseVanillaLetsIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        s.cobwebItemId = COBWEB_ITEM_ID;
        give(s, 0, 4, COBWEB_ITEM_ID, ItemDict.FLAG_BLOCK);
        PlayerState v = s.players[1];
        v.x = 2.5;
        v.y = GROUND_Y;
        v.z = 0.5;
        v.onGround = true;

        Simulation.tick(s, arena, action(Input.BLOCK_PLACE, 2, 64, 0), Input.NONE);

        assertTrue(s.cobwebs.containsKey(BlockStore.key(2, 64, 0)),
                "BlockItem.canPlace ends in isUnobstructed(state, pos, placementContext), and"
                        + " CollisionGetter.isUnobstructed returns true the moment"
                        + " state.getCollisionShape is empty; Blocks.COBWEB is registered"
                        + " .noCollision(), so the entity loop in EntityGetter.isUnobstructed is"
                        + " never reached and webbing the opponent is a real vanilla mechanic");
        assertEquals(3, s.players[0].slotCount[0], "and it costs a web, as it should");
    }

    @Test
    void aSolidBlockIsStillRefusedInsideAPlayer() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        give(s, 0, 4, BLOCK_ITEM_ID, ItemDict.FLAG_BLOCK);
        PlayerState v = s.players[1];
        v.x = 2.5;
        v.y = GROUND_Y;
        v.z = 0.5;
        v.onGround = true;

        Simulation.tick(s, arena, action(Input.BLOCK_PLACE, 2, 64, 0), Input.NONE);

        assertFalse(s.blocks.contains(2, 64, 0),
                "a full cube has a collision shape, so isUnobstructed does run the entity loop and"
                        + " blocksBuilding refuses it; the two arms differ in vanilla and must"
                        + " keep differing here");
        assertEquals(4, s.players[0].slotCount[0], "a refused placement must not consume the stack");
    }
    @Test
    void aBlockCannotBeMinedThroughAWall() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        s.blockProps = new BlockProps.Builder()
                .add(BLOCK_ITEM_ID, 1f / 40f, 6f, BLOCK_ITEM_ID, -1, ItemDict.TOOL_NONE, false)
                .add(WALL_ITEM_ID, 1f / 40f, 6f, WALL_ITEM_ID, -1, ItemDict.TOOL_NONE, false)
                .build();
        s.blocks.place(TARGET_X, TARGET_Y, TARGET_Z, BLOCK_ITEM_ID);
        wall(s);

        for (int i = 0; i < 8; i++) {
            Simulation.tick(s, arena, action(Input.BLOCK_BREAK, TARGET_X, TARGET_Y, TARGET_Z),
                    Input.NONE);
        }

        assertTrue(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z),
                "BLOCK_BREAK was the one block action with no sightline test, so a client could"
                        + " name a cell it had no line to and mine it out of a sealed box. Every"
                        + " other opcode on the same switch already pays cellInSight");

        mineTheWallAway(s);
        for (int i = 0; i < 8 && s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z); i++) {
            Simulation.tick(s, arena, action(Input.BLOCK_BREAK, TARGET_X, TARGET_Y, TARGET_Z),
                    Input.NONE);
        }

        assertFalse(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z),
                "with the wall gone the same frames have to break it, or the gate is refusing"
                        + " something other than the occlusion");
    }

    @Test
    void aCellTheEyeIsAlreadyInsideIsNotOccludedFromItself() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        s.blockProps = new BlockProps.Builder()
                .add(BLOCK_ITEM_ID, 1f / 40f, 6f, BLOCK_ITEM_ID, -1, ItemDict.TOOL_NONE, false)
                .build();
        PlayerState a = s.players[0];
        int headX = (int) Math.floor(a.x);
        int headY = (int) Math.floor(a.y + Combat.eyeHeight(a));
        int headZ = (int) Math.floor(a.z);
        s.blocks.place(headX, headY, headZ, BLOCK_ITEM_ID);

        for (int i = 0; i < 8 && s.blocks.contains(headX, headY, headZ); i++) {
            Simulation.tick(s, arena, action(Input.BLOCK_BREAK, headX, headY, headZ), Input.NONE);
        }

        assertFalse(s.blocks.contains(headX, headY, headZ),
                "the aim point of a cell containing the eye clamps back onto the eye, so the"
                        + " segment has no direction and the cell reports itself as its own"
                        + " occluder. Vanilla's clip has no entry point from inside a shape and"
                        + " returns a miss, so a cell you are standing in never blocks you");
    }
}
