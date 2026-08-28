package me.nootnoot.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import me.nootnoot.sim.net.Protocol;
import org.junit.jupiter.api.Test;

class RelayRegistryJsonTest {

    private static final long AT = 1_700_000_000_000L;

    private static RelayRegistry registry(String relayId, int redisPort) {
        return new RelayRegistry(relayId, "EU", "relay-eu.jackpotmc.com", 7777, 7778,
                "127.0.0.1", redisPort, "", () -> 3, () -> 12, () -> 4L);
    }

    @Test
    void theHeartbeatCarriesEverythingAChooserNeeds() {
        String json = registry("eu-relay-1", 6379).json(AT);

        assertEquals("{\"relayId\":\"eu-relay-1\",\"region\":\"EU\","
                        + "\"host\":\"relay-eu.jackpotmc.com\",\"port\":7777,\"controlPort\":7778,"
                        + "\"sessions\":3,\"refereeQueueDepth\":12,\"refereeShed\":4,"
                        + "\"protocolVersion\":" + Protocol.VERSION + ",\"at\":" + AT + "}",
                json);
    }

    @Test
    void theProtocolVersionIsStampedSoAWrongBuildIsNeverPicked() {
        String json = registry("eu-relay-1", 6379).json(AT);

        assertTrue(json.contains("\"protocolVersion\":" + Protocol.VERSION),
                "a relay on the wrong build aborts every match it carries at HELLO, so the"
                        + " chooser has to be able to see its version before it picks it");
    }

    @Test
    void theTimestampIsTheFieldTheReaderAgesEntriesOutOn() {
        assertTrue(registry("eu-relay-1", 6379).json(AT).contains("\"at\":" + AT),
                "the key gets a long expire, so staleness is decided by this stamp exactly the"
                        + " way the edge registry decides it");
    }

    @Test
    void aQuoteOrBackslashInAnIdCannotBreakTheJson() {
        String json = registry("a\"b\\c", 6379).json(AT);

        assertTrue(json.contains("\"relayId\":\"a\\\"b\\\\c\""),
                "RELAY_ID comes from an operator's env, and an unescaped quote would make every"
                        + " entry in the hash unparseable, not just this one");
    }

    @Test
    void aRegistryThatCannotReachRedisDegradesToUnlistedInsteadOfThrowing() throws IOException {
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }

        RelayRegistry unreachable = registry("eu-relay-1", deadPort);

        assertFalse(unreachable.heartbeat(),
                "a registry that is down must never throw into the relay - forwarding has to"
                        + " carry on with this relay simply unlisted");
        assertFalse(unreachable.listed());
    }
}
