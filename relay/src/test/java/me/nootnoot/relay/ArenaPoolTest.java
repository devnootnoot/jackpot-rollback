package me.nootnoot.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import me.nootnoot.sim.ArenaCodec;
import me.nootnoot.sim.state.Arena;
import org.junit.jupiter.api.Test;

class ArenaPoolTest {

    private static byte[] blob(double groundY) {
        ArenaCodec.PaletteEntry[] geometry = {
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 6f, 0, 0, new double[0][]),
        };
        int[][] blocks = {{0, 0, 0, 0}, {1, 0, 0, 0}, {0, 0, 1, 0}};
        return ArenaCodec.encode(new ArenaCodec.Snapshot(0L, 0, 0, 0, 4, 4, 4,
                new String[]{"minecraft:stone"}, blocks, geometry, groundY));
    }

    @Test
    void everySessionOnOneArenaSharesOneInstance() {
        ArenaPool pool = new ArenaPool();
        byte[] b = blob(64.0);
        long key = ArenaPool.keyOf(b);

        Arena first = pool.acquire(key, b);
        Arena second = pool.acquire(key, b.clone());

        assertSame(first, second,
                "a referee session builds a 13.8 MB Arena for a real map, and every duel on that map"
                        + " builds a byte-identical one. Sharing the immutable instance is the"
                        + " difference between ~37 concurrent sessions per relay and thousands");
        assertEquals(1, pool.pooled());
        assertEquals(2, pool.usesOf(key));
    }

    @Test
    void theLastSessionToLeaveEvictsIt() {
        ArenaPool pool = new ArenaPool();
        byte[] b = blob(64.0);
        long key = ArenaPool.keyOf(b);

        pool.acquire(key, b);
        pool.acquire(key, b);
        pool.release(key);
        assertEquals(1, pool.pooled(), "one session still holds it");

        pool.release(key);
        assertEquals(0, pool.pooled(),
                "with no session left the arena must be dropped, or a relay that has served many"
                        + " maps keeps every one of them alive forever");
    }

    @Test
    void differentArenasDoNotCollide() {
        ArenaPool pool = new ArenaPool();
        byte[] one = blob(64.0);
        byte[] other = blob(70.0);

        Arena a = pool.acquire(ArenaPool.keyOf(one), one);
        Arena b = pool.acquire(ArenaPool.keyOf(other), other);

        assertNotSame(a, b, "two different arenas must never be conflated by the pool key");
        assertEquals(2, pool.pooled());
    }

    @Test
    void releasingSomethingNeverPooledIsHarmless() {
        ArenaPool pool = new ArenaPool();
        pool.release(ArenaPool.NOT_POOLED);
        pool.release(12345L);
        assertEquals(0, pool.pooled());
    }
}
