package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class BlockActionPacingTest {
    private static final double GROUND_Y = 64.0;

    private static final int TICKS = 40;

    private static final int BLOCK_ITEM_ID = 4200;
    private static final int OBSIDIAN_ITEM_ID = 4201;
    private static final int CRYSTAL_ITEM_ID = 4202;
    private static final int ROCKET_ITEM_ID = 4203;
    private static final int RUBBLE_ITEM_ID = 4204;
    private static final int SNOWBALL_ITEM_ID = 4205;
    private static final int EMPTY_BUCKET_ITEM_ID = 4206;
    private static final int WATER_BUCKET_ITEM_ID = 4207;

    private static final int[] WATER_CELL = {4, 64, 0};

    private static final int[] CRYSTAL_BASE = {2, 64, 0};

    private static final int STACK = 64;

    private static final int[][] PLACE_CELLS = {
            {1, 64, 1}, {2, 64, 1}, {3, 64, 1}, {1, 64, 2}, {2, 64, 2},
            {3, 64, 2}, {1, 64, 3}, {2, 64, 3}, {3, 64, 3}, {1, 64, 4},
    };

    private static final int CLICK_TICKS = PLACE_CELLS.length * 2;

    private static final int[][] BREAK_CELLS = {
            {1, 64, 1}, {2, 64, 1}, {3, 64, 1}, {1, 64, 2}, {2, 64, 2},
            {3, 64, 2}, {1, 64, 3},
    };

    private static GameState duel(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        s.players[1].x = 40.0;
        return s;
    }

    private static Input place(int action, int[] cell, int slot) {
        return Input.NONE.withHeldSlot(slot).withBlockAction(action, cell[0], cell[1], cell[2]);
    }

    private static Input click(int action, int[] cell, int slot) {
        return place(action, cell, slot).withUsePress(true);
    }

    private static Input swingAt(int action, int[] cell) {
        return new Input(false, false, false, false, false, false, false, true, false, 0f, 0f, 0)
                .withBlockAction(action, cell[0], cell[1], cell[2]);
    }

    @Test
    void theBlockUseCadenceIsTheSameRightClickDelayEveryOtherUsePathObeys() {
        assertEquals(4, Combat.USE_REPEAT_DELAY,
                "Minecraft.startUseItem opens with this.rightClickDelay = 4, and a block placement"
                        + " reaches the server through that same startUseItem call, so a placement"
                        + " and a pearl throw are paced by one field, not by two");
    }

    @Test
    void everyBlockActionThatIsAVanillaRightClickIsOnThePacedList() {
        for (int action = Input.BLOCK_NONE; action <= Input.BLOCK_CLOSE_CONTAINER; action++) {
            boolean rightClick = action != Input.BLOCK_NONE
                    && action != Input.BLOCK_BREAK
                    && action != Input.BLOCK_HIT_CRYSTAL
                    && action != Input.BLOCK_CLOSE_CONTAINER;
            assertEquals(rightClick, Combat.isBlockUse(action),
                    "block action " + action + " is classified wrong. BLOCK_BREAK is the destroy"
                            + " path and pays MultiPlayerGameMode.destroyDelay instead;"
                            + " BLOCK_HIT_CRYSTAL is Minecraft.startAttack on an entity, which"
                            + " vanilla paces by the click and not by rightClickDelay;"
                            + " BLOCK_CLOSE_CONTAINER is a screen close and never reaches"
                            + " useItemOn. Everything else reaches the server through"
                            + " startUseItem and must pay rightClickDelay");
        }
    }

    @Test
    void aPlacementDrivenEveryTickLandsOnlyOnTheVanillaRightClickCadence() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));

        for (int frame = 0; frame < TICKS; frame++) {
            int[] cell = PLACE_CELLS[frame / Combat.USE_REPEAT_DELAY];
            Simulation.tick(s, arena, place(Input.BLOCK_PLACE, cell, 0), Input.NONE);
        }

        assertEquals(TICKS / Combat.USE_REPEAT_DELAY, STACK - s.players[0].slotCount[0],
                "a client that pins a block placement on every frame must land on the right click"
                        + " cadence, not one block per tick");
    }

    @Test
    void aRefusedBlockUseNeverReArmsTheDelayItWasRefusedBy() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        PlayerState a = s.players[0];

        Simulation.tick(s, arena, place(Input.BLOCK_PLACE, PLACE_CELLS[0], 0), Input.NONE);
        assertTrue(s.blocks.contains(PLACE_CELLS[0][0], PLACE_CELLS[0][1], PLACE_CELLS[0][2]),
                "the first placement has to land, or nothing is armed and the test is empty");
        assertEquals(Combat.USE_REPEAT_DELAY, a.useDelay,
                "the placement arms the delay to the vanilla rightClickDelay");

        int previous = a.useDelay;
        for (int frame = 1; frame < Combat.USE_REPEAT_DELAY; frame++) {
            Simulation.tick(s, arena, place(Input.BLOCK_PLACE, PLACE_CELLS[frame], 0), Input.NONE);
            int now = a.useDelay;
            assertEquals(previous - 1, now,
                    "a refused right click spends a tick off the delay and arms nothing:"
                            + " Minecraft.handleKeybinds simply does not call startUseItem while"
                            + " rightClickDelay is nonzero, and Minecraft.tick decrements it"
                            + " regardless, so a held button can never hold the window open:"
                            + " frame " + frame);
            assertFalse(s.blocks.contains(PLACE_CELLS[frame][0], PLACE_CELLS[frame][1],
                            PLACE_CELLS[frame][2]),
                    "and nothing may be placed inside the window: frame " + frame);
            previous = now;
        }

        assertEquals(1, a.useDelay,
                "the delay was armed after its own tick's decrement, so USE_REPEAT_DELAY - 1"
                        + " refusals leave exactly one tick on the clock");
        Simulation.tick(s, arena,
                place(Input.BLOCK_PLACE, PLACE_CELLS[Combat.USE_REPEAT_DELAY], 0), Input.NONE);
        assertTrue(s.blocks.contains(PLACE_CELLS[Combat.USE_REPEAT_DELAY][0],
                        PLACE_CELLS[Combat.USE_REPEAT_DELAY][1],
                        PLACE_CELLS[Combat.USE_REPEAT_DELAY][2]),
                "and the very next tick places, which is what proves the early return neither"
                        + " re-armed the counter nor swallowed the use that follows it");
    }

    @Test
    void aPlacementRunAtAFreshCellEveryTickStillCannotBeatTheCadence() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));

        int placed = 0;
        for (int frame = 0; frame < Combat.USE_REPEAT_DELAY; frame++) {
            Simulation.tick(s, arena, place(Input.BLOCK_PLACE, PLACE_CELLS[frame], 0), Input.NONE);
            if (s.blocks.contains(PLACE_CELLS[frame][0], PLACE_CELLS[frame][1],
                    PLACE_CELLS[frame][2])) {
                placed++;
            }
        }

        assertEquals(1, placed,
                "walking the target cell forward every tick must not buy a second placement:"
                        + " the delay is one counter on the player, not one per cell");
    }

    @Test
    void theCrystalArmDrawsOnTheSameOneRightClickDelayThePlainPlacementDoes() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, STACK, TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        kit.give(0, 1, 4, TestKit.item().itemId(CRYSTAL_ITEM_ID).flags(ItemDict.FLAG_END_CRYSTAL));
        s.obsidianItemId = OBSIDIAN_ITEM_ID;
        s.blocks.place(2, 64, 0, OBSIDIAN_ITEM_ID);

        Simulation.tick(s, arena, place(Input.BLOCK_PLACE, PLACE_CELLS[0], 0), Input.NONE);
        assertTrue(s.blocks.contains(PLACE_CELLS[0][0], PLACE_CELLS[0][1], PLACE_CELLS[0][2]),
                "the plain placement has to land first, or the crystal was never being paced");

        int[] base = {2, 64, 0};
        for (int frame = 1; frame < Combat.USE_REPEAT_DELAY; frame++) {
            Simulation.tick(s, arena, place(Input.BLOCK_PLACE_CRYSTAL, base, 1), Input.NONE);
            assertTrue(s.crystals.isEmpty(),
                    "vanilla holds ONE rightClickDelay on the client, so a crystal cannot be"
                            + " placed inside the window a block placement just opened: frame "
                            + frame);
        }

        Simulation.tick(s, arena, place(Input.BLOCK_PLACE_CRYSTAL, base, 1), Input.NONE);
        assertEquals(1, s.crystals.size(),
                "and it must land the moment the delay expires, exactly USE_REPEAT_DELAY ticks"
                        + " after the placement that armed it");
    }

    @Test
    void theDestroyDelayIsTheVanillaFiveTicks() {
        assertEquals(5, Combat.DESTROY_DELAY,
                "MultiPlayerGameMode.continueDestroyBlock sets this.destroyDelay = 5 the moment"
                        + " destroyProgress reaches 1.0, and spends one tick of it per call:"
                        + " if (this.destroyDelay > 0) { --this.destroyDelay; return true; }");
    }

    private static GameState instantBreakField(Arena arena) {
        GameState s = duel(arena);
        BlockProps.Builder props = new BlockProps.Builder();
        props.add(RUBBLE_ITEM_ID, 0f, 6f, RUBBLE_ITEM_ID, -1, ItemDict.TOOL_NONE, false);
        s.blockProps = props.build();
        for (int[] cell : BREAK_CELLS) {
            s.blocks.place(cell[0], cell[1], cell[2], RUBBLE_ITEM_ID);
        }
        return s;
    }

    private static int standing(GameState s) {
        int n = 0;
        for (int[] cell : BREAK_CELLS) {
            if (s.blocks.contains(cell[0], cell[1], cell[2])) {
                n++;
            }
        }
        return n;
    }

    @Test
    void anInstantBreakBlockDrivenEveryTickObeysTheVanillaDestroyDelay() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = instantBreakField(arena);
        int cadence = Combat.DESTROY_DELAY + 1;

        for (int frame = 0; frame < TICKS; frame++) {
            int[] cell = BREAK_CELLS[frame / cadence];
            Simulation.tick(s, arena,
                    Input.NONE.withBlockAction(Input.BLOCK_BREAK, cell[0], cell[1], cell[2]),
                    Input.NONE);
        }

        int broken = BREAK_CELLS.length - standing(s);
        assertEquals((TICKS + Combat.DESTROY_DELAY) / cadence, broken,
                "a hardness zero block finishes in one tick, so the only thing between one break"
                        + " and the next is destroyDelay: one break, then five ticks the destroy"
                        + " call spends doing nothing, is " + cadence + " ticks per block");
    }

    @Test
    void switchingTargetEveryTickDoesNotRefundTheDestroyDelay() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = instantBreakField(arena);

        for (int frame = 0; frame < Combat.DESTROY_DELAY; frame++) {
            int[] cell = BREAK_CELLS[frame];
            Simulation.tick(s, arena,
                    Input.NONE.withBlockAction(Input.BLOCK_BREAK, cell[0], cell[1], cell[2]),
                    Input.NONE);
        }

        assertEquals(1, BREAK_CELLS.length - standing(s),
                "vanilla's destroyDelay is checked before sameDestroyTarget, so aiming at a fresh"
                        + " block every tick spends the delay instead of dodging it");
    }

    private static Input rocketPress() {
        return new Input(false, false, false, false, false, false, false, false, false,
                0f, 90f, 0).withUsePress(true);
    }

    private static GameState rocketState(Arena arena) {
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK,
                TestKit.item().itemId(ROCKET_ITEM_ID).useKind(Combat.USE_FIREWORK)
                        .fireworkFlight(1));
        return s;
    }

    @Test
    void aFireworkPressPinnedHighEveryTickCannotOutrunTheRightClickCadence() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = rocketState(arena);

        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, rocketPress(), Input.NONE);
        }

        assertEquals(0, Combat.useCooldownTicks(Combat.USE_FIREWORK),
                "Items.FIREWORK_ROCKET ships no useCooldown component, so rightClickDelay is the"
                        + " only thing vanilla has pacing a rocket");
        assertEquals(TICKS / Combat.USE_REPEAT_DELAY, s.nextProjectileId,
                "the rocket arm used to run off a server derived rising edge with no delay at all,"
                        + " which handed a client toggling the bit a rocket every other tick: "
                        + s.nextProjectileId + " rockets in " + TICKS + " ticks");
    }

    @Test
    void aHeldFireworkStillRepeatsOnTheCadenceLikeVanilla() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = rocketState(arena);

        Input held = new Input(false, false, false, false, false, false, false, false, true,
                0f, 90f, 0);
        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, held, Input.NONE);
        }

        assertEquals(TICKS / Combat.USE_REPEAT_DELAY, s.nextProjectileId,
                "Minecraft.handleKeybinds re-enters startUseItem off keyUse.isDown() the moment"
                        + " rightClickDelay hits zero, so a held rocket must keep firing and must"
                        + " fire no faster than a pressed one");
    }

    @Test
    void anHonestFireworkPressStillLaunchesOnTheVeryFirstTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = rocketState(arena);

        Simulation.tick(s, arena, rocketPress(), Input.NONE);

        assertEquals(1, s.nextProjectileId, "pacing the rocket must not cost the player their first one");
        assertEquals(STACK - 1, s.players[0].slotCount[0]);
    }


    @Test
    void aGenuineClickStreamPlacesOnceAClickAndIsNeverCappedByTheHeldCadence() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));

        for (int frame = 0; frame < CLICK_TICKS; frame++) {
            boolean down = frame % 2 == 0;
            Simulation.tick(s, arena,
                    down ? click(Input.BLOCK_PLACE, PLACE_CELLS[frame / 2], 0) : Input.NONE,
                    Input.NONE);
        }

        assertEquals(CLICK_TICKS / 2, STACK - s.players[0].slotCount[0],
                "Minecraft.handleKeybinds paces only the keyUse.isDown() re-entry with"
                        + " rightClickDelay; the while (keyUse.consumeClick()) startUseItem() loop"
                        + " above it is unconditional, so a player who releases and re-presses"
                        + " places on every one of those clicks");
    }

    @Test
    void aPinnedPressBitStillCannotOutrunTheHeldCadence() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));

        for (int frame = 0; frame < CLICK_TICKS; frame++) {
            Simulation.tick(s, arena, click(Input.BLOCK_PLACE, PLACE_CELLS[frame / 2], 0),
                    Input.NONE);
        }

        assertEquals(1 + (CLICK_TICKS - 1) / Combat.USE_REPEAT_DELAY,
                STACK - s.players[0].slotCount[0],
                "the press bit is a wire bit and the sim derives the click edge itself, so pinning"
                        + " it high buys the one honest click it looks like and then falls back to"
                        + " the held cadence");
    }

    @Test
    void oneRightClickStillOnlyDoesOneThing() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, STACK, TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        kit.give(0, ItemDict.OFF_HAND, STACK, TestKit.item().itemId(SNOWBALL_ITEM_ID)
                .maxStack(STACK).useKind(Combat.USE_SNOWBALL));

        Simulation.tick(s, arena,
                click(Input.BLOCK_PLACE, PLACE_CELLS[0], 0).withOffhandUse(true), Input.NONE);

        assertTrue(s.blocks.contains(PLACE_CELLS[0][0], PLACE_CELLS[0][1], PLACE_CELLS[0][2]),
                "the main hand block placement is what vanilla's startUseItem resolves first");
        assertEquals(STACK, s.players[0].slotCount[ItemDict.OFF_HAND],
                "Minecraft.startUseItem returns the moment a hand succeeds, so splitting the"
                        + " counters must not let one click both place a block and empty the off"
                        + " hand");
    }

    @Test
    void aBucketClutchIsNotRefusedByABlockPlacedTheTickBefore() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, STACK, TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        kit.add(TestKit.item().itemId(EMPTY_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_EMPTY));
        int water = kit.add(TestKit.item().itemId(WATER_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_WATER));
        kit.put(0, 1, water, 1);

        Simulation.tick(s, arena, place(Input.BLOCK_PLACE, PLACE_CELLS[0], 0), Input.NONE);
        assertTrue(s.blocks.contains(PLACE_CELLS[0][0], PLACE_CELLS[0][1], PLACE_CELLS[0][2]),
                "the placement has to land first, or the clutch was never being blocked by one");

        Simulation.tick(s, arena, click(Input.BLOCK_PLACE_WATER, WATER_CELL, 1), Input.NONE);

        assertNotNull(s.fluids.get(BlockStore.key(WATER_CELL[0], WATER_CELL[1], WATER_CELL[2])),
                "the clutch is a fresh right CLICK, and Minecraft.handleKeybinds runs"
                        + " while (keyUse.consumeClick()) startUseItem() unconditionally, above and"
                        + " independent of the rightClickDelay gate: a click never waits on the"
                        + " counter a block placement armed, so one counter starves nothing");
    }

    @Test
    void aHeldBucketIsStillStarvedByABlockPlacedTheTickBefore() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, STACK, TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        kit.add(TestKit.item().itemId(EMPTY_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_EMPTY));
        int water = kit.add(TestKit.item().itemId(WATER_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_WATER));
        kit.put(0, 1, water, 1);

        Simulation.tick(s, arena, place(Input.BLOCK_PLACE, PLACE_CELLS[0], 0), Input.NONE);
        Simulation.tick(s, arena, place(Input.BLOCK_PLACE_WATER, WATER_CELL, 1), Input.NONE);

        assertNull(s.fluids.get(BlockStore.key(WATER_CELL[0], WATER_CELL[1], WATER_CELL[2])),
                "splitting the counter by use class let a player alternate classes and beat"
                        + " vanilla's cadence, so the classes are gone: with the button merely"
                        + " HELD, vanilla's keyUse.isDown() re-entry waits on rightClickDelay and"
                        + " the pour is starved by the placement, exactly as it is in vanilla");
    }

    @Test
    void theBucketStillPacesItself() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        int empty = kit.add(TestKit.item().itemId(EMPTY_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_EMPTY));
        int water = kit.add(TestKit.item().itemId(WATER_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_WATER));
        kit.put(0, 0, water, 1);

        Simulation.tick(s, arena, place(Input.BLOCK_PLACE_WATER, WATER_CELL, 0), Input.NONE);
        assertEquals(empty, s.players[0].slotEntry[0], "the pour has to land first");

        for (int frame = 1; frame < Combat.USE_REPEAT_DELAY; frame++) {
            Simulation.tick(s, arena, place(Input.BLOCK_PICKUP_FLUID, WATER_CELL, 0), Input.NONE);
            assertEquals(empty, s.players[0].slotEntry[0],
                    "splitting the counters must not hand the fluid class a free repeat: frame "
                            + frame);
        }

        Simulation.tick(s, arena, place(Input.BLOCK_PICKUP_FLUID, WATER_CELL, 0), Input.NONE);
        assertEquals(water, s.players[0].slotEntry[0],
                "and the fluid class must open again exactly USE_REPEAT_DELAY ticks after it"
                        + " was armed");
    }

    @Test
    void aPlacementLeavesTheSameDelayOnTheClockAThrowDoes() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState placed = duel(arena);
        TestKit.of(placed).give(0, 0, STACK,
                TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        GameState thrown = duel(arena);
        TestKit.of(thrown).give(0, 0, STACK,
                TestKit.item().itemId(SNOWBALL_ITEM_ID).maxStack(STACK)
                        .useKind(Combat.USE_SNOWBALL));

        Simulation.tick(placed, arena, place(Input.BLOCK_PLACE, PLACE_CELLS[0], 0), Input.NONE);
        Simulation.tick(thrown, arena,
                new Input(false, false, false, false, false, false, false, false, true, 0f, 0f, 0),
                Input.NONE);

        assertEquals(Combat.USE_REPEAT_DELAY,
                placed.players[0].useDelay,
                "block uses used to read the counter before the tick spent it and item uses after,"
                        + " so the same delay was worth five ticks one way and four the other");
        assertEquals(placed.players[0].useDelay,
                thrown.players[0].useDelay,
                "the counter is spent once, at a fixed point in the tick, so what a use costs"
                        + " cannot depend on which phase resolved it");
    }

    @Test
    void aBlockPlaceAndAnItemUseSpendOneCounterNotTwo() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, STACK, TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        kit.give(0, 1, STACK, TestKit.item().itemId(SNOWBALL_ITEM_ID).maxStack(STACK)
                .useKind(Combat.USE_SNOWBALL));

        Simulation.tick(s, arena, place(Input.BLOCK_PLACE, PLACE_CELLS[0], 0), Input.NONE);
        assertTrue(s.blocks.contains(PLACE_CELLS[0][0], PLACE_CELLS[0][1], PLACE_CELLS[0][2]),
                "the placement has to land first, or the throw was never being paced by it");

        for (int frame = 1; frame < Combat.USE_REPEAT_DELAY; frame++) {
            Simulation.tick(s, arena,
                    new Input(false, false, false, false, false, false, false, false, true,
                            0f, 0f, 1),
                    Input.NONE);
            assertEquals(STACK, s.players[0].slotCount[1],
                    "Minecraft holds one rightClickDelay field, so a snowball held down inside the"
                            + " window a block placement opened is refused by that same field:"
                            + " frame " + frame);
        }

        Simulation.tick(s, arena,
                new Input(false, false, false, false, false, false, false, false, true, 0f, 0f, 1),
                Input.NONE);
        assertEquals(STACK - 1, s.players[0].slotCount[1],
                "and it must throw the moment the one counter expires, exactly USE_REPEAT_DELAY"
                        + " ticks after the placement armed it");
    }

    private static void restock(GameState s) {
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.y = GROUND_Y;
        a.z = 0.0;
        a.vx = 0.0;
        a.vy = 0.0;
        a.vz = 0.0;
        a.health = a.maxHealth;
        if (s.crystals.isEmpty()) {
            CrystalState c = new CrystalState();
            c.id = s.nextCrystalId++;
            c.bx = CRYSTAL_BASE[0];
            c.by = CRYSTAL_BASE[1];
            c.bz = CRYSTAL_BASE[2];
            s.crystals.add(c);
        }
    }

    private static GameState crystalField(Arena arena) {
        GameState s = duel(arena);
        PlayerState a = s.players[0];
        a.maxHealth = 20000f;
        a.health = 20000f;
        return s;
    }

    private static int detonations(GameState s, Arena arena, boolean release) {
        int popped = 0;
        for (int frame = 0; frame < TICKS; frame++) {
            restock(s);
            boolean swing = !release || frame % 2 == 0;
            Simulation.tick(s, arena,
                    swing ? swingAt(Input.BLOCK_HIT_CRYSTAL, CRYSTAL_BASE) : Input.NONE,
                    Input.NONE);
            if (s.crystals.isEmpty()) {
                popped++;
            }
        }
        return popped;
    }

    @Test
    void aPinnedAttackBitDetonatesExactlyOneCrystal() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalField(arena);

        assertEquals(1, detonations(s, arena, false),
                "Minecraft.startAttack is only ever reached from while (keyAttack.consumeClick()),"
                        + " and the continueAttack call below it destroys blocks and never attacks"
                        + " an entity, so holding the button down attacks exactly once");
    }

    @Test
    void aRealCrystalClickCadenceIsNotThrottled() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = crystalField(arena);

        assertEquals(TICKS / 2, detonations(s, arena, true),
                "crystal pvp is click driven: one detonation per press, and a press every other"
                        + " tick is ten a second, well past what a human sustains");
    }

    private static GameState fullPockets(Arena arena) {
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK, TestKit.item().itemId(RUBBLE_ITEM_ID));
        return s;
    }

    private static int dropped(GameState s, Arena arena, boolean release) {
        for (int frame = 0; frame < TICKS; frame++) {
            boolean down = !release || frame % 2 == 0;
            Simulation.tick(s, arena, Input.NONE.withDrop(down, false), Input.NONE);
        }
        return s.players[0].dropSeq;
    }

    @Test
    void pinningTheDropBitTossesOneItemNotOnePerTick() {
        Arena arena = Arena.flat(GROUND_Y);

        assertEquals(1, dropped(fullPockets(arena), arena, false),
                "Minecraft.handleKeybinds drops inside while (keyDrop.consumeClick()), so the key"
                        + " has to be released before it can throw a second item");
    }

    @Test
    void aRealDropClickStreamStillTossesOnePerPress() {
        Arena arena = Arena.flat(GROUND_Y);

        assertEquals(TICKS / 2, dropped(fullPockets(arena), arena, true),
                "and the rising edge must cost the player nothing: every press they actually make"
                        + " still throws an item");
    }

    private static final int RUBBLE_X = 20;
    private static final int RUBBLE_Y = 100;
    private static final int RUBBLE_Z = 20;
    private static final int RUBBLE_SPAN = 5;

    private static final float SOFT_RESISTANCE = 0.1f;

    private static final int SMALL_BUDGET = 8;

    private static GameState rubbleField() {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = GROUND_Y;
        a.z = 0.5;
        a.health = 20f;
        PlayerState v = s.players[1];
        v.x = 10_000.0;
        v.y = GROUND_Y;
        v.health = 20f;
        for (int x = RUBBLE_X; x < RUBBLE_X + RUBBLE_SPAN; x++) {
            for (int y = RUBBLE_Y; y < RUBBLE_Y + RUBBLE_SPAN; y++) {
                for (int z = RUBBLE_Z; z < RUBBLE_Z + RUBBLE_SPAN; z++) {
                    s.blocks.place(x, y, z, RUBBLE_ITEM_ID);
                    s.blockResistance.put(BlockStore.key(x, y, z), SOFT_RESISTANCE);
                }
            }
        }
        return s;
    }

    private static int rubbleStanding(GameState s) {
        int n = 0;
        for (int x = RUBBLE_X; x < RUBBLE_X + RUBBLE_SPAN; x++) {
            for (int y = RUBBLE_Y; y < RUBBLE_Y + RUBBLE_SPAN; y++) {
                for (int z = RUBBLE_Z; z < RUBBLE_Z + RUBBLE_SPAN; z++) {
                    if (s.blocks.contains(x, y, z)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static int blastRubble(GameState s, Arena arena) {
        int before = rubbleStanding(s);
        Combat.explode(s, arena, RUBBLE_X + 2.5, RUBBLE_Y + 2.5, RUBBLE_Z + 2.5,
                Combat.CRYSTAL_POWER, 0, false);
        return before - rubbleStanding(s);
    }

    @Test
    void oneTickCannotStripMoreCellsThanTheBlastBudget() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = rubbleField();
        s.blastCellBudget = SMALL_BUDGET;

        int removed = blastRubble(s, arena);

        assertEquals(SMALL_BUDGET, removed,
                "the block phase of an explosion is the only unbounded work in a tick, so it has"
                        + " to spend from a per tick budget and stop when the budget is gone");
        assertEquals(0, s.blastCellBudget, "and the budget it spent must be gone");
    }

    @Test
    void aSecondBlastInTheSameTickCannotReopenASpentBudget() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = rubbleField();
        s.blastCellBudget = SMALL_BUDGET;

        blastRubble(s, arena);
        int second = blastRubble(s, arena);

        assertEquals(0, second,
                "the budget is per tick and shared by every explosion in that tick, or two"
                        + " detonations a tick would each get a full allowance");
    }

    @Test
    void anUnclippedBlastStillTakesTheWholeSphereDown() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState clipped = rubbleField();
        clipped.blastCellBudget = SMALL_BUDGET;
        GameState whole = rubbleField();

        int clippedRemoved = blastRubble(clipped, arena);
        int wholeRemoved = blastRubble(whole, arena);

        assertTrue(wholeRemoved > clippedRemoved,
                "the bound must only bite on a tick that is already far past anything a real"
                        + " crystal chain does: an ordinary blast has to land in full");
        assertTrue(whole.blastCellBudget > 0,
                "and it must not have come anywhere near exhausting the allowance: "
                        + wholeRemoved + " cells of " + GameState.BLAST_CELLS_PER_TICK);
    }

    @Test
    void theBlastBudgetIsRestoredAtTheTopOfEveryTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        s.blastCellBudget = 0;

        Simulation.tick(s, arena, Input.NONE, Input.NONE);

        assertEquals(GameState.BLAST_CELLS_PER_TICK, s.blastCellBudget,
                "the budget is a per tick allowance, so it has to be refilled before the first"
                        + " thing in a tick that can detonate anything");
    }
}
