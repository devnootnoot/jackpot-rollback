package me.nootnoot.limbo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

/**
 * The local stub limbo server. Run one per player machine; the proxy transfers the client here and the
 * rollback mod drives the sim while the player is "held" in this empty world. On the
 * {@code jackpotrollback:return} payload the limbo transfers the client back to the proxy.
 *
 * <p>Usage: {@code java -jar limbo.jar [config.properties]} or {@code ./gradlew :limbo:runLimbo
 * --args="config.properties"}. See {@code LimboConfig} for keys and {@code CapturedPhase} for the
 * capture-replay data you must supply.
 */
public final class LimboServer {

    private static final Logger LOG = LoggerFactory.getLogger("limbo");

    private LimboServer() {
    }

    public static List<Capture> loadCaptures(LimboConfig config) {
        List<Capture> captures = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        List<byte[]> configPackets = load(config.configPackets);
        List<byte[]> playPackets = load(config.playPackets);
        if (configPackets.isEmpty() || playPackets.isEmpty()) {
            missing.add("protocol " + config.protocolVersion + " (" + config.versionName + "): "
                    + config.configPackets + " / " + config.playPackets);
        } else {
            LOG.info("[limbo] loaded capture protocol={} ({}): {} config + {} play, sectionFluidCount={}",
                    config.protocolVersion, config.versionName, configPackets.size(), playPackets.size(),
                    config.sectionFluidCount);
        }
        captures.add(new Capture(config.protocolVersion, config.versionName, configPackets, playPackets,
                chunkId(config, playPackets), config.playKeepAliveId, config.playTransferId,
                config.playCustomPayloadId, config.sectionFluidCount));

        if (config.protocolVersionAlt > 0) {
            List<byte[]> configAlt = load(config.configPacketsAlt);
            List<byte[]> playAlt = load(config.playPacketsAlt);
            if (configAlt.isEmpty() || playAlt.isEmpty()) {
                missing.add("protocol " + config.protocolVersionAlt + " (" + config.versionNameAlt + "): "
                        + config.configPacketsAlt + " / " + config.playPacketsAlt);
            } else {
                captures.add(new Capture(config.protocolVersionAlt, config.versionNameAlt, configAlt, playAlt,
                        chunkId(config, playAlt), config.playKeepAliveIdAlt, config.playTransferIdAlt,
                        config.playCustomPayloadIdAlt, config.sectionFluidCountAlt));
                LOG.info("[limbo] loaded alt capture protocol={} ({}): {} config + {} play, sectionFluidCount={}",
                        config.protocolVersionAlt, config.versionNameAlt, configAlt.size(), playAlt.size(),
                        config.sectionFluidCountAlt);
            }
        }

        for (LimboConfig.CaptureSpec spec : config.extraCaptures) {
            List<byte[]> cfg = load(spec.configPackets);
            List<byte[]> ply = load(spec.playPackets);
            if (cfg.isEmpty() || ply.isEmpty()) {
                missing.add("protocol " + spec.protocolVersion + " (" + spec.versionName + "): "
                        + spec.configPackets + " / " + spec.playPackets);
            } else {
                captures.add(new Capture(spec.protocolVersion, spec.versionName, cfg, ply,
                        chunkId(config, ply), spec.keepAliveId, spec.transferId, spec.customPayloadId,
                        spec.sectionFluidCount));
                LOG.info("[limbo] loaded extra capture protocol={} ({}): {} config + {} play, "
                                + "sectionFluidCount={}", spec.protocolVersion, spec.versionName,
                        cfg.size(), ply.size(), spec.sectionFluidCount);
            }
        }

        if (!missing.isEmpty()) {
            String detail = String.join("; ", missing);
            if (!config.capturesLenient) {
                throw new IllegalStateException("limbo has no capture data for " + detail
                        + " - a client on that protocol cannot be served at all. Record it with"
                        + " 'gradlew devLimboCapture -PcaptureVersion=<mc version>', or set"
                        + " captures.lenient=true to start anyway and refuse those clients at login.");
            }
            LOG.error("[limbo] captures.lenient=true and NO capture data for {} - clients on those"
                    + " protocols are refused at login", detail);
        }
        captures.removeIf(c -> c.config.isEmpty() || c.play.isEmpty());
        if (captures.isEmpty()) {
            throw new IllegalStateException("limbo has no usable capture at all - it can serve nobody");
        }
        return captures;
    }

    private static int chunkId(LimboConfig config, List<byte[]> play) {
        return config.chunkDataId >= 0 ? config.chunkDataId
                : LimboHandler.detectChunkId(play, config.stripWorldMinBytes);
    }

    private static List<byte[]> load(Path path) {
        try {
            return CapturedPhase.load(path);
        } catch (IOException e) {
            LOG.warn("[limbo] could not read capture {}: {}", path, e.toString());
            return List.of();
        }
    }

    public static Channel start(LimboConfig config, List<Capture> captures, EventLoopGroup boss,
                                EventLoopGroup worker) throws InterruptedException {
        ArenaControl arena = arenaControl(config);
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast("frame-decoder", new VarIntFrameCodec.Decoder());
                        ch.pipeline().addLast("frame-encoder", new VarIntFrameCodec.Encoder());
                        ch.pipeline().addLast("handler", new LimboHandler(config, captures, arena));
                    }
                });
        Channel channel = bootstrap.bind(config.bindHost, config.bindPort).sync().channel();
        LOG.info("[limbo] listening on {}:{} (return -> {}:{}), serving protocols {}",
                config.bindHost, config.bindPort, config.proxyHost, config.proxyPort,
                captures.stream().map(c -> c.protocolVersion + "/" + c.versionName).toList());
        return channel;
    }

    private static ArenaControl arenaControl(LimboConfig config) {
        if (!config.nativeTerrainEnabled) {
            return null;
        }
        if (config.redisHost == null || config.redisHost.isBlank()) {
            LOG.warn("[limbo] native terrain enabled but LOCAL_REDIS_HOST resolved empty (got '{}') - serving "
                    + "void only. Set LOCAL_REDIS_HOST/LOCAL_REDIS_PORT/LOCAL_REDIS_PASSWORD on THIS limbo "
                    + "container (the same region-local Redis mcleagues writes to), and deploy a limbo.jar built "
                    + "after the local-Redis switch.", config.redisHost);
            return null;
        }
        JedisPooled redisClient = new JedisPooled(
                new HostAndPort(config.redisHost, config.redisPort),
                DefaultJedisClientConfig.builder()
                        .password(config.redisPassword == null || config.redisPassword.isBlank()
                                ? null : config.redisPassword)
                        .build());
        LOG.info("[limbo] native terrain enabled - arena chunks served from Redis {}:{}",
                config.redisHost, config.redisPort);
        return new ArenaControl(redisClient);
    }

    public static void main(String[] args) throws Exception {
        LimboConfig config = LimboConfig.load(args.length > 0 ? args[0] : null);
        Protocol.PROTOCOL_VERSION = config.protocolVersion;
        Protocol.VERSION_NAME = config.versionName;

        List<Capture> captures = loadCaptures(config);

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        try {
            start(config, captures, boss, worker).closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
