package me.nootnoot.sim.state;

public final class ItemEntityState {
    public static final int NEUTRAL_OWNER = -1;

    public int id;

    public int owner = NEUTRAL_OWNER;

    public int dropUid;
    public int entry;
    public int itemId;
    public int count;
    public int damage;
    public double x;
    public double y;
    public double z;
    public double vx;
    public double vy;
    public double vz;
    public int pickupDelay;
    public int life;
    public boolean dead;

    public ItemEntityState copy() {
        ItemEntityState e = new ItemEntityState();
        e.id = id;
        e.owner = owner;
        e.dropUid = dropUid;
        e.entry = entry;
        e.itemId = itemId;
        e.count = count;
        e.damage = damage;
        e.x = x;
        e.y = y;
        e.z = z;
        e.vx = vx;
        e.vy = vy;
        e.vz = vz;
        e.pickupDelay = pickupDelay;
        e.life = life;
        e.dead = dead;
        return e;
    }
}
