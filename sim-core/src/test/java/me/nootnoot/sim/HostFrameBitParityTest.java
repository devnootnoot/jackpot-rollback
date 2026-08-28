package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.contract.HostFrameContract;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class HostFrameBitParityTest {

    private static final double GROUND_Y = 64.0;

    private static final int OFF_HAND_ITEM_ID = 7401;

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

    private static List<Integer> everyUseKind() {
        List<Integer> out = new ArrayList<>();
        for (int kind = Combat.USE_NONE; kind <= Combat.USE_WIND_CHARGE; kind++) {
            out.add(kind);
        }
        return out;
    }

    private static Input attacking(boolean attack) {
        return new Input(false, false, false, false, false, false, false, attack, false,
                0f, 0f, 0);
    }

    @Test
    void aHeldMouseButtonIsNotAnAttackFrame() {
        assertFalse(HostFrameContract.attackFrame(false, false, Input.BLOCK_NONE),
                "with no swing packet and no dig there is nothing an unmodded client would have"
                        + " sent, so the frame carries no attack. The mod used to hand the sim the"
                        + " raw held button here");
        assertTrue(HostFrameContract.attackFrame(true, false, Input.BLOCK_NONE),
                "one counted click is one arm swing and one attack frame");
        assertTrue(HostFrameContract.attackFrame(false, true, Input.BLOCK_NONE),
                "a dig runs without a fresh click, and the edge has always reported attack for it");
        assertTrue(HostFrameContract.attackFrame(false, false, Input.BLOCK_BREAK),
                "a frame that names a break IS a dig, whatever the host's own latch says");
    }

    @Test
    void theHeldButtonAndTheSwingStreamLeaveTheSimInDifferentStates() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState held = duel(arena);
        GameState swung = duel(arena);

        for (int tick = 0; tick < 10; tick++) {
            boolean press = tick == 0;
            Input heldFrame = attacking(true);
            Input swungFrame = attacking(
                    HostFrameContract.attackFrame(press, false, Input.BLOCK_NONE));
            Simulation.tick(held, arena, heldFrame, Input.NONE);
            Simulation.tick(swung, arena, swungFrame, Input.NONE);
        }

        assertTrue(held.players[0].prevAttack,
                "the control case has to hold the bit down, or the comparison below is vacuous");
        assertFalse(swung.players[0].prevAttack,
                "a player who pressed once and then held reports attack on ONE frame if the bit"
                        + " means a swing, and on all ten if it means the button. prevAttack is a"
                        + " checksummed field and Combat.attackEdge is read off it, so this is not"
                        + " cosmetic: the two hosts hand the sim different state for the same"
                        + " physical hold");
    }

    @Test
    void theOffHandBitIsNeitherHostsRawButton() {
        assertFalse(HostFrameContract.offhandUseFrame(Combat.USE_PEARL, false, false),
                "no use action fired and no off hand is raised, so an unmodded client sent"
                        + " nothing. The mod used to report the off hand on every tick of a hold");
        assertTrue(HostFrameContract.offhandUseFrame(Combat.USE_PEARL, true, false),
                "the use action fell through to the off hand");
        assertTrue(HostFrameContract.offhandUseFrame(Combat.USE_FOOD, false, true),
                "an off-hand gapple is raised for its whole bite without a fresh click");
        assertFalse(HostFrameContract.offhandUseFrame(Combat.USE_NONE, true, true),
                "an off hand that can do nothing does nothing, however the button is held");
        assertTrue(HostFrameContract.offhandUsePress(Combat.USE_PEARL, true),
                "the press half is the discrete click Combat.useAttempts counts");
        assertFalse(HostFrameContract.offhandUsePress(Combat.USE_NONE, true),
                "a press with nothing usable in the off hand is not an off-hand press");
    }

    @Test
    void everyKindTheOffHandAdmitsIsOneTheSimulationCanFireAndNoOthers() {
        List<String> wrong = new ArrayList<>();
        for (int kind : everyUseKind()) {
            boolean admitted = HostFrameContract.offhandUseKind(kind) != Combat.USE_NONE;
            boolean fired = simulationFiresFromTheOffHand(kind);
            if (admitted != fired) {
                wrong.add("use kind " + kind + " is " + (admitted ? "admitted" : "refused")
                        + " by HostFrameContract.offhandUseKind but the simulation "
                        + (fired ? "does" : "does not") + " act on it from the off hand");
            }
        }
        assertTrue(wrong.isEmpty(),
                "the off-hand kind list is the one place both hosts decide what a right click"
                        + " means when the main hand is passive, and the only defensible content"
                        + " for it is exactly what Combat.handleOffhandUse acts on. A kind it"
                        + " admits that the sim ignores spends a click on nothing; a kind it"
                        + " refuses that the sim would have fired is a capability that is dead on"
                        + " both hosts and can only be discovered by a player: " + wrong);
    }

    private static boolean simulationFiresFromTheOffHand(int kind) {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        PlayerState a = s.players[0];
        a.food = 0f;
        a.slotEntry[0] = 0;
        a.slotCount[0] = 0;
        TestKit kit = TestKit.of(s);
        TestKit.Item item = TestKit.item().itemId(OFF_HAND_ITEM_ID).useKind(kind);
        if (kind == Combat.USE_FOOD) {
            item = TestKit.item().itemId(OFF_HAND_ITEM_ID).food(4, 1f, 0, true);
        }
        kit.give(0, ItemDict.OFF_HAND, 8, item);

        int before = a.offhandConsumeSeq;
        Input frame = Input.NONE
                .withOffhandUse(true)
                .withOffhandUsePress(true)
                .withClicks(new Clicks(0, 1, 0, 0, 0));
        for (int tick = 0; tick < Combat.EAT_DURATION + 8; tick++) {
            Simulation.tick(s, arena, frame, Input.NONE);
        }
        return s.players[0].offhandConsumeSeq > before;
    }

    @Test
    void aBlockUseFrameIsWorthTheOneClickTheSimulationDefines() {
        List<String> wrong = new ArrayList<>();
        for (int action = Input.BLOCK_NONE; action <= Input.BLOCK_CLOSE_CONTAINER; action++) {
            if (!Combat.isBlockUse(action)) {
                continue;
            }
            int clicks = HostFrameContract.useClicks(action, Clicks.MAX);
            int repeats = HostFrameContract.drainableUseRepeats(action);
            if (clicks != Combat.BLOCK_USE_CLICKS) {
                wrong.add("action " + action + " carries " + clicks + " use clicks, not "
                        + Combat.BLOCK_USE_CLICKS);
            }
            if (clicks + repeats != Combat.BLOCK_USE_CLICKS) {
                wrong.add("action " + action + " lets a host drain " + repeats + " repeats on top"
                        + " of its " + clicks + " click, which is " + (clicks + repeats)
                        + " presses spent on a frame the sim defines as worth "
                        + Combat.BLOCK_USE_CLICKS);
            }
            if (!HostFrameContract.singleUseFrame(action)) {
                wrong.add("action " + action + " is not reported as a single-use frame");
            }
        }
        assertTrue(wrong.isEmpty(),
                "how many use clicks a block-use frame is worth is defined once, by"
                        + " Combat.blockUseClicks over Combat.BLOCK_USE_CLICKS. Everything a"
                        + " producer consults - the counter it mints, the repeats it is allowed to"
                        + " drain behind the head intent, and the predicate that names the frame -"
                        + " has to fall out of that one number, or raising it later moves some of"
                        + " them and not the others: " + wrong);

        assertEquals(Clicks.MAX, HostFrameContract.useClickCap(Input.BLOCK_NONE),
                "a frame that names no world action is not capped, or a fast clicker loses"
                        + " presses on the ticks they are not building anything");
        assertEquals(HostFrameContract.repeatDrainLimit(),
                HostFrameContract.drainableUseRepeats(Input.BLOCK_NONE),
                "and it may drain the whole run of identical repeats behind the head intent");
        assertFalse(HostFrameContract.singleUseFrame(Input.BLOCK_NONE),
                "BLOCK_NONE is not a single-use frame");
    }
}
