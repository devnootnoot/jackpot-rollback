package me.nootnoot.sim;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;

public final class TestKit {
    public static final class Item {
        int itemId = 1;
        int maxStack = 64;
        int maxDamage;
        int flags;
        int useKind;
        float meleeDamage = ItemDict.FIST_DAMAGE;
        float meleeSpeed = ItemDict.FIST_SPEED;
        int knockback;
        int weaponEnchants;
        int maceInfo;
        int rangedInfo;
        int toolInfo;
        int foodNutrition;
        float foodSaturation;
        int foodEatTicks;
        int fireworkFlight;
        final int[] effects = new int[4];
        int armorPoints;
        float armorToughness;
        float armorKbResistance;
        int armorProtection;
        int armorBlastProtection;
        int armorProjectileProtection;
        int armorFireProtection;
        int armorFeatherFalling;
        int equipSlot;
        int containerSeed = -1;

        public Item itemId(int v) {
            itemId = v;
            return this;
        }

        public Item maxStack(int v) {
            maxStack = v;
            return this;
        }

        public Item maxDamage(int v) {
            maxDamage = v;
            return this;
        }

        public Item flags(int v) {
            flags |= v;
            return this;
        }

        public Item useKind(int v) {
            useKind = v;
            return this;
        }

        public Item melee(float damage, float speed) {
            meleeDamage = damage;
            meleeSpeed = speed;
            return this;
        }

        public Item knockback(int v) {
            knockback = v;
            return this;
        }

        public Item weapon(int sharpness, int fireAspect, int punch, int flame) {
            weaponEnchants = ItemDict.packWeapon(sharpness, fireAspect, punch, flame);
            return this;
        }

        public Item mace(boolean isMace, int windBurst, int density, int breach) {
            maceInfo = ItemDict.packMace(isMace, windBurst, density, breach);
            if (isMace) {
                flags |= ItemDict.FLAG_MACE;
            }
            return this;
        }

        public Item ranged(int bowPower, int quickCharge, int multishot, boolean infinity,
                           int piercing) {
            rangedInfo = ItemDict.packRanged(bowPower, quickCharge, multishot, infinity, piercing);
            return this;
        }

        public Item tool(int tier, int efficiency, int toolClass, boolean aquaAffinity) {
            toolInfo = ItemDict.packTool(tier, efficiency, toolClass, aquaAffinity);
            return this;
        }

        public Item food(int nutrition, float saturation, int eatTicks, boolean alwaysEdible) {
            useKind = Combat.USE_FOOD;
            foodNutrition = nutrition;
            foodSaturation = saturation;
            foodEatTicks = eatTicks;
            if (alwaysEdible) {
                flags |= ItemDict.FLAG_ALWAYS_EDIBLE;
            }
            return this;
        }

        public Item fireworkFlight(int v) {
            fireworkFlight = v;
            return this;
        }

        public Item effect(int index, int packed) {
            effects[index] = packed;
            return this;
        }

        public Item armor(int points, float toughness, float kbResistance, int equip) {
            armorPoints = points;
            armorToughness = toughness;
            armorKbResistance = kbResistance;
            equipSlot = equip;
            return this;
        }

        public Item containerSeed(int v) {
            containerSeed = v;
            return this;
        }

        public Item armorEnchants(int protection, int blast, int projectile, int fire,
                                  int featherFalling) {
            armorProtection = protection;
            armorBlastProtection = blast;
            armorProjectileProtection = projectile;
            armorFireProtection = fire;
            armorFeatherFalling = featherFalling;
            return this;
        }
    }

    private final GameState state;
    private final List<Item> items = new ArrayList<>();

    private TestKit(GameState state) {
        this.state = state;
    }

    public static TestKit of(GameState state) {
        TestKit kit = new TestKit(state);
        ItemDict d = state.dict;
        for (int e = 1; e <= d.size(); e++) {
            Item i = new Item();
            i.itemId = d.itemId(e);
            i.maxStack = d.maxStack(e);
            i.maxDamage = d.maxDamage(e);
            i.flags = d.flags(e);
            i.useKind = d.useKind(e);
            i.meleeDamage = d.meleeDamage(e);
            i.meleeSpeed = d.meleeSpeed(e);
            i.knockback = d.knockback(e);
            i.weaponEnchants = d.weaponEnchants(e);
            i.maceInfo = d.maceInfo(e);
            i.rangedInfo = d.rangedInfo(e);
            i.toolInfo = d.toolInfo(e);
            i.foodNutrition = d.foodNutrition(e);
            i.foodSaturation = d.foodSaturation(e);
            i.foodEatTicks = d.foodEatTicks(e);
            i.fireworkFlight = d.fireworkFlight(e);
            for (int k = 0; k < 4; k++) {
                i.effects[k] = d.effect(e, k);
            }
            i.armorPoints = d.armorPoints(e);
            i.armorToughness = d.armorToughness(e);
            i.armorKbResistance = d.armorKbResistance(e);
            i.armorProtection = d.armorProtection(e);
            i.armorBlastProtection = d.armorBlastProtection(e);
            i.armorProjectileProtection = d.armorProjectileProtection(e);
            i.armorFireProtection = d.armorFireProtection(e);
            i.armorFeatherFalling = d.armorFeatherFalling(e);
            i.equipSlot = d.equipSlot(e);
            i.containerSeed = d.containerSeed(e);
            kit.items.add(i);
        }
        return kit;
    }

    public static Item item() {
        return new Item();
    }

    public int add(Item item) {
        items.add(item);
        rebuild();
        return items.size();
    }

    public int give(int player, int slot, int count, Item item) {
        int entry = add(item);
        PlayerState p = state.players[player];
        p.slotEntry[slot] = entry;
        p.slotCount[slot] = count;
        p.slotDamage[slot] = 0;
        Loadout.recomputeDerived(state, p);
        return entry;
    }

    public void put(int player, int slot, int entry, int count) {
        PlayerState p = state.players[player];
        p.slotEntry[slot] = entry;
        p.slotCount[slot] = count;
        p.slotDamage[slot] = 0;
        Loadout.recomputeDerived(state, p);
    }

    private void rebuild() {
        ItemDict.Builder b = new ItemDict.Builder();
        for (Item i : items) {
            b.add(i.itemId, i.maxStack, i.maxDamage, i.flags, i.useKind, i.meleeDamage,
                    i.meleeSpeed, i.knockback, i.weaponEnchants, i.maceInfo, i.rangedInfo,
                    i.toolInfo, i.foodNutrition, i.foodSaturation, i.foodEatTicks,
                    i.fireworkFlight, i.effects[0], i.effects[1], i.effects[2], i.effects[3],
                    i.armorPoints, i.armorToughness, i.armorKbResistance, i.armorProtection,
                    i.armorBlastProtection, i.armorProjectileProtection, i.armorFireProtection,
                    i.armorFeatherFalling, i.equipSlot, i.containerSeed);
        }
        state.dict = b.build();
        for (PlayerState p : state.players) {
            Loadout.recomputeDerived(state, p);
        }
    }
}
