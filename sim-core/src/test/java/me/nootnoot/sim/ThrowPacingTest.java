package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ThrowPacingTest {
    private static final double GROUND_Y = 64.0;

    private static final int SNOWBALL_ITEM_ID = 810;
    private static final int POTION_ITEM_ID = 811;
    private static final int BOTTLE_ITEM_ID = 812;

    private static final int STACK = 64;
    private static final int TICKS = 40;

    private static GameState duel(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.z = 0.0;
        a.yaw = -90f;
        s.players[1].x = 40.0;
        return s;
    }

    private static TestKit.Item throwable(int itemId, int useKind) {
        return TestKit.item().itemId(itemId).maxStack(STACK).useKind(useKind);
    }

    private static GameState mainHand(Arena arena, int itemId, int useKind) {
        GameState s = duel(arena);
        TestKit.of(s).give(0, 0, STACK, throwable(itemId, useKind));
        return s;
    }

    private static GameState offHand(Arena arena, int itemId, int useKind) {
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.put(0, 0, ItemDict.NONE, 0);
        kit.give(0, ItemDict.OFF_HAND, STACK, throwable(itemId, useKind));
        return s;
    }

    private static Input mainUse(boolean down) {
        return new Input(false, false, false, false, false, false, false, false, down, -90f, 0f, 0);
    }

    private static Input offUse(boolean down) {
        return Input.NONE.withOffhandUse(down);
    }

    private static int thrownWhileHolding(GameState s, Arena arena, boolean offhand, int slot) {
        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, offhand ? offUse(true) : mainUse(true), Input.NONE);
        }
        return STACK - s.players[0].slotCount[slot];
    }

    private static int thrownWhileTogglingTheBit(GameState s, Arena arena, boolean offhand,
                                                 int slot) {
        for (int frame = 0; frame < TICKS; frame++) {
            boolean down = frame % 2 == 0;
            Simulation.tick(s, arena, offhand ? offUse(down) : mainUse(down), Input.NONE);
        }
        return STACK - s.players[0].slotCount[slot];
    }

    private static int cadenceCeiling() {
        return (TICKS + Combat.USE_REPEAT_DELAY - 1) / Combat.USE_REPEAT_DELAY;
    }

    private static Input mainPress() {
        return mainUse(false).withUsePress(true);
    }

    @Test
    void theHeldUseCadenceIsTheVanillaRightClickDelay() {
        assertEquals(4, Combat.USE_REPEAT_DELAY,
                "Minecraft.startUseItem sets this.rightClickDelay = 4 and the held branch"
                        + " (keyUse.isDown() && rightClickDelay == 0) is the only one that waits on it");
    }

    @Test
    void theFourZeroCooldownThrowablesCarryNoServerSideCooldown() {
        assertEquals(0, Combat.SNOWBALL_COOLDOWN_TICKS,
                "Items.SNOWBALL is registered with stacksTo(16) and no useCooldown component");
        assertEquals(0, Combat.EGG_COOLDOWN_TICKS,
                "Items.EGG is registered with stacksTo(16) and a chicken variant, no useCooldown");
        assertEquals(0, Combat.SPLASH_POTION_COOLDOWN_TICKS,
                "Items.SPLASH_POTION is registered with stacksTo(1) and potion contents, no useCooldown");
        assertEquals(0, Combat.XP_BOTTLE_COOLDOWN_TICKS,
                "Items.EXPERIENCE_BOTTLE is registered with a rarity and a glint override, no useCooldown");
    }

    @Test
    void aGenuineClickStreamIsNeverPacedByTheHeldPathCadence() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL);

        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, frame % 2 == 0 ? mainPress() : mainUse(false), Input.NONE);
        }

        assertEquals(TICKS / 2, STACK - s.players[0].slotCount[0],
                "Minecraft.handleKeybinds runs while (keyUse.consumeClick()) startUseItem()"
                        + " unconditionally and only the keyUse.isDown() re-entry below it waits on"
                        + " rightClickDelay, so a player releasing and re-pressing throws once per"
                        + " click and rightClickDelay never touches them");
    }

    @Test
    void aClickStreamAndAHeldButtonDoNotPaceTheSameWay() {
        Arena arena = Arena.flat(GROUND_Y);

        int held = thrownOver(mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL), arena,
                mainUse(true), TICKS, 0);
        GameState clicked = mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL);
        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(clicked, arena, frame % 2 == 0 ? mainPress() : mainUse(false),
                    Input.NONE);
        }

        assertTrue(STACK - clicked.players[0].slotCount[0] > held,
                "capping a click at the held cadence is the regression this pair exists to catch:"
                        + " clicking has to beat holding, exactly as it does in vanilla");
    }

    @Test
    void aPressBitAssertedEveryTickCannotOutrunTheUseCadence() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL);

        int thrown = thrownOver(s, arena, mainPress(), TICKS, 0);

        assertEquals(TICKS / Combat.USE_REPEAT_DELAY, thrown,
                "a client that pins usePress high on every frame must land on the cadence, not on"
                        + " one throw per tick: " + thrown + " throws in " + TICKS + " ticks");
    }

    @Test
    void aPressBitAssertedEveryTickCannotOutrunAHeldButton() {
        Arena arena = Arena.flat(GROUND_Y);

        int held = thrownOver(mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL), arena,
                mainUse(true), TICKS, 0);
        int pressed = thrownOver(mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL), arena,
                mainPress(), TICKS, 0);

        assertEquals(held, pressed,
                "the two paths are the same right click, so neither bit may buy a throw the other"
                        + " one is refused");
    }

    @Test
    void thePressPathIsStillGatedByARealItemCooldown() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_PEARL);

        for (int frame = 0; frame < TICKS; frame++) {
            Simulation.tick(s, arena, mainPress(), Input.NONE);
        }

        assertEquals(TICKS / Combat.PEARL_COOLDOWN_TICKS, STACK - s.players[0].slotCount[0],
                "ender_pearl carries useCooldown(1.0F) = 20 ticks, and ServerPlayerGameMode.useItem"
                        + " refuses a press while that cooldown is live");
    }

    @Test
    void alternatingThrowKindsCannotBeatTheHeldCadence() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, STACK, throwable(SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL));
        kit.give(0, 1, STACK, throwable(POTION_ITEM_ID, Combat.USE_SPLASH_POTION));

        for (int frame = 0; frame < TICKS; frame++) {
            Input use = new Input(false, false, false, false, false, false, false, false, true,
                    -90f, 0f, frame % 2);
            Simulation.tick(s, arena, use, Input.NONE);
        }

        int thrown = (STACK - s.players[0].slotCount[0]) + (STACK - s.players[0].slotCount[1]);
        assertEquals(cadenceCeiling(), thrown,
                "vanilla's rightClickDelay is one field on the client, not one per item, so"
                        + " swapping kinds under a held button cannot buy a faster cadence");
    }

    @Test
    void aMainHandSnowballCannotBeThrownFasterByTogglingTheUseBit() {
        Arena arena = Arena.flat(GROUND_Y);

        int held = thrownWhileHolding(mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL),
                arena, false, 0);
        int toggled = thrownWhileTogglingTheBit(
                mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL), arena, false, 0);

        assertEquals(cadenceCeiling(), held, "holding the button must throw once per cooldown");
        assertEquals(held, toggled,
                "releasing and re-pressing must not buy a throw the cooldown would refuse");
    }

    @Test
    void aMainHandSplashPotionCannotBeThrownFasterByTogglingTheUseBit() {
        Arena arena = Arena.flat(GROUND_Y);

        int held = thrownWhileHolding(mainHand(arena, POTION_ITEM_ID, Combat.USE_SPLASH_POTION),
                arena, false, 0);
        int toggled = thrownWhileTogglingTheBit(
                mainHand(arena, POTION_ITEM_ID, Combat.USE_SPLASH_POTION), arena, false, 0);

        assertEquals(cadenceCeiling(), held);
        assertEquals(held, toggled,
                "the potion rising edge must be gated by the same cooldown the hold path is");
    }

    @Test
    void anOffHandSnowballCannotBeThrownFasterByTogglingTheUseBit() {
        Arena arena = Arena.flat(GROUND_Y);

        int held = thrownWhileHolding(offHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL),
                arena, true, ItemDict.OFF_HAND);
        int toggled = thrownWhileTogglingTheBit(
                offHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL), arena, true,
                ItemDict.OFF_HAND);

        assertTrue(held > 0, "the off-hand path has to actually throw, or this proves nothing");
        assertEquals(cadenceCeiling(), held);
        assertEquals(held, toggled);
    }

    @Test
    void anOffHandSplashPotionCannotBeThrownFasterByTogglingTheUseBit() {
        Arena arena = Arena.flat(GROUND_Y);

        int held = thrownWhileHolding(offHand(arena, POTION_ITEM_ID, Combat.USE_SPLASH_POTION),
                arena, true, ItemDict.OFF_HAND);
        int toggled = thrownWhileTogglingTheBit(
                offHand(arena, POTION_ITEM_ID, Combat.USE_SPLASH_POTION), arena, true,
                ItemDict.OFF_HAND);

        assertEquals(cadenceCeiling(), held);
        assertEquals(held, toggled);
    }

    @Test
    void anOffHandXpBottleCannotBeThrownFasterByTogglingTheUseBit() {
        Arena arena = Arena.flat(GROUND_Y);

        int held = thrownWhileHolding(offHand(arena, BOTTLE_ITEM_ID, Combat.USE_XP_BOTTLE),
                arena, true, ItemDict.OFF_HAND);
        int toggled = thrownWhileTogglingTheBit(
                offHand(arena, BOTTLE_ITEM_ID, Combat.USE_XP_BOTTLE), arena, true,
                ItemDict.OFF_HAND);

        assertEquals(cadenceCeiling(), held);
        assertEquals(held, toggled);
    }

    private static final int PEARL_ITEM_ID = 813;
    private static final int WIND_ITEM_ID = 814;

    private static final int PACING_TICKS = 60;

    private static Input offPress() {
        return Input.NONE.withOffhandUsePress(true);
    }

    private static int thrownOver(GameState s, Arena arena, Input in, int ticks, int slot) {
        for (int frame = 0; frame < ticks; frame++) {
            Simulation.tick(s, arena, in, Input.NONE);
        }
        return STACK - s.players[0].slotCount[slot];
    }

    @Test
    void aMainHandPressCannotDriveAnOffHandThrowable() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = offHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL);

        int thrown = thrownOver(s, arena, mainPress(), TICKS, ItemDict.OFF_HAND);

        assertEquals(0, thrown,
                "usePress is the MAIN hand's rising edge; an off-hand item may only be driven by"
                        + " the off-hand's own bits, or clicking a sword empties the off hand");
    }

    @Test
    void anOffHandPressDrivesTheOffHandThrowableAtTheCadenceAndNoFaster() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = offHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL);

        int thrown = thrownOver(s, arena, offPress(), TICKS, ItemDict.OFF_HAND);

        assertEquals(TICKS / Combat.USE_REPEAT_DELAY, thrown,
                "an honest off-hand click must still throw, and an off-hand press bit pinned high"
                        + " every tick must not outrun the cadence the held bit obeys");
    }

    @Test
    void anOffHandPressIsStillGatedByARealItemCooldown() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = offHand(arena, PEARL_ITEM_ID, Combat.USE_PEARL);

        int thrown = thrownOver(s, arena, offPress(), PACING_TICKS, ItemDict.OFF_HAND);

        assertEquals(PACING_TICKS / Combat.PEARL_COOLDOWN_TICKS, thrown,
                "separating the hands must not hand the off hand a cooldown bypass");
    }

    @Test
    void aHeldPearlPacesOnItsOwnCooldownLikeEveryOtherThrowable() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = mainHand(arena, PEARL_ITEM_ID, Combat.USE_PEARL);

        int thrown = thrownOver(s, arena, mainUse(true), PACING_TICKS, 0);

        assertEquals(PACING_TICKS / Combat.PEARL_COOLDOWN_TICKS, thrown,
                "vanilla holds no per item 'must release first' rule: Minecraft.startUseItem runs"
                        + " off keyUse.isDown() and ender_pearl's useCooldown(1.0F) is the only"
                        + " thing pacing it, so a held pearl throws once every 20 ticks");
    }

    @Test
    void aHeldWindChargePacesOnItsOwnCooldownToo() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = mainHand(arena, WIND_ITEM_ID, Combat.USE_WIND_CHARGE);

        int thrown = thrownOver(s, arena, mainUse(true), PACING_TICKS, 0);

        assertEquals(PACING_TICKS / Combat.WIND_CHARGE_COOLDOWN_TICKS, thrown,
                "wind_charge carries useCooldown(0.5F) = 10 ticks and nothing else, so it must"
                        + " pace on that and not on a release-and-repress rule of our own");
    }

    @Test
    void theTwoThrowableFamiliesShareOneRule() {
        Arena arena = Arena.flat(GROUND_Y);

        int snowball = thrownOver(mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL), arena,
                mainUse(true), PACING_TICKS, 0);
        int pearl = thrownOver(mainHand(arena, PEARL_ITEM_ID, Combat.USE_PEARL), arena,
                mainUse(true), PACING_TICKS, 0);

        assertEquals(PACING_TICKS / Combat.USE_REPEAT_DELAY, snowball,
                "a zero cooldown throwable falls back to the held cadence");
        assertEquals(PACING_TICKS / Combat.PEARL_COOLDOWN_TICKS, pearl);
        assertTrue(snowball > pearl,
                "both families now run the same held rule and differ only by the cooldown vanilla"
                        + " actually gives them");
    }

    @Test
    void theHeldPathStillThrowsOnTheVeryFirstTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = mainHand(arena, SNOWBALL_ITEM_ID, Combat.USE_SNOWBALL);

        Simulation.tick(s, arena, mainUse(true), Input.NONE);
        assertEquals(STACK - 1, s.players[0].slotCount[0],
                "requiring the cooldown must not cost the player their first throw");
    }
}
