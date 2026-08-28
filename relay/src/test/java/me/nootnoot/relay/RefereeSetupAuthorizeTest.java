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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.ArenaCodec;
import me.nootnoot.sim.MatchSetupFrame0Encoder;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.net.ControlProtocol;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.UdpTransport;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class RefereeSetupAuthorizeTest {
    private static final double GROUND_Y = 64.0;

    private static final int INVENTORY_SLOTS = 41;

    private static final int LEGACY_HOTBAR_BYTES = 41;

    private static final byte[] TOKEN_0 = {1, 2, 3, 4};

    private static final byte[] TOKEN_1 = {5, 6, 7, 8};

    private static final int DECIDED_FRAMES = 256;

    @Test
    void aMatchSetupAuthorizeRegistersARefereeAndShipsASignedResult() throws Exception {
        byte[] secret = "control-secret".getBytes(StandardCharsets.UTF_8);
        ControlAuth auth = new ControlAuth(secret);
        RelayServer relay = new RelayServer(0);
        int controlPort = relay.enableReferee(secret, 0);
        relay.start();
        long session = 4242L;
        try (Socket ctl = new Socket("127.0.0.1", controlPort)) {
            ctl.setSoTimeout(3000);
            DataInputStream cin = new DataInputStream(new BufferedInputStream(ctl.getInputStream()));
            DataOutputStream cout = new DataOutputStream(new BufferedOutputStream(ctl.getOutputStream()));

            ControlProtocol.AuthorizeSetup authorize = new ControlProtocol.AuthorizeSetup(
                    session, Long.MAX_VALUE, TOKEN_0, TOKEN_1, setupWire(session), arenaBlob());
            sendFrame(cout, auth, ControlProtocol.encode(authorize));
            Thread.sleep(200);

            assertEquals(0, relay.refereeConfirmedFrame(session),
                    "a match-setup authorize must register a referee for the session");

            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            UdpTransport t0 = new UdpTransport(relayAddr, session, 0, TOKEN_0);
            UdpTransport t1 = new UdpTransport(relayAddr, session, 1, TOKEN_1);
            try {
                Thread.sleep(150);
                final int n = 16;
                sendChunked(t0, n, 8);
                sendChunked(t1, n, 8);

                long deadline = System.currentTimeMillis() + 3000;
                while (relay.refereeConfirmedFrame(session) < n && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20);
                }
                assertEquals(n, relay.refereeConfirmedFrame(session),
                        "the referee must re-simulate every frame teed to it");

                relay.finalizeReferee(session);
                ControlProtocol.Result result = readResult(cin, auth);
                assertNotNull(result, "the relay must ship a signed RESULT back");
                assertEquals(session, result.sessionId());
                assertEquals(n, result.confirmedFrame());
            } finally {
                t0.close();
                t1.close();
            }
        } finally {
            relay.stop();
        }
    }

    @Test
    void anArenaBlobTheRelayCannotRebuildLeavesTheSessionUnauthorized() throws Exception {
        byte[] secret = "control-secret".getBytes(StandardCharsets.UTF_8);
        ControlAuth auth = new ControlAuth(secret);
        RelayServer relay = new RelayServer(0);
        int controlPort = relay.enableReferee(secret, 0);
        relay.start();
        long session = 4343L;
        try (Socket ctl = new Socket("127.0.0.1", controlPort)) {
            DataOutputStream cout = new DataOutputStream(new BufferedOutputStream(ctl.getOutputStream()));
            ControlProtocol.AuthorizeSetup authorize = new ControlProtocol.AuthorizeSetup(
                    session, Long.MAX_VALUE, TOKEN_0, TOKEN_1, setupWire(session),
                    new byte[]{9, 9, 9, 9});
            sendFrame(cout, auth, ControlProtocol.encode(authorize));
            Thread.sleep(300);
            assertTrue(relay.refereeConfirmedFrame(session) < 0,
                    "an arena the relay cannot rebuild must not register a referee that would"
                            + " re-simulate the duel against different collision");
        } finally {
            relay.stop();
        }
    }

    @Test
    void anExpiredAuthorizeIsSweptAndReported() throws Exception {
        byte[] secret = "control-secret".getBytes(StandardCharsets.UTF_8);
        ControlAuth auth = new ControlAuth(secret);
        RelayServer relay = new RelayServer(0);
        int controlPort = relay.enableReferee(secret, 0);
        relay.start();
        long session = 4444L;
        try (Socket ctl = new Socket("127.0.0.1", controlPort)) {
            ctl.setSoTimeout(15_000);
            DataInputStream cin = new DataInputStream(new BufferedInputStream(ctl.getInputStream()));
            DataOutputStream cout = new DataOutputStream(new BufferedOutputStream(ctl.getOutputStream()));
            ControlProtocol.AuthorizeSetup authorize = new ControlProtocol.AuthorizeSetup(
                    session, System.currentTimeMillis() + 200L, TOKEN_0, TOKEN_1,
                    setupWire(session), new byte[0]);
            sendFrame(cout, auth, ControlProtocol.encode(authorize));

            ControlProtocol.Result result = readResult(cin, auth);
            assertNotNull(result, "an expiring session must not be left in the relay forever");
            assertEquals(session, result.sessionId());
            assertTrue(relay.refereeConfirmedFrame(session) < 0,
                    "the swept session must be gone from the relay");
        } finally {
            relay.stop();
        }
    }

    @Test
    void aRelayedDuelIsRefereedAllTheWayToASignedDecidedVerdict() throws Exception {
        byte[] secret = "control-secret".getBytes(StandardCharsets.UTF_8);
        ControlAuth auth = new ControlAuth(secret);
        RelayServer relay = new RelayServer(0);
        int controlPort = relay.enableReferee(secret, 0);
        relay.start();
        long session = 4545L;
        try (Socket ctl = new Socket("127.0.0.1", controlPort)) {
            ctl.setSoTimeout(15_000);
            DataInputStream cin = new DataInputStream(new BufferedInputStream(ctl.getInputStream()));
            DataOutputStream cout = new DataOutputStream(new BufferedOutputStream(ctl.getOutputStream()));

            ControlProtocol.AuthorizeSetup authorize = new ControlProtocol.AuthorizeSetup(
                    session, Long.MAX_VALUE, TOKEN_0, TOKEN_1, setupWire(session, 1, 0f),
                    new byte[0]);
            sendFrame(cout, auth, ControlProtocol.encode(authorize));
            Thread.sleep(200);
            assertEquals(0, relay.refereeConfirmedFrame(session),
                    "the session must be registered before either peer binds a slot");

            SocketAddress relayAddr = new InetSocketAddress("127.0.0.1", relay.port());
            UdpTransport t0 = new UdpTransport(relayAddr, session, 0, TOKEN_0);
            UdpTransport t1 = new UdpTransport(relayAddr, session, 1, TOKEN_1);
            ControlProtocol.Result result;
            try {
                Thread.sleep(150);
                for (int pass = 0; pass < 3; pass++) {
                    sendChunked(t0, DECIDED_FRAMES, 32);
                    sendChunked(t1, DECIDED_FRAMES, 32);
                    Thread.sleep(150);
                }
                result = readResult(cin, auth);
            } finally {
                t0.close();
                t1.close();
            }

            assertNotNull(result, "the referee must ship a signed verdict once the match is over,"
                    + " with no operator action and nothing finalizing the session by hand");
            assertEquals(session, result.sessionId());
            assertTrue(result.decided(), "slot 1 starts this match on 0 health, so the referee's"
                    + " own re-simulation reaches a decided end from the input streams alone");
            assertEquals(0, result.winnerSlot(), "slot 0 is the only player left standing");
            assertEquals(1, result.winsP0());
            assertEquals(0, result.winsP1());
            assertTrue(result.confirmedFrame() > Simulation.ROUND_COUNTDOWN_TICKS,
                    "the verdict must come from frames the referee actually re-simulated, not"
                    + " from frame 0");
            assertTrue(relay.refereeConfirmedFrame(session) < 0,
                    "a decided session must be released rather than left re-simulating forever");
        } finally {
            relay.stop();
        }
    }

    @Test
    void anAuthorizeTheRefereeCannotTakeDoesNotKillTheControlLink() throws Exception {
        byte[] secret = "control-secret".getBytes(StandardCharsets.UTF_8);
        ControlAuth auth = new ControlAuth(secret);
        RelayServer relay = new RelayServer(0);
        int controlPort = relay.enableReferee(secret, 0);
        relay.start();
        long poisoned = 4646L;
        long healthy = 4747L;
        try (Socket ctl = new Socket("127.0.0.1", controlPort)) {
            ctl.setSoTimeout(5_000);
            DataOutputStream cout = new DataOutputStream(new BufferedOutputStream(ctl.getOutputStream()));

            sendFrame(cout, auth, ControlProtocol.encode(new ControlProtocol.AuthorizeSetup(
                    poisoned, Long.MAX_VALUE, TOKEN_0, TOKEN_1, setupWire(poisoned),
                    new byte[]{9, 9, 9, 9})));
            Thread.sleep(300);
            assertTrue(relay.refereeConfirmedFrame(poisoned) < 0,
                    "an arena the relay cannot rebuild must not register a referee that would"
                            + " re-simulate the duel against different collision");

            sendFrame(cout, auth, ControlProtocol.encode(new ControlProtocol.AuthorizeSetup(
                    healthy, Long.MAX_VALUE, TOKEN_0, TOKEN_1, setupWire(healthy), arenaBlob())));
            Thread.sleep(400);
            assertEquals(0, relay.refereeConfirmedFrame(healthy),
                    "one session the referee cannot take must not cost every later session on the"
                            + " same link its third witness");
        } finally {
            relay.stop();
        }
    }

    private static byte[] arenaBlob() {
        ArenaCodec.PaletteEntry[] geometry = {
            new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 6.0f, 0, 0, new double[0][]),
        };
        int[][] blocks = {{0, 0, 0, 0}, {1, 0, 0, 0}, {2, 0, 0, 0}};
        ArenaCodec.Snapshot snapshot = new ArenaCodec.Snapshot(0L, -8, (int) GROUND_Y - 1, -8,
                16, 4, 16, new String[]{"minecraft:stone"}, blocks, geometry, GROUND_Y);
        return ArenaCodec.encode(snapshot);
    }

    private static byte[] setupWire(long sessionId) {
        return setupWire(sessionId, 3, 20f);
    }

    private static byte[] setupWire(long sessionId, int rounds, float slot1Health) {
        GameState kit = HarnessScenarios.duel(Arena.flat(GROUND_Y));
        kit.players[1].health = slot1Health;
        byte[] sections = MatchSetupFrame0Encoder.sections(kit);

        ByteBuffer w = ByteBuffer.allocate(1 << 19);
        w.putLong(sessionId);
        w.put((byte) 0);
        w.putShort((short) TOKEN_0.length);
        w.put(TOKEN_0);
        putString(w, "relay.test.internal");
        w.putInt(7777);
        w.putInt(rounds);
        for (int i = 0; i < 8; i++) {
            w.put((byte) 0);
        }
        w.putInt(0);
        w.putInt(0);
        w.putDouble(GROUND_Y);
        w.putInt(0);
        w.putDouble(0.0);
        w.putDouble(0.0);
        w.putDouble(64.0);
        w.put((byte) 1);
        putPlayer(w, kit, 0);
        putPlayer(w, kit, 1);
        w.put((byte) 0);
        w.putInt(0);
        w.putInt(0);
        w.putInt(0);
        w.put(sections);

        byte[] out = new byte[w.position()];
        w.flip();
        w.get(out);
        return out;
    }

    private static void putPlayer(ByteBuffer w, GameState kit, int slot) {
        w.putDouble(kit.players[slot].x);
        w.putDouble(kit.players[slot].y);
        w.putDouble(kit.players[slot].z);
        w.putFloat(kit.players[slot].yaw);
        w.putFloat(kit.players[slot].pitch);
        w.putFloat(kit.players[slot].health);
        w.putFloat(1f);
        w.putFloat(4f);
        w.putInt(0);
        w.putFloat(0f);
        w.putFloat(0f);
        w.putInt(0);
        w.putLong(slot);
        w.putLong(slot);
        putString(w, "slot" + slot);
        putString(w, "");
        putString(w, "");
        putString(w, "");
        w.putInt(0);
        w.putFloat(20f);
        w.putFloat(5f);
        w.putInt(0);
        w.putInt(0);
        w.putFloat(0f);
        for (int i = 0; i < 9 * LEGACY_HOTBAR_BYTES; i++) {
            w.put((byte) 0);
        }
        w.putInt(0);
        w.put((byte) 0);
        for (int s = 0; s < INVENTORY_SLOTS; s++) {
            w.putInt(0);
        }
        w.putInt(0);
    }

    private static void putString(ByteBuffer w, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        w.putShort((short) bytes.length);
        w.put(bytes);
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
        if (len < 0 || len > (1 << 24)) {
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
