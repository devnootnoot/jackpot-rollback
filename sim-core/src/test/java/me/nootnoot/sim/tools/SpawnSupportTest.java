package me.nootnoot.sim.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import me.nootnoot.sim.state.Arena;
import org.junit.jupiter.api.Test;

class SpawnSupportTest {

    private static Arena arenaWithFloorAt(int floorY) {
        int sizeX = 8;
        int sizeY = 64;
        int sizeZ = 8;
        boolean[] grid = new boolean[sizeX * sizeY * sizeZ];
        for (int rz = 0; rz < sizeZ; rz++) {
            for (int rx = 0; rx < sizeX; rx++) {
                grid[(rz * sizeY + floorY) * sizeX + rx] = true;
            }
        }
        return new Arena(32.0, grid, 0, 0, 0, sizeX, sizeY, sizeZ, new double[0][], Map.of());
    }

    @Test
    void aSpawnStandingOnTheFloorIsSupported() {
        assertTrue(SpawnSupport.of(arenaWithFloorAt(5), 4.5, 6.0, 4.5, "spawn0").supported(),
                "a spawn one block above the floor is exactly where a player stands");
    }

    @Test
    void aSpawnFloatingWellAboveTheFloorIsNotSupported() {
        SpawnSupport.Report r = SpawnSupport.of(arenaWithFloorAt(5), 4.5, 32.0, 4.5, "spawn0");
        assertFalse(r.supported(),
                "this is the shape every shipped arena was in: spawn y=32 with the floor 26 blocks"
                        + " below. The sim's invisible ground slab is derived from the spawn point"
                        + " itself, so a sim-authoritative host floats on it while a"
                        + " client-authoritative host falls to the real floor");
        assertTrue(r.describe().contains("IS NOT ON ANY BLOCK"));
    }

    @Test
    void aSpawnOverAnEmptyColumnIsNotSupported() {
        Arena empty = new Arena(32.0, new boolean[8 * 64 * 8], 0, 0, 0, 8, 64, 8,
                new double[0][], Map.of());
        assertFalse(SpawnSupport.of(empty, 4.5, 32.0, 4.5, "spawn0").supported(),
                "no geometry anywhere in the column means the slab is the only thing holding the"
                        + " player up");
    }
}
