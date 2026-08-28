package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.CrystalKitFixture;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CatchUpFillerActsForPlayerTest {
    private static final double GROUND_Y = 64.0;
    private static final int WARMUP_TICKS = 12;
    private static final int STALL_TICKS = 40;

    private record Burst(int framesRun, PlayerState before, PlayerState after, int projectiles) {
    }

    private static Input hold(int slot, float pitch, boolean press, boolean attack) {
        return new Input(false, false, false, false, false, false, false, attack, true,
                0f, pitch, slot)
                .withUsePress(press)
                .withClicks(new Clicks(0, press ? 1 : 0, 0, 0, 0));
    }

    private static Burst holdThenStallThenRelease(int slot, float pitch, boolean attack) {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x7171L, 2, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena,
                CrystalKitFixture.build(GROUND_Y), 512);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena,
                CrystalKitFixture.build(GROUND_Y), 512);

        for (int i = 0; i < WARMUP_TICKS; i++) {
            net.step();
            s0.update(hold(slot, pitch, i == 0, attack));
            s1.update(Input.NONE);
        }
        PlayerState before = s0.state().players[0].copy();

        for (int i = 0; i < STALL_TICKS; i++) {
            net.step();
            s0.flush();
            s1.update(Input.NONE);
        }
        int headBeforeBurst = s0.head();

        net.step();
        s0.update(Input.NONE);
        s1.update(Input.NONE);

        int framesRun = s0.head() - headBeforeBurst;
        assertTrue(framesRun >= 20,
                "the fixture has to produce a SNAP catch-up burst that runs past"
                        + " PREDICTION_DECAY_FRAMES, otherwise it is not testing the decay filler at"
                        + " all; it ran " + framesRun + " frames");
        return new Burst(framesRun, before, s0.state().players[0], s0.state().projectiles.size());
    }

    @Test
    void aCatchUpBurstMustNotEatFoodTheHolderLetGoOf() {
        Burst b = holdThenStallThenRelease(CrystalKitFixture.SLOT_GAPPLE, 0f, false);

        assertEquals(Loadout.countAt(b.before(), CrystalKitFixture.SLOT_GAPPLE),
                Loadout.countAt(b.after(), CrystalKitFixture.SLOT_GAPPLE),
                "the player was ten ticks into a gapple when the session stalled and had released"
                        + " the button by the time it resumed, yet the catch-up burst finished the"
                        + " bite for them. NetSession.catchUpFiller builds every filler frame from"
                        + " lastInputBeforeBurst, so use stays pinned TRUE for the whole burst, and"
                        + " Combat.handleUse reads those frames as a continuing hold.");
    }

    @Test
    void aCatchUpBurstMustNotThrowItemsTheHolderLetGoOf() {
        Burst b = holdThenStallThenRelease(CrystalKitFixture.SLOT_FIREWORK3, 89f, false);

        assertEquals(Loadout.countAt(b.before(), CrystalKitFixture.SLOT_FIREWORK3),
                Loadout.countAt(b.after(), CrystalKitFixture.SLOT_FIREWORK3),
                "a pinned use re-arms Combat.useFires every USE_REPEAT_DELAY ticks, so a burst of"
                        + " " + b.framesRun() + " frames launches a rocket roughly every four frames"
                        + " on behalf of a player who is not holding anything. The same repeat path"
                        + " serves pearls, snowballs, eggs, splash potions, xp bottles and wind"
                        + " charges in both hands, and the crossbow shot.");
    }

    @Test
    void aCatchUpBurstMustNotSwingForThePlayer() {
        Burst b = holdThenStallThenRelease(CrystalKitFixture.SLOT_SWORD, 0f, true);

        assertEquals(b.before().attackTicker + b.framesRun(), b.after().attackTicker,
                "the first filler frame carries attack=true while prevAttack is false from the real"
                        + " resume frame, so ClickBudget.attackEdge fires a phantom swing that"
                        + " resets the attack cooldown. The ticker must simply run on across a"
                        + " burst nobody swung in.");
    }

    @Test
    void aFillerFrameMustNotEndAGestureEither() {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x7171L, 2, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena,
                CrystalKitFixture.build(GROUND_Y), 512);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena,
                CrystalKitFixture.build(GROUND_Y), 512);

        for (int i = 0; i < WARMUP_TICKS; i++) {
            net.step();
            s0.update(Input.NONE);
            s1.update(Input.NONE);
        }
        for (int i = 0; i < STALL_TICKS; i++) {
            net.step();
            s0.flush();
            s1.update(Input.NONE);
        }

        net.step();
        s0.update(hold(CrystalKitFixture.SLOT_CROSSBOW, 0f, true, false));
        s1.update(Input.NONE);

        PlayerState p = s0.state().players[0];
        assertTrue(p.drawTicks > 0,
                "the real resume frame started loading the crossbow, so the draw has to survive the"
                        + " burst that follows it. lastInputBeforeBurst is idle here, so every"
                        + " filler frame carries use=false - a stale release the player never made,"
                        + " and the mirror image of the pinned hold. A filler frame may neither"
                        + " start nor finish a gesture.");
    }
}
