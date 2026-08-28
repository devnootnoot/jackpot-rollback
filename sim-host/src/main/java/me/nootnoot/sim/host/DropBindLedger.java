package me.nootnoot.sim.host;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import me.nootnoot.sim.state.Clicks;

public final class DropBindLedger<T> {

    public static final int MAX_RECORDS = 256;

    public static final int MAX_BINDS_PER_PASS = 64;

    public static final int NO_SLOT = -1;

    public static final int MAX_STARVED_GAP = MAX_RECORDS;

    public static final int MAX_MINTS_PER_TICK = Clicks.MAX;

    public static int mintable(boolean wholeStack, int clicks, int available, int alreadyMinted) {
        int room = MAX_MINTS_PER_TICK - Math.max(0, alreadyMinted);
        if (clicks <= 0 || available <= 0 || room <= 0) {
            return 0;
        }
        return wholeStack ? 1 : Math.min(Math.min(clicks, available), room);
    }

    public record Bind<T>(int seq, int uid, int slot, T stack) {
    }

    private record Record<T>(int slot, T stack) {
    }

    private final Deque<Record<T>> records = new ArrayDeque<>();

    private int boundSeq = -1;

    public void reset() {
        records.clear();
        boundSeq = -1;
    }

    public int boundSeq() {
        return boundSeq;
    }

    public int pending() {
        return records.size();
    }

    public boolean full() {
        return records.size() >= MAX_RECORDS;
    }

    public boolean record(int slot, T stack) {
        if (slot < 0 || stack == null || records.size() >= MAX_RECORDS) {
            return false;
        }
        records.add(new Record<>(slot, stack));
        return true;
    }

    public List<Bind<T>> pass(int dropSeq, int uidBase, int lastDropSlot) {
        if (boundSeq < 0 || dropSeq <= boundSeq) {
            boundSeq = dropSeq;
            return List.of();
        }
        int from = boundSeq + 1;
        int to = Math.min(dropSeq, from + MAX_BINDS_PER_PASS - 1);
        List<Bind<T>> binds = new ArrayList<>(to - from + 1);
        for (int seq = from; seq <= to; seq++) {
            if (seq == dropSeq) {
                resync(lastDropSlot);
            }
            Record<T> rec = records.poll();
            if (rec == null) {
                boundSeq = starved(dropSeq, seq) ? dropSeq : seq - 1;
                return binds;
            }
            binds.add(new Bind<>(seq, uidBase | (seq & 0x0FFFFFFF), rec.slot(), rec.stack()));
        }
        boundSeq = to;
        return binds;
    }

    private static boolean starved(int dropSeq, int unfedSeq) {
        return dropSeq - (unfedSeq - 1) > MAX_STARVED_GAP;
    }

    public boolean caughtUp(int dropSeq) {
        return boundSeq >= dropSeq;
    }

    private void resync(int lastDropSlot) {
        if (lastDropSlot < 0 || records.isEmpty() || records.peek().slot() == lastDropSlot) {
            return;
        }
        boolean present = false;
        for (Record<T> rec : records) {
            if (rec.slot() == lastDropSlot) {
                present = true;
                break;
            }
        }
        if (!present) {
            return;
        }
        while (records.peek().slot() != lastDropSlot) {
            records.poll();
        }
    }
}
