package me.nootnoot.relay;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.SlotTokens;
import me.nootnoot.sim.net.VersionFence;

public final class RelayServer {
    private static final int MAX_PACKET = 16384;

    private static final int MAX_SESSIONS = envInt("RELAY_MAX_SESSIONS", 10_000);
    private static final long IDLE_TTL_NANOS = envInt("RELAY_IDLE_TTL_SECONDS", 30) * 1_000_000_000L;

    private static final long REBIND_GRACE_NANOS = envInt("RELAY_REBIND_GRACE_SECONDS", 3) * 1_000_000_000L;
    private static final long SWEEP_INTERVAL_NANOS = 5_000_000_000L;
    private static final long METRICS_INTERVAL_NANOS = 60_000_000_000L;

    private static final double SRC_PKT_RATE = 250.0;
    private static final double SRC_PKT_BURST = 500.0;
    private static final double SRC_BYTE_RATE = 1_000_000.0;
    private static final double SRC_BYTE_BURST = 2_000_000.0;
    private static final double SES_PKT_RATE = 600.0;
    private static final double SES_PKT_BURST = 1_200.0;
    private static final double GLOBAL_PKT_RATE = 500_000.0;
    private static final double GLOBAL_PKT_BURST = 1_000_000.0;
    private static final int MAX_SOURCES = 65_536;
    static final int MIN_SLOT_TOKEN_BYTES = 16;

    public static final class Metrics {
        public final AtomicLong forwarded = new AtomicLong();
        public final AtomicLong hello = new AtomicLong();
        public final AtomicLong rateLimited = new AtomicLong();
        public final AtomicLong invalid = new AtomicLong();
        public final AtomicLong unknownSource = new AtomicLong();
        public final AtomicLong unauthorized = new AtomicLong();
        public final AtomicLong noPeer = new AtomicLong();
        public final AtomicLong sessionCap = new AtomicLong();
        public final AtomicLong evicted = new AtomicLong();
        public final AtomicLong refereeShed = new AtomicLong();
        public final AtomicLong reclaimed = new AtomicLong();
        public final AtomicLong orphanPins = new AtomicLong();
        public final AtomicLong handlerError = new AtomicLong();
        public final AtomicLong versionMismatch = new AtomicLong();

        @Override
        public String toString() {
            return "forwarded=" + forwarded + " hello=" + hello + " rateLimited=" + rateLimited
                    + " invalid=" + invalid + " unknownSource=" + unknownSource
                    + " unauthorized=" + unauthorized + " noPeer=" + noPeer
                    + " sessionCap=" + sessionCap + " evicted=" + evicted
                    + " reclaimed=" + reclaimed + " orphanPins=" + orphanPins
                    + " handlerError=" + handlerError
                    + " versionMismatch=" + versionMismatch;
        }
    }

    private final DatagramSocket socket;

    private final boolean requireSlotAuthentication;

    private final byte[] slotSecret;

    private final int maxSessions;

    private final Map<Long, SocketAddress[]> sessions = new ConcurrentHashMap<>();

    private final Map<Long, byte[][]> pinnedTokens = new ConcurrentHashMap<>();

    private final Map<SocketAddress, long[]> peers = new ConcurrentHashMap<>();

    private final Map<SocketAddress, Long> peerLastSeen = new ConcurrentHashMap<>();

    private final Map<Long, int[]> sessionVersions = new ConcurrentHashMap<>();

    private final Map<Long, Long> sessionLastSeen = new java.util.HashMap<>();
    private final RateLimiter limiter = new RateLimiter(
            SRC_PKT_RATE, SRC_PKT_BURST, SRC_BYTE_RATE, SRC_BYTE_BURST,
            SES_PKT_RATE, SES_PKT_BURST, GLOBAL_PKT_RATE, GLOBAL_PKT_BURST, MAX_SOURCES);
    private final Metrics metrics = new Metrics();
    private RefereeManager referees;
    private ControlEndpoint control;
    private long lastSweepNanos;
    private long lastMetricsNanos;

    private volatile boolean running;
    private Thread thread;

    public RelayServer(int port) throws SocketException {
        this(port, true);
    }

    public RelayServer(int port, boolean requireSlotAuthentication) throws SocketException {
        this(port, requireSlotAuthentication, MAX_SESSIONS);
    }

    RelayServer(int port, boolean requireSlotAuthentication, int maxSessions) throws SocketException {
        this(port, requireSlotAuthentication, maxSessions, null);
    }

    public RelayServer(int port, boolean requireSlotAuthentication, byte[] slotSecret)
            throws SocketException {
        this(port, requireSlotAuthentication, MAX_SESSIONS, slotSecret);
    }

    RelayServer(int port, boolean requireSlotAuthentication, int maxSessions, byte[] slotSecret)
            throws SocketException {
        this(port, requireSlotAuthentication, maxSessions, slotSecret, null);
    }

    public RelayServer(int port, boolean requireSlotAuthentication, byte[] slotSecret,
                       InetAddress bindAddress) throws SocketException {
        this(port, requireSlotAuthentication, MAX_SESSIONS, slotSecret, bindAddress);
    }

    RelayServer(int port, boolean requireSlotAuthentication, int maxSessions, byte[] slotSecret,
                InetAddress bindAddress) throws SocketException {
        this.socket = bindAddress == null
                ? new DatagramSocket(port)
                : new DatagramSocket(new InetSocketAddress(bindAddress, port));
        this.requireSlotAuthentication = requireSlotAuthentication;
        this.maxSessions = Math.max(1, maxSessions);
        this.slotSecret = slotSecret == null || slotSecret.length == 0 ? null : slotSecret.clone();
    }

    public int sessionCount() {
        return sessions.size();
    }

    int pinnedTokenCount() {
        return pinnedTokens.size();
    }

    public boolean requiresSlotAuthentication() {
        return requireSlotAuthentication;
    }

    public boolean verifiesDerivedSlotTokens() {
        return slotSecret != null;
    }

    public int port() {
        return socket.getLocalPort();
    }

    public String boundHost() {
        InetAddress local = socket.getLocalAddress();
        if (local == null) {
            return "";
        }
        return local.isAnyLocalAddress() ? RelayBindGate.WILDCARD : local.getHostAddress();
    }

    public Metrics metrics() {
        return metrics;
    }

    public int refereeQueueDepth() {
        return referees == null ? 0 : referees.queueDepth();
    }

    public long refereeShedCount() {
        return referees == null ? 0L : referees.shedCount();
    }

    public int enableReferee(byte[] controlSecret, int controlPort) throws IOException {
        RefereeManager manager = new RefereeManager();
        try {
            this.control = new ControlEndpoint(controlPort, controlSecret, manager);
        } catch (IOException unbindable) {
            manager.stop();
            throw unbindable;
        }
        this.referees = manager;
        manager.onShed(() -> metrics.refereeShed.incrementAndGet());
        this.control.start();
        return this.control.port();
    }

    int refereeConfirmedFrame(long sessionId) {
        return referees == null ? -1 : referees.confirmedFrame(sessionId);
    }

    void finalizeReferee(long sessionId) {
        if (referees != null) {
            referees.finalizeSession(sessionId);
        }
    }

    public void start() {
        running = true;
        thread = new Thread(this::loop, "relay-server");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        socket.close();
        if (control != null) {
            control.stop();
        }
        if (referees != null) {
            referees.stop();
        }
    }

    private void loop() {
        byte[] buf = new byte[MAX_PACKET];
        lastSweepNanos = System.nanoTime();
        lastMetricsNanos = lastSweepNanos;
        try {
            socket.setSoTimeout(200);
        } catch (SocketException ignored) {
        }
        while (running) {
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(dp);
                handle(dp);
            } catch (SocketTimeoutException e) {
            } catch (IOException e) {
                if (!running) {
                    return;
                }
            } catch (Throwable t) {
                metrics.handlerError.incrementAndGet();
            }
            try {
                maintain(System.nanoTime());
            } catch (Throwable t) {
                metrics.handlerError.incrementAndGet();
            }
        }
    }

    private void handle(DatagramPacket dp) {
        SocketAddress src = dp.getSocketAddress();
        byte[] buf = dp.getData();
        int len = dp.getLength();
        long now = System.nanoTime();

        if (!limiter.allowGlobal(len, now) || !limiter.allowSource(src, len, now)) {
            metrics.rateLimited.incrementAndGet();
            return;
        }
        if (!Protocol.isWellFormed(buf, len)) {
            metrics.invalid.incrementAndGet();
            return;
        }
        if (Protocol.isHello(buf, len)) {
            registerHello(src, buf, len, now);
            return;
        }

        long[] info = peers.get(src);
        if (info == null) {
            metrics.unknownSource.incrementAndGet();
            return;
        }
        if (!limiter.allowSession(info[0], len, now)) {
            metrics.rateLimited.incrementAndGet();
            return;
        }
        SocketAddress[] pair = sessions.get(info[0]);
        if (pair == null) {
            metrics.unknownSource.incrementAndGet();
            return;
        }
        SocketAddress dst = pair[info[1] == 0 ? 1 : 0];
        if (dst == null) {
            metrics.noPeer.incrementAndGet();
            return;
        }
        sessionLastSeen.put(info[0], now);
        peerLastSeen.put(src, now);
        try {
            socket.send(new DatagramPacket(buf, len, dst));
            metrics.forwarded.incrementAndGet();
            if (referees != null) {
                referees.tee(info[0], (int) info[1], java.util.Arrays.copyOf(buf, len));
            }
        } catch (IOException ignored) {
        }
    }

    private void registerHello(SocketAddress src, byte[] buf, int len, long now) {
        Message.Hello h;
        try {
            h = (Message.Hello) Protocol.decode(java.util.Arrays.copyOf(buf, len));
        } catch (RuntimeException notDecodable) {
            metrics.invalid.incrementAndGet();
            return;
        }
        int slot = (h.slot() == 0 || h.slot() == 1) ? h.slot() : 0;

        SocketAddress[] pair = sessions.get(h.sessionId());
        if (pair == null && sessions.size() >= maxSessions && !reclaimSessionSlot(now)) {
            metrics.sessionCap.incrementAndGet();
            return;
        }

        byte[] token = h.token() == null ? Message.EMPTY_TOKEN : h.token();
        if (slotSecret != null) {
            if (!SlotTokens.matches(slotSecret, h.sessionId(), slot, token)) {
                metrics.unauthorized.incrementAndGet();
                return;
            }
        } else if (referees != null && referees.isAuthorized(h.sessionId())) {
            if (!referees.tokenMatches(h.sessionId(), slot, token)) {
                metrics.invalid.incrementAndGet();
                return;
            }
        } else if (requireSlotAuthentication && !pinSlotToken(h.sessionId(), slot, token)) {
            metrics.unauthorized.incrementAndGet();
            return;
        }
        if (pair == null) {
            pair = new SocketAddress[2];
            SocketAddress[] prev = sessions.putIfAbsent(h.sessionId(), pair);
            if (prev != null) {
                pair = prev;
            }
        }
        SocketAddress old = pair[slot];
        if (old != null && !old.equals(src)) {
            boolean sameHost = old instanceof InetSocketAddress a && src instanceof InetSocketAddress b
                    && a.getAddress() != null && a.getAddress().equals(b.getAddress());
            Long lastSeen = peerLastSeen.get(old);
            boolean stale = lastSeen == null || now - lastSeen >= REBIND_GRACE_NANOS;

            if (!sameHost && !stale) {
                metrics.invalid.incrementAndGet();
                return;
            }

            peers.remove(old);
            peerLastSeen.remove(old);
        }
        pair[slot] = src;
        peers.put(src, new long[]{h.sessionId(), slot});
        peerLastSeen.put(src, now);
        int[] vers = sessionVersions.computeIfAbsent(h.sessionId(),
                k -> new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE});
        vers[slot] = h.protocolVersion();
        sessionLastSeen.put(h.sessionId(), now);
        metrics.hello.incrementAndGet();

        if (pair[0] != null && pair[1] != null
                && vers[0] != Integer.MIN_VALUE && vers[1] != Integer.MIN_VALUE && vers[0] != vers[1]) {
            metrics.versionMismatch.incrementAndGet();
            byte[] abort = Protocol.encode(new Message.Abort(Protocol.ABORT_VERSION_MISMATCH));
            sendTo(pair[0], abort);
            sendTo(pair[1], abort);
            for (String line : VersionFence.report(
                    "[relay] session " + h.sessionId() + " aborted before frame 0: the two peers do"
                            + " not speak the same protocol. Each peer is an edge plugin or a"
                            + " modded client, whichever is hosting that slot.",
                    "slot 0 (" + pair[0] + ")", vers[0],
                    "slot 1 (" + pair[1] + ")", vers[1])) {
                System.out.println("[relay] " + line);
            }
        }
    }

    private boolean pinSlotToken(long session, int slot, byte[] token) {
        if (token.length < MIN_SLOT_TOKEN_BYTES) {
            return false;
        }
        byte[][] pinned = pinnedTokens.get(session);
        if (pinned == null) {
            if (pinnedTokens.size() >= maxSessions) {
                sweepOrphanPins();
                if (pinnedTokens.size() >= maxSessions) {
                    return false;
                }
            }
            byte[][] fresh = new byte[2][];
            byte[][] prev = pinnedTokens.putIfAbsent(session, fresh);
            pinned = prev != null ? prev : fresh;
        }
        synchronized (pinned) {
            if (pinned[slot] == null) {
                pinned[slot] = token.clone();
                return true;
            }
            return MessageDigest.isEqual(pinned[slot], token);
        }
    }

    private void sweepOrphanPins() {
        for (Iterator<Map.Entry<Long, byte[][]>> it = pinnedTokens.entrySet().iterator(); it.hasNext(); ) {
            if (!sessions.containsKey(it.next().getKey())) {
                it.remove();
                metrics.orphanPins.incrementAndGet();
            }
        }
    }

    private boolean reclaimSessionSlot(long now) {
        long victim = 0L;
        long oldest = Long.MAX_VALUE;
        boolean found = false;
        for (Map.Entry<Long, Long> e : sessionLastSeen.entrySet()) {
            if (now - e.getValue() < REBIND_GRACE_NANOS) {
                continue;
            }
            SocketAddress[] pair = sessions.get(e.getKey());
            if (pair != null && pair[0] != null && pair[1] != null) {
                continue;
            }
            if (e.getValue() < oldest) {
                oldest = e.getValue();
                victim = e.getKey();
                found = true;
            }
        }
        if (!found) {
            return false;
        }
        evict(victim);
        sessionLastSeen.remove(victim);
        metrics.reclaimed.incrementAndGet();
        return true;
    }

    private void maintain(long now) {
        if (now - lastSweepNanos >= SWEEP_INTERVAL_NANOS) {
            lastSweepNanos = now;
            Iterator<Map.Entry<Long, Long>> it = sessionLastSeen.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Long, Long> e = it.next();
                if (now - e.getValue() > IDLE_TTL_NANOS) {
                    evict(e.getKey());
                    it.remove();
                    metrics.evicted.incrementAndGet();
                }
            }
            sweepOrphanPins();
        }
        if (now - lastMetricsNanos >= METRICS_INTERVAL_NANOS) {
            lastMetricsNanos = now;
            System.out.println("[relay] metrics: " + metrics + " sessions=" + sessions.size());
        }
    }

    private void evict(long session) {
        SocketAddress[] pair = sessions.remove(session);
        if (pair != null) {
            if (pair[0] != null) {
                peers.remove(pair[0]);
                peerLastSeen.remove(pair[0]);
            }
            if (pair[1] != null) {
                peers.remove(pair[1]);
                peerLastSeen.remove(pair[1]);
            }
        }
        sessionVersions.remove(session);
        pinnedTokens.remove(session);
        limiter.forgetSession(session);
        if (referees != null) {
            referees.finalizeSession(session);
        }
    }

    private void sendTo(SocketAddress dst, byte[] data) {
        try {
            socket.send(new DatagramPacket(data, data.length, dst));
        } catch (IOException ignored) {
        }
    }

    private static int envInt(String name, int fallback) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) {
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static boolean envFlag(String name) {
        String v = System.getenv(name);
        return v != null && ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()));
    }

    public static void main(String[] args) throws Exception {
        int port = 7777;

            String env = System.getenv("RELAY_PORT");
            if (env != null && !env.isBlank()) {
                port = Integer.parseInt(env.trim());
            }

        String slotSecret = System.getenv(RelayBindGate.SECRET_ENV);
        RelayBindGate.Verdict bind = RelayBindGate.evaluate(slotSecret,
                System.getenv(RelayBindGate.BIND_ENV),
                envFlag(RelayBindGate.PUBLIC_ENV), envFlag(RelayBindGate.OPEN_ENV));
        if (!bind.start()) {
            System.err.println("[relay] " + bind.message());
            System.exit(RelayBindGate.EXIT_CONFIG);
            return;
        }
        InetAddress bindAddress;
        try {
            bindAddress = InetAddress.getByName(bind.bindHost());
        } catch (UnknownHostException unresolvable) {
            System.err.println("[relay] #### RELAY REFUSED TO START: " + RelayBindGate.BIND_ENV
                    + " DOES NOT RESOLVE #### " + RelayBindGate.BIND_ENV + "=" + bind.bindHost()
                    + " is not an address this host can bind (" + unresolvable + "). Use a literal"
                    + " address this machine owns, " + RelayBindGate.WILDCARD + " for every"
                    + " interface, or drop the variable.");
            System.exit(RelayBindGate.EXIT_CONFIG);
            return;
        }
        (bind.severe() ? System.err : System.out).println("[relay] " + bind.message());
        byte[] slotKey = slotSecret == null || slotSecret.isBlank() ? null
                : slotSecret.trim().getBytes(StandardCharsets.UTF_8);
        RelayServer relay = new RelayServer(port, bind.slotAuthentication(), slotKey, bindAddress);
        String controlSecret = System.getenv("RELAY_CONTROL_SECRET");
        int refereeControlPort = 0;
        if (controlSecret == null || controlSecret.isBlank()) {
            System.out.println("[relay] #### NO REFEREE ON THIS RELAY #### RELAY_CONTROL_SECRET is"
                    + " unset, so the control endpoint never opens, nothing can authorize a"
                    + " session and this relay re-simulates nothing. Every result it carries rests"
                    + " on the two clients corroborating each other, which means a loser who"
                    + " refuses to concede can force a VOID and deny an honest winner their ELO."
                    + " Set it to the same value core has as ROLLBACK_REFEREE_CONTROL_SECRET.");
        } else {
            int controlPort = envInt("RELAY_CONTROL_PORT", 7778);
            try {
                controlPort = relay.enableReferee(
                        controlSecret.getBytes(StandardCharsets.UTF_8), controlPort);
                refereeControlPort = controlPort;
                System.out.println("[relay] referee + control endpoint on tcp/" + controlPort
                        + " - every forwarded input frame of an authorized session is"
                        + " re-simulated here and the result is signed with RELAY_CONTROL_SECRET");
            } catch (IOException unbindable) {
                System.out.println("[relay] #### NO REFEREE ON THIS RELAY #### could not bind the"
                        + " control endpoint on tcp/" + controlPort + " (" + unbindable + ")."
                        + " The relay keeps forwarding, but nothing can authorize a session and"
                        + " every result it carries falls back to two-witness client"
                        + " corroboration. Free the port or move it with RELAY_CONTROL_PORT.");
            }
        }
        int metricsPort = envInt("RELAY_METRICS_PORT", RelayMetricsEndpoint.DEFAULT_PORT);
        if (metricsPort > 0) {
            try {
                RelayMetricsEndpoint endpoint = RelayMetricsEndpoint.start(metricsPort,
                        relay.metrics(), () -> relay.sessions.size(),
                        System.getenv("RELAY_ID") == null ? "relay" : System.getenv("RELAY_ID"));
                System.out.println("[relay] prometheus metrics on http://0.0.0.0:"
                        + endpoint.port() + "/metrics - watch rollback_relay_unauthorized_total"
                        + " and rollback_relay_version_mismatch_total");
            } catch (IOException ex) {
                System.out.println("[relay] could not bind the metrics endpoint on tcp/"
                        + metricsPort + " (" + ex + ") - the relay keeps forwarding, but nothing"
                        + " can scrape it");
            }
        }
        RelayRegistry registry = RelayRegistry.fromEnv(relay, refereeControlPort);
        if (registry != null) {
            registry.start();
            Runtime.getRuntime().addShutdownHook(
                    new Thread(registry::stop, "relay-registry-shutdown"));
        }
        relay.running = true;
        System.out.println("[relay] listening on udp/" + relay.boundHost() + ":"
                + relay.port() + " in " + bind.mode() + " mode");
        relay.loop();
    }
}
