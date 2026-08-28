package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.Loadout;
import org.junit.jupiter.api.Test;

class PreStartHoldSampleTest {
    private static final double GROUND_Y = 64.0;
    private static final int TICKS = 60;
    private static final int PEER_JOINS_AT = 8;

    private static Input click() {
        return new Input(false, false, false, false, false, false, false, false, true,
                0f, 89f, CrystalKitFixture.SLOT_FIREWORK3)
                .withUsePress(true)
                .withClicks(new Clicks(0, 1, 0, 0, 0));
    }

    private static int run(int clickTick, int[] firstAdvancedTick) {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x99L, 2, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena,
                CrystalKitFixture.build(GROUND_Y), 512);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena,
                CrystalKitFixture.build(GROUND_Y), 512);

        int start = Loadout.countAt(s0.state().players[0], CrystalKitFixture.SLOT_FIREWORK3);
        for (int t = 0; t < TICKS; t++) {
            net.step();
            int head = s0.head();
            s0.update(t == clickTick ? click() : Input.NONE);
            if (firstAdvancedTick != null && head == 0 && s0.head() > 0) {
                firstAdvancedTick[0] = t;
            }
            if (t >= PEER_JOINS_AT) {
                s1.update(Input.NONE);
            }
        }
        return start - Loadout.countAt(s0.state().players[0], CrystalKitFixture.SLOT_FIREWORK3);
    }

    private static int firstAdvancedTick() {
        int[] found = {-1};
        run(-1, found);
        assertTrue(found[0] > 1,
                "the fixture has to make s0 sit in the pre-start hold for a few ticks, otherwise"
                        + " there is nothing to test; it started frame 0 on tick " + found[0]);
        return found[0];
    }

    @Test
    void theTickThatFinallyStartsFrameZeroGetsItsInputSimulated() {
        int start = firstAdvancedTick();

        assertEquals(1, run(start, null),
                "a right-click on the tick the peer finally shows up never reached the sim."
                        + " updateOneFrame took the sample BEFORE returning on the pre-start hold,"
                        + " so rawInputs already held an entry for a frame that had not run yet and"
                        + " the `while (rawInputs.end() <= frame)` guard threw every later sample"
                        + " away. Frame 0 then ran on whatever the player happened to be doing on"
                        + " the very first tick of the hold - which can be seconds earlier.");
    }

    @Test
    void aHeldTickCostsTheSampleTakenOnIt() {
        int start = firstAdvancedTick();

        assertEquals(0, run(start - 1, null),
                "a held tick runs no frame at all, so the sample taken on it is the one that is"
                        + " dropped - the same rule the two stall paths already follow.");
    }
}
