package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EdgeNoSilentKitReductionTest {

    private static final Path MAIN = Path.of("src/main/java/me/nootnoot/edge");

    private static final String BUILDER = "EdgeDevKit.encode";

    private static final List<String> WARNERS =
            List.of("warnDisabledMechanics", "EdgeGameTypes.warningLines");

    @Test
    void everyPathThatBuildsADemoKitAlsoPrintsTheDisabledMechanicWarning() throws IOException {
        List<String> silent = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if ("EdgeDevKit.java".equals(name)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains(BUILDER)) {
                    continue;
                }
                if (WARNERS.stream().noneMatch(source::contains)) {
                    silent.add(name);
                }
            }
        }
        assertTrue(silent.isEmpty(), silent + " build a demo kit from a gameType and hand it to a"
                + " player without printing the disabled-mechanic warning. A kit that quietly"
                + " arrives without its crystals, anchors or buckets is what makes every fixed"
                + " crystal bug look unfixed");
    }

    @Test
    void everyRuleSetThatDisablesSomethingSaysSoOutLoud() {
        for (String id : EdgeGameTypes.IDS) {
            EdgeGameTypes.Rules rules = EdgeGameTypes.rules(id);
            boolean disables = !rules.allowExplosion() || !rules.allowBucket()
                    || !rules.vanillaBuild();
            if (!disables) {
                continue;
            }
            List<String> lines = EdgeGameTypes.warningLines(id, null, "test");
            assertFalse(lines.isEmpty(), id + " disables a mechanic and must say which items die");
            for (EdgeGameTypes.DeadItems dead : EdgeGameTypes.deadKitItems(id)) {
                for (String material : dead.materials()) {
                    assertTrue(lines.stream().anyMatch(line -> line.contains(material)),
                            id + " must name " + material + " in the banner, not just the"
                                    + " mechanic");
                }
            }
        }
    }
}
