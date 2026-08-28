package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TranscendentalSourceGateTest {
    private static final Set<String> PLATFORM_DEPENDENT = Set.of(
            "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "pow", "exp", "cbrt",
            "hypot", "log", "log10", "expm1", "log1p", "sinh", "cosh", "tanh",
            "toRadians", "toDegrees", "random");

    private record Hit(String file, int line, String owner, String method) {
        String key() {
            return file + " :: " + owner + "." + method;
        }

        @Override
        public String toString() {
            return file + ":" + line + " -> " + owner + "." + method;
        }
    }

    private static Map<String, Integer> reviewedCounts() {
        Map<String, Integer> all = new LinkedHashMap<>();
        all.put("EdgeCageDisplay.java :: Math.toRadians", 1);
        all.put("EdgeCombatFx.java :: Math.sin", 2);
        all.put("EdgeCombatFx.java :: Math.cos", 2);
        all.put("EdgeProjectiles.java :: Math.atan2", 2);
        all.put("KillEffectRenderer.java :: Math.toRadians", 1);
        all.put("McSimRenderer.java :: Math.atan2", 3);
        all.put("McSimRenderer.java :: Math.pow", 1);
        all.put("McSimRenderer.java :: Math.toRadians", 1);
        all.put("McSimRenderer.java :: Math.sin", 1);
        all.put("McSimRenderer.java :: Math.cos", 1);
        all.put("McSimRenderer.java :: Math.random", 1);
        return all;
    }

    static Set<String> reviewedKeys() {
        return reviewedCounts().keySet();
    }

    private static Map<String, String> reviewedReasons() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("EdgeCageDisplay.java :: Math.toRadians", "the between-round cage gate swing angle,"
                + " fed to a display-entity transform. Nothing reads it back and it never reaches"
                + " an Input frame.");
        all.put("EdgeCombatFx.java :: Math.sin", "the spawn positions of the particle ring drawn"
                + " around a hit. Particles are spawned per viewer and never read back.");
        all.put("EdgeCombatFx.java :: Math.cos", "the spawn positions of the particle ring drawn"
                + " around a hit. Particles are spawned per viewer and never read back.");
        all.put("EdgeProjectiles.java :: Math.atan2", "the yaw and pitch an arrow entity is drawn"
                + " at. The simulated projectile carries velocity, not angles.");
        all.put("KillEffectRenderer.java :: Math.toRadians", "the kill-effect camera field of"
                + " view, client rendering only.");
        all.put("McSimRenderer.java :: Math.atan2", "render angles: the local player's movement"
                + " yaw and the drawn yaw and pitch of a projectile.");
        all.put("McSimRenderer.java :: Math.pow", "an easing curve for smoothing the opponent's"
                + " rendered position between confirmed frames.");
        all.put("McSimRenderer.java :: Math.toRadians", "a fixed quarter turn applied to a"
                + " rendered model.");
        all.put("McSimRenderer.java :: Math.sin", "the spawn positions of a client particle ring."
                + " The sim never sees a particle.");
        all.put("McSimRenderer.java :: Math.cos", "the spawn positions of a client particle ring."
                + " The sim never sees a particle.");
        all.put("McSimRenderer.java :: Math.random", "cosmetic particle jitter, explicitly outside"
                + " the sim.");
        return all;
    }

    @Test
    void noProducerOfReplicatedStateCallsAPlatformDependentMathMethod() throws IOException {
        List<Hit> hits = scanEverything();

        Map<String, Integer> seen = new TreeMap<>();
        for (Hit h : hits) {
            seen.merge(h.key(), 1, Integer::sum);
        }
        Map<String, Integer> reviewed = reviewedCounts();

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            Integer allowed = reviewed.get(e.getKey());
            if (allowed == null) {
                problems.add("NEW: " + e.getKey() + " x" + e.getValue() + " at "
                        + hits.stream().filter(h -> h.key().equals(e.getKey())).toList());
            } else if (!allowed.equals(e.getValue())) {
                problems.add("COUNT CHANGED: " + e.getKey() + " was reviewed at x" + allowed
                        + " and there are now x" + e.getValue() + " at "
                        + hits.stream().filter(h -> h.key().equals(e.getKey())).toList());
            }
        }
        for (String key : reviewed.keySet()) {
            if (!seen.containsKey(key)) {
                problems.add("STALE: " + key + " is reviewed here but no longer exists");
            }
        }

        assertTrue(problems.isEmpty(),
                "java.lang.Math methods that are not bit-reproducible across architectures, in code"
                        + " that feeds the replicated simulation. This is the check the spawn yaw"
                        + " walked around: TranscendentalBytecodeGateTest reads sim-core's compiled"
                        + " classes only, so a helper placed in sim-host, in the edge, in the mod or"
                        + " hand-copied into the plugin was never looked at, and Math.atan2 ran"
                        + " inside checksummed frame-0 state on whatever architecture happened to"
                        + " host the match. Use StrictMath, or MathTables, or derive the value from"
                        + " exact arithmetic. If the value is genuinely presentation only, add it to"
                        + " reviewedCounts() and reviewedReasons() with the reason: " + problems);
    }

    @Test
    void everyReviewedCallSaysWhyItIsAllowed() {
        Set<String> counted = new TreeSet<>(reviewedCounts().keySet());
        Set<String> explained = new TreeSet<>(reviewedReasons().keySet());
        assertEquals(counted, explained,
                "every reviewed call needs a reason and every reason needs a call. An allow list"
                        + " entry with no argument attached to it is how the next one gets waved"
                        + " through");
        for (Map.Entry<String, String> e : reviewedReasons().entrySet()) {
            assertTrue(e.getValue().length() >= 30,
                    e.getKey() + " is allowed with a reason too short to be one: " + e.getValue());
        }
    }

    @Test
    void nothingHidesTheOwnerBehindAStaticImport() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (List<Path> files : scanTargets().values()) {
            for (Path f : files) {
                String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
                if (src.contains("import static java.lang.Math.")
                        || src.contains("import static java.lang.StrictMath.")) {
                    offenders.add(f.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "a static import of Math turns Math.sin into a bare sin(), which this scanner and"
                        + " a reader both miss: " + offenders);
    }

    @Test
    void theTwoHandCopiedSpawnFacingsComputeTheSameThing() throws IOException {
        Path host = findUp("jackpot-rollback", "sim-host", "src", "main", "java", "me", "nootnoot",
                "sim", "host");
        assertTrue(host != null, "sim-host sources were not found from working directory "
                + Path.of("").toAbsolutePath());
        Path hostFile = host.resolve("SpawnFacing.java");
        assertTrue(Files.isRegularFile(hostFile), "sim-host has no SpawnFacing.java any more;"
                + " if it moved, move this check with it rather than deleting it");

        Path practice = findUp("mcleagues", "mcleagues-core", "src", "main", "java", "me",
                "nootnoot", "modules", "practice");
        if (practice == null) {
            return;
        }
        List<Path> copies = new ArrayList<>();
        for (Path f : javaFilesUnder(practice)) {
            if ("SpawnFacing.java".equals(f.getFileName().toString())) {
                copies.add(f);
            }
        }
        assertEquals(1, copies.size(),
                "the plugin repo is checked out beside this one and holds " + copies.size()
                        + " copies of SpawnFacing. The plugin computes the frame-0 spawn yaw the"
                        + " edge also computes for itself, so the two have to stay one function");

        assertEquals(body(hostFile), body(copies.get(0)),
                "the plugin's hand-copied SpawnFacing no longer computes the same thing as"
                        + " sim-host's. Both stamp the yaw of a player at frame 0, which is"
                        + " checksummed state, and cross-play compares a yaw the plugin computed"
                        + " against one the edge computed. They must be the same arithmetic, not"
                        + " just the same intent");
    }

    @Test
    void theScanReachedEveryTreeItIsSupposedToCover() throws IOException {
        Map<String, List<Path>> roots = scanTargets();
        for (String required : List.of("sim-core", "sim-host", "edge", "relay")) {
            assertTrue(roots.containsKey(required),
                    "the scan never found " + required + " from working directory "
                            + Path.of("").toAbsolutePath() + "; a green gate over a partial tree"
                            + " is worth nothing");
        }

        Set<String> seen = new LinkedHashSet<>();
        for (List<Path> files : roots.values()) {
            for (Path f : files) {
                seen.add(f.getFileName().toString());
            }
        }
        assertTrue(seen.contains("Simulation.java"), "the sim itself was not scanned");
        assertTrue(seen.contains("Combat.java"), "Combat was not scanned");
        assertTrue(seen.contains("SpawnFacing.java"),
                "SpawnFacing was not scanned, and it is the file this gate was widened for");
        assertTrue(seen.contains("EdgeInputSource.java"), "the edge frame producer was not scanned");
        if (roots.containsKey("mod")) {
            assertTrue(seen.contains("McInputSource.java"),
                    "the mod tree was found but its frame producer was not scanned");
        }
        if (roots.containsKey("plugin-rollback")) {
            assertTrue(seen.contains("RollbackHandoffManager.java"),
                    "the plugin repo was found but the manager that stamps frame 0 was not"
                            + " scanned");
        }
        assertTrue(seen.size() >= 60,
                "only " + seen.size() + " sources were scanned across " + roots.keySet());
    }

    @Test
    void theScannerSeesACallAndIgnoresTextThatOnlyLooksLikeOne() {
        String live = """
                class Sample {
                    float yaw(double dx, double dz) {
                        return (float) Math.toDegrees(Math.atan2(-dx, dz));
                    }
                }
                """;
        List<Hit> hits = scanSource("Sample.java", live);
        assertEquals(2, hits.size(), "the scanner missed a live call: " + hits);
        assertTrue(hits.stream().anyMatch(h -> "atan2".equals(h.method())));
        assertTrue(hits.stream().anyMatch(h -> "toDegrees".equals(h.method())));

        String exact = """
                class Sample {
                    float yaw(double dx, double dz) {
                        return (float) (StrictMath.atan2(-dx, dz) * 180.0 / StrictMath.PI);
                    }
                    double safe() {
                        return Math.sqrt(2.0) + Math.floor(1.5) + Math.PI;
                    }
                }
                """;
        assertTrue(scanSource("Sample.java", exact).isEmpty(),
                "StrictMath is the bit-reproducible escape hatch and sqrt/floor/PI are exact, so"
                        + " none of these may be flagged: " + scanSource("Sample.java", exact));

        String stonecutter = """
                class Sample {
                    void go() {
                        //? if >=26.1 {
                        /*float a = (float) Math.toRadians(fov);
                        *///?} else {
                        float a = fallback(fov);
                        //?}
                    }
                }
                """;
        assertEquals(1, scanSource("Sample.java", stonecutter).size(),
                "the mod is multiversion: the branch that is live on 26.x sits inside a block"
                        + " comment in the checked-in source. A scanner that treats it as a"
                        + " comment reads only half the mod, which is how the kill-effect"
                        + " projection went unseen the first time this gate ran");

        String notCode = """
                class Sample {
                    void go() {
                        log("Math.sin is banned here");
                    }
                }
                """;
        assertTrue(scanSource("Sample.java", notCode).isEmpty(),
                "text inside a string literal is not code");

        String qualified = """
                class Sample {
                    double go() {
                        return net.minecraft.util.Mth.sin(1.0f) + Fastmath.cos(2.0);
                    }
                }
                """;
        assertTrue(scanSource("Sample.java", qualified).isEmpty(),
                "only java.lang.Math and java.lang.StrictMath are this gate's business");
    }

    @Test
    void theScannerWouldHaveCaughtTheSpawnYawBeforeItWasFixed() throws IOException {
        Path host = findUp("jackpot-rollback", "sim-host", "src", "main", "java", "me", "nootnoot",
                "sim", "host");
        assertTrue(host != null);
        String fixed = new String(Files.readAllBytes(host.resolve("SpawnFacing.java")),
                StandardCharsets.UTF_8);
        assertTrue(scanSource("SpawnFacing.java", fixed).isEmpty(),
                "SpawnFacing still calls a platform-dependent Math method: "
                        + scanSource("SpawnFacing.java", fixed));

        String before = fixed.replace(
                "(float) (StrictMath.atan2(-dx, dz) * 180.0 / StrictMath.PI)",
                "(float) Math.toDegrees(Math.atan2(-dx, dz))");
        assertFalse(before.equals(fixed), "the negative control no longer matches the fixed source,"
                + " so it is not testing the shape that was actually there");
        assertEquals(2, scanSource("SpawnFacing.java", before).size(),
                "the shape that was live before this fix has to be caught, or widening the gate"
                        + " changed nothing");
    }

    private static String body(Path file) throws IOException {
        String src = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        String stripped = blankOutNonCode(src);
        int brace = stripped.indexOf('{');
        return stripped.substring(brace < 0 ? 0 : brace).replaceAll("\\s+", " ").trim();
    }

    private static List<Hit> scanEverything() throws IOException {
        List<Hit> out = new ArrayList<>();
        for (List<Path> files : scanTargets().values()) {
            for (Path f : files) {
                out.addAll(scanSource(f.getFileName().toString(),
                        new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
            }
        }
        return out;
    }

    private static final Pattern CALL = Pattern.compile(
            "(?<![A-Za-z0-9_$.])(Math|StrictMath)\\s*\\.\\s*([A-Za-z0-9_]+)\\s*\\(");

    static List<Hit> scanSource(String file, String raw) {
        String src = blankOutNonCode(revealStonecutterBranches(raw));
        List<Hit> out = new ArrayList<>();
        Matcher m = CALL.matcher(src);
        while (m.find()) {
            String owner = m.group(1);
            String method = m.group(2);
            if ("StrictMath".equals(owner) && !"random".equals(method)) {
                continue;
            }
            if (!PLATFORM_DEPENDENT.contains(method)) {
                continue;
            }
            out.add(new Hit(file, lineOf(src, m.start()), owner, method));
        }
        return out;
    }

    private static Map<String, List<Path>> scanTargets() throws IOException {
        Map<String, List<Path>> out = new LinkedHashMap<>();
        for (String module : List.of("sim-core", "sim-host", "edge", "relay")) {
            Path p = findUp("jackpot-rollback", module, "src", "main", "java");
            if (p != null) {
                out.put(module, javaFilesUnder(p));
            }
        }
        Path mod = findUp("pvphq-rollback-mod", "src", "main", "java");
        if (mod != null) {
            out.put("mod", javaFilesUnder(mod));
        }
        Path practice = findUp("mcleagues", "mcleagues-core", "src", "main", "java", "me",
                "nootnoot", "modules", "practice");
        if (practice != null) {
            List<Path> rollbackOnly = new ArrayList<>();
            for (Path f : javaFilesUnder(practice)) {
                if (feedsTheRollbackHandoff(f)) {
                    rollbackOnly.add(f);
                }
            }
            out.put("plugin-rollback", rollbackOnly);
        }
        return out;
    }

    private static boolean feedsTheRollbackHandoff(Path file) {
        String name = file.getFileName().toString();
        Path dir = file.getParent();
        if (dir != null && "edge".equals(dir.getFileName().toString())) {
            return true;
        }
        return name.startsWith("Rollback") || name.startsWith("Arena")
                || "SpawnFacing.java".equals(name) || "MatchSetupCodec.java".equals(name);
    }

    private static Path findUp(String... segments) {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p;
            for (String s : segments) {
                candidate = candidate.resolve(s);
            }
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> javaFilesUnder(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    static String revealStonecutterBranches(String src) {
        StringBuilder sb = new StringBuilder(src);
        int i = 0;
        while ((i = sb.indexOf("/*", i)) >= 0) {
            int end = sb.indexOf("*/", i + 2);
            if (end < 0) {
                break;
            }
            if (directiveFollows(sb, end + 2) || directivePrecedes(sb, i)) {
                sb.setCharAt(i, ' ');
                sb.setCharAt(i + 1, ' ');
                sb.setCharAt(end, ' ');
                sb.setCharAt(end + 1, ' ');
            }
            i = end + 2;
        }
        return sb.toString();
    }

    private static boolean directiveFollows(CharSequence src, int from) {
        int i = from;
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
        return i + 3 <= src.length() && "//?".contentEquals(src.subSequence(i, i + 3));
    }

    private static boolean directivePrecedes(CharSequence src, int open) {
        int i = open - 1;
        while (i >= 0 && Character.isWhitespace(src.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        int lineStart = i;
        while (lineStart > 0 && src.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        while (lineStart <= i && Character.isWhitespace(src.charAt(lineStart))) {
            lineStart++;
        }
        return lineStart + 3 <= src.length()
                && "//?".contentEquals(src.subSequence(lineStart, lineStart + 3));
    }

    private static int lineOf(String src, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (src.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String blankOutNonCode(String src) {
        char[] c = src.toCharArray();
        int i = 0;
        while (i < c.length) {
            char ch = c[i];
            if (ch == '/' && i + 1 < c.length && c[i + 1] == '/') {
                while (i < c.length && c[i] != '\n') {
                    c[i++] = ' ';
                }
            } else if (ch == '/' && i + 1 < c.length && c[i + 1] == '*') {
                c[i++] = ' ';
                c[i++] = ' ';
                while (i < c.length && !(c[i] == '*' && i + 1 < c.length && c[i + 1] == '/')) {
                    if (c[i] != '\n') {
                        c[i] = ' ';
                    }
                    i++;
                }
                if (i < c.length) {
                    c[i++] = ' ';
                }
                if (i < c.length) {
                    c[i++] = ' ';
                }
            } else if (ch == '"' || ch == '\'') {
                char quote = ch;
                c[i++] = ' ';
                while (i < c.length && c[i] != quote) {
                    if (c[i] == '\\' && i + 1 < c.length) {
                        c[i] = ' ';
                        i++;
                    }
                    if (i < c.length && c[i] != '\n') {
                        c[i] = ' ';
                    }
                    i++;
                }
                if (i < c.length) {
                    c[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(c);
    }
}
