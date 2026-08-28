package me.nootnoot.sim.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarnessCompareReportTest {
    private static final int TICKS = 40;
    private static final int BREAK_AT = 17;

    @Test
    void identicalStreamsProduceNoFailures(@TempDir Path dir) throws IOException {
        HarnessCompareMain.RunFile left = read(dir, "Linux", "amd64", -1, -1);
        HarnessCompareMain.RunFile right = read(dir, "Windows_11", "amd64", -1, -1);

        List<String> failures = new ArrayList<>();
        HarnessCompareMain.comparePair(left, right, failures);
        assertEquals(List.of(), failures);
    }

    @Test
    void aDivergenceNamesTheFirstTickAndTheField(@TempDir Path dir) throws IOException {
        int facet = indexOf("p0.motion");
        HarnessCompareMain.RunFile left = read(dir, "Linux", "amd64", -1, -1);
        HarnessCompareMain.RunFile right = read(dir, "Linux", "aarch64", BREAK_AT, facet);

        List<String> failures = new ArrayList<>();
        HarnessCompareMain.comparePair(left, right, failures);

        assertEquals(1, failures.size(), failures.toString());
        String report = failures.get(0);
        assertTrue(report.contains("first differing tick : " + BREAK_AT), report);
        assertTrue(report.contains("last agreeing tick   : " + (BREAK_AT - 1)), report);
        assertTrue(report.contains("fields at tick " + BREAK_AT + " : p0.motion"), report);
        assertTrue(report.contains("likeliest origin     : p0.motion at tick " + BREAK_AT), report);
        assertTrue(report.contains("the only part of the state that moved first"), report);
    }

    @Test
    void aStreamWithoutFacetColumnsStillNamesTheTickAndSaysWhyItCannotNameTheField(
            @TempDir Path dir) throws IOException {
        HarnessCompareMain.RunFile left = read(dir, "Linux", "amd64", -1, -1);
        Path bare = dir.resolve("checksums-Old-aarch64.txt");
        List<Long> stream = new ArrayList<>();
        for (int t = 0; t < TICKS; t++) {
            stream.add(checksum(t, t >= BREAK_AT ? 1 : 0));
        }
        List<String> lines = new ArrayList<>();
        lines.add("# os=Old arch=aarch64 java=21");
        lines.add("# stream-digest=" + Long.toHexString(HarnessDigest.fold(stream))
                + " arena-hash=2 rollback-digest=3");
        for (int t = 0; t < TICKS; t++) {
            lines.add(t + " " + Long.toHexString(stream.get(t)));
        }
        Files.write(bare, lines, StandardCharsets.UTF_8);

        List<String> failures = new ArrayList<>();
        HarnessCompareMain.comparePair(left, HarnessCompareMain.read(bare), failures);

        String report = String.join("\n", failures);
        assertTrue(report.contains("first differing tick : " + BREAK_AT), report);
        assertTrue(report.contains("no facet columns"), report);
    }

    @Test
    void aWellFormedStreamIsInternallyConsistent(@TempDir Path dir) throws IOException {
        assertEquals(null,
                HarnessCompareMain.internalConsistency(read(dir, "Linux", "amd64", -1, -1)));
    }

    @Test
    void aStreamWhoseHeaderDoesNotFoldFromItsOwnRowsIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("checksums-Linux-amd64.txt");
        read(dir, "Linux", "amd64", -1, -1);
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith("#")) {
                String[] cells = lines.get(i).split(" ");
                cells[1] = Long.toHexString(Long.parseUnsignedLong(cells[1], 16) ^ 1L);
                lines.set(i, String.join(" ", cells));
                break;
            }
        }
        Files.write(file, lines, StandardCharsets.UTF_8);

        String failure = HarnessCompareMain.internalConsistency(HarnessCompareMain.read(file));
        assertTrue(failure != null,
                "one flipped bit in one tick of a collected stream, with the header left alone,"
                        + " is exactly what a hand-edited or truncated file looks like, and it must"
                        + " not be able to pass the gate the architecture decision rests on");
        assertTrue(failure.contains("not internally consistent"), failure);
    }

    @Test
    void aStreamMissingARowIsRefused(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("checksums-Linux-amd64.txt");
        read(dir, "Linux", "amd64", -1, -1);
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        lines.remove(lines.size() - 1);
        Files.write(file, lines, StandardCharsets.UTF_8);

        String failure = HarnessCompareMain.internalConsistency(HarnessCompareMain.read(file));
        assertTrue(failure != null, "a truncated stream must not compare as a shorter clean run");
    }

    @Test
    void aStreamWithReorderedRowsIsRefusedOnRead(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("checksums-Linux-amd64.txt");
        read(dir, "Linux", "amd64", -1, -1);
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        int first = -1;
        for (int i = 0; i < lines.size() && first < 0; i++) {
            if (!lines.get(i).startsWith("#")) {
                first = i;
            }
        }
        String swap = lines.get(first);
        lines.set(first, lines.get(first + 1));
        lines.set(first + 1, swap);
        Files.write(file, lines, StandardCharsets.UTF_8);

        IOException e = assertThrows(IOException.class, () -> HarnessCompareMain.read(file));
        assertTrue(e.getMessage().contains("out of order"), e.getMessage());
    }

    private static int indexOf(String facet) {
        for (int i = 0; i < StateFacets.COUNT; i++) {
            if (StateFacets.name(i).equals(facet)) {
                return i;
            }
        }
        throw new IllegalStateException("no facet named " + facet);
    }

    private static long checksum(int tick, int offset) {
        return 0x9E3779B97F4A7C15L * (tick + 1) + offset;
    }

    private static HarnessCompareMain.RunFile read(Path dir, String os, String arch,
                                                   int breakAt, int brokenFacet) throws IOException {
        Path file = dir.resolve("checksums-" + os + "-" + arch + ".txt");
        List<Long> stream = new ArrayList<>();
        for (int t = 0; t < TICKS; t++) {
            stream.add(checksum(t, breakAt >= 0 && t >= breakAt ? 1 : 0));
        }
        List<String> lines = new ArrayList<>();
        lines.add("# os=" + os + " arch=" + arch + " java=21.0.0");
        lines.add("# ticks=" + TICKS + " stream-digest=" + Long.toHexString(HarnessDigest.fold(stream))
                + " arena-hash=2 rollback-digest=3");
        lines.add("# facets=" + String.join(",", StateFacets.NAMES));
        for (int t = 0; t < TICKS; t++) {
            boolean broken = breakAt >= 0 && t >= breakAt;
            StringBuilder row = new StringBuilder();
            row.append(t).append(' ').append(Long.toHexString(stream.get(t)));
            for (int f = 0; f < StateFacets.COUNT; f++) {
                int value = (t + 1) * 31 + f;
                if (broken && f == brokenFacet) {
                    value ^= 0x5A5A5A5A;
                }
                row.append(' ').append(Integer.toHexString(value));
            }
            lines.add(row.toString());
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
        return HarnessCompareMain.read(file);
    }
}
