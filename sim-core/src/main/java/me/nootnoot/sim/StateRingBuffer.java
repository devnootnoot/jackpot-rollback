package me.nootnoot.sim;

import java.util.Arrays;
import me.nootnoot.sim.state.GameState;

public final class StateRingBuffer {
    private final GameState[] buf;
    private final int[] ticks;
    private final int capacity;

    public StateRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0");
        }
        this.capacity = capacity;
        this.buf = new GameState[capacity];
        this.ticks = new int[capacity];
        Arrays.fill(ticks, -1);
    }

    public void save(GameState state) {
        int idx = Math.floorMod(state.tick, capacity);
        buf[idx] = state.copy();
        ticks[idx] = state.tick;
    }

    public GameState peek(int tick) {
        int idx = Math.floorMod(tick, capacity);
        if (ticks[idx] != tick || buf[idx] == null) {
            return null;
        }
        return buf[idx];
    }

    public GameState load(int tick) {
        int idx = Math.floorMod(tick, capacity);
        if (ticks[idx] != tick || buf[idx] == null) {
            return null;
        }
        return buf[idx].copy();
    }

    public int capacity() {
        return capacity;
    }
}
