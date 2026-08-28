package me.nootnoot.sim.harness;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.SimProbe;
import me.nootnoot.sim.net.Protocol;

public final class HarnessMain {
    public static final String OUT_DIR_PROPERTY = "jackpot.harness.out";

    private HarnessMain() {
    }

    static Path resolveOutDir(Path explicit) {
        if (explicit != null) {
            return explicit.toAbsolutePath().normalize();
        }
        String property = System.getProperty(OUT_DIR_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property).toAbsolutePath().normalize();
        }
        Path fromCode = outDirBesideTheseClasses();
        if (fromCode != null) {
            return fromCode;
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath()
                .resolve("build").resolve("harness").normalize();
    }

    static Path outDirBesideTheseClasses() {
        CodeSource source = HarnessMain.class.getProtectionDomain().getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        Path here;
        try {
            here = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
            return null;
        }
        Path root = Files.isDirectory(here) ? here : here.getParent();
        for (Path at = root; at != null; at = at.getParent()) {
            if (at.getFileName() != null && at.getFileName().toString().equals("build")) {
                return at.resolve("harness");
            }
        }
        return root == null ? null : root.resolve("harness");
    }

    public static void main(String[] args) throws IOException {
        boolean checkOnly = false;
        boolean reportOnly = false;
        boolean counts = false;
        Path writeReference = null;
        Path outDir = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--check" -> checkOnly = true;
                case "--report-only" -> reportOnly = true;
                case "--counts" -> counts = true;
                case "--write-reference" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--write-reference needs a path");
                        System.exit(2);
                    }
                    writeReference = Path.of(args[++i]);
                }
                case "--out" -> {
                    if (i + 1 >= args.length) {
                        System.err.println("--out needs a path");
                        System.exit(2);
                    }
                    outDir = Path.of(args[++i]);
                }
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.err.println("usage: HarnessMain [--check] [--report-only] [--counts]"
                            + " [--out <dir>] [--write-reference <path>]");
                    System.exit(2);
                }
            }
        }

        HarnessDigest.Run run = HarnessDigest.run();

        String arch = System.getProperty("os.arch");
        String os = System.getProperty("os.name").replaceAll("\\s+", "_");
        String javaVersion = System.getProperty("java.version");
        String javaVendor = System.getProperty("java.vendor");

        System.out.println("=== sim-core determinism harness ===");
        System.out.println("os=" + os + " arch=" + arch + " java=" + javaVersion + " (" + javaVendor + ")");
        System.out.println("ticks=" + HarnessDigest.TICKS
                + " seed=" + Long.toHexString(HarnessDigest.SEED)
                + " checksum-rev=" + Protocol.CHECKSUM_REV);
        System.out.println("intra-JVM converged: " + run.converged());
        if (!run.converged()) {
            System.out.println("!! DIVERGED at tick " + run.divergedAtTick());
            System.out.println("!! " + run.disagreement());
        }
        System.out.println("arena hash    : " + Long.toHexString(run.arenaHash())
                + (run.independentArenas()
                        ? " (two independently extracted arenas, distinct object graphs)"
                        : " (!! ONE Arena instance was handed in twice, so extraction is not measured)"));
        printRollback(run);
        int total = HarnessCoverage.REQUIREMENTS.length;
        int covered = total - run.uncoveredBehaviour().size();
        System.out.println("scenario cover: " + covered + "/" + total + " behaviours exercised");
        for (String gap : run.uncoveredBehaviour()) {
            System.out.println("  NOT EXERCISED " + gap);
        }
        System.out.println("final checksum: " + Long.toHexString(run.finalChecksum()));
        System.out.println("stream digest : " + Long.toHexString(run.streamDigest()));

        if (counts) {
            printCounts(run);
        }

        if (writeReference != null) {
            HarnessDigest.writeReference(writeReference, run);
            System.out.println("wrote reference " + writeReference.toAbsolutePath());
            System.out.println("check it into git so every other machine is measured against it");
            return;
        }

        if (!checkOnly) {
            Path dir = resolveOutDir(outDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("checksums-" + os + "-" + arch + ".txt");
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("# os=" + os + " arch=" + arch + " java=" + javaVersion + "\n");
                w.write("# ticks=" + HarnessDigest.TICKS
                        + " seed=" + Long.toHexString(HarnessDigest.SEED)
                        + " checksum-rev=" + Protocol.CHECKSUM_REV + "\n");
                w.write("# stream-digest=" + Long.toHexString(run.streamDigest())
                        + " arena-hash=" + Long.toHexString(run.arenaHash())
                        + " rollback-digest=" + Long.toHexString(run.rollbackDigest()) + "\n");
                w.write("# rollback-frames=" + run.rollback().frames()
                        + " rollbacks=" + run.rollback().rollbacks()
                        + " resimulated=" + run.rollback().resimulatedFrames() + "\n");
                w.write("# facets=" + String.join(",", StateFacets.NAMES) + "\n");
                StringBuilder row = new StringBuilder(64 + 9 * StateFacets.COUNT);
                for (int i = 0; i < run.checksums().size(); i++) {
                    row.setLength(0);
                    row.append(i).append(' ').append(Long.toHexString(run.checksums().get(i)));
                    for (int facet : run.facets().get(i)) {
                        row.append(' ').append(Integer.toHexString(facet));
                    }
                    row.append('\n');
                    w.write(row.toString());
                }
            }
            System.out.println("wrote " + file.toAbsolutePath());
        }

        HarnessDigest.Reference ref = HarnessDigest.reference();
        List<String> problems = HarnessDigest.mismatches(ref, run);
        System.out.println("reference     : " + Long.toHexString(ref.streamDigest())
                + " (recorded at checksum-rev " + ref.checksumRev() + ")");

        boolean uncovered = !run.uncoveredBehaviour().isEmpty()
                || !run.rollback().uncovered().isEmpty()
                || !run.independentArenas();
        if (!run.uncoveredBehaviour().isEmpty()) {
            System.out.println("the scenario no longer exercises " + run.uncoveredBehaviour().size()
                    + " behaviour(s) the gate is supposed to cover;"
                    + " a digest that matches while coverage has fallen out proves very little");
        }
        if (!run.rollback().uncovered().isEmpty()) {
            System.out.println("the rollback pass no longer reaches "
                    + run.rollback().uncovered().size()
                    + " rollback shape(s) the gate is supposed to cover;"
                    + " a forward-only digest is not a rollback gate");
        }
        if (!run.independentArenas()) {
            System.out.println("both simulation instances were handed the same Arena object, so the"
                    + " arena hash compares an extraction with itself");
        }

        if (problems.isEmpty() && run.converged() && !uncovered) {
            System.out.println("OK: " + HarnessDigest.verdict(ref, problems));
            System.out.println("CROSS-MACHINE GATE: this only proves " + arch
                    + "; the gate is green when an x64 and an arm64 run agree.");
            return;
        }

        for (String p : problems) {
            System.out.println("MISMATCH " + p);
        }
        System.out.println(HarnessDigest.verdict(ref, problems));

        if (reportOnly) {
            System.out.println("--report-only: not failing");
            return;
        }
        System.exit(1);
    }

    private static void printRollback(HarnessDigest.Run run) {
        RollbackAudit.Stats s = run.rollback();
        System.out.println("rollback pass : " + s.rollbacks() + " rollbacks over " + s.frames()
                + " frames, " + s.resimulatedFrames() + " resimulated frames, deepest "
                + s.deepestRollback() + ", " + s.batchedRollbacks() + " batched, "
                + s.deliveries() + " scripted arrivals");
        int total = RollbackAudit.REQUIREMENTS.length;
        System.out.println("rollback cover: " + (total - s.uncovered().size()) + "/" + total
                + " rollback shapes exercised");
        for (String gap : s.uncovered()) {
            System.out.println("  NOT EXERCISED " + gap);
        }
        System.out.println("rollback dgst : " + Long.toHexString(run.rollbackDigest()));
    }

    private static void printCounts(HarnessDigest.Run run) {
        HarnessCounts.Result c = HarnessCounts.run();
        System.out.println();
        System.out.println("=== sim entry-point counts over the same scripted run ===");
        if (c.finalChecksum() != run.finalChecksum()) {
            System.out.println("!! the counted run ended at checksum "
                    + Long.toHexString(c.finalChecksum()) + " but the digested run ended at "
                    + Long.toHexString(run.finalChecksum())
                    + ", so these counts do not describe the run the gate measures");
        }
        int width = 0;
        for (String n : SimProbe.names()) {
            width = Math.max(width, n.length());
        }
        for (String n : HarnessCounts.observedNames()) {
            width = Math.max(width, n.length());
        }
        for (int i = 0; i < SimProbe.COUNTERS; i++) {
            System.out.printf("  %-" + width + "s %12d%n", SimProbe.name(i), c.entryPoint(i));
        }
        System.out.println("--- observed from replicated state, no read-back into sim logic ---");
        for (int i = 0; i < HarnessCounts.OBSERVED_COUNTERS; i++) {
            System.out.printf("  %-" + width + "s %12d%n",
                    HarnessCounts.observedName(i), c.observation(i));
        }
        List<String> zeroes = new ArrayList<>(c.zeroEntryPoints());
        zeroes.addAll(c.zeroObservations());
        System.out.println("uncovered (count of exactly zero): " + zeroes.size());
        for (String z : zeroes) {
            System.out.println("  ZERO " + z);
        }
    }
}
