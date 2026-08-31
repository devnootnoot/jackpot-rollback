package me.nootnoot.edge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;

public final class EdgePlayerEntity {

    public static final int ENTITY_ID_BASE = 1_900_000_000;
    public static final int ENTITY_ID_SPAN = 0x10000;

    private static final AtomicInteger ENTITY_ID_SEQ = new AtomicInteger();

    private static final int DATA_FLAGS = 0;
    private static final int DATA_NO_GRAVITY = 5;
    private static final int DATA_POSE = 6;
    private static final int DATA_LIVING_FLAGS = 8;
    private static final int DATA_HEALTH = 9;
    private static final int DATA_SKIN_LAYERS = 16;

    private static final byte LIVING_FLAG_USING_ITEM = 0x01;
    private static final byte LIVING_FLAG_OFF_HAND = 0x02;

    private static final byte FLAG_ON_FIRE = 0x01;
    private static final byte FLAG_SNEAKING = 0x02;
    private static final byte FLAG_SPRINTING = 0x08;
    private static final byte FLAG_SWIMMING = 0x10;
    private static final byte FLAG_GLIDING = (byte) 0x80;

    private static final byte ALL_SKIN_LAYERS = 0x7F;

    private static final String TEXTURES_KEY = "textures";
    private static final String NAME_FALLBACK = "opponent";
    private static final int NAME_LIMIT = 16;

    private static final float DEGREES_TO_BYTE = 0.7111111f;

    private final Player viewer;


    private final int entityId;
    private final UUID uuid;
    private final UserProfile profile;
    private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);

    private static final double RELATIVE_MOVE_LIMIT = 7.5;

    private static final double MOVE_EPSILON_SQ = 7.6293945E-6;

    private static final int RESYNC_INTERVAL_TICKS = 100;

    private double sentX;
    private double sentY;
    private double sentZ;
    private boolean sentPosition;
    private int sinceResync;

    private boolean spawned;
    private boolean sneaking;
    private boolean sprinting;
    private boolean swimming;
    private boolean gliding;
    private boolean onFire;
    private boolean blocking;
    private boolean blockingOffHand;
    private EntityPose pose = EntityPose.STANDING;

    private boolean flagsDirty;
    private boolean poseDirty;
    private boolean livingFlagsDirty;
    private int lastHeadYawByte = Integer.MIN_VALUE;

    public EdgePlayerEntity(Player viewer, int entityId, UUID uuid, String name,
                            String skinValue, String skinSignature) {
        this.viewer = viewer;
        this.entityId = entityId;
        this.uuid = uuid != null ? uuid : UUID.randomUUID();
        this.profile = new UserProfile(this.uuid, trimName(name), textures(skinValue, skinSignature));
    }

    public static int nextEntityId() {
        return ENTITY_ID_BASE + (ENTITY_ID_SEQ.getAndIncrement() & (ENTITY_ID_SPAN - 1));
    }

    public static ItemStack itemOf(org.bukkit.inventory.ItemStack bukkit) {
        if (bukkit == null || bukkit.getType().isAir()) {
            return ItemStack.EMPTY;
        }
        return SpigotConversionUtil.fromBukkitItemStack(bukkit);
    }

    private static String trimName(String name) {
        if (name == null || name.isBlank()) {
            return NAME_FALLBACK;
        }
        String trimmed = name.trim();
        return trimmed.length() > NAME_LIMIT ? trimmed.substring(0, NAME_LIMIT) : trimmed;
    }

    private static List<TextureProperty> textures(String value, String signature) {
        List<TextureProperty> properties = new ArrayList<>(1);
        if (value != null && !value.isBlank()) {
            String sig = signature == null || signature.isBlank() ? null : signature;
            properties.add(new TextureProperty(TEXTURES_KEY, value, sig));
        }
        return properties;
    }

    private static EntityPose poseFor(boolean gliding, boolean swimming, boolean sneaking) {
        if (gliding) {
            return EntityPose.FALL_FLYING;
        }
        if (swimming) {
            return EntityPose.SWIMMING;
        }
        return sneaking ? EntityPose.CROUCHING : EntityPose.STANDING;
    }

    public Player viewer() {
        return viewer;
    }

    public int entityId() {
        return entityId;
    }

    public UUID uuid() {
        return uuid;
    }

    public boolean spawned() {
        return spawned;
    }

    public void spawn(double x, double y, double z, float yaw, float pitch) {
        if (spawned || !viewer.isOnline()) {
            return;
        }
        spawned = true;
        sentX = x;
        sentY = y;
        sentZ = z;
        sentPosition = true;
        sinceResync = 0;

        send(new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED),
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile, false, 0, GameMode.SURVIVAL,
                        null, null)));

        send(new WrapperPlayServerSpawnEntity(entityId, Optional.of(uuid), EntityTypes.PLAYER,
                new Vector3d(x, y, z), pitch, yaw, yaw, 0, Optional.empty()));

        List<EntityData<?>> data = new ArrayList<>(4);
        data.add(new EntityData<Byte>(DATA_FLAGS, EntityDataTypes.BYTE, flags()));
        data.add(new EntityData<Boolean>(DATA_NO_GRAVITY, EntityDataTypes.BOOLEAN, Boolean.TRUE));
        data.add(new EntityData<EntityPose>(DATA_POSE, EntityDataTypes.ENTITY_POSE, pose));
        data.add(new EntityData<Byte>(DATA_SKIN_LAYERS, EntityDataTypes.BYTE, ALL_SKIN_LAYERS));
        send(new WrapperPlayServerEntityMetadata(entityId, data));
        flagsDirty = false;
        poseDirty = false;

        sendHeadYaw(yaw, true);

        if (!equipment.isEmpty()) {
            sendEquipment(new ArrayList<>(equipment.keySet()));
        }
    }

    public void move(double x, double y, double z, float yaw, float pitch, boolean onGround) {
        if (!spawned || !viewer.isOnline()) {
            return;
        }
        double dx = x - sentX;
        double dy = y - sentY;
        double dz = z - sentZ;
        boolean idle = sentPosition && dx * dx + dy * dy + dz * dz < MOVE_EPSILON_SQ;
        boolean encodable = !idle
                && sentPosition
                && Math.abs(dx) < RELATIVE_MOVE_LIMIT
                && Math.abs(dy) < RELATIVE_MOVE_LIMIT
                && Math.abs(dz) < RELATIVE_MOVE_LIMIT
                && ++sinceResync < RESYNC_INTERVAL_TICKS;
        if (encodable) {
            send(new WrapperPlayServerEntityRelativeMoveAndRotation(entityId, dx, dy, dz,
                    yaw, pitch, onGround));
        } else {
            sinceResync = 0;
            send(new WrapperPlayServerEntityTeleport(entityId, new Vector3d(x, y, z), yaw, pitch,
                    onGround));
        }
        sentX = x;
        sentY = y;
        sentZ = z;
        sentPosition = true;
        sendHeadYaw(yaw, false);
        flushMetadata();
    }

    public void setSneaking(boolean value) {
        if (sneaking == value) {
            return;
        }
        sneaking = value;
        flagsDirty = true;
        if (pose == EntityPose.STANDING || pose == EntityPose.CROUCHING) {
            applyPose(poseFor(gliding, swimming, value));
        }
        flushMetadata();
    }

    public void setSprinting(boolean value) {
        if (sprinting == value) {
            return;
        }
        sprinting = value;
        flagsDirty = true;
        flushMetadata();
    }

    public void setOnFire(boolean value) {
        if (onFire == value) {
            return;
        }
        onFire = value;
        flagsDirty = true;
        flushMetadata();
    }

    public void setPose(EntityPose value) {
        applyPose(value);
        flushMetadata();
    }

    public void setState(boolean sneaking, boolean sprinting, boolean swimming, boolean gliding,
                         boolean onFire) {
        if (this.sneaking != sneaking || this.sprinting != sprinting || this.swimming != swimming
                || this.gliding != gliding || this.onFire != onFire) {
            this.sneaking = sneaking;
            this.sprinting = sprinting;
            this.swimming = swimming;
            this.gliding = gliding;
            this.onFire = onFire;
            flagsDirty = true;
        }
        applyPose(poseFor(gliding, swimming, sneaking));
        flushMetadata();
    }

    public void setBlocking(boolean value, boolean offHand) {
        setUsingItem(value, offHand);
    }

    public void setUsingItem(boolean value, boolean offHand) {
        if (blocking == value && blockingOffHand == offHand) {
            return;
        }
        blocking = value;
        blockingOffHand = offHand;
        livingFlagsDirty = true;
        flushMetadata();
    }

    public void effect(PotionType type, int amplifier, int durationTicks) {
        if (!spawned || type == null) {
            return;
        }
        EdgePackets.entityEffect(viewer, entityId, type, amplifier, durationTicks);
    }

    public void clearEffect(PotionType type) {
        if (!spawned || type == null) {
            return;
        }
        EdgePackets.removeEntityEffect(viewer, entityId, type);
    }

    public void setHealth(float value) {
        if (!spawned) {
            return;
        }
        send(new WrapperPlayServerEntityMetadata(entityId,
                List.of(new EntityData<Float>(DATA_HEALTH, EntityDataTypes.FLOAT, value))));
    }

    public void status(int status) {
        if (!spawned) {
            return;
        }
        EdgePackets.status(viewer, entityId, status);
    }

    public void flushMetadata() {
        if (!spawned || (!flagsDirty && !poseDirty && !livingFlagsDirty)) {
            return;
        }
        List<EntityData<?>> data = new ArrayList<>(3);
        if (flagsDirty) {
            data.add(new EntityData<Byte>(DATA_FLAGS, EntityDataTypes.BYTE, flags()));
            flagsDirty = false;
        }
        if (poseDirty) {
            data.add(new EntityData<EntityPose>(DATA_POSE, EntityDataTypes.ENTITY_POSE, pose));
            poseDirty = false;
        }
        if (livingFlagsDirty) {
            data.add(new EntityData<Byte>(DATA_LIVING_FLAGS, EntityDataTypes.BYTE, livingFlags()));
            livingFlagsDirty = false;
        }
        send(new WrapperPlayServerEntityMetadata(entityId, data));
    }

    private byte livingFlags() {
        if (!blocking) {
            return 0;
        }
        int bits = LIVING_FLAG_USING_ITEM;
        if (blockingOffHand) {
            bits |= LIVING_FLAG_OFF_HAND;
        }
        return (byte) bits;
    }

    public void swing() {
        if (!spawned) {
            return;
        }
        send(new WrapperPlayServerEntityAnimation(entityId,
                WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
    }

    public void swingOffHand() {
        if (!spawned) {
            return;
        }
        send(new WrapperPlayServerEntityAnimation(entityId,
                WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND));
    }

    public void hurt() {
        hurt(0.0f);
    }

    public void hurt(float sourceYaw) {
        if (!spawned) {
            return;
        }
        send(new WrapperPlayServerHurtAnimation(entityId, sourceYaw));
    }

    public void setHeldItem(ItemStack item) {
        setEquipment(EquipmentSlot.MAIN_HAND, item);
    }

    public void setEquipment(EquipmentSlot slot, ItemStack item) {
        ItemStack value = item != null ? item : ItemStack.EMPTY;
        if (Objects.equals(equipment.put(slot, value), value)) {
            return;
        }
        if (spawned) {
            sendEquipment(List.of(slot));
        }
    }

    public void setEquipment(Map<EquipmentSlot, ItemStack> items) {
        List<EquipmentSlot> changed = new ArrayList<>(items.size());
        for (Map.Entry<EquipmentSlot, ItemStack> entry : items.entrySet()) {
            ItemStack value = entry.getValue() != null ? entry.getValue() : ItemStack.EMPTY;
            if (!Objects.equals(equipment.put(entry.getKey(), value), value)) {
                changed.add(entry.getKey());
            }
        }
        if (spawned && !changed.isEmpty()) {
            sendEquipment(changed);
        }
    }

    public void despawn() {
        if (!spawned) {
            return;
        }
        spawned = false;
        sentPosition = false;
        flagsDirty = false;
        poseDirty = false;
        livingFlagsDirty = false;
        blocking = false;
        blockingOffHand = false;
        lastHeadYawByte = Integer.MIN_VALUE;
        send(new WrapperPlayServerDestroyEntities(entityId));
        send(new WrapperPlayServerPlayerInfoRemove(uuid));
    }

    private void applyPose(EntityPose value) {
        if (value == null || pose == value) {
            return;
        }
        pose = value;
        poseDirty = true;
    }

    private byte flags() {
        int bits = 0;
        if (onFire) {
            bits |= FLAG_ON_FIRE;
        }
        if (sneaking) {
            bits |= FLAG_SNEAKING;
        }
        if (sprinting) {
            bits |= FLAG_SPRINTING;
        }
        if (swimming) {
            bits |= FLAG_SWIMMING;
        }
        if (gliding) {
            bits |= FLAG_GLIDING;
        }
        return (byte) bits;
    }

    private void sendHeadYaw(float yaw, boolean force) {
        int packed = ((int) (yaw * DEGREES_TO_BYTE)) & 0xFF;
        if (!force && packed == lastHeadYawByte) {
            return;
        }
        lastHeadYawByte = packed;
        send(new WrapperPlayServerEntityHeadLook(entityId, yaw));
    }

    private void sendEquipment(List<EquipmentSlot> slots) {
        List<Equipment> payload = new ArrayList<>(slots.size());
        for (EquipmentSlot slot : slots) {
            payload.add(new Equipment(slot, equipment.getOrDefault(slot, ItemStack.EMPTY)));
        }
        send(new WrapperPlayServerEntityEquipment(entityId, payload));
    }

    private void send(PacketWrapper<?> wrapper) {
        if (!viewer.isOnline()) {
            return;
        }
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || !api.isInitialized()) {
            return;
        }
        api.getPlayerManager().sendPacketSilently(viewer, wrapper);
    }
}
