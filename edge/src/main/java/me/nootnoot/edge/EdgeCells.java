package me.nootnoot.edge;

import me.nootnoot.sim.Combat;
import me.nootnoot.sim.Fluids;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import org.bukkit.Material;
import org.bukkit.World;

public final class EdgeCells {

    private final Arena arena;
    private final World world;

    public EdgeCells(Arena arena, World world) {
        this.arena = arena;
        this.world = world;
    }

    public Material at(GameState state, int x, int y, int z) {
        if (state == null) {
            return Material.AIR;
        }
        long key = BlockStore.key(x, y, z);
        if (state.blocks.contains(x, y, z)) {
            return EdgeItemIds.material(state.blocks.idAt(x, y, z));
        }
        if (state.cobwebs.containsKey(key)) {
            return Material.COBWEB;
        }
        if (state.fires.containsKey(key)) {
            return Material.FIRE;
        }
        Integer fluid = state.fluids.get(key);
        if (fluid != null) {
            return Fluids.type(fluid) == Fluids.LAVA ? Material.LAVA : Material.WATER;
        }
        if (state.brokenArena.contains(key)) {
            return Material.AIR;
        }
        return terrain(x, y, z);
    }

    public Material terrain(int x, int y, int z) {
        if (arena == null || world == null || !arena.hasVoxelGrid()) {
            return Material.AIR;
        }
        if (!arena.isSolidVoxel(x, y, z) && !arena.isDecorVoxel(x, y, z)) {
            return Material.AIR;
        }
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return Material.AIR;
        }
        return world.getBlockAt(x, y, z).getType();
    }

    public boolean breakable(GameState state, int x, int y, int z) {
        return Combat.breakableCell(state, arena, x, y, z);
    }

    public static float blastResistance(Material material) {
        if (material == null || material.isAir() || !material.isBlock()) {
            return 0f;
        }
        try {
            return material.getBlastResistance();
        } catch (RuntimeException ex) {
            return 0f;
        }
    }
}
