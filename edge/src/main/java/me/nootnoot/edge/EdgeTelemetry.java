package me.nootnoot.edge;

import java.util.logging.Level;
import java.util.logging.Logger;
import me.nootnoot.sim.host.MatchTelemetry;
import me.nootnoot.sim.state.PlayerState;

public final class EdgeTelemetry implements MatchTelemetry {

    private final Logger logger;
    private final String who;
    private final EdgeMetrics metrics;
    private boolean loggedRenderFailure;
    private boolean loggedContainerRefusal;

    public EdgeTelemetry(Logger logger, String who) {
        this(logger, who, null);
    }

    public EdgeTelemetry(Logger logger, String who, EdgeMetrics metrics) {
        this.logger = logger;
        this.who = who;
        this.metrics = metrics;
    }

    public void count(int counter) {
        if (metrics != null) {
            metrics.hit(counter);
        }
    }

    public void catchUp(int frames) {
        if (metrics != null) {
            metrics.recordCatchUp(frames);
        }
    }

    @Override
    public void matchStarted(int slot, int inputBytes) {
        logger.info("[" + who + "] match starting: slot=" + slot + " inputBytes=" + inputBytes);
    }

    @Override
    public void renderFailed(Throwable error) {
        count(EdgeMetrics.RENDER_FAULTS);
        if (loggedRenderFailure) {
            return;
        }
        loggedRenderFailure = true;
        logger.log(Level.SEVERE, "[" + who + "] edge render threw - the sim keeps running but nothing "
                + "will be shown. This is logged once per match.", error);
    }

    @Override
    public void teardownFailed(String step, Throwable error) {
        count(EdgeMetrics.TEARDOWN_FAULTS);
        logger.log(Level.SEVERE, "[" + who + "] match teardown step '" + step + "' threw. The"
                + " remaining teardown steps still ran, but this player may be holding a resource"
                + " the match was supposed to hand back - check their ATTACK_SPEED with"
                + " /edge attackspeed " + who, error);
    }

    public void containerBlobRefused(Throwable error) {
        count(EdgeMetrics.CONTAINER_BLOBS_REFUSED);
        if (loggedContainerRefusal) {
            return;
        }
        loggedContainerRefusal = true;
        logger.log(Level.WARNING, "[" + who + "] refused a malformed out-of-band blob from the "
                + "opponent - the match continues without it. This is logged once per match.", error);
    }

    public void movementCorrectionRefused() {
        count(EdgeMetrics.MOVEMENT_CORRECTIONS_REFUSED);
        logger.warning("[" + who + "] refused a movement correction whose destination was inside"
                + " arena geometry - teleporting there is what buries a vanilla client in the"
                + " floor, and it cannot climb out on its own");
    }

    public void movementFlagged(int violations) {
        count(EdgeMetrics.MOVEMENT_CLAMPS);
        logger.warning("[" + who + "] movement validator has clamped " + violations
                + " reports - position is being corrected, review this player");
    }

    @Override
    public void tickDiagnostics(int tick, PlayerState local) {
        logger.info("[" + who + "] tick=" + tick
                + String.format(" pos=%.2f,%.2f,%.2f", local.x, local.y, local.z)
                + String.format(" hp=%.1f", local.health));
    }
}
