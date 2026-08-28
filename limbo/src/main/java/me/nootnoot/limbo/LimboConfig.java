package me.nootnoot.limbo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Limbo configuration, loaded from a {@code .properties} file (path passed as the first CLI arg) with
 * sensible defaults. The {@code packets.config} / {@code packets.play} files are the capture-replay
 * data you supply (the version-specific configuration + join packets).
 */
public final class LimboConfig {

    public static final int FLUID_COUNT_MIN_PROTOCOL = 775;

    public final String bindHost;
    public final int bindPort;
    public final String proxyHost;
    public final int proxyPort;
    public final Path configPackets;
    public final Path playPackets;
    public final int protocolVersion;
    public final String versionName;
    public final Path configPacketsAlt;
    public final Path playPacketsAlt;
    public final int protocolVersionAlt;
    public final String versionNameAlt;
    public final String returnChannel;

    // Play-phase packet ids the limbo builds itself (version-fragile — verify/override per version).
    public final boolean keepAliveEnabled;
    public final int keepAliveSeconds;
    public final int playKeepAliveId;
    public final int playTransferId;
    public final int playCustomPayloadId;
    public final int playKeepAliveIdAlt;
    public final int playTransferIdAlt;
    public final int playCustomPayloadIdAlt;
    public final boolean sectionFluidCount;
    public final boolean sectionFluidCountAlt;

    /** Replace the captured world's terrain in the PLAY replay with empty (all-air) chunks so the client gets
     *  a clean VOID — the captured dimension (and its day sky) is kept, and an empty chunk is emitted at each
     *  terrain chunk's own coordinates so the spawn chunk still exists and "Loading terrain…" dismisses at
     *  once. A captured chunk packet is recognised as one whose body is over {@link #stripWorldMinBytes}
     *  bytes (terrain chunks are large) and begins with sane chunk coords — or, if {@link #chunkDataId} is
     *  set, exactly that packet id. The rollback mod then pastes the arena into the void. */
    public final boolean stripWorldEnabled;
    public final int stripWorldMinBytes;
    /** Explicit clientbound "Chunk Data and Update Light" packet id, or -1 to detect chunks by size+coords. */
    public final int chunkDataId;
    /** Number of 16-block sections in the captured dimension's height (overworld -64..320 = 24). */
    public final int worldSectionCount;
    public final boolean nativeTerrainEnabled;
    public final String redisHost;
    public final int redisPort;
    public final String redisPassword;
    public final int nativeWaitMs;
    /** Velocity modern-forwarding secret. When non-empty the limbo runs as a Velocity backend (does the
     *  {@code velocity:player_info} HMAC handshake at login); empty = standalone offline mode (dev). */
    public final String forwardingSecret;

    public final java.util.List<CaptureSpec> extraCaptures;
    public final boolean capturesLenient;

    public static final class CaptureSpec {
        public final int protocolVersion;
        public final String versionName;
        public final Path configPackets;
        public final Path playPackets;
        public final int keepAliveId;
        public final int transferId;
        public final int customPayloadId;
        public final boolean sectionFluidCount;

        CaptureSpec(int protocolVersion, String versionName, Path configPackets, Path playPackets,
                    int keepAliveId, int transferId, int customPayloadId, boolean sectionFluidCount) {
            this.protocolVersion = protocolVersion;
            this.versionName = versionName;
            this.configPackets = configPackets;
            this.playPackets = playPackets;
            this.keepAliveId = keepAliveId;
            this.transferId = transferId;
            this.customPayloadId = customPayloadId;
            this.sectionFluidCount = sectionFluidCount;
        }
    }

    private LimboConfig(String bindHost, int bindPort, String proxyHost, int proxyPort,
                        Path configPackets, Path playPackets, int protocolVersion,
                        String versionName, Path configPacketsAlt, Path playPacketsAlt,
                        int protocolVersionAlt, String versionNameAlt, String returnChannel,
                        boolean keepAliveEnabled, int keepAliveSeconds,
                        int playKeepAliveId, int playTransferId, int playCustomPayloadId,
                        int playKeepAliveIdAlt, int playTransferIdAlt, int playCustomPayloadIdAlt,
                        boolean sectionFluidCount, boolean sectionFluidCountAlt,
                        boolean stripWorldEnabled, int stripWorldMinBytes,
                        int chunkDataId, int worldSectionCount, String forwardingSecret,
                        boolean nativeTerrainEnabled, String redisHost, int redisPort, String redisPassword,
                        int nativeWaitMs, java.util.List<CaptureSpec> extraCaptures,
                        boolean capturesLenient) {
        this.bindHost = bindHost;
        this.bindPort = bindPort;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.configPackets = configPackets;
        this.playPackets = playPackets;
        this.protocolVersion = protocolVersion;
        this.versionName = versionName;
        this.configPacketsAlt = configPacketsAlt;
        this.playPacketsAlt = playPacketsAlt;
        this.protocolVersionAlt = protocolVersionAlt;
        this.versionNameAlt = versionNameAlt;
        this.returnChannel = returnChannel;
        this.keepAliveEnabled = keepAliveEnabled;
        this.keepAliveSeconds = keepAliveSeconds;
        this.playKeepAliveId = playKeepAliveId;
        this.playTransferId = playTransferId;
        this.playCustomPayloadId = playCustomPayloadId;
        this.playKeepAliveIdAlt = playKeepAliveIdAlt;
        this.playTransferIdAlt = playTransferIdAlt;
        this.playCustomPayloadIdAlt = playCustomPayloadIdAlt;
        this.sectionFluidCount = sectionFluidCount;
        this.sectionFluidCountAlt = sectionFluidCountAlt;
        this.stripWorldEnabled = stripWorldEnabled;
        this.stripWorldMinBytes = stripWorldMinBytes;
        this.chunkDataId = chunkDataId;
        this.worldSectionCount = worldSectionCount;
        this.forwardingSecret = forwardingSecret;
        this.nativeTerrainEnabled = nativeTerrainEnabled;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.redisPassword = redisPassword;
        this.nativeWaitMs = nativeWaitMs;
        this.extraCaptures = extraCaptures;
        this.capturesLenient = capturesLenient;
    }

    /** Env override → properties → default, so one deploy artifact works per-region via container env. */
    private static String envOr(String envKey, Properties p, String propKey, String def) {
        String e = System.getenv(envKey);
        if (e != null && !e.isBlank()) {
            return e.trim();
        }
        return p.getProperty(propKey, def);
    }

    private static int parseId(Properties p, String key, int def) {
        String v = p.getProperty(key);
        if (v == null) {
            return def;
        }
        v = v.trim();
        return v.startsWith("0x") ? Integer.parseInt(v.substring(2), 16) : Integer.parseInt(v);
    }

    private static java.util.List<CaptureSpec> loadExtraCaptures(Properties p) {
        java.util.List<CaptureSpec> out = new java.util.ArrayList<>();
        for (int n = 2; ; n++) {
            String protoVal = p.getProperty("capture." + n + ".protocol");
            if (protoVal == null || protoVal.isBlank()) {
                break;
            }
            int proto = Integer.parseInt(protoVal.trim());
            boolean is26 = proto >= FLUID_COUNT_MIN_PROTOCOL;
            int ka = parseId(p, "capture." + n + ".id.keepalive", is26 ? 0x2C : Protocol.PLAY_KEEPALIVE_OUT);
            int tr = parseId(p, "capture." + n + ".id.transfer", is26 ? 0x81 : Protocol.PLAY_TRANSFER_OUT);
            int cp = parseId(p, "capture." + n + ".id.custompayload", is26 ? 0x16 : Protocol.PLAY_CUSTOM_PAYLOAD_IN);
            boolean fluid = Boolean.parseBoolean(
                    p.getProperty("capture." + n + ".section.fluidcount",
                            String.valueOf(proto >= FLUID_COUNT_MIN_PROTOCOL)));
            out.add(new CaptureSpec(proto,
                    p.getProperty("capture." + n + ".name", "v" + proto),
                    Path.of(p.getProperty("capture." + n + ".config", "config-" + n + ".bin")),
                    Path.of(p.getProperty("capture." + n + ".play", "play-" + n + ".bin")),
                    ka, tr, cp, fluid));
        }
        return out;
    }

    public static LimboConfig load(String path) {
        Properties p = new Properties();
        if (path != null) {
            try (InputStream in = Files.newInputStream(Path.of(path))) {
                p.load(in);
            } catch (IOException e) {
                throw new RuntimeException("failed to read limbo config: " + path, e);
            }
        }
        int kaId = parseId(p, "play.id.keepalive", Protocol.PLAY_KEEPALIVE_OUT);
        int trId = parseId(p, "play.id.transfer", Protocol.PLAY_TRANSFER_OUT);
        int cpId = parseId(p, "play.id.custompayload", Protocol.PLAY_CUSTOM_PAYLOAD_IN);
        int protoMain = Integer.parseInt(p.getProperty("protocol.version", "772"));
        int protoAlt = Integer.parseInt(p.getProperty("protocol.version.alt", "-1"));
        boolean fluidMain = Boolean.parseBoolean(
                p.getProperty("play.section.fluidcount",
                        String.valueOf(protoMain >= FLUID_COUNT_MIN_PROTOCOL)));
        boolean fluidAlt = Boolean.parseBoolean(
                p.getProperty("play.section.fluidcount.alt",
                        String.valueOf(protoAlt >= FLUID_COUNT_MIN_PROTOCOL)));
        return new LimboConfig(
                envOr("LIMBO_BIND_HOST", p, "bind.host", "0.0.0.0"),
                Integer.parseInt(envOr("LIMBO_BIND_PORT", p, "bind.port", "25565")),
                envOr("LIMBO_PROXY_HOST", p, "proxy.host", "127.0.0.1"),
                Integer.parseInt(envOr("LIMBO_PROXY_PORT", p, "proxy.port", "25565")),
                Path.of(p.getProperty("packets.config", "config.bin")),
                Path.of(p.getProperty("packets.play", "play.bin")),
                protoMain,
                p.getProperty("protocol.name", "1.21.11"),
                Path.of(p.getProperty("packets.config.alt", "config-26.bin")),
                Path.of(p.getProperty("packets.play.alt", "play-26.bin")),
                protoAlt,
                p.getProperty("protocol.name.alt", "26.1.2"),
                p.getProperty("return.channel", "jackpotrollback:return"),
                Boolean.parseBoolean(p.getProperty("play.keepalive.enabled", "true")),
                Integer.parseInt(p.getProperty("play.keepalive.seconds", "10")),
                kaId, trId, cpId,
                parseId(p, "play.id.keepalive.alt", kaId),
                parseId(p, "play.id.transfer.alt", trId),
                parseId(p, "play.id.custompayload.alt", cpId),
                fluidMain, fluidAlt,
                Boolean.parseBoolean(p.getProperty("play.strip.world", "true")),
                Integer.parseInt(p.getProperty("play.strip.world.minbytes", "1024")),
                parseId(p, "play.id.chunkdata", -1),
                Integer.parseInt(p.getProperty("play.world.sections", "24")),
                envOr("FORWARDING_SECRET", p, "forwarding.secret", ""),
                Boolean.parseBoolean(envOr("LIMBO_NATIVE_ENABLED", p, "play.native.enabled", "true")),
                envOr("LOCAL_REDIS_HOST", p, "redis.host", ""),
                Integer.parseInt(envOr("LOCAL_REDIS_PORT", p, "redis.port", "6379")),
                envOr("LOCAL_REDIS_PASSWORD", p, "redis.password", ""),
                Integer.parseInt(envOr("LIMBO_NATIVE_WAIT_MS", p, "play.native.wait.ms", "300")),
                loadExtraCaptures(p),
                Boolean.parseBoolean(p.getProperty("captures.lenient", "false")));
    }
}
