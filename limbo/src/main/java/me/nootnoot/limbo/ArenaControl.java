package me.nootnoot.limbo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPooled;

final class ArenaControl {

    private static final Logger LOG = LoggerFactory.getLogger("limbo");

    private static final int MAGIC = 0x52424147;
    private static final int VERSION = 1;
    static final String KEY_PREFIX = "rollback:arena:";

    private static final int MAX_COLUMNS = 32_768;
    private static final int MAX_DATA = 4 << 20;

    static final class Assignment {
        final int slot;
        final int sectionsPerColumn;
        final Map<Long, byte[]> columns;

        Assignment(int slot, int sectionsPerColumn, Map<Long, byte[]> columns) {
            this.slot = slot;
            this.sectionsPerColumn = sectionsPerColumn;
            this.columns = columns;
        }
    }

    private final JedisPooled redis;
    private final ExecutorService executor =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "limbo-arena-redis");
                t.setDaemon(true);
                return t;
            });

    ArenaControl(JedisPooled redis) {
        this.redis = redis;
    }

    static long columnKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    CompletableFuture<Assignment> beginResolve(UUID uuid) {
        if (uuid == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> fetch(uuid), executor);
    }

    private Assignment fetch(UUID uuid) {
        try {
            byte[] value = redis.get((KEY_PREFIX + uuid).getBytes(StandardCharsets.UTF_8));
            if (value == null) {
                return null;
            }
            return parse(gunzip(value));
        } catch (Exception e) {
            LOG.debug("[limbo] arena lookup failed for {}: {}", uuid, e.toString());
            return null;
        }
    }

    private static Assignment parse(byte[] raw) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
        if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION) {
            return null;
        }
        int slot = in.readInt();
        int sections = in.readInt();
        int cols = in.readInt();
        if (sections <= 0 || sections > 64 || cols < 0 || cols > MAX_COLUMNS) {
            throw new IOException("malformed arena blob (sections=" + sections + ", cols=" + cols + ")");
        }
        Map<Long, byte[]> columns = new HashMap<>(Math.max(16, cols * 2));
        for (int i = 0; i < cols; i++) {
            int cx = in.readInt();
            int cz = in.readInt();
            int len = in.readInt();
            if (len < 0 || len > MAX_DATA) {
                throw new IOException("malformed column data length " + len);
            }
            byte[] data = new byte[len];
            in.readFully(data);
            columns.put(columnKey(cx, cz), data);
        }
        return new Assignment(slot, sections, columns);
    }

    private static byte[] gunzip(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        }
    }
}
