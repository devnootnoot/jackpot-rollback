package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class MatchSetupFrame0RoundTripTest {

    private static final int SWORD = 1;
    private static final int CROSSBOW = 2;
    private static final int ARROW = 3;
    private static final int TIPPED_ARROW = 4;
    private static final int GLOWSTONE = 5;
    private static final int GAPPLE = 6;
    private static final int CHESTPLATE = 7;
    private static final int SHULKER = 8;
    private static final int TOTEM = 9;

    private static final int SWORD_ID = 979;
    private static final int CROSSBOW_ID = 1150;
    private static final int ARROW_ID = 1071;
    private static final int TIPPED_ARROW_ID = 1074;
    private static final int GLOWSTONE_ID = 292;
    private static final int GAPPLE_ID = 1044;
    private static final int CHESTPLATE_ID = 1004;
    private static final int SHULKER_ID = 621;
    private static final int TOTEM_ID = 1132;
    private static final int OBSIDIAN_ID = 405;
    private static final int COBBLESTONE_ID = 24;
    private static final int STRING_ID = 899;
    private static final int COBWEB_ID = 118;
    private static final int MUD_ID = 340;
    private static final int GLOWSTONE_DUST_ID = 887;

    private static final int SHULKER_CONTAINER = 1;
    private static final int ENDER_CHEST_CONTAINER = 2;

    @Test
    void everySectionSurvivesTheRoundTrip() {
        GameState source = source();
        byte[] sections = MatchSetupFrame0Encoder.sections(source);
        assertEquals(MatchSetupFrame0Encoder.sectionBytes(source), sections.length,
                "the size estimate has to match the bytes actually written");

        GameState decoded = MatchSetupFrame0Decoder.decode(wire(sections)).state();

        assertDictEquals(source.dict, decoded.dict);
        assertSlotsEqual(source, decoded);
        assertContainersEqual(source, decoded);
        assertBlocksEqual(source.blockProps, decoded.blockProps);
        assertEquals(source.edgeHosted[0], decoded.edgeHosted[0]);
        assertEquals(source.edgeHosted[1], decoded.edgeHosted[1]);
        assertEquals(source.cobwebItemId, decoded.cobwebItemId);
        assertEquals(source.stringItemId, decoded.stringItemId);
        assertEquals(source.obsidianItemId, decoded.obsidianItemId);
        assertEquals(source.cobblestoneItemId, decoded.cobblestoneItemId);
        assertEquals(source.mudItemId, decoded.mudItemId);
        assertEquals(source.glowstoneItemId, decoded.glowstoneItemId);
        assertEquals(source.glowstoneDustItemId, decoded.glowstoneDustItemId);
        assertEquals(source.nextContainerId, decoded.nextContainerId);
        assertEquals(source.dict.digest(), decoded.dict.digest(),
                "a dictionary that round-trips must hash the same on both edges");
        assertEquals(source.blockProps.digest(), decoded.blockProps.digest());
    }

    @Test
    void reEncodingTheDecodedStateIsByteIdentical() {
        GameState source = source();
        byte[] first = MatchSetupFrame0Encoder.sections(source);
        GameState decoded = MatchSetupFrame0Decoder.decode(wire(first)).state();
        byte[] second = MatchSetupFrame0Encoder.sections(decoded);
        assertArrayEquals(first, second,
                "encode(decode(x)) must reproduce x byte for byte or the two edges cannot agree"
                        + " on frame 0");
    }

    @Test
    void containerInsertionOrderDoesNotChangeTheBytes() {
        GameState forward = source();
        GameState reversed = source();
        reversed.containers.clear();
        reversed.containers.put(ENDER_CHEST_CONTAINER, enderChest());
        reversed.containers.put(SHULKER_CONTAINER, totemShulker());
        assertArrayEquals(MatchSetupFrame0Encoder.sections(forward),
                MatchSetupFrame0Encoder.sections(reversed),
                "container ids are written in sorted order, never in map iteration order");
    }

    @Test
    void aShulkerFullOfTotemsArrivesWithItsContents() {
        GameState decoded = MatchSetupFrame0Decoder.decode(wire(
                MatchSetupFrame0Encoder.sections(source()))).state();

        assertEquals(SHULKER_CONTAINER, decoded.dict.containerSeed(SHULKER),
                "the shulker entry has to keep pointing at its container");
        assertEquals(SHULKER, decoded.dict.entryForContainer(SHULKER_CONTAINER));
        Container c = decoded.containers.get(SHULKER_CONTAINER);
        assertNotNull(c);
        for (int cell = 0; cell < Container.CELLS; cell++) {
            assertEquals(TOTEM, c.entry[cell], "cell " + cell);
            assertEquals(1, c.count[cell], "cell " + cell);
        }
        Container initial = decoded.roundInitialContainers.get(SHULKER_CONTAINER);
        assertNotNull(initial, "the round-initial copy has to carry the contents too");
        assertEquals(TOTEM, initial.entry[0]);
        assertTrue(initial != c, "the round-initial copy must not alias the live container");
    }

    private static GameState source() {
        GameState s = new GameState();
        s.dict = dict();
        s.blockProps = blocks();
        s.edgeHosted[0] = true;
        s.edgeHosted[1] = false;
        s.cobwebItemId = COBWEB_ID;
        s.stringItemId = STRING_ID;
        s.obsidianItemId = OBSIDIAN_ID;
        s.cobblestoneItemId = COBBLESTONE_ID;
        s.mudItemId = MUD_ID;
        s.glowstoneItemId = GLOWSTONE_ID;
        s.glowstoneDustItemId = GLOWSTONE_DUST_ID;

        PlayerState p0 = s.players[0];
        put(p0, 0, SWORD, 1, 37);
        put(p0, 1, GLOWSTONE, 64, 0);
        put(p0, 2, CROSSBOW, 1, 12);
        put(p0, 3, TIPPED_ARROW, 16, 0);
        put(p0, 4, GAPPLE, 8, 0);
        put(p0, 9, SHULKER, 1, 0);
        put(p0, ItemDict.ARMOR_CHEST, CHESTPLATE, 1, 145);
        put(p0, ItemDict.OFF_HAND, TOTEM, 1, 0);

        PlayerState p1 = s.players[1];
        put(p1, 0, SWORD, 1, 0);
        put(p1, 5, ARROW, 32, 0);
        put(p1, 6, CROSSBOW, 1, 0);
        put(p1, 20, GLOWSTONE, 17, 0);
        put(p1, ItemDict.ARMOR_CHEST, CHESTPLATE, 1, 0);

        s.containers.put(SHULKER_CONTAINER, totemShulker());
        s.containers.put(ENDER_CHEST_CONTAINER, enderChest());
        s.nextContainerId = ENDER_CHEST_CONTAINER + 1;
        return s;
    }

    private static Container totemShulker() {
        Container c = new Container();
        for (int cell = 0; cell < Container.CELLS; cell++) {
            c.entry[cell] = TOTEM;
            c.count[cell] = 1;
        }
        return c;
    }

    private static Container enderChest() {
        Container c = new Container();
        c.entry[0] = GLOWSTONE;
        c.count[0] = 64;
        c.entry[4] = GAPPLE;
        c.count[4] = 3;
        c.entry[26] = SWORD;
        c.count[26] = 1;
        c.damage[26] = 900;
        return c;
    }

    private static void put(PlayerState p, int slot, int entry, int count, int damage) {
        p.slotEntry[slot] = entry;
        p.slotCount[slot] = count;
        p.slotDamage[slot] = damage;
    }

    private static ItemDict dict() {
        ItemDict.Builder b = new ItemDict.Builder();
        b.add(SWORD_ID, 1, 2031, ItemDict.FLAG_SWORD, Combat.USE_NONE, 8f, 1.6f, 2,
                ItemDict.packWeapon(5, 2, 0, 0), 0, 0,
                ItemDict.packTool(5, 0, ItemDict.TOOL_SWORD, false),
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        b.add(CROSSBOW_ID, 1, 465, ItemDict.FLAG_CROSSBOW | ItemDict.FLAG_CROSSBOW_CHARGED,
                Combat.USE_CROSSBOW, 1f, 4f, 0, 0, 0,
                ItemDict.packRanged(0, 3, 1, false, 4), 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        b.add(ARROW_ID, 64, 0, ItemDict.FLAG_ARROW_PLAIN, Combat.USE_NONE, 1f, 4f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        b.add(TIPPED_ARROW_ID, 64, 0, ItemDict.FLAG_ARROW_SPECIAL, Combat.USE_NONE, 1f, 4f, 0,
                0, 0, 0, 0, 0, 0f, 0,
                0, ItemDict.packEffect(Effects.WEAKNESS, 0, 880), 0, 0, 0,
                0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        b.add(GLOWSTONE_ID, 64, 0, ItemDict.FLAG_BLOCK | ItemDict.FLAG_GLOWSTONE, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        b.add(GAPPLE_ID, 64, 0, ItemDict.FLAG_ALWAYS_EDIBLE, Combat.USE_FOOD, 1f, 4f, 0, 0, 0, 0, 0,
                4, 9.6f, 32, 0,
                ItemDict.packEffect(Effects.REGENERATION, 1, 100),
                ItemDict.packEffect(Effects.ABSORPTION, 0, 2400), 0, 0,
                0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        b.add(CHESTPLATE_ID, 1, 592, 0, Combat.USE_NONE, 1f, 4f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0,
                8, 3f, 0.1f, 4, 3, 2, 1, 0, ItemDict.EQUIP_CHEST, -1);
        b.add(SHULKER_ID, 1, 0, ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE,
                SHULKER_CONTAINER);
        b.add(TOTEM_ID, 1, 0, ItemDict.FLAG_TOTEM, Combat.USE_NONE, 1f, 4f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        return b.build();
    }

    private static BlockProps blocks() {
        BlockProps.Builder b = new BlockProps.Builder();
        b.add(OBSIDIAN_ID, 50f, 1200f, OBSIDIAN_ID, 3, ItemDict.TOOL_PICKAXE, true);
        b.add(COBBLESTONE_ID, 2f, 6f, COBBLESTONE_ID, 0, ItemDict.TOOL_PICKAXE, true);
        b.add(GLOWSTONE_ID, 0.3f, 0.3f, GLOWSTONE_DUST_ID, -1, ItemDict.TOOL_NONE, false);
        b.add(SHULKER_ID, 2f, 2f, SHULKER_ID, -1, ItemDict.TOOL_PICKAXE, false);
        return b.build();
    }

    private static void assertDictEquals(ItemDict expected, ItemDict actual) {
        assertEquals(expected.size(), actual.size());
        for (int e = 1; e <= expected.size(); e++) {
            String at = " at entry " + e;
            assertEquals(expected.itemId(e), actual.itemId(e), "itemId" + at);
            assertEquals(expected.maxStack(e), actual.maxStack(e), "maxStack" + at);
            assertEquals(expected.maxDamage(e), actual.maxDamage(e), "maxDamage" + at);
            assertEquals(expected.flags(e), actual.flags(e), "flags" + at);
            assertEquals(expected.useKind(e), actual.useKind(e), "useKind" + at);
            assertEquals(expected.meleeDamage(e), actual.meleeDamage(e), "meleeDamage" + at);
            assertEquals(expected.meleeSpeed(e), actual.meleeSpeed(e), "meleeSpeed" + at);
            assertEquals(expected.knockback(e), actual.knockback(e), "knockback" + at);
            assertEquals(expected.weaponEnchants(e), actual.weaponEnchants(e),
                    "weaponEnchants" + at);
            assertEquals(expected.maceInfo(e), actual.maceInfo(e), "maceInfo" + at);
            assertEquals(expected.rangedInfo(e), actual.rangedInfo(e), "rangedInfo" + at);
            assertEquals(expected.toolInfo(e), actual.toolInfo(e), "toolInfo" + at);
            assertEquals(expected.foodNutrition(e), actual.foodNutrition(e), "foodNutrition" + at);
            assertEquals(expected.foodSaturation(e), actual.foodSaturation(e),
                    "foodSaturation" + at);
            assertEquals(expected.foodEatTicks(e), actual.foodEatTicks(e), "foodEatTicks" + at);
            assertEquals(expected.fireworkFlight(e), actual.fireworkFlight(e),
                    "fireworkFlight" + at);
            for (int i = 0; i < 4; i++) {
                assertEquals(expected.effect(e, i), actual.effect(e, i), "effect" + i + at);
            }
            assertEquals(expected.armorPoints(e), actual.armorPoints(e), "armorPoints" + at);
            assertEquals(expected.armorToughness(e), actual.armorToughness(e),
                    "armorToughness" + at);
            assertEquals(expected.armorKbResistance(e), actual.armorKbResistance(e),
                    "armorKbResistance" + at);
            assertEquals(expected.armorProtection(e), actual.armorProtection(e),
                    "armorProtection" + at);
            assertEquals(expected.armorBlastProtection(e), actual.armorBlastProtection(e),
                    "armorBlastProtection" + at);
            assertEquals(expected.armorProjectileProtection(e), actual.armorProjectileProtection(e),
                    "armorProjectileProtection" + at);
            assertEquals(expected.armorFireProtection(e), actual.armorFireProtection(e),
                    "armorFireProtection" + at);
            assertEquals(expected.armorFeatherFalling(e), actual.armorFeatherFalling(e),
                    "armorFeatherFalling" + at);
            assertEquals(expected.equipSlot(e), actual.equipSlot(e), "equipSlot" + at);
            assertEquals(expected.containerSeed(e), actual.containerSeed(e), "containerSeed" + at);
        }
    }

    private static void assertSlotsEqual(GameState expected, GameState actual) {
        for (int i = 0; i < 2; i++) {
            PlayerState a = expected.players[i];
            PlayerState b = actual.players[i];
            for (int slot = 0; slot < ItemDict.SLOTS; slot++) {
                String at = " for player " + i + " slot " + slot;
                assertEquals(a.slotEntry[slot], b.slotEntry[slot], "entry" + at);
                assertEquals(a.slotCount[slot], b.slotCount[slot], "count" + at);
                assertEquals(a.slotDamage[slot], b.slotDamage[slot], "damage" + at);
            }
        }
    }

    private static void assertContainersEqual(GameState expected, GameState actual) {
        assertEquals(expected.containers.size(), actual.containers.size());
        for (java.util.Map.Entry<Integer, Container> e : expected.containers.entrySet()) {
            Container a = e.getValue();
            Container b = actual.containers.get(e.getKey());
            assertNotNull(b, "container " + e.getKey() + " is missing");
            for (int cell = 0; cell < Container.CELLS; cell++) {
                String at = " in container " + e.getKey() + " cell " + cell;
                assertEquals(a.entry[cell], b.entry[cell], "entry" + at);
                assertEquals(a.count[cell], b.count[cell], "count" + at);
                assertEquals(a.damage[cell], b.damage[cell], "damage" + at);
            }
        }
    }

    private static void assertBlocksEqual(BlockProps expected, BlockProps actual) {
        assertEquals(expected.size(), actual.size());
        assertArrayEquals(expected.sortedKeys(), actual.sortedKeys());
        for (int key : expected.sortedKeys()) {
            String at = " for block " + key;
            assertEquals(expected.hardness(key), actual.hardness(key), "hardness" + at);
            assertEquals(expected.blastResistance(key), actual.blastResistance(key),
                    "blastResistance" + at);
            assertEquals(expected.dropItemId(key), actual.dropItemId(key), "dropItemId" + at);
            assertEquals(expected.harvestTier(key), actual.harvestTier(key), "harvestTier" + at);
            assertEquals(expected.toolClass(key), actual.toolClass(key), "toolClass" + at);
            assertEquals(expected.requiresTool(key), actual.requiresTool(key),
                    "requiresTool" + at);
        }
    }

    private static byte[] wire(byte[] sections) {
        ByteBuffer b = ByteBuffer.allocate(1 << 18);
        b.putLong(9001L);
        b.put((byte) 0);
        b.putShort((short) 0);
        putString(b, "127.0.0.1");
        b.putInt(7777);
        b.putInt(3);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) 0);
        b.putInt(0);
        b.putInt(0);
        b.putDouble(64.0);
        b.putInt(0);
        b.putDouble(0.0);
        b.putDouble(0.0);
        b.putDouble(200.0);
        b.put((byte) 0);
        putPlayer(b);
        putPlayer(b);
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

    private static void putPlayer(ByteBuffer b) {
        b.putDouble(0.0);
        b.putDouble(64.0);
        b.putDouble(0.0);
        b.putFloat(0f);
        b.putFloat(0f);
        b.putFloat(20f);
        b.putFloat(1f);
        b.putFloat(4f);
        b.putInt(0);
        b.putFloat(0f);
        b.putFloat(0f);
        b.putInt(0);
        b.putLong(0L);
        b.putLong(0L);
        putString(b, "Dev");
        putString(b, "");
        putString(b, "");
        putString(b, "");
        b.putInt(0);
        b.putFloat(20f);
        b.putFloat(5f);
        b.putInt(0);
        b.putInt(0);
        b.putFloat(0f);
        for (int s = 0; s < 9 * 41; s++) {
            b.put((byte) 0);
        }
        b.putInt(0);
        b.put((byte) 0);
        for (int s = 0; s < MatchSetupFrame0Decoder.INVENTORY_SLOTS; s++) {
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
