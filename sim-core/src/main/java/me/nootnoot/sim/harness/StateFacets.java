package me.nootnoot.sim.harness;

import java.util.Arrays;
import java.util.Map;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;

public final class StateFacets {
    public static final String[] NAMES = {
            "round",
            "setup",
            "events",
            "p0.motion",
            "p0.vitals",
            "p0.combat",
            "p0.inventory",
            "p1.motion",
            "p1.vitals",
            "p1.combat",
            "p1.inventory",
            "projectiles",
            "blocks",
            "containers",
            "items",
            "crystals",
            "fluids",
    };

    public static final int COUNT = NAMES.length;

    private static final int ROUND = 0;
    private static final int SETUP = 1;
    private static final int EVENTS = 2;
    private static final int P0_MOTION = 3;
    private static final int PLAYER_STRIDE = 4;
    private static final int PROJECTILES = 11;
    private static final int BLOCKS = 12;
    private static final int CONTAINERS = 13;
    private static final int ITEMS = 14;
    private static final int CRYSTALS = 15;
    private static final int FLUIDS = 16;

    public static final String[] GAME_STATE_FIELDS = {
            "tick", "players", "projectiles", "nextProjectileId", "blocks", "crystals",
            "nextCrystalId", "anchors", "items", "nextItemId", "itemsRefused", "brokenArena",
            "vanillaBuild", "allowExplosion", "allowBucket", "potSwordBoost",
            "breakableItemIds", "placeableItemIds", "blockResistance",
            "fluids", "cobwebs", "fires",
            "cobwebItemId", "stringItemId", "obsidianItemId", "cobblestoneItemId", "mudItemId",
            "glowstoneItemId", "glowstoneDustItemId", "playCenterX", "playCenterZ", "playRadius",
            "playCircular", "roundWinsP0", "roundWinsP1", "roundsTarget", "roundResetCountdown",
            "awaitingReady", "roundStartGrace", "roundMatchOver", "roundMatchWinner",
            "roundInitial", "dict", "blockProps", "edgeHosted", "containers", "blockContainers",
            "roundInitialContainers", "nextContainerId", "events", "blastCellBudget",
            "blastMarchBudget", "blastSeq",
    };

    public static final String[] PLAYER_STATE_FIELDS = {
            "x", "y", "z", "vx", "vy", "vz", "yaw", "pitch", "onGround", "sprinting", "sneaking",
            "swimming", "submergedEye", "jumpCooldown", "fallDistance", "authoritySuspendTicks",
            "impulseVx", "impulseVy", "impulseVz", "impulseSeq",
            "noFallTicks",
            "cageFallTicks", "hasElytra", "gliding", "fireworkTicks", "prevJump",
            "prevElytraStart", "rewindX", "rewindY", "rewindZ", "rewindHeight", "rewindPos",
            "rewindFilled", "health", "maxHealth", "hurtTime", "lastDamage", "dead", "absorption",
            "effectTicks", "effectAmp", "effectCounter", "food", "saturation", "exhaustion",
            "regenTimer", "fireTicks", "tookFireDamageThisTick", "ready", "instaReady",
            "attackTicker", "missTicks", "attackDamage", "attackSpeed",
            "knockbackLevel", "armor", "armorToughness", "protection", "blastProtection",
            "projectileProtection",
            "fireProtection", "featherFalling", "kbResistance", "blockTicks", "shieldDisabled",
            "lastBlockHitTick", "meleeClaimTick", "meleeClaimsGranted", "meleeClaimsOffAim",
            "hasTotem", "totemSeq", "potionsThrown",
            "arrows", "drawTicks", "prevAttack", "prevUse", "prevOffhandUse", "prevUsePress",
            "prevDrop", "prevInvClick", "eatGap", "offhandEatGap", "heldSlot", "heldUseKind",
            "heldItemId", "offhandItemId", "armorFeetId", "armorLegsId", "armorChestId",
            "armorHeadId", "slotEntry", "slotCount", "slotDamage", "slotCrossbowLoaded",
            "slotCrossbowConsumed", "slotCrossbowEntry", "crossbowHeldUseSlot", "cursorEntry",
            "cursorCount", "cursorDamage", "cursorCrossbowLoaded", "cursorCrossbowConsumed",
            "cursorCrossbowEntry", "openContainer", "openContainerKey", "enderContainer",
            "invActionSeq", "dropSeq", "lastDropSlot", "lastDropItemId", "lastDropCount",
            "consumeSeq", "consumeSlot", "arrowsConsumed", "offhandConsumeSeq", "offhandEatTicks",
            "eatTicks", "eating", "pearls", "useCooldown", "useDelay", "destroyDelay",
            "miningTarget", "miningProgress", "pickupSeq", "lastPickupItemId", "lastPickupCount",
            "lastPickupDropUid", "pickupRingItemId", "pickupRingCount", "pickupRingDropUid",
            "toolDamageSeq", "armorDamageSeq", "armorDamageAmount",
    };

    public static final String[] NOT_CHECKSUMMED = {"itemGrid", "clickBudget"};

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private StateFacets() {
    }

    public static String name(int facet) {
        return NAMES[facet];
    }

    public static int[] of(GameState s) {
        long[] h = new long[COUNT];
        Arrays.fill(h, FNV_OFFSET);

        h[ROUND] = mixInt(h[ROUND], s.tick);
        h[ROUND] = mixInt(h[ROUND], s.roundWinsP0);
        h[ROUND] = mixInt(h[ROUND], s.roundWinsP1);
        h[ROUND] = mixInt(h[ROUND], s.roundsTarget);
        h[ROUND] = mixInt(h[ROUND], s.roundResetCountdown);
        h[ROUND] = mixInt(h[ROUND], s.awaitingReady ? 1 : 0);
        h[ROUND] = mixInt(h[ROUND], s.roundStartGrace);
        h[ROUND] = mixInt(h[ROUND], s.roundMatchOver ? 1 : 0);
        h[ROUND] = mixInt(h[ROUND], s.roundMatchWinner);
        h[ROUND] = mixInt(h[ROUND], s.roundInitial == null ? -1 : s.roundInitial.length);
        if (s.roundInitial != null) {
            for (PlayerState seed : s.roundInitial) {
                h[ROUND] = mixInt(h[ROUND], seed == null ? 0 : 1);
                if (seed != null) {
                    long[] parts = new long[PLAYER_STRIDE];
                    Arrays.fill(parts, FNV_OFFSET);
                    player(parts, 0, seed);
                    for (long p : parts) {
                        h[ROUND] = mixLong(h[ROUND], p);
                    }
                }
            }
        }

        h[SETUP] = mixInt(h[SETUP], s.nextProjectileId);
        h[SETUP] = mixLong(h[SETUP], s.dict.digest());
        h[SETUP] = mixLong(h[SETUP], s.blockProps.digest());
        h[SETUP] = mixInt(h[SETUP], s.edgeHosted[0] ? 1 : 0);
        h[SETUP] = mixInt(h[SETUP], s.edgeHosted[1] ? 1 : 0);
        h[SETUP] = mixInt(h[SETUP], s.blastCellBudget);
        h[SETUP] = mixInt(h[SETUP], s.blastMarchBudget);
        h[SETUP] = mixInt(h[SETUP], s.blastSeq);
        h[SETUP] = mixInt(h[SETUP], s.vanillaBuild ? 1 : 0);
        h[SETUP] = mixInt(h[SETUP], s.allowExplosion ? 1 : 0);
        h[SETUP] = mixInt(h[SETUP], s.allowBucket ? 1 : 0);
        h[SETUP] = mixInt(h[SETUP], s.potSwordBoost ? 1 : 0);
        h[SETUP] = mixItemIds(h[SETUP], s.breakableItemIds, 0x42524B31);
        h[SETUP] = mixItemIds(h[SETUP], s.placeableItemIds, 0x504C4331);
        h[SETUP] = mixInt(h[SETUP], s.cobwebItemId);
        h[SETUP] = mixInt(h[SETUP], s.stringItemId);
        h[SETUP] = mixInt(h[SETUP], s.obsidianItemId);
        h[SETUP] = mixInt(h[SETUP], s.cobblestoneItemId);
        h[SETUP] = mixInt(h[SETUP], s.mudItemId);
        h[SETUP] = mixInt(h[SETUP], s.glowstoneItemId);
        h[SETUP] = mixInt(h[SETUP], s.glowstoneDustItemId);
        h[SETUP] = mixDouble(h[SETUP], s.playCenterX);
        h[SETUP] = mixDouble(h[SETUP], s.playCenterZ);
        h[SETUP] = mixDouble(h[SETUP], s.playRadius);
        h[SETUP] = mixInt(h[SETUP], s.playCircular ? 1 : 0);

        h[EVENTS] = mixInt(h[EVENTS], s.events.size());
        for (CombatEvent e : s.events) {
            h[EVENTS] = mixInt(h[EVENTS], e.type());
            h[EVENTS] = mixInt(h[EVENTS], e.attacker());
            h[EVENTS] = mixInt(h[EVENTS], e.victim());
            h[EVENTS] = mixInt(h[EVENTS], e.crit() ? 1 : 0);
            h[EVENTS] = mixInt(h[EVENTS], e.kind());
        }

        player(h, P0_MOTION, s.players[0]);
        player(h, P0_MOTION + PLAYER_STRIDE, s.players[1]);

        h[PROJECTILES] = mixInt(h[PROJECTILES], s.projectiles.size());
        for (ProjectileState p : s.projectiles) {
            h[PROJECTILES] = projectile(h[PROJECTILES], p);
        }

        long[] blockKeys = s.blocks.sortedKeys();
        h[BLOCKS] = mixInt(h[BLOCKS], blockKeys.length);
        for (long k : blockKeys) {
            h[BLOCKS] = mixLong(h[BLOCKS], k);
            h[BLOCKS] = mixInt(h[BLOCKS], s.blocks.idAtKey(k));
        }
        long[] brokenKeys = new long[s.brokenArena.size()];
        int bi = 0;
        for (long k : s.brokenArena) {
            brokenKeys[bi++] = k;
        }
        Arrays.sort(brokenKeys);
        h[BLOCKS] = mixInt(h[BLOCKS], brokenKeys.length);
        for (long k : brokenKeys) {
            h[BLOCKS] = mixLong(h[BLOCKS], k);
        }
        long[] resKeys = sortedKeys(s.blockResistance);
        h[BLOCKS] = mixInt(h[BLOCKS], resKeys.length);
        for (long k : resKeys) {
            h[BLOCKS] = mixLong(h[BLOCKS], k);
            h[BLOCKS] = mixFloat(h[BLOCKS], s.blockResistance.get(k));
        }

        h[CONTAINERS] = containers(h[CONTAINERS], s.containers);
        h[CONTAINERS] = containers(h[CONTAINERS], s.roundInitialContainers);
        long[] blockContainerKeys = sortedKeys(s.blockContainers);
        h[CONTAINERS] = mixInt(h[CONTAINERS], blockContainerKeys.length);
        for (long k : blockContainerKeys) {
            h[CONTAINERS] = mixLong(h[CONTAINERS], k);
            h[CONTAINERS] = mixInt(h[CONTAINERS], s.blockContainers.get(k));
        }
        h[CONTAINERS] = mixInt(h[CONTAINERS], s.nextContainerId);

        h[ITEMS] = mixInt(h[ITEMS], s.items.size());
        for (ItemEntityState e : s.items) {
            h[ITEMS] = mixInt(h[ITEMS], e.id);
            h[ITEMS] = mixInt(h[ITEMS], e.dropUid);
            h[ITEMS] = mixInt(h[ITEMS], e.entry);
            h[ITEMS] = mixInt(h[ITEMS], e.itemId);
            h[ITEMS] = mixInt(h[ITEMS], e.count);
            h[ITEMS] = mixInt(h[ITEMS], e.damage);
            h[ITEMS] = mixDouble(h[ITEMS], e.x);
            h[ITEMS] = mixDouble(h[ITEMS], e.y);
            h[ITEMS] = mixDouble(h[ITEMS], e.z);
            h[ITEMS] = mixDouble(h[ITEMS], e.vx);
            h[ITEMS] = mixDouble(h[ITEMS], e.vy);
            h[ITEMS] = mixDouble(h[ITEMS], e.vz);
            h[ITEMS] = mixInt(h[ITEMS], e.pickupDelay);
            h[ITEMS] = mixInt(h[ITEMS], e.life);
            h[ITEMS] = mixInt(h[ITEMS], e.dead ? 1 : 0);
        }
        h[ITEMS] = mixInt(h[ITEMS], s.nextItemId);
        h[ITEMS] = mixInt(h[ITEMS], s.itemsRefused);

        h[CRYSTALS] = mixInt(h[CRYSTALS], s.crystals.size());
        for (CrystalState c : s.crystals) {
            h[CRYSTALS] = mixInt(h[CRYSTALS], c.id);
            h[CRYSTALS] = mixInt(h[CRYSTALS], c.owner);
            h[CRYSTALS] = mixInt(h[CRYSTALS], c.bx);
            h[CRYSTALS] = mixInt(h[CRYSTALS], c.by);
            h[CRYSTALS] = mixInt(h[CRYSTALS], c.bz);
        }
        h[CRYSTALS] = mixInt(h[CRYSTALS], s.nextCrystalId);
        long[] anchorKeys = sortedKeys(s.anchors);
        h[CRYSTALS] = mixInt(h[CRYSTALS], anchorKeys.length);
        for (long k : anchorKeys) {
            h[CRYSTALS] = mixLong(h[CRYSTALS], k);
            h[CRYSTALS] = mixInt(h[CRYSTALS], s.anchors.get(k));
        }

        h[FLUIDS] = intMap(h[FLUIDS], s.fluids);
        h[FLUIDS] = intMap(h[FLUIDS], s.cobwebs);
        h[FLUIDS] = intMap(h[FLUIDS], s.fires);

        int[] out = new int[COUNT];
        for (int i = 0; i < COUNT; i++) {
            out[i] = (int) (h[i] ^ (h[i] >>> 32));
        }
        return out;
    }

    private static long[] sortedKeys(Map<Long, ?> map) {
        long[] keys = new long[map.size()];
        int i = 0;
        for (long k : map.keySet()) {
            keys[i++] = k;
        }
        Arrays.sort(keys);
        return keys;
    }

    private static long intMap(long h, Map<Long, Integer> map) {
        long[] keys = sortedKeys(map);
        h = mixInt(h, keys.length);
        for (long k : keys) {
            h = mixLong(h, k);
            h = mixInt(h, map.get(k));
        }
        return h;
    }

    private static long containers(long h, Map<Integer, Container> map) {
        int[] ids = new int[map.size()];
        int i = 0;
        for (int k : map.keySet()) {
            ids[i++] = k;
        }
        Arrays.sort(ids);
        h = mixInt(h, ids.length);
        for (int id : ids) {
            Container c = map.get(id);
            h = mixInt(h, id);
            for (int cell = 0; cell < Container.CELLS; cell++) {
                h = mixInt(h, c.entry[cell]);
                h = mixInt(h, c.count[cell]);
                h = mixInt(h, c.damage[cell]);
            }
        }
        return h;
    }

    private static void player(long[] h, int base, PlayerState p) {
        int motion = base;
        int vitals = base + 1;
        int combat = base + 2;
        int inventory = base + 3;

        h[motion] = mixDouble(h[motion], p.x);
        h[motion] = mixDouble(h[motion], p.y);
        h[motion] = mixDouble(h[motion], p.z);
        h[motion] = mixDouble(h[motion], p.vx);
        h[motion] = mixDouble(h[motion], p.vy);
        h[motion] = mixDouble(h[motion], p.vz);
        h[motion] = mixFloat(h[motion], p.yaw);
        h[motion] = mixFloat(h[motion], p.pitch);
        h[motion] = mixInt(h[motion], p.onGround ? 1 : 0);
        h[motion] = mixInt(h[motion], p.sprinting ? 1 : 0);
        h[motion] = mixInt(h[motion], p.sneaking ? 1 : 0);
        h[motion] = mixInt(h[motion], p.swimming ? 1 : 0);
        h[motion] = mixInt(h[motion], p.submergedEye ? 1 : 0);
        h[motion] = mixInt(h[motion], p.jumpCooldown);
        h[motion] = mixFloat(h[motion], p.fallDistance);
        h[motion] = mixInt(h[motion], p.authoritySuspendTicks);
        h[motion] = mixDouble(h[motion], p.impulseVx);
        h[motion] = mixDouble(h[motion], p.impulseVy);
        h[motion] = mixDouble(h[motion], p.impulseVz);
        h[motion] = mixInt(h[motion], p.impulseSeq);
        h[motion] = mixInt(h[motion], p.noFallTicks);
        h[motion] = mixInt(h[motion], p.cageFallTicks);
        h[motion] = mixInt(h[motion], p.hasElytra ? 1 : 0);
        h[motion] = mixInt(h[motion], p.gliding ? 1 : 0);
        h[motion] = mixInt(h[motion], p.fireworkTicks);
        h[motion] = mixInt(h[motion], p.prevJump ? 1 : 0);
        h[motion] = mixInt(h[motion], p.prevElytraStart ? 1 : 0);
        h[motion] = mixInt(h[motion], p.rewindPos);
        h[motion] = mixInt(h[motion], p.rewindFilled);
        for (int i = 0; i < PlayerState.REWIND_FRAMES; i++) {
            h[motion] = mixDouble(h[motion], p.rewindX[i]);
            h[motion] = mixDouble(h[motion], p.rewindY[i]);
            h[motion] = mixDouble(h[motion], p.rewindZ[i]);
            h[motion] = mixDouble(h[motion], p.rewindHeight[i]);
        }

        h[vitals] = mixFloat(h[vitals], p.health);
        h[vitals] = mixFloat(h[vitals], p.maxHealth);
        h[vitals] = mixInt(h[vitals], p.hurtTime);
        h[vitals] = mixFloat(h[vitals], p.lastDamage);
        h[vitals] = mixInt(h[vitals], p.dead ? 1 : 0);
        h[vitals] = mixFloat(h[vitals], p.absorption);
        for (int i = 0; i < Effects.COUNT; i++) {
            h[vitals] = mixInt(h[vitals], p.effectTicks[i]);
            h[vitals] = mixInt(h[vitals], p.effectAmp[i]);
            h[vitals] = mixInt(h[vitals], p.effectCounter[i]);
        }
        h[vitals] = mixFloat(h[vitals], p.food);
        h[vitals] = mixFloat(h[vitals], p.saturation);
        h[vitals] = mixFloat(h[vitals], p.exhaustion);
        h[vitals] = mixInt(h[vitals], p.regenTimer);
        h[vitals] = mixInt(h[vitals], p.fireTicks);
        h[vitals] = mixInt(h[vitals], p.tookFireDamageThisTick ? 1 : 0);
        h[vitals] = mixInt(h[vitals], p.ready ? 1 : 0);
        h[vitals] = mixInt(h[vitals], p.instaReady ? 1 : 0);

        h[combat] = mixInt(h[combat], p.attackTicker);
        h[combat] = mixInt(h[combat], p.missTicks);
        h[combat] = mixFloat(h[combat], p.attackDamage);
        h[combat] = mixFloat(h[combat], p.attackSpeed);
        h[combat] = mixInt(h[combat], p.knockbackLevel);
        h[combat] = mixFloat(h[combat], p.armor);
        h[combat] = mixFloat(h[combat], p.armorToughness);
        h[combat] = mixFloat(h[combat], p.protection);
        h[combat] = mixInt(h[combat], p.blastProtection);
        h[combat] = mixInt(h[combat], p.projectileProtection);
        h[combat] = mixInt(h[combat], p.fireProtection);
        h[combat] = mixInt(h[combat], p.featherFalling);
        h[combat] = mixFloat(h[combat], p.kbResistance);
        h[combat] = mixInt(h[combat], p.blockTicks);
        h[combat] = mixInt(h[combat], p.shieldDisabled);
        h[combat] = mixInt(h[combat], p.lastBlockHitTick);
        h[combat] = mixInt(h[combat], p.meleeClaimTick);
        h[combat] = mixInt(h[combat], p.meleeClaimsGranted);
        h[combat] = mixInt(h[combat], p.meleeClaimsOffAim);
        h[combat] = mixInt(h[combat], p.hasTotem ? 1 : 0);
        h[combat] = mixInt(h[combat], p.totemSeq);
        h[combat] = mixInt(h[combat], p.potionsThrown);
        h[combat] = mixInt(h[combat], p.arrows);
        h[combat] = mixInt(h[combat], p.drawTicks);
        h[combat] = mixInt(h[combat], p.prevAttack ? 1 : 0);
        h[combat] = mixInt(h[combat], p.prevUse ? 1 : 0);
        h[combat] = mixInt(h[combat], p.prevOffhandUse ? 1 : 0);
        h[combat] = mixInt(h[combat], p.prevUsePress ? 1 : 0);
        h[combat] = mixInt(h[combat], p.prevDrop ? 1 : 0);
        h[combat] = mixInt(h[combat], p.prevInvClick ? 1 : 0);

        h[inventory] = mixInt(h[inventory], p.eatGap);
        h[inventory] = mixInt(h[inventory], p.offhandEatGap);
        h[inventory] = mixInt(h[inventory], p.heldSlot);
        h[inventory] = mixInt(h[inventory], p.heldUseKind);
        h[inventory] = mixInt(h[inventory], p.heldItemId);
        h[inventory] = mixInt(h[inventory], p.offhandItemId);
        h[inventory] = mixInt(h[inventory], p.armorFeetId);
        h[inventory] = mixInt(h[inventory], p.armorLegsId);
        h[inventory] = mixInt(h[inventory], p.armorChestId);
        h[inventory] = mixInt(h[inventory], p.armorHeadId);
        for (int i = 0; i < ItemDict.SLOTS; i++) {
            h[inventory] = mixInt(h[inventory], p.slotEntry[i]);
            h[inventory] = mixInt(h[inventory], p.slotCount[i]);
            h[inventory] = mixInt(h[inventory], p.slotDamage[i]);
            h[inventory] = mixInt(h[inventory], p.slotCrossbowLoaded[i] ? 1 : 0);
            h[inventory] = mixInt(h[inventory], p.slotCrossbowConsumed[i] ? 1 : 0);
            h[inventory] = mixInt(h[inventory], p.slotCrossbowEntry[i]);
        }
        h[inventory] = mixInt(h[inventory], p.crossbowHeldUseSlot);
        h[inventory] = mixInt(h[inventory], p.cursorEntry);
        h[inventory] = mixInt(h[inventory], p.cursorCount);
        h[inventory] = mixInt(h[inventory], p.cursorDamage);
        h[inventory] = mixInt(h[inventory], p.cursorCrossbowLoaded ? 1 : 0);
        h[inventory] = mixInt(h[inventory], p.cursorCrossbowConsumed ? 1 : 0);
        h[inventory] = mixInt(h[inventory], p.cursorCrossbowEntry);
        h[inventory] = mixInt(h[inventory], p.openContainer);
        h[inventory] = mixLong(h[inventory], p.openContainerKey);
        h[inventory] = mixInt(h[inventory], p.enderContainer);
        h[inventory] = mixInt(h[inventory], p.invActionSeq);
        h[inventory] = mixInt(h[inventory], p.dropSeq);
        h[inventory] = mixInt(h[inventory], p.lastDropSlot);
        h[inventory] = mixInt(h[inventory], p.lastDropItemId);
        h[inventory] = mixInt(h[inventory], p.lastDropCount);
        h[inventory] = mixInt(h[inventory], p.consumeSeq);
        h[inventory] = mixInt(h[inventory], p.consumeSlot);
        h[inventory] = mixInt(h[inventory], p.arrowsConsumed);
        h[inventory] = mixInt(h[inventory], p.offhandConsumeSeq);
        h[inventory] = mixInt(h[inventory], p.offhandEatTicks);
        h[inventory] = mixInt(h[inventory], p.eatTicks);
        h[inventory] = mixInt(h[inventory], p.eating ? 1 : 0);
        h[inventory] = mixInt(h[inventory], p.pearls);
        for (int i = 0; i < p.useCooldown.length; i++) {
            h[inventory] = mixInt(h[inventory], p.useCooldown[i]);
        }
        h[inventory] = mixInt(h[inventory], p.useDelay);
        h[inventory] = mixInt(h[inventory], p.destroyDelay);
        h[inventory] = mixLong(h[inventory], p.miningTarget);
        h[inventory] = mixFloat(h[inventory], p.miningProgress);
        h[inventory] = mixInt(h[inventory], p.pickupSeq);
        h[inventory] = mixInt(h[inventory], p.lastPickupItemId);
        h[inventory] = mixInt(h[inventory], p.lastPickupCount);
        h[inventory] = mixInt(h[inventory], p.lastPickupDropUid);
        for (int i = 0; i < PlayerState.PICKUP_RING; i++) {
            h[inventory] = mixInt(h[inventory], p.pickupRingItemId[i]);
            h[inventory] = mixInt(h[inventory], p.pickupRingCount[i]);
            h[inventory] = mixInt(h[inventory], p.pickupRingDropUid[i]);
        }
        h[inventory] = mixInt(h[inventory], p.toolDamageSeq);
        h[inventory] = mixInt(h[inventory], p.armorDamageSeq);
        h[inventory] = mixInt(h[inventory], p.armorDamageAmount);
    }

    private static long projectile(long h, ProjectileState p) {
        h = mixInt(h, p.id);
        h = mixInt(h, p.type);
        h = mixInt(h, p.owner);
        h = mixDouble(h, p.x);
        h = mixDouble(h, p.y);
        h = mixDouble(h, p.z);
        h = mixDouble(h, p.vx);
        h = mixDouble(h, p.vy);
        h = mixDouble(h, p.vz);
        h = mixFloat(h, p.damage);
        h = mixInt(h, p.life);
        h = mixInt(h, p.fresh ? 1 : 0);
        h = mixInt(h, p.stuck ? 1 : 0);
        h = mixInt(h, p.shakeTime);
        h = mixInt(h, p.dead ? 1 : 0);
        h = mixInt(h, p.leftOwner ? 1 : 0);
        h = mixInt(h, p.claimSpent ? 1 : 0);
        h = mixInt(h, p.effect0);
        h = mixInt(h, p.effect1);
        h = mixInt(h, p.effect2);
        h = mixInt(h, p.effect3);
        h = mixInt(h, p.bowEnchants);
        h = mixInt(h, p.infiniteArrow ? 1 : 0);
        h = mixInt(h, p.arrowItemId);
        h = mixInt(h, p.arrowEntry);
        return h;
    }

    private static long mixDouble(long h, double v) {
        return mixLong(h, Double.doubleToRawLongBits(v));
    }

    private static long mixFloat(long h, float v) {
        return mixInt(h, Float.floatToRawIntBits(v));
    }

    private static long mixItemIds(long h, int[] ids, int tag) {
        if (ids == null || ids.length == 0) {
            return h;
        }
        long mixed = mixInt(mixInt(h, tag), ids.length);
        for (int id : ids) {
            mixed = mixInt(mixed, id);
        }
        return mixed;
    }

    private static long mixInt(long h, int v) {
        return mixLong(h, v & 0xFFFFFFFFL);
    }

    private static long mixLong(long h, long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v & 0xFF);
            h *= FNV_PRIME;
            v >>>= 8;
        }
        return h;
    }
}
