package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ImpulseAllowanceOutlivesTheRoundTripTest {

    private static double decayedVerticalAllowance(double start, int ticks) {
        double up = start;
        for (int i = 0; i < ticks; i++) {
            up = Math.max(0.0, (up - EdgeMovementValidator.GRAVITY)
                    * EdgeMovementValidator.VERTICAL_DRAG);
        }
        return up;
    }

    @Test
    void theOldDecayHadSpentTheAllowanceBeforeTheClientCouldBeObserved() {
        double jumpPop = 0.4;
        assertTrue(decayedVerticalAllowance(jumpPop, 4) < jumpPop * 0.25,
                "this pins the bug, not the fix. The envelope was armed on the tick the SIM applied"
                        + " the impulse, but the victim's client cannot even start moving until the"
                        + " velocity packet reaches it, and its move packet only returns a further"
                        + " one-way trip later. At 80ms that is 3-5 ticks, by which point the"
                        + " allowance had decayed to almost nothing and the client's real knockback"
                        + " was clamped away as a violation by its own edge");
    }

    @Test
    void theHoldCoversAFullRoundTripAtRealisticPing() {
        assertTrue(EdgeMovementValidator.IMPULSE_HOLD_TICKS >= 8,
                "the hold has to cover the velocity packet going out AND the move packet coming"
                        + " back, for pings well above the 80ms being tested. Anything shorter"
                        + " reintroduces the clamp at higher latency");
    }

    @Test
    void theHoldIsBoundedSoItCannotBecomeAFreeMovementWindow() {
        assertTrue(EdgeMovementValidator.IMPULSE_HOLD_TICKS <= 20,
                "the allowance is only widened after an impulse the SIM ITSELF authored, but it"
                        + " still has to expire. An unbounded hold would let a client keep moving"
                        + " at impulse speed indefinitely after one legitimate hit");
    }
}
