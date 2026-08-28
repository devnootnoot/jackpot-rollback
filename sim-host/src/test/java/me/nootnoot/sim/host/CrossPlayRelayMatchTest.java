package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import me.nootnoot.relay.RelayServer;
import me.nootnoot.sim.ArenaAgreement;
import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.GameStateFrame0Codec;
import me.nootnoot.sim.MatchSetupFrame0Decoder;
import me.nootnoot.sim.net.SlotTokens;
import me.nootnoot.sim.net.UdpTransport;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import org.junit.jupiter.api.Test;

class CrossPlayRelayMatchTest {

    private static final byte[] SECRET =
            "core-and-relay-share-this".getBytes(StandardCharsets.UTF_8);

    private static final long SESSION = 0x0C0FFEE0551A4L;
    private static final int ROUNDS_TARGET = 2;
    private static final int RING = 2048;
    private static final int TICK_CAP = 6000;
    private static final long TICK_NANOS = 1_500_000L;

    private static final int EDGE_SLOT = CrossPlayFixture.EDGE_SLOT;
    private static final int MOD_SLOT = CrossPlayFixture.MOD_SLOT;

    private record Ends(int comparedFrames, int commonFrame) {
    }

    @Test
    void aCrossPlayMatchRunsOverARealRelayWithTokensDerivedTheWayCoreDerivesThem() throws Exception {
        GameState kit = CrossPlayFixture.kit();
        byte[] modToken = SlotTokens.derive(SECRET, SESSION, MOD_SLOT);
        byte[] edgeToken = SlotTokens.derive(SECRET, SESSION, EDGE_SLOT);

        byte[] modBlob = CrossPlayFixture.setupBlob(kit, SESSION, MOD_SLOT, modToken,
                ROUNDS_TARGET);
        byte[] edgeBlob = CrossPlayFixture.setupBlob(kit, SESSION, EDGE_SLOT, edgeToken,
                ROUNDS_TARGET);

        GameState modState = MatchSetupFrame0Decoder.decode(modBlob).state();
        GameState edgeState = MatchSetupFrame0Decoder.decode(edgeBlob).state();
        assertArrayEquals(GameStateFrame0Codec.encode(modState),
                GameStateFrame0Codec.encode(edgeState),
                "the two halves of a cross-play match are addressed differently and must still"
                        + " decode one frame 0");

        Arena arena = Arena.flat(CrossPlayFixture.GROUND_Y);
        assertNull(ArenaAgreement.of(arena, edgeState, "edge")
                        .disagreement(ArenaAgreement.of(arena, modState, "mod")),
                "the hosts disagreed before the relay was even dialled");

        RelayServer relay = new RelayServer(0, true, SECRET);
        assertTrue(relay.verifiesDerivedSlotTokens(),
                "this test only means something against a relay that VERIFIES the token, which is"
                        + " the shape a public relay has to run in");
        relay.start();

        UdpTransport edgeLink = null;
        UdpTransport modLink = null;
        try {
            SocketAddress addr = new InetSocketAddress("127.0.0.1", relay.port());
            edgeLink = new UdpTransport(addr, SESSION, EDGE_SLOT, edgeToken);
            modLink = new UdpTransport(addr, SESSION, MOD_SLOT, modToken);

            MatchDriver edge = new MatchDriver(edgeLink, EDGE_SLOT, arena, edgeState, RING,
                    new CrossPlayScript(EDGE_SLOT, true, CrossPlayFixture.EDGE_X),
                    new CrossPlayFixture.NoRender());
            MatchDriver mod = new MatchDriver(modLink, MOD_SLOT, arena, modState, RING,
                    new CrossPlayScript(MOD_SLOT, false, CrossPlayFixture.MOD_X),
                    new CrossPlayFixture.NoRender());

            Ends ends = run(edge, mod);

            assertEquals(0L, relay.metrics().unauthorized.get(),
                    "the relay refused a bind. Both peers presented the token core derives for"
                            + " their slot, so a refusal here means the derivation the peers use"
                            + " and the one the relay checks have drifted apart - which takes down"
                            + " every match at once and shows up only as a session that never"
                            + " pairs");
            assertEquals(0L, relay.metrics().versionMismatch.get());
            assertEquals(1, relay.sessionCount(),
                    "one match is one relay session: an edge-hosted slot and a mod-hosted slot"
                            + " have to land in the SAME session id or they never see each other");

            assertFalse(edge.aborted(), "the edge host aborted: " + edge.abortReason());
            assertFalse(mod.aborted(), "the mod host aborted: " + mod.abortReason());
            assertEquals(-1, edge.desyncFrame(), "the edge host saw a checksum desync");
            assertEquals(-1, mod.desyncFrame(), "the mod host saw a checksum desync");
            assertTrue(edge.finishedNormally() || mod.finishedNormally(),
                    "neither host reached the end of a best-of-" + ROUNDS_TARGET
                            + " over the relay; confirmed to frame " + ends.commonFrame());
            assertTrue(ends.comparedFrames() > 60, "only " + ends.comparedFrames()
                    + " confirmed frames were shared, which is too few to call this a match");
            assertEquals(edge.roundWins(EDGE_SLOT), mod.roundWins(EDGE_SLOT),
                    "the two hosts disagree about the score");
            assertEquals(edge.roundWins(MOD_SLOT), mod.roundWins(MOD_SLOT),
                    "the two hosts disagree about the score");
        } finally {
            if (edgeLink != null) {
                edgeLink.close();
            }
            if (modLink != null) {
                modLink.close();
            }
            relay.stop();
        }
    }

    @Test
    void aTokenMintedForTheOtherHalfOfTheMatchDoesNotSeatThisHalf() throws Exception {
        RelayServer relay = new RelayServer(0, true, SECRET);
        relay.start();
        UdpTransport wrong = null;
        try {
            SocketAddress addr = new InetSocketAddress("127.0.0.1", relay.port());
            wrong = new UdpTransport(addr, SESSION, MOD_SLOT,
                    SlotTokens.derive(SECRET, SESSION, EDGE_SLOT));
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (System.nanoTime() < deadline && relay.metrics().unauthorized.get() == 0L) {
                Thread.sleep(10);
            }

            assertEquals(0, relay.sessionCount(),
                    "cross-play hands each half a DIFFERENT token. If the modded client could seat"
                            + " itself with the token minted for the edge, the per-slot binding is"
                            + " decorative");
            assertTrue(relay.metrics().unauthorized.get() > 0);
        } finally {
            if (wrong != null) {
                wrong.close();
            }
            relay.stop();
        }
    }

    private static Ends run(MatchDriver edge, MatchDriver mod) throws InterruptedException {
        boolean edgeLive = true;
        boolean modLive = true;
        Map<Integer, Long> edgeSeal = new HashMap<>();
        Map<Integer, Long> modSeal = new HashMap<>();

        for (int i = 0; i < TICK_CAP && (edgeLive || modLive); i++) {
            long deadline = System.nanoTime() + TICK_NANOS;
            if (edgeLive) {
                edgeLive = edge.tick();
            }
            if (modLive) {
                modLive = mod.tick();
            }
            edgeSeal.put(edge.confirmedFrame(), Checksum.of(edge.confirmedState()));
            modSeal.put(mod.confirmedFrame(), Checksum.of(mod.confirmedState()));
            long left = deadline - System.nanoTime();
            if (left > 0) {
                Thread.sleep(left / 1_000_000L, (int) (left % 1_000_000L));
            }
        }

        int compared = 0;
        for (Map.Entry<Integer, Long> e : edgeSeal.entrySet()) {
            Long theirs = modSeal.get(e.getKey());
            if (theirs == null) {
                continue;
            }
            compared++;
            assertEquals(e.getValue(), theirs,
                    "the two hosts confirmed DIFFERENT state at frame " + e.getKey()
                            + " with real packets on the wire");
        }
        return new Ends(compared, Math.min(edge.confirmedFrame(), mod.confirmedFrame()));
    }
}
