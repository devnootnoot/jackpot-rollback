package me.nootnoot.relay;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import me.nootnoot.sim.net.Protocol;

final class RelayRegistry {

    static final String KEY_SERVERS = "rollback:relay:servers";

    static final String ID_ENV = "RELAY_ID";
    static final String REGION_ENV = "REGION";
    static final String HOST_ENV = "RELAY_ADVERTISE_HOST";
    static final String PORT_ENV = "RELAY_ADVERTISE_PORT";
    static final String CONTROL_PORT_ENV = "RELAY_ADVERTISE_CONTROL_PORT";
    static final String REDIS_HOST_ENV = "REDIS_HOST";
    static final String REDIS_PORT_ENV = "REDIS_PORT";
    static final String REDIS_PASSWORD_ENV = "REDIS_PASSWORD";
    static final String OFF_ENV = "RELAY_REGISTRY_DISABLED";

    static final int HEARTBEAT_TTL_SECONDS = 30;

    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final String UNKNOWN_REGION = "unknown";

    private final String relayId;
    private final String region;
    private final String host;
    private final int port;
    private final int controlPort;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final IntSupplier sessions;
    private final IntSupplier refereeQueueDepth;
    private final LongSupplier refereeShed;

    private volatile boolean running;
    private volatile boolean listed;
    private volatile boolean warned;
    private volatile Thread thread;

    RelayRegistry(String relayId, String region, String host, int port, int controlPort,
                  String redisHost, int redisPort, String redisPassword,
                  IntSupplier sessions, IntSupplier refereeQueueDepth, LongSupplier refereeShed) {
        this.relayId = relayId;
        this.region = region;
        this.host = host;
        this.port = port;
        this.controlPort = controlPort;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword == null ? "" : redisPassword;
        this.sessions = sessions;
        this.refereeQueueDepth = refereeQueueDepth;
        this.refereeShed = refereeShed;
    }

    static RelayRegistry fromEnv(RelayServer relay, int refereeControlPort) {
        if (envFlag(OFF_ENV)) {
            System.out.println("[relay] " + OFF_ENV + " is set, so this relay stays out of "
                    + KEY_SERVERS + " - core can only send it a match if something names it"
                    + " explicitly");
            return null;
        }
        String redisHost = env(REDIS_HOST_ENV);
        if (redisHost.isEmpty()) {
            System.out.println("[relay] #### THIS RELAY IS UNLISTED #### " + REDIS_HOST_ENV
                    + " is unset, so it never registers in " + KEY_SERVERS + " and core cannot"
                    + " discover it. Point it at the same global redis core uses.");
            return null;
        }
        String host = env(HOST_ENV);
        if (host.isEmpty()) {
            System.out.println("[relay] #### THIS RELAY IS UNLISTED #### " + HOST_ENV
                    + " is unset. The address this process binds is not the address a client"
                    + " dials, so publishing it would hand every match a dead endpoint. Set "
                    + HOST_ENV + " to the public host of this relay.");
            return null;
        }
        String region = env(REGION_ENV);
        if (region.isEmpty()) {
            region = UNKNOWN_REGION;
            System.out.println("[relay] " + REGION_ENV + " is unset, so this relay registers in"
                    + " region \"" + UNKNOWN_REGION + "\" and is only ever picked as a last"
                    + " resort - set it to the region players in front of this relay are in");
        }
        int port = envInt(PORT_ENV, relay.port());
        int controlPort = envInt(CONTROL_PORT_ENV, refereeControlPort);
        String relayId = env(ID_ENV);
        if (relayId.isEmpty()) {
            relayId = host + ":" + port;
        }
        return new RelayRegistry(relayId, region, host, port, controlPort, redisHost,
                envInt(REDIS_PORT_ENV, DEFAULT_REDIS_PORT), env(REDIS_PASSWORD_ENV),
                relay::sessionCount, relay::refereeQueueDepth, relay::refereeShedCount);
    }

    String relayId() {
        return relayId;
    }

    boolean listed() {
        return listed;
    }

    void start() {
        running = true;
        Thread t = new Thread(this::loop, "relay-registry");
        t.setDaemon(true);
        thread = t;
        t.start();
        System.out.println("[relay] registering as " + relayId + " (region " + region + ") in "
                + KEY_SERVERS + " on " + redisHost + ":" + redisPort + " every "
                + (HEARTBEAT_INTERVAL_MS / 1000L) + "s, advertising udp/" + host + ":" + port
                + " and control tcp/" + controlPort + " - core discovers it with no config");
    }

    void stop() {
        if (!running) {
            return;
        }
        running = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        deregister();
    }

    private void loop() {
        while (running) {
            heartbeat();
            try {
                Thread.sleep(HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    boolean heartbeat() {
        try (RespClient redis = RespClient.connect(redisHost, redisPort, redisPassword)) {
            redis.hset(KEY_SERVERS, relayId, json(System.currentTimeMillis()));
            redis.expire(KEY_SERVERS, HEARTBEAT_TTL_SECONDS * 4L);
            if (!listed) {
                System.out.println("[relay] listed in " + KEY_SERVERS + " as " + relayId);
            }
            listed = true;
            warned = false;
            return true;
        } catch (Throwable unreachable) {
            boolean first = listed || !warned;
            listed = false;
            warned = true;
            if (first) {
                System.out.println("[relay] #### THIS RELAY IS UNLISTED #### could not write "
                        + KEY_SERVERS + " on " + redisHost + ":" + redisPort + " (" + unreachable
                        + ") - forwarding is untouched, but core cannot pick this relay for a new"
                        + " match until the registry comes back");
            }
            return false;
        }
    }

    private void deregister() {
        try (RespClient redis = RespClient.connect(redisHost, redisPort, redisPassword)) {
            redis.hdel(KEY_SERVERS, relayId);
            listed = false;
            System.out.println("[relay] deregistered " + relayId + " from " + KEY_SERVERS);
        } catch (Throwable unreachable) {
            System.out.println("[relay] could not deregister " + relayId + " from " + KEY_SERVERS
                    + " (" + unreachable + ") - the entry goes stale on its own");
        }
    }

    String json(long nowMs) {
        StringBuilder b = new StringBuilder(256);
        b.append('{');
        string(b, "relayId", relayId).append(',');
        string(b, "region", region).append(',');
        string(b, "host", host).append(',');
        number(b, "port", port).append(',');
        number(b, "controlPort", controlPort).append(',');
        number(b, "sessions", sessions.getAsInt()).append(',');
        number(b, "refereeQueueDepth", refereeQueueDepth.getAsInt()).append(',');
        number(b, "refereeShed", refereeShed.getAsLong()).append(',');
        number(b, "protocolVersion", Protocol.VERSION).append(',');
        number(b, "at", nowMs);
        return b.append('}').toString();
    }

    private static StringBuilder string(StringBuilder b, String name, String value) {
        b.append('"').append(name).append("\":\"");
        escape(b, value);
        return b.append('"');
    }

    private static StringBuilder number(StringBuilder b, String name, long value) {
        return b.append('"').append(name).append("\":").append(value);
    }

    private static void escape(StringBuilder b, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? "" : v.trim();
    }

    private static int envInt(String name, int fallback) {
        String v = env(name);
        if (!v.isEmpty()) {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static boolean envFlag(String name) {
        String v = env(name);
        return "true".equalsIgnoreCase(v) || "1".equals(v);
    }
}
