package me.nootnoot.sim.host;

import me.nootnoot.sim.state.Input;

/**
 * SEAM: captures the local player's input for one tick as a deterministic {@link Input} frame.
 *
 * <p>The MC implementation reads the modern player-input packet state (movement keys), the current
 * look (yaw/pitch), and the attack/use click state from {@code MinecraftClient}. Keeping it behind an
 * interface keeps the netcode (sim-core) free of any Minecraft dependency and unit-testable.
 */
public interface InputSource {

    Input sample();
}
