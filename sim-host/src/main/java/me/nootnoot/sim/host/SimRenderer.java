package me.nootnoot.sim.host;

import java.util.List;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;

/**
 * SEAM: presents the local authoritative sim state to the player each tick.
 *
 * <p>The MC implementation drives the client-side entity representations (both players + projectiles)
 * to the sim's positions/rotations and suppresses the stub server's authority, so what the player sees
 * IS the rolled-back sim. Behind an interface so the netcode stays Minecraft-free.
 */
public interface SimRenderer {

    /**
     * @param head      predicted head state — render the LOCAL player from this (responsive, 0-ms)
     * @param confirmed authoritative confirmed state — render the REMOTE player from this (smooth, no
     *                  rollback overshoot)
     */
    void render(GameState head, GameState confirmed);

    /** Play combat feedback for newly-confirmed events (hit/crit/death). */
    void playEvents(List<CombatEvent> events, GameState state);

    /** Play the LOCAL player's own hit feedback immediately from the predicted head (its hit is decided
     *  client-side and won't be rolled back), so it isn't delayed ~a ping like the confirmed pass. The impl
     *  dedupes by head frame. Default no-op for non-rendering impls. */
    default void playLocalHits(List<CombatEvent> headEvents, GameState head, int headFrame) {
    }

    /** Feed the local avatar's accumulated rollback position correction (predicted − corrected) for this tick.
     *  The MC impl eases the camera across it so a re-simulated knockback (a hit landed at range) shoves smoothly
     *  instead of snapping on the victim's own screen. Default no-op for non-rendering impls. */
    default void beginCatchUp(int frames) {
    }

    default void feedPeerCorrection(double dx, double dy, double dz) {
    }

    default void feedLocalCorrection(double dx, double dy, double dz) {
    }

    /** Tear down any visual state when the match ends. */
    void clear();
}
