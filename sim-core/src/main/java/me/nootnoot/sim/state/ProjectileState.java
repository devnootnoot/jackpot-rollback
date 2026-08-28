package me.nootnoot.sim.state;

public final class ProjectileState {
    public static final int TYPE_ARROW = 0;
    public static final int TYPE_PEARL = 1;
    public static final int TYPE_SNOWBALL = 2;
    public static final int TYPE_EGG = 3;
    public static final int TYPE_FIREWORK = 4;
    public static final int TYPE_SPLASH_POTION = 5;
    public static final int TYPE_XP_BOTTLE = 6;
    public static final int TYPE_WIND_CHARGE = 7;

    public int id;
    public int type;
    public int owner;
    public double x;
    public double y;
    public double z;
    public double vx;
    public double vy;
    public double vz;
    public float damage;
    public int life;
    public boolean fresh = true;
    public boolean stuck;
    public int shakeTime;
    public boolean dead;
    public boolean leftOwner;
    public boolean claimSpent;

    public int effect0;
    public int effect1;
    public int effect2;
    public int effect3;

    public int bowEnchants;

    public boolean infiniteArrow;
    public int arrowItemId;
    public int arrowEntry;

    public int effect(int i) {
        return switch (i) {
            case 0 -> effect0;
            case 1 -> effect1;
            case 2 -> effect2;
            default -> effect3;
        };
    }

    public ProjectileState copy() {
        ProjectileState p = new ProjectileState();
        p.id = id;
        p.type = type;
        p.owner = owner;
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.damage = damage;
        p.life = life;
        p.fresh = fresh;
        p.stuck = stuck;
        p.shakeTime = shakeTime;
        p.dead = dead;
        p.leftOwner = leftOwner;
        p.claimSpent = claimSpent;
        p.effect0 = effect0;
        p.effect1 = effect1;
        p.effect2 = effect2;
        p.effect3 = effect3;
        p.bowEnchants = bowEnchants;
        p.infiniteArrow = infiniteArrow;
        p.arrowItemId = arrowItemId;
        p.arrowEntry = arrowEntry;
        return p;
    }
}
