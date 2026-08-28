package me.nootnoot.sim.contract;

import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;

public final class InventoryIntents {

    public static final int CLICK_UNKNOWN = 0;

    public static final int CLICK_PICKUP = 1;

    public static final int CLICK_QUICK_MOVE = 2;

    public static final int CLICK_SWAP = 3;

    public static final int CLICK_THROW = 4;

    public static final int CLICK_PICKUP_ALL = 5;

    public static final int ADDR_NONE = -1;

    public static final int ADDR_OUTSIDE = -2;

    public static final int BUTTON_PRIMARY = 0;

    public static final int BUTTON_SECONDARY = 1;

    public static final int OFFHAND_SWAP_BUTTON = 40;

    public record Intent(int action, int src, int dst) {

        public boolean acts() {
            return action != Input.INV_NONE;
        }

        public boolean quickMoves() {
            return action == Input.INV_QUICK_MOVE;
        }

        public boolean throwsFromSlot() {
            return action == Input.INV_DROP_ONE || action == Input.INV_DROP_STACK;
        }

        public boolean dropsCursor() {
            return action == Input.INV_DROP_CURSOR_ONE || action == Input.INV_DROP_CURSOR_ALL;
        }

        public boolean wholeStack() {
            return action == Input.INV_DROP_STACK || action == Input.INV_DROP_CURSOR_ALL;
        }
    }

    public static final Intent NONE = new Intent(Input.INV_NONE, 0, 0);

    private InventoryIntents() {
    }

    public static int maxAddr() {
        return Input.ADDR_CELL_BASE + Container.CELLS - 1;
    }

    public static boolean legalAddr(int addr) {
        return addr >= 0 && addr <= maxAddr()
                && (Input.addrIsCell(addr) || addr < ItemDict.SLOTS);
    }

    public static int hotbarTarget(int button) {
        if (button == OFFHAND_SWAP_BUTTON) {
            return ItemDict.OFF_HAND;
        }
        return button >= 0 && button < ItemDict.HOTBAR ? button : ADDR_NONE;
    }

    public static Intent decide(int click, int addr, int button, boolean slotFilled,
                                boolean cursorEmpty) {
        if (addr == ADDR_OUTSIDE) {
            if (click != CLICK_PICKUP || cursorEmpty
                    || (button != BUTTON_PRIMARY && button != BUTTON_SECONDARY)) {
                return NONE;
            }
            return new Intent(button == BUTTON_PRIMARY ? Input.INV_DROP_CURSOR_ALL
                    : Input.INV_DROP_CURSOR_ONE, 0, 0);
        }
        if (!legalAddr(addr)) {
            return NONE;
        }
        switch (click) {
            case CLICK_THROW: {
                if (!slotFilled || !cursorEmpty) {
                    return NONE;
                }
                return new Intent(button == BUTTON_SECONDARY ? Input.INV_DROP_STACK
                        : Input.INV_DROP_ONE, addr, 0);
            }
            case CLICK_QUICK_MOVE:
                return slotFilled ? new Intent(Input.INV_QUICK_MOVE, addr, 0) : NONE;
            case CLICK_SWAP: {
                int hotbar = hotbarTarget(button);
                if (hotbar < 0 || hotbar == addr) {
                    return NONE;
                }
                return new Intent(Input.INV_SWAP_SLOT, addr, hotbar);
            }
            case CLICK_PICKUP_ALL:
                return cursorEmpty ? NONE : new Intent(Input.INV_PICKUP_ALL, addr, 0);
            case CLICK_PICKUP: {
                if (button == BUTTON_PRIMARY) {
                    return new Intent(Input.INV_PICKUP, addr, 0);
                }
                if (button == BUTTON_SECONDARY) {
                    return new Intent(Input.INV_PICKUP_HALF, addr, 0);
                }
                return NONE;
            }
            default:
                return NONE;
        }
    }

    public static Intent slotThrow(int slot, boolean whole) {
        if (!legalAddr(slot) || Input.addrIsCell(slot)) {
            return NONE;
        }
        return new Intent(whole ? Input.INV_DROP_STACK : Input.INV_DROP_ONE, slot, 0);
    }

    public static Intent addrThrow(int addr, boolean whole) {
        if (!legalAddr(addr)) {
            return NONE;
        }
        return new Intent(whole ? Input.INV_DROP_STACK : Input.INV_DROP_ONE, addr, 0);
    }

    public static Intent cursorResolve() {
        return new Intent(Input.INV_CURSOR_RESOLVE, 0, 0);
    }

    public static boolean isCursorResolve(int action) {
        return action == Input.INV_CURSOR_RESOLVE;
    }

    public static boolean fillsCursor(int action) {
        return action == Input.INV_PICKUP
                || action == Input.INV_PICKUP_HALF
                || action == Input.INV_PICKUP_ALL;
    }

    public static Intent chestEquip(int heldSlot) {
        if (heldSlot < 0 || heldSlot >= ItemDict.HOTBAR) {
            return NONE;
        }
        return new Intent(Input.INV_MOVE, heldSlot, ItemDict.ARMOR_CHEST);
    }

    public static boolean chestArmour(GameState state, PlayerState player, int heldSlot) {
        if (state == null || player == null || heldSlot < 0 || heldSlot >= ItemDict.HOTBAR) {
            return false;
        }
        if (player.prevUse) {
            return false;
        }
        int entry = Loadout.entryAt(player, heldSlot);
        if (entry == ItemDict.NONE) {
            return false;
        }
        return ItemDict.equipSlotToInventory(Loadout.dict(state).equipSlot(entry))
                == ItemDict.ARMOR_CHEST;
    }
}
