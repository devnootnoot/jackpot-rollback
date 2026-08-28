package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Map;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class ChecksumLedgerPruneTest {
    private static final double GROUND_Y = 64.0;
    private static final int FRAMES = 3000;

    @SuppressWarnings("unchecked")
    private static Map<Integer, Long> ledger(NetSession s, String name) throws Exception {
        Field f = NetSession.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<Integer, Long>) f.get(s);
    }

    private static int retention() throws Exception {
        Field f = NetSession.class.getDeclaredField("CHECKSUM_RETENTION_FRAMES");
        f.setAccessible(true);
        return f.getInt(null);
    }

    @Test
    void theLocalChecksumLedgerStopsGrowingOnceTheMatchOutlivesTheRetentionWindow() throws Exception {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x9A9AL, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 4096);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 4096);

        for (int i = 0; i < FRAMES; i++) {
            net.step();
            s0.update(Input.NONE);
            s1.update(Input.NONE);
        }
        for (int i = 0; i < 4000 && s0.confirmedFrame() < FRAMES - 8; i++) {
            net.step();
            s0.flush();
            s1.flush();
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        int confirmed = s0.confirmedFrame();
        assertTrue(confirmed > retention(),
                "the match has to outrun the retention window for this test to mean anything:"
                        + " confirmed " + confirmed + " against a window of " + retention());

        int held = ledger(s0, "localChecksums").size();
        assertTrue(held <= retention() + 64,
                "localChecksums held " + held + " entries at confirmed frame " + confirmed
                        + ". One entry per confirmed frame with nothing ever removed is 72000"
                        + " entries an hour of play, per session, for a ledger nobody reads once"
                        + " the frame is minutes old");
    }

    @Test
    void aPeerCannotGrowThePendingLedgerByNamingFramesThatWillNeverArrive() throws Exception {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x9B9BL, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 512);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 512);

        for (int i = 0; i < 40; i++) {
            net.step();
            s0.update(Input.NONE);
            s1.update(Input.NONE);
        }

        Transport peer = net.endpoint(1);
        for (int i = 0; i < 5000; i++) {
            peer.send(Protocol.encode(new Message.Checksum(Integer.MAX_VALUE - i, i)));
            peer.send(Protocol.encode(new Message.Checksum(-i - 1, i)));
        }
        for (int i = 0; i < 40; i++) {
            net.step();
            s0.flush();
            s1.flush();
        }

        int pending = ledger(s0, "pendingRemoteChecksums").size();
        assertTrue(pending < 64,
                "a peer that names frames outside the prediction window bought " + pending
                        + " map entries for the cost of a datagram");
    }
}
