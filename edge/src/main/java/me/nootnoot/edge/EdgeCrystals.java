package me.nootnoot.edge;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import org.bukkit.entity.Player;

public final class EdgeCrystals {

    private static final int DATA_SHOW_BOTTOM = 9;

    private static final double CRYSTAL_Y_OFFSET = 1.0;

    private final Player viewer;
    private final EdgeInputSource input;

    private final Map<Integer, Integer> alive = new HashMap<>();
    private final EdgeEntityBands.Allocator ids =
            new EdgeEntityBands.Allocator(EdgeEntityBands.CRYSTAL_BASE, EdgeEntityBands.CRYSTAL_SPAN);

    public EdgeCrystals(Player viewer, EdgeInputSource input) {
        this.viewer = viewer;
        this.input = input;
    }

    public void render(GameState head) {
        if (!viewer.isOnline()) {
            return;
        }
        Set<Integer> seen = new HashSet<>();
        for (CrystalState crystal : head.crystals) {
            seen.add(crystal.id);
            if (alive.containsKey(crystal.id)) {
                continue;
            }
            int entityId = ids.alloc();
            if (entityId < 0) {
                continue;
            }
            alive.put(crystal.id, entityId);
            EdgePackets.spawn(viewer, entityId, UUID.randomUUID(), EntityTypes.END_CRYSTAL,
                    crystal.bx + 0.5, crystal.by + CRYSTAL_Y_OFFSET, crystal.bz + 0.5, 0.0f, 0.0f, 0);
            List<EntityData<?>> data = new ArrayList<>(1);
            data.add(new EntityData<Boolean>(DATA_SHOW_BOTTOM, EntityDataTypes.BOOLEAN, Boolean.FALSE));
            EdgePackets.metadata(viewer, entityId, data);
            if (input != null) {
                input.registerCrystal(entityId, crystal.bx, crystal.by, crystal.bz);
            }
        }
        if (alive.isEmpty()) {
            return;
        }
        List<Integer> gone = new ArrayList<>();
        for (int simId : alive.keySet()) {
            if (!seen.contains(simId)) {
                gone.add(simId);
            }
        }
        for (int simId : gone) {
            int entityId = alive.remove(simId);
            EdgePackets.destroy(viewer, entityId);
            ids.free(entityId);
            if (input != null) {
                input.unregisterCrystal(entityId);
            }
        }
    }

    public void clear() {
        for (int entityId : alive.values()) {
            EdgePackets.destroy(viewer, entityId);
        }
        alive.clear();
        ids.reset();
        if (input != null) {
            input.clearCrystals();
        }
    }
}
