package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.UUID;
import me.nootnoot.edge.tools.DevAssignMain;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class EdgeSpawnFacingTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static final double COLOSSEUM_X0 = 30.51;
    private static final double COLOSSEUM_Z0 = 0.45;
    private static final float COLOSSEUM_YAW0 = 89.98f;
    private static final double COLOSSEUM_X1 = -29.46;
    private static final double COLOSSEUM_Z1 = 0.53;
    private static final float COLOSSEUM_YAW1 = -90.01f;

    private static double facingDot(float yaw, double fromX, double fromZ, double toX, double toZ) {
        Vec3 look = Combat.lookVector(yaw, 0f);
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        return (look.x() * dx + look.z() * dz) / len;
    }

    private static float yawGap(float a, float b) {
        return Math.abs(Input.wrapYaw(a - b));
    }

    private static String assignmentWithoutSpawnYaws() {
        return "{\"v\":1,\"sessionId\":7,\"slot\":0,"
                + "\"token\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\","
                + "\"relay\":{\"host\":\"127.0.0.1\",\"port\":7777},"
                + "\"player\":{\"uuid\":\"" + A + "\",\"name\":\"playerA\"},"
                + "\"opponent\":{\"uuid\":\"" + B + "\",\"name\":\"playerB\"},"
                + "\"arena\":{\"name\":\"dev\",\"groundY\":-60.0},"
                + "\"spawns\":{\"x0\":-4.0,\"y0\":-60.0,\"z0\":0.0,"
                + "\"x1\":4.0,\"y1\":-60.0,\"z1\":0.0},"
                + "\"rounds\":1}";
    }

    private static String assignmentWithSpawnYaws(float yaw0, float yaw1) {
        return "{\"v\":1,\"sessionId\":7,\"slot\":0,"
                + "\"token\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\","
                + "\"relay\":{\"host\":\"127.0.0.1\",\"port\":7777},"
                + "\"player\":{\"uuid\":\"" + A + "\",\"name\":\"playerA\"},"
                + "\"opponent\":{\"uuid\":\"" + B + "\",\"name\":\"playerB\"},"
                + "\"arena\":{\"name\":\"colosseum\",\"groundY\":32.0},"
                + "\"spawns\":{\"x0\":" + COLOSSEUM_X0 + ",\"y0\":32.0,\"z0\":" + COLOSSEUM_Z0 + ","
                + "\"x1\":" + COLOSSEUM_X1 + ",\"y1\":32.0,\"z1\":" + COLOSSEUM_Z1 + ","
                + "\"yaw0\":" + yaw0 + ",\"yaw1\":" + yaw1 + "},"
                + "\"rounds\":1}";
    }

    @Test
    void seededPlayersFaceEachOtherOnARealArenaSpawnPair() {
        GameState g = EdgeMatch.seed(COLOSSEUM_X0, 32.0, COLOSSEUM_Z0,
                COLOSSEUM_X1, 32.0, COLOSSEUM_Z1, 1);

        assertTrue(facingDot(g.players[0].yaw, COLOSSEUM_X0, COLOSSEUM_Z0,
                        COLOSSEUM_X1, COLOSSEUM_Z1) > 0.999,
                "slot 0 spawned looking at " + g.players[0].yaw + ", which is not at the opponent");
        assertTrue(facingDot(g.players[1].yaw, COLOSSEUM_X1, COLOSSEUM_Z1,
                        COLOSSEUM_X0, COLOSSEUM_Z0) > 0.999,
                "slot 1 spawned looking at " + g.players[1].yaw + ", which is not at the opponent");

        assertTrue(yawGap(g.players[0].yaw, COLOSSEUM_YAW0) < 1.0f,
                "EdgeMatch.seed derives the yaw from the spawn pair and never reads the yaw the"
                        + " colosseum arena file authors for spawn 0; landing within a degree of"
                        + " it is how we know the derivation reproduces what the arena author"
                        + " meant instead of a different convention that merely happens to point"
                        + " at the opponent");
        assertTrue(yawGap(g.players[1].yaw, COLOSSEUM_YAW1) < 1.0f,
                "same for spawn 1: derived, then compared against the authored yaw");
    }

    @Test
    void theRoundResetSnapshotCarriesTheSameFacing() {
        GameState g = EdgeMatch.seed(COLOSSEUM_X0, 32.0, COLOSSEUM_Z0,
                COLOSSEUM_X1, 32.0, COLOSSEUM_Z1, 3);
        assertNotNull(g.roundInitial);
        assertEquals(g.players[0].yaw, g.roundInitial[0].yaw,
                "round 2 respawns from roundInitial; if that snapshot kept the old yaw the bug"
                        + " would come back after the first round");
        assertEquals(g.players[1].yaw, g.roundInitial[1].yaw);
    }

    @Test
    void theDevSpawnPairNoLongerFacesTheWrongWay() {
        GameState g = EdgeMatch.seed(-4.0, 64.0, 0.0, 4.0, 64.0, 0.0, 1);
        assertEquals(0f, yawGap(g.players[0].yaw, -90f), 1e-3f,
                "slot 0 sits at x=-4 and must look EAST at the opponent; the old hardcoded 90 was"
                        + " due west, exactly 180 degrees away");
        assertEquals(0f, yawGap(g.players[1].yaw, 90f), 1e-3f,
                "slot 1 sits at x=+4 and must look WEST; the old hardcoded -90 was due east");
    }

    @Test
    void anAssignmentWithNoSpawnYawsDerivesThemFromTheSpawnsInsteadOfGuessing() {
        EdgeAssignment a = EdgeAssignment.parse(assignmentWithoutSpawnYaws());
        assertNotNull(a);
        assertTrue(facingDot(a.spawnYaw(0), a.spawnX(0), a.spawnZ(0), a.spawnX(1), a.spawnZ(1)) > 0.999,
                "the fallback yaw must point slot 0 at slot 1");
        assertTrue(facingDot(a.spawnYaw(1), a.spawnX(1), a.spawnZ(1), a.spawnX(0), a.spawnZ(0)) > 0.999,
                "and slot 1 at slot 0");
    }

    @Test
    void anAssignmentThatCarriesSpawnYawsIsLeftAlone() {
        EdgeAssignment a = EdgeAssignment.parse(
                assignmentWithSpawnYaws(COLOSSEUM_YAW0, COLOSSEUM_YAW1));
        assertNotNull(a);
        assertEquals(COLOSSEUM_YAW0, a.spawnYaw(0), 1e-3f,
                "core owns the spawn yaw in the assignment: RollbackHandoffManager computes it"
                        + " with SpawnFacing.yaw over the spawn pair and hands it to"
                        + " EdgeSessionBroker.assign, so it is a derived facing, not the yaw the"
                        + " arena file authors. Whatever the assignment carries wins here - the"
                        + " edge only derives a yaw when the assignment omits one, and an edge"
                        + " that recomputed a carried yaw would drift from core the moment the"
                        + " two derivations stopped agreeing");
        assertEquals(COLOSSEUM_YAW1, a.spawnYaw(1), 1e-3f);
    }

    @Test
    void theDevAssignFallbackArenaAlsoFacesTheOpponent() {
        DevAssignMain.ArenaRef dev = DevAssignMain.ArenaRef.dev(64.0);
        double[] s0 = dev.spawn0();
        double[] s1 = dev.spawn1();
        assertTrue(facingDot((float) s0[3], s0[0], s0[2], s1[0], s1[2]) > 0.999,
                "devAssign without -ParenaName is how this bug was reachable in a dev duel");
        assertTrue(facingDot((float) s1[3], s1[0], s1[2], s0[0], s0[2]) > 0.999);
    }
}
