package me.nootnoot.edge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.potion.PotionType;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCollectItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHurtAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetCooldown;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnExperienceOrb;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;

public final class EdgePackets {

    public static final int LEVEL_EVENT_BREAK_BLOCK = 2001;
    public static final int LEVEL_EVENT_POTION_SPLASH = 2002;
    public static final int LEVEL_EVENT_INSTANT_POTION_SPLASH = 2007;

    public static final byte EFFECT_FLAG_PARTICLES = 0x02;
    public static final byte EFFECT_FLAG_ICON = 0x04;

    private static final int DATA_ITEM_STACK = 8;

    private static final int DATA_FIREWORK_ATTACHED = 9;

    private EdgePackets() {
    }

    public static void send(Player viewer, PacketWrapper<?> wrapper) {
        if (viewer == null || !viewer.isOnline()) {
            return;
        }
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || !api.isInitialized()) {
            return;
        }
        api.getPlayerManager().sendPacketSilently(viewer, wrapper);
    }

    public static void status(Player viewer, int entityId, int status) {
        send(viewer, new WrapperPlayServerEntityStatus(entityId, status));
    }

    public static void criticalHit(Player viewer, int entityId) {
        send(viewer, new WrapperPlayServerEntityAnimation(entityId,
                WrapperPlayServerEntityAnimation.EntityAnimationType.CRITICAL_HIT));
    }

    public static void swing(Player viewer, int entityId) {
        send(viewer, new WrapperPlayServerEntityAnimation(entityId,
                WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM));
    }

    public static void hurt(Player viewer, int entityId, float sourceYaw) {
        send(viewer, new WrapperPlayServerHurtAnimation(entityId, sourceYaw));
    }

    public static void spawn(Player viewer, int entityId, UUID uuid, EntityType type,
                             double x, double y, double z, float yaw, float pitch, int data) {
        send(viewer, new WrapperPlayServerSpawnEntity(entityId, Optional.of(uuid), type,
                new Vector3d(x, y, z), pitch, yaw, yaw, data, Optional.empty()));
    }

    public static void spawn(Player viewer, int entityId, UUID uuid, EntityType type,
                             double x, double y, double z, float yaw, float pitch, int data,
                             double vx, double vy, double vz) {
        send(viewer, new WrapperPlayServerSpawnEntity(entityId, Optional.of(uuid), type,
                new Vector3d(x, y, z), pitch, yaw, yaw, data,
                Optional.of(new Vector3d(vx, vy, vz))));
    }

    public static void teleport(Player viewer, int entityId, double x, double y, double z,
                                float yaw, float pitch) {
        send(viewer, new WrapperPlayServerEntityTeleport(entityId, new Vector3d(x, y, z), yaw, pitch,
                false));
    }

    public static void teleport(Player viewer, int entityId, double x, double y, double z,
                                double vx, double vy, double vz, float yaw, float pitch) {
        send(viewer, new WrapperPlayServerEntityTeleport(entityId, new Vector3d(x, y, z),
                new Vector3d(vx, vy, vz), yaw, pitch, RelativeFlag.NONE, false));
    }

    public static void destroy(Player viewer, int entityId) {
        send(viewer, new WrapperPlayServerDestroyEntities(entityId));
    }

    public static void metadata(Player viewer, int entityId, List<EntityData<?>> data) {
        if (data.isEmpty()) {
            return;
        }
        send(viewer, new WrapperPlayServerEntityMetadata(entityId, data));
    }

    public static void carriedItem(Player viewer, int entityId, org.bukkit.inventory.ItemStack stack) {
        ItemStack payload = stack == null || stack.getType().isAir()
                ? ItemStack.EMPTY : SpigotConversionUtil.fromBukkitItemStack(stack);
        metadata(viewer, entityId,
                List.of(new EntityData<ItemStack>(DATA_ITEM_STACK, EntityDataTypes.ITEMSTACK, payload)));
    }

    public static void attachedRocket(Player viewer, int entityId, int attachedTo,
                                      double x, double y, double z) {
        spawn(viewer, entityId, UUID.randomUUID(), EntityTypes.FIREWORK_ROCKET, x, y, z,
                0.0f, 0.0f, 0);
        metadata(viewer, entityId, List.of(new EntityData<Optional<Integer>>(
                DATA_FIREWORK_ATTACHED, EntityDataTypes.OPTIONAL_INT, Optional.of(attachedTo))));
    }

    public static void breakStage(Player viewer, int breakerId, int x, int y, int z, int stage) {
        send(viewer, new WrapperPlayServerBlockBreakAnimation(breakerId, new Vector3i(x, y, z),
                (byte) stage));
    }

    public static void levelEvent(Player viewer, int type, int x, int y, int z, int data) {
        send(viewer, new WrapperPlayServerEffect(type, new Vector3i(x, y, z), data, false));
    }

    public static void blockAck(Player viewer, int sequence) {
        send(viewer, new WrapperPlayServerAcknowledgeBlockChanges(sequence));
    }

    public static void entityEffect(Player viewer, int entityId, PotionType type, int amplifier,
                                    int durationTicks) {
        send(viewer, new WrapperPlayServerEntityEffect(entityId, type, amplifier, durationTicks,
                (byte) (EFFECT_FLAG_PARTICLES | EFFECT_FLAG_ICON)));
    }

    public static void removeEntityEffect(Player viewer, int entityId, PotionType type) {
        send(viewer, new WrapperPlayServerRemoveEntityEffect(entityId, type));
    }

    public static void collect(Player viewer, int collectedId, int collectorId, int count) {
        send(viewer, new WrapperPlayServerCollectItem(collectedId, collectorId, count));
    }

    public static void spawnOrb(Player viewer, int entityId, double x, double y, double z, int count) {
        send(viewer, new WrapperPlayServerSpawnExperienceOrb(entityId, x, y, z, (short) count));
    }

    public static void cooldown(Player viewer, ItemType item, int ticks) {
        send(viewer, new WrapperPlayServerSetCooldown(item, ticks));
    }
}
