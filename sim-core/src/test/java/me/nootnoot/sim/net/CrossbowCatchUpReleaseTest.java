package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CrossbowCatchUpReleaseTest {
    private static final double GROUND_Y = 64.0;
    private static final int CROSSBOW_SLOT = CrystalKitFixture.SLOT_CROSSBOW;
    private static final int WARMUP_TICKS = 40;
    private static final int RESUME_TICKS = 60;

    private static Input hold(int frame) {
        boolean press = frame == 0;
        return new Input(false, false, false, false, false, false, false, false, true,
                0f, 0f, CROSSBOW_SLOT)
                .withUsePress(press)
                .withClicks(new Clicks(0, press ? 1 : 0, 0, 0, 0));
    }

    private static int fireFrame(int stallTicks) {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x5151L, 2, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena,
                CrystalKitFixture.build(GROUND_Y), 512);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena,
                CrystalKitFixture.build(GROUND_Y), 512);

        for (int i = 0; i < WARMUP_TICKS; i++) {
            net.step();
            s0.update(hold(s0.head()));
            s1.update(Input.NONE);
        }
        assertTrue(s0.state().players[0].slotCrossbowLoaded[CROSSBOW_SLOT],
                "the crossbow has to finish loading before the stall, or the case is not set up");

        for (int i = 0; i < stallTicks; i++) {
            net.step();
            s0.flush();
            s1.update(Input.NONE);
        }

        int fired = -1;
        for (int i = 0; i < RESUME_TICKS; i++) {
            net.step();
            s0.update(hold(s0.head()));
            s1.update(Input.NONE);
            if (fired < 0 && !s0.state().projectiles.isEmpty()) {
                fired = i;
            }
        }
        return fired;
    }

    @Test
    void aCrossbowHeldThroughASmallCatchUpKeepsItsBolt() {
        assertEquals(-1, fireFrame(0),
                "no catch-up at all: the held-use latch must hold the bolt");
        assertEquals(-1, fireFrame(25),
                "a short catch-up runs on heldOnly() frames, which preserve use, so the latch holds");
    }

    @Test
    void aCatchUpBurstMustNotFireACrossbowTheHolderNeverReleased() {
        int fired = fireFrame(40);

        assertEquals(-1, fired,
                "the player never let go of the right mouse button, so nothing may leave the"
                        + " crossbow. NetSession.catchUpFiller hands the sim Input.released() once a"
                        + " SNAP burst runs past PREDICTION_DECAY_FRAMES, and released() clears use."
                        + " Combat.handleUse reads that single synthetic frame as the end of the"
                        + " gesture, drops crossbowHeldUseSlot, and the next real held frame finds a"
                        + " loaded crossbow with no latch and shoots it. The crossbow rule is right;"
                        + " the frame the rule was asked about is one the player never sent."
                        + " Reported as: crossbows insta-shoot the moment they finish charging.");
    }

    @Test
    void theSynthesisedCatchUpFrameIsWhatEndsTheGesture() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = CrystalKitFixture.build(GROUND_Y);
        PlayerState p = s.players[0];
        for (int t = 0; t < 30; t++) {
            me.nootnoot.sim.Simulation.tick(s, arena, hold(t), Input.NONE);
        }
        assertTrue(p.slotCrossbowLoaded[CROSSBOW_SLOT], "loaded");
        assertEquals(0, s.projectiles.size(), "and holding alone never fires it");

        me.nootnoot.sim.Simulation.tick(s, arena, hold(30).released(), Input.NONE);
        me.nootnoot.sim.Simulation.tick(s, arena, hold(31), Input.NONE);

        assertEquals(1, s.projectiles.size(),
                "one released() frame in the middle of a physically unbroken hold is the whole"
                        + " defect: it is indistinguishable from a real release, so the very next"
                        + " held frame fires. This assertion documents the mechanism - it is the"
                        + " frame source that must stop synthesising it, not this rule.");
    }
}
