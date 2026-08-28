package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EdgeClearAlwaysRestoresTest {

    private static final Path RENDERER = Path.of("src/main/java/me/nootnoot/edge/EdgeRenderer.java");

    private static String clearBody() throws IOException {
        String src = Files.readString(RENDERER, StandardCharsets.UTF_8);
        int start = src.indexOf("public void clear()");
        assertTrue(start >= 0, "EdgeRenderer.clear() was renamed; this guard no longer reads it");
        int depth = 0;
        int i = src.indexOf('{', start);
        int open = i;
        for (; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("EdgeRenderer.clear() has no closing brace");
    }

    @Test
    void theAttributeRestoreRunsEvenWhenAnEarlierTeardownStepThrows() throws IOException {
        String body = clearBody();

        int finallyAt = body.indexOf("} finally {");
        assertTrue(finallyAt >= 0,
                "EdgeRenderer.clear() has no finally block. Every step before status.reset() is a"
                        + " Bukkit call on a player who may already be gone - despawning the"
                        + " opponent entity, clearing the world mirror, removing the cage - and a"
                        + " RuntimeException out of any of them used to skip the attack-speed"
                        + " restore entirely. The player then keeps the duel's shifted"
                        + " ATTACK_SPEED base on every other mode on the server. This is the same"
                        + " shape as the EdgeStatusMirror baseline bug: the restore has to be"
                        + " unconditional, not the last statement of a happy path.");

        int reset = body.indexOf("status.reset()");
        assertTrue(reset > finallyAt,
                "status.reset() is not inside clear()'s finally block, so it is still skippable");
    }

    @Test
    void noTeardownStepCanAbortTheRestOfTheTeardown() throws IOException {
        String body = clearBody();
        int finallyAt = body.indexOf("} finally {");
        String happyPath = body.substring(0, finallyAt);

        for (String bare : new String[] {
                "peer.reset();", "dig.clear();", "projectiles.clear();", "crystals.clear();",
                "drops.clear();", "worldMirror.clear();", "npc.despawn();"}) {
            assertTrue(!happyPath.contains(bare),
                    "clear() still calls " + bare + " unguarded. One throwing step must not stop"
                            + " the steps after it: a half-torn-down renderer leaves the opponent"
                            + " NPC, the dig overlay or the world mirror on a player who is back"
                            + " in the lobby.");
        }
        assertTrue(happyPath.contains("teardownStep("),
                "clear() no longer routes its steps through the guard that keeps one failure from"
                        + " eating the rest of the teardown");
    }
}
