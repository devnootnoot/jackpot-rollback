package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CachedTestsDeclareWhatTheyFenceTest {

    private static final Pattern QUOTED_BUILD_FILE =
            Pattern.compile("\"([A-Za-z0-9._/-]+\\.(?:gradle|kts|md))\"");

    private static final String[] MODULES = {"sim-core", "sim-host", "edge", "relay", "limbo"};

    private static Path rollbackRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("devenv.gradle"))
                    && Files.isDirectory(p.resolve("sim-core"))) {
                return p;
            }
            p = p.getParent();
        }
        return Path.of("nowhere");
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static String rootBuildScript() throws IOException {
        return read(rollbackRoot().resolve("build.gradle"));
    }

    private static Stream<Path> testSources() throws IOException {
        Path root = rollbackRoot();
        Stream<Path> all = Stream.empty();
        for (String module : MODULES) {
            Path dir = root.resolve(module).resolve("src/test/java");
            if (!Files.isDirectory(dir)) {
                continue;
            }
            all = Stream.concat(all, Files.walk(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".java")));
        }
        return all;
    }

    @Test
    void theBuildCacheStaysOnAndTheTestTasksCarryTheFilesTheirFencesRead() throws IOException {
        String properties = read(rollbackRoot().resolve("gradle.properties"));
        assertTrue(properties.contains("org.gradle.caching=true"),
                "turning the cache off is not the fix. A cacheable task with a wrong key is a"
                        + " declaration bug, and switching caching off only hides it: the same"
                        + " tests would still be reading files nothing in this build tracks, so"
                        + " nothing would re-run them on a remote cache, on CI, or the day"
                        + " somebody turns the flag back on");

        String build = rootBuildScript();
        int at = build.indexOf("tasks.withType(Test).configureEach");
        assertTrue(at > 0, "the input declaration has to hang off tasks.withType(Test) so it"
                + " reaches every module's test task, including modules added later");
        String block = build.substring(at);
        for (String property : new String[] {"fencedRepoBuildScripts", "fencedSiblingModuleSources",
                "fencedRollbackModSources", "fencedMcleaguesCoreSources"}) {
            assertTrue(block.contains(property),
                    "every Test task has to declare " + property + ". Gradle keys a cached test"
                            + " result on the task's declared inputs only, so a fence that reads a"
                            + " file outside them is green for as long as its own module happens"
                            + " not to change, red the next time it does for an unrelated reason,"
                            + " and green again on revert");
        }
    }

    @Test
    void everyRepoBuildFileATestReadsIsInTheCacheKey() throws IOException {
        Path root = rollbackRoot();
        Set<String> read = new TreeSet<>();
        try (Stream<Path> sources = testSources()) {
            for (Path source : sources.toList()) {
                Matcher m = QUOTED_BUILD_FILE.matcher(read(source));
                while (m.find()) {
                    String name = m.group(1);
                    if (Files.isRegularFile(root.resolve(name))) {
                        read.add(name);
                    }
                }
            }
        }
        assertTrue(read.contains("devenv.gradle") && read.contains("jarcheck.gradle")
                        && read.contains("limbo/build.gradle"),
                "this test is only meaningful while the build fences still read the repo's own"
                        + " scripts, and it just found none of them - check the scan, not the"
                        + " build. A module-scoped path like limbo/build.gradle has to be found"
                        + " too: it is a build script a test asserts about, and for a long time"
                        + " the scan only looked at bare filenames and could not see one");

        String build = rootBuildScript();
        int declaration = build.indexOf("def fenceScripts");
        assertTrue(declaration >= 0, "fenceScripts is where repo-level build files enter the key");
        String list = build.substring(declaration, build.indexOf(')', declaration));
        for (String name : read) {
            assertTrue(list.contains("'" + name + "'"),
                    "a test in this repo reads " + name + " and no test task declares it, so"
                            + " editing " + name + " changes no cache key and every cached test"
                            + " result stays valid against a file that no longer says what they"
                            + " assert. Add it to fenceScripts in build.gradle");
        }
    }

    @Test
    void theSiblingTreesTheFencesReadAreInTheCacheKeyToo() throws IOException {
        Set<String> mentioned = new TreeSet<>();
        try (Stream<Path> sources = testSources()) {
            for (Path source : sources.toList()) {
                String text = read(source);
                if (text.contains("pvphq-rollback-mod")) {
                    mentioned.add("pvphq-rollback-mod");
                }
                if (text.contains("mcleagues-core")) {
                    mentioned.add("mcleagues-core");
                }
            }
        }

        String build = rootBuildScript();
        for (String tree : mentioned) {
            assertTrue(build.contains(tree),
                    "tests in this repo read " + tree + ", which is outside this build entirely."
                            + " Nothing in a test task's key mentions it, so a change over there"
                            + " leaves every parity gate here reporting on the tree as it was the"
                            + " last time THIS repo changed. Declare it in build.gradle");
        }

        for (String module : MODULES) {
            assertTrue(build.contains("'" + module + "'"),
                    module + " has to be in fenceModuleSources: the parity gates in sim-core read"
                            + " edge and mod sources, and sim-core's test task does not otherwise"
                            + " depend on either");
        }
    }
}
