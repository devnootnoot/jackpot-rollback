package me.nootnoot.limbo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A man-in-the-middle capture tool that writes the {@code config.bin} / {@code play.bin} files the
 * limbo replays. Run it, point a real client at it, and it forwards to a real 1.21.11 server while
 * recording the clientbound configuration- and play-phase frames.
 *
 * <p><b>The backend server MUST be offline mode ({@code online-mode=false}) AND have compression off
 * ({@code network-compression-threshold=-1})</b> — otherwise frames are encrypted/compressed and the
 * framing parser can't split them.
 *
 * <p>Run: {@code ./gradlew :limbo:runCaptureProxy --args="25600 <backendHost> <backendPort>"}. Then
 * connect a 1.21.11 client to {@code localhost:25600}, join the world, and once you've spawned,
 * disconnect — {@code config.bin} and {@code play.bin} are written to the working dir.
 */
public final class CaptureProxy {

    private enum Phase { HANDSHAKE, STATUS, LOGIN, CONFIG, PLAY }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("usage: CaptureProxy <listenPort> <backendHost> <backendPort> [configOut] [playOut]");
            return;
        }
        int listenPort = Integer.parseInt(args[0]);
        String backendHost = args[1];
        int backendPort = Integer.parseInt(args[2]);
        Path configOut = Path.of(args.length > 3 ? args[3] : "config.bin");
        Path playOut = Path.of(args.length > 4 ? args[4] : "play.bin");
        long captureMs = args.length > 5 ? Long.parseLong(args[5]) : 5000; // play-phase capture window

        System.out.println("[capture] listening on " + listenPort + " -> " + backendHost + ":" + backendPort);
        System.out.println("[capture] backend MUST be online-mode=false AND network-compression-threshold=-1");

        try (ServerSocket server = new ServerSocket(listenPort)) {
            Socket client = server.accept();
            Socket backend = new Socket(backendHost, backendPort);
            System.out.println("[capture] client connected; join the world then disconnect to finish.");

            ByteArrayOutputStream configBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream playBuf = new ByteArrayOutputStream();
            AtomicReference<Phase> phase = new AtomicReference<>(Phase.HANDSHAKE);
            long[] playStart = {0}; // millis when PLAY began; play.bin captures for captureMs after
            int[] counts = {0, 0}; // config frames, play frames
            // Distinct play-phase packet ids seen each way — reveals the version's keepalive / custom-
            // payload ids for the limbo's play.id.* config. Stay connected ~20s for the keepalive.
            Set<Integer> sbPlayIds = ConcurrentHashMap.newKeySet();
            Set<Integer> cbPlayIds = ConcurrentHashMap.newKeySet();
            // Auto-detect: keepalive long round-trip (clientbound id -> echoed serverbound), and the
            // serverbound plugin-message id (the packet carrying "minecraft:brand").
            java.util.Map<Long, Integer> kaCandidates = new ConcurrentHashMap<>();
            int[] detected = {-1, -1, -1}; // keepalive clientbound, keepalive serverbound, plugin-msg serverbound

            // Serverbound (client -> backend): drives phase transitions.
            Thread sb = new Thread(() -> {
                try (InputStream in = client.getInputStream(); OutputStream out = backend.getOutputStream()) {
                    byte[] body;
                    while ((body = readFrame(in)) != null) {
                        int id = peekVarInt(body, 0);
                        switch (phase.get()) {
                            case HANDSHAKE -> {
                                int next = handshakeNextState(body);
                                phase.set(next == 1 ? Phase.STATUS : Phase.LOGIN);
                            }
                            case LOGIN -> {
                                if (id == Protocol.LOGIN_ACK) {
                                    phase.set(Phase.CONFIG);
                                    System.out.println("[capture] -> CONFIG");
                                }
                            }
                            case CONFIG -> {
                                if (id == Protocol.CFG_FINISH_ACK) {
                                    playStart[0] = System.currentTimeMillis();
                                    phase.set(Phase.PLAY);
                                    System.out.println("[capture] -> PLAY (capturing play for " + captureMs + "ms)");
                                }
                            }
                            case PLAY -> {
                                sbPlayIds.add(id);
                                detectFromServerbound(body, kaCandidates, detected);
                            }
                            default -> {
                            }
                        }
                        writeFrame(out, body);
                    }
                } catch (IOException ignored) {
                } finally {
                    closeQuietly(backend);
                    closeQuietly(client);
                }
            }, "capture-serverbound");

            // Clientbound (backend -> client): captured per phase.
            Thread cb = new Thread(() -> {
                try (InputStream in = backend.getInputStream(); OutputStream out = client.getOutputStream()) {
                    byte[] body;
                    while ((body = readFrame(in)) != null) {
                        switch (phase.get()) {
                            case CONFIG -> {
                                appendFrame(configBuf, body);
                                counts[0]++;
                            }
                            case PLAY -> {
                                cbPlayIds.add(peekVarInt(body, 0));
                                recordKeepaliveCandidate(body, kaCandidates);
                                if (playStart[0] != 0 && System.currentTimeMillis() - playStart[0] < captureMs) {
                                    appendFrame(playBuf, body);
                                    counts[1]++;
                                }
                            }
                            default -> {
                            }
                        }
                        writeFrame(out, body);
                    }
                } catch (IOException ignored) {
                } finally {
                    closeQuietly(backend);
                    closeQuietly(client);
                }
            }, "capture-clientbound");

            sb.start();
            cb.start();
            sb.join();
            cb.join();

            Files.write(configOut, configBuf.toByteArray());
            Files.write(playOut, playBuf.toByteArray());
            System.out.println("[capture] wrote " + configOut + " (" + counts[0] + " frames), "
                    + playOut + " (" + counts[1] + " frames)");
            System.out.println("[capture] observed PLAY clientbound ids: " + toHex(cbPlayIds));
            System.out.println("[capture] observed PLAY serverbound ids: " + toHex(sbPlayIds));
            System.out.println("[capture] === DETECTED play ids (paste into limbo.properties) ===");
            System.out.printf("[capture] play.id.keepalive=%s   (clientbound keep-alive)%n",
                    detected[0] >= 0 ? String.format("0x%02X", detected[0]) : "?? (stay connected ~20s and re-run)");
            System.out.printf("[capture] play.id.custompayload=%s   (serverbound plugin message)%n",
                    detected[2] >= 0 ? String.format("0x%02X", detected[2]) : "?? (not seen)");
            if (detected[1] >= 0) {
                System.out.printf("[capture] (serverbound keep-alive id is 0x%02X, FYI)%n", detected[1]);
            }
            System.out.println("[capture] play.id.transfer still needs a protocol report (servers don't send Transfer).");
            if (counts[0] == 0 || counts[1] == 0) {
                System.out.println("[capture] WARNING: a phase captured 0 frames — check LOGIN_ACK/CFG_FINISH_ACK ids "
                        + "in Protocol.java and that the backend is offline+uncompressed.");
            }
        }
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int pos = 0;
        int b;
        do {
            b = in.read();
            if (b == -1) {
                return Integer.MIN_VALUE;
            }
            value |= (b & 0x7F) << pos;
            pos += 7;
            if (pos >= 35) {
                throw new IOException("VarInt too big");
            }
        } while ((b & 0x80) != 0);
        return value;
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int len = readVarInt(in);
        if (len == Integer.MIN_VALUE) {
            return null;
        }
        if (len < 0) {
            throw new IOException("bad frame length");
        }
        byte[] body = in.readNBytes(len);
        return body.length < len ? null : body;
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeFrame(OutputStream out, byte[] body) throws IOException {
        writeVarInt(out, body.length);
        out.write(body);
        out.flush();
    }

    private static void appendFrame(ByteArrayOutputStream buf, byte[] body) {
        try {
            writeVarInt(buf, body.length);
            buf.write(body);
        } catch (IOException ignored) {
            // ByteArrayOutputStream does not actually throw
        }
    }

    /** Clientbound play packet shaped {@code [id][8-byte long]} is a keep-alive candidate. */
    private static void recordKeepaliveCandidate(byte[] body, java.util.Map<Long, Integer> map) {
        int[] cur = {0};
        int id = readVarIntAt(body, cur);
        if (body.length - cur[0] == 8) {
            map.put(readLong(body, cur[0]), id);
        }
    }

    /**
     * Identify the keep-alive ids (a serverbound 8-byte long matching a recorded clientbound one) and
     * the serverbound plugin-message id (the packet whose first string is {@code minecraft:brand}).
     * Writes into {@code detected = [kaClientbound, kaServerbound, pluginMsgServerbound]}.
     */
    private static void detectFromServerbound(byte[] body, java.util.Map<Long, Integer> kaCandidates, int[] detected) {
        int[] cur = {0};
        int id = readVarIntAt(body, cur);
        if (body.length - cur[0] == 8) {
            Integer clientId = kaCandidates.get(readLong(body, cur[0]));
            if (clientId != null && detected[1] < 0) {
                detected[0] = clientId;
                detected[1] = id;
                System.out.printf("[capture] detected KEEP-ALIVE: clientbound=0x%02X serverbound=0x%02X%n", clientId, id);
            }
            return;
        }
        if (detected[2] < 0) {
            try {
                String channel = readStringAt(body, cur);
                if ("minecraft:brand".equals(channel)) {
                    detected[2] = id;
                    System.out.printf("[capture] detected PLUGIN MESSAGE (brand): serverbound=0x%02X%n", id);
                }
            } catch (RuntimeException ignored) {
                // not a plugin message
            }
        }
    }

    private static long readLong(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[off + i] & 0xFFL);
        }
        return v;
    }

    private static String readStringAt(byte[] b, int[] cur) {
        int len = readVarIntAt(b, cur);
        if (len < 0 || cur[0] + len > b.length) {
            throw new IllegalArgumentException("bad string");
        }
        String s = new String(b, cur[0], len, java.nio.charset.StandardCharsets.UTF_8);
        cur[0] += len;
        return s;
    }

    private static int peekVarInt(byte[] data, int offset) {
        int value = 0;
        int pos = 0;
        int i = offset;
        while (true) {
            int b = data[i++] & 0xFF;
            value |= (b & 0x7F) << pos;
            pos += 7;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
    }

    private static int handshakeNextState(byte[] body) {
        int[] cur = {0};
        readVarIntAt(body, cur); // packet id (0)
        readVarIntAt(body, cur); // protocol version
        int strLen = readVarIntAt(body, cur); // server address length
        cur[0] += strLen; // skip address
        cur[0] += 2; // port (unsigned short)
        return readVarIntAt(body, cur); // next state
    }

    private static int readVarIntAt(byte[] data, int[] cur) {
        int value = 0;
        int pos = 0;
        while (true) {
            int b = data[cur[0]++] & 0xFF;
            value |= (b & 0x7F) << pos;
            pos += 7;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
    }

    private static String toHex(Set<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : new TreeSet<>(ids)) {
            sb.append(sb.isEmpty() ? "" : " ").append(String.format("0x%02X", id));
        }
        return sb.toString();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private CaptureProxy() {
    }
}
