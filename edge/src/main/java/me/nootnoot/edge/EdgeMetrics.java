package me.nootnoot.edge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import me.nootnoot.sim.SimProbe;
import me.nootnoot.sim.net.InputCodec;
import me.nootnoot.sim.net.NetSession;
import me.nootnoot.sim.net.Protocol;

public final class EdgeMetrics {

    public static final int MATCHES_STARTED = 0;
    public static final int MATCHES_ENDED = 1;
    public static final int ROLLBACKS = 2;
    public static final int RESIMULATED_FRAMES = 3;
    public static final int CATCHUP_BURSTS = 4;
    public static final int CATCHUP_FRAMES = 5;
    public static final int DESYNC_ABORTS = 6;
    public static final int PEER_TIMEOUTS = 7;
    public static final int MOVEMENT_CLAMPS = 8;
    public static final int BLOCK_REACH_REFUSED = 9;
    public static final int ASSIGNMENTS_RECEIVED = 10;
    public static final int ASSIGNMENTS_EXPIRED = 11;
    public static final int ASSIGNMENTS_UNPARSEABLE = 12;
    public static final int RESULTS_DROPPED = 13;
    public static final int SIM_FAULTS = 14;
    public static final int RENDER_FAULTS = 15;
    public static final int CONTAINER_BLOBS_REFUSED = 16;
    public static final int SIM_FRAMES = 17;
    public static final int TEARDOWN_FAULTS = 18;
    public static final int PLACEMENT_PRECHECK_STALE = 19;
    public static final int MOVEMENT_CORRECTIONS_REFUSED = 20;
    public static final int COUNTERS = 21;

    private static final String[] COUNTER_NAMES = {
            "matches_started_total",
            "matches_ended_total",
            "rollbacks_total",
            "resimulated_frames_total",
            "catchup_bursts_total",
            "catchup_frames_total",
            "desync_aborts_total",
            "peer_timeouts_total",
            "movement_clamps_total",
            "claim_block_reach_refused_total",
            "assignments_received_total",
            "assignments_expired_total",
            "assignments_unparseable_total",
            "results_dropped_total",
            "sim_faults_total",
            "render_faults_total",
            "container_blobs_refused_total",
            "sim_frames_total",
            "teardown_faults_total",
            "placement_precheck_stale_total",
            "movement_corrections_refused_total",
    };

    private static final int[] PROBE_COUNTERS = {
            SimProbe.AUTHORITY_STAMP_WALKED,
            SimProbe.AUTHORITY_STAMP_CLIPPED,
            SimProbe.MELEE_CLAIM_GRANTED_OFF_AIM,
            SimProbe.MELEE_CLAIM_ATTEMPT,
            SimProbe.MELEE_CLAIM_GRANTED,
            SimProbe.MELEE_CLAIM_REFUSED,
            SimProbe.MELEE_CLAIM_REFUSED_OUT_OF_REACH,
            SimProbe.MELEE_CLAIM_REFUSED_OCCLUDED,
            SimProbe.ARROW_CLAIM_ATTEMPT,
            SimProbe.ARROW_CLAIM_GRANTED,
            SimProbe.ARROW_CLAIM_REFUSED,
            SimProbe.SIGHT_TEST,
            SimProbe.SIGHT_BLOCKED_BY_ARENA,
            SimProbe.SIGHT_BLOCKED_BY_PLACED_BLOCK,
            SimProbe.COBWEB_SIGHT_CROSSED,
            SimProbe.PROJECTILE_SPAWN_REFUSED,
            SimProbe.ITEM_ENTITY_REFUSED,
    };

    private static final String[] PROBE_METRIC_NAMES = {
            "authority_stamp_walked_total",
            "authority_stamp_clipped_total",
            "claim_melee_granted_off_aim_total",
            "claim_melee_attempts_total",
            "claim_melee_granted_total",
            "claim_melee_refused_total",
            "claim_melee_refused_out_of_reach_total",
            "claim_melee_refused_occluded_total",
            "claim_arrow_attempts_total",
            "claim_arrow_granted_total",
            "claim_arrow_refused_total",
            "claim_sightline_tests_total",
            "claim_sightline_blocked_by_arena_total",
            "claim_sightline_blocked_by_placed_block_total",
            "claim_sightline_cobweb_crossed_total",
            "claim_projectile_spawn_refused_total",
            "claim_item_entity_refused_total",
    };

    private final AtomicLong[] counters = new AtomicLong[COUNTERS];

    private final Map<String, AtomicLong> outcomes = new LinkedHashMap<>();

    private final AtomicLong rollbackDepthSum = new AtomicLong();
    private final AtomicLong rollbackDepthMax = new AtomicLong();
    private final AtomicLong frameDeficitMax = new AtomicLong();
    private final AtomicLong catchupBurstMax = new AtomicLong();

    private final AtomicLong pickupMillisSum = new AtomicLong();
    private final AtomicLong pickupMillisCount = new AtomicLong();
    private final AtomicLong pickupMillisMax = new AtomicLong();
    private final AtomicLong frameZeroMillisSum = new AtomicLong();
    private final AtomicLong frameZeroMillisCount = new AtomicLong();
    private final AtomicLong frameZeroMillisMax = new AtomicLong();

    private final long[] probeSink = new long[SimProbe.COUNTERS];

    private volatile long[] probeMirror = new long[SimProbe.COUNTERS];

    private volatile int liveMatches;
    private volatile int pendingMatches;
    private volatile int unclaimedAssignments;
    private volatile int frameDeficit;
    private volatile int queuedResults;
    private volatile boolean redisHealthy = true;
    private volatile boolean directLinkUp;
    private volatile long lastDesyncAtMs;
    private volatile int lastDesyncFrame = -1;
    private volatile String lastDesyncDetail = "";

    private final long startedAtMs = System.currentTimeMillis();

    private final String edgeId;
    private final String region;

    public EdgeMetrics(String edgeId, String region) {
        this.edgeId = edgeId == null ? "" : edgeId;
        this.region = region == null ? "" : region;
        for (int i = 0; i < COUNTERS; i++) {
            counters[i] = new AtomicLong();
        }
        for (String cause : new String[]{EdgeOutcome.FINISHED, EdgeOutcome.LOCAL_QUIT,
                EdgeOutcome.LOCAL_FORFEIT, EdgeOutcome.PEER_GONE, EdgeOutcome.DESYNC,
                EdgeOutcome.SELF_FAULT, EdgeOutcome.PEER_NEVER_ARRIVED,
                EdgeOutcome.ARENA_MISMATCH, EdgeOutcome.NO_FRAME_ZERO,
                EdgeOutcome.DESYNC_ANNOUNCED, EdgeOutcome.PEER_OVERRUN}) {
            outcomes.put(cause, new AtomicLong());
        }
    }

    public String edgeId() {
        return edgeId;
    }

    public String region() {
        return region;
    }

    public long[] probeSink() {
        return probeSink;
    }

    public void hit(int counter) {
        counters[counter].incrementAndGet();
    }

    public void add(int counter, long amount) {
        counters[counter].addAndGet(amount);
    }

    public void outcome(String cause) {
        if (cause == null || cause.isEmpty()) {
            return;
        }
        outcomes.computeIfAbsent(cause, ignored -> new AtomicLong()).incrementAndGet();
        counters[MATCHES_ENDED].incrementAndGet();
        if (EdgeOutcome.DESYNC.equals(cause) || EdgeOutcome.DESYNC_ANNOUNCED.equals(cause)) {
            counters[DESYNC_ABORTS].incrementAndGet();
        } else if (EdgeOutcome.PEER_GONE.equals(cause)) {
            counters[PEER_TIMEOUTS].incrementAndGet();
        } else if (EdgeOutcome.SELF_FAULT.equals(cause)) {
            counters[SIM_FAULTS].incrementAndGet();
        }
    }

    public void recordDesync(int frame, String detail) {
        lastDesyncFrame = frame;
        lastDesyncDetail = detail == null ? "" : detail;
        lastDesyncAtMs = System.currentTimeMillis();
    }

    public void recordRollback(int rollbacks, int unconfirmedWindow) {
        if (rollbacks <= 0) {
            return;
        }
        counters[ROLLBACKS].addAndGet(rollbacks);
        long window = Math.max(0, unconfirmedWindow);
        counters[RESIMULATED_FRAMES].addAndGet(window * rollbacks);
        rollbackDepthSum.addAndGet(window * rollbacks);
        raise(rollbackDepthMax, window);
    }

    public void recordFrameDeficit(int deficit) {
        frameDeficit = deficit;
        raise(frameDeficitMax, deficit);
    }

    public void recordCatchUp(int frames) {
        if (frames <= 0) {
            return;
        }
        counters[CATCHUP_BURSTS].incrementAndGet();
        counters[CATCHUP_FRAMES].addAndGet(frames);
        raise(catchupBurstMax, frames);
    }

    public void recordAssignmentPickup(long millis) {
        if (millis < 0) {
            return;
        }
        pickupMillisSum.addAndGet(millis);
        pickupMillisCount.incrementAndGet();
        raise(pickupMillisMax, millis);
    }

    public void recordFrameZeroLatency(long millis) {
        if (millis < 0) {
            return;
        }
        frameZeroMillisSum.addAndGet(millis);
        frameZeroMillisCount.incrementAndGet();
        raise(frameZeroMillisMax, millis);
    }

    public void gauges(int live, int pendingCount, int unclaimed, int queued, boolean healthy,
                       boolean direct) {
        liveMatches = live;
        pendingMatches = pendingCount;
        unclaimedAssignments = unclaimed;
        queuedResults = queued;
        redisHealthy = healthy;
        directLinkUp = direct;
    }

    public void mirrorProbes() {
        long[] copy = new long[SimProbe.COUNTERS];
        System.arraycopy(probeSink, 0, copy, 0, SimProbe.COUNTERS);
        probeMirror = copy;
    }

    private static void raise(AtomicLong target, long candidate) {
        long seen = target.get();
        while (candidate > seen && !target.compareAndSet(seen, candidate)) {
            seen = target.get();
        }
    }

    public record Sample(String name, long value) {
    }

    public record Snapshot(String edgeId, String region, long atMs, long uptimeMs,
                          int inputBytes, int checksumRev, int protocolVersion,
                          int peerTimeoutTicks, int liveMatches, int pendingMatches,
                          int unclaimedAssignments, int frameDeficit, int queuedResults,
                          boolean redisHealthy, boolean directLinkUp,
                          long lastDesyncAtMs, int lastDesyncFrame, String lastDesyncDetail,
                          long desyncAborts, long matchesEnded, double desyncAbortRatio,
                          List<Sample> counters, List<Sample> outcomes, List<Sample> claims,
                          List<Sample> latencies) {

        public String versionFence() {
            return "inputBytes=" + inputBytes + " checksumRev=" + checksumRev
                    + " protocolVersion=" + protocolVersion;
        }
    }

    public Snapshot snapshot() {
        long ended = counters[MATCHES_ENDED].get();
        long desyncs = counters[DESYNC_ABORTS].get();

        List<Sample> counterSamples = new ArrayList<>(COUNTERS + 4);
        for (int i = 0; i < COUNTERS; i++) {
            counterSamples.add(new Sample(COUNTER_NAMES[i], counters[i].get()));
        }
        counterSamples.add(new Sample("rollback_depth_frames_sum", rollbackDepthSum.get()));
        counterSamples.add(new Sample("rollback_depth_frames_max", rollbackDepthMax.get()));
        counterSamples.add(new Sample("frame_deficit_frames_max", frameDeficitMax.get()));
        counterSamples.add(new Sample("catchup_burst_frames_max", catchupBurstMax.get()));

        List<Sample> outcomeSamples = new ArrayList<>(outcomes.size());
        for (Map.Entry<String, AtomicLong> e : outcomes.entrySet()) {
            outcomeSamples.add(new Sample(e.getKey(), e.getValue().get()));
        }

        long[] probes = probeMirror;
        List<Sample> claimSamples = new ArrayList<>(PROBE_COUNTERS.length);
        for (int i = 0; i < PROBE_COUNTERS.length; i++) {
            claimSamples.add(new Sample(PROBE_METRIC_NAMES[i], probes[PROBE_COUNTERS[i]]));
        }
        claimSamples.add(new Sample("claim_block_reach_refused_total",
                counters[BLOCK_REACH_REFUSED].get()));

        List<Sample> latencySamples = new ArrayList<>(6);
        latencySamples.add(new Sample("assignment_pickup_millis_sum", pickupMillisSum.get()));
        latencySamples.add(new Sample("assignment_pickup_millis_count", pickupMillisCount.get()));
        latencySamples.add(new Sample("assignment_pickup_millis_max", pickupMillisMax.get()));
        latencySamples.add(new Sample("assignment_frame_zero_millis_sum",
                frameZeroMillisSum.get()));
        latencySamples.add(new Sample("assignment_frame_zero_millis_count",
                frameZeroMillisCount.get()));
        latencySamples.add(new Sample("assignment_frame_zero_millis_max",
                frameZeroMillisMax.get()));

        return new Snapshot(edgeId, region, System.currentTimeMillis(),
                System.currentTimeMillis() - startedAtMs,
                InputCodec.BYTES, Protocol.CHECKSUM_REV, Protocol.VERSION,
                NetSession.PEER_TIMEOUT_TICKS, liveMatches, pendingMatches, unclaimedAssignments,
                frameDeficit, queuedResults, redisHealthy, directLinkUp,
                lastDesyncAtMs, lastDesyncFrame, lastDesyncDetail,
                desyncs, ended, ended == 0 ? 0.0 : (double) desyncs / (double) ended,
                counterSamples, outcomeSamples, claimSamples, latencySamples);
    }

    public static String json(Snapshot s) {
        StringBuilder b = new StringBuilder(2048);
        b.append('{');
        field(b, "v", 1).append(',');
        field(b, "edgeId", s.edgeId()).append(',');
        field(b, "region", s.region()).append(',');
        field(b, "at", s.atMs()).append(',');
        field(b, "uptimeMs", s.uptimeMs()).append(',');
        field(b, "inputBytes", s.inputBytes()).append(',');
        field(b, "checksumRev", s.checksumRev()).append(',');
        field(b, "protocolVersion", s.protocolVersion()).append(',');
        field(b, "liveMatches", s.liveMatches()).append(',');
        field(b, "pendingMatches", s.pendingMatches()).append(',');
        field(b, "unclaimedAssignments", s.unclaimedAssignments()).append(',');
        field(b, "frameDeficit", s.frameDeficit()).append(',');
        field(b, "queuedResults", s.queuedResults()).append(',');
        field(b, "redisHealthy", s.redisHealthy() ? 1 : 0).append(',');
        field(b, "directLinkUp", s.directLinkUp() ? 1 : 0).append(',');
        field(b, "lastDesyncAt", s.lastDesyncAtMs()).append(',');
        field(b, "lastDesyncFrame", s.lastDesyncFrame()).append(',');
        field(b, "lastDesyncDetail", s.lastDesyncDetail()).append(',');
        b.append("\"counters\":");
        samples(b, s.counters());
        b.append(",\"outcomes\":");
        samples(b, s.outcomes());
        b.append(",\"claims\":");
        samples(b, s.claims());
        b.append(",\"latency\":");
        samples(b, s.latencies());
        b.append('}');
        return b.toString();
    }

    public static String prometheus(Snapshot s) {
        StringBuilder b = new StringBuilder(4096);
        String labels = "{edge=\"" + escape(s.edgeId()) + "\",region=\"" + escape(s.region())
                + "\"}";
        b.append("# HELP rollback_edge_build_info The version fence this edge enforces. A desync"
                + " between two edges whose triples differ is a deploy skew, not a sim bug.\n");
        b.append("# TYPE rollback_edge_build_info gauge\n");
        b.append("rollback_edge_build_info{edge=\"").append(escape(s.edgeId()))
                .append("\",region=\"").append(escape(s.region()))
                .append("\",input_bytes=\"").append(s.inputBytes())
                .append("\",checksum_rev=\"").append(s.checksumRev())
                .append("\",protocol_version=\"").append(s.protocolVersion())
                .append("\"} 1\n");
        gauge(b, "rollback_edge_uptime_millis", labels, s.uptimeMs());
        gauge(b, "rollback_edge_matches_live", labels, s.liveMatches());
        gauge(b, "rollback_edge_matches_pending", labels, s.pendingMatches());
        gauge(b, "rollback_edge_assignments_unclaimed", labels, s.unclaimedAssignments());
        gauge(b, "rollback_edge_frame_deficit_frames", labels, s.frameDeficit());
        gauge(b, "rollback_edge_results_queued", labels, s.queuedResults());
        gauge(b, "rollback_edge_redis_up", labels, s.redisHealthy() ? 1 : 0);
        gauge(b, "rollback_edge_direct_link_up", labels, s.directLinkUp() ? 1 : 0);
        gauge(b, "rollback_edge_peer_timeout_ticks", labels, s.peerTimeoutTicks());
        gauge(b, "rollback_edge_last_desync_unixtime_millis", labels, s.lastDesyncAtMs());
        gauge(b, "rollback_edge_last_desync_frame", labels, s.lastDesyncFrame());
        b.append("# HELP rollback_edge_desync_abort_ratio Desync aborts divided by matches ended"
                + " on this edge since start. THE alert metric.\n");
        b.append("# TYPE rollback_edge_desync_abort_ratio gauge\n");
        b.append("rollback_edge_desync_abort_ratio").append(labels).append(' ')
                .append(String.format(Locale.ROOT, "%.6f", s.desyncAbortRatio())).append('\n');
        for (Sample sample : s.counters()) {
            counter(b, "rollback_edge_" + sample.name(), labels, sample.value());
        }
        for (Sample sample : s.claims()) {
            counter(b, "rollback_edge_" + sample.name(), labels, sample.value());
        }
        for (Sample sample : s.latencies()) {
            counter(b, "rollback_edge_" + sample.name(), labels, sample.value());
        }
        b.append("# HELP rollback_edge_match_outcomes_total Ended matches by the cause this edge"
                + " filed with core.\n");
        b.append("# TYPE rollback_edge_match_outcomes_total counter\n");
        for (Sample sample : s.outcomes()) {
            b.append("rollback_edge_match_outcomes_total{edge=\"").append(escape(s.edgeId()))
                    .append("\",region=\"").append(escape(s.region()))
                    .append("\",cause=\"").append(escape(sample.name())).append("\"} ")
                    .append(sample.value()).append('\n');
        }
        return b.toString();
    }

    public static List<String> human(Snapshot s) {
        List<String> lines = new ArrayList<>();
        lines.add("edge " + s.edgeId() + " (" + s.region() + ") up " + (s.uptimeMs() / 1000L)
                + "s  fence " + s.versionFence());
        lines.add("live=" + s.liveMatches() + " pending=" + s.pendingMatches()
                + " unclaimed=" + s.unclaimedAssignments() + " deficit=" + s.frameDeficit()
                + " queuedResults=" + s.queuedResults()
                + " redis=" + (s.redisHealthy() ? "up" : "DOWN")
                + " directLink=" + (s.directLinkUp() ? "up" : "off"));
        lines.add("desync aborts=" + s.desyncAborts() + " of " + s.matchesEnded()
                + " ended matches (" + String.format(Locale.ROOT, "%.2f%%",
                s.desyncAbortRatio() * 100.0) + ")"
                + (s.lastDesyncFrame() >= 0
                ? "  last at frame " + s.lastDesyncFrame() + ": " + s.lastDesyncDetail() : ""));
        StringBuilder causes = new StringBuilder("outcomes:");
        for (Sample sample : s.outcomes()) {
            causes.append(' ').append(sample.name()).append('=').append(sample.value());
        }
        lines.add(causes.toString());
        for (Sample sample : s.counters()) {
            lines.add("  " + sample.name() + "=" + sample.value());
        }
        for (Sample sample : s.claims()) {
            lines.add("  " + sample.name() + "=" + sample.value());
        }
        for (Sample sample : s.latencies()) {
            lines.add("  " + sample.name() + "=" + sample.value());
        }
        return lines;
    }

    private static void gauge(StringBuilder b, String name, String labels, long value) {
        b.append("# TYPE ").append(name).append(" gauge\n");
        b.append(name).append(labels).append(' ').append(value).append('\n');
    }

    private static void counter(StringBuilder b, String name, String labels, long value) {
        b.append("# TYPE ").append(name).append(" counter\n");
        b.append(name).append(labels).append(' ').append(value).append('\n');
    }

    private static void samples(StringBuilder b, List<Sample> list) {
        b.append('{');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            Sample sample = list.get(i);
            b.append('"').append(escape(sample.name())).append("\":").append(sample.value());
        }
        b.append('}');
    }

    private static StringBuilder field(StringBuilder b, String name, long value) {
        return b.append('"').append(name).append("\":").append(value);
    }

    private static StringBuilder field(StringBuilder b, String name, String value) {
        return b.append('"').append(name).append("\":\"").append(escape(value)).append('"');
    }

    private static String escape(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c == '\n' || c == '\r') {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
