package me.nootnoot.sim;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class ArenaFixtures {
    static final long SESSION_ID = 0x0123456789ABCDEFL;
    static final int BASE_X = 100;
    static final int BASE_Y = 64;
    static final int BASE_Z = -20;
    static final int SIZE_X = 4;
    static final int SIZE_Y = 3;
    static final int SIZE_Z = 4;

    static final int STONE = 0;
    static final int SLAB = 1;
    static final int TORCH = 2;
    static final int OBSIDIAN = 3;
    static final int AIR = 4;

    static final int STONE_ITEM = 1;
    static final int SLAB_ITEM = 2;
    static final int TORCH_ITEM = 3;
    static final int OBSIDIAN_ITEM = 4;

    private ArenaFixtures() {
    }

    static String[] palette() {
        return new String[]{
                "minecraft:stone",
                "minecraft:stone_slab[type=bottom]",
                "minecraft:torch",
                "minecraft:obsidian",
                "minecraft:air"};
    }

    static ArenaCodec.PaletteEntry[] geometry() {
        return new ArenaCodec.PaletteEntry[]{
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 6.0f, STONE_ITEM, 11, new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_PARTIAL, 6.0f, SLAB_ITEM, 12,
                        new double[][]{{0.0, 0.0, 0.0, 1.0, 0.5, 1.0}}),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_DECOR, 0.0f, TORCH_ITEM, 13, new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 1200.0f, OBSIDIAN_ITEM, 14, new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_SKIP, 0.0f, 0, 0, new double[0][])};
    }

    static int[][] blocks() {
        List<int[]> out = new ArrayList<>();
        for (int z = 0; z < SIZE_Z; z++) {
            for (int x = 0; x < SIZE_X; x++) {
                out.add(new int[]{x, 0, z, STONE});
            }
        }
        out.add(new int[]{1, 1, 1, OBSIDIAN});
        out.add(new int[]{2, 1, 2, SLAB});
        out.add(new int[]{0, 1, 0, TORCH});
        out.add(new int[]{3, 1, 3, AIR});
        return out.toArray(new int[0][]);
    }

    static ArenaCodec.Snapshot full() {
        return new ArenaCodec.Snapshot(SESSION_ID, BASE_X, BASE_Y, BASE_Z, SIZE_X, SIZE_Y, SIZE_Z,
                palette(), blocks(), geometry(), (double) BASE_Y);
    }

    static ArenaCodec.Snapshot legacyOnly() {
        return new ArenaCodec.Snapshot(SESSION_ID, BASE_X, BASE_Y, BASE_Z, SIZE_X, SIZE_Y, SIZE_Z,
                palette(), blocks(), null, null);
    }

    static ArenaCodec.Snapshot withBlocks(int[][] blocks) {
        return new ArenaCodec.Snapshot(SESSION_ID, BASE_X, BASE_Y, BASE_Z, SIZE_X, SIZE_Y, SIZE_Z,
                palette(), blocks, geometry(), (double) BASE_Y);
    }

    static int[][] copyBlocks() {
        int[][] src = blocks();
        int[][] out = new int[src.length][];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i].clone();
        }
        return out;
    }

    static byte[] legacyEncode(long sessionId, int baseX, int baseY, int baseZ,
                               int sizeX, int sizeY, int sizeZ, String[] palette, int[][] blocks) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(raw))) {
            out.writeLong(sessionId);
            out.writeInt(baseX);
            out.writeInt(baseY);
            out.writeInt(baseZ);
            out.writeInt(sizeX);
            out.writeInt(sizeY);
            out.writeInt(sizeZ);
            out.writeShort(palette.length);
            for (String s : palette) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                out.writeShort(bytes.length);
                out.write(bytes);
            }
            out.writeInt(blocks.length);
            for (int[] b : blocks) {
                out.writeShort(b[0]);
                out.writeShort(b[1]);
                out.writeShort(b[2]);
                out.writeShort(b[3]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return raw.toByteArray();
    }

    record LegacyBlocks(long sessionId, int baseX, int baseY, int baseZ,
                        int sizeX, int sizeY, int sizeZ, String[] palette, int[][] blocks) {
    }

    static LegacyBlocks legacyDecode(byte[] gzip) {
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new ByteArrayInputStream(gzip)))) {
            long sessionId = in.readLong();
            int baseX = in.readInt();
            int baseY = in.readInt();
            int baseZ = in.readInt();
            int sizeX = in.readInt();
            int sizeY = in.readInt();
            int sizeZ = in.readInt();
            int paletteCount = in.readShort() & 0xFFFF;
            String[] palette = new String[paletteCount];
            for (int i = 0; i < paletteCount; i++) {
                int len = in.readShort() & 0xFFFF;
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                palette[i] = new String(bytes, StandardCharsets.UTF_8);
            }
            int blockCount = in.readInt();
            int[][] blocks = new int[blockCount][4];
            for (int i = 0; i < blockCount; i++) {
                blocks[i][0] = in.readShort() & 0xFFFF;
                blocks[i][1] = in.readShort() & 0xFFFF;
                blocks[i][2] = in.readShort() & 0xFFFF;
                blocks[i][3] = in.readShort() & 0xFFFF;
            }
            return new LegacyBlocks(sessionId, baseX, baseY, baseZ, sizeX, sizeY, sizeZ, palette, blocks);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] inflate(byte[] gzip) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] gzip(byte[] payload) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(raw)) {
            out.write(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return raw.toByteArray();
    }
}
