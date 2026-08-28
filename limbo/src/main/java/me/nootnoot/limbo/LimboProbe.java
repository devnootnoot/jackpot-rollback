package me.nootnoot.limbo;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LimboProbe {
    private static final int MAX_FRAME = 1 << 23;

    public record Result(boolean reachedPlay, int configFrames, int playFrames, int chunkPackets,
                         int voidChunksValidated, boolean sawPassthrough, boolean transferred,
                         String transferHost, int transferPort, String failure) {
        public boolean ok() {
            return failure == null;
        }
    }

    private final String host;
    private final int port;
    private final int protocolVersion;
    private final String username;
    private final int worldSections;
    private final boolean sectionFluidCount;
    private final int chunkPacketId;
    private final int keepAliveId;
    private final int transferId;
    private final int customPayloadId;
    private final int disconnectId;
    private final String returnChannel;
    private final long playWindowMillis;

    public LimboProbe(String host, int port, int protocolVersion, String username, int worldSections,
                      boolean sectionFluidCount, int chunkPacketId, int keepAliveId,
                      int transferId, int customPayloadId, int disconnectId, String returnChannel,
                      long playWindowMillis) {
        this.host = host;
        this.port = port;
        this.protocolVersion = protocolVersion;
        this.username = username;
        this.worldSections = worldSections;
        this.sectionFluidCount = sectionFluidCount;
        this.chunkPacketId = chunkPacketId;
        this.keepAliveId = keepAliveId;
        this.transferId = transferId;
        this.customPayloadId = customPayloadId;
        this.disconnectId = disconnectId;
        this.returnChannel = returnChannel;
        this.playWindowMillis = playWindowMillis;
    }

    public Result run() {
        try (Socket socket = new Socket()) {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 15_000);
            socket.setSoTimeout(30_000);
            InputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            handshake(out);
            hello(out);
            if (!login(in, out)) {
                return failed("login was refused for protocol " + protocolVersion);
            }
            int configFrames = configuration(in, out);
            return play(socket, in, out, configFrames);
        } catch (IOException e) {
            return failed(e.toString());
        }
    }

    private static Result failed(String why) {
        return new Result(false, 0, 0, 0, 0, false, false, null, 0, why);
    }

    private void handshake(OutputStream out) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, 0x00);
        writeVarInt(b, protocolVersion);
        writeString(b, host);
        b.write((port >> 8) & 0xFF);
        b.write(port & 0xFF);
        writeVarInt(b, 2);
        writeFrame(out, b.toByteArray());
    }

    private void hello(OutputStream out) throws IOException {
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, Protocol.LOGIN_START);
        writeString(b, username);
        writeLong(b, uuid.getMostSignificantBits());
        writeLong(b, uuid.getLeastSignificantBits());
        writeFrame(out, b.toByteArray());
    }

    private boolean login(InputStream in, OutputStream out) throws IOException {
        while (true) {
            byte[] body = readFrame(in);
            if (body == null) {
                return false;
            }
            int[] pos = {0};
            int id = readVarInt(body, pos);
            if (id == Protocol.LOGIN_DISCONNECT) {
                return false;
            }
            if (id == Protocol.LOGIN_SUCCESS) {
                int[] check = {pos[0]};
                check[0] += 16;
                int nameLength = readVarInt(body, check);
                check[0] += nameLength;
                int properties = readVarInt(body, check);
                if (protocolVersion >= LimboHandler.SESSION_ID_MIN_PROTOCOL) {
                    check[0] += 16;
                }
                if (properties != 0 || check[0] != body.length) {
                    throw new IOException("login_finished body is " + body.length + " bytes but"
                            + " uuid+name+properties"
                            + (protocolVersion >= LimboHandler.SESSION_ID_MIN_PROTOCOL
                            ? "+sessionId" : "") + " parses " + check[0]
                            + " - a real client rejects the packet");
                }
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeVarInt(b, Protocol.LOGIN_ACK);
                writeFrame(out, b.toByteArray());
                return true;
            }
        }
    }

    private int configuration(InputStream in, OutputStream out) throws IOException {
        int frames = 0;
        while (true) {
            byte[] body = readFrame(in);
            if (body == null) {
                throw new IOException("limbo closed during CONFIGURATION after " + frames + " frames");
            }
            frames++;
            int[] pos = {0};
            int id = readVarInt(body, pos);
            if (id == CFG_SELECT_KNOWN_PACKS) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeVarInt(b, Protocol.CFG_KNOWN_PACKS_IN);
                b.write(body, pos[0], body.length - pos[0]);
                writeFrame(out, b.toByteArray());
                continue;
            }
            if (id == CFG_FINISH_OUT) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                writeVarInt(b, Protocol.CFG_FINISH_ACK);
                writeFrame(out, b.toByteArray());
                return frames;
            }
        }
    }

    private static final int CFG_SELECT_KNOWN_PACKS = 0x0E;
    private static final int CFG_FINISH_OUT = 0x03;

    private Result play(Socket socket, InputStream in, OutputStream out, int configFrames)
            throws IOException {
        long deadline = System.currentTimeMillis() + playWindowMillis;
        socket.setSoTimeout(5_000);
        int playFrames = 0;
        int chunks = 0;
        int validated = 0;
        boolean passthrough = false;
        boolean returnRequested = false;
        List<String> problems = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            byte[] body;
            try {
                body = readFrame(in);
            } catch (SocketTimeoutException idle) {
                if (!returnRequested && passthrough && chunks > 0) {
                    requestReturn(out);
                    returnRequested = true;
                    continue;
                }
                break;
            }
            if (body == null) {
                break;
            }
            playFrames++;
            int[] pos = {0};
            int id = readVarInt(body, pos);
            if (id == disconnectId) {
                problems.add("limbo sent a PLAY disconnect");
                break;
            }
            if (id == keepAliveId && body.length - pos[0] == 8) {
                echo(out, PLAY_KEEPALIVE_IN_FOR(protocolVersion), body, pos[0]);
            } else if (id == chunkPacketId) {
                chunks++;
                String bad = validateVoidChunk(body, pos[0]);
                if (bad == null) {
                    validated++;
                } else if (problems.size() < 3) {
                    problems.add(bad);
                }
            } else if (id == transferId) {
                String toHost = readString(body, pos);
                int toPort = readVarInt(body, pos);
                return new Result(true, configFrames, playFrames, chunks, validated, passthrough, true,
                        toHost, toPort, problems.isEmpty() ? null : String.join("; ", problems));
            } else {
                passthrough = true;
            }
            if (!returnRequested && passthrough && chunks > 0 && playFrames > chunks) {
                requestReturn(out);
                returnRequested = true;
            }
        }
        if (problems.isEmpty() && !passthrough) {
            problems.add("the limbo replayed no PLAY packet other than chunks - the join packet"
                    + " never arrived");
        }
        if (problems.isEmpty() && chunks == 0) {
            problems.add("the limbo sent no chunk at all (id 0x"
                    + Integer.toHexString(chunkPacketId) + ") - the client would sit on"
                    + " 'Loading terrain'");
        }
        if (problems.isEmpty() && returnRequested) {
            problems.add("the limbo never answered the " + returnChannel + " payload with a"
                    + " clientbound transfer - play.id.custompayload or play.id.transfer is wrong"
                    + " for protocol " + protocolVersion);
        }
        return new Result(passthrough, configFrames, playFrames, chunks, validated, passthrough,
                false, null, 0, problems.isEmpty() ? null : String.join("; ", problems));
    }

    private static int PLAY_KEEPALIVE_IN_FOR(int protocolVersion) {
        return protocolVersion >= LimboConfig.FLUID_COUNT_MIN_PROTOCOL ? 0x1C : Protocol.PLAY_KEEPALIVE_IN;
    }

    private void requestReturn(OutputStream out) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, customPayloadId);
        writeString(b, returnChannel);
        writeFrame(out, b.toByteArray());
    }

    private String validateVoidChunk(byte[] body, int start) {
        int[] pos = {start};
        pos[0] += 8;
        int heightmaps = readVarInt(body, pos);
        if (heightmaps < 0 || heightmaps > 64) {
            return "chunk heightmap count " + heightmaps + " is not sane";
        }
        for (int i = 0; i < heightmaps; i++) {
            readVarInt(body, pos);
            int longs = readVarInt(body, pos);
            pos[0] += longs * 8;
        }
        int dataSize = readVarInt(body, pos);
        if (dataSize < 0 || pos[0] + dataSize > body.length) {
            return "chunk data size " + dataSize + " runs past the packet";
        }
        int end = pos[0] + dataSize;
        for (int s = 0; s < worldSections; s++) {
            pos[0] += 2;
            if (sectionFluidCount) {
                pos[0] += 2;
            }
            if (!skipPalettedContainer(body, pos, end, 8, 4096)
                    || !skipPalettedContainer(body, pos, end, 3, 64)) {
                return "section " + s + " does not decode with sectionFluidCount=" + sectionFluidCount;
            }
        }
        if (pos[0] != end) {
            return "chunk sections consumed " + (pos[0] - (end - dataSize)) + " of " + dataSize
                    + " bytes with sectionFluidCount=" + sectionFluidCount
                    + " (a real client disconnects here)";
        }
        return null;
    }

    private static boolean skipPalettedContainer(byte[] body, int[] pos, int end, int maxDirectBits,
                                                 int entries) {
        if (pos[0] >= end) {
            return false;
        }
        int bitsPerEntry = body[pos[0]++] & 0xFF;
        if (bitsPerEntry == 0) {
            readVarInt(body, pos);
            return pos[0] <= end;
        }
        if (bitsPerEntry <= maxDirectBits) {
            int paletteLength = readVarInt(body, pos);
            if (paletteLength < 0) {
                return false;
            }
            for (int i = 0; i < paletteLength; i++) {
                readVarInt(body, pos);
            }
        }
        int perLong = 64 / bitsPerEntry;
        int longs = (entries + perLong - 1) / perLong;
        pos[0] += longs * 8;
        return pos[0] <= end;
    }

    private static void echo(OutputStream out, int id, byte[] body, int from) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeVarInt(b, id);
        b.write(body, from, body.length - from);
        writeFrame(out, b.toByteArray());
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

    private static String readString(byte[] data, int[] pos) {
        int length = readVarInt(data, pos);
        if (length < 0 || pos[0] + length > data.length) {
            return "";
        }
        String value = new String(data, pos[0], length, StandardCharsets.UTF_8);
        pos[0] += length;
        return value;
    }

    private static void writeVarInt(OutputStream out, int value) throws IOException {
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
}
