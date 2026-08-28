package me.nootnoot.edge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import me.nootnoot.sim.Fluids;
import me.nootnoot.sim.host.PaintedCells;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

public final class EdgeWorldMirror {

    private static final int KIND_BLOCK = 1;
    private static final int KIND_ANCHOR = 2;
    private static final int KIND_COBWEB = 3;
    private static final int KIND_FIRE = 4;
    private static final int KIND_FLUID = 5;
    private static final int KIND_BROKEN = 6;

    private static final String AIR = "minecraft:air";
    private static final String COBWEB = "minecraft:cobweb";
    private static final String FIRE = "minecraft:fire";
    private static final String RESPAWN_ANCHOR = "minecraft:respawn_anchor";

    private static final int MAX_WRITES_PER_TICK = 256;
    private static final int MAX_COSMETICS_PER_TICK = 6;
    private static final int BLAST_THRESHOLD = 4;

    private static final float PLACE_PITCH = 0.8f;

    private static final Map<UUID, Object> WRITERS = new ConcurrentHashMap<>();

    private final Player viewer;
    private final World world;
    private final EdgeArenaPaster.Overlay overlay;
    private final Object lease = new Object();

    private boolean writer;
    private boolean closed;

    private final Map<Long, Shown> shown = new HashMap<>();
    private final Map<Long, Desired> desired = new HashMap<>();
    private final Map<String, BlockData> resolved = new HashMap<>();
    private final EdgeBlockAcks acks;

    public EdgeWorldMirror(Player viewer, World world, EdgeArenaPaster.Overlay overlay,
                           EdgeBlockAcks acks) {
        this.viewer = viewer;
        this.world = world;
        this.overlay = overlay;
        this.acks = acks;
        claim();
    }

    private boolean claim() {
        if (closed) {
            return false;
        }
        if (writer) {
            return true;
        }
        if (world == null || overlay == null) {
            return false;
        }
        writer = WRITERS.putIfAbsent(world.getUID(), lease) == null;
        return writer;
    }

    public boolean writes() {
        return writer;
    }

    private record Desired(String descriptor, int kind, int itemId, int charge) {
    }

    private record Shown(String descriptor, int kind, int itemId, int charge, Material was) {
    }

    public void render(GameState head) {
        if (!claim() || !viewer.isOnline()) {
            return;
        }
        forgetCellsAnotherPainterTook();
        collect(head);
        reconcile();
        desired.clear();
    }

    private void forgetCellsAnotherPainterTook() {
        for (long key : overlay.lost(PaintedCells.BLOCKS)) {
            shown.remove(key);
        }
    }

    private void collect(GameState head) {
        for (long key : head.blocks.sortedKeys()) {
            int itemId = head.blocks.idAtKey(key);
            Material material = EdgeItemIds.material(itemId);
            String descriptor = EdgeStacks.descriptor(material);
            if (descriptor == null) {
                continue;
            }
            if (material == Material.RESPAWN_ANCHOR) {
                Integer charge = head.anchors.get(key);
                int level = charge == null ? 0 : Math.max(0, Math.min(4, charge));
                desired.put(key, new Desired(RESPAWN_ANCHOR + "[charges=" + level + "]",
                        KIND_ANCHOR, itemId, level));
                continue;
            }
            desired.put(key, new Desired(descriptor, KIND_BLOCK, itemId, 0));
        }
        for (long key : head.cobwebs.keySet()) {
            desired.putIfAbsent(key, new Desired(COBWEB, KIND_COBWEB, 0, 0));
        }
        for (long key : head.fires.keySet()) {
            desired.putIfAbsent(key, new Desired(FIRE, KIND_FIRE, 0, 0));
        }
        for (Map.Entry<Long, Integer> entry : head.fluids.entrySet()) {
            desired.putIfAbsent(entry.getKey(),
                    new Desired(fluidDescriptor(entry.getValue()), KIND_FLUID, 0, 0));
        }
        for (long key : head.brokenArena) {
            desired.putIfAbsent(key, new Desired(AIR, KIND_BROKEN, 0, 0));
        }
    }

    private void reconcile() {
        int fresh = 0;
        for (Map.Entry<Long, Desired> entry : desired.entrySet()) {
            Shown current = shown.get(entry.getKey());
            if (current == null || !current.descriptor().equals(entry.getValue().descriptor())) {
                fresh++;
            }
        }
        List<Long> removals = new ArrayList<>();
        for (Map.Entry<Long, Shown> entry : shown.entrySet()) {
            if (!desired.containsKey(entry.getKey())) {
                removals.add(entry.getKey());
            }
        }
        boolean blast = fresh + removals.size() >= BLAST_THRESHOLD;

        int writes = 0;
        int cosmetics = 0;
        for (Map.Entry<Long, Desired> entry : desired.entrySet()) {
            if (writes >= MAX_WRITES_PER_TICK) {
                break;
            }
            long key = entry.getKey();
            Desired want = entry.getValue();
            Shown current = shown.get(key);
            if (current != null && current.descriptor().equals(want.descriptor())) {
                continue;
            }
            int x = BlockStore.unpackX(key);
            int y = BlockStore.unpackY(key);
            int z = BlockStore.unpackZ(key);
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            Material was = current != null ? current.was() : overlay.cover(x, y, z);
            overlay.set(PaintedCells.BLOCKS, x, y, z, want.descriptor());
            shown.put(key, new Shown(want.descriptor(), want.kind(), want.itemId(), want.charge(), was));
            if (acks != null) {
                acks.decided(x, y, z);
            }
            writes++;
            if (current == null) {
                if (want.kind() == KIND_BROKEN) {
                    if (!blast && cosmetics < MAX_COSMETICS_PER_TICK && was != null && !was.isAir()) {
                        cosmetics++;
                        breakFx(x, y, z, was);
                    }
                } else if (want.kind() != KIND_FLUID && want.kind() != KIND_FIRE) {
                    placeFx(x, y, z, want.descriptor());
                }
            } else if (want.kind() == KIND_ANCHOR && current.kind() == KIND_ANCHOR
                    && want.charge() > current.charge()) {
                chargeFx(x, y, z);
            }
        }

        for (long key : removals) {
            Shown current = shown.remove(key);
            int x = BlockStore.unpackX(key);
            int y = BlockStore.unpackY(key);
            int z = BlockStore.unpackZ(key);
            overlay.clear(PaintedCells.BLOCKS, x, y, z);
            if (acks != null) {
                acks.decided(x, y, z);
            }
            if (current.kind() == KIND_ANCHOR) {
                continue;
            }
            if (current.kind() == KIND_FLUID || current.kind() == KIND_FIRE
                    || current.kind() == KIND_BROKEN) {
                continue;
            }
            if (!blast && cosmetics < MAX_COSMETICS_PER_TICK) {
                cosmetics++;
                breakFx(x, y, z, materialOf(current.descriptor()));
            }
        }

        if (desired.isEmpty() && overlay.size(PaintedCells.BLOCKS) > 0) {
            shown.clear();
            overlay.restore(PaintedCells.BLOCKS);
        }
    }

    private void placeFx(int x, int y, int z, String descriptor) {
        SoundGroup group = soundGroup(descriptor);
        if (group == null) {
            return;
        }
        viewer.playSound(centre(x, y, z), group.getPlaceSound(), SoundCategory.BLOCKS, 1.0f,
                PLACE_PITCH);
    }

    private void chargeFx(int x, int y, int z) {
        viewer.playSound(centre(x, y, z), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,
                SoundCategory.BLOCKS, 1.0f, 1.0f);
    }

    private void breakFx(int x, int y, int z, Material was) {
        if (was == null || was.isAir() || !was.isBlock()) {
            return;
        }
        try {
            viewer.playEffect(new Location(world, x, y, z), Effect.STEP_SOUND, was);
        } catch (IllegalArgumentException ignored) {
            return;
        }
    }

    private Location centre(int x, int y, int z) {
        return new Location(world, x + 0.5, y + 0.5, z + 0.5);
    }

    private SoundGroup soundGroup(String descriptor) {
        BlockData data = data(descriptor);
        return data == null ? null : data.getSoundGroup();
    }

    private Material materialOf(String descriptor) {
        BlockData data = data(descriptor);
        return data == null ? null : data.getMaterial();
    }

    private BlockData data(String descriptor) {
        BlockData cached = resolved.get(descriptor);
        if (cached != null) {
            return cached;
        }
        try {
            BlockData created = Bukkit.createBlockData(descriptor);
            resolved.put(descriptor, created);
            return created;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    private static String fluidDescriptor(int packed) {
        int level = Math.max(0, Math.min(7, 8 - Fluids.amount(packed)));
        String block = Fluids.type(packed) == Fluids.LAVA ? "minecraft:lava" : "minecraft:water";
        return block + "[level=" + level + "]";
    }

    public void clear() {
        shown.clear();
        desired.clear();
        closed = true;
        if (!writer) {
            return;
        }
        writer = false;
        if (overlay != null) {
            overlay.restore(PaintedCells.BLOCKS);
        }
        if (world != null) {
            WRITERS.remove(world.getUID(), lease);
        }
    }
}
