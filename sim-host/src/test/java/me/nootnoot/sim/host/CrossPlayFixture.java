package me.nootnoot.sim.host;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.MatchSetupFrame0Decoder;
import me.nootnoot.sim.MatchSetupFrame0Encoder;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;

final class CrossPlayFixture {

    static final double GROUND_Y = 64.0;
    static final double EDGE_X = -1.0;
    static final double MOD_X = 1.0;
    static final double Z = 0.5;

    static final int EDGE_SLOT = 0;
    static final int MOD_SLOT = 1;

    static final int SLOT_SWORD = 0;
    static final int SLOT_CRYSTAL = 1;
    static final int SLOT_OBSIDIAN = 2;
    static final int SLOT_CROSSBOW = 3;
    static final int SLOT_ANCHOR = 4;
    static final int SLOT_SHULKER = 5;
    static final int SLOT_ARROWS = 6;
    static final int SLOT_GLOWSTONE = 7;

    static final int ID_SWORD = 979;
    static final int ID_CRYSTAL = 1096;
    static final int ID_OBSIDIAN = 405;
    static final int ID_CROSSBOW = 1150;
    static final int ID_ANCHOR = 1080;
    static final int ID_SHULKER_EDGE = 621;
    static final int ID_SHULKER_MOD = 622;
    static final int ID_ARROW = 1071;
    static final int ID_TOTEM = 1132;
    static final int ID_COBWEB = 118;
    static final int ID_STRING = 899;
    static final int ID_GLOWSTONE = 292;

    static final int CONTAINER_EDGE = 1;
    static final int CONTAINER_MOD = 2;

    static final int CELL_Y = (int) GROUND_Y;

    private CrossPlayFixture() {
    }

    static final class NoRender implements SimRenderer {
        @Override
        public void render(GameState head, GameState confirmed) {
        }

        @Override
        public void playEvents(List<CombatEvent> events, GameState state) {
        }

        @Override
        public void clear() {
        }
    }

    static GameState kit() {
        GameState s = new GameState();
        ItemDict.Builder b = new ItemDict.Builder();
        int sword = b.add(ID_SWORD, 1, 2031, ItemDict.FLAG_SWORD, Combat.USE_NONE,
                30f, 1.6f, 0, 0, 0, 0,
                ItemDict.packTool(5, 0, ItemDict.TOOL_SWORD, false),
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        int crystal = b.add(ID_CRYSTAL, 64, 0, ItemDict.FLAG_END_CRYSTAL, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int obsidian = b.add(ID_OBSIDIAN, 64, 0, ItemDict.FLAG_BLOCK, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int crossbow = b.add(ID_CROSSBOW, 1, 465,
                ItemDict.FLAG_CROSSBOW | ItemDict.FLAG_CROSSBOW_CHARGED, Combat.USE_CROSSBOW,
                1f, 4f, 0, 0, 0, ItemDict.packRanged(0, 0, 0, false, 0), 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        int anchor = b.add(ID_ANCHOR, 64, 0,
                ItemDict.FLAG_BLOCK | ItemDict.FLAG_RESPAWN_ANCHOR, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int glowstone = b.add(ID_GLOWSTONE, 64, 0,
                ItemDict.FLAG_BLOCK | ItemDict.FLAG_GLOWSTONE, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int shulkerEdge = b.add(ID_SHULKER_EDGE, 1, 0,
                ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER, Combat.USE_NONE, 1f, 4f,
                0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, CONTAINER_EDGE);
        int shulkerMod = b.add(ID_SHULKER_MOD, 1, 0,
                ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER, Combat.USE_NONE, 1f, 4f,
                0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, CONTAINER_MOD);
        int arrow = b.add(ID_ARROW, 64, 0, ItemDict.FLAG_ARROW_PLAIN, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int totem = b.add(ID_TOTEM, 1, 0, ItemDict.FLAG_TOTEM, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        s.dict = b.build();

        for (int id : new int[]{CONTAINER_EDGE, CONTAINER_MOD}) {
            Container c = new Container();
            for (int cell = 0; cell < Container.CELLS; cell++) {
                c.entry[cell] = totem;
                c.count[cell] = 1;
            }
            s.containers.put(id, c);
        }
        s.nextContainerId = CONTAINER_MOD + 1;
        s.obsidianItemId = ID_OBSIDIAN;
        s.glowstoneItemId = ID_GLOWSTONE;
        s.cobwebItemId = ID_COBWEB;
        s.stringItemId = ID_STRING;

        for (int i = 0; i < 2; i++) {
            PlayerState p = s.players[i];
            put(p, SLOT_SWORD, sword, 1);
            put(p, SLOT_CRYSTAL, crystal, 64);
            put(p, SLOT_OBSIDIAN, obsidian, 64);
            put(p, SLOT_CROSSBOW, crossbow, 1);
            put(p, SLOT_ANCHOR, anchor, 16);
            put(p, SLOT_SHULKER, i == EDGE_SLOT ? shulkerEdge : shulkerMod, 1);
            put(p, SLOT_ARROWS, arrow, 64);
            put(p, SLOT_GLOWSTONE, glowstone, 64);
            put(p, ItemDict.OFF_HAND, totem, 1);
        }
        s.edgeHosted[EDGE_SLOT] = true;
        s.edgeHosted[MOD_SLOT] = false;
        return s;
    }

    private static void put(PlayerState p, int slot, int entry, int count) {
        p.slotEntry[slot] = entry;
        p.slotCount[slot] = count;
        p.slotDamage[slot] = 0;
    }

    static byte[] setupBlob(GameState kit, long sessionId, int slot, byte[] slotToken, int rounds) {
        byte[] sections = MatchSetupFrame0Encoder.sections(kit);
        byte[] token = slotToken == null ? new byte[0] : slotToken;
        ByteBuffer b = ByteBuffer.allocate(1 << 18);
        b.putLong(sessionId);
        b.put((byte) slot);
        b.putShort((short) token.length);
        b.put(token);
        putString(b, "127.0.0.1");
        b.putInt(7777);
        b.putInt(rounds);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) 1);
        b.put((byte) 1);
        b.put((byte) 1);
        b.put((byte) 1);
        b.put((byte) 1);
        b.put((byte) 0);
        b.putInt(0);
        b.putInt(0);
        b.putDouble(GROUND_Y);
        b.putInt(0);
        b.putDouble(0.0);
        b.putDouble(0.0);
        b.putDouble(200.0);
        b.put((byte) 0);
        putPlayer(b, EDGE_X, 90f, "Unmodded");
        putPlayer(b, MOD_X, -90f, "Modded");
        b.put((byte) 0);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        b.put(sections);

        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    private static void putPlayer(ByteBuffer b, double x, float yaw, String name) {
        b.putDouble(x);
        b.putDouble(GROUND_Y);
        b.putDouble(Z);
        b.putFloat(yaw);
        b.putFloat(0f);
        b.putFloat(20f);
        b.putFloat(1f);
        b.putFloat(1.6f);
        b.putInt(0);
        b.putFloat(0f);
        b.putFloat(0f);
        b.putInt(0);
        b.putLong(0L);
        b.putLong(name.hashCode());
        putString(b, name);
        putString(b, "");
        putString(b, "");
        putString(b, "");
        b.putInt(SLOT_SWORD);
        b.putFloat(20f);
        b.putFloat(5f);
        b.putInt(0);
        b.putInt(0);
        b.putFloat(0f);
        for (int i = 0; i < 9 * 41; i++) {
            b.put((byte) 0);
        }
        b.putInt(64);
        b.put((byte) 0);
        for (int slot = 0; slot < MatchSetupFrame0Decoder.INVENTORY_SLOTS; slot++) {
            b.putInt(0);
        }
        b.putInt(0);
    }

    private static void putString(ByteBuffer b, String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        b.putShort((short) raw.length);
        b.put(raw);
    }
}
