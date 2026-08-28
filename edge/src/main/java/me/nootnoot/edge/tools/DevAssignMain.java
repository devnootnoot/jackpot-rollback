package me.nootnoot.edge.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import me.nootnoot.edge.EdgeAssignment;
import me.nootnoot.edge.EdgeDevKit;
import me.nootnoot.edge.EdgeGameTypes;
import me.nootnoot.sim.host.SpawnFacing;
import me.nootnoot.sim.net.SlotTokens;
import me.nootnoot.sim.tools.FarenaArena;
import redis.clients.jedis.Jedis;
import me.nootnoot.sim.ArenaCodec;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.tools.SpawnSupport;
import me.nootnoot.sim.net.LaggedTransport;

public final class DevAssignMain {

    public static final double CAGE_DROP_HEIGHT = 15.0;
    public static final String ARENA_KEY_PREFIX = "rollback:edge:arena:";
    public static final int ARENA_TTL_SECONDS = 1800;

    private static final double DEV_X0 = -4.0;
    private static final double DEV_Z0 = 0.0;
    private static final double DEV_X1 = 4.0;
    private static final double DEV_Z1 = 0.0;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private DevAssignMain() {
    }

    public record ArenaRef(String name, double groundY, String key, String sha256, int bytes,
                           double[] spawn0, double[] spawn1) {

        public boolean hasBlob() {
            return key != null && !key.isEmpty() && bytes > 0;
        }

        public static ArenaRef dev(double groundY) {
            double y = groundY + CAGE_DROP_HEIGHT;
            return new ArenaRef("dev", groundY, "", "", 0,
                    new double[]{DEV_X0, y, DEV_Z0,
                            SpawnFacing.yaw(DEV_X0, DEV_Z0, DEV_X1, DEV_Z1), 0.0},
                    new double[]{DEV_X1, y, DEV_Z1,
                            SpawnFacing.yaw(DEV_X1, DEV_Z1, DEV_X0, DEV_Z0), 0.0});
        }
    }

    public static void main(String[] args) {
        String nameA = prop("playerA", null);
        String nameB = prop("playerB", null);
        if (nameA == null || nameB == null) {
            System.out.println("usage: gradlew devAssign -PplayerA=<name> -PplayerB=<name>"
                    + " [-PgameType=<type>] [-ParenaName=<arena>] [-Pmod=<who>] [-PnoDevKit]");
            System.out.println("  -PgameType picks the demo kit both edges build AND the rule set"
                    + " they run; one of " + EdgeGameTypes.IDS + " (default "
                    + EdgeGameTypes.CRYSTAL + ", the only one with every mechanic on)");
            System.out.println("  -ParenaName loads a REAL .farena from the practice arena"
                    + " directory, publishes it to the dev redis and points both slots at it");
            System.out.println("  -Pmod picks which slots host the sim on their own MODDED client:"
                    + " none (default), A, B or both. A mod-hosted slot is handed off to the limbo"
                    + " instead of being hosted by its edge, and REQUIRES -ParenaName.");
            listArenas();
            System.exit(2);
        }
        String edgeA = prop("edgeA", "edge-a");
        String edgeB = prop("edgeB", "edge-b");
        String redisHost = prop("redisHost", "127.0.0.1");
        int redisPort = Integer.parseInt(prop("redisPort", "6380"));
        String relayHost = prop("relayHost", "127.0.0.1");
        int relayPort = Integer.parseInt(prop("relayPort", "7777"));
        double groundY = Double.parseDouble(prop("groundY", "-60.0"));
        int rounds = Integer.parseInt(prop("rounds", "1"));
        boolean devKit = Boolean.parseBoolean(prop("devKit", "true"));
        int lagA = lagProp("lagA");
        int lagB = lagProp("lagB");
        String requested = prop("gameType", EdgeGameTypes.CRYSTAL);
        String gameType = EdgeGameTypes.canonical(requested);
        String arenaName = prop("arenaName", null);
        String modWho = prop("mod", "none");
        String hostKind0 = hostKindOf(modWho, 0);
        String hostKind1 = hostKindOf(modWho, 1);
        if (hostKind0 == null) {
            System.out.println("-Pmod=" + modWho + " is not one of none, A, B, both");
            System.exit(2);
        }

        ArenaRef arena = ArenaRef.dev(groundY);
        byte[] blob = null;
        if (arenaName != null) {
            FarenaArena.Loaded loaded = loadArena(arenaName);
            if (loaded == null) {
                System.exit(2);
            }
            blob = loaded.blob();
            arena = refOf(loaded);
        }
        boolean anyMod = EdgeAssignment.HOST_MOD.equals(hostKind0)
                || EdgeAssignment.HOST_MOD.equals(hostKind1);
        if (anyMod && !arena.hasBlob()) {
            System.out.println("REFUSING: -Pmod=" + modWho + " asks for a MOD-hosted slot, but no");
            System.out.println("arena bytes are being shipped. A modded client builds its collision");
            System.out.println("from the bytes the server sends it and has no local arena file to");
            System.out.println("fall back on, so the mod would fight on a bare plane while the edge");
            System.out.println("fought on arena.bin - a guaranteed desync. Re-run with -ParenaName.");
            listArenas();
            System.exit(2);
        }

        long sessionId = new SecureRandom().nextLong();
        String slotSecret = slotSecret();
        byte[] token0 = slotTokenFor(slotSecret, sessionId, 0);
        byte[] token1 = slotTokenFor(slotSecret, sessionId, 1);
        System.out.println(slotSecret == null
                ? "no " + SLOT_SECRET_ENV + " and no -PrelaySlotSecret: per-slot tokens are RANDOM."
                + " That only binds a relay running trust-on-first-use (the loopback dev shape)."
                + " A relay started with " + SLOT_SECRET_ENV + " verifies every HELLO against"
                + " HMAC-SHA256(secret, \"" + SlotTokens.LABEL + "\" || sessionId || slot) and"
                + " would drop both peers of this session with no log line but a rising"
                + " rollback_relay_unauthorized_total."
                : "per-slot tokens DERIVED from " + SLOT_SECRET_ENV + " for session " + sessionId
                + " - the same rule mcleagues-core's RollbackSlotTokens uses, so this assignment"
                + " binds a relay started with the same secret");
        UUID uuidA = offlineUuid(nameA);
        UUID uuidB = offlineUuid(nameB);
        long expires = System.currentTimeMillis() + 120_000L;

        String directA = prop("directA", null);
        String directB = prop("directB", null);
        String linkToken = directA == null || directB == null ? null
                : Base64.getEncoder().encodeToString(randomToken());
        String jsonA = assignment(sessionId, 0, uuidA, nameA, uuidB, nameB, relayHost, relayPort,
                rounds, expires, devKit, gameType, arena,
                hostOf(directB), directPortOf(directB), linkToken, hostKind0, hostKind1, token0,
                lagA);
        String jsonB = assignment(sessionId, 1, uuidB, nameB, uuidA, nameA, relayHost, relayPort,
                rounds, expires, devKit, gameType, arena,
                hostOf(directA), directPortOf(directA), linkToken, hostKind1, hostKind0, token1,
                lagB);
        if (linkToken != null) {
            System.out.println("direct edge link: slot 0 dials " + directB + ", slot 1 dials "
                    + directA + " - the relay is not in the path for this session");
        }

        try (Jedis jedis = new Jedis(redisHost, redisPort)) {
            if (blob != null) {
                jedis.setex(arena.key().getBytes(StandardCharsets.UTF_8), ARENA_TTL_SECONDS, blob);
                System.out.println("published " + blob.length + "B of arena bytes to "
                        + arena.key() + " (ttl " + ARENA_TTL_SECONDS + "s)");
            }
            jedis.rpush("rollback:edge:assign:" + edgeA, jsonA);
            jedis.rpush("rollback:edge:assign:" + edgeB, jsonB);
        }

        System.out.println("pushed session " + sessionId
                + (devKit ? "  (demo kit requested on BOTH slots)"
                : "  (NO KIT - both players start empty-handed)"));
        explainReferee(sessionId, devKit);
        if (!requested.equalsIgnoreCase(gameType)) {
            System.out.println("gameType '" + requested + "' resolved to " + gameType
                    + "  (known: " + EdgeGameTypes.IDS + ")");
        }
        System.out.println("gameType " + gameType + "  ->  " + EdgeGameTypes.summary(gameType));
        warnDisabledMechanics(gameType);
        System.out.println("devAssign cannot carry a real per-player kit: this tool has no server to");
        System.out.println("serialize inventories with, so it never sets the assignment's setup bytes.");
        System.out.println("Each edge builds the DEMO kit for this gameType instead, through the same");
        System.out.println("setup-byte encoder and the same EdgeLoadout decoder a real match uses, so");
        System.out.println("it only matches if BOTH edges run this same build. Real kits come from");
        System.out.println("mcleagues-core (MatchSetupCodec -> EdgeSessionBroker.assign(setup)) only.");
        if (arena.hasBlob()) {
            System.out.println("arena '" + arena.name() + "' is REAL: both slots read the same redis");
            System.out.println("key, so both edges decode byte-identical bytes and the arena hash");
            System.out.println("handshake passes. The palette geometry is a DEV HEURISTIC built from");
            System.out.println("the block-state strings, not the server's VoxelShape table - it is");
            System.out.println("identical on both edges but it is NOT what mcleagues-core would ship.");
        } else {
            System.out.println("devAssign carried NO arena bytes, so both edges fall back to their");
            System.out.println("local arena.bin (gradlew devSampleArena) or the flat arena. Pass");
            System.out.println("-ParenaName=colosseum to run a real one instead.");
        }
        System.out.println("  slot 0  " + nameA + "  must be connected to " + edgeA
                + "  (127.0.0.1:" + portOf(edgeA) + ")  hosted by " + hostKind0);
        System.out.println("  slot 1  " + nameB + "  must be connected to " + edgeB
                + "  (127.0.0.1:" + portOf(edgeB) + ")  hosted by " + hostKind1);
        System.out.println();
        System.out.println("If a player is on the OTHER port, that edge will log a warning and nothing");
        System.out.println("will start - re-run with the two names swapped.");
        if (anyMod) {
            System.out.println();
            System.out.println("A MOD slot is NOT hosted by its edge. That edge sends the client the");
            System.out.println("match_setup, arena and cage plugin messages and then transfers it to");
            System.out.println("the limbo, exactly like RollbackHandoffManager does on the network.");
            System.out.println("The limbo must be running (gradlew devLimbo) and the client must have");
            System.out.println("the rollback mod installed, or the transfer lands on nothing.");
        }
    }

    private static String controlSecret() {
        String configured = prop("relayControlSecret", null);
        if (configured == null) {
            configured = System.getenv(DevRefereeAuthorizer.CONTROL_SECRET_ENV);
        }
        if (configured == null) {
            configured = DevRefereeAuthorizer.DEV_CONTROL_SECRET;
        }
        return configured.isBlank() ? null : configured.trim();
    }

    private static void explainReferee(long sessionId, boolean devKit) {
        if (!devKit) {
            System.out.println("#### NO REFEREE FOR SESSION " + sessionId + " #### -PnoDevKit");
            System.out.println("leaves the assignment with no frame 0 for anyone to hand the relay,");
            System.out.println("so there is nothing for a referee to re-simulate against.");
            return;
        }
        System.out.println("referee: this tool no longer authorizes the session itself. It has no");
        System.out.println("server to build frame 0 with, so the EDGE that picks this assignment up");
        System.out.println("authorizes it, using the very bytes it hands the host - the same array,");
        System.out.println("never a second encoding that could drift. Both edges try; the relay");
        System.out.println("keeps the first and refuses to reset a referee that has taken frames.");
        System.out.println("Watch the edge log for  [referee] session " + sessionId + " authorized");
        System.out.println("and read the signed verdict in the devRun window as");
        System.out.println("  [relay] referee verdict: session " + sessionId + " - ...");
        if (prop("noReferee", null) != null) {
            System.out.println("-PnoReferee does NOT apply here any more: the edges decide. Start");
            System.out.println("them without a referee with  gradlew devRun -PnoReferee  instead.");
        }
    }

    private static void warnUnsupportedSpawns(FarenaArena.Loaded loaded) {
        Arena arena;
        try {
            arena = ArenaCodec.toArena(ArenaCodec.decode(loaded.blob()));
        } catch (RuntimeException undecodable) {
            return;
        }
        SpawnSupport.Report r0 = SpawnSupport.of(arena, loaded.spawn0().x(), loaded.spawn0().y(),
                loaded.spawn0().z(), "spawn0");
        SpawnSupport.Report r1 = SpawnSupport.of(arena, loaded.spawn1().x(), loaded.spawn1().y(),
                loaded.spawn1().z(), "spawn1");
        if (r0.supported() && r1.supported()) {
            return;
        }
        System.out.println("  ################################################################");
        System.out.println("  ##  THIS ARENA'S SPAWN POINTS ARE NOT ON ITS FLOOR");
        System.out.println("  ##  " + r0.describe());
        System.out.println("  ##  " + r1.describe());
        System.out.println("  ################################################################");
    }

    private static FarenaArena.Loaded loadArena(String arenaName) {
        Path directory = arenaDirectory();
        String name = arenaName.toLowerCase(Locale.ROOT);
        Path file = FarenaArena.resolve(directory, name);
        if (!Files.isRegularFile(file)) {
            System.out.println("no such arena: " + file.toAbsolutePath());
            listArenas();
            return null;
        }
        int playRadius = Integer.parseInt(prop("playRadius",
                String.valueOf(FarenaArena.DEFAULT_PLAY_RADIUS)));
        int maxBlocks = Integer.parseInt(prop("arenaMaxBlocks",
                String.valueOf(FarenaArena.DEFAULT_MAX_BLOCKS)));
        try {
            FarenaArena.Loaded loaded = FarenaArena.load(file, name, playRadius, maxBlocks);
            System.out.println("loaded " + file.toAbsolutePath());
            System.out.println("  " + loaded.describe());
            warnUnsupportedSpawns(loaded);
            System.out.println("  play radius " + playRadius + ", geometry "
                    + loaded.geometry());
            return loaded;
        } catch (IOException | RuntimeException ex) {
            System.out.println("could not read " + file.toAbsolutePath() + ": " + ex);
            return null;
        }
    }

    private static ArenaRef refOf(FarenaArena.Loaded loaded) {
        String sha = sha256(loaded.blob());
        FarenaArena.Spawn s0 = loaded.spawn0();
        FarenaArena.Spawn s1 = loaded.spawn1();
        return new ArenaRef(loaded.name(), loaded.groundY(), ARENA_KEY_PREFIX + sha, sha,
                loaded.blob().length,
                new double[]{s0.x(), s0.y() + CAGE_DROP_HEIGHT, s0.z(), s0.yaw(), s0.pitch()},
                new double[]{s1.x(), s1.y() + CAGE_DROP_HEIGHT, s1.z(), s1.yaw(), s1.pitch()});
    }

    private static Path arenaDirectory() {
        String configured = prop("arenaDir", null);
        return Path.of(configured == null ? "." : configured);
    }

    private static void listArenas() {
        Path directory = arenaDirectory();
        try {
            List<String> names = FarenaArena.names(directory);
            if (names.isEmpty()) {
                System.out.println("no .farena files under " + directory.toAbsolutePath()
                        + " - point -ParenaDir at the practice arenas folder");
                return;
            }
            System.out.println("arenas available in " + directory.toAbsolutePath() + ": "
                    + String.join(", ", names));
        } catch (IOException ex) {
            System.out.println("could not list " + directory.toAbsolutePath() + ": " + ex);
        }
    }

    public static final String SLOT_SECRET_ENV = "RELAY_SLOT_SECRET";

    public static String slotSecret() {
        String configured = prop("relaySlotSecret", null);
        if (configured == null) {
            configured = System.getenv(SLOT_SECRET_ENV);
        }
        return configured == null || configured.isBlank() ? null : configured.trim();
    }

    public static byte[] slotTokenFor(String secret, long sessionId, int slot) {
        if (secret == null || secret.isBlank()) {
            return randomToken();
        }
        return SlotTokens.derive(secret.trim().getBytes(StandardCharsets.UTF_8), sessionId, slot);
    }

    public static String hostKindOf(String modWho, int slot) {
        String who = modWho == null ? "none" : modWho.trim().toLowerCase(Locale.ROOT);
        return switch (who) {
            case "", "none", "no", "false", "edge" -> EdgeAssignment.HOST_EDGE;
            case "a", "0", "slot0" -> slot == 0 ? EdgeAssignment.HOST_MOD : EdgeAssignment.HOST_EDGE;
            case "b", "1", "slot1" -> slot == 1 ? EdgeAssignment.HOST_MOD : EdgeAssignment.HOST_EDGE;
            case "both", "all", "mod", "true" -> EdgeAssignment.HOST_MOD;
            default -> null;
        };
    }

    private static void warnDisabledMechanics(String gameType) {
        List<String> lines = EdgeGameTypes.warningLines(gameType, null,
                "devAssign -PgameType=" + (gameType == null || gameType.isBlank() ? "?" : gameType));
        if (lines.isEmpty()) {
            return;
        }
        System.out.println();
        for (String line : lines) {
            System.out.println(line);
        }
        System.out.println("## Re-run with -PgameType=" + EdgeGameTypes.CRYSTAL
                + " to exercise all of them.");
        System.out.println();
    }

    private static int portOf(String edge) {
        return "edge-a".equals(edge) ? 25566 : "edge-b".equals(edge) ? 25567 : 0;
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static int lagProp(String key) {
        String raw = prop(key, null);
        if (raw == null) {
            return 0;
        }
        try {
            int ms = LaggedTransport.clamp(Integer.parseInt(raw.trim()));
            System.out.println("-P" + key + "=" + raw.trim() + ": that slot's edge will hold every"
                    + " packet " + ms + "ms in EACH direction, so +" + (ms * 2) + "ms round trip on"
                    + " top of the real link. The player can read it back in game with /ping.");
            return ms;
        } catch (NumberFormatException notANumber) {
            System.out.println("-P" + key + "=" + raw + " is not a number of milliseconds - ignored");
            return 0;
        }
    }

    private static String prop(String key, String def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : v;
    }

    public static String sha256(byte[] blob) {
        try {
            byte[] out = MessageDigest.getInstance("SHA-256").digest(blob);
            char[] hex = new char[out.length * 2];
            for (int i = 0; i < out.length; i++) {
                hex[i * 2] = HEX[(out[i] >> 4) & 0xF];
                hex[i * 2 + 1] = HEX[out[i] & 0xF];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static byte[] randomToken() {
        byte[] t = new byte[32];
        new SecureRandom().nextBytes(t);
        return t;
    }

    private static String hostOf(String hostPort) {
        if (hostPort == null) {
            return null;
        }
        int colon = hostPort.lastIndexOf(':');
        return colon <= 0 ? null : hostPort.substring(0, colon);
    }

    private static int directPortOf(String hostPort) {
        if (hostPort == null) {
            return 0;
        }
        int colon = hostPort.lastIndexOf(':');
        try {
            return colon <= 0 ? 0 : Integer.parseInt(hostPort.substring(colon + 1));
        } catch (NumberFormatException notAPort) {
            return 0;
        }
    }

    public static String assignment(long sessionId, int slot, UUID self, String selfName,
                                     UUID opp, String oppName, String relayHost, int relayPort,
                                     double groundY, int rounds, long expires, boolean devKit) {
        return assignment(sessionId, slot, self, selfName, opp, oppName, relayHost, relayPort,
                groundY, rounds, expires, devKit, EdgeGameTypes.IRON);
    }

    public static String assignment(long sessionId, int slot, UUID self, String selfName,
                                     UUID opp, String oppName, String relayHost, int relayPort,
                                     double groundY, int rounds, long expires, boolean devKit,
                                     String gameType) {
        return assignment(sessionId, slot, self, selfName, opp, oppName, relayHost, relayPort,
                rounds, expires, devKit, gameType, ArenaRef.dev(groundY));
    }

    public static String assignment(long sessionId, int slot, UUID self, String selfName,
                                     UUID opp, String oppName, String relayHost, int relayPort,
                                     int rounds, long expires, boolean devKit, String gameType,
                                     ArenaRef arena) {
        return assignment(sessionId, slot, self, selfName, opp, oppName, relayHost, relayPort,
                rounds, expires, devKit, gameType, arena, null, 0, null);
    }

    public static String assignment(long sessionId, int slot, UUID self, String selfName,
                                     UUID opp, String oppName, String relayHost, int relayPort,
                                     int rounds, long expires, boolean devKit, String gameType,
                                     ArenaRef arena, String peerHost, int peerPort,
                                     String linkToken64) {
        return assignment(sessionId, slot, self, selfName, opp, oppName, relayHost, relayPort,
                rounds, expires, devKit, gameType, arena, peerHost, peerPort, linkToken64,
                EdgeAssignment.HOST_EDGE, EdgeAssignment.HOST_EDGE);
    }

    public static String assignment(long sessionId, int slot, UUID self, String selfName,
                                     UUID opp, String oppName, String relayHost, int relayPort,
                                     int rounds, long expires, boolean devKit, String gameType,
                                     ArenaRef arena, String peerHost, int peerPort,
                                     String linkToken64, String selfHostKind,
                                     String oppHostKind) {
        return assignment(sessionId, slot, self, selfName, opp, oppName, relayHost, relayPort,
                rounds, expires, devKit, gameType, arena, peerHost, peerPort, linkToken64,
                selfHostKind, oppHostKind, randomToken(), 0);
    }

    public static String assignment(long sessionId, int slot, UUID self, String selfName,
                                     UUID opp, String oppName, String relayHost, int relayPort,
                                     int rounds, long expires, boolean devKit, String gameType,
                                     ArenaRef arena, String peerHost, int peerPort,
                                     String linkToken64, String selfHostKind,
                                     String oppHostKind, byte[] slotToken, int lagMs) {
        byte[] token = slotToken != null && slotToken.length > 0 ? slotToken : randomToken();
        String type = EdgeGameTypes.canonical(gameType);
        double[] s0 = arena.spawn0();
        double[] s1 = arena.spawn1();
        StringBuilder arenaJson = new StringBuilder();
        arenaJson.append("{\"name\":\"").append(arena.name()).append("\",\"groundY\":")
                .append(arena.groundY());
        if (arena.hasBlob()) {
            arenaJson.append(",\"key\":\"").append(arena.key()).append("\"")
                    .append(",\"sha256\":\"").append(arena.sha256()).append("\"")
                    .append(",\"bytes\":").append(arena.bytes());
        }
        arenaJson.append("}");
        String peerJson = peerHost == null || peerHost.isEmpty() || peerPort <= 0
                || linkToken64 == null || linkToken64.isEmpty()
                ? ""
                : "\"peer\":{\"host\":\"" + peerHost + "\",\"port\":" + peerPort
                        + ",\"token\":\"" + linkToken64 + "\"},";
        return "{"
                + "\"v\":1,"
                + "\"sessionId\":" + sessionId + ","
                + "\"slot\":" + slot + ","
                + "\"token\":\"" + Base64.getEncoder().encodeToString(token) + "\","
                + "\"relay\":{\"host\":\"" + relayHost + "\",\"port\":" + relayPort + "},"
                + peerJson
                + "\"player\":{\"uuid\":\"" + self + "\",\"name\":\"" + selfName + "\"},"
                + "\"opponent\":{\"uuid\":\"" + opp + "\",\"name\":\"" + oppName + "\"},"
                + "\"hostKind\":\"" + selfHostKind + "\",\"opponentHostKind\":\""
                + oppHostKind + "\","
                + "\"arena\":" + arenaJson + ","
                + "\"spawns\":{\"x0\":" + s0[0] + ",\"y0\":" + s0[1] + ",\"z0\":" + s0[2] + ","
                + "\"x1\":" + s1[0] + ",\"y1\":" + s1[1] + ",\"z1\":" + s1[2] + ","
                + "\"yaw0\":" + SpawnFacing.yaw(s0[0], s0[2], s1[0], s1[2])
                + ",\"pitch0\":" + s0[4] + ","
                + "\"yaw1\":" + SpawnFacing.yaw(s1[0], s1[2], s0[0], s0[2])
                + ",\"pitch1\":" + s1[4] + "},"
                + "\"rounds\":" + rounds + ","
                + "\"devKit\":" + devKit + ","
                + "\"lagMs\":" + lagMs + ","
                + "\"mode\":{\"gameType\":\"" + type + "\",\"pots\":" + EdgeGameTypes.pots(type)
                + ",\"totems\":" + EdgeGameTypes.totems(type) + "},"
                + "\"expiresAtMs\":" + expires
                + "}";
    }
}
