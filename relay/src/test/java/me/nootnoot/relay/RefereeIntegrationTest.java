package me.nootnoot.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.GameStateFrame0Codec;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.net.ControlProtocol;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.UdpTransport;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class RefereeIntegrationTest {
    private static final double GROUND_Y = 64.0;

    @Test
    void refereeReplaysTeedInputsAndShipsSignedResult() throws Exception {
        byte[] secret = "control-secret".getBytes(StandardCharsets.UTF_8);
        ControlAuth auth = new ControlAuth(secret);
        RelayServer relay = new RelayServer(0);
        int controlPort = relay.enableReferee(secret, 0);
        relay.start();
        long session = 555L;
        try {
            GameState frame0 = HarnessScenarios.duel(Arena.flat(GROUND_Y));
            ControlProtocol.Authorize authorize = new ControlProtocol.Authorize(
                    session, Long.MAX_VALUE, new byte[]{1, 2, 3, 4}, new byte[]{5, 6, 7, 8},
                    GROUND_Y, new double[0][], GameStateFrame0Codec.encode(frame0));

            try (Socket ctl = new Socket("127.0.0.1", controlPort)) {
                ctl.setSoTimeout(3000);
                DataInputStream cin = new DataInputStream(new BufferedInputStream(ctl.getInputStream()));
                DataOutputStream cout = new DataOutputStream(new BufferedOutputStream(ctl.getOutputStream()));
                sendFrame(cout, auth, ControlProtocol.encode(authorize));
                Thread.sleep(150);

                SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());

                UdpTransport t0 = new UdpTransport(relayAddr, session, 0, new byte[]{1, 2, 3, 4});
                UdpTransport t1 = new UdpTransport(relayAddr, session, 1, new byte[]{5, 6, 7, 8});
                try {
                    Thread.sleep(150);
                    final int n = 24;
                    sendChunked(t0, n, 8);
                    sendChunked(t1, n, 8);

                    long deadline = System.currentTimeMillis() + 3000;
                    while (relay.refereeConfirmedFrame(session) < n && System.currentTimeMillis() < deadline) {
                        Thread.sleep(20);
                    }
                    assertEquals(n, relay.refereeConfirmedFrame(session),
                            "referee must confirm every teed frame");

                    relay.finalizeReferee(session);
                    ControlProtocol.Result result = readResult(cin, auth);
                    assertNotNull(result, "the relay must ship a signed RESULT back");
                    assertEquals(session, result.sessionId());
                    assertEquals(n, result.confirmedFrame());
                } finally {
                    t0.close();
                    t1.close();
                }
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void forgedControlMessageIsRejected() throws Exception {
        byte[] secret = "real-secret".getBytes(StandardCharsets.UTF_8);
        ControlAuth wrong = new ControlAuth("wrong-secret".getBytes(StandardCharsets.UTF_8));
        RelayServer relay = new RelayServer(0);
        int controlPort = relay.enableReferee(secret, 0);
        relay.start();
        long session = 777L;
        try {
            GameState frame0 = HarnessScenarios.duel(Arena.flat(GROUND_Y));
            ControlProtocol.Authorize authorize = new ControlProtocol.Authorize(
                    session, Long.MAX_VALUE, new byte[]{1}, new byte[]{2},
                    GROUND_Y, new double[0][], GameStateFrame0Codec.encode(frame0));
            try (Socket ctl = new Socket("127.0.0.1", controlPort)) {
                DataOutputStream cout = new DataOutputStream(new BufferedOutputStream(ctl.getOutputStream()));
                sendFrame(cout, wrong, ControlProtocol.encode(authorize));
                Thread.sleep(200);
                assertTrue(relay.refereeConfirmedFrame(session) < 0,
                        "a forged AUTHORIZE must not register a referee");
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void wrongSlotTokenIsRejectedWhenRefereeEnabled() throws Exception {
        byte[] secret = "control-secret".getBytes(StandardCharsets.UTF_8);
        ControlAuth auth = new ControlAuth(secret);
        RelayServer relay = new RelayServer(0);
        int controlPort = relay.enableReferee(secret, 0);
        relay.start();
        long session = 999L;
        try {
            GameState frame0 = HarnessScenarios.duel(Arena.flat(GROUND_Y));
            ControlProtocol.Authorize authorize = new ControlProtocol.Authorize(
                    session, Long.MAX_VALUE, new byte[]{1, 2, 3, 4}, new byte[]{5, 6, 7, 8},
                    GROUND_Y, new double[0][], GameStateFrame0Codec.encode(frame0));
            try (Socket ctl = new Socket("127.0.0.1", controlPort)) {
                DataOutputStream cout = new DataOutputStream(new BufferedOutputStream(ctl.getOutputStream()));
                sendFrame(cout, auth, ControlProtocol.encode(authorize));
                Thread.sleep(150);

                SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());

                UdpTransport t0 = new UdpTransport(relayAddr, session, 0, new byte[]{9, 9, 9, 9});
                UdpTransport t1 = new UdpTransport(relayAddr, session, 1);
                try {
                    Thread.sleep(150);
                    sendChunked(t0, 8, 8);
                    sendChunked(t1, 8, 8);
                    Thread.sleep(300);

                    assertEquals(0, relay.refereeConfirmedFrame(session),
                            "a Hello with a wrong/absent token must not claim a slot");
                } finally {
                    t0.close();
                    t1.close();
                }
            }
        } finally {
            relay.stop();
        }
    }

    private static void sendChunked(UdpTransport t, int total, int chunk) {
        for (int base = 0; base < total; base += chunk) {
            int n = Math.min(chunk, total - base);
            List<Input> run = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                run.add(Input.NONE);
            }
            t.send(Protocol.encode(new Message.InputFrames(base, run, base + n - 1, 0)));
        }
    }

    private static void sendFrame(DataOutputStream out, ControlAuth auth, byte[] msg) throws Exception {
        out.writeInt(msg.length);
        out.write(msg);
        out.write(auth.sign(msg));
        out.flush();
    }

    private static ControlProtocol.Result readResult(DataInputStream in, ControlAuth auth) throws Exception {
        int len = in.readInt();
        if (len < 0 || len > (1 << 22)) {
            return null;
        }
        byte[] msg = new byte[len];
        in.readFully(msg);
        byte[] tag = new byte[32];
        in.readFully(tag);
        if (!auth.verify(msg, tag)) {
            return null;
        }
        ControlProtocol.ControlMessage m = ControlProtocol.decode(msg);
        return m instanceof ControlProtocol.Result r ? r : null;
    }
}
