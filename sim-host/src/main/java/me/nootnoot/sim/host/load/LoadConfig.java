package me.nootnoot.sim.host.load;

public record LoadConfig(int matches, int ticks, int warmupTicks, long seed, int latencyFrames,
                         int jitterFrames, double loss, int ringCapacity, int budgetMicros,
                         int retentionSampleInterval, boolean gcBeforeRetentionSample,
                         int stallEveryTicks, int stallTicks, boolean stallSynchronised,
                         int paceMode, int chatPerTick) {

    public static final int PACE_NONE = 0;
    public static final int PACE_PARK = 1;
    public static final int PACE_SPIN = 2;


    public static final int TICK_MICROS = 50_000;

    public static LoadConfig defaults() {
        return new LoadConfig(8, 4000, 600, 0x10ADL, 1, 1, 0.01, 1024, TICK_MICROS, 200, false,
                0, 0, false, PACE_NONE, 0);
    }

    public LoadConfig withMatches(int n) {
        return new LoadConfig(n, ticks, warmupTicks, seed, latencyFrames, jitterFrames, loss,
                ringCapacity, budgetMicros, retentionSampleInterval, gcBeforeRetentionSample,
                stallEveryTicks, stallTicks, stallSynchronised, paceMode, chatPerTick);
    }

    public boolean paced() {
        return paceMode != PACE_NONE;
    }

    public int drivers() {
        return matches * 2;
    }
}
