package me.nootnoot.sim.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class HarnessCompareMain {
    record RunFile(Path file, String os, String arch, String java, long digest,
                          long arenaHash, long rollbackDigest, List<Long> checksums,
                          List<String> facetNames, List<int[]> facets, int declaredTicks) {
        String platform() {
            return os + "/" + arch;
        }

        long recomputedDigest() {
            return HarnessDigest.fold(checksums);
        }
    }

    private HarnessCompareMain() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of("build", "harness");
        int min = 2;
        int requiredPlatforms = 2;
        int requiredArches = 2;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dir" -> dir = Path.of(require(args, ++i, "--dir"));
                case "--min" -> min = Integer.parseInt(require(args, ++i, "--min"));
                case "--allow-same-arch" -> requiredArches = 1;
                case "--require-arches" ->
                        requiredArches = Integer.parseInt(require(args, ++i, "--require-arches"));
                case "--require-platforms" ->
                        requiredPlatforms = Integer.parseInt(require(args, ++i, "--require-platforms"));
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.err.println("usage: HarnessCompareMain [--dir <path>] [--min <n>]"
                            + " [--require-platforms <n>] [--require-arches <n>] [--allow-same-arch]");
                    System.exit(2);
                }
            }
        }

        if (!Files.isDirectory(dir)) {
            System.out.println("FAIL no harness output directory at " + dir.toAbsolutePath());
            System.exit(1);
        }

        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("checksums-"))
                    .filter(p -> p.getFileName().toString().endsWith(".txt"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        List<RunFile> runs = new ArrayList<>();
        for (Path f : files) {
            runs.add(read(f));
        }

        System.out.println("=== cross-machine determinism comparison ===");
        for (RunFile r : runs) {
            System.out.println("  " + r.platform() + " java=" + r.java()
                    + " ticks=" + r.checksums().size() + " digest=" + Long.toHexString(r.digest())
                    + " arena=" + Long.toHexString(r.arenaHash())
                    + " rollback=" + Long.toHexString(r.rollbackDigest())
                    + (r.facets().isEmpty() ? " (no facet columns)"
                            : " facets=" + r.facetNames().size())
                    + "  (" + r.file() + ")");
        }

        List<String> failures = new ArrayList<>();

        for (RunFile r : runs) {
            String broken = internalConsistency(r);
            if (broken != null) {
                failures.add(broken);
            }
        }

        if (runs.size() < min) {
            failures.add("expected at least " + min + " harness runs to compare, found " + runs.size()
                    + "; a gate with nothing to compare proves nothing");
        }

        Set<String> arches = new LinkedHashSet<>();
        Set<String> platforms = new LinkedHashSet<>();
        for (RunFile r : runs) {
            arches.add(r.arch());
            platforms.add(r.platform());
        }
        if (runs.size() >= min && platforms.size() < requiredPlatforms) {
            failures.add("the runs cover " + platforms.size() + " platform(s) " + platforms
                    + " but " + requiredPlatforms + " are required; two runs off the same"
                    + " os and architecture measure the same JVM twice");
        }
        if (runs.size() >= min && arches.size() < requiredArches) {
            failures.add("the runs cover " + arches.size() + " architecture(s) " + arches
                    + " but " + requiredArches + " are required; the cross-architecture gate"
                    + " needs an x64 run and an arm64 run");
        }

        if (!runs.isEmpty()) {
            RunFile base = runs.get(0);
            for (int i = 1; i < runs.size(); i++) {
                comparePair(base, runs.get(i), failures);
            }
        }

        HarnessDigest.Reference ref = HarnessDigest.reference();
        System.out.println("committed reference digest=" + Long.toHexString(ref.streamDigest())
                + " checksum-rev=" + ref.checksumRev());
        for (RunFile r : runs) {
            if (r.digest() != ref.streamDigest()) {
                failures.add(r.platform() + ": digest " + Long.toHexString(r.digest())
                        + " does not match the committed reference " + Long.toHexString(ref.streamDigest()));
            }
            if (r.rollbackDigest() != ref.rollbackDigest()) {
                failures.add(r.platform() + ": rollback digest " + Long.toHexString(r.rollbackDigest())
                        + " does not match the committed reference "
                        + Long.toHexString(ref.rollbackDigest()));
            }
            if (r.arenaHash() != ref.arenaHash()) {
                failures.add(r.platform() + ": arena hash " + Long.toHexString(r.arenaHash())
                        + " does not match the committed reference "
                        + Long.toHexString(ref.arenaHash()));
            }
            long last = r.checksums().get(r.checksums().size() - 1);
            if (last != ref.finalChecksum()) {
                failures.add(r.platform() + ": final checksum " + Long.toHexString(last)
                        + " does not match the committed reference " + Long.toHexString(ref.finalChecksum()));
            }
        }

        if (requiredArches < 2) {
            System.out.println("NOTE this comparison required only " + requiredArches
                    + " architecture; it can prove the os and the jvm agree but it cannot prove"
                    + " x64 and arm64 agree. That needs an arm64 runner.");
        }

        if (failures.isEmpty()) {
            System.out.println("PASS " + runs.size() + " runs across " + platforms
                    + " are bit-identical, tick by tick, and every file's header digest folds"
                    + " from that file's own rows");
            return;
        }
        for (String f : failures) {
            System.out.println("FAIL " + f);
        }
        System.exit(1);
    }

    static String internalConsistency(RunFile r) {
        long recomputed = r.recomputedDigest();
        if (recomputed != r.digest()) {
            return r.platform() + " (" + r.file() + "): the file is not internally consistent."
                    + " Its header claims stream-digest " + Long.toHexString(r.digest())
                    + " but folding its own " + r.checksums().size() + " per-tick checksums gives "
                    + Long.toHexString(recomputed) + ". Every other verdict in this tool is drawn"
                    + " from a header this file writes about itself, so a stream whose rows and"
                    + " header disagree has either been truncated in transit or edited by hand and"
                    + " proves nothing about the machine that produced it. Re-run"
                    + " :sim-core:harness on that machine and collect the file again.";
        }
        if (r.declaredTicks() >= 0 && r.declaredTicks() != r.checksums().size()) {
            return r.platform() + " (" + r.file() + "): the header declares " + r.declaredTicks()
                    + " ticks but the file carries " + r.checksums().size() + " checksum rows,"
                    + " so rows have been added or removed since it was written.";
        }
        return null;
    }

    static void comparePair(RunFile base, RunFile other, List<String> failures) {
        String pair = base.platform() + " vs " + other.platform();
        if (base.arenaHash() != other.arenaHash()) {
            failures.add(pair + ": the arenas extracted on the two machines disagree ("
                    + Long.toHexString(base.arenaHash()) + " vs "
                    + Long.toHexString(other.arenaHash())
                    + "), so arena extraction is not cross-machine deterministic");
        }
        if (base.rollbackDigest() != other.rollbackDigest()) {
            failures.add(pair + ": the rollback digests disagree ("
                    + Long.toHexString(base.rollbackDigest()) + " vs "
                    + Long.toHexString(other.rollbackDigest())
                    + "), so save/load/resimulate is not cross-machine deterministic even"
                    + " if the forward stream is");
        }
        if (base.checksums().size() != other.checksums().size()) {
            failures.add(pair + ": tick counts differ ("
                    + base.checksums().size() + " vs " + other.checksums().size() + ")");
            return;
        }

        int ticks = base.checksums().size();
        int firstBad = -1;
        int differing = 0;
        int lastBad = -1;
        for (int t = 0; t < ticks; t++) {
            if (!base.checksums().get(t).equals(other.checksums().get(t))) {
                if (firstBad < 0) {
                    firstBad = t;
                }
                lastBad = t;
                differing++;
            }
        }

        if (firstBad < 0) {
            if (base.digest() != other.digest()) {
                failures.add(pair + ": streams match tick by tick but the recorded digests differ ("
                        + Long.toHexString(base.digest()) + " vs " + Long.toHexString(other.digest())
                        + "), so one of the files was written by a different harness build");
            }
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append(pair).append(": the checksum streams diverge.");
        report.append("\n    first differing tick : ").append(firstBad)
                .append("  ").append(Long.toHexString(base.checksums().get(firstBad)))
                .append(" vs ").append(Long.toHexString(other.checksums().get(firstBad)));
        report.append("\n    last agreeing tick   : ").append(firstBad - 1);
        report.append("\n    differing ticks      : ").append(differing).append(" of ").append(ticks)
                .append(differing == ticks - firstBad
                        ? ", never re-converges"
                        : ", last one at tick " + lastBad + " (the streams re-converge in places,"
                                + " which usually means a transient value rather than drift)");
        report.append(facetReport(base, other, firstBad));
        failures.add(report.toString());
    }

    static String facetReport(RunFile base, RunFile other, int firstBad) {
        List<String> names = base.facetNames();
        if (base.facets().isEmpty() || other.facets().isEmpty()) {
            return "\n    fields               : one of these streams has no facet columns, so"
                    + " the divergence cannot be attributed to a part of the state."
                    + " Re-record both with a harness that writes them.";
        }
        if (!names.equals(other.facetNames())) {
            return "\n    fields               : the two streams name different facets ("
                    + names + " vs " + other.facetNames() + "), so they came from different"
                    + " harness builds and cannot be attributed field by field.";
        }

        int width = names.size();
        int[] firstPerFacet = new int[width];
        Arrays.fill(firstPerFacet, -1);
        int ticks = Math.min(base.facets().size(), other.facets().size());
        for (int t = 0; t < ticks; t++) {
            int[] l = base.facets().get(t);
            int[] r = other.facets().get(t);
            for (int f = 0; f < width && f < l.length && f < r.length; f++) {
                if (firstPerFacet[f] < 0 && l[f] != r[f]) {
                    firstPerFacet[f] = t;
                }
            }
        }

        StringBuilder atTick = new StringBuilder();
        List<String> earliest = new ArrayList<>();
        int earliestTick = Integer.MAX_VALUE;
        List<String> origin = new ArrayList<>();
        for (int f = 0; f < width; f++) {
            if (firstPerFacet[f] < 0) {
                continue;
            }
            earliest.add(names.get(f) + " @" + firstPerFacet[f]);
            if (firstPerFacet[f] < earliestTick) {
                earliestTick = firstPerFacet[f];
                origin.clear();
                origin.add(names.get(f));
            } else if (firstPerFacet[f] == earliestTick) {
                origin.add(names.get(f));
            }
            if (firstBad < base.facets().size() && firstBad < other.facets().size()) {
                int l = base.facets().get(firstBad)[f];
                int r = other.facets().get(firstBad)[f];
                if (l != r) {
                    if (atTick.length() > 0) {
                        atTick.append(", ");
                    }
                    atTick.append(names.get(f)).append(" (").append(Integer.toHexString(l))
                            .append(" vs ").append(Integer.toHexString(r)).append(')');
                }
            }
        }

        if (earliest.isEmpty()) {
            return "\n    fields               : every facet agrees on every tick even though the"
                    + " checksums differ, so the divergence is in a field the facets do not cover"
                    + " (see StateFacets.NOT_CHECKSUMMED and the field-coverage test).";
        }
        return "\n    fields at tick " + firstBad + " : "
                + (atTick.length() == 0 ? "none" : atTick.toString())
                + "\n    earliest per field   : " + String.join(", ", earliest)
                + "\n    likeliest origin     : " + String.join(" and ", origin)
                + " at tick " + earliestTick
                + (origin.size() == 1
                        ? " (the only part of the state that moved first)"
                        : " (several parts moved on the same tick, so look at whichever the sim"
                                + " writes first on that tick)");
    }

    private static String require(String[] args, int i, String flag) {
        if (i >= args.length) {
            System.err.println(flag + " needs a value");
            System.exit(2);
        }
        return args[i];
    }

    static RunFile read(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String os = "?";
        String arch = "?";
        String java = "?";
        long digest = 0;
        long arenaHash = 0;
        long rollbackDigest = 0;
        int declaredTicks = -1;
        List<String> facetNames = List.of();
        List<Long> checksums = new ArrayList<>();
        List<int[]> facets = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                for (String token : line.substring(1).trim().split("\\s+")) {
                    int eq = token.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String key = token.substring(0, eq);
                    String value = token.substring(eq + 1);
                    switch (key) {
                        case "os" -> os = value;
                        case "arch" -> arch = value;
                        case "java" -> java = value;
                        case "ticks" -> declaredTicks = Integer.parseInt(value);
                        case "stream-digest" -> digest = Long.parseUnsignedLong(value, 16);
                        case "arena-hash" -> arenaHash = Long.parseUnsignedLong(value, 16);
                        case "rollback-digest" ->
                                rollbackDigest = Long.parseUnsignedLong(value, 16);
                        case "facets" -> facetNames = List.of(value.split(","));
                        default -> {
                        }
                    }
                }
                continue;
            }
            String[] cells = line.split("\\s+");
            if (cells.length < 2) {
                throw new IOException("malformed checksum line in " + file + ": " + line);
            }
            int tick = Integer.parseInt(cells[0]);
            if (tick != checksums.size()) {
                throw new IOException(file + " numbers its rows out of order: row "
                        + checksums.size() + " says tick " + tick + ". The stream is the evidence"
                        + " this gate rests on, so a reordered, duplicated or dropped row is a"
                        + " corrupt file, not a stream to compare.");
            }
            checksums.add(Long.parseUnsignedLong(cells[1], 16));
            if (cells.length > 2) {
                int[] row = new int[cells.length - 2];
                for (int c = 2; c < cells.length; c++) {
                    row[c - 2] = Integer.parseUnsignedInt(cells[c], 16);
                }
                facets.add(row);
            }
        }

        if (checksums.isEmpty()) {
            throw new IOException("no checksum rows in " + file);
        }
        if (!facets.isEmpty() && facets.size() != checksums.size()) {
            throw new IOException(file + " has facet columns on only " + facets.size()
                    + " of " + checksums.size() + " ticks");
        }
        if (!facets.isEmpty() && facetNames.size() != facets.get(0).length) {
            throw new IOException(file + " declares " + facetNames.size() + " facet names but"
                    + " each row carries " + facets.get(0).length + " facet columns");
        }
        return new RunFile(file, os, arch, java, digest, arenaHash, rollbackDigest, checksums,
                facetNames, facets, declaredTicks);
    }
}
