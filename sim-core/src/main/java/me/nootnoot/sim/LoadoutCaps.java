package me.nootnoot.sim;

import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;

public final class LoadoutCaps {
    public static final float MAX_ATTACK_DAMAGE = ItemDict.MAX_ATTACK_DAMAGE;
    public static final float MAX_ATTACK_SPEED = ItemDict.MAX_ATTACK_SPEED;
    public static final int MAX_KNOCKBACK = ItemDict.MAX_KNOCKBACK;
    public static final int MAX_SHARPNESS = ItemDict.MAX_SHARPNESS;
    public static final int MAX_FIRE_ASPECT = ItemDict.MAX_FIRE_ASPECT;
    public static final int MAX_PUNCH = ItemDict.MAX_PUNCH;
    public static final int MAX_FLAME = ItemDict.MAX_FLAME;
    public static final int MAX_BREACH = ItemDict.MAX_BREACH;
    public static final int MAX_DENSITY = ItemDict.MAX_DENSITY;
    public static final int MAX_WIND_BURST = ItemDict.MAX_WIND_BURST;
    public static final int MAX_POWER = ItemDict.MAX_POWER;
    public static final int MAX_QUICK_CHARGE = ItemDict.MAX_QUICK_CHARGE;
    public static final int MIN_CROSSBOW_LOAD = ItemDict.MIN_CROSSBOW_LOAD;
    public static final int MAX_MULTISHOT = ItemDict.MAX_MULTISHOT;
    public static final int MAX_PIERCING = ItemDict.MAX_PIERCING;
    public static final int MAX_EFFICIENCY = ItemDict.MAX_EFFICIENCY;
    public static final int MAX_ARMOR_POINTS_PER_PIECE = ItemDict.MAX_ARMOR_POINTS_PER_PIECE;
    public static final float MAX_TOUGHNESS_PER_PIECE = ItemDict.MAX_TOUGHNESS_PER_PIECE;
    public static final float MAX_KB_RESISTANCE_PER_PIECE = ItemDict.MAX_KB_RESISTANCE_PER_PIECE;
    public static final float MAX_KB_RESISTANCE_TOTAL = ItemDict.MAX_KB_RESISTANCE_TOTAL;
    public static final int MAX_EPF_LEVEL = ItemDict.MAX_EPF_LEVEL;
    public static final int MAX_FEATHER_FALLING = ItemDict.MAX_FEATHER_FALLING;
    public static final int MAX_EFFECT_AMPLIFIER = ItemDict.MAX_EFFECT_AMPLIFIER;
    public static final int MAX_EFFECT_DURATION = ItemDict.MAX_EFFECT_DURATION;
    public static final int MAX_FIREWORK_FLIGHT = ItemDict.MAX_FIREWORK_FLIGHT;
    public static final int MAX_FOOD_NUTRITION = ItemDict.MAX_FOOD_NUTRITION;
    public static final float MAX_FOOD_SATURATION = ItemDict.MAX_FOOD_SATURATION;
    public static final int MIN_FOOD_EAT_TICKS = ItemDict.MIN_FOOD_EAT_TICKS;
    public static final int MAX_STACK = ItemDict.MAX_STACK;

    private LoadoutCaps() {
    }

    public static void seal(GameState state) {
        if (state == null || state.players == null || state.players.length < 2) {
            return;
        }
        if (state.dict == null) {
            state.dict = ItemDict.empty();
        }
        if (state.blockProps == null) {
            state.blockProps = me.nootnoot.sim.state.BlockProps.empty();
        }
        for (PlayerState p : state.players) {
            if (p == null) {
                continue;
            }
            Loadout.recomputeDerived(state, p);
        }
    }
}
