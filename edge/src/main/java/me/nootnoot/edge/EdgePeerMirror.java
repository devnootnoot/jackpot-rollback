package me.nootnoot.edge;

import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import java.util.concurrent.ThreadLocalRandom;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class EdgePeerMirror {

    private static final double MOUTH_HEIGHT = 1.4;
    private static final double EYE_HEIGHT = 1.62;

    private static final int CHEW_PERIOD = 4;
    private static final int CHEW_PARTICLES = 4;
    private static final double CHEW_SPREAD = 0.1;
    private static final double CHEW_SPEED = 0.05;

    private static final int FIRE_CRACKLE_PERIOD = 12;
    private static final float FIRE_CRACKLE_VOLUME = 0.6f;

    private static final double STEP_ACCUMULATOR_SCALE = 0.6;
    private static final float STEP_VOLUME_SCALE = 0.15f;

    private static final int EFFECT_REFRESH_TICKS = 40;

    private final Player viewer;
    private final World world;
    private final EdgeStacks stacks;
    private final boolean shieldOffHand;

    private final boolean[] shownEffect = new boolean[Effects.COUNT];
    private final int[] shownEffectAmp = new int[Effects.COUNT];

    private int shownHeldId = Integer.MIN_VALUE;
    private int shownHeldSlot = Integer.MIN_VALUE;
    private int shownHeldCount = Integer.MIN_VALUE;
    private int shownOffhandId = Integer.MIN_VALUE;
    private int shownFeetId = Integer.MIN_VALUE;
    private int shownLegsId = Integer.MIN_VALUE;
    private int shownChestId = Integer.MIN_VALUE;
    private int shownHeadId = Integer.MIN_VALUE;

    private boolean shownCrossbowLoaded;
    private boolean shownCrossbowDrawing;

    private int shownEatTicks;
    private int shownConsumeSeq = Integer.MIN_VALUE;
    private int shownFireworkTicks;
    private int refreshTick;

    private double stepAccumulator;
    private double lastX;
    private double lastZ;
    private boolean stepSeeded;

    public EdgePeerMirror(Player viewer, World world, EdgeStacks stacks, boolean shieldOffHand) {
        this.viewer = viewer;
        this.world = world;
        this.stacks = stacks;
        this.shieldOffHand = shieldOffHand;
    }

    public void apply(EdgePlayerEntity npc, PlayerState other) {
        if (npc == null || other == null || !viewer.isOnline()) {
            return;
        }
        equipment(npc, other);
        usingItem(npc, other);
        crossbow(npc, other);
        eating(other);
        fireCrackle(other);
        firework(npc, other);
        steps(other);
        effects(npc, other);
    }

    private void equipment(EdgePlayerEntity npc, PlayerState other) {
        int heldCount = other.slotCount[clampSlot(other.heldSlot)];
        if (other.heldItemId != 0 && heldCount <= 0) {
            heldCount = 1;
        }
        if (other.heldItemId != shownHeldId || other.heldSlot != shownHeldSlot
                || heldCount != shownHeldCount) {
            shownHeldId = other.heldItemId;
            shownHeldSlot = other.heldSlot;
            shownHeldCount = heldCount;
            ItemStack held = other.heldItemId == 0 ? null
                    : stacks.resolveHeld(other.heldItemId, other.heldSlot, heldCount);
            npc.setHeldItem(EdgePlayerEntity.itemOf(held));
            shownCrossbowLoaded = false;
        }
        if (other.offhandItemId != shownOffhandId) {
            shownOffhandId = other.offhandItemId;
            npc.setEquipment(EquipmentSlot.OFF_HAND, peItem(other.offhandItemId, EdgeLoadout.OFF_HAND));
        }
        if (other.armorFeetId != shownFeetId) {
            shownFeetId = other.armorFeetId;
            npc.setEquipment(EquipmentSlot.BOOTS, peItem(other.armorFeetId, EdgeLoadout.ARMOR_FEET));
        }
        if (other.armorLegsId != shownLegsId) {
            shownLegsId = other.armorLegsId;
            npc.setEquipment(EquipmentSlot.LEGGINGS, peItem(other.armorLegsId, EdgeLoadout.ARMOR_LEGS));
        }
        if (other.armorChestId != shownChestId) {
            shownChestId = other.armorChestId;
            npc.setEquipment(EquipmentSlot.CHEST_PLATE,
                    peItem(other.armorChestId, EdgeLoadout.ARMOR_CHEST));
        }
        if (other.armorHeadId != shownHeadId) {
            shownHeadId = other.armorHeadId;
            npc.setEquipment(EquipmentSlot.HELMET, peItem(other.armorHeadId, EdgeLoadout.ARMOR_HEAD));
        }
    }

    private com.github.retrooper.packetevents.protocol.item.ItemStack peItem(int itemId,
                                                                            int inventorySlot) {
        return EdgePlayerEntity.itemOf(stacks.resolve(itemId, inventorySlot));
    }

    private void usingItem(EdgePlayerEntity npc, PlayerState other) {
        boolean blocking = other.blockTicks > 0;
        boolean offhandEating = other.offhandEatTicks > 0;
        boolean using = blocking || other.eating || offhandEating || other.drawTicks > 0;
        boolean offHand = offhandEating && !other.eating && !blocking && other.drawTicks <= 0;
        if (blocking && shieldOffHand) {
            offHand = true;
        }
        npc.setUsingItem(using, offHand);
    }

    private void crossbow(EdgePlayerEntity npc, PlayerState other) {
        if (other.heldUseKind != Combat.USE_CROSSBOW) {
            shownCrossbowDrawing = false;
            shownCrossbowLoaded = false;
            return;
        }
        boolean loaded = other.slotCrossbowLoaded[clampSlot(other.heldSlot)];
        boolean drawing = other.drawTicks > 0;
        Location at = at(other, EYE_HEIGHT);
        if (drawing && !shownCrossbowDrawing) {
            viewer.playSound(at, Sound.ITEM_CROSSBOW_LOADING_START, SoundCategory.PLAYERS, 0.6f, 1.0f);
        }
        if (loaded && !shownCrossbowLoaded) {
            viewer.playSound(at, Sound.ITEM_CROSSBOW_LOADING_END, SoundCategory.PLAYERS, 1.0f, 1.0f);
            npc.setHeldItem(EdgePlayerEntity.itemOf(
                    EdgeHeldItems.charged(stacks.resolveHeld(other.heldItemId, other.heldSlot, 1), true)));
        }
        if (shownCrossbowLoaded && !loaded) {
            viewer.playSound(at, Sound.ITEM_CROSSBOW_SHOOT, SoundCategory.PLAYERS, 1.0f,
                    1.0f / (rand() * 0.4f + 1.2f) + 0.5f);
            npc.setHeldItem(EdgePlayerEntity.itemOf(
                    EdgeHeldItems.charged(stacks.resolveHeld(other.heldItemId, other.heldSlot, 1), false)));
        }
        shownCrossbowDrawing = drawing;
        shownCrossbowLoaded = loaded;
    }

    private void eating(PlayerState other) {
        int eatTicks = other.eating ? other.eatTicks : 0;
        if (other.offhandEatTicks > eatTicks) {
            eatTicks = other.offhandEatTicks;
        }
        if (eatTicks > 0 && eatTicks % CHEW_PERIOD == 0 && eatTicks != shownEatTicks) {
            Location mouth = at(other, MOUTH_HEIGHT);
            viewer.playSound(mouth, Sound.ENTITY_GENERIC_EAT, SoundCategory.PLAYERS, 0.5f,
                    rand() * 0.1f + 0.9f);
            ItemStack held = stacks.resolveHeld(other.heldItemId, other.heldSlot, 1);
            if (held != null && !held.getType().isAir()) {
                viewer.spawnParticle(Particle.ITEM, mouth, CHEW_PARTICLES, CHEW_SPREAD,
                        CHEW_SPREAD * 0.5, CHEW_SPREAD, CHEW_SPEED, held);
            }
        }
        int previousEatTicks = shownEatTicks;
        shownEatTicks = eatTicks;
        if (other.consumeSeq == shownConsumeSeq) {
            return;
        }
        boolean wasFood = previousEatTicks > 0;
        if (wasFood && shownConsumeSeq != Integer.MIN_VALUE) {
            viewer.playSound(at(other, MOUTH_HEIGHT), Sound.ENTITY_PLAYER_BURP,
                    SoundCategory.PLAYERS, 0.5f, rand() * 0.1f + 0.9f);
        }
        shownConsumeSeq = other.consumeSeq;
    }

    private void fireCrackle(PlayerState other) {
        if (other.fireTicks <= 0 || other.fireTicks % FIRE_CRACKLE_PERIOD != 0) {
            return;
        }
        viewer.playSound(at(other, MOUTH_HEIGHT), Sound.BLOCK_FIRE_AMBIENT, SoundCategory.PLAYERS,
                FIRE_CRACKLE_VOLUME, rand() * 0.7f + 0.3f);
    }

    private void firework(EdgePlayerEntity npc, PlayerState other) {
        if (other.fireworkTicks > shownFireworkTicks) {
            npc.swing();
            viewer.playSound(at(other, MOUTH_HEIGHT), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
        }
        shownFireworkTicks = other.fireworkTicks;
    }

    private void steps(PlayerState other) {
        if (world == null) {
            return;
        }
        if (!stepSeeded) {
            stepSeeded = true;
            lastX = other.x;
            lastZ = other.z;
            return;
        }
        double dx = other.x - lastX;
        double dz = other.z - lastZ;
        lastX = other.x;
        lastZ = other.z;
        if (!other.onGround || other.swimming || other.gliding) {
            return;
        }
        double before = stepAccumulator;
        stepAccumulator += Math.sqrt(dx * dx + dz * dz) * STEP_ACCUMULATOR_SCALE;
        if ((int) stepAccumulator <= (int) before) {
            return;
        }
        int bx = (int) Math.floor(other.x);
        int by = (int) Math.floor(other.y) - 1;
        int bz = (int) Math.floor(other.z);
        if (by < world.getMinHeight() || by >= world.getMaxHeight()) {
            return;
        }
        Block below = world.getBlockAt(bx, by, bz);
        if (below.getType().isAir()) {
            return;
        }
        SoundGroup group = below.getBlockData().getSoundGroup();
        viewer.playSound(at(other, 0.0), group.getStepSound(), SoundCategory.BLOCKS,
                group.getVolume() * STEP_VOLUME_SCALE, group.getPitch());
    }

    private void effects(EdgePlayerEntity npc, PlayerState other) {
        boolean refresh = ++refreshTick >= EFFECT_REFRESH_TICKS;
        if (refresh) {
            refreshTick = 0;
        }
        for (int id = 1; id < Effects.COUNT; id++) {
            PotionType type = potionType(id);
            if (type == null) {
                continue;
            }
            boolean active = other.effectTicks[id] > 0;
            int amplifier = Math.max(0, other.effectAmp[id]);
            if (!active) {
                if (shownEffect[id]) {
                    shownEffect[id] = false;
                    shownEffectAmp[id] = 0;
                    npc.clearEffect(type);
                }
                continue;
            }
            if (shownEffect[id] && shownEffectAmp[id] == amplifier && !refresh) {
                continue;
            }
            shownEffect[id] = true;
            shownEffectAmp[id] = amplifier;
            npc.effect(type, amplifier, other.effectTicks[id]);
        }
    }

    public static PotionType potionType(int id) {
        return switch (id) {
            case Effects.SPEED -> PotionTypes.SPEED;
            case Effects.SLOWNESS -> PotionTypes.SLOWNESS;
            case Effects.STRENGTH -> PotionTypes.STRENGTH;
            case Effects.INSTANT_HEALTH -> PotionTypes.INSTANT_HEALTH;
            case Effects.INSTANT_DAMAGE -> PotionTypes.INSTANT_DAMAGE;
            case Effects.JUMP_BOOST -> PotionTypes.JUMP_BOOST;
            case Effects.REGENERATION -> PotionTypes.REGENERATION;
            case Effects.RESISTANCE -> PotionTypes.RESISTANCE;
            case Effects.POISON -> PotionTypes.POISON;
            case Effects.WITHER -> PotionTypes.WITHER;
            case Effects.ABSORPTION -> PotionTypes.ABSORPTION;
            case Effects.WEAKNESS -> PotionTypes.WEAKNESS;
            case Effects.SATURATION -> PotionTypes.SATURATION;
            case Effects.HEALTH_BOOST -> PotionTypes.HEALTH_BOOST;
            case Effects.SLOW_FALLING -> PotionTypes.SLOW_FALLING;
            case Effects.FIRE_RESISTANCE -> PotionTypes.FIRE_RESISTANCE;
            default -> null;
        };
    }

    public void reset() {
        shownHeldId = Integer.MIN_VALUE;
        shownHeldSlot = Integer.MIN_VALUE;
        shownHeldCount = Integer.MIN_VALUE;
        shownOffhandId = Integer.MIN_VALUE;
        shownFeetId = Integer.MIN_VALUE;
        shownLegsId = Integer.MIN_VALUE;
        shownChestId = Integer.MIN_VALUE;
        shownHeadId = Integer.MIN_VALUE;
        shownCrossbowLoaded = false;
        shownCrossbowDrawing = false;
        shownEatTicks = 0;
        shownFireworkTicks = 0;
        stepSeeded = false;
        stepAccumulator = 0.0;
        for (int id = 0; id < Effects.COUNT; id++) {
            shownEffect[id] = false;
            shownEffectAmp[id] = 0;
        }
    }

    private static int clampSlot(int heldSlot) {
        return heldSlot < 0 || heldSlot >= EdgeLoadout.HOTBAR_SLOTS ? 0 : heldSlot;
    }

    private Location at(PlayerState state, double yOffset) {
        return new Location(viewer.getWorld(), state.x, state.y + yOffset, state.z);
    }

    private static float rand() {
        return ThreadLocalRandom.current().nextFloat();
    }
}
