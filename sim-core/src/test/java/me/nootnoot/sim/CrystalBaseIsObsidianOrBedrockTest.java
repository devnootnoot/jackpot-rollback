package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.tools.DevPaletteGeometry;
import org.junit.jupiter.api.Test;

class CrystalBaseIsObsidianOrBedrockTest {

    private static final int SIZE = 8;

    private static Arena arenaOfResistance(float resistance) {
        boolean[] grid = new boolean[SIZE * SIZE * SIZE];
        Map<Long, Float> res = new HashMap<>();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                grid[(z * SIZE + 1) * SIZE + x] = true;
                res.put(BlockStore.key(x, 1, z), resistance);
            }
        }
        return new Arena(64.0, grid, 0, 0, 0, SIZE, SIZE, SIZE, new double[0][], res);
    }

    private static boolean canPlaceOn(float resistance) {
        Arena arena = arenaOfResistance(resistance);
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        a.x = 3.5;
        a.y = 2.0;
        a.z = 5.5;
        a.yaw = 180f;
        a.pitch = 0f;
        PlayerState b = s.players[1];
        b.x = 40.0;
        b.y = 2.0;
        b.z = 40.0;
        return Combat.crystalPlacementOpen(s, arena, a, 3, 1, 3);
    }

    @Test
    void anOrdinaryArenaBlockIsNotACrystalBase() {
        assertFalse(canPlaceOn(DevPaletteGeometry.DEFAULT_RESISTANCE),
                "the arena palette ships every block with dropItemId 0, and the unknown case used"
                        + " to return true, so a crystal could be placed on stone, wood, glass,"
                        + " anything. Vanilla allows obsidian and bedrock only");
    }

    @Test
    void obsidianIsACrystalBase() {
        assertTrue(canPlaceOn(DevPaletteGeometry.OBSIDIAN_RESISTANCE));
    }

    @Test
    void bedrockIsACrystalBase() {
        assertTrue(canPlaceOn(DevPaletteGeometry.INDESTRUCTIBLE_RESISTANCE));
    }
}
