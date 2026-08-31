package me.nootnoot.edge;

import me.nootnoot.sim.contract.InventoryIntents;
import me.nootnoot.sim.host.InventoryPaintPlan;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import java.util.Arrays;

public final class EdgeInventoryMirror {

    private final Player player;
    private final EdgeEntryStacks stacks;
    private final int localSlot;
    private final InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
    private final boolean[] resync = new boolean[InventoryPaintPlan.PLAYER_CELLS];
    private final boolean[] wasPredicted = new boolean[resync.length];

    public EdgeInventoryMirror(Player player, EdgeEntryStacks stacks, int localSlot) {
        this.player = player;
        this.stacks = stacks;
        this.localSlot = localSlot;
    }

    private static int entryOfState(PlayerState p, int slot) {
        return p == null || p.slotEntry == null ? -1 : p.slotEntry[slot];
    }

    private static int countOfState(PlayerState p, int slot) {
        return p == null || p.slotCount == null ? -1 : p.slotCount[slot];
    }

    public void apply(GameState head, GameState confirmed) {
        if (confirmed == null || !player.isOnline()) {
            return;
        }
        PlayerState mine = confirmed.players[localSlot];
        if (mine == null || mine.slotEntry == null) {
            return;
        }
        PlayerState predicted = head == null ? null : head.players[localSlot];
        if (predicted != null && predicted.slotEntry == null) {
            predicted = null;
        }
        plan.plan(mine, predicted, confirmed.tick);
        PlayerInventory inv = player.getInventory();
        boolean wrote = false;
        for (int slot = 0; slot < ItemDict.SLOTS; slot++) {
            boolean forced = takeResync(slot);
            if (!forced && !plan.repaint(slot)) {
                continue;
            }
            if (EdgeTrace.on()) {
                PlayerState src = plan.view(slot);
                PlayerState h = head == null ? null : head.players[localSlot];
                EdgeTrace.log("paint slot=" + slot
                        + " from=" + (plan.predicted(slot) ? "HEAD" : "CONFIRMED")
                        + " forcedResync=" + forced
                        + " painting=" + entryOfState(src, slot) + "x" + countOfState(src, slot)
                        + " confirmed=" + entryOfState(mine, slot) + "x" + countOfState(mine, slot)
                        + " head=" + entryOfState(h, slot) + "x" + countOfState(h, slot)
                        + " confirmedTick=" + confirmed.tick
                        + " headTick=" + (head == null ? -1 : head.tick));
            }
            inv.setItem(slot, stackFor(confirmed, plan.view(slot), slot));
            wrote = true;
        }
        if (takeResync(InventoryPaintPlan.PLAYER_CURSOR)
                || plan.repaint(InventoryPaintPlan.PLAYER_CURSOR)) {
            PlayerState src = plan.view(InventoryPaintPlan.PLAYER_CURSOR);
            player.setItemOnCursor(stacks.stack(confirmed, src.cursorEntry, src.cursorCount,
                    src.cursorDamage, src.cursorCrossbowLoaded));
            wrote = true;
        }
        if (wrote) {
            player.updateInventory();
        }
    }

    public void own(InventoryIntents.Intent intent, int headFrame) {
        plan.ownIntent(intent, headFrame);
    }

    public void ownHandSwap(int heldSlot, int headFrame) {
        plan.ownHandSwap(heldSlot, headFrame);
    }

    public InventoryPaintPlan plan() {
        return plan;
    }

    public void reseed() {
        Arrays.fill(resync, false);
        plan.reseed();
    }

    private ItemStack stackFor(GameState confirmed, PlayerState src, int slot) {
        return stacks.stack(confirmed, src.slotEntry[slot], src.slotCount[slot],
                src.slotDamage[slot], src.slotCrossbowLoaded[slot]);
    }

    private boolean takeResync(int cell) {
        boolean pending = resync[cell];
        boolean owned = plan.predicted(cell);
        boolean changedOwner = owned != wasPredicted[cell];
        wasPredicted[cell] = owned;
        resync[cell] = false;
        return pending || changedOwner;
    }

    public void resyncAll() {
        Arrays.fill(resync, true);
    }

    public void resync(int cell) {
        if (cell >= 0 && cell < resync.length) {
            resync[cell] = true;
        }
    }
}
