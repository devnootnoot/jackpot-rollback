package me.nootnoot.limbo;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;

final class BlockStateRemap {

    private static final ConcurrentHashMap<Integer, BlockStateRemap> CACHE = new ConcurrentHashMap<>();
    private static final BlockStateRemap MISSING = new BlockStateRemap(new int[0]);

    private final int[] table;

    private BlockStateRemap(int[] table) {
        this.table = table;
    }

    int map(int id) {
        return id >= 0 && id < table.length ? table[id] : 0;
    }

    static BlockStateRemap forTarget(int targetProtocol) {
        BlockStateRemap r = CACHE.computeIfAbsent(targetProtocol, BlockStateRemap::loadFor);
        return r == MISSING ? null : r;
    }

    private static BlockStateRemap loadFor(int targetProtocol) {
        String res = "/blockstate/remap_" + LimboHandler.BAKE_PROTOCOL + "_to_" + targetProtocol + ".bin";
        try (InputStream in = BlockStateRemap.class.getResourceAsStream(res)) {
            if (in == null) {
                return MISSING;
            }
            DataInputStream d = new DataInputStream(new BufferedInputStream(in));
            int n = d.readInt();
            int[] t = new int[n];
            for (int i = 0; i < n; i++) {
                t[i] = d.readInt();
            }
            return new BlockStateRemap(t);
        } catch (Exception e) {
            return MISSING;
        }
    }
}
