package me.nootnoot.edge;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAnimation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import java.util.UUID;
import java.util.function.Function;

public final class EdgeIntentListener extends PacketListenerAbstract {

    private final Function<UUID, EdgeMatch> lookup;

    public EdgeIntentListener(Function<UUID, EdgeMatch> lookup) {
        super(PacketListenerPriority.NORMAL);
        this.lookup = lookup;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        if (user == null || user.getUUID() == null) {
            return;
        }
        EdgeMatch match = lookup.apply(user.getUUID());
        if (match == null) {
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            onInteractEntity(event, match);
        } else if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            onAnimation(event, match);
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            onDigging(event, match);
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            onBlockPlacement(event, match);
        } else if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            onUseItem(event, match);
        } else if (event.getPacketType() == PacketType.Play.Client.ENTITY_ACTION) {
            onEntityAction(event, match);
        }
    }

    private void onInteractEntity(PacketReceiveEvent event, EdgeMatch match) {
        WrapperPlayClientInteractEntity wrapper = new WrapperPlayClientInteractEntity(event);
        int entityId = wrapper.getEntityId();
        boolean crystal = match.input().ownsCrystal(entityId);
        if (!crystal && !match.ownsEntity(entityId)) {
            return;
        }
        event.setCancelled(true);
        if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
            return;
        }
        if (crystal) {
            match.input().claimCrystalEntity(entityId);
        } else {
            match.input().claimAttack();
        }
    }

    private void onAnimation(PacketReceiveEvent event, EdgeMatch match) {
        WrapperPlayClientAnimation wrapper = new WrapperPlayClientAnimation(event);
        if (wrapper.getHand() == InteractionHand.MAIN_HAND) {
            match.input().claimSwing();
        }
    }

    private void onDigging(PacketReceiveEvent event, EdgeMatch match) {
        WrapperPlayClientPlayerDigging wrapper = new WrapperPlayClientPlayerDigging(event);
        DiggingAction action = wrapper.getAction();
        if (action == DiggingAction.START_DIGGING) {
            Vector3i at = wrapper.getBlockPosition();
            match.input().beginBlockDig(at.getX(), at.getY(), at.getZ());
            match.input().acks().claimDigStart(wrapper.getSequence(), at.getX(), at.getY(),
                    at.getZ());
            event.setCancelled(true);
        } else if (action == DiggingAction.FINISHED_DIGGING) {
            Vector3i at = wrapper.getBlockPosition();
            match.input().finishBlockDig();
            match.input().acks().claimDigFinish(wrapper.getSequence(), at.getX(), at.getY(),
                    at.getZ());
            event.setCancelled(true);
        } else if (action == DiggingAction.CANCELLED_DIGGING) {
            match.input().abortBlockDig();
            match.input().acks().claimBare(wrapper.getSequence());
            event.setCancelled(true);
        } else if (action == DiggingAction.DROP_ITEM) {
            match.input().onDrop(false);
            event.setCancelled(true);
        } else if (action == DiggingAction.DROP_ITEM_STACK) {
            match.input().onDrop(true);
            event.setCancelled(true);
        }
    }

    private void onBlockPlacement(PacketReceiveEvent event, EdgeMatch match) {
        WrapperPlayClientPlayerBlockPlacement wrapper =
                new WrapperPlayClientPlayerBlockPlacement(event);
        Vector3i at = wrapper.getBlockPosition();
        BlockFace face = wrapper.getFace();
        boolean offHand = wrapper.getHand() == InteractionHand.OFF_HAND;
        match.input().onUseItemOn(offHand, at.getX(), at.getY(), at.getZ(),
                modX(face), modY(face), modZ(face));
        match.input().acks().claimPlace(wrapper.getSequence(), at.getX(), at.getY(), at.getZ(),
                modX(face), modY(face), modZ(face));
        if (!match.input().vanillaUseAllowed()) {
            event.setCancelled(true);
        }
    }

    private void onUseItem(PacketReceiveEvent event, EdgeMatch match) {
        WrapperPlayClientUseItem wrapper = new WrapperPlayClientUseItem(event);
        match.input().onUseItem(wrapper.getHand() == InteractionHand.OFF_HAND);
        match.input().acks().claimBare(wrapper.getSequence());
        if (!match.input().vanillaUseAllowed()) {
            event.setCancelled(true);
        }
    }

    private void onEntityAction(PacketReceiveEvent event, EdgeMatch match) {
        WrapperPlayClientEntityAction wrapper = new WrapperPlayClientEntityAction(event);
        WrapperPlayClientEntityAction.Action action = wrapper.getAction();
        if (action == WrapperPlayClientEntityAction.Action.START_SPRINTING) {
            match.input().onSprint(true);
        } else if (action == WrapperPlayClientEntityAction.Action.STOP_SPRINTING) {
            match.input().onSprint(false);
        } else if (action == WrapperPlayClientEntityAction.Action.START_FLYING_WITH_ELYTRA) {
            match.input().onElytraStart();
        }
    }

    private static int modX(BlockFace face) {
        return face == null ? 0 : face.getModX();
    }

    private static int modY(BlockFace face) {
        return face == null ? 0 : face.getModY();
    }

    private static int modZ(BlockFace face) {
        return face == null ? 0 : face.getModZ();
    }
}
