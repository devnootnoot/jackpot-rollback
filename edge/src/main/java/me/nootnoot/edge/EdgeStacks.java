package me.nootnoot.edge;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class EdgeStacks {

    private final EdgeLoadout loadout;
    private final int slot;

    public EdgeStacks(EdgeLoadout loadout, int slot) {
        this.loadout = loadout;
        this.slot = slot;
    }

    public ItemStack resolve(int canonicalId, int inventorySlot) {
        if (canonicalId == 0) {
            return null;
        }
        ItemStack seeded = loadout == null ? null : loadout.item(slot, inventorySlot);
        if (seeded != null && !seeded.getType().isAir()
                && EdgeItemIds.canonical(seeded.getType()) == canonicalId) {
            return seeded;
        }
        return plain(canonicalId, 1);
    }

    public ItemStack resolveHeld(int canonicalId, int heldSlot, int count) {
        int hotbar = heldSlot < 0 || heldSlot >= EdgeLoadout.HOTBAR_SLOTS ? 0 : heldSlot;
        ItemStack stack = resolve(canonicalId, hotbar);
        if (stack != null && count > 0) {
            stack.setAmount(Math.min(stack.getMaxStackSize(), count));
        }
        return stack;
    }

    public static ItemStack plain(int canonicalId, int count) {
        Material material = EdgeItemIds.material(canonicalId);
        if (material.isAir()) {
            return null;
        }
        return new ItemStack(material, Math.max(1, Math.min(material.getMaxStackSize(), count)));
    }

    public static String descriptor(Material material) {
        if (material == null || !material.isBlock() || material.isAir()) {
            return null;
        }
        try {
            return material.getKey().toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
