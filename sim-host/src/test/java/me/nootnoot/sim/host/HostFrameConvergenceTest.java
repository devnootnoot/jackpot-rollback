package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class HostFrameConvergenceTest {

    private static final double GROUND_Y = 64.0;
    private static final int FLOOR_Y = 63;

    private static final int TARGET_X = 3;
    private static final int TARGET_Y = 64;
    private static final int TARGET_Z = 0;

    private static final int CRYSTAL_X = 1;
    private static final int CRYSTAL_Y = 64;
    private static final int CRYSTAL_Z = 0;

    private static final int SWING_SUPPRESS_SAMPLES = 2;
    private static final int MINING_FINISH_GRACE = 20;
    private static final int CLIENT_DIG_TAIL = 6;

    private static final int MINE_TICKS = 8;

    private record Physical(int leftPresses, int rightPresses, int swapPresses,
                            boolean crystalUnderCrosshair, boolean holdingCrystal,
                            boolean aimingAtObsidian, boolean holdingGlowstone,
                            boolean holdingAnchor, int anchorCharge) {

        static Physical idle() {
            return new Physical(0, 0, 0, false, false, false, false, false,
                    InputFrameRules.NO_ANCHOR);
        }

        Physical clicking(int left, int right) {
            return new Physical(left, right, swapPresses, crystalUnderCrosshair, holdingCrystal,
                    aimingAtObsidian, holdingGlowstone, holdingAnchor, anchorCharge);
        }

        Physical swapping(int presses) {
            return new Physical(leftPresses, rightPresses, presses, crystalUnderCrosshair,
                    holdingCrystal, aimingAtObsidian, holdingGlowstone, holdingAnchor, anchorCharge);
        }

        Physical crystalLoop() {
            return new Physical(leftPresses, rightPresses, swapPresses, true, true, true,
                    holdingGlowstone, holdingAnchor, anchorCharge);
        }

        Physical glowstoneAtAnchor(int charge) {
            return new Physical(leftPresses, rightPresses, swapPresses, crystalUnderCrosshair,
                    false, false, true, false, charge);
        }
    }

    private static Input modded(Physical in) {
        boolean crystalHit = in.leftPresses() > 0 && in.crystalUnderCrosshair();
        int anchor = InputFrameRules.anchorAction(in.holdingGlowstone(), in.holdingAnchor(),
                in.anchorCharge());
        int blockAction = Input.BLOCK_NONE;
        if (in.rightPresses() > 0) {
            if (anchor == Input.BLOCK_CHARGE_ANCHOR) {
                blockAction = anchor;
            } else if (in.holdingCrystal() && in.aimingAtObsidian()) {
                blockAction = Input.BLOCK_PLACE_CRYSTAL;
            } else if (anchor != Input.BLOCK_NONE) {
                blockAction = anchor;
            }
        }
        boolean swapping = in.swapPresses() > 0;
        return new Input(false, false, false, false, false, false, false,
                in.leftPresses() > 0, in.rightPresses() > 0, 0f, 0f, 0)
                .withUsePress(in.rightPresses() > 0)
                .withCrystalHit(crystalHit, CRYSTAL_X, CRYSTAL_Y, CRYSTAL_Z)
                .withBlockAction(blockAction, TARGET_X, TARGET_Y, TARGET_Z)
                .withSwapHands(swapping)
                .withClicks(new Clicks(
                        InputFrameRules.attackClicks(blockAction, in.leftPresses()),
                        InputFrameRules.useClicks(blockAction, in.rightPresses()),
                        0, 0,
                        InputFrameRules.swapClicks(swapping, in.swapPresses())));
    }

    private static Input unmodded(Physical in) {
        int swingPackets = in.leftPresses();
        int placementPackets = in.rightPresses();
        int swapPackets = in.swapPresses();
        boolean crystalClaim = swingPackets > 0 && in.crystalUnderCrosshair();
        int anchor = InputFrameRules.anchorAction(in.holdingGlowstone(), in.holdingAnchor(),
                in.anchorCharge());
        int blockAction = Input.BLOCK_NONE;
        if (placementPackets > 0) {
            if (anchor == Input.BLOCK_CHARGE_ANCHOR) {
                blockAction = anchor;
            } else if (in.holdingCrystal() && in.aimingAtObsidian()) {
                blockAction = Input.BLOCK_PLACE_CRYSTAL;
            } else if (anchor != Input.BLOCK_NONE) {
                blockAction = anchor;
            }
        }
        boolean swapping = swapPackets > 0;
        return new Input(false, false, false, false, false, false, false,
                swingPackets > 0, placementPackets > 0, 0f, 0f, 0)
                .withUsePress(placementPackets > 0)
                .withCrystalHit(crystalClaim, CRYSTAL_X, CRYSTAL_Y, CRYSTAL_Z)
                .withBlockAction(blockAction, TARGET_X, TARGET_Y, TARGET_Z)
                .withSwapHands(swapping)
                .withClicks(new Clicks(
                        InputFrameRules.attackClicks(blockAction, swingPackets),
                        InputFrameRules.useClicks(blockAction, placementPackets),
                        0, 0,
                        InputFrameRules.swapClicks(swapping, swapPackets)));
    }

    private static void bothHostsAgree(Physical in, String why) {
        assertEquals(modded(in), unmodded(in), why);
    }

    @Test
    void theCrystalPlaceAndHitLoopRunsAtTheSameSpeedOnBothHosts() {
        Physical loop = Physical.idle().crystalLoop().clicking(1, 1);
        bothHostsAgree(loop,
                "a left click on a crystal and a right click on the base it stood on are two"
                        + " different keys, so one client tick carries both");

        Input frame = modded(loop);
        assertTrue(frame.crystalHit(),
                "the detonation rides its own wire channel");
        assertEquals(Input.BLOCK_PLACE_CRYSTAL, frame.blockAction(),
                "which leaves blockAction free for the crystal that replaces it, in the SAME"
                        + " frame. Spending blockAction on the hit is what made a modded player"
                        + " place on every OTHER tick, half an unmodded player's rate in the core"
                        + " crystal loop");
        assertEquals(1, frame.clicks().attack(), "the hit spends its own left click");
        assertEquals(1, frame.clicks().use(), "and the place spends its own right click");

        Input legacy = frame.withCrystalHit(false, 0, 0, 0)
                .withBlockAction(Input.BLOCK_HIT_CRYSTAL, CRYSTAL_X, CRYSTAL_Y, CRYSTAL_Z);
        assertNotEquals(frame, legacy,
                "and the one-field shape is a different frame, so this test fails the moment a"
                        + " host goes back to it");
    }

    @Test
    void aFullAnchorDetonatesOnBothHostsEvenWhileHoldingGlowstone() {
        for (int charge = 0; charge < Combat.ANCHOR_MAX_CHARGE; charge++) {
            Physical partial = Physical.idle().glowstoneAtAnchor(charge).clicking(0, 1);
            bothHostsAgree(partial, "an anchor with room takes the charge");
            assertEquals(Input.BLOCK_CHARGE_ANCHOR, modded(partial).blockAction(),
                    "charge " + charge + " of " + Combat.ANCHOR_MAX_CHARGE);
        }

        Physical full = Physical.idle().glowstoneAtAnchor(Combat.ANCHOR_MAX_CHARGE).clicking(0, 1);
        bothHostsAgree(full,
                "and a FULL anchor detonates instead, because a charge the sim would refuse is"
                        + " not what the player asked for");
        assertEquals(Input.BLOCK_DETONATE_ANCHOR, modded(full).blockAction(),
                "a modded player holding glowstone could never blow a full anchor: the charge"
                        + " branch swallowed the click and the sim refused it");
        assertEquals(Input.BLOCK_DETONATE_ANCHOR,
                InputFrameRules.anchorAction(true, false, Combat.ANCHOR_MAX_CHARGE),
                "the rule that decides between the three anchor opcodes lives in one place, so"
                        + " neither host can hold a different opinion about it");
        assertEquals(Input.BLOCK_NONE,
                InputFrameRules.anchorAction(false, false, InputFrameRules.NO_ANCHOR),
                "and a cell that is not an anchor is not an anchor action on either host");
    }

    @Test
    void everySwapFrameCarriesItsOwnClickCountOnBothHosts() {
        for (int presses = 1; presses <= Clicks.MAX; presses++) {
            Physical swap = Physical.idle().swapping(presses);
            bothHostsAgree(swap,
                    "the swap key is counted at frame rate on both hosts, so pressing it twice"
                            + " inside one 20Hz tick is two swaps and not one");
            assertEquals(presses, modded(swap).clicks().swap(),
                    "the fifth counter is on the wire and honoured by the sim, so a host that"
                            + " does not fill it caps swap-offhand at 10/s, half of vanilla");
        }
        assertEquals(1, InputFrameRules.swapClicks(true, 0),
                "a swap resolved off the key edge still names one click, so the sim's uncounted"
                        + " fallback is never the thing that decides the rate");
        assertEquals(0, InputFrameRules.swapClicks(false, Clicks.MAX),
                "and a frame with no swap carries no swap clicks");
    }

    @Test
    void aSwapNoLongerCancelsTheInventoryCountOnEitherHost() {
        Physical swap = Physical.idle().swapping(1);
        Clicks clicks = modded(swap).clicks().withInv(Clicks.MAX);
        assertEquals(Clicks.MAX, clicks.inv(),
                "the swap drain and the named inventory op read different counters now, so a"
                        + " frame carrying both no longer has to zero one of them");
        assertEquals(1, clicks.swap(), "and the swap keeps its own");
    }

    @Test
    void theSurplusUseClickIsCarriedForwardIdenticallyOnBothHosts() {
        for (int presses = 0; presses <= Clicks.MAX; presses++) {
            int spent = InputFrameRules.useClicks(Input.BLOCK_PLACE, presses);
            int carried = InputFrameRules.deferredUseClicks(Input.BLOCK_PLACE, presses);
            assertEquals(presses, spent + carried,
                    "a cell-naming frame spends one press and holds the rest back: the edge by"
                            + " leaving the identical intents queued, the mod by pushing the same"
                            + " number back into its press counter. Neither invents a press nor"
                            + " drops one");
            assertEquals(presses, InputFrameRules.useClicks(Input.BLOCK_NONE, presses)
                            + InputFrameRules.deferredUseClicks(Input.BLOCK_NONE, presses),
                    "and an untargeted frame carries the whole burst, with nothing deferred");
            assertEquals(0, InputFrameRules.deferredUseClicks(Input.BLOCK_NONE, presses),
                    "so a throw is never clipped back to the tick rate");
        }
    }

    private static final class SwingPackets {

        private boolean clientDigging;
        private int clientDigGrace;
        private boolean digging;
        private int digFinishGrace;
        private int swingSuppress;
        private int swings;
        private boolean crystalUnderCrosshair;

        void startDiggingPacket() {
            digging = true;
            clientDigging = true;
            clientDigGrace = 0;
            digFinishGrace = 0;
            swingSuppress = SWING_SUPPRESS_SAMPLES;
        }

        void finishedDiggingPacket() {
            digFinishGrace = MINING_FINISH_GRACE;
            clientDigGrace = CLIENT_DIG_TAIL;
        }

        void aimAtACrystal() {
            crystalUnderCrosshair = true;
        }

        void armSwingPacket() {
            if (swingSuppress > 0) {
                swingSuppress = 0;
                return;
            }
            swings++;
            if (clientDigging) {
                return;
            }
            digging = false;
            clientDigging = false;
            clientDigGrace = 0;
            digFinishGrace = 0;
        }

        Input frame() {
            if (clientDigging && clientDigGrace > 0 && --clientDigGrace == 0) {
                clientDigging = false;
            }
            boolean digHeld = clientDigging;
            if (swingSuppress > 0) {
                swingSuppress--;
            }
            int presses = swings;
            swings = 0;
            boolean crystalHit = crystalUnderCrosshair && presses > 0;
            if (crystalHit) {
                digging = false;
                clientDigging = false;
                clientDigGrace = 0;
                digFinishGrace = 0;
                digHeld = false;
            }
            int blockAction = Input.BLOCK_NONE;
            if (digging) {
                if (digFinishGrace > 0 && --digFinishGrace == 0) {
                    digging = false;
                } else {
                    blockAction = Input.BLOCK_BREAK;
                }
            }
            boolean attack = presses > 0 || digHeld || blockAction == Input.BLOCK_BREAK;
            return new Input(false, false, false, false, false, false, false, attack, false,
                    0f, 0f, 0)
                    .withBlockAction(blockAction, 0, FLOOR_Y, 0)
                    .withCrystalHit(crystalHit, crystalHit ? CRYSTAL_X : 0,
                            crystalHit ? CRYSTAL_Y : 0, crystalHit ? CRYSTAL_Z : 0)
                    .withClicks(new Clicks(InputFrameRules.attackClicks(blockAction, presses),
                            0, 0, 0, 0));
        }
    }

    private static final class Crosshair {

        private boolean onTheBlock;
        private int presses;

        void aimAtTheBlock() {
            onTheBlock = true;
        }

        void lookAway() {
            onTheBlock = false;
        }

        void leftPress() {
            presses++;
        }

        Input frame(boolean attackHeld) {
            int taken = presses;
            presses = 0;
            int blockAction = onTheBlock && (attackHeld || taken > 0)
                    ? Input.BLOCK_BREAK : Input.BLOCK_NONE;
            return new Input(false, false, false, false, false, false, false,
                    attackHeld || taken > 0, false, 0f, 0f, 0)
                    .withBlockAction(blockAction, 0, FLOOR_Y, 0)
                    .withClicks(new Clicks(InputFrameRules.attackClicks(blockAction, taken),
                            0, 0, 0, 0));
        }
    }

    private static GameState fresh(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        s.players[0].attackTicker = 0;
        s.players[1].x = 40.0;
        return s;
    }

    @Test
    void aWhiffRightAfterABlockBreaksSpendsTheSameAttackChargeOnBothHosts() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState moddedState = fresh(arena);
        GameState unmoddedState = fresh(arena);

        Crosshair mod = new Crosshair();
        SwingPackets edge = new SwingPackets();
        mod.aimAtTheBlock();
        mod.leftPress();
        edge.startDiggingPacket();
        edge.armSwingPacket();

        for (int tick = 0; tick < MINE_TICKS; tick++) {
            edge.armSwingPacket();
            Input a = mod.frame(true);
            Input b = edge.frame();
            assertEquals(a, b,
                    "holding left click on a block is the same frame on both hosts: a mining"
                            + " opcode, the held bit set, and no attack clicks");
            Simulation.tick(moddedState, arena, a, Input.NONE);
            Simulation.tick(unmoddedState, arena, b, Input.NONE);
        }

        edge.finishedDiggingPacket();
        for (int tail = 0; tail < CLIENT_DIG_TAIL; tail++) {
            edge.armSwingPacket();
            Input a = mod.frame(true);
            Input b = edge.frame();
            assertEquals(a, b,
                    "the client breaks the block before the sim does, and keeps swinging while"
                            + " its own destroyDelay drains. Both hosts keep naming the cell and"
                            + " neither turns that tail into an attack");
            Simulation.tick(moddedState, arena, a, Input.NONE);
            Simulation.tick(unmoddedState, arena, b, Input.NONE);
        }

        mod.lookAway();
        mod.leftPress();
        edge.armSwingPacket();
        Input moddedWhiff = mod.frame(false);
        Input unmoddedWhiff = edge.frame();

        assertEquals(moddedWhiff.clicks(), unmoddedWhiff.clicks(),
                "a fresh click after the block broke is an attack on both hosts. The unmodded"
                        + " client stops streaming dig animations once its own destroyDelay runs"
                        + " out, so a swing past that tail is a real click and must retire the"
                        + " stale mining tail rather than be swallowed by it");
        assertEquals(1, moddedWhiff.clicks().attack(),
                "the whiff spends a click, which is what resets the attack charge in vanilla");
        assertEquals(moddedWhiff, unmoddedWhiff,
                "and the whole frame agrees, mining opcode included: a swing at something else"
                        + " retires the stale mining tail on the edge exactly as the mod's live"
                        + " crosshair raycast stops naming the cell");

        Simulation.tick(moddedState, arena, moddedWhiff, Input.NONE);
        Simulation.tick(unmoddedState, arena, unmoddedWhiff, Input.NONE);

        assertEquals(moddedState.players[0].attackTicker, unmoddedState.players[0].attackTicker,
                "attack charge scales melee damage, so a swallowed whiff would hand the unmodded"
                        + " player a fully charged hit where the modded player got a weak one");
        assertEquals(Checksum.of(moddedState), Checksum.of(unmoddedState),
                "identical frames leave identical state");
    }

    @Test
    void theDigTailIsExactlyAsLongAsTheClientKeepsSwinging() {
        SwingPackets edge = new SwingPackets();
        edge.startDiggingPacket();
        edge.armSwingPacket();
        edge.frame();
        edge.finishedDiggingPacket();

        for (int tail = 0; tail < CLIENT_DIG_TAIL; tail++) {
            edge.armSwingPacket();
            assertEquals(0, edge.frame().clicks().attack(),
                    "the client keeps swinging while its destroyDelay drains, and none of those"
                            + " packets is an attack");
        }
        edge.armSwingPacket();
        assertTrue(edge.frame().clicks().attack() > 0,
                "but once the tail is over, a swing is a click again, instead of a whole second"
                        + " of attacks vanishing after every block a player mines");
    }
    @Test
    void aSwingAtACrystalWhileDiggingKeepsTheClickThatDetonatesIt() {
        SwingPackets edge = new SwingPackets();
        edge.startDiggingPacket();
        edge.frame();
        edge.frame();
        edge.aimAtACrystal();
        edge.armSwingPacket();

        Input frame = edge.frame();
        assertTrue(frame.crystalHit(), "the swing resolved to the crystal, so the dig is over");
        assertTrue(frame.clicks().attack() > 0,
                "Combat.hitCrystalOnce runs inside while (budget.takeAttack()), so a crystal"
                        + " frame carrying no attack click detonates nothing at all. The edge"
                        + " used to drop the packet before it was counted whenever the client"
                        + " happened to be digging, which is a rate an unmodded player could"
                        + " not reach and a modded one could");
    }

    private static int moddedLeftClick(double crystalDistanceSq, double opponentDistanceSq) {
        return InputFrameRules.leftClickTarget(crystalDistanceSq < Double.MAX_VALUE,
                crystalDistanceSq, opponentDistanceSq < Double.MAX_VALUE, opponentDistanceSq);
    }

    private static int unmoddedLeftClick(boolean crystalEntityClaim,
                                         double crosshairCrystalDistanceSq, boolean meleeClaim) {
        boolean crystal = crystalEntityClaim || crosshairCrystalDistanceSq < Double.MAX_VALUE;
        double crystalDistanceSq = crystalEntityClaim
                ? InputFrameRules.CLAIMED_DISTANCE_SQ : crosshairCrystalDistanceSq;
        return InputFrameRules.leftClickTarget(crystal, crystalDistanceSq, meleeClaim,
                InputFrameRules.CLAIMED_DISTANCE_SQ);
    }

    @Test
    void oneLeftClickNamesOneTargetOnBothHosts() {
        assertEquals(InputFrameRules.TARGET_CRYSTAL, moddedLeftClick(4.0, 9.0),
                "GameRenderer.pick returns the nearest entity along the ray and nothing else, so"
                        + " a crystal in front of the opponent is the whole click");
        assertEquals(InputFrameRules.TARGET_MELEE, moddedLeftClick(9.0, 4.0),
                "and an opponent in front of the crystal is the whole click the other way");
        assertEquals(InputFrameRules.TARGET_CRYSTAL,
                moddedLeftClick(4.0, Double.MAX_VALUE), "a lone crystal");
        assertEquals(InputFrameRules.TARGET_MELEE,
                moddedLeftClick(Double.MAX_VALUE, 4.0), "a lone opponent");
        assertEquals(InputFrameRules.TARGET_NONE,
                moddedLeftClick(Double.MAX_VALUE, Double.MAX_VALUE), "and a click at air");

        assertEquals(InputFrameRules.TARGET_CRYSTAL,
                unmoddedLeftClick(true, Double.MAX_VALUE, true),
                "the unmodded halves are both already resolved by the vanilla server, so they"
                        + " tie, and the crystal keeps the frame. That is the resolution the sim"
                        + " reaches anyway: hitCrystalOnce stamps meleeClaimTick and swingOnce"
                        + " refuses a second claim on the same tick");
        assertEquals(InputFrameRules.TARGET_MELEE, unmoddedLeftClick(false, 4.0, true),
                "but the crosshair scan is a guess, and a guess loses to the pick the server"
                        + " already made");
        assertEquals(InputFrameRules.TARGET_CRYSTAL, unmoddedLeftClick(false, 4.0, false),
                "with no melee claim the guess is all there is and it stands");

        for (int crystal = 0; crystal < 3; crystal++) {
            for (int melee = 0; melee < 3; melee++) {
                double cd = crystal == 2 ? Double.MAX_VALUE : crystal;
                double md = melee == 2 ? Double.MAX_VALUE : melee;
                int target = moddedLeftClick(cd, md);
                assertTrue(target == InputFrameRules.TARGET_NONE
                                || target == InputFrameRules.TARGET_CRYSTAL
                                || target == InputFrameRules.TARGET_MELEE,
                        "the rule is total and names exactly one target, so neither host can"
                                + " emit crystalHit and meleeHit on the same frame the way the"
                                + " mod used to");
            }
        }
    }
}
