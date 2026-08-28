package me.nootnoot.edge;

import java.util.List;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.ItemDict;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class EdgeHeldItems {

    public static final float FIST_DAMAGE = 1f;
    public static final float FIST_SPEED = 4f;

    private static final int CROSSBOW_BASE_LOAD_TICKS = 25;
    private static final int CROSSBOW_QUICK_CHARGE_TICKS = 5;

    private static final int MAX_EFFECTS = 4;

    private static final int GOLDEN_APPLE_NUTRITION = 4;
    private static final float GOLDEN_APPLE_SATURATION = 9.6f;

    public record Classified(int useKind, int foodNutrition, float foodSaturation,
                             boolean alwaysEdible, int foodEatTicks, int fireworkFlight) {

        public static final Classified NONE = new Classified(0, 0, 0f, false, 0, 0);
    }

    private EdgeHeldItems() {
    }

    public static Classified classify(ItemStack stack, Classified seeded) {
        int live = useKindOf(stack);
        if (live == Combat.USE_NONE) {
            return Classified.NONE;
        }
        if (live == Combat.USE_FIREWORK) {
            return new Classified(live, 0, 0f, false, 0, fireworkFlight(stack));
        }
        if (live == seeded.useKind()) {
            return seeded;
        }
        if (live != Combat.USE_FOOD) {
            return new Classified(live, 0, 0f, false, 0, 0);
        }
        Material type = stack.getType();
        if (type == Material.GOLDEN_APPLE || type == Material.ENCHANTED_GOLDEN_APPLE) {
            return new Classified(Combat.USE_FOOD, GOLDEN_APPLE_NUTRITION,
                    GOLDEN_APPLE_SATURATION, true, 0, 0);
        }
        return new Classified(Combat.USE_FOOD, 0, 0f, drinkable(type), 0, 0);
    }

    public static int useKindOf(ItemStack stack) {
        if (empty(stack)) {
            return Combat.USE_NONE;
        }
        Material type = stack.getType();
        if (type == Material.SHIELD) {
            return Combat.USE_SHIELD;
        }
        if (type == Material.ENDER_PEARL) {
            return Combat.USE_PEARL;
        }
        if (type == Material.SNOWBALL) {
            return Combat.USE_SNOWBALL;
        }
        if (type == Material.EGG) {
            return Combat.USE_EGG;
        }
        if (type == Material.BOW) {
            return Combat.USE_BOW;
        }
        if (type == Material.CROSSBOW) {
            return Combat.USE_CROSSBOW;
        }
        if (type == Material.FIREWORK_ROCKET) {
            return Combat.USE_FIREWORK;
        }
        if (type == Material.SPLASH_POTION || type == Material.LINGERING_POTION) {
            return Combat.USE_SPLASH_POTION;
        }
        if (type == Material.EXPERIENCE_BOTTLE) {
            return Combat.USE_XP_BOTTLE;
        }
        if (type == Material.WIND_CHARGE) {
            return Combat.USE_WIND_CHARGE;
        }
        if (interactsWithWorld(type) || equippable(type)) {
            return Combat.USE_NONE;
        }
        if (drinkable(type) || type.isEdible()) {
            return Combat.USE_FOOD;
        }
        return Combat.USE_NONE;
    }

    public static boolean interactsWithWorld(Material type) {
        if (type.isAir()) {
            return false;
        }
        return type.isBlock() || type == Material.WATER_BUCKET || type == Material.LAVA_BUCKET
                || type == Material.BUCKET || type == Material.POWDER_SNOW_BUCKET
                || type == Material.END_CRYSTAL;
    }

    public static boolean equippable(Material type) {
        if (type == Material.ELYTRA || type == Material.TURTLE_HELMET) {
            return true;
        }
        String name = type.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }

    public static boolean drinkable(Material type) {
        return type == Material.POTION || type == Material.MILK_BUCKET
                || type == Material.HONEY_BOTTLE;
    }

    public static boolean continuousUse(int useKind) {
        return Combat.continuousUse(useKind);
    }

    public static int fireworkFlight(ItemStack stack) {
        if (empty(stack) || stack.getType() != Material.FIREWORK_ROCKET) {
            return 0;
        }
        if (!(stack.getItemMeta() instanceof FireworkMeta meta) || !meta.hasPower()) {
            return 0;
        }
        return Math.max(0, meta.getPower());
    }

    public static boolean crossbowCharged(ItemStack stack) {
        return !empty(stack) && stack.getType() == Material.CROSSBOW
                && stack.getItemMeta() instanceof CrossbowMeta meta
                && meta.hasChargedProjectiles();
    }

    public static ItemStack charged(ItemStack stack, boolean loaded) {
        if (stack == null || !(stack.getItemMeta() instanceof CrossbowMeta meta)) {
            return stack;
        }
        if (meta.hasChargedProjectiles() == loaded) {
            return stack;
        }
        if (loaded) {
            meta.addChargedProjectile(new ItemStack(Material.ARROW));
        } else {
            meta.setChargedProjectiles(null);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    public static int simEffect(PotionEffectType type) {
        if (type == null) {
            return Effects.NONE;
        }
        if (type.equals(PotionEffectType.SPEED)) {
            return Effects.SPEED;
        }
        if (type.equals(PotionEffectType.SLOWNESS)) {
            return Effects.SLOWNESS;
        }
        if (type.equals(PotionEffectType.STRENGTH)) {
            return Effects.STRENGTH;
        }
        if (type.equals(PotionEffectType.INSTANT_HEALTH)) {
            return Effects.INSTANT_HEALTH;
        }
        if (type.equals(PotionEffectType.INSTANT_DAMAGE)) {
            return Effects.INSTANT_DAMAGE;
        }
        if (type.equals(PotionEffectType.JUMP_BOOST)) {
            return Effects.JUMP_BOOST;
        }
        if (type.equals(PotionEffectType.REGENERATION)) {
            return Effects.REGENERATION;
        }
        if (type.equals(PotionEffectType.RESISTANCE)) {
            return Effects.RESISTANCE;
        }
        if (type.equals(PotionEffectType.POISON)) {
            return Effects.POISON;
        }
        if (type.equals(PotionEffectType.WITHER)) {
            return Effects.WITHER;
        }
        if (type.equals(PotionEffectType.ABSORPTION)) {
            return Effects.ABSORPTION;
        }
        if (type.equals(PotionEffectType.WEAKNESS)) {
            return Effects.WEAKNESS;
        }
        if (type.equals(PotionEffectType.SATURATION)) {
            return Effects.SATURATION;
        }
        if (type.equals(PotionEffectType.HEALTH_BOOST)) {
            return Effects.HEALTH_BOOST;
        }
        if (type.equals(PotionEffectType.SLOW_FALLING)) {
            return Effects.SLOW_FALLING;
        }
        if (type.equals(PotionEffectType.FIRE_RESISTANCE)) {
            return Effects.FIRE_RESISTANCE;
        }
        return Effects.NONE;
    }

    private static boolean arrow(ItemStack stack) {
        if (empty(stack)) {
            return false;
        }
        Material type = stack.getType();
        return type == Material.ARROW || type == Material.SPECTRAL_ARROW
                || type == Material.TIPPED_ARROW;
    }

    private static int level(ItemStack stack, Enchantment enchantment) {
        return stack.getEnchantmentLevel(enchantment);
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }
}
