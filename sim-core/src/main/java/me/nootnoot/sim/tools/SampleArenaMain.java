package me.nootnoot.sim.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.ArenaCodec;
import me.nootnoot.sim.ArenaHash;
import me.nootnoot.sim.state.Arena;

public final class SampleArenaMain {

    private static final int MARGIN = 2;
    private static final int BEDROCK = 1;
    private static final int DIRT = 2;
    private static final int GRASS = 3;
    private static final int WALL = 4;
    private static final int COVER = 5;

    private static final int FLOOR_DEPTH = 3;
    private static final int SPAWN_X = 4;
    private static final int SPAWN_CLEARANCE = 6;
    private static final int COVER_INSET = 4;
    private static final int LATTICE = 12;
    private static final int JITTER = 3;
    private static final int PILLAR_SIDE = 2;
    private static final int LOW_WALL_LENGTH = 7;
    private static final int LOW_WALL_HEIGHT = 2;

    private static final int MIN_RADIUS = 12;
    private static final int MAX_RADIUS = 1024;
    private static final int MIN_WALL_HEIGHT = 1;
    private static final int MAX_WALL_HEIGHT = 64;

    private SampleArenaMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: SampleArenaMain <out-file> [out-file...]");
            System.err.println("  -Darena.radius=<blocks>      half extent of the floor (default 48)");
            System.err.println("  -Darena.surface=<y>          y of the top solid floor layer (default -61)");
            System.err.println("  -Darena.wall-height=<blocks> perimeter wall height (default 8)");
            System.err.println("  -Darena.seed=<int>           cover layout seed (default 1337)");
            System.exit(2);
            return;
        }
        int radius = Integer.getInteger("arena.radius", 48);
        int surface = Integer.getInteger("arena.surface", -61);
        int wallHeight = Integer.getInteger("arena.wall-height", 8);
        int seed = Integer.getInteger("arena.seed", 1337);
        if (radius < MIN_RADIUS || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("arena.radius out of range: " + radius);
        }
        if (wallHeight < MIN_WALL_HEIGHT || wallHeight > MAX_WALL_HEIGHT) {
            throw new IllegalArgumentException("arena.wall-height out of range: " + wallHeight);
        }
        byte[] blob = build(radius, surface, wallHeight, seed);
        for (String arg : args) {
            Path out = Path.of(arg);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.write(out, blob);
            System.out.println("wrote " + out.toAbsolutePath() + " (" + blob.length + " bytes)");
        }
        ArenaCodec.Snapshot snapshot = ArenaCodec.decode(blob);
        Arena arena = ArenaCodec.toArena(snapshot);
        System.out.println("arena hash=0x" + Long.toHexString(ArenaHash.of(arena))
                + " ground-y=" + arena.groundY
                + " grid=" + arena.sizeX() + "x" + arena.sizeY() + "x" + arena.sizeZ()
                + " blocks=" + snapshot.blocks().length
                + " stand-y=" + (surface + 1));
        System.out.println("shape: floor " + (2 * radius + 1) + "x" + (2 * radius + 1)
                + " topped at y=" + surface
                + ", perimeter wall y=" + (surface + 1) + ".." + (surface + wallHeight)
                + ", point-symmetric cover from seed=" + seed
                + ", spawns clear at x=-" + SPAWN_X + "/x=+" + SPAWN_X + " z=0");
        System.out.println("the edge pastes this geometry into the world before frame 0;"
                + " both edges must load the SAME arena.bin");
    }

    private static byte[] build(int radius, int surface, int wallHeight, int seed) {
        int bottom = surface - FLOOR_DEPTH;
        int top = surface + wallHeight;
        int baseX = -radius - MARGIN;
        int baseY = bottom - MARGIN;
        int baseZ = -radius - MARGIN;
        int sizeX = 2 * radius + 1 + 2 * MARGIN;
        int sizeY = top - bottom + 1 + 2 * MARGIN;
        int sizeZ = sizeX;

        byte[] cells = new byte[sizeX * sizeY * sizeZ];
        Placer placer = new Placer(cells, baseX, baseY, baseZ, sizeX, sizeY, sizeZ);

        for (int z = -radius; z <= radius; z++) {
            for (int y = bottom; y <= surface; y++) {
                for (int x = -radius; x <= radius; x++) {
                    placer.set(x, y, z, y == bottom ? BEDROCK : (y == surface ? GRASS : DIRT));
                }
            }
        }

        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                if (x != -radius && x != radius && z != -radius && z != radius) {
                    continue;
                }
                for (int y = surface + 1; y <= top; y++) {
                    placer.set(x, y, z, WALL);
                }
            }
        }

        placeCover(placer, radius, surface, seed);

        List<int[]> blocks = new ArrayList<>();
        for (int dz = 0; dz < sizeZ; dz++) {
            for (int dy = 0; dy < sizeY; dy++) {
                for (int dx = 0; dx < sizeX; dx++) {
                    int index = cells[(dz * sizeY + dy) * sizeX + dx];
                    if (index == 0) {
                        continue;
                    }
                    blocks.add(new int[]{dx, dy, dz, index});
                }
            }
        }

        String[] palette = {"minecraft:air", "minecraft:bedrock", "minecraft:dirt",
                "minecraft:grass_block", "minecraft:stone_bricks", "minecraft:polished_andesite"};
        ArenaCodec.PaletteEntry[] geometry = {
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_SKIP, 0.0f, 0, 0, new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 3600000.0f, BEDROCK, 0,
                        new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 0.5f, DIRT, 0,
                        new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 0.5f, GRASS, 0,
                        new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 3600000.0f, WALL, 0,
                        new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 6.0f, COVER, 0,
                        new double[0][])};

        ArenaCodec.Snapshot snapshot = new ArenaCodec.Snapshot(0L, baseX, baseY, baseZ,
                sizeX, sizeY, sizeZ, palette, blocks.toArray(new int[0][]), geometry,
                (double) baseY);
        return ArenaCodec.encode(snapshot);
    }

    private static void placeCover(Placer placer, int radius, int surface, int seed) {
        int inner = radius - COVER_INSET;
        if (inner < LATTICE) {
            return;
        }
        Rng rng = new Rng(seed);
        for (int gz = -inner; gz <= inner; gz += LATTICE) {
            for (int gx = LATTICE / 2; gx <= inner; gx += LATTICE) {
                int roll = rng.next(100);
                int jx = rng.next(2 * JITTER + 1) - JITTER;
                int jz = rng.next(2 * JITTER + 1) - JITTER;
                int extra = rng.next(3);
                int px = gx + jx;
                int pz = gz + jz;
                if (roll < 30) {
                    continue;
                }
                boolean pillar = roll < 75;
                boolean alongX = (roll & 1) == 0;
                if (pillar) {
                    pillar(placer, radius, surface, px, pz, 3 + extra);
                    pillar(placer, radius, surface, -px - (PILLAR_SIDE - 1),
                            -pz - (PILLAR_SIDE - 1), 3 + extra);
                } else {
                    lowWall(placer, radius, surface, px, pz, alongX);
                    lowWall(placer, radius, surface, -px, -pz, alongX);
                }
            }
        }
    }

    private static void pillar(Placer placer, int radius, int surface, int x, int z, int height) {
        for (int dz = 0; dz < PILLAR_SIDE; dz++) {
            for (int dx = 0; dx < PILLAR_SIDE; dx++) {
                for (int y = surface + 1; y <= surface + height; y++) {
                    put(placer, radius, x + dx, y, z + dz);
                }
            }
        }
    }

    private static void lowWall(Placer placer, int radius, int surface, int x, int z,
                                boolean alongX) {
        int half = LOW_WALL_LENGTH / 2;
        for (int i = -half; i <= half; i++) {
            int bx = alongX ? x + i : x;
            int bz = alongX ? z : z + i;
            for (int y = surface + 1; y <= surface + LOW_WALL_HEIGHT; y++) {
                put(placer, radius, bx, y, bz);
            }
        }
    }

    private static void put(Placer placer, int radius, int x, int y, int z) {
        int limit = radius - 2;
        if (x < -limit || x > limit || z < -limit || z > limit) {
            return;
        }
        if (nearSpawn(x, z)) {
            return;
        }
        placer.set(x, y, z, COVER);
    }

    private static boolean nearSpawn(int x, int z) {
        return withinClearance(x, z, -SPAWN_X) || withinClearance(x, z, SPAWN_X);
    }

    private static boolean withinClearance(int x, int z, int spawnX) {
        int dx = x - spawnX;
        return dx * dx + z * z <= SPAWN_CLEARANCE * SPAWN_CLEARANCE;
    }

    private static final class Placer {

        private final byte[] cells;
        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;

        private Placer(byte[] cells, int baseX, int baseY, int baseZ,
                       int sizeX, int sizeY, int sizeZ) {
            this.cells = cells;
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        private void set(int x, int y, int z, int index) {
            int dx = x - baseX;
            int dy = y - baseY;
            int dz = z - baseZ;
            if (dx < 0 || dy < 0 || dz < 0 || dx >= sizeX || dy >= sizeY || dz >= sizeZ) {
                return;
            }
            cells[(dz * sizeY + dy) * sizeX + dx] = (byte) index;
        }
    }

    private static final class Rng {

        private long state;

        private Rng(long seed) {
            this.state = seed;
        }

        private int next(int bound) {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            return (int) Math.floorMod(z, (long) bound);
        }
    }
}
