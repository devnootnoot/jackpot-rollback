package me.nootnoot.sim;

import java.nio.ByteBuffer;
import java.util.Arrays;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;

public final class MatchSetupFrame0Encoder {

    private MatchSetupFrame0Encoder() {
    }

    public static byte[] sections(GameState state) {
        ByteBuffer b = ByteBuffer.allocate(sectionBytes(state));
        writeSections(b, state);
        return b.array();
    }

    public static int sectionBytes(GameState state) {
        return 8 + dict(state).size() * ItemDict.ENTRY_BYTES
                + 2 * ItemDict.SLOTS * ItemDict.SLOT_BYTES
                + 4 + state.containers.size() * (4 + Container.CELLS * ItemDict.SLOT_BYTES)
                + 8 + blockProps(state).size() * BlockProps.ROW_BYTES
                + 2
                + 32;
    }

    public static void writeSections(ByteBuffer b, GameState state) {
        writeLoadoutSection(b, state);
        writeContainerSection(b, state);
        writeBlockSection(b, state);
        writeHostSection(b, state);
        writeSeedSection(b, state);
    }

    private static void writeLoadoutSection(ByteBuffer b, GameState state) {
        ItemDict dict = dict(state);
        int entryCount = dict.size();
        if (entryCount > ItemDict.MAX_ENTRIES) {
            throw new IllegalArgumentException("entry count out of bounds: " + entryCount);
        }
        b.putInt(MatchSetupFrame0Decoder.LOADOUT_MAGIC);
        b.putInt(entryCount);
        for (int e = 1; e <= entryCount; e++) {
            writeEntry(b, dict, e);
        }
        for (int i = 0; i < 2; i++) {
            PlayerState p = state.players[i];
            for (int slot = 0; slot < ItemDict.SLOTS; slot++) {
                if (p == null) {
                    writeSlot(b, ItemDict.NONE, 0, 0);
                    continue;
                }
                writeSlot(b, p.slotEntry[slot], p.slotCount[slot], p.slotDamage[slot]);
            }
        }
    }

    private static void writeEntry(ByteBuffer b, ItemDict d, int e) {
        b.putInt(d.itemId(e));
        b.putInt(d.maxStack(e));
        b.putInt(d.maxDamage(e));
        b.putInt(d.flags(e));
        b.putInt(d.useKind(e));
        b.putFloat(d.meleeDamage(e));
        b.putFloat(d.meleeSpeed(e));
        b.put((byte) d.knockback(e));
        b.putShort((short) d.weaponEnchants(e));
        b.putShort((short) d.maceInfo(e));
        b.putShort((short) d.rangedInfo(e));
        b.putShort((short) d.toolInfo(e));
        b.put((byte) d.foodNutrition(e));
        b.putFloat(d.foodSaturation(e));
        b.putShort((short) d.foodEatTicks(e));
        b.put((byte) d.fireworkFlight(e));
        b.putInt(d.effect(e, 0));
        b.putInt(d.effect(e, 1));
        b.putInt(d.effect(e, 2));
        b.putInt(d.effect(e, 3));
        b.put((byte) d.armorPoints(e));
        b.putFloat(d.armorToughness(e));
        b.putFloat(d.armorKbResistance(e));
        b.put((byte) d.armorProtection(e));
        b.put((byte) d.armorBlastProtection(e));
        b.put((byte) d.armorProjectileProtection(e));
        b.put((byte) d.armorFireProtection(e));
        b.put((byte) d.armorFeatherFalling(e));
        b.put((byte) d.equipSlot(e));
        b.putInt(d.containerSeed(e));
    }

    private static void writeContainerSection(ByteBuffer b, GameState state) {
        int[] ids = sortedContainerIds(state);
        if (ids.length > MatchSetupFrame0Decoder.MAX_CONTAINERS) {
            throw new IllegalArgumentException("container count out of bounds: " + ids.length);
        }
        b.putInt(ids.length);
        for (int id : ids) {
            b.putInt(id);
            Container c = state.containers.get(id);
            for (int cell = 0; cell < Container.CELLS; cell++) {
                writeSlot(b, c.entry[cell], c.count[cell], c.damage[cell]);
            }
        }
    }

    private static void writeBlockSection(ByteBuffer b, GameState state) {
        BlockProps props = blockProps(state);
        int[] keys = props.sortedKeys();
        if (keys.length > BlockProps.MAX_ROWS) {
            throw new IllegalArgumentException("block count out of bounds: " + keys.length);
        }
        b.putInt(MatchSetupFrame0Decoder.BLOCK_MAGIC);
        b.putInt(keys.length);
        for (int key : keys) {
            b.putInt(key);
            b.putFloat(props.hardness(key));
            b.putFloat(props.blastResistance(key));
            b.putInt(props.dropItemId(key));
            b.put((byte) props.harvestTier(key));
            b.put((byte) props.toolClass(key));
            b.put((byte) (props.requiresTool(key) ? 1 : 0));
        }
    }

    private static void writeHostSection(ByteBuffer b, GameState state) {
        b.put((byte) (state.edgeHosted[0] ? 1 : 0));
        b.put((byte) (state.edgeHosted[1] ? 1 : 0));
    }

    private static void writeSeedSection(ByteBuffer b, GameState state) {
        b.putInt(MatchSetupFrame0Decoder.SEED_MAGIC);
        b.putInt(state.cobwebItemId);
        b.putInt(state.stringItemId);
        b.putInt(state.obsidianItemId);
        b.putInt(state.cobblestoneItemId);
        b.putInt(state.mudItemId);
        b.putInt(state.glowstoneItemId);
        b.putInt(state.glowstoneDustItemId);
    }

    private static void writeSlot(ByteBuffer b, int entry, int count, int damage) {
        b.putShort((short) clamp(entry, 0xFFFF));
        b.put((byte) clamp(count, 0xFF));
        b.putShort((short) clamp(damage, 0xFFFF));
    }

    private static int clamp(int value, int max) {
        return value < 0 ? 0 : Math.min(value, max);
    }

    private static int[] sortedContainerIds(GameState state) {
        int[] ids = new int[state.containers.size()];
        int i = 0;
        for (int id : state.containers.keySet()) {
            ids[i++] = id;
        }
        Arrays.sort(ids);
        return ids;
    }

    private static ItemDict dict(GameState state) {
        return state.dict == null ? ItemDict.empty() : state.dict;
    }

    private static BlockProps blockProps(GameState state) {
        return state.blockProps == null ? BlockProps.empty() : state.blockProps;
    }
}
