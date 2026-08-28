package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class GreenBuildMeansTheGatesRanTest {

    private static final String[] MODULES = {"sim-core", "sim-host", "edge", "relay", "limbo"};

    private static final Pattern BARE_FILE = Pattern.compile("\"([A-Za-z0-9_-]+\\.[A-Za-z0-9]+)\"");

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

    private static String testTaskBlock() throws IOException {
        String build = rootBuildScript();
        int at = build.indexOf("tasks.withType(Test).configureEach");
        assertTrue(at > 0, "the Test task policy has to hang off tasks.withType(Test) so it reaches"
                + " every module, including modules added later");
        int end = build.indexOf("tasks.matching { it.name == 'check' }", at);
        assertTrue(end > at, "the check-task wiring is supposed to follow the Test block; if it"
                + " moved, move this scan with it rather than widening it to the whole file");
        return build.substring(at, end);
    }

    @Test
    void noTestResultInThisRepositoryIsEverRestoredFromTheBuildCache() throws IOException {
        String block = testTaskBlock();

        assertTrue(block.contains("outputs.cacheIf("),
                "org.gradle.caching=true means a warm `gradlew clean build` restores test results"
                        + " and executes no test at all, and prints BUILD SUCCESSFUL either way."
                        + " Compilation and jars are pure functions of their declared inputs and"
                        + " may be restored; a test task here is not one, because these tests read"
                        + " the mod tree, the mcleagues-core tree, this build's own scripts, a"
                        + " committed golden digest and multi-megabyte capture blobs, and several"
                        + " of them assert things about the state of the build tree itself. Declare"
                        + " every Test task non-cacheable");

        assertTrue(block.contains("outputs.upToDateWhen { false }"),
                "cacheIf and upToDateWhen are separate gates and BOTH have to be shut. A task that"
                        + " is merely not up to date still gets a cache lookup, and a task that is"
                        + " merely not cacheable is still skipped as UP-TO-DATE. Either one alone"
                        + " leaves a green build that ran nothing");
    }

    @Test
    void everyGateStampsTheInvocationItActuallyRanIn() throws IOException {
        String build = rootBuildScript();
        String block = testTaskBlock();

        int whenReady = build.indexOf("gradle.taskGraph.whenReady");
        assertTrue(whenReady > 0, "the gate ledger is set up from taskGraph.whenReady, which is the"
                + " one hook that runs once per build and runs before any task does");
        int cleared = build.indexOf("gateEvidenceDir.listFiles()", whenReady);
        int collects = build.indexOf("graph.allTasks", whenReady);
        assertTrue(cleared > whenReady && collects > cleared,
                "a stamp left on disk by the run BEFORE this one is the same lie the build cache"
                        + " told, so the evidence directory has to be emptied once per build,"
                        + " before any gate can run. This used to be done by writing a random"
                        + " per-invocation id into each stamp and comparing against it, and that"
                        + " was replaced because a single build was observed writing more than one"
                        + " id - which reported gates that had genuinely run as silent. Clearing"
                        + " the directory at graph-ready cannot fail that way: presence IS the"
                        + " evidence, and nothing that survives from a previous build is present");
        assertTrue(build.contains("ext.recordGateRan"),
                "gates need one shared way to record that they ran");

        assertTrue(block.contains("doFirst") && block.contains("afterSuite"),
                "a Test task has to stamp from doFirst, so a task that executed and then FAILED is"
                        + " still counted as having run, and again from afterSuite so the stamp"
                        + " carries the real test count. Neither runs when the result is restored"
                        + " or skipped, which is exactly the discrimination this needs");
        assertTrue(block.contains("finalizedBy verifyGatesRan"),
                "and every Test task has to be finalized by the check, so it runs even when the"
                        + " tests fail and even when only one module's test task was asked for");
        assertTrue(block.contains("mustRunAfter testTask"),
                "finalizedBy alone does not ORDER the check after every task it finalizes. Without"
                        + " a mustRunAfter, gradle is free to schedule it as soon as one finalized"
                        + " task completes, and it did: a clean build ran :sim-host:check, fired"
                        + " the check, and failed naming :edge:test and :limbo:test as silent while"
                        + " they were still waiting their turn. That is a false RED on a green"
                        + " tree, and it costs exactly as much trust as a false green");

        int verify = build.indexOf("tasks.register('verifyGatesRan')");
        assertTrue(verify > 0, "verifyGatesRan is what turns the stamps into a verdict");
        String task = build.substring(verify);
        assertTrue(task.contains("DID NOT RUN") && task.contains("throw new GradleException"),
                "printing which gates were silent and then passing is the same failure this whole"
                        + " check exists to remove. A silent gate has to fail the build");
        assertTrue(task.contains("what this build actually verified"),
                "a human reads BUILD SUCCESSFUL and cannot tell whether anything ran, so the build"
                        + " has to say so itself, per gate, in plain words");
        assertTrue(task.contains("gateTasks.isEmpty()"),
                "verifyGatesRan run with no gate in the graph proves nothing and must not pass"
                        + " quietly - that is how a green run with an empty task list happens");

        assertTrue(build.contains("tasks.matching { it.name == 'check' }")
                        && build.indexOf("finalizedBy verifyGatesRan",
                                build.indexOf("tasks.matching { it.name == 'check' }")) > 0,
                "check has to be finalized by it too, so `gradlew build` is covered without anyone"
                        + " remembering to add a task name to the command line");
    }

    @Test
    void theTwoAlwaysRunDeterminismGatesAreOnTheLedgerToo() throws IOException {
        Path root = rollbackRoot();

        String jarcheck = read(root.resolve("jarcheck.gradle"));
        assertTrue(jarcheck.contains("alwaysRunGates.add(':verifyEmbeddedSimVersion')")
                        && jarcheck.contains("recordGateRan"),
                "verifyEmbeddedSimVersion is the check that stops a jar with a stale sim-core"
                        + " shipping. It registers itself on the ledger next to its own definition,"
                        + " so it cannot be added to this build and forgotten by the verdict");

        String simCore = read(root.resolve("sim-core/build.gradle"));
        assertTrue(simCore.contains("alwaysRunGates.add(':sim-core:checkHarnessDigest')")
                        && simCore.contains("recordGateRan"),
                "checkHarnessDigest is the determinism gate - the one result in this repository"
                        + " that a restored green would hide completely");
    }

    @Test
    void theCacheStaysOnForEverythingThatIsAPureFunctionOfItsInputs() throws IOException {
        String properties = read(rollbackRoot().resolve("gradle.properties"));
        assertTrue(properties.contains("org.gradle.caching=true"),
                "the fix is not to switch the build cache off. Compilation, jars and resource"
                        + " processing really are keyed honestly and restoring them is free speed."
                        + " Only the test tasks are declared non-restorable, and only because their"
                        + " results are not a function of any input set Gradle can key on");
    }

    @Test
    void everyLooseFileAModuleTestReadsIsDeclaredOnThatModulesTestTask() throws IOException {
        Path root = rollbackRoot();
        for (String module : MODULES) {
            Path moduleDir = root.resolve(module);
            Path testDir = moduleDir.resolve("src/test/java");
            if (!Files.isDirectory(testDir)) {
                continue;
            }
            Set<String> loose = new LinkedHashSet<>();
            try (Stream<Path> sources = Files.walk(testDir)) {
                for (Path source : sources.filter(
                        p -> p.getFileName().toString().endsWith(".java")).toList()) {
                    Matcher m = BARE_FILE.matcher(read(source));
                    while (m.find()) {
                        String name = m.group(1);
                        if (name.endsWith(".gradle") || name.endsWith(".kts")
                                || name.endsWith(".md")) {
                            continue;
                        }
                        if (Files.isRegularFile(moduleDir.resolve(name))) {
                            loose.add(name);
                        }
                    }
                }
            }
            if (loose.isEmpty()) {
                continue;
            }
            Path script = moduleDir.resolve("build.gradle");
            assertTrue(Files.isRegularFile(script),
                    module + " has tests reading " + loose + " out of its project directory and no"
                            + " build script to declare them on");
            String declared = read(script);
            for (String name : loose) {
                assertTrue(declared.contains(name),
                        "a test in " + module + " reads " + name + " straight out of the project"
                                + " directory. It is not source, it is not a resource, and nothing"
                                + " on that test task's input list mentions it, so Gradle keys the"
                                + " cached result on a set of files that does not include the one"
                                + " the test is actually asserting about. Editing " + name
                                + " changes no key: the result stays valid against a file that no"
                                + " longer says what the test claims. Declare it with"
                                + " inputs.file in " + module + "/build.gradle");
            }
        }
    }

    @Test
    void theLimboCaptureBlobsAreOnTheKeyEvenThoughNoTestNamesThem() throws IOException {
        String script = read(rollbackRoot().resolve("limbo/build.gradle"));
        assertTrue(script.contains("limboCaptureFiles") && script.contains("*.bin"),
                "LimboCaptureContractTest reads limbo.properties, and limbo.properties names the"
                        + " capture .bin files it then decodes chunk by chunk. No test source"
                        + " contains the string config-26.2.bin, so no scan of the test sources can"
                        + " find them - they have to be declared on purpose. Re-record a capture"
                        + " and every one of those assertions would otherwise stay green against"
                        + " the bytes of the capture before it");
    }
}
