package me.nootnoot.edge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import me.nootnoot.sim.state.BlockStore;

public final class EdgeBlockAcks {

    public static final int NONE = -1;

    private static final long NO_CELL = Long.MIN_VALUE;

    private static final int DECIDE_DEADLINE_TICKS = 10;

    private static final int MINE_DEADLINE_TICKS = 40;

    private static final int MAX_PENDING = 64;

    private static final int MAX_DECIDED = 256;

    private record Claim(int sequence, long first, long second, int expiry) {
    }

    private final ConcurrentLinkedQueue<Claim> arriving = new ConcurrentLinkedQueue<>();
    private final ArrayDeque<Claim> waiting = new ArrayDeque<>();
    private final Set<Long> decided = new HashSet<>();
    private final List<Long> settled = new ArrayList<>();

    private int tick;
    private int acked = NONE;

    public void claimPlace(int sequence, int x, int y, int z, int faceX, int faceY, int faceZ) {
        offer(sequence, BlockStore.key(x, y, z),
                BlockStore.key(x + faceX, y + faceY, z + faceZ), DECIDE_DEADLINE_TICKS);
    }

    public void claimDigStart(int sequence, int x, int y, int z) {
        offer(sequence, BlockStore.key(x, y, z), NO_CELL, DECIDE_DEADLINE_TICKS);
    }

    public void claimDigFinish(int sequence, int x, int y, int z) {
        offer(sequence, BlockStore.key(x, y, z), NO_CELL, MINE_DEADLINE_TICKS);
    }

    public void claimBare(int sequence) {
        offer(sequence, NO_CELL, NO_CELL, 0);
    }

    private void offer(int sequence, long first, long second, int expiry) {
        if (sequence <= 0) {
            return;
        }
        arriving.add(new Claim(sequence, first, second, expiry));
    }

    public void decided(int x, int y, int z) {
        if (decided.size() >= MAX_DECIDED) {
            decided.clear();
        }
        decided.add(BlockStore.key(x, y, z));
    }

    public List<Long> settled() {
        return settled;
    }

    public int release() {
        tick++;
        settled.clear();
        Claim next;
        while ((next = arriving.poll()) != null) {
            waiting.add(new Claim(next.sequence(), next.first(), next.second(),
                    tick + next.expiry()));
        }
        int sequence = NONE;
        while (!waiting.isEmpty()) {
            Claim head = waiting.peek();
            boolean settledHead = head.first() == NO_CELL
                    || decided.remove(head.first())
                    || (head.second() != NO_CELL && decided.remove(head.second()));
            if (!settledHead && waiting.size() <= MAX_PENDING && tick < head.expiry()) {
                break;
            }
            waiting.poll();
            if (head.first() != NO_CELL) {
                settled.add(head.first());
            }
            if (head.second() != NO_CELL) {
                settled.add(head.second());
            }
            sequence = Math.max(sequence, head.sequence());
        }
        if (waiting.isEmpty()) {
            decided.clear();
        }
        if (sequence <= acked) {
            return NONE;
        }
        acked = sequence;
        return sequence;
    }

    public void clear() {
        arriving.clear();
        waiting.clear();
        decided.clear();
        settled.clear();
        acked = NONE;
    }
}
