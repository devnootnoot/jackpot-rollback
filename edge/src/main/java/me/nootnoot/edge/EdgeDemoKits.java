package me.nootnoot.edge;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.MatchSetupFrame0Decoder;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

public final class EdgeDemoKits {

    public static final String IRON = EdgeGameTypes.IRON;
    public static final String DIAMOND = EdgeGameTypes.DIAMOND;
    public static final String POT = EdgeGameTypes.POT;
    public static final String NETHERITE_POT = EdgeGameTypes.NETHERITE_POT;
    public static final String CRYSTAL = EdgeGameTypes.CRYSTAL;

    private static final int SLOTS = MatchSetupFrame0Decoder.INVENTORY_SLOTS;

    private static final float FIST_DAMAGE = EdgeHeldItems.FIST_DAMAGE;
    private static final float FIST_SPEED = EdgeHeldItems.FIST_SPEED;
    private static final float SWORD_SPEED = 1.6f;
    private static final float AXE_SPEED = 1.0f;
    private static final float PICKAXE_SPEED = 1.2f;
    private static final float MACE_SPEED = 0.6f;

    private static final float IRON_SWORD_DAMAGE = 6f;
    private static final float DIAMOND_SWORD_DAMAGE = 7f;
    private static final float NETHERITE_SWORD_DAMAGE = 8f;
    private static final float DIAMOND_AXE_DAMAGE = 9f;
    private static final float NETHERITE_AXE_DAMAGE = 10f;
    private static final float NETHERITE_PICKAXE_DAMAGE = 6f;
    private static final float MACE_DAMAGE = 6f;

    private static final float IRON_ARMOR = 15f;
    private static final float DIAMOND_ARMOR = 20f;
    private static final float NETHERITE_ARMOR = 20f;
    private static final float DIAMOND_TOUGHNESS = 8f;
    private static final float NETHERITE_TOUGHNESS = 12f;
    private static final float NETHERITE_KB_RESISTANCE = 0.4f;

    private static final int GAPPLE_NUTRITION = 4;
    private static final float GAPPLE_SATURATION = 9.6f;
    private static final int GAPPLE_EAT_TICKS = 32;

    private static final float FULL_HEALTH = 20f;
    private static final float FULL_FOOD = 20f;
    private static final float START_SATURATION = 5f;

    private static final int POT_HOTBAR_START = 2;
    private static final int POT_STORAGE_END = 27;
    private static final int POT_HARMING_START = 27;
    private static final int POT_HARMING_END = 30;
    private static final int POT_SWIFTNESS_START = 30;
    private static final int POT_SWIFTNESS_END = 32;
    private static final int POT_STRENGTH_START = 32;
    private static final int POT_STRENGTH_END = 34;
    private static final int POT_FIRE_RESISTANCE_START = 34;
    private static final int POT_FIRE_RESISTANCE_END = 36;

    private static final int STACK = 64;
    private static final int EFFICIENCY_LEVEL = 5;
    private static final int MULTISHOT_LEVEL = 1;
    private static final int WIND_BURST_LEVEL = 3;
    private static final int BREACH_LEVEL = 4;
    private static final int SHULKER_SLOTS = 27;
    private static final int CRYSTAL_ENDER_CHESTS = 8;
    private static final int CRYSTAL_PEARLS = 16;
    private static final int CRYSTAL_LOOSE_TOTEM_START = 30;
    private static final int CRYSTAL_LOOSE_TOTEM_END = 33;
    private static final int CRYSTAL_WIND_BURST_MACE_SLOT = 33;
    private static final int CRYSTAL_NOTCH_APPLE_SLOT = 34;
    private static final int CRYSTAL_NOTCH_APPLES = 16;
    private static final int CRYSTAL_SPARE_GAPPLES = 35;
    private static final int FLIGHT_ONE = 1;
    private static final int FLIGHT_TWO = 2;
    private static final int FLIGHT_THREE = 3;

    private EdgeDemoKits() {
    }

    public record Stats(float damage, float speed, int useKind, int nutrition, float saturation,
                        boolean alwaysEdible, int eatTicks, int fireworkFlight) {

        public static final Stats FIST =
                new Stats(FIST_DAMAGE, FIST_SPEED, Combat.USE_NONE, 0, 0f, false, 0, 0);
    }

    public record Kit(String id, String summary, ItemStack[] items, int selectedSlot,
                      float armor, float armorToughness, float kbResistance,
                      float health, float food, float saturation) {
    }

    public static Kit forGameType(String gameType) {
        return build(EdgeGameTypes.normalize(gameType));
    }

    public static List<String> materials(Kit kit) {
        if (kit == null) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (ItemStack stack : kit.items()) {
            if (stack != null && stack.getType() != Material.AIR) {
                names.add(stack.getType().name());
            }
        }
        return List.copyOf(names);
    }

    public static Stats stats(ItemStack stack) {
        if (stack == null || stack.getType() == Material.AIR) {
            return Stats.FIST;
        }
        Material type = stack.getType();
        return switch (type) {
            case IRON_SWORD -> melee(IRON_SWORD_DAMAGE, SWORD_SPEED);
            case DIAMOND_SWORD -> melee(DIAMOND_SWORD_DAMAGE, SWORD_SPEED);
            case NETHERITE_SWORD -> melee(NETHERITE_SWORD_DAMAGE, SWORD_SPEED);
            case DIAMOND_AXE -> melee(DIAMOND_AXE_DAMAGE, AXE_SPEED);
            case NETHERITE_AXE -> melee(NETHERITE_AXE_DAMAGE, AXE_SPEED);
            case NETHERITE_PICKAXE -> melee(NETHERITE_PICKAXE_DAMAGE, PICKAXE_SPEED);
            case MACE -> melee(MACE_DAMAGE, MACE_SPEED);
            case GOLDEN_APPLE, ENCHANTED_GOLDEN_APPLE -> new Stats(FIST_DAMAGE, FIST_SPEED,
                    Combat.USE_FOOD, GAPPLE_NUTRITION, GAPPLE_SATURATION, true, GAPPLE_EAT_TICKS, 0);
            case SPLASH_POTION -> new Stats(FIST_DAMAGE, FIST_SPEED,
                    Combat.USE_SPLASH_POTION, 0, 0f, false, 0, 0);
            case ENDER_PEARL -> new Stats(FIST_DAMAGE, FIST_SPEED,
                    Combat.USE_PEARL, 0, 0f, false, 0, 0);
            case CROSSBOW -> new Stats(FIST_DAMAGE, FIST_SPEED,
                    Combat.USE_CROSSBOW, 0, 0f, false, 0, 0);
            case BOW -> new Stats(FIST_DAMAGE, FIST_SPEED,
                    Combat.USE_BOW, 0, 0f, false, 0, 0);
            case FIREWORK_ROCKET -> new Stats(FIST_DAMAGE, FIST_SPEED,
                    Combat.USE_FIREWORK, 0, 0f, false, 0, EdgeHeldItems.fireworkFlight(stack));
            default -> Stats.FIST;
        };
    }

    private static Stats melee(float damage, float speed) {
        return new Stats(damage, speed, Combat.USE_NONE, 0, 0f, false, 0, 0);
    }

    private static Kit build(String id) {
        ItemStack[] items = new ItemStack[SLOTS];
        switch (id) {
            case CRYSTAL -> {
                items[0] = new ItemStack(Material.NETHERITE_SWORD);
                items[1] = new ItemStack(Material.END_CRYSTAL, STACK);
                items[2] = new ItemStack(Material.OBSIDIAN, STACK);
                items[3] = new ItemStack(Material.RESPAWN_ANCHOR, STACK);
                items[4] = new ItemStack(Material.GLOWSTONE, STACK);
                items[5] = efficiencyPickaxe();
                items[6] = new ItemStack(Material.ELYTRA);
                items[7] = new ItemStack(Material.GOLDEN_APPLE, STACK);
                items[8] = fireworks(FLIGHT_THREE);

                items[9] = new ItemStack(Material.END_CRYSTAL, STACK);
                items[10] = new ItemStack(Material.END_CRYSTAL, STACK);
                items[11] = new ItemStack(Material.NETHERITE_AXE);
                items[12] = new ItemStack(Material.OBSIDIAN, STACK);
                items[13] = new ItemStack(Material.WIND_CHARGE, STACK);
                items[14] = multishotCrossbow();
                items[15] = fireworks(FLIGHT_ONE);
                items[16] = fireworks(FLIGHT_TWO);
                items[17] = chargedCrossbow();
                items[18] = new ItemStack(Material.ENDER_CHEST, CRYSTAL_ENDER_CHESTS);
                items[19] = totemShulker();
                items[20] = totemShulker();
                items[21] = new ItemStack(Material.ARROW, STACK);
                items[22] = weaknessArrows();
                items[23] = new ItemStack(Material.ENDER_PEARL, CRYSTAL_PEARLS);
                items[24] = new ItemStack(Material.BOW);
                items[25] = new ItemStack(Material.MACE);
                items[26] = new ItemStack(Material.SHIELD);
                items[27] = new ItemStack(Material.WATER_BUCKET);
                items[28] = new ItemStack(Material.LAVA_BUCKET);
                items[29] = new ItemStack(Material.COBWEB, STACK);
                for (int slot = CRYSTAL_LOOSE_TOTEM_START; slot < CRYSTAL_LOOSE_TOTEM_END; slot++) {
                    items[slot] = new ItemStack(Material.TOTEM_OF_UNDYING);
                }
                items[CRYSTAL_WIND_BURST_MACE_SLOT] = windBurstMace();
                items[CRYSTAL_NOTCH_APPLE_SLOT] =
                        new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, CRYSTAL_NOTCH_APPLES);
                items[CRYSTAL_SPARE_GAPPLES] = new ItemStack(Material.GOLDEN_APPLE, STACK);
                items[EdgeLoadout.OFF_HAND] = new ItemStack(Material.TOTEM_OF_UNDYING);
                armor(items, Material.NETHERITE_BOOTS, Material.NETHERITE_LEGGINGS,
                        Material.NETHERITE_CHESTPLATE, Material.NETHERITE_HELMET);
                return new Kit(id, EdgeGameTypes.summary(CRYSTAL), items, 0,
                        NETHERITE_ARMOR, NETHERITE_TOUGHNESS, NETHERITE_KB_RESISTANCE,
                        FULL_HEALTH, FULL_FOOD, START_SATURATION);
            }
            case NETHERITE_POT -> {
                items[0] = new ItemStack(Material.NETHERITE_SWORD);
                items[1] = new ItemStack(Material.GOLDEN_APPLE, 1);
                fillPots(items);
                armor(items, Material.NETHERITE_BOOTS, Material.NETHERITE_LEGGINGS,
                        Material.NETHERITE_CHESTPLATE, Material.NETHERITE_HELMET);
                return new Kit(id, "netherite sword + full netherite + "
                        + potCount() + " splash healing + " + potExtras()
                        + " + 1 golden apple", items, 0,
                        NETHERITE_ARMOR, NETHERITE_TOUGHNESS, NETHERITE_KB_RESISTANCE,
                        FULL_HEALTH, FULL_FOOD, START_SATURATION);
            }
            case POT -> {
                items[0] = new ItemStack(Material.DIAMOND_SWORD);
                items[1] = new ItemStack(Material.GOLDEN_APPLE, 1);
                fillPots(items);
                armor(items, Material.DIAMOND_BOOTS, Material.DIAMOND_LEGGINGS,
                        Material.DIAMOND_CHESTPLATE, Material.DIAMOND_HELMET);
                return new Kit(id, "diamond sword + full diamond + "
                        + potCount() + " splash healing + " + potExtras()
                        + " + 1 golden apple", items, 0,
                        DIAMOND_ARMOR, DIAMOND_TOUGHNESS, 0f,
                        FULL_HEALTH, FULL_FOOD, START_SATURATION);
            }
            case DIAMOND -> {
                items[0] = new ItemStack(Material.DIAMOND_SWORD);
                items[1] = new ItemStack(Material.COBBLESTONE, 64);
                items[2] = new ItemStack(Material.GOLDEN_APPLE, 16);
                items[3] = new ItemStack(Material.ENDER_PEARL, 16);
                armor(items, Material.DIAMOND_BOOTS, Material.DIAMOND_LEGGINGS,
                        Material.DIAMOND_CHESTPLATE, Material.DIAMOND_HELMET);
                return new Kit(id, "diamond sword + full diamond + 16 golden apples"
                        + " + 16 ender pearls + 64 cobblestone", items, 0,
                        DIAMOND_ARMOR, DIAMOND_TOUGHNESS, 0f,
                        FULL_HEALTH, FULL_FOOD, START_SATURATION);
            }
            default -> {
                items[0] = new ItemStack(Material.DIAMOND_SWORD);
                items[1] = new ItemStack(Material.COBBLESTONE, 64);
                items[2] = new ItemStack(Material.GOLDEN_APPLE, 8);
                armor(items, Material.IRON_BOOTS, Material.IRON_LEGGINGS,
                        Material.IRON_CHESTPLATE, Material.IRON_HELMET);
                return new Kit(IRON, "diamond sword + full iron + 64 cobblestone"
                        + " + 8 golden apples", items, 0,
                        IRON_ARMOR, 0f, 0f, FULL_HEALTH, FULL_FOOD, START_SATURATION);
            }
        }
    }

    private static void armor(ItemStack[] items, Material feet, Material legs, Material chest,
                              Material head) {
        items[EdgeLoadout.ARMOR_FEET] = new ItemStack(feet);
        items[EdgeLoadout.ARMOR_LEGS] = new ItemStack(legs);
        items[EdgeLoadout.ARMOR_CHEST] = new ItemStack(chest);
        items[EdgeLoadout.ARMOR_HEAD] = new ItemStack(head);
    }

    private static void fillPots(ItemStack[] items) {
        for (int slot = POT_HOTBAR_START; slot < POT_STORAGE_END; slot++) {
            items[slot] = healingPot();
        }
        for (int slot = POT_HARMING_START; slot < POT_HARMING_END; slot++) {
            items[slot] = splashPot(PotionType.STRONG_HARMING);
        }
        for (int slot = POT_SWIFTNESS_START; slot < POT_SWIFTNESS_END; slot++) {
            items[slot] = splashPot(PotionType.STRONG_SWIFTNESS);
        }
        for (int slot = POT_STRENGTH_START; slot < POT_STRENGTH_END; slot++) {
            items[slot] = splashPot(PotionType.STRONG_STRENGTH);
        }
        for (int slot = POT_FIRE_RESISTANCE_START; slot < POT_FIRE_RESISTANCE_END; slot++) {
            items[slot] = splashPot(PotionType.LONG_FIRE_RESISTANCE);
        }
    }

    private static int potCount() {
        return POT_STORAGE_END - POT_HOTBAR_START;
    }

    private static String potExtras() {
        return (POT_HARMING_END - POT_HARMING_START) + " splash harming + "
                + (POT_SWIFTNESS_END - POT_SWIFTNESS_START) + " splash speed + "
                + (POT_STRENGTH_END - POT_STRENGTH_START) + " splash strength + "
                + (POT_FIRE_RESISTANCE_END - POT_FIRE_RESISTANCE_START)
                + " splash fire resistance";
    }

    private static ItemStack efficiencyPickaxe() {
        ItemStack pickaxe = new ItemStack(Material.NETHERITE_PICKAXE);
        pickaxe.addUnsafeEnchantment(Enchantment.EFFICIENCY, EFFICIENCY_LEVEL);
        return pickaxe;
    }

    private static ItemStack fireworks(int flight) {
        ItemStack rockets = new ItemStack(Material.FIREWORK_ROCKET, STACK);
        if (rockets.getItemMeta() instanceof FireworkMeta meta) {
            meta.setPower(flight);
            rockets.setItemMeta(meta);
        }
        return rockets;
    }

    private static ItemStack chargedCrossbow() {
        ItemStack crossbow = new ItemStack(Material.CROSSBOW);
        if (crossbow.getItemMeta() instanceof CrossbowMeta meta) {
            meta.addChargedProjectile(new ItemStack(Material.ARROW));
            crossbow.setItemMeta(meta);
        }
        return crossbow;
    }

    private static ItemStack windBurstMace() {
        ItemStack mace = new ItemStack(Material.MACE);
        mace.addUnsafeEnchantment(Enchantment.WIND_BURST, WIND_BURST_LEVEL);
        mace.addUnsafeEnchantment(Enchantment.BREACH, BREACH_LEVEL);
        return mace;
    }

    private static ItemStack multishotCrossbow() {
        ItemStack crossbow = chargedCrossbow();
        crossbow.addUnsafeEnchantment(Enchantment.MULTISHOT, MULTISHOT_LEVEL);
        return crossbow;
    }

    private static ItemStack weaknessArrows() {
        ItemStack arrows = new ItemStack(Material.TIPPED_ARROW, STACK);
        if (arrows.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(PotionType.WEAKNESS);
            arrows.setItemMeta(meta);
        }
        return arrows;
    }

    private static ItemStack totemShulker() {
        ItemStack box = new ItemStack(Material.SHULKER_BOX);
        if (box.getItemMeta() instanceof BlockStateMeta meta
                && meta.getBlockState() instanceof ShulkerBox state) {
            Inventory contents = state.getInventory();
            for (int slot = 0; slot < SHULKER_SLOTS; slot++) {
                contents.setItem(slot, new ItemStack(Material.TOTEM_OF_UNDYING));
            }
            meta.setBlockState(state);
            box.setItemMeta(meta);
        }
        return box;
    }

    private static ItemStack healingPot() {
        return splashPot(PotionType.STRONG_HEALING);
    }

    private static ItemStack splashPot(PotionType potionType) {
        ItemStack pot = new ItemStack(Material.SPLASH_POTION, 1);
        if (pot.getItemMeta() instanceof PotionMeta meta) {
            meta.setBasePotionType(potionType);
            pot.setItemMeta(meta);
        }
        return pot;
    }
}
