package me.nootnoot.sim;

import java.util.List;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;

public final class RefereeSession {
    public static final int MAX_FRAME_LEAD = 4096;

    public static final int CHECKSUM_HISTORY = 8192;

    private final Arena arena;
    private final GameState state;
    private int frame;
    private final InputLedger in0 = new InputLedger();
    private final InputLedger in1 = new InputLedger();
    private final long[] checksums = new long[CHECKSUM_HISTORY];

    public RefereeSession(Arena arena, GameState initial) {
        this.arena = arena;

        this.state = initial.copy();
    }

    static boolean withinLead(int confirmed, int candidate) {
        return candidate >= 0 && candidate <= (long) confirmed + MAX_FRAME_LEAD;
    }

    public void ingest(int slot, int baseFrame, List<Input> inputs) {
        if (slot != 0 && slot != 1) {
            return;
        }
        InputLedger dst = slot == 0 ? in0 : in1;
        if (!withinLead(frame, baseFrame)) {
            return;
        }
        int f = baseFrame;
        for (Input in : inputs) {
            if (f >= frame && withinLead(frame, f)) {
                dst.ensureEnd(f + 1);
                if (dst.get(f) == null && in != null) {
                    dst.set(f, in);
                }
            }
            f++;
        }
        advance();
    }

    private void advance() {
        while (frame < in0.end() && frame < in1.end()
                && in0.get(frame) != null && in1.get(frame) != null) {
            Simulation.tick(state, arena, in0.get(frame), in1.get(frame));
            frame++;
            checksums[Math.floorMod(frame - 1, CHECKSUM_HISTORY)] = Checksum.of(state);
        }
        in0.releaseBelow(frame);
        in1.releaseBelow(frame);
    }

    public int confirmedFrame() {
        return frame;
    }

    public GameState state() {
        return state;
    }

    public long checksum() {
        return Checksum.of(state);
    }

    public long checksumAt(int n) {
        if (n < 1 || n > frame) {
            throw new IndexOutOfBoundsException("frame " + n + " not yet confirmed (have " + frame + ")");
        }
        if (n <= frame - CHECKSUM_HISTORY) {
            throw new IndexOutOfBoundsException("frame " + n + " fell out of the referee's "
                    + CHECKSUM_HISTORY + " frame checksum history (have "
                    + (frame - CHECKSUM_HISTORY + 1) + " to " + frame + ")");
        }
        return checksums[Math.floorMod(n - 1, CHECKSUM_HISTORY)];
    }

    public boolean matchOver() {
        return state.roundMatchOver;
    }

    public int winnerSlot() {
        return state.roundMatchOver ? state.roundMatchWinner : -1;
    }

    public int roundWins(int slot) {
        return slot == 0 ? state.roundWinsP0 : state.roundWinsP1;
    }
}
