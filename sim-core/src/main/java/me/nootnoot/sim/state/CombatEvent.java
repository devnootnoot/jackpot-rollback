package me.nootnoot.sim.state;

public record CombatEvent(int type, int attacker, int victim, boolean crit, int kind) {
    public static final int HIT = 0;
    public static final int DEATH = 1;
    public static final int SWING = 2;
    public static final int BLOCK = 3;
    public static final int TOTEM = 4;
    public static final int EXPLOSION = 5;
    public static final int FLUID_SOLIDIFY = 6;
    public static final int POTION_SPLASH = 7;
    public static final int FIRE_EXTINGUISH = 8;
    public static final int BUCKET_FILL = 9;
    public static final int BUCKET_EMPTY = 10;
    public static final int ITEM_BREAK = 11;

    public static final int HIT_WEAK = 0;
    public static final int HIT_STRONG = 1;
    public static final int HIT_KNOCKBACK = 2;
    public static final int HIT_CRIT = 3;
    public static final int HIT_ARROW = 4;
    public static final int HIT_FIRE = 5;
    public static final int HIT_FALL = 6;
    public static final int HIT_SMASH = 7;
    public static final int HIT_SMASH_HEAVY = 8;
    public static final int HIT_EXPLOSION = 9;

    public CombatEvent(int type, int attacker, int victim, boolean crit) {
        this(type, attacker, victim, crit, HIT_WEAK);
    }
}
