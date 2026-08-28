package me.nootnoot.sim.state;

public final class ItemDict {
    public static final int NONE = 0;
    public static final int SLOTS = 41;
    public static final int HOTBAR = 9;
    public static final int MAIN_SLOTS = 36;
    public static final int ARMOR_FEET = 36;
    public static final int ARMOR_LEGS = 37;
    public static final int ARMOR_CHEST = 38;
    public static final int ARMOR_HEAD = 39;
    public static final int OFF_HAND = 40;

    public static final int MAX_ENTRIES = 1024;

    public static final int EQUIP_NONE = 0;
    public static final int EQUIP_FEET = 1;
    public static final int EQUIP_LEGS = 2;
    public static final int EQUIP_CHEST = 3;
    public static final int EQUIP_HEAD = 4;
    public static final int EQUIP_OFF_HAND = 5;

    public static final int TOOL_NONE = 0;
    public static final int TOOL_PICKAXE = 1;
    public static final int TOOL_AXE = 2;
    public static final int TOOL_SHOVEL = 3;
    public static final int TOOL_HOE = 4;
    public static final int TOOL_SHEARS = 5;
    public static final int TOOL_SWORD = 6;

    public static final int FLAG_BLOCK = 1;
    public static final int FLAG_ARROW_PLAIN = 1 << 1;
    public static final int FLAG_ARROW_SPECIAL = 1 << 2;
    public static final int FLAG_AXE = 1 << 3;
    public static final int FLAG_SWORD = 1 << 4;
    public static final int FLAG_SHEARS = 1 << 5;
    public static final int FLAG_BOW = 1 << 6;
    public static final int FLAG_CROSSBOW = 1 << 7;
    public static final int FLAG_MACE = 1 << 8;
    public static final int FLAG_TOTEM = 1 << 9;
    public static final int FLAG_ELYTRA = 1 << 10;
    public static final int FLAG_SHIELD = 1 << 11;
    public static final int FLAG_CROSSBOW_CHARGED = 1 << 12;
    public static final int FLAG_BUCKET_EMPTY = 1 << 13;
    public static final int FLAG_BUCKET_WATER = 1 << 14;
    public static final int FLAG_BUCKET_LAVA = 1 << 15;
    public static final int FLAG_END_CRYSTAL = 1 << 16;
    public static final int FLAG_RESPAWN_ANCHOR = 1 << 17;
    public static final int FLAG_GLOWSTONE = 1 << 18;
    public static final int FLAG_ENDER_CHEST = 1 << 19;
    public static final int FLAG_SHULKER = 1 << 20;
    public static final int FLAG_ALWAYS_EDIBLE = 1 << 21;
    public static final int FLAG_SPEAR = 1 << 22;

    public static final float FIST_DAMAGE = 1.0f;
    public static final float FIST_SPEED = 4.0f;

    public static final double DEFAULT_ATTACK_RANGE = 3.0;
    public static final double DEFAULT_ATTACK_HITBOX_MARGIN = 0.0;
    public static final double DEFAULT_ATTACK_MIN_RANGE = 0.0;
    public static final double SPEAR_ATTACK_MIN_RANGE = 2.0;
    public static final double SPEAR_ATTACK_RANGE = 4.5;
    public static final double SPEAR_ATTACK_HITBOX_MARGIN = 0.125;

    public static final float MAX_ATTACK_DAMAGE = 20.0f;
    public static final float MAX_ATTACK_SPEED = 4.0f;
    public static final int MAX_KNOCKBACK = 5;
    public static final int MAX_SHARPNESS = 5;
    public static final int MAX_FIRE_ASPECT = 2;
    public static final int MAX_PUNCH = 2;
    public static final int MAX_FLAME = 1;
    public static final int MAX_BREACH = 4;
    public static final int MAX_DENSITY = 5;
    public static final int MAX_WIND_BURST = 3;
    public static final int MAX_POWER = 5;
    public static final int MAX_QUICK_CHARGE = 3;
    public static final int MIN_CROSSBOW_LOAD = 10;
    public static final int MAX_MULTISHOT = 1;
    public static final int MAX_PIERCING = 4;
    public static final int MAX_EFFICIENCY = 5;
    public static final int MAX_ARMOR_POINTS_PER_PIECE = 8;
    public static final float MAX_TOUGHNESS_PER_PIECE = 3.0f;
    public static final float MAX_KB_RESISTANCE_PER_PIECE = 0.1f;
    public static final float MAX_KB_RESISTANCE_TOTAL = 0.9f;
    public static final int MAX_EPF_LEVEL = 16;
    public static final int MAX_FEATHER_FALLING = 4;
    public static final int MAX_EFFECT_AMPLIFIER = 4;
    public static final int MAX_EFFECT_DURATION = 32767;
    public static final int MAX_FIREWORK_FLIGHT = 3;
    public static final int MAX_FOOD_NUTRITION = 20;
    public static final float MAX_FOOD_SATURATION = 20.0f;
    public static final int MIN_FOOD_EAT_TICKS = 16;
    public static final int MAX_STACK = 64;

    public static final int ENTRY_BYTES = 80;
    public static final int SLOT_BYTES = 5;

    private final int size;
    private final int[] itemId;
    private final int[] maxStack;
    private final int[] maxDamage;
    private final int[] flags;
    private final int[] useKind;
    private final int[] knockback;
    private final int[] weaponEnchants;
    private final int[] maceInfo;
    private final int[] rangedInfo;
    private final int[] toolInfo;
    private final int[] foodNutrition;
    private final int[] foodEatTicks;
    private final int[] fireworkFlight;
    private final int[] effect0;
    private final int[] effect1;
    private final int[] effect2;
    private final int[] effect3;
    private final int[] armorPoints;
    private final int[] armorProtection;
    private final int[] armorBlastProtection;
    private final int[] armorProjectileProtection;
    private final int[] armorFireProtection;
    private final int[] armorFeatherFalling;
    private final int[] equipSlot;
    private final int[] containerSeed;
    private final float[] meleeDamage;
    private final float[] meleeSpeed;
    private final float[] foodSaturation;
    private final float[] armorToughness;
    private final float[] armorKbResistance;
    private final double[] attackMinRange;
    private final double[] attackMaxRange;
    private final double[] attackHitboxMargin;
    private final java.util.HashMap<Integer, Integer> byItemId;

    private long digest;
    private boolean sealed;

    public static final class Builder {
        private final java.util.ArrayList<int[]> ints = new java.util.ArrayList<>();
        private final java.util.ArrayList<float[]> floats = new java.util.ArrayList<>();

        public Builder() {
            ints.add(new int[]{0, MAX_STACK, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, EQUIP_NONE, -1});
            floats.add(new float[]{FIST_DAMAGE, FIST_SPEED, 0f, 0f, 0f});
        }

        public int add(int itemId, int maxStack, int maxDamage, int flags, int useKind,
                       float meleeDamage, float meleeSpeed, int knockback,
                       int weaponEnchants, int maceInfo, int rangedInfo, int toolInfo,
                       int foodNutrition, float foodSaturation, int foodEatTicks, int fireworkFlight,
                       int effect0, int effect1, int effect2, int effect3,
                       int armorPoints, float armorToughness, float armorKbResistance,
                       int armorProtection, int armorBlastProtection, int armorProjectileProtection,
                       int armorFireProtection, int armorFeatherFalling,
                       int equipSlot, int containerSeed) {
            ints.add(new int[]{itemId, maxStack, maxDamage, flags, useKind, knockback,
                    weaponEnchants, maceInfo, rangedInfo, toolInfo,
                    foodNutrition, foodEatTicks, fireworkFlight,
                    effect0, effect1, effect2, effect3,
                    armorPoints, armorProtection, armorBlastProtection,
                    armorProjectileProtection, armorFireProtection, armorFeatherFalling,
                    equipSlot, containerSeed});
            floats.add(new float[]{meleeDamage, meleeSpeed, foodSaturation, armorToughness,
                    armorKbResistance});
            return ints.size() - 1;
        }

        public int size() {
            return ints.size() - 1;
        }

        public ItemDict build() {
            return new ItemDict(ints, floats);
        }
    }

    private ItemDict(java.util.List<int[]> ints, java.util.List<float[]> floats) {
        this.size = ints.size();
        this.itemId = new int[size];
        this.maxStack = new int[size];
        this.maxDamage = new int[size];
        this.flags = new int[size];
        this.useKind = new int[size];
        this.knockback = new int[size];
        this.weaponEnchants = new int[size];
        this.maceInfo = new int[size];
        this.rangedInfo = new int[size];
        this.toolInfo = new int[size];
        this.foodNutrition = new int[size];
        this.foodEatTicks = new int[size];
        this.fireworkFlight = new int[size];
        this.effect0 = new int[size];
        this.effect1 = new int[size];
        this.effect2 = new int[size];
        this.effect3 = new int[size];
        this.armorPoints = new int[size];
        this.armorProtection = new int[size];
        this.armorBlastProtection = new int[size];
        this.armorProjectileProtection = new int[size];
        this.armorFireProtection = new int[size];
        this.armorFeatherFalling = new int[size];
        this.equipSlot = new int[size];
        this.containerSeed = new int[size];
        this.meleeDamage = new float[size];
        this.meleeSpeed = new float[size];
        this.foodSaturation = new float[size];
        this.armorToughness = new float[size];
        this.armorKbResistance = new float[size];
        this.attackMinRange = new double[size];
        this.attackMaxRange = new double[size];
        this.attackHitboxMargin = new double[size];
        for (int i = 0; i < size; i++) {
            int[] a = ints.get(i);
            float[] f = floats.get(i);
            itemId[i] = a[0];
            maxStack[i] = a[1];
            maxDamage[i] = a[2];
            flags[i] = a[3];
            useKind[i] = a[4];
            knockback[i] = a[5];
            weaponEnchants[i] = a[6];
            maceInfo[i] = a[7];
            rangedInfo[i] = a[8];
            toolInfo[i] = a[9];
            foodNutrition[i] = a[10];
            foodEatTicks[i] = a[11];
            fireworkFlight[i] = a[12];
            effect0[i] = a[13];
            effect1[i] = a[14];
            effect2[i] = a[15];
            effect3[i] = a[16];
            armorPoints[i] = a[17];
            armorProtection[i] = a[18];
            armorBlastProtection[i] = a[19];
            armorProjectileProtection[i] = a[20];
            armorFireProtection[i] = a[21];
            armorFeatherFalling[i] = a[22];
            equipSlot[i] = a[23];
            containerSeed[i] = a[24];
            meleeDamage[i] = f[0];
            meleeSpeed[i] = f[1];
            foodSaturation[i] = f[2];
            armorToughness[i] = f[3];
            armorKbResistance[i] = f[4];
            boolean spear = (flags[i] & FLAG_SPEAR) != 0;
            attackMinRange[i] = spear ? SPEAR_ATTACK_MIN_RANGE : DEFAULT_ATTACK_MIN_RANGE;
            attackMaxRange[i] = spear ? SPEAR_ATTACK_RANGE : DEFAULT_ATTACK_RANGE;
            attackHitboxMargin[i] = spear ? SPEAR_ATTACK_HITBOX_MARGIN : DEFAULT_ATTACK_HITBOX_MARGIN;
        }
        this.byItemId = new java.util.HashMap<>();
        for (int i = size - 1; i >= 1; i--) {
            byItemId.put(itemId[i], i);
        }
        seal();
    }

    public static ItemDict empty() {
        return new Builder().build();
    }

    private void seal() {
        for (int i = 1; i < size; i++) {
            maxStack[i] = clampInt(maxStack[i] <= 0 ? 1 : maxStack[i], 1, MAX_STACK);
            maxDamage[i] = Math.max(0, maxDamage[i]);
            meleeDamage[i] = clampFloat(meleeDamage[i], 0f, MAX_ATTACK_DAMAGE);
            meleeSpeed[i] = clampFloat(meleeSpeed[i], 0f, MAX_ATTACK_SPEED);
            knockback[i] = clampInt(knockback[i], 0, MAX_KNOCKBACK);
            weaponEnchants[i] = packWeapon(
                    clampInt(sharpnessOf(weaponEnchants[i]), 0, MAX_SHARPNESS),
                    clampInt(fireAspectOf(weaponEnchants[i]), 0, MAX_FIRE_ASPECT),
                    clampInt(punchOf(weaponEnchants[i]), 0, MAX_PUNCH),
                    clampInt(flameOf(weaponEnchants[i]), 0, MAX_FLAME));
            maceInfo[i] = packMace(isMaceOf(maceInfo[i]),
                    clampInt(windBurstOf(maceInfo[i]), 0, MAX_WIND_BURST),
                    clampInt(densityOf(maceInfo[i]), 0, MAX_DENSITY),
                    clampInt(breachOf(maceInfo[i]), 0, MAX_BREACH));
            rangedInfo[i] = packRanged(
                    clampInt(bowPowerOf(rangedInfo[i]), 0, MAX_POWER),
                    clampInt(quickChargeOf(rangedInfo[i]), 0, MAX_QUICK_CHARGE),
                    clampInt(multishotOf(rangedInfo[i]), 0, MAX_MULTISHOT),
                    infinityOf(rangedInfo[i]),
                    clampInt(piercingOf(rangedInfo[i]), 0, MAX_PIERCING));
            toolInfo[i] = packTool(clampInt(tierOf(toolInfo[i]), 0, 7),
                    clampInt(efficiencyOf(toolInfo[i]), 0, MAX_EFFICIENCY),
                    clampInt(toolClassOf(toolInfo[i]), 0, TOOL_SWORD),
                    aquaAffinityOf(toolInfo[i]));
            foodNutrition[i] = clampInt(foodNutrition[i], 0, MAX_FOOD_NUTRITION);
            foodSaturation[i] = clampFloat(foodSaturation[i], 0f, MAX_FOOD_SATURATION);
            foodEatTicks[i] = foodEatTicks[i] <= 0 ? 0 : Math.max(MIN_FOOD_EAT_TICKS, foodEatTicks[i]);
            fireworkFlight[i] = clampInt(fireworkFlight[i], 0, MAX_FIREWORK_FLIGHT);
            effect0[i] = clampEffect(effect0[i]);
            effect1[i] = clampEffect(effect1[i]);
            effect2[i] = clampEffect(effect2[i]);
            effect3[i] = clampEffect(effect3[i]);
            armorPoints[i] = clampInt(armorPoints[i], 0, MAX_ARMOR_POINTS_PER_PIECE);
            armorToughness[i] = clampFloat(armorToughness[i], 0f, MAX_TOUGHNESS_PER_PIECE);
            armorKbResistance[i] = clampFloat(armorKbResistance[i], 0f, MAX_KB_RESISTANCE_PER_PIECE);
            armorProtection[i] = clampInt(armorProtection[i], 0, MAX_EPF_LEVEL);
            armorBlastProtection[i] = clampInt(armorBlastProtection[i], 0, MAX_EPF_LEVEL);
            armorProjectileProtection[i] = clampInt(armorProjectileProtection[i], 0, MAX_EPF_LEVEL);
            armorFireProtection[i] = clampInt(armorFireProtection[i], 0, MAX_EPF_LEVEL);
            armorFeatherFalling[i] = clampInt(armorFeatherFalling[i], 0, MAX_FEATHER_FALLING);
            equipSlot[i] = clampInt(equipSlot[i], EQUIP_NONE, EQUIP_OFF_HAND);
            useKind[i] = clampInt(useKind[i], 0, 11);
        }
        this.digest = computeDigest();
        this.sealed = true;
    }

    public boolean sealed() {
        return sealed;
    }

    public int size() {
        return size - 1;
    }

    public boolean valid(int entry) {
        return entry > 0 && entry < size;
    }

    public int entryForItemId(int id) {
        Integer e = byItemId.get(id);
        return e == null ? NONE : e;
    }

    public int entryForContainer(int id) {
        if (id < 0) {
            return NONE;
        }
        for (int e = 1; e < size; e++) {
            if (containerSeed[e] == id) {
                return e;
            }
        }
        return NONE;
    }

    public long digest() {
        return digest;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static float clampFloat(float v, float lo, float hi) {
        if (!Float.isFinite(v)) {
            return lo;
        }
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int clampEffect(int packed) {
        int id = effectId(packed);
        if (id == Effects.NONE) {
            return 0;
        }
        return packEffect(id, clampInt(effectAmplifier(packed), 0, MAX_EFFECT_AMPLIFIER),
                clampInt(effectDuration(packed), 0, MAX_EFFECT_DURATION));
    }

    public int itemId(int e) {
        return valid(e) ? itemId[e] : 0;
    }

    public int maxStack(int e) {
        return valid(e) ? maxStack[e] : MAX_STACK;
    }

    public int maxDamage(int e) {
        return valid(e) ? maxDamage[e] : 0;
    }

    public int flags(int e) {
        return valid(e) ? flags[e] : 0;
    }

    public boolean flag(int e, int bit) {
        return (flags(e) & bit) != 0;
    }

    public int useKind(int e) {
        return valid(e) ? useKind[e] : 0;
    }

    public float meleeDamage(int e) {
        return valid(e) ? meleeDamage[e] : FIST_DAMAGE;
    }

    public float meleeSpeed(int e) {
        return valid(e) ? meleeSpeed[e] : FIST_SPEED;
    }

    public double attackMinRange(int e) {
        return valid(e) ? attackMinRange[e] : DEFAULT_ATTACK_MIN_RANGE;
    }

    public double attackMaxRange(int e) {
        return valid(e) ? attackMaxRange[e] : DEFAULT_ATTACK_RANGE;
    }

    public double attackHitboxMargin(int e) {
        return valid(e) ? attackHitboxMargin[e] : DEFAULT_ATTACK_HITBOX_MARGIN;
    }

    public int knockback(int e) {
        return valid(e) ? knockback[e] : 0;
    }

    public int weaponEnchants(int e) {
        return valid(e) ? weaponEnchants[e] : 0;
    }

    public int maceInfo(int e) {
        return valid(e) ? maceInfo[e] : 0;
    }

    public int rangedInfo(int e) {
        return valid(e) ? rangedInfo[e] : 0;
    }

    public int toolInfo(int e) {
        return valid(e) ? toolInfo[e] : 0;
    }

    public int sharpness(int e) {
        return sharpnessOf(weaponEnchants(e));
    }

    public int fireAspect(int e) {
        return fireAspectOf(weaponEnchants(e));
    }

    public int punch(int e) {
        return punchOf(weaponEnchants(e));
    }

    public int flame(int e) {
        return flameOf(weaponEnchants(e));
    }

    public boolean isMace(int e) {
        return isMaceOf(maceInfo(e));
    }

    public int windBurst(int e) {
        return windBurstOf(maceInfo(e));
    }

    public int density(int e) {
        return densityOf(maceInfo(e));
    }

    public int breach(int e) {
        return breachOf(maceInfo(e));
    }

    public int bowPower(int e) {
        return bowPowerOf(rangedInfo(e));
    }

    public int quickCharge(int e) {
        return quickChargeOf(rangedInfo(e));
    }

    public boolean multishot(int e) {
        return multishotOf(rangedInfo(e)) != 0;
    }

    public boolean infinity(int e) {
        return infinityOf(rangedInfo(e));
    }

    public int piercing(int e) {
        return piercingOf(rangedInfo(e));
    }

    public int crossbowLoadTicks(int e) {
        return Math.max(MIN_CROSSBOW_LOAD, 25 - 5 * quickCharge(e));
    }

    public int tier(int e) {
        return tierOf(toolInfo(e));
    }

    public int efficiency(int e) {
        return efficiencyOf(toolInfo(e));
    }

    public int toolClass(int e) {
        return toolClassOf(toolInfo(e));
    }

    public boolean aquaAffinity(int e) {
        return aquaAffinityOf(toolInfo(e));
    }

    public int foodNutrition(int e) {
        return valid(e) ? foodNutrition[e] : 0;
    }

    public float foodSaturation(int e) {
        return valid(e) ? foodSaturation[e] : 0f;
    }

    public int foodEatTicks(int e) {
        return valid(e) ? foodEatTicks[e] : 0;
    }

    public boolean alwaysEdible(int e) {
        return flag(e, FLAG_ALWAYS_EDIBLE);
    }

    public int fireworkFlight(int e) {
        return valid(e) ? fireworkFlight[e] : 0;
    }

    public int effect(int e, int i) {
        if (!valid(e)) {
            return 0;
        }
        return switch (i) {
            case 0 -> effect0[e];
            case 1 -> effect1[e];
            case 2 -> effect2[e];
            default -> effect3[e];
        };
    }

    public int armorPoints(int e) {
        return valid(e) ? armorPoints[e] : 0;
    }

    public float armorToughness(int e) {
        return valid(e) ? armorToughness[e] : 0f;
    }

    public float armorKbResistance(int e) {
        return valid(e) ? armorKbResistance[e] : 0f;
    }

    public int armorProtection(int e) {
        return valid(e) ? armorProtection[e] : 0;
    }

    public int armorBlastProtection(int e) {
        return valid(e) ? armorBlastProtection[e] : 0;
    }

    public int armorProjectileProtection(int e) {
        return valid(e) ? armorProjectileProtection[e] : 0;
    }

    public int armorFireProtection(int e) {
        return valid(e) ? armorFireProtection[e] : 0;
    }

    public int armorFeatherFalling(int e) {
        return valid(e) ? armorFeatherFalling[e] : 0;
    }

    public int equipSlot(int e) {
        return valid(e) ? equipSlot[e] : EQUIP_NONE;
    }

    public int containerSeed(int e) {
        return valid(e) ? containerSeed[e] : -1;
    }

    public boolean isBlock(int e) {
        return flag(e, FLAG_BLOCK);
    }

    public boolean isArrowPlain(int e) {
        return flag(e, FLAG_ARROW_PLAIN);
    }

    public boolean isArrowSpecial(int e) {
        return flag(e, FLAG_ARROW_SPECIAL);
    }

    public boolean isArrow(int e) {
        return flag(e, FLAG_ARROW_PLAIN | FLAG_ARROW_SPECIAL);
    }

    public boolean isAxe(int e) {
        return flag(e, FLAG_AXE);
    }

    public boolean isSword(int e) {
        return flag(e, FLAG_SWORD);
    }

    public boolean isShears(int e) {
        return flag(e, FLAG_SHEARS);
    }

    public boolean isSpear(int e) {
        return flag(e, FLAG_SPEAR);
    }

    public boolean isBow(int e) {
        return flag(e, FLAG_BOW);
    }

    public boolean isCrossbow(int e) {
        return flag(e, FLAG_CROSSBOW);
    }

    public boolean isTotem(int e) {
        return flag(e, FLAG_TOTEM);
    }

    public boolean isElytra(int e) {
        return flag(e, FLAG_ELYTRA);
    }

    public boolean isShield(int e) {
        return flag(e, FLAG_SHIELD);
    }

    public boolean crossbowCharged(int e) {
        return flag(e, FLAG_CROSSBOW_CHARGED);
    }

    public boolean isBucketEmpty(int e) {
        return flag(e, FLAG_BUCKET_EMPTY);
    }

    public boolean isBucketWater(int e) {
        return flag(e, FLAG_BUCKET_WATER);
    }

    public boolean isBucketLava(int e) {
        return flag(e, FLAG_BUCKET_LAVA);
    }

    public boolean isEndCrystal(int e) {
        return flag(e, FLAG_END_CRYSTAL);
    }

    public boolean isRespawnAnchor(int e) {
        return flag(e, FLAG_RESPAWN_ANCHOR);
    }

    public boolean isGlowstone(int e) {
        return flag(e, FLAG_GLOWSTONE);
    }

    public boolean isEnderChest(int e) {
        return flag(e, FLAG_ENDER_CHEST);
    }

    public boolean isShulker(int e) {
        return flag(e, FLAG_SHULKER);
    }

    private long computeDigest() {
        long h = 0xcbf29ce484222325L;
        h = mix(h, size);
        for (int i = 0; i < size; i++) {
            h = mix(h, itemId[i]);
            h = mix(h, maxStack[i]);
            h = mix(h, maxDamage[i]);
            h = mix(h, flags[i]);
            h = mix(h, useKind[i]);
            h = mix(h, knockback[i]);
            h = mix(h, weaponEnchants[i]);
            h = mix(h, maceInfo[i]);
            h = mix(h, rangedInfo[i]);
            h = mix(h, toolInfo[i]);
            h = mix(h, foodNutrition[i]);
            h = mix(h, foodEatTicks[i]);
            h = mix(h, fireworkFlight[i]);
            h = mix(h, effect0[i]);
            h = mix(h, effect1[i]);
            h = mix(h, effect2[i]);
            h = mix(h, effect3[i]);
            h = mix(h, armorPoints[i]);
            h = mix(h, armorProtection[i]);
            h = mix(h, armorBlastProtection[i]);
            h = mix(h, armorProjectileProtection[i]);
            h = mix(h, armorFireProtection[i]);
            h = mix(h, armorFeatherFalling[i]);
            h = mix(h, equipSlot[i]);
            h = mix(h, containerSeed[i]);
            h = mix(h, Float.floatToRawIntBits(meleeDamage[i]));
            h = mix(h, Float.floatToRawIntBits(meleeSpeed[i]));
            h = mix(h, Float.floatToRawIntBits(foodSaturation[i]));
            h = mix(h, Float.floatToRawIntBits(armorToughness[i]));
            h = mix(h, Float.floatToRawIntBits(armorKbResistance[i]));
        }
        return h;
    }

    private static long mix(long h, int v) {
        long x = v & 0xFFFFFFFFL;
        for (int i = 0; i < 8; i++) {
            h ^= (x & 0xFF);
            h *= 0x100000001b3L;
            x >>>= 8;
        }
        return h;
    }

    public static int packEffect(int id, int amplifier, int durationTicks) {
        return (id & 0xFF) | ((amplifier & 0xFF) << 8) | ((durationTicks & 0xFFFF) << 16);
    }

    public static int effectId(int packed) {
        return packed & 0xFF;
    }

    public static int effectAmplifier(int packed) {
        return (packed >> 8) & 0xFF;
    }

    public static int effectDuration(int packed) {
        return (packed >> 16) & 0xFFFF;
    }

    public static int packWeapon(int sharpness, int fireAspect, int punch, int flame) {
        return (sharpness & 0x1F) | ((fireAspect & 0x7) << 5) | ((punch & 0x7) << 8)
                | (flame != 0 ? 0x800 : 0);
    }

    public static int sharpnessOf(int weaponEnchants) {
        return weaponEnchants & 0x1F;
    }

    public static int fireAspectOf(int weaponEnchants) {
        return (weaponEnchants >> 5) & 0x7;
    }

    public static int punchOf(int weaponEnchants) {
        return (weaponEnchants >> 8) & 0x7;
    }

    public static int flameOf(int weaponEnchants) {
        return (weaponEnchants >> 11) & 0x1;
    }

    public static int packMace(boolean isMace, int windBurst, int density, int breach) {
        return (isMace ? 1 : 0) | ((windBurst & 0x7) << 1) | ((density & 0xF) << 4)
                | ((breach & 0xF) << 8);
    }

    public static boolean isMaceOf(int maceInfo) {
        return (maceInfo & 0x1) != 0;
    }

    public static int windBurstOf(int maceInfo) {
        return (maceInfo >> 1) & 0x7;
    }

    public static int densityOf(int maceInfo) {
        return (maceInfo >> 4) & 0xF;
    }

    public static int breachOf(int maceInfo) {
        return (maceInfo >> 8) & 0xF;
    }

    public static int packRanged(int bowPower, int quickCharge, int multishot, boolean infinity,
                                 int piercing) {
        return (bowPower & 0x7) | ((quickCharge & 0x7) << 3) | ((multishot & 0x1) << 6)
                | (infinity ? 0x80 : 0) | ((piercing & 0x7) << 8);
    }

    public static int bowPowerOf(int rangedInfo) {
        return rangedInfo & 0x7;
    }

    public static int quickChargeOf(int rangedInfo) {
        return (rangedInfo >> 3) & 0x7;
    }

    public static int multishotOf(int rangedInfo) {
        return (rangedInfo >> 6) & 0x1;
    }

    public static boolean infinityOf(int rangedInfo) {
        return (rangedInfo & 0x80) != 0;
    }

    public static int piercingOf(int rangedInfo) {
        return (rangedInfo >> 8) & 0x7;
    }

    public static int packTool(int tier, int efficiency, int toolClass, boolean aquaAffinity) {
        return (tier & 0x7) | ((efficiency & 0x7) << 3) | ((toolClass & 0x7) << 6)
                | (aquaAffinity ? 0x200 : 0);
    }

    public static int tierOf(int toolInfo) {
        return toolInfo & 0x7;
    }

    public static int efficiencyOf(int toolInfo) {
        return (toolInfo >> 3) & 0x7;
    }

    public static int toolClassOf(int toolInfo) {
        return (toolInfo >> 6) & 0x7;
    }

    public static boolean aquaAffinityOf(int toolInfo) {
        return (toolInfo & 0x200) != 0;
    }

    public static int packCrossbow(int loadTicks, boolean multishot) {
        return (loadTicks & 0xFF) | (multishot ? 0x100 : 0);
    }

    public static int equipSlotToInventory(int equip) {
        return switch (equip) {
            case EQUIP_FEET -> ARMOR_FEET;
            case EQUIP_LEGS -> ARMOR_LEGS;
            case EQUIP_CHEST -> ARMOR_CHEST;
            case EQUIP_HEAD -> ARMOR_HEAD;
            default -> -1;
        };
    }
}
