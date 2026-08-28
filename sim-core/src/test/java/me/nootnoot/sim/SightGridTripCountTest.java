package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class SightGridTripCountTest {
    private static final double GROUND_Y = 64.0;

    private static final double FAR = Input.MAX_WORLD_COORD;

    private static final double RAY_Y = 100.5;

    private static GameState withBlockAt(int x, int y, int z) {
        GameState s = new GameState();
        s.blocks.place(x, y, z, 1);
        return s;
    }

    @Test
    void theCellCubeIsOnlyWalkedWhileItIsSmallerThanThePlacedBlockSet() {
        assertFalse(ClaimAuthority.sparserThanTheGrid(64, 2, 2, 2),
                "eight cells against sixty four placed blocks: walking the cube is the cheap side");
        assertTrue(ClaimAuthority.sparserThanTheGrid(1, 4, 4, 4),
                "sixty four cells against one placed block is the expensive side");
        assertTrue(ClaimAuthority.sparserThanTheGrid(1024, 1 << 21, 1, 1),
                "one long thin span is enough on its own");
        assertTrue(ClaimAuthority.sparserThanTheGrid(1024, 1 << 12, 1 << 12, 1),
                "and a plane that only overflows once the two spans are multiplied still counts");
    }

    @Test
    void aSightRayAcrossTheWholeLegalWorldDoesNotWalkTheCellsBetweenItsEnds() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState blocked = withBlockAt(0, (int) RAY_Y, 0);
        GameState clear = withBlockAt(0, (int) RAY_Y + 100, 0);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            assertFalse(ClaimAuthority.unobstructed(blocked, arena, -FAR, RAY_Y, 0.5,
                            new Vec3(FAR, RAY_Y, 0.5)),
                    "the block sitting on the ray still occludes");
            assertTrue(ClaimAuthority.unobstructed(clear, arena, -FAR, RAY_Y, 0.5,
                            new Vec3(FAR, RAY_Y, 0.5)),
                    "and a block off the ray still does not");
        }, "a two million block separation used to be a two million cubed cell walk, which is a"
                + " hang, not a slow tick");
    }

    @Test
    void theClampedWalkAgreesWithTheCubeWalkOnAShortRay() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = new GameState();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                s.blocks.place(x, 100, z, 1);
            }
        }

        assertEquals(25, s.blocks.size(),
                "the fixture has to be dense enough that the short ray takes the cube walk");
        assertFalse(ClaimAuthority.unobstructed(s, arena, -1.5, 100.5, 0.5,
                new Vec3(1.5, 100.5, 0.5)));
        assertTrue(ClaimAuthority.unobstructed(s, arena, -1.5, 102.5, 0.5,
                new Vec3(1.5, 102.5, 0.5)));
    }
}
