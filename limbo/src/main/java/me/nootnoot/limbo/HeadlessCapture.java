package me.nootnoot.limbo;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public final class HeadlessCapture {
    private static final int MAX_FRAME = 1 << 23;

    private final String host;
    private final int port;
    private final int protocolVersion;
    private final String username;
    private final int viewDistance;
    private final long playWindowMillis;
    private final Ids ids;

    private final List<byte[]> configFrames = new ArrayList<>();
    private final List<byte[]> playFrames = new ArrayList<>();
    private final Map<Integer, Integer> playIdCounts = new TreeMap<>();
    private byte[] loginFinishedBody;

    public record Ids(int loginCbFinished, int loginCbDisconnect, int loginCbCompression,
                      int loginCbCustomQuery, int loginSbAck, int loginSbCustomQueryAnswer,
                      int cfgCbSelectKnownPacks, int cfgCbFinish, int cfgCbKeepAlive, int cfgCbPing,
                      int cfgCbDisconnect, int cfgCbCodeOfConduct, int cfgSbSelectKnownPacks,
                      int cfgSbFinish, int cfgSbKeepAlive, int cfgSbPong, int cfgSbClientInformation,
                      int cfgSbAcceptCodeOfConduct, int playCbKeepAlive, int playCbDisconnect,
                      int playCbPlayerPosition, int playCbChunk, int playSbKeepAlive,
                      int playSbAcceptTeleport, int playSbCustomPayload) {
    }

    public HeadlessCapture(String host, int port, int protocolVersion, String username,
                           int viewDistance, long playWindowMillis, Ids ids) {
        this.host = host;
        this.port = port;
        this.protocolVersion = protocolVersion;
        this.username = username;
        this.viewDistance = viewDistance;
        this.playWindowMillis = playWindowMillis;
        this.ids = ids;
    }

    public List<byte[]> configFrames() {
        return configFrames;
    }

    public List<byte[]> playFrames() {
        return playFrames;
    }

    public byte[] loginFinishedBody() {
        return loginFinishedBody;
    }

    public Map<Integer, Integer> playIdCounts() {
        return playIdCounts;
    }

    public void run() throws IOException {
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 20_000);
            socket.setSoTimeout(60_000);
            InputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            sendHandshake(out);
            sendHello(out);
            login(in, out);
            configuration(in, out);
            play(socket, in, out);
        }
    }

    private void sendHandshake(OutputStream out) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0x00);
        writeVarInt(b, protocolVersion);
        writeString(b, host);
        b.write((port >> 8) & 0xFF);
        b.write(port & 0xFF);
        writeVarInt(b, 2);
        writeFrame(out, b.toByteArray());
    }

    private void sendHello(OutputStream out) throws IOException {
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0x00);
        writeString(b, username);
        writeLong(b, uuid.getMostSignificantBits());
        writeLong(b, uuid.getLeastSignificantBits());
        writeFrame(out, b.toByteArray());
    }

    private void login(InputStream in, OutputStream out) throws IOException {
        while (true) {
            byte[] body = readFrame(in);
            if (body == null) {
                throw new IOException("server closed during LOGIN");
            }
            int[] pos = {0};
            int id = readVarInt(body, pos);
            if (id == ids.loginCbDisconnect()) {
                throw new IOException("server refused login: " + tail(body, pos[0]));
            }
            if (id == ids.loginCbCompression()) {
                throw new IOException("the backend enabled compression - set"
                        + " network-compression-threshold=-1 on it and capture again");
            }
            if (id == ids.loginCbCustomQuery()) {
                int messageId = readVarInt(body, pos);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeVarInt(b, ids.loginSbCustomQueryAnswer());
                writeVarInt(b, messageId);
                b.write(0);
                writeFrame(out, b.toByteArray());
                continue;
            }
            if (id == ids.loginCbFinished()) {
                loginFinishedBody = body;
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeVarInt(b, ids.loginSbAck());
                writeFrame(out, b.toByteArray());
                return;
            }
        }
    }

    private void configuration(InputStream in, OutputStream out) throws IOException {
        sendClientInformation(out, ids.cfgSbClientInformation());
        while (true) {
            byte[] body = readFrame(in);
            if (body == null) {
                throw new IOException("server closed during CONFIGURATION after "
                        + configFrames.size() + " frames");
            }
            configFrames.add(body);
            int[] pos = {0};
            int id = readVarInt(body, pos);
            if (id == ids.cfgCbDisconnect()) {
                throw new IOException("server disconnected during CONFIGURATION: " + tail(body, pos[0]));
            }
            if (id == ids.cfgCbSelectKnownPacks()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeVarInt(b, ids.cfgSbSelectKnownPacks());
                b.write(body, pos[0], body.length - pos[0]);
                writeFrame(out, b.toByteArray());
                continue;
            }
            if (id == ids.cfgCbKeepAlive()) {
                configFrames.remove(configFrames.size() - 1);
                echo(out, ids.cfgSbKeepAlive(), body, pos[0], 8);
                continue;
            }
            if (id == ids.cfgCbPing()) {
                configFrames.remove(configFrames.size() - 1);
                echo(out, ids.cfgSbPong(), body, pos[0], 4);
                continue;
            }
            if (id == ids.cfgCbCodeOfConduct()) {
                configFrames.remove(configFrames.size() - 1);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeVarInt(b, ids.cfgSbAcceptCodeOfConduct());
                writeFrame(out, b.toByteArray());
                continue;
            }
            if (id == ids.cfgCbFinish()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeVarInt(b, ids.cfgSbFinish());
                writeFrame(out, b.toByteArray());
                return;
            }
        }
    }

    private void play(Socket socket, InputStream in, OutputStream out) throws IOException {
        long deadline = System.currentTimeMillis() + playWindowMillis;
        socket.setSoTimeout(15_000);
        while (System.currentTimeMillis() < deadline) {
            byte[] body;
            try {
                body = readFrame(in);
            } catch (java.net.SocketTimeoutException idle) {
                break;
            }
            if (body == null) {
                break;
            }
            int[] pos = {0};
            int id = readVarInt(body, pos);
            playIdCounts.merge(id, 1, Integer::sum);
            if (id == ids.playCbDisconnect()) {
                throw new IOException("server disconnected during PLAY: " + tail(body, pos[0]));
            }
            playFrames.add(body);
            if (id == ids.playCbKeepAlive()) {
                echo(out, ids.playSbKeepAlive(), body, pos[0], 8);
            } else if (id == ids.playCbPlayerPosition()) {
                int teleportId = readVarInt(body, pos);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeVarInt(b, ids.playSbAcceptTeleport());
                writeVarInt(b, teleportId);
                writeFrame(out, b.toByteArray());
            }
        }
    }

    private void sendClientInformation(OutputStream out, int id) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, id);
        writeString(b, "en_us");
        b.write(viewDistance);
        writeVarInt(b, 0);
        b.write(1);
        b.write(0);
        writeVarInt(b, 1);
        b.write(0);
        b.write(1);
        writeVarInt(b, 0);
        writeFrame(out, b.toByteArray());
    }

    private static void echo(OutputStream out, int id, byte[] body, int from, int length)
            throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, id);
        b.write(body, from, Math.min(length, body.length - from));
        writeFrame(out, b.toByteArray());
    }

    private static String tail(byte[] body, int from) {
        return new String(body, from, body.length - from, StandardCharsets.UTF_8)
                .replaceAll("[^\\x20-\\x7E]", ".");
    }

    public static void write(Path path, List<byte[]> frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] body : frames) {
            writeVarInt(out, body.length);
            out.write(body);
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, out.toByteArray());
    }

    private static void writeFrame(OutputStream out, byte[] body) throws IOException {
        writeVarInt(out, body.length);
        out.write(body);
        out.flush();
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int length = readVarInt(in);
        if (length == Integer.MIN_VALUE) {
            return null;
        }
        if (length < 0 || length > MAX_FRAME) {
            throw new IOException("bad frame length " + length);
        }
        byte[] body = in.readNBytes(length);
        return body.length < length ? null : body;
    }

    private static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            int b = in.read();
            if (b == -1) {
                return shift == 0 ? Integer.MIN_VALUE : -1;
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IOException("VarInt too big");
    }

    private static int readVarInt(byte[] data, int[] pos) {
        int value = 0;
        int shift = 0;
        while (shift < 35) {
            if (pos[0] >= data.length) {
                return -1;
            }
            int b = data[pos[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return -1;
    }

    static void writeVarInt(java.io.OutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, raw.length);
        out.write(raw);
    }

    private static void writeLong(ByteArrayOutputStream out, long value) {
        for (int i = 7; i >= 0; i--) {
            out.write((int) ((value >> (i * 8)) & 0xFF));
        }
    }

    private static int prop(String key) {
        String v = System.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required system property " + key);
        }
        v = v.trim();
        return v.startsWith("0x") ? Integer.parseInt(v.substring(2), 16) : Integer.parseInt(v);
    }

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public static void main(String[] args) throws Exception {
        Ids ids = new Ids(
                prop("id.login.cb.finished"), prop("id.login.cb.disconnect"),
                prop("id.login.cb.compression"), prop("id.login.cb.customQuery"),
                prop("id.login.sb.ack"), prop("id.login.sb.customQueryAnswer"),
                prop("id.cfg.cb.selectKnownPacks"), prop("id.cfg.cb.finish"),
                prop("id.cfg.cb.keepAlive"), prop("id.cfg.cb.ping"), prop("id.cfg.cb.disconnect"),
                prop("id.cfg.cb.codeOfConduct"), prop("id.cfg.sb.selectKnownPacks"),
                prop("id.cfg.sb.finish"), prop("id.cfg.sb.keepAlive"), prop("id.cfg.sb.pong"),
                prop("id.cfg.sb.clientInformation"), prop("id.cfg.sb.acceptCodeOfConduct"),
                prop("id.play.cb.keepAlive"), prop("id.play.cb.disconnect"),
                prop("id.play.cb.playerPosition"), prop("id.play.cb.chunk"),
                prop("id.play.sb.keepAlive"), prop("id.play.sb.acceptTeleport"),
                prop("id.play.sb.customPayload"));

        HeadlessCapture capture = new HeadlessCapture(
                prop("capture.host", "127.0.0.1"),
                Integer.parseInt(prop("capture.port", "25599")),
                prop("capture.protocol"),
                prop("capture.username", "CaptureBot"),
                Integer.parseInt(prop("capture.viewDistance", "8")),
                Long.parseLong(prop("capture.play.ms", "12000")),
                ids);
        capture.run();

        Path configOut = Path.of(prop("capture.config.out", "config.bin"));
        Path playOut = Path.of(prop("capture.play.out", "play.bin"));
        write(configOut, capture.configFrames());
        write(playOut, capture.playFrames());

        int chunks = capture.playIdCounts().getOrDefault(ids.playCbChunk(), 0);
        System.out.println("[capture] protocol " + capture.protocolVersion
                + ": " + capture.configFrames().size() + " config frames -> " + configOut
                + " (" + Files.size(configOut) + "B), "
                + capture.playFrames().size() + " play frames -> " + playOut
                + " (" + Files.size(playOut) + "B)");
        System.out.println("[capture] chunk packets (id 0x"
                + Integer.toHexString(ids.playCbChunk()).toUpperCase(java.util.Locale.ROOT) + "): " + chunks);
        System.out.println("[capture] login_finished body was " + capture.loginFinishedBody().length
                + " bytes (id + uuid + name + properties)");
        if (capture.configFrames().isEmpty() || chunks == 0) {
            throw new IllegalStateException("capture is unusable: " + capture.configFrames().size()
                    + " config frames, " + chunks + " chunk packets");
        }
        Map<String, Integer> summary = new LinkedHashMap<>();
        capture.playIdCounts().forEach((id, count) ->
                summary.put("0x" + Integer.toHexString(id).toUpperCase(java.util.Locale.ROOT), count));
        System.out.println("[capture] play ids seen: " + summary);
    }
}
