package me.nootnoot.sim.harness;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.state.Arena;

public final class HarnessDigest {
    public static final int TICKS = InputLog.SCRIPT_END;
    public static final long SEED = 0xC0FFEEL;

    public static final String REFERENCE_RESOURCE = "/me/nootnoot/sim/harness/reference-digest.txt";

    public record Reference(int ticks, long seed, int checksumRev, long finalChecksum,
                            long streamDigest, long arenaHash, long rollbackDigest) {
    }

    public record Run(boolean converged, int divergedAtTick, long finalChecksum, long streamDigest,
                      long arenaHash, boolean independentArenas, long rollbackDigest,
                      RollbackAudit.Stats rollback, String disagreement,
                      List<String> uncoveredBehaviour, List<Long> checksums,
                      List<int[]> facets) {
    }

    private HarnessDigest() {
    }

    public static Run run() {
        InputLog log = InputLog.scripted(SEED, TICKS);
        Arena arenaA = HarnessScenarios.arena();
        Arena arenaB = HarnessScenarios.arena();
        HarnessCoverage coverage = new HarnessCoverage();
        DeterminismHarness.Result r = DeterminismHarness.run(
                log, HarnessScenarios.combat(arenaA), HarnessScenarios.combat(arenaB),
                arenaA, arenaB, coverage);
        return new Run(r.converged(), r.divergedAtTick(), r.finalChecksum(), fold(r.checksums()),
                r.arenaHash(), r.independentArenas(), r.rollbackDigest(), r.rollback(),
                r.disagreement(), coverage.missing(), r.checksums(), r.facets());
    }

    public static long fold(List<Long> checksums) {
        long digest = 0xcbf29ce484222325L;
        for (long c : checksums) {
            digest ^= c;
            digest *= 0x100000001b3L;
        }
        return digest;
    }

    public static Reference reference() {
        try (InputStream in = HarnessDigest.class.getResourceAsStream(REFERENCE_RESOURCE)) {
            if (in == null) {
                throw new IOException("reference digest resource missing: " + REFERENCE_RESOURCE);
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return parse(lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Reference parse(List<String> lines) throws IOException {
        int ticks = -1;
        long seed = -1;
        int rev = -1;
        long finalChecksum = 0;
        long digest = 0;
        long arenaHash = 0;
        long rollbackDigest = 0;
        boolean sawFinal = false;
        boolean sawDigest = false;
        boolean sawArena = false;
        boolean sawRollback = false;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IOException("malformed reference line: " + line);
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            switch (key) {
                case "ticks" -> ticks = Integer.parseInt(value);
                case "seed" -> seed = Long.parseUnsignedLong(value, 16);
                case "checksum-rev" -> rev = Integer.parseInt(value);
                case "final-checksum" -> {
                    finalChecksum = Long.parseUnsignedLong(value, 16);
                    sawFinal = true;
                }
                case "stream-digest" -> {
                    digest = Long.parseUnsignedLong(value, 16);
                    sawDigest = true;
                }
                case "arena-hash" -> {
                    arenaHash = Long.parseUnsignedLong(value, 16);
                    sawArena = true;
                }
                case "rollback-digest" -> {
                    rollbackDigest = Long.parseUnsignedLong(value, 16);
                    sawRollback = true;
                }
                default -> throw new IOException("unknown reference key: " + key);
            }
        }

        List<String> missing = new ArrayList<>();
        if (ticks < 0) {
            missing.add("ticks");
        }
        if (seed < 0) {
            missing.add("seed");
        }
        if (rev < 0) {
            missing.add("checksum-rev");
        }
        if (!sawFinal) {
            missing.add("final-checksum");
        }
        if (!sawDigest) {
            missing.add("stream-digest");
        }
        if (!sawArena) {
            missing.add("arena-hash");
        }
        if (!sawRollback) {
            missing.add("rollback-digest");
        }
        if (!missing.isEmpty()) {
            throw new IOException("reference is incomplete, it omits " + missing
                    + ". Every key is load bearing: a reference that simply leaves out arena-hash"
                    + " or rollback-digest silently downgrades this gate to a stream-digest-only"
                    + " check, so arena extraction and save/load/resimulate stop being compared"
                    + " against the reference machine at all. Re-record with"
                    + " ./gradlew :sim-core:updateHarnessDigest");
        }
        return new Reference(ticks, seed, rev, finalChecksum, digest, arenaHash, rollbackDigest);
    }

    public static void writeReference(Path file, Run run) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("ticks=" + TICKS + "\n");
            w.write("seed=" + Long.toHexString(SEED) + "\n");
            w.write("checksum-rev=" + Protocol.CHECKSUM_REV + "\n");
            w.write("final-checksum=" + Long.toHexString(run.finalChecksum()) + "\n");
            w.write("stream-digest=" + Long.toHexString(run.streamDigest()) + "\n");
            w.write("arena-hash=" + Long.toHexString(run.arenaHash()) + "\n");
            w.write("rollback-digest=" + Long.toHexString(run.rollbackDigest()) + "\n");
        }
    }

    public static List<String> mismatches(Reference ref, Run run) {
        List<String> problems = new ArrayList<>();
        if (ref.ticks() != TICKS) {
            problems.add("tick count: reference " + ref.ticks() + " vs harness " + TICKS);
        }
        if (ref.seed() != SEED) {
            problems.add("seed: reference " + Long.toHexString(ref.seed())
                    + " vs harness " + Long.toHexString(SEED));
        }
        if (ref.finalChecksum() != run.finalChecksum()) {
            problems.add("final checksum: reference " + Long.toHexString(ref.finalChecksum())
                    + " vs this run " + Long.toHexString(run.finalChecksum()));
        }
        if (ref.streamDigest() != run.streamDigest()) {
            problems.add("stream digest: reference " + Long.toHexString(ref.streamDigest())
                    + " vs this run " + Long.toHexString(run.streamDigest()));
        }
        if (ref.arenaHash() != run.arenaHash()) {
            problems.add("arena hash: reference " + Long.toHexString(ref.arenaHash())
                    + " vs this run " + Long.toHexString(run.arenaHash())
                    + ", so arena extraction does not agree with the reference machine");
        }
        if (ref.rollbackDigest() != run.rollbackDigest()) {
            problems.add("rollback digest: reference " + Long.toHexString(ref.rollbackDigest())
                    + " vs this run " + Long.toHexString(run.rollbackDigest())
                    + ", so save/load/resimulate does not replay the same way on this machine");
        }
        return problems;
    }

    public static String verdict(Reference ref, List<String> problems) {
        if (problems.isEmpty()) {
            return "digest matches the committed reference";
        }
        if (ref.checksumRev() != Protocol.CHECKSUM_REV) {
            return "the reference was recorded at CHECKSUM_REV " + ref.checksumRev()
                    + " and this build is CHECKSUM_REV " + Protocol.CHECKSUM_REV
                    + ", so sim behaviour changed on purpose: re-record with"
                    + " ./gradlew :sim-core:updateHarnessDigest and check the new digest into git";
        }
        return "CHECKSUM_REV is unchanged (" + Protocol.CHECKSUM_REV
                + ") but the digest moved, so either sim behaviour changed without a rev bump"
                + " or this machine does not agree with the reference machine";
    }
}
