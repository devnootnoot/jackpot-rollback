package me.nootnoot.edge;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.logging.Logger;

public final class EdgeDesyncAlert {

    public static final long WINDOW_MILLIS = 15L * 60L * 1000L;

    public static final int MIN_ENDED = 5;

    public static final int MIN_DESYNCS = 2;

    public static final double RATIO_THRESHOLD = 0.02;

    private static final long REPEAT_MILLIS = 60L * 1000L;

    private final Logger logger;
    private final String edgeId;

    private final Deque<long[]> window = new ArrayDeque<>();

    private volatile boolean firing;
    private long lastShoutMs;

    public EdgeDesyncAlert(Logger logger, String edgeId) {
        this.logger = logger;
        this.edgeId = edgeId == null ? "" : edgeId;
    }

    public synchronized void matchEnded(String cause, long nowMs) {
        window.addLast(new long[]{nowMs, EdgeOutcome.DESYNC.equals(cause) ? 1L : 0L});
        prune(nowMs);
    }

    public boolean firing() {
        return firing;
    }

    public boolean evaluate(EdgeMetrics.Snapshot snapshot, long nowMs) {
        int ended;
        int desyncs;
        boolean alerting;
        boolean announce;
        boolean clear;
        synchronized (this) {
            prune(nowMs);
            ended = window.size();
            desyncs = 0;
            for (long[] entry : window) {
                desyncs += (int) entry[1];
            }
            alerting = ended >= MIN_ENDED && desyncs >= MIN_DESYNCS
                    && (double) desyncs / (double) ended >= RATIO_THRESHOLD;
            announce = alerting && (!firing || nowMs - lastShoutMs >= REPEAT_MILLIS);
            clear = !alerting && firing;
            if (announce) {
                lastShoutMs = nowMs;
            }
            firing = alerting;
        }
        if (announce) {
            shout(snapshot, desyncs, ended);
        } else if (clear) {
            logger.warning("[metrics] DESYNC ALERT CLEARED on edge " + edgeId
                    + " - no desync abort has been filed inside the last "
                    + (WINDOW_MILLIS / 60000L) + " minutes above the threshold");
        }
        return alerting;
    }

    private void prune(long nowMs) {
        while (!window.isEmpty() && nowMs - window.peekFirst()[0] > WINDOW_MILLIS) {
            window.removeFirst();
        }
    }

    private void shout(EdgeMetrics.Snapshot snapshot, int desyncs, int ended) {
        String ratio = String.format(Locale.ROOT, "%.1f%%", 100.0 * desyncs / ended);
        logger.severe("################ DESYNC ABORT ALERT ################");
        logger.severe("edge " + edgeId + " (" + snapshot.region() + ") aborted " + desyncs
                + " of the last " + ended + " matches on a CHECKSUM MISMATCH (" + ratio + " over "
                + (WINDOW_MILLIS / 60000L) + " minutes, threshold "
                + String.format(Locale.ROOT, "%.0f%%", RATIO_THRESHOLD * 100.0) + ").");
        logger.severe("This means two peers ran DIFFERENT SIMULATIONS. Almost always a partial"
                + " deploy: one side is on an older jar.");
        logger.severe("VERSION FENCE ON THIS EDGE: " + snapshot.versionFence());
        logger.severe("Compare that triple against every other edge and the mod's"
                + " RollbackModRegistry.EXPECTED_VERSION. If they differ, redeploy the odd one out."
                + " If they match, the two sides are the same protocol but not the same sim - stop"
                + " the fleet with rollback.edge.enabled=false in core and re-run the determinism"
                + " gate. Last desync was at frame " + snapshot.lastDesyncFrame() + ": "
                + snapshot.lastDesyncDetail());
        logger.severe("Runbook: jackpot-rollback/RUNBOOK.md, section 'A desync storm'.");
        logger.severe("###################################################");
    }
}
