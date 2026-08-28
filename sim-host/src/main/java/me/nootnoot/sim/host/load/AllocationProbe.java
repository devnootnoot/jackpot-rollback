package me.nootnoot.sim.host.load;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.StateRingBuffer;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;

public final class AllocationProbe {

    public record Cost(String name, long iterations, double bytesEach, double nanosEach) {
    }

    private static final int ENCODE_REPS = 200_000;

    private static final int LADDER_WARMUP_ROUNDS = 2;

    private AllocationProbe() {
    }

    public static List<Cost> measure(Arena arena, InputLog log, int ticks, int ringCapacity,
                                     int rounds) {
        List<Cost> out = new ArrayList<>();
        Cost[] ladder = interleavedLadder(arena, log, ticks, ringCapacity, rounds);
        out.add(ladder[0]);
        out.add(ladder[1]);
        out.add(ladder[2]);
        out.add(delta("StateRingBuffer.save (GameState.copy)", ladder[0], ladder[1]));
        out.add(delta("Checksum.of", ladder[1], ladder[2]));
        out.add(alloc("new GameState()", GameState::new));
        out.add(alloc("new PlayerState()", PlayerState::new));
        PlayerState seed = HarnessScenarios.combat(arena).players[0];
        out.add(alloc("PlayerState.copy()", seed::copy));
        out.add(alloc("ItemDict.empty()", ItemDict::empty));
        out.add(alloc("BlockProps.empty()", BlockProps::empty));
        Input in = log.frames.get(0)[0];
        out.add(encode("Protocol.encode(InputFrames x8)", new Message.InputFrames(0, repeat(in, 8), 0, 0)));
        out.add(encode("Protocol.encode(InputFrames x1)", new Message.InputFrames(0, repeat(in, 1), 0, 0)));
        out.add(encode("Protocol.encode(InputFrames x0)", new Message.InputFrames(0, List.of(), 0, 0)));
        out.add(encode("Protocol.encode(Checksum)", new Message.Checksum(1, 0x1234L)));
        return out;
    }

    private static final String[] LADDER_NAMES = {
            "Simulation.tick only",
            "Simulation.tick + ring save",
            "Simulation.tick + ring save + Checksum.of",
    };

    private static Cost[] interleavedLadder(Arena arena, InputLog log, int ticks,
                                            int ringCapacity, int rounds) {
        long[] bytes = new long[3];
        long[] nanos = new long[3];
        long[] iterations = new long[3];
        for (int stage = 0; stage < 3; stage++) {
            for (int i = 0; i < LADDER_WARMUP_ROUNDS; i++) {
                oneLadderRound(arena, log, ticks, ringCapacity, stage);
            }
        }
        ThreadMXBean b = bean();
        for (int round = 0; round < rounds; round++) {
            for (int stage = 0; stage < 3; stage++) {
                GameState g = HarnessScenarios.combat(arena);
                StateRingBuffer ring = new StateRingBuffer(ringCapacity);
                long a0 = b.getCurrentThreadAllocatedBytes();
                long t0 = System.nanoTime();
                runLadder(g, ring, arena, log, ticks, stage);
                nanos[stage] += System.nanoTime() - t0;
                bytes[stage] += b.getCurrentThreadAllocatedBytes() - a0;
                iterations[stage] += ticks;
                blackhole(ring);
            }
        }
        Cost[] out = new Cost[3];
        for (int stage = 0; stage < 3; stage++) {
            out[stage] = new Cost(LADDER_NAMES[stage], iterations[stage],
                    (double) bytes[stage] / iterations[stage],
                    (double) nanos[stage] / iterations[stage]);
        }
        return out;
    }

    private static final int ALLOC_REPS = 400_000;

    private static Cost alloc(String name, Supplier<Object> factory) {
        Object sink = null;
        for (int i = 0; i < 50_000; i++) {
            sink = factory.get();
        }
        ThreadMXBean b = bean();
        long a0 = b.getCurrentThreadAllocatedBytes();
        long t0 = System.nanoTime();
        for (int i = 0; i < ALLOC_REPS; i++) {
            sink = factory.get();
        }
        long ns = System.nanoTime() - t0;
        long bytes = b.getCurrentThreadAllocatedBytes() - a0;
        blackhole(sink);
        return new Cost(name, ALLOC_REPS, (double) bytes / ALLOC_REPS, (double) ns / ALLOC_REPS);
    }

    private static Cost delta(String name, Cost lower, Cost upper) {
        return new Cost(name, upper.iterations(), upper.bytesEach() - lower.bytesEach(),
                upper.nanosEach() - lower.nanosEach());
    }

    private static List<Input> repeat(Input in, int count) {
        List<Input> run = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            run.add(in);
        }
        return run;
    }

    private static void oneLadderRound(Arena arena, InputLog log, int ticks, int ringCapacity,
                                       int stage) {
        GameState g = HarnessScenarios.combat(arena);
        StateRingBuffer ring = new StateRingBuffer(ringCapacity);
        runLadder(g, ring, arena, log, ticks, stage);
        blackhole(ring);
    }

    private static void runLadder(GameState g, StateRingBuffer ring, Arena arena, InputLog log,
                                  int ticks, int stage) {
        long sum = 0;
        int n = log.frames.size();
        for (int t = 0; t < ticks; t++) {
            Input[] f = log.frames.get(t % n);
            if (stage >= 1) {
                ring.save(g);
            }
            Simulation.tick(g, arena, f[0], f[1]);
            if (stage >= 2) {
                sum ^= Checksum.of(g);
            }
        }
        blackhole(sum);
    }

    private static ThreadMXBean bean() {
        return (ThreadMXBean) ManagementFactory.getThreadMXBean();
    }

    private static Cost encode(String name, Message m) {
        byte[] sink = null;
        for (int i = 0; i < 20_000; i++) {
            sink = Protocol.encode(m);
        }
        ThreadMXBean b = bean();
        long a0 = b.getCurrentThreadAllocatedBytes();
        long t0 = System.nanoTime();
        for (int i = 0; i < ENCODE_REPS; i++) {
            sink = Protocol.encode(m);
        }
        long ns = System.nanoTime() - t0;
        long bytes = b.getCurrentThreadAllocatedBytes() - a0;
        blackhole(sink);
        return new Cost(name, ENCODE_REPS, (double) bytes / ENCODE_REPS, (double) ns / ENCODE_REPS);
    }

    private static volatile Object sinkField;

    private static volatile long longSinkField;

    private static void blackhole(Object o) {
        sinkField = o;
    }

    private static void blackhole(long v) {
        longSinkField = v;
    }
}
