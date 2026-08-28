package me.nootnoot.edge;

import com.destroystokyo.paper.profile.ProfileProperty;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import me.nootnoot.sim.ArenaAgreement;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.host.SpawnFacing;
import me.nootnoot.sim.host.ArenaAgreementExchange;
import me.nootnoot.sim.host.MatchDriver;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.Transport;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class EdgeMatch {

    public static final int RING_CAPACITY = 1024;

    private static final int IDENTITY_MAGIC = 0x4A454944;
    private static final byte IDENTITY_VERSION = 1;
    private static final int IDENTITY_INTERVAL_TICKS = 20;
    private static final int IDENTITY_MAX_SENDS = 300;
    private static final int IDENTITY_TAIL_SENDS = 3;
    private static final int IDENTITY_HEADER_BYTES = 4 + 1 + 8 + 8;
    private static final int IDENTITY_MAX_NAME_BYTES = 64;
    private static final int IDENTITY_MAX_SKIN_BYTES = 4096;
    private static final int IDENTITY_MAX_SIGNATURE_BYTES = 1024;
    private static final int STATS_INTERVAL_TICKS = 10;
    private static final String TEXTURES_KEY = "textures";

    private final Player player;
    private final int slot;
    private final MatchDriver driver;
    private final EdgeInputSource input;
    private final EdgeRenderer renderer;
    private final byte[] identity;
    private final EdgeTelemetry telemetry;
    private final ArenaAgreementExchange arenaExchange;

    private int identityTick;
    private int statsTick;
    private int identitySends;
    private int identityTailSends;
    private boolean peerIdentitySeen;

    public EdgeMatch(Player player, int slot, Transport transport, Arena arena, GameState initial,
                     EdgeTelemetry telemetry, EdgeMovementValidator.Limits movementLimits,
                     ArenaAgreement agreement, EdgeLoadout loadout, EdgeCageDisplay cage,
                     Plugin plugin, boolean pots, boolean totems, World world,
                     EdgeArenaPaster.Overlay worldOverlay) {
        this.player = player;
        this.slot = slot;
        this.input = new EdgeInputSource(player, slot, movementLimits, telemetry, loadout, arena,
                world);
        this.renderer = new EdgeRenderer(player, slot, input, loadout, cage, plugin, pots, totems,
                world, worldOverlay);
        this.driver = new MatchDriver(transport, slot, arena, initial, RING_CAPACITY, input, renderer,
                telemetry);
        this.input.attach(this.driver);
        this.input.attachPaint(this.renderer.inventory().plan(), this.renderer.container().plan());
        this.renderer.attachTelemetry(telemetry);
        this.identity = encodeIdentity(player);
        this.telemetry = telemetry;
        this.arenaExchange = new ArenaAgreementExchange(agreement);
    }

    public static GameState seed(double spawnX0, double spawnY0, double spawnZ0,
                                 double spawnX1, double spawnY1, double spawnZ1, int rounds) {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        PlayerState b = g.players[1];
        a.x = spawnX0;
        a.y = spawnY0;
        a.z = spawnZ0;
        a.yaw = SpawnFacing.yaw(spawnX0, spawnZ0, spawnX1, spawnZ1);
        a.health = 20f;
        a.maxHealth = 20f;
        b.x = spawnX1;
        b.y = spawnY1;
        b.z = spawnZ1;
        b.yaw = SpawnFacing.yaw(spawnX1, spawnZ1, spawnX0, spawnZ0);
        b.health = 20f;
        b.maxHealth = 20f;
        g.roundsTarget = rounds > 0 ? rounds : 1;
        g.roundInitial = new PlayerState[]{a.copy(), b.copy()};
        g.roundResetCountdown = Simulation.ROUND_COUNTDOWN_TICKS;
        return g;
    }

    public Player player() {
        return player;
    }

    public int slot() {
        return slot;
    }

    public EdgeInputSource input() {
        return input;
    }

    public EdgeRenderer renderer() {
        return renderer;
    }

    public MatchDriver driver() {
        return driver;
    }

    public void markServerTeleport() {
        input.markServerTeleport();
    }

    public boolean ownsEntity(int entityId) {
        return entityId >= 0 && renderer.opponentEntityId() == entityId;
    }

    public boolean tick() {
        pumpContainers();
        pumpIdentity();
        pumpStats();
        arenaExchange.pump(driver);
        if (arenaExchange.abortReason() != null) {
            return false;
        }
        return driver.tick();
    }

    public boolean aborted() {
        return arenaExchange.abortReason() != null || driver.aborted();
    }

    public String abortReason() {
        String arena = arenaExchange.abortReason();
        return arena != null ? arena : driver.abortReason();
    }

    public ArenaAgreement agreement() {
        return arenaExchange.local();
    }

    public void end() {
        driver.end();
    }

    private void pumpContainers() {
        for (byte[] blob : driver.pollContainer()) {
            try {
                if (arenaExchange.offer(blob)) {
                    continue;
                }
                EdgeMatchStats.Counts counts = EdgeMatchStats.decode(blob);
                if (counts != null) {
                    renderer.applyPeerCounts(counts);
                    continue;
                }
                applyIdentity(blob);
            } catch (RuntimeException refused) {
                telemetry.containerBlobRefused(refused);
            }
        }
    }

    private void pumpStats() {
        if (statsTick++ % STATS_INTERVAL_TICKS != 0) {
            return;
        }
        driver.sendContainer(EdgeMatchStats.encode(EdgeMatchStats.left(player)));
    }

    private void pumpIdentity() {
        if (identitySends >= IDENTITY_MAX_SENDS
                || (peerIdentitySeen && identityTailSends >= IDENTITY_TAIL_SENDS)) {
            return;
        }
        if (identityTick++ % IDENTITY_INTERVAL_TICKS != 0) {
            return;
        }
        driver.sendContainer(identity);
        identitySends++;
        if (peerIdentitySeen) {
            identityTailSends++;
        }
    }

    private void applyIdentity(byte[] blob) {
        if (blob == null || blob.length < IDENTITY_HEADER_BYTES
                || blob.length > Protocol.MAX_CONTAINER_BYTES) {
            return;
        }
        UUID uuid;
        String name;
        String skinValue;
        String skinSignature;
        try {
            ByteBuffer b = ByteBuffer.wrap(blob);
            if (b.getInt() != IDENTITY_MAGIC || b.get() != IDENTITY_VERSION) {
                return;
            }
            uuid = new UUID(b.getLong(), b.getLong());
            name = readString(b, IDENTITY_MAX_NAME_BYTES);
            skinValue = readString(b, IDENTITY_MAX_SKIN_BYTES);
            skinSignature = readString(b, IDENTITY_MAX_SIGNATURE_BYTES);
            if (b.hasRemaining()) {
                return;
            }
        } catch (BufferUnderflowException | IllegalArgumentException refused) {
            return;
        }
        peerIdentitySeen = true;
        renderer.applyOpponentIdentity(uuid, name, skinValue, skinSignature);
    }

    private static byte[] encodeIdentity(Player player) {
        String skinValue = null;
        String skinSignature = null;
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if (TEXTURES_KEY.equals(property.getName())) {
                skinValue = property.getValue();
                skinSignature = property.getSignature();
                break;
            }
        }
        byte[] blob = writeIdentity(player.getUniqueId(), player.getName(), skinValue, skinSignature);
        if (blob.length > Protocol.MAX_CONTAINER_BYTES) {
            blob = writeIdentity(player.getUniqueId(), player.getName(), null, null);
        }
        return blob;
    }

    private static byte[] writeIdentity(UUID uuid, String name, String skinValue,
                                        String skinSignature) {
        byte[] n = utf8(name);
        byte[] v = utf8(skinValue);
        byte[] s = utf8(skinSignature);
        ByteBuffer b = ByteBuffer.allocate(IDENTITY_HEADER_BYTES + 6 + n.length + v.length + s.length);
        b.putInt(IDENTITY_MAGIC);
        b.put(IDENTITY_VERSION);
        b.putLong(uuid.getMostSignificantBits());
        b.putLong(uuid.getLeastSignificantBits());
        b.putShort((short) n.length);
        b.put(n);
        b.putShort((short) v.length);
        b.put(v);
        b.putShort((short) s.length);
        b.put(s);
        return b.array();
    }

    private static String readString(ByteBuffer b, int max) {
        int len = b.getShort() & 0xFFFF;
        if (len > max) {
            throw new IllegalArgumentException("identity field of " + len + " bytes exceeds " + max);
        }
        if (len > b.remaining()) {
            throw new BufferUnderflowException();
        }
        if (len == 0) {
            return null;
        }
        byte[] data = new byte[len];
        b.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static byte[] utf8(String s) {
        if (s == null) {
            return new byte[0];
        }
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        return data.length > 0xFFFF ? new byte[0] : data;
    }
}
