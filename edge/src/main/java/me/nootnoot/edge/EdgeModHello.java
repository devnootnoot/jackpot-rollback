package me.nootnoot.edge;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EdgeModHello extends PacketListenerAbstract {

    public static final String CHANNEL = "jackpotrollback:mod_hello";

    private final Map<UUID, Integer> versions = new ConcurrentHashMap<>();

    public EdgeModHello() {
        super(PacketListenerPriority.NORMAL);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getConnectionState() != ConnectionState.PLAY) {
            return;
        }
        if (event.getPacketType() != PacketType.Play.Client.PLUGIN_MESSAGE) {
            return;
        }
        WrapperPlayClientPluginMessage wrapper = new WrapperPlayClientPluginMessage(event);
        if (!CHANNEL.equals(wrapper.getChannelName())) {
            return;
        }
        User user = event.getUser();
        if (user == null || user.getUUID() == null) {
            return;
        }
        byte[] data = wrapper.getData();
        if (data.length < Integer.BYTES) {
            return;
        }
        versions.put(user.getUUID(), ByteBuffer.wrap(data).getInt());
    }

    public Integer version(UUID uuid) {
        return versions.get(uuid);
    }

    public boolean modded(UUID uuid) {
        return versions.containsKey(uuid);
    }

    public void forget(UUID uuid) {
        versions.remove(uuid);
    }
}
