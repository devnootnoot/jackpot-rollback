package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class EdgeRefereeAuthorizerGateTest {

    private static final UUID A = UUID.nameUUIDFromBytes("a".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("b".getBytes());

    private static EdgeRefereeAuthorizer authorizer() {
        return new EdgeRefereeAuthorizer(Logger.getLogger("edge-referee-gate-test"));
    }

    @Test
    void aDevKitAssignmentIsClaimedExactlyOnce() {
        EdgeRefereeAuthorizer a = authorizer();
        EdgeAssignment first = EdgeAssignment.parse(assignment(11L, true, null));
        assertTrue(a.claim(first),
                "the edge builds frame 0 for a dev-kit assignment, so it is the only thing on the"
                        + " network that can authorize this session with the relay");
        assertFalse(a.claim(EdgeAssignment.parse(assignment(11L, true, null))),
                "brokerPoll re-walks the pending assignments every tick, so an unclaimed"
                        + " assignment would be authorized again on every poll and each authorize"
                        + " would reset the referee's re-simulation back to frame 0");
        assertTrue(a.isAuthorized(11L));
    }

    @Test
    void anAssignmentCarryingRealSetupBytesIsLeftAlone() {
        EdgeRefereeAuthorizer a = authorizer();
        String setup = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});
        EdgeAssignment real = EdgeAssignment.parse(assignment(12L, true, setup));
        assertFalse(a.owns(real),
                "setup bytes on the assignment mean mcleagues-core built frame 0 and has already"
                        + " authorized the referee itself. An edge that authorized on top of that"
                        + " would be overwriting a real match's frame 0 with a demo kit");
        assertFalse(a.claim(real));
    }

    @Test
    void anAssignmentWithNoKitAtAllIsLeftAlone() {
        EdgeRefereeAuthorizer a = authorizer();
        EdgeAssignment bare = EdgeAssignment.parse(assignment(13L, false, null));
        assertFalse(a.owns(bare),
                "with neither setup bytes nor a dev kit there is no frame 0 in existence, so there"
                        + " is nothing for a referee to re-simulate against");
    }

    @Test
    void forgettingASessionLetsItBeAuthorizedAgain() {
        EdgeRefereeAuthorizer a = authorizer();
        assertTrue(a.claim(EdgeAssignment.parse(assignment(14L, true, null))));
        a.forget(14L);
        assertTrue(a.claim(EdgeAssignment.parse(assignment(14L, true, null))),
                "a session id is reused across a devAssign re-run, so forgetting has to release it");
    }

    private static String assignment(long sessionId, boolean devKit, String setupBase64) {
        return "{\"v\":1,\"sessionId\":" + sessionId + ",\"slot\":0,"
                + "\"token\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\","
                + "\"relay\":{\"host\":\"127.0.0.1\",\"port\":7777},"
                + "\"player\":{\"uuid\":\"" + A + "\",\"name\":\"playerA\"},"
                + "\"opponent\":{\"uuid\":\"" + B + "\",\"name\":\"playerB\"},"
                + "\"arena\":{\"name\":\"aztec\",\"groundY\":64.0},"
                + "\"spawns\":{\"x0\":10.5,\"y0\":64.0,\"z0\":-3.5,"
                + "\"x1\":-9.5,\"y1\":65.0,\"z1\":4.5,"
                + "\"yaw0\":-135.0,\"pitch0\":2.0,\"yaw1\":45.0,\"pitch1\":-2.0},"
                + (setupBase64 == null ? "" : "\"setup\":\"" + setupBase64 + "\",")
                + "\"devKit\":" + devKit + ","
                + "\"rounds\":3}";
    }
}
