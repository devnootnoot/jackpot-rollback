package me.nootnoot.sim.host;

import java.util.Arrays;
import me.nootnoot.sim.contract.InventoryIntents;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;

public final class InventoryPaintPlan {

    public static final int NO_FRAME = Integer.MIN_VALUE;

    public static final int PLAYER_CURSOR = ItemDict.SLOTS;

    public static final int PLAYER_CELLS = ItemDict.SLOTS + 1;

    public static final int CONTAINER_CELLS = Container.CELLS;

    private static final int EMPTY = ItemDict.NONE;

    private final int cells;

    private final int[] shownEntry;

    private final int[] shownCount;

    private final int[] shownDamage;

    private final boolean[] shownBow;

    private final int[] ownedUntil;

    private final boolean[] repaint;

    private final boolean[] predicted;

    private boolean seeded;

    private PlayerState confirmedView;

    private PlayerState headView;

    private InventoryPaintPlan(int cells) {
        this.cells = cells;
        this.shownEntry = new int[cells];
        this.shownCount = new int[cells];
        this.shownDamage = new int[cells];
        this.shownBow = new boolean[cells];
        this.ownedUntil = new int[cells];
        this.repaint = new boolean[cells];
        this.predicted = new boolean[cells];
        reseed();
    }

    public static InventoryPaintPlan forPlayer() {
        return new InventoryPaintPlan(PLAYER_CELLS);
    }

    public static InventoryPaintPlan forContainer() {
        return new InventoryPaintPlan(CONTAINER_CELLS);
    }

    public int cells() {
        return cells;
    }

    public void reseed() {
        seeded = false;
        Arrays.fill(ownedUntil, NO_FRAME);
        Arrays.fill(repaint, false);
        Arrays.fill(predicted, false);
        confirmedView = null;
        headView = null;
    }

    public static int landingFrame(int headFrame) {
        return InventoryPredictionLedger.landingFrame(headFrame);
    }

    public void own(int cell, int landingFrame) {
        if (cell < 0 || cell >= cells) {
            return;
        }
        int now = ownedUntil[cell];
        if (now == NO_FRAME || landingFrame - now > 0) {
            ownedUntil[cell] = landingFrame;
        }
    }

    public void ownIntent(InventoryIntents.Intent intent, int headFrame) {
        if (intent == null || !intent.acts()) {
            return;
        }
        int landing = landingFrame(headFrame);
        int action = intent.action();
        switch (action) {
            case Input.INV_QUICK_MOVE, Input.INV_PICKUP_ALL, Input.INV_CURSOR_RESOLVE -> ownAll(landing);
            case Input.INV_DROP_CURSOR_ONE, Input.INV_DROP_CURSOR_ALL -> own(cursorCell(), landing);
            case Input.INV_DROP_ONE, Input.INV_DROP_STACK -> ownAddr(intent.src(), landing);
            case Input.INV_PICKUP, Input.INV_PICKUP_HALF -> {
                ownAddr(intent.src(), landing);
                own(cursorCell(), landing);
            }
            case Input.INV_SWAP_SLOT, Input.INV_MOVE -> {
                ownAddr(intent.src(), landing);
                ownAddr(intent.dst(), landing);
            }
            case Input.INV_CONTAINER_TAKE, Input.INV_CONTAINER_PUT -> {
                ownAddr(intent.src(), landing);
                own(cursorCell(), landing);
            }
            default -> ownAll(landing);
        }
    }

    public void ownHandSwap(int heldSlot, int headFrame) {
        int landing = landingFrame(headFrame);
        own(heldSlot, landing);
        own(ItemDict.OFF_HAND, landing);
    }

    public void ownAll(int landingFrame) {
        for (int cell = 0; cell < cells; cell++) {
            own(cell, landingFrame);
        }
    }

    private int cursorCell() {
        return cells == PLAYER_CELLS ? PLAYER_CURSOR : -1;
    }

    private void ownAddr(int addr, int landingFrame) {
        if (Input.addrIsCell(addr)) {
            if (cells == CONTAINER_CELLS) {
                own(Input.addrIndex(addr), landingFrame);
            }
            return;
        }
        if (cells == PLAYER_CELLS) {
            own(addr, landingFrame);
        }
    }

    public void plan(PlayerState confirmed, PlayerState head, int confirmedTick) {
        confirmedView = confirmed;
        headView = head;
        for (int cell = 0; cell < cells; cell++) {
            boolean owned = resolveOwnership(cell, confirmedTick) && head != null;
            predicted[cell] = owned;
            PlayerState src = owned ? head : confirmed;
            submit(cell, entryOf(src, cell), countOf(src, cell), damageOf(src, cell), bowOf(src, cell));
        }
        seeded = true;
    }

    public void plan(Container confirmed, Container head, int confirmedTick) {
        for (int cell = 0; cell < cells; cell++) {
            boolean owned = resolveOwnership(cell, confirmedTick) && head != null;
            predicted[cell] = owned;
            Container src = owned ? head : confirmed;
            if (src == null) {
                submit(cell, EMPTY, 0, 0, false);
            } else {
                submit(cell, src.entry[cell], src.count[cell], src.damage[cell], false);
            }
        }
        seeded = true;
    }

    private boolean resolveOwnership(int cell, int confirmedTick) {
        int until = ownedUntil[cell];
        if (until == NO_FRAME) {
            return false;
        }
        if (confirmedTick - until > 0) {
            ownedUntil[cell] = NO_FRAME;
            return false;
        }
        return true;
    }

    private void submit(int cell, int entry, int count, int damage, boolean bow) {
        boolean changed = !seeded
                || shownEntry[cell] != entry
                || shownCount[cell] != count
                || shownDamage[cell] != damage
                || shownBow[cell] != bow;
        repaint[cell] = changed;
        shownEntry[cell] = entry;
        shownCount[cell] = count;
        shownDamage[cell] = damage;
        shownBow[cell] = bow;
    }

    public boolean repaint(int cell) {
        return cell >= 0 && cell < cells && repaint[cell];
    }

    public boolean predicted(int cell) {
        return cell >= 0 && cell < cells && predicted[cell];
    }

    public PlayerState view(int cell) {
        return predicted(cell) && headView != null ? headView : confirmedView;
    }

    public int ownedCells(int confirmedTick) {
        int n = 0;
        for (int cell = 0; cell < cells; cell++) {
            int until = ownedUntil[cell];
            if (until != NO_FRAME && confirmedTick - until < 0) {
                n++;
            }
        }
        return n;
    }

    public int repaintCount() {
        int n = 0;
        for (int cell = 0; cell < cells; cell++) {
            if (repaint[cell]) {
                n++;
            }
        }
        return n;
    }

    private static int entryOf(PlayerState p, int cell) {
        if (p == null) {
            return EMPTY;
        }
        return cell == PLAYER_CURSOR ? p.cursorEntry : p.slotEntry[cell];
    }

    private static int countOf(PlayerState p, int cell) {
        if (p == null) {
            return 0;
        }
        return cell == PLAYER_CURSOR ? p.cursorCount : p.slotCount[cell];
    }

    private static int damageOf(PlayerState p, int cell) {
        if (p == null) {
            return 0;
        }
        return cell == PLAYER_CURSOR ? p.cursorDamage : p.slotDamage[cell];
    }

    private static boolean bowOf(PlayerState p, int cell) {
        if (p == null) {
            return false;
        }
        if (cell == PLAYER_CURSOR) {
            return p.cursorCrossbowLoaded;
        }
        if (p.slotCrossbowLoaded == null || cell >= p.slotCrossbowLoaded.length) {
            return false;
        }
        return p.slotCrossbowLoaded[cell];
    }
}
