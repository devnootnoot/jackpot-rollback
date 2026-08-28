package me.nootnoot.sim;

import java.util.ArrayList;
import me.nootnoot.sim.state.Input;

public final class InputLedger {
    public static final int MAX_RETAINED = 8192;

    private final ArrayList<Input> items = new ArrayList<>();

    private int base;

    public int base() {
        return base;
    }

    public int end() {
        return base + items.size();
    }

    public int retained() {
        return items.size();
    }

    public Input get(int frame) {
        int i = frame - base;
        return i < 0 || i >= items.size() ? null : items.get(i);
    }

    public void add(Input value) {
        items.add(value);
    }

    public void set(int frame, Input value) {
        int i = frame - base;
        if (i >= 0 && i < items.size()) {
            items.set(i, value);
        }
    }

    public void ensureEnd(int frame) {
        int want = Math.min(frame - base, MAX_RETAINED);
        while (items.size() < want) {
            items.add(null);
        }
    }

    public void releaseBelow(int floor) {
        int drop = Math.min(floor - base, items.size());
        if (drop <= 0) {
            return;
        }
        items.subList(0, drop).clear();
        base += drop;
    }
}
