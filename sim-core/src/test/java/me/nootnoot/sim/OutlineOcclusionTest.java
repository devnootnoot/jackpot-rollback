package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class OutlineOcclusionTest {
    private static final double GROUND_Y = 64.0;

    private static final double FENCE_COLLIDER_TOP = 66.5;
    private static final double FENCE_OUTLINE_TOP = 66.0;

    private static Arena withFenceAt(int x, int y, int z) {
        return new Arena(GROUND_Y, new double[][]{
                {x, y, z, x + 1.0, y + 1.5, z + 1.0}
        });
    }

    private static Aabb onlyBox(Arena arena, boolean occluders) {
        List<Aabb> out = new ArrayList<>();
        if (occluders) {
            arena.collectNearOccluders(out, 0.0, GROUND_Y, 0.0, 4.0, 70.0, 2.0, null);
        } else {
            arena.collectNearSolids(out, 0.0, GROUND_Y, 0.0, 4.0, 70.0, 2.0, null);
        }
        Aabb found = null;
        for (Aabb a : out) {
            if (a.minY >= GROUND_Y) {
                found = a;
            }
        }
        assertTrue(found != null, "the fence box should have been collected");
        return found;
    }

    @Test
    void aFenceTallColliderIsClippedToItsOwnCellForSightOnly() {
        Arena arena = withFenceAt(1, 65, 0);

        assertEquals(FENCE_COLLIDER_TOP, onlyBox(arena, false).maxY, 1.0E-9,
                "collision must keep the 1.5 tall shape, that is what the player walks into");
        assertEquals(FENCE_OUTLINE_TOP, onlyBox(arena, true).maxY, 1.0E-9,
                "the client raycasts ClipContext.Block.OUTLINE, and a fence outline is one block tall");
    }

    @Test
    void aBoxThatAlreadyFitsItsCellIsLeftAlone() {
        Aabb slab = new Aabb(3.0, 65.0, 2.0, 4.0, 65.5, 3.0);
        assertEquals(slab.maxY, Arena.outlineOf(slab).maxY, 1.0E-9,
                "a slab's collision and outline shapes are the same box");

        Aabb stair = new Aabb(3.5, 65.0, 2.0, 4.0, 66.0, 3.0);
        assertEquals(stair.maxY, Arena.outlineOf(stair).maxY, 1.0E-9);
    }

    @Test
    void aMultiCellBoxIsNeverClipped() {
        Aabb wall = new Aabb(0.0, 64.0, 0.0, 16.0, 80.0, 1.0);
        assertEquals(wall.maxY, Arena.outlineOf(wall).maxY, 1.0E-9,
                "a hand authored arena box spanning many cells is not a block shape and must not"
                        + " lose its height, or a whole wall would stop occluding");
    }

    private static GameState stateWithAttackerAt(double x, double y, double z) {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = x;
        a.y = y;
        a.z = z;
        a.health = 20f;
        s.players[1].x = 40.0;
        return s;
    }

    @Test
    void aSightlineThroughTheClippedBandIsNoLongerRefused() {
        Arena arena = withFenceAt(1, 65, 0);
        GameState s = stateWithAttackerAt(0.0, GROUND_Y, 0.5);

        double band = (FENCE_OUTLINE_TOP + FENCE_COLLIDER_TOP) / 2.0;
        Vec3 aim = new Vec3(3.0, band, 0.5);

        assertTrue(ClaimAuthority.unobstructed(s, arena, 0.0, band, 0.5, aim),
                "the segment runs above the fence's outline and only clips the part of the collision"
                        + " shape vanilla's own pick ray ignores");
    }

    @Test
    void aSightlineThroughTheBlockItselfIsStillRefused() {
        Arena arena = withFenceAt(1, 65, 0);
        GameState s = stateWithAttackerAt(0.0, GROUND_Y, 0.5);

        double inside = 65.5;
        Vec3 aim = new Vec3(3.0, inside, 0.5);

        assertFalse(ClaimAuthority.unobstructed(s, arena, 0.0, inside, 0.5, aim),
                "clipping the overhang must not stop the block itself from occluding");
    }

    private static final int CORNER_ITEM_ID = 900;

    private static final double ONE_FRAME_OF_TRAVEL = 0.35;

    private static Vec3 cornerAim() {
        return new Vec3(3.0, GROUND_Y + Combat.EYE_STANDING, 0.6);
    }

    private static GameState peekingPast(double recordedZ, double liveZ) {
        GameState s = stateWithAttackerAt(0.0, GROUND_Y, recordedZ);
        ClaimAuthority.record(s);
        s.players[0].z = liveZ;
        s.blocks.place(1, 65, 1, CORNER_ITEM_ID);
        return s;
    }

    @Test
    void theSightTestJudgesFromTheLiveEyeAndNothingElse() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = peekingPast(0.95, 0.95 + ONE_FRAME_OF_TRAVEL);
        PlayerState a = s.players[0];
        Vec3 aim = cornerAim();

        assertFalse(ClaimAuthority.unobstructed(s, arena, a.x, a.y + Combat.eyeHeight(a), a.z, aim),
                "from where the attacker stands now the corner clips the segment");
        assertFalse(ClaimAuthority.sightClear(s, arena, a, aim),
                "sightClear is the crystal path's occlusion test and it runs before tickPlayer, so"
                        + " the live eye is already the frame the input applies to: a stale rewound"
                        + " origin must not buy a sightline the live eye does not have");
    }

    @Test
    void aCornerBlockingTheLiveEyeStillRefusesTheHit() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = peekingPast(1.30, 1.30 + ONE_FRAME_OF_TRAVEL);
        Vec3 aim = cornerAim();

        assertFalse(ClaimAuthority.sightClear(s, arena, s.players[0], aim),
                "sub block motion buys tolerance, it does not buy a hit through a block");
    }
}
