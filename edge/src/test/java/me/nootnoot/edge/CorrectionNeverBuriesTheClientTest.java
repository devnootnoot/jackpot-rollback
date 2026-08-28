package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CorrectionNeverBuriesTheClientTest {

    private static final Path SRC =
            Path.of("src/main/java/me/nootnoot/edge/EdgeInputSource.java");

    private static String source() throws IOException {
        return Files.readString(SRC, StandardCharsets.UTF_8);
    }

    @Test
    void theCorrectionTargetIsCollisionCheckedBeforeTeleporting() throws IOException {
        String s = source();
        int correct = s.indexOf("private void correct(");
        assertTrue(correct > 0, "correct() must still exist");
        String body = s.substring(correct, Math.min(s.length(), correct + 900));
        assertTrue(body.contains("resolveStandingY"),
                "EdgeMovementValidator.accepted is a raw accumulator of clamped deltas that is"
                        + " never tested against a block, and a correction only fires once it is"
                        + " over two blocks from the client, so by construction the destination can"
                        + " be inside terrain. Bukkit teleport writes the position with no"
                        + " collision resolution on either the server or the client, and a vanilla"
                        + " client cannot climb out of a floor. The destination has to be resolved"
                        + " before it is used");
    }

    @Test
    void aCorrectionThatCannotBeResolvedIsRefusedRatherThanApplied() throws IOException {
        String s = source();
        assertTrue(s.contains("movementCorrectionRefused"),
                "if no standable point exists near the accepted position the correction must be"
                        + " dropped and the accumulator reseeded. Teleporting anyway is what"
                        + " buried the player");
    }

    @Test
    void theLiveClientIsNeverTeleportedOnACollisionTestAlone() throws IOException {
        String s = source();
        assertTrue(!s.contains("private void unstick("),
                "a vanilla client reports a Y a fraction BELOW the surface constantly while walking"
                        + " and landing, and a strict AABB overlap counts that as buried. Ejecting"
                        + " on that test teleports the player during ordinary movement, which is"
                        + " far worse than the rare stuck case it was meant to solve");
    }
}
