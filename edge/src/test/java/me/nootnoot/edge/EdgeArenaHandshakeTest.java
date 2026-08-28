package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.nootnoot.sim.ArenaAgreement;
import me.nootnoot.sim.net.LoopbackNetwork;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.Transport;
import org.junit.jupiter.api.Test;

class EdgeArenaHandshakeTest {

    private static final int TIMEOUT_TICKS = 40;

    @Test
    void anAgreementSurvivesAWireRoundTrip() {
        ArenaAgreement local = agreement(0x1234L, "arena.bin");
        ArenaAgreement decoded = ArenaAgreement.decode(local.encode());
        assertEquals(local, decoded);
        assertNull(local.disagreement(decoded));
    }

    @Test
    void anIdentityBlobIsNotMistakenForAnAgreement() {
        assertNull(ArenaAgreement.decode(new byte[]{1, 2, 3}));
        assertNull(ArenaAgreement.decode(new byte[128]));
    }

    @Test
    void twoEdgesWithTheSameArenaAgree() {
        LoopbackNetwork net = new LoopbackNetwork(7L, 0, 0, 0.0);
        EdgeHandshake a = new EdgeHandshake(net.endpoint(0), agreement(99L, "arena.bin"),
                TIMEOUT_TICKS);
        EdgeHandshake b = new EdgeHandshake(net.endpoint(1), agreement(99L, "arena.bin"),
                TIMEOUT_TICKS);
        assertEquals(EdgeHandshake.Status.AGREED, run(net, a, b));
        assertEquals(EdgeHandshake.Status.AGREED, b.status());
        assertNotNull(a.peer());
        assertNull(a.failure());
    }

    @Test
    void aDifferentArenaHashIsRefusedBeforeTheMatchStarts() {
        LoopbackNetwork net = new LoopbackNetwork(7L, 0, 0, 0.0);
        EdgeHandshake a = new EdgeHandshake(net.endpoint(0), agreement(99L, "arena.bin"),
                TIMEOUT_TICKS);
        EdgeHandshake b = new EdgeHandshake(net.endpoint(1), agreement(100L, "other.bin"),
                TIMEOUT_TICKS);
        assertEquals(EdgeHandshake.Status.MISMATCH, run(net, a, b));
        assertTrue(a.failure().contains("arena hash"));
        assertTrue(a.failure().contains("arena.bin"));
        assertTrue(a.failure().contains("other.bin"));
    }

    @Test
    void aDifferentSpawnIsRefusedBeforeTheMatchStarts() {
        LoopbackNetwork net = new LoopbackNetwork(7L, 0, 0, 0.0);
        ArenaAgreement mine = agreement(99L, "arena.bin");
        ArenaAgreement theirs = new ArenaAgreement(99L, mine.groundY(), mine.x0(),
                mine.y0() + 1.0, mine.z0(), mine.x1(), mine.y1(), mine.z1(),
                mine.stateChecksum(), "arena.bin");
        EdgeHandshake a = new EdgeHandshake(net.endpoint(0), mine, TIMEOUT_TICKS);
        EdgeHandshake b = new EdgeHandshake(net.endpoint(1), theirs, TIMEOUT_TICKS);
        assertEquals(EdgeHandshake.Status.MISMATCH, run(net, a, b));
        assertTrue(a.failure().contains("spawn0"));
    }

    @Test
    void aSilentPeerTimesOutInsteadOfStarting() {
        LoopbackNetwork net = new LoopbackNetwork(7L, 0, 0, 0.0);
        EdgeHandshake a = new EdgeHandshake(net.endpoint(0), agreement(99L, "arena.bin"),
                TIMEOUT_TICKS);
        EdgeHandshake.Status status = EdgeHandshake.Status.WAITING;
        for (int i = 0; i < TIMEOUT_TICKS + 2 && status == EdgeHandshake.Status.WAITING; i++) {
            status = a.pump();
            net.step();
        }
        assertEquals(EdgeHandshake.Status.TIMEOUT, status);
        assertTrue(a.failure().contains("never sent an arena hash"));
    }

    @Test
    void packetsThatArriveDuringTheHandshakeAreHandedToTheSession() {
        LoopbackNetwork net = new LoopbackNetwork(7L, 0, 0, 0.0);
        Transport peer = net.endpoint(1);
        byte[] identity = Protocol.encode(new Message.Container(new byte[]{9, 9, 9, 9}));
        byte[] checksum = Protocol.encode(new Message.Checksum(3, 0xABCDL));
        peer.send(identity);
        peer.send(checksum);

        EdgeHandshake a = new EdgeHandshake(net.endpoint(0), agreement(99L, "arena.bin"),
                TIMEOUT_TICKS);
        assertEquals(EdgeHandshake.Status.WAITING, a.pump());
        peer.send(Protocol.encode(new Message.Container(agreement(99L, "arena.bin").encode())));
        assertEquals(EdgeHandshake.Status.AGREED, a.pump());

        List<byte[]> replayed = a.receive();
        assertEquals(2, replayed.size());
        assertTrue(Protocol.decode(replayed.get(0)) instanceof Message.Container);
        assertTrue(Protocol.decode(replayed.get(1)) instanceof Message.Checksum);
        assertTrue(a.receive().isEmpty());
    }

    @Test
    void theSameArenaFileHashesTheSameOnBothEdges() {
        EdgeArena one = EdgeArena.flat(-60.0);
        EdgeArena two = EdgeArena.flat(-60.0);
        assertEquals(one.hash(), two.hash());
        assertEquals(-60.0, one.groundY());
        assertTrue(one.simSolid(0, -61, 0));
    }

    @Test
    void aDifferentFlatGroundHashesDifferently() {
        assertTrue(EdgeArena.flat(-60.0).hash() != EdgeArena.flat(64.0).hash());
    }

    @Test
    void aDifferentFrameZeroIsRefusedBeforeTheMatchStarts() {
        LoopbackNetwork net = new LoopbackNetwork(7L, 0, 0, 0.0);
        ArenaAgreement mine = stated(99L, 0xABCDL);
        ArenaAgreement theirs = stated(99L, 0xABCEL);
        EdgeHandshake a = new EdgeHandshake(net.endpoint(0), mine, TIMEOUT_TICKS);
        EdgeHandshake b = new EdgeHandshake(net.endpoint(1), theirs, TIMEOUT_TICKS);
        assertEquals(EdgeHandshake.Status.MISMATCH, run(net, a, b));
        assertTrue(a.failure().contains("frame 0 state"), a.failure());
    }

    @Test
    void aPeerOnAnOlderJarIsRefusedAsAVersionSkewNotAsATimeout() {
        LoopbackNetwork net = new LoopbackNetwork(7L, 0, 0, 0.0);
        EdgeHandshake a = new EdgeHandshake(net.endpoint(0), agreement(99L, "arena.bin"),
                TIMEOUT_TICKS);
        byte[] older = agreement(99L, "arena.bin").encode();
        older[4] = 1;
        Transport peer = net.endpoint(1);

        EdgeHandshake.Status status = EdgeHandshake.Status.WAITING;
        for (int i = 0; i < TIMEOUT_TICKS && status == EdgeHandshake.Status.WAITING; i++) {
            peer.send(Protocol.encode(new Message.Container(older)));
            status = a.pump();
            net.step();
        }

        assertEquals(EdgeHandshake.Status.MISMATCH, status);
        assertTrue(a.failure().contains("version 1"), a.failure());
    }

    private static ArenaAgreement stated(long hash, long stateChecksum) {
        return new ArenaAgreement(hash, -66.0, -4.0, -60.0, 0.0, 4.0, -60.0, 0.0,
                stateChecksum, "arena.bin");
    }

    private static EdgeHandshake.Status run(LoopbackNetwork net, EdgeHandshake a, EdgeHandshake b) {
        EdgeHandshake.Status status = EdgeHandshake.Status.WAITING;
        for (int i = 0; i < TIMEOUT_TICKS && status == EdgeHandshake.Status.WAITING; i++) {
            status = a.pump();
            b.pump();
            net.step();
        }
        return status;
    }

    private static ArenaAgreement agreement(long hash, String source) {
        return new ArenaAgreement(hash, -66.0, -4.0, -60.0, 0.0, 4.0, -60.0, 0.0,
                ArenaAgreement.NO_STATE, source);
    }
}
