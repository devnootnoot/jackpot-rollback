package me.nootnoot.sim;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;

public final class GameStateFrame0Codec {
    private static final int VERSION = 17;

    private GameStateFrame0Codec() {
    }

    public static byte[] encode(GameState s) {
        if (!s.projectiles.isEmpty() || s.blocks.sortedKeys().length != 0 || !s.crystals.isEmpty()
                || !s.items.isEmpty() || !s.anchors.isEmpty() || !s.brokenArena.isEmpty()
                || !s.blockResistance.isEmpty() || !s.fluids.isEmpty() || !s.cobwebs.isEmpty()
                || !s.fires.isEmpty() || !s.events.isEmpty()) {
            throw new IllegalArgumentException("frame-0 codec requires empty per-tick collections");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(2048);
        try (DataOutputStream o = new DataOutputStream(bos)) {
            o.writeInt(VERSION);
            o.writeInt(s.tick);
            o.writeInt(s.nextProjectileId);
            o.writeInt(s.nextCrystalId);
            o.writeInt(s.nextItemId);
            o.writeInt(s.roundWinsP0);
            o.writeInt(s.roundWinsP1);
            o.writeInt(s.roundsTarget);
            o.writeInt(s.roundResetCountdown);
            o.writeBoolean(s.awaitingReady);
            o.writeInt(s.roundStartGrace);
            o.writeBoolean(s.roundMatchOver);
            o.writeInt(s.roundMatchWinner);
            o.writeBoolean(s.vanillaBuild);
            o.writeBoolean(s.allowExplosion);
            o.writeBoolean(s.allowBucket);
            o.writeBoolean(s.potSwordBoost);
            o.writeInt(s.cobwebItemId);
            o.writeInt(s.stringItemId);
            o.writeInt(s.obsidianItemId);
            o.writeInt(s.cobblestoneItemId);
            o.writeInt(s.mudItemId);
            o.writeInt(s.glowstoneItemId);
            o.writeInt(s.glowstoneDustItemId);
            o.writeDouble(s.playCenterX);
            o.writeDouble(s.playCenterZ);
            o.writeDouble(s.playRadius);
            o.writeBoolean(s.playCircular);
            writeDict(o, s.dict);
            writeBlockProps(o, s.blockProps);
            o.writeBoolean(s.edgeHosted[0]);
            o.writeBoolean(s.edgeHosted[1]);
            int[] containerIds = sortedContainerIds(s);
            o.writeInt(containerIds.length);
            for (int id : containerIds) {
                o.writeInt(id);
                Container c = s.containers.get(id);
                for (int i = 0; i < Container.CELLS; i++) {
                    o.writeInt(c.entry[i]);
                    o.writeInt(c.count[i]);
                    o.writeInt(c.damage[i]);
                }
            }
            long[] blockContainerKeys = sortedBlockContainerKeys(s);
            o.writeInt(blockContainerKeys.length);
            for (long k : blockContainerKeys) {
                o.writeLong(k);
                o.writeInt(s.blockContainers.get(k));
            }
            o.writeInt(s.nextContainerId);
            o.writeInt(s.blastCellBudget);
            o.writeInt(s.blastMarchBudget);
            o.writeInt(s.blastSeq);
            o.writeInt(s.itemsRefused);
            writePlayer(o, s.players[0]);
            writePlayer(o, s.players[1]);
            boolean hasInitial = s.roundInitial != null;
            o.writeBoolean(hasInitial);
            if (hasInitial) {
                writePlayer(o, s.roundInitial[0]);
                writePlayer(o, s.roundInitial[1]);
            }
            int[] seedIds = sortedIds(s.roundInitialContainers);
            o.writeInt(seedIds.length);
            for (int id : seedIds) {
                o.writeInt(id);
                Container c = s.roundInitialContainers.get(id);
                for (int i = 0; i < Container.CELLS; i++) {
                    o.writeInt(c.entry[i]);
                    o.writeInt(c.count[i]);
                    o.writeInt(c.damage[i]);
                }
            }
            o.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    public static GameState decode(byte[] data) {
        GameState s = new GameState();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readInt();
            if (version != VERSION) {
                throw new IllegalArgumentException("unsupported frame-0 codec version: " + version);
            }
            s.tick = in.readInt();
            s.nextProjectileId = in.readInt();
            s.nextCrystalId = in.readInt();
            s.nextItemId = in.readInt();
            s.roundWinsP0 = in.readInt();
            s.roundWinsP1 = in.readInt();
            s.roundsTarget = in.readInt();
            s.roundResetCountdown = in.readInt();
            s.awaitingReady = in.readBoolean();
            s.roundStartGrace = in.readInt();
            s.roundMatchOver = in.readBoolean();
            s.roundMatchWinner = in.readInt();
            s.vanillaBuild = in.readBoolean();
            s.allowExplosion = in.readBoolean();
            s.allowBucket = in.readBoolean();
            s.potSwordBoost = in.readBoolean();
            s.cobwebItemId = in.readInt();
            s.stringItemId = in.readInt();
            s.obsidianItemId = in.readInt();
            s.cobblestoneItemId = in.readInt();
            s.mudItemId = in.readInt();
            s.glowstoneItemId = in.readInt();
            s.glowstoneDustItemId = in.readInt();
            s.playCenterX = in.readDouble();
            s.playCenterZ = in.readDouble();
            s.playRadius = in.readDouble();
            s.playCircular = in.readBoolean();
            s.dict = readDict(in);
            s.blockProps = readBlockProps(in);
            s.edgeHosted[0] = in.readBoolean();
            s.edgeHosted[1] = in.readBoolean();
            int containerCount = in.readInt();
            if (containerCount < 0 || containerCount > 4096) {
                throw new IllegalArgumentException("containers out of bounds: " + containerCount);
            }
            for (int i = 0; i < containerCount; i++) {
                int id = in.readInt();
                Container c = new Container();
                for (int cell = 0; cell < Container.CELLS; cell++) {
                    c.entry[cell] = in.readInt();
                    c.count[cell] = in.readInt();
                    c.damage[cell] = in.readInt();
                }
                s.containers.put(id, c);
            }
            int blockContainerCount = in.readInt();
            if (blockContainerCount < 0 || blockContainerCount > 65536) {
                throw new IllegalArgumentException("blockContainers out of bounds: "
                        + blockContainerCount);
            }
            for (int i = 0; i < blockContainerCount; i++) {
                long k = in.readLong();
                s.blockContainers.put(k, in.readInt());
            }
            s.nextContainerId = in.readInt();
            s.blastCellBudget = in.readInt();
            s.blastMarchBudget = in.readInt();
            s.blastSeq = in.readInt();
            s.itemsRefused = in.readInt();
            s.players[0] = readPlayer(in);
            s.players[1] = readPlayer(in);
            if (in.readBoolean()) {
                s.roundInitial = new PlayerState[]{readPlayer(in), readPlayer(in)};
            }
            int seedCount = in.readInt();
            if (seedCount < 0 || seedCount > 4096) {
                throw new IllegalArgumentException("roundInitialContainers out of bounds: " + seedCount);
            }
            for (int i = 0; i < seedCount; i++) {
                int id = in.readInt();
                Container c = new Container();
                for (int cell = 0; cell < Container.CELLS; cell++) {
                    c.entry[cell] = in.readInt();
                    c.count[cell] = in.readInt();
                    c.damage[cell] = in.readInt();
                }
                s.roundInitialContainers.put(id, c);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return s;
    }

    private static void writePlayer(DataOutputStream o, PlayerState p) throws IOException {
        o.writeDouble(p.x);
        o.writeDouble(p.y);
        o.writeDouble(p.z);
        o.writeDouble(p.vx);
        o.writeDouble(p.vy);
        o.writeDouble(p.vz);
        o.writeFloat(p.yaw);
        o.writeFloat(p.pitch);
        o.writeBoolean(p.onGround);
        o.writeBoolean(p.sprinting);
        o.writeBoolean(p.sneaking);
        o.writeBoolean(p.swimming);
        o.writeInt(p.eatGap);
        o.writeInt(p.offhandEatGap);
        o.writeBoolean(p.submergedEye);
        o.writeFloat(p.health);
        o.writeFloat(p.maxHealth);
        o.writeInt(p.hurtTime);
        o.writeFloat(p.lastDamage);
        o.writeInt(p.attackTicker);
        o.writeInt(p.missTicks);
        o.writeInt(p.jumpCooldown);
        o.writeBoolean(p.dead);
        o.writeInt(p.heldSlot);
        o.writeInt(p.heldUseKind);
        o.writeInt(p.heldItemId);
        o.writeInt(p.offhandItemId);
        o.writeInt(p.armorFeetId);
        o.writeInt(p.armorLegsId);
        o.writeInt(p.armorChestId);
        o.writeInt(p.armorHeadId);
        o.writeFloat(p.absorption);
        writeIntArray(o, p.effectTicks);
        writeIntArray(o, p.effectAmp);
        writeIntArray(o, p.effectCounter);
        o.writeFloat(p.food);
        o.writeFloat(p.saturation);
        o.writeFloat(p.exhaustion);
        o.writeInt(p.regenTimer);
        o.writeBoolean(p.prevAttack);
        o.writeBoolean(p.prevUse);
        o.writeBoolean(p.prevOffhandUse);
        o.writeBoolean(p.prevUsePress);
        o.writeBoolean(p.prevDrop);
        o.writeBoolean(p.prevInvClick);
        o.writeFloat(p.attackDamage);
        o.writeFloat(p.attackSpeed);
        o.writeInt(p.knockbackLevel);
        writeIntArray(o, p.slotEntry);
        writeIntArray(o, p.slotCount);
        writeIntArray(o, p.slotDamage);
        writeBoolArray(o, p.slotCrossbowLoaded);
        writeBoolArray(o, p.slotCrossbowConsumed);
        writeIntArray(o, p.slotCrossbowEntry);
        o.writeInt(p.crossbowHeldUseSlot);
        o.writeInt(p.cursorEntry);
        o.writeInt(p.cursorCount);
        o.writeInt(p.cursorDamage);
        o.writeBoolean(p.cursorCrossbowLoaded);
        o.writeBoolean(p.cursorCrossbowConsumed);
        o.writeInt(p.cursorCrossbowEntry);
        o.writeInt(p.openContainer);
        o.writeLong(p.openContainerKey);
        o.writeInt(p.enderContainer);
        o.writeInt(p.invActionSeq);
        o.writeInt(p.dropSeq);
        o.writeInt(p.lastDropSlot);
        o.writeInt(p.lastDropItemId);
        o.writeInt(p.lastDropCount);
        o.writeInt(p.consumeSeq);
        o.writeInt(p.consumeSlot);
        o.writeInt(p.arrowsConsumed);
        o.writeInt(p.offhandConsumeSeq);
        o.writeInt(p.offhandEatTicks);
        o.writeInt(p.eatTicks);
        o.writeBoolean(p.eating);
        o.writeInt(p.arrows);
        o.writeInt(p.drawTicks);
        o.writeInt(p.blockTicks);
        o.writeInt(p.shieldDisabled);
        o.writeInt(p.lastBlockHitTick);
        o.writeInt(p.meleeClaimTick);
        o.writeInt(p.meleeClaimsGranted);
        o.writeInt(p.meleeClaimsOffAim);
        o.writeBoolean(p.tookFireDamageThisTick);
        o.writeBoolean(p.hasTotem);
        o.writeInt(p.totemSeq);
        o.writeInt(p.potionsThrown);
        o.writeBoolean(p.hasElytra);
        o.writeBoolean(p.gliding);
        o.writeInt(p.fireworkTicks);
        o.writeBoolean(p.prevJump);
        o.writeBoolean(p.prevElytraStart);
        o.writeFloat(p.armor);
        o.writeFloat(p.armorToughness);
        o.writeFloat(p.protection);
        o.writeInt(p.blastProtection);
        o.writeInt(p.projectileProtection);
        o.writeInt(p.fireProtection);
        o.writeInt(p.featherFalling);
        o.writeFloat(p.kbResistance);
        o.writeInt(p.pearls);
        o.writeFloat(p.fallDistance);
        o.writeInt(p.noFallTicks);
        o.writeInt(p.cageFallTicks);
        o.writeInt(p.fireTicks);
        writeIntArray(o, p.useCooldown);
        o.writeInt(p.useDelay);
        o.writeInt(p.destroyDelay);
        o.writeLong(p.miningTarget);
        o.writeFloat(p.miningProgress);
        o.writeInt(p.pickupSeq);
        o.writeInt(p.lastPickupItemId);
        o.writeInt(p.lastPickupCount);
        o.writeInt(p.lastPickupDropUid);
        for (int i = 0; i < PlayerState.PICKUP_RING; i++) {
            o.writeInt(p.pickupRingItemId[i]);
            o.writeInt(p.pickupRingCount[i]);
            o.writeInt(p.pickupRingDropUid[i]);
        }
        o.writeInt(p.toolDamageSeq);
        o.writeInt(p.armorDamageSeq);
        o.writeInt(p.armorDamageAmount);
        o.writeBoolean(p.ready);
        o.writeBoolean(p.instaReady);
        o.writeInt(p.rewindPos);
        o.writeInt(p.rewindFilled);
        writeDoubleArray(o, p.rewindX);
        writeDoubleArray(o, p.rewindY);
        writeDoubleArray(o, p.rewindZ);
        writeDoubleArray(o, p.rewindHeight);
    }

    private static PlayerState readPlayer(DataInputStream in) throws IOException {
        PlayerState p = new PlayerState();
        p.x = in.readDouble();
        p.y = in.readDouble();
        p.z = in.readDouble();
        p.vx = in.readDouble();
        p.vy = in.readDouble();
        p.vz = in.readDouble();
        p.yaw = in.readFloat();
        p.pitch = in.readFloat();
        p.onGround = in.readBoolean();
        p.sprinting = in.readBoolean();
        p.sneaking = in.readBoolean();
        p.swimming = in.readBoolean();
        p.eatGap = in.readInt();
        p.offhandEatGap = in.readInt();
        p.submergedEye = in.readBoolean();
        p.health = in.readFloat();
        p.maxHealth = in.readFloat();
        p.hurtTime = in.readInt();
        p.lastDamage = in.readFloat();
        p.attackTicker = in.readInt();
        p.missTicks = in.readInt();
        p.jumpCooldown = in.readInt();
        p.dead = in.readBoolean();
        p.heldSlot = in.readInt();
        p.heldUseKind = in.readInt();
        p.heldItemId = in.readInt();
        p.offhandItemId = in.readInt();
        p.armorFeetId = in.readInt();
        p.armorLegsId = in.readInt();
        p.armorChestId = in.readInt();
        p.armorHeadId = in.readInt();
        p.absorption = in.readFloat();
        p.effectTicks = readIntArray(in, Effects.COUNT);
        p.effectAmp = readIntArray(in, Effects.COUNT);
        p.effectCounter = readIntArray(in, Effects.COUNT);
        p.food = in.readFloat();
        p.saturation = in.readFloat();
        p.exhaustion = in.readFloat();
        p.regenTimer = in.readInt();
        p.prevAttack = in.readBoolean();
        p.prevUse = in.readBoolean();
        p.prevOffhandUse = in.readBoolean();
        p.prevUsePress = in.readBoolean();
        p.prevDrop = in.readBoolean();
        p.prevInvClick = in.readBoolean();
        p.attackDamage = in.readFloat();
        p.attackSpeed = in.readFloat();
        p.knockbackLevel = in.readInt();
        p.slotEntry = readIntArray(in, ItemDict.SLOTS);
        p.slotCount = readIntArray(in, ItemDict.SLOTS);
        p.slotDamage = readIntArray(in, ItemDict.SLOTS);
        p.slotCrossbowLoaded = readBoolArray(in, ItemDict.SLOTS);
        p.slotCrossbowConsumed = readBoolArray(in, ItemDict.SLOTS);
        p.slotCrossbowEntry = readIntArray(in, ItemDict.SLOTS);
        p.crossbowHeldUseSlot = crossbowHeldUseSlot(in.readInt());
        p.cursorEntry = in.readInt();
        p.cursorCount = in.readInt();
        p.cursorDamage = in.readInt();
        p.cursorCrossbowLoaded = in.readBoolean();
        p.cursorCrossbowConsumed = in.readBoolean();
        p.cursorCrossbowEntry = in.readInt();
        p.openContainer = in.readInt();
        p.openContainerKey = in.readLong();
        p.enderContainer = in.readInt();
        p.invActionSeq = in.readInt();
        p.dropSeq = in.readInt();
        p.lastDropSlot = in.readInt();
        p.lastDropItemId = in.readInt();
        p.lastDropCount = in.readInt();
        p.consumeSeq = in.readInt();
        p.consumeSlot = in.readInt();
        p.arrowsConsumed = in.readInt();
        p.offhandConsumeSeq = in.readInt();
        p.offhandEatTicks = in.readInt();
        p.eatTicks = in.readInt();
        p.eating = in.readBoolean();
        p.arrows = in.readInt();
        p.drawTicks = in.readInt();
        p.blockTicks = in.readInt();
        p.shieldDisabled = in.readInt();
        p.lastBlockHitTick = in.readInt();
        p.meleeClaimTick = in.readInt();
        p.meleeClaimsGranted = in.readInt();
        p.meleeClaimsOffAim = in.readInt();
        p.tookFireDamageThisTick = in.readBoolean();
        p.hasTotem = in.readBoolean();
        p.totemSeq = in.readInt();
        p.potionsThrown = in.readInt();
        p.hasElytra = in.readBoolean();
        p.gliding = in.readBoolean();
        p.fireworkTicks = in.readInt();
        p.prevJump = in.readBoolean();
        p.prevElytraStart = in.readBoolean();
        p.armor = in.readFloat();
        p.armorToughness = in.readFloat();
        p.protection = in.readFloat();
        p.blastProtection = in.readInt();
        p.projectileProtection = in.readInt();
        p.fireProtection = in.readInt();
        p.featherFalling = in.readInt();
        p.kbResistance = in.readFloat();
        p.pearls = in.readInt();
        p.fallDistance = in.readFloat();
        p.noFallTicks = in.readInt();
        p.cageFallTicks = in.readInt();
        p.fireTicks = in.readInt();
        p.useCooldown = readIntArray(in, PlayerState.USE_KINDS);
        p.useDelay = in.readInt();
        p.destroyDelay = in.readInt();
        p.miningTarget = in.readLong();
        p.miningProgress = in.readFloat();
        p.pickupSeq = in.readInt();
        p.lastPickupItemId = in.readInt();
        p.lastPickupCount = in.readInt();
        p.lastPickupDropUid = in.readInt();
        for (int i = 0; i < PlayerState.PICKUP_RING; i++) {
            p.pickupRingItemId[i] = in.readInt();
            p.pickupRingCount[i] = in.readInt();
            p.pickupRingDropUid[i] = in.readInt();
        }
        p.toolDamageSeq = in.readInt();
        p.armorDamageSeq = in.readInt();
        p.armorDamageAmount = in.readInt();
        p.ready = in.readBoolean();
        p.instaReady = in.readBoolean();
        p.rewindPos = readRingIndex(in, "rewindPos", PlayerState.REWIND_FRAMES - 1);
        p.rewindFilled = readRingIndex(in, "rewindFilled", PlayerState.REWIND_FRAMES);
        readDoublesInto(in, p.rewindX);
        readDoublesInto(in, p.rewindY);
        readDoublesInto(in, p.rewindZ);
        readDoublesInto(in, p.rewindHeight);
        return p;
    }

    private static int[] sortedContainerIds(GameState s) {
        int[] ids = new int[s.containers.size()];
        int i = 0;
        for (int k : s.containers.keySet()) {
            ids[i++] = k;
        }
        java.util.Arrays.sort(ids);
        return ids;
    }

    private static int[] sortedIds(java.util.Map<Integer, Container> map) {
        int[] ids = new int[map.size()];
        int i = 0;
        for (int k : map.keySet()) {
            ids[i++] = k;
        }
        java.util.Arrays.sort(ids);
        return ids;
    }

    private static long[] sortedBlockContainerKeys(GameState s) {
        long[] keys = new long[s.blockContainers.size()];
        int i = 0;
        for (long k : s.blockContainers.keySet()) {
            keys[i++] = k;
        }
        java.util.Arrays.sort(keys);
        return keys;
    }

    private static void writeDict(DataOutputStream o, ItemDict d) throws IOException {
        int n = d.size();
        o.writeInt(n);
        for (int e = 1; e <= n; e++) {
            o.writeInt(d.itemId(e));
            o.writeInt(d.maxStack(e));
            o.writeInt(d.maxDamage(e));
            o.writeInt(d.flags(e));
            o.writeInt(d.useKind(e));
            o.writeFloat(d.meleeDamage(e));
            o.writeFloat(d.meleeSpeed(e));
            o.writeInt(d.knockback(e));
            o.writeInt(d.weaponEnchants(e));
            o.writeInt(d.maceInfo(e));
            o.writeInt(d.rangedInfo(e));
            o.writeInt(d.toolInfo(e));
            o.writeInt(d.foodNutrition(e));
            o.writeFloat(d.foodSaturation(e));
            o.writeInt(d.foodEatTicks(e));
            o.writeInt(d.fireworkFlight(e));
            o.writeInt(d.effect(e, 0));
            o.writeInt(d.effect(e, 1));
            o.writeInt(d.effect(e, 2));
            o.writeInt(d.effect(e, 3));
            o.writeInt(d.armorPoints(e));
            o.writeFloat(d.armorToughness(e));
            o.writeFloat(d.armorKbResistance(e));
            o.writeInt(d.armorProtection(e));
            o.writeInt(d.armorBlastProtection(e));
            o.writeInt(d.armorProjectileProtection(e));
            o.writeInt(d.armorFireProtection(e));
            o.writeInt(d.armorFeatherFalling(e));
            o.writeInt(d.equipSlot(e));
            o.writeInt(d.containerSeed(e));
        }
    }

    private static ItemDict readDict(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > ItemDict.MAX_ENTRIES) {
            throw new IllegalArgumentException("dictionary size out of bounds: " + n);
        }
        ItemDict.Builder builder = new ItemDict.Builder();
        for (int e = 0; e < n; e++) {
            int itemId = in.readInt();
            int maxStack = in.readInt();
            int maxDamage = in.readInt();
            int flags = in.readInt();
            int useKind = in.readInt();
            float meleeDamage = in.readFloat();
            float meleeSpeed = in.readFloat();
            int knockback = in.readInt();
            int weaponEnchants = in.readInt();
            int maceInfo = in.readInt();
            int rangedInfo = in.readInt();
            int toolInfo = in.readInt();
            int foodNutrition = in.readInt();
            float foodSaturation = in.readFloat();
            int foodEatTicks = in.readInt();
            int fireworkFlight = in.readInt();
            int effect0 = in.readInt();
            int effect1 = in.readInt();
            int effect2 = in.readInt();
            int effect3 = in.readInt();
            int armorPoints = in.readInt();
            float armorToughness = in.readFloat();
            float armorKbResistance = in.readFloat();
            int armorProtection = in.readInt();
            int armorBlastProtection = in.readInt();
            int armorProjectileProtection = in.readInt();
            int armorFireProtection = in.readInt();
            int armorFeatherFalling = in.readInt();
            int equipSlot = in.readInt();
            int containerSeed = in.readInt();
            builder.add(itemId, maxStack, maxDamage, flags, useKind, meleeDamage, meleeSpeed,
                    knockback, weaponEnchants, maceInfo, rangedInfo, toolInfo,
                    foodNutrition, foodSaturation, foodEatTicks, fireworkFlight,
                    effect0, effect1, effect2, effect3,
                    armorPoints, armorToughness, armorKbResistance,
                    armorProtection, armorBlastProtection, armorProjectileProtection,
                    armorFireProtection, armorFeatherFalling, equipSlot, containerSeed);
        }
        return builder.build();
    }

    private static void writeBlockProps(DataOutputStream o, BlockProps b) throws IOException {
        int[] keys = b.sortedKeys();
        o.writeInt(keys.length);
        for (int k : keys) {
            o.writeInt(k);
            o.writeFloat(b.hardness(k));
            o.writeFloat(b.blastResistance(k));
            o.writeInt(b.dropItemId(k));
            o.writeInt(b.harvestTier(k));
            o.writeInt(b.toolClass(k));
            o.writeBoolean(b.requiresTool(k));
        }
    }

    private static BlockProps readBlockProps(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > BlockProps.MAX_ROWS) {
            throw new IllegalArgumentException("block props out of bounds: " + n);
        }
        BlockProps.Builder builder = new BlockProps.Builder();
        for (int i = 0; i < n; i++) {
            int k = in.readInt();
            float hardness = in.readFloat();
            float blast = in.readFloat();
            int dropItemId = in.readInt();
            int harvestTier = in.readInt();
            int toolClass = in.readInt();
            boolean requiresTool = in.readBoolean();
            builder.add(k, hardness, blast, dropItemId, harvestTier, toolClass, requiresTool);
        }
        return builder.build();
    }

    private static void writeIntArray(DataOutputStream o, int[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (int v : a) {
            o.writeInt(v);
        }
    }

    private static void writeFloatArray(DataOutputStream o, float[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (float v : a) {
            o.writeFloat(v);
        }
    }

    private static void writeBoolArray(DataOutputStream o, boolean[] a) throws IOException {
        if (a == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(a.length);
        for (boolean v : a) {
            o.writeBoolean(v);
        }
    }

    private static int crossbowHeldUseSlot(int raw) {
        return raw >= 0 && raw < ItemDict.SLOTS ? raw : PlayerState.NO_CROSSBOW_HELD_USE;
    }

    private static int[] readIntArray(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        if (len > 4096) {
            throw new IllegalArgumentException("int array out of bounds: " + len);
        }
        int[] a = new int[len];
        for (int i = 0; i < len; i++) {
            a[i] = in.readInt();
        }
        return a;
    }

    private static int[] readIntArray(DataInputStream in, int expected) throws IOException {
        int[] a = readIntArray(in);
        if (a == null || a.length != expected) {
            throw new IllegalArgumentException("expected fixed array of " + expected
                    + " but got " + (a == null ? "null" : a.length));
        }
        return a;
    }

    private static void writeDoubleArray(DataOutputStream o, double[] a) throws IOException {
        o.writeInt(a.length);
        for (double v : a) {
            o.writeDouble(v);
        }
    }

    private static void readDoublesInto(DataInputStream in, double[] a) throws IOException {
        int len = in.readInt();
        if (len != a.length) {
            throw new IllegalArgumentException("expected fixed array of " + a.length
                    + " but got " + len);
        }
        for (int i = 0; i < len; i++) {
            a[i] = in.readDouble();
        }
    }

    private static int readRingIndex(DataInputStream in, String name, int max) throws IOException {
        int v = in.readInt();
        if (v < 0 || v > max) {
            throw new IllegalArgumentException(name + " out of bounds: " + v);
        }
        return v;
    }

    private static float[] readFloatArray(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        if (len > 4096) {
            throw new IllegalArgumentException("float array out of bounds: " + len);
        }
        float[] a = new float[len];
        for (int i = 0; i < len; i++) {
            a[i] = in.readFloat();
        }
        return a;
    }

    private static boolean[] readBoolArray(DataInputStream in, int expected) throws IOException {
        boolean[] a = readBoolArray(in);
        if (a == null || a.length != expected) {
            throw new IllegalArgumentException("expected fixed array of " + expected
                    + " but got " + (a == null ? "null" : a.length));
        }
        return a;
    }

    private static boolean[] readBoolArray(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        if (len > 4096) {
            throw new IllegalArgumentException("bool array out of bounds: " + len);
        }
        boolean[] a = new boolean[len];
        for (int i = 0; i < len; i++) {
            a[i] = in.readBoolean();
        }
        return a;
    }
}
