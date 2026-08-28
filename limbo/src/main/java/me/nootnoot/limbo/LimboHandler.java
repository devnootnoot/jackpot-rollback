package me.nootnoot.limbo;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.concurrent.ScheduledFuture;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-connection state machine. Handles handshake/status/login and keep-alive dynamically; replays the
 * captured configuration and play packets to drive the client into a spawned state; and, on the return
 * custom payload, issues a clientbound Transfer back to the proxy.
 */
public class LimboHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger LOG = LoggerFactory.getLogger("limbo");

    static final int BAKE_PROTOCOL = 775;

    static final int SESSION_ID_MIN_PROTOCOL = 776;

    private enum State { HANDSHAKE, STATUS, LOGIN, CONFIGURATION, PLAY }

    private final LimboConfig config;
    private final List<Capture> captures;
    private List<byte[]> configPackets;
    private List<byte[]> playPackets;
    private final ArenaControl arenaControl;
    /** The captured clientbound chunk-data ('level_chunk_with_light') packet id — the ONLY id we rewrite. */
    private int chunkId;
    private int selectedProtocol;
    private int clientProtocol;
    private String selectedVersionName;
    private int playKeepAliveId;
    private int playTransferId;
    private int playCustomPayloadId;
    private boolean sectionFluidCount;
    private BlockStateRemap arenaRemap;
    private Set<Integer> dropIds;

    private State state = State.HANDSHAKE;
    private boolean unsupportedProtocol;
    private String username = "player";
    private UUID uuid;
    private java.util.concurrent.CompletableFuture<ArenaControl.Assignment> arenaFuture;
    private ScheduledFuture<?> keepAlive;

    public LimboHandler(LimboConfig config, List<Capture> captures, ArenaControl arenaControl) {
        this.config = config;
        this.captures = captures;
        this.arenaControl = arenaControl;
        Capture def = captures.get(0);
        this.configPackets = def.config;
        this.playPackets = def.play;
        this.chunkId = def.chunkId;
        this.selectedProtocol = def.protocolVersion;
        this.selectedVersionName = def.versionName;
        this.playKeepAliveId = def.keepAliveId;
        this.playTransferId = def.transferId;
        this.playCustomPayloadId = def.customPayloadId;
        this.sectionFluidCount = def.sectionFluidCount;
        this.dropIds = def.dropIds;
        this.velocityForwarding = config.forwardingSecret != null && !config.forwardingSecret.isEmpty();
    }

    /**
     * Find the captured chunk-data packet id by frequency: the server sends one {@code level_chunk_with_light}
     * per loaded chunk (dozens), but only a few {@code light_update}s — so the most common large-packet id is
     * the chunk id. Detecting it from the capture (rather than hardcoding a version-specific number) keeps the
     * limbo correct whatever protocol the client speaks, and ensures we only ever rewrite real chunk packets.
     */
    static int detectChunkId(List<byte[]> packets, int minBytes) {
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (byte[] body : packets) {
            if (body.length <= minBytes) {
                continue;
            }
            int[] pos = {0};
            int id = readVarIntAt(body, pos);
            if (id >= 0) {
                counts.merge(id, 1, Integer::sum);
            }
        }
        int best = -1, bestCount = 0;
        for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
        int id = Proto.readVarInt(frame);
        switch (state) {
            case HANDSHAKE -> handleHandshake(frame);
            case STATUS -> handleStatus(ctx, id, frame);
            case LOGIN -> handleLogin(ctx, id, frame);
            case CONFIGURATION -> handleConfiguration(ctx, id);
            case PLAY -> handlePlay(ctx, id, frame);
        }
    }

    private void handleHandshake(ByteBuf frame) {
        // id already read; fields: protocolVersion, serverAddress, port, nextState
        int clientProtocol = Proto.readVarInt(frame);
        Proto.readString(frame);
        frame.readUnsignedShort();
        int next = Proto.readVarInt(frame);
        Capture cap = null;
        for (Capture c : captures) {
            if (c.protocolVersion == clientProtocol) {
                cap = c;
                break;
            }
        }
        this.unsupportedProtocol = cap == null;
        if (cap == null) {
            cap = captures.get(0);
            LOG.error("[limbo] NO CAPTURE for client protocol {} - the limbo only has {}. Replaying"
                    + " another version's configuration would disconnect the client with a decode"
                    + " error, so this login is refused. Record the missing capture with"
                    + " 'gradlew devLimboCapture -PcaptureVersion=<mc version>'.",
                    clientProtocol, servedProtocols());
        }
        this.configPackets = cap.config;
        this.playPackets = cap.play;
        this.chunkId = cap.chunkId;
        this.selectedProtocol = cap.protocolVersion;
        this.selectedVersionName = cap.versionName;
        this.playKeepAliveId = cap.keepAliveId;
        this.playTransferId = cap.transferId;
        this.playCustomPayloadId = cap.customPayloadId;
        this.sectionFluidCount = cap.sectionFluidCount;
        this.clientProtocol = clientProtocol;
        this.arenaRemap = clientProtocol != BAKE_PROTOCOL ? BlockStateRemap.forTarget(clientProtocol) : null;
        this.dropIds = cap.dropIds;
        LOG.info("[limbo] client protocol {} -> serving capture protocol={} ({})",
                clientProtocol, cap.protocolVersion, cap.versionName);
        state = (next == 1) ? State.STATUS : State.LOGIN; // 2 = login, 3 = transfer
    }

    private void handleStatus(ChannelHandlerContext ctx, int id, ByteBuf frame) {
        if (id == Protocol.ST_REQUEST) {
            String json = "{\"version\":{\"name\":\"" + selectedVersionName + "\",\"protocol\":"
                    + selectedProtocol + "},\"players\":{\"max\":2,\"online\":0},"
                    + "\"description\":{\"text\":\"Rollback Limbo\"}}";
            send(ctx, Protocol.ST_RESPONSE, b -> Proto.writeString(b, json));
        } else if (id == Protocol.ST_PING) {
            long payload = frame.readLong();
            send(ctx, Protocol.ST_PONG, b -> b.writeLong(payload));
        }
    }

    /** Arbitrary message id we tag our velocity:player_info request with, to match against the response. */
    private static final int VELOCITY_FORWARDING_MSG_ID = 0x52;
    private final boolean velocityForwarding;

    private void handleLogin(ChannelHandlerContext ctx, int id, ByteBuf frame) {
        if (id == Protocol.LOGIN_START) {
            username = Proto.readString(frame);
            uuid = frame.readableBytes() >= 16 ? Proto.readUuid(frame) : offlineUuid(username);
            if (unsupportedProtocol) {
                refuseUnsupported(ctx);
                return;
            }
            if (velocityForwarding) {
                // Behind a Velocity proxy: ask for the forwarded player info before LoginSuccess. An empty
                // request body makes Velocity reply with the v1 (no-key) format, which keeps parsing simple.
                send(ctx, Protocol.LOGIN_PLUGIN_REQUEST, b -> {
                    Proto.writeVarInt(b, VELOCITY_FORWARDING_MSG_ID);
                    Proto.writeString(b, "velocity:player_info");
                });
            } else {
                sendLoginSuccess(ctx); // standalone offline mode (dev)
            }
        } else if (id == Protocol.LOGIN_PLUGIN_RESPONSE) {
            int messageId = Proto.readVarInt(frame);
            boolean success = frame.readableBytes() > 0 && frame.readBoolean();
            if (!success || messageId != VELOCITY_FORWARDING_MSG_ID || !verifyForwarding(frame)) {
                LOG.warn("[limbo] velocity forwarding failed for {} — is forwarding.secret correct + the "
                        + "proxy on modern forwarding?", username);
                ctx.close();
                return;
            }
            sendLoginSuccess(ctx);
        } else if (id == Protocol.LOGIN_ACK) {
            state = State.CONFIGURATION;
            replay(ctx, configPackets); // registry data ... finish configuration
        }
    }

    private String servedProtocols() {
        StringBuilder sb = new StringBuilder();
        for (Capture c : captures) {
            sb.append(sb.isEmpty() ? "" : ", ").append(c.protocolVersion).append(" (")
                    .append(c.versionName).append(')');
        }
        return sb.toString();
    }

    private void refuseUnsupported(ChannelHandlerContext ctx) {
        String json = "{\"text\":\"This limbo has no packet capture for your Minecraft version"
                + " (protocol " + clientProtocol + ").\\nIt can only serve: " + servedProtocols()
                + "\"}";
        send(ctx, Protocol.LOGIN_DISCONNECT, b -> Proto.writeString(b, json));
        LOG.error("[limbo] refused {} on protocol {} - no capture", username, clientProtocol);
        ctx.close();
    }

    private void sendLoginSuccess(ChannelHandlerContext ctx) {
        send(ctx, Protocol.LOGIN_SUCCESS, b -> {
            Proto.writeUuid(b, uuid);
            Proto.writeString(b, username);
            Proto.writeVarInt(b, 0); // 0 properties
            if (selectedProtocol >= SESSION_ID_MIN_PROTOCOL) {
                Proto.writeUuid(b, uuid);
            }
        });
        if (config.nativeTerrainEnabled && arenaControl != null) {
            arenaFuture = arenaControl.beginResolve(uuid);
        }
        LOG.info("[limbo] login: {} ({})", username, uuid);
    }

    /**
     * Verify + apply Velocity modern forwarding from a {@code velocity:player_info} response body:
     * {@code [32-byte HMAC-SHA256 signature][version varint][address][uuid][username][properties...]}.
     * The HMAC is over everything after the signature, keyed by the shared forwarding secret. On success
     * the real (forwarded) uuid + username replace the offline ones.
     */
    private boolean verifyForwarding(ByteBuf frame) {
        if (frame.readableBytes() < 32) {
            return false;
        }
        byte[] signature = new byte[32];
        frame.readBytes(signature);
        byte[] data = new byte[frame.readableBytes()];
        frame.getBytes(frame.readerIndex(), data);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.forwardingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            if (!MessageDigest.isEqual(signature, mac.doFinal(data))) {
                return false; // bad signature — secret mismatch
            }
        } catch (Exception e) {
            return false;
        }
        Proto.readVarInt(frame);   // forwarding version (1)
        Proto.readString(frame);   // player address (unused)
        uuid = Proto.readUuid(frame);
        username = Proto.readString(frame);
        return true;
    }

    private void handleConfiguration(ChannelHandlerContext ctx, int id) {
        if (id == Protocol.CFG_FINISH_ACK) {
            state = State.PLAY;
            replayPlay(ctx); // join game ... spawn (terrain stripped → void)
            startKeepAlive(ctx);
            LOG.info("[limbo] {} entered PLAY", username);
        }
        // serverbound known-packs / keep-alive / brand are ignored
    }

    private void handlePlay(ChannelHandlerContext ctx, int id, ByteBuf frame) {
        if (id == playCustomPayloadId) {
            String channel = Proto.readString(frame);
            if (config.returnChannel.equals(channel)) {
                sendTransfer(ctx);
            }
        }
        // keep-alive responses, teleport confirms, movement, etc. are ignored
    }

    private void startKeepAlive(ChannelHandlerContext ctx) {
        if (!config.keepAliveEnabled) {
            return;
        }
        keepAlive = ctx.executor().scheduleAtFixedRate(
                () -> send(ctx, playKeepAliveId, b -> b.writeLong(System.nanoTime())),
                config.keepAliveSeconds, config.keepAliveSeconds, TimeUnit.SECONDS);
    }

    private void sendTransfer(ChannelHandlerContext ctx) {
        LOG.info("[limbo] returning {} to proxy {}:{}", username, config.proxyHost, config.proxyPort);
        send(ctx, playTransferId, b -> {
            Proto.writeString(b, config.proxyHost);
            Proto.writeVarInt(b, config.proxyPort);
        });
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (keepAlive != null) {
            keepAlive.cancel(false);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.debug("[limbo] connection error: {}", cause.toString());
        ctx.close();
    }

    private void replay(ChannelHandlerContext ctx, List<byte[]> packets) {
        for (byte[] body : packets) {
            ByteBuf buf = ctx.alloc().buffer(body.length);
            buf.writeBytes(body);
            ctx.write(buf);
        }
        ctx.flush();
    }

    private void replayPlay(ChannelHandlerContext ctx) {
        ArenaControl.Assignment arena = resolveArena();
        if (arena != null && arena.sectionsPerColumn != config.worldSectionCount) {
            LOG.error("[limbo] arena section count {} != dimension section count {} — refusing native serve, "
                    + "voiding (set mcleagues native-section-count = play.world.sections)",
                    arena.sectionsPerColumn, config.worldSectionCount);
            arena = null;
        }
        if (arena != null && clientProtocol != BAKE_PROTOCOL && arenaRemap == null) {
            LOG.warn("[limbo] native arena: no block-state remap table to transcode bake protocol {} -> {} ({}) "
                    + "— voiding (the mod paints the arena instead)", BAKE_PROTOCOL, clientProtocol, selectedVersionName);
            arena = null;
        }
        if (arena != null) {
            replayPlayArena(ctx, arena);
            return;
        }
        int passthrough = 0, voided = 0, dropped = 0;
        for (byte[] body : playPackets) {
            int[] idPos = {0};
            if (dropIds.contains(readVarIntAt(body, idPos))) {
                dropped++;
                continue;
            }
            int[] coords = config.stripWorldEnabled ? chunkCoords(body) : null;
            if (coords != null) {
                writeEmptyChunk(ctx, chunkId, coords[0], coords[1]);
                voided++;
            } else {
                ByteBuf buf = ctx.alloc().buffer(body.length);
                buf.writeBytes(body);
                ctx.write(buf);
                passthrough++;
            }
        }
        ctx.flush();
        if (config.stripWorldEnabled) {
            LOG.info("[limbo] play replay: {} passthrough, {} terrain chunks → void, {} entity/block packets dropped",
                    passthrough, voided, dropped);
        }
    }

    private ArenaControl.Assignment resolveArena() {
        if (arenaFuture == null) {
            return null;
        }
        try {
            return arenaFuture.get(config.nativeWaitMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.warn("[limbo] arena lookup not ready within {}ms for {} — serving void (arena may not render)",
                    config.nativeWaitMs, uuid);
            return null;
        } catch (Exception e) {
            LOG.debug("[limbo] arena lookup failed for {}: {}", uuid, e.toString());
            return null;
        }
    }

    private void replayPlayArena(ChannelHandlerContext ctx, ArenaControl.Assignment arena) {
        Set<Long> served = new HashSet<>();
        int passthrough = 0, voided = 0;
        for (byte[] body : playPackets) {
            int[] idPos = {0};
            if (dropIds.contains(readVarIntAt(body, idPos))) {
                continue;
            }
            int[] coords = chunkCoords(body);
            if (coords == null) {
                ByteBuf buf = ctx.alloc().buffer(body.length);
                buf.writeBytes(body);
                ctx.write(buf);
                passthrough++;
                continue;
            }
            long key = ArenaControl.columnKey(coords[0], coords[1]);
            byte[] sectionData = arena.columns.get(key);
            if (sectionData != null) {
                writeArenaChunk(ctx, chunkId, coords[0], coords[1], sectionData, arena.sectionsPerColumn);
                served.add(key);
            } else {
                writeEmptyChunk(ctx, chunkId, coords[0], coords[1]);
                voided++;
            }
        }
        int injected = 0;
        for (Map.Entry<Long, byte[]> e : arena.columns.entrySet()) {
            if (served.contains(e.getKey())) {
                continue;
            }
            int cx = (int) (e.getKey() >> 32);
            int cz = (int) (long) e.getKey();
            writeArenaChunk(ctx, chunkId, cx, cz, e.getValue(), arena.sectionsPerColumn);
            injected++;
        }
        ctx.flush();
        LOG.info("[limbo] play replay: arena slot {} → {} served, {} injected, {} void, {} passthrough",
                arena.slot, served.size(), injected, voided, passthrough);
    }

    /**
     * If {@code body} is a captured {@code level_chunk_with_light} packet, write an empty chunk at its
     * coordinates and return true; otherwise leave it untouched and return false. A chunk is a candidate by
     * its configured id, or — when none is set — by being over the size threshold; either way the full chunk
     * header (Int X, Int Z, heightmap array, data size) is parsed and bounds-checked before we touch it, so a
     * {@code light_update} packet (same leading shape, different layout) can never be mistaken for a chunk and
     * corrupted. Everything that doesn't validate passes through verbatim.
     */
    private int[] chunkCoords(byte[] body) {
        int[] pos = {0};
        int id = readVarIntAt(body, pos);
        if (id < 0) {
            return null;
        }
        if (id != chunkId || pos[0] + 8 > body.length) {
            return null; // only ever rewrite the detected chunk-data id — leave light_update etc. untouched
        }
        int cx = readIntAt(body, pos);
        int cz = readIntAt(body, pos);
        if (Math.abs(cx) > 1_875_000 || Math.abs(cz) > 1_875_000) {
            return null; // not plausible chunk coords
        }
        // Heightmaps: a prefixed array of {VarInt type, prefixed long array}. Walk it to confirm the layout.
        int heightmaps = readVarIntAt(body, pos);
        if (heightmaps < 0 || heightmaps > 64) {
            return null;
        }
        for (int i = 0; i < heightmaps; i++) {
            if (readVarIntAt(body, pos) < 0) {
                return null; // heightmap type
            }
            int longs = readVarIntAt(body, pos);
            if (longs < 0 || longs > 4096) {
                return null;
            }
            pos[0] += longs * 8;
            if (pos[0] < 0 || pos[0] > body.length) {
                return null;
            }
        }
        // Data: a prefixed byte array of chunk sections. Its size must fit inside the remaining body.
        int dataSize = readVarIntAt(body, pos);
        if (dataSize < 0 || pos[0] + dataSize > body.length) {
            return null;
        }
        return new int[]{cx, cz};
    }

    /** Writes an all-air chunk (1.21.5+ format: optional heightmaps omitted, BPE-0 palettes, empty light). */
    private void writeEmptyChunk(ChannelHandlerContext ctx, int id, int cx, int cz) {
        ByteBuf sections = ctx.alloc().buffer();
        for (int i = 0; i < config.worldSectionCount; i++) {
            sections.writeShort(0);            // non-air block count
            if (sectionFluidCount) {
                sections.writeShort(0);
            }
            sections.writeByte(0);             // block states: bits per entry 0 (single-valued)
            Proto.writeVarInt(sections, 0);    // palette = minecraft:air (state id 0)
            sections.writeByte(0);             // biomes: bits per entry 0 (single-valued)
            Proto.writeVarInt(sections, 0);    // palette = biome id 0
        }

        ByteBuf buf = ctx.alloc().buffer();
        Proto.writeVarInt(buf, id);
        buf.writeInt(cx);
        buf.writeInt(cz);
        Proto.writeVarInt(buf, 0);             // heightmaps: empty array (client fills with min values)
        Proto.writeVarInt(buf, sections.readableBytes());
        buf.writeBytes(sections);
        sections.release();
        Proto.writeVarInt(buf, 0);             // block entities: empty
        Proto.writeVarInt(buf, 0);             // sky light mask
        Proto.writeVarInt(buf, 0);             // block light mask
        Proto.writeVarInt(buf, 0);             // empty sky light mask
        Proto.writeVarInt(buf, 0);             // empty block light mask
        Proto.writeVarInt(buf, 0);             // sky light arrays
        Proto.writeVarInt(buf, 0);             // block light arrays
        ctx.write(buf);
    }

    private static final byte[] FULL_BRIGHT = new byte[2048];

    static {
        Arrays.fill(FULL_BRIGHT, (byte) 0xFF);
    }

    private void writeArenaChunk(ChannelHandlerContext ctx, int id, int cx, int cz, byte[] sectionData, int sectionCount) {
        if (clientProtocol != BAKE_PROTOCOL) {
            byte[] transcoded = arenaRemap == null ? null
                    : ArenaChunkTranscoder.transcode(sectionData, sectionCount, clientProtocol, arenaRemap);
            if (transcoded == null) {
                writeEmptyChunk(ctx, id, cx, cz);
                return;
            }
            sectionData = transcoded;
        }
        ByteBuf buf = ctx.alloc().buffer();
        Proto.writeVarInt(buf, id);
        buf.writeInt(cx);
        buf.writeInt(cz);
        Proto.writeVarInt(buf, 0);
        Proto.writeVarInt(buf, sectionData.length);
        buf.writeBytes(sectionData);
        Proto.writeVarInt(buf, 0);
        writeFullBrightLight(buf, sectionCount);
        ctx.write(buf);
    }

    private static void writeFullBrightLight(ByteBuf buf, int sectionCount) {
        int lightSections = sectionCount + 2;
        BitSet full = new BitSet(lightSections);
        full.set(0, lightSections);
        writeLongArray(buf, full.toLongArray());
        writeLongArray(buf, full.toLongArray());
        writeLongArray(buf, new long[0]);
        writeLongArray(buf, new long[0]);
        Proto.writeVarInt(buf, lightSections);
        for (int i = 0; i < lightSections; i++) {
            Proto.writeVarInt(buf, FULL_BRIGHT.length);
            buf.writeBytes(FULL_BRIGHT);
        }
        Proto.writeVarInt(buf, lightSections);
        for (int i = 0; i < lightSections; i++) {
            Proto.writeVarInt(buf, FULL_BRIGHT.length);
            buf.writeBytes(FULL_BRIGHT);
        }
    }

    private static void writeLongArray(ByteBuf buf, long[] values) {
        Proto.writeVarInt(buf, values.length);
        for (long v : values) {
            buf.writeLong(v);
        }
    }

    private static int readVarIntAt(byte[] b, int[] pos) {
        int value = 0, shift = 0;
        while (shift < 35) {
            if (pos[0] >= b.length) {
                return -1;
            }
            int x = b[pos[0]++] & 0xFF;
            value |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        return -1;
    }

    private static int readIntAt(byte[] b, int[] pos) {
        int i = pos[0];
        pos[0] += 4;
        return ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }

    private void send(ChannelHandlerContext ctx, int id, Consumer<ByteBuf> writer) {
        ByteBuf buf = ctx.alloc().buffer();
        Proto.writeVarInt(buf, id);
        writer.accept(buf);
        ctx.writeAndFlush(buf);
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
