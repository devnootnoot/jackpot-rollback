package me.nootnoot.edge;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.inventory.ItemStack;

public final class EdgeBlockLoot {

    private EdgeBlockLoot() {
    }

    public static int dropItemId(Material block, ItemStack tool) {
        if (block == null || block.isAir()) {
            return 0;
        }
        if (block == Material.COBWEB) {
            Material type = tool == null ? Material.AIR : tool.getType();
            boolean swordOrShears = type == Material.SHEARS || Tag.ITEMS_SWORDS.isTagged(type);
            return EdgeItemIds.canonical(swordOrShears ? Material.COBWEB : Material.STRING);
        }
        if (block == Material.FIRE || block == Material.SOUL_FIRE) {
            return 0;
        }
        if (block == Material.SNOW || block == Material.SNOW_BLOCK) {
            Material type = tool == null ? Material.AIR : tool.getType();
            return Tag.ITEMS_SHOVELS.isTagged(type)
                    ? EdgeItemIds.canonical(Material.SNOWBALL) : 0;
        }
        return EdgeItemIds.canonical(loot(block));
    }

    public static Material loot(Material block) {
        if (block == Material.GRASS_BLOCK || block == Material.DIRT_PATH
                || block == Material.FARMLAND || block == Material.MYCELIUM
                || block == Material.PODZOL || block == Material.ROOTED_DIRT) {
            return Material.DIRT;
        }
        if (block == Material.STONE) {
            return Material.COBBLESTONE;
        }
        if (block == Material.DEEPSLATE) {
            return Material.COBBLED_DEEPSLATE;
        }
        if (block == Material.GLOWSTONE) {
            return Material.GLOWSTONE_DUST;
        }
        if (Tag.LEAVES.isTagged(block)) {
            return Material.AIR;
        }
        if (block == Material.SNOW || block == Material.SNOW_BLOCK) {
            return Material.AIR;
        }
        return block;
    }
}
