package me.nootnoot.sim.tools;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import me.nootnoot.sim.ArenaCodec;

public final class FarenaArena {

    public static final int MAGIC = 0x46534152;
    public static final int VERSION_LEGACY = 2;
    public static final int VERSION_PLAIN = 3;
    public static final int VERSION_GZIP = 4;

    public static final String EXTENSION = ".farena";
    public static final int SECTION_CELLS = 4096;
    public static final int EXTRACT_MARGIN = 2;
    public static final int DEFAULT_PLAY_RADIUS = 110;
    public static final int DEFAULT_MAX_BLOCKS = 8_000_000;
    public static final int MAX_SPAN = 65536;
    public static final int MAX_PALETTE = 65535;

    private FarenaArena() {
    }

    public record Spawn(double x, double y, double z, float yaw, float pitch) {

        public String describe() {
            return String.format(Locale.ROOT, "(%.4f,%.4f,%.4f yaw=%.2f pitch=%.2f)",
                    x, y, z, yaw, pitch);
        }
    }

    public record Loaded(String name, byte[] blob, Spawn spawn0, Spawn spawn1, double groundY,
                         int blocks, int paletteSize, String geometry,
                         int baseX, int baseY, int baseZ, int sizeX, int sizeY, int sizeZ) {

        public String describe() {
            return String.format(Locale.ROOT,
                    "%s: %d blocks, %d palette states, %dB gzip, ground-y=%.2f,"
                            + " base=(%d,%d,%d) size=(%d,%d,%d), spawn0=%s spawn1=%s",
                    name, blocks, paletteSize, blob.length, groundY,
                    baseX, baseY, baseZ, sizeX, sizeY, sizeZ,
                    spawn0.describe(), spawn1.describe());
        }
    }

    public record Schematic(List<Spawn> spawns, List<String> palette, List<Section> sections) {
    }

    public record Section(int chunkOffsetX, int chunkOffsetZ, int sectionOffsetY, char[] ids) {

        public char idAt(int packed) {
            return ids.length == 1 ? ids[0] : ids[packed];
        }
    }

    public static List<String> names(Path directory) throws IOException {
        if (directory == null || !Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(Locale.ROOT).endsWith(EXTENSION))
                    .map(n -> n.substring(0, n.length() - EXTENSION.length()))
                    .sorted()
                    .toList();
        }
    }

    public static Path resolve(Path directory, String name) {
        return directory.resolve(name.toLowerCase(Locale.ROOT) + EXTENSION);
    }

    public static Loaded load(Path file, String name, int playRadius, int maxBlocks)
            throws IOException {
        Schematic schematic = read(file);
        List<Spawn> spawns = schematic.spawns();
        if (spawns.size() < 2) {
            throw new IllegalStateException(name + " defines " + spawns.size()
                    + " spawn points; an edge duel needs two");
        }
        List<String> palette = schematic.palette();
        if (palette.size() > MAX_PALETTE) {
            throw new IllegalStateException(name + " has " + palette.size()
                    + " palette states; the 16-bit arena wire allows at most " + MAX_PALETTE);
        }
        ArenaCodec.PaletteEntry[] geometry = DevPaletteGeometry.of(palette);
        Spawn s0 = restOnFloor(spawns.get(0), schematic, geometry);
        Spawn s1 = restOnFloor(spawns.get(1), schematic, geometry);
        int cx = (int) Math.round((s0.x() + s1.x()) / 2.0);
        int cy = (int) Math.round((s0.y() + s1.y()) / 2.0);
        int cz = (int) Math.round((s0.z() + s1.z()) / 2.0);

        Extracted ex = extract(schematic, cx, cy, cz, playRadius, maxBlocks);
        if (ex.blocks().isEmpty()) {
            throw new IllegalStateException(name + " extracted zero blocks around (" + cx + ","
                    + cy + "," + cz + ") radius " + playRadius);
        }
        checkSpan(ex.sizeX(), "X", name);
        checkSpan(ex.sizeY(), "Y", name);
        checkSpan(ex.sizeZ(), "Z", name);

        double groundY = Math.min(s0.y(), s1.y());
        ArenaCodec.Snapshot snapshot = new ArenaCodec.Snapshot(0L,
                ex.baseX(), ex.baseY(), ex.baseZ(), ex.sizeX(), ex.sizeY(), ex.sizeZ(),
                palette.toArray(new String[0]), ex.blocks().toArray(new int[0][]),
                geometry, groundY);
        byte[] blob = ArenaCodec.encode(snapshot);
        return new Loaded(name, blob, s0, s1, groundY, ex.blocks().size(), palette.size(),
                DevPaletteGeometry.describe(geometry),
                ex.baseX(), ex.baseY(), ex.baseZ(), ex.sizeX(), ex.sizeY(), ex.sizeZ());
    }

    static Spawn restOnFloor(Spawn spawn, Schematic schematic,
                             ArenaCodec.PaletteEntry[] geometry) {
        int bx = (int) Math.floor(spawn.x());
        int bz = (int) Math.floor(spawn.z());
        double best = Double.NEGATIVE_INFINITY;
        for (Section section : schematic.sections()) {
            int sx = section.chunkOffsetX() << 4;
            int sz = section.chunkOffsetZ() << 4;
            int sy = section.sectionOffsetY() << 4;
            int lx = bx - sx;
            int lz = bz - sz;
            if (lx < 0 || lx > 15 || lz < 0 || lz > 15) {
                continue;
            }
            for (int ly = 0; ly < 16; ly++) {
                int worldY = sy + ly;
                if (worldY >= spawn.y()) {
                    continue;
                }
                int idx = section.idAt((lz << 8) | (ly << 4) | lx);
                if (idx < 0 || idx >= geometry.length) {
                    continue;
                }
                double top = standingTop(worldY, geometry[idx]);
                if (top > best) {
                    best = top;
                }
            }
        }
        if (best == Double.NEGATIVE_INFINITY
                || spawn.y() - best <= SpawnSupport.MAX_DROP_TO_FLOOR) {
            return spawn;
        }
        return new Spawn(spawn.x(), best, spawn.z(), spawn.yaw(), spawn.pitch());
    }

    private static double standingTop(int blockY, ArenaCodec.PaletteEntry entry) {
        if (entry.kind() == ArenaCodec.KIND_FULL_CUBE) {
            return blockY + 1.0;
        }
        if (entry.kind() != ArenaCodec.KIND_PARTIAL || entry.partialBoxes() == null) {
            return Double.NEGATIVE_INFINITY;
        }
        double top = Double.NEGATIVE_INFINITY;
        for (double[] box : entry.partialBoxes()) {
            if (box.length >= 5 && box[4] > top) {
                top = box[4];
            }
        }
        return top == Double.NEGATIVE_INFINITY ? top : blockY + top;
    }

    public static Schematic read(Path file) throws IOException {
        try (InputStream raw = Files.newInputStream(file);
             BufferedInputStream buffered = new BufferedInputStream(raw)) {
            DataInputStream header = new DataInputStream(buffered);
            int magic = header.readInt();
            if (magic != MAGIC) {
                throw new IllegalStateException(file + " is not a .farena file (magic 0x"
                        + Integer.toHexString(magic) + ", expected 0x" + Integer.toHexString(MAGIC)
                        + ")");
            }
            int version = header.readInt();
            if (version == VERSION_GZIP) {
                try (GZIPInputStream gzip = new GZIPInputStream(buffered);
                     DataInputStream body = new DataInputStream(gzip)) {
                    return readBody(body, version);
                }
            }
            if (version == VERSION_PLAIN || version == VERSION_LEGACY) {
                return readBody(header, version);
            }
            throw new IllegalStateException("unsupported .farena version: " + version);
        }
    }

    private static Schematic readBody(DataInputStream in, int version) throws IOException {
        int anchorX = in.readInt();
        int anchorY = in.readInt();
        int anchorZ = in.readInt();

        int spawnCount = in.readInt();
        List<Spawn> spawns = new ArrayList<>(Math.max(0, spawnCount));
        for (int i = 0; i < spawnCount; i++) {
            spawns.add(new Spawn(in.readDouble(), in.readDouble(), in.readDouble(),
                    in.readFloat(), in.readFloat()));
        }

        int paletteSize = in.readInt();
        List<String> palette = new ArrayList<>(Math.max(0, paletteSize));
        for (int i = 0; i < paletteSize; i++) {
            palette.add(in.readUTF());
        }

        int sectionCount = in.readInt();
        List<Section> sections = new ArrayList<>(Math.max(0, sectionCount));
        for (int i = 0; i < sectionCount; i++) {
            int chunkOffsetX = in.readInt();
            int chunkOffsetZ = in.readInt();
            int sectionOffsetY = in.readInt();
            char[] ids = new char[SECTION_CELLS];
            for (int j = 0; j < SECTION_CELLS; j++) {
                ids[j] = (char) in.readInt();
            }
            sections.add(new Section(chunkOffsetX, chunkOffsetZ, sectionOffsetY, ids));
        }

        if (version != VERSION_LEGACY) {
            return new Schematic(spawns, palette, sections);
        }
        List<Spawn> shifted = new ArrayList<>(spawns.size());
        for (Spawn spawn : spawns) {
            shifted.add(new Spawn(spawn.x() - anchorX * 16.0, spawn.y() - anchorY * 16.0,
                    spawn.z() - anchorZ * 16.0, spawn.yaw(), spawn.pitch()));
        }
        List<Section> moved = new ArrayList<>(sections.size());
        for (Section section : sections) {
            moved.add(new Section(section.chunkOffsetX() - anchorX,
                    section.chunkOffsetZ() - anchorZ,
                    section.sectionOffsetY() - anchorY, section.ids()));
        }
        return new Schematic(shifted, palette, moved);
    }

    private record Extracted(int baseX, int baseY, int baseZ, int sizeX, int sizeY, int sizeZ,
                             List<int[]> blocks) {
    }

    private static Extracted extract(Schematic schematic, int cx, int cy, int cz,
                                     int radius, int maxBlocks) {
        List<String> palette = schematic.palette();
        boolean[] skip = new boolean[palette.size()];
        for (int i = 0; i < palette.size(); i++) {
            skip[i] = DevPaletteGeometry.isAir(palette.get(i));
        }

        int minXB = cx - radius;
        int maxXB = cx + radius;
        int minZB = cz - radius;
        int maxZB = cz + radius;

        List<Section> sections = new ArrayList<>(schematic.sections());
        sections.sort(Comparator.comparingInt(s -> Math.abs((s.sectionOffsetY() << 4) + 8 - cy)));

        List<int[]> world = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        boolean capped = false;
        for (Section section : sections) {
            int sx = section.chunkOffsetX() << 4;
            int sy = section.sectionOffsetY() << 4;
            int sz = section.chunkOffsetZ() << 4;
            if (sx + 15 < minXB || sx > maxXB || sz + 15 < minZB || sz > maxZB) {
                continue;
            }
            for (int packed = 0; packed < SECTION_CELLS; packed++) {
                int idx = section.idAt(packed);
                if (idx < 0 || idx >= skip.length || skip[idx]) {
                    continue;
                }
                int wx = sx + (packed & 15);
                int wy = sy + ((packed >> 4) & 15);
                int wz = sz + ((packed >> 8) & 15);
                if (wx < minXB || wx > maxXB || wz < minZB || wz > maxZB) {
                    continue;
                }
                world.add(new int[]{wx, wy, wz, idx});
                minX = Math.min(minX, wx);
                minY = Math.min(minY, wy);
                minZ = Math.min(minZ, wz);
                maxX = Math.max(maxX, wx);
                maxY = Math.max(maxY, wy);
                maxZ = Math.max(maxZ, wz);
                if (world.size() >= maxBlocks) {
                    capped = true;
                    break;
                }
            }
            if (capped) {
                break;
            }
        }
        if (world.isEmpty()) {
            return new Extracted(0, 0, 0, 1, 1, 1, List.of());
        }

        int baseX = minX - EXTRACT_MARGIN;
        int baseY = minY - EXTRACT_MARGIN;
        int baseZ = minZ - EXTRACT_MARGIN;
        int sizeX = (maxX + EXTRACT_MARGIN) - baseX + 1;
        int sizeY = (maxY + EXTRACT_MARGIN) - baseY + 1;
        int sizeZ = (maxZ + EXTRACT_MARGIN) - baseZ + 1;

        List<int[]> blocks = new ArrayList<>(world.size());
        for (int[] w : world) {
            blocks.add(new int[]{w[0] - baseX, w[1] - baseY, w[2] - baseZ, w[3]});
        }
        return new Extracted(baseX, baseY, baseZ, sizeX, sizeY, sizeZ, blocks);
    }

    private static void checkSpan(int size, String axis, String name) {
        if (size < 1 || size > MAX_SPAN) {
            throw new IllegalStateException(name + " spans " + size + " blocks on " + axis
                    + "; the 16-bit arena wire allows at most " + MAX_SPAN);
        }
    }
}
