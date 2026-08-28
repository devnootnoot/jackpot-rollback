package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UseCooldownTableTest {

    private static Path combatSource() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            Path candidate = p.resolve("sim-core/src/main/java/me/nootnoot/sim/Combat.java");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            p = p.getParent();
        }
        return null;
    }

    private static String table(String source) {
        int at = source.indexOf("public static int useCooldownTicks(");
        assertTrue(at >= 0, "useCooldownTicks is no longer where this gate looks for it");
        int end = source.indexOf("};", at);
        assertTrue(end > at, "the cooldown switch is no longer a switch expression");
        return source.substring(at, end);
    }

    @Test
    void everyUseKindThatArmsTheRepeatDelayIsNamedOnTheCooldownTable() throws IOException {
        Path source = combatSource();
        assertTrue(source != null, "this gate could not find Combat.java from "
                + Path.of("").toAbsolutePath() + ", so it would pass without reading anything");
        String body = Files.readString(source);
        String table = table(body);

        Set<String> armed = new LinkedHashSet<>();
        Matcher m = Pattern.compile("startUseItem\\(\\s*a\\s*,\\s*(USE_[A-Z_]+)\\s*\\)")
                .matcher(body);
        while (m.find()) {
            armed.add(m.group(1));
        }
        assertTrue(armed.size() >= 6,
                "only " + armed + " reach startUseItem, so this scan is not reading what it"
                        + " thinks it is");

        List<String> missing = new ArrayList<>();
        for (String kind : armed) {
            if (!table.contains("case Combat." + kind + " ->") && !table.contains("case " + kind + " ->")) {
                missing.add(kind);
            }
        }
        assertTrue(missing.isEmpty(),
                "startUseItem writes useCooldown[kind] = useCooldownTicks(kind), so a kind with"
                        + " no row on that table silently takes the default instead of a value"
                        + " anyone decided. USE_FIREWORK sat in that hole: it is the one kind"
                        + " that both arms the repeat delay and spawns a projectile, and its"
                        + " pacing was whatever default happened to say. Add the row, even when"
                        + " the honest value is zero, so the table is the whole answer: "
                        + missing);
    }
}
