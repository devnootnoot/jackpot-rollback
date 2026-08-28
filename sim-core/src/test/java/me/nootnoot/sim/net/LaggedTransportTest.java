package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LaggedTransportTest {

    private static final class Spy implements Transport {
        final List<byte[]> sent = new ArrayList<>();
        final List<byte[]> inbox = new ArrayList<>();
        boolean closed;

        @Override
        public void send(byte[] packet) {
            sent.add(packet);
        }

        @Override
        public List<byte[]> receive() {
            List<byte[]> out = new ArrayList<>(inbox);
            inbox.clear();
            return out;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void zeroLagIsAStraightPassThrough() {
        Spy spy = new Spy();
        LaggedTransport t = new LaggedTransport(spy, 0);
        t.send(new byte[]{1});
        assertEquals(1, spy.sent.size(),
                "with no simulated lag the decorator must not buffer anything, or every match on"
                        + " the edge pays for a dev feature nobody switched on");
        spy.inbox.add(new byte[]{2});
        assertEquals(1, t.receive().size());
    }

    @Test
    void anOutboundPacketIsHeldUntilItsDelayHasPassed() throws InterruptedException {
        Spy spy = new Spy();
        LaggedTransport t = new LaggedTransport(spy, 60);
        t.send(new byte[]{1});
        assertEquals(0, spy.sent.size(), "the packet must not reach the wire immediately");
        Thread.sleep(90);
        t.send(new byte[]{2});
        assertTrue(spy.sent.size() >= 1,
                "once the delay has elapsed the held packet has to actually go out, or the match"
                        + " simply stalls instead of feeling laggy");
    }

    @Test
    void anInboundPacketIsHeldTheSameWay() throws InterruptedException {
        Spy spy = new Spy();
        LaggedTransport t = new LaggedTransport(spy, 60);
        spy.inbox.add(new byte[]{7});
        assertEquals(0, t.receive().size(), "the peer's packet must not be visible yet");
        Thread.sleep(90);
        List<byte[]> late = t.receive();
        assertEquals(1, late.size(), "delaying only one direction would halve the simulated ping");
        assertEquals(7, late.get(0)[0]);
    }

    @Test
    void theRoundTripIsTwiceTheOneWay() {
        LaggedTransport t = new LaggedTransport(new Spy(), 75);
        assertEquals(75, t.oneWayMillis());
        assertEquals(150, t.addedRoundTripMillis(),
                "both directions are delayed, so what a player feels is twice the configured"
                        + " one-way number. /ping says this out loud so nobody reads 75 as 75 ping");
    }

    @Test
    void anAbsurdValueIsClampedRatherThanHangingTheMatch() {
        assertEquals(LaggedTransport.MAX_ONE_WAY_MILLIS, LaggedTransport.clamp(60_000));
        assertEquals(0, LaggedTransport.clamp(-5));
    }

    @Test
    void closingReleasesTheHeldPacketsAndTheRealTransport() {
        Spy spy = new Spy();
        LaggedTransport t = new LaggedTransport(spy, 100);
        t.send(new byte[]{1});
        t.close();
        assertTrue(spy.closed, "the real transport still has to be closed");
    }
}
