package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FloatAccumulatedLoopGateTest {
    private static final Set<String> REVIEWED_AND_ALLOWED = Set.of();

    private static final Set<String> KEYWORDS = Set.of(
            "if", "else", "for", "while", "do", "return", "new", "int", "long", "float", "double",
            "boolean", "char", "byte", "short", "void", "true", "false", "null", "this", "super",
            "static", "final", "instanceof", "break", "continue", "switch", "case", "default");

    private static final Pattern LOOP_HEAD = Pattern.compile("(?<![A-Za-z0-9_$])(while|for)\\s*\\(");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern FLOAT_LITERAL = Pattern.compile("[0-9]+\\.[0-9]|[0-9][fFdD](?![A-Za-z0-9_$])");
    private static final Pattern FLOAT_DECL = Pattern.compile("(?<![A-Za-z0-9_$])(float|double)\\s+");

    private record Finding(String file, int line, String reason, String header) {
        @Override
        public String toString() {
            return file + ":" + line + " [" + reason + "] " + header;
        }
    }

    private static String key(Finding f) {
        return f.file() + " :: " + f.header();
    }

    @Test
    void noLoopInTheSimOrItsHostsCountsItsIterationsWithAFloat() throws IOException {
        List<Path> roots = scanRoots();
        assertFalse(roots.isEmpty(), "no source roots were found from working directory "
                + Path.of("").toAbsolutePath() + "; this gate cannot run blind");

        List<Finding> findings = new ArrayList<>();
        for (Path root : roots) {
            findings.addAll(scanTree(root));
        }

        List<Finding> unreviewed = new ArrayList<>();
        for (Finding f : findings) {
            if (!REVIEWED_AND_ALLOWED.contains(key(f))) {
                unreviewed.add(f);
            }
        }

        assertTrue(unreviewed.isEmpty(),
                "these loops decide how many times they run from a floating-point value the loop"
                        + " body itself accumulates. Two peers on different architectures can run a"
                        + " different NUMBER of iterations from identical state, and the harness"
                        + " only runs on one of them. Derive the count as an integer once, or add"
                        + " the loop to REVIEWED_AND_ALLOWED with the reason it is safe: "
                        + unreviewed);
    }

    @Test
    void theScannerActuallyFindsTheShapesThisSweepRemoved() {
        String hunger = """
                class Sample {
                    void tick(P p) {
                        while (p.exhaustion >= 4.0f) {
                            p.exhaustion -= 4.0f;
                        }
                    }
                }
                """;
        assertEquals(1, scanSource("Sample.java", hunger).size(),
                "the exhaustion drain was a while whose trip count came out of a float the body"
                        + " decremented; the scanner has to see that shape or it is decoration");

        String backOff = """
                class Sample {
                    double clip(double dx) {
                        double step = 0.05;
                        while (dx != 0.0 && noSolid(dx)) {
                            dx = (dx < step && dx >= -step) ? 0.0 : (dx > 0.0 ? dx - step : dx + step);
                        }
                        return dx;
                    }
                }
                """;
        assertEquals(1, scanSource("Sample.java", backOff).size(),
                "the sneak edge clip stepped dx toward zero by 0.05 a turn and stopped on a float"
                        + " comparison");

        String rayMarch = """
                class Sample {
                    void march() {
                        for (double t = 0.0; t <= REACH; t += STEP) {
                            probe(t);
                        }
                    }
                }
                """;
        assertEquals(1, scanSource("Sample.java", rayMarch).size(),
                "the edge fluid ray walked a double induction variable to a double limit");

        String unbounded = """
                class Sample {
                    void march(double dx) {
                        for (int i = 0; dx != 0.0; i++) {
                            dx = dx - 0.05;
                        }
                    }
                }
                """;
        assertEquals(1, scanSource("Sample.java", unbounded).size(),
                "an int counter that no condition ever reads is not a bound, so this still counts"
                        + " its trips with a float");
    }

    @Test
    void theScannerDoesNotFlagACountDerivedOnceAsAnInteger() {
        String indexed = """
                class Sample {
                    void march(double dx) {
                        int bound = steps(dx);
                        for (int i = 0; i < bound && dx != 0.0 && noSolid(dx); i++) {
                            dx = stepTowardZero(dx);
                        }
                    }
                }
                """;
        assertTrue(scanSource("Sample.java", indexed).isEmpty(),
                "this is the fixed shape: an integer bound computed once, the float only decides"
                        + " an early exit inside it");

        String probe = """
                class Sample {
                    int count(double step) {
                        int n = 0;
                        while (n <= CAP && n * step <= 1.0) {
                            n++;
                        }
                        return n;
                    }
                }
                """;
        assertTrue(scanSource("Sample.java", probe).isEmpty(),
                "index times step is not an accumulator and the counter is capped");

        String literalsInStrings = """
                class Sample {
                    void go() {
                        while (open) {
                            log("dx != 0.0 && dx -= 0.05");
                        }
                    }
                }
                """;
        assertTrue(scanSource("Sample.java", literalsInStrings).isEmpty(),
                "text inside a string literal is not code");
    }

    @Test
    void theScanReachedEveryModuleItIsSupposedToCover() throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        for (Path root : scanRoots()) {
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java"))
                        .forEach(p -> seen.add(p.getFileName().toString()));
            }
        }
        assertTrue(seen.contains("Simulation.java"), "the sim itself was not scanned: " + seen.size());
        assertTrue(seen.contains("Combat.java"), "Combat was not scanned");
        assertTrue(seen.contains("EdgeInputSource.java"), "the edge frame producer was not scanned");
        if (findUp("pvphq-rollback-mod", "src", "main", "java") != null) {
            assertTrue(seen.contains("McInputSource.java"),
                    "the mod tree is on disk and its frame producer was still not scanned. The mod"
                            + " is a frame producer exactly like the edge, and its fluid ray"
                            + " accumulated a double induction variable for as long as this gate"
                            + " looked only at the four gradle modules");
        }
        assertTrue(seen.size() >= 40, "only " + seen.size() + " sources were scanned; a green gate"
                + " over a partial tree is worth nothing");
    }

    private static List<Path> scanRoots() {
        List<Path> out = new ArrayList<>();
        List<Path> candidates = List.of(
                Path.of("src", "main", "java"),
                Path.of("sim-core", "src", "main", "java"),
                Path.of("..", "edge", "src", "main", "java"),
                Path.of("edge", "src", "main", "java"),
                Path.of("..", "sim-host", "src", "main", "java"),
                Path.of("sim-host", "src", "main", "java"),
                Path.of("..", "relay", "src", "main", "java"),
                Path.of("relay", "src", "main", "java"));
        Set<String> already = new LinkedHashSet<>();
        for (Path c : candidates) {
            if (!Files.isDirectory(c)) {
                continue;
            }
            String real = c.toAbsolutePath().normalize().toString();
            if (already.add(real)) {
                out.add(c);
            }
        }
        Path mod = findUp("pvphq-rollback-mod", "src", "main", "java");
        if (mod != null && already.add(mod.toString())) {
            out.add(mod);
        }
        return out;
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

    private static List<Finding> scanTree(Path root) throws IOException {
        List<Finding> out = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        }
        files.sort(Path::compareTo);
        for (Path p : files) {
            String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            out.addAll(scanSource(p.getFileName().toString(), src));
        }
        return out;
    }

    static List<Finding> scanSource(String file, String raw) {
        String src = blankOutNonCode(TranscendentalSourceGateTest.revealStonecutterBranches(raw));
        List<Finding> out = new ArrayList<>();
        Matcher m = LOOP_HEAD.matcher(src);
        while (m.find()) {
            int open = m.end() - 1;
            int close = matching(src, open, '(', ')');
            if (close < 0) {
                continue;
            }
            String header = src.substring(open + 1, close).trim();
            String body = bodyAfter(src, close + 1);
            int line = lineOf(src, m.start());
            String scope = enclosingScope(src, m.start());
            String reason = "for".equals(m.group(1))
                    ? judgeFor(scope, header, body)
                    : judgeWhile(scope, header, body);
            if (reason != null) {
                out.add(new Finding(file, line, reason, squash(header)));
            }
        }
        return out;
    }

    private static String judgeFor(String scope, String header, String body) {
        List<String> parts = splitTop(header);
        if (parts.size() != 3) {
            return null;
        }
        String init = parts.get(0);
        String cond = parts.get(1);
        String update = parts.get(2);

        Matcher decl = FLOAT_DECL.matcher(init);
        if (decl.find()) {
            Matcher id = IDENT.matcher(init.substring(decl.end()));
            if (id.find()) {
                String v = id.group();
                if (mutates(update, v) || mutates(body, v)) {
                    return "floating induction variable " + v;
                }
            }
        }
        for (String v : identifiers(update)) {
            if (mutates(update, v) && isFloating(scope, v)) {
                return "floating loop update on " + v;
            }
        }
        String counter = intCounter(init);
        for (String v : identifiers(cond)) {
            if (v.equals(counter) || !mutates(body, v)) {
                continue;
            }
            if (!hasFloatLiteral(cond) && !isFloating(scope, v)) {
                continue;
            }
            if (counter == null || !readsCounter(cond, counter)) {
                return "condition rides on " + v + " with no integer bound";
            }
        }
        return null;
    }

    private static String judgeWhile(String scope, String cond, String body) {
        boolean literal = hasFloatLiteral(cond);
        for (String v : identifiers(cond)) {
            if (!mutates(body, v)) {
                continue;
            }
            if (literal || isFloating(scope, v)) {
                return "trip count accumulates into " + v;
            }
        }
        return null;
    }

    private static String intCounter(String init) {
        Matcher m = Pattern.compile("(?<![A-Za-z0-9_$])(int|long)\\s+([A-Za-z_$][A-Za-z0-9_$]*)")
                .matcher(init);
        return m.find() ? m.group(2) : null;
    }

    private static boolean readsCounter(String cond, String counter) {
        return Pattern.compile("(?<![A-Za-z0-9_$])" + Pattern.quote(counter)
                + "\\s*(<|>|<=|>=|!=)").matcher(cond).find();
    }

    private static boolean hasFloatLiteral(String text) {
        return FLOAT_LITERAL.matcher(text).find();
    }

    private static boolean isFloating(String scope, String ident) {
        return Pattern.compile("(?<![A-Za-z0-9_$])(float|double)\\s+(final\\s+)?"
                + Pattern.quote(ident) + "(?![A-Za-z0-9_$])").matcher(scope).find();
    }

    private static boolean mutates(String body, String ident) {
        String q = Pattern.quote(ident);
        if (Pattern.compile("(?<![A-Za-z0-9_$])" + q + "\\s*(\\+=|-=|\\*=|/=)").matcher(body).find()) {
            return true;
        }
        return Pattern.compile("(?<![=!<>+\\-*/%&|^])(?<![A-Za-z0-9_$])" + q + "\\s*=(?!=)")
                .matcher(body).find();
    }

    private static Set<String> identifiers(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = IDENT.matcher(text);
        while (m.find()) {
            String v = m.group();
            if (!KEYWORDS.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    private static List<String> splitTop(String header) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == ';' && depth == 0) {
                out.add(header.substring(start, i));
                start = i + 1;
            }
        }
        out.add(header.substring(start));
        return out;
    }

    private static String bodyAfter(String src, int from) {
        int i = from;
        while (i < src.length() && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
        if (i >= src.length()) {
            return "";
        }
        if (src.charAt(i) == '{') {
            int end = matching(src, i, '{', '}');
            return end < 0 ? src.substring(i) : src.substring(i + 1, end);
        }
        int end = src.indexOf(';', i);
        return end < 0 ? src.substring(i) : src.substring(i, end);
    }

    private static int matching(String src, int open, char in, char out) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == in) {
                depth++;
            } else if (c == out) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String enclosingScope(String src, int loopAt) {
        List<Integer> opens = new ArrayList<>();
        for (int i = 0; i < loopAt; i++) {
            char c = src.charAt(i);
            if (c == '{') {
                opens.add(i);
            } else if (c == '}' && !opens.isEmpty()) {
                opens.remove(opens.size() - 1);
            }
        }
        if (opens.size() < 2) {
            return src.substring(0, loopAt);
        }
        int methodBody = opens.get(1);
        int sigStart = 0;
        for (int i = methodBody - 1; i >= 0; i--) {
            char c = src.charAt(i);
            if (c == ';' || c == '}' || c == '{') {
                sigStart = i + 1;
                break;
            }
        }
        return src.substring(sigStart, loopAt);
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

    private static String squash(String header) {
        return header.replaceAll("\\s+", " ").trim();
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
                    if (c[i] != '\n') {
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
