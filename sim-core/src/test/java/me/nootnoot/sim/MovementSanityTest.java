package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class MovementSanityTest {
    private static final double GROUND_Y = 64.0;
    private static final float YAW_NORTH = 0f;

    private static PlayerState standing() {
        PlayerState p = new PlayerState();
        p.x = 0;
        p.y = GROUND_Y;
        p.z = 0;
        p.yaw = YAW_NORTH;
        p.onGround = true;
        p.health = 20f;
        return p;
    }

    private static Input forwardWalk(boolean sprint) {
        return new Input(true, false, false, false, false, sprint, false, false, false, YAW_NORTH, 0f, 0);
    }

    @Test
    void walkingForwardMovesAndStaysGrounded() {
        Arena arena = Arena.flat(GROUND_Y);
        PlayerState p = standing();
        double startZ = p.z;
        for (int i = 0; i < 40; i++) {
            stepOne(p, arena, forwardWalk(false));
        }
        double distance = Math.abs(p.z - startZ);
        assertTrue(distance > 3.0, "walked too little in 2s: " + distance);
        assertEquals(GROUND_Y, p.y, 1.0E-9, "player left the ground plane while walking");
        assertTrue(p.onGround, "player should be grounded while walking on flat ground");
    }

    @Test
    void groundedVerticalVelocityIsCanonical() {
        Arena arena = Arena.flat(GROUND_Y);
        PlayerState p = standing();
        for (int i = 0; i < 20; i++) {
            stepOne(p, arena, Input.NONE);
        }
        assertEquals(-0.0784, p.vy, 1.0E-9, "grounded vy should settle to -0.0784");
        assertEquals(GROUND_Y, p.y, 1.0E-9);
    }

    @Test
    void sprintingIsFasterThanWalking() {
        Arena arena = Arena.flat(GROUND_Y);
        PlayerState walk = standing();
        PlayerState run = standing();
        for (int i = 0; i < 40; i++) {
            stepOne(walk, arena, forwardWalk(false));
            stepOne(run, arena, forwardWalk(true));
        }
        double walkDist = Math.abs(walk.z);
        double runDist = Math.abs(run.z);
        assertTrue(runDist > walkDist * 1.2, "sprint not meaningfully faster: walk=" + walkDist + " run=" + runDist);
    }

    @Test
    void wallStopsHorizontalMovement() {
        double[][] wall = {{-5.0, GROUND_Y, -2.0, 5.0, GROUND_Y + 3.0, -1.5}};
        Arena arena = new Arena(GROUND_Y, wall);
        PlayerState p = standing();
        for (int i = 0; i < 60; i++) {
            stepOne(p, arena, forwardWalk(true));
        }

        assertTrue(p.z >= -1.2 - 1.0E-6, "player tunnelled into/through the wall: z=" + p.z);
    }

    @Test
    void fallingPlayerLandsOnFloor() {
        Arena arena = Arena.flat(GROUND_Y);
        PlayerState p = standing();
        p.y = GROUND_Y + 10.0;
        p.onGround = false;
        for (int i = 0; i < 60; i++) {
            stepOne(p, arena, Input.NONE);
        }
        assertEquals(GROUND_Y, p.y, 1.0E-9, "player did not settle on the floor after falling");
        assertTrue(p.onGround);
    }

    @Test
    void waterSinkAndSwimUpMatchVanillaTerminalVelocities() {
        Arena arena = Arena.flat(GROUND_Y);

        PlayerState sink = submerged();
        for (int i = 0; i < 60; i++) {
            stepInFluid(sink, arena, Input.NONE, Fluids.WATER);
        }
        assertEquals(-0.025, sink.vy, 1.0E-6, "water sink should converge on vanilla's -(gravity/16) / 0.2");

        PlayerState swim = submerged();
        Input jump = new Input(false, false, false, false, true, false, false, false, false, YAW_NORTH, 0f, 0);
        for (int i = 0; i < 60; i++) {
            stepInFluid(swim, arena, jump, Fluids.WATER);
        }
        assertEquals(0.135, swim.vy, 1.0E-6, "swim-up should converge on vanilla's (0.04*0.8 - 0.005) / 0.2");
    }

    @Test
    void sprintingInWaterRemovesTheSinkAndDampsLess() {
        Arena arena = Arena.flat(GROUND_Y);
        Input sprintForward = new Input(true, false, false, false, false, true, false, false, false, YAW_NORTH, 0f, 0);
        Input walkForward = new Input(true, false, false, false, false, false, false, false, false, YAW_NORTH, 0f, 0);

        PlayerState sprint = submerged();
        sprint.sprinting = true;
        PlayerState walk = submerged();
        for (int i = 0; i < 60; i++) {
            stepInFluid(sprint, arena, sprintForward, Fluids.WATER);
            stepInFluid(walk, arena, walkForward, Fluids.WATER);
        }
        assertEquals(0.0, sprint.vy, 1.0E-6, "a sprinting swimmer takes no applyFluidMovingSpeed sink");
        assertTrue(Math.abs(sprint.vz) > Math.abs(walk.vz),
                "sprint-swim (0.9 damping) should out-run walk-swim (0.8): " + sprint.vz + " vs " + walk.vz);
    }

    @Test
    void deepLavaSinkMatchesVanilla() {
        Arena arena = Arena.flat(GROUND_Y);
        PlayerState p = submerged();
        for (int i = 0; i < 60; i++) {
            stepInFluid(p, arena, Input.NONE, Fluids.LAVA);
        }
        assertEquals(-0.04, p.vy, 1.0E-6, "deep-lava sink should converge on -(gravity/4) / 0.5");
    }

    @Test
    void swimmingNeedsFullSubmersionToStartButNotToContinue() {
        Arena arena = Arena.flat(GROUND_Y);
        Input sprintForward = new Input(true, false, false, false, false, true, false, false, false, YAW_NORTH, 0f, 0);

        PlayerState wading = standing();
        for (int i = 0; i < 40; i++) {
            stepInShallowWater(wading, arena, sprintForward);
        }
        assertFalse(wading.swimming, "sprinting through shallow water must not start a swim");

        PlayerState under = submerged();
        for (int i = 0; i < 40; i++) {
            stepInFluid(under, arena, sprintForward, Fluids.WATER);
        }
        assertTrue(under.swimming, "sprinting while fully submerged should start a swim");

        for (int i = 0; i < 5; i++) {
            stepInShallowWater(under, arena, sprintForward);
        }
        assertTrue(under.swimming, "a swim already in progress should survive losing submersion");
    }

    @Test
    void releasingSprintEndsTheSwim() {
        Arena arena = Arena.flat(GROUND_Y);
        Input sprintForward = new Input(true, false, false, false, false, true, false, false, false, YAW_NORTH, 0f, 0);
        Input walkForward = new Input(true, false, false, false, false, false, false, false, false, YAW_NORTH, 0f, 0);

        PlayerState p = submerged();
        for (int i = 0; i < 40; i++) {
            stepInFluid(p, arena, sprintForward, Fluids.WATER);
        }
        assertTrue(p.swimming);

        stepInFluid(p, arena, walkForward, Fluids.WATER);
        stepInFluid(p, arena, walkForward, Fluids.WATER);
        assertFalse(p.swimming, "dropping sprint must end the swim");
    }

    @Test
    void theSwimmingBoxIsProneAndFitsAOneBlockGap() {
        PlayerState p = submerged();
        assertEquals(Simulation.PLAYER_HEIGHT, Simulation.poseHeight(p), 1.0E-9);
        p.swimming = true;
        assertEquals(Simulation.PLAYER_SWIM_HEIGHT, Simulation.poseHeight(p), 1.0E-9);
        p.sneaking = true;
        assertEquals(Simulation.PLAYER_SWIM_HEIGHT, Simulation.poseHeight(p), 1.0E-9,
                "vanilla getExpectedPose puts SWIMMING ahead of CROUCHING");
        assertEquals(Combat.EYE_GLIDING, Combat.eyeHeight(p), 1.0E-9,
                "the swimming and gliding poses share the 0.4 eye height");
    }

    @Test
    void theSwimmingFlagIsInTheChecksum() {
        GameState a = new GameState();
        GameState b = new GameState();
        assertEquals(Checksum.of(a), Checksum.of(b));
        b.players[0].swimming = true;
        assertTrue(Checksum.of(a) != Checksum.of(b), "swimming must be hashed or a desync goes undetected");
    }

    private static void stepInShallowWater(PlayerState p, Arena arena, Input in) {
        GameState g = new GameState();
        g.players[0] = p;
        g.players[1].x = 1000;
        g.players[1].y = GROUND_Y;
        g.players[1].onGround = true;
        int px = (int) Math.floor(p.x);
        int pz = (int) Math.floor(p.z);
        int py = (int) Math.floor(p.y);
        for (int x = px - 3; x <= px + 3; x++) {
            for (int z = pz - 3; z <= pz + 3; z++) {
                Fluids.place(g, arena, 0, Fluids.WATER, x, py, z);
            }
        }
        Simulation.tick(g, arena, in, Input.NONE);
    }

    private static PlayerState submerged() {
        PlayerState p = standing();
        p.y = GROUND_Y + 10.0;
        p.onGround = false;
        return p;
    }

    private static void stepInFluid(PlayerState p, Arena arena, Input in, int type) {
        GameState g = new GameState();
        g.players[0] = p;
        g.players[1].x = 1000;
        g.players[1].y = GROUND_Y;
        g.players[1].onGround = true;
        int px = (int) Math.floor(p.x);
        int py = (int) Math.floor(p.y);
        int pz = (int) Math.floor(p.z);
        for (int y = py - 2; y <= py + 3; y++) {
            for (int x = px - 2; x <= px + 2; x++) {
                for (int z = pz - 2; z <= pz + 2; z++) {
                    Fluids.place(g, arena, 0, type, x, y, z);
                }
            }
        }
        Simulation.tick(g, arena, in, Input.NONE);
    }

    private static void stepOne(PlayerState p, Arena arena, Input in) {
        var g = new me.nootnoot.sim.state.GameState();
        g.players[0] = p;

        g.players[1].x = 1000;
        g.players[1].y = GROUND_Y;
        g.players[1].onGround = true;
        Simulation.tick(g, arena, in, Input.NONE);
    }
}
