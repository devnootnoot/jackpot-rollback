package me.nootnoot.sim;

public final class ResimulationBudgetException extends RuntimeException {
    public final int frame;
    public final int head;
    public final int depth;
    public final int budget;
    public final int capacity;

    public ResimulationBudgetException(int frame, int head, int depth, int budget, int capacity) {
        super("a correction at frame " + frame + " asks this side to resimulate " + depth
                + " frames back from head=" + head + ", and only " + budget + " of the " + capacity
                + " frame resimulation budget is left. Rollback depth is entirely the peer's to"
                + " choose, so this is the one cost in the tick the other side sets: the budget"
                + " refills with local progress and absorbs any single correction, and only a"
                + " SUSTAINED demand well past the playable envelope drains it. Either that link is"
                + " far outside what this netcode can carry, or the corrections were manufactured."
                + " There is no way to tell those apart from here, and the result is the same"
                + " either way: a no contest that awards nobody.");
        this.frame = frame;
        this.head = head;
        this.depth = depth;
        this.budget = budget;
        this.capacity = capacity;
    }
}
