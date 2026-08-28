package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.contract.HostFrameContract;
import org.junit.jupiter.api.Test;

class UseFrameContractTest {

    private static final String EDGE =
            "EdgeInputSource builds the frame's use bit as mainRaised || mainPress:"
                    + " mainPress is a use packet that arrived this tick, mainRaised is the SERVER"
                    + " reporting Player.isHandRaised for the main hand. An unmodded player"
                    + " therefore never produces a frame whose use is true while no use action"
                    + " fired and no item is being held up - the raw mouse button is not one of"
                    + " the two facts the edge can see";

    private static final String VANILLA_REPEAT =
            "MinecraftClient.handleKeybinds re-fires doItemUse only when itemUseCooldown == 0,"
                    + " and doItemUse sets itemUseCooldown = 4 every time it runs. A held right"
                    + " click on a non continuous item therefore acts on a 4 tick lattice, which"
                    + " is the same USE_REPEAT_DELAY the sim already paces repeats on";

    private static boolean held(boolean useHeld, boolean usePress, boolean handRaised,
                                int cooldown) {
        return HostFrameContract.useFrame(
                HostFrameContract.useAction(useHeld, usePress, handRaised, cooldown), handRaised);
    }

    @Test
    void useIsNeverTrueWithoutAUseActionOrARaisedHand() {
        assertFalse(HostFrameContract.useFrame(false, false), EDGE);
        assertTrue(HostFrameContract.useFrame(true, false), EDGE);
        assertTrue(HostFrameContract.useFrame(false, true), EDGE);
        assertTrue(HostFrameContract.useFrame(true, true), EDGE);
    }

    @Test
    void aHeldButtonAloneIsNotAUseActionOnceTheRepeatIsSpent() {
        assertTrue(HostFrameContract.useAction(true, false, false, 0),
                "the first tick of a hold is the doItemUse that vanilla runs immediately");
        assertFalse(HostFrameContract.useAction(true, false, false, 3),
                "the three ticks that follow are the itemUseCooldown, and an unmodded client"
                        + " sends nothing on them. Reading the raw GLFW button reported use on"
                        + " all four, which is the frame an unmodded player cannot make");
    }

    @Test
    void aRaisedHandHoldsUseTrueOnEveryTickWithoutAnyPacket() {
        assertTrue(held(true, false, true, 3),
                "a shield, a bow, a crossbow being loaded and food being eaten all keep"
                        + " isHandRaised true on the server, so the edge reports use on every"
                        + " tick of the hold and the mod must too");
    }

    @Test
    void aFreshPressAlwaysActsNoMatterWhatTheRepeatSaid() {
        assertTrue(HostFrameContract.useAction(true, true, false, 3),
                "vanilla drains useKey.consumeClick() into doItemUse without consulting"
                        + " itemUseCooldown, so a physical click is never swallowed by the"
                        + " lattice");
        assertTrue(HostFrameContract.useAction(false, true, false, 3),
                "a click pressed AND released inside one 20Hz sample still acted in vanilla");
    }

    @Test
    void theHeldLatticeMatchesTheSimsOwnRepeatDelay() {
        List<Integer> acted = new ArrayList<>();
        int cooldown = 0;
        for (int tick = 0; tick < 20; tick++) {
            boolean action = HostFrameContract.useAction(true, false, false, cooldown);
            if (action) {
                acted.add(tick);
            }
            cooldown = HostFrameContract.useRepeatCooldown(true, action, cooldown);
        }
        assertEquals(List.of(0, 4, 8, 12, 16), acted, VANILLA_REPEAT);
        assertEquals(Combat.USE_REPEAT_DELAY, 4,
                "if the sim's repeat delay ever stops being vanilla's itemUseCooldown the two"
                        + " lattices drift and a modded player throws at a different rate");
    }

    @Test
    void releasingTheButtonRearmsTheNextHoldImmediately() {
        int cooldown = HostFrameContract.useRepeatCooldown(true, true, 0);
        assertEquals(Combat.USE_REPEAT_DELAY - 1, cooldown,
                VANILLA_REPEAT + "; the tick the action fired is the first of the four, because"
                        + " Minecraft.tick ages itemUseCooldown before handleKeybinds reads it");
        assertEquals(0, HostFrameContract.useRepeatCooldown(false, false, cooldown),
                "letting go and pressing again is a fresh doItemUse in vanilla, not a wait for"
                        + " the remainder of the previous lattice");
    }

    @Test
    void aContinuousUseTickIsNotAlsoARepeatAction() {
        assertFalse(HostFrameContract.useAction(true, false, true, 0),
                "vanilla guards the held branch with !player.isUsingItem(), so drawing a bow"
                        + " does not also re-enter doItemUse every four ticks");
        assertEquals(0, HostFrameContract.useRepeatCooldown(true, false, 1),
                "the cooldown still ages while the hand is up, exactly as Minecraft.tick ages"
                        + " itemUseCooldown, so the tick the hand drops is not delayed");
    }
}
