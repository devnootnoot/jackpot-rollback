package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import org.junit.jupiter.api.Test;

class MatchSetupFrame0DecoderTest {
    private static final int CROSSBOW_ENTRY = 1;
    private static final int SWORD_ENTRY = 2;
    private static final int GLOWSTONE_ENTRY = 3;
    private static final int ARROW_ENTRY = 4;

    private static final int OBSIDIAN_ID = 102;
    private static final int COBBLESTONE_ID = 103;

    @Test
    void decodesFrame0FromWireAndSeeds() {
        double[][] boxes = {{1, 2, 3, 4, 5, 6}, {7, 8, 9, 10, 11, 12}};
        byte[] wire = buildWire(3, true, true, 67.5, boxes, 10.0, -20.0, 48.0, true);

        MatchSetupFrame0Decoder.Frame0 f = MatchSetupFrame0Decoder.decode(wire);
        GameState s = f.state();

        assertEquals(67.5, f.arenaGroundY());
        assertArrayEquals(boxes, f.arenaBoxes());

        assertEquals(3, s.roundsTarget);
        assertTrue(s.vanillaBuild);
        assertTrue(s.potSwordBoost);
        assertEquals(100, s.cobwebItemId);
        assertEquals(101, s.stringItemId);
        assertEquals(OBSIDIAN_ID, s.obsidianItemId);
        assertEquals(COBBLESTONE_ID, s.cobblestoneItemId);
        assertEquals(104, s.mudItemId);
        assertEquals(105, s.glowstoneItemId);
        assertEquals(106, s.glowstoneDustItemId);
        assertEquals(10.0, s.playCenterX);
        assertEquals(-20.0, s.playCenterZ);
        assertEquals(48.0, s.playRadius);
        assertTrue(s.playCircular);
        assertEquals(Simulation.ROUND_COUNTDOWN_TICKS, s.roundResetCountdown);

        assertEquals(0.0, s.players[0].x);
        assertEquals(20.0f, s.players[0].health);
        assertEquals(16, s.players[0].pearls);
        assertEquals(200, s.players[0].effectTicks[Effects.SPEED]);
        assertEquals(MatchSetupFrame0Decoder.SETTLED_GROUNDED_VY, s.players[0].vy);

        assertEquals(4, s.dict.size());
        assertEquals(8.0f, s.dict.meleeDamage(SWORD_ENTRY), 1e-6);
        assertTrue(s.dict.isSword(SWORD_ENTRY));
        assertTrue(s.dict.isCrossbow(CROSSBOW_ENTRY));
        assertTrue(s.dict.crossbowCharged(CROSSBOW_ENTRY));
        assertTrue(s.dict.isGlowstone(GLOWSTONE_ENTRY));

        assertEquals(SWORD_ENTRY, s.players[0].slotEntry[0]);
        assertEquals(1, s.players[0].slotCount[0]);
        assertEquals(GLOWSTONE_ENTRY, s.players[0].slotEntry[1]);
        assertEquals(32, s.players[0].slotCount[1]);
        assertEquals(CROSSBOW_ENTRY, s.players[0].slotEntry[4]);
        assertTrue(s.players[0].slotCrossbowLoaded[4],
                "a charged crossbow entry must seed the sim-owned charge");
        assertEquals(CROSSBOW_ENTRY, s.players[1].slotEntry[7]);
        assertTrue(s.players[1].slotCrossbowLoaded[7]);

        assertEquals(8.0f, s.players[0].attackDamage, 1e-6);

        Container c = s.containers.get(5);
        assertNotNull(c);
        assertEquals(GLOWSTONE_ENTRY, c.entry[0]);
        assertEquals(12, c.count[0]);
        assertEquals(6, s.nextContainerId);

        assertEquals(50.0f, s.blockProps.hardness(OBSIDIAN_ID), 1e-6);
        assertEquals(1200.0f, s.blockProps.blastResistance(OBSIDIAN_ID), 1e-6);
        assertEquals(OBSIDIAN_ID, s.blockProps.dropItemId(OBSIDIAN_ID));
        assertEquals(ItemDict.TOOL_PICKAXE, s.blockProps.toolClass(OBSIDIAN_ID));
        assertTrue(s.blockProps.requiresTool(OBSIDIAN_ID));
        assertEquals(2.0f, s.blockProps.hardness(COBBLESTONE_ID), 1e-6);

        assertTrue(!s.edgeHosted[0]);
        assertTrue(s.edgeHosted[1]);

        assertNotNull(s.roundInitial);
        assertEquals(20.0f, s.roundInitial[0].health);
        assertTrue(s.roundInitial[0].slotCrossbowLoaded[4]);
        assertTrue(s.roundInitial[0].slotEntry != s.players[0].slotEntry,
                "the round-initial snapshot must not alias the live slot table");
    }

    @Test
    void aPreChargedCrossbowIsSeededWithTheArrowItWouldHaveBeenLoadedWith() {
        byte[] wire = buildWire(3, true, true, 67.5, new double[][]{{1, 2, 3, 4, 5, 6}},
                0, 0, 8, false);
        GameState s = MatchSetupFrame0Decoder.decode(wire).state();

        assertTrue(s.dict.isArrow(ARROW_ENTRY), "the kit has to actually carry an arrow");
        assertEquals(ARROW_ENTRY, s.players[0].slotCrossbowEntry[4],
                "a kit crossbow that starts charged must fire a bolt that has an entry");
        assertEquals(ARROW_ENTRY, s.players[1].slotCrossbowEntry[7]);
        assertEquals(ItemDict.NONE, s.players[0].slotCrossbowEntry[0],
                "a slot holding no charged crossbow must carry no charge entry");
        assertEquals(ItemDict.NONE, s.players[1].slotCrossbowEntry[4]);
        assertEquals(ARROW_ENTRY, s.roundInitial[0].slotCrossbowEntry[4],
                "the round-initial snapshot must carry the seed too");
    }

    @Test
    void theExplosionAndBucketRuleBytesReachTheGameState() {
        byte[] wire = buildWire(3, true, true, 67.5, new double[][]{{1, 2, 3, 4, 5, 6}},
                0, 0, 8, false);
        GameState on = MatchSetupFrame0Decoder.decode(wire).state();
        assertTrue(on.allowExplosion, "control: this wire has both rule bytes set");
        assertTrue(on.allowBucket);

        int rules = ruleByteOffset(wire);
        wire[rules + 1] = 0;
        wire[rules + 2] = 0;
        GameState off = MatchSetupFrame0Decoder.decode(wire).state();

        assertFalse(off.allowBucket, "the byte straight after vanillaBuild is allowBucketUsage;"
                + " the edge used to read it and throw it away, which is how a rule the modded"
                + " client obeys stopped applying to anyone else");
        assertFalse(off.allowExplosion, "and the one after that is allowExplosion");
        assertTrue(off.vanillaBuild, "reading the rule bytes one position out would eat"
                + " vanillaBuild instead, so pin the neighbours too");
        assertTrue(off.potSwordBoost);
    }

    private static int ruleByteOffset(byte[] wire) {
        ByteBuffer b = ByteBuffer.wrap(wire);
        b.getLong();
        b.get();
        int tokenLen = b.getShort() & 0xFFFF;
        b.position(b.position() + tokenLen);
        int hostLen = b.getShort() & 0xFFFF;
        b.position(b.position() + hostLen);
        b.getInt();
        b.getInt();
        return b.position();
    }

    @Test
    void theAddressHeaderNeverReachesFrame0() {
        double[][] boxes = {{1, 2, 3, 4, 5, 6}};
        byte[] slot0Token = new byte[32];
        byte[] slot1Token = new byte[32];
        Arrays.fill(slot0Token, (byte) 0xA1);
        Arrays.fill(slot1Token, (byte) 0xB2);

        byte[] toSlot0 = buildWire(0, slot0Token, 3, true, true, 67.5, boxes, 0, 0, 8, false);
        byte[] toSlot1 = buildWire(1, slot1Token, 3, true, true, 67.5, boxes, 0, 0, 8, false);

        assertEquals(toSlot0.length, toSlot1.length);
        int header = 8 + 1 + 2 + slot0Token.length;
        assertArrayEquals(Arrays.copyOfRange(toSlot0, header, toSlot0.length),
                Arrays.copyOfRange(toSlot1, header, toSlot1.length),
                "the two copies of a cross-play setup blob differ only in the address header");

        GameState a = MatchSetupFrame0Decoder.decode(toSlot0).state();
        GameState b = MatchSetupFrame0Decoder.decode(toSlot1).state();
        assertEquals(Checksum.of(a), Checksum.of(b),
                "each side must be addressed with its OWN slot and its OWN Hello token, so the"
                        + " opponent's token never travels to the opponent's host. That is only"
                        + " safe while the slot byte and the token are invisible to frame 0 - if"
                        + " this ever fails, re-addressing a blob desyncs the match");
    }

    @Test
    void aBlobWithoutTheLoadoutTailIsRefused() {
        double[][] boxes = {{1, 2, 3, 4, 5, 6}};
        byte[] full = buildWire(3, true, true, 67.5, boxes, 0, 0, 8, false);
        byte[] truncated = new byte[full.length - 200];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        assertThrows(IllegalArgumentException.class,
                () -> MatchSetupFrame0Decoder.decode(truncated));
    }

    @Test
    void aLyingBoxCountIsRefusedBeforeAnythingIsAllocatedForIt() {
        double[][] boxes = {{1, 2, 3, 4, 5, 6}};
        byte[] wire = buildWire(3, true, true, 67.5, boxes, 0, 0, 8, false);
        int at = boxCountOffset(wire);

        ByteBuffer.wrap(wire).putInt(at, (1 << 20) - 1);

        assertThrows(IllegalArgumentException.class,
                () -> MatchSetupFrame0Decoder.decode(wire),
                "the count was range-checked against MAX_BOXES and then new double[count][6] ran"
                        + " before a single one of those doubles was known to be present, so a blob"
                        + " that cannot possibly hold them still allocated a million arrays for"
                        + " them. Every other length in this decoder goes through require() first;"
                        + " this one did not.");
    }

    @Test
    void anHonestBoxCountStillDecodes() {
        double[][] boxes = {{1, 2, 3, 4, 5, 6}, {7, 8, 9, 10, 11, 12}};
        byte[] wire = buildWire(3, true, true, 67.5, boxes, 0, 0, 8, false);
        assertArrayEquals(boxes, MatchSetupFrame0Decoder.decode(wire).arenaBoxes());
    }

    private static int boxCountOffset(byte[] wire) {
        ByteBuffer b = ByteBuffer.wrap(wire);
        for (int i = 0; i + 12 <= wire.length; i++) {
            if (b.getInt(i) == 1 && b.getDouble(i + 4) == 1.0) {
                return i;
            }
        }
        throw new IllegalStateException("the box count was not found in the test wire");
    }

    private static byte[] buildWire(int rounds, boolean vanillaBuild, boolean potSwordBoost,
                                    double groundY, double[][] boxes, double pcx, double pcz,
                                    double pr, boolean circular) {
        return buildWire(0, new byte[]{7, 7, 7, 7}, rounds, vanillaBuild, potSwordBoost, groundY,
                boxes, pcx, pcz, pr, circular);
    }

    private static byte[] buildWire(int headerSlot, byte[] headerToken, int rounds,
                                    boolean vanillaBuild, boolean potSwordBoost,
                                    double groundY, double[][] boxes, double pcx, double pcz,
                                    double pr, boolean circular) {
        ByteBuffer b = ByteBuffer.allocate(1 << 16);
        b.putLong(1234L);
        b.put((byte) headerSlot);
        b.putShort((short) headerToken.length);
        b.put(headerToken);
        putString(b, "127.0.0.1");
        b.putInt(7777);
        b.putInt(rounds);
        b.put((byte) (vanillaBuild ? 1 : 0));
        b.put((byte) 1);
        b.put((byte) 1);
        b.put((byte) 1);
        b.put((byte) 1);
        b.put((byte) 0);
        b.put((byte) 1);
        b.put((byte) (potSwordBoost ? 1 : 0));
        putItemIdSet(b, 3, 1);
        putItemIdSet(b, 49);
        b.putDouble(groundY);
        b.putInt(boxes.length);
        for (double[] box : boxes) {
            for (int k = 0; k < 6; k++) {
                b.putDouble(box[k]);
            }
        }
        b.putDouble(pcx);
        b.putDouble(pcz);
        b.putDouble(pr);
        b.put((byte) (circular ? 1 : 0));
        putPlayer(b, 0.0, 64.0, 0.0, 20.0f, 7.0f, 16, 1);
        putPlayer(b, 3.0, 64.0, 0.0, 18.0f, 6.0f, 8, 0);

        b.put((byte) 0);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);

        putLoadoutSection(b);
        putContainerSection(b);
        putBlockSection(b);
        b.put((byte) 0);
        b.put((byte) 1);
        putSeedSection(b);

        byte[] out = new byte[b.position()];
        b.flip();
        b.get(out);
        return out;
    }

    private static void putSeedSection(ByteBuffer b) {
        b.putInt(MatchSetupFrame0Decoder.SEED_MAGIC);
        b.putInt(100);
        b.putInt(101);
        b.putInt(OBSIDIAN_ID);
        b.putInt(COBBLESTONE_ID);
        b.putInt(104);
        b.putInt(105);
        b.putInt(106);
    }

    private static void putLoadoutSection(ByteBuffer b) {
        b.putInt(MatchSetupFrame0Decoder.LOADOUT_MAGIC);
        b.putInt(4);
        putEntry(b, 261, 1, 465, ItemDict.FLAG_CROSSBOW | ItemDict.FLAG_CROSSBOW_CHARGED,
                Combat.USE_CROSSBOW, 1f, 4f, 0, 0, ItemDict.packRanged(0, 3, 0, false, 0), 0,
                ItemDict.EQUIP_NONE);
        putEntry(b, 279, 1, 2031, ItemDict.FLAG_SWORD, Combat.USE_NONE, 8f, 1.6f,
                ItemDict.packWeapon(1, 0, 0, 0), 0, 0,
                ItemDict.packTool(5, 0, ItemDict.TOOL_SWORD, false), ItemDict.EQUIP_NONE);
        putEntry(b, 89, 64, 0, ItemDict.FLAG_BLOCK | ItemDict.FLAG_GLOWSTONE, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, ItemDict.EQUIP_NONE);
        putEntry(b, 262, 64, 0, ItemDict.FLAG_ARROW_SPECIAL, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, ItemDict.EQUIP_NONE);

        int[] p0 = new int[ItemDict.SLOTS];
        int[] c0 = new int[ItemDict.SLOTS];
        p0[0] = SWORD_ENTRY;
        c0[0] = 1;
        p0[1] = GLOWSTONE_ENTRY;
        c0[1] = 32;
        p0[4] = CROSSBOW_ENTRY;
        c0[4] = 1;
        p0[5] = ARROW_ENTRY;
        c0[5] = 16;
        putSlots(b, p0, c0);

        int[] p1 = new int[ItemDict.SLOTS];
        int[] c1 = new int[ItemDict.SLOTS];
        p1[7] = CROSSBOW_ENTRY;
        c1[7] = 1;
        p1[8] = ARROW_ENTRY;
        c1[8] = 16;
        putSlots(b, p1, c1);
    }

    private static void putSlots(ByteBuffer b, int[] entries, int[] counts) {
        for (int i = 0; i < ItemDict.SLOTS; i++) {
            b.putShort((short) entries[i]);
            b.put((byte) counts[i]);
            b.putShort((short) 0);
        }
    }

    private static void putEntry(ByteBuffer b, int itemId, int maxStack, int maxDamage, int flags,
                                 int useKind, float meleeDamage, float meleeSpeed,
                                 int weaponEnchants, int maceInfo, int rangedInfo, int toolInfo,
                                 int equipSlot) {
        b.putInt(itemId);
        b.putInt(maxStack);
        b.putInt(maxDamage);
        b.putInt(flags);
        b.putInt(useKind);
        b.putFloat(meleeDamage);
        b.putFloat(meleeSpeed);
        b.put((byte) 0);
        b.putShort((short) weaponEnchants);
        b.putShort((short) maceInfo);
        b.putShort((short) rangedInfo);
        b.putShort((short) toolInfo);
        b.put((byte) 0);
        b.putFloat(0f);
        b.putShort((short) 0);
        b.put((byte) 0);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        b.put((byte) 0);
        b.putFloat(0f);
        b.putFloat(0f);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) 0);
        b.put((byte) equipSlot);
        b.putInt(-1);
    }

    private static void putContainerSection(ByteBuffer b) {
        b.putInt(1);
        b.putInt(5);
        for (int i = 0; i < Container.CELLS; i++) {
            b.putShort((short) (i == 0 ? GLOWSTONE_ENTRY : 0));
            b.put((byte) (i == 0 ? 12 : 0));
            b.putShort((short) 0);
        }
    }

    private static void putBlockSection(ByteBuffer b) {
        b.putInt(MatchSetupFrame0Decoder.BLOCK_MAGIC);
        b.putInt(2);
        putBlock(b, OBSIDIAN_ID, 50f, 1200f, OBSIDIAN_ID, 3, ItemDict.TOOL_PICKAXE, true);
        putBlock(b, COBBLESTONE_ID, 2f, 6f, COBBLESTONE_ID, 0, ItemDict.TOOL_PICKAXE, true);
    }

    private static void putBlock(ByteBuffer b, int id, float hardness, float blast, int drop,
                                 int harvestTier, int toolClass, boolean requiresTool) {
        b.putInt(id);
        b.putFloat(hardness);
        b.putFloat(blast);
        b.putInt(drop);
        b.put((byte) harvestTier);
        b.put((byte) toolClass);
        b.put((byte) (requiresTool ? 1 : 0));
    }

    private static void putPlayer(ByteBuffer b, double x, double y, double z, float health,
                                  float attackDamage, int pearls, int effects) {
        b.putDouble(x);
        b.putDouble(y);
        b.putDouble(z);
        b.putFloat(0f);
        b.putFloat(0f);
        b.putFloat(health);
        b.putFloat(attackDamage);
        b.putFloat(1.6f);
        b.putInt(0);
        b.putFloat(8.0f);
        b.putFloat(0f);
        b.putInt(pearls);
        b.putLong(0L);
        b.putLong(0L);
        putString(b, "Player");
        putString(b, "");
        putString(b, "");
        putString(b, "{}");
        b.putInt(0);
        b.putFloat(20.0f);
        b.putFloat(5.0f);
        b.putInt(0);
        b.putInt(0);
        b.putFloat(0f);
        for (int s = 0; s < 9; s++) {
            b.putFloat(s == 8 ? 9.0f : 7.0f - s);
            b.putFloat(1.6f);
            b.putInt(0);
            b.putInt(0);
            b.putInt(0);
            b.putFloat(0f);
            b.put((byte) 0);
            b.putInt(32);
            b.putInt(1);
            b.putInt(0);
            b.putInt(0);
        }
        b.putInt(0);
        b.put((byte) 0);
        for (int s = 0; s < 41; s++) {
            b.putInt(0);
        }
        b.putInt(effects);
        for (int e = 0; e < effects; e++) {
            b.putInt(Effects.SPEED);
            b.putInt(0);
            b.putInt(200);
        }
    }

    private static void putString(ByteBuffer b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        b.putShort((short) bytes.length);
        b.put(bytes);
    }

    private static void putItemIdSet(ByteBuffer b, int... ids) {
        b.putInt(ids.length);
        for (int id : ids) {
            b.putInt(id);
        }
    }
}
