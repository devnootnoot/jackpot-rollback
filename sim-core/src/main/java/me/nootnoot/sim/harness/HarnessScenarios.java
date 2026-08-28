package me.nootnoot.sim.harness;

import me.nootnoot.sim.ArenaCodec;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;

public final class HarnessScenarios {
    private HarnessScenarios() {
    }

    public static GameState duel(Arena arena) {
        GameState g = new GameState();

        PlayerState a = g.players[0];
        a.x = 0.0;
        a.y = arena.groundY;
        a.z = 0.0;
        a.yaw = 0f;
        a.health = 20f;
        a.onGround = true;
        a.vy = -0.0784;
        a.attackTicker = 100;
        a.pearls = 16;

        PlayerState b = g.players[1];
        b.x = 3.0;
        b.y = arena.groundY;
        b.z = 0.0;
        b.yaw = 180f;
        b.health = 20f;
        b.onGround = true;
        b.vy = -0.0784;
        b.attackTicker = 100;
        b.pearls = 16;

        seedKit(g);
        return g;
    }

    public static final int SWORD_ENTRY = 1;
    public static final int PEARL_ENTRY = 2;
    public static final int APPLE_ENTRY = 3;
    public static final int BOOTS_ENTRY = 4;
    public static final int LEGGINGS_ENTRY = 5;
    public static final int CHESTPLATE_ENTRY = 6;
    public static final int HELMET_ENTRY = 7;

    private static void seedKit(GameState g) {
        ItemDict.Builder d = new ItemDict.Builder();
        d.add(1001, 1, 2031, ItemDict.FLAG_SWORD, 0, 8.0f, 1.6f, 0,
                ItemDict.packWeapon(0, 0, 0, 0), 0, 0,
                ItemDict.packTool(Loadout.TIER_NETHERITE, 0, ItemDict.TOOL_SWORD, false),
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        d.add(1002, 16, 0, 0, 1, 1.0f, 4.0f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        d.add(1003, 64, 0, ItemDict.FLAG_ALWAYS_EDIBLE, 2, 1.0f, 4.0f, 0, 0, 0, 0, 0,
                4, 9.6f, 32, 0,
                ItemDict.packEffect(Effects.REGENERATION, 1, 100),
                ItemDict.packEffect(Effects.ABSORPTION, 0, 2400), 0, 0,
                0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        d.add(1004, 1, 481, 0, 0, 1.0f, 4.0f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0,
                3, 2.0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_FEET, -1);
        d.add(1005, 1, 555, 0, 0, 1.0f, 4.0f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0,
                6, 2.0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_LEGS, -1);
        d.add(1006, 1, 592, 0, 0, 1.0f, 4.0f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0,
                8, 2.0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_CHEST, -1);
        d.add(1007, 1, 407, 0, 0, 1.0f, 4.0f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0,
                3, 2.0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_HEAD, -1);
        g.dict = d.build();

        for (PlayerState p : g.players) {
            p.slotEntry[0] = SWORD_ENTRY;
            p.slotCount[0] = 1;
            p.slotEntry[1] = PEARL_ENTRY;
            p.slotCount[1] = 16;
            p.slotEntry[2] = APPLE_ENTRY;
            p.slotCount[2] = 16;
            p.slotEntry[ItemDict.ARMOR_FEET] = BOOTS_ENTRY;
            p.slotCount[ItemDict.ARMOR_FEET] = 1;
            p.slotEntry[ItemDict.ARMOR_LEGS] = LEGGINGS_ENTRY;
            p.slotCount[ItemDict.ARMOR_LEGS] = 1;
            p.slotEntry[ItemDict.ARMOR_CHEST] = CHESTPLATE_ENTRY;
            p.slotCount[ItemDict.ARMOR_CHEST] = 1;
            p.slotEntry[ItemDict.ARMOR_HEAD] = HELMET_ENTRY;
            p.slotCount[ItemDict.ARMOR_HEAD] = 1;
            Loadout.recomputeDerived(g, p);
        }
        g.roundInitial = new PlayerState[]{g.players[0].copy(), g.players[1].copy()};
    }

    public static final double COMBAT_GROUND_Y = 64.0;

    public static final double ATTACKER_X = 0.5;
    public static final double ATTACKER_Z = 0.5;
    public static final double VICTIM_X = 3.5;
    public static final double VICTIM_Z = 0.5;

    public static final float ATTACKER_YAW = -90f;
    public static final float VICTIM_YAW = 90f;

    public static final int PILLAR_X = 2;
    public static final int PILLAR_Z = 0;
    public static final int PILLAR_FOOT_Y = 64;
    public static final int SIGHTLINE_VOXEL_Y = 65;

    public static final int ARENA_CRYSTAL_BASE_X = 0;
    public static final int ARENA_CRYSTAL_BASE_Y = 64;
    public static final int ARENA_CRYSTAL_BASE_Z = 5;

    public static final int PLACED_CRYSTAL_BASE_X = 0;
    public static final int PLACED_CRYSTAL_BASE_Y = 64;
    public static final int PLACED_CRYSTAL_BASE_Z = 4;

    public static final int BUILD_Y = 64;

    public static final int SHULKER_X = 1;
    public static final int SHULKER_Y = 64;
    public static final int SHULKER_Z = 1;

    public static final int ENDER_CHEST_X = 1;
    public static final int ENDER_CHEST_Y = 64;
    public static final int ENDER_CHEST_Z = -1;

    public static final int ANCHOR_X = 0;
    public static final int ANCHOR_Y = 64;
    public static final int ANCHOR_Z = 1;

    public static final int FEET_CELL_X = 0;
    public static final int FEET_CELL_Y = 64;
    public static final int FEET_CELL_Z = 0;

    public static final int WATER_NEAR_X = 0;
    public static final int WATER_NEAR_Y = 65;
    public static final int WATER_NEAR_Z = 0;

    public static final int WATER_FAR_X = 1;
    public static final int WATER_FAR_Y = 65;
    public static final int WATER_FAR_Z = 0;

    public static final int SLOT_SWORD = 0;
    public static final int SLOT_UTILITY = 1;
    public static final int SLOT_BOW = 2;
    public static final int SLOT_ARROWS = 3;
    public static final int SLOT_OBSIDIAN = 4;
    public static final int SLOT_END_CRYSTAL = 5;
    public static final int SLOT_WATER_BUCKET = 6;
    public static final int SLOT_LAVA_BUCKET = 7;
    public static final int SLOT_PICKAXE = 8;

    public static final int STASH_COBBLESTONE = 9;
    public static final int STASH_SHULKER = 10;
    public static final int STASH_ENDER_CHEST = 11;
    public static final int STASH_ANCHOR = 12;
    public static final int STASH_GLOWSTONE = 13;
    public static final int STASH_COBWEB = 14;
    public static final int STASH_WATER_BUCKET = 16;
    public static final int STASH_SPARE_ANCHOR = 17;
    public static final int STASH_SPARE_GLOWSTONE = 18;
    public static final int STASH_SCRATCH = 20;
    public static final int STASH_WIND_CHARGE = 21;
    public static final int STASH_XP_BOTTLE = 22;
    public static final int STASH_SPLASH_POTION = 23;
    public static final int STASH_SNOWBALL = 24;
    public static final int PARK_OFFHAND = 19;
    public static final int PARK_WIND = 26;
    public static final int PARK_XP = 27;

    public static final int STASH_SPEAR = 15;
    public static final int STASH_EGG = 25;
    public static final int STASH_FIREWORK = 28;
    public static final int STASH_ELYTRA = 29;
    public static final int PARK_CHESTPLATE = 30;
    public static final int PARK_SPARE_A = 31;
    public static final int PARK_SPARE_B = 32;
    public static final int STASH_PEARL = 33;
    public static final int STASH_MACE = 34;
    public static final int STASH_CROSSBOW = 35;

    public static final int VICTIM_SLOT_SWORD = 0;
    public static final int VICTIM_SLOT_SHIELD = 1;
    public static final int VICTIM_SLOT_PEARLS = 2;
    public static final int VICTIM_SLOT_APPLES = 3;
    public static final int VICTIM_SLOT_COBBLESTONE = 4;
    public static final int VICTIM_SLOT_WIND = 5;
    public static final int VICTIM_SLOT_XP = 6;
    public static final int VICTIM_SLOT_SPLASH = 7;
    public static final int VICTIM_SLOT_SNOWBALL = 8;
    public static final int VICTIM_STASH_CROSSBOW = 9;
    public static final int VICTIM_STASH_BOW = 10;
    public static final int VICTIM_STASH_FIREWORK = 11;
    public static final int VICTIM_STASH_TOTEM = 12;
    public static final int VICTIM_PARK_OFFHAND = 13;

    public static final int SHULKER_CONTAINER_ID = 1;

    public static final int ROUNDS_TARGET = 64;

    public static final int ID_SWORD = 2001;
    public static final int ID_BRITTLE_SWORD = 2002;
    public static final int ID_BOW = 2003;
    public static final int ID_ARROW = 2004;
    public static final int ID_OBSIDIAN = 2005;
    public static final int ID_COBBLESTONE = 2006;
    public static final int ID_END_CRYSTAL = 2007;
    public static final int ID_WATER_BUCKET = 2008;
    public static final int ID_LAVA_BUCKET = 2009;
    public static final int ID_EMPTY_BUCKET = 2010;
    public static final int ID_PICKAXE = 2011;
    public static final int ID_SHULKER = 2012;
    public static final int ID_ENDER_CHEST = 2013;
    public static final int ID_RESPAWN_ANCHOR = 2014;
    public static final int ID_GLOWSTONE = 2015;
    public static final int ID_COBWEB = 2016;
    public static final int ID_STRING = 2017;
    public static final int ID_APPLE = 2018;
    public static final int ID_SNOWBALL = 2019;
    public static final int ID_PEARL = 2020;
    public static final int ID_SHIELD = 2021;
    public static final int ID_BOOTS = 2022;
    public static final int ID_LEGGINGS = 2023;
    public static final int ID_CHESTPLATE = 2024;
    public static final int ID_HELMET = 2025;
    public static final int ID_WIND_CHARGE = 2026;
    public static final int ID_XP_BOTTLE = 2027;
    public static final int ID_SPLASH_POTION = 2028;
    public static final int ID_SPEAR = 2029;
    public static final int ID_EGG = 2030;
    public static final int ID_FIREWORK = 2031;
    public static final int ID_ELYTRA = 2032;
    public static final int ID_CROSSBOW = 2033;
    public static final int ID_MACE = 2034;
    public static final int ID_TOTEM = 2035;

    private static final int ARENA_BASE_X = -4;
    private static final int ARENA_BASE_Y = 64;
    private static final int ARENA_BASE_Z = -6;
    private static final int ARENA_SIZE_X = 18;
    private static final int ARENA_SIZE_Y = 6;
    private static final int ARENA_SIZE_Z = 14;

    public static final long ARENA_SESSION_ID = 0x4841524E455353L;

    private static final int PALETTE_AIR = 0;
    private static final int PALETTE_COBBLESTONE = 1;
    private static final int PALETTE_OBSIDIAN = 2;
    private static final int PALETTE_POST = 3;
    private static final int PALETTE_SLAB = 4;
    private static final int PALETTE_DECOR = 5;

    public static final double POST_HALF_WIDTH = 0.125;

    public static final double POST_HEIGHT = 1.8;

    public static final double SLAB_HEIGHT = 0.5;

    public static final int POST_NEAR_X = -1;
    public static final int POST_NEAR_Y = 64;
    public static final int POST_NEAR_Z = 0;

    public static final int POST_SIDE_X = -1;
    public static final int POST_SIDE_Y = 64;
    public static final int POST_SIDE_Z = 1;

    public static final int SLAB_X = -4;
    public static final int SLAB_Y = 64;
    public static final int SLAB_Z = 0;

    public static final int SLAB_SIDE_X = -4;
    public static final int SLAB_SIDE_Y = 64;
    public static final int SLAB_SIDE_Z = 1;

    public static final int SUPPORT_X = -3;
    public static final int SUPPORT_Y = 64;
    public static final int SUPPORT_Z = 0;

    public static final int DECOR_X = -3;
    public static final int DECOR_Y = 65;
    public static final int DECOR_Z = 0;

    public static final int BEHIND_POST_X = -2;
    public static final int BEHIND_POST_Y = 64;
    public static final int BEHIND_POST_Z = 0;

    public static final int RUBBLE_X = -2;
    public static final int RUBBLE_Y = 64;
    public static final int RUBBLE_Z = -3;

    public static final int RUBBLE_TOP_Y = 65;

    public static final int BEDROCK_X = -3;
    public static final int BEDROCK_Y = 64;
    public static final int BEDROCK_Z = -3;

    public static final int BACKSTOP_X = 8;
    public static final int BACKSTOP_LOW_Y = 64;
    public static final int BACKSTOP_HIGH_Y = 65;
    public static final int BACKSTOP_MIN_Z = -2;
    public static final int BACKSTOP_MAX_Z = 2;

    public static final int SIGHT_BLOCK_X = 1;
    public static final int SIGHT_BLOCK_Y = 65;
    public static final int SIGHT_BLOCK_Z = 0;

    public static final int KILL_BASE_X = 2;
    public static final int KILL_BASE_Y = 64;
    public static final int KILL_BASE_Z = -1;

    public static final int LAVA_CONTACT_X = 1;
    public static final int LAVA_CONTACT_Y = 64;
    public static final int LAVA_CONTACT_Z = 0;

    private static final int[][] ARENA_CELLS = {
            {PILLAR_X, PILLAR_FOOT_Y, PILLAR_Z, PALETTE_COBBLESTONE},
            {PILLAR_X, SIGHTLINE_VOXEL_Y, PILLAR_Z, PALETTE_COBBLESTONE},
            {ARENA_CRYSTAL_BASE_X, ARENA_CRYSTAL_BASE_Y, ARENA_CRYSTAL_BASE_Z, PALETTE_OBSIDIAN},
            {POST_NEAR_X, POST_NEAR_Y, POST_NEAR_Z, PALETTE_POST},
            {POST_SIDE_X, POST_SIDE_Y, POST_SIDE_Z, PALETTE_POST},
            {SLAB_X, SLAB_Y, SLAB_Z, PALETTE_SLAB},
            {SLAB_SIDE_X, SLAB_SIDE_Y, SLAB_SIDE_Z, PALETTE_SLAB},
            {SUPPORT_X, SUPPORT_Y, SUPPORT_Z, PALETTE_COBBLESTONE},
            {DECOR_X, DECOR_Y, DECOR_Z, PALETTE_DECOR},
            {RUBBLE_X, RUBBLE_Y, RUBBLE_Z, PALETTE_COBBLESTONE},
            {RUBBLE_X, RUBBLE_TOP_Y, RUBBLE_Z, PALETTE_COBBLESTONE},
            {BEDROCK_X, BEDROCK_Y, BEDROCK_Z, PALETTE_OBSIDIAN},
            {BACKSTOP_X, BACKSTOP_LOW_Y, -2, PALETTE_OBSIDIAN},
            {BACKSTOP_X, BACKSTOP_LOW_Y, -1, PALETTE_OBSIDIAN},
            {BACKSTOP_X, BACKSTOP_LOW_Y, 0, PALETTE_OBSIDIAN},
            {BACKSTOP_X, BACKSTOP_LOW_Y, 1, PALETTE_OBSIDIAN},
            {BACKSTOP_X, BACKSTOP_LOW_Y, 2, PALETTE_OBSIDIAN},
            {BACKSTOP_X, BACKSTOP_HIGH_Y, -2, PALETTE_OBSIDIAN},
            {BACKSTOP_X, BACKSTOP_HIGH_Y, -1, PALETTE_OBSIDIAN},
            {BACKSTOP_X, BACKSTOP_HIGH_Y, 0, PALETTE_OBSIDIAN},
            {BACKSTOP_X, BACKSTOP_HIGH_Y, 1, PALETTE_OBSIDIAN},
            {BACKSTOP_X, BACKSTOP_HIGH_Y, 2, PALETTE_OBSIDIAN},
    };

    private static final byte[] ARENA_BYTES = ArenaCodec.encode(arenaSnapshot());

    public static byte[] arenaBytes() {
        return ARENA_BYTES.clone();
    }

    public static Arena arena() {
        return ArenaCodec.toArena(ArenaCodec.decode(arenaBytes()));
    }

    private static ArenaCodec.Snapshot arenaSnapshot() {
        String[] palette = {"air", "cobblestone", "obsidian", "post", "slab", "decor"};
        double low = 0.5 - POST_HALF_WIDTH;
        double high = 0.5 + POST_HALF_WIDTH;
        ArenaCodec.PaletteEntry[] geometry = {
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_SKIP, 0f, 0, 0, new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 6.0f,
                        ID_COBBLESTONE, ID_COBBLESTONE, new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, 1200.0f,
                        ID_OBSIDIAN, ID_OBSIDIAN, new double[0][]),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_PARTIAL, 6.0f,
                        ID_COBBLESTONE, ID_COBBLESTONE,
                        new double[][]{{low, 0.0, low, high, POST_HEIGHT, high}}),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_PARTIAL, 6.0f,
                        ID_COBBLESTONE, ID_COBBLESTONE,
                        new double[][]{{0.0, 0.0, 0.0, 1.0, SLAB_HEIGHT, 1.0}}),
                new ArenaCodec.PaletteEntry(ArenaCodec.KIND_DECOR, 0.3f,
                        ID_GLOWSTONE, ID_GLOWSTONE, new double[0][]),
        };
        if (geometry.length != palette.length) {
            throw new IllegalStateException("harness palette and geometry are out of step");
        }
        int[][] blocks = new int[ARENA_CELLS.length][];
        for (int i = 0; i < ARENA_CELLS.length; i++) {
            int[] cell = ARENA_CELLS[i];
            blocks[i] = new int[]{cell[0] - ARENA_BASE_X, cell[1] - ARENA_BASE_Y,
                    cell[2] - ARENA_BASE_Z, cell[3]};
        }
        return new ArenaCodec.Snapshot(ARENA_SESSION_ID, ARENA_BASE_X, ARENA_BASE_Y, ARENA_BASE_Z,
                ARENA_SIZE_X, ARENA_SIZE_Y, ARENA_SIZE_Z, palette, blocks, geometry,
                COMBAT_GROUND_Y);
    }

    public static GameState combat(Arena arena) {
        GameState g = new GameState();

        PlayerState a = g.players[0];
        a.x = ATTACKER_X;
        a.y = arena.groundY;
        a.z = ATTACKER_Z;
        a.yaw = ATTACKER_YAW;
        a.health = 20f;
        a.onGround = true;
        a.vy = -0.0784;
        a.attackTicker = 100;

        PlayerState b = g.players[1];
        b.x = VICTIM_X;
        b.y = arena.groundY;
        b.z = VICTIM_Z;
        b.yaw = VICTIM_YAW;
        b.health = 20f;
        b.onGround = true;
        b.vy = -0.0784;
        b.attackTicker = 100;

        seedCombatKit(g);
        return g;
    }

    private static void seedCombatKit(GameState g) {
        ItemDict.Builder d = new ItemDict.Builder();
        int sword = weapon(d, ID_SWORD, 2031, ItemDict.FLAG_SWORD, 8.0f, 1.6f,
                ItemDict.packTool(Loadout.TIER_NETHERITE, 0, ItemDict.TOOL_SWORD, false));
        int brittleSword = weapon(d, ID_BRITTLE_SWORD, 3, ItemDict.FLAG_SWORD, 7.0f, 1.6f,
                ItemDict.packTool(Loadout.TIER_NETHERITE, 0, ItemDict.TOOL_SWORD, false));
        int bow = weapon(d, ID_BOW, 384, ItemDict.FLAG_BOW, 1.0f, 4.0f, 0);
        int arrow = stack(d, ID_ARROW, 64, ItemDict.FLAG_ARROW_PLAIN, 0);
        int obsidian = stack(d, ID_OBSIDIAN, 64, ItemDict.FLAG_BLOCK, 0);
        int cobblestone = stack(d, ID_COBBLESTONE, 64, ItemDict.FLAG_BLOCK, 0);
        int endCrystal = stack(d, ID_END_CRYSTAL, 64, ItemDict.FLAG_END_CRYSTAL, 0);
        int waterBucket = stack(d, ID_WATER_BUCKET, 1, ItemDict.FLAG_BUCKET_WATER, 0);
        int lavaBucket = stack(d, ID_LAVA_BUCKET, 1, ItemDict.FLAG_BUCKET_LAVA, 0);
        stack(d, ID_EMPTY_BUCKET, 1, ItemDict.FLAG_BUCKET_EMPTY, 0);
        int pickaxe = weapon(d, ID_PICKAXE, 12, 0, 5.0f, 1.0f,
                ItemDict.packTool(Loadout.TIER_NETHERITE, 0, ItemDict.TOOL_PICKAXE, false));
        int shulker = d.add(ID_SHULKER, 1, 0, ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER, 0,
                1.0f, 4.0f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0,
                0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, SHULKER_CONTAINER_ID);
        int enderChest = stack(d, ID_ENDER_CHEST, 1,
                ItemDict.FLAG_BLOCK | ItemDict.FLAG_ENDER_CHEST, 0);
        int respawnAnchor = stack(d, ID_RESPAWN_ANCHOR, 16, ItemDict.FLAG_RESPAWN_ANCHOR, 0);
        int glowstone = stack(d, ID_GLOWSTONE, 64, ItemDict.FLAG_GLOWSTONE, 0);
        int cobweb = stack(d, ID_COBWEB, 64, ItemDict.FLAG_BLOCK, 0);
        stack(d, ID_STRING, 64, 0, 0);
        int apple = d.add(ID_APPLE, 64, 0, ItemDict.FLAG_ALWAYS_EDIBLE, 2, 1.0f, 4.0f, 0, 0, 0, 0, 0,
                4, 9.6f, 32, 0,
                ItemDict.packEffect(Effects.REGENERATION, 1, 100),
                ItemDict.packEffect(Effects.ABSORPTION, 0, 2400), 0, 0,
                0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        int snowball = stack(d, ID_SNOWBALL, 16, 0, 3);
        int pearl = stack(d, ID_PEARL, 16, 0, 1);
        int shield = weapon(d, ID_SHIELD, 336, ItemDict.FLAG_SHIELD, 1.0f, 4.0f, 0);
        int boots = armor(d, ID_BOOTS, 481, 3, ItemDict.EQUIP_FEET);
        int leggings = armor(d, ID_LEGGINGS, 555, 6, ItemDict.EQUIP_LEGS);
        int chestplate = armor(d, ID_CHESTPLATE, 592, 8, ItemDict.EQUIP_CHEST);
        int helmet = armor(d, ID_HELMET, 407, 3, ItemDict.EQUIP_HEAD);
        int windCharge = stack(d, ID_WIND_CHARGE, 16, 0, 11);
        int xpBottle = stack(d, ID_XP_BOTTLE, 16, 0, 10);
        int splashPotion = stack(d, ID_SPLASH_POTION, 16, 0, 8);
        int spear = weapon(d, ID_SPEAR, 250, ItemDict.FLAG_SPEAR, 7.0f, 1.0f,
                ItemDict.packTool(Loadout.TIER_DIAMOND, 0, ItemDict.TOOL_NONE, false));
        int egg = stack(d, ID_EGG, 16, 0, 4);
        int firework = d.add(ID_FIREWORK, 64, 0, 0, 6, 1.0f, 4.0f, 0, 0, 0, 0, 0,
                0, 0f, 0, 2, 0, 0, 0, 0,
                0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        int crossbow = d.add(ID_CROSSBOW, 1, 465, ItemDict.FLAG_CROSSBOW, 7, 1.0f, 4.0f, 0,
                ItemDict.packWeapon(0, 0, 0, 0), 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        int mace = d.add(ID_MACE, 1, 500, ItemDict.FLAG_MACE, 0, 6.0f, 0.6f, 0,
                ItemDict.packWeapon(0, 0, 0, 0), ItemDict.packMace(true, 0, 0, 0), 0,
                ItemDict.packTool(Loadout.TIER_IRON, 0, ItemDict.TOOL_NONE, false),
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        int totem = d.add(ID_TOTEM, 1, 0, ItemDict.FLAG_TOTEM, 0, 1.0f, 4.0f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        int elytra = d.add(ID_ELYTRA, 1, 432, ItemDict.FLAG_ELYTRA, 0, 1.0f, 4.0f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0,
                0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_CHEST, -1);
        g.dict = d.build();

        g.blockProps = new BlockProps.Builder()
                .add(ID_COBBLESTONE, 2.0f, 6.0f, ID_COBBLESTONE, Loadout.TIER_STONE,
                        ItemDict.TOOL_PICKAXE, true)
                .add(ID_OBSIDIAN, 50.0f, 1200.0f, ID_OBSIDIAN, Loadout.TIER_DIAMOND,
                        ItemDict.TOOL_PICKAXE, true)
                .add(ID_GLOWSTONE, 0.3f, 0.3f, ID_GLOWSTONE, -1, ItemDict.TOOL_NONE, false)
                .add(ID_RESPAWN_ANCHOR, 50.0f, 1200.0f, ID_RESPAWN_ANCHOR, Loadout.TIER_DIAMOND,
                        ItemDict.TOOL_PICKAXE, true)
                .add(ID_SHULKER, 2.0f, 2.0f, ID_SHULKER, -1, ItemDict.TOOL_NONE, false)
                .add(ID_ENDER_CHEST, 2.0f, 600.0f, ID_ENDER_CHEST, -1, ItemDict.TOOL_NONE, false)
                .add(ID_COBWEB, 4.0f, 4.0f, ID_STRING, -1, ItemDict.TOOL_NONE, false)
                .build();

        g.vanillaBuild = true;
        g.roundsTarget = ROUNDS_TARGET;
        g.obsidianItemId = ID_OBSIDIAN;
        g.cobblestoneItemId = ID_COBBLESTONE;
        g.glowstoneItemId = ID_GLOWSTONE;
        g.glowstoneDustItemId = ID_GLOWSTONE;
        g.cobwebItemId = ID_COBWEB;
        g.stringItemId = ID_STRING;

        Container seeded = new Container();
        seeded.entry[0] = cobblestone;
        seeded.count[0] = 16;
        seeded.entry[1] = arrow;
        seeded.count[1] = 8;
        g.containers.put(SHULKER_CONTAINER_ID, seeded);
        g.roundInitialContainers.put(SHULKER_CONTAINER_ID, seeded.copy());
        g.nextContainerId = SHULKER_CONTAINER_ID + 1;

        PlayerState a = g.players[0];
        put(a, SLOT_SWORD, sword, 1);
        put(a, SLOT_UTILITY, brittleSword, 1);
        put(a, SLOT_BOW, bow, 1);
        put(a, SLOT_ARROWS, arrow, 32);
        put(a, SLOT_OBSIDIAN, obsidian, 64);
        put(a, SLOT_END_CRYSTAL, endCrystal, 8);
        put(a, SLOT_WATER_BUCKET, waterBucket, 1);
        put(a, SLOT_LAVA_BUCKET, lavaBucket, 1);
        put(a, SLOT_PICKAXE, pickaxe, 1);
        put(a, STASH_COBBLESTONE, cobblestone, 64);
        put(a, STASH_SHULKER, shulker, 1);
        put(a, STASH_ENDER_CHEST, enderChest, 1);
        put(a, STASH_ANCHOR, respawnAnchor, 4);
        put(a, STASH_GLOWSTONE, glowstone, 32);
        put(a, STASH_COBWEB, cobweb, 8);
        put(a, STASH_WATER_BUCKET, waterBucket, 1);
        put(a, STASH_SPARE_ANCHOR, respawnAnchor, 4);
        put(a, STASH_SPARE_GLOWSTONE, glowstone, 32);
        put(a, STASH_SNOWBALL, snowball, 16);
        put(a, STASH_WIND_CHARGE, windCharge, 16);
        put(a, STASH_XP_BOTTLE, xpBottle, 16);
        put(a, STASH_SPLASH_POTION, splashPotion, 16);
        put(a, STASH_SPEAR, spear, 1);
        put(a, STASH_EGG, egg, 16);
        put(a, STASH_FIREWORK, firework, 16);
        put(a, STASH_ELYTRA, elytra, 1);
        put(a, STASH_PEARL, pearl, 16);
        put(a, STASH_MACE, mace, 1);
        put(a, STASH_CROSSBOW, crossbow, 1);
        put(a, ItemDict.OFF_HAND, snowball, 16);

        PlayerState b = g.players[1];
        put(b, VICTIM_SLOT_SWORD, sword, 1);
        put(b, VICTIM_SLOT_SHIELD, shield, 1);
        put(b, VICTIM_SLOT_PEARLS, pearl, 16);
        put(b, VICTIM_SLOT_APPLES, apple, 16);
        put(b, VICTIM_SLOT_COBBLESTONE, cobblestone, 64);
        put(b, VICTIM_SLOT_WIND, windCharge, 16);
        put(b, VICTIM_SLOT_XP, xpBottle, 16);
        put(b, VICTIM_SLOT_SPLASH, splashPotion, 16);
        put(b, VICTIM_SLOT_SNOWBALL, snowball, 16);
        put(b, VICTIM_STASH_CROSSBOW, crossbow, 1);
        put(b, VICTIM_STASH_BOW, bow, 1);
        put(b, VICTIM_STASH_FIREWORK, firework, 16);
        put(b, VICTIM_STASH_TOTEM, totem, 1);

        for (PlayerState p : g.players) {
            put(p, ItemDict.ARMOR_FEET, boots, 1);
            put(p, ItemDict.ARMOR_LEGS, leggings, 1);
            put(p, ItemDict.ARMOR_CHEST, chestplate, 1);
            put(p, ItemDict.ARMOR_HEAD, helmet, 1);
            Loadout.recomputeDerived(g, p);
        }
        g.roundInitial = new PlayerState[]{g.players[0].copy(), g.players[1].copy()};
    }

    private static void put(PlayerState p, int slot, int entry, int count) {
        p.slotEntry[slot] = entry;
        p.slotCount[slot] = count;
    }

    private static int weapon(ItemDict.Builder d, int itemId, int maxDamage, int flags,
                              float meleeDamage, float meleeSpeed, int toolInfo) {
        int useKind = (flags & ItemDict.FLAG_BOW) != 0 ? 5 : (flags & ItemDict.FLAG_SHIELD) != 0 ? 9 : 0;
        return d.add(itemId, 1, maxDamage, flags, useKind, meleeDamage, meleeSpeed, 0,
                ItemDict.packWeapon(0, 0, 0, 0), 0, 0, toolInfo,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
    }

    private static int stack(ItemDict.Builder d, int itemId, int maxStack, int flags, int useKind) {
        return d.add(itemId, maxStack, 0, flags, useKind, 1.0f, 4.0f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
    }

    private static int armor(ItemDict.Builder d, int itemId, int maxDamage, int points, int equip) {
        return d.add(itemId, 1, maxDamage, 0, 0, 1.0f, 4.0f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0,
                points, 2.0f, 0f, 0, 0, 0, 0, 0, equip, -1);
    }
}
