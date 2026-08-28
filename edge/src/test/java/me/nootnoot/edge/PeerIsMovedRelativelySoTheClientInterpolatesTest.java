package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PeerIsMovedRelativelySoTheClientInterpolatesTest {

    private static final Path ENTITY =
            Path.of("src/main/java/me/nootnoot/edge/EdgePlayerEntity.java");

    private static String source() throws IOException {
        return Files.readString(ENTITY, StandardCharsets.UTF_8);
    }

    @Test
    void theOpponentIsMovedWithARelativePacketNotATeleportEveryTick() throws IOException {
        String s = source();
        assertTrue(s.contains("WrapperPlayServerEntityRelativeMoveAndRotation"),
                "a vanilla client SNAPS an entity to a teleport packet and only interpolates a"
                        + " relative move. Sending an absolute teleport every tick shows the"
                        + " opponent stepping at 20Hz with no smoothing between updates, which is"
                        + " what 'very choppy' looks like and is independent of latency");
    }

    @Test
    void aLargeJumpStillFallsBackToATeleport() throws IOException {
        String s = source();
        assertTrue(s.contains("RELATIVE_MOVE_LIMIT"),
                "the relative packet encodes its delta as a short in 1/4096 of a block, so it cannot"
                        + " express a large jump. A pearl or a round reset has to teleport or the"
                        + " opponent would be left behind");
        assertTrue(s.contains("WrapperPlayServerEntityTeleport"),
                "the teleport path must survive for exactly that case");
    }

    @Test
    void thePositionIsPeriodicallyResynced() throws IOException {
        String s = source();
        assertTrue(s.contains("RESYNC_INTERVAL_TICKS"),
                "relative moves accumulate rounding error, so an absolute teleport has to be sent"
                        + " periodically or the opponent drifts away from where the sim says they"
                        + " are, and hit registration starts disagreeing with what is on screen");
    }

    @Test
    void theRelativeBaselineIsSeededOnSpawnAndClearedOnDespawn() throws IOException {
        String s = source();
        int seeded = s.indexOf("sentPosition = true");
        int cleared = s.indexOf("sentPosition = false");
        assertTrue(seeded > 0 && cleared > 0,
                "a relative move is meaningless without a known last position. It has to be seeded"
                        + " where the entity was spawned and cleared when it despawns, or the first"
                        + " move after a respawn is a delta from a stale origin");
        assertFalse(s.contains("private boolean sentPosition = true;"),
                "it must start false so the first move after construction is an absolute teleport");
    }

    @Test
    void anOnGroundFlipDoesNotForceASnapTeleport() throws IOException {
        String s = source();
        int enc = s.indexOf("boolean encodable = sentPosition");
        int end = s.indexOf(";", enc);
        assertFalse(s.substring(enc, end).contains("groundFlipped"),
                "onGround flips on every jump and every step off a block edge. Forcing an absolute"
                        + " teleport there makes the client SNAP the opponent instead of"
                        + " interpolating, so knockback takeoff and landing each became a hard"
                        + " jump - the choppiness this file exists to prevent, fired at any"
                        + " latency including zero");
    }
}
