package me.nootnoot.sim.host;

import java.util.HashMap;
import java.util.Map;

public final class PaintedCells {

    public static final int NOBODY = 0;

    public static final int BLOCKS = 1;

    public static final int FLUIDS = 2;

    public static final int COBWEBS = 3;

    public static final int FIRE = 4;

    public static final int ARENA_BREAK = 5;

    public static final int CAGE = 6;

    public static final int ARENA_SWEEP = 7;

    private final Map<Long, Integer> painter = new HashMap<>();

    public void clear() {
        painter.clear();
    }

    public int paintedBy(long key) {
        Integer who = painter.get(key);
        return who == null ? NOBODY : who;
    }

    public boolean owned(long key) {
        return painter.containsKey(key);
    }

    public boolean ownedByAnyoneElse(long key, int me) {
        int who = paintedBy(key);
        return who != NOBODY && who != me;
    }

    public void paint(long key, int who) {
        if (who == NOBODY) {
            painter.remove(key);
            return;
        }
        painter.put(key, who);
    }

    public void release(long key) {
        painter.remove(key);
    }

    public int size() {
        return painter.size();
    }

    public <T> T cover(long key, T live, T recorded, T empty) {
        if (!owned(key)) {
            return live;
        }
        return recorded != null ? recorded : empty;
    }
}
