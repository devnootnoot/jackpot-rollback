package me.nootnoot.edge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import me.nootnoot.sim.ArenaCodec;
import me.nootnoot.sim.ArenaHash;
import me.nootnoot.sim.state.Arena;

public final class EdgeArena {

    private final Arena arena;
    private final ArenaCodec.Snapshot snapshot;
    private final String source;
    private final int blocks;
    private final boolean fromFile;
    private final long hash;

    private EdgeArena(Arena arena, ArenaCodec.Snapshot snapshot, String source, int blocks,
                      boolean fromFile) {
        this.arena = arena;
        this.snapshot = snapshot;
        this.source = source;
        this.blocks = blocks;
        this.fromFile = fromFile;
        this.hash = ArenaHash.of(arena);
    }

    public static EdgeArena flat(double groundY) {
        return new EdgeArena(Arena.flat(groundY), null, "flat", 0, false);
    }

    public static EdgeArena fromFile(File file) throws IOException {
        return fromBytes(Files.readAllBytes(file.toPath()), file.getName());
    }

    public static EdgeArena fromBytes(byte[] blob, String source) {
        ArenaCodec.Snapshot snapshot = ArenaCodec.decode(blob);
        return new EdgeArena(ArenaCodec.toArena(snapshot), snapshot, source,
                snapshot.blocks().length, true);
    }

    public Arena arena() {
        return arena;
    }

    public ArenaCodec.Snapshot snapshot() {
        return snapshot;
    }

    public String source() {
        return source;
    }

    public int blocks() {
        return blocks;
    }

    public boolean fromFile() {
        return fromFile;
    }

    public long hash() {
        return hash;
    }

    public double groundY() {
        return arena.groundY;
    }

    public boolean simSolid(int x, int y, int z) {
        return arena.isSolidVoxel(x, y, z) || arena.staticFillsCell(x, y, z);
    }

    public double standY(int x, int z) {
        if (!fromFile) {
            return arena.groundY;
        }
        int bottom = arena.baseY();
        for (int y = bottom + arena.sizeY() - 1; y >= bottom; y--) {
            if (simSolid(x, y, z)) {
                return y + 1.0;
            }
        }
        return arena.groundY;
    }

    public String describe() {
        if (!fromFile) {
            return "flat ground-y=" + arena.groundY;
        }
        return source + " blocks=" + blocks + " ground-y=" + arena.groundY
                + " base=" + arena.baseX() + "," + arena.baseY() + "," + arena.baseZ()
                + " size=" + arena.sizeX() + "x" + arena.sizeY() + "x" + arena.sizeZ()
                + " partial-boxes=" + arena.partialBoxes().length;
    }
}
