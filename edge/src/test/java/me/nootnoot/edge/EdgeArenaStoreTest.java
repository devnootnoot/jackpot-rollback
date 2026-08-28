package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.UUID;
import java.util.logging.Logger;
import me.nootnoot.sim.ArenaCodec;
import org.junit.jupiter.api.Test;

class EdgeArenaStoreTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final double GROUND_Y = 64.0;
    private static final String KEY_PREFIX = "rollback:edge:arena:";

    private static byte[] arenaBlob(int floorSize) {
        String[] palette = {"minecraft:air", "minecraft:stone"};
        ArenaCodec.PaletteEntry[] geometry = {
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_SKIP, 0f, 0, 0, new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 6f, 0, 0, new double[0][])};
        int[][] blocks = new int[floorSize * floorSize][4];
        int i = 0;
        for (int dx = 0; dx < floorSize; dx++) {
            for (int dz = 0; dz < floorSize; dz++) {
                blocks[i++] = new int[]{dx, 0, dz, 1};
            }
        }
        return ArenaCodec.encode(new ArenaCodec.Snapshot(0L, 0, 63, 0, floorSize, 4, floorSize,
                palette, blocks, geometry, GROUND_Y));
    }

    private static String assignment(String key, String sha, int bytes) {
        String arena = sha == null
                ? "\"arena\":{\"name\":\"aztec\",\"groundY\":" + GROUND_Y + "},"
                : "\"arena\":{\"name\":\"aztec\",\"groundY\":" + GROUND_Y + ",\"key\":\"" + key
                        + "\",\"sha256\":\"" + sha + "\",\"bytes\":" + bytes + "},";
        return "{\"v\":1,\"sessionId\":7,\"slot\":0,"
                + "\"token\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\","
                + "\"relay\":{\"host\":\"127.0.0.1\",\"port\":7777},"
                + "\"player\":{\"uuid\":\"" + A + "\",\"name\":\"playerA\"},"
                + "\"opponent\":{\"uuid\":\"" + B + "\",\"name\":\"playerB\"},"
                + arena
                + "\"spawns\":{\"x0\":10.5,\"y0\":64.0,\"z0\":-3.5,"
                + "\"x1\":-9.5,\"y1\":65.0,\"z1\":4.5,"
                + "\"yaw0\":-135.0,\"pitch0\":2.0,\"yaw1\":45.0,\"pitch1\":-2.0},"
                + "\"rounds\":3}";
    }

    private static EdgeArenaStore store() {
        return new EdgeArenaStore(Logger.getLogger("edge-arena-store-test"));
    }

    @Test
    void anAssignmentWithoutArenaBytesResolvesToTheLocalFallback() {
        EdgeAssignment a = EdgeAssignment.parse(assignment("", null, 0));
        assertNotNull(a);
        EdgeArenaStore.Result result = store().resolve(a);
        assertEquals(EdgeArenaStore.State.RESOLVED, result.state());
        assertNull(result.arena(),
                "no shared bytes must mean 'use whatever this edge loaded locally', not a refusal");
    }

    @Test
    void theSharedBytesBecomeTheMatchArena() {
        byte[] blob = arenaBlob(8);
        String sha = EdgeArenaStore.sha256(blob);
        EdgeAssignment a = EdgeAssignment.parse(assignment(KEY_PREFIX + sha, sha, blob.length));
        assertNotNull(a);
        assertTrue(a.hasArenaBlob());

        EdgeArenaStore store = store();
        assertEquals(EdgeArenaStore.State.PENDING, store.resolve(a).state());
        store.prefetch(key -> KEY_PREFIX.concat(sha).equals(key) ? blob : null, a);
        EdgeArenaStore.Result result = store.resolve(a);
        assertEquals(EdgeArenaStore.State.RESOLVED, result.state());
        assertNotNull(result.arena());
        assertEquals(GROUND_Y, result.arena().groundY());
        assertTrue(result.arena().simSolid(3, 63, 3), "the shipped floor must be solid in the sim");
    }

    @Test
    void bothEdgesBuildTheSameArenaFromTheSameBytes() {
        byte[] blob = arenaBlob(8);
        String sha = EdgeArenaStore.sha256(blob);
        EdgeAssignment a = EdgeAssignment.parse(assignment(KEY_PREFIX + sha, sha, blob.length));
        EdgeArenaStore one = store();
        EdgeArenaStore two = store();
        one.prefetch(key -> blob, a);
        two.prefetch(key -> blob.clone(), a);
        assertEquals(one.resolve(a).arena().hash(), two.resolve(a).arena().hash(),
                "the arena agreement handshake compares this hash - identical bytes must give"
                        + " identical hashes or no brokered match can ever start");
    }

    @Test
    void tamperedBytesAreRefusedInsteadOfSilentlyUsed() {
        byte[] blob = arenaBlob(8);
        String sha = EdgeArenaStore.sha256(blob);
        EdgeAssignment a = EdgeAssignment.parse(assignment(KEY_PREFIX + sha, sha, blob.length));
        byte[] tampered = blob.clone();
        tampered[tampered.length - 1] ^= 0x7F;

        EdgeArenaStore store = store();
        store.prefetch(key -> tampered, a);
        EdgeArenaStore.Result result = store.resolve(a);
        assertEquals(EdgeArenaStore.State.FAILED, result.state());
        assertTrue(result.reason().contains("sha256"));
    }

    @Test
    void aMissingKeyStaysPendingSoTheNextPollRetries() {
        byte[] blob = arenaBlob(4);
        String sha = EdgeArenaStore.sha256(blob);
        EdgeAssignment a = EdgeAssignment.parse(assignment(KEY_PREFIX + sha, sha, blob.length));
        EdgeArenaStore store = store();
        store.prefetch(key -> null, a);
        assertEquals(EdgeArenaStore.State.PENDING, store.resolve(a).state());
        store.prefetch(key -> blob, a);
        assertEquals(EdgeArenaStore.State.RESOLVED, store.resolve(a).state());
    }

    @Test
    void realSpawnsAndAnglesSurviveTheAssignment() {
        EdgeAssignment a = EdgeAssignment.parse(assignment("", null, 0));
        assertNotNull(a);
        assertEquals(10.5, a.spawnX(0));
        assertEquals(64.0, a.spawnY(0));
        assertEquals(-3.5, a.spawnZ(0));
        assertEquals(-9.5, a.spawnX(1));
        assertEquals(65.0, a.spawnY(1));
        assertEquals(4.5, a.spawnZ(1));
        assertEquals(-135f, a.spawnYaw(0));
        assertEquals(2f, a.spawnPitch(0));
        assertEquals(45f, a.spawnYaw(1));
        assertEquals(-2f, a.spawnPitch(1));
    }
}
