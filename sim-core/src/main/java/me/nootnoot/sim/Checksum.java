package me.nootnoot.sim;

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

public final class Checksum {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private Checksum() {
    }

    public static long of(GameState s) {
        long h = FNV_OFFSET;
        h = mixInt(h, s.tick);
        h = mixInt(h, s.nextProjectileId);

        h = mixInt(h, s.roundWinsP0);
        h = mixInt(h, s.roundWinsP1);
        h = mixInt(h, s.roundsTarget);
        h = mixInt(h, s.roundResetCountdown);
        h = mixInt(h, s.awaitingReady ? 1 : 0);
        h = mixInt(h, s.roundStartGrace);
        h = mixInt(h, s.roundMatchOver ? 1 : 0);
        h = mixInt(h, s.roundMatchWinner);

        h = mixLong(h, s.dict.digest());
        h = mixLong(h, s.blockProps.digest());
        h = mixInt(h, s.edgeHosted[0] ? 1 : 0);
        h = mixInt(h, s.edgeHosted[1] ? 1 : 0);

        h = containers(h, s.containers);
        h = containers(h, s.roundInitialContainers);

        long[] blockContainerKeys = new long[s.blockContainers.size()];
        int bci = 0;
        for (long k : s.blockContainers.keySet()) {
            blockContainerKeys[bci++] = k;
        }
        Arrays.sort(blockContainerKeys);
        h = mixInt(h, blockContainerKeys.length);
        for (long k : blockContainerKeys) {
            h = mixLong(h, k);
            h = mixInt(h, s.blockContainers.get(k));
        }
        h = mixInt(h, s.nextContainerId);
        h = mixInt(h, s.blastCellBudget);
        h = mixInt(h, s.blastMarchBudget);
        h = mixInt(h, s.blastSeq);

        h = mixInt(h, s.events.size());
        for (CombatEvent e : s.events) {
            h = mixInt(h, e.type());
            h = mixInt(h, e.attacker());
            h = mixInt(h, e.victim());
            h = mixInt(h, e.crit() ? 1 : 0);
            h = mixInt(h, e.kind());
        }

        h = player(h, s.players[0]);
        h = player(h, s.players[1]);

        h = mixInt(h, s.roundInitial == null ? -1 : s.roundInitial.length);
        if (s.roundInitial != null) {
            for (PlayerState seed : s.roundInitial) {
                h = mixInt(h, seed == null ? 0 : 1);
                if (seed != null) {
                    h = player(h, seed);
                }
            }
        }

        h = mixInt(h, s.projectiles.size());
        for (ProjectileState p : s.projectiles) {
            h = projectile(h, p);
        }

        long[] blockKeys = s.blocks.sortedKeys();
        h = mixInt(h, blockKeys.length);
        for (long k : blockKeys) {
            h = mixLong(h, k);
            h = mixInt(h, s.blocks.idAtKey(k));
        }

        h = mixInt(h, s.crystals.size());
        for (CrystalState c : s.crystals) {
            h = mixInt(h, c.id);
            h = mixInt(h, c.owner);
            h = mixInt(h, c.bx);
            h = mixInt(h, c.by);
            h = mixInt(h, c.bz);
        }
        h = mixInt(h, s.nextCrystalId);

        long[] anchorKeys = new long[s.anchors.size()];
        int ai = 0;
        for (long k : s.anchors.keySet()) {
            anchorKeys[ai++] = k;
        }
        Arrays.sort(anchorKeys);
        h = mixInt(h, anchorKeys.length);
        for (long k : anchorKeys) {
            h = mixLong(h, k);
            h = mixInt(h, s.anchors.get(k));
        }

        h = mixInt(h, s.items.size());
        for (ItemEntityState e : s.items) {
            h = mixInt(h, e.id);
            h = mixInt(h, e.owner);
            h = mixInt(h, e.dropUid);
            h = mixInt(h, e.entry);
            h = mixInt(h, e.itemId);
            h = mixInt(h, e.count);
            h = mixInt(h, e.damage);
            h = mixDouble(h, e.x);
            h = mixDouble(h, e.y);
            h = mixDouble(h, e.z);
            h = mixDouble(h, e.vx);
            h = mixDouble(h, e.vy);
            h = mixDouble(h, e.vz);
            h = mixInt(h, e.pickupDelay);
            h = mixInt(h, e.life);
            h = mixInt(h, e.dead ? 1 : 0);
        }
        h = mixInt(h, s.nextItemId);
        h = mixInt(h, s.itemsRefused);

        long[] brokenKeys = new long[s.brokenArena.size()];
        int bi = 0;
        for (long k : s.brokenArena) {
            brokenKeys[bi++] = k;
        }
        Arrays.sort(brokenKeys);
        h = mixInt(h, brokenKeys.length);
        for (long k : brokenKeys) {
            h = mixLong(h, k);
        }
        h = mixInt(h, s.vanillaBuild ? 1 : 0);
        h = mixInt(h, s.allowExplosion ? 1 : 0);
        h = mixInt(h, s.allowBucket ? 1 : 0);
        h = mixInt(h, s.potSwordBoost ? 1 : 0);
        h = mixItemIds(h, s.breakableItemIds, 0x42524B31);
        h = mixItemIds(h, s.placeableItemIds, 0x504C4331);

        h = mixInt(h, s.cobwebItemId);
        h = mixInt(h, s.stringItemId);
        h = mixInt(h, s.obsidianItemId);
        h = mixInt(h, s.cobblestoneItemId);
        h = mixInt(h, s.mudItemId);
        h = mixInt(h, s.glowstoneItemId);
        h = mixInt(h, s.glowstoneDustItemId);

        h = mixDouble(h, s.playCenterX);
        h = mixDouble(h, s.playCenterZ);
        h = mixDouble(h, s.playRadius);
        h = mixInt(h, s.playCircular ? 1 : 0);

        long[] resKeys = new long[s.blockResistance.size()];
        int rk = 0;
        for (long k : s.blockResistance.keySet()) {
            resKeys[rk++] = k;
        }
        Arrays.sort(resKeys);
        h = mixInt(h, resKeys.length);
        for (long k : resKeys) {
            h = mixLong(h, k);
            h = mixFloat(h, s.blockResistance.get(k));
        }

        long[] fluidKeys = new long[s.fluids.size()];
        int fk = 0;
        for (long k : s.fluids.keySet()) {
            fluidKeys[fk++] = k;
        }
        Arrays.sort(fluidKeys);
        h = mixInt(h, fluidKeys.length);
        for (long k : fluidKeys) {
            h = mixLong(h, k);
            h = mixInt(h, s.fluids.get(k));
        }

        long[] webKeys = new long[s.cobwebs.size()];
        int wk = 0;
        for (long k : s.cobwebs.keySet()) {
            webKeys[wk++] = k;
        }
        Arrays.sort(webKeys);
        h = mixInt(h, webKeys.length);
        for (long k : webKeys) {
            h = mixLong(h, k);
            h = mixInt(h, s.cobwebs.get(k));
        }

        long[] fireKeys = new long[s.fires.size()];
        int fkc = 0;
        for (long k : s.fires.keySet()) {
            fireKeys[fkc++] = k;
        }
        Arrays.sort(fireKeys);
        h = mixInt(h, fireKeys.length);
        for (long k : fireKeys) {
            h = mixLong(h, k);
            h = mixInt(h, s.fires.get(k));
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

    private static long player(long h, PlayerState p) {
        h = mixDouble(h, p.x);
        h = mixDouble(h, p.y);
        h = mixDouble(h, p.z);
        h = mixDouble(h, p.vx);
        h = mixDouble(h, p.vy);
        h = mixDouble(h, p.vz);
        h = mixFloat(h, p.yaw);
        h = mixFloat(h, p.pitch);
        h = mixInt(h, p.onGround ? 1 : 0);
        h = mixInt(h, p.sprinting ? 1 : 0);
        h = mixInt(h, p.sneaking ? 1 : 0);
        h = mixInt(h, p.swimming ? 1 : 0);
        h = mixInt(h, p.eatGap);
        h = mixInt(h, p.offhandEatGap);
        h = mixInt(h, p.submergedEye ? 1 : 0);
        h = mixFloat(h, p.health);
        h = mixInt(h, p.hurtTime);
        h = mixFloat(h, p.lastDamage);
        h = mixInt(h, p.attackTicker);
        h = mixInt(h, p.missTicks);
        h = mixInt(h, p.jumpCooldown);
        h = mixInt(h, p.dead ? 1 : 0);
        h = mixInt(h, p.prevAttack ? 1 : 0);
        h = mixInt(h, p.prevUse ? 1 : 0);
        h = mixInt(h, p.prevOffhandUse ? 1 : 0);
        h = mixInt(h, p.prevUsePress ? 1 : 0);
        h = mixInt(h, p.prevDrop ? 1 : 0);
        h = mixInt(h, p.prevInvClick ? 1 : 0);
        h = mixFloat(h, p.attackDamage);
        h = mixFloat(h, p.attackSpeed);
        h = mixInt(h, p.knockbackLevel);
        h = mixFloat(h, p.armor);
        h = mixFloat(h, p.armorToughness);
        h = mixFloat(h, p.protection);
        h = mixInt(h, p.blastProtection);
        h = mixInt(h, p.projectileProtection);
        h = mixInt(h, p.fireProtection);
        h = mixInt(h, p.featherFalling);
        h = mixFloat(h, p.kbResistance);
        h = mixInt(h, p.pearls);
        h = mixFloat(h, p.fallDistance);
        h = mixInt(h, p.authoritySuspendTicks);
        h = mixDouble(h, p.impulseVx);
        h = mixDouble(h, p.impulseVy);
        h = mixDouble(h, p.impulseVz);
        h = mixInt(h, p.impulseSeq);
        h = mixInt(h, p.noFallTicks);
        h = mixInt(h, p.cageFallTicks);
        h = mixInt(h, p.fireTicks);
        h = mixInt(h, p.heldSlot);
        h = mixInt(h, p.heldUseKind);
        h = mixInt(h, p.heldItemId);
        h = mixInt(h, p.offhandItemId);
        h = mixInt(h, p.armorFeetId);
        h = mixInt(h, p.armorLegsId);
        h = mixInt(h, p.armorChestId);
        h = mixInt(h, p.armorHeadId);
        h = mixFloat(h, p.absorption);
        for (int i = 0; i < Effects.COUNT; i++) {
            h = mixInt(h, p.effectTicks[i]);
            h = mixInt(h, p.effectAmp[i]);
            h = mixInt(h, p.effectCounter[i]);
        }
        h = mixFloat(h, p.maxHealth);
        h = mixFloat(h, p.food);
        h = mixFloat(h, p.saturation);
        h = mixFloat(h, p.exhaustion);
        h = mixInt(h, p.regenTimer);
        h = mixInt(h, p.eatTicks);
        h = mixInt(h, p.eating ? 1 : 0);
        h = mixInt(h, p.arrows);
        h = mixInt(h, p.drawTicks);
        for (int i = 0; i < ItemDict.SLOTS; i++) {
            h = mixInt(h, p.slotEntry[i]);
            h = mixInt(h, p.slotCount[i]);
            h = mixInt(h, p.slotDamage[i]);
            h = mixInt(h, p.slotCrossbowLoaded[i] ? 1 : 0);
            h = mixInt(h, p.slotCrossbowConsumed[i] ? 1 : 0);
            h = mixInt(h, p.slotCrossbowEntry[i]);
        }
        h = mixInt(h, p.crossbowHeldUseSlot);
        h = mixInt(h, p.cursorEntry);
        h = mixInt(h, p.cursorCount);
        h = mixInt(h, p.cursorDamage);
        h = mixInt(h, p.cursorCrossbowLoaded ? 1 : 0);
        h = mixInt(h, p.cursorCrossbowConsumed ? 1 : 0);
        h = mixInt(h, p.cursorCrossbowEntry);
        h = mixInt(h, p.openContainer);
        h = mixLong(h, p.openContainerKey);
        h = mixInt(h, p.enderContainer);
        h = mixInt(h, p.invActionSeq);
        h = mixInt(h, p.dropSeq);
        h = mixInt(h, p.lastDropSlot);
        h = mixInt(h, p.lastDropItemId);
        h = mixInt(h, p.lastDropCount);
        h = mixInt(h, p.consumeSeq);
        h = mixInt(h, p.consumeSlot);
        h = mixInt(h, p.arrowsConsumed);
        h = mixInt(h, p.offhandConsumeSeq);
        h = mixInt(h, p.offhandEatTicks);
        h = mixInt(h, p.blockTicks);
        h = mixInt(h, p.shieldDisabled);
        h = mixInt(h, p.lastBlockHitTick);
        h = mixInt(h, p.meleeClaimTick);
        h = mixInt(h, p.meleeClaimsGranted);
        h = mixInt(h, p.meleeClaimsOffAim);
        h = mixInt(h, p.tookFireDamageThisTick ? 1 : 0);
        h = mixInt(h, p.hasTotem ? 1 : 0);
        h = mixInt(h, p.totemSeq);
        h = mixInt(h, p.potionsThrown);
        h = mixInt(h, p.hasElytra ? 1 : 0);
        h = mixInt(h, p.gliding ? 1 : 0);
        h = mixInt(h, p.fireworkTicks);
        h = mixInt(h, p.prevJump ? 1 : 0);
        h = mixInt(h, p.prevElytraStart ? 1 : 0);
        for (int i = 0; i < p.useCooldown.length; i++) {
            h = mixInt(h, p.useCooldown[i]);
        }
        h = mixInt(h, p.useDelay);
        h = mixInt(h, p.destroyDelay);
        h = mixLong(h, p.miningTarget);
        h = mixFloat(h, p.miningProgress);
        h = mixInt(h, p.pickupSeq);
        h = mixInt(h, p.lastPickupItemId);
        h = mixInt(h, p.lastPickupCount);
        h = mixInt(h, p.lastPickupDropUid);
        for (int i = 0; i < PlayerState.PICKUP_RING; i++) {
            h = mixInt(h, p.pickupRingItemId[i]);
            h = mixInt(h, p.pickupRingCount[i]);
            h = mixInt(h, p.pickupRingDropUid[i]);
        }
        h = mixInt(h, p.toolDamageSeq);
        h = mixInt(h, p.armorDamageSeq);
        h = mixInt(h, p.armorDamageAmount);
        h = mixInt(h, p.ready ? 1 : 0);
        h = mixInt(h, p.instaReady ? 1 : 0);
        h = mixInt(h, p.rewindPos);
        h = mixInt(h, p.rewindFilled);
        for (int i = 0; i < PlayerState.REWIND_FRAMES; i++) {
            h = mixDouble(h, p.rewindX[i]);
            h = mixDouble(h, p.rewindY[i]);
            h = mixDouble(h, p.rewindZ[i]);
            h = mixDouble(h, p.rewindHeight[i]);
        }
        return h;
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

    private static long mixInt(long h, int v) {
        return mixLong(h, v & 0xFFFFFFFFL);
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

    private static long mixLong(long h, long v) {
        for (int i = 0; i < 8; i++) {
            h ^= (v & 0xFF);
            h *= FNV_PRIME;
            v >>>= 8;
        }
        return h;
    }
}
