package me.nootnoot.edge;

import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;

public final class EdgeMining {

    private static final float INSTANT = 1.0f;

    private static final float BARE_HAND_SPEED = 1.0f;
    private static final float SWORD_SPEED = 1.5f;
    private static final float SHEARS_ON_WEB_SPEED = 15.0f;
    private static final float SHEARS_ON_LEAF_SPEED = 15.0f;
    private static final float SHEARS_ON_WOOL_SPEED = 5.0f;

    private static final float SUBMERGED_PENALTY = 0.2f;
    private static final float AIRBORNE_PENALTY = 0.2f;

    private static final float HARVEST_DIVISOR = 30.0f;
    private static final float NO_HARVEST_DIVISOR = 100.0f;

    private static final int TIER_HAND = -1;
    private static final int TIER_WOOD = 0;
    private static final int TIER_STONE = 1;
    private static final int TIER_IRON = 2;
    private static final int TIER_DIAMOND = 3;
    private static final int TIER_NETHERITE = 4;

    private EdgeMining() {
    }

    public static float destroyDelta(PlayerState self, ItemStack tool, ItemStack helmet,
                                     Material block) {
        if (block == null || block.isAir()) {
            return 0f;
        }
        if (block == Material.FIRE || block == Material.SOUL_FIRE) {
            return INSTANT;
        }
        float hardness = hardness(block);
        if (hardness < 0f) {
            return 0f;
        }
        if (hardness == 0f) {
            return INSTANT;
        }
        float speed = toolSpeed(tool, block);
        int efficiency = tool == null ? 0 : tool.getEnchantmentLevel(Enchantment.EFFICIENCY);
        if (speed > 1.0f && efficiency > 0) {
            speed += efficiency * efficiency + 1;
        }
        if (self != null && self.submergedEye && !aquaAffinity(helmet)) {
            speed *= SUBMERGED_PENALTY;
        }
        if (self != null && !self.onGround) {
            speed *= AIRBORNE_PENALTY;
        }
        float divisor = canHarvest(tool, block) ? HARVEST_DIVISOR : NO_HARVEST_DIVISOR;
        float delta = speed / hardness / divisor;
        return delta <= 0f ? 0f : Math.min(INSTANT, delta);
    }

    public static float hardness(Material block) {
        if (block == null || block.isAir() || !block.isBlock()) {
            return -1f;
        }
        try {
            return block.getHardness();
        } catch (RuntimeException ex) {
            return -1f;
        }
    }

    private static boolean aquaAffinity(ItemStack helmet) {
        return helmet != null && !helmet.getType().isAir()
                && helmet.getEnchantmentLevel(Enchantment.AQUA_AFFINITY) > 0;
    }

    private static float toolSpeed(ItemStack tool, Material block) {
        Material type = tool == null ? Material.AIR : tool.getType();
        if (block == Material.COBWEB) {
            return type == Material.SHEARS || Tag.ITEMS_SWORDS.isTagged(type)
                    ? SHEARS_ON_WEB_SPEED : BARE_HAND_SPEED;
        }
        if (type == Material.SHEARS) {
            if (Tag.LEAVES.isTagged(block)) {
                return SHEARS_ON_LEAF_SPEED;
            }
            if (Tag.WOOL.isTagged(block)) {
                return SHEARS_ON_WOOL_SPEED;
            }
        }
        if (!correctClass(type, block)) {
            return Tag.ITEMS_SWORDS.isTagged(type) ? SWORD_SPEED : BARE_HAND_SPEED;
        }
        String name = type.name();
        if (name.startsWith("NETHERITE_")) {
            return 9.0f;
        }
        if (name.startsWith("DIAMOND_")) {
            return 8.0f;
        }
        if (name.startsWith("GOLDEN_")) {
            return 12.0f;
        }
        if (name.startsWith("IRON_")) {
            return 6.0f;
        }
        if (name.startsWith("STONE_")) {
            return 4.0f;
        }
        if (name.startsWith("WOODEN_")) {
            return 2.0f;
        }
        return BARE_HAND_SPEED;
    }

    private static boolean correctClass(Material tool, Material block) {
        if (Tag.ITEMS_PICKAXES.isTagged(tool)) {
            return Tag.MINEABLE_PICKAXE.isTagged(block);
        }
        if (Tag.ITEMS_AXES.isTagged(tool)) {
            return Tag.MINEABLE_AXE.isTagged(block);
        }
        if (Tag.ITEMS_SHOVELS.isTagged(tool)) {
            return Tag.MINEABLE_SHOVEL.isTagged(block);
        }
        if (Tag.ITEMS_HOES.isTagged(tool)) {
            return Tag.MINEABLE_HOE.isTagged(block);
        }
        return false;
    }

    private static boolean canHarvest(ItemStack tool, Material block) {
        Material type = tool == null ? Material.AIR : tool.getType();
        if (!Tag.MINEABLE_PICKAXE.isTagged(block)) {
            return true;
        }
        if (!correctClass(type, block)) {
            return false;
        }
        return tier(type) >= required(block);
    }

    private static int required(Material block) {
        if (Tag.NEEDS_DIAMOND_TOOL.isTagged(block)) {
            return TIER_DIAMOND;
        }
        if (Tag.NEEDS_IRON_TOOL.isTagged(block)) {
            return TIER_IRON;
        }
        if (Tag.NEEDS_STONE_TOOL.isTagged(block)) {
            return TIER_STONE;
        }
        return TIER_WOOD;
    }

    private static int tier(Material tool) {
        String name = tool.name();
        if (name.startsWith("NETHERITE_")) {
            return TIER_NETHERITE;
        }
        if (name.startsWith("DIAMOND_")) {
            return TIER_DIAMOND;
        }
        if (name.startsWith("IRON_")) {
            return TIER_IRON;
        }
        if (name.startsWith("STONE_")) {
            return TIER_STONE;
        }
        if (name.startsWith("WOODEN_") || name.startsWith("GOLDEN_")) {
            return TIER_WOOD;
        }
        return TIER_HAND;
    }
}
