package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CrossbowHoldTest {
    private static final double GROUND_Y = 64.0;
    private static final int CROSSBOW_ITEM_ID = 7401;
    private static final int ARROW_ITEM_ID = 7402;

    private static GameState armed() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        p.x = 0.5;
        p.y = GROUND_Y;
        p.z = 0.5;
        p.onGround = true;
        p.health = 20f;
        PlayerState v = s.players[1];
        v.x = 10_000.0;
        v.y = GROUND_Y;
        v.z = 0.5;
        v.health = 20f;
        TestKit kit = TestKit.of(s);
        int crossbow = kit.add(TestKit.item().itemId(CROSSBOW_ITEM_ID).maxStack(1)
                .useKind(Combat.USE_CROSSBOW).flags(ItemDict.FLAG_CROSSBOW));
        int arrow = kit.add(TestKit.item().itemId(ARROW_ITEM_ID)
                .flags(ItemDict.FLAG_ARROW_PLAIN));
        kit.put(0, 0, crossbow, 1);
        kit.put(0, 1, arrow, 8);
        return s;
    }

    private static Input hold() {
        return new Input(false, false, false, false, false, false, false, false, true, 0f, 0f, 0);
    }

    private static Input press() {
        return hold().withUsePress(true);
    }

    @Test
    void aChargedCrossbowHoldsItsBoltWhileTheButtonStaysDown() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = armed();
        PlayerState p = s.players[0];

        for (int i = 0; i < Combat.CROSSBOW_LOAD + 1; i++) {
            Simulation.tick(s, arena, hold(), Input.NONE);
        }
        assertTrue(p.slotCrossbowLoaded[0], "the charge has to complete first");

        for (int i = 0; i < 40; i++) {
            Simulation.tick(s, arena, hold(), Input.NONE);
        }

        assertTrue(p.slotCrossbowLoaded[0],
                "CrossbowItem.getUseDuration is 72000, so finishing the charge does NOT end the"
                        + " use: Minecraft.handleKeybinds drains every use click into an empty"
                        + " loop while isUsingItem(), and a held crossbow just sits there loaded");
        assertEquals(0, s.projectiles.size(), "nothing may leave the crossbow on its own");
    }

    @Test
    void releasingAndPressingAgainIsWhatFiresIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = armed();
        PlayerState p = s.players[0];

        for (int i = 0; i < Combat.CROSSBOW_LOAD + 1; i++) {
            Simulation.tick(s, arena, hold(), Input.NONE);
        }
        assertTrue(p.slotCrossbowLoaded[0], "the charge has to complete first");
        assertEquals(0, s.projectiles.size(), "and it may not have fired yet");

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        Simulation.tick(s, arena, press(), Input.NONE);

        assertFalse(p.slotCrossbowLoaded[0],
                "the next use call finds a charged crossbow and performShooting empties it");
        assertEquals(1, s.projectiles.size(), "exactly one bolt for the one fresh press");
    }

    @Test
    void theHeldUseLatchClearsEvenWhileSomeOtherItemIsInHand() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = armed();
        PlayerState p = s.players[0];

        for (int i = 0; i < Combat.CROSSBOW_LOAD + 1; i++) {
            Simulation.tick(s, arena, hold(), Input.NONE);
        }
        assertTrue(p.slotCrossbowLoaded[0], "the charge has to complete first");

        Simulation.tick(s, arena, Input.NONE.withHeldSlot(1), Input.NONE);
        Simulation.tick(s, arena, press().withHeldSlot(0), Input.NONE);

        assertFalse(p.slotCrossbowLoaded[0],
                "the button came up while the arrows were in hand, so the gesture that charged"
                        + " the crossbow is over and the next press has to fire it");
        assertEquals(1, s.projectiles.size(),
                "a latch that only clears while the crossbow itself is held jams the crossbow"
                        + " shut for the rest of the round");
    }

    @Test
    void aSecondCrossbowIsNotJammedByTheChargeOfTheFirst() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = armed();
        PlayerState p = s.players[0];
        TestKit kit = TestKit.of(s);
        int spare = kit.add(TestKit.item().itemId(CROSSBOW_ITEM_ID + 1).maxStack(1)
                .useKind(Combat.USE_CROSSBOW).flags(ItemDict.FLAG_CROSSBOW));
        kit.put(0, 2, spare, 1);
        p.slotCrossbowLoaded[2] = true;
        p.slotCrossbowConsumed[2] = true;
        p.slotCrossbowEntry[2] = p.slotEntry[1];

        for (int i = 0; i < Combat.CROSSBOW_LOAD + 1; i++) {
            Simulation.tick(s, arena, hold(), Input.NONE);
        }
        assertTrue(p.slotCrossbowLoaded[0], "the charge has to complete first");

        Simulation.tick(s, arena, press().withHeldSlot(2), Input.NONE);

        assertEquals(1, s.projectiles.size(),
                "the latch belongs to the crossbow that was charged, not to the player: the spare"
                        + " was already loaded when the round started and owes nobody a release");
        assertTrue(p.slotCrossbowLoaded[0], "and the charged one keeps its bolt");
    }

    @Test
    void aCrossbowThatWasAlreadyChargedAtMatchStartFiresOnTheFirstPress() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = armed();
        PlayerState p = s.players[0];
        p.slotCrossbowLoaded[0] = true;
        p.slotCrossbowConsumed[0] = true;
        p.slotCrossbowEntry[0] = p.slotEntry[1];

        Simulation.tick(s, arena, press(), Input.NONE);

        assertEquals(1, s.projectiles.size(),
                "nobody held the button through that charge, so there is no gesture to release");
    }
}
