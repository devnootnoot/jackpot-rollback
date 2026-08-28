package me.nootnoot.sim.state;

import me.nootnoot.sim.SimProbe;

public final class Loadout {
    public static final int HAND_MAIN = 0;
    public static final int HAND_OFF = 1;

    public static final int TIER_NONE = 0;
    public static final int TIER_WOOD = 1;
    public static final int TIER_STONE = 2;
    public static final int TIER_IRON = 3;
    public static final int TIER_DIAMOND = 4;
    public static final int TIER_NETHERITE = 5;
    public static final int TIER_GOLD = 6;

    private static final float[] TIER_SPEED = {1.0f, 2.0f, 4.0f, 6.0f, 8.0f, 9.0f, 12.0f};

    private static final float BARE_HAND_SPEED = 1.0f;
    private static final float SWORD_SPEED = 1.5f;
    private static final float SHEARS_ON_WEB_SPEED = 15.0f;
    private static final float SUBMERGED_PENALTY = 0.2f;
    private static final float AIRBORNE_PENALTY = 0.2f;
    private static final float HARVEST_DIVISOR = 30.0f;
    private static final float NO_HARVEST_DIVISOR = 100.0f;

    private Loadout() {
    }

    public static ItemDict dict(GameState s) {
        if (s.dict == null) {
            s.dict = ItemDict.empty();
        }
        return s.dict;
    }

    public static BlockProps blockProps(GameState s) {
        if (s.blockProps == null) {
            s.blockProps = BlockProps.empty();
        }
        return s.blockProps;
    }

    public static int mainSlot(PlayerState p) {
        return clampSlot(p.heldSlot);
    }

    public static int clampSlot(int slot) {
        return Math.max(0, Math.min(ItemDict.HOTBAR - 1, slot));
    }

    public static boolean legalSlot(int slot) {
        return slot >= 0 && slot < ItemDict.SLOTS;
    }

    public static int entryAt(PlayerState p, int slot) {
        if (!legalSlot(slot)) {
            return ItemDict.NONE;
        }
        return p.slotCount[slot] > 0 ? p.slotEntry[slot] : ItemDict.NONE;
    }

    public static int countAt(PlayerState p, int slot) {
        return legalSlot(slot) ? p.slotCount[slot] : 0;
    }

    public static int handSlot(PlayerState p, int hand) {
        return hand == HAND_OFF ? ItemDict.OFF_HAND : mainSlot(p);
    }

    public static int held(PlayerState p) {
        return entryAt(p, mainSlot(p));
    }

    public static int offhand(PlayerState p) {
        return entryAt(p, ItemDict.OFF_HAND);
    }

    public static int handEntry(PlayerState p, int hand) {
        return entryAt(p, handSlot(p, hand));
    }

    public static float meleeDamage(GameState s, PlayerState p) {
        return dict(s).meleeDamage(held(p));
    }

    public static float meleeSpeed(GameState s, PlayerState p) {
        return dict(s).meleeSpeed(held(p));
    }

    public static double attackMaxRange(GameState s, PlayerState p) {
        return dict(s).attackMaxRange(held(p));
    }

    public static double attackHitboxMargin(GameState s, PlayerState p) {
        return dict(s).attackHitboxMargin(held(p));
    }

    public static int knockback(GameState s, PlayerState p) {
        return dict(s).knockback(held(p));
    }

    public static int sharpness(GameState s, PlayerState p) {
        return dict(s).sharpness(held(p));
    }

    public static int fireAspect(GameState s, PlayerState p) {
        return dict(s).fireAspect(held(p));
    }

    public static int breach(GameState s, PlayerState p) {
        return dict(s).breach(held(p));
    }

    public static int density(GameState s, PlayerState p) {
        return dict(s).density(held(p));
    }

    public static int windBurst(GameState s, PlayerState p) {
        return dict(s).windBurst(held(p));
    }

    public static boolean isMace(GameState s, PlayerState p) {
        return dict(s).isMace(held(p));
    }

    public static boolean isSword(GameState s, PlayerState p) {
        return dict(s).isSword(held(p));
    }

    public static boolean isAxe(GameState s, PlayerState p) {
        return dict(s).isAxe(held(p));
    }

    public static int useKind(GameState s, PlayerState p) {
        return dict(s).useKind(held(p));
    }

    public static int offhandUseKind(GameState s, PlayerState p) {
        return dict(s).useKind(offhand(p));
    }

    public static int heldItemId(GameState s, PlayerState p) {
        return dict(s).itemId(held(p));
    }

    public static int offhandItemId(GameState s, PlayerState p) {
        return dict(s).itemId(offhand(p));
    }

    public static int foodNutrition(GameState s, PlayerState p, int hand) {
        return dict(s).foodNutrition(handEntry(p, hand));
    }

    public static float foodSaturation(GameState s, PlayerState p, int hand) {
        return dict(s).foodSaturation(handEntry(p, hand));
    }

    public static int foodEatTicks(GameState s, PlayerState p, int hand) {
        return dict(s).foodEatTicks(handEntry(p, hand));
    }

    public static boolean alwaysEdible(GameState s, PlayerState p, int hand) {
        return dict(s).alwaysEdible(handEntry(p, hand));
    }

    public static int fireworkFlight(GameState s, PlayerState p) {
        return dict(s).fireworkFlight(held(p));
    }

    public static int effect(GameState s, PlayerState p, int hand, int i) {
        return dict(s).effect(handEntry(p, hand), i);
    }

    public static int bowPower(GameState s, PlayerState p) {
        return dict(s).bowPower(held(p));
    }

    public static int punch(GameState s, PlayerState p) {
        return dict(s).punch(held(p));
    }

    public static int flame(GameState s, PlayerState p) {
        return dict(s).flame(held(p));
    }

    public static boolean multishot(GameState s, PlayerState p) {
        return dict(s).multishot(held(p));
    }

    public static int crossbowLoadTicks(GameState s, PlayerState p) {
        return dict(s).crossbowLoadTicks(held(p));
    }

    public static boolean infinity(GameState s, PlayerState p) {
        int arrowSlot = activeArrowSlot(s, p);
        return dict(s).infinity(held(p)) && arrowSlot >= 0
                && dict(s).isArrowPlain(entryAt(p, arrowSlot));
    }

    public static boolean hasTotem(GameState s, PlayerState p) {
        return totemSlot(s, p) >= 0;
    }

    public static int totemSlot(GameState s, PlayerState p) {
        ItemDict d = dict(s);
        int main = mainSlot(p);
        if (d.isTotem(entryAt(p, main))) {
            return main;
        }
        if (d.isTotem(entryAt(p, ItemDict.OFF_HAND))) {
            return ItemDict.OFF_HAND;
        }
        return -1;
    }

    public static boolean hasElytra(GameState s, PlayerState p) {
        int e = entryAt(p, ItemDict.ARMOR_CHEST);
        if (!dict(s).isElytra(e)) {
            return false;
        }
        int max = dict(s).maxDamage(e);
        return max <= 0 || max - p.slotDamage[ItemDict.ARMOR_CHEST] > 1;
    }

    public static int activeArrowSlot(GameState s, PlayerState p) {
        ItemDict d = dict(s);
        if (d.isArrow(entryAt(p, ItemDict.OFF_HAND))) {
            return ItemDict.OFF_HAND;
        }
        for (int i = 0; i < ItemDict.MAIN_SLOTS; i++) {
            if (d.isArrow(entryAt(p, i))) {
                return i;
            }
        }
        return -1;
    }

    public static int arrowItemId(GameState s, PlayerState p) {
        int slot = activeArrowSlot(s, p);
        return slot < 0 ? 0 : dict(s).itemId(entryAt(p, slot));
    }

    public static int arrows(GameState s, PlayerState p) {
        ItemDict d = dict(s);
        int total = 0;
        for (int i = 0; i < ItemDict.SLOTS; i++) {
            if (d.isArrow(entryAt(p, i))) {
                total += p.slotCount[i];
            }
        }
        return total;
    }

    public static boolean invFull(GameState s, PlayerState p) {
        return firstFitSlot(s, p, ItemDict.NONE, 0) < 0;
    }

    private static int firstFitSlot(GameState s, PlayerState p, int entry, int damage) {
        ItemDict d = dict(s);
        if (entry != ItemDict.NONE) {
            int max = d.maxStack(entry);
            for (int i = 0; i < ItemDict.MAIN_SLOTS; i++) {
                if (p.slotEntry[i] == entry && p.slotCount[i] > 0 && p.slotCount[i] < max
                        && p.slotDamage[i] == damage) {
                    return i;
                }
            }
        }
        for (int i = 0; i < ItemDict.MAIN_SLOTS; i++) {
            if (p.slotEntry[i] == ItemDict.NONE || p.slotCount[i] <= 0) {
                return i;
            }
        }
        return -1;
    }

    public static boolean consume(PlayerState p, int slot, int n) {
        if (!legalSlot(slot) || n <= 0) {
            return false;
        }
        if (p.slotEntry[slot] == ItemDict.NONE || p.slotCount[slot] < n) {
            return false;
        }
        p.slotCount[slot] -= n;
        if (p.slotCount[slot] <= 0) {
            p.slotCount[slot] = 0;
            p.slotEntry[slot] = ItemDict.NONE;
            p.slotDamage[slot] = 0;
            p.slotCrossbowLoaded[slot] = false;
            p.slotCrossbowConsumed[slot] = false;
            p.slotCrossbowEntry[slot] = ItemDict.NONE;
        }
        p.consumeSeq += n;
        p.consumeSlot = slot;
        return true;
    }

    public static boolean addItem(GameState s, PlayerState p, int entry, int n) {
        return addItemRemainder(s, p, entry, n) == 0;
    }

    public static int addItemRemainder(GameState s, PlayerState p, int entry, int n) {
        return addItemRemainder(s, p, entry, n, 0);
    }

    public static int addItemRemainder(GameState s, PlayerState p, int entry, int n, int damage) {
        ItemDict d = dict(s);
        if (!d.valid(entry) || n <= 0) {
            return Math.max(0, n);
        }
        int dmg = clampDamage(d, entry, damage);
        int remaining = n;
        int max = d.maxStack(entry);
        while (remaining > 0) {
            int target = firstFitSlot(s, p, entry, dmg);
            if (target < 0) {
                if (remaining != n) {
                    p.invActionSeq++;
                }
                return remaining;
            }
            if (p.slotEntry[target] == ItemDict.NONE || p.slotCount[target] <= 0) {
                p.slotEntry[target] = entry;
                p.slotCount[target] = 0;
                p.slotDamage[target] = dmg;
                p.slotCrossbowLoaded[target] = false;
                p.slotCrossbowConsumed[target] = false;
                p.slotCrossbowEntry[target] = ItemDict.NONE;
            }
            int room = max - p.slotCount[target];
            int moved = Math.min(room, remaining);
            p.slotCount[target] += moved;
            remaining -= moved;
        }
        p.invActionSeq++;
        return 0;
    }

    public static boolean damageSlot(GameState s, PlayerState p, int slot, int n) {
        if (!legalSlot(slot) || n <= 0) {
            return false;
        }
        int e = entryAt(p, slot);
        int max = dict(s).maxDamage(e);
        if (max <= 0) {
            return false;
        }
        int worn = p.slotDamage[slot] + n;
        SimProbe.hit(SimProbe.DURABILITY_DAMAGED);
        if (worn < max) {
            p.slotDamage[slot] = worn;
            return false;
        }
        breakSlot(s, p, slot, e);
        return true;
    }

    private static void breakSlot(GameState s, PlayerState p, int slot, int entry) {
        SimProbe.hit(SimProbe.ITEM_BROKEN);
        p.slotCount[slot]--;
        if (p.slotCount[slot] <= 0) {
            clearSlot(p, slot);
        } else {
            p.slotDamage[slot] = 0;
            p.slotCrossbowLoaded[slot] = false;
            p.slotCrossbowConsumed[slot] = false;
            p.slotCrossbowEntry[slot] = ItemDict.NONE;
        }
        p.invActionSeq++;
        int idx = playerIndex(s, p);
        if (idx >= 0) {
            s.events.add(new CombatEvent(CombatEvent.ITEM_BREAK, idx, slot, false,
                    dict(s).itemId(entry)));
        }
        recomputeDerived(s, p);
    }

    private static int playerIndex(GameState s, PlayerState p) {
        for (int i = 0; i < s.players.length; i++) {
            if (s.players[i] == p) {
                return i;
            }
        }
        return -1;
    }

    private static int clampDamage(ItemDict d, int entry, int damage) {
        int max = d.maxDamage(entry);
        if (max <= 0 || damage <= 0) {
            return 0;
        }
        return Math.min(max - 1, damage);
    }

    public static boolean swapHands(GameState s, PlayerState p) {
        int main = mainSlot(p);
        if (!slotAccepts(s, entryAt(p, main), ItemDict.OFF_HAND)
                || !slotAccepts(s, entryAt(p, ItemDict.OFF_HAND), main)) {
            return false;
        }
        swapSlots(p, main, ItemDict.OFF_HAND);
        p.invActionSeq++;
        return true;
    }

    public static void swapSlots(PlayerState p, int a, int b) {
        if (!legalSlot(a) || !legalSlot(b) || a == b) {
            return;
        }
        int e = p.slotEntry[a];
        int c = p.slotCount[a];
        int d = p.slotDamage[a];
        boolean l = p.slotCrossbowLoaded[a];
        boolean u = p.slotCrossbowConsumed[a];
        int x = p.slotCrossbowEntry[a];
        p.slotEntry[a] = p.slotEntry[b];
        p.slotCount[a] = p.slotCount[b];
        p.slotDamage[a] = p.slotDamage[b];
        p.slotCrossbowLoaded[a] = p.slotCrossbowLoaded[b];
        p.slotCrossbowConsumed[a] = p.slotCrossbowConsumed[b];
        p.slotCrossbowEntry[a] = p.slotCrossbowEntry[b];
        p.slotEntry[b] = e;
        p.slotCount[b] = c;
        p.slotDamage[b] = d;
        p.slotCrossbowLoaded[b] = l;
        p.slotCrossbowConsumed[b] = u;
        p.slotCrossbowEntry[b] = x;
    }

    public static boolean slotAccepts(GameState s, int entry, int slot) {
        if (!legalSlot(slot)) {
            return false;
        }
        if (entry == ItemDict.NONE) {
            return true;
        }
        if (slot >= ItemDict.ARMOR_FEET && slot <= ItemDict.ARMOR_HEAD) {
            return ItemDict.equipSlotToInventory(dict(s).equipSlot(entry)) == slot;
        }
        return true;
    }

    public static boolean moveStack(GameState s, PlayerState p, int src, int dst) {
        if (!legalSlot(src) || !legalSlot(dst) || src == dst) {
            return false;
        }
        int srcEntry = entryAt(p, src);
        if (srcEntry == ItemDict.NONE) {
            return false;
        }
        if (!slotAccepts(s, srcEntry, dst)) {
            return false;
        }
        int dstEntry = entryAt(p, dst);
        if (dstEntry == ItemDict.NONE) {
            p.slotEntry[dst] = p.slotEntry[src];
            p.slotCount[dst] = p.slotCount[src];
            p.slotDamage[dst] = p.slotDamage[src];
            p.slotCrossbowLoaded[dst] = p.slotCrossbowLoaded[src];
            p.slotCrossbowConsumed[dst] = p.slotCrossbowConsumed[src];
            p.slotCrossbowEntry[dst] = p.slotCrossbowEntry[src];
            clearSlot(p, src);
            p.invActionSeq++;
            return true;
        }
        int max = dict(s).maxStack(dstEntry);
        if (dstEntry == srcEntry && p.slotDamage[dst] == p.slotDamage[src] && p.slotCount[dst] < max) {
            int moved = Math.min(max - p.slotCount[dst], p.slotCount[src]);
            p.slotCount[dst] += moved;
            p.slotCount[src] -= moved;
            if (p.slotCount[src] <= 0) {
                clearSlot(p, src);
            }
            p.invActionSeq++;
            return true;
        }
        if (!slotAccepts(s, dstEntry, src)) {
            return false;
        }
        swapSlots(p, src, dst);
        p.invActionSeq++;
        return true;
    }

    public static boolean moveContainer(GameState s, PlayerState p, Container c, int cell, int slot,
                                        boolean take) {
        if (c == null || cell < 0 || cell >= Container.CELLS || !legalSlot(slot)) {
            return false;
        }
        ItemDict d = dict(s);
        int cellEntry = c.count[cell] > 0 ? c.entry[cell] : ItemDict.NONE;
        int slotEntry = entryAt(p, slot);
        if (take) {
            if (cellEntry == ItemDict.NONE || !slotAccepts(s, cellEntry, slot)) {
                return false;
            }
            if (slotEntry == ItemDict.NONE) {
                p.slotEntry[slot] = cellEntry;
                p.slotCount[slot] = c.count[cell];
                p.slotDamage[slot] = c.damage[cell];
                p.slotCrossbowLoaded[slot] = false;
                p.slotCrossbowConsumed[slot] = false;
                p.slotCrossbowEntry[slot] = ItemDict.NONE;
                clearCell(c, cell);
            } else if (slotEntry == cellEntry && p.slotDamage[slot] == c.damage[cell]
                    && p.slotCount[slot] < d.maxStack(slotEntry)) {
                int moved = Math.min(d.maxStack(slotEntry) - p.slotCount[slot], c.count[cell]);
                p.slotCount[slot] += moved;
                c.count[cell] -= moved;
                if (c.count[cell] <= 0) {
                    clearCell(c, cell);
                }
            } else {
                if (!slotAccepts(s, cellEntry, slot)) {
                    return false;
                }
                swapCell(p, slot, c, cell);
            }
            p.invActionSeq++;
            return true;
        }
        if (slotEntry == ItemDict.NONE) {
            return false;
        }
        if (cellEntry == ItemDict.NONE) {
            c.entry[cell] = slotEntry;
            c.count[cell] = p.slotCount[slot];
            c.damage[cell] = p.slotDamage[slot];
            clearSlot(p, slot);
        } else if (cellEntry == slotEntry && c.damage[cell] == p.slotDamage[slot]
                && c.count[cell] < d.maxStack(cellEntry)) {
            int moved = Math.min(d.maxStack(cellEntry) - c.count[cell], p.slotCount[slot]);
            c.count[cell] += moved;
            p.slotCount[slot] -= moved;
            if (p.slotCount[slot] <= 0) {
                clearSlot(p, slot);
            }
        } else {
            if (!slotAccepts(s, cellEntry, slot)) {
                return false;
            }
            swapCell(p, slot, c, cell);
        }
        p.invActionSeq++;
        return true;
    }

    private static void clearSlot(PlayerState p, int slot) {
        p.slotEntry[slot] = ItemDict.NONE;
        p.slotCount[slot] = 0;
        p.slotDamage[slot] = 0;
        p.slotCrossbowLoaded[slot] = false;
        p.slotCrossbowConsumed[slot] = false;
        p.slotCrossbowEntry[slot] = ItemDict.NONE;
    }

    public static boolean consumeCell(Container c, int cell, int n) {
        if (c == null || cell < 0 || cell >= Container.CELLS || n <= 0) {
            return false;
        }
        if (c.entry[cell] == ItemDict.NONE || c.count[cell] < n) {
            return false;
        }
        c.count[cell] -= n;
        if (c.count[cell] <= 0) {
            clearCell(c, cell);
        }
        return true;
    }

    private static void clearCell(Container c, int cell) {
        c.entry[cell] = ItemDict.NONE;
        c.count[cell] = 0;
        c.damage[cell] = 0;
    }

    private static void swapCell(PlayerState p, int slot, Container c, int cell) {
        int e = p.slotEntry[slot];
        int n = p.slotCount[slot];
        int dmg = p.slotDamage[slot];
        p.slotEntry[slot] = c.entry[cell];
        p.slotCount[slot] = c.count[cell];
        p.slotDamage[slot] = c.damage[cell];
        p.slotCrossbowLoaded[slot] = false;
        p.slotCrossbowConsumed[slot] = false;
        p.slotCrossbowEntry[slot] = ItemDict.NONE;
        c.entry[cell] = e;
        c.count[cell] = n;
        c.damage[cell] = dmg;
    }

    public static int cursorEntry(PlayerState p) {
        return p.cursorCount > 0 ? p.cursorEntry : ItemDict.NONE;
    }

    public static int cursorCount(PlayerState p) {
        return cursorEntry(p) == ItemDict.NONE ? 0 : p.cursorCount;
    }

    public static int cursorDamage(PlayerState p) {
        return cursorEntry(p) == ItemDict.NONE ? 0 : p.cursorDamage;
    }

    public static void clearCursor(PlayerState p) {
        p.cursorEntry = ItemDict.NONE;
        p.cursorCount = 0;
        p.cursorDamage = 0;
        p.cursorCrossbowLoaded = false;
        p.cursorCrossbowConsumed = false;
        p.cursorCrossbowEntry = ItemDict.NONE;
    }

    public static boolean consumeCursor(PlayerState p, int n) {
        if (n <= 0 || cursorEntry(p) == ItemDict.NONE || p.cursorCount < n) {
            return false;
        }
        p.cursorCount -= n;
        if (p.cursorCount <= 0) {
            clearCursor(p);
        }
        p.invActionSeq++;
        return true;
    }

    public static boolean clickSlot(GameState s, PlayerState p, int slot, boolean primary) {
        if (!legalSlot(slot)) {
            return false;
        }
        StackView target = new StackView();
        StackView cursor = new StackView();
        readSlot(p, slot, target);
        readCursor(p, cursor);
        if (!clickStack(s, target, cursor, primary, slot)) {
            return false;
        }
        writeSlot(p, slot, target);
        writeCursor(p, cursor);
        p.invActionSeq++;
        return true;
    }

    public static boolean clickCell(GameState s, PlayerState p, Container c, int cell,
                                    boolean primary) {
        if (c == null || cell < 0 || cell >= Container.CELLS) {
            return false;
        }
        StackView target = new StackView();
        StackView cursor = new StackView();
        readCell(c, cell, target);
        readCursor(p, cursor);
        if (!clickStack(s, target, cursor, primary, NO_ACCEPT_GATE)) {
            return false;
        }
        writeCell(c, cell, target);
        writeCursor(p, cursor);
        p.invActionSeq++;
        return true;
    }

    public static boolean swapWithHotbar(GameState s, PlayerState p, int slot, int hotbar) {
        if (!legalSlot(slot) || !legalHotbarButton(hotbar) || slot == hotbar) {
            return false;
        }
        StackView target = new StackView();
        StackView source = new StackView();
        readSlot(p, slot, target);
        readSlot(p, hotbar, source);
        if (!swapStack(s, target, source, slot)) {
            return false;
        }
        writeSlot(p, slot, target);
        writeSlot(p, hotbar, source);
        p.invActionSeq++;
        return true;
    }

    public static boolean swapCellWithHotbar(GameState s, PlayerState p, Container c, int cell,
                                             int hotbar) {
        if (c == null || cell < 0 || cell >= Container.CELLS || !legalHotbarButton(hotbar)) {
            return false;
        }
        StackView target = new StackView();
        StackView source = new StackView();
        readCell(c, cell, target);
        readSlot(p, hotbar, source);
        if (!swapStack(s, target, source, NO_ACCEPT_GATE)) {
            return false;
        }
        writeCell(c, cell, target);
        writeSlot(p, hotbar, source);
        p.invActionSeq++;
        return true;
    }

    public static boolean pickupAllToCursor(GameState s, PlayerState p, Container c) {
        StackView cursor = new StackView();
        readCursor(p, cursor);
        if (cursor.empty()) {
            return false;
        }
        int max = dict(s).maxStack(cursor.entry);
        int[] order = c == null ? PICKUP_ALL_IN_INVENTORY : PICKUP_ALL_WITH_CONTAINER;
        boolean moved = false;
        for (int pass = 0; pass < 2 && cursor.count < max; pass++) {
            boolean skipFull = pass == 0;
            for (int cell = 0; c != null && cell < Container.CELLS && cursor.count < max; cell++) {
                moved |= gatherCell(c, cell, cursor, max, skipFull);
            }
            for (int i = 0; i < order.length && cursor.count < max; i++) {
                moved |= gatherSlot(p, order[i], cursor, max, skipFull);
            }
        }
        if (!moved) {
            return false;
        }
        writeCursor(p, cursor);
        p.invActionSeq++;
        return true;
    }

    public static int placeCursorBack(GameState s, PlayerState p) {
        StackView cursor = new StackView();
        readCursor(p, cursor);
        if (cursor.empty()) {
            return 0;
        }
        StackView target = new StackView();
        boolean moved = false;
        while (!cursor.empty()) {
            int slot = firstFitSlot(s, p, cursor.entry, cursor.damage);
            if (slot < 0) {
                break;
            }
            readSlot(p, slot, target);
            if (!insertInto(s, target, cursor, cursor.count, slot)) {
                break;
            }
            writeSlot(p, slot, target);
            moved = true;
        }
        if (moved) {
            writeCursor(p, cursor);
            p.invActionSeq++;
        }
        return cursor.count;
    }

    public static boolean quickMove(GameState s, PlayerState p, Container c, int addr) {
        if (Input.addrIsCell(addr)) {
            int cell = Input.addrIndex(addr);
            if (c == null || cell < 0 || cell >= Container.CELLS || c.count[cell] <= 0
                    || c.entry[cell] == ItemDict.NONE) {
                return false;
            }
            StackView stack = new StackView();
            readCell(c, cell, stack);
            if (!fillSlots(s, p, stack, PLAYER_RECEIVE_ORDER)) {
                return false;
            }
            writeCell(c, cell, stack);
            p.invActionSeq++;
            return true;
        }
        if (!legalSlot(addr) || entryAt(p, addr) == ItemDict.NONE) {
            return false;
        }
        StackView stack = new StackView();
        readSlot(p, addr, stack);
        boolean moved = c != null
                ? fillCells(s, c, stack)
                : fillSlots(s, p, stack, quickMoveOrder(s, p, addr, stack.entry));
        if (!moved) {
            return false;
        }
        writeSlot(p, addr, stack);
        p.invActionSeq++;
        return true;
    }

    private static boolean emptySlot(PlayerState p, int slot) {
        return p.slotEntry[slot] == ItemDict.NONE || p.slotCount[slot] <= 0;
    }

    private static boolean fillSlots(GameState s, PlayerState p, StackView stack, int[] order) {
        int max = dict(s).maxStack(stack.entry);
        boolean moved = false;
        if (max > 1) {
            for (int i = 0; i < order.length && !stack.empty(); i++) {
                int slot = order[i];
                if (p.slotCount[slot] <= 0 || p.slotEntry[slot] != stack.entry
                        || p.slotDamage[slot] != stack.damage || p.slotCount[slot] >= max) {
                    continue;
                }
                int n = Math.min(max - p.slotCount[slot], stack.count);
                p.slotCount[slot] += n;
                stack.count -= n;
                if (stack.count <= 0) {
                    stack.clear();
                }
                moved = true;
            }
        }
        for (int i = 0; i < order.length && !stack.empty(); i++) {
            int slot = order[i];
            if (!emptySlot(p, slot) || !slotAccepts(s, stack.entry, slot)) {
                continue;
            }
            int n = Math.min(max, stack.count);
            p.slotEntry[slot] = stack.entry;
            p.slotCount[slot] = n;
            p.slotDamage[slot] = stack.damage;
            p.slotCrossbowLoaded[slot] = stack.crossbowLoaded;
            p.slotCrossbowConsumed[slot] = stack.crossbowConsumed;
            p.slotCrossbowEntry[slot] = stack.crossbowEntry;
            stack.count -= n;
            if (stack.count <= 0) {
                stack.clear();
            }
            moved = true;
        }
        return moved;
    }

    private static boolean fillCells(GameState s, Container c, StackView stack) {
        int max = dict(s).maxStack(stack.entry);
        boolean moved = false;
        if (max > 1) {
            for (int cell = 0; cell < Container.CELLS && !stack.empty(); cell++) {
                if (c.count[cell] <= 0 || c.entry[cell] != stack.entry
                        || c.damage[cell] != stack.damage || c.count[cell] >= max) {
                    continue;
                }
                int n = Math.min(max - c.count[cell], stack.count);
                c.count[cell] += n;
                stack.count -= n;
                if (stack.count <= 0) {
                    stack.clear();
                }
                moved = true;
            }
        }
        for (int cell = 0; cell < Container.CELLS && !stack.empty(); cell++) {
            if (c.count[cell] > 0 && c.entry[cell] != ItemDict.NONE) {
                continue;
            }
            int n = Math.min(max, stack.count);
            c.entry[cell] = stack.entry;
            c.count[cell] = n;
            c.damage[cell] = stack.damage;
            stack.count -= n;
            if (stack.count <= 0) {
                stack.clear();
            }
            moved = true;
        }
        return moved;
    }

    private static int[] quickMoveOrder(GameState s, PlayerState p, int src, int entry) {
        boolean worn = src >= ItemDict.ARMOR_FEET && src <= ItemDict.ARMOR_HEAD;
        if (!worn) {
            int equip = dict(s).equipSlot(entry);
            int armour = ItemDict.equipSlotToInventory(equip);
            if (armour >= 0 && emptySlot(p, armour)) {
                return new int[]{armour};
            }
            if (equip == ItemDict.EQUIP_OFF_HAND && src != ItemDict.OFF_HAND
                    && emptySlot(p, ItemDict.OFF_HAND)) {
                return new int[]{ItemDict.OFF_HAND};
            }
        }
        if (src < ItemDict.HOTBAR) {
            return STORAGE_ORDER;
        }
        if (src < ItemDict.MAIN_SLOTS) {
            return HOTBAR_ORDER;
        }
        return STORAGE_THEN_HOTBAR_ORDER;
    }

    private static final int[] STORAGE_ORDER = ascending(ItemDict.HOTBAR, ItemDict.MAIN_SLOTS);

    private static final int[] HOTBAR_ORDER = ascending(0, ItemDict.HOTBAR);

    private static final int[] STORAGE_THEN_HOTBAR_ORDER = concat(STORAGE_ORDER, HOTBAR_ORDER);

    private static final int[] PLAYER_RECEIVE_ORDER =
            concat(descending(0, ItemDict.HOTBAR), descending(ItemDict.HOTBAR, ItemDict.MAIN_SLOTS));

    private static final int[] PICKUP_ALL_WITH_CONTAINER = STORAGE_THEN_HOTBAR_ORDER;

    private static final int[] PICKUP_ALL_IN_INVENTORY = concat(
            new int[]{ItemDict.ARMOR_HEAD, ItemDict.ARMOR_CHEST, ItemDict.ARMOR_LEGS,
                    ItemDict.ARMOR_FEET},
            concat(STORAGE_THEN_HOTBAR_ORDER, new int[]{ItemDict.OFF_HAND}));

    private static int[] ascending(int from, int to) {
        int[] out = new int[to - from];
        for (int i = 0; i < out.length; i++) {
            out[i] = from + i;
        }
        return out;
    }

    private static int[] descending(int from, int to) {
        int[] out = new int[to - from];
        for (int i = 0; i < out.length; i++) {
            out[i] = to - 1 - i;
        }
        return out;
    }

    private static int[] concat(int[] a, int[] b) {
        int[] out = new int[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static final int NO_ACCEPT_GATE = -1;

    private static boolean legalHotbarButton(int button) {
        return (button >= 0 && button < ItemDict.HOTBAR) || button == ItemDict.OFF_HAND;
    }

    private static boolean gatherSlot(PlayerState p, int slot, StackView cursor, int max,
                                      boolean skipFull) {
        if (p.slotCount[slot] <= 0 || p.slotEntry[slot] != cursor.entry
                || p.slotDamage[slot] != cursor.damage) {
            return false;
        }
        if (skipFull && p.slotCount[slot] >= max) {
            return false;
        }
        int moved = Math.min(max - cursor.count, p.slotCount[slot]);
        if (moved <= 0) {
            return false;
        }
        cursor.count += moved;
        p.slotCount[slot] -= moved;
        if (p.slotCount[slot] <= 0) {
            clearSlot(p, slot);
        }
        return true;
    }

    private static boolean gatherCell(Container c, int cell, StackView cursor, int max,
                                      boolean skipFull) {
        if (c.count[cell] <= 0 || c.entry[cell] != cursor.entry
                || c.damage[cell] != cursor.damage) {
            return false;
        }
        if (skipFull && c.count[cell] >= max) {
            return false;
        }
        int moved = Math.min(max - cursor.count, c.count[cell]);
        if (moved <= 0) {
            return false;
        }
        cursor.count += moved;
        c.count[cell] -= moved;
        if (c.count[cell] <= 0) {
            clearCell(c, cell);
        }
        return true;
    }

    private static boolean accepts(GameState s, int entry, int acceptSlot) {
        return acceptSlot == NO_ACCEPT_GATE || slotAccepts(s, entry, acceptSlot);
    }

    private static boolean sameStack(StackView a, StackView b) {
        return a.entry == b.entry && a.damage == b.damage;
    }

    private static boolean insertInto(GameState s, StackView target, StackView cursor, int amount,
                                      int acceptSlot) {
        if (cursor.empty() || !accepts(s, cursor.entry, acceptSlot)) {
            return false;
        }
        int max = dict(s).maxStack(cursor.entry);
        int room;
        if (target.empty()) {
            room = max;
        } else if (sameStack(target, cursor)) {
            room = max - target.count;
        } else {
            room = 0;
        }
        int moved = Math.min(Math.min(amount, cursor.count), room);
        if (moved <= 0) {
            return false;
        }
        if (target.empty()) {
            int carried = cursor.count;
            target.copyFrom(cursor);
            target.count = moved;
            cursor.count = carried - moved;
        } else {
            target.count += moved;
            cursor.count -= moved;
        }
        if (cursor.count <= 0) {
            cursor.clear();
        }
        return true;
    }

    private static boolean clickStack(GameState s, StackView target, StackView cursor,
                                      boolean primary, int acceptSlot) {
        if (target.empty()) {
            return !cursor.empty()
                    && insertInto(s, target, cursor, primary ? cursor.count : 1, acceptSlot);
        }
        if (cursor.empty()) {
            int amount = primary ? target.count : (target.count + 1) / 2;
            if (amount <= 0) {
                return false;
            }
            int held = target.count;
            cursor.copyFrom(target);
            cursor.count = amount;
            target.count = held - amount;
            if (target.count <= 0) {
                target.clear();
            }
            return true;
        }
        if (accepts(s, cursor.entry, acceptSlot)) {
            if (sameStack(target, cursor)) {
                return insertInto(s, target, cursor, primary ? cursor.count : 1, acceptSlot);
            }
            if (cursor.count > dict(s).maxStack(cursor.entry)) {
                return false;
            }
            StackView held = new StackView();
            held.copyFrom(target);
            target.copyFrom(cursor);
            cursor.copyFrom(held);
            return true;
        }
        if (!sameStack(target, cursor)) {
            return false;
        }
        int moved = Math.min(dict(s).maxStack(cursor.entry) - cursor.count, target.count);
        if (moved <= 0) {
            return false;
        }
        cursor.count += moved;
        target.count -= moved;
        if (target.count <= 0) {
            target.clear();
        }
        return true;
    }

    private static boolean swapStack(GameState s, StackView target, StackView source,
                                     int acceptSlot) {
        if (target.empty() && source.empty()) {
            return false;
        }
        if (source.empty()) {
            source.copyFrom(target);
            target.clear();
            return true;
        }
        if (!accepts(s, source.entry, acceptSlot)) {
            return false;
        }
        StackView held = new StackView();
        held.copyFrom(target);
        target.copyFrom(source);
        source.copyFrom(held);
        return true;
    }

    private static void readSlot(PlayerState p, int slot, StackView out) {
        boolean filled = p.slotCount[slot] > 0 && p.slotEntry[slot] != ItemDict.NONE;
        out.entry = filled ? p.slotEntry[slot] : ItemDict.NONE;
        out.count = filled ? p.slotCount[slot] : 0;
        out.damage = filled ? p.slotDamage[slot] : 0;
        out.crossbowLoaded = filled && p.slotCrossbowLoaded[slot];
        out.crossbowConsumed = filled && p.slotCrossbowConsumed[slot];
        out.crossbowEntry = filled ? p.slotCrossbowEntry[slot] : ItemDict.NONE;
    }

    private static void writeSlot(PlayerState p, int slot, StackView in) {
        if (in.empty()) {
            clearSlot(p, slot);
            return;
        }
        p.slotEntry[slot] = in.entry;
        p.slotCount[slot] = in.count;
        p.slotDamage[slot] = in.damage;
        p.slotCrossbowLoaded[slot] = in.crossbowLoaded;
        p.slotCrossbowConsumed[slot] = in.crossbowConsumed;
        p.slotCrossbowEntry[slot] = in.crossbowEntry;
    }

    private static void readCell(Container c, int cell, StackView out) {
        boolean filled = c.count[cell] > 0 && c.entry[cell] != ItemDict.NONE;
        out.entry = filled ? c.entry[cell] : ItemDict.NONE;
        out.count = filled ? c.count[cell] : 0;
        out.damage = filled ? c.damage[cell] : 0;
        out.crossbowLoaded = false;
        out.crossbowConsumed = false;
        out.crossbowEntry = ItemDict.NONE;
    }

    private static void writeCell(Container c, int cell, StackView in) {
        if (in.empty()) {
            clearCell(c, cell);
            return;
        }
        c.entry[cell] = in.entry;
        c.count[cell] = in.count;
        c.damage[cell] = in.damage;
    }

    private static void readCursor(PlayerState p, StackView out) {
        boolean filled = p.cursorCount > 0 && p.cursorEntry != ItemDict.NONE;
        out.entry = filled ? p.cursorEntry : ItemDict.NONE;
        out.count = filled ? p.cursorCount : 0;
        out.damage = filled ? p.cursorDamage : 0;
        out.crossbowLoaded = filled && p.cursorCrossbowLoaded;
        out.crossbowConsumed = filled && p.cursorCrossbowConsumed;
        out.crossbowEntry = filled ? p.cursorCrossbowEntry : ItemDict.NONE;
    }

    private static void writeCursor(PlayerState p, StackView in) {
        if (in.empty()) {
            clearCursor(p);
            return;
        }
        p.cursorEntry = in.entry;
        p.cursorCount = in.count;
        p.cursorDamage = in.damage;
        p.cursorCrossbowLoaded = in.crossbowLoaded;
        p.cursorCrossbowConsumed = in.crossbowConsumed;
        p.cursorCrossbowEntry = in.crossbowEntry;
    }

    private static final class StackView {
        private int entry = ItemDict.NONE;
        private int count;
        private int damage;
        private boolean crossbowLoaded;
        private boolean crossbowConsumed;
        private int crossbowEntry = ItemDict.NONE;

        private boolean empty() {
            return entry == ItemDict.NONE || count <= 0;
        }

        private void clear() {
            entry = ItemDict.NONE;
            count = 0;
            damage = 0;
            crossbowLoaded = false;
            crossbowConsumed = false;
            crossbowEntry = ItemDict.NONE;
        }

        private void copyFrom(StackView o) {
            entry = o.entry;
            count = o.count;
            damage = o.damage;
            crossbowLoaded = o.crossbowLoaded;
            crossbowConsumed = o.crossbowConsumed;
            crossbowEntry = o.crossbowEntry;
        }
    }

    public static int retype(GameState s, PlayerState p, int slot, int entry) {
        ItemDict d = dict(s);
        if (!legalSlot(slot) || !d.valid(entry)) {
            return 0;
        }
        if (p.slotEntry[slot] == ItemDict.NONE || p.slotCount[slot] <= 0) {
            return 0;
        }
        if (p.slotCount[slot] == 1) {
            p.slotEntry[slot] = entry;
            p.slotDamage[slot] = 0;
            p.slotCrossbowLoaded[slot] = false;
            p.slotCrossbowConsumed[slot] = false;
            p.slotCrossbowEntry[slot] = ItemDict.NONE;
            p.invActionSeq++;
            return 0;
        }
        p.slotCount[slot]--;
        p.invActionSeq++;
        return addItemRemainder(s, p, entry, 1);
    }

    public static void recomputeDerived(GameState s, PlayerState p) {
        ItemDict d = dict(s);
        p.heldSlot = clampSlot(p.heldSlot);
        int main = entryAt(p, p.heldSlot);
        p.heldUseKind = d.useKind(main);
        p.heldItemId = d.itemId(main);
        p.offhandItemId = d.itemId(entryAt(p, ItemDict.OFF_HAND));
        p.attackDamage = d.meleeDamage(main);
        p.attackSpeed = d.meleeSpeed(main);
        p.knockbackLevel = d.knockback(main);
        p.hasTotem = hasTotem(s, p);
        p.hasElytra = hasElytra(s, p);
        p.arrows = arrows(s, p);

        float armor = 0f;
        float toughness = 0f;
        float kbResistance = 0f;
        int protection = 0;
        int blast = 0;
        int projectile = 0;
        int fire = 0;
        int feather = 0;
        for (int slot = ItemDict.ARMOR_FEET; slot <= ItemDict.ARMOR_HEAD; slot++) {
            int e = entryAt(p, slot);
            armor += d.armorPoints(e);
            toughness += d.armorToughness(e);
            kbResistance += d.armorKbResistance(e);
            protection += d.armorProtection(e);
            blast += d.armorBlastProtection(e);
            projectile += d.armorProjectileProtection(e);
            fire += d.armorFireProtection(e);
            feather += d.armorFeatherFalling(e);
        }
        p.armor = armor;
        p.armorToughness = toughness;
        p.kbResistance = Math.min(ItemDict.MAX_KB_RESISTANCE_TOTAL, kbResistance);
        p.protection = Math.min(ItemDict.MAX_EPF_LEVEL, protection);
        p.blastProtection = Math.min(ItemDict.MAX_EPF_LEVEL, blast);
        p.projectileProtection = Math.min(ItemDict.MAX_EPF_LEVEL, projectile);
        p.fireProtection = Math.min(ItemDict.MAX_EPF_LEVEL, fire);
        p.featherFalling = Math.min(ItemDict.MAX_FEATHER_FALLING, feather);
        p.armorFeetId = d.itemId(entryAt(p, ItemDict.ARMOR_FEET));
        p.armorLegsId = d.itemId(entryAt(p, ItemDict.ARMOR_LEGS));
        p.armorChestId = d.itemId(entryAt(p, ItemDict.ARMOR_CHEST));
        p.armorHeadId = d.itemId(entryAt(p, ItemDict.ARMOR_HEAD));
    }

    public static float minePerTick(GameState s, PlayerState p, int blockItemId) {
        return minePerTick(s, p, mainSlot(p), blockItemId);
    }

    public static float minePerTick(GameState s, PlayerState p, int toolSlot, int blockItemId) {
        BlockProps props = blockProps(s);
        float hardness = props.hardness(blockItemId);
        if (hardness < 0f) {
            return 0f;
        }
        if (hardness == 0f) {
            return 1f;
        }
        ItemDict d = dict(s);
        int tool = entryAt(p, toolSlot);
        int blockToolClass = props.toolClass(blockItemId);
        float speed = toolSpeed(d, tool, blockToolClass, blockItemId, s);
        int efficiency = d.efficiency(tool);
        if (speed > 1.0f && efficiency > 0) {
            speed += efficiency * efficiency + 1;
        }
        if (p.submergedEye && !d.aquaAffinity(entryAt(p, ItemDict.ARMOR_HEAD))) {
            speed *= SUBMERGED_PENALTY;
        }
        if (!p.onGround) {
            speed *= AIRBORNE_PENALTY;
        }
        float divisor = canHarvest(d, props, tool, blockItemId) ? HARVEST_DIVISOR : NO_HARVEST_DIVISOR;
        float delta = speed / hardness / divisor;
        return delta <= 0f ? 0f : Math.min(1f, delta);
    }

    private static float toolSpeed(ItemDict d, int tool, int blockToolClass, int blockItemId,
                                   GameState s) {
        if (s.cobwebItemId != 0 && blockItemId == s.cobwebItemId) {
            return d.isShears(tool) || d.isSword(tool) ? SHEARS_ON_WEB_SPEED : BARE_HAND_SPEED;
        }
        int toolClass = d.toolClass(tool);
        if (blockToolClass == ItemDict.TOOL_NONE || toolClass != blockToolClass) {
            return d.isSword(tool) ? SWORD_SPEED : BARE_HAND_SPEED;
        }
        int tier = d.tier(tool);
        return TIER_SPEED[Math.max(0, Math.min(TIER_SPEED.length - 1, tier))];
    }

    public static boolean canHarvest(GameState s, PlayerState p, int toolSlot, int blockItemId) {
        return canHarvest(dict(s), blockProps(s), entryAt(p, toolSlot), blockItemId);
    }

    public static boolean dropsBlock(GameState s, PlayerState p, int toolSlot, int blockItemId) {
        return !blockProps(s).requiresTool(blockItemId) || canHarvest(s, p, toolSlot, blockItemId);
    }

    private static boolean canHarvest(ItemDict d, BlockProps props, int tool, int blockItemId) {
        int required = props.harvestTier(blockItemId);
        if (required < 0) {
            return true;
        }
        if (props.toolClass(blockItemId) != d.toolClass(tool)) {
            return false;
        }
        return harvestRank(d.tier(tool)) >= required;
    }

    private static int harvestRank(int tier) {
        return tier == TIER_GOLD ? TIER_WOOD : tier;
    }
}
