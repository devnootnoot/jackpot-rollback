package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class ProtocolTest {
    @Test
    void helloRoundTrips() {
        Message.Hello m = new Message.Hello(0xDEADBEEFCAFEL, 1, 12345);
        Message.Hello d = (Message.Hello) Protocol.decode(Protocol.encode(m));
        assertEquals(m.sessionId(), d.sessionId());
        assertEquals(m.slot(), d.slot());
        assertEquals(m.protocolVersion(), d.protocolVersion());
        assertArrayEquals(new byte[0], d.token());
    }

    @Test
    void helloTokenRoundTrips() {
        byte[] token = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32};
        Message.Hello m = new Message.Hello(0xABCDEFL, 0, 777, token);
        byte[] wire = Protocol.encode(m);

        assertEquals(1 + 8 + 4 + 4 + 2 + 32, wire.length);
        org.junit.jupiter.api.Assertions.assertTrue(Protocol.isWellFormed(wire, wire.length));
        Message.Hello d = (Message.Hello) Protocol.decode(wire);
        assertArrayEquals(token, d.token());
    }

    @Test
    void inputFramesRoundTrip() {
        List<Input> inputs = List.of(
                new Input(true, false, false, true, true, false, false, true, false, 12.5f, -3.25f, 3),
                new Input(false, true, true, false, false, true, true, false, true, -170.0f, 89.0f, 8),

                new Input(true, true, false, false, true, false, true, false, true, 45f, -10f, 4)
                        .withOffhandUse(true)
                        .withInvAction(Input.INV_MOVE, 12, 40),
                Input.NONE);
        Message.InputFrames m = new Message.InputFrames(42, inputs, 17);
        assertEquals(m, Protocol.decode(Protocol.encode(m)));
    }

    @Test
    void emptyInputFramesRoundTrip() {
        Message.InputFrames m = new Message.InputFrames(7, List.of(), -1);
        assertEquals(m, Protocol.decode(Protocol.encode(m)));
    }

    @Test
    void checksumRoundTrips() {
        Message.Checksum m = new Message.Checksum(123, 0x1133265f7ffd6643L);
        assertEquals(m, Protocol.decode(Protocol.encode(m)));
    }

    @Test
    void abortAndHeartbeatRoundTrip() {
        assertEquals(new Message.Abort(1), Protocol.decode(Protocol.encode(new Message.Abort(1))));
        assertEquals(new Message.Heartbeat(99L), Protocol.decode(Protocol.encode(new Message.Heartbeat(99L))));
    }

    @Test
    void snapshotRoundTrips() {
        double[][] boxes = {{-5, 64, -2, 5, 67, -1.5}, {0, 64, 0, 1, 65, 1}};
        Message.Snapshot m = new Message.Snapshot(64.0, boxes);
        Message decoded = Protocol.decode(Protocol.encode(m));
        Message.Snapshot s = (Message.Snapshot) decoded;
        assertEquals(64.0, s.groundY());
        assertEquals(boxes.length, s.boxes().length);
        for (int i = 0; i < boxes.length; i++) {
            assertArrayEquals(boxes[i], s.boxes()[i]);
        }
    }

}
