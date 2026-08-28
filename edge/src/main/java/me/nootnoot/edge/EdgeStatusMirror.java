package me.nootnoot.edge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class EdgeStatusMirror {

    private static final float MIN_SHOWN_HEALTH = 0.5f;
    private static final double HEALTH_EPSILON = 0.01;
    private static final float ABSORPTION_EPSILON = 0.01f;
    private static final float SATURATION_EPSILON = 0.05f;
    private static final double ATTACK_SPEED_EPSILON = 0.001;
    private static final double ATTACK_RANGE_EPSILON = 0.001;
    private static final int EFFECT_REFRESH_TICKS = 40;
    private static final int FIRE_CRACKLE_PERIOD = 12;
    private static final float FIRE_CRACKLE_VOLUME = 0.6f;

    private final Player player;
    private final Logger log;

    private final boolean[] shownActive = new boolean[Effects.COUNT];
    private final int[] shownAmp = new int[Effects.COUNT];

    private int refreshTick;
    private double shownHealth = -1.0;
    private double shownAbsorption = -1.0;
    private int shownFood = -1;
    private float shownSaturation = -1.0f;
    private boolean shownBurning;
    private double ownAttackSpeedBase = Double.NaN;
    private boolean wroteAttackSpeed;
    private double shownAttackSpeed = Double.NaN;
    private double ownAttackRangeBase = Double.NaN;
    private boolean wroteAttackRange;
    private double shownAttackRange = Double.NaN;
    private int shownShieldDisabled;
    private int shownPearlCooldown;

    public EdgeStatusMirror(Player player, Logger log) {
        this.player = player;
        this.log = log;
        EdgeAttackSpeed.repair(player, log, "entering an edge match", false);
        AttributeInstance attribute = player.getAttribute(Attribute.ATTACK_SPEED);
        this.ownAttackSpeedBase = attribute != null ? attribute.getBaseValue() : Double.NaN;
        AttributeInstance range = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        this.ownAttackRangeBase = range != null ? range.getBaseValue() : Double.NaN;
    }

    public void apply(ItemDict dict, PlayerState mine) {
        if (!player.isOnline() || mine == null) {
            return;
        }
        mirrorAttackSpeed(dict, mine);
        mirrorAttackRange(dict, mine);
        mirrorHealth(mine);
        mirrorHunger(mine);
        mirrorAbsorption(mine);
        mirrorFire(mine);
        mirrorShieldCooldown(mine);
        mirrorPearlCooldown(mine);
        mirrorEffects(mine);
    }

    private void mirrorHealth(PlayerState mine) {
        if (mine.dead) {
            shownHealth = -1.0;
            return;
        }
        double cap = maxHealth();
        double shown = Math.max(MIN_SHOWN_HEALTH, Math.min(cap, mine.health));
        if (Math.abs(shownHealth - shown) <= HEALTH_EPSILON
                && Math.abs(player.getHealth() - shown) <= HEALTH_EPSILON) {
            return;
        }
        shownHealth = shown;
        try {
            player.setHealth(shown);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void mirrorHunger(PlayerState mine) {
        int food = Math.max(0, Math.min(20, (int) mine.food));
        float saturation = Math.max(0.0f, mine.saturation);
        if (food != shownFood) {
            shownFood = food;
            player.setFoodLevel(food);
        }
        if (Math.abs(saturation - shownSaturation) > SATURATION_EPSILON) {
            shownSaturation = saturation;
            player.setSaturation(saturation);
        }
    }

    private void mirrorAbsorption(PlayerState mine) {
        double absorption = Math.max(0.0f, mine.absorption);
        if (Math.abs(absorption - shownAbsorption) <= ABSORPTION_EPSILON) {
            return;
        }
        shownAbsorption = absorption;
        player.setAbsorptionAmount(absorption);
    }

    private void mirrorFire(PlayerState mine) {
        boolean burning = mine.fireTicks > 0;
        if (burning == shownBurning && !burning) {
            return;
        }
        shownBurning = burning;
        player.setFireTicks(Math.max(0, mine.fireTicks));
        if (burning && mine.fireTicks % FIRE_CRACKLE_PERIOD == 0) {
            player.playSound(player.getLocation(), Sound.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS,
                    FIRE_CRACKLE_VOLUME, ThreadLocalRandom.current().nextFloat() * 0.7f + 0.3f);
        }
    }

    private void mirrorShieldCooldown(PlayerState mine) {
        if (mine.shieldDisabled > 0 && shownShieldDisabled <= 0) {
            player.setCooldown(Material.SHIELD, mine.shieldDisabled);
        }
        shownShieldDisabled = mine.shieldDisabled;
    }

    private void mirrorPearlCooldown(PlayerState mine) {
        int left = mine.useCooldown[Combat.USE_PEARL];
        if (left > 0 && shownPearlCooldown <= 0) {
            player.setCooldown(Material.ENDER_PEARL, left);
        }
        shownPearlCooldown = left;
    }

    private void mirrorEffects(PlayerState mine) {
        boolean refresh = ++refreshTick >= EFFECT_REFRESH_TICKS;
        if (refresh) {
            refreshTick = 0;
        }
        for (int id = 1; id < Effects.COUNT; id++) {
            PotionEffectType type = EdgeLoadout.bukkitEffect(id);
            if (type == null || type.isInstant()) {
                continue;
            }
            boolean active = mine.effectTicks[id] > 0;
            int amp = Math.max(0, mine.effectAmp[id]);
            if (!active) {
                if (shownActive[id]) {
                    shownActive[id] = false;
                    shownAmp[id] = 0;
                    player.removePotionEffect(type);
                }
                continue;
            }
            if (shownActive[id] && shownAmp[id] == amp && !refresh) {
                continue;
            }
            shownActive[id] = true;
            shownAmp[id] = amp;
            player.addPotionEffect(new PotionEffect(type, mine.effectTicks[id], amp, false, true, true));
        }
    }

    public void reset() {
        restoreAttackSpeed();
        restoreAttackRange();
        if (!player.isOnline()) {
            return;
        }
        restoreStep("potion effects", this::clearEffects);
        restoreStep("absorption", () -> player.setAbsorptionAmount(0.0));
        restoreStep("fire", () -> player.setFireTicks(0));
    }

    private void clearEffects() {
        List<PotionEffect> active = new ArrayList<>(player.getActivePotionEffects());
        for (PotionEffect effect : active) {
            player.removePotionEffect(effect.getType());
        }
    }

    private void restoreStep(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            if (log != null) {
                log.log(Level.SEVERE, "[edge] could not restore " + what + " for "
                        + player.getName() + " when the match ended - the remaining restores still"
                        + " ran", t);
            }
        }
    }

    private void mirrorAttackSpeed(ItemDict dict, PlayerState mine) {
        AttributeInstance attribute = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attribute == null || dict == null) {
            return;
        }
        double want = simMeleeSpeed(dict, mine);
        double live = attribute.getValue();
        if (Math.abs(shownAttackSpeed - want) <= ATTACK_SPEED_EPSILON
                && Math.abs(live - want) <= ATTACK_SPEED_EPSILON) {
            return;
        }
        double base = attribute.getBaseValue();
        wroteAttackSpeed = true;
        attribute.setBaseValue(correctedBase(base, live, want));
        shownAttackSpeed = want;
    }

    private void mirrorAttackRange(ItemDict dict, PlayerState mine) {
        AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        if (attribute == null || dict == null) {
            return;
        }
        double want = simAttackRange(dict, mine);
        double live = attribute.getValue();
        if (Math.abs(shownAttackRange - want) <= ATTACK_RANGE_EPSILON
                && Math.abs(live - want) <= ATTACK_RANGE_EPSILON) {
            return;
        }
        double base = attribute.getBaseValue();
        wroteAttackRange = true;
        attribute.setBaseValue(correctedBase(base, live, want));
        shownAttackRange = want;
    }

    private void restoreAttackRange() {
        double base = ownAttackRangeBase;
        boolean wrote = wroteAttackRange;
        ownAttackRangeBase = Double.NaN;
        wroteAttackRange = false;
        shownAttackRange = Double.NaN;
        restoreStep("the entity-interaction-range base this match captured", () -> {
            AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
            if (attribute != null && wrote && !Double.isNaN(base)) {
                attribute.setBaseValue(base);
            }
        });
    }

    static double simAttackRange(ItemDict dict, PlayerState mine) {
        int entry = heldEntry(mine);
        double reach = dict.attackMaxRange(entry) + dict.attackHitboxMargin(entry);
        return reach > 0.0 ? reach : ItemDict.DEFAULT_ATTACK_RANGE;
    }

    static double correctedBase(double base, double live, double want) {
        return base + (want - live);
    }

    private void restoreAttackSpeed() {
        double base = ownAttackSpeedBase;
        boolean wrote = wroteAttackSpeed;
        ownAttackSpeedBase = Double.NaN;
        wroteAttackSpeed = false;
        shownAttackSpeed = Double.NaN;
        restoreStep("the attack-speed base this match captured", () -> {
            AttributeInstance attribute = player.getAttribute(Attribute.ATTACK_SPEED);
            if (attribute != null && wrote && !Double.isNaN(base)) {
                attribute.setBaseValue(base);
            }
        });
        restoreStep("the attack-speed sanity sweep",
                () -> EdgeAttackSpeed.repair(player, log, "leaving an edge match", false));
    }

    static double simMeleeSpeed(ItemDict dict, PlayerState mine) {
        float speed = dict.meleeSpeed(heldEntry(mine));
        return speed > 0f ? speed : EdgeHeldItems.FIST_SPEED;
    }

    static int heldEntry(PlayerState mine) {
        int slot = mine.heldSlot;
        return slot >= 0 && slot < ItemDict.HOTBAR && mine.slotCount[slot] > 0
                ? mine.slotEntry[slot]
                : ItemDict.NONE;
    }

    private double maxHealth() {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        double value = attribute != null ? attribute.getValue() : 20.0;
        return value <= 0.0 ? 20.0 : value;
    }
}
