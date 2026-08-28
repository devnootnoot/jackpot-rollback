package me.nootnoot.edge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.nootnoot.sim.host.PaintedCells;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class EdgeCageDisplay {

    public static final String DISPLAY_KEY = "cage_display";

    private static final int GATE_DURATION_TICKS = 15;
    private static final int GATE_INTERPOLATION_TICKS = 3;
    private static final int GATE_SPAWN_INTERPOLATION_TICKS = 10;
    private static final float GATE_SWING_DEGREES = 90f;
    private static final long GATE_REMOVE_DELAY_TICKS = 30L;
    private static final long RESEND_DELAY_TICKS = 10L;
    private static final double GATE_SWEEP_RADIUS = 0.35;
    private static final float SPAWN_YAW = 90f;

    private final EdgeCage cage;
    private final EdgeArenaPaster.Overlay overlay;
    private final Plugin plugin;
    private final World world;
    private final List<Gate> gates = new ArrayList<>();
    private final Set<Cell> floorCells = new LinkedHashSet<>();
    private final Set<Cell> wallCells = new LinkedHashSet<>();

    private boolean placed;
    private boolean opened;
    private int generation;

    private record Cell(int x, int y, int z) {
    }

    private record Gate(BlockDisplay display, Location destination) {
    }

    public EdgeCageDisplay(EdgeCage cage, EdgeArenaPaster.Overlay overlay, Plugin plugin,
                           World world) {
        this.cage = cage;
        this.overlay = overlay;
        this.plugin = plugin;
        this.world = world;
    }

    public boolean placed() {
        return placed;
    }

    public boolean opened() {
        return opened;
    }

    public int blocks() {
        return overlay == null ? 0 : overlay.size(PaintedCells.CAGE);
    }

    public EdgeCage.Extent extent(int slot) {
        return cage == null ? null : cage.extent(slot);
    }

    public void place(GameState state) {
        if (placed && !opened) {
            return;
        }
        remove();
        if (cage == null || overlay == null || state == null || cage.empty()) {
            return;
        }
        generation++;
        for (int slot = 0; slot < EdgeCage.SLOTS; slot++) {
            PlayerState spawn = anchor(state, slot);
            if (spawn == null) {
                continue;
            }
            int bx = (int) Math.floor(spawn.x);
            int by = (int) Math.floor(spawn.y);
            int bz = (int) Math.floor(spawn.z);
            Set<Cell> claimed = new LinkedHashSet<>();
            for (EdgeCage.Block block : cage.floor(slot)) {
                Cell cell = new Cell(bx + block.rx(), by + block.ry(), bz + block.rz());
                overlay.set(PaintedCells.CAGE, cell.x(), cell.y(), cell.z(), block.data());
                floorCells.add(cell);
                claimed.add(cell);
            }
            for (EdgeCage.Block block : cage.walls(slot)) {
                Cell cell = new Cell(bx + block.rx(), by + block.ry(), bz + block.rz());
                overlay.set(PaintedCells.CAGE, cell.x(), cell.y(), cell.z(), block.data());
                wallCells.add(cell);
                claimed.add(cell);
            }
            seal(bx, by, bz, claimed);
            spawnGates(spawn, cage.gate(slot));
        }
        placed = true;
        opened = false;
    }

    private void seal(int bx, int by, int bz, Set<Cell> claimed) {
        int radius = EdgeCage.SHELL_WALL_RADIUS;
        for (int rx = -radius; rx <= radius; rx++) {
            for (int rz = -radius; rz <= radius; rz++) {
                Cell floorCell = new Cell(bx + rx, by + EdgeCage.SHELL_FLOOR_Y, bz + rz);
                if (!claimed.contains(floorCell)) {
                    overlay.set(PaintedCells.CAGE, floorCell.x(), floorCell.y(), floorCell.z(),
                            EdgeCage.BARRIER);
                }
                wallCells.remove(floorCell);
                floorCells.add(floorCell);

                Cell roofCell = new Cell(bx + rx, by + EdgeCage.SHELL_ROOF_Y, bz + rz);
                if (!claimed.contains(roofCell)) {
                    overlay.set(PaintedCells.CAGE, roofCell.x(), roofCell.y(), roofCell.z(),
                            EdgeCage.BARRIER);
                    wallCells.add(roofCell);
                }

                if (Math.abs(rx) != radius && Math.abs(rz) != radius) {
                    continue;
                }
                for (int ry = 0; ry < EdgeCage.SHELL_ROOF_Y; ry++) {
                    Cell wallCell = new Cell(bx + rx, by + ry, bz + rz);
                    if (claimed.contains(wallCell)) {
                        continue;
                    }
                    overlay.set(PaintedCells.CAGE, wallCell.x(), wallCell.y(), wallCell.z(),
                            EdgeCage.BARRIER);
                    wallCells.add(wallCell);
                }
            }
        }
    }

    public void resend(Player player) {
        if (!placed || overlay == null || player == null) {
            return;
        }
        final int at = generation;
        if (plugin == null) {
            overlay.resend(player);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (at != generation || !placed || !player.isOnline()) {
                return;
            }
            overlay.resend(player);
        }, RESEND_DELAY_TICKS);
    }

    public void open() {
        if (!placed || opened) {
            return;
        }
        opened = true;
        dropWalls();
        startMoving();
        final int at = generation;
        if (plugin == null) {
            removeGates();
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (at != generation) {
                return;
            }
            removeGates();
        }, GATE_REMOVE_DELAY_TICKS);
    }

    public void remove() {
        placed = false;
        opened = false;
        generation++;
        floorCells.clear();
        wallCells.clear();
        if (overlay != null) {
            overlay.restore(PaintedCells.CAGE);
        }
        removeGates();
    }

    private void dropWalls() {
        if (overlay == null) {
            return;
        }
        for (Cell cell : wallCells) {
            if (floorCells.contains(cell)) {
                continue;
            }
            overlay.clear(PaintedCells.CAGE, cell.x(), cell.y(), cell.z());
        }
        wallCells.clear();
        for (Cell cell : floorCells) {
            overlay.blank(PaintedCells.CAGE, cell.x(), cell.y(), cell.z());
        }
    }

    private void removeGates() {
        for (Gate gate : gates) {
            try {
                gate.display().remove();
            } catch (RuntimeException ignored) {
                continue;
            }
        }
        gates.clear();
    }

    private void spawnGates(PlayerState spawn, String gateData) {
        if (world == null || gateData == null) {
            return;
        }
        Location base = new Location(world, spawn.x, spawn.y, spawn.z);
        base.setYaw(SPAWN_YAW);
        base.setPitch(0f);

        Location leftGate = base.clone().subtract(0, 1, 1);
        Location leftGateEnd = base.clone().subtract(0, 2.5, 3.5);
        leftGate.setYaw(leftGate.getYaw() + 180);
        leftGateEnd.setYaw(leftGateEnd.getYaw() + 180);

        Location rightGate = base.clone().subtract(0, 1, -1);
        Location rightGateEnd = base.clone().subtract(0, 2.5, -3.5);

        spawnGate(leftGate, leftGateEnd, gateData);
        spawnGate(rightGate, rightGateEnd, gateData);
    }

    private void spawnGate(Location at, Location destination, String gateData) {
        try {
            sweepStaleGates(at);
            BlockDisplay display = world.spawn(at, BlockDisplay.class, entity -> {
                mark(entity);
                entity.setBlock(Bukkit.createBlockData(gateData));
                entity.setTeleportDuration(GATE_SPAWN_INTERPOLATION_TICKS);
                entity.setInterpolationDelay(0);
                entity.setInterpolationDuration(GATE_SPAWN_INTERPOLATION_TICKS);
                entity.setTransformation(new Transformation(
                        new Vector3f(-1f, 0f, -1.5f),
                        new AxisAngle4f(),
                        new Vector3f(1.5f, 0.5f, 3f),
                        new AxisAngle4f()
                ));
            });
            gates.add(new Gate(display, destination));
        } catch (RuntimeException ignored) {
            return;
        }
    }

    private void mark(BlockDisplay display) {
        if (plugin == null) {
            return;
        }
        display.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE, (byte) 1);
    }

    private void sweepStaleGates(Location at) {
        if (plugin == null || world == null) {
            return;
        }
        NamespacedKey tag = key(plugin);
        Collection<Entity> nearby = world.getNearbyEntities(at, GATE_SWEEP_RADIUS, GATE_SWEEP_RADIUS,
                GATE_SWEEP_RADIUS, entity -> entity instanceof BlockDisplay
                        && entity.getPersistentDataContainer().has(tag, PersistentDataType.BYTE));
        for (Entity entity : nearby) {
            try {
                entity.remove();
            } catch (RuntimeException ignored) {
                continue;
            }
        }
    }

    public static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, DISPLAY_KEY);
    }

    private void startMoving() {
        if (plugin == null || gates.isEmpty()) {
            return;
        }
        for (Gate gate : new ArrayList<>(gates)) {
            final BlockDisplay display = gate.display();
            final Location start = display.getLocation().clone();
            final Location destination = gate.destination().clone();
            final Transformation startTransformation = display.getTransformation();
            final int[] update = {0};

            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                if (!display.isValid()) {
                    task.cancel();
                    return;
                }
                update[0]++;
                double progress = Math.min(1.0, update[0] / (double) GATE_DURATION_TICKS);
                Location next = lerpLocation(start, destination, progress);
                float finalAngle = (float) Math.toRadians(GATE_SWING_DEGREES * progress);

                display.setTeleportDuration(GATE_INTERPOLATION_TICKS);
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(GATE_INTERPOLATION_TICKS);
                display.teleportAsync(next);
                display.setTransformation(new Transformation(
                        startTransformation.getTranslation(),
                        new Quaternionf().rotateZ(finalAngle),
                        startTransformation.getScale(),
                        startTransformation.getRightRotation()
                ));

                if (progress >= 1.0) {
                    task.cancel();
                }
            }, 1L, 1L);
        }
    }

    private static Location lerpLocation(Location from, Location to, double progress) {
        double x = lerp(from.getX(), to.getX(), progress);
        double y = lerp(from.getY(), to.getY(), progress);
        double z = lerp(from.getZ(), to.getZ(), progress);

        float yaw = lerpYaw(from.getYaw(), to.getYaw(), progress);
        float pitch = (float) lerp(from.getPitch(), to.getPitch(), progress);

        return new Location(from.getWorld(), x, y, z, yaw, pitch);
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    private static float lerpYaw(float from, float to, double progress) {
        float difference = ((to - from + 540f) % 360f) - 180f;
        return from + difference * (float) progress;
    }

    private static PlayerState anchor(GameState state, int slot) {
        if (state.roundInitial != null && slot < state.roundInitial.length
                && state.roundInitial[slot] != null) {
            return state.roundInitial[slot];
        }
        return slot < state.players.length ? state.players[slot] : null;
    }
}
