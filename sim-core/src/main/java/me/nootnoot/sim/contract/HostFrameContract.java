package me.nootnoot.sim.contract;

import me.nootnoot.sim.Combat;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;

public final class HostFrameContract {

    public static final int NO_ANCHOR = -1;

    private HostFrameContract() {
    }

    public static int ruledBlockAction(GameState s, int blockAction) {
        return s != null && Combat.ruleForbids(s, blockAction) ? Input.BLOCK_NONE : blockAction;
    }

    public static boolean placeableCell(GameState s, Arena arena, PlayerState a,
                                        int x, int y, int z) {
        return s == null || a == null || arena == null || Combat.placementOpen(s, arena, a, x, y, z);
    }

    public static boolean webbableCell(GameState s, Arena arena, PlayerState a,
                                       int x, int y, int z) {
        return s == null || a == null || arena == null || Combat.webPlacementOpen(s, arena, a, x, y, z);
    }

    public static boolean crystalBaseCell(GameState s, Arena arena, PlayerState a,
                                          int x, int y, int z) {
        return s == null || a == null || arena == null || Combat.crystalPlacementOpen(s, arena, a, x, y, z);
    }

    public static boolean containerOpens(GameState s, Arena arena, int x, int y, int z) {
        return s == null || arena == null || !Combat.solidCell(s, arena, x, y + 1, z);
    }

    public static boolean leftClickActs(boolean handsBusy) {
        return !handsBusy;
    }

    public static boolean screenQuietFrame(boolean forward, boolean back, boolean left,
                                           boolean right, boolean jump, boolean sprint,
                                           boolean attack, boolean use, boolean offhandUse,
                                           boolean meleeHit, boolean crystalHit, int blockAction,
                                           int attackClicks, int useClicks) {
        return !forward && !back && !left && !right && !jump && !sprint
                && !attack && !use && !offhandUse && !meleeHit && !crystalHit
                && blockAction == Input.BLOCK_NONE && attackClicks == 0 && useClicks == 0;
    }

    public static boolean worldActionFrame(boolean meleeHit, boolean crystalHit, int blockAction) {
        return meleeHit || crystalHit || blockAction != Input.BLOCK_NONE;
    }

    public static boolean worldActionFrame(Input in) {
        return in != null
                && worldActionFrame(in.meleeHit(), in.crystalHit(), in.blockAction());
    }

    public static boolean screenQuietFrame(Input in) {
        return in != null && screenQuietFrame(in.forward(), in.back(), in.left(), in.right(),
                in.jump(), in.sprint(), in.attack(), in.use(), in.offhandUse(), in.meleeHit(),
                in.crystalHit(), in.blockAction(), in.clicks().attack(), in.clicks().use());
    }

    public static boolean miningFrame(int blockAction) {
        return blockAction == Input.BLOCK_BREAK;
    }

    public static int attackClicks(int blockAction, int attackPresses) {
        return miningFrame(blockAction) ? 0 : attackPresses;
    }

    public static int useClickCap(int blockAction) {
        return Combat.blockUseClicks(blockAction, Clicks.MAX);
    }

    public static boolean singleUseFrame(int blockAction) {
        return useClickCap(blockAction) < Clicks.MAX;
    }

    public static int useClicks(int blockAction, int usePresses) {
        return Combat.blockUseClicks(blockAction, usePresses);
    }

    public static int deferredUseClicks(int blockAction, int usePresses) {
        return Math.max(0, usePresses - useClicks(blockAction, usePresses));
    }

    public static int repeatDrainLimit() {
        return Clicks.MAX - 1;
    }

    public static int drainableUseRepeats(int blockAction) {
        return Math.min(repeatDrainLimit(), useClickCap(blockAction) - 1);
    }

    public static int dropClicks(boolean dropping, int dropPresses) {
        return countedClicks(dropping, dropPresses);
    }

    public static int invClicks(boolean acting, int invPresses) {
        return countedClicks(acting, invPresses);
    }

    public static int swapClicks(boolean swapping, int swapPresses) {
        return countedClicks(swapping, swapPresses);
    }

    private static int countedClicks(boolean acting, int presses) {
        return acting ? Math.max(1, Clicks.clamp(presses)) : 0;
    }

    public static boolean minesThisFrame(boolean crystalHit, boolean meleeHit) {
        return !crystalHit && !meleeHit;
    }

    public static final int MINING_TAIL_TICKS = 6;

    public static boolean attackFrame(boolean swing, boolean digging, int blockAction) {
        return swing || digging || miningFrame(blockAction);
    }

    public static boolean mainHandConsumesUse(boolean placesIntoWorld, boolean equippable,
                                              boolean aimedAtBlock) {
        return equippable || (placesIntoWorld && aimedAtBlock);
    }

    public static boolean useFrame(boolean useAction, boolean handRaised) {
        return useAction || handRaised;
    }

    public static int offhandUseKind(int stackUseKind) {
        return stackUseKind == Combat.USE_PEARL || stackUseKind == Combat.USE_SNOWBALL
                || stackUseKind == Combat.USE_EGG || stackUseKind == Combat.USE_SPLASH_POTION
                || stackUseKind == Combat.USE_XP_BOTTLE || stackUseKind == Combat.USE_WIND_CHARGE
                || stackUseKind == Combat.USE_FOOD
                ? stackUseKind : Combat.USE_NONE;
    }

    public static boolean offhandUseFrame(int offhandUseKind, boolean offhandPress,
                                          boolean offhandRaised) {
        return offhandUseKind != Combat.USE_NONE && (offhandPress || offhandRaised);
    }

    public static boolean offhandUsePress(int offhandUseKind, boolean offhandPress) {
        return offhandUseKind != Combat.USE_NONE && offhandPress;
    }

    public static boolean useAction(boolean useHeld, boolean usePress, boolean handRaised,
                                    int repeatCooldown) {
        return usePress || (useHeld && !handRaised && repeatCooldown <= 0);
    }

    public static int useRepeatCooldown(boolean useHeld, boolean acted, int repeatCooldown) {
        if (!useHeld) {
            return 0;
        }
        if (acted) {
            return Combat.USE_REPEAT_DELAY - 1;
        }
        return repeatCooldown > 0 ? repeatCooldown - 1 : 0;
    }

    public static final int TARGET_NONE = 0;

    public static final int TARGET_CRYSTAL = 1;

    public static final int TARGET_MELEE = 2;

    public static final double CLAIMED_DISTANCE_SQ = 0.0;

    public static final double CRYSTAL_PICK_MISS = Combat.CRYSTAL_PICK_MISS;

    public static double crystalPickDistanceSq(GameState s, PlayerState a, int slot,
                                               double lookX, double lookY, double lookZ,
                                               int bx, int by, int bz) {
        return s == null || a == null ? CRYSTAL_PICK_MISS
                : Combat.crystalPickDistanceSq(s, a, slot, lookX, lookY, lookZ, bx, by, bz);
    }

    public static int leftClickTarget(boolean crystalHit, double crystalDistanceSq,
                                      boolean meleeHit, double meleeDistanceSq) {
        if (crystalHit && meleeHit) {
            return crystalDistanceSq <= meleeDistanceSq ? TARGET_CRYSTAL : TARGET_MELEE;
        }
        if (crystalHit) {
            return TARGET_CRYSTAL;
        }
        if (meleeHit) {
            return TARGET_MELEE;
        }
        return TARGET_NONE;
    }

    public static boolean elytraDeploy(boolean jumpDown, boolean prevJumpDown, boolean onGround,
                                       boolean hasElytra, boolean gliding, int cageFallTicks) {
        return jumpDown && !prevJumpDown && !onGround && hasElytra && !gliding && cageFallTicks <= 0;
    }

    public static final int CHEST_EQUIP_COOLDOWN = 5;

    public static boolean chestEquip(boolean usePressEdge, boolean holdingChestArmour,
                                     int equipCooldown) {
        return usePressEdge && holdingChestArmour && equipCooldown <= 0;
    }

    public static boolean chestEquipUseFrame(boolean use, boolean usePress) {
        return use || usePress;
    }

    public static boolean chestEquipPress(boolean usePress, boolean use, boolean prevUse) {
        return usePress || (use && !prevUse);
    }

    public static int chestEquipCooldown(boolean useHeld, int equipCooldown) {
        if (useHeld) {
            return CHEST_EQUIP_COOLDOWN;
        }
        return equipCooldown > 0 ? equipCooldown - 1 : 0;
    }

    public static int anchorCharge(GameState s, int x, int y, int z) {
        if (s == null) {
            return NO_ANCHOR;
        }
        Integer charge = s.anchors.get(BlockStore.key(x, y, z));
        return charge == null ? NO_ANCHOR : charge;
    }

    public static int projectileClaim(GameState s, int owner) {
        return Combat.projectileClaim(s, owner);
    }

    public static int anchorAction(boolean holdingGlowstone, boolean holdingAnchor, int charge) {
        if (holdingGlowstone && charge >= 0 && charge < Combat.ANCHOR_MAX_CHARGE) {
            return Input.BLOCK_CHARGE_ANCHOR;
        }
        if (holdingAnchor && charge < 1) {
            return Input.BLOCK_PLACE_ANCHOR;
        }
        if (charge >= 1) {
            return Input.BLOCK_DETONATE_ANCHOR;
        }
        return Input.BLOCK_NONE;
    }
}
