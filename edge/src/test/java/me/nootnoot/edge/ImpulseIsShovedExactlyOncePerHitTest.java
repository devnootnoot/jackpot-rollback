package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ImpulseIsShovedExactlyOncePerHitTest {

    private static String source() throws IOException {
        return Files.readString(Path.of("src/main/java/me/nootnoot/edge/EdgeRenderer.java"),
                StandardCharsets.UTF_8);
    }

    @Test
    void theShoveIsGatedOnTheSequenceMovingFORWARD() throws IOException {
        String s = source();
        assertTrue(s.contains("mine.impulseSeq - highestImpulseSeq > 0"),
                "a rollback rewinds impulseSeq and resimulation re-increments it, so a NOT-EQUAL"
                        + " test fires again for an impulse the client was already shoved by."
                        + " Every rollback during a knockback re-sent setVelocity, so one hit"
                        + " shoved the player two or three times - flying upward, jumping far too"
                        + " high, and hanging in the sky. It has to be strictly monotonic");
        assertFalse(s.contains("mine.impulseSeq != lastImpulseSeq"),
                "the not-equal form must not come back");
    }

    @Test
    void theComparisonIsWrapSafe() throws IOException {
        String s = source();
        assertFalse(s.contains("mine.impulseSeq > highestImpulseSeq"),
                "the counter is an int that increments for the whole match, so the comparison is"
                        + " written as a signed difference rather than a bare greater-than");
    }
}
