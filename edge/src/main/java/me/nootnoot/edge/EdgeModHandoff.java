package me.nootnoot.edge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.logging.Logger;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.VersionFence;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class EdgeModHandoff {

    public static final String SETUP_CHANNEL = "jackpotrollback:match_setup";
    public static final String ARENA_CHANNEL = "jackpotrollback:arena";
    public static final String CAGE_CHANNEL = "jackpotrollback:cage";

    public static final int DEFAULT_FRAGMENT_BYTES = 700_000;
    public static final long DEFAULT_TRANSFER_DELAY_TICKS = 40L;

    public interface BlobSource {
        byte[] fetch(String key);
    }

    private final Plugin plugin;
    private final Logger log;
    private final EdgeModHello hello;
    private final String limboHost;
    private final int limboPort;
    private final int fragmentBytes;
    private final long transferDelayTicks;
    private final boolean requireHello;

    public EdgeModHandoff(Plugin plugin, Logger log, EdgeModHello hello, String limboHost,
                          int limboPort, int fragmentBytes, long transferDelayTicks,
                          boolean requireHello) {
        this.plugin = plugin;
        this.log = log;
        this.hello = hello;
        this.limboHost = limboHost;
        this.limboPort = limboPort;
        this.fragmentBytes = fragmentBytes > 0 ? fragmentBytes : DEFAULT_FRAGMENT_BYTES;
        this.transferDelayTicks = transferDelayTicks;
        this.requireHello = requireHello;
    }

    public String describe() {
        return "limbo=" + limboHost + ":" + limboPort + " delay=" + transferDelayTicks + "t"
                + " fragment=" + fragmentBytes + "B require-hello=" + requireHello;
    }

    public void start(Player player, EdgeAssignment assignment, BlobSource blobs) {
        UUID uuid = player.getUniqueId();
        Integer modVersion = hello.version(uuid);
        if (modVersion == null && requireHello) {
            refuse(player, "this client never sent " + EdgeModHello.CHANNEL + ", so it is not"
                    + " running the rollback mod. A MOD-hosted slot is simulated ON the client:"
                    + " without the mod the transfer would land the player in the limbo with"
                    + " nothing driving the sim. Install the mod, or re-run devAssign without"
                    + " -Pmod for this slot.");
            return;
        }
        if (modVersion != null && modVersion != Protocol.VERSION) {
            for (String line : VersionFence.report(
                    "[mod-handoff] refusing the MOD-hosted slot for " + player.getName()
                            + ": frame 0 and the checksum function differ between these two"
                            + " builds, so handing the match over would desync it rather than"
                            + " fail it.",
                    VersionFence.MOD_ARTIFACT + " on " + player.getName(), modVersion,
                    VersionFence.EDGE_ARTIFACT + " on this server", Protocol.VERSION)) {
                log.severe(line);
            }
            refuse(player, "this client's rollback mod is at " + VersionFence.triple(modVersion)
                    + " and this edge build is at " + VersionFence.local()
                    + ", so the two would not simulate the same match");
            return;
        }
        if (!assignment.hasArenaBlob()) {
            refuse(player, "the assignment ships no arena bytes. A modded client builds its"
                    + " collision from the bytes the server sends it, so with none it would"
                    + " fight on a bare plane while its opponent fought on a real arena."
                    + " Re-run devAssign with -ParenaName.");
            return;
        }

        byte[] setup = assignment.hasSetup()
                ? assignment.setup() : buildDevSetup(player, assignment);
        if (setup == null) {
            return;
        }

        String key = assignment.arenaKey();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[] blob = blobs == null ? null : blobs.fetch(key);
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> deliver(player, assignment, setup, blob));
        });
    }

    private byte[] buildDevSetup(Player player, EdgeAssignment assignment) {
        if (!assignment.devKit()) {
            refuse(player, "the assignment carries no setup bytes and did not ask for the dev kit,"
                    + " so there is no frame 0 to hand the mod. Drop -PnoDevKit.");
            return null;
        }
        try {
            for (String line : EdgeGameTypes.warningLines(assignment.gameType(),
                    EdgeDemoKits.materials(EdgeDevKit.kitFor(assignment)),
                    "mod handoff for " + assignment.playerName())) {
                log.warning("[mod-handoff] " + line);
            }
            byte[] setup = EdgeDevKit.encodeAddressed(assignment);
            log.warning("[mod-handoff] no setup bytes on the assignment - handing the mod the DEMO"
                    + " KIT " + EdgeDevKit.describe(assignment) + " for gameType '"
                    + (assignment.gameType().isEmpty() ? "?" : assignment.gameType()) + "', "
                    + setup.length + "B. The mod decodes it with the same"
                    + " MatchSetupFrame0Decoder a real mcleagues-core match uses, but the peer only"
                    + " builds the identical frame 0 if it is running this same build.");
            return setup;
        } catch (RuntimeException ex) {
            refuse(player, "the dev kit could not be encoded for the mod: " + ex);
            return null;
        }
    }

    private void deliver(Player player, EdgeAssignment assignment, byte[] setup, byte[] blob) {
        if (!player.isOnline()) {
            log.warning("[mod-handoff] " + assignment.playerName() + " left before the handoff"
                    + " could be delivered - session " + assignment.sessionId() + " is dead");
            return;
        }
        if (blob == null || blob.length != assignment.arenaBytes()) {
            refuse(player, "the arena bytes at " + assignment.arenaKey() + " are "
                    + (blob == null ? "missing" : blob.length + "B, not the " + assignment.arenaBytes()
                            + "B the assignment names")
                    + ". The peer reads the same key, so shipping the mod anything else is a"
                    + " guaranteed desync.");
            return;
        }
        String sha = EdgeArenaStore.sha256(blob);
        if (!sha.equalsIgnoreCase(assignment.arenaSha256())) {
            refuse(player, "the arena bytes at " + assignment.arenaKey() + " hash to " + sha
                    + " but the assignment says " + assignment.arenaSha256()
                    + " - refusing rather than handing the mod a different arena than the peer.");
            return;
        }

        int fragments = sendArena(player, blob);
        if (assignment.hasCage()) {
            send(player, CAGE_CHANNEL, assignment.cage());
        }
        send(player, SETUP_CHANNEL, setup);
        log.info("[mod-handoff] " + player.getName() + " (slot " + assignment.slot() + ") got "
                + fragments + " arena fragments (" + blob.length + "B), "
                + (assignment.hasCage() ? assignment.cage().length + "B of cage, " : "no cage, ")
                + setup.length + "B of setup - transferring to the limbo at " + limboHost + ":"
                + limboPort + " in " + transferDelayTicks + " ticks");
        player.sendMessage(Component.text("handing your client the match - transferring to the"
                + " local sim"));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            player.transfer(limboHost, limboPort);
        }, transferDelayTicks);
    }

    private int sendArena(Player player, byte[] gzip) {
        int total = Math.max(1, (gzip.length + fragmentBytes - 1) / fragmentBytes);
        for (int i = 0; i < total; i++) {
            int off = i * fragmentBytes;
            int len = Math.min(fragmentBytes, gzip.length - off);
            ByteBuffer buf = ByteBuffer.allocate(8 + len);
            buf.putInt(total);
            buf.putInt(i);
            buf.put(gzip, off, len);
            send(player, ARENA_CHANNEL, buf.array());
        }
        return total;
    }

    private void send(Player player, String channel, byte[] data) {
        PacketEvents.getAPI().getPlayerManager()
                .sendPacket(player, new WrapperPlayServerPluginMessage(channel, data));
    }

    private void refuse(Player player, String reason) {
        log.severe("[mod-handoff] refusing the MOD-hosted slot for " + player.getName() + ": "
                + reason);
        player.sendMessage(Component.text("match refused: this server could not hand your client"
                + " the match"));
    }
}
