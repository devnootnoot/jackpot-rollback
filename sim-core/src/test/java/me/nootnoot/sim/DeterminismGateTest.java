package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import me.nootnoot.sim.harness.DeterminismHarness;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class DeterminismGateTest {
    @Test
    void twoInstancesConvergeBitForBit() {
        InputLog log = InputLog.generated(0xC0FFEEL, 2000);
        Arena arena = Arena.flat(64.0);
        DeterminismHarness.Result r = DeterminismHarness.run(
                log, HarnessScenarios.duel(arena), HarnessScenarios.duel(arena), arena);
        assertTrue(r.converged(), "instances diverged at tick " + r.divergedAtTick());
    }

    @Test
    void rollbackReproducesExactly() {
        Arena arena = Arena.flat(64.0);
        InputLog log = InputLog.generated(0x1234L, 500);

        GameState full = HarnessScenarios.duel(arena);
        List<Long> fullStream = new ArrayList<>();
        for (Input[] f : log.frames) {
            Simulation.tick(full, arena, f[0], f[1]);
            fullStream.add(Checksum.of(full));
        }

        final int saveAfter = 200;
        GameState live = HarnessScenarios.duel(arena);
        StateRingBuffer ring = new StateRingBuffer(64);
        for (int t = 0; t < saveAfter; t++) {
            Input[] f = log.frames.get(t);
            Simulation.tick(live, arena, f[0], f[1]);
            ring.save(live);
        }
        int snapTick = live.tick;
        for (int t = saveAfter; t < log.frames.size(); t++) {
            Input[] f = log.frames.get(t);
            Simulation.tick(live, arena, f[0], f[1]);
        }

        GameState restored = ring.load(snapTick);
        assertNotNull(restored, "snapshot for tick " + snapTick + " was not retained");

        List<Long> reStream = new ArrayList<>();
        for (int t = saveAfter; t < log.frames.size(); t++) {
            Input[] f = log.frames.get(t);
            Simulation.tick(restored, arena, f[0], f[1]);
            reStream.add(Checksum.of(restored));
        }

        assertEquals(fullStream.subList(saveAfter, fullStream.size()), reStream,
                "rollback re-sim did not reproduce the original path");
    }

    @Test
    void simRuntimeUsesNoForbiddenTranscendentals() throws IOException {
        Path src = Path.of("src", "main", "java", "me", "nootnoot", "sim");
        List<String> forbidden = List.of(
                "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "pow", "exp", "cbrt",
                "hypot", "log", "log10", "expm1", "log1p", "sinh", "cosh", "tanh",
                "toRadians", "toDegrees");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(src)) {
            List<Path> javaFiles = files.filter(f -> f.toString().endsWith(".java")).toList();
            for (Path p : javaFiles) {
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    for (String fn : forbidden) {
                        String needle = "Math." + fn + "(";
                        if (line.contains(needle) && !line.contains("StrictMath." + fn + "(")) {
                            violations.add(p.getFileName() + ":" + (i + 1) + " -> Math." + fn + "(");
                        }
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), "forbidden transcendental Math.* in sim runtime: " + violations);
    }
}
