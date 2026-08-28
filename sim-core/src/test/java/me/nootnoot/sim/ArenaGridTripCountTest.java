package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.state.Arena;
import org.junit.jupiter.api.Test;

class ArenaGridTripCountTest {
    private static final double GROUND_Y = 64.0;

    private static final int BASE_X = -8;
    private static final int BASE_Y = 64;
    private static final int BASE_Z = -8;
    private static final int SIZE_X = 16;
    private static final int SIZE_Y = 8;
    private static final int SIZE_Z = 16;

    private static Arena grid() {
        boolean[] cells = new boolean[SIZE_X * SIZE_Y * SIZE_Z];
        for (int z = 0; z < SIZE_Z; z++) {
            for (int y = 0; y < SIZE_Y; y++) {
                for (int x = 0; x < SIZE_X; x++) {
                    cells[(z * SIZE_Y + y) * SIZE_X + x] = ((x + y + z) & 3) == 0;
                }
            }
        }
        return new Arena(GROUND_Y, cells, BASE_X, BASE_Y, BASE_Z, SIZE_X, SIZE_Y, SIZE_Z,
                new double[0][], Map.of());
    }

    private static List<Aabb> collect(Arena arena, double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ) {
        List<Aabb> out = new ArrayList<>();
        arena.collectNearSolids(out, minX, minY, minZ, maxX, maxY, maxZ, Set.of());
        return out;
    }

    private static int voxelsIn(List<Aabb> boxes) {
        int n = 0;
        for (Aabb b : boxes) {
            if (b.maxX - b.minX == 1.0 && b.maxY - b.minY == 1.0 && b.maxZ - b.minZ == 1.0) {
                n++;
            }
        }
        return n;
    }

    private static int solidCellCount() {
        int n = 0;
        for (int z = 0; z < SIZE_Z; z++) {
            for (int y = 0; y < SIZE_Y; y++) {
                for (int x = 0; x < SIZE_X; x++) {
                    if (((x + y + z) & 3) == 0) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    @Test
    void aQueryInsideTheGridStillReturnsExactlyTheCellsItAlwaysDid() {
        Arena arena = grid();

        List<Aabb> boxes = collect(arena, -2.4, 65.1, -2.4, 1.6, 68.9, 1.6);

        int expected = 0;
        for (int x = -3; x <= 1; x++) {
            for (int y = 65; y <= 68; y++) {
                for (int z = -3; z <= 1; z++) {
                    if (arena.isSolidVoxel(x, y, z)) {
                        expected++;
                    }
                }
            }
        }
        assertEquals(expected, voxelsIn(boxes),
                "clamping the loop to the grid may not change which cells a normal query sees;"
                        + " every cell it drops was out of range and contributed nothing");
    }

    @Test
    void theTripCountCannotExceedTheGridNoMatterWhatTheBoundsAre() {
        Arena arena = grid();

        List<Aabb> boxes = collect(arena, -1e12, -1e12, -1e12, 1e12, 1e12, 1e12);

        assertEquals(solidCellCount(), voxelsIn(boxes),
                "the bounds are floating point floors of a swept player box, so nothing stopped"
                        + " an absurd velocity or a stamped authority position from asking for a"
                        + " cube of cells wider than the grid; the walk has to be the grid, and"
                        + " this call would not return at all if it were not");
    }

    @Test
    void aNonFiniteBoundWalksNothingInsteadOfPickingACellOutOfTheAir() {
        Arena arena = grid();

        assertEquals(0, voxelsIn(collect(arena, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN)),
                "a NaN bound floors to zero, which used to name cell 0,0,0 as though it had been"
                        + " asked for");
        assertEquals(0, voxelsIn(collect(arena, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY)),
                "an inverted box is empty");
    }

    @Test
    void aBoundThatSitsExactlyOnACellFaceIsStillTakenByTheFloor() {
        Arena arena = grid();

        List<Aabb> onFace = collect(arena, 0.0, 65.0, 0.0, 1.0, 66.0, 1.0);
        List<Aabb> justInside = collect(arena, 0.0, 65.0, 0.0,
                Math.nextDown(1.0), Math.nextDown(66.0), Math.nextDown(1.0));

        assertTrue(voxelsIn(onFace) >= voxelsIn(justInside),
                "Math.floor is exact for every double, so a bound landing on an integer face is"
                        + " deterministic; the extra cell the face picks up is the same one on"
                        + " every machine");
        assertEquals(voxelsIn(collect(arena, 0.0, 65.0, 0.0, 1.0, 66.0, 1.0)), voxelsIn(onFace),
                "and the same call twice is the same walk");
    }

    @Test
    void anArenaWithNoVoxelGridIsUnaffected() {
        Arena flat = Arena.flat(GROUND_Y);

        assertEquals(0, voxelsIn(collect(flat, -1e9, -1e9, -1e9, 1e9, 1e9, 1e9)),
                "a partial-box arena never enters the voxel walk at all");
    }
}
