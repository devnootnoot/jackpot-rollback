package me.nootnoot.edge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import me.nootnoot.sim.MatchSetupFrame0Decoder;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class EdgeLoadout {

    public static final int SLOTS = MatchSetupFrame0Decoder.INVENTORY_SLOTS;
    public static final int HOTBAR_SLOTS = 9;
    public static final int ARMOR_FEET = 36;
    public static final int ARMOR_LEGS = 37;
    public static final int ARMOR_CHEST = 38;
    public static final int ARMOR_HEAD = 39;
    public static final int OFF_HAND = 40;

    private final MatchSetupFrame0Decoder.Frame0 frame0;
    private final ItemStack[][] items;
    private final int[] selectedSlot;
    private final PlayerState[] seeded;
    private final int unreadable;

    public record Snapshot(ItemStack[] contents, int heldSlot, int foodLevel, float saturation,
                           double maxHealth, double health, Collection<PotionEffect> effects) {
    }

    private EdgeLoadout(MatchSetupFrame0Decoder.Frame0 frame0, ItemStack[][] items,
                        int unreadable) {
        this.frame0 = frame0;
        this.items = items;
        this.unreadable = unreadable;
        this.selectedSlot = frame0.selectedSlot().clone();
        this.seeded = new PlayerState[2];
        for (int i = 0; i < 2; i++) {
            this.seeded[i] = frame0.state().players[i].copy();
        }
    }

    public static EdgeLoadout decode(byte[] setup) {
        MatchSetupFrame0Decoder.Frame0 f = MatchSetupFrame0Decoder.decode(setup,
                EdgeLoadout::probeCrossbow);
        int unreadable = 0;
        ItemStack[][] items = new ItemStack[2][SLOTS];
        for (int i = 0; i < 2; i++) {
            for (int s = 0; s < SLOTS; s++) {
                byte[] nbt = f.inventory()[i][s];
                if (nbt == null || nbt.length == 0) {
                    continue;
                }
                ItemStack stack = read(nbt);
                if (stack == null) {
                    unreadable++;
                    continue;
                }
                items[i][s] = stack;
            }
        }
        return new EdgeLoadout(f, items, unreadable);
    }

    private static boolean probeCrossbow(byte[] nbt) {
        if (nbt == null || nbt.length == 0) {
            return false;
        }
        return EdgeHeldItems.crossbowCharged(read(nbt));
    }

    private static ItemStack read(byte[] nbt) {
        try {
            return ItemStack.deserializeBytes(nbt);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public GameState state() {
        return frame0.state();
    }

    public double groundY() {
        return frame0.arenaGroundY();
    }

    public int rounds() {
        return frame0.state().roundsTarget;
    }

    public int unreadable() {
        return unreadable;
    }

    public double spawnX(int slot) {
        return seeded[slot].x;
    }

    public double spawnY(int slot) {
        return seeded[slot].y;
    }

    public double spawnZ(int slot) {
        return seeded[slot].z;
    }

    public float spawnYaw(int slot) {
        return seeded[slot].yaw;
    }

    public float spawnPitch(int slot) {
        return seeded[slot].pitch;
    }

    public MatchSetupFrame0Decoder.Identity identity(int slot) {
        return frame0.identities()[slot];
    }

    public int selectedSlot(int slot) {
        int sel = selectedSlot[slot];
        return sel < 0 || sel >= HOTBAR_SLOTS ? 0 : sel;
    }

    public ItemStack item(int slot, int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= SLOTS) {
            return null;
        }
        ItemStack stack = items[slot][inventorySlot];
        return stack == null ? null : stack.clone();
    }

    public boolean offhandShield(int slot) {
        if (slot < 0 || slot > 1) {
            return false;
        }
        ItemStack stack = items[slot][OFF_HAND];
        return stack != null && stack.getType() == Material.SHIELD;
    }

    public float meleeSpeed(int slot, int hotbarSlot) {
        float speed = frame0.state().dict.meleeSpeed(seededEntry(slot, hotbarSlot));
        return speed > 0f ? speed : EdgeHeldItems.FIST_SPEED;
    }

    public float coreMeleeDamage(int slot, int hotbarSlot) {
        return frame0.state().dict.meleeDamage(seededEntry(slot, hotbarSlot));
    }

    private int seededEntry(int slot, int hotbarSlot) {
        if (slot < 0 || slot > 1 || hotbarSlot < 0 || hotbarSlot >= HOTBAR_SLOTS) {
            return ItemDict.NONE;
        }
        PlayerState p = seeded[slot];
        return p.slotCount[hotbarSlot] > 0 ? p.slotEntry[hotbarSlot] : ItemDict.NONE;
    }

    public EdgeHeldItems.Classified classified(int slot, int hotbarSlot) {
        int entry = seededEntry(slot, hotbarSlot);
        if (entry == ItemDict.NONE) {
            return EdgeHeldItems.Classified.NONE;
        }
        ItemDict d = frame0.state().dict;
        return new EdgeHeldItems.Classified(d.useKind(entry), d.foodNutrition(entry),
                d.foodSaturation(entry), d.alwaysEdible(entry), d.foodEatTicks(entry),
                d.fireworkFlight(entry));
    }

    public Snapshot apply(Player player, int slot) {
        PlayerInventory inv = player.getInventory();
        Snapshot before = new Snapshot(inv.getContents(), inv.getHeldItemSlot(),
                player.getFoodLevel(), player.getSaturation(), maxHealth(player),
                player.getHealth(), new ArrayList<>(player.getActivePotionEffects()));

        PlayerState p = seeded[slot];
        setMaxHealth(player, p.maxHealth);
        for (int s = 0; s < SLOTS; s++) {
            inv.setItem(s, item(slot, s));
        }
        inv.setHeldItemSlot(selectedSlot(slot));
        player.setFoodLevel(clampFood(p.food));
        player.setSaturation(Math.max(0f, p.saturation));
        player.setHealth(Math.max(0.5, Math.min(maxHealth(player), p.health)));
        clearEffects(player);
        for (PotionEffect effect : effectsOf(p)) {
            player.addPotionEffect(effect);
        }
        player.updateInventory();
        return before;
    }

    public void applyInventory(Player player, int slot) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        for (int s = 0; s < SLOTS; s++) {
            inv.setItem(s, item(slot, s));
        }
        inv.setHeldItemSlot(selectedSlot(slot));
        player.setItemOnCursor(null);
        player.updateInventory();
    }

    public static void restore(Player player, Snapshot before) {
        if (player == null || before == null || !player.isOnline()) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        inv.setContents(before.contents());
        inv.setHeldItemSlot(Math.max(0, Math.min(HOTBAR_SLOTS - 1, before.heldSlot())));
        setMaxHealth(player, (float) before.maxHealth());
        player.setFoodLevel(before.foodLevel());
        player.setSaturation(before.saturation());
        player.setHealth(Math.max(0.0, Math.min(before.maxHealth(), before.health())));
        clearEffects(player);
        for (PotionEffect effect : before.effects()) {
            player.addPotionEffect(effect);
        }
        player.updateInventory();
    }

    private static void clearEffects(Player player) {
        for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
            player.removePotionEffect(effect.getType());
        }
    }

    private static List<PotionEffect> effectsOf(PlayerState p) {
        List<PotionEffect> out = new ArrayList<>();
        for (int id = 1; id < Effects.COUNT; id++) {
            int ticks = p.effectTicks[id];
            if (ticks <= 0) {
                continue;
            }
            PotionEffectType type = bukkitEffect(id);
            if (type == null || type.isInstant()) {
                continue;
            }
            out.add(new PotionEffect(type, ticks, Math.max(0, p.effectAmp[id]), false, false, true));
        }
        return out;
    }

    public static PotionEffectType bukkitEffect(int id) {
        return switch (id) {
            case Effects.SPEED -> PotionEffectType.SPEED;
            case Effects.SLOWNESS -> PotionEffectType.SLOWNESS;
            case Effects.STRENGTH -> PotionEffectType.STRENGTH;
            case Effects.INSTANT_HEALTH -> PotionEffectType.INSTANT_HEALTH;
            case Effects.INSTANT_DAMAGE -> PotionEffectType.INSTANT_DAMAGE;
            case Effects.JUMP_BOOST -> PotionEffectType.JUMP_BOOST;
            case Effects.REGENERATION -> PotionEffectType.REGENERATION;
            case Effects.RESISTANCE -> PotionEffectType.RESISTANCE;
            case Effects.POISON -> PotionEffectType.POISON;
            case Effects.WITHER -> PotionEffectType.WITHER;
            case Effects.ABSORPTION -> PotionEffectType.ABSORPTION;
            case Effects.WEAKNESS -> PotionEffectType.WEAKNESS;
            case Effects.SATURATION -> PotionEffectType.SATURATION;
            case Effects.HEALTH_BOOST -> PotionEffectType.HEALTH_BOOST;
            case Effects.SLOW_FALLING -> PotionEffectType.SLOW_FALLING;
            case Effects.FIRE_RESISTANCE -> PotionEffectType.FIRE_RESISTANCE;
            default -> null;
        };
    }

    private static int clampFood(float food) {
        int value = Math.round(food);
        return value < 0 ? 0 : Math.min(20, value);
    }

    private static double maxHealth(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getBaseValue() : 20.0;
    }

    private static void setMaxHealth(Player player, float value) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null || value <= 0f || attribute.getBaseValue() == value) {
            return;
        }
        attribute.setBaseValue(value);
    }

    public String describe() {
        return "rounds=" + rounds() + " ground-y=" + groundY()
                + " p0=" + identity(0).name() + " p1=" + identity(1).name()
                + " held=" + selectedSlot(0) + "/" + selectedSlot(1)
                + (unreadable > 0 ? " unreadable-items=" + unreadable : "");
    }
}
