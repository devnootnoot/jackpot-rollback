package me.nootnoot.sim.state;

public final class PlayerState {
    public static final int NO_CROSSBOW_HELD_USE = -1;

    public double x;
    public double y;
    public double z;

    public double vx;
    public double vy;
    public double vz;

    public float yaw;
    public float pitch;

    public boolean onGround;
    public boolean sprinting;
    public boolean sneaking;

    public boolean swimming;

    public int eatGap;

    public int offhandEatGap;

    public boolean submergedEye;

    public float health;
    public float maxHealth = 20.0f;
    public int hurtTime;
    public float lastDamage;
    public int attackTicker;
    public int missTicks;
    public int jumpCooldown;
    public boolean dead;
    public int heldSlot;
    public int heldUseKind;
    public int heldItemId;
    public int offhandItemId;

    public int armorFeetId;
    public int armorLegsId;
    public int armorChestId;
    public int armorHeadId;

    public float absorption;
    public int[] effectTicks = new int[Effects.COUNT];
    public int[] effectAmp = new int[Effects.COUNT];
    public int[] effectCounter = new int[Effects.COUNT];

    public float food = 20.0f;
    public float saturation = 5.0f;
    public float exhaustion = 0.0f;
    public int regenTimer = 0;

    public float attackDamage = 6.0f;
    public float attackSpeed = 1.6f;
    public int knockbackLevel;

    public int[] slotEntry = new int[ItemDict.SLOTS];
    public int[] slotCount = new int[ItemDict.SLOTS];
    public int[] slotDamage = new int[ItemDict.SLOTS];
    public boolean[] slotCrossbowLoaded = new boolean[ItemDict.SLOTS];
    public boolean[] slotCrossbowConsumed = new boolean[ItemDict.SLOTS];
    public int[] slotCrossbowEntry = new int[ItemDict.SLOTS];
    public int crossbowHeldUseSlot = NO_CROSSBOW_HELD_USE;

    public int cursorEntry = ItemDict.NONE;
    public int cursorCount;
    public int cursorDamage;
    public boolean cursorCrossbowLoaded;
    public boolean cursorCrossbowConsumed;
    public int cursorCrossbowEntry = ItemDict.NONE;

    public int openContainer = -1;
    public long openContainerKey = Long.MIN_VALUE;
    public int enderContainer = -1;
    public int invActionSeq;
    public int dropSeq;
    public int lastDropSlot;
    public int lastDropItemId;
    public int lastDropCount;

    public int consumeSeq;
    public int consumeSlot;

    public int arrowsConsumed;

    public int offhandConsumeSeq;
    public int offhandEatTicks;

    public int eatTicks;
    public boolean eating;

    public int arrows;
    public int drawTicks;

    public int blockTicks;
    public int shieldDisabled;
    public int lastBlockHitTick = -1000;
    public int meleeClaimTick = -1000;
    public int meleeClaimsGranted;
    public int meleeClaimsOffAim;
    public boolean tookFireDamageThisTick;

    public boolean hasTotem;
    public int totemSeq;
    public int potionsThrown;

    public boolean hasElytra;
    public boolean gliding;
    public int fireworkTicks;
    public boolean prevJump;
    public boolean prevElytraStart;
    public float armor;
    public float armorToughness;
    public float protection;
    public int blastProtection;
    public int projectileProtection;
    public int fireProtection;
    public int featherFalling;
    public float kbResistance;
    public int pearls;

    public float fallDistance;
    public int authoritySuspendTicks;
    public int impulseHoldTicks;
    public double impulseVx;
    public double impulseVy;
    public double impulseVz;
    public int impulseSeq;

    public int noFallTicks;

    public int cageFallTicks;

    public int fireTicks;

    public static final int USE_KINDS = 12;
    public int[] useCooldown = new int[USE_KINDS];

    public int useDelay;

    public int destroyDelay;

    public long miningTarget = Long.MIN_VALUE;
    public float miningProgress;

    public int pickupSeq;
    public int lastPickupItemId;
    public int lastPickupCount;
    public int lastPickupDropUid;

    public static final int PICKUP_RING = 16;
    public final int[] pickupRingItemId = new int[PICKUP_RING];
    public final int[] pickupRingCount = new int[PICKUP_RING];
    public final int[] pickupRingDropUid = new int[PICKUP_RING];

    public int toolDamageSeq;
    public int armorDamageSeq;
    public int armorDamageAmount;

    public final ClickBudget clickBudget = new ClickBudget();

    public boolean prevAttack;
    public boolean prevUse;
    public boolean prevOffhandUse;
    public boolean prevUsePress;
    public boolean prevDrop;
    public boolean prevInvClick;

    public boolean ready;
    public boolean instaReady;

    public static final int REWIND_FRAMES = 25;
    public final double[] rewindX = new double[REWIND_FRAMES];
    public final double[] rewindY = new double[REWIND_FRAMES];
    public final double[] rewindZ = new double[REWIND_FRAMES];
    public final double[] rewindHeight = new double[REWIND_FRAMES];
    public int rewindPos;
    public int rewindFilled;

    public PlayerState copy() {
        PlayerState p = new PlayerState();
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        p.yaw = yaw;
        p.pitch = pitch;
        p.onGround = onGround;
        p.sprinting = sprinting;
        p.sneaking = sneaking;
        p.swimming = swimming;
        p.eatGap = eatGap;
        p.offhandEatGap = offhandEatGap;
        p.submergedEye = submergedEye;
        p.health = health;
        p.maxHealth = maxHealth;
        p.hurtTime = hurtTime;
        p.lastDamage = lastDamage;
        p.attackTicker = attackTicker;
        p.missTicks = missTicks;
        p.jumpCooldown = jumpCooldown;
        p.dead = dead;
        p.heldSlot = heldSlot;
        p.heldUseKind = heldUseKind;
        p.heldItemId = heldItemId;
        p.offhandItemId = offhandItemId;
        p.armorFeetId = armorFeetId;
        p.armorLegsId = armorLegsId;
        p.armorChestId = armorChestId;
        p.armorHeadId = armorHeadId;
        p.absorption = absorption;
        p.effectTicks = effectTicks.clone();
        p.effectAmp = effectAmp.clone();
        p.effectCounter = effectCounter.clone();
        p.food = food;
        p.saturation = saturation;
        p.exhaustion = exhaustion;
        p.regenTimer = regenTimer;
        p.clickBudget.clear();
        p.prevAttack = prevAttack;
        p.prevUse = prevUse;
        p.prevOffhandUse = prevOffhandUse;
        p.prevUsePress = prevUsePress;
        p.prevDrop = prevDrop;
        p.prevInvClick = prevInvClick;
        p.attackDamage = attackDamage;
        p.attackSpeed = attackSpeed;
        p.knockbackLevel = knockbackLevel;
        p.slotEntry = slotEntry.clone();
        p.slotCount = slotCount.clone();
        p.slotDamage = slotDamage.clone();
        p.slotCrossbowLoaded = slotCrossbowLoaded.clone();
        p.slotCrossbowConsumed = slotCrossbowConsumed.clone();
        p.slotCrossbowEntry = slotCrossbowEntry.clone();
        p.crossbowHeldUseSlot = crossbowHeldUseSlot;
        p.cursorEntry = cursorEntry;
        p.cursorCount = cursorCount;
        p.cursorDamage = cursorDamage;
        p.cursorCrossbowLoaded = cursorCrossbowLoaded;
        p.cursorCrossbowConsumed = cursorCrossbowConsumed;
        p.cursorCrossbowEntry = cursorCrossbowEntry;
        p.openContainer = openContainer;
        p.openContainerKey = openContainerKey;
        p.enderContainer = enderContainer;
        p.invActionSeq = invActionSeq;
        p.dropSeq = dropSeq;
        p.lastDropSlot = lastDropSlot;
        p.lastDropItemId = lastDropItemId;
        p.lastDropCount = lastDropCount;
        p.consumeSeq = consumeSeq;
        p.consumeSlot = consumeSlot;
        p.arrowsConsumed = arrowsConsumed;
        p.offhandConsumeSeq = offhandConsumeSeq;
        p.offhandEatTicks = offhandEatTicks;
        p.eatTicks = eatTicks;
        p.eating = eating;
        p.arrows = arrows;
        p.drawTicks = drawTicks;
        p.blockTicks = blockTicks;
        p.shieldDisabled = shieldDisabled;
        p.lastBlockHitTick = lastBlockHitTick;
        p.meleeClaimTick = meleeClaimTick;
        p.meleeClaimsGranted = meleeClaimsGranted;
        p.meleeClaimsOffAim = meleeClaimsOffAim;
        p.tookFireDamageThisTick = tookFireDamageThisTick;
        p.hasTotem = hasTotem;
        p.totemSeq = totemSeq;
        p.potionsThrown = potionsThrown;
        p.hasElytra = hasElytra;
        p.gliding = gliding;
        p.fireworkTicks = fireworkTicks;
        p.prevJump = prevJump;
        p.prevElytraStart = prevElytraStart;
        p.armor = armor;
        p.armorToughness = armorToughness;
        p.protection = protection;
        p.blastProtection = blastProtection;
        p.projectileProtection = projectileProtection;
        p.fireProtection = fireProtection;
        p.featherFalling = featherFalling;
        p.kbResistance = kbResistance;
        p.pearls = pearls;
        p.fallDistance = fallDistance;
        p.authoritySuspendTicks = authoritySuspendTicks;
        p.impulseHoldTicks = impulseHoldTicks;
        p.impulseVx = impulseVx;
        p.impulseVy = impulseVy;
        p.impulseVz = impulseVz;
        p.impulseSeq = impulseSeq;
        p.noFallTicks = noFallTicks;
        p.cageFallTicks = cageFallTicks;
        p.fireTicks = fireTicks;
        p.useCooldown = useCooldown.clone();
        p.useDelay = useDelay;
        p.destroyDelay = destroyDelay;
        p.miningTarget = miningTarget;
        p.miningProgress = miningProgress;
        p.pickupSeq = pickupSeq;
        p.lastPickupItemId = lastPickupItemId;
        p.lastPickupCount = lastPickupCount;
        p.lastPickupDropUid = lastPickupDropUid;
        System.arraycopy(pickupRingItemId, 0, p.pickupRingItemId, 0, PICKUP_RING);
        System.arraycopy(pickupRingCount, 0, p.pickupRingCount, 0, PICKUP_RING);
        System.arraycopy(pickupRingDropUid, 0, p.pickupRingDropUid, 0, PICKUP_RING);
        p.toolDamageSeq = toolDamageSeq;
        p.armorDamageSeq = armorDamageSeq;
        p.armorDamageAmount = armorDamageAmount;
        p.ready = ready;
        p.instaReady = instaReady;
        System.arraycopy(rewindX, 0, p.rewindX, 0, REWIND_FRAMES);
        System.arraycopy(rewindY, 0, p.rewindY, 0, REWIND_FRAMES);
        System.arraycopy(rewindZ, 0, p.rewindZ, 0, REWIND_FRAMES);
        System.arraycopy(rewindHeight, 0, p.rewindHeight, 0, REWIND_FRAMES);
        p.rewindPos = rewindPos;
        p.rewindFilled = rewindFilled;
        return p;
    }
}
