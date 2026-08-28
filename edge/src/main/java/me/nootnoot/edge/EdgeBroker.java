package me.nootnoot.edge;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Jedis;

public final class EdgeBroker {

    public static final String KEY_SERVERS = "rollback:edge:servers";
    public static final String KEY_ASSIGN_PREFIX = "rollback:edge:assign:";
    public static final String KEY_RESULTS = "rollback:edge:results";
    public static final String KEY_METRICS = "rollback:edge:metrics";

    private static final int HEARTBEAT_TTL_SECONDS = 30;
    private static final int METRICS_TTL_SECONDS = 300;
    private static final int ASSIGNMENT_DRAIN_LIMIT = 16;
    private static final int RESULT_TRIM = 4096;

    private static final int OUTBOX_CAPACITY = 256;

    private final Logger logger;
    private final String edgeId;
    private final String region;
    private final String publicHost;
    private final int publicPort;
    private final String directHost;
    private final int directPort;
    private final JedisPool pool;

    private final ConcurrentMap<UUID, EdgeAssignment> pendingByPlayer = new ConcurrentHashMap<>();

    private final java.util.concurrent.ConcurrentLinkedDeque<String> resultOutbox =
            new java.util.concurrent.ConcurrentLinkedDeque<>();

    private volatile boolean healthy = true;

    private volatile EdgeMetrics metrics;

    public EdgeBroker(Logger logger, String edgeId, String region, String publicHost, int publicPort,
                      String directHost, int directPort,
                      String redisHost, int redisPort, String redisPassword) {
        this.logger = logger;
        this.edgeId = edgeId;
        this.region = region;
        this.publicHost = publicHost;
        this.publicPort = publicPort;
        this.directHost = directHost == null ? "" : directHost;
        this.directPort = directPort;

        DefaultJedisClientConfig.Builder cfg = DefaultJedisClientConfig.builder();
        if (redisPassword != null && !redisPassword.isBlank()) {
            cfg.password(redisPassword);
        }
        cfg.connectionTimeoutMillis(3000);
        cfg.socketTimeoutMillis(3000);
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMinIdle(1);
        poolConfig.setMaxTotal(8);
        poolConfig.setMaxWait(java.time.Duration.ofSeconds(3));
        this.pool = new JedisPool(poolConfig, new HostAndPort(redisHost, redisPort), cfg.build());
    }

    public String edgeId() {
        return edgeId;
    }

    public void attachMetrics(EdgeMetrics metrics) {
        this.metrics = metrics;
    }

    private void count(int counter) {
        EdgeMetrics m = metrics;
        if (m != null) {
            m.hit(counter);
        }
    }

    public boolean healthy() {
        return healthy;
    }

    public void heartbeat() {
        JsonObject o = new JsonObject();
        o.addProperty("edgeId", edgeId);
        o.addProperty("region", region);
        o.addProperty("host", publicHost);
        o.addProperty("port", publicPort);
        if (!directHost.isEmpty() && directPort > 0) {
            o.addProperty("directHost", directHost);
            o.addProperty("directPort", directPort);
        }
        o.addProperty("at", System.currentTimeMillis());
        withRedis(jedis -> {
            jedis.hset(KEY_SERVERS, edgeId, o.toString());
            jedis.expire(KEY_SERVERS, HEARTBEAT_TTL_SECONDS * 4L);
            return null;
        });
    }

    public void publishMetrics(String json) {
        if (json == null || json.isEmpty()) {
            return;
        }
        withRedis(jedis -> {
            jedis.hset(KEY_METRICS, edgeId, json);
            jedis.expire(KEY_METRICS, METRICS_TTL_SECONDS);
            return null;
        });
    }

    public List<EdgeAssignment> drainAssignments() {
        List<EdgeAssignment> out = new ArrayList<>();
        withRedis(jedis -> {
            for (int i = 0; i < ASSIGNMENT_DRAIN_LIMIT; i++) {
                String json = jedis.lpop(KEY_ASSIGN_PREFIX + edgeId);
                if (json == null) {
                    break;
                }
                EdgeAssignment a = EdgeAssignment.parse(json);
                if (a == null) {
                    count(EdgeMetrics.ASSIGNMENTS_UNPARSEABLE);
                    logger.warning("[broker] dropped an unparseable assignment");
                    continue;
                }
                if (a.expired(System.currentTimeMillis())) {
                    count(EdgeMetrics.ASSIGNMENTS_EXPIRED);
                    logger.warning("[broker] dropped an expired assignment: " + a.describe());
                    continue;
                }
                out.add(a);
            }
            return null;
        });
        long now = System.currentTimeMillis();
        for (EdgeAssignment a : out) {
            pendingByPlayer.put(a.playerUuid(), a);
            count(EdgeMetrics.ASSIGNMENTS_RECEIVED);
            EdgeMetrics m = metrics;
            if (m != null && a.issuedAtMs() > 0L) {
                m.recordAssignmentPickup(now - a.issuedAtMs());
            }
            logger.info("[broker] assignment received: " + a.describe());
        }
        return out;
    }

    public EdgeAssignment claim(UUID playerUuid) {
        return pendingByPlayer.remove(playerUuid);
    }

    public EdgeAssignment peek(UUID playerUuid) {
        return pendingByPlayer.get(playerUuid);
    }

    public java.util.Collection<EdgeAssignment> pendingAssignments() {
        return pendingByPlayer.values();
    }

    public boolean hasPending() {
        return !pendingByPlayer.isEmpty();
    }

    public void expirePending(long nowMs) {
        pendingByPlayer.entrySet().removeIf(e -> e.getValue().expired(nowMs));
    }

    public byte[] fetchBlob(String key) {
        byte[] raw = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[][] holder = new byte[1][];
        withRedis(jedis -> {
            holder[0] = jedis.get(raw);
            return null;
        });
        return holder[0];
    }

    public void reportResult(long sessionId, int reporterSlot, int winnerSlot, int winsP0, int winsP1,
                             String cause, String detail) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("sessionId", sessionId);
        o.addProperty("edgeId", edgeId);
        o.addProperty("reporterSlot", reporterSlot);
        o.addProperty("winnerSlot", winnerSlot);
        o.addProperty("winsP0", winsP0);
        o.addProperty("winsP1", winsP1);
        o.addProperty("cause", cause == null ? "" : cause);
        o.addProperty("reason", detail == null ? "" : detail);
        o.addProperty("at", System.currentTimeMillis());
        enqueueResult(o.toString());
        logger.info("[broker] result reported: session=" + sessionId + " winnerSlot=" + winnerSlot
                + " cause=" + cause + " detail=" + detail);
    }

    private void enqueueResult(String json) {
        while (resultOutbox.size() >= OUTBOX_CAPACITY) {
            String dropped = resultOutbox.pollFirst();
            if (dropped == null) {
                break;
            }
            count(EdgeMetrics.RESULTS_DROPPED);
            logger.severe("[broker] the result outbox overflowed and a match result was dropped: "
                    + dropped);
        }
        resultOutbox.addLast(json);
    }

    public void flushResults() {
        if (resultOutbox.isEmpty()) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            String json;
            while ((json = resultOutbox.peekFirst()) != null) {
                jedis.rpush(KEY_RESULTS, json);
                jedis.ltrim(KEY_RESULTS, -RESULT_TRIM, -1);
                resultOutbox.pollFirst();
            }
            if (!healthy) {
                healthy = true;
                logger.info("[broker] redis reachable again");
            }
        } catch (Throwable t) {
            if (healthy) {
                healthy = false;
                logger.warning("[broker] redis unavailable: " + t);
            }
            logger.warning("[broker] " + resultOutbox.size() + " match result(s) are queued until"
                    + " redis comes back - none are lost unless this process dies first");
        }
    }

    public int queuedResults() {
        return resultOutbox.size();
    }

    public void close() {
        flushResults();
        withRedis(jedis -> {
            jedis.hdel(KEY_SERVERS, edgeId);
            jedis.hdel(KEY_METRICS, edgeId);
            return null;
        });
        try {
            pool.close();
        } catch (RuntimeException ignored) {
        }
    }

    private interface RedisWork {
        Object run(Jedis jedis);
    }

    private void withRedis(RedisWork work) {
        try (Jedis jedis = pool.getResource()) {
            work.run(jedis);
            if (!healthy) {
                healthy = true;
                logger.info("[broker] redis reachable again");
            }
        } catch (Throwable t) {
            if (healthy) {
                healthy = false;
                logger.warning("[broker] redis unavailable: " + t);
            }
        }
    }
}
