package me.nootnoot.sim;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;

public final class ArenaCodec {
    public static final int KIND_SKIP = 0;
    public static final int KIND_FULL_CUBE = 1;
    public static final int KIND_PARTIAL = 2;
    public static final int KIND_DECOR = 3;

    public static final int EXT_MAGIC = 0x41524558;
    public static final int EXT_VERSION = 1;
    public static final int SECTION_GROUND_Y = 1;
    public static final int SECTION_PALETTE_GEOMETRY = 2;

    public static final float BLAST_PROOF_RESISTANCE = 1.0e7f;
    public static final float HIGH_RESISTANCE_THRESHOLD = 50.0f;
    public static final int MAX_DROP_ENTRIES = 262_144;
    public static final long GRID_VOLUME_LIMIT = 134_217_728L;

    private static final int U16 = 0xFFFF;
    private static final int MAX_PALETTE = 65535;
    private static final int MAX_BLOCKS = 16_777_216;
    private static final int MAX_SECTION_BYTES = 1 << 26;
    private static final double[][] NO_BOXES = new double[0][];

    private ArenaCodec() {
    }

    public record PaletteEntry(int kind, float resistance, int blockItemId, int dropItemId,
                               double[][] partialBoxes) {
    }

    public record Snapshot(long sessionId, int baseX, int baseY, int baseZ,
                           int sizeX, int sizeY, int sizeZ,
                           String[] palette, int[][] blocks,
                           PaletteEntry[] geometry, Double groundY) {

        public double resolvedGroundY() {
            return groundY != null ? groundY : baseY;
        }

        public boolean hasGeometry() {
            return geometry != null;
        }
    }

    public static byte[] encode(long sessionId, int baseX, int baseY, int baseZ,
                                int sizeX, int sizeY, int sizeZ,
                                List<String> palette, List<int[]> blocks) {
        return encode(new Snapshot(sessionId, baseX, baseY, baseZ, sizeX, sizeY, sizeZ,
                palette.toArray(new String[0]), blocks.toArray(new int[0][]), null, null));
    }

    public static byte[] encode(Snapshot s) {
        validate(s);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            out.writeLong(s.sessionId());
            out.writeInt(s.baseX());
            out.writeInt(s.baseY());
            out.writeInt(s.baseZ());
            out.writeInt(s.sizeX());
            out.writeInt(s.sizeY());
            out.writeInt(s.sizeZ());

            String[] palette = s.palette();
            out.writeShort(palette.length);
            for (String entry : palette) {
                byte[] bytes = entry.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > U16) {
                    throw new IllegalArgumentException("palette entry longer than 65535 bytes: " + entry);
                }
                out.writeShort(bytes.length);
                out.write(bytes);
            }

            int[][] blocks = s.blocks();
            out.writeInt(blocks.length);
            for (int[] b : blocks) {
                out.writeShort(b[0]);
                out.writeShort(b[1]);
                out.writeShort(b[2]);
                out.writeShort(b[3]);
            }

            writeExtension(out, s);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to encode arena blob", e);
        }
        return raw.toByteArray();
    }

    public static Snapshot decode(byte[] gzip) {
        if (gzip == null || gzip.length == 0) {
            throw new IllegalArgumentException("empty arena blob");
        }
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(gzip)))) {
            long sessionId = in.readLong();
            int baseX = in.readInt();
            int baseY = in.readInt();
            int baseZ = in.readInt();
            int sizeX = in.readInt();
            int sizeY = in.readInt();
            int sizeZ = in.readInt();
            if (sizeX < 0 || sizeY < 0 || sizeZ < 0) {
                throw new IllegalArgumentException("negative arena size: "
                        + sizeX + "x" + sizeY + "x" + sizeZ);
            }

            int paletteCount = in.readShort() & U16;
            String[] palette = new String[paletteCount];
            for (int i = 0; i < paletteCount; i++) {
                int len = in.readShort() & U16;
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                palette[i] = new String(bytes, StandardCharsets.UTF_8);
            }

            int blockCount = in.readInt();
            if (blockCount < 0 || blockCount > MAX_BLOCKS) {
                throw new IllegalArgumentException("arena block count out of bounds: " + blockCount);
            }
            List<int[]> read = new ArrayList<>(blockCount < 4096 ? blockCount : 4096);
            for (int i = 0; i < blockCount; i++) {
                int[] b = new int[4];
                b[0] = in.readShort() & U16;
                b[1] = in.readShort() & U16;
                b[2] = in.readShort() & U16;
                b[3] = in.readShort() & U16;
                read.add(b);
            }
            int[][] blocks = read.toArray(new int[0][]);

            Extension ext = readExtension(in, paletteCount);
            if (in.read() >= 0) {
                throw new IllegalArgumentException("unexpected trailing bytes in arena blob");
            }
            return new Snapshot(sessionId, baseX, baseY, baseZ, sizeX, sizeY, sizeZ,
                    palette, blocks, ext.geometry, ext.groundY);
        } catch (IOException e) {
            throw new IllegalArgumentException("malformed arena blob", e);
        }
    }

    public static Arena toArena(Snapshot s) {
        PaletteEntry[] geometry = s.geometry();
        if (geometry == null) {
            throw new IllegalArgumentException("arena snapshot carries no palette geometry section");
        }
        if (geometry.length != s.palette().length) {
            throw new IllegalArgumentException("palette geometry size " + geometry.length
                    + " does not match palette size " + s.palette().length);
        }
        int n = geometry.length;
        float[] resistance = new float[n];
        int[] dropItem = new int[n];
        int[] blockItem = new int[n];
        for (int i = 0; i < n; i++) {
            resistance[i] = geometry[i].resistance();
            dropItem[i] = geometry[i].dropItemId();
            blockItem[i] = geometry[i].blockItemId();
        }

        int bx = s.baseX();
        int by = s.baseY();
        int bz = s.baseZ();
        int sx = s.sizeX();
        int sy = s.sizeY();
        int sz = s.sizeZ();
        long volume = (long) sx * (long) sy * (long) sz;
        boolean[] grid = (volume > 0 && volume <= GRID_VOLUME_LIMIT) ? new boolean[(int) volume] : null;

        List<double[]> partials = new ArrayList<>();
        List<double[]> fullBoxes = grid == null ? new ArrayList<>() : null;
        HashMap<Long, Float> voxelResistance = new HashMap<>();
        HashMap<Long, Integer> voxelDropItem = new HashMap<>();
        HashMap<Long, Integer> voxelBlockItem = new HashMap<>();
        HashSet<Long> decorVoxels = new HashSet<>();

        for (int[] b : s.blocks()) {
            int idx = b[3];
            if (idx < 0 || idx >= n) {
                throw new IllegalArgumentException("palette index out of range: " + idx);
            }
            int kind = geometry[idx].kind();
            if (kind == KIND_SKIP) {
                continue;
            }
            int wx = bx + b[0];
            int wy = by + b[1];
            int wz = bz + b[2];
            if (kind == KIND_FULL_CUBE) {
                if (grid != null) {
                    if (b[0] >= sx || b[1] >= sy || b[2] >= sz) {
                        throw new IllegalArgumentException("arena block outside the declared grid: "
                                + b[0] + "," + b[1] + "," + b[2]);
                    }
                    grid[(b[2] * sy + b[1]) * sx + b[0]] = true;
                } else {
                    fullBoxes.add(new double[]{wx, wy, wz, wx + 1.0, wy + 1.0, wz + 1.0});
                }
                long key = BlockStore.key(wx, wy, wz);
                if (resistance[idx] > HIGH_RESISTANCE_THRESHOLD) {
                    voxelResistance.put(key, resistance[idx]);
                }
                if (dropItem[idx] != 0 && resistance[idx] < BLAST_PROOF_RESISTANCE
                        && voxelDropItem.size() < MAX_DROP_ENTRIES) {
                    voxelDropItem.put(key, dropItem[idx]);
                }
                if (blockItem[idx] != 0 && voxelBlockItem.size() < MAX_DROP_ENTRIES) {
                    voxelBlockItem.put(key, blockItem[idx]);
                }
            } else if (kind == KIND_PARTIAL) {
                for (double[] box : geometry[idx].partialBoxes()) {
                    partials.add(new double[]{
                            wx + box[0], wy + box[1], wz + box[2],
                            wx + box[3], wy + box[4], wz + box[5]});
                }
            } else {
                long key = BlockStore.key(wx, wy, wz);
                decorVoxels.add(key);
                if (blockItem[idx] != 0 && voxelBlockItem.size() < MAX_DROP_ENTRIES) {
                    voxelBlockItem.put(key, blockItem[idx]);
                }
            }
        }

        double groundY = s.resolvedGroundY();
        if (grid == null) {
            List<double[]> legacy = new ArrayList<>(fullBoxes.size() + partials.size());
            legacy.addAll(fullBoxes);
            legacy.addAll(partials);
            return new Arena(groundY, legacy.toArray(new double[0][]));
        }
        return new Arena(groundY, grid, bx, by, bz, sx, sy, sz,
                partials.toArray(new double[0][]), voxelResistance, voxelDropItem, decorVoxels,
                voxelBlockItem);
    }

    private static void validate(Snapshot s) {
        if (s.palette() == null || s.blocks() == null) {
            throw new IllegalArgumentException("arena snapshot is missing its palette or block list");
        }
        if (s.palette().length > MAX_PALETTE) {
            throw new IllegalArgumentException("palette larger than 65535 entries: " + s.palette().length);
        }
        if (s.sizeX() < 0 || s.sizeY() < 0 || s.sizeZ() < 0) {
            throw new IllegalArgumentException("negative arena size");
        }
        if (s.geometry() != null && s.geometry().length != s.palette().length) {
            throw new IllegalArgumentException("palette geometry size " + s.geometry().length
                    + " does not match palette size " + s.palette().length);
        }
        for (int[] b : s.blocks()) {
            if (b == null || b.length < 4) {
                throw new IllegalArgumentException("arena block entry must be {dx, dy, dz, paletteIndex}");
            }
            for (int k = 0; k < 3; k++) {
                if (b[k] < 0 || b[k] > U16) {
                    throw new IllegalArgumentException("arena block coordinate does not fit 16 bits: " + b[k]);
                }
            }
            if (b[3] < 0 || b[3] >= s.palette().length) {
                throw new IllegalArgumentException("palette index out of range: " + b[3]);
            }
        }
    }

    private static void writeExtension(DataOutputStream out, Snapshot s) throws IOException {
        Double groundY = s.groundY();
        PaletteEntry[] geometry = s.geometry();
        if (groundY == null && geometry == null) {
            return;
        }
        out.writeInt(EXT_MAGIC);
        out.writeByte(EXT_VERSION);
        out.writeByte((groundY != null ? 1 : 0) + (geometry != null ? 1 : 0));
        if (groundY != null) {
            out.writeByte(SECTION_GROUND_Y);
            out.writeInt(8);
            out.writeDouble(groundY);
        }
        if (geometry != null) {
            byte[] payload = geometryPayload(geometry);
            out.writeByte(SECTION_PALETTE_GEOMETRY);
            out.writeInt(payload.length);
            out.write(payload);
        }
    }

    private static byte[] geometryPayload(PaletteEntry[] geometry) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeInt(geometry.length);
        for (PaletteEntry e : geometry) {
            if (e.kind() < KIND_SKIP || e.kind() > KIND_DECOR) {
                throw new IllegalArgumentException("unknown palette kind: " + e.kind());
            }
            double[][] boxes = e.partialBoxes() != null ? e.partialBoxes() : NO_BOXES;
            if (boxes.length > U16) {
                throw new IllegalArgumentException("palette entry has too many collision boxes: " + boxes.length);
            }
            out.writeByte(e.kind());
            out.writeFloat(e.resistance());
            out.writeInt(e.blockItemId());
            out.writeInt(e.dropItemId());
            out.writeShort(boxes.length);
            for (double[] box : boxes) {
                if (box == null || box.length < 6) {
                    throw new IllegalArgumentException("collision box must be {minX, minY, minZ, maxX, maxY, maxZ}");
                }
                for (int k = 0; k < 6; k++) {
                    out.writeDouble(box[k]);
                }
            }
        }
        out.flush();
        return raw.toByteArray();
    }

    private static Extension readExtension(DataInputStream in, int paletteCount) throws IOException {
        int first = in.read();
        if (first < 0) {
            return new Extension(null, null);
        }
        int magic = (first << 24) | (in.readUnsignedByte() << 16)
                | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
        if (magic != EXT_MAGIC) {
            throw new IllegalArgumentException("unknown trailing arena section: 0x" + Integer.toHexString(magic));
        }
        int version = in.readUnsignedByte();
        if (version < 1) {
            throw new IllegalArgumentException("unsupported arena extension version: " + version);
        }
        int sections = in.readUnsignedByte();
        Double groundY = null;
        PaletteEntry[] geometry = null;
        for (int i = 0; i < sections; i++) {
            int id = in.readUnsignedByte();
            int len = in.readInt();
            if (len < 0 || len > MAX_SECTION_BYTES) {
                throw new IllegalArgumentException("arena section length out of bounds: " + len);
            }
            byte[] payload = new byte[len];
            in.readFully(payload);
            if (id == SECTION_GROUND_Y) {
                if (len != 8) {
                    throw new IllegalArgumentException("ground-y section must be 8 bytes, got " + len);
                }
                groundY = new DataInputStream(new ByteArrayInputStream(payload)).readDouble();
            } else if (id == SECTION_PALETTE_GEOMETRY) {
                geometry = readGeometry(payload, paletteCount);
            }
        }
        return new Extension(groundY, geometry);
    }

    private static PaletteEntry[] readGeometry(byte[] payload, int paletteCount) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        int count = in.readInt();
        if (count != paletteCount) {
            throw new IllegalArgumentException("palette geometry size " + count
                    + " does not match palette size " + paletteCount);
        }
        PaletteEntry[] geometry = new PaletteEntry[count];
        for (int i = 0; i < count; i++) {
            int kind = in.readUnsignedByte();
            if (kind < KIND_SKIP || kind > KIND_DECOR) {
                throw new IllegalArgumentException("unknown palette kind: " + kind);
            }
            float resistance = in.readFloat();
            int blockItemId = in.readInt();
            int dropItemId = in.readInt();
            int boxCount = in.readShort() & U16;
            double[][] boxes = boxCount == 0 ? NO_BOXES : new double[boxCount][6];
            for (int b = 0; b < boxCount; b++) {
                for (int k = 0; k < 6; k++) {
                    boxes[b][k] = in.readDouble();
                }
            }
            geometry[i] = new PaletteEntry(kind, resistance, blockItemId, dropItemId, boxes);
        }
        return geometry;
    }

    private record Extension(Double groundY, PaletteEntry[] geometry) {
    }
}
