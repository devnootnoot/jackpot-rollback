package me.nootnoot.sim.state;

public final class CrystalState {
    public int id;
    public int owner;
    public int bx;
    public int by;
    public int bz;

    public CrystalState copy() {
        CrystalState c = new CrystalState();
        c.id = id;
        c.owner = owner;
        c.bx = bx;
        c.by = by;
        c.bz = bz;
        return c;
    }
}
