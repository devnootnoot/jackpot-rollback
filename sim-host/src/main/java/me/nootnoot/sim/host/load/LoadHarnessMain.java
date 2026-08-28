package me.nootnoot.sim.host.load;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.net.InputCodec;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.state.Arena;

public final class LoadHarnessMain {

    private LoadHarnessMain() {
    }

    public static void main(String[] args) throws IOException {
        List<Integer> sweep = new ArrayList<>(List.of(1, 2, 4, 8, 16, 32, 64));
        int ticks = 4000;
        int warmup = 600;
        long seed = 0x10ADL;
        int latency = 1;
        int jitter = 1;
        double loss = 0.01;
        int ring = 1024;
        int retentionInterval = 200;
        boolean gcBeforeRetention = false;
        int stallEvery = 0;
        int stallTicks = 0;
        boolean stallSync = false;
        int paceMode = LoadConfig.PACE_NONE;
        int chatPerTick = 0;
        Path json = null;
        boolean allocProbe = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--matches" -> {
                    sweep.clear();
                    for (String part : args[++i].split(",")) {
                        sweep.add(Integer.parseInt(part.trim()));
                    }
                }
                case "--ticks" -> ticks = Integer.parseInt(args[++i]);
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Long.decode(args[++i]);
                case "--latency" -> latency = Integer.parseInt(args[++i]);
                case "--jitter" -> jitter = Integer.parseInt(args[++i]);
                case "--loss" -> loss = Double.parseDouble(args[++i]);
                case "--ring" -> ring = Integer.parseInt(args[++i]);
                case "--retention-interval" -> retentionInterval = Integer.parseInt(args[++i]);
                case "--gc-before-retention" -> gcBeforeRetention = true;
                case "--stall-every" -> stallEvery = Integer.parseInt(args[++i]);
                case "--stall-ticks" -> stallTicks = Integer.parseInt(args[++i]);
                case "--chat-per-tick" -> chatPerTick = Integer.parseInt(args[++i]);
                case "--stall-sync" -> stallSync = true;
                case "--paced" -> paceMode = LoadConfig.PACE_PARK;
                case "--spin-paced" -> paceMode = LoadConfig.PACE_SPIN;
                case "--alloc-probe" -> allocProbe = true;
                case "--json" -> json = Path.of(args[++i]);
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    usage();
                    System.exit(2);
                }
            }
        }

        LoadConfig base = new LoadConfig(sweep.get(0), ticks, warmup, seed, latency, jitter, loss,
                ring, LoadConfig.TICK_MICROS, retentionInterval, gcBeforeRetention, stallEvery,
                stallTicks, stallSync, paceMode, chatPerTick);

        System.out.println("=== rollback load harness ===");
        System.out.println("os=" + System.getProperty("os.name")
                + " arch=" + System.getProperty("os.arch")
                + " java=" + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("cpus=" + Runtime.getRuntime().availableProcessors()
                + " maxHeap=" + (Runtime.getRuntime().maxMemory() >> 20) + "MiB");
        System.out.println("checksum-rev=" + Protocol.CHECKSUM_REV
                + " input-bytes=" + InputCodec.BYTES
                + " protocol-version=" + Protocol.VERSION);
        System.out.println("link: one-way=" + base.latencyFrames() + " frames ("
                + (base.latencyFrames() * 50) + "ms) jitter=" + base.jitterFrames()
                + " frames loss=" + base.loss()
                + " ring=" + base.ringCapacity());
        System.out.println("ticks=" + base.ticks() + " warmup=" + base.warmupTicks()
                + " seed=0x" + Long.toHexString(base.seed())
                + " stall=" + base.stallTicks() + " ticks every " + base.stallEveryTicks()
                + (base.paceMode() == LoadConfig.PACE_PARK ? " PACED at 20Hz (park)"
                        : base.paceMode() == LoadConfig.PACE_SPIN ? " PACED at 20Hz (spin)"
                        : " UNPACED (free-running)"));
        System.out.println();

        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"machine\": {");
        out.append("\"os\": ").append(quote(System.getProperty("os.name")));
        out.append(", \"arch\": ").append(quote(System.getProperty("os.arch")));
        out.append(", \"java\": ").append(quote(System.getProperty("java.version")));
        out.append(", \"cpus\": ").append(Runtime.getRuntime().availableProcessors());
        out.append(", \"maxHeapMiB\": ").append(Runtime.getRuntime().maxMemory() >> 20);
        out.append("},\n");
        out.append("  \"link\": {\"oneWayFrames\": ").append(base.latencyFrames())
                .append(", \"jitterFrames\": ").append(base.jitterFrames())
                .append(", \"loss\": ").append(base.loss())
                .append(", \"ring\": ").append(base.ringCapacity())
                .append("},\n");
        out.append("  \"sweep\": [\n");

        header();
        boolean first = true;
        for (int n : sweep) {
            LoadHarness harness = new LoadHarness(base.withMatches(n));
            LoadHarness.Result r = harness.run();
            row(r);
            reasons(r);
            if (!first) {
                out.append(",\n");
            }
            first = false;
            appendJson(out, r);
        }
        out.append("\n  ]");

        if (allocProbe) {
            System.out.println();
            System.out.println("--- isolated allocation and cost probes ---");
            List<AllocationProbe.Cost> costs = probe(base);
            System.out.printf(Locale.ROOT, "%-38s %14s %14s%n", "site", "bytes/call", "ns/call");
            out.append(",\n  \"probes\": [\n");
            boolean firstCost = true;
            for (AllocationProbe.Cost c : costs) {
                System.out.printf(Locale.ROOT, "%-38s %14.1f %14.1f%n", c.name(), c.bytesEach(),
                        c.nanosEach());
                if (!firstCost) {
                    out.append(",\n");
                }
                firstCost = false;
                out.append("    {\"site\": ").append(quote(c.name()))
                        .append(", \"iterations\": ").append(c.iterations())
                        .append(", \"bytesEach\": ").append(round(c.bytesEach()))
                        .append(", \"nanosEach\": ").append(round(c.nanosEach()))
                        .append("}");
            }
            out.append("\n  ]");
        }
        out.append("\n}\n");

        if (json != null) {
            Files.writeString(json, out.toString(), StandardCharsets.UTF_8);
            System.out.println();
            System.out.println("json written to " + json.toAbsolutePath());
        }
    }

    private static List<AllocationProbe.Cost> probe(LoadConfig base) {
        Arena arena = HarnessScenarios.arena();
        InputLog log = InputLog.scripted(base.seed(), InputLog.SCRIPT_END);
        return AllocationProbe.measure(arena, log, InputLog.SCRIPT_END, base.ringCapacity(), 4);
    }

    private static void reasons(LoadHarness.Result r) {
        Map<String, Integer> tally = new LinkedHashMap<>();
        for (LoadHarness.MatchReport m : r.matches()) {
            if (m.deadReason() != null) {
                tally.merge(m.deadReason().replaceAll("[0-9]+", "N"), 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : tally.entrySet()) {
            System.out.println("        " + e.getValue() + " match(es) ended: " + e.getKey());
        }
    }

    private static void header() {
        System.out.printf(Locale.ROOT,
                "%7s %8s %10s %10s %10s %10s %11s %11s %8s %9s %9s %7s %9s %8s%n",
                "matches", "drivers", "tick p50", "tick p99", "tick p99.9", "tick max",
                "p99-noGC", "p99.9-noGC", "over50ms", "rb/drv/s", "resim/rb", "burst",
                "KiB/tick", "GC ms");
    }

    private static void row(LoadHarness.Result r) {
        double drivers = Math.max(1, r.drivers());
        double ticks = Math.max(1, r.measuredTicks());
        System.out.printf(Locale.ROOT,
                "%7d %8d %9.2fms %9.2fms %9.2fms %9.2fms %10.2fms %10.2fms %8d %9.2f %9.2f"
                        + " %7d %9.1f %8d%n",
                r.config().matches(), r.drivers(),
                r.edgeTick().percentile(50) / 1e6,
                r.edgeTick().percentile(99) / 1e6,
                r.edgeTick().percentile(99.9) / 1e6,
                r.edgeTick().max() / 1e6,
                r.edgeTickExGc().percentile(99) / 1e6,
                r.edgeTickExGc().percentile(99.9) / 1e6,
                r.edgeTick().countAbove(50_000_000L),
                r.rollbacks() / drivers / ticks * 20.0,
                r.rollbacks() == 0 ? 0.0 : (double) r.framesResimulated() / r.rollbacks(),
                r.deepestBurst(),
                r.allocBytes() / ticks / 1024.0,
                r.gcMillisInWindow());
    }

    private static void appendJson(StringBuilder out, LoadHarness.Result r) {
        double drivers = Math.max(1, r.drivers());
        double ticks = Math.max(1, r.measuredTicks());
        out.append("    {");
        out.append("\"matches\": ").append(r.config().matches());
        out.append(", \"drivers\": ").append(r.drivers());
        out.append(", \"measuredTicks\": ").append(r.measuredTicks());
        out.append(", \"liveDriversAtEnd\": ").append(r.liveDrivers());
        out.append(", \"edgeTickMicros\": {");
        out.append("\"p50\": ").append(round(r.edgeTick().percentile(50) / 1000.0));
        out.append(", \"p99\": ").append(round(r.edgeTick().percentile(99) / 1000.0));
        out.append(", \"p999\": ").append(round(r.edgeTick().percentile(99.9) / 1000.0));
        out.append(", \"max\": ").append(round(r.edgeTick().max() / 1000.0));
        out.append(", \"mean\": ").append(round(r.edgeTick().mean() / 1000.0));
        out.append(", \"over50ms\": ").append(r.edgeTick().countAbove(50_000_000L));
        out.append("}");
        out.append(", \"edgeTickExGcMicros\": {");
        out.append("\"p50\": ").append(round(r.edgeTickExGc().percentile(50) / 1000.0));
        out.append(", \"p99\": ").append(round(r.edgeTickExGc().percentile(99) / 1000.0));
        out.append(", \"p999\": ").append(round(r.edgeTickExGc().percentile(99.9) / 1000.0));
        out.append(", \"max\": ").append(round(r.edgeTickExGc().max() / 1000.0));
        out.append("}");
        out.append(", \"driverTickMicros\": {");
        out.append("\"p50\": ").append(round(r.driverTick().percentile(50) / 1000.0));
        out.append(", \"p99\": ").append(round(r.driverTick().percentile(99) / 1000.0));
        out.append(", \"p999\": ").append(round(r.driverTick().percentile(99.9) / 1000.0));
        out.append(", \"max\": ").append(round(r.driverTick().max() / 1000.0));
        out.append("}");
        out.append(", \"framesAdvanced\": ").append(r.framesAdvanced());
        out.append(", \"framesResimulated\": ").append(r.framesResimulated());
        out.append(", \"framesSimulated\": ").append(r.framesSimulated());
        out.append(", \"rollbacks\": ").append(r.rollbacks());
        out.append(", \"rollbacksPerDriver\": ").append(round(r.rollbacks() / drivers));
        out.append(", \"resimPerRollback\": ").append(
                round(r.rollbacks() == 0 ? 0.0 : (double) r.framesResimulated() / r.rollbacks()));
        out.append(", \"catchUpBursts\": ").append(r.catchUpBursts());
        out.append(", \"catchUpFrames\": ").append(r.catchUpFrames());
        out.append(", \"deepestBurst\": ").append(r.deepestBurst());
        out.append(", \"stalledDriverTicks\": ").append(r.stalledDriverTicks());
        out.append(", \"paceMode\": ").append(r.config().paceMode());
        out.append(", \"pacedOverruns\": ").append(r.pacedOverruns());
        out.append(", \"packetsSent\": ").append(r.packetsSent());
        out.append(", \"bytesSent\": ").append(r.bytesSent());
        out.append(", \"allocBytes\": ").append(r.allocBytes());
        out.append(", \"allocBytesPerEdgeTick\": ").append(round(r.allocBytes() / ticks));
        out.append(", \"allocBytesPerDriverTick\": ").append(
                round(r.allocBytes() / ticks / drivers));
        out.append(", \"allocBytesPerSimulatedFrame\": ").append(
                round(r.framesSimulated() == 0 ? 0.0 : (double) r.allocBytes() / r.framesSimulated()));
        out.append(", \"gcCount\": ").append(r.gcCount());
        out.append(", \"gcMillis\": ").append(r.gcMillis());
        out.append(", \"gcMillisInsideTicks\": ").append(r.gcMillisInWindow());
        out.append(", \"peakRetainedEntries\": ").append(r.peakRetainedTotal());
        out.append(", \"finalRetainedEntries\": ").append(r.finalRetainedTotal());
        out.append(", \"retentionSamples\": [");
        boolean firstSample = true;
        for (LoadHarness.Retained s : r.retention()) {
            if (!firstSample) {
                out.append(", ");
            }
            firstSample = false;
            out.append("{\"tick\": ").append(s.tick())
                    .append(", \"total\": ").append(s.total())
                    .append(", \"localChecksums\": ").append(s.localChecksums())
                    .append(", \"pendingRemoteChecksums\": ").append(s.pendingRemoteChecksums())
                    .append(", \"rawInputs\": ").append(s.rawInputs())
                    .append(", \"localInputs\": ").append(s.localInputs())
                    .append(", \"ctlLocalInputs\": ").append(s.controllerLocalInputs())
                    .append(", \"ctlRemoteActual\": ").append(s.controllerRemoteActual())
                    .append(", \"ctlRemoteUsed\": ").append(s.controllerRemoteUsed())
                    .append(", \"chatQueue\": ").append(s.chatQueue())
                    .append(", \"containerQueue\": ").append(s.containerQueue())
                    .append(", \"heapUsedBytes\": ").append(s.heapUsedBytes())
                    .append("}");
        }
        out.append("]");
        out.append("}");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String round(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static void usage() {
        System.err.println("usage: LoadHarnessMain [--matches 1,2,4,...] [--ticks N] [--warmup N]"
                + " [--seed 0x..] [--latency frames] [--jitter frames] [--loss p] [--ring N]"
                + " [--retention-interval N] [--gc-before-retention]"
                + " [--stall-every N] [--stall-ticks N] [--stall-sync] [--paced] [--spin-paced]"
                + " [--chat-per-tick N] [--alloc-probe]"
                + " [--json <path>]");
    }
}
