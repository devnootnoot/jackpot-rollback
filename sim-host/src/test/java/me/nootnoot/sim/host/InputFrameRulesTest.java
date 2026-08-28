package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class InputFrameRulesTest {

    private static final double GROUND_Y = 64.0;

    private static final int MINE_TICKS = 40;

    private static final int SWING_PACKET_PERIOD = 3;

    private static final int FLOOR_Y = 63;

    private static final int CELL_A_X = 1;
    private static final int CELL_B_X = -1;

    private static final class ModdedHost {

        private int leftPresses;
        private int rightPresses;

        void leftPress() {
            leftPresses++;
        }

        void rightPress() {
            rightPresses++;
        }

        Input frame(boolean attackHeld, boolean useHeld, int blockAction, int targetX) {
            int attackPresses = leftPresses;
            int usePresses = rightPresses;
            leftPresses = 0;
            rightPresses = 0;
            int attackClicks = InputFrameRules.attackClicks(blockAction, attackPresses);
            int useClicks = InputFrameRules.useClicks(blockAction, usePresses);
            rightPresses = usePresses - useClicks;
            return new Input(false, false, false, false, false, false, false,
                    attackHeld, useHeld, 0f, 0f, 0)
                    .withBlockAction(blockAction, targetX, FLOOR_Y, 0)
                    .withClicks(new Clicks(attackClicks, useClicks, 0, 0, 0));
        }
    }

    private static final class UnmoddedHost {

        private int swingPackets;
        private boolean clientDigging;
        private final List<Integer> useIntents = new ArrayList<>();

        void startDiggingPacket() {
            clientDigging = true;
        }

        void abortDiggingPacket() {
            clientDigging = false;
        }

        void armSwingPacket() {
            if (clientDigging) {
                return;
            }
            swingPackets++;
        }

        void blockPlacementPacket(int targetX) {
            useIntents.add(targetX);
        }

        Input frame(int blockAction) {
            boolean digging = clientDigging;
            int swings = swingPackets;
            swingPackets = 0;
            int targetX = 0;
            int useClicks = 0;
            if (!useIntents.isEmpty()) {
                targetX = useIntents.remove(0);
                useClicks = 1;
                if (!InputFrameRules.singleUseFrame(blockAction)) {
                    while (useClicks < Clicks.MAX && !useIntents.isEmpty()
                            && useIntents.get(0) == targetX) {
                        useIntents.remove(0);
                        useClicks++;
                    }
                }
            }
            boolean attack = swings > 0 || digging;
            int cell = InputFrameRules.miningFrame(blockAction) ? 0 : targetX;
            return new Input(false, false, false, false, false, false, false,
                    attack, useClicks > 0, 0f, 0f, 0)
                    .withBlockAction(blockAction, cell, FLOOR_Y, 0)
                    .withClicks(new Clicks(InputFrameRules.attackClicks(blockAction, swings),
                            useClicks, 0, 0, 0));
        }
    }

    private static GameState fresh(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        s.players[0].attackTicker = 0;
        s.players[1].x = 40.0;
        return s;
    }

    private void holdLeftClickOnABlock(int blockAction, int minimumCharge) {
        Arena arena = Arena.flat(GROUND_Y);
        GameState modded = fresh(arena);
        GameState unmodded = fresh(arena);

        ModdedHost mod = new ModdedHost();
        UnmoddedHost edge = new UnmoddedHost();
        mod.leftPress();
        edge.startDiggingPacket();

        for (int tick = 0; tick < MINE_TICKS; tick++) {
            if (tick % SWING_PACKET_PERIOD == 0) {
                edge.armSwingPacket();
            }
            Input a = mod.frame(true, false, blockAction, 0);
            Input b = edge.frame(blockAction);

            if (tick > 0) {
                assertEquals(a.clicks(), b.clicks(),
                        "the vanilla client's arm-swing packets while it destroys a block are the"
                                + " unmodded half of the same physical input the mod counts off"
                                + " GLFW, so neither host may keep spending attack clicks for the"
                                + " rest of the hold. Tick zero is exempt because that is the press"
                                + " itself: the mod carries it as a count and the edge as the"
                                + " rising edge of the held state, and the sim spends exactly one"
                                + " attack unit either way, which the checksum below proves");
            }
            assertEquals(a.attack(), b.attack(),
                    "and the same held state, so the rising edge that gates a fresh destroy"
                            + " lands on the same tick for both");

            Simulation.tick(modded, arena, a, Input.NONE);
            Simulation.tick(unmodded, arena, b, Input.NONE);
        }

        assertEquals(modded.players[0].attackTicker, unmodded.players[0].attackTicker,
                "attack charge scales melee damage, so the two populations must accumulate it at"
                        + " the same rate for the same physical input");
        assertTrue(unmodded.players[0].attackTicker >= minimumCharge,
                "an uninterrupted hold swings at most once, so the charge runs for the rest of"
                        + " the dig and the next hit lands fully charged");
        assertEquals(Checksum.of(modded), Checksum.of(unmodded),
                "identical frames leave identical state");
    }

    @Test
    void holdingLeftClickOnABlockTheSimMinesKeepsTheSameAttackChargeOnBothHosts() {
        holdLeftClickOnABlock(Input.BLOCK_BREAK, MINE_TICKS - 1);
    }

    @Test
    void holdingLeftClickOnABlockTheSimRefusesKeepsTheSameAttackChargeOnBothHosts() {
        holdLeftClickOnABlock(Input.BLOCK_NONE, MINE_TICKS - 2);
    }

    @Test
    void twoUsePressesAtDifferentCellsAreOnePerFrameOnBothHosts() {
        ModdedHost mod = new ModdedHost();
        UnmoddedHost edge = new UnmoddedHost();

        mod.rightPress();
        mod.rightPress();
        edge.blockPlacementPacket(CELL_A_X);
        edge.blockPlacementPacket(CELL_B_X);

        Input modFirst = mod.frame(false, true, Input.BLOCK_PLACE, CELL_A_X);
        Input edgeFirst = edge.frame(Input.BLOCK_PLACE);
        assertEquals(1, modFirst.clicks().use(),
                "the frame names ONE target cell, so a block targeted use carries exactly one"
                        + " press and the surplus waits for the frame that names its own cell");
        assertEquals(modFirst.clicks(), edgeFirst.clicks(),
                "both hosts hold the surplus press back rather than firing it at a cell it never"
                        + " targeted");

        Input modSecond = mod.frame(false, true, Input.BLOCK_PLACE, CELL_B_X);
        Input edgeSecond = edge.frame(Input.BLOCK_PLACE);
        assertEquals(1, modSecond.clicks().use(),
                "the deferred press is not lost, it is spent on the next frame");
        assertEquals(modSecond.clicks(), edgeSecond.clicks(),
                "and the second frame agrees too, so no click is dropped on either host");
        assertEquals(CELL_B_X, modSecond.targetX(),
                "the deferred press resolves against the cell the crosshair names when it is"
                        + " actually spent");
        assertEquals(CELL_B_X, edgeSecond.targetX(),
                "which is the cell the queued intent carried all along");
    }

    @Test
    void anUntargetedUseStillRidesTheWholeCount() {
        ModdedHost mod = new ModdedHost();
        UnmoddedHost edge = new UnmoddedHost();

        mod.rightPress();
        mod.rightPress();
        mod.rightPress();
        edge.blockPlacementPacket(0);
        edge.blockPlacementPacket(0);
        edge.blockPlacementPacket(0);

        Input modFrame = mod.frame(false, true, Input.BLOCK_NONE, 0);
        Input edgeFrame = edge.frame(Input.BLOCK_NONE);

        assertEquals(3, modFrame.clicks().use(),
                "a throw names no cell, so the whole burst rides one frame and a fast clicker is"
                        + " not clipped back to the tick rate");
        assertEquals(modFrame.clicks(), edgeFrame.clicks(),
                "the untargeted path is unchanged on both hosts");
    }

    @Test
    void everyBlockUseOpcodeIsSingleTargetAndOnlyMiningZeroesTheAttackCount() {
        int[] blockUses = {Input.BLOCK_PLACE, Input.BLOCK_PLACE_OFFHAND, Input.BLOCK_PLACE_CRYSTAL,
                Input.BLOCK_PLACE_ANCHOR, Input.BLOCK_CHARGE_ANCHOR, Input.BLOCK_DETONATE_ANCHOR,
                Input.BLOCK_PLACE_WATER, Input.BLOCK_PLACE_LAVA, Input.BLOCK_PICKUP_FLUID,
                Input.BLOCK_OPEN_CONTAINER};
        for (int action : blockUses) {
            assertTrue(InputFrameRules.singleUseFrame(action),
                    "every action that names a cell and consumes a right click is one per frame");
            assertEquals(1, InputFrameRules.useClicks(action, Clicks.MAX),
                    "a maxed count cannot multiply an action at a single named cell");
            assertEquals(Clicks.MAX, InputFrameRules.attackClicks(action, Clicks.MAX),
                    "only mining touches the attack count");
        }
        assertEquals(0, InputFrameRules.attackClicks(Input.BLOCK_BREAK, Clicks.MAX),
                "a mining frame carries no attack clicks");
        assertEquals(Clicks.MAX, InputFrameRules.useClicks(Input.BLOCK_BREAK, Clicks.MAX),
                "mining is attack driven, so it does not touch the use count");
        assertEquals(Clicks.MAX, InputFrameRules.useClicks(Input.BLOCK_HIT_CRYSTAL, Clicks.MAX),
                "a crystal hit is attack driven too");
        assertEquals(Clicks.MAX, InputFrameRules.useClicks(Input.BLOCK_NONE, Clicks.MAX),
                "an untargeted frame is not capped");
    }
}
