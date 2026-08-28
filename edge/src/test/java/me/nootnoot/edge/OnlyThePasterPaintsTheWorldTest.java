package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OnlyThePasterPaintsTheWorldTest {

    private static final Path MAIN = Path.of("src/main/java/me/nootnoot/edge");

    private static final String PASTER = "EdgeArenaPaster.java";

    @Test
    void everyWorldMutationGoesThroughTheOneClassThatJournalsIt() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (PASTER.equals(name)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (source.contains(".setBlockData(") || source.contains(".setType(")) {
                    offenders.add(name);
                }
            }
        }
        assertTrue(offenders.isEmpty(), offenders + " write blocks into the Paper world without"
                + " going through EdgeArenaPaster.Overlay. A cell written outside the Overlay is"
                + " journaled nowhere, so nothing can ever put it back, and it survives in the"
                + " region files after the process dies. That is how an arena ends up with blocks"
                + " standing in it that neither the arena file nor the sim ever asked for");
    }

    @Test
    void everyJournalRemovalAlsoRetiresTheStrayCell() throws IOException {
        String source = Files.readString(MAIN.resolve(PASTER), StandardCharsets.UTF_8);
        int removals = count(source, "previous.remove(");
        int retires = count(source, "owner.forgetStray(");
        assertEquals(removals, retires, "every path that drops a cell from the overlay's own"
                + " journal has to drop it from the world-scoped stray journal too. A removal"
                + " without a retire leaves a permanent row holding a stale block, which the next"
                + " match then stamps back into the world");
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }
}
