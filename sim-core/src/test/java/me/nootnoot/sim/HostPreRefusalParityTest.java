package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.contract.HostFrameContract;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class HostPreRefusalParityTest {

    private static final double GROUND_Y = 64.0;

    private static final int BLOCK_ITEM_ID = 7300;
    private static final int ANCHOR_ITEM_ID = 7301;
    private static final int OBSIDIAN_ITEM_ID = 7302;
    private static final int CRYING_OBSIDIAN_ITEM_ID = 7303;
    private static final int CRYSTAL_ITEM_ID = 7304;
    private static final int SHULKER_ITEM_ID = 7305;
    private static final int TOTEM_ITEM_ID = 7306;

    private static final int SHULKER_CONTAINER_ID = 1;

    private static final float SOFT_HARDNESS = 1f / 40f;

    private static GameState duel(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = arena.groundY;
        a.z = 0.5;
        a.onGround = true;
        a.health = 20f;
        s.players[1].x = 40.0;
        return s;
    }

    private static void giveBlock(GameState s, int slot, int count) {
        TestKit.of(s).give(0, slot, count,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
    }

    private static Input place(int x, int y, int z) {
        return Input.NONE.withBlockAction(Input.BLOCK_PLACE, x, y, z);
    }

    private static void predictionMatchesSim(GameState s, Arena arena, int x, int y, int z,
                                             boolean expected, String why) {
        boolean predicted = HostFrameContract.placeableCell(s, arena, s.players[0], x, y, z);
        assertEquals(expected, predicted, "the shared predicate disagrees with the case: " + why);
        int held = s.players[0].slotCount[0];
        Simulation.tick(s, arena, place(x, y, z), Input.NONE);
        boolean placed = s.players[0].slotCount[0] < held;
        assertEquals(predicted, placed,
                "a host that asks HostFrameContract.placeableCell must get the answer the sim then"
                        + " gives, or it is refusing what the sim would allow: " + why);
    }

    @Test
    void aBlockPlacesAgainstAnUnchargedAnchorThatTheModUsedToRefuse() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        giveBlock(s, 0, 8);
        s.blocks.place(3, 64, 0, ANCHOR_ITEM_ID);
        s.anchors.put(BlockStore.key(3, 64, 0), 0);

        predictionMatchesSim(s, arena, 3, 65, 0, true,
                "aiming at an uncharged respawn anchor and placing on its top face. The mod carried"
                        + " a !targetIsAnchor term the edge never had, so a modded player could not"
                        + " place ANY block while the crosshair was on an anchor");
    }

    @Test
    void theSharedPredicateAnswersEveryPlacementCaseTheWaySimulationDoes() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState open = duel(arena);
        giveBlock(open, 0, 8);
        predictionMatchesSim(open, arena, 2, 64, 0, true, "an empty cell on the ground");

        GameState midAir = duel(arena);
        giveBlock(midAir, 0, 8);
        predictionMatchesSim(midAir, arena, 2, 67, 0, false, "a cell touching nothing");

        GameState underfoot = duel(arena);
        giveBlock(underfoot, 0, 8);
        predictionMatchesSim(underfoot, arena, 0, 64, 0, false,
                "the cell the placing player is standing in");

        GameState occupied = duel(arena);
        giveBlock(occupied, 0, 8);
        occupied.blocks.place(2, 64, 0, BLOCK_ITEM_ID);
        predictionMatchesSim(occupied, arena, 2, 64, 0, false, "a cell a block already fills");

        GameState hulled = duel(arena);
        giveBlock(hulled, 0, 8);
        CrystalState c = new CrystalState();
        c.id = 1;
        c.bx = 2;
        c.by = 63;
        c.bz = 0;
        hulled.crystals.add(c);
        predictionMatchesSim(hulled, arena, 2, 64, 0, false,
                "a cell an end crystal hull already fills");
    }

    @Test
    void theOffHandPlacementAsksTheSamePredicateAsTheMainHand() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, ItemDict.OFF_HAND, 4,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));

        assertFalse(HostFrameContract.placeableCell(s, arena, s.players[0], 2, 67, 0));
        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_PLACE_OFFHAND, 2, 67, 0),
                Input.NONE);
        assertFalse(s.blocks.contains(2, 67, 0));

        assertTrue(HostFrameContract.placeableCell(s, arena, s.players[0], 2, 64, 0));
        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_PLACE_OFFHAND, 2, 64, 0).withUsePress(true),
                Input.NONE);
        assertTrue(s.blocks.contains(2, 64, 0));
    }

    @Test
    void aCobwebGoesIntoACellHoldingAPlayerAndASolidBlockDoesNot() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        s.cobwebItemId = 7307;
        TestKit.of(s).give(0, 0, 4,
                TestKit.item().itemId(s.cobwebItemId).flags(ItemDict.FLAG_BLOCK));

        assertTrue(HostFrameContract.webbableCell(s, arena, s.players[0], 0, 64, 0),
                "vanilla webs the cell you are standing in, which is why webbableCell exists"
                        + " alongside placeableCell");
        assertFalse(HostFrameContract.placeableCell(s, arena, s.players[0], 0, 64, 0));

        Simulation.tick(s, arena, place(0, 64, 0), Input.NONE);
        assertTrue(s.cobwebs.containsKey(BlockStore.key(0, 64, 0)),
                "the sim webs that cell, so a host that refuses it locally is refusing a legal"
                        + " action");
    }

    private static GameState crystalState(Arena arena) {
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, 4,
                TestKit.item().itemId(CRYSTAL_ITEM_ID).flags(ItemDict.FLAG_END_CRYSTAL));
        s.obsidianItemId = OBSIDIAN_ITEM_ID;
        BlockProps.Builder props = new BlockProps.Builder();
        props.add(CRYING_OBSIDIAN_ITEM_ID, 50f, 1200f, CRYING_OBSIDIAN_ITEM_ID, -1,
                ItemDict.TOOL_PICKAXE, false);
        s.blockProps = props.build();
        return s;
    }

    @Test
    void cryingObsidianIsNotACrystalBaseOnEitherHost() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalState(arena);
        s.blocks.place(2, 64, 0, CRYING_OBSIDIAN_ITEM_ID);

        assertFalse(HostFrameContract.crystalBaseCell(s, arena, s.players[0], 2, 64, 0),
                "26.2 EndCrystalItem.useOn accepts OBSIDIAN and BEDROCK only, so the edge's"
                        + " Material list including CRYING_OBSIDIAN named a placement the sim"
                        + " always refused");

        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 2, 64, 0), Input.NONE);
        assertTrue(s.crystals.isEmpty(), "the sim refuses that place, so the intent was wasted");
        assertEquals(4, s.players[0].slotCount[0], "and the crystal is not consumed");

        s.blocks.removeAt(2, 64, 0);
        s.blocks.place(2, 64, 0, OBSIDIAN_ITEM_ID);
        assertTrue(HostFrameContract.crystalBaseCell(s, arena, s.players[0], 2, 64, 0));
        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 2, 64, 0).withUsePress(true),
                Input.NONE);
        assertEquals(1, s.crystals.size(), "obsidian is the base vanilla accepts");
    }

    @Test
    void aCrystalBaseAlreadyWearingACrystalIsRefusedByThePredicateAndBySimulation() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalState(arena);
        s.blocks.place(2, 64, 0, OBSIDIAN_ITEM_ID);

        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 2, 64, 0), Input.NONE);
        assertEquals(1, s.crystals.size());

        assertFalse(HostFrameContract.crystalBaseCell(s, arena, s.players[0], 2, 64, 0),
                "the second crystal has nowhere to stand, and both hosts must read that off the"
                        + " replicated crystals rather than off their own entity list");
        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 2, 64, 0).withUsePress(true),
                Input.NONE);
        assertEquals(1, s.crystals.size());
    }

    private static GameState withShulkerAt(Arena arena, int x, int y, int z) {
        GameState s = duel(arena);
        PlayerState a = s.players[0];
        a.x = x + 0.5;
        a.y = y;
        a.z = z + 2.5;
        BlockProps.Builder props = new BlockProps.Builder();
        props.add(SHULKER_ITEM_ID, SOFT_HARDNESS, 6f, SHULKER_ITEM_ID, -1, ItemDict.TOOL_NONE,
                false);
        props.add(BLOCK_ITEM_ID, SOFT_HARDNESS, 6f, BLOCK_ITEM_ID, -1, ItemDict.TOOL_NONE, false);
        s.blockProps = props.build();
        TestKit kit = TestKit.of(s);
        int totem = kit.add(TestKit.item().itemId(TOTEM_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_TOTEM));
        int shulker = kit.add(TestKit.item().itemId(SHULKER_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER)
                .containerSeed(SHULKER_CONTAINER_ID));
        Container box = new Container();
        box.entry[0] = totem;
        box.count[0] = 1;
        s.containers.put(SHULKER_CONTAINER_ID, box);
        s.nextContainerId = SHULKER_CONTAINER_ID + 1;
        kit.put(0, 0, shulker, 1);
        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_PLACE, x, y, z),
                Input.NONE);
        assertTrue(s.blocks.contains(x, y, z), "the box must be down to start with");
        return s;
    }

    @Test
    void simulationOpensABuriedContainerSoOnlyTheFrameProducersCanRefuseIt() {
        Arena arena = Arena.flat(GROUND_Y);
        int x = 2;
        int y = 64;
        int z = 2;
        GameState s = withShulkerAt(arena, x, y, z);

        assertTrue(HostFrameContract.containerOpens(s, arena, x, y, z),
                "nothing is on top of it yet");
        s.blocks.place(x, y + 1, z, BLOCK_ITEM_ID);
        assertFalse(HostFrameContract.containerOpens(s, arena, x, y, z),
                "vanilla refuses to open a box with a solid block in the space it opens into");

        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_OPEN_CONTAINER, x, y, z)
                        .withClicks(new Clicks(0, 1, 0, 0, 0)),
                Input.NONE);
        assertEquals(SHULKER_CONTAINER_ID, s.players[0].openContainer,
                "the SIM has no obstruction rule: it opens whatever cell the frame names. That is"
                        + " exactly why the obstruction test has to live in one place both frame"
                        + " producers call - the edge had none at all, so an unmodded player"
                        + " opened a buried box a modded player could not");
    }

    @Test
    void simulationDetonatesACrystalOnAFrameProducedWhileTheHandsAreBusy() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.z = 0.5;
        a.yaw = -90f;
        CrystalState c = new CrystalState();
        c.id = 1;
        c.bx = 2;
        c.by = 64;
        c.bz = 0;
        s.crystals.add(c);
        a.eating = true;
        a.eatTicks = 10;

        assertFalse(HostFrameContract.leftClickActs(true),
                "while an item is being used vanilla drains the attack presses into an empty loop,"
                        + " so an unmodded client sends no attack at all");

        Input attack = new Input(false, false, false, false, false, false, false, true, false,
                -90f, 0f, 0)
                .withCrystalHit(true, 2, 64, 0);
        Simulation.tick(s, arena, attack, Input.NONE);

        assertTrue(s.crystals.isEmpty(),
                "the sim honours a crystal hit whatever the hands are doing, so a mod that"
                        + " raycasts mid-bite really does detonate where an unmodded player"
                        + " cannot. The gate has to be in the frame producer, and it has to be the"
                        + " same gate on both");
    }
}
