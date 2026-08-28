package me.nootnoot.sim.harness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import me.nootnoot.sim.RollbackController;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;

public final class RollbackAudit {
    public static final int RING_CAPACITY = 1024;

    public static final int PREDICTION_BOUND = 600;

    public static final long PLAN_SEED = 0x524F4C4C4241434BL;

    public static final String[] REQUIREMENTS = {
            "rollback-depth-one-frame",
            "rollback-depth-under-decay",
            "rollback-depth-past-decay",
            "rollback-depth-at-prediction-bound",
            "rollback-batched-arrival",
            "rollback-spans-round-reset",
            "rollback-spans-death",
            "rollback-spans-container-change",
            "rollback-spans-live-projectile",
    };

    private static final int DEPTH_ONE_FRAME = 0;
    private static final int DEPTH_UNDER_DECAY = 1;
    private static final int DEPTH_PAST_DECAY = 2;
    private static final int DEPTH_AT_BOUND = 3;
    private static final int BATCHED_ARRIVAL = 4;
    private static final int SPANS_ROUND_RESET = 5;
    private static final int SPANS_DEATH = 6;
    private static final int SPANS_CONTAINER_CHANGE = 7;
    private static final int SPANS_LIVE_PROJECTILE = 8;

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static final int[] BURST_LADDER = {
            2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, PREDICTION_BOUND,
    };

    private static final int BURST_ONE_IN = 64;

    private static final int JITTER_SPREAD = 8;

    private static final int FORCED_HOLE_LEAD = 4;

    private static final int FORCED_HOLE_TRAIL = 4;

    public record Stats(int frames, int rollbacks, int resimulatedFrames, int deepestRollback,
                        int batchedRollbacks, int deliveries, List<String> uncovered) {
    }

    public record Result(boolean agreed, int divergedAtFrame, long digest, String disagreement,
                         Stats stats) {
    }

    private RollbackAudit() {
    }

    public static Stats notRun() {
        return new Stats(0, 0, 0, 0, 0, 0, List.of(REQUIREMENTS));
    }

    public static final class Landmarks {
        private static final int PER_KIND = 6;
        private static final int MIN_GAP = 64;

        private final List<Integer> roundReset = new ArrayList<>();
        private final List<Integer> death = new ArrayList<>();
        private final List<Integer> containerChange = new ArrayList<>();
        private final List<Integer> liveProjectile = new ArrayList<>();

        private int prevRoundWins = -1;
        private long prevContainerFold = Long.MIN_VALUE;

        public void observe(GameState g) {
            int wins = g.roundWinsP0 + g.roundWinsP1;
            if (prevRoundWins >= 0 && wins > prevRoundWins) {
                offer(roundReset, g.tick);
            }
            prevRoundWins = wins;

            for (CombatEvent e : g.events) {
                if (e.type() == CombatEvent.DEATH) {
                    offer(death, g.tick);
                    break;
                }
            }

            long fold = containerFold(g);
            if (prevContainerFold != Long.MIN_VALUE && fold != prevContainerFold) {
                offer(containerChange, g.tick);
            }
            prevContainerFold = fold;

            if (!g.projectiles.isEmpty()) {
                offer(liveProjectile, g.tick);
            }
        }

        public List<Integer> roundReset() {
            return List.copyOf(roundReset);
        }

        public List<Integer> death() {
            return List.copyOf(death);
        }

        public List<Integer> containerChange() {
            return List.copyOf(containerChange);
        }

        public List<Integer> liveProjectile() {
            return List.copyOf(liveProjectile);
        }

        private int[] merged() {
            int n = roundReset.size() + death.size() + containerChange.size() + liveProjectile.size();
            int[] out = new int[n];
            int i = 0;
            for (int t : roundReset) {
                out[i++] = t;
            }
            for (int t : death) {
                out[i++] = t;
            }
            for (int t : containerChange) {
                out[i++] = t;
            }
            for (int t : liveProjectile) {
                out[i++] = t;
            }
            Arrays.sort(out);
            return out;
        }

        private static void offer(List<Integer> into, int tick) {
            if (into.size() >= PER_KIND) {
                return;
            }
            if (!into.isEmpty() && tick - into.get(into.size() - 1) < MIN_GAP) {
                return;
            }
            into.add(tick);
        }

        private static long containerFold(GameState g) {
            int[] ids = new int[g.containers.size()];
            int n = 0;
            for (int id : g.containers.keySet()) {
                ids[n++] = id;
            }
            Arrays.sort(ids);
            long h = FNV_OFFSET;
            for (int id : ids) {
                h = mix(h, id);
                Container c = g.containers.get(id);
                for (int i = 0; i < Container.CELLS; i++) {
                    h = mix(h, c.entry[i]);
                    h = mix(h, c.count[i]);
                    h = mix(h, c.damage[i]);
                }
            }
            return h;
        }
    }

    private record Delivery(int arrival, int start, int length) {
    }

    public static Result run(InputLog log, GameState initial, Arena arena, List<Long> forward,
                             Landmarks marks) {
        int n = log.frames.size();
        List<Delivery> plan = plan(n, marks);
        boolean[] seen = new boolean[REQUIREMENTS.length];
        List<Long> trace = new ArrayList<>();

        RollbackController ctrl = new RollbackController(arena, 0, initial, RING_CAPACITY);

        int rollbacks = 0;
        int resimulated = 0;
        int deepest = 0;
        int batched = 0;
        int verified = 0;
        int cursor = 0;

        for (int t = 0; t < n; t++) {
            ctrl.advance(log.frames.get(t)[0]);

            while (cursor < plan.size() && plan.get(cursor).arrival() <= t) {
                Delivery d = plan.get(cursor++);
                int headBefore = ctrl.head();
                int before = ctrl.rollbackCount();

                if (d.length() > 1) {
                    ctrl.beginInputBatch();
                    for (int i = 0; i < d.length(); i++) {
                        ctrl.onRemoteInput(d.start() + i, log.frames.get(d.start() + i)[1]);
                    }
                    ctrl.endInputBatch();
                } else {
                    ctrl.onRemoteInput(d.start(), log.frames.get(d.start())[1]);
                }

                if (ctrl.rollbackCount() == before) {
                    continue;
                }

                int from = ctrl.lastRollbackFrame();
                int depth = headBefore - from;
                rollbacks++;
                resimulated += depth;
                deepest = Math.max(deepest, depth);
                if (d.length() > 1) {
                    batched++;
                    seen[BATCHED_ARRIVAL] = true;
                }
                classifyDepth(seen, depth);
                classifySpan(seen, marks, from, headBefore);

                trace.add((long) from);
                trace.add((long) headBefore);
                trace.add(ctrl.checksumAt(from));
                trace.add(ctrl.checksumAt(headBefore));
            }

            int confirmed = ctrl.confirmedFrame();
            while (verified < confirmed) {
                verified++;
                long got = ctrl.checksumAt(verified);
                long want = forward.get(verified - 1);
                if (got != want) {
                    return diverged(verified, got, want, trace, rollbacks, resimulated, deepest,
                            batched, plan.size(), n, seen);
                }
            }
        }

        while (cursor < plan.size()) {
            Delivery d = plan.get(cursor++);
            ctrl.beginInputBatch();
            for (int i = 0; i < d.length(); i++) {
                ctrl.onRemoteInput(d.start() + i, log.frames.get(d.start() + i)[1]);
            }
            ctrl.endInputBatch();
        }

        int confirmed = ctrl.confirmedFrame();
        while (verified < confirmed) {
            verified++;
            long got = ctrl.checksumAt(verified);
            long want = forward.get(verified - 1);
            if (got != want) {
                return diverged(verified, got, want, trace, rollbacks, resimulated, deepest,
                        batched, plan.size(), n, seen);
            }
        }

        if (verified != n) {
            Stats stats = stats(n, rollbacks, resimulated, deepest, batched, plan.size(), seen);
            return new Result(false, verified, 0L,
                    "the rollback pass only confirmed " + verified + " of " + n
                            + " frames, so most of the run was never checked against the forward run",
                    stats);
        }

        long finalChecksum = ctrl.checksumAt(n);
        trace.add((long) rollbacks);
        trace.add((long) resimulated);
        trace.add((long) deepest);
        trace.add(finalChecksum);

        Stats stats = stats(n, rollbacks, resimulated, deepest, batched, plan.size(), seen);
        return new Result(true, -1, HarnessDigest.fold(trace), null, stats);
    }

    private static Result diverged(int frame, long got, long want, List<Long> trace, int rollbacks,
                                   int resimulated, int deepest, int batched, int deliveries,
                                   int frames, boolean[] seen) {
        Stats stats = stats(frames, rollbacks, resimulated, deepest, batched, deliveries, seen);
        return new Result(false, frame, HarnessDigest.fold(trace),
                "the rolled-back and resimulated state at frame " + frame + " is "
                        + Long.toHexString(got) + " but the forward run reached "
                        + Long.toHexString(want), stats);
    }

    private static Stats stats(int frames, int rollbacks, int resimulated, int deepest, int batched,
                               int deliveries, boolean[] seen) {
        List<String> uncovered = new ArrayList<>();
        for (int i = 0; i < seen.length; i++) {
            if (!seen[i]) {
                uncovered.add(REQUIREMENTS[i]);
            }
        }
        return new Stats(frames, rollbacks, resimulated, deepest, batched, deliveries, uncovered);
    }

    private static void classifyDepth(boolean[] seen, int depth) {
        if (depth == 1) {
            seen[DEPTH_ONE_FRAME] = true;
        }
        if (depth > 1 && depth < RollbackController.PREDICTION_DECAY_FRAMES) {
            seen[DEPTH_UNDER_DECAY] = true;
        }
        if (depth >= RollbackController.PREDICTION_DECAY_FRAMES) {
            seen[DEPTH_PAST_DECAY] = true;
        }
        if (depth >= PREDICTION_BOUND) {
            seen[DEPTH_AT_BOUND] = true;
        }
    }

    private static void classifySpan(boolean[] seen, Landmarks marks, int from, int to) {
        if (spans(marks.roundReset, from, to)) {
            seen[SPANS_ROUND_RESET] = true;
        }
        if (spans(marks.death, from, to)) {
            seen[SPANS_DEATH] = true;
        }
        if (spans(marks.containerChange, from, to)) {
            seen[SPANS_CONTAINER_CHANGE] = true;
        }
        if (spans(marks.liveProjectile, from, to)) {
            seen[SPANS_LIVE_PROJECTILE] = true;
        }
    }

    private static boolean spans(List<Integer> ticks, int from, int to) {
        for (int tick : ticks) {
            if (tick > from && tick <= to) {
                return true;
            }
        }
        return false;
    }

    static List<Delivery> plan(int n, Landmarks marks) {
        int[][] forced = forcedHoles(n, marks);
        List<Delivery> out = new ArrayList<>();
        long r = PLAN_SEED;
        int burst = 0;
        int hole = 0;
        int f = 0;

        while (f < n) {
            while (hole < forced.length && forced[hole][1] < f) {
                hole++;
            }
            if (hole < forced.length && f >= forced[hole][0]) {
                int end = Math.min(forced[hole][1], n - 1);
                out.add(new Delivery(Math.min(n - 1, forced[hole][2]), f, end - f + 1));
                f = end + 1;
                hole++;
                continue;
            }

            int limit = hole < forced.length ? forced[hole][0] - f : n - f;
            r = xorshift(r);

            if ((r & (BURST_ONE_IN - 1)) == 0L) {
                int len = Math.min(Math.min(BURST_LADDER[burst % BURST_LADDER.length], limit), n - f);
                burst++;
                if (len > 1) {
                    int slack = (int) ((r >>> 8) % 4);
                    out.add(new Delivery(Math.min(n - 1, f + len - 1 + slack), f, len));
                    f += len;
                    continue;
                }
            }

            int delay = (int) ((r >>> 16) % JITTER_SPREAD);
            out.add(new Delivery(Math.min(n - 1, f + delay), f, 1));
            f++;
        }

        out.sort(Comparator.comparingInt(Delivery::arrival).thenComparingInt(Delivery::start));
        return out;
    }

    private static int[][] forcedHoles(int n, Landmarks marks) {
        int[] ticks = marks.merged();
        List<int[]> holes = new ArrayList<>();
        for (int tick : ticks) {
            int start = Math.max(0, tick - FORCED_HOLE_LEAD);
            int end = Math.min(n - 1, tick + FORCED_HOLE_TRAIL);
            if (start > end) {
                continue;
            }
            if (!holes.isEmpty() && start <= holes.get(holes.size() - 1)[1] + 1) {
                int[] last = holes.get(holes.size() - 1);
                last[1] = Math.max(last[1], end);
                last[2] = last[1];
                continue;
            }
            holes.add(new int[]{start, end, end});
        }
        return holes.toArray(new int[0][]);
    }

    private static long xorshift(long s) {
        s ^= s << 13;
        s ^= s >>> 7;
        s ^= s << 17;
        return s;
    }

    private static long mix(long h, int v) {
        long x = v & 0xFFFFFFFFL;
        for (int i = 0; i < 4; i++) {
            h ^= (x & 0xFF);
            h *= FNV_PRIME;
            x >>>= 8;
        }
        return h;
    }
}
