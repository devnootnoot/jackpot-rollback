package me.nootnoot.sim;

import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;

public final class CrystalKitFixture {

    public static final int SLOT_SWORD = 0;
    public static final int SLOT_CRYSTAL = 1;
    public static final int SLOT_OBSIDIAN = 2;
    public static final int SLOT_ANCHOR = 3;
    public static final int SLOT_GLOWSTONE = 4;
    public static final int SLOT_PICKAXE = 5;
    public static final int SLOT_CROSSBOW = 6;
    public static final int SLOT_GAPPLE = 7;
    public static final int SLOT_FIREWORK3 = 8;
    public static final int SLOT_ELYTRA = 17;
    public static final int SLOT_ARROWS = 21;
    public static final int SLOT_TIPPED = 22;
    public static final int SLOT_PEARL = 23;
    public static final int SLOT_BOW = 24;
    public static final int SLOT_MACE = 25;
    public static final int SLOT_SHIELD = 26;
    public static final int SLOT_WATER_BUCKET = 27;
    public static final int SLOT_LAVA_BUCKET = 28;
    public static final int SLOT_COBWEB = 29;
    public static final int SLOT_LOOSE_TOTEM_START = 30;
    public static final int SLOT_LOOSE_TOTEM_END = 35;
    public static final int SLOT_SPARE_GAPPLE = 35;

    public static final int ID_NETHERITE_SWORD = 1;
    public static final int ID_END_CRYSTAL = 2;
    public static final int ID_OBSIDIAN = 3;
    public static final int ID_RESPAWN_ANCHOR = 4;
    public static final int ID_GLOWSTONE = 5;
    public static final int ID_NETHERITE_PICKAXE = 6;
    public static final int ID_CROSSBOW = 7;
    public static final int ID_GOLDEN_APPLE = 8;
    public static final int ID_FIREWORK = 9;
    public static final int ID_ELYTRA = 10;
    public static final int ID_ENDER_CHEST = 11;
    public static final int ID_SHULKER_BOX = 12;
    public static final int ID_ARROW = 13;
    public static final int ID_TIPPED_ARROW = 14;
    public static final int ID_TOTEM = 15;
    public static final int ID_NETHERITE_BOOTS = 16;
    public static final int ID_NETHERITE_LEGGINGS = 17;
    public static final int ID_NETHERITE_CHESTPLATE = 18;
    public static final int ID_NETHERITE_HELMET = 19;
    public static final int ID_ENDER_PEARL = 20;
    public static final int ID_BOW = 21;
    public static final int ID_MACE = 22;
    public static final int ID_SHIELD = 23;
    public static final int ID_WATER_BUCKET = 24;
    public static final int ID_LAVA_BUCKET = 25;
    public static final int ID_COBWEB = 26;
    public static final int ID_STRING = 27;

    private CrystalKitFixture() {
    }

    public static GameState build(double groundY) {
        GameState s = new GameState();
        for (int i = 0; i < 2; i++) {
            PlayerState p = s.players[i];
            p.health = 20f;
            p.food = 20f;
            p.saturation = 5f;
            p.onGround = true;
            p.y = groundY;
            p.z = 0.5;
        }
        s.players[0].x = 0.5;
        s.players[1].x = 10_000.0;

        ItemDict.Builder b = new ItemDict.Builder();
        int sword = b.add(ID_NETHERITE_SWORD, 1, 2031, ItemDict.FLAG_SWORD, Combat.USE_NONE,
                8f, 1.6f, 0, 0, 0, 0,
                ItemDict.packTool(5, 0, ItemDict.TOOL_SWORD, false),
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int crystal = b.add(ID_END_CRYSTAL, 64, 0, ItemDict.FLAG_END_CRYSTAL, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int obsidian = b.add(ID_OBSIDIAN, 64, 0, ItemDict.FLAG_BLOCK, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int anchor = b.add(ID_RESPAWN_ANCHOR, 64, 0,
                ItemDict.FLAG_BLOCK | ItemDict.FLAG_RESPAWN_ANCHOR, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int glowstone = b.add(ID_GLOWSTONE, 64, 0,
                ItemDict.FLAG_BLOCK | ItemDict.FLAG_GLOWSTONE, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int pickaxe = b.add(ID_NETHERITE_PICKAXE, 1, 2031, 0, Combat.USE_NONE,
                6f, 1.2f, 0, 0, 0, 0,
                ItemDict.packTool(5, 5, ItemDict.TOOL_PICKAXE, false),
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int crossbow = b.add(ID_CROSSBOW, 1, 465, ItemDict.FLAG_CROSSBOW, Combat.USE_CROSSBOW,
                1f, 4f, 0, 0, 0, ItemDict.packRanged(0, 0, 0, false, 0), 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int gapple = b.add(ID_GOLDEN_APPLE, 64, 0, ItemDict.FLAG_ALWAYS_EDIBLE, Combat.USE_FOOD,
                1f, 4f, 0, 0, 0, 0, 0, 4, 9.6f, 32, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int firework3 = firework(b, 3);
        int elytra = b.add(ID_ELYTRA, 1, 432, ItemDict.FLAG_ELYTRA, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_CHEST, -1);
        int enderChest = b.add(ID_ENDER_CHEST, 64, 0,
                ItemDict.FLAG_BLOCK | ItemDict.FLAG_ENDER_CHEST, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int shulkerA = b.add(ID_SHULKER_BOX, 1, 0,
                ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, 1);
        int shulkerB = b.add(ID_SHULKER_BOX, 1, 0,
                ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, 2);
        int arrow = b.add(ID_ARROW, 64, 0, ItemDict.FLAG_ARROW_PLAIN, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int tipped = b.add(ID_TIPPED_ARROW, 64, 0, ItemDict.FLAG_ARROW_SPECIAL, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int totem = b.add(ID_TOTEM, 1, 0, ItemDict.FLAG_TOTEM, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int firework1 = firework(b, 1);
        int firework2 = firework(b, 2);
        int boots = armour(b, ID_NETHERITE_BOOTS, 3, ItemDict.EQUIP_FEET);
        int leggings = armour(b, ID_NETHERITE_LEGGINGS, 6, ItemDict.EQUIP_LEGS);
        int chestplate = armour(b, ID_NETHERITE_CHESTPLATE, 8, ItemDict.EQUIP_CHEST);
        int helmet = armour(b, ID_NETHERITE_HELMET, 3, ItemDict.EQUIP_HEAD);
        int pearl = b.add(ID_ENDER_PEARL, 16, 0, 0, Combat.USE_PEARL,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int bow = b.add(ID_BOW, 1, 384, ItemDict.FLAG_BOW, Combat.USE_BOW,
                1f, 4f, 0, 0, 0, ItemDict.packRanged(0, 0, 0, false, 0), 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int mace = b.add(ID_MACE, 1, 500, ItemDict.FLAG_MACE, Combat.USE_NONE,
                6f, 0.6f, 0, 0, ItemDict.packMace(true, 0, 0, 0), 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int shield = b.add(ID_SHIELD, 1, 336, ItemDict.FLAG_SHIELD, Combat.USE_SHIELD,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int waterBucket = b.add(ID_WATER_BUCKET, 1, 0, ItemDict.FLAG_BUCKET_WATER, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int lavaBucket = b.add(ID_LAVA_BUCKET, 1, 0, ItemDict.FLAG_BUCKET_LAVA, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int cobweb = b.add(ID_COBWEB, 64, 0, ItemDict.FLAG_BLOCK, Combat.USE_NONE,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        s.dict = b.build();

        Container c1 = new Container();
        Container c2 = new Container();
        for (int cell = 0; cell < 27; cell++) {
            c1.entry[cell] = totem;
            c1.count[cell] = 1;
            c2.entry[cell] = totem;
            c2.count[cell] = 1;
        }
        s.containers.put(1, c1);
        s.containers.put(2, c2);
        s.nextContainerId = 3;
        s.obsidianItemId = ID_OBSIDIAN;
        s.glowstoneItemId = ID_GLOWSTONE;
        s.cobwebItemId = ID_COBWEB;
        s.stringItemId = ID_STRING;

        for (int i = 0; i < 2; i++) {
            PlayerState p = s.players[i];
            put(p, SLOT_SWORD, sword, 1);
            put(p, SLOT_CRYSTAL, crystal, 64);
            put(p, SLOT_OBSIDIAN, obsidian, 64);
            put(p, SLOT_ANCHOR, anchor, 64);
            put(p, SLOT_GLOWSTONE, glowstone, 64);
            put(p, SLOT_PICKAXE, pickaxe, 1);
            put(p, SLOT_CROSSBOW, crossbow, 1);
            put(p, SLOT_GAPPLE, gapple, 64);
            put(p, SLOT_FIREWORK3, firework3, 64);
            put(p, 9, crystal, 64);
            put(p, 10, crystal, 64);
            put(p, 11, crystal, 64);
            put(p, 12, obsidian, 64);
            put(p, 13, obsidian, 64);
            put(p, 14, glowstone, 64);
            put(p, 15, firework1, 64);
            put(p, 16, firework2, 64);
            put(p, SLOT_ELYTRA, elytra, 1);
            put(p, 18, enderChest, 8);
            put(p, 19, shulkerA, 1);
            put(p, 20, shulkerB, 1);
            put(p, SLOT_ARROWS, arrow, 64);
            put(p, SLOT_TIPPED, tipped, 64);
            put(p, SLOT_PEARL, pearl, 16);
            put(p, SLOT_BOW, bow, 1);
            put(p, SLOT_MACE, mace, 1);
            put(p, SLOT_SHIELD, shield, 1);
            put(p, SLOT_WATER_BUCKET, waterBucket, 1);
            put(p, SLOT_LAVA_BUCKET, lavaBucket, 1);
            put(p, SLOT_COBWEB, cobweb, 64);
            for (int slot = SLOT_LOOSE_TOTEM_START; slot < SLOT_LOOSE_TOTEM_END; slot++) {
                put(p, slot, totem, 1);
            }
            put(p, SLOT_SPARE_GAPPLE, gapple, 64);
            put(p, ItemDict.OFF_HAND, totem, 1);
            put(p, ItemDict.ARMOR_FEET, boots, 1);
            put(p, ItemDict.ARMOR_LEGS, leggings, 1);
            put(p, ItemDict.ARMOR_CHEST, chestplate, 1);
            put(p, ItemDict.ARMOR_HEAD, helmet, 1);
            Loadout.recomputeDerived(s, p);
        }
        return s;
    }

    private static int firework(ItemDict.Builder b, int flight) {
        return b.add(ID_FIREWORK, 64, 0, 0, Combat.USE_FIREWORK,
                1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, flight, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
    }

    private static int armour(ItemDict.Builder b, int itemId, int points, int equip) {
        return b.add(itemId, 1, 592, 0, Combat.USE_NONE, 1f, 4f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, points, 3f, 0.1f, 0, 0, 0, 0, 0, equip, -1);
    }

    private static void put(PlayerState p, int slot, int entry, int count) {
        p.slotEntry[slot] = entry;
        p.slotCount[slot] = count;
        p.slotDamage[slot] = 0;
    }
}
