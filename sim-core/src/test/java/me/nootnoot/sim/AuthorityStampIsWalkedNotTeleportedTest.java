package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class AuthorityStampIsWalkedNotTeleportedTest {
    private static final double GROUND_Y = 64.0;

    private static final int STONE = 920;

    private static GameState edgeHostedSlot0() {
        GameState g = new GameState();
        for (PlayerState p : g.players) {
            p.y = GROUND_Y;
            p.onGround = true;
            p.health = 20f;
            p.maxHealth = 20f;
        }
        g.players[0].x = 0.0;
        g.players[1].x = 40.0;
        g.edgeHosted[0] = true;
        g.roundsTarget = 1;
        return g;
    }

    private static void wallAt(GameState g, int x) {
        for (int y = 65; y <= 68; y++) {
            for (int z = -2; z <= 2; z++) {
                g.blocks.place(x, y, z, STONE);
            }
        }
    }

    @Test
    void aStampOnTheFarSideOfAWallDoesNotArriveThroughIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = edgeHostedSlot0();
        wallAt(s, 8);
        PlayerState a = s.players[0];

        Simulation.tick(s, arena,
                Input.NONE.withAuthority(Authority.at(20.0, a.y, a.z, true)), Input.NONE);

        assertTrue(a.x < 8.0,
                "20 blocks is inside MAX_AUTHORITY_STEP, so the magnitude clamp lets the stamp"
                        + " through; the only thing that can stop it landing on the far side of a"
                        + " wall is walking the delta rather than clamping one 20 block axis"
                        + " sweep, and the player ended at x " + a.x);
    }

    @Test
    void theWalkIsFineEnoughThatNoFullCubeCanFitBetweenTwoSteps() {
        assertTrue(Simulation.AUTHORITY_WALK_STEP < Simulation.PLAYER_WIDTH,
                "a sub-step wider than the player hull can straddle a one block wall: neither"
                        + " endpoint of the leg overlaps it, so no axis clamp fires and the leg"
                        + " passes through");
        assertTrue(Simulation.AUTHORITY_WALK_STEP < 1.0,
                "and it has to be under a block so a single voxel is never skipped outright");
    }

    @Test
    void aStampSmallerThanOneSubStepIsStillTheSingleSweepItAlwaysWas() {
        assertEquals(1, Simulation.authorityWalkSteps(0.4, -0.1, 0.2),
                "every ordinary 20 Hz sample is well under one sub-step, so the common path must"
                        + " take exactly the same single collideArena call it took before and"
                        + " produce bit-identical results");
        assertEquals(1, Simulation.authorityWalkSteps(Simulation.AUTHORITY_WALK_STEP, 0.0, 0.0),
                "and the boundary itself is still one leg");
    }

    @Test
    void theWalkIsBoundedSoAHostileStampCannotBuyUnboundedWork() {
        int worst = Simulation.authorityWalkSteps(Simulation.MAX_AUTHORITY_STEP,
                Simulation.MAX_AUTHORITY_STEP, Simulation.MAX_AUTHORITY_STEP);
        assertTrue(worst <= Simulation.AUTHORITY_WALK_MAX_STEPS,
                "the furthest stamp the magnitude clamp admits still has to cost a bounded number"
                        + " of collision sweeps, or the clamp buys a CPU amplifier instead");
        assertTrue(worst > 1, "and the worst case really does substep, or this proves nothing");
    }

    @Test
    void anHonestSampleIsUnchangedByTheWalk() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = edgeHostedSlot0();
        PlayerState a = s.players[0];
        double target = a.x + 0.25;

        Simulation.tick(s, arena,
                Input.NONE.withAuthority(Authority.at(target, a.y, a.z, true)), Input.NONE);

        assertEquals(target, a.x, 1.0E-9,
                "walking the delta may not cost an honest edge sample its authority");
    }

    @Test
    void anOpenLaneIsStillCrossedWhenNothingIsInTheWay() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = edgeHostedSlot0();
        PlayerState a = s.players[0];

        Simulation.tick(s, arena,
                Input.NONE.withAuthority(Authority.at(20.0, a.y, a.z, true)), Input.NONE);

        assertEquals(20.0, a.x, 1.0E-6,
                "a legal lag catch-up over open ground is exactly what MAX_AUTHORITY_STEP exists"
                        + " to admit, so the walk must still deliver the player to the sample");
    }
}
