package me.nootnoot.sim;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import me.nootnoot.sim.contract.MatchRules;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;

public final class MatchSetupFrame0Decoder {
    public static final double SETTLED_GROUNDED_VY = -0.0784;
    public static final int INVENTORY_SLOTS = 41;
    private static final int MAX_BOXES = 1 << 20;
    private static final int MAX_EFFECTS = 256;
    private static final int MAX_NBT = 1 << 20;
    private static final int MAX_RULE_IDS = 4096;

    public static final int LOADOUT_MAGIC = 0x504C4431;
    public static final int BLOCK_MAGIC = 0x42504431;
    public static final int SEED_MAGIC = 0x53454431;
    public static final int MAX_CONTAINERS = 64;

    public record Identity(UUID uuid, String name, String skinValue, String skinSignature,
                           String chatPrefixJson) {
    }

    public record Frame0(GameState state, double arenaGroundY, double[][] arenaBoxes,
                         byte[][][] inventory, int[] selectedSlot, Identity[] identities) {
    }

    public interface CrossbowProbe {
        boolean charged(byte[] itemNbt);
    }

    private MatchSetupFrame0Decoder() {
    }

    public static Frame0 decode(byte[] wire) {
        return decode(wire, null);
    }

    public static Frame0 decode(byte[] wire, CrossbowProbe probe) {
        ByteBuffer b = ByteBuffer.wrap(wire);
        b.getLong();
        b.get();
        skip(b, b.getShort() & 0xFFFF);
        skip(b, b.getShort() & 0xFFFF);
        b.getInt();
        int rounds = b.getInt();
        MatchRules rules = MatchRules.read(b);
        int[] breakableItemIds = readItemIdSet(b);
        int[] placeableItemIds = readItemIdSet(b);

        double groundY = b.getDouble();
        int boxCount = b.getInt();
        if (boxCount < 0 || boxCount > MAX_BOXES) {
            throw new IllegalArgumentException("box count out of bounds: " + boxCount);
        }
        require(b, boxCount * 6 * 8, "arena boxes");
        double[][] boxes = new double[boxCount][6];
        for (int i = 0; i < boxCount; i++) {
            for (int k = 0; k < 6; k++) {
                boxes[i][k] = b.getDouble();
            }
        }
        double playCenterX = b.getDouble();
        double playCenterZ = b.getDouble();
        double playRadius = b.getDouble();
        boolean playCircular = b.get() != 0;

        GameState state = new GameState();
        state.roundsTarget = rounds > 0 ? rounds : 1;
        rules.applyTo(state);
        state.breakableItemIds = breakableItemIds;
        state.placeableItemIds = placeableItemIds;
        state.playCenterX = playCenterX;
        state.playCenterZ = playCenterZ;
        state.playRadius = playRadius;
        state.playCircular = playCircular;

        byte[][][] inventory = new byte[2][INVENTORY_SLOTS][];
        int[] selectedSlot = new int[2];
        Identity[] identities = new Identity[2];
        for (int i = 0; i < 2; i++) {
            PlayerState p = state.players[i];
            p.x = b.getDouble();
            p.y = b.getDouble();
            p.z = b.getDouble();
            p.yaw = b.getFloat();
            p.pitch = b.getFloat();
            p.health = b.getFloat();
            p.maxHealth = p.health;
            p.attackDamage = b.getFloat();
            p.attackSpeed = b.getFloat();
            p.knockbackLevel = b.getInt();
            p.armor = b.getFloat();
            p.kbResistance = b.getFloat();
            p.pearls = b.getInt();
            p.onGround = true;
            p.vy = SETTLED_GROUNDED_VY;
            p.attackTicker = 100;

            UUID uuid = new UUID(b.getLong(), b.getLong());
            String name = readString(b);
            String skinValue = readString(b);
            String skinSignature = readString(b);
            String chatPrefixJson = readString(b);
            identities[i] = new Identity(uuid, name, skinValue, skinSignature, chatPrefixJson);
            selectedSlot[i] = b.getInt();
            p.food = b.getFloat();
            p.saturation = b.getFloat();
            p.protection = b.getInt();
            p.blastProtection = b.getInt();
            p.armorToughness = b.getFloat();
            skip(b, 9 * LEGACY_HOTBAR_BYTES);
            b.getInt();
            b.get();
            for (int s = 0; s < INVENTORY_SLOTS; s++) {
                int nbtLen = b.getInt();
                if (nbtLen < 0 || nbtLen > MAX_NBT) {
                    throw new IllegalArgumentException("nbt length out of bounds: " + nbtLen);
                }
                require(b, nbtLen, "inventory slot nbt");
                byte[] nbt = new byte[nbtLen];
                b.get(nbt);
                inventory[i][s] = nbt;
            }
            int effCount = b.getInt();
            if (effCount < 0 || effCount > MAX_EFFECTS) {
                throw new IllegalArgumentException("effect count out of bounds: " + effCount);
            }
            for (int e = 0; e < effCount; e++) {
                int id = b.getInt();
                int amp = b.getInt();
                int dur = b.getInt();
                Combat.applyEffect(p, id, amp, dur);
            }
        }

        if (b.remaining() < 13) {
            throw new IllegalArgumentException("setup blob is missing its native-terrain trailer");
        }
        b.get();
        b.getInt();
        b.getInt();
        b.getInt();

        readLoadoutSection(b, state);
        readContainerSection(b, state);
        readBlockSection(b, state);
        readHostSection(b, state);
        readSeedSection(b, state);

        LoadoutCaps.seal(state);
        state.roundInitial = new PlayerState[]{state.players[0].copy(), state.players[1].copy()};
        state.roundResetCountdown = Simulation.ROUND_COUNTDOWN_TICKS;
        return new Frame0(state, groundY, boxes, inventory, selectedSlot, identities);
    }

    private static final int LEGACY_HOTBAR_BYTES = 41;

    private static void readLoadoutSection(ByteBuffer b, GameState state) {
        require(b, 8, "loadout section header");
        int magic = b.getInt();
        if (magic != LOADOUT_MAGIC) {
            throw new IllegalArgumentException("bad loadout section magic: " + magic);
        }
        int entryCount = b.getInt();
        if (entryCount < 0 || entryCount > ItemDict.MAX_ENTRIES) {
            throw new IllegalArgumentException("entry count out of bounds: " + entryCount);
        }
        require(b, entryCount * ItemDict.ENTRY_BYTES, "loadout entries");
        ItemDict.Builder builder = new ItemDict.Builder();
        for (int i = 0; i < entryCount; i++) {
            int itemId = b.getInt();
            int maxStack = b.getInt();
            int maxDamage = b.getInt();
            int flags = b.getInt();
            int useKind = b.getInt();
            float meleeDamage = b.getFloat();
            float meleeSpeed = b.getFloat();
            int knockback = b.get() & 0xFF;
            int weaponEnchants = b.getShort() & 0xFFFF;
            int maceInfo = b.getShort() & 0xFFFF;
            int rangedInfo = b.getShort() & 0xFFFF;
            int toolInfo = b.getShort() & 0xFFFF;
            int foodNutrition = b.get() & 0xFF;
            float foodSaturation = b.getFloat();
            int foodEatTicks = b.getShort() & 0xFFFF;
            int fireworkFlight = b.get() & 0xFF;
            int effect0 = b.getInt();
            int effect1 = b.getInt();
            int effect2 = b.getInt();
            int effect3 = b.getInt();
            int armorPoints = b.get() & 0xFF;
            float armorToughness = b.getFloat();
            float armorKbResistance = b.getFloat();
            int armorProtection = b.get() & 0xFF;
            int armorBlastProtection = b.get() & 0xFF;
            int armorProjectileProtection = b.get() & 0xFF;
            int armorFireProtection = b.get() & 0xFF;
            int armorFeatherFalling = b.get() & 0xFF;
            int equipSlot = b.get() & 0xFF;
            int containerSeed = b.getInt();
            builder.add(itemId, maxStack, maxDamage, flags, useKind, meleeDamage, meleeSpeed,
                    knockback, weaponEnchants, maceInfo, rangedInfo, toolInfo,
                    foodNutrition, foodSaturation, foodEatTicks, fireworkFlight,
                    effect0, effect1, effect2, effect3,
                    armorPoints, armorToughness, armorKbResistance,
                    armorProtection, armorBlastProtection, armorProjectileProtection,
                    armorFireProtection, armorFeatherFalling, equipSlot, containerSeed);
        }
        ItemDict dict = builder.build();
        state.dict = dict;

        require(b, 2 * ItemDict.SLOTS * ItemDict.SLOT_BYTES, "loadout slot tables");
        for (int i = 0; i < 2; i++) {
            PlayerState p = state.players[i];
            for (int slot = 0; slot < ItemDict.SLOTS; slot++) {
                int entry = b.getShort() & 0xFFFF;
                int count = b.get() & 0xFF;
                int damage = b.getShort() & 0xFFFF;
                if (!dict.valid(entry) || count <= 0) {
                    p.slotEntry[slot] = ItemDict.NONE;
                    p.slotCount[slot] = 0;
                    p.slotDamage[slot] = 0;
                    continue;
                }
                p.slotEntry[slot] = entry;
                p.slotCount[slot] = Math.min(count, dict.maxStack(entry));
                p.slotDamage[slot] = Math.min(damage, Math.max(0, dict.maxDamage(entry) - 1));
                p.slotCrossbowLoaded[slot] = dict.crossbowCharged(entry);
                p.slotCrossbowConsumed[slot] = dict.crossbowCharged(entry);
            }
            seedChargedCrossbows(state, p);
        }
    }

    public static void seedChargedCrossbows(GameState state, PlayerState p) {
        int arrowSlot = Loadout.activeArrowSlot(state, p);
        int arrowEntry = arrowSlot >= 0 ? Loadout.entryAt(p, arrowSlot) : ItemDict.NONE;
        for (int slot = 0; slot < ItemDict.SLOTS; slot++) {
            p.slotCrossbowEntry[slot] = p.slotCrossbowLoaded[slot] ? arrowEntry : ItemDict.NONE;
        }
    }

    private static void readContainerSection(ByteBuffer b, GameState state) {
        require(b, 4, "container section header");
        int containerCount = b.getInt();
        if (containerCount < 0 || containerCount > MAX_CONTAINERS) {
            throw new IllegalArgumentException("container count out of bounds: " + containerCount);
        }
        require(b, containerCount * (4 + Container.CELLS * ItemDict.SLOT_BYTES), "containers");
        int highest = 0;
        for (int i = 0; i < containerCount; i++) {
            int id = b.getInt();
            Container c = new Container();
            for (int cell = 0; cell < Container.CELLS; cell++) {
                int entry = b.getShort() & 0xFFFF;
                int count = b.get() & 0xFF;
                int damage = b.getShort() & 0xFFFF;
                if (!state.dict.valid(entry) || count <= 0) {
                    continue;
                }
                c.entry[cell] = entry;
                c.count[cell] = Math.min(count, state.dict.maxStack(entry));
                c.damage[cell] = Math.min(damage, Math.max(0, state.dict.maxDamage(entry) - 1));
            }
            state.containers.put(id, c);
            highest = Math.max(highest, id);
        }
        state.nextContainerId = highest + 1;
        for (java.util.Map.Entry<Integer, Container> e : state.containers.entrySet()) {
            state.roundInitialContainers.put(e.getKey(), e.getValue().copy());
        }
    }

    private static void readBlockSection(ByteBuffer b, GameState state) {
        require(b, 8, "block section header");
        int magic = b.getInt();
        if (magic != BLOCK_MAGIC) {
            throw new IllegalArgumentException("bad block section magic: " + magic);
        }
        int blockCount = b.getInt();
        if (blockCount < 0 || blockCount > BlockProps.MAX_ROWS) {
            throw new IllegalArgumentException("block count out of bounds: " + blockCount);
        }
        require(b, blockCount * BlockProps.ROW_BYTES, "block rows");
        BlockProps.Builder builder = new BlockProps.Builder();
        for (int i = 0; i < blockCount; i++) {
            int blockItemId = b.getInt();
            float hardness = b.getFloat();
            float blastResistance = b.getFloat();
            int dropItemId = b.getInt();
            int harvestTier = b.get();
            int toolClass = b.get() & 0xFF;
            boolean requiresTool = b.get() != 0;
            builder.add(blockItemId, hardness, blastResistance, dropItemId, harvestTier, toolClass,
                    requiresTool);
        }
        state.blockProps = builder.build();
    }

    private static void readHostSection(ByteBuffer b, GameState state) {
        require(b, 2, "host-kind section");
        state.edgeHosted[0] = b.get() != 0;
        state.edgeHosted[1] = b.get() != 0;
    }

    private static void readSeedSection(ByteBuffer b, GameState state) {
        require(b, 32, "registry seed section");
        int magic = b.getInt();
        if (magic != SEED_MAGIC) {
            throw new IllegalArgumentException("bad seed section magic: " + magic);
        }
        state.cobwebItemId = b.getInt();
        state.stringItemId = b.getInt();
        state.obsidianItemId = b.getInt();
        state.cobblestoneItemId = b.getInt();
        state.mudItemId = b.getInt();
        state.glowstoneItemId = b.getInt();
        state.glowstoneDustItemId = b.getInt();
    }

    private static void require(ByteBuffer b, int n, String what) {
        if (n < 0 || b.remaining() < n) {
            throw new IllegalArgumentException("setup blob truncated reading " + what);
        }
    }

    private static void skip(ByteBuffer b, int n) {
        if (n < 0 || n > b.remaining()) {
            throw new IllegalArgumentException("skip out of bounds: " + n);
        }
        b.position(b.position() + n);
    }

    private static String readString(ByteBuffer b) {
        int len = b.getShort() & 0xFFFF;
        if (len > b.remaining()) {
            throw new IllegalArgumentException("string length out of bounds: " + len);
        }
        byte[] data = new byte[len];
        b.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    public static int[] readItemIdSet(ByteBuffer b) {
        require(b, 4, "block rule list header");
        int count = b.getInt();
        if (count < 0 || count > MAX_RULE_IDS) {
            throw new IllegalArgumentException("block rule id count out of bounds: " + count);
        }
        require(b, count * 4, "block rule list");
        int[] ids = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = b.getInt();
        }
        return normalizeItemIds(ids);
    }

    public static int[] normalizeItemIds(int[] ids) {
        if (ids == null || ids.length == 0) {
            return GameState.NO_ITEM_IDS;
        }
        int[] sorted = ids.clone();
        Arrays.sort(sorted);
        int kept = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) {
                sorted[kept++] = sorted[i];
            }
        }
        return kept == sorted.length ? sorted : Arrays.copyOf(sorted, kept);
    }
}
