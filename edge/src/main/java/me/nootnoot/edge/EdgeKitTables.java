package me.nootnoot.edge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.MatchSetupFrame0Decoder;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;

public final class EdgeKitTables {

    private static final int MAX_ITEM_EFFECTS = 4;

    private static final int TIER_NONE = 0;
    private static final int TIER_WOOD = 1;
    private static final int TIER_STONE = 2;
    private static final int TIER_IRON = 3;
    private static final int TIER_DIAMOND = 4;
    private static final int TIER_NETHERITE = 5;
    private static final int TIER_GOLD = 6;

    private static final int GAPPLE_REGEN_AMPLIFIER = 1;
    private static final int GAPPLE_REGEN_TICKS = 100;
    private static final int NOTCH_REGEN_TICKS = 400;
    private static final int GAPPLE_ABSORPTION_TICKS = 2400;
    private static final int NOTCH_ABSORPTION_AMPLIFIER = 3;
    private static final int NOTCH_EXTRA_TICKS = 6000;

    private static final float DIAMOND_PIECE_TOUGHNESS = 2f;
    private static final float NETHERITE_PIECE_TOUGHNESS = 3f;
    private static final float NETHERITE_PIECE_KB_RESISTANCE = 0.1f;

    private static volatile BlockProps blockProps;

    private EdgeKitTables() {
    }

    public static GameState build(EdgeDemoKits.Kit kit, boolean edgeHosted0, boolean edgeHosted1) {
        if (!EdgeItemIds.ready()) {
            throw new IllegalStateException("the canonical item table has not loaded, so every item"
                    + " id would be 0 and the dev kit would be a dictionary of nothing");
        }
        Tables tables = new Tables();
        int[][] slots0 = tables.read(kit);
        int[][] slots1 = tables.read(kit);
        tables.seedPlainArrow();
        tables.seedBuckets();

        GameState state = new GameState();
        state.dict = tables.dict.build();
        state.blockProps = blockProps();
        state.edgeHosted[0] = edgeHosted0;
        state.edgeHosted[1] = edgeHosted1;
        state.cobwebItemId = EdgeItemIds.canonical(Material.COBWEB);
        state.stringItemId = EdgeItemIds.canonical(Material.STRING);
        state.obsidianItemId = EdgeItemIds.canonical(Material.OBSIDIAN);
        state.cobblestoneItemId = EdgeItemIds.canonical(Material.COBBLESTONE);
        state.mudItemId = EdgeItemIds.canonical(Material.MUD);
        state.glowstoneItemId = EdgeItemIds.canonical(Material.GLOWSTONE);
        state.glowstoneDustItemId = EdgeItemIds.canonical(Material.GLOWSTONE_DUST);

        apply(state.players[0], slots0);
        apply(state.players[1], slots1);
        state.containers.putAll(tables.containers);
        state.nextContainerId = tables.nextContainerId;
        return state;
    }

    public static String describe(GameState state) {
        return "entries=" + state.dict.size() + " containers=" + state.containers.size()
                + " blocks=" + state.blockProps.size()
                + " dict-digest=" + Long.toHexString(state.dict.digest());
    }

    private static void apply(PlayerState p, int[][] slots) {
        for (int s = 0; s < ItemDict.SLOTS; s++) {
            p.slotEntry[s] = slots[s][0];
            p.slotCount[s] = slots[s][1];
            p.slotDamage[s] = slots[s][2];
        }
    }

    private static final class Tables {

        private final ItemDict.Builder dict = new ItemDict.Builder();
        private final Map<String, Integer> byKey = new HashMap<>();
        private final Map<Integer, Container> containers = new LinkedHashMap<>();
        private int nextContainerId = 1;
        private boolean anyRanged;
        private boolean anyPlainArrow;
        private boolean anyBucket;
        private boolean anyEmptyBucket;
        private boolean anyWaterBucket;
        private boolean anyLavaBucket;

        private int[][] read(EdgeDemoKits.Kit kit) {
            int[][] out = new int[ItemDict.SLOTS][3];
            ItemStack[] items = kit.items();
            for (int s = 0; s < ItemDict.SLOTS; s++) {
                ItemStack stack = s < items.length ? items[s] : null;
                if (empty(stack)) {
                    continue;
                }
                int entry = intern(stack);
                if (entry == ItemDict.NONE) {
                    continue;
                }
                out[s][0] = entry;
                out[s][1] = countOf(stack);
                out[s][2] = damageOf(stack);
            }
            return out;
        }

        private void seedPlainArrow() {
            if (anyRanged && !anyPlainArrow) {
                intern(new ItemStack(Material.ARROW));
            }
        }

        private void seedBuckets() {
            if (!anyBucket) {
                return;
            }
            if (!anyEmptyBucket) {
                intern(new ItemStack(Material.BUCKET));
            }
            if (!anyWaterBucket) {
                intern(new ItemStack(Material.WATER_BUCKET));
            }
            if (!anyLavaBucket) {
                intern(new ItemStack(Material.LAVA_BUCKET));
            }
        }

        private int intern(ItemStack stack) {
            Material type = stack.getType();
            int itemId = EdgeItemIds.canonical(type);
            if (itemId == 0) {
                return ItemDict.NONE;
            }
            EdgeDemoKits.Stats stats = EdgeDemoKits.stats(stack);
            int useKind = EdgeHeldItems.useKindOf(stack);
            int flags = flagsOf(stack, type);
            if (useKind == Combat.USE_FOOD && stats.alwaysEdible()) {
                flags |= ItemDict.FLAG_ALWAYS_EDIBLE;
            }
            if (EdgeHeldItems.drinkable(type)) {
                flags |= ItemDict.FLAG_ALWAYS_EDIBLE;
            }
            if ((flags & (ItemDict.FLAG_BOW | ItemDict.FLAG_CROSSBOW)) != 0) {
                anyRanged = true;
            }
            if ((flags & ItemDict.FLAG_ARROW_PLAIN) != 0) {
                anyPlainArrow = true;
            }
            if ((flags & ItemDict.FLAG_BUCKET_EMPTY) != 0) {
                anyBucket = true;
                anyEmptyBucket = true;
            }
            if ((flags & ItemDict.FLAG_BUCKET_WATER) != 0) {
                anyBucket = true;
                anyWaterBucket = true;
            }
            if ((flags & ItemDict.FLAG_BUCKET_LAVA) != 0) {
                anyBucket = true;
                anyLavaBucket = true;
            }

            int equip = equipSlotOf(type);
            boolean wearable = equip != ItemDict.EQUIP_NONE && equip != ItemDict.EQUIP_OFF_HAND;
            int maxStack = Math.max(1, Math.min(ItemDict.MAX_STACK, type.getMaxStackSize()));
            int maxDamage = Math.max(0, type.getMaxDurability());
            int knockback = level(stack, Enchantment.KNOCKBACK);
            int weaponEnchants = ItemDict.packWeapon(level(stack, Enchantment.SHARPNESS),
                    level(stack, Enchantment.FIRE_ASPECT), level(stack, Enchantment.PUNCH),
                    level(stack, Enchantment.FLAME));
            int maceInfo = ItemDict.packMace(type == Material.MACE,
                    level(stack, Enchantment.WIND_BURST), level(stack, Enchantment.DENSITY),
                    level(stack, Enchantment.BREACH));
            int rangedInfo = ItemDict.packRanged(level(stack, Enchantment.POWER),
                    level(stack, Enchantment.QUICK_CHARGE), level(stack, Enchantment.MULTISHOT),
                    level(stack, Enchantment.INFINITY) > 0, level(stack, Enchantment.PIERCING));
            int toolInfo = ItemDict.packTool(toolTier(type), level(stack, Enchantment.EFFICIENCY),
                    toolClassOf(type), level(stack, Enchantment.AQUA_AFFINITY) > 0);
            int armorPoints = wearable ? armorPointsOf(type) : 0;
            float armorToughness = wearable ? toughnessOf(type) : 0f;
            float armorKbResistance = wearable ? kbResistanceOf(type) : 0f;
            int armorProtection = level(stack, Enchantment.PROTECTION);
            int armorBlastProtection = level(stack, Enchantment.BLAST_PROTECTION);
            int armorProjectileProtection = level(stack, Enchantment.PROJECTILE_PROTECTION);
            int armorFireProtection = level(stack, Enchantment.FIRE_PROTECTION);
            int armorFeatherFalling = level(stack, Enchantment.FEATHER_FALLING);
            int[] effects = effectsOf(stack, type);

            int containerSeed = -1;
            if ((flags & ItemDict.FLAG_SHULKER) != 0) {
                containerSeed = allocateContainer(stack);
            }

            String key = itemId + "|" + maxStack + "|" + maxDamage + "|" + flags + "|" + useKind
                    + "|" + Float.floatToIntBits(stats.damage())
                    + "|" + Float.floatToIntBits(stats.speed())
                    + "|" + knockback + "|" + weaponEnchants + "|" + maceInfo + "|" + rangedInfo
                    + "|" + toolInfo + "|" + stats.nutrition()
                    + "|" + Float.floatToIntBits(stats.saturation())
                    + "|" + stats.eatTicks() + "|" + stats.fireworkFlight()
                    + "|" + effects[0] + "|" + effects[1] + "|" + effects[2] + "|" + effects[3]
                    + "|" + armorPoints + "|" + Float.floatToIntBits(armorToughness)
                    + "|" + Float.floatToIntBits(armorKbResistance)
                    + "|" + armorProtection + "|" + armorBlastProtection
                    + "|" + armorProjectileProtection + "|" + armorFireProtection
                    + "|" + armorFeatherFalling + "|" + equip;
            if (containerSeed < 0) {
                Integer existing = byKey.get(key);
                if (existing != null) {
                    return existing;
                }
            }

            int index = dict.add(itemId, maxStack, maxDamage, flags, useKind,
                    stats.damage(), stats.speed(), knockback, weaponEnchants, maceInfo, rangedInfo,
                    toolInfo, stats.nutrition(), stats.saturation(), stats.eatTicks(),
                    stats.fireworkFlight(), effects[0], effects[1], effects[2], effects[3],
                    armorPoints, armorToughness, armorKbResistance, armorProtection,
                    armorBlastProtection, armorProjectileProtection, armorFireProtection,
                    armorFeatherFalling, equip, containerSeed);
            if (containerSeed < 0) {
                byKey.put(key, index);
            }
            return index;
        }

        private int allocateContainer(ItemStack stack) {
            if (containers.size() >= MatchSetupFrame0Decoder.MAX_CONTAINERS) {
                return -1;
            }
            Container c = new Container();
            ItemStack[] inside = shulkerContents(stack);
            if (inside != null) {
                for (int cell = 0; cell < Container.CELLS && cell < inside.length; cell++) {
                    ItemStack in = inside[cell];
                    if (empty(in)) {
                        continue;
                    }
                    int entry = intern(in);
                    if (entry == ItemDict.NONE) {
                        continue;
                    }
                    c.entry[cell] = entry;
                    c.count[cell] = countOf(in);
                    c.damage[cell] = damageOf(in);
                }
            }
            int id = nextContainerId++;
            containers.put(id, c);
            return id;
        }
    }

    private static BlockProps blockProps() {
        BlockProps cached = blockProps;
        if (cached != null) {
            return cached;
        }
        Set<Integer> seen = new HashSet<>();
        BlockProps.Builder builder = new BlockProps.Builder();
        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isBlock() || material.isAir()) {
                continue;
            }
            int id = EdgeItemIds.canonical(material);
            if (id == 0 || seen.contains(id)) {
                continue;
            }
            if (seen.size() >= BlockProps.MAX_ROWS) {
                break;
            }
            seen.add(id);
            int harvestTier = harvestTier(material);
            builder.add(id, hardness(material), blastResistance(material), dropOf(material),
                    harvestTier, blockToolClass(material), harvestTier >= 0);
        }
        cached = builder.build();
        blockProps = cached;
        return cached;
    }

    private static int dropOf(Material block) {
        if (block == Material.FIRE || block == Material.SOUL_FIRE) {
            return 0;
        }
        return EdgeItemIds.canonical(EdgeBlockLoot.loot(block));
    }

    private static float hardness(Material block) {
        try {
            return block.getHardness();
        } catch (RuntimeException ex) {
            return BlockProps.DEFAULT_HARDNESS;
        }
    }

    private static float blastResistance(Material block) {
        try {
            return block.getBlastResistance();
        } catch (RuntimeException ex) {
            return 6.0f;
        }
    }

    private static int blockToolClass(Material block) {
        if (Tag.MINEABLE_PICKAXE.isTagged(block)) {
            return ItemDict.TOOL_PICKAXE;
        }
        if (Tag.MINEABLE_AXE.isTagged(block)) {
            return ItemDict.TOOL_AXE;
        }
        if (Tag.MINEABLE_SHOVEL.isTagged(block)) {
            return ItemDict.TOOL_SHOVEL;
        }
        if (Tag.MINEABLE_HOE.isTagged(block)) {
            return ItemDict.TOOL_HOE;
        }
        return ItemDict.TOOL_NONE;
    }

    private static int harvestTier(Material block) {
        if (!Tag.MINEABLE_PICKAXE.isTagged(block)) {
            return -1;
        }
        if (Tag.NEEDS_DIAMOND_TOOL.isTagged(block)) {
            return TIER_DIAMOND;
        }
        if (Tag.NEEDS_IRON_TOOL.isTagged(block)) {
            return TIER_IRON;
        }
        if (Tag.NEEDS_STONE_TOOL.isTagged(block)) {
            return TIER_STONE;
        }
        return TIER_WOOD;
    }

    private static int flagsOf(ItemStack stack, Material type) {
        int flags = 0;
        if (type.isBlock()) {
            flags |= ItemDict.FLAG_BLOCK;
        }
        if (type == Material.ARROW) {
            flags |= ItemDict.FLAG_ARROW_PLAIN;
        }
        if (type == Material.SPECTRAL_ARROW || type == Material.TIPPED_ARROW) {
            flags |= ItemDict.FLAG_ARROW_SPECIAL;
        }
        if (Tag.ITEMS_AXES.isTagged(type)) {
            flags |= ItemDict.FLAG_AXE;
        }
        if (Tag.ITEMS_SWORDS.isTagged(type)) {
            flags |= ItemDict.FLAG_SWORD;
        }
        if (type == Material.SHEARS) {
            flags |= ItemDict.FLAG_SHEARS;
        }
        if (type == Material.BOW) {
            flags |= ItemDict.FLAG_BOW;
        }
        if (type == Material.CROSSBOW) {
            flags |= ItemDict.FLAG_CROSSBOW;
            if (EdgeHeldItems.crossbowCharged(stack)) {
                flags |= ItemDict.FLAG_CROSSBOW_CHARGED;
            }
        }
        if (type == Material.MACE) {
            flags |= ItemDict.FLAG_MACE;
        }
        if (type == Material.TOTEM_OF_UNDYING) {
            flags |= ItemDict.FLAG_TOTEM;
        }
        if (type == Material.ELYTRA) {
            flags |= ItemDict.FLAG_ELYTRA;
        }
        if (type == Material.SHIELD) {
            flags |= ItemDict.FLAG_SHIELD;
        }
        if (type == Material.BUCKET) {
            flags |= ItemDict.FLAG_BUCKET_EMPTY;
        }
        if (type == Material.WATER_BUCKET) {
            flags |= ItemDict.FLAG_BUCKET_WATER;
        }
        if (type == Material.LAVA_BUCKET) {
            flags |= ItemDict.FLAG_BUCKET_LAVA;
        }
        if (type == Material.END_CRYSTAL) {
            flags |= ItemDict.FLAG_END_CRYSTAL;
        }
        if (type == Material.RESPAWN_ANCHOR) {
            flags |= ItemDict.FLAG_RESPAWN_ANCHOR;
        }
        if (type == Material.GLOWSTONE) {
            flags |= ItemDict.FLAG_GLOWSTONE;
        }
        if (type == Material.ENDER_CHEST) {
            flags |= ItemDict.FLAG_ENDER_CHEST;
        }
        if (Tag.SHULKER_BOXES.isTagged(type)) {
            flags |= ItemDict.FLAG_SHULKER;
        }
        return flags;
    }

    private static int equipSlotOf(Material type) {
        if (type == Material.ELYTRA) {
            return ItemDict.EQUIP_CHEST;
        }
        if (type == Material.TURTLE_HELMET || type == Material.CARVED_PUMPKIN) {
            return ItemDict.EQUIP_HEAD;
        }
        String name = type.name();
        if (name.endsWith("_HELMET")) {
            return ItemDict.EQUIP_HEAD;
        }
        if (name.endsWith("_CHESTPLATE")) {
            return ItemDict.EQUIP_CHEST;
        }
        if (name.endsWith("_LEGGINGS")) {
            return ItemDict.EQUIP_LEGS;
        }
        if (name.endsWith("_BOOTS")) {
            return ItemDict.EQUIP_FEET;
        }
        return ItemDict.EQUIP_NONE;
    }

    private static int toolClassOf(Material type) {
        if (Tag.ITEMS_PICKAXES.isTagged(type)) {
            return ItemDict.TOOL_PICKAXE;
        }
        if (Tag.ITEMS_AXES.isTagged(type)) {
            return ItemDict.TOOL_AXE;
        }
        if (Tag.ITEMS_SHOVELS.isTagged(type)) {
            return ItemDict.TOOL_SHOVEL;
        }
        if (Tag.ITEMS_HOES.isTagged(type)) {
            return ItemDict.TOOL_HOE;
        }
        if (type == Material.SHEARS) {
            return ItemDict.TOOL_SHEARS;
        }
        if (Tag.ITEMS_SWORDS.isTagged(type)) {
            return ItemDict.TOOL_SWORD;
        }
        return ItemDict.TOOL_NONE;
    }

    private static int toolTier(Material type) {
        String name = type.name();
        if (name.startsWith("NETHERITE_")) {
            return TIER_NETHERITE;
        }
        if (name.startsWith("DIAMOND_")) {
            return TIER_DIAMOND;
        }
        if (name.startsWith("GOLDEN_")) {
            return TIER_GOLD;
        }
        if (name.startsWith("IRON_")) {
            return TIER_IRON;
        }
        if (name.startsWith("STONE_")) {
            return TIER_STONE;
        }
        if (name.startsWith("WOODEN_")) {
            return TIER_WOOD;
        }
        if (type == Material.SHEARS) {
            return TIER_IRON;
        }
        return TIER_NONE;
    }

    private static int armorPointsOf(Material type) {
        return switch (type) {
            case LEATHER_BOOTS, LEATHER_HELMET, GOLDEN_BOOTS, CHAINMAIL_BOOTS -> 1;
            case LEATHER_LEGGINGS, GOLDEN_HELMET, CHAINMAIL_HELMET, IRON_BOOTS, IRON_HELMET,
                 TURTLE_HELMET -> 2;
            case LEATHER_CHESTPLATE, GOLDEN_LEGGINGS, DIAMOND_BOOTS, DIAMOND_HELMET,
                 NETHERITE_BOOTS, NETHERITE_HELMET -> 3;
            case CHAINMAIL_LEGGINGS -> 4;
            case GOLDEN_CHESTPLATE, CHAINMAIL_CHESTPLATE, IRON_LEGGINGS -> 5;
            case IRON_CHESTPLATE, DIAMOND_LEGGINGS, NETHERITE_LEGGINGS -> 6;
            case DIAMOND_CHESTPLATE, NETHERITE_CHESTPLATE -> 8;
            default -> 0;
        };
    }

    private static float toughnessOf(Material type) {
        String name = type.name();
        if (name.startsWith("DIAMOND_")) {
            return DIAMOND_PIECE_TOUGHNESS;
        }
        if (name.startsWith("NETHERITE_")) {
            return NETHERITE_PIECE_TOUGHNESS;
        }
        return 0f;
    }

    private static float kbResistanceOf(Material type) {
        return type.name().startsWith("NETHERITE_") ? NETHERITE_PIECE_KB_RESISTANCE : 0f;
    }

    private static int[] effectsOf(ItemStack stack, Material type) {
        int[] packed = new int[MAX_ITEM_EFFECTS];
        if (type == Material.GOLDEN_APPLE || type == Material.ENCHANTED_GOLDEN_APPLE) {
            boolean notch = type == Material.ENCHANTED_GOLDEN_APPLE;
            packed[0] = ItemDict.packEffect(Effects.REGENERATION, GAPPLE_REGEN_AMPLIFIER,
                    notch ? NOTCH_REGEN_TICKS : GAPPLE_REGEN_TICKS);
            packed[1] = ItemDict.packEffect(Effects.ABSORPTION,
                    notch ? NOTCH_ABSORPTION_AMPLIFIER : 0, GAPPLE_ABSORPTION_TICKS);
            if (notch) {
                packed[2] = ItemDict.packEffect(Effects.RESISTANCE, 0, NOTCH_EXTRA_TICKS);
                packed[3] = ItemDict.packEffect(Effects.FIRE_RESISTANCE, 0, NOTCH_EXTRA_TICKS);
            }
            return packed;
        }
        List<PotionEffect> all;
        try {
            if (!(stack.getItemMeta() instanceof PotionMeta meta)) {
                return packed;
            }
            all = meta.getAllEffects();
        } catch (RuntimeException ex) {
            return packed;
        }
        int i = 0;
        for (PotionEffect effect : all) {
            if (i >= MAX_ITEM_EFFECTS) {
                break;
            }
            int id = EdgeHeldItems.simEffect(effect.getType());
            if (id == Effects.NONE) {
                continue;
            }
            packed[i++] = ItemDict.packEffect(id, Math.max(0, effect.getAmplifier()),
                    Math.max(0, Math.min(0xFFFF, effect.getDuration())));
        }
        return packed;
    }

    private static ItemStack[] shulkerContents(ItemStack stack) {
        try {
            if (stack.getItemMeta() instanceof BlockStateMeta meta && meta.hasBlockState()
                    && meta.getBlockState() instanceof ShulkerBox box) {
                return box.getInventory().getContents();
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static int level(ItemStack stack, Enchantment enchantment) {
        try {
            return Math.max(0, stack.getEnchantmentLevel(enchantment));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static int countOf(ItemStack stack) {
        return Math.max(1, Math.min(ItemDict.MAX_STACK, stack.getAmount()));
    }

    private static int damageOf(ItemStack stack) {
        try {
            if (stack.getItemMeta() instanceof Damageable damageable) {
                return Math.max(0, Math.min(0xFFFF, damageable.getDamage()));
            }
        } catch (RuntimeException ignored) {
            return 0;
        }
        return 0;
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }
}
