package me.nootnoot.sim.state;

public final class Container {
    public static final int CELLS = 27;

    public final int[] entry = new int[CELLS];
    public final int[] count = new int[CELLS];
    public final int[] damage = new int[CELLS];

    public Container copy() {
        Container c = new Container();
        System.arraycopy(entry, 0, c.entry, 0, CELLS);
        System.arraycopy(count, 0, c.count, 0, CELLS);
        System.arraycopy(damage, 0, c.damage, 0, CELLS);
        return c;
    }
}
