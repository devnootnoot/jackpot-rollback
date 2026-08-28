package me.nootnoot.edge;

import java.util.BitSet;

public final class EdgeEntityBands {

    public static final int PROJECTILE_BASE = 1_910_000_000;
    public static final int PROJECTILE_SPAN = 4096;

    public static final int CRYSTAL_BASE = 1_915_000_000;
    public static final int CRYSTAL_SPAN = 1024;

    public static final int ITEM_BASE = 1_920_000_000;
    public static final int ITEM_SPAN = 8192;

    public static final int ORB_BASE = 1_925_000_000;
    public static final int ORB_SPAN = 1024;

    public static final int BREAKER_BASE = 1_930_000_000;

    public static final int BOOST_BASE = 1_935_000_000;

    private EdgeEntityBands() {
    }

    public static int breaker(int slot) {
        return BREAKER_BASE + (slot & 1);
    }

    public static int boost(int slot) {
        return BOOST_BASE + (slot & 1);
    }

    public static final class Allocator {

        private final int base;
        private final int span;
        private final BitSet used;

        private int cursor;

        public Allocator(int base, int span) {
            this.base = base;
            this.span = span;
            this.used = new BitSet(span);
        }

        public int alloc() {
            int free = used.nextClearBit(cursor);
            if (free >= span) {
                free = used.nextClearBit(0);
            }
            if (free >= span) {
                return -1;
            }
            used.set(free);
            cursor = free + 1 >= span ? 0 : free + 1;
            return base + free;
        }

        public void free(int entityId) {
            int index = entityId - base;
            if (index >= 0 && index < span) {
                used.clear(index);
            }
        }

        public void reset() {
            used.clear();
            cursor = 0;
        }
    }
}
