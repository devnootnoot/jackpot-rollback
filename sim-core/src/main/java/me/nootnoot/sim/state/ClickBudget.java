package me.nootnoot.sim.state;

import me.nootnoot.sim.SimProbe;

public final class ClickBudget {
    private boolean loaded;

    private int tick;

    public int attack;
    public int use;
    public int drop;
    public int inv;
    public int swap;

    public boolean attackEdge;
    public boolean useUncounted;

    public int attackDrained;

    public void load(int now, Clicks c, int useCap, boolean edge, boolean synthetic) {
        loaded = true;
        tick = now;
        attack = synthetic ? 0 : c.attack();
        use = synthetic ? 0 : Math.min(c.use(), useCap);
        drop = synthetic ? 0 : c.drop();
        inv = synthetic ? 0 : c.inv();
        swap = synthetic ? 0 : c.swap();
        attackEdge = edge && attack == 0 && !synthetic;
        useUncounted = use == 0 && !synthetic;
        attackDrained = 0;
    }

    public void clear() {
        loaded = false;
        tick = 0;
        attack = 0;
        use = 0;
        drop = 0;
        inv = 0;
        swap = 0;
        attackEdge = false;
        useUncounted = false;
        attackDrained = 0;
    }

    public boolean loadedFor(int now) {
        return loaded && tick == now;
    }

    public boolean firstAttackOfSample() {
        return attackDrained == 0;
    }

    public boolean takeAttack() {
        if (attack > 0) {
            SimProbe.hit(SimProbe.CLICK_ATTACK_COUNTED_DRAIN);
            attack--;
            attackDrained++;
            return true;
        }
        if (attackEdge) {
            SimProbe.hit(SimProbe.CLICK_ATTACK_EDGE_DRAIN);
            attackEdge = false;
            attackDrained++;
            return true;
        }
        return false;
    }

    public boolean takeUse() {
        if (use > 0) {
            SimProbe.hit(SimProbe.CLICK_USE_COUNTED_DRAIN);
            use--;
            return true;
        }
        if (useUncounted) {
            SimProbe.hit(SimProbe.CLICK_USE_UNCOUNTED_DRAIN);
            useUncounted = false;
            return true;
        }
        return false;
    }

    public boolean takeDrop() {
        if (drop > 0) {
            SimProbe.hit(SimProbe.CLICK_DROP_COUNTED_DRAIN);
            drop--;
            return true;
        }
        return false;
    }

    public boolean takeInv() {
        if (inv > 0) {
            SimProbe.hit(SimProbe.CLICK_INV_COUNTED_DRAIN);
            inv--;
            return true;
        }
        return false;
    }

    public boolean takeSwap() {
        if (swap > 0) {
            SimProbe.hit(SimProbe.CLICK_SWAP_COUNTED_DRAIN);
            swap--;
            return true;
        }
        return false;
    }
}
