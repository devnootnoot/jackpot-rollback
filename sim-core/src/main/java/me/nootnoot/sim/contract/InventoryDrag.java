package me.nootnoot.sim.contract;

public final class InventoryDrag {

    public static final int NO_SLOT = Integer.MIN_VALUE;

    public static final int TYPE_EVEN = 0;

    public static final int TYPE_SINGLE = 1;

    public static final int TYPE_CLONE = 2;

    public record Collapse(boolean pickup, int slotId, int button) {
    }

    public static final Collapse NONE = new Collapse(false, NO_SLOT, 0);

    private int status;
    private int type;
    private int slots;
    private int firstSlot = NO_SLOT;

    public static int header(int mask) {
        return mask & 3;
    }

    public static int type(int mask) {
        return mask >> 2 & 3;
    }

    public static int mask(int header, int type) {
        return header & 3 | (type & 3) << 2;
    }

    public static boolean validType(int type, boolean cloneAllowed) {
        return type == TYPE_EVEN || type == TYPE_SINGLE || (type == TYPE_CLONE && cloneAllowed);
    }

    public boolean dragging() {
        return status != 0;
    }

    public void reset() {
        status = 0;
        slots = 0;
        firstSlot = NO_SLOT;
    }

    public Collapse offer(int slotId, int mask, boolean cursorEmpty, boolean placeable,
                          boolean cloneAllowed) {
        int expected = status;
        status = header(mask);
        if ((expected != 1 || status != 2) && expected != status) {
            reset();
            return NONE;
        }
        if (cursorEmpty) {
            reset();
            return NONE;
        }
        if (status == 0) {
            type = type(mask);
            if (!validType(type, cloneAllowed)) {
                reset();
                return NONE;
            }
            slots = 0;
            firstSlot = NO_SLOT;
            status = 1;
            return NONE;
        }
        if (status == 1) {
            if (placeable && slotId != firstSlot) {
                if (slots == 0) {
                    firstSlot = slotId;
                }
                slots++;
            }
            return NONE;
        }
        int only = slots == 1 ? firstSlot : NO_SLOT;
        int collapsedButton = type;
        reset();
        return only == NO_SLOT ? NONE : new Collapse(true, only, collapsedButton);
    }
}
