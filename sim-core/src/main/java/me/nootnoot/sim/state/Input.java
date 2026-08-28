package me.nootnoot.sim.state;

public record Input(
        boolean forward,
        boolean back,
        boolean left,
        boolean right,
        boolean jump,
        boolean sprint,
        boolean sneak,
        boolean attack,
        boolean use,
        boolean usePress,
        boolean offhandUse,
        boolean offhandUsePress,

        boolean meleeHit,

        boolean dropItem,
        boolean dropStack,
        boolean swapHands,

        float yaw,
        float pitch,
        int heldSlot,

        int blockAction,
        int targetX,
        int targetY,
        int targetZ,

        int projectileHit,

        int invAction,
        int invSrc,
        int invDst,

        Authority authority,
        Clicks clicks,

        boolean crystalHit,
        int crystalX,
        int crystalY,
        int crystalZ,
        boolean elytraStart,

        boolean synthetic
) {
    public static final int BLOCK_NONE = 0;
    public static final int BLOCK_PLACE = 1;
    public static final int BLOCK_BREAK = 2;

    public static final int BLOCK_PLACE_CRYSTAL = 3;

    public static final int BLOCK_HIT_CRYSTAL = 4;

    public static final int BLOCK_PLACE_ANCHOR = 5;

    public static final int BLOCK_CHARGE_ANCHOR = 6;

    public static final int BLOCK_DETONATE_ANCHOR = 7;

    public static final int BLOCK_PLACE_WATER = 8;

    public static final int BLOCK_PLACE_LAVA = 9;

    public static final int BLOCK_PICKUP_FLUID = 10;

    public static final int BLOCK_PLACE_OFFHAND = 11;

    public static final int BLOCK_OPEN_CONTAINER = 12;

    public static final int BLOCK_CLOSE_CONTAINER = 13;

    public static final int INV_NONE = 0;
    public static final int INV_MOVE = 1;
    public static final int INV_DROP_ONE = 2;
    public static final int INV_DROP_STACK = 3;
    public static final int INV_CONTAINER_TAKE = 4;
    public static final int INV_CONTAINER_PUT = 5;

    public static final int INV_PICKUP = 6;

    public static final int INV_PICKUP_HALF = 7;

    public static final int INV_SWAP_SLOT = 8;

    public static final int INV_PICKUP_ALL = 9;

    public static final int INV_DROP_CURSOR_ONE = 10;

    public static final int INV_DROP_CURSOR_ALL = 11;

    public static final int INV_CURSOR_RESOLVE = 12;

    public static final int INV_QUICK_MOVE = 13;

    public static final int INV_MAX = INV_QUICK_MOVE;

    public static final int ADDR_CELL_BASE = 64;

    public static boolean addrIsCell(int addr) {
        return addr >= ADDR_CELL_BASE;
    }

    public static int addrIndex(int addr) {
        return addr >= ADDR_CELL_BASE ? addr - ADDR_CELL_BASE : addr;
    }

    public static int cellAddr(int cell) {
        return ADDR_CELL_BASE + cell;
    }

    public static final int NO_PROJECTILE_HIT = -1;

    public static final int PROJECTILE_HIT_SELF = 0x4000;
    public static final int PROJECTILE_HIT_ID_MASK = 0xFFF;

    public static final int MAX_TARGET_COORD = (1 << 20) - 1;

    public static final double MAX_WORLD_COORD = MAX_TARGET_COORD;

    public static final float MAX_WRAPPABLE_YAW = 1 << 20;

    public Input {
        yaw = wrapYaw(yaw);
        pitch = clampPitch(pitch);
        heldSlot = Math.max(0, Math.min(8, heldSlot));
        blockAction = blockAction < 0 || blockAction > BLOCK_CLOSE_CONTAINER ? BLOCK_NONE : blockAction;
        invAction = invAction < 0 || invAction > INV_MAX ? INV_NONE : invAction;
        targetX = clampCoord(targetX);
        targetY = clampCoord(targetY);
        targetZ = clampCoord(targetZ);
        crystalX = clampCoord(crystalX);
        crystalY = clampCoord(crystalY);
        crystalZ = clampCoord(crystalZ);
        projectileHit = (short) projectileHit;
        invSrc = invSrc & 0xFF;
        invDst = invDst & 0xFF;
        authority = canonicalAuthority(authority);
        clicks = clicks == null ? Clicks.NONE : clicks;
        if (synthetic) {
            usePress = false;
            offhandUsePress = false;
            meleeHit = false;
            dropItem = false;
            dropStack = false;
            swapHands = false;
            blockAction = BLOCK_NONE;
            targetX = 0;
            targetY = 0;
            targetZ = 0;
            projectileHit = NO_PROJECTILE_HIT;
            invAction = INV_NONE;
            invSrc = 0;
            invDst = 0;
            crystalHit = false;
            crystalX = 0;
            crystalY = 0;
            crystalZ = 0;
            elytraStart = false;
            authority = Authority.NONE;
            clicks = Clicks.NONE;
        }
    }

    public Input(boolean forward, boolean back, boolean left, boolean right, boolean jump,
                 boolean sprint, boolean sneak, boolean attack, boolean use, boolean usePress,
                 boolean offhandUse, boolean offhandUsePress, boolean meleeHit, boolean dropItem,
                 boolean dropStack, boolean swapHands, float yaw, float pitch, int heldSlot,
                 int blockAction, int targetX, int targetY, int targetZ, int projectileHit,
                 int invAction, int invSrc, int invDst, Authority authority, Clicks clicks) {
        this(forward, back, left, right, jump, sprint, sneak, attack, use, usePress, offhandUse,
                offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch, heldSlot,
                blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc, invDst,
                authority, clicks, false, 0, 0, 0, false, false);
    }

    public Input(boolean forward, boolean back, boolean left, boolean right, boolean jump,
                 boolean sprint, boolean sneak, boolean attack, boolean use, boolean usePress,
                 boolean offhandUse, boolean offhandUsePress, boolean meleeHit, boolean dropItem,
                 boolean dropStack, boolean swapHands, float yaw, float pitch, int heldSlot,
                 int blockAction, int targetX, int targetY, int targetZ, int projectileHit,
                 int invAction, int invSrc, int invDst, Authority authority) {
        this(forward, back, left, right, jump, sprint, sneak, attack, use, usePress, offhandUse,
                offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch, heldSlot,
                blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc, invDst,
                authority, Clicks.NONE);
    }

    public Input(boolean forward, boolean back, boolean left, boolean right, boolean jump,
                 boolean sprint, boolean sneak, boolean attack, boolean use,
                 float yaw, float pitch, int heldSlot) {
        this(forward, back, left, right, jump, sprint, sneak, attack, use, false, false, false,
                false, false, false, false, yaw, pitch, heldSlot,
                BLOCK_NONE, 0, 0, 0, NO_PROJECTILE_HIT, INV_NONE, 0, 0, Authority.NONE);
    }

    public static int clampCoord(int v) {
        return Math.max(-MAX_TARGET_COORD, Math.min(MAX_TARGET_COORD, v));
    }

    public static double clampWorldCoord(double v) {
        if (!Double.isFinite(v)) {
            return 0.0;
        }
        return Math.max(-MAX_WORLD_COORD, Math.min(MAX_WORLD_COORD, v));
    }

    public static float wrapYaw(float y) {
        if (!Float.isFinite(y) || Math.abs(y) > MAX_WRAPPABLE_YAW) {
            return 0f;
        }
        double v = y - 360.0 * Math.floor((y + 180.0) / 360.0);
        float w = (float) v;
        if (w >= 180f) {
            w -= 360f;
        } else if (w < -180f) {
            w += 360f;
        }
        return w == 0f ? 0f : w;
    }

    public static float clampPitch(float p) {
        float v = Float.isFinite(p) ? p : 0f;
        v = Math.max(-90f, Math.min(90f, v));
        return v == 0f ? 0f : v;
    }

    private static Authority canonicalAuthority(Authority a) {
        if (a == null || !a.present()) {
            return Authority.NONE;
        }
        double x = clampWorldCoord(a.x());
        double y = clampWorldCoord(a.y());
        double z = clampWorldCoord(a.z());
        if (x == a.x() && y == a.y() && z == a.z()) {
            return a;
        }
        return Authority.at(x, y, z, a.onGround());
    }

    public Input released() {
        return new Input(false, false, false, false, false, false, sneak, false, false, false, false,
                false, false, false, false, false, yaw, pitch, heldSlot,
                BLOCK_NONE, 0, 0, 0, NO_PROJECTILE_HIT, INV_NONE, 0, 0, Authority.NONE,
                Clicks.NONE, false, 0, 0, 0, false, synthetic);
    }

    public Input gestureOnly() {
        return new Input(false, false, false, false, false, false, sneak, false, use, false,
                offhandUse, false, false, false, false, false, yaw, pitch, heldSlot,
                BLOCK_NONE, 0, 0, 0, NO_PROJECTILE_HIT, INV_NONE, 0, 0, Authority.NONE,
                Clicks.NONE, false, 0, 0, 0, false, synthetic);
    }

    public Input heldOnly() {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, false,
                offhandUse, false, false, false, false, false, yaw, pitch, heldSlot,
                BLOCK_NONE, 0, 0, 0, NO_PROJECTILE_HIT, INV_NONE, 0, 0, Authority.NONE,
                Clicks.NONE, false, 0, 0, 0, false, synthetic);
    }

    public Input withMeleeHit(boolean hit) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, hit, dropItem, dropStack, swapHands, yaw, pitch, heldSlot,
                blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc, invDst,
                authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withBlockAction(int action, int tx, int ty, int tz) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                heldSlot, action, tx, ty, tz, projectileHit, invAction, invSrc, invDst,
                authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withCrystalHit(boolean hit, int cx, int cy, int cz) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                heldSlot, blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc,
                invDst, authority, clicks, hit, cx, cy, cz, elytraStart, synthetic);
    }

    public Input withDrop(boolean drop, boolean stack) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, drop, stack, swapHands, yaw, pitch, heldSlot,
                blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc, invDst,
                authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withProjectileHit(int arrowId) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                heldSlot, blockAction, targetX, targetY, targetZ, arrowId, invAction, invSrc, invDst,
                authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withOffhandUse(boolean offhand) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhand, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                heldSlot, blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc,
                invDst, authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withOffhandUsePress(boolean press) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, press, meleeHit, dropItem, dropStack, swapHands, yaw, pitch, heldSlot,
                blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc, invDst,
                authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withUsePress(boolean press) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, press,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                heldSlot, blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc,
                invDst, authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withSwapHands(boolean swap) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swap, yaw, pitch, heldSlot,
                blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc, invDst,
                authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withInvAction(int action, int src, int dst) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                heldSlot, blockAction, targetX, targetY, targetZ, projectileHit, action, src, dst,
                authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withHeldSlot(int slot) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                slot, blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc,
                invDst, authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withAuthority(Authority a) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                heldSlot, blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc,
                invDst, a, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withClicks(Clicks c) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                heldSlot, blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc,
                invDst, authority, c, crystalHit, crystalX, crystalY, crystalZ, elytraStart, synthetic);
    }

    public Input withElytraStart(boolean start) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                heldSlot, blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc,
                invDst, authority, clicks, crystalHit, crystalX, crystalY, crystalZ, start, synthetic);
    }

    public Input withSynthetic(boolean fill) {
        return new Input(forward, back, left, right, jump, sprint, sneak, attack, use, usePress,
                offhandUse, offhandUsePress, meleeHit, dropItem, dropStack, swapHands, yaw, pitch,
                heldSlot, blockAction, targetX, targetY, targetZ, projectileHit, invAction, invSrc,
                invDst, authority, clicks, crystalHit, crystalX, crystalY, crystalZ, elytraStart, fill);
    }

    public static final Input NONE = new Input(false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false, 0f, 0f, 0,
            BLOCK_NONE, 0, 0, 0, NO_PROJECTILE_HIT, INV_NONE, 0, 0, Authority.NONE);
}
