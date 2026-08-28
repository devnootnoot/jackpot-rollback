package me.nootnoot.sim.host;

import me.nootnoot.sim.ClaimAuthority;

public final class InventoryPredictionLedger {

    public static final int NO_FRAME = Integer.MIN_VALUE;

    private InventoryPredictionLedger() {
    }

    public static int landingFrame(int headFrame) {
        return headFrame + ClaimAuthority.INPUT_DELAY_FRAMES;
    }
}
