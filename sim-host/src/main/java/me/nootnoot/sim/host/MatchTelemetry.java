package me.nootnoot.sim.host;

import me.nootnoot.sim.state.PlayerState;

public interface MatchTelemetry {

    MatchTelemetry NONE = new MatchTelemetry() {
    };

    default void matchStarted(int slot, int inputBytes) {
    }

    default void renderFailed(Throwable error) {
    }

    default void teardownFailed(String step, Throwable error) {
    }

    default void tickDiagnostics(int tick, PlayerState local) {
    }
}
