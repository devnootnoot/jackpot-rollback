package me.nootnoot.relay;

import java.util.HashMap;
import java.util.Map;
import me.nootnoot.sim.ArenaCodec;
import me.nootnoot.sim.state.Arena;

final class ArenaPool {

    static final long NOT_POOLED = Long.MIN_VALUE;

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final Map<Long, Arena> byKey = new HashMap<>();
    private final Map<Long, Integer> uses = new HashMap<>();

    static long keyOf(byte[] blob) {
        long h = FNV_OFFSET;
        for (byte b : blob) {
            h ^= b & 0xFF;
            h *= FNV_PRIME;
        }
        return h == NOT_POOLED ? 0L : h;
    }

    synchronized Arena acquire(long key, byte[] blob) {
        Arena arena = byKey.get(key);
        if (arena == null) {
            arena = ArenaCodec.toArena(ArenaCodec.decode(blob));
            byKey.put(key, arena);
        }
        uses.merge(key, 1, Integer::sum);
        return arena;
    }

    synchronized void release(long key) {
        if (key == NOT_POOLED) {
            return;
        }
        Integer left = uses.get(key);
        if (left == null) {
            return;
        }
        if (left <= 1) {
            uses.remove(key);
            byKey.remove(key);
            return;
        }
        uses.put(key, left - 1);
    }

    synchronized int pooled() {
        return byKey.size();
    }

    synchronized int usesOf(long key) {
        return uses.getOrDefault(key, 0);
    }
}
