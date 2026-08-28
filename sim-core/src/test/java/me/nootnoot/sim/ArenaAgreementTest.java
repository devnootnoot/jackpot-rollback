package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ArenaAgreementTest {

    private static final int HEADER_BYTES = 79;

    @Test
    void theWireLayoutIsPinned() {
        ArenaAgreement a = agreement(0x0123456789ABCDEFL, "arena.bin");
        byte[] blob = a.encode();
        assertEquals(HEADER_BYTES + "arena.bin".length(), blob.length);

        ByteBuffer b = ByteBuffer.wrap(blob);
        assertEquals(ArenaAgreement.MAGIC, b.getInt());
        assertEquals(ArenaAgreement.VERSION, b.get());
        assertEquals(0x0123456789ABCDEFL, b.getLong());
        assertEquals(-66.0, b.getDouble());
        assertEquals(-4.0, b.getDouble());
        assertEquals(-60.0, b.getDouble());
        assertEquals(0.0, b.getDouble());
        assertEquals(4.0, b.getDouble());
        assertEquals(-60.0, b.getDouble());
        assertEquals(0.0, b.getDouble());
        assertEquals(ArenaAgreement.NO_STATE, b.getLong());
        assertEquals("arena.bin".length(), b.getShort() & 0xFFFF);
        byte[] src = new byte[b.remaining()];
        b.get(src);
        assertEquals("arena.bin", new String(src, StandardCharsets.UTF_8));
    }

    @Test
    void aRoundTripPreservesEveryField() {
        ArenaAgreement local = agreement(0x1234L, "arena.bin");
        ArenaAgreement decoded = ArenaAgreement.decode(local.encode());
        assertEquals(local, decoded);
        assertNull(local.disagreement(decoded));
    }

    @Test
    void anUnknownBlobDecodesToNullSoTheChannelCanBeShared() {
        assertNull(ArenaAgreement.decode(null));
        assertNull(ArenaAgreement.decode(new byte[]{1, 2, 3}));
        assertNull(ArenaAgreement.decode(new byte[128]));
        byte[] truncated = Arrays.copyOf(agreement(1L, "arena.bin").encode(), HEADER_BYTES - 1);
        assertNull(ArenaAgreement.decode(truncated));
    }

    @Test
    void aTruncatedSourceLengthDecodesToNullInsteadOfThrowing() {
        byte[] blob = agreement(1L, "arena.bin").encode();
        ByteBuffer.wrap(blob).putShort(HEADER_BYTES - 2, (short) 4096);
        assertNull(ArenaAgreement.decode(blob));
    }

    @Test
    void theMismatchMessageNamesBothSources() {
        ArenaAgreement mine = agreement(99L, "arena.bin");
        ArenaAgreement theirs = agreement(100L, "other.bin");
        String reason = mine.disagreement(theirs);
        assertNotNull(reason);
        assertTrue(reason.contains("arena hash"), reason);
        assertTrue(reason.contains("arena.bin"), reason);
        assertTrue(reason.contains("other.bin"), reason);
    }

    @Test
    void aDifferentSpawnIsReportedEvenWhenTheHashMatches() {
        ArenaAgreement mine = agreement(99L, "arena.bin");
        ArenaAgreement theirs = new ArenaAgreement(99L, mine.groundY(), mine.x0(), mine.y0() + 1.0,
                mine.z0(), mine.x1(), mine.y1(), mine.z1(), mine.stateChecksum(), "arena.bin");
        String reason = mine.disagreement(theirs);
        assertNotNull(reason);
        assertTrue(reason.contains("spawn0"), reason);
    }

    @Test
    void anAgreementBuiltFromAnArenaCarriesItsHashAndGround() {
        Arena arena = Arena.flat(-60.0);
        ArenaAgreement a = ArenaAgreement.of(arena, -4.0, -60.0, 0.0, 4.0, -60.0, 0.0, "flat");
        assertEquals(ArenaHash.of(arena), a.arenaHash());
        assertEquals(-60.0, a.groundY());
        assertNull(a.disagreement(ArenaAgreement.of(Arena.flat(-60.0), -4.0, -60.0, 0.0, 4.0, -60.0,
                0.0, "flat")));
        assertNotNull(a.disagreement(ArenaAgreement.of(Arena.flat(64.0), -4.0, -60.0, 0.0, 4.0,
                -60.0, 0.0, "flat")));
    }

    @Test
    void anAgreementBuiltFromAStateCarriesThatStatesSpawnsAndChecksum() {
        Arena arena = Arena.flat(-60.0);
        GameState s = seeded(-60.0);
        ArenaAgreement a = ArenaAgreement.of(arena, s, "flat");

        assertEquals(s.players[0].x, a.x0());
        assertEquals(s.players[0].y, a.y0());
        assertEquals(s.players[0].z, a.z0());
        assertEquals(s.players[1].x, a.x1());
        assertEquals(Checksum.of(s), a.stateChecksum());
        assertTrue(a.statedState());
        assertNull(a.disagreement(ArenaAgreement.of(Arena.flat(-60.0), seeded(-60.0), "flat")));
    }

    @Test
    void aFrame0StateThatDiffersIsNamedBeforeAnyTickIsSimulated() {
        Arena arena = Arena.flat(-60.0);
        GameState mine = seeded(-60.0);
        GameState theirs = seeded(-60.0);
        theirs.players[1].slotCrossbowEntry[3] = 7;

        ArenaAgreement a = ArenaAgreement.of(arena, mine, "flat");
        ArenaAgreement b = ArenaAgreement.of(arena, theirs, "flat");

        assertNotEquals(a.stateChecksum(), b.stateChecksum(),
                "the fixture must actually move a checksummed field, or this test proves nothing");
        String reason = a.disagreement(b);
        assertNotNull(reason, "the two hosts opened on different states and nothing said so."
                + " This is the failure that has always been discovered at round 2 instead");
        assertTrue(reason.contains("frame 0 state"), reason);
        assertTrue(reason.contains(ArenaAgreement.hex(b.stateChecksum())), reason);
    }

    @Test
    void twoDevAgreementsStateNoFrame0AndAreNotComparedOnOne() {
        Arena arena = Arena.flat(-60.0);
        ArenaAgreement dev = ArenaAgreement.of(arena, -4.0, -60.0, 0.0, 4.0, -60.0, 0.0, "flat");
        ArenaAgreement other = ArenaAgreement.of(arena, -4.0, -60.0, 0.0, 4.0, -60.0, 0.0, "flat");

        assertFalse(dev.statedState());
        assertNull(dev.disagreement(other),
                "neither side was handed a setup blob, so there is no shared opening state to"
                        + " compare and NO_STATE on BOTH sides is how that is said rather than a"
                        + " false mismatch");
    }

    @Test
    void aPeerThatSimplyOmitsItsFrame0ChecksumIsRefused() {
        Arena arena = Arena.flat(-60.0);
        GameState mine = seeded(-60.0);
        ArenaAgreement a = ArenaAgreement.of(arena, mine, "flat");
        ArenaAgreement silent = new ArenaAgreement(a.arenaHash(), a.groundY(), a.x0(), a.y0(),
                a.z0(), a.x1(), a.y1(), a.z1(), ArenaAgreement.NO_STATE, "flat");

        String reason = a.disagreement(silent);
        assertNotNull(reason,
                "the frame-0 checksum was compared only when BOTH sides stated one, so a peer that"
                        + " zeroes the field - a stale build that seeded from spawns instead of the"
                        + " setup blob, or a client that would rather not be checked - skipped the"
                        + " comparison entirely. That is fail-open on the one check that catches a"
                        + " cross-play frame-0 divergence before a tick is simulated");
        assertTrue(reason.contains("frame 0 state"), reason);
        assertTrue(reason.contains("not stated"), reason);
        assertNotNull(silent.disagreement(a),
                "and it has to read the same from the silent side, or the two hosts disagree about"
                        + " whether they disagree");
    }

    @Test
    void anAgreementFromAnOlderBuildIsIdentifiableByItsVersion() {
        byte[] blob = agreement(1L, "arena.bin").encode();
        assertEquals(ArenaAgreement.VERSION, ArenaAgreement.peerVersion(blob));

        blob[4] = 1;
        assertNull(ArenaAgreement.decode(blob),
                "a version this build does not speak must not decode into a comparable agreement");
        assertEquals(1, ArenaAgreement.peerVersion(blob),
                "but the version itself must stay readable, or a jar skew is indistinguishable"
                        + " from a peer that sent nothing at all");
        assertEquals(-1, ArenaAgreement.peerVersion(new byte[]{1, 2, 3, 4, 5}));
        assertEquals(-1, ArenaAgreement.peerVersion(null));
    }

    private static GameState seeded(double groundY) {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        PlayerState b = g.players[1];
        a.x = -4.0;
        a.y = groundY;
        a.z = 0.0;
        a.health = 20f;
        a.maxHealth = 20f;
        b.x = 4.0;
        b.y = groundY;
        b.z = 0.0;
        b.health = 20f;
        b.maxHealth = 20f;
        return g;
    }

    private static ArenaAgreement agreement(long hash, String source) {
        return new ArenaAgreement(hash, -66.0, -4.0, -60.0, 0.0, 4.0, -60.0, 0.0,
                ArenaAgreement.NO_STATE, source);
    }
}
