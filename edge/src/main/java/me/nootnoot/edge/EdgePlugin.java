package me.nootnoot.edge;

import com.github.retrooper.packetevents.PacketEvents;
import com.google.gson.JsonObject;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import me.nootnoot.sim.ArenaAgreement;
import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.SimProbe;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.host.SpawnFacing;
import me.nootnoot.sim.contract.InventoryIntents;
import me.nootnoot.sim.net.DirectLink;
import me.nootnoot.sim.net.InputCodec;
import me.nootnoot.sim.net.NetSession;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.Transport;
import me.nootnoot.sim.net.UdpTransport;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import io.papermc.paper.event.entity.EntityLoadCrossbowEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import me.nootnoot.edge.present.CoreHeadActionBar;
import me.nootnoot.edge.present.CoreSounds;
import me.nootnoot.edge.present.CoreTitles;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import me.nootnoot.sim.host.MatchDriver;
import me.nootnoot.sim.net.LaggedTransport;

public final class EdgePlugin extends JavaPlugin implements Listener {

    private final Map<UUID, EdgeMatch> matches = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private static final double AUTO = -100000.0;

    private static final double ATTACK_SPEED_SKEW_TOLERANCE = 0.01;

    private static final String DEV_IDENTITY_PREFIX = "edge-slot-";

    private static final long NOON = 6000L;

    private static final int PROBE_MIRROR_INTERVAL_TICKS = 20;

    private static final double DEFAULT_CAGE_DROP_HEIGHT = 15.0;

    private static final double MAX_CAGE_DROP_HEIGHT = 32.0;

    private boolean lockArenaConditions;
    private boolean attackSpeedRepair;
    private EdgeMovementValidator.Limits movementLimits;
    private int statusTick;
    private int probeMirrorTick;
    private int slot;
    private long sessionId;
    private String relayHost;
    private int relayPort;
    private double groundY;
    private double spawnX0;
    private double spawnY0;
    private double spawnZ0;
    private double spawnX1;
    private double spawnY1;
    private double spawnZ1;
    private String arenaFileName;
    private int arenaHandshakeTimeoutTicks;
    private int localRounds;
    private int localLagMs;
    private double cageDropHeight;
    private EdgeArena loadedArena;
    private EdgeArenaStore arenaStore;
    private EdgeArenaPaster paster;
    private EdgeArenaWorld arenaWorld;
    private final Map<UUID, World> pasteWorlds = new ConcurrentHashMap<>();
    private final Map<UUID, Location> homes = new ConcurrentHashMap<>();
    private final Map<UUID, LoadoutSession> loadouts = new ConcurrentHashMap<>();
    private final Map<UUID, EdgeCageDisplay> cages = new ConcurrentHashMap<>();
    private final Map<UUID, EdgeArenaPaster.Overlay> overlays = new ConcurrentHashMap<>();
    private EdgeBroker broker;
    private EdgeMetrics metrics;
    private EdgeMetricsEndpoint metricsEndpoint;
    private EdgeDesyncAlert desyncAlert;
    private final Map<UUID, Long> assignmentIssuedAt = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> syncCursor = new ConcurrentHashMap<>();
    private EdgeModHello modHello;
    private EdgeModHandoff modHandoff;
    private EdgeRefereeAuthorizer refereeAuthorizer;
    private final Map<UUID, Integer> simulatedLag = new ConcurrentHashMap<>();
    private boolean devKitEnabled;
    private String localGameType;
    private String edgeId;
    private final Map<UUID, MatchMeta> matchMeta = new ConcurrentHashMap<>();
    private static final long UNCLAIMED_WARN_INTERVAL_MS = 10_000L;
    private volatile long lastUnclaimedWarnMs;

    private DirectLink directLink;
    private long directLinkFenceReported;
    private long directLinkUntaggedReported;
    private long directLinkForgedReported;

    private static final int PASTE_WAIT_TIMEOUT_TICKS = 1200;

    private final Map<UUID, LoadoutSession> deferredRestores = new ConcurrentHashMap<>();
    private final Map<UUID, String> exitNotices = new ConcurrentHashMap<>();
    private final Map<UUID, int[]> pasteWaits = new ConcurrentHashMap<>();

    private record Pending(Player player, long sessionId, int slot, EdgeHandshake handshake,
                           EdgeArena arena, GameState initial, ArenaAgreement agreement,
                           World world, Location spawn, EdgeLoadout loadout, EdgeCage cage,
                           boolean pots, boolean totems, boolean brokered) {
    }

    private record MatchMeta(long sessionId, int slot, boolean brokered) {
    }

    private record LoadoutSession(EdgeLoadout loadout, EdgeLoadout.Snapshot before) {
    }

    @Override
    public void onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings().checkForUpdates(false);
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        EdgeBuildFence.Verdict build = EdgeBuildFence.check(getFile(), getDataFolder());
        for (String line : build.lines()) {
            if (build.fresh()) {
                getLogger().info(line);
            } else {
                getLogger().severe(line);
            }
        }
        if (!build.fresh()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        PacketEvents.getAPI().init();
        PacketEvents.getAPI().getEventManager()
                .registerListener(new EdgeIntentListener(matches::get));
        modHello = new EdgeModHello();
        PacketEvents.getAPI().getEventManager().registerListener(modHello);
        EdgeItemIds.load(getLogger());

        CoreTitles.install(this);
        CoreSounds.install(this);
        CoreHeadActionBar.install(this);
        sweepOrphanedCageDisplays();

        saveDefaultConfig();
        slot = getConfig().getInt("slot", 0);
        sessionId = getConfig().getLong("session-id", 1L);
        relayHost = getConfig().getString("relay-host", "127.0.0.1");
        relayPort = getConfig().getInt("relay-port", 7777);
        groundY = getConfig().getDouble("ground-y", AUTO);
        spawnX0 = getConfig().getDouble("spawn.x0", -4.0);
        spawnY0 = getConfig().getDouble("spawn.y0", AUTO);
        spawnZ0 = getConfig().getDouble("spawn.z0", 0.0);
        spawnX1 = getConfig().getDouble("spawn.x1", 4.0);
        spawnY1 = getConfig().getDouble("spawn.y1", AUTO);
        spawnZ1 = getConfig().getDouble("spawn.z1", 0.0);
        arenaFileName = getConfig().getString("arena-file", "arena.bin");
        arenaHandshakeTimeoutTicks = getConfig().getInt("arena-handshake-timeout-ticks", 600);
        localRounds = Math.max(1, getConfig().getInt("rounds", 1));
        localLagMs = LaggedTransport.clamp(envOrConfigInt("EDGE_SIMULATED_LAG_MS",
                "dev.simulated-lag-ms", 0));
        cageDropHeight = Math.max(0.0, Math.min(MAX_CAGE_DROP_HEIGHT,
                getConfig().getDouble("cage.drop-height", DEFAULT_CAGE_DROP_HEIGHT)));
        loadedArena = loadArena();
        arenaStore = new EdgeArenaStore(getLogger());
        paster = new EdgeArenaPaster(this, getLogger(),
                getConfig().getBoolean("arena.paste", true),
                getConfig().getInt("arena.blocks-per-tick",
                        EdgeArenaPaster.DEFAULT_BLOCKS_PER_TICK),
                getConfig().getInt("arena.millis-per-tick",
                        EdgeArenaPaster.DEFAULT_MILLIS_PER_TICK),
                getConfig().getBoolean("arena.refuse-on-clip", true));
        getLogger().info("arena paste: " + (paster.pastes(loadedArena)
                ? "on, up to " + paster.blocksPerTick() + " blocks/tick capped at "
                        + paster.millisPerTick() + "ms/tick"
                : paster.enabled() ? "nothing to paste (no arena file)" : "disabled by config"));
        getLogger().info("real arenas are big: a core assignment ships the whole extracted arena"
                + " (colosseum ~148k blocks, courtyard ~2.1M), so the paste is what the pre-frame-0"
                + " wait is spent on. arena.refuse-on-clip="
                + paster.refuseOnClip() + ": a world too short to hold the arena is refused when"
                + " the missing blocks are solid to the sim and near the fight.");
        getLogger().info("arena source: a brokered assignment that carries arena bytes wins over"
                + " arena-file; " + (loadedArena != null ? arenaFileName : "the flat fallback")
                + " is only used when core supplies none (offline and /edge testing)");

        arenaWorld = new EdgeArenaWorld(this, getLogger(),
                envOrConfigString("EDGE_ARENA_WORLD", "arena.world",
                        EdgeArenaWorld.DEFAULT_NAME));

        movementLimits = readMovementLimits();

        localGameType = EdgeGameTypes.canonical(
                getConfig().getString("game-type", EdgeGameTypes.CRYSTAL));
        getLogger().info("/edge game-type=" + localGameType + " -> "
                + EdgeGameTypes.summary(localGameType) + "; rules "
                + EdgeGameTypes.rules(localGameType).describe() + ". /edge builds this DEMO kit"
                + " itself through the same encoder a brokered match uses, so BOTH edges must"
                + " configure the same game-type and run the same build or frame 0 differs and the"
                + " match desyncs. It does not touch brokered matches, which carry their own kit.");
        warnDisabledMechanics(localGameType, EdgeDemoKits.materials(
                EdgeDemoKits.forGameType(localGameType)), "config.yml game-type");

        attackSpeedRepair = getConfig().getBoolean("attack-speed-repair", true);
        getLogger().info("attack-speed-repair=" + attackSpeedRepair + ": until the double-write was"
                + " fixed, a match-time ATTACK_SPEED shift could be captured as the restore"
                + " baseline and persisted into playerdata, where it follows the account into every"
                + " other mode. With this on, an account whose ATTACK_SPEED base is not the vanilla"
                + " default (" + EdgeAttackSpeed.VANILLA_BASE + ") is put back on join and at both"
                + " ends of every edge match. Only the base value is touched, never a modifier, and"
                + " a base outside the band this edge can produce is reported and left alone."
                + " /edge attackspeed <player|all> [force] repairs an account by hand.");

        devKitEnabled = getConfig().getBoolean("dev-kit.enabled", false);
        if (devKitEnabled) {
            getLogger().warning("dev-kit.enabled=true - brokered matches that arrive WITHOUT setup"
                    + " bytes get a hardcoded DEMO kit chosen by the assignment's gameType (one of "
                    + EdgeGameTypes.IDS + ", default " + EdgeGameTypes.UNKNOWN_FALLBACK
                    + " for a name this edge does not know). BOTH edges"
                    + " must run with the same setting and the same build or frame 0 differs and"
                    + " the match desyncs. Turn it off for production.");
        }

        refereeAuthorizer = new EdgeRefereeAuthorizer(getLogger());
        modHandoff = new EdgeModHandoff(this, getLogger(), modHello,
                envOrConfigString("EDGE_LIMBO_HOST", "mod.limbo-host", "127.0.0.1"),
                envOrConfigInt("EDGE_LIMBO_PORT", "mod.limbo-port", 25565),
                getConfig().getInt("mod.arena-fragment-bytes",
                        EdgeModHandoff.DEFAULT_FRAGMENT_BYTES),
                getConfig().getLong("mod.transfer-delay-ticks",
                        EdgeModHandoff.DEFAULT_TRANSFER_DELAY_TICKS),
                getConfig().getBoolean("mod.require-hello", true));
        getLogger().info("mod handoff: " + modHandoff.describe() + ". An assignment whose OWN"
                + " hostKind is MOD is NOT hosted here - this server sends that client the"
                + " match_setup, arena and cage plugin messages and transfers it to the limbo,"
                + " which is what mcleagues-core's RollbackHandoffManager does on the network.");

        lockArenaConditions = getConfig().getBoolean("lock-arena-conditions", true);
        for (World w : getServer().getWorlds()) {
            applyArenaConditions(w);
        }

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::tickAll, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, this::holdArenaConditions, 100L, 100L);
        getLogger().info("edge ready: slot=" + slot + " relay=" + relayHost + ":" + relayPort
                + " session=" + sessionId + " /edge rounds=" + localRounds
                + " (brokered matches use the rounds in the assignment)");
        if (localRounds > 1) {
            getLogger().warning("rounds=" + localRounds + " only applies to /edge - BOTH edges must"
                    + " configure the same value or frame 0 checksums differ and the match desyncs");
        }
        getLogger().info("cage drop height: brokered matches use the value baked into the"
                + " assignment spawns by mcleagues-core; cage.drop-height=" + cageDropHeight
                + " only applies to /edge, where BOTH edges must configure the same value or"
                + " frame 0 differs and the match desyncs");
        startMetrics();
        startBroker();
        scheduleMetrics();
        getLogger().info("metrics: counters land in redis at " + EdgeBroker.KEY_METRICS
                + " (field " + edgeId + ") every broker poll and on http /metrics for a scraper."
                + " THE alert is the desync-abort rate: it means two builds disagree. Version fence"
                + " on this build: inputBytes=" + InputCodec.BYTES + " checksumRev="
                + Protocol.CHECKSUM_REV + " protocolVersion=" + Protocol.VERSION);
        getLogger().info("forfeit bound: a peer that stops sending is declared gone after "
                + NetSession.PEER_TIMEOUT_TICKS + " ticks (" + (NetSession.PEER_TIMEOUT_TICKS / 20)
                + "s). Results are queued on the match thread and pushed by the async broker poll"
                + " (1s), so nothing blocks a tick on redis and nothing is lost while redis is"
                + " down. A peer that leaves cleanly sends a teardown, which ends the match on the"
                + " very next tick.");
        getLogger().info("movement validation: " + (movementLimits.enabled() ? "clamp" : "off")
                + " ceiling=" + movementLimits.perTickCeiling()
                + " sustained=" + movementLimits.sustainedPerTick()
                + " threshold=" + movementLimits.violationThreshold());
    }

    private EdgeArena loadArena() {
        if (arenaFileName == null || arenaFileName.isBlank()) {
            getLogger().info("arena-file is unset - using the flat fallback arena");
            return null;
        }
        File file = new File(arenaFileName);
        if (!file.isAbsolute()) {
            file = new File(getDataFolder(), arenaFileName);
        }
        if (!file.isFile()) {
            getLogger().info("arena file not found: " + file.getAbsolutePath()
                    + " - using the flat fallback arena"
                    + " (generate one with: gradlew devSampleArena)");
            return null;
        }
        try {
            EdgeArena arena = EdgeArena.fromFile(file);
            getLogger().info("arena loaded: " + arena.describe()
                    + " hash=" + ArenaAgreement.hex(arena.hash()));
            if (groundY > AUTO && groundY != arena.groundY()) {
                getLogger().warning("config ground-y=" + groundY + " is ignored: the arena file"
                        + " defines ground-y=" + arena.groundY());
            }
            return arena;
        } catch (IOException | RuntimeException ex) {
            getLogger().severe("arena file unreadable (" + file.getAbsolutePath() + "): " + ex
                    + " - using the flat fallback arena, which WILL NOT match a peer that loaded it");
            return null;
        }
    }

    private static String env(String name) {
        String raw = System.getenv(name);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private String envOrConfigString(String envName, String path, String def) {
        String e = env(envName);
        if (e != null) {
            return e;
        }
        String c = getConfig().getString(path, def);
        return c == null || c.isBlank() ? def : c;
    }

    private int envOrConfigInt(String envName, String path, int def) {
        String e = env(envName);
        if (e != null) {
            try {
                return Integer.parseInt(e);
            } catch (NumberFormatException ex) {
                getLogger().warning(envName + "=" + e + " is not a number - falling back to "
                        + path);
            }
        }
        return getConfig().getInt(path, def);
    }

    private boolean envOrConfigBoolean(String envName, String path, boolean def) {
        String e = env(envName);
        if (e != null) {
            return Boolean.parseBoolean(e);
        }
        return getConfig().getBoolean(path, def);
    }

    private String resolveEdgeId() {
        String id = envOrConfigString("EDGE_ID", "broker.edge-id", "");
        if (id == null || id.isBlank()) {
            id = env("HOSTNAME");
        }
        if (id == null || id.isBlank()) {
            id = "edge-" + getServer().getPort();
        }
        return id;
    }

    private String resolveRegion() {
        String region = getConfig().getString("broker.region", "");
        if (region == null || region.isBlank()) {
            region = env("REGION");
        }
        if (region == null || region.isBlank()) {
            region = "LOCAL";
        }
        return region;
    }

    private void startMetrics() {
        edgeId = resolveEdgeId();
        metrics = new EdgeMetrics(edgeId, resolveRegion());
        desyncAlert = new EdgeDesyncAlert(getLogger(), edgeId);
        if (!envOrConfigBoolean("EDGE_METRICS_SIM_PROBE", "metrics.sim-probe", true)) {
            getLogger().info("sim probe counters disabled - every claim-rejection metric on this"
                    + " edge stays at zero. The desync alert is unaffected.");
        } else if (SimProbe.installed()) {
            getLogger().warning("a sim probe sink was already installed - leaving it alone, so the"
                    + " claim-rejection counters on this process stay at zero");
        } else {
            SimProbe.install(metrics.probeSink());
        }
        if (!envOrConfigBoolean("EDGE_METRICS_ENABLED", "metrics.enabled", true)) {
            getLogger().info("metrics endpoint disabled by config - counters are still collected"
                    + " and still published to redis for core to aggregate");
            return;
        }
        metricsEndpoint = EdgeMetricsEndpoint.start(getLogger(),
                envOrConfigString("EDGE_METRICS_BIND", "metrics.bind", ""),
                envOrConfigInt("EDGE_METRICS_PORT", "metrics.port",
                        EdgeMetricsEndpoint.DEFAULT_PORT));
    }

    private void scheduleMetrics() {
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::publishMetrics, 40L, 20L);
    }

    private void publishMetrics() {
        EdgeMetrics m = metrics;
        if (m == null) {
            return;
        }
        EdgeBroker b = broker;
        m.gauges(matches.size(), pending.size(),
                b == null ? 0 : b.pendingAssignments().size(),
                b == null ? 0 : b.queuedResults(),
                b == null || b.healthy(), directLink != null);
        EdgeMetrics.Snapshot snapshot = m.snapshot();
        boolean alerting = desyncAlert != null
                && desyncAlert.evaluate(snapshot, System.currentTimeMillis());
        EdgeMetricsEndpoint endpoint = metricsEndpoint;
        if (endpoint != null) {
            endpoint.publish(snapshot, alerting);
        }
        if (b != null) {
            b.publishMetrics(EdgeMetrics.json(snapshot));
        }
        reportDirectLinkFence();
    }

    private void reportDirectLinkFence() {
        DirectLink link = directLink;
        if (link == null) {
            return;
        }
        long seen = link.versionMismatchedHellos();
        if (seen > directLinkFenceReported) {
            directLinkFenceReported = seen;
            String diagnosis = link.versionMismatchDiagnosis();
            if (diagnosis != null) {
                getLogger().severe("[direct-link] " + diagnosis);
            }
        }
        long untagged = link.untaggedFrames();
        if (untagged > directLinkUntaggedReported) {
            directLinkUntaggedReported = untagged;
            String diagnosis = link.untaggedFrameDiagnosis();
            if (diagnosis != null) {
                getLogger().severe("[direct-link] " + diagnosis);
            }
        }
        long forged = link.badFrameTags() + link.replayedFrames();
        if (forged > directLinkForgedReported) {
            directLinkForgedReported = forged;
            getLogger().warning("[direct-link] " + link.badFrameTags() + " datagrams failed their"
                    + " per-frame tag and " + link.replayedFrames() + " were replays of a frame"
                    + " already delivered. Both were refused before the session saw them. A"
                    + " non-zero count means somebody is putting the peer edge's address on"
                    + " datagrams they cannot sign, which is worth looking at even though it"
                    + " changed no match.");
        }
    }

    private void startBroker() {
        if (!envOrConfigBoolean("EDGE_BROKER_ENABLED", "broker.enabled", false)) {
            getLogger().info("broker disabled - use /edge for local testing");
            return;
        }
        EdgeArchGate.Verdict arch = EdgeArchGate.evaluate(
                envOrConfigString(EdgeArchGate.ENV, EdgeArchGate.PATH, ""),
                System.getProperty("os.arch"));
        if (!arch.brokerAllowed()) {
            getLogger().severe(arch.message());
            return;
        }
        if (arch.warning()) {
            getLogger().warning(arch.message());
        } else {
            getLogger().info(arch.message());
        }
        String region = resolveRegion();
        String publicHost = envOrConfigString("EDGE_PUBLIC_HOST", "broker.public-host", "127.0.0.1");
        int publicPort = envOrConfigInt("EDGE_PUBLIC_PORT", "broker.public-port", 0);
        if (publicPort <= 0) {
            publicPort = getServer().getPort();
        }
        String redisHost = envOrConfigString("REDIS_HOST", "broker.redis-host", "127.0.0.1");
        int redisPort = envOrConfigInt("REDIS_PORT", "broker.redis-port", 6379);
        String redisPassword = envOrConfigString("REDIS_PASSWORD", "broker.redis-password", "");

        if ("LOCAL".equals(region)) {
            getLogger().warning("region is LOCAL - mcleagues-core maps this edge to no Region, so"
                    + " EdgeRegistry.pickFor cannot prefer it for anyone. Set REGION on the"
                    + " container to one of the network's region names.");
        }
        if (publicHost.equals("127.0.0.1")) {
            getLogger().warning("public-host is 127.0.0.1 - this edge is advertising a loopback"
                    + " address to the whole network. Set EDGE_PUBLIC_HOST to the address the"
                    + " proxy reaches this server on.");
        }

        startDirectLink(publicHost);

        broker = new EdgeBroker(getLogger(), edgeId, region, publicHost, publicPort,
                directLink == null ? "" : directHostAdvertised(publicHost),
                directLink == null ? 0 : directLink.port(),
                redisHost, redisPort, redisPassword);
        broker.attachMetrics(metrics);
        broker.heartbeat();
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::brokerPoll, 20L, 20L);
        getLogger().info("broker enabled: edgeId=" + edgeId + " region=" + region
                + " redis=" + redisHost + ":" + redisPort + " advertising " + publicHost + ":" + publicPort
                + (directLink == null ? " (relay only)"
                        : " direct-link udp/" + directLink.port()));
    }

    private String directHostAdvertised(String publicHost) {
        String configured = envOrConfigString("EDGE_DIRECT_ADVERTISE_HOST",
                "direct.advertise-host", "");
        return configured == null || configured.isBlank() ? publicHost : configured;
    }

    private void startDirectLink(String publicHost) {
        boolean directEnabled = envOrConfigBoolean("EDGE_DIRECT_ENABLED", "direct.enabled", true);
        int directPort = envOrConfigInt("EDGE_DIRECT_PORT", "direct.port", 7787);
        if (!directEnabled) {
            getLogger().info("direct edge links disabled by config - every match dials the relay,"
                    + " which is the pre-direct behaviour and keeps the relay referee in the path");
            return;
        }
        String bind = envOrConfigString("EDGE_DIRECT_BIND", "direct.bind", "");
        try {
            directLink = new DirectLink(directPort, bind);
        } catch (java.net.SocketException ex) {
            getLogger().severe("could not bind the direct edge link on udp/" + directPort + " ("
                    + ex + ") - every match will fall back to the relay. This is a degradation,"
                    + " not a failure: the relay path is unchanged.");
            return;
        }
        getLogger().info("direct edge link listening on udp/" + directLink.port()
                + ", advertised as " + directHostAdvertised(publicHost) + ":" + directLink.port()
                + ". An assignment that carries a peer endpoint and a link token skips the relay;"
                + " anything else (a MODDED opponent behind NAT, an older core, a peer edge that"
                + " advertises no direct port) still uses it.");
    }

    private void brokerPoll() {
        EdgeBroker b = broker;
        if (b == null) {
            return;
        }
        b.heartbeat();
        b.flushResults();
        b.expirePending(System.currentTimeMillis());
        boolean arrived = !b.drainAssignments().isEmpty();
        for (EdgeAssignment a : b.pendingAssignments()) {
            arenaStore.prefetch(b::fetchBlob, a);
        }
        if (arrived || b.hasPending()) {
            getServer().getScheduler().runTask(this, this::claimAssignments);
        }
    }

    private void claimAssignments() {
        EdgeBroker b = broker;
        if (b == null) {
            return;
        }
        authorizeReferees(b);
        for (Player online : getServer().getOnlinePlayers()) {
            tryStartBrokered(b, online);
        }
        reportUnclaimed(b);
    }

    private void authorizeReferees(EdgeBroker b) {
        EdgeRefereeAuthorizer authorizer = refereeAuthorizer;
        if (authorizer == null) {
            return;
        }
        for (EdgeAssignment a : b.pendingAssignments()) {
            if (!authorizer.claim(a)) {
                continue;
            }
            byte[] setup = authorizer.encodeFrameZero(a);
            if (setup == null) {
                continue;
            }
            String key = a.arenaKey();
            getServer().getScheduler().runTaskAsynchronously(this, () -> authorizer.publish(a,
                    setup, key == null || key.isEmpty() ? null : b.fetchBlob(key)));
        }
    }

    private void reportUnclaimed(EdgeBroker b) {
        if (!b.hasPending()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastUnclaimedWarnMs < UNCLAIMED_WARN_INTERVAL_MS) {
            return;
        }
        lastUnclaimedWarnMs = now;
        for (EdgeAssignment a : b.pendingAssignments()) {
            boolean online = getServer().getPlayer(a.playerUuid()) != null;
            if (online) {
                continue;
            }
            getLogger().warning("waiting for " + a.playerName() + " to join THIS edge ("
                    + edgeId + ", port " + getServer().getPort() + ") - they are not connected here."
                    + " If they joined the other edge, the assignment went to the wrong server:"
                    + " re-push with the names in the other order.");
        }
    }

    private void tryStartBrokered(EdgeBroker b, Player player) {
        if (matches.containsKey(player.getUniqueId()) || pending.containsKey(player.getUniqueId())) {
            return;
        }
        EdgeAssignment peeked = b.peek(player.getUniqueId());
        if (peeked == null) {
            return;
        }
        EdgeArenaStore.Result resolved = arenaStore.resolve(peeked);
        if (resolved.state() == EdgeArenaStore.State.PENDING) {
            return;
        }
        EdgeAssignment a = b.claim(player.getUniqueId());
        if (a == null) {
            return;
        }
        if (a.issuedAtMs() > 0L) {
            assignmentIssuedAt.put(player.getUniqueId(), a.issuedAtMs());
        }
        if (resolved.state() == EdgeArenaStore.State.FAILED) {
            getLogger().severe("refusing the match for " + a.playerName() + ": the arena bytes for '"
                    + a.arenaName() + "' are unusable - " + resolved.reason() + ". The peer edge"
                    + " reads the same key, so starting on a local arena instead would desync.");
            player.sendMessage(Component.text("match refused: this edge could not load the arena"));
            return;
        }
        if (a.selfIsModded()) {
            getLogger().info("assignment " + a.describe() + " puts this player's sim on their own"
                    + " client - handing off to the limbo instead of hosting it here");
            modHandoff.start(player, a, b::fetchBlob);
            return;
        }
        beginMatch(player, a.sessionId(), a.slot(), a.token(), a.relayHost(), a.relayPort(),
                a.spawnX(0), a.spawnY(0), a.spawnZ(0), a.spawnX(1), a.spawnY(1), a.spawnZ(1),
                a.spawnYaw(0), a.spawnPitch(0), a.spawnYaw(1), a.spawnPitch(1),
                a.groundY(), a.rounds(), setupFor(a), cageFor(a.cage()), resolved.arena(), true,
                a.pots(), a.totems(), directLinkFor(a), true, a.lagMs(),
                "assignment " + a.describe());
    }

    private DirectPeer directLinkFor(EdgeAssignment a) {
        if (directLink == null || !a.hasPeerEndpoint()) {
            return null;
        }
        if (a.opponentIsModded()) {
            getLogger().info("session " + a.sessionId() + " keeps the relay: the opponent hosts the"
                    + " sim on their own client, which cannot be dialled directly");
            return null;
        }
        return new DirectPeer(new InetSocketAddress(a.peerHost(), a.peerPort()), a.linkToken());
    }

    private record DirectPeer(InetSocketAddress address, byte[] token) {
    }

    private EdgeCage cageFor(byte[] payload) {
        if (payload == null || payload.length == 0) {
            EdgeCage built = EdgeCage.fallback();
            getLogger().info("no cage payload on the assignment - using the built-in cage ("
                    + built.describe() + "). It is compiled into this jar, so both edges build the"
                    + " same geometry as long as they run the same build.");
            return built;
        }
        try {
            EdgeCage decoded = EdgeCage.decode(payload);
            if (decoded.empty()) {
                EdgeCage built = EdgeCage.fallback();
                getLogger().warning("the assignment cage payload carries no blocks (core has no"
                        + " schematic for one of the chosen cages) - using the built-in barrier"
                        + " shell (" + built.describe() + "). Both edges read the same bytes, so"
                        + " both make the same substitution.");
                return built;
            }
            getLogger().info("cage decoded from the assignment: " + decoded.describe());
            return decoded;
        } catch (IOException | RuntimeException ex) {
            getLogger().warning("the assignment cage payload is unreadable (" + ex + ") - using the"
                    + " built-in cage. Both edges receive the same bytes, so both reject it the"
                    + " same way and still build the same cage.");
            return EdgeCage.fallback();
        }
    }

    private byte[] setupFor(EdgeAssignment a) {
        if (a.hasSetup()) {
            return a.setup();
        }
        if (!devKitEnabled && !a.devKit()) {
            getLogger().warning("#### NO KIT ON THIS ASSIGNMENT ####");
            getLogger().warning("  the assignment carries no setup bytes and neither dev-kit.enabled"
                    + " nor the assignment's devKit flag is set, so this brokered match will be"
                    + " REFUSED for " + a.playerName() + " rather than started on a locally"
                    + " synthesised frame 0 the peer would not agree with.");
            getLogger().warning("  real kits only come from mcleagues-core (MatchSetupCodec ->"
                    + " EdgeSessionBroker.assign(setup)); gradlew devAssign never sends setup bytes"
                    + " and relies on the dev kit instead.");
            return null;
        }
        try {
            byte[] setup = EdgeDevKit.encode(a);
            warnDisabledMechanics(a.gameType(),
                    EdgeDemoKits.materials(EdgeDevKit.kitFor(a)),
                    a.gameType().isEmpty()
                            ? "this assignment carried NO gameType at all, so it defaulted to "
                                    + EdgeGameTypes.UNKNOWN_FALLBACK
                            : "assignment gameType '" + a.gameType() + "'");
            getLogger().warning("no setup bytes on the assignment - filling the DEMO KIT "
                    + EdgeDevKit.describe(a) + " for gameType '"
                    + (a.gameType().isEmpty() ? "?" : a.gameType()) + "', " + setup.length
                    + "B. This is derived from the assignment alone, so the peer edge builds the"
                    + " identical frame 0 only if it is running the same build with the dev kit on"
                    + " too and was sent the same gameType.");
            return setup;
        } catch (RuntimeException ex) {
            getLogger().severe("dev kit build failed (" + ex + ") - starting the match empty-handed");
            return null;
        }
    }

    private EdgeLoadout localDevKit(long session, int matchSlot, double gy, int rounds,
                                    double ax0, double ay0, double az0,
                                    double ax1, double ay1, double az1,
                                    float ayaw0, float apitch0, float ayaw1, float apitch1) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("sessionId", session);
        o.addProperty("slot", matchSlot);
        o.addProperty("token", Base64.getEncoder().encodeToString(new byte[32]));
        JsonObject relay = new JsonObject();
        relay.addProperty("host", relayHost);
        relay.addProperty("port", relayPort);
        o.add("relay", relay);
        o.add("player", devIdentity(matchSlot));
        o.add("opponent", devIdentity(1 - matchSlot));
        JsonObject arena = new JsonObject();
        arena.addProperty("groundY", gy);
        o.add("arena", arena);
        JsonObject spawns = new JsonObject();
        spawns.addProperty("x0", ax0);
        spawns.addProperty("y0", ay0);
        spawns.addProperty("z0", az0);
        spawns.addProperty("x1", ax1);
        spawns.addProperty("y1", ay1);
        spawns.addProperty("z1", az1);
        spawns.addProperty("yaw0", ayaw0);
        spawns.addProperty("pitch0", apitch0);
        spawns.addProperty("yaw1", ayaw1);
        spawns.addProperty("pitch1", apitch1);
        o.add("spawns", spawns);
        o.addProperty("rounds", Math.max(1, rounds));
        o.addProperty("devKit", true);
        JsonObject mode = new JsonObject();
        mode.addProperty("gameType", localGameType);
        mode.addProperty("pots", EdgeGameTypes.pots(localGameType));
        mode.addProperty("totems", EdgeGameTypes.totems(localGameType));
        o.add("mode", mode);

        EdgeAssignment stand = EdgeAssignment.parse(o.toString());
        if (stand == null) {
            getLogger().severe("could not build the /edge dev assignment - starting empty-handed");
            return null;
        }
        try {
            warnDisabledMechanics(localGameType,
                    EdgeDemoKits.materials(EdgeDevKit.kitFor(stand)), "/edge game-type");
            byte[] setup = EdgeDevKit.encode(stand);
            EdgeLoadout built = EdgeLoadout.decode(setup);
            getLogger().warning("/edge has no setup bytes, so this edge built the DEMO KIT "
                    + EdgeDevKit.describe(stand) + " for game-type '" + localGameType + "', "
                    + setup.length + "B, through the same encoder and decoder a brokered match"
                    + " uses. Every value it is derived from is either fixed per slot or resolved"
                    + " identically on both edges, so the peer builds the same frame 0 only if it"
                    + " runs this build with the same game-type, rounds, spawns and ground-y.");
            return built;
        } catch (RuntimeException ex) {
            getLogger().severe("/edge dev kit build failed (" + ex + ") - starting empty-handed");
            return null;
        }
    }

    private static JsonObject devIdentity(int forSlot) {
        String name = DEV_IDENTITY_PREFIX + forSlot;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString());
        o.addProperty("name", name);
        return o;
    }

    private boolean attackSpeedCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("edge.attackspeed")) {
            sender.sendMessage(Component.text("you do not have edge.attackspeed"));
            return true;
        }
        boolean force = args.length > 2 && "force".equalsIgnoreCase(args[2]);
        String target = args.length > 1 ? args[1]
                : sender instanceof Player self ? self.getName() : "all";
        List<Player> players = new ArrayList<>();
        if ("all".equalsIgnoreCase(target) || "*".equals(target)) {
            players.addAll(getServer().getOnlinePlayers());
        } else {
            Player found = getServer().getPlayerExact(target);
            if (found == null) {
                sender.sendMessage(Component.text("no player named '" + target + "' is online."
                        + " ATTACK_SPEED lives in that account's playerdata, which this server only"
                        + " holds while they are connected, so they have to be online to repair"));
                return true;
            }
            players.add(found);
        }
        if (players.isEmpty()) {
            sender.sendMessage(Component.text("nobody is online to repair"));
            return true;
        }
        int repaired = 0;
        for (Player p : players) {
            EdgeAttackSpeed.Report before = EdgeAttackSpeed.inspect(p);
            sender.sendMessage(Component.text(p.getName() + " " + before.describe()));
            if (before.verdict() == EdgeAttackSpeed.Verdict.CLEAN
                    || before.verdict() == EdgeAttackSpeed.Verdict.NO_ATTRIBUTE) {
                continue;
            }
            EdgeAttackSpeed.Report after =
                    EdgeAttackSpeed.repair(p, getLogger(), "/edge attackspeed by "
                            + sender.getName(), force);
            if (after.verdict() == EdgeAttackSpeed.Verdict.CLEAN) {
                repaired++;
                sender.sendMessage(Component.text("  repaired " + p.getName() + ": base "
                        + before.base() + " -> " + after.base() + " (modifiers untouched)"));
            } else {
                sender.sendMessage(Component.text("  left " + p.getName() + " alone - add 'force'"
                        + " to overwrite a base another feature owns"));
            }
        }
        sender.sendMessage(Component.text("repaired " + repaired + " of " + players.size()
                + " account(s). The value is written into playerdata when they next disconnect,"
                + " so keep them online until the log line is printed"));
        return true;
    }

    private void warnDisabledMechanics(String gameType, List<String> kitMaterials, String source) {
        for (String line : EdgeGameTypes.warningLines(gameType, kitMaterials, source)) {
            getLogger().warning(line);
        }
    }

    private EdgeMovementValidator.Limits readMovementLimits() {
        EdgeMovementValidator.Limits d = EdgeMovementValidator.Limits.DEFAULTS;
        return new EdgeMovementValidator.Limits(
                getConfig().getBoolean("movement.enabled", d.enabled()),
                getConfig().getDouble("movement.per-tick-ceiling", d.perTickCeiling()),
                getConfig().getDouble("movement.vertical-up-ceiling", d.verticalUpCeiling()),
                getConfig().getDouble("movement.vertical-down-ceiling", d.verticalDownCeiling()),
                getConfig().getDouble("movement.sustained-per-tick", d.sustainedPerTick()),
                getConfig().getDouble("movement.burst-credit-blocks", d.burstCreditBlocks()),
                getConfig().getDouble("movement.correction-divergence", d.correctionDivergence()),
                getConfig().getInt("movement.correction-hold-ticks", d.correctionHoldTicks()),
                getConfig().getInt("movement.violation-threshold", d.violationThreshold()))
                .normalized();
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, Pending> e : new ArrayList<>(pending.entrySet())) {
            Pending p = e.getValue();
            teardownStep(e.getKey(), "pending shutdown", () -> abandonPendingOnShutdown(p));
        }
        pending.clear();
        for (UUID uuid : new ArrayList<>(matches.keySet())) {
            EdgeMatch m = matches.get(uuid);
            if (m != null) {
                endMatch(uuid, m, EdgeOutcome.LOCAL_QUIT, null);
            }
        }
        matches.clear();
        matchMeta.clear();
        syncCursor.clear();
        assignmentIssuedAt.clear();
        pasteWaits.clear();
        deferredRestores.clear();
        exitNotices.clear();
        for (UUID uuid : new ArrayList<>(cages.keySet())) {
            guardTeardown("cage on shutdown for " + uuid, () -> clearCage(uuid));
        }
        cages.clear();
        for (UUID uuid : new ArrayList<>(overlays.keySet())) {
            guardTeardown("world overlay on shutdown for " + uuid, () -> restoreOverlay(uuid));
        }
        overlays.clear();
        for (UUID uuid : new ArrayList<>(loadouts.keySet())) {
            guardTeardown("loadout on shutdown for " + uuid, () -> restoreLoadout(uuid));
        }
        loadouts.clear();
        pasteWorlds.clear();
        EdgeArenaPaster p = paster;
        guardTeardown("arena paster", () -> {
            if (p != null) {
                p.shutdown();
            }
        });
        homes.clear();
        EdgeArenaWorld aw = arenaWorld;
        guardTeardown("arena world", () -> {
            if (aw != null) {
                aw.shutdown();
            }
        });
        DirectLink link = directLink;
        directLink = null;
        guardTeardown("direct link", () -> {
            if (link != null) {
                link.close();
            }
        });
        EdgeBroker b = broker;
        broker = null;
        guardTeardown("broker", () -> {
            if (b != null) {
                b.close();
            }
        });
        EdgeMetricsEndpoint endpoint = metricsEndpoint;
        metricsEndpoint = null;
        guardTeardown("metrics endpoint", () -> {
            if (endpoint != null) {
                endpoint.stop();
            }
        });
        guardTeardown("sim probe", SimProbe::uninstall);
        guardTeardown("packetevents", () -> PacketEvents.getAPI().terminate());
    }

    private void abandonPendingOnShutdown(Pending p) {
        EdgeBroker b = broker;
        if (p.brokered() && b != null) {
            recordOutcome(EdgeOutcome.NO_FRAME_ZERO, -1, "this edge shut down before frame 0");
            b.reportResult(p.sessionId(), p.slot(), -1, 0, 0, EdgeOutcome.NO_FRAME_ZERO,
                    "this edge shut down before frame 0");
        }
        p.handshake().close();
        releasePin(p.player());
    }

    private void applyArenaConditions(World world) {
        if (!lockArenaConditions) {
            return;
        }
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.DO_PATROL_SPAWNING, false);
        world.setGameRule(GameRule.DO_TRADER_SPAWNING, false);
        world.setGameRule(GameRule.DO_INSOMNIA, false);
        world.setTime(NOON);
        world.setStorm(false);
        world.setThundering(false);
        world.setClearWeatherDuration(Integer.MAX_VALUE);
        purgeMobs(world);
    }

    private void purgeMobs(World world) {
        for (Entity e : world.getEntities()) {
            if (e instanceof Mob) {
                e.remove();
            }
        }
    }

    private void holdArenaConditions() {
        if (!lockArenaConditions) {
            return;
        }
        for (World w : getServer().getWorlds()) {
            if (w.getTime() != NOON) {
                w.setTime(NOON);
            }
            if (w.hasStorm() || w.isThundering()) {
                w.setStorm(false);
                w.setThundering(false);
            }
            purgeMobs(w);
        }
    }

    private void tickAll() {
        tickPending();
        int worstDeficit = 0;
        for (Map.Entry<UUID, EdgeMatch> e : matches.entrySet()) {
            EdgeMatch m = e.getValue();
            boolean alive;
            try {
                alive = m.tick();
            } catch (Throwable ex) {
                getLogger().log(Level.WARNING, "edge match faulted - ending it so the player gets"
                        + " their attributes and inventory back", ex);
                alive = false;
            }
            if (!alive) {
                guardTeardown("match exit for " + e.getKey(), () -> finish(e.getKey(), m));
                continue;
            }
            worstDeficit = Math.max(worstDeficit, sampleSync(e.getKey(), m));
            if (++statusTick % 40 == 0) {
                getLogger().info("sync: head=" + m.driver().head()
                        + " confirmed=" + m.driver().confirmedFrame()
                        + " rollbacks=" + m.driver().rollbackCount()
                        + " desync=" + m.driver().desyncFrame());
            }
        }
        EdgeMetrics mx = metrics;
        if (mx != null) {
            mx.recordFrameDeficit(worstDeficit);
            if (++probeMirrorTick % PROBE_MIRROR_INTERVAL_TICKS == 0) {
                mx.mirrorProbes();
            }
        }
    }

    private int sampleSync(UUID uuid, EdgeMatch m) {
        int head = m.driver().head();
        int confirmed = m.driver().confirmedFrame();
        int deficit = Math.max(0, head - confirmed);
        EdgeMetrics mx = metrics;
        if (mx == null) {
            return deficit;
        }
        mx.hit(EdgeMetrics.SIM_FRAMES);
        int[] cursor = syncCursor.computeIfAbsent(uuid, ignored -> new int[]{0});
        int rollbacks = m.driver().rollbackCount();
        int fresh = rollbacks - cursor[0];
        if (fresh > 0) {
            cursor[0] = rollbacks;
            mx.recordRollback(fresh, deficit);
        }
        return deficit;
    }

    private void tickPending() {
        for (Map.Entry<UUID, Pending> e : pending.entrySet()) {
            Pending p = e.getValue();
            if (!p.player().isOnline()) {
                abandonPending(e.getKey(), p, EdgeOutcome.NO_FRAME_ZERO,
                        "our player left before frame 0", null);
                continue;
            }
            EdgeHandshake.Status status;
            try {
                status = p.handshake().pump();
            } catch (RuntimeException ex) {
                abandonPending(e.getKey(), p, EdgeOutcome.NO_FRAME_ZERO,
                        "arena handshake faulted: " + ex,
                        "match aborted before frame 0 - the arena handshake faulted");
                continue;
            }
            if (status == EdgeHandshake.Status.WAITING) {
                continue;
            }
            if (status == EdgeHandshake.Status.AGREED) {
                if (!paster.ready(p.world())) {
                    if (waitedTooLongForThePaste(e.getKey())) {
                        abandonPending(e.getKey(), p, EdgeOutcome.NO_FRAME_ZERO,
                                "the arena was still being pasted "
                                        + (PASTE_WAIT_TIMEOUT_TICKS / 20) + "s after both edges"
                                        + " agreed on it - refusing rather than holding the peer"
                                        + " open forever",
                                "match aborted before frame 0 - this edge could not finish"
                                        + " building the arena in time");
                    }
                    continue;
                }
                pending.remove(e.getKey());
                pasteWaits.remove(e.getKey());
                startMatch(e.getKey(), p);
                continue;
            }
            boolean mismatch = status == EdgeHandshake.Status.MISMATCH;
            String reason = mismatch
                    ? "arena mismatch: " + p.handshake().failure()
                    : "arena handshake timed out: " + p.handshake().failure();
            abandonPending(e.getKey(), p,
                    mismatch ? EdgeOutcome.ARENA_MISMATCH : EdgeOutcome.PEER_NEVER_ARRIVED,
                    reason, "match aborted before frame 0 - " + reason);
        }
    }

    private boolean waitedTooLongForThePaste(UUID uuid) {
        int[] waited = pasteWaits.computeIfAbsent(uuid, ignored -> new int[1]);
        return ++waited[0] >= PASTE_WAIT_TIMEOUT_TICKS;
    }

    private void abandonPending(UUID uuid, Pending p, String cause, String logLine,
                                String playerMessage) {
        pending.remove(uuid);
        pasteWaits.remove(uuid);
        teardownStep(uuid, "match resources", () -> releaseMatch(uuid));
        teardownStep(uuid, "arena handshake", () -> p.handshake().close());
        getLogger().warning("match refused for " + p.player().getName() + " - " + logLine);
        teardownStep(uuid, "refusal notice", () -> {
            if (playerMessage != null && p.player().isOnline()) {
                p.player().sendMessage(Component.text(playerMessage));
            }
        });
        if (!p.brokered()) {
            return;
        }
        EdgeBroker b = broker;
        if (b != null) {
            recordOutcome(cause, -1, logLine);
            b.reportResult(p.sessionId(), p.slot(), -1, 0, 0, cause, logLine);
        }
    }

    private void startMatch(UUID uuid, Pending p) {
        EdgeArenaPaster.Overlay overlay = paster.newOverlay(p.world());
        overlays.put(uuid, overlay);
        EdgeCageDisplay display = new EdgeCageDisplay(p.cage(), overlay, this, p.world());
        cages.put(uuid, display);
        placeCage(display, p.initial(), p.player());
        p.player().teleport(p.spawn());
        releasePin(p.player());
        EdgeMatch match;
        try {
            match = new EdgeMatch(p.player(), p.slot(), p.handshake(), p.arena().arena(),
                    p.initial(), new EdgeTelemetry(getLogger(), p.player().getName(), metrics),
                    movementLimits, p.agreement(), p.loadout(), display, this, p.pots(), p.totems(),
                    p.world(), overlay);
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "[edge] the match failed to build at frame 0 for "
                    + p.player().getName(), t);
            abandonPending(uuid, p, EdgeOutcome.NO_FRAME_ZERO,
                    "the match failed to build at frame 0: " + t,
                    "match aborted at frame 0 - this edge could not start the sim");
            return;
        }
        match.markServerTeleport();
        matches.put(uuid, match);
        matchMeta.put(uuid, new MatchMeta(p.sessionId(), p.slot(), p.brokered()));
        syncCursor.put(uuid, new int[]{0});
        EdgeMetrics mx = metrics;
        if (mx != null) {
            mx.hit(EdgeMetrics.MATCHES_STARTED);
            Long issued = assignmentIssuedAt.remove(uuid);
            if (issued != null) {
                mx.recordFrameZeroLatency(System.currentTimeMillis() - issued);
            }
        }
        ArenaAgreement peer = p.handshake().peer();
        getLogger().info("arena agreed with the peer: hash="
                + ArenaAgreement.hex(p.agreement().arenaHash())
                + " local=" + p.arena().describe()
                + " peer-source=" + (peer != null ? peer.source() : "?"));
        p.player().sendMessage(Component.text("arena verified ("
                + ArenaAgreement.hex(p.agreement().arenaHash()) + ") - match started as slot "
                + p.slot() + ", first to " + p.initial().roundsTarget));
    }

    private void releasePaste(UUID uuid) {
        World world = pasteWorlds.remove(uuid);
        EdgeArenaPaster p = paster;
        if (world != null && p != null) {
            p.release(world);
        }
    }

    private void restoreOverlay(UUID uuid) {
        EdgeArenaPaster.Overlay overlay = overlays.remove(uuid);
        if (overlay == null) {
            return;
        }
        try {
            overlay.restoreAll();
        } catch (RuntimeException ex) {
            getLogger().warning("failed to put back the world blocks this match painted: " + ex);
        }
    }

    private void releaseMatch(UUID uuid) {
        guardTeardown("cage for " + uuid, () -> clearCage(uuid));
        guardTeardown("world overlay for " + uuid, () -> restoreOverlay(uuid));
        guardTeardown("return from the arena world for " + uuid, () -> sendHome(uuid));
        guardTeardown("arena paste for " + uuid, () -> releasePaste(uuid));
        guardTeardown("pre-match inventory for " + uuid, () -> restoreLoadout(uuid));
        guardTeardown("gravity for " + uuid, () -> releasePin(getServer().getPlayer(uuid)));
    }

    private void sendHome(UUID uuid) {
        Location home = homes.remove(uuid);
        EdgeArenaWorld aw = arenaWorld;
        Player player = getServer().getPlayer(uuid);
        if (aw == null || player == null || !player.isOnline() || !aw.is(player.getWorld())) {
            return;
        }
        if (home == null || home.getWorld() == null || aw.is(home.getWorld())) {
            home = aw.home();
        }
        try {
            player.teleport(home);
        } catch (RuntimeException ex) {
            getLogger().warning("failed to move " + player.getName() + " out of the arena world: "
                    + ex);
        }
    }

    private void pinBeforeFrameZero(Player player) {
        try {
            player.setFallDistance(0f);
            player.setGravity(false);
        } catch (RuntimeException ex) {
            getLogger().warning("failed to pin " + player.getName() + " while the arena handshake"
                    + " runs - they may drop out of the cage spawn before frame 0: " + ex);
        }
    }

    private void releasePin(Player player) {
        if (player == null) {
            return;
        }
        try {
            player.setGravity(true);
            player.setFallDistance(0f);
        } catch (RuntimeException ex) {
            getLogger().warning("failed to restore gravity for " + player.getName() + ": " + ex);
        }
    }

    private void sweepOrphanedCageDisplays() {
        NamespacedKey tag = EdgeCageDisplay.key(this);
        int removed = 0;
        for (World world : getServer().getWorlds()) {
            for (BlockDisplay display : world.getEntitiesByClass(BlockDisplay.class)) {
                if (!display.getPersistentDataContainer().has(tag, PersistentDataType.BYTE)) {
                    continue;
                }
                try {
                    display.remove();
                    removed++;
                } catch (RuntimeException ignored) {
                    continue;
                }
            }
        }
        if (removed > 0) {
            getLogger().info("removed " + removed + " cage gate display(s) left behind by an"
                    + " unclean shutdown");
        }
    }

    private void placeCage(EdgeCageDisplay display, GameState initial, Player player) {
        try {
            display.place(initial);
            display.resend(player);
        } catch (RuntimeException ex) {
            getLogger().warning("failed to raise the cage before the first countdown tick: " + ex);
        }
    }

    private void clearCage(UUID uuid) {
        EdgeCageDisplay display = cages.remove(uuid);
        if (display == null) {
            return;
        }
        try {
            display.remove();
        } catch (RuntimeException ex) {
            getLogger().warning("failed to clear the between-round cage: " + ex);
        }
    }

    private void restoreLoadout(UUID uuid) {
        LoadoutSession session = loadouts.remove(uuid);
        if (session == null) {
            return;
        }
        Player player = getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            deferredRestores.put(uuid, session);
            return;
        }
        try {
            EdgeLoadout.restore(player, session.before());
        } catch (RuntimeException ex) {
            getLogger().warning("failed to restore the pre-match inventory: " + ex);
        }
    }

    private void finish(UUID uuid, EdgeMatch m) {
        if (m.aborted()) {
            getLogger().warning("match aborted: " + m.abortReason());
        }
        endMatch(uuid, m, null, () -> showFarewell(m));
    }

    private void showFarewell(EdgeMatch m) {
        Player p = m.player();
        if (p == null || !p.isOnline()) {
            return;
        }
        if (m.driver().peerDisconnected()) {
            p.sendMessage(Component.text("your opponent left the match - you win by forfeit"));
        } else if (m.aborted()) {
            p.sendMessage(Component.text("match aborted: " + m.abortReason()));
        } else {
            boolean won = m.driver().localWon();
            int winsMine = m.driver().roundWins(m.slot());
            int winsTheirs = m.driver().roundWins(1 - m.slot());
            m.renderer().showResult(won, winsMine, winsTheirs, m.driver().state());
        }
    }

    private void endMatch(UUID uuid, EdgeMatch m, String localExit, Runnable farewell) {
        matches.remove(uuid);
        try {
            teardownStep(uuid, "result report", () -> reportResult(uuid, m, localExit));
            if (farewell != null) {
                teardownStep(uuid, "end screen", farewell);
            }
            teardownStep(uuid, "match resources", () -> releaseMatch(uuid));
        } finally {
            teardownStep(uuid, "driver end", m::end);
            matchMeta.remove(uuid);
            syncCursor.remove(uuid);
        }
    }

    private void teardownStep(UUID uuid, String what, Runnable step) {
        guardTeardown(uuid == null ? what : what + " for " + uuid, step);
    }

    private void guardTeardown(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "[edge] teardown step '" + what + "' failed - the"
                    + " remaining teardown steps still ran", t);
        }
    }

    private void reportResult(UUID uuid, EdgeMatch m, String localExit) {
        MatchMeta meta = matchMeta.remove(uuid);
        syncCursor.remove(uuid);
        EdgeBroker b = broker;
        if (meta == null || b == null || !meta.brokered()) {
            return;
        }
        int winnerSlot = -1;
        String cause;
        String detail;
        if (localExit != null) {
            cause = localExit;
            detail = localExit + " while the match was live";
            winnerSlot = 1 - meta.slot();
        } else if (!m.aborted()) {
            winnerSlot = m.driver().localWon() ? meta.slot() : 1 - meta.slot();
            cause = EdgeOutcome.FINISHED;
            detail = "the sim confirmed the match over";
        } else if (m.driver().selfFaulted()) {
            cause = EdgeOutcome.SELF_FAULT;
            winnerSlot = 1 - meta.slot();
            detail = "our own sim faulted: " + m.abortReason();
        } else if (m.driver().desyncFrame() >= 0) {
            cause = EdgeOutcome.DESYNC;
            detail = "checksum mismatch at frame " + m.driver().desyncFrame();
        } else if (m.driver().peerFaulted()) {
            cause = EdgeOutcome.PEER_OVERRUN;
            detail = m.abortReason();
        } else if (m.driver().peerAnnouncedDesync()) {
            cause = EdgeOutcome.DESYNC_ANNOUNCED;
            detail = m.abortReason();
        } else if (m.abortReason() != null && m.abortReason().startsWith("arena mismatch")) {
            cause = EdgeOutcome.ARENA_MISMATCH;
            detail = m.abortReason();
        } else {
            cause = EdgeOutcome.PEER_GONE;
            detail = m.abortReason() == null ? "the peer stopped sending" : m.abortReason();
        }
        recordOutcome(cause, m.driver().desyncFrame(), detail);
        b.reportResult(meta.sessionId(), meta.slot(), winnerSlot,
                m.driver().roundWins(0), m.driver().roundWins(1), cause, detail);
    }

    private void recordOutcome(String cause, int desyncFrame, String detail) {
        EdgeMetrics mx = metrics;
        if (mx == null) {
            return;
        }
        mx.outcome(cause);
        if (EdgeOutcome.DESYNC.equals(cause) || EdgeOutcome.DESYNC_ANNOUNCED.equals(cause)) {
            mx.recordDesync(desyncFrame, detail);
        }
        EdgeDesyncAlert alert = desyncAlert;
        if (alert != null) {
            alert.matchEnded(cause, System.currentTimeMillis());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("ping")) {
            return reportPing(sender);
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("metrics")) {
            EdgeMetrics mx = metrics;
            if (mx == null) {
                sender.sendMessage(Component.text("metrics are not running on this edge"));
                return true;
            }
            EdgeMetrics.Snapshot snapshot = mx.snapshot();
            EdgeDesyncAlert alert = desyncAlert;
            if (alert != null && alert.firing()) {
                sender.sendMessage(Component.text("DESYNC ALERT FIRING - see RUNBOOK.md"));
            }
            for (String line : EdgeMetrics.human(snapshot)) {
                sender.sendMessage(Component.text(line));
            }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("attackspeed")) {
            return attackSpeedCommand(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("players only");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("stop")) {
            Pending waiting = pending.remove(player.getUniqueId());
            if (waiting != null) {
                waiting.handshake().close();
            }
            EdgeMatch existing = matches.get(player.getUniqueId());
            if (existing != null) {
                endMatch(player.getUniqueId(), existing, EdgeOutcome.LOCAL_QUIT, null);
            } else {
                matchMeta.remove(player.getUniqueId());
                releaseMatch(player.getUniqueId());
            }
            player.sendMessage(Component.text("edge match stopped"));
            return true;
        }
        if (matches.containsKey(player.getUniqueId()) || pending.containsKey(player.getUniqueId())) {
            player.sendMessage(Component.text("already in an edge match"));
            return true;
        }

        player.sendMessage(Component.text("/edge builds the " + localGameType + " demo kit locally"
                + " - it carries no setup bytes, so the other edge must be on the same build and"
                + " the same game-type. Real kits only come from mcleagues-core."));
        beginMatch(player, sessionId, slot, new byte[32], relayHost, relayPort,
                spawnX0, spawnY0, spawnZ0, spawnX1, spawnY1, spawnZ1,
                SpawnFacing.yaw(spawnX0, spawnZ0, spawnX1, spawnZ1), 0f,
                SpawnFacing.yaw(spawnX1, spawnZ1, spawnX0, spawnZ0), 0f,
                AUTO, localRounds, null, EdgeCage.fallback(), null, false,
                EdgeGameTypes.pots(localGameType), EdgeGameTypes.totems(localGameType),
                null, false, localLagMs, "/edge");
        return true;
    }

    private boolean reportPing(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("/ping is a player command"));
            return true;
        }
        Integer lag = simulatedLag.get(player.getUniqueId());
        int oneWay = lag == null ? 0 : lag;
        if (oneWay <= 0) {
            player.sendMessage(Component.text("simulated lag: none. Your link to the relay is"
                    + " whatever the network actually gives you.", NamedTextColor.GRAY));
        } else {
            player.sendMessage(Component.text("simulated lag: +" + oneWay + "ms one way, so +"
                    + (oneWay * 2) + "ms round trip on top of your real latency."
                    + " This is injected on THIS edge between the sim and the relay,"
                    + " so it delays your inputs reaching your opponent and theirs reaching you,"
                    + " exactly like a slow link would.", NamedTextColor.YELLOW));
        }
        EdgeMatch match = matches.get(player.getUniqueId());
        if (match == null) {
            player.sendMessage(Component.text("not in a match, so there is no peer to measure.",
                    NamedTextColor.GRAY));
            return true;
        }
        MatchDriver driver = match.driver();
        long rollbacks = driver.rollbackCount();
        long resim = driver.resimulatedFrames();
        String depth = rollbacks == 0 ? "n/a"
                : String.format(Locale.ROOT, "%.2f", resim / (double) rollbacks);
        player.sendMessage(Component.text("peer is " + driver.peerFrameAdvantage()
                + " frames ahead of me, I am " + driver.myFrameAdvantage() + " ahead of them."
                + " That gap is what the link costs you - time sync paces the leading side, so"
                + " head minus confirmed stays near 1 no matter the latency and tells you nothing.",
                NamedTextColor.GRAY));
        player.sendMessage(Component.text("rollback depth " + depth + " frames on average ("
                + rollbacks + " rollbacks, " + resim + " frames resimulated). A rollback almost"
                + " every frame is NORMAL here: your opponent's authoritative position is part of"
                + " their Input, it changes every tick while they move, so the repeat-last-input"
                + " prediction can never match it. Watch the DEPTH, not the count.",
                NamedTextColor.GRAY));
        return true;
    }

    private void beginMatch(Player player, long session, int matchSlot, byte[] token,
                            String rHost, int rPort,
                            double ax0, double ay0, double az0,
                            double ax1, double ay1, double az1,
                            float ayaw0, float apitch0, float ayaw1, float apitch1,
                            double assignedGroundY, int rounds, byte[] setup, EdgeCage cage,
                            EdgeArena assignedArena,
                            boolean pinned, boolean pots, boolean totems, DirectPeer direct,
                            boolean brokered, int lagMs, String source) {
        EdgeLoadout loadout;
        try {
            loadout = setup == null || setup.length == 0 ? null : EdgeLoadout.decode(setup);
        } catch (RuntimeException ex) {
            refuseBeforeFrameZero(player, session, matchSlot, brokered,
                    "the assignment setup bytes are unreadable (" + ex + ") - refusing the match:"
                            + " falling back to an empty kit would build a different frame 0 than"
                            + " the peer",
                    "match refused: the kit payload could not be decoded");
            return;
        }
        if (loadout == null && brokered) {
            refuseBeforeFrameZero(player, session, matchSlot, true,
                    "the brokered assignment carries no setup bytes - refusing the match: the"
                            + " locally synthesised frame 0 has no kit, no item dictionary and only"
                            + " the spawns this edge was told, so it is NOT the frame 0 the peer"
                            + " builds. Send setup bytes from mcleagues-core, or enable"
                            + " dev-kit.enabled on BOTH edges so both derive the same demo kit.",
                    "match refused: the assignment carried no kit, so this edge cannot build the"
                            + " same frame 0 as your opponent");
            return;
        }
        if (loadout != null) {
            getLogger().info("loadout decoded from the assignment: " + loadout.describe());
            if (rounds > 0 && rounds != loadout.rounds()) {
                getLogger().warning("assignment rounds=" + rounds + " is ignored: the setup bytes"
                        + " define rounds=" + loadout.rounds() + ", and frame 0 must come from the"
                        + " bytes both edges share");
            }
            rounds = loadout.rounds();
            ax0 = loadout.spawnX(0);
            ay0 = loadout.spawnY(0);
            az0 = loadout.spawnZ(0);
            ax1 = loadout.spawnX(1);
            ay1 = loadout.spawnY(1);
            az1 = loadout.spawnZ(1);
        }
        boolean fixedSpawns = pinned || loadout != null;

        World hostWorld = player.getWorld();
        EdgeArena arena = assignedArena != null ? assignedArena : loadedArena;
        if (assignedArena != null) {
            getLogger().info("using the arena the assignment shipped (" + assignedArena.describe()
                    + ") - the local " + (loadedArena != null ? arenaFileName : "flat fallback")
                    + " is ignored for this match");
        }
        double gy;
        if (arena != null) {
            gy = arena.groundY();
        } else if (pinned && assignedGroundY > AUTO) {
            gy = assignedGroundY;
            arena = EdgeArena.flat(gy);
        } else if (loadout != null) {
            gy = loadout.groundY();
            arena = EdgeArena.flat(gy);
        } else {
            gy = groundY;
            if (gy <= AUTO) {
                gy = surfaceY(hostWorld, ax0, az0);
                getLogger().info("resolved ground-y=" + gy
                        + " (set ground-y in config.yml to pin it; BOTH edges must match)");
            }
            arena = EdgeArena.flat(gy);
        }
        if (pinned && assignedGroundY > AUTO && arena.groundY() != assignedGroundY) {
            getLogger().warning("assignment ground-y=" + assignedGroundY
                    + " differs from the loaded arena ground-y=" + arena.groundY()
                    + " - the arena handshake will refuse this match");
        }
        if (devKitEnabled && !arena.fromFile()) {
            getLogger().warning("#### DEV MATCH ON THE FLAT FALLBACK ARENA ####");
            getLogger().warning("  the flat fallback is an unbreakable ground slab with no voxel"
                    + " terrain. Combat.blastResistanceAt and Combat.scatterFire read arena"
                    + " terrain through isSolidVoxel/isDecorVoxel and never see that slab, so on"
                    + " this arena a crystal or respawn-anchor blast can destroy NOTHING except"
                    + " blocks a player placed, leaves no crater, starts no fire, and a pickaxe"
                    + " can mine no terrain at all.");
            getLogger().warning("  an anchor still places, charges and detonates here, so the"
                    + " anchor loop itself is testable - but 'the explosion did nothing to blocks'"
                    + " is what this arena looks like even when the sim is correct.");
            getLogger().warning("  run 'gradlew devSampleArena' once and restart both edges, or"
                    + " pass -ParenaName=<arena> to devAssign, before judging any explosion, fire"
                    + " or mining behaviour.");
        }
        if (loadout != null && arena.groundY() != loadout.groundY()) {
            getLogger().warning("setup ground-y=" + loadout.groundY()
                    + " differs from the loaded arena ground-y=" + arena.groundY()
                    + " - the arena file wins for collision, but check that they describe the"
                    + " same arena");
        }

        double devDrop = fixedSpawns ? 0.0 : cageDropHeight;
        double y0 = fixedSpawns ? ay0 : resolveSpawnY(ay0, ax0, az0, gy, arena) + devDrop;
        double y1 = fixedSpawns ? ay1 : resolveSpawnY(ay1, ax1, az1, gy, arena) + devDrop;
        double sx = matchSlot == 0 ? ax0 : ax1;
        double sy = matchSlot == 0 ? y0 : y1;
        double sz = matchSlot == 0 ? az0 : az1;
        if (devDrop > 0.0) {
            getLogger().info("raising the /edge spawns " + devDrop + " blocks so the cage hangs in"
                    + " the air; the sim grants " + Simulation.CAGE_FALL_GRACE
                    + " fall-grace ticks at the round start, which covers the drop");
        }

        if (loadout == null && !brokered) {
            loadout = localDevKit(session, matchSlot, arena.groundY(), rounds,
                    ax0, y0, az0, ax1, y1, az1, ayaw0, apitch0, ayaw1, apitch1);
        }

        boolean pasting = paster.pastes(arena);
        World world = pasting ? arenaWorld.or(hostWorld) : hostWorld;
        if (!pasting) {
            warnWorldMismatch(player, arena, sx, sy - devDrop, sz);
        }
        float spawnYaw = loadout != null ? loadout.spawnYaw(matchSlot)
                : (matchSlot == 0 ? ayaw0 : ayaw1);
        float spawnPitch = loadout != null ? loadout.spawnPitch(matchSlot)
                : (matchSlot == 0 ? apitch0 : apitch1);
        Location spawn = new Location(world, sx, sy, sz, spawnYaw, spawnPitch);
        EdgeArenaPaster.Outcome acquired = paster.acquire(world, arena, () -> {
            if (pasting && player.isOnline()) {
                player.sendMessage(Component.text("arena built in the world - waiting on the peer"));
            }
        });
        if (!acquired.ok()) {
            getLogger().severe("refusing the match for " + player.getName() + ": "
                    + acquired.reason());
            player.sendMessage(Component.text("match refused: " + acquired.reason()));
            return;
        }
        if (pasting) {
            pasteWorlds.put(player.getUniqueId(), world);
        }
        if (!world.equals(hostWorld)) {
            homes.putIfAbsent(player.getUniqueId(), player.getLocation());
        }
        player.teleport(spawn);
        pinBeforeFrameZero(player);
        if (loadout != null) {
            loadouts.put(player.getUniqueId(),
                    new LoadoutSession(loadout, loadout.apply(player, matchSlot)));
            reportAttackSpeedSkew(player, loadout, matchSlot);
        }

        Transport transport;
        String wire;
        try {
            if (direct != null) {
                transport = directLink.open(session, matchSlot, direct.address(), direct.token());
                wire = "direct " + direct.address();
            } else {
                transport = new UdpTransport(new InetSocketAddress(rHost, rPort), session, matchSlot,
                        token);
                wire = "relay " + rHost + ":" + rPort;
            }
            int lag = LaggedTransport.clamp(lagMs);
            if (lag > 0) {
                transport = new LaggedTransport(transport, lag);
                wire = wire + " +" + lag + "ms one-way (SIMULATED, dev only)";
                simulatedLag.put(player.getUniqueId(), lag);
            } else {
                simulatedLag.remove(player.getUniqueId());
            }
        } catch (Exception ex) {
            getLogger().warning("peer link setup failed for " + player.getName() + ": " + ex);
            player.sendMessage(Component.text("peer link setup failed: " + ex));
            releaseMatch(player.getUniqueId());
            return;
        }

        int target = Math.max(1, rounds);
        GameState initial = loadout != null ? loadout.state()
                : EdgeMatch.seed(ax0, y0, az0, ax1, y1, az1, target);
        ArenaAgreement agreement = new ArenaAgreement(arena.hash(), arena.groundY(),
                ax0, y0, az0, ax1, y1, az1,
                loadout != null ? Checksum.of(initial) : ArenaAgreement.NO_STATE,
                arena.source());
        EdgeHandshake handshake = new EdgeHandshake(transport, agreement, arenaHandshakeTimeoutTicks);
        pending.put(player.getUniqueId(),
                new Pending(player, session, matchSlot, handshake, arena, initial, agreement,
                        world, spawn, loadout, cage, pots, totems, brokered));
        pasteWaits.remove(player.getUniqueId());
        getLogger().info("match pending for " + player.getName() + " (" + source + ") session="
                + session + " slot=" + matchSlot + " link=" + wire
                + " first-to=" + target + " kit=" + (loadout != null ? "from setup" : "none")
                + " cage=" + (cage != null ? cage.describe() : "none"));
        player.sendMessage(Component.text("verifying the arena with the peer ("
                + arena.source() + " " + ArenaAgreement.hex(arena.hash())
                + ") - no frame is simulated until both edges agree"));
    }

    private void refuseBeforeFrameZero(Player player, long session, int matchSlot, boolean brokered,
                                       String logLine, String playerLine) {
        getLogger().severe(logLine);
        player.sendMessage(Component.text(playerLine));
        if (!brokered) {
            return;
        }
        EdgeBroker b = broker;
        if (b != null) {
            recordOutcome(EdgeOutcome.NO_FRAME_ZERO, -1, logLine);
            b.reportResult(session, matchSlot, -1, 0, 0, EdgeOutcome.NO_FRAME_ZERO, logLine);
        }
    }

    private void reportAttackSpeedSkew(Player player, EdgeLoadout loadout, int matchSlot) {
        AttributeInstance attribute = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attribute == null) {
            return;
        }
        float sim = loadout.meleeSpeed(matchSlot, loadout.selectedSlot(matchSlot));
        double live = attribute.getValue();
        if (Math.abs(live - sim) <= ATTACK_SPEED_SKEW_TOLERANCE) {
            return;
        }
        getLogger().info("attack-speed skew for " + player.getName() + ": the sim uses " + sim
                + " but the client's cooldown indicator is showing " + live
                + " - EdgeStatusMirror drives the attribute from the held item for every rendered"
                + " frame of the match, so an unmodded player times their swings against the speed"
                + " the sim actually charges them, and it puts the player's own base back when the"
                + " match ends. Nothing else may write that attribute: a writer that shifts it"
                + " before the mirror has read it makes the mirror adopt the shift as the"
                + " baseline, and the shift then outlives the match");
    }

    private double resolveSpawnY(double configured, double x, double z, double gy,
                                 EdgeArena arena) {
        if (configured > AUTO) {
            return configured;
        }
        if (!arena.fromFile()) {
            return gy;
        }
        double resolved = arena.standY((int) Math.floor(x), (int) Math.floor(z));
        getLogger().info("resolved spawn y=" + resolved + " at " + x + "," + z
                + " from the arena file, so both edges resolve the same value"
                + " (set spawn.y0/spawn.y1 in config.yml to pin it)");
        return resolved;
    }

    private static double surfaceY(World world, double x, double z) {
        return world.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1.0;
    }

    private void warnWorldMismatch(Player player, EdgeArena arena, double x, double y, double z) {
        World world = player.getWorld();
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int floorY = (int) Math.floor(y) - 1;
        double worldSurface = surfaceY(world, x, z);
        boolean simSolid = arena.simSolid(bx, floorY, bz);
        boolean worldSolid = world.getBlockAt(bx, floorY, bz).getType().isSolid();
        if (worldSurface == y && simSolid && worldSolid) {
            return;
        }
        getLogger().warning("#### SIM ARENA / WORLD MISMATCH AT THE SPAWN POINT ####");
        getLogger().warning("  arena: " + arena.describe());
        getLogger().warning("  spawn: " + x + "," + y + "," + z + " (slot " + slot + ")");
        getLogger().warning("  world highest block + 1 here: " + worldSurface);
        getLogger().warning("  block under the spawn: sim solid=" + simSolid
                + " world solid=" + worldSolid);
        getLogger().warning("  nothing is being pasted for this match (arena.paste is off, or the"
                + " arena is the flat fallback) - the world must already contain the same geometry"
                + " as the sim arena, or players will fall through floors or stand inside walls");
        player.sendMessage(Component.text("warning: the world does not match the sim arena at your"
                + " spawn (world surface " + worldSurface + " vs sim spawn " + y + ") - see the"
                + " server log"));
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (lockArenaConditions) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        EdgeMatch m = matches.get(event.getPlayer().getUniqueId());
        if (m != null) {
            m.input().onInput(event.getInput());
        }
    }

    @EventHandler
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) {
            return;
        }
        if (matches.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && inMatch(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onRegainHealth(EntityRegainHealthEvent event) {
        if (event.getEntity() instanceof Player p && inMatch(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p && inMatch(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private boolean inMatch(UUID uuid) {
        return matches.containsKey(uuid) || pending.containsKey(uuid);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!inMatch(event.getPlayer().getUniqueId())) {
            return;
        }
        if (allowsVanillaUse(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean allowsVanillaUse(Player player) {
        EdgeMatch m = matches.get(player.getUniqueId());
        return m != null && m.input().vanillaUseAllowed();
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (inMatch(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockMultiPlace(BlockMultiPlaceEvent event) {
        if (inMatch(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (inMatch(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (inMatch(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (inMatch(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (inMatch(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null && inMatch(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player p && inMatch(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLoadCrossbow(EntityLoadCrossbowEvent event) {
        if (event.getEntity() instanceof Player p && inMatch(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (inMatch(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player p && inMatch(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (inMatch(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        Player p = event.getPlayer();
        if (!inMatch(p.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        EdgeMatch match = matches.get(p.getUniqueId());
        if (match != null) {
            int slot = p.getInventory().getHeldItemSlot();
            boolean whole = event.getItemDrop().getItemStack().getAmount() > 1;
            match.input().onInventoryDrop(slot, whole);
            match.renderer().inventory().own(InventoryIntents.slotThrow(slot, whole),
                    match.driver().head());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p) || !inMatch(p.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        routeInventoryIntent(p, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        EdgeMatch match = matches.get(p.getUniqueId());
        if (match == null) {
            return;
        }
        event.setCancelled(true);
        match.renderer().inventory().resyncAll();
        match.renderer().container().resyncAll();
    }

    private static final int OUTSIDE_RAW_SLOT = -999;

    private void routeInventoryIntent(Player p, InventoryClickEvent event) {
        EdgeMatch match = matches.get(p.getUniqueId());
        if (match == null) {
            return;
        }
        boolean container = match.renderer().container().owns(event.getView().getTopInventory());
        if (!container && event.getView().getType() != InventoryType.CRAFTING) {
            return;
        }
        int addr = clickAddress(match, event, container);
        ClickType click = event.getClick();
        ItemStack clicked = event.getCurrentItem();
        boolean filled = clicked != null && !clicked.getType().isAir()
                && clicked.getAmount() > 0;
        ItemStack cursor = event.getCursor();
        boolean cursorEmpty = cursor == null || cursor.getType().isAir()
                || cursor.getAmount() <= 0;
        InventoryIntents.Intent intent = InventoryIntents.decide(clickKind(click), addr,
                clickButton(click, event), filled, cursorEmpty);
        if (!intent.acts()) {
            match.renderer().inventory().resyncAll();
            match.renderer().container().resyncAll();
            return;
        }
        match.input().onInventoryIntent(intent);
        int headFrame = match.driver().head();
        match.renderer().inventory().own(intent, headFrame);
        match.renderer().container().own(intent, headFrame);
    }

    private static int clickKind(ClickType click) {
        return switch (click) {
            case LEFT, RIGHT, WINDOW_BORDER_LEFT, WINDOW_BORDER_RIGHT
                    -> InventoryIntents.CLICK_PICKUP;
            case SHIFT_LEFT, SHIFT_RIGHT -> InventoryIntents.CLICK_QUICK_MOVE;
            case NUMBER_KEY, SWAP_OFFHAND -> InventoryIntents.CLICK_SWAP;
            case DROP, CONTROL_DROP -> InventoryIntents.CLICK_THROW;
            case DOUBLE_CLICK -> InventoryIntents.CLICK_PICKUP_ALL;
            default -> InventoryIntents.CLICK_UNKNOWN;
        };
    }

    private static int clickButton(ClickType click, InventoryClickEvent event) {
        return switch (click) {
            case RIGHT, WINDOW_BORDER_RIGHT, SHIFT_RIGHT, CONTROL_DROP
                    -> InventoryIntents.BUTTON_SECONDARY;
            case NUMBER_KEY -> event.getHotbarButton();
            case SWAP_OFFHAND -> InventoryIntents.OFFHAND_SWAP_BUTTON;
            default -> InventoryIntents.BUTTON_PRIMARY;
        };
    }

    private int clickAddress(EdgeMatch match, InventoryClickEvent event, boolean container) {
        if (event.getRawSlot() == OUTSIDE_RAW_SLOT) {
            return InventoryIntents.ADDR_OUTSIDE;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return InventoryIntents.ADDR_NONE;
        }
        int raw = event.getSlot();
        if (container && match.renderer().container().owns(clicked)) {
            return raw >= 0 && raw < Container.CELLS ? Input.cellAddr(raw)
                    : InventoryIntents.ADDR_NONE;
        }
        if (clicked.getType() != InventoryType.PLAYER) {
            return InventoryIntents.ADDR_NONE;
        }
        return raw >= 0 && raw < ItemDict.SLOTS ? raw : InventoryIntents.ADDR_NONE;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) {
            return;
        }
        EdgeMatch match = matches.get(p.getUniqueId());
        if (match == null) {
            return;
        }
        match.input().onCursorResolve();
        match.renderer().inventory().own(InventoryIntents.cursorResolve(), match.driver().head());
        EdgeContainerMirror mirror = match.renderer().container();
        if (!mirror.owns(event.getInventory())) {
            return;
        }
        if (mirror.closing()) {
            mirror.forget();
            return;
        }
        match.input().onCloseContainer();
        mirror.closedByPlayer();
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHandItems(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        if (!inMatch(p.getUniqueId())) {
            return;
        }
        event.setCancelled(true);
        EdgeMatch match = matches.get(p.getUniqueId());
        if (match != null) {
            match.input().onSwapHands();
            match.renderer().inventory().ownHandSwap(p.getInventory().getHeldItemSlot(),
                    match.driver().head());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (mirrored(event.getEntity().getWorld())) {
            event.setCancelled(true);
        }
    }

    private boolean mirrored(World world) {
        if (world == null || matches.isEmpty()) {
            return false;
        }
        for (EdgeMatch m : matches.values()) {
            Player p = m.player();
            if (p != null && p.isOnline() && world.equals(p.getWorld())) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (mirrored(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        if (mirrored(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (mirrored(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        if (mirrored(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        if (mirrored(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (mirrored(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (mirrored(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (mirrored(event.getLocation().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (mirrored(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (mirrored(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (mirrored(event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        EdgeMatch m = matches.get(event.getPlayer().getUniqueId());
        if (m == null || m.input().correcting()) {
            return;
        }
        m.markServerTeleport();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Pending waiting = pending.remove(uuid);
        if (waiting != null) {
            pasteWaits.remove(uuid);
            waiting.handshake().close();
            if (waiting.brokered()) {
                EdgeBroker b = broker;
                if (b != null) {
                    recordOutcome(EdgeOutcome.NO_FRAME_ZERO, -1,
                            "our player left before frame 0");
                    b.reportResult(waiting.sessionId(), waiting.slot(), -1, 0, 0,
                            EdgeOutcome.NO_FRAME_ZERO, "our player left before frame 0");
                }
            }
        }
        EdgeMatch m = matches.get(uuid);
        if (m != null) {
            endMatch(uuid, m, EdgeOutcome.LOCAL_QUIT, null);
            exitNotices.put(uuid, "you left a live match - it was forfeited to your opponent");
        }
        releaseMatch(uuid);
        releasePin(event.getPlayer());
        matchMeta.remove(uuid);
        if (modHello != null) {
            modHello.forget(uuid);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        UUID uuid = joined.getUniqueId();
        LoadoutSession stranded = deferredRestores.remove(uuid);
        if (stranded != null) {
            try {
                EdgeLoadout.restore(joined, stranded.before());
            } catch (RuntimeException ex) {
                getLogger().warning("failed to restore " + joined.getName()
                        + "'s pre-match inventory on rejoin: " + ex);
            }
        }
        EdgeArenaWorld arena = arenaWorld;
        if (arena != null && arena.is(joined.getWorld())) {
            arena.sendHome(joined);
            joined.sendMessage(Component.text("you logged back in inside the arena world - it is"
                    + " rebuilt for every match, so you were moved back out"));
        }
        if (attackSpeedRepair) {
            EdgeAttackSpeed.repair(joined, getLogger(), "joining this edge", false);
        }
        String notice = exitNotices.remove(uuid);
        if (notice != null) {
            joined.sendMessage(Component.text(notice));
        }
        EdgeBroker b = broker;
        if (b == null) {
            return;
        }
        getServer().getScheduler().runTaskLater(this, () -> {
            if (joined.isOnline()) {
                tryStartBrokered(b, joined);
            }
        }, 20L);
    }

}
