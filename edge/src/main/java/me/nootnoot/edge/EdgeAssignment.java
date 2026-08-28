package me.nootnoot.edge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Base64;
import java.util.UUID;
import me.nootnoot.sim.host.SpawnFacing;
import me.nootnoot.sim.net.DirectLink;
import me.nootnoot.sim.net.LaggedTransport;

public final class EdgeAssignment {

    public static final String HOST_EDGE = "EDGE";
    public static final String HOST_MOD = "MOD";

    private final int version;
    private final long sessionId;
    private final int slot;
    private final byte[] token;
    private final String relayHost;
    private final int relayPort;
    private final String peerHost;
    private final int peerPort;
    private final byte[] linkToken;
    private final UUID playerUuid;
    private final String playerName;
    private final UUID opponentUuid;
    private final String opponentName;
    private final String hostKind;
    private final String opponentHostKind;
    private final String arenaName;
    private final long arenaHash;
    private final String arenaKey;
    private final String arenaSha256;
    private final int arenaBytes;
    private final double groundY;
    private final double x0;
    private final double y0;
    private final double z0;
    private final double x1;
    private final double y1;
    private final double z1;
    private final float yaw0;
    private final float pitch0;
    private final float yaw1;
    private final float pitch1;
    private final int rounds;
    private final byte[] setup;
    private final byte[] cage;
    private final boolean devKit;
    private final int lagMs;
    private final String gameType;
    private final boolean pots;
    private final boolean totems;
    private final long expiresAtMs;
    private final long issuedAtMs;

    private EdgeAssignment(JsonObject o) {
        this.version = o.get("v").getAsInt();
        this.sessionId = o.get("sessionId").getAsLong();
        this.slot = o.get("slot").getAsInt();
        this.token = Base64.getDecoder().decode(o.get("token").getAsString());
        JsonObject relay = o.getAsJsonObject("relay");
        this.relayHost = relay.get("host").getAsString();
        this.relayPort = relay.get("port").getAsInt();
        JsonObject peer = o.has("peer") && o.get("peer").isJsonObject()
                ? o.getAsJsonObject("peer") : null;
        this.peerHost = peer != null && peer.has("host") ? peer.get("host").getAsString() : "";
        this.peerPort = peer != null && peer.has("port") ? peer.get("port").getAsInt() : 0;
        this.linkToken = peer != null && peer.has("token")
                ? Base64.getDecoder().decode(peer.get("token").getAsString()) : new byte[0];
        JsonObject me = o.getAsJsonObject("player");
        this.playerUuid = UUID.fromString(me.get("uuid").getAsString());
        this.playerName = me.get("name").getAsString();
        JsonObject opp = o.getAsJsonObject("opponent");
        this.opponentUuid = UUID.fromString(opp.get("uuid").getAsString());
        this.opponentName = opp.get("name").getAsString();
        this.hostKind = o.has("hostKind") ? o.get("hostKind").getAsString() : HOST_EDGE;
        this.opponentHostKind = o.has("opponentHostKind")
                ? o.get("opponentHostKind").getAsString() : HOST_EDGE;
        JsonObject arena = o.getAsJsonObject("arena");
        this.arenaName = arena.has("name") ? arena.get("name").getAsString() : "";
        this.arenaHash = arena.has("hash") ? Long.parseUnsignedLong(arena.get("hash").getAsString(), 16) : 0L;
        this.arenaKey = arena.has("key") ? arena.get("key").getAsString() : "";
        this.arenaSha256 = arena.has("sha256") ? arena.get("sha256").getAsString() : "";
        this.arenaBytes = arena.has("bytes") ? arena.get("bytes").getAsInt() : 0;
        this.groundY = arena.get("groundY").getAsDouble();
        JsonObject sp = o.getAsJsonObject("spawns");
        this.x0 = sp.get("x0").getAsDouble();
        this.y0 = sp.get("y0").getAsDouble();
        this.z0 = sp.get("z0").getAsDouble();
        this.x1 = sp.get("x1").getAsDouble();
        this.y1 = sp.get("y1").getAsDouble();
        this.z1 = sp.get("z1").getAsDouble();
        this.yaw0 = sp.has("yaw0") ? sp.get("yaw0").getAsFloat()
                : SpawnFacing.yaw(x0, z0, x1, z1);
        this.pitch0 = sp.has("pitch0") ? sp.get("pitch0").getAsFloat() : 0f;
        this.yaw1 = sp.has("yaw1") ? sp.get("yaw1").getAsFloat()
                : SpawnFacing.yaw(x1, z1, x0, z0);
        this.pitch1 = sp.has("pitch1") ? sp.get("pitch1").getAsFloat() : 0f;
        this.rounds = o.has("rounds") ? o.get("rounds").getAsInt() : 1;
        this.setup = o.has("setup") && !o.get("setup").isJsonNull()
                ? Base64.getDecoder().decode(o.get("setup").getAsString()) : null;
        this.cage = o.has("cage") && !o.get("cage").isJsonNull()
                ? Base64.getDecoder().decode(o.get("cage").getAsString()) : null;
        this.devKit = o.has("devKit") && !o.get("devKit").isJsonNull()
                && o.get("devKit").getAsBoolean();
        this.lagMs = o.has("lagMs") && !o.get("lagMs").isJsonNull()
                ? LaggedTransport.clamp(o.get("lagMs").getAsInt()) : 0;
        JsonObject mode = o.has("mode") && o.get("mode").isJsonObject()
                ? o.getAsJsonObject("mode") : null;
        this.gameType = mode != null && mode.has("gameType")
                ? mode.get("gameType").getAsString() : "";
        this.pots = mode != null && mode.has("pots") && mode.get("pots").getAsBoolean();
        this.totems = mode != null && mode.has("totems") && mode.get("totems").getAsBoolean();
        this.expiresAtMs = o.has("expiresAtMs") ? o.get("expiresAtMs").getAsLong() : 0L;
        this.issuedAtMs = o.has("issuedAtMs") ? o.get("issuedAtMs").getAsLong() : 0L;
    }

    public static EdgeAssignment parse(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            EdgeAssignment a = new EdgeAssignment(o);
            return a.version == 1 ? a : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public long sessionId() {
        return sessionId;
    }

    public int slot() {
        return slot;
    }

    public byte[] token() {
        return token.clone();
    }

    public String relayHost() {
        return relayHost;
    }

    public int relayPort() {
        return relayPort;
    }

    public String peerHost() {
        return peerHost;
    }

    public int peerPort() {
        return peerPort;
    }

    public byte[] linkToken() {
        return linkToken.clone();
    }

    public boolean hasPeerEndpoint() {
        return !peerHost.isEmpty() && peerPort > 0
                && linkToken.length >= DirectLink.MIN_LINK_SECRET_BYTES;
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public String playerName() {
        return playerName;
    }

    public UUID opponentUuid() {
        return opponentUuid;
    }

    public String opponentName() {
        return opponentName;
    }

    public String hostKind() {
        return hostKind;
    }

    public String opponentHostKind() {
        return opponentHostKind;
    }

    public boolean opponentIsModded() {
        return HOST_MOD.equals(opponentHostKind);
    }

    public boolean selfIsModded() {
        return HOST_MOD.equals(hostKind);
    }

    public String arenaName() {
        return arenaName;
    }

    public long arenaHash() {
        return arenaHash;
    }

    public String arenaKey() {
        return arenaKey;
    }

    public String arenaSha256() {
        return arenaSha256;
    }

    public int arenaBytes() {
        return arenaBytes;
    }

    public boolean hasArenaBlob() {
        return !arenaKey.isEmpty() && !arenaSha256.isEmpty() && arenaBytes > 0;
    }

    public double groundY() {
        return groundY;
    }

    public double spawnX(int forSlot) {
        return forSlot == 0 ? x0 : x1;
    }

    public double spawnY(int forSlot) {
        return forSlot == 0 ? y0 : y1;
    }

    public double spawnZ(int forSlot) {
        return forSlot == 0 ? z0 : z1;
    }

    public float spawnYaw(int forSlot) {
        return forSlot == 0 ? yaw0 : yaw1;
    }

    public float spawnPitch(int forSlot) {
        return forSlot == 0 ? pitch0 : pitch1;
    }

    public int rounds() {
        return rounds;
    }

    public boolean hasSetup() {
        return setup != null && setup.length > 0;
    }

    public byte[] setup() {
        return setup == null ? null : setup.clone();
    }

    public boolean hasCage() {
        return cage != null && cage.length > 0;
    }

    public byte[] cage() {
        return cage == null ? null : cage.clone();
    }

    public boolean devKit() {
        return devKit;
    }

    public int lagMs() {
        return lagMs;
    }

    public String gameType() {
        return gameType;
    }

    public boolean pots() {
        return pots;
    }

    public boolean totems() {
        return totems;
    }

    public long issuedAtMs() {
        return issuedAtMs;
    }

    public boolean expired(long nowMs) {
        return expiresAtMs > 0L && nowMs > expiresAtMs;
    }

    public String describe() {
        return "session=" + sessionId + " slot=" + slot
                + " player=" + playerName + "(" + hostKind + ")"
                + " opponent=" + opponentName + "(" + opponentHostKind + ")"
                + " link=" + (hasPeerEndpoint() ? "direct " + peerHost + ":" + peerPort
                        : "relay " + relayHost + ":" + relayPort)
                + " arena=" + arenaName
                + (hasArenaBlob() ? " (" + arenaBytes + "B at " + arenaKey + ")" : " (no bytes)")
                + " rounds=" + rounds
                + " mode=" + (gameType.isEmpty() ? "?" : gameType)
                + (pots ? " pots" : "") + (totems ? " totems" : "")
                + " setup=" + (hasSetup() ? setup.length + "B" : devKit ? "none (dev-kit requested)" : "none")
                + " cage=" + (hasCage() ? cage.length + "B" : "none");
    }
}
