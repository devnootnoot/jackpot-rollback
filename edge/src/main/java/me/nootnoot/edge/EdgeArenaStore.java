package me.nootnoot.edge;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import me.nootnoot.sim.ArenaAgreement;

public final class EdgeArenaStore {

    public static final int MAX_CACHED = 4;
    public static final int MAX_CACHED_BLOCKS = 4_000_000;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public interface Fetcher {
        byte[] fetch(String key);
    }

    public enum State {
        RESOLVED,
        PENDING,
        FAILED
    }

    public record Result(State state, EdgeArena arena, String reason) {

        static Result resolved(EdgeArena arena) {
            return new Result(State.RESOLVED, arena, "");
        }

        static Result pending(String reason) {
            return new Result(State.PENDING, null, reason);
        }

        static Result failed(String reason) {
            return new Result(State.FAILED, null, reason);
        }
    }

    private static final int MISS_LOG_INTERVAL = 10;

    private final Logger log;
    private final Map<String, EdgeArena> cache = new LinkedHashMap<>();
    private final Map<String, String> failures = new LinkedHashMap<>();
    private final Map<String, Integer> misses = new LinkedHashMap<>();

    public EdgeArenaStore(Logger log) {
        this.log = log;
    }

    public synchronized void prefetch(Fetcher fetcher, EdgeAssignment assignment) {
        if (fetcher == null || assignment == null || !assignment.hasArenaBlob()) {
            return;
        }
        String sha = assignment.arenaSha256();
        if (cache.containsKey(sha) || failures.containsKey(sha)) {
            return;
        }
        byte[] blob = fetcher.fetch(assignment.arenaKey());
        if (blob == null || blob.length == 0) {
            int attempts = misses.merge(sha, 1, Integer::sum);
            if (attempts == 1 || attempts % MISS_LOG_INTERVAL == 0) {
                log.warning("arena bytes for '" + assignment.arenaName() + "' are not in redis yet ("
                        + assignment.arenaKey() + ", attempt " + attempts + ") - retrying on the"
                        + " next broker poll");
            }
            return;
        }
        misses.remove(sha);
        if (blob.length != assignment.arenaBytes()) {
            fail(sha, "the redis blob is " + blob.length + "B but the assignment says "
                    + assignment.arenaBytes() + "B");
            log.severe("arena bytes for '" + assignment.arenaName() + "' are the wrong length ("
                    + blob.length + "B, expected " + assignment.arenaBytes() + "B) - refusing them;"
                    + " loading a different arena than the peer is a guaranteed desync");
            return;
        }
        String actual = sha256(blob);
        if (!actual.equalsIgnoreCase(sha)) {
            fail(sha, "sha256 " + actual + " does not match the assignment's " + sha);
            log.severe("arena bytes for '" + assignment.arenaName() + "' hash to " + actual
                    + " but the assignment says " + sha + " - refusing them; the peer edge reads the"
                    + " same key, so whatever is under it is not what this match was assigned");
            return;
        }
        EdgeArena arena;
        try {
            arena = EdgeArena.fromBytes(blob, assignment.arenaName());
        } catch (RuntimeException ex) {
            fail(sha, "unreadable: " + ex);
            log.severe("arena bytes for '" + assignment.arenaName() + "' (" + blob.length
                    + "B) could not be decoded: " + ex + " - refusing the match rather than falling"
                    + " back to a local arena the peer is not using");
            return;
        }
        remember(sha, arena);
        log.info("arena '" + assignment.arenaName() + "' loaded from " + assignment.arenaKey()
                + ": " + arena.describe() + " hash=" + ArenaAgreement.hex(arena.hash()));
    }

    public synchronized Result resolve(EdgeAssignment assignment) {
        if (assignment == null || !assignment.hasArenaBlob()) {
            return Result.resolved(null);
        }
        String sha = assignment.arenaSha256();
        EdgeArena arena = cache.get(sha);
        if (arena != null) {
            return Result.resolved(arena);
        }
        String failure = failures.get(sha);
        if (failure != null) {
            return Result.failed(failure);
        }
        return Result.pending("the arena bytes at " + assignment.arenaKey() + " are still loading");
    }

    public synchronized int cached() {
        return cache.size();
    }

    private void remember(String sha, EdgeArena arena) {
        cache.put(sha, arena);
        trim(cache);
        while (cache.size() > 1 && cachedBlocks() > MAX_CACHED_BLOCKS) {
            String oldest = cache.keySet().iterator().next();
            EdgeArena evicted = cache.remove(oldest);
            log.info("dropped the cached arena '" + (evicted == null ? oldest : evicted.source())
                    + "' (" + (evicted == null ? 0 : evicted.blocks()) + " blocks) to stay under the "
                    + MAX_CACHED_BLOCKS + " cached-block budget; real arenas are large enough that"
                    + " holding several of them at once is what runs this edge out of heap");
        }
    }

    private int cachedBlocks() {
        int total = 0;
        for (EdgeArena arena : cache.values()) {
            total += arena.blocks();
        }
        return total;
    }

    private void fail(String sha, String reason) {
        failures.put(sha, reason);
        misses.remove(sha);
        trim(failures);
    }

    private static void trim(Map<String, ?> map) {
        while (map.size() > MAX_CACHED) {
            map.remove(map.keySet().iterator().next());
        }
    }

    public static String sha256(byte[] blob) {
        try {
            byte[] out = MessageDigest.getInstance("SHA-256").digest(blob);
            char[] hex = new char[out.length * 2];
            for (int i = 0; i < out.length; i++) {
                hex[i * 2] = HEX[(out[i] >> 4) & 0xF];
                hex[i * 2 + 1] = HEX[out[i] & 0xF];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
