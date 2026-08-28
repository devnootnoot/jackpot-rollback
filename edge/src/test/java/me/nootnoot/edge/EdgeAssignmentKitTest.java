package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.UUID;
import me.nootnoot.edge.tools.DevAssignMain;
import org.junit.jupiter.api.Test;

class EdgeAssignmentKitTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static String devAssignment(boolean devKit) {
        return DevAssignMain.assignment(1234L, 0, A, "playerA", B, "playerB",
                "127.0.0.1", 7777, -60.0, 1, System.currentTimeMillis() + 60_000L, devKit);
    }

    @Test
    void devAssignNeverCarriesSetupBytes() {
        EdgeAssignment a = EdgeAssignment.parse(devAssignment(true));
        assertNotNull(a, "the dev assignment must parse");
        assertFalse(a.hasSetup(),
                "devAssign has no server to serialize inventories with, so it can never put real"
                        + " setup bytes on the assignment - only mcleagues-core can");
    }

    @Test
    void devAssignRequestsTheDevKitSoTheMatchIsNotEmptyHanded() {
        EdgeAssignment a = EdgeAssignment.parse(devAssignment(true));
        assertNotNull(a);
        assertTrue(a.devKit(),
                "without the devKit flag the edge falls through to a null loadout and both players"
                        + " spawn empty-handed with an unequipped opponent NPC");
    }

    @Test
    void anAssignmentWithoutTheDevKitFlagAsksForNoKit() {
        EdgeAssignment a = EdgeAssignment.parse(devAssignment(false));
        assertNotNull(a);
        assertFalse(a.devKit());
        assertFalse(a.hasSetup());
    }

    @Test
    void coreSetupBytesSurviveTheAssignmentRoundTrip() {
        byte[] setup = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        String json = "{\"v\":1,\"sessionId\":7,\"slot\":1,"
                + "\"token\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\","
                + "\"relay\":{\"host\":\"127.0.0.1\",\"port\":7777},"
                + "\"player\":{\"uuid\":\"" + A + "\",\"name\":\"playerA\"},"
                + "\"opponent\":{\"uuid\":\"" + B + "\",\"name\":\"playerB\"},"
                + "\"arena\":{\"name\":\"dev\",\"groundY\":-60.0},"
                + "\"spawns\":{\"x0\":-4.0,\"y0\":-60.0,\"z0\":0.0,"
                + "\"x1\":4.0,\"y1\":-60.0,\"z1\":0.0},"
                + "\"rounds\":1,"
                + "\"setup\":\"" + Base64.getEncoder().encodeToString(setup) + "\"}";

        EdgeAssignment a = EdgeAssignment.parse(json);
        assertNotNull(a, "an assignment carrying core setup bytes must parse");
        assertTrue(a.hasSetup());
        assertArrayEquals(setup, a.setup(),
                "the kit bytes both edges share must survive base64 on the assignment untouched");
    }
}
