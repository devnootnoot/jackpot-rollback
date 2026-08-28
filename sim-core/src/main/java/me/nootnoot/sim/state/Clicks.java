package me.nootnoot.sim.state;

public record Clicks(int attack, int use, int drop, int inv, int swap) {
    public static final int MAX = 7;

    public static final Clicks NONE = new Clicks(0, 0, 0, 0, 0);

    public Clicks {
        attack = clamp(attack);
        use = clamp(use);
        drop = clamp(drop);
        inv = clamp(inv);
        swap = clamp(swap);
    }

    public static int clamp(int v) {
        return v < 0 ? 0 : Math.min(MAX, v);
    }

    public boolean none() {
        return attack == 0 && use == 0 && drop == 0 && inv == 0 && swap == 0;
    }

    public Clicks withAttack(int n) {
        return new Clicks(n, use, drop, inv, swap);
    }

    public Clicks withUse(int n) {
        return new Clicks(attack, n, drop, inv, swap);
    }

    public Clicks withDrop(int n) {
        return new Clicks(attack, use, n, inv, swap);
    }

    public Clicks withInv(int n) {
        return new Clicks(attack, use, drop, n, swap);
    }

    public Clicks withSwap(int n) {
        return new Clicks(attack, use, drop, inv, n);
    }
}
