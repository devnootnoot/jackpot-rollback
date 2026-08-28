package me.nootnoot.edge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

public final class EdgeEntryStacks {

    private static final int NEST_DEPTH = 3;
    private static final int AMBIGUOUS = -1;

    private final EdgeLoadout loadout;

    private ItemStack[] entryStacks;

    public EdgeEntryStacks(EdgeLoadout loadout) {
        this.loadout = loadout;
    }

    public ItemStack stack(GameState confirmed, int entry, int count, int damage) {
        return stack(confirmed, entry, count, damage, false);
    }

    public ItemStack stack(GameState confirmed, int entry, int count, int damage,
                           boolean crossbowLoaded) {
        if (entry == ItemDict.NONE || count <= 0) {
            return null;
        }
        ItemStack template = entryStack(confirmed, entry);
        if (template == null) {
            return null;
        }
        ItemStack out = template.clone();
        out.setAmount(Math.max(1, Math.min(out.getMaxStackSize(), count)));
        int max = out.getType().getMaxDurability();
        if (max > 0) {
            ItemMeta meta = out.getItemMeta();
            if (meta instanceof Damageable damageable) {
                damageable.setDamage(Math.max(0, Math.min(max - 1, damage)));
                out.setItemMeta(meta);
            }
        }
        return EdgeHeldItems.charged(out, crossbowLoaded);
    }

    private ItemStack entryStack(GameState confirmed, int entry) {
        ItemDict dict = confirmed.dict;
        if (dict == null) {
            return null;
        }
        if (entryStacks == null) {
            seed(dict);
        }
        if (entry <= 0 || entry >= entryStacks.length) {
            return null;
        }
        return entryStacks[entry];
    }

    private void seed(ItemDict dict) {
        entryStacks = new ItemStack[dict.size() + 1];
        GameState frame0 = loadout != null ? loadout.state() : null;
        PlayerState[] seeded = frame0 != null ? frame0.roundInitial : null;
        if (seeded != null) {
            List<ItemStack> nesting = new ArrayList<>();
            for (int p = 0; p < seeded.length && p < 2; p++) {
                PlayerState state = seeded[p];
                if (state == null) {
                    continue;
                }
                for (int s = 0; s < state.slotEntry.length; s++) {
                    ItemStack stack = loadout.item(p, s);
                    if (stack == null || stack.getType().isAir()) {
                        continue;
                    }
                    int e = state.slotEntry[s];
                    if (e > 0 && e < entryStacks.length && entryStacks[e] == null) {
                        entryStacks[e] = stack;
                    }
                    nesting.add(stack);
                }
            }
            Map<Integer, Integer> byItemId = uniqueEntriesByItemId(dict);
            for (ItemStack stack : nesting) {
                seedNested(byItemId, stack, 0);
            }
        }
        for (int e = 1; e < entryStacks.length; e++) {
            if (entryStacks[e] != null) {
                continue;
            }
            Material material = EdgeItemIds.material(dict.itemId(e));
            entryStacks[e] = material.isAir() ? null : new ItemStack(material);
        }
    }

    private static Map<Integer, Integer> uniqueEntriesByItemId(ItemDict dict) {
        Map<Integer, Integer> byItemId = new HashMap<>();
        for (int e = 1; e <= dict.size(); e++) {
            int itemId = dict.itemId(e);
            if (itemId == 0) {
                continue;
            }
            Integer seen = byItemId.put(itemId, e);
            if (seen != null) {
                byItemId.put(itemId, AMBIGUOUS);
            }
        }
        return byItemId;
    }

    private void seedNested(Map<Integer, Integer> byItemId, ItemStack stack, int depth) {
        if (stack == null || depth >= NEST_DEPTH) {
            return;
        }
        for (ItemStack inner : nested(stack)) {
            if (inner == null || inner.getType().isAir()) {
                continue;
            }
            Integer entry = byItemId.get(EdgeItemIds.canonical(inner));
            if (entry != null && entry > 0 && entry < entryStacks.length
                    && entryStacks[entry] == null) {
                entryStacks[entry] = inner.clone();
            }
            seedNested(byItemId, inner, depth + 1);
        }
    }

    private static List<ItemStack> nested(ItemStack stack) {
        ItemMeta meta;
        try {
            meta = stack.getItemMeta();
        } catch (RuntimeException ex) {
            return List.of();
        }
        if (meta instanceof BundleMeta bundle) {
            return bundle.getItems();
        }
        if (!(meta instanceof BlockStateMeta block)) {
            return List.of();
        }
        try {
            if (!block.hasBlockState()) {
                return List.of();
            }
            BlockState state = block.getBlockState();
            if (!(state instanceof Container container)) {
                return List.of();
            }
            return Arrays.asList(container.getInventory().getContents());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }
}
