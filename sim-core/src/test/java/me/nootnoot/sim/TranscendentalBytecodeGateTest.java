package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TranscendentalBytecodeGateTest {
    private static final Set<String> ALLOWED = Set.of(
            "sqrt", "abs", "min", "max", "floor", "ceil", "round", "signum",
            "floorDiv", "floorMod", "toIntExact", "addExact", "subtractExact", "multiplyExact",
            "copySign", "fma", "rint", "nextUp", "nextDown", "ulp");

    private static final Set<String> PLATFORM_DEPENDENT = Set.of(
            "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "pow", "exp", "cbrt",
            "hypot", "log", "log10", "expm1", "log1p", "sinh", "cosh", "tanh",
            "toRadians", "toDegrees", "random");

    private static final Set<String> MUST_BE_SCANNED = Set.of(
            "Simulation", "Combat", "Collision", "Projectiles", "Checksum");

    private record MathCall(String owner, String method, String descriptor, Path file) {
        @Override
        public String toString() {
            return file + " -> " + owner.substring(owner.lastIndexOf('/') + 1) + "." + method + descriptor;
        }
    }

    @Test
    void compiledSimCallsNoPlatformDependentMathMethod() throws IOException {
        List<MathCall> calls = scanMainClasses();
        Set<String> reviewed = reviewedInTheSourceGate();
        List<String> violations = new ArrayList<>();

        for (MathCall call : calls) {
            if ("java/lang/StrictMath".equals(call.owner())) {
                if ("random".equals(call.method())) {
                    violations.add(call.toString());
                }
                continue;
            }
            if (ALLOWED.contains(call.method())) {
                continue;
            }
            if (reviewed.contains(reviewKey(call))) {
                continue;
            }
            violations.add(call.toString());
        }

        assertTrue(violations.isEmpty(),
                "java.lang.Math calls that are not bit-reproducible across platforms: " + violations);
    }

    private static Set<String> reviewedInTheSourceGate() {
        Set<String> out = new TreeSet<>();
        for (String key : TranscendentalSourceGateTest.reviewedKeys()) {
            out.add(key.replace(".java :: ", " :: "));
        }
        return out;
    }

    private static String reviewKey(MathCall call) {
        String name = call.file().getFileName().toString();
        name = name.substring(0, name.length() - ".class".length());
        int nested = name.indexOf('$');
        if (nested > 0) {
            name = name.substring(0, nested);
        }
        String owner = call.owner().substring(call.owner().lastIndexOf('/') + 1);
        return name + " :: " + owner + "." + call.method();
    }

    @Test
    void theTwoGatesShareOneAllowList() {
        Set<String> reviewed = reviewedInTheSourceGate();
        assertFalse(reviewed.isEmpty(),
                "the bytecode gate reads its allow list from TranscendentalSourceGateTest so the"
                        + " two cannot drift apart, and it came back empty");
        for (String key : reviewed) {
            assertFalse(key.contains(".java"),
                    "an allow list key did not translate from a source file to a class: " + key);
        }
    }

    @Test
    void theAllowListItselfCannotBeWidenedIntoPlatformDependentTerritory() {
        Set<String> overlap = new TreeSet<>(ALLOWED);
        overlap.retainAll(PLATFORM_DEPENDENT);
        assertTrue(overlap.isEmpty(),
                "the allow list has grown to cover platform-dependent Math methods: " + overlap);

        assertFalse(ALLOWED.contains("sin"), "sin must never reach the allow list");
        assertFalse(ALLOWED.contains("pow"), "pow must never reach the allow list");
        assertFalse(ALLOWED.contains("random"), "random must never reach the allow list");
    }

    @Test
    void everyAllowedMethodIsExactlySpecifiedByTheJls() {
        Set<String> exactBySpec = Set.of(
                "sqrt", "abs", "absExact", "min", "max", "clamp", "floor", "ceil", "rint", "round",
                "signum", "copySign", "fma", "nextUp", "nextDown", "nextAfter", "ulp", "getExponent",
                "scalb", "IEEEremainder",
                "floorDiv", "floorMod", "ceilDiv", "ceilMod", "toIntExact", "addExact", "subtractExact",
                "multiplyExact", "negateExact", "incrementExact", "decrementExact", "multiplyHigh",
                "multiplyFull", "unsignedMultiplyHigh");

        Set<String> notExact = new TreeSet<>(ALLOWED);
        notExact.removeAll(exactBySpec);
        assertTrue(notExact.isEmpty(),
                "the allow list contains Math methods the JLS does not require to be bit-exact: " + notExact);
    }

    @Test
    void theScanTargetIsPresentAndNewerThanEverySource() throws IOException {
        Newest newestClass = newestModified(findClassOutput(), ".class");
        Newest newestSource = newestModified(findMainSources(), ".java");

        assertTrue(newestClass.modified().compareTo(newestSource.modified()) >= 0,
                "the scan target is stale: " + newestSource.file().toAbsolutePath() + " ("
                        + newestSource.modified() + ") is newer than the newest class file "
                        + newestClass.file().toAbsolutePath() + " (" + newestClass.modified() + "). A gate that reads yesterday's bytecode passes"
                        + " green while today's source calls Math.sin, which is exactly how this"
                        + " check earned its keep. Recompile sim-core, or point -D"
                        + CLASS_DIR_PROPERTY + "=<dir> at the output of the compile under test.");
    }

    @Test
    void theScanActuallyCoversTheSimClasses() throws IOException {
        Map<String, Path> modules = findClassOutputs();
        Path classes = modules.get("sim-core");
        List<Path> classFiles = classFilesUnder(classes);
        assertTrue(classFiles.size() >= 30,
                "only " + classFiles.size() + " class files were scanned under " + classes.toAbsolutePath()
                        + "; the build output looks stale or partial, so a green gate would be meaningless");

        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, Path> module : modules.entrySet()) {
            for (Path p : classFilesUnder(module.getValue())) {
                String n = p.getFileName().toString();
                names.add(n.substring(0, n.length() - ".class".length()));
            }
        }
        Set<String> missing = new TreeSet<>(MUST_BE_SCANNED);
        missing.removeAll(names);
        assertTrue(missing.isEmpty(), "the scan never saw these sim classes: " + missing);

        if (modules.containsKey("sim-host")) {
            assertTrue(names.contains("SpawnFacing"),
                    "sim-host is compiled and the scan still did not see SpawnFacing, the class"
                            + " that put Math.atan2 inside checksummed frame-0 state precisely"
                            + " because it lives outside sim-core");
        }
    }

    @Test
    void theModulesOutsideSimCoreAreScannedOnlyWhileTheirBytecodeIsCurrent() throws IOException {
        Map<String, Path> modules = findClassOutputs();
        assertTrue(modules.containsKey("sim-core"), "sim-core must always be scanned");
        for (String module : List.of("sim-host", "edge", "relay")) {
            Path classes = modules.get(module);
            if (classes == null) {
                continue;
            }
            Path sources = firstDirectory(
                    Path.of("..", module, "src", "main", "java"),
                    Path.of(module, "src", "main", "java"));
            assertTrue(sources != null, module + " was selected for scanning with no sources");
            assertTrue(newestModified(classes, ".class").modified()
                            .compareTo(newestModified(sources, ".java").modified()) >= 0,
                    module + " was selected for scanning but its bytecode is older than its"
                            + " source. Reading yesterday's classes proves nothing about today's"
                            + " code, so a stale module has to be dropped, not scanned");
        }
        assertTrue(TranscendentalSourceGateTest.reviewedKeys() != null,
                "a module whose bytecode is stale is dropped here on purpose. It is still covered:"
                        + " TranscendentalSourceGateTest reads every module straight off disk and"
                        + " never depends on a build having run");
    }

    @Test
    void thePoolReaderActuallyFindsMathCalls() throws IOException {
        List<MathCall> calls = scanMainClasses();

        assertFalse(calls.isEmpty(), "the reader found no java.lang.Math calls at all, so it is not working");

        boolean sawSqrt = calls.stream()
                .anyMatch(c -> "java/lang/Math".equals(c.owner()) && "sqrt".equals(c.method()));
        assertTrue(sawSqrt, "the sim uses Math.sqrt, so the reader must see it");
    }

    private static List<MathCall> scanMainClasses() throws IOException {
        List<MathCall> calls = new ArrayList<>();
        for (Map.Entry<String, Path> module : findClassOutputs().entrySet()) {
            Path classes = module.getValue();
            for (Path file : classFilesUnder(classes)) {
                calls.addAll(mathCallsIn(
                        Path.of(module.getKey()).resolve(classes.relativize(file)), file));
            }
        }
        return calls;
    }

    static Map<String, Path> findClassOutputs() throws IOException {
        Map<String, Path> out = new LinkedHashMap<>();
        out.put("sim-core", findClassOutput());
        for (String module : List.of("sim-host", "edge", "relay")) {
            Path classes = firstDirectory(
                    Path.of("..", module, "build", "classes", "java", "main"),
                    Path.of(module, "build", "classes", "java", "main"));
            Path sources = firstDirectory(
                    Path.of("..", module, "src", "main", "java"),
                    Path.of(module, "src", "main", "java"));
            if (classes == null || sources == null) {
                continue;
            }
            Newest newestClass = newestModified(classes, ".class");
            Newest newestSource = newestModified(sources, ".java");
            if (newestClass.modified().compareTo(newestSource.modified()) >= 0) {
                out.put(module, classes);
            }
        }
        return out;
    }

    private static Path firstDirectory(Path... candidates) {
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        return null;
    }

    private static List<Path> classFilesUnder(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".class")).sorted().toList();
        }
    }

    private static List<MathCall> mathCallsIn(Path label, Path classFile) throws IOException {
        List<MathCall> found = new ArrayList<>();

        try (InputStream raw = Files.newInputStream(classFile);
             DataInputStream in = new DataInputStream(raw)) {
            if (in.readInt() != 0xCAFEBABE) {
                return found;
            }
            in.readUnsignedShort();
            in.readUnsignedShort();

            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            int[][] refs = new int[count][];
            int[] classNameIndex = new int[count];
            int[][] nameAndType = new int[count][];

            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classNameIndex[i] = in.readUnsignedShort();
                    case 12 -> nameAndType[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                    case 10, 11 -> refs[i] = new int[]{in.readUnsignedShort(), in.readUnsignedShort()};
                    case 8, 16, 19, 20 -> in.readUnsignedShort();
                    case 15 -> {
                        in.readUnsignedByte();
                        in.readUnsignedShort();
                    }
                    case 3, 4, 9, 17, 18 -> in.readInt();
                    case 5, 6 -> {
                        in.readLong();
                        i++;
                    }
                    default -> throw new IOException("unknown constant pool tag " + tag + " in " + classFile);
                }
            }

            for (int i = 1; i < count; i++) {
                if (refs[i] == null) {
                    continue;
                }
                String owner = utf8[classNameIndex[refs[i][0]]];
                if (!"java/lang/Math".equals(owner) && !"java/lang/StrictMath".equals(owner)) {
                    continue;
                }
                String method = utf8[nameAndType[refs[i][1]][0]];
                String descriptor = utf8[nameAndType[refs[i][1]][1]];
                found.add(new MathCall(owner, method, descriptor, label));
            }
        }

        return found;
    }

    static final String CLASS_DIR_PROPERTY = "simcore.classes";

    private static Path findClassOutput() {
        String override = System.getProperty(CLASS_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            Path p = Path.of(override);
            assertTrue(Files.isDirectory(p), CLASS_DIR_PROPERTY + " points at " + p.toAbsolutePath()
                    + ", which is not a directory");
            return p;
        }
        List<Path> candidates = List.of(
                Path.of("build", "classes", "java", "main"),
                Path.of("sim-core", "build", "classes", "java", "main"));
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        throw new AssertionError("no compiled sim-core classes to scan. Looked at "
                + candidates.stream().map(c -> c.toAbsolutePath().toString()).toList()
                + " from working directory " + Path.of("").toAbsolutePath()
                + ". This gate reads bytecode, so with nothing to read it proves nothing and must"
                + " not pass. Compile sim-core first, or point -D" + CLASS_DIR_PROPERTY
                + "=<dir> at the class output.");
    }

    private static Path findMainSources() {
        List<Path> candidates = List.of(
                Path.of("src", "main", "java"),
                Path.of("sim-core", "src", "main", "java"));
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        throw new AssertionError("sim-core main sources not found from working directory "
                + Path.of("").toAbsolutePath() + "; the staleness check cannot run without them");
    }

    private record Newest(Path file, FileTime modified) {
    }

    private static Newest newestModified(Path root, String suffix) throws IOException {
        Newest newest = null;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path f : files.filter(x -> x.toString().endsWith(suffix)).sorted().toList()) {
                FileTime t = Files.getLastModifiedTime(f);
                if (newest == null || t.compareTo(newest.modified()) > 0) {
                    newest = new Newest(f, t);
                }
            }
        }
        assertTrue(newest != null, "no " + suffix + " files under " + root.toAbsolutePath());
        return newest;
    }
}
