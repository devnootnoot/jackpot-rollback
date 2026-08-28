package me.nootnoot.edge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import io.papermc.paper.math.Position;
import me.nootnoot.sim.ArenaCodec;
import me.nootnoot.sim.host.PaintedCells;
import me.nootnoot.sim.state.BlockStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

public final class EdgeArenaPaster {

    public static final int DEFAULT_BLOCKS_PER_TICK = 16_384;
    public static final int DEFAULT_MILLIS_PER_TICK = 30;

    public static final int CLIP_ENVELOPE_BELOW = 16;
    public static final int CLIP_ENVELOPE_ABOVE = 256;

    private static final int MIN_BLOCKS_PER_TICK = 64;
    private static final int MAX_BLOCKS_PER_TICK = 1_048_576;
    private static final int MIN_MILLIS_PER_TICK = 2;
    private static final int MAX_MILLIS_PER_TICK = 200;
    private static final long PROGRESS_INTERVAL_MS = 2000L;
    private static final int HEAVY_ARENA_BLOCKS = 750_000;

    private final Plugin plugin;
    private final Logger log;
    private final boolean enabled;
    private final int blocksPerTick;
    private final int millisPerTick;
    private final boolean refuseOnClip;
    private final Map<UUID, Job> jobs = new HashMap<>();
    private final Map<UUID, Map<Long, Stray>> strayCells = new HashMap<>();
    private final Map<UUID, Set<Overlay>> liveOverlays = new HashMap<>();

    public EdgeArenaPaster(Plugin plugin, Logger log, boolean enabled, int blocksPerTick,
                           int millisPerTick, boolean refuseOnClip) {
        this.plugin = plugin;
        this.log = log;
        this.enabled = enabled;
        this.blocksPerTick = Math.max(MIN_BLOCKS_PER_TICK,
                Math.min(MAX_BLOCKS_PER_TICK, blocksPerTick));
        this.millisPerTick = Math.max(MIN_MILLIS_PER_TICK,
                Math.min(MAX_MILLIS_PER_TICK, millisPerTick));
        this.refuseOnClip = refuseOnClip;
    }

    public boolean enabled() {
        return enabled;
    }

    public int blocksPerTick() {
        return blocksPerTick;
    }

    public int millisPerTick() {
        return millisPerTick;
    }

    public boolean refuseOnClip() {
        return refuseOnClip;
    }

    public boolean pastes(EdgeArena arena) {
        return enabled && arena != null && arena.snapshot() != null
                && arena.snapshot().blocks().length > 0;
    }

    public record Outcome(boolean ok, String reason) {

        static Outcome proceed() {
            return new Outcome(true, "");
        }

        static Outcome refused(String reason) {
            return new Outcome(false, reason);
        }
    }

    public record ClipReport(int blocks, int clipped, int clippedCollidable, int inEnvelope,
                             int neededMinY, int neededMaxY, int worldMinY, int worldMaxY,
                             double groundY) {

        public boolean clean() {
            return clipped == 0;
        }

        public boolean collidableLost() {
            return clippedCollidable > 0;
        }

        public String describe() {
            return clipped + " of " + blocks + " blocks fall outside the world height ("
                    + clippedCollidable + " of them are solid to the sim, " + inEnvelope
                    + " inside the play envelope); the arena needs y " + neededMinY + ".."
                    + neededMaxY + " and this world offers " + worldMinY + ".."
                    + (worldMaxY - 1);
        }
    }

    public ClipReport scan(World world, EdgeArena arena) {
        ArenaCodec.Snapshot snapshot = arena.snapshot();
        int[][] blocks = snapshot.blocks();
        ArenaCodec.PaletteEntry[] geometry = snapshot.geometry();
        int baseY = snapshot.baseY();
        int worldMinY = world.getMinHeight();
        int worldMaxY = world.getMaxHeight();
        double groundY = arena.groundY();
        double envelopeLow = groundY - CLIP_ENVELOPE_BELOW;
        double envelopeHigh = groundY + CLIP_ENVELOPE_ABOVE;

        int clipped = 0;
        int clippedCollidable = 0;
        int inEnvelope = 0;
        int neededMinY = Integer.MAX_VALUE;
        int neededMaxY = Integer.MIN_VALUE;
        for (int[] b : blocks) {
            int wy = baseY + b[1];
            if (wy < neededMinY) {
                neededMinY = wy;
            }
            if (wy > neededMaxY) {
                neededMaxY = wy;
            }
            if (wy >= worldMinY && wy < worldMaxY) {
                continue;
            }
            clipped++;
            if (!collidable(geometry, b[3])) {
                continue;
            }
            clippedCollidable++;
            if (wy >= envelopeLow && wy <= envelopeHigh) {
                inEnvelope++;
            }
        }
        if (neededMinY == Integer.MAX_VALUE) {
            neededMinY = worldMinY;
            neededMaxY = worldMinY;
        }
        return new ClipReport(blocks.length, clipped, clippedCollidable, inEnvelope,
                neededMinY, neededMaxY, worldMinY, worldMaxY, groundY);
    }

    private static boolean collidable(ArenaCodec.PaletteEntry[] geometry, int index) {
        if (geometry == null || index < 0 || index >= geometry.length) {
            return true;
        }
        int kind = geometry[index].kind();
        return kind == ArenaCodec.KIND_FULL_CUBE || kind == ArenaCodec.KIND_PARTIAL;
    }

    private String reportClipping(World world, EdgeArena arena, ClipReport clip) {
        if (clip.blocks() >= HEAVY_ARENA_BLOCKS) {
            log.info("this is a heavy arena: " + clip.blocks() + " blocks at " + blocksPerTick
                    + "/tick capped to " + millisPerTick + "ms/tick - expect the paste to take"
                    + " several seconds before frame 0");
        }
        if (clip.clean()) {
            return null;
        }
        log.severe("#### THE WORLD CANNOT HOLD THE WHOLE SIM ARENA ####");
        log.severe("  arena: " + arena.describe());
        log.severe("  world: " + world.getName() + " y " + clip.worldMinY() + ".."
                + (clip.worldMaxY() - 1));
        log.severe("  " + clip.describe());
        if (!clip.collidableLost()) {
            log.severe("  every clipped block is air/decor to the sim, so nothing the sim collides"
                    + " with is missing from the world - this is cosmetic only");
            return null;
        }
        log.severe("  the clipped blocks the sim STILL COLLIDES WITH are invisible to the player:"
                + " they are solid in the sim and absent from the world");
        if (clip.inEnvelope() <= 0) {
            log.severe("  none of them are within " + CLIP_ENVELOPE_BELOW + " blocks below or "
                    + CLIP_ENVELOPE_ABOVE + " blocks above ground-y=" + clip.groundY()
                    + ", so the fight area itself is intact - continuing");
            return null;
        }
        log.severe("  " + clip.inEnvelope() + " of them ARE inside the play envelope (ground-y "
                + clip.groundY() + " -" + CLIP_ENVELOPE_BELOW + "/+" + CLIP_ENVELOPE_ABOVE
                + ") - players would walk into walls that are not there");
        if (!refuseOnClip) {
            log.severe("  arena.refuse-on-clip is false, so the match is starting anyway");
            return null;
        }
        log.severe("  refusing the match. Give this world a min-height of " + clip.neededMinY()
                + " and a max-height of " + (clip.neededMaxY() + 1)
                + " (a datapack dimension_type), or set arena.refuse-on-clip: false to override.");
        return "the world (y " + clip.worldMinY() + ".." + (clip.worldMaxY() - 1)
                + ") cannot hold the arena (y " + clip.neededMinY() + ".." + clip.neededMaxY()
                + "); " + clip.inEnvelope() + " solid blocks around the fight would be missing";
    }

    private record Stray(long arenaHash, BlockData original) {
    }

    private void sweepStrayCells(World world, long arenaHash) {
        if (world == null) {
            return;
        }
        Map<Long, Stray> stray = strayCells.get(world.getUID());
        if (stray == null || stray.isEmpty()) {
            return;
        }
        List<Map.Entry<Long, Stray>> entries = new ArrayList<>(stray.entrySet());
        List<Map.Entry<Long, Stray>> orphans = new ArrayList<>();
        int held = 0;
        int foreign = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Map.Entry<Long, Stray> entry = entries.get(i);
            if (heldByALiveOverlay(world, entry.getKey())) {
                held++;
                continue;
            }
            if (entry.getValue().arenaHash() != arenaHash) {
                foreign++;
                stray.remove(entry.getKey());
                continue;
            }
            orphans.add(entry);
        }
        if (foreign > 0) {
            log.severe("dropping " + foreign + " stray cell(s) in " + world.getName()
                    + " that were painted while a different arena stood here. What the journal"
                    + " recorded under them belonged to that arena, so writing it back now would"
                    + " stamp the old arena into this world. Those cells are left as they stand"
                    + " and this journal cannot repair them.");
        }
        if (!orphans.isEmpty()) {
            log.severe("restoring " + orphans.size() + " block(s) in " + world.getName()
                    + " that a previous match painted and never put back. A match that ends"
                    + " without reaching its teardown leaves its overlay unrestored, and the arena"
                    + " blob carries no air cells, so those blocks were still standing when this"
                    + " arena was pasted over them. This match starts clean.");
            for (Map.Entry<Long, Stray> orphan : orphans) {
                long key = orphan.getKey();
                int x = BlockStore.unpackX(key);
                int y = BlockStore.unpackY(key);
                int z = BlockStore.unpackZ(key);
                try {
                    world.getBlockAt(x, y, z).setBlockData(orphan.getValue().original(), false);
                    stray.remove(key);
                } catch (RuntimeException ex) {
                    log.warning("failed to restore a stray block at " + x + "," + y + "," + z
                            + ": " + ex);
                }
            }
        } else if (held > 0) {
            log.info("the stray-cell journal for " + world.getName() + " holds " + held
                    + " cell(s) a live match still owns - leaving them alone");
        }
        if (stray.isEmpty()) {
            strayCells.remove(world.getUID());
        }
    }

    private boolean heldByALiveOverlay(World world, long key) {
        Set<Overlay> live = liveOverlays.get(world.getUID());
        if (live == null || live.isEmpty()) {
            return false;
        }
        int x = BlockStore.unpackX(key);
        int y = BlockStore.unpackY(key);
        int z = BlockStore.unpackZ(key);
        for (Overlay overlay : live) {
            if (overlay.holds(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    void retireOverlay(World world, Overlay overlay) {
        if (world == null) {
            return;
        }
        Set<Overlay> live = liveOverlays.get(world.getUID());
        if (live == null) {
            return;
        }
        live.remove(overlay);
        if (live.isEmpty()) {
            liveOverlays.remove(world.getUID());
        }
    }

    void rememberStray(World world, long key, BlockData original) {
        if (world == null) {
            return;
        }
        Job job = jobs.get(world.getUID());
        strayCells.computeIfAbsent(world.getUID(), k -> new LinkedHashMap<>())
                .putIfAbsent(key, new Stray(job == null ? 0L : job.hash, original));
    }

    void forgetStray(World world, long key) {
        if (world == null) {
            return;
        }
        Map<Long, Stray> stray = strayCells.get(world.getUID());
        if (stray != null) {
            stray.remove(key);
        }
    }

    public Outcome acquire(World world, EdgeArena arena, Runnable onReady) {
        if (!pastes(arena)) {
            sweepStrayCells(world, 0L);
            onReady.run();
            return Outcome.proceed();
        }
        Job job = jobs.get(world.getUID());
        if (job != null && job.hash != arena.hash()) {
            if (job.refs > 0) {
                log.severe("refusing to paste " + arena.describe() + " into " + world.getName()
                        + ": a different arena (hash " + Long.toHexString(job.hash) + ") is"
                        + " pasted there and " + job.refs + " live match(es) are still on it");
                return Outcome.refused("another arena is already pasted in " + world.getName());
            }
            ClipReport swapClip = scan(world, arena);
            String swapRefusal = reportClipping(world, arena, swapClip);
            if (swapRefusal != null) {
                return Outcome.refused(swapRefusal);
            }
            log.info("a different arena (hash " + Long.toHexString(job.hash) + ") is still"
                    + " standing in " + world.getName() + " with no match on it - taking it back"
                    + " out before pasting " + arena.describe());
            job.swapTo(arena, swapClip, onReady);
            return Outcome.proceed();
        }
        if (job == null) {
            ClipReport clip = scan(world, arena);
            String refusal = reportClipping(world, arena, clip);
            if (refusal != null) {
                return Outcome.refused(refusal);
            }
            job = new Job(world, arena, clip);
            jobs.put(world.getUID(), job);
        }
        job.acquire(onReady);
        return Outcome.proceed();
    }

    public boolean ready(World world) {
        if (world == null) {
            return true;
        }
        Job job = jobs.get(world.getUID());
        return job == null || job.state == State.READY;
    }

    public void release(World world) {
        if (world == null) {
            return;
        }
        Job job = jobs.get(world.getUID());
        if (job != null) {
            job.release();
        }
    }

    public void shutdown() {
        for (Job job : new ArrayList<>(jobs.values())) {
            job.cancelTask();
            job.revertAll();
        }
        jobs.clear();
        liveOverlays.clear();
        strayCells.clear();
    }

    public Overlay newOverlay(World world) {
        Overlay overlay = new Overlay(this, world, log);
        if (world != null) {
            liveOverlays.computeIfAbsent(world.getUID(), k -> new LinkedHashSet<>()).add(overlay);
        }
        return overlay;
    }

    private static BlockData resolveData(Logger log, String descriptor, String what) {
        try {
            return Bukkit.createBlockData(descriptor);
        } catch (IllegalArgumentException | NullPointerException ex) {
            log.warning(what + " (" + descriptor + ") is not a block state this server"
                    + " understands - using stone instead");
            return Bukkit.createBlockData(Material.STONE);
        }
    }

    public static final class Overlay {

        private final World world;
        private final Logger log;
        private static final int OUT_OF_WORLD_LOG_INTERVAL = 64;

        private final Map<Cell, BlockData> previous = new LinkedHashMap<>();
        private final Map<String, BlockData> resolved = new HashMap<>();
        private final PaintedCells painted = new PaintedCells();
        private final Map<Integer, Set<Long>> lost = new LinkedHashMap<>();

        private int outOfWorld;

        private final EdgeArenaPaster owner;

        private Overlay(EdgeArenaPaster owner, World world, Logger log) {
            this.owner = owner;
            this.world = world;
            this.log = log;
        }

        public int size(int painter) {
            int n = 0;
            for (Cell cell : previous.keySet()) {
                if (painted.paintedBy(key(cell)) == painter) {
                    n++;
                }
            }
            return n;
        }

        public boolean empty() {
            return previous.isEmpty();
        }

        private static long key(Cell cell) {
            return BlockStore.key(cell.x(), cell.y(), cell.z());
        }

        public Material cover(int x, int y, int z) {
            if (world == null) {
                return null;
            }
            BlockData original = previous.get(new Cell(x, y, z));
            if (original != null) {
                return original.getMaterial();
            }
            return world.getBlockAt(x, y, z).getType();
        }

        public boolean owns(int painter, int x, int y, int z) {
            int owner = painted.paintedBy(BlockStore.key(x, y, z));
            return owner == PaintedCells.NOBODY || owner == painter;
        }

        public Set<Long> lost(int painter) {
            Set<Long> out = lost.remove(painter);
            return out == null ? Set.of() : out;
        }

        public void set(int painter, int x, int y, int z, String descriptor) {
            if (world == null) {
                return;
            }
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                outOfWorld++;
                if (outOfWorld == 1 || outOfWorld % OUT_OF_WORLD_LOG_INTERVAL == 0) {
                    log.severe("overlay block " + descriptor + " at " + x + "," + y + "," + z
                            + " is outside " + world.getName() + " (y " + world.getMinHeight()
                            + ".." + (world.getMaxHeight() - 1) + ") and was dropped (" + outOfWorld
                            + " so far) - the sim still has it, so the player sees nothing there"
                            + " while the sim collides with it");
                }
                return;
            }
            Block block = world.getBlockAt(x, y, z);
            BlockData current = block.getBlockData();
            Cell here = new Cell(x, y, z);
            if (previous.putIfAbsent(here, current) == null) {
                owner.rememberStray(world, BlockStore.key(x, y, z), current);
            }
            BlockData target = resolved.get(descriptor);
            if (target == null) {
                target = resolveData(log, descriptor, "overlay block");
                resolved.put(descriptor, target);
            }
            if (!current.equals(target)) {
                block.setBlockData(target, false);
            }
            long key = BlockStore.key(x, y, z);
            int owner = painted.paintedBy(key);
            if (owner != PaintedCells.NOBODY && owner != painter) {
                lost.computeIfAbsent(owner, k -> new LinkedHashSet<>()).add(key);
            }
            painted.paint(key, painter);
        }

        public boolean holds(int x, int y, int z) {
            return previous.containsKey(new Cell(x, y, z));
        }

        public void blank(int painter, int x, int y, int z) {
            if (world == null || !previous.containsKey(new Cell(x, y, z))
                    || !owns(painter, x, y, z)) {
                return;
            }
            try {
                Block block = world.getBlockAt(x, y, z);
                if (block.getType() != Material.AIR) {
                    block.setType(Material.AIR, false);
                }
            } catch (RuntimeException ex) {
                log.warning("failed to blank an overlay block at " + x + "," + y + "," + z + ": " + ex);
            }
        }

        public void resend(Player player) {
            if (world == null || player == null || previous.isEmpty()) {
                return;
            }
            Map<Position, BlockData> changes = new HashMap<>(previous.size());
            for (Cell cell : previous.keySet()) {
                try {
                    Block block = world.getBlockAt(cell.x(), cell.y(), cell.z());
                    changes.put(Position.block(cell.x(), cell.y(), cell.z()), block.getBlockData());
                } catch (RuntimeException ignored) {
                    continue;
                }
            }
            if (changes.isEmpty()) {
                return;
            }
            try {
                player.sendMultiBlockChange(changes);
            } catch (RuntimeException ex) {
                log.warning("failed to resend the cage blocks: " + ex);
            }
        }

        public void clear(int painter, int x, int y, int z) {
            if (world == null || !owns(painter, x, y, z)) {
                return;
            }
            long key = BlockStore.key(x, y, z);
            painted.release(key);
            BlockData original = previous.remove(new Cell(x, y, z));
            if (original == null) {
                return;
            }
            try {
                world.getBlockAt(x, y, z).setBlockData(original, false);
                owner.forgetStray(world, key);
            } catch (RuntimeException ex) {
                log.warning("failed to clear an overlay block at " + x + "," + y + "," + z + ": " + ex);
            }
        }

        public void restore(int painter) {
            if (previous.isEmpty()) {
                return;
            }
            List<Map.Entry<Cell, BlockData>> entries = new ArrayList<>(previous.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                Cell cell = entries.get(i).getKey();
                if (painted.paintedBy(key(cell)) != painter) {
                    continue;
                }
                painted.release(key(cell));
                previous.remove(cell);
                lost.values().forEach(set -> set.remove(key(cell)));
                try {
                    world.getBlockAt(cell.x(), cell.y(), cell.z())
                            .setBlockData(entries.get(i).getValue(), false);
                    owner.forgetStray(world, key(cell));
                } catch (RuntimeException ex) {
                    log.warning("failed to restore an overlay block at " + cell.x() + ","
                            + cell.y() + "," + cell.z() + ": " + ex);
                }
            }
        }

        public void restoreAll() {
            owner.retireOverlay(world, this);
            if (world == null || previous.isEmpty()) {
                return;
            }
            List<Map.Entry<Cell, BlockData>> entries = new ArrayList<>(previous.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                Cell cell = entries.get(i).getKey();
                painted.release(key(cell));
                previous.remove(cell);
                lost.values().forEach(set -> set.remove(key(cell)));
                try {
                    world.getBlockAt(cell.x(), cell.y(), cell.z())
                            .setBlockData(entries.get(i).getValue(), false);
                    owner.forgetStray(world, key(cell));
                } catch (RuntimeException ex) {
                    log.warning("failed to restore an overlay block at " + cell.x() + ","
                            + cell.y() + "," + cell.z() + ": " + ex);
                }
            }
        }

        private record Cell(int x, int y, int z) {
        }
    }

    private enum State {
        PASTING,
        READY,
        REVERTING
    }

    private final class Job {

        private final World world;
        private final Map<BlockData, BlockData> canonical = new HashMap<>();
        private final List<Runnable> waiters = new ArrayList<>();

        private long hash;
        private String describe;
        private String[] palette;
        private int[][] blocks;
        private BlockData[] resolved;
        private BlockData[] originals;
        private int baseX;
        private int baseY;
        private int baseZ;
        private ClipReport clip;
        private EdgeArena next;
        private ClipReport nextClip;

        private State state = State.PASTING;
        private BukkitTask task;
        private int refs;
        private int cursor;
        private int written;
        private int revertCursor;
        private int skipped;
        private int overwrote;
        private boolean restart;
        private long startedAt;
        private long lastProgressAt;

        private Job(World world, EdgeArena arena, ClipReport clip) {
            this.world = world;
            adopt(arena, clip);
        }

        private void adopt(EdgeArena arena, ClipReport report) {
            ArenaCodec.Snapshot snapshot = arena.snapshot();
            this.hash = arena.hash();
            this.describe = arena.describe();
            this.palette = snapshot.palette();
            this.blocks = snapshot.blocks();
            this.baseX = snapshot.baseX();
            this.baseY = snapshot.baseY();
            this.baseZ = snapshot.baseZ();
            this.clip = report;
            this.resolved = new BlockData[palette.length];
            this.originals = new BlockData[blocks.length];
            this.cursor = 0;
            this.written = 0;
            this.skipped = 0;
            this.overwrote = 0;
        }

        private void swapTo(EdgeArena arena, ClipReport report, Runnable onReady) {
            refs++;
            waiters.add(onReady);
            next = arena;
            nextClip = report;
            restart = true;
            if (state != State.REVERTING) {
                beginRevert();
            }
        }

        private void acquire(Runnable onReady) {
            refs++;
            if (state == State.READY) {
                onReady.run();
                return;
            }
            waiters.add(onReady);
            if (state == State.REVERTING) {
                restart = true;
                return;
            }
            startTask();
        }

        private void release() {
            if (refs > 0) {
                refs--;
            }
            if (refs > 0) {
                return;
            }
            waiters.clear();
            restart = false;
            beginRevert();
        }

        private void startTask() {
            if (task != null) {
                return;
            }
            startedAt = System.currentTimeMillis();
            lastProgressAt = startedAt;
            log.info("pasting arena " + describe + " into " + world.getName() + ": "
                    + blocks.length + " blocks at up to " + blocksPerTick + "/tick, capped at "
                    + millisPerTick + "ms/tick"
                    + (clip.clean() ? "" : " (" + clip.clipped() + " will be skipped: outside the"
                            + " world height)"));
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::step, 1L, 1L);
        }

        private void cancelTask() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }

        private void step() {
            if (state == State.PASTING) {
                pasteStep();
            } else if (state == State.REVERTING) {
                revertStep();
            }
        }

        private void pasteStep() {
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight();
            int budget = blocksPerTick;
            long deadline = System.nanoTime() + millisPerTick * 1_000_000L;
            while (cursor < blocks.length && budget-- > 0) {
                int[] b = blocks[cursor];
                cursor++;
                int wy = baseY + b[1];
                if (wy < minY || wy >= maxY) {
                    skipped++;
                    continue;
                }
                Block block = world.getBlockAt(baseX + b[0], wy, baseZ + b[2]);
                BlockData previous = canonicalize(block.getBlockData());
                BlockData target = data(b[3]);
                originals[cursor - 1] = previous;
                if (!previous.equals(target)) {
                    if (!previous.getMaterial().isAir()) {
                        overwrote++;
                    }
                    block.setBlockData(target, false);
                }
                if ((cursor & 0xFF) == 0 && System.nanoTime() >= deadline) {
                    break;
                }
            }
            written = cursor;
            if (cursor < blocks.length) {
                progress();
                return;
            }
            cancelTask();
            state = State.READY;
            log.info("arena paste complete in " + world.getName() + ": " + written + " blocks in "
                    + (System.currentTimeMillis() - startedAt) + "ms"
                    + (skipped > 0 ? " (" + skipped + " outside the world height)" : ""));
            if (skipped != clip.clipped()) {
                log.severe("the arena paste skipped " + skipped + " blocks but the pre-paste scan"
                        + " expected " + clip.clipped() + " - the world height changed underneath"
                        + " the paste, so the world no longer matches what the sim collides with");
            }
            sweepStrayCells(world, hash);
            List<Runnable> pendingWaiters = new ArrayList<>(waiters);
            waiters.clear();
            for (Runnable waiter : pendingWaiters) {
                waiter.run();
            }
        }

        private void progress() {
            long now = System.currentTimeMillis();
            if (now - lastProgressAt < PROGRESS_INTERVAL_MS) {
                return;
            }
            lastProgressAt = now;
            long elapsed = Math.max(1L, now - startedAt);
            long remaining = (long) (blocks.length - cursor) * elapsed / Math.max(1, cursor);
            log.info("arena paste " + (100L * cursor / blocks.length) + "% (" + cursor + "/"
                    + blocks.length + " blocks, ~" + remaining + "ms left)");
        }

        private void beginRevert() {
            cancelTask();
            state = State.REVERTING;
            revertCursor = written - 1;
            startedAt = System.currentTimeMillis();
            log.info("reverting the arena in " + world.getName() + ": " + written + " blocks");
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::step, 1L, 1L);
        }

        private void revertStep() {
            int budget = blocksPerTick;
            long deadline = System.nanoTime() + millisPerTick * 1_000_000L;
            while (revertCursor >= 0 && budget-- > 0) {
                restore(revertCursor);
                revertCursor--;
                if ((revertCursor & 0xFF) == 0 && System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (revertCursor >= 0) {
                return;
            }
            cancelTask();
            log.info("arena revert complete in " + world.getName() + " in "
                    + (System.currentTimeMillis() - startedAt) + "ms");
            written = 0;
            cursor = 0;
            skipped = 0;
            overwrote = 0;
            if (refs > 0 && restart) {
                restart = false;
                if (next != null) {
                    adopt(next, nextClip);
                    next = null;
                    nextClip = null;
                }
                state = State.PASTING;
                startTask();
                return;
            }
            jobs.remove(world.getUID());
        }

        private void revertAll() {
            for (int i = written - 1; i >= 0; i--) {
                restore(i);
            }
            written = 0;
            cursor = 0;
        }

        private void restore(int index) {
            BlockData previous = originals[index];
            if (previous == null) {
                return;
            }
            originals[index] = null;
            int[] b = blocks[index];
            world.getBlockAt(baseX + b[0], baseY + b[1], baseZ + b[2])
                    .setBlockData(previous, false);
        }

        private BlockData canonicalize(BlockData data) {
            BlockData existing = canonical.putIfAbsent(data, data);
            return existing != null ? existing : data;
        }

        private BlockData data(int index) {
            BlockData cached = resolved[index];
            if (cached != null) {
                return cached;
            }
            String descriptor = index < palette.length ? palette[index] : null;
            BlockData created = resolveData(log, descriptor, "arena palette entry " + index);
            resolved[index] = created;
            return created;
        }
    }
}
