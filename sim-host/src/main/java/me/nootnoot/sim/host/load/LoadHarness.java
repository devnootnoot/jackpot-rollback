package me.nootnoot.sim.host.load;

import com.sun.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.host.MatchDriver;
import me.nootnoot.sim.net.NetSession;
import me.nootnoot.sim.state.Arena;

public final class LoadHarness {

    public record MatchReport(int index, int headSlot0, int headSlot1, int rollbacksSlot0,
                              int rollbacksSlot1, long resimSlot0, long resimSlot1,
                              long catchUpBursts, long catchUpFrames, int deepestBurst,
                              long packetsSent, long bytesSent, boolean alive, String deadReason,
                              NetSession.Retention retention) {
    }

    public record Retained(int tick, int total, int localChecksums, int pendingRemoteChecksums,
                           int rawInputs, int localInputs, int controllerLocalInputs,
                           int controllerRemoteActual, int controllerRemoteUsed, int chatQueue,
                           int containerQueue, long heapUsedBytes) {
    }

    public record Result(LoadConfig config, int measuredTicks, int drivers,
                         TickSamples edgeTick, TickSamples edgeTickExGc, TickSamples driverTick,
                         long allocBytes, long gcCount, long gcMillis, long gcMillisInWindow,
                         long framesSimulated, long framesAdvanced, long framesResimulated,
                         long rollbacks, long catchUpBursts, long catchUpFrames, int deepestBurst,
                         long packetsSent, long bytesSent, int liveDrivers,
                         List<MatchReport> matches, List<Retained> retention,
                         long peakRetainedTotal, long finalRetainedTotal,
                         long stalledDriverTicks, long pacedOverruns) {
    }

    private final LoadConfig config;
    private final List<LoadMatch> matches = new ArrayList<>();
    private final Arena arena;
    private final InputLog log;

    private static final String CHAT_LINE =
            "gg wp that was a close one, rematch when you are ready friend";

    private int clock;
    private long stalledDriverTicks;
    private long pacedOverruns;

    private final int[] baseHead;
    private final int[] baseRollbacks;
    private final long[] baseResim;
    private final long[] baseBursts;
    private final long[] baseBurstFrames;
    private final long[] basePackets;
    private final long[] baseBytes;

    public LoadHarness(LoadConfig config) {
        this.config = config;
        this.arena = HarnessScenarios.arena();
        this.log = InputLog.scripted(config.seed(), InputLog.SCRIPT_END);
        for (int i = 0; i < config.matches(); i++) {
            matches.add(new LoadMatch(i, log, arena, config, config.seed() * 0x9E3779B97F4A7C15L + i));
        }
        int n = config.drivers();
        this.baseHead = new int[n];
        this.baseRollbacks = new int[n];
        this.baseResim = new long[n];
        this.baseBursts = new long[n];
        this.baseBurstFrames = new long[n];
        this.basePackets = new long[n];
        this.baseBytes = new long[n];
    }

    private void snapshotBaseline() {
        for (int i = 0; i < matches.size(); i++) {
            LoadMatch m = matches.get(i);
            for (int slot = 0; slot < 2; slot++) {
                int k = i * 2 + slot;
                baseHead[k] = m.driver(slot).head();
                baseRollbacks[k] = m.driver(slot).rollbackCount();
                baseResim[k] = m.driver(slot).resimulatedFrames();
                m.renderer(slot).resetDeepest();
                baseBursts[k] = m.renderer(slot).catchUpBursts();
                baseBurstFrames[k] = m.renderer(slot).catchUpFrames();
                basePackets[k] = m.transport(slot).packetsSent();
                baseBytes[k] = m.transport(slot).bytesSent();
            }
        }
    }

    private boolean stalled(int matchIndex, int slot) {
        if (slot != 0 || config.stallEveryTicks() <= 0 || config.stallTicks() <= 0) {
            return false;
        }
        int skew = config.stallSynchronised() ? 0 : matchIndex * 13;
        int phase = Math.floorMod(clock + skew, config.stallEveryTicks());
        return phase < config.stallTicks();
    }

    public Result run() {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        for (int t = 0; t < config.warmupTicks(); t++) {
            onePass(null);
        }
        snapshotBaseline();
        stalledDriverTicks = 0;
        pacedOverruns = 0;

        TickSamples edgeTick = new TickSamples(config.ticks());
        TickSamples edgeTickExGc = new TickSamples(config.ticks());
        TickSamples driverTick = new TickSamples(config.ticks() * config.drivers());
        List<Retained> retention = new ArrayList<>();

        long gcCount0 = gcCount(gcBeans);
        long gcMillis0 = gcMillis(gcBeans);
        long alloc0 = threads.getCurrentThreadAllocatedBytes();
        long gcInWindow = 0;
        long peakRetained = 0;

        int measured = 0;
        long budgetNanos = config.budgetMicros() * 1000L;
        long deadline = System.nanoTime();
        for (int t = 0; t < config.ticks(); t++) {
            long gcBefore = gcMillis(gcBeans);
            long t0 = System.nanoTime();
            onePass(driverTick);
            long elapsed = System.nanoTime() - t0;
            if (config.paced()) {
                deadline += budgetNanos;
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    pacedOverruns++;
                    deadline = System.nanoTime();
                } else {
                    if (config.paceMode() == LoadConfig.PACE_PARK) {
                        LockSupport.parkNanos(remaining);
                    }
                    while (System.nanoTime() < deadline) {
                        Thread.onSpinWait();
                    }
                }
            }
            long gcDelta = gcMillis(gcBeans) - gcBefore;
            edgeTick.add(elapsed);
            edgeTickExGc.add(Math.max(0L, elapsed - gcDelta * 1_000_000L));
            gcInWindow += gcDelta;
            measured++;
            if (config.retentionSampleInterval() > 0
                    && t % config.retentionSampleInterval() == 0) {
                if (config.gcBeforeRetentionSample()) {
                    System.gc();
                }
                Retained r = sampleRetention(t);
                retention.add(r);
                peakRetained = Math.max(peakRetained, r.total());
            }
            if (!anyAlive()) {
                break;
            }
        }

        long allocBytes = threads.getCurrentThreadAllocatedBytes() - alloc0;
        long gcCountDelta = gcCount(gcBeans) - gcCount0;
        long gcMillisDelta = gcMillis(gcBeans) - gcMillis0;

        return summarise(measured, edgeTick, edgeTickExGc, driverTick, allocBytes, gcCountDelta,
                gcMillisDelta, gcInWindow, retention, peakRetained);
    }

    private void onePass(TickSamples driverTick) {
        for (int i = 0; i < matches.size(); i++) {
            LoadMatch m = matches.get(i);
            m.stepNetwork();
            for (int c = 0; c < config.chatPerTick(); c++) {
                m.driver(1).sendChat(CHAT_LINE);
            }
            for (int slot = 0; slot < 2; slot++) {
                if (!m.alive(slot)) {
                    continue;
                }
                if (stalled(i, slot)) {
                    stalledDriverTicks++;
                    continue;
                }
                if (driverTick == null) {
                    m.tick(slot);
                } else {
                    long t0 = System.nanoTime();
                    m.tick(slot);
                    driverTick.add(System.nanoTime() - t0);
                }
            }
        }
        clock++;
    }

    private boolean anyAlive() {
        for (LoadMatch m : matches) {
            if (m.anyAlive()) {
                return true;
            }
        }
        return false;
    }

    private Retained sampleRetention(int tick) {
        int total = 0;
        int localChecksums = 0;
        int pending = 0;
        int raw = 0;
        int local = 0;
        int cLocal = 0;
        int cActual = 0;
        int cUsed = 0;
        int chat = 0;
        int container = 0;
        for (LoadMatch m : matches) {
            for (int slot = 0; slot < 2; slot++) {
                NetSession.Retention r = m.driver(slot).retention();
                total += r.total();
                localChecksums += r.localChecksums();
                pending += r.pendingRemoteChecksums();
                raw += r.rawInputs();
                local += r.localInputs();
                cLocal += r.controllerLocalInputs();
                cActual += r.controllerRemoteActual();
                cUsed += r.controllerRemoteUsed();
                chat += r.chatQueue();
                container += r.containerQueue();
            }
        }
        Runtime rt = Runtime.getRuntime();
        long heap = rt.totalMemory() - rt.freeMemory();
        return new Retained(tick, total, localChecksums, pending, raw, local, cLocal, cActual,
                cUsed, chat, container, heap);
    }

    private Result summarise(int measured, TickSamples edgeTick, TickSamples edgeTickExGc,
                             TickSamples driverTick,
                             long allocBytes, long gcCount, long gcMillis, long gcInWindow,
                             List<Retained> retention, long peakRetained) {
        List<MatchReport> reports = new ArrayList<>(matches.size());
        long framesAdvanced = 0;
        long framesResim = 0;
        long rollbacks = 0;
        long bursts = 0;
        long burstFrames = 0;
        int deepest = 0;
        long packets = 0;
        long bytes = 0;
        int live = 0;
        long finalRetained = 0;
        for (int i = 0; i < matches.size(); i++) {
            LoadMatch m = matches.get(i);
            int k0 = i * 2;
            int k1 = k0 + 1;
            MatchDriver d0 = m.driver(0);
            MatchDriver d1 = m.driver(1);
            long b = m.renderer(0).catchUpBursts() - baseBursts[k0]
                    + m.renderer(1).catchUpBursts() - baseBursts[k1];
            long bf = m.renderer(0).catchUpFrames() - baseBurstFrames[k0]
                    + m.renderer(1).catchUpFrames() - baseBurstFrames[k1];
            int deep = Math.max(m.renderer(0).deepestBurst(), m.renderer(1).deepestBurst());
            long sent = m.transport(0).packetsSent() - basePackets[k0]
                    + m.transport(1).packetsSent() - basePackets[k1];
            long sentBytes = m.transport(0).bytesSent() - baseBytes[k0]
                    + m.transport(1).bytesSent() - baseBytes[k1];
            framesAdvanced += (d0.head() - baseHead[k0]) + (d1.head() - baseHead[k1]);
            framesResim += (d0.resimulatedFrames() - baseResim[k0])
                    + (d1.resimulatedFrames() - baseResim[k1]);
            rollbacks += (d0.rollbackCount() - baseRollbacks[k0])
                    + (d1.rollbackCount() - baseRollbacks[k1]);
            bursts += b;
            burstFrames += bf;
            deepest = Math.max(deepest, deep);
            packets += sent;
            bytes += sentBytes;
            live += (m.alive(0) ? 1 : 0) + (m.alive(1) ? 1 : 0);
            finalRetained += d0.retention().total() + d1.retention().total();
            reports.add(new MatchReport(m.index(), d0.head(), d1.head(),
                    d0.rollbackCount() - baseRollbacks[k0], d1.rollbackCount() - baseRollbacks[k1],
                    d0.resimulatedFrames() - baseResim[k0], d1.resimulatedFrames() - baseResim[k1],
                    b, bf, deep, sent, sentBytes, m.anyAlive(), m.deadReason(), d0.retention()));
        }
        return new Result(config, measured, config.drivers(), edgeTick, edgeTickExGc, driverTick,
                allocBytes,
                gcCount, gcMillis, gcInWindow, framesAdvanced + framesResim, framesAdvanced,
                framesResim, rollbacks, bursts, burstFrames, deepest, packets, bytes, live,
                reports, retention, peakRetained, finalRetained, stalledDriverTicks,
                pacedOverruns);
    }

    private static long gcCount(List<GarbageCollectorMXBean> beans) {
        long n = 0;
        for (GarbageCollectorMXBean b : beans) {
            long c = b.getCollectionCount();
            if (c > 0) {
                n += c;
            }
        }
        return n;
    }

    private static long gcMillis(List<GarbageCollectorMXBean> beans) {
        long n = 0;
        for (GarbageCollectorMXBean b : beans) {
            long c = b.getCollectionTime();
            if (c > 0) {
                n += c;
            }
        }
        return n;
    }
}
