package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class MeleeClaimLatchTest {
    private static final double GROUND_Y = 64.0;

    private static GameState faceOff(double victimX) {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        a.x = 0;
        a.y = GROUND_Y;
        a.z = 0.5;
        a.yaw = -90f;
        a.onGround = true;
        a.vy = -0.0784;
        a.health = 20f;
        a.attackTicker = 100;

        PlayerState v = g.players[1];
        v.x = victimX;
        v.y = GROUND_Y;
        v.z = 0.5;
        v.yaw = 90f;
        v.onGround = true;
        v.vy = -0.0784;
        v.health = 20f;
        v.attackTicker = 100;
        TestKit.of(g).give(0, 0, 1, TestKit.item().melee(6f, 1.6f).flags(ItemDict.FLAG_SWORD));
        return g;
    }

    private static Input swing() {
        return new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                .withMeleeHit(true);
    }

    @Test
    void aPlainSwingStillLandsAndSpendsTheClaimOnThatFrame() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff(2.0);
        int frame = g.tick;

        Simulation.tick(g, arena, swing(), Input.NONE);

        assertTrue(g.players[1].health < 20f, "the control swing should land");
        assertEquals(frame, g.players[0].meleeClaimTick,
                "the claim should be pinned to the frame that carried the meleeHit bit");
    }

    @Test
    void theClaimIsSpentEvenWhenItIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff(30.0);
        int frame = g.tick;

        Simulation.tick(g, arena, swing(), Input.NONE);

        assertEquals(20f, g.players[1].health, 0f, "an out-of-reach swing must not land");
        assertEquals(frame, g.players[0].meleeClaimTick,
                "the claim is spent the moment it is evaluated, like a projectile's claimSpent");
    }

    @Test
    void oneClickCannotBothNameACrystalAndMeleeTheOpponent() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff(2.0);

        int frame = g.tick;
        Input both = swing().withBlockAction(Input.BLOCK_HIT_CRYSTAL, 2, 64, 40);
        Simulation.tick(g, arena, both, Input.NONE);

        assertEquals(20f, g.players[1].health, 0f,
                "the click named a crystal, so it must not also resolve as a melee hit");
        assertEquals(frame, g.players[0].meleeClaimTick,
                "the crystal branch should have spent the click's entity claim");
    }

    @Test
    void aRealDetonationSpendsTheClicksEntityClaim() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff(60.0);
        CrystalState c = new CrystalState();
        c.id = 1;
        c.bx = 2;
        c.by = 64;
        c.bz = 0;
        g.crystals.add(c);
        int frame = g.tick;

        Simulation.tick(g, arena, swing().withBlockAction(Input.BLOCK_HIT_CRYSTAL, 2, 64, 0), Input.NONE);

        assertTrue(g.crystals.isEmpty(), "the crystal should have detonated");
        assertEquals(frame, g.players[0].meleeClaimTick,
                "one click, one entity: the detonation consumed the claim");
    }

    @Test
    void thePreviousFramesClaimDoesNotBlockTheNextClick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff(2.0);

        Simulation.tick(g, arena, swing(), Input.NONE);
        float afterFirst = g.players[1].health;
        assertTrue(afterFirst < 20f, "the first swing should land");

        Simulation.tick(g, arena, Input.NONE, Input.NONE);
        for (int i = 0; i < Combat.I_FRAMES; i++) {
            Simulation.tick(g, arena, Input.NONE, Input.NONE);
        }
        g.players[0].attackTicker = 100;

        Simulation.tick(g, arena, swing(), Input.NONE);

        assertTrue(g.players[1].health < afterFirst,
                "a later click must get its own claim: the latch is per frame, not per match");
    }
}
