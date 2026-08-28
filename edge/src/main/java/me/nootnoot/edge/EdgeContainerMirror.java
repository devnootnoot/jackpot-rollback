package me.nootnoot.edge;

import me.nootnoot.sim.contract.InventoryIntents;
import me.nootnoot.sim.host.InventoryPaintPlan;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lidded;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

public final class EdgeContainerMirror {

    private static final Component TITLE = Component.text("Container");

    private final Player player;
    private final EdgeEntryStacks stacks;
    private final int localSlot;

    private final InventoryPaintPlan plan = InventoryPaintPlan.forContainer();
    private final boolean[] resync = new boolean[InventoryPaintPlan.CONTAINER_CELLS];

    private Inventory view;
    private int openId = -1;
    private boolean closing;
    private boolean awaitingClose;
    private final Set<Long> openLids = new HashSet<>();

    public EdgeContainerMirror(Player player, EdgeEntryStacks stacks, int localSlot) {
        this.player = player;
        this.stacks = stacks;
        this.localSlot = localSlot;
    }

    public void apply(GameState head, GameState confirmed) {
        if (confirmed == null || !player.isOnline()) {
            return;
        }
        PlayerState mine = confirmed.players[localSlot];
        if (mine == null) {
            return;
        }
        Container c = mine.openContainer < 0 ? null : confirmed.containers.get(mine.openContainer);
        if (c == null) {
            awaitingClose = false;
            lids(Long.MIN_VALUE, peerLidCell(confirmed));
            close();
            return;
        }
        if (awaitingClose) {
            return;
        }
        lids(mine.openContainerKey, peerLidCell(confirmed));
        if (view == null || openId != mine.openContainer) {
            openId = mine.openContainer;
            Arrays.fill(resync, false);
            plan.reseed();
            view = Bukkit.createInventory(player, Container.CELLS, TITLE);
            player.openInventory(view);
        }
        plan.plan(c, predictedCells(head, mine.openContainer), confirmed.tick);
        boolean wrote = false;
        for (int i = 0; i < Container.CELLS; i++) {
            if (!takeResync(i) && !plan.repaint(i)) {
                continue;
            }
            Container src = plan.predicted(i) ? predictedCells(head, mine.openContainer) : c;
            if (src == null) {
                src = c;
            }
            view.setItem(i, stacks.stack(confirmed, src.entry[i], src.count[i], src.damage[i]));
            wrote = true;
        }
        if (wrote) {
            player.updateInventory();
        }
    }

    private Container predictedCells(GameState head, int openContainer) {
        if (head == null || openContainer < 0) {
            return null;
        }
        PlayerState mine = head.players[localSlot];
        if (mine == null || mine.openContainer != openContainer) {
            return null;
        }
        return head.containers.get(openContainer);
    }

    public void own(InventoryIntents.Intent intent, int headFrame) {
        plan.ownIntent(intent, headFrame);
    }

    public InventoryPaintPlan plan() {
        return plan;
    }

    public boolean owns(Inventory inventory) {
        return view != null && inventory != null && view.equals(inventory);
    }

    public boolean closing() {
        return closing;
    }

    public void forget() {
        view = null;
        openId = -1;
        lids(Long.MIN_VALUE, Long.MIN_VALUE);
        plan.reseed();
    }

    public void closedByPlayer() {
        awaitingClose = true;
        forget();
    }

    public void reseed() {
        awaitingClose = false;
        lids(Long.MIN_VALUE, Long.MIN_VALUE);
        close();
    }

    private long peerLidCell(GameState confirmed) {
        for (int slot = 0; slot < confirmed.players.length; slot++) {
            if (slot == localSlot) {
                continue;
            }
            PlayerState other = confirmed.players[slot];
            if (other != null && other.openContainer >= 0) {
                return other.openContainerKey;
            }
        }
        return Long.MIN_VALUE;
    }

    private void lids(long mineCell, long peerCell) {
        Set<Long> want = new HashSet<>(4);
        if (mineCell != Long.MIN_VALUE) {
            want.add(mineCell);
        }
        if (peerCell != Long.MIN_VALUE) {
            want.add(peerCell);
        }
        for (Iterator<Long> held = openLids.iterator(); held.hasNext();) {
            long cell = held.next();
            if (!want.contains(cell)) {
                held.remove();
                drive(cell, false);
            }
        }
        for (long cell : want) {
            if (openLids.add(cell)) {
                drive(cell, true);
            }
        }
    }

    private void drive(long cell, boolean open) {
        if (!player.isOnline()) {
            return;
        }
        World world = player.getWorld();
        int x = BlockStore.unpackX(cell);
        int y = BlockStore.unpackY(cell);
        int z = BlockStore.unpackZ(cell);
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return;
        }
        BlockState state = world.getBlockAt(x, y, z).getState(false);
        if (!(state instanceof Lidded box)) {
            return;
        }
        try {
            if (open) {
                box.open();
            } else {
                box.close();
            }
        } catch (IllegalStateException ignored) {
        }
    }

    private void close() {
        if (view == null) {
            return;
        }
        closing = true;
        try {
            if (player.isOnline() && view.equals(player.getOpenInventory().getTopInventory())) {
                player.closeInventory();
            }
        } finally {
            closing = false;
            forget();
        }
    }

    private boolean takeResync(int cell) {
        boolean pending = resync[cell];
        resync[cell] = plan.predicted(cell);
        return pending || resync[cell];
    }

    public void resyncAll() {
        Arrays.fill(resync, true);
    }
}
