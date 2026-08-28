package me.nootnoot.sim.net;

import java.nio.ByteBuffer;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;

public final class InputCodec {
    public static final int BYTES = 67;

    private static final int FORWARD = 1;
    private static final int BACK = 1 << 1;
    private static final int LEFT = 1 << 2;
    private static final int RIGHT = 1 << 3;
    private static final int JUMP = 1 << 4;
    private static final int SPRINT = 1 << 5;
    private static final int SNEAK = 1 << 6;
    private static final int ATTACK = 1 << 7;
    private static final int USE = 1 << 8;
    private static final int USE_PRESS = 1 << 9;
    private static final int OFFHAND_USE = 1 << 10;
    private static final int MELEE_HIT = 1 << 11;
    private static final int DROP_ITEM = 1 << 12;
    private static final int DROP_STACK = 1 << 13;
    private static final int SWAP_HANDS = 1 << 14;
    private static final int AUTH_PRESENT = 1 << 15;
    private static final int AUTH_ON_GROUND = 1 << 16;
    private static final int OFFHAND_USE_PRESS = 1 << 17;

    private static final int ATTACK_CLICKS_SHIFT = 18;
    private static final int USE_CLICKS_SHIFT = 21;
    private static final int DROP_CLICKS_SHIFT = 24;
    private static final int INV_CLICKS_SHIFT = 27;

    private static final int CRYSTAL_HIT = 1 << 30;

    private static final int ELYTRA_START = 1 << 31;

    private static final int CLICK_MASK = 0x7;

    private static final int FLAG_MASK = 0xFFFFFFFF;

    private static final int HELD_SLOT_MASK = 0x0F;

    private static final int SWAP_CLICKS_SHIFT = 4;

    private static final int SYNTHETIC = 1 << 7;

    private InputCodec() {
    }

    public static void write(ByteBuffer b, Input in) {
        int m = 0;
        if (in.forward()) {
            m |= FORWARD;
        }
        if (in.back()) {
            m |= BACK;
        }
        if (in.left()) {
            m |= LEFT;
        }
        if (in.right()) {
            m |= RIGHT;
        }
        if (in.jump()) {
            m |= JUMP;
        }
        if (in.sprint()) {
            m |= SPRINT;
        }
        if (in.sneak()) {
            m |= SNEAK;
        }
        if (in.attack()) {
            m |= ATTACK;
        }
        if (in.use()) {
            m |= USE;
        }
        if (in.usePress()) {
            m |= USE_PRESS;
        }
        if (in.offhandUse()) {
            m |= OFFHAND_USE;
        }
        if (in.offhandUsePress()) {
            m |= OFFHAND_USE_PRESS;
        }
        if (in.meleeHit()) {
            m |= MELEE_HIT;
        }
        if (in.dropItem()) {
            m |= DROP_ITEM;
        }
        if (in.dropStack()) {
            m |= DROP_STACK;
        }
        if (in.swapHands()) {
            m |= SWAP_HANDS;
        }
        Authority auth = in.authority();
        if (auth.present()) {
            m |= AUTH_PRESENT;
        }
        if (auth.onGround()) {
            m |= AUTH_ON_GROUND;
        }
        if (in.crystalHit()) {
            m |= CRYSTAL_HIT;
        }
        if (in.elytraStart()) {
            m |= ELYTRA_START;
        }
        Clicks clicks = in.clicks();
        m |= (clicks.attack() & CLICK_MASK) << ATTACK_CLICKS_SHIFT;
        m |= (clicks.use() & CLICK_MASK) << USE_CLICKS_SHIFT;
        m |= (clicks.drop() & CLICK_MASK) << DROP_CLICKS_SHIFT;
        m |= (clicks.inv() & CLICK_MASK) << INV_CLICKS_SHIFT;
        b.putInt(m);
        b.putFloat(finite(in.yaw()));
        b.putFloat(finite(in.pitch()));
        b.put((byte) ((in.heldSlot() & HELD_SLOT_MASK)
                | ((clicks.swap() & CLICK_MASK) << SWAP_CLICKS_SHIFT)
                | (in.synthetic() ? SYNTHETIC : 0)));
        b.put((byte) in.blockAction());
        b.putInt(in.targetX());
        b.putInt(in.targetY());
        b.putInt(in.targetZ());
        b.putShort((short) in.projectileHit());
        b.put((byte) in.invAction());
        b.put((byte) in.invSrc());
        b.put((byte) in.invDst());
        b.putInt(in.crystalX());
        b.putInt(in.crystalY());
        b.putInt(in.crystalZ());
        b.putDouble(Input.clampWorldCoord(auth.x()));
        b.putDouble(Input.clampWorldCoord(auth.y()));
        b.putDouble(Input.clampWorldCoord(auth.z()));
    }

    public static Input read(ByteBuffer b) {
        int m = b.getInt() & FLAG_MASK;
        float yaw = Input.wrapYaw(finite(b.getFloat()));
        float pitch = Input.clampPitch(finite(b.getFloat()));
        int slotByte = b.get() & 0xFF;
        int heldSlot = Math.min(8, slotByte & HELD_SLOT_MASK);
        int swapClicks = (slotByte >>> SWAP_CLICKS_SHIFT) & CLICK_MASK;
        boolean synthetic = (slotByte & SYNTHETIC) != 0;
        int blockAction = b.get() & 0xFF;
        if (blockAction > Input.BLOCK_CLOSE_CONTAINER) {
            blockAction = Input.BLOCK_NONE;
        }
        int targetX = Input.clampCoord(b.getInt());
        int targetY = Input.clampCoord(b.getInt());
        int targetZ = Input.clampCoord(b.getInt());
        int projectileHit = b.getShort();
        int invAction = b.get() & 0xFF;
        if (invAction > Input.INV_MAX) {
            invAction = Input.INV_NONE;
        }
        int invSrc = b.get() & 0xFF;
        int invDst = b.get() & 0xFF;
        int crystalX = Input.clampCoord(b.getInt());
        int crystalY = Input.clampCoord(b.getInt());
        int crystalZ = Input.clampCoord(b.getInt());
        double authX = Input.clampWorldCoord(b.getDouble());
        double authY = Input.clampWorldCoord(b.getDouble());
        double authZ = Input.clampWorldCoord(b.getDouble());
        Authority authority = (m & AUTH_PRESENT) != 0
                ? new Authority(true, authX, authY, authZ, (m & AUTH_ON_GROUND) != 0)
                : Authority.NONE;
        Clicks clicks = new Clicks(
                (m >>> ATTACK_CLICKS_SHIFT) & CLICK_MASK,
                (m >>> USE_CLICKS_SHIFT) & CLICK_MASK,
                (m >>> DROP_CLICKS_SHIFT) & CLICK_MASK,
                (m >>> INV_CLICKS_SHIFT) & CLICK_MASK,
                swapClicks);
        return new Input(
                (m & FORWARD) != 0,
                (m & BACK) != 0,
                (m & LEFT) != 0,
                (m & RIGHT) != 0,
                (m & JUMP) != 0,
                (m & SPRINT) != 0,
                (m & SNEAK) != 0,
                (m & ATTACK) != 0,
                (m & USE) != 0,
                (m & USE_PRESS) != 0,
                (m & OFFHAND_USE) != 0,
                (m & OFFHAND_USE_PRESS) != 0,
                (m & MELEE_HIT) != 0,
                (m & DROP_ITEM) != 0,
                (m & DROP_STACK) != 0,
                (m & SWAP_HANDS) != 0,
                yaw,
                pitch,
                heldSlot,
                blockAction,
                targetX,
                targetY,
                targetZ,
                projectileHit,
                invAction,
                invSrc,
                invDst,
                authority,
                clicks,
                (m & CRYSTAL_HIT) != 0,
                crystalX,
                crystalY,
                crystalZ,
                (m & ELYTRA_START) != 0,
                synthetic);
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0f;
    }
}
