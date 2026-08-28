package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HostInventorySurfaceGateTest {

    private static final String INTENTS = "InventoryIntents.";

    private record Surface(String name, Path source, String body) {
    }

    private static Path rollbackRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))
                    && Files.isDirectory(p.resolve("sim-core"))
                    && Files.isDirectory(p.resolve("edge"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    private static Path modFile(Path root, String tail) {
        Path up = root.getParent();
        for (int i = 0; up != null && i < 3; i++) {
            Path candidate = up.resolve("pvphq-rollback-mod/src/main/java/me/nootnoot/rollback/"
                    + "client/" + tail);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            up = up.getParent();
        }
        return null;
    }

    private static Map<String, Path> surfaceFiles() {
        Path root = rollbackRoot();
        assertTrue(root != null, "this gate could not find the jackpot-rollback root from "
                + Path.of("").toAbsolutePath() + ", so it can see neither host's inventory surface"
                + " and would pass without checking anything. Fix the lookup, do not delete it");
        Map<String, Path> out = new LinkedHashMap<>();
        out.put("EdgePlugin", root.resolve("edge/src/main/java/me/nootnoot/edge/EdgePlugin.java"));
        out.put("EdgeInputSource",
                root.resolve("edge/src/main/java/me/nootnoot/edge/EdgeInputSource.java"));
        out.put("McInputSource", modFile(root, "McInputSource.java"));
        out.put("RollbackModClient", modFile(root, "RollbackModClient.java"));
        out.put("RollbackContainerScreen", modFile(root, "RollbackContainerScreen.java"));
        out.put("ClientPlayerInteractionManagerMixin",
                modFile(root, "mixin/ClientPlayerInteractionManagerMixin.java"));
        return out;
    }

    private static String strip(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
                out.append("LITERAL");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static List<Surface> surfaces() throws IOException {
        List<Surface> out = new ArrayList<>();
        for (Map.Entry<String, Path> e : surfaceFiles().entrySet()) {
            assertTrue(e.getValue() != null && Files.isRegularFile(e.getValue()),
                    "the inventory surface file " + e.getKey() + " is not where this gate looks."
                            + " Both hosts have to be readable or the gate passes vacuously, which"
                            + " is how the modded half went unchecked for eight rounds. Fix the"
                            + " path, do not delete the entry");
            out.add(new Surface(e.getKey(), e.getValue(), strip(Files.readString(e.getValue()))));
        }
        return out;
    }

    @Test
    void neitherHostNamesAnInventoryActionOfItsOwn() throws IOException {
        List<String> complaints = new ArrayList<>();
        for (Surface s : surfaces()) {
            if (s.body().contains("Input.INV_")) {
                complaints.add(s.name() + " (" + s.source() + ")");
            }
        }
        assertTrue(complaints.isEmpty(),
                "a host that picks its own Input.INV_ constant is deciding what a physical click"
                        + " means, and every inventory divergence so far has been two hosts giving"
                        + " two answers to that. The only producer of an inventory action is"
                        + " InventoryIntents; the hosts translate a click into (kind, addr, button,"
                        + " slotFilled, cursorEmpty) and pass the result through unread. Offenders: "
                        + complaints);
    }

    @Test
    void neitherHostResolvesAnInventoryOperationItself() throws IOException {
        List<String> forbidden = List.of("Loadout.quickMove", "Loadout.moveStack",
                "Loadout.moveContainer", "Loadout.clickSlot", "Loadout.clickCell",
                "Loadout.swapWithHotbar", "Loadout.swapCellWithHotbar",
                "Loadout.pickupAllToCursor", "Loadout.placeCursorBack", "Loadout.addItem",
                "Loadout.consumeCursor");
        List<String> complaints = new ArrayList<>();
        for (Surface s : surfaces()) {
            for (String token : forbidden) {
                if (s.body().contains(token)) {
                    complaints.add(s.name() + " calls " + token);
                }
            }
        }
        assertTrue(complaints.isEmpty(),
                "the sim owns what an inventory action DOES. A host that resolves a destination or"
                        + " mutates a slot table is a second implementation of vanilla, and the two"
                        + " drifted the moment one of them was made vanilla-exact - that is the"
                        + " shift-click bug in one sentence. Offenders: " + complaints);
    }

    @Test
    void everyClickEntryPointGoesThroughTheSharedDecision() throws IOException {
        Map<String, Integer> required = new LinkedHashMap<>();
        required.put("EdgePlugin", 1);
        required.put("RollbackContainerScreen", 1);
        required.put("ClientPlayerInteractionManagerMixin", 1);
        List<String> complaints = new ArrayList<>();
        for (Surface s : surfaces()) {
            Integer want = required.get(s.name());
            if (want == null) {
                continue;
            }
            int seen = occurrences(s.body(), INTENTS + "decide(");
            if (seen < want) {
                complaints.add(s.name() + " calls " + INTENTS + "decide( " + seen + " times, wanted"
                        + " at least " + want);
            }
        }
        assertTrue(complaints.isEmpty(),
                "each of these files is a place a physical click enters the netcode. One that stops"
                        + " calling " + INTENTS + "decide is back to deciding for itself: "
                        + complaints);
    }

    @Test
    void theDecisionTableIsTheOnlyPlaceInSimCoreThatMintsAnInventoryAction() throws IOException {
        Path root = rollbackRoot();
        Path sim = root.resolve("sim-core/src/main/java/me/nootnoot/sim");
        List<String> allowed = List.of("state\\Input.java", "state/Input.java",
                "net\\InputCodec.java", "net/InputCodec.java",
                "contract\\InventoryIntents.java", "contract/InventoryIntents.java",
                "Combat.java", "harness\\InputLog.java", "harness/InputLog.java",
                "harness\\HarnessScenarios.java", "harness/HarnessScenarios.java");
        List<String> complaints = new ArrayList<>();
        try (var walk = Files.walk(sim)) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.toString();
                if (allowed.stream().anyMatch(name::endsWith)) {
                    continue;
                }
                if (strip(Files.readString(file)).contains("Input.INV_")) {
                    complaints.add(name);
                }
            }
        }
        assertTrue(complaints.isEmpty(),
                "an inventory action named outside the decision table, the wire codec, the"
                        + " simulation that executes it and the recorded harness is a fourth"
                        + " vocabulary nobody is checking: " + complaints);
    }

    private static int occurrences(String body, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = body.indexOf(token, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + token.length();
        }
    }
}
