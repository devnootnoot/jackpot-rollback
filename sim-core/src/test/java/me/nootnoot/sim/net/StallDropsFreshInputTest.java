package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.Loadout;
import org.junit.jupiter.api.Test;

class StallDropsFreshInputTest {
    private static final double GROUND_Y = 64.0;
    private static final int TICKS = 140;

    private static Input click() {
        return new Input(false, false, false, false, false, false, false, false, true,
                0f, 89f, CrystalKitFixture.SLOT_FIREWORK3)
                .withUsePress(true)
                .withClicks(new Clicks(0, 1, 0, 0, 0));
    }

    private static List<Integer> stallTicks() {
        List<Integer> stalls = new ArrayList<>();
        run(-1, stalls);
        return stalls;
    }

    private static int run(int clickTick, List<Integer> stalls) {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x99L, 2, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena,
                CrystalKitFixture.build(GROUND_Y), 512, 1);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena,
                CrystalKitFixture.build(GROUND_Y), 512, 1);

        int start = Loadout.countAt(s0.state().players[0], CrystalKitFixture.SLOT_FIREWORK3);
        for (int t = 0; t < TICKS; t++) {
            net.step();
            int head = s0.head();
            s0.update(t == clickTick ? click() : Input.NONE);
            if (stalls != null && s0.head() == head && head > 0) {
                stalls.add(t);
            }
            if (t % 3 != 0) {
                s1.update(Input.NONE);
            }
        }
        return start - Loadout.countAt(s0.state().players[0], CrystalKitFixture.SLOT_FIREWORK3);
    }

    @Test
    void theTickAfterATimeSyncSleepStillGetsItsInputSimulated() {
        List<Integer> stalls = stallTicks();
        assertTrue(stalls.size() >= 3,
                "the fixture has to make NetSession.timeSyncStall actually sleep, otherwise there is"
                        + " nothing to test; it slept on ticks " + stalls);

        for (int stall : stalls) {
            assertEquals(1, run(stall + 1, null),
                    "a right-click on the tick right after the sleep frame at tick " + stall
                            + " never reached the sim. updateOneFrame appended the sample BEFORE"
                            + " deciding to sleep, so rawInputs already held an entry for the frame"
                            + " that had not run yet, and the `while (rawInputs.end() <= frame)`"
                            + " guard silently threw the next tick's sample away. Every discrete"
                            + " action on that tick - a click, a drop, an inventory move, a block"
                            + " place - is lost, and the inventory hold releases on a move the sim"
                            + " never saw.");
        }
    }

    @Test
    void aSleepFrameCostsTheSampleTakenOnIt() {
        List<Integer> stalls = stallTicks();
        for (int stall : stalls) {
            assertEquals(0, run(stall, null),
                    "a sleep frame runs no frame at all, so the sample taken on it is the one that"
                            + " is dropped. Pinning this: exactly one of each stall/resume pair is"
                            + " lost, and it must be the older one.");
        }
    }
}
