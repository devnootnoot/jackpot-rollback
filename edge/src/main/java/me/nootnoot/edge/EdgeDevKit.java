package me.nootnoot.edge;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import me.nootnoot.sim.MatchSetupFrame0Decoder;
import me.nootnoot.sim.MatchSetupFrame0Encoder;
import me.nootnoot.sim.contract.MatchRules;
import me.nootnoot.sim.state.GameState;
import org.bukkit.inventory.ItemStack;

public final class EdgeDevKit {

    private static final int SLOTS = MatchSetupFrame0Decoder.INVENTORY_SLOTS;
    private static final int HOTBAR_SLOTS = EdgeLoadout.HOTBAR_SLOTS;

    private static final double PLAY_RADIUS = 200.0;

    private static final byte[] NO_ITEM = new byte[0];

    private EdgeDevKit() {
    }

    public static EdgeDemoKits.Kit kitFor(EdgeAssignment assignment) {
        return EdgeDemoKits.forGameType(assignment.gameType());
    }

    public static byte[] encode(EdgeAssignment assignment) {
        return encode(assignment, EdgeDemoKits.forGameType(assignment.gameType()));
    }

    public static byte[] encode(EdgeAssignment assignment, EdgeDemoKits.Kit kit) {
        return encode(assignment, kit, 0L, 0, NO_ITEM, "", 0);
    }

    public static byte[] encodeAddressed(EdgeAssignment assignment) {
        return encode(assignment, EdgeDemoKits.forGameType(assignment.gameType()),
                assignment.sessionId(), assignment.slot(), assignment.token(),
                assignment.relayHost(), assignment.relayPort());
    }

    public static byte[] encode(EdgeAssignment assignment, EdgeDemoKits.Kit kit,
                                long sessionId, int headerSlot, byte[] slotToken,
                                String relayHost, int relayPort) {
        GameState tables = EdgeKitTables.build(kit, edgeHosted(assignment, 0),
                edgeHosted(assignment, 1));
        int slot = assignment.slot();
        UUID uuid0 = slot == 0 ? assignment.playerUuid() : assignment.opponentUuid();
        String name0 = slot == 0 ? assignment.playerName() : assignment.opponentName();
        UUID uuid1 = slot == 0 ? assignment.opponentUuid() : assignment.playerUuid();
        String name1 = slot == 0 ? assignment.opponentName() : assignment.playerName();

        byte[][] items = serialize(kit);
        double x0 = assignment.spawnX(0);
        double y0 = assignment.spawnY(0);
        double z0 = assignment.spawnZ(0);
        double x1 = assignment.spawnX(1);
        double y1 = assignment.spawnY(1);
        double z1 = assignment.spawnZ(1);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            writeAddress(out, sessionId, headerSlot, slotToken, relayHost, relayPort);
            out.writeInt(Math.max(1, assignment.rounds()));
            out.write(ruleBytes(kit.id()));
            out.writeInt(0);
            out.writeInt(0);
            out.writeDouble(assignment.groundY());
            out.writeInt(0);
            out.writeDouble((x0 + x1) / 2.0);
            out.writeDouble((z0 + z1) / 2.0);
            out.writeDouble(PLAY_RADIUS);
            out.writeByte(0);
            writePlayer(out, x0, y0, z0, assignment.spawnYaw(0), assignment.spawnPitch(0),
                    uuid0, name0, kit, items);
            writePlayer(out, x1, y1, z1, assignment.spawnYaw(1), assignment.spawnPitch(1),
                    uuid1, name1, kit, items);
            out.writeByte(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(0);
            out.write(MatchSetupFrame0Encoder.sections(tables));
        } catch (IOException ex) {
            throw new IllegalStateException("dev kit encode failed", ex);
        }
        return bytes.toByteArray();
    }

    static void writeAddress(DataOutputStream out, long sessionId, int slot, byte[] slotToken,
                             String relayHost, int relayPort) throws IOException {
        byte[] token = slotToken == null ? NO_ITEM : slotToken;
        out.writeLong(sessionId);
        out.writeByte(slot);
        out.writeShort(token.length);
        out.write(token);
        writeString(out, relayHost == null ? "" : relayHost);
        out.writeInt(relayPort);
    }

    static byte[] address(long sessionId, int slot, byte[] slotToken, String relayHost,
                          int relayPort) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writeAddress(out, sessionId, slot, slotToken, relayHost, relayPort);
        } catch (IOException ex) {
            throw new IllegalStateException("address encode failed", ex);
        }
        return bytes.toByteArray();
    }

    static MatchRules matchRules(String kitId) {
        EdgeGameTypes.Rules rules = EdgeGameTypes.rules(kitId);
        return new MatchRules(rules.vanillaBuild(), rules.allowBucket(), rules.allowExplosion(),
                rules.explosionParticles(), rules.totemParticles(), rules.instaReady(),
                rules.instaReady(), rules.potSwordBoost());
    }

    static byte[] ruleBytes(String kitId) {
        return matchRules(kitId).bytes();
    }

    private static boolean edgeHosted(EdgeAssignment assignment, int forSlot) {
        String kind = assignment.slot() == forSlot
                ? assignment.hostKind() : assignment.opponentHostKind();
        return !EdgeAssignment.HOST_MOD.equals(kind);
    }

    public static String describe(EdgeAssignment assignment) {
        EdgeDemoKits.Kit kit = EdgeDemoKits.forGameType(assignment.gameType());
        GameState tables = EdgeKitTables.build(kit, edgeHosted(assignment, 0),
                edgeHosted(assignment, 1));
        return kit.id() + " (" + kit.summary() + ") ["
                + EdgeGameTypes.rules(kit.id()).describe() + "] " + EdgeKitTables.describe(tables);
    }

    public static String describe() {
        EdgeDemoKits.Kit kit = EdgeDemoKits.forGameType(EdgeDemoKits.IRON);
        return kit.id() + " (" + kit.summary() + ")";
    }

    private static byte[][] serialize(EdgeDemoKits.Kit kit) {
        ItemStack[] stacks = kit.items();
        byte[][] nbt = new byte[SLOTS][];
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = i < stacks.length ? stacks[i] : null;
            nbt[i] = stack == null ? NO_ITEM : stack.serializeAsBytes();
        }
        return nbt;
    }

    private static void writePlayer(DataOutputStream out, double x, double y, double z, float yaw,
                                    float pitch, UUID uuid, String name, EdgeDemoKits.Kit kit,
                                    byte[][] items) throws IOException {
        EdgeDemoKits.Stats held = EdgeDemoKits.stats(kit.items()[kit.selectedSlot()]);
        out.writeDouble(x);
        out.writeDouble(y);
        out.writeDouble(z);
        out.writeFloat(yaw);
        out.writeFloat(pitch);
        out.writeFloat(kit.health());
        out.writeFloat(held.damage());
        out.writeFloat(held.speed());
        out.writeInt(0);
        out.writeFloat(kit.armor());
        out.writeFloat(kit.kbResistance());
        out.writeInt(0);
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
        writeString(out, name);
        writeString(out, "");
        writeString(out, "");
        writeString(out, "");
        out.writeInt(kit.selectedSlot());
        out.writeFloat(kit.food());
        out.writeFloat(kit.saturation());
        out.writeInt(0);
        out.writeInt(0);
        out.writeFloat(kit.armorToughness());
        for (int s = 0; s < HOTBAR_SLOTS; s++) {
            ItemStack stack = kit.items()[s];
            EdgeDemoKits.Stats stats = EdgeDemoKits.stats(stack);
            out.writeFloat(stats.damage());
            out.writeFloat(stats.speed());
            out.writeInt(0);
            out.writeInt(stats.useKind());
            out.writeInt(stats.nutrition());
            out.writeFloat(stats.saturation());
            out.writeByte(stats.alwaysEdible() ? 1 : 0);
            out.writeInt(stats.eatTicks());
            out.writeInt(count(items, stack, s));
            out.writeInt(0);
            out.writeInt(stats.fireworkFlight());
        }
        out.writeInt(0);
        out.writeByte(0);
        for (int s = 0; s < SLOTS; s++) {
            out.writeInt(items[s].length);
            out.write(items[s]);
        }
        out.writeInt(0);
    }

    private static int count(byte[][] items, ItemStack stack, int slot) {
        if (items[slot].length == 0 || stack == null) {
            return 0;
        }
        return stack.getAmount();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] raw = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        out.writeShort(raw.length);
        out.write(raw);
    }
}
