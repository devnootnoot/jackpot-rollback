package me.nootnoot.sim.host;

import me.nootnoot.sim.contract.HostFrameContract;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;

public final class InputFrameRules {

    public static final int NO_ANCHOR = HostFrameContract.NO_ANCHOR;

    private InputFrameRules() {
    }

    public static int ruledBlockAction(GameState s, int blockAction) {
        return HostFrameContract.ruledBlockAction(s, blockAction);
    }

    public static boolean miningFrame(int blockAction) {
        return HostFrameContract.miningFrame(blockAction);
    }

    public static boolean placeableCell(GameState s, Arena arena, PlayerState a,
                                        int x, int y, int z) {
        return HostFrameContract.placeableCell(s, arena, a, x, y, z);
    }

    public static boolean webbableCell(GameState s, Arena arena, PlayerState a,
                                       int x, int y, int z) {
        return HostFrameContract.webbableCell(s, arena, a, x, y, z);
    }

    public static boolean crystalBaseCell(GameState s, Arena arena, PlayerState a,
                                          int x, int y, int z) {
        return HostFrameContract.crystalBaseCell(s, arena, a, x, y, z);
    }

    public static boolean containerOpens(GameState s, Arena arena, int x, int y, int z) {
        return HostFrameContract.containerOpens(s, arena, x, y, z);
    }

    public static boolean leftClickActs(boolean handsBusy) {
        return HostFrameContract.leftClickActs(handsBusy);
    }

    public static int attackClicks(int blockAction, int attackPresses) {
        return HostFrameContract.attackClicks(blockAction, attackPresses);
    }

    public static int useClickCap(int blockAction) {
        return HostFrameContract.useClickCap(blockAction);
    }

    public static boolean singleUseFrame(int blockAction) {
        return HostFrameContract.singleUseFrame(blockAction);
    }

    public static int useClicks(int blockAction, int usePresses) {
        return HostFrameContract.useClicks(blockAction, usePresses);
    }

    public static int deferredUseClicks(int blockAction, int usePresses) {
        return HostFrameContract.deferredUseClicks(blockAction, usePresses);
    }

    public static int repeatDrainLimit() {
        return HostFrameContract.repeatDrainLimit();
    }

    public static int drainableUseRepeats(int blockAction) {
        return HostFrameContract.drainableUseRepeats(blockAction);
    }

    public static int dropClicks(boolean dropping, int dropPresses) {
        return HostFrameContract.dropClicks(dropping, dropPresses);
    }

    public static int invClicks(boolean acting, int invPresses) {
        return HostFrameContract.invClicks(acting, invPresses);
    }

    public static int swapClicks(boolean swapping, int swapPresses) {
        return HostFrameContract.swapClicks(swapping, swapPresses);
    }

    public static boolean minesThisFrame(boolean crystalHit, boolean meleeHit) {
        return HostFrameContract.minesThisFrame(crystalHit, meleeHit);
    }

    public static final int MINING_TAIL_TICKS = HostFrameContract.MINING_TAIL_TICKS;

    public static boolean attackFrame(boolean swing, boolean digging, int blockAction) {
        return HostFrameContract.attackFrame(swing, digging, blockAction);
    }

    public static boolean mainHandConsumesUse(boolean placesIntoWorld, boolean equippable,
                                              boolean aimedAtBlock) {
        return HostFrameContract.mainHandConsumesUse(placesIntoWorld, equippable, aimedAtBlock);
    }

    public static boolean useFrame(boolean useAction, boolean handRaised) {
        return HostFrameContract.useFrame(useAction, handRaised);
    }

    public static int offhandUseKind(int stackUseKind) {
        return HostFrameContract.offhandUseKind(stackUseKind);
    }

    public static boolean offhandUseFrame(int offhandUseKind, boolean offhandPress,
                                          boolean offhandRaised) {
        return HostFrameContract.offhandUseFrame(offhandUseKind, offhandPress, offhandRaised);
    }

    public static boolean offhandUsePress(int offhandUseKind, boolean offhandPress) {
        return HostFrameContract.offhandUsePress(offhandUseKind, offhandPress);
    }

    public static boolean useAction(boolean useHeld, boolean usePress, boolean handRaised,
                                    int repeatCooldown) {
        return HostFrameContract.useAction(useHeld, usePress, handRaised, repeatCooldown);
    }

    public static int useRepeatCooldown(boolean useHeld, boolean acted, int repeatCooldown) {
        return HostFrameContract.useRepeatCooldown(useHeld, acted, repeatCooldown);
    }

    public static final int TARGET_NONE = HostFrameContract.TARGET_NONE;

    public static final int TARGET_CRYSTAL = HostFrameContract.TARGET_CRYSTAL;

    public static final int TARGET_MELEE = HostFrameContract.TARGET_MELEE;

    public static final double CLAIMED_DISTANCE_SQ = HostFrameContract.CLAIMED_DISTANCE_SQ;

    public static final double CRYSTAL_PICK_MISS = HostFrameContract.CRYSTAL_PICK_MISS;

    public static double crystalPickDistanceSq(GameState s, PlayerState a, int slot,
                                               double lookX, double lookY, double lookZ,
                                               int bx, int by, int bz) {
        return HostFrameContract.crystalPickDistanceSq(s, a, slot, lookX, lookY, lookZ,
                bx, by, bz);
    }

    public static int leftClickTarget(boolean crystalHit, double crystalDistanceSq,
                                      boolean meleeHit, double meleeDistanceSq) {
        return HostFrameContract.leftClickTarget(crystalHit, crystalDistanceSq,
                meleeHit, meleeDistanceSq);
    }

    public static int anchorCharge(GameState s, int x, int y, int z) {
        return HostFrameContract.anchorCharge(s, x, y, z);
    }

    public static int projectileClaim(GameState s, int owner) {
        return HostFrameContract.projectileClaim(s, owner);
    }

    public static int anchorAction(boolean holdingGlowstone, boolean holdingAnchor, int charge) {
        return HostFrameContract.anchorAction(holdingGlowstone, holdingAnchor, charge);
    }

    public static boolean elytraDeploy(boolean jumpDown, boolean prevJumpDown, boolean onGround,
                                       boolean hasElytra, boolean gliding, int cageFallTicks) {
        return HostFrameContract.elytraDeploy(jumpDown, prevJumpDown, onGround, hasElytra, gliding,
                cageFallTicks);
    }

    public static final int CHEST_EQUIP_COOLDOWN = HostFrameContract.CHEST_EQUIP_COOLDOWN;

    public static boolean chestEquip(boolean usePressEdge, boolean holdingChestArmour,
                                     int equipCooldown) {
        return HostFrameContract.chestEquip(usePressEdge, holdingChestArmour, equipCooldown);
    }

    public static boolean chestEquipUseFrame(boolean use, boolean usePress) {
        return HostFrameContract.chestEquipUseFrame(use, usePress);
    }

    public static boolean chestEquipPress(boolean usePress, boolean use, boolean prevUse) {
        return HostFrameContract.chestEquipPress(usePress, use, prevUse);
    }

    public static int chestEquipCooldown(boolean useHeld, int equipCooldown) {
        return HostFrameContract.chestEquipCooldown(useHeld, equipCooldown);
    }
}
