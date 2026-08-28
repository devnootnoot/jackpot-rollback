package me.nootnoot.sim.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarnessOutputPathTest {
    @Test
    void anExplicitOutDirWins(@TempDir Path dir) {
        assertEquals(dir.toAbsolutePath().normalize(), HarnessMain.resolveOutDir(dir));
    }

    @Test
    void theResolvedPathIsAlwaysAbsolute() {
        assertTrue(HarnessMain.resolveOutDir(Path.of("build", "harness")).isAbsolute());
        assertTrue(HarnessMain.resolveOutDir(null).isAbsolute(),
                "CI uploads sim-core/build/harness by path, and a relative default meant the stream"
                        + " landed wherever the process happened to be started from");
    }

    @Test
    void theDefaultIsDerivedFromWhereTheseClassesLiveNotFromTheWorkingDirectory() {
        Path fromCode = HarnessMain.outDirBesideTheseClasses();
        assertNotNull(fromCode, "the harness classes are on a normal file-system classpath in this"
                + " build, so the fallback that guesses from user.dir must not be the one in use");
        assertEquals(fromCode, HarnessMain.resolveOutDir(null),
                "with no --out and no jackpot.harness.out property the stream has to land beside"
                        + " the compiled classes, so it is in the same place whether the harness is"
                        + " started by Gradle, by an IDE run configuration or by java -cp");
        assertEquals("harness", fromCode.getFileName().toString());
    }

    @Test
    void theSystemPropertyOverridesTheDerivedDefault(@TempDir Path dir) {
        String previous = System.getProperty(HarnessMain.OUT_DIR_PROPERTY);
        System.setProperty(HarnessMain.OUT_DIR_PROPERTY, dir.toString());
        try {
            assertEquals(dir.toAbsolutePath().normalize(), HarnessMain.resolveOutDir(null));
        } finally {
            if (previous == null) {
                System.clearProperty(HarnessMain.OUT_DIR_PROPERTY);
            } else {
                System.setProperty(HarnessMain.OUT_DIR_PROPERTY, previous);
            }
        }
    }
}
