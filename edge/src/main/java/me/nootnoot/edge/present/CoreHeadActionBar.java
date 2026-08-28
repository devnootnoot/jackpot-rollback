package me.nootnoot.edge.present;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class CoreHeadActionBar {

    public record Face(String name, UUID uuid, String skinValue, String skinSignature) {
    }

    private static final int INTERVAL = 20;
    private static final int DURATION = 8 * 20;

    private static Plugin plugin;
    private static boolean nmsChecked;
    private static Method fromJson;
    private static Method getHandle;
    private static Field connectionField;
    private static Method sendMethod;
    private static Constructor<?> actionBarPacket;

    private CoreHeadActionBar() {
    }

    public static void install(Plugin owner) {
        plugin = owner;
    }

    public static void send(Player viewer, Face left, String value, Face right) {
        if (viewer == null || plugin == null) {
            return;
        }
        final int repeats = (int) Math.ceil((double) DURATION / INTERVAL);
        final int[] amountDone = {0};
        final UUID uuid = viewer.getUniqueId();
        final String json = payload(left, value, right);

        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            final Player resolved = Bukkit.getPlayer(uuid);
            if (resolved == null || !resolved.isOnline() || amountDone[0] == repeats) {
                task.cancel();
                return;
            }
            if (!sendRaw(resolved, json)) {
                resolved.sendActionBar(Component.text(value, NamedTextColor.GRAY));
            }
            amountDone[0]++;
        }, 0L, INTERVAL);
    }

    private static String payload(Face left, String value, Face right) {
        JsonArray root = new JsonArray();
        root.add(head(left));
        root.add(text(value, "gray"));
        root.add(head(right));
        return root.toString();
    }

    private static JsonObject head(Face face) {
        JsonObject component = new JsonObject();
        component.addProperty("type", "object");
        component.addProperty("object", "player");
        component.add("player", profile(face));
        component.addProperty("hat", true);
        return component;
    }

    private static JsonObject profile(Face face) {
        JsonObject profile = new JsonObject();
        profile.addProperty("name", face.name());
        profile.add("id", uuidToIntArray(face.uuid()));

        JsonArray properties = new JsonArray();

        if (face.skinValue() != null && !face.skinValue().isBlank()) {
            JsonObject texture = new JsonObject();
            texture.addProperty("name", "textures");
            texture.addProperty("value", face.skinValue());
            if (face.skinSignature() != null && !face.skinSignature().isBlank()) {
                texture.addProperty("signature", face.skinSignature());
            }
            properties.add(texture);
        }

        profile.add("properties", properties);
        return profile;
    }

    private static JsonObject text(String value, String color) {
        JsonObject component = new JsonObject();
        component.addProperty("text", value);
        component.addProperty("color", color);
        return component;
    }

    private static JsonArray uuidToIntArray(UUID uuid) {
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();

        JsonArray array = new JsonArray();

        array.add((int) (most >> 32));
        array.add((int) most);
        array.add((int) (least >> 32));
        array.add((int) least);

        return array;
    }

    private static synchronized boolean resolveNms() {
        if (nmsChecked) {
            return fromJson != null;
        }
        nmsChecked = true;
        try {
            Class<?> chat = Class.forName("org.bukkit.craftbukkit.util.CraftChatMessage");
            Class<?> component = Class.forName("net.minecraft.network.chat.Component");
            Class<?> packet = Class.forName(
                    "net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket");
            Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer");
            Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> listener = Class.forName(
                    "net.minecraft.server.network.ServerGamePacketListenerImpl");
            Class<?> packetType = Class.forName("net.minecraft.network.protocol.Packet");

            fromJson = chat.getMethod("fromJSON", String.class);
            actionBarPacket = packet.getConstructor(component);
            getHandle = craftPlayer.getMethod("getHandle");
            connectionField = serverPlayer.getField("connection");
            sendMethod = listener.getMethod("send", packetType);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            fromJson = null;
            return false;
        }
    }

    private static boolean sendRaw(Player viewer, String json) {
        if (!resolveNms()) {
            return false;
        }
        try {
            Object component = fromJson.invoke(null, json);
            Object packet = actionBarPacket.newInstance(component);
            Object handle = getHandle.invoke(viewer);
            Object connection = connectionField.get(handle);
            sendMethod.invoke(connection, packet);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            fromJson = null;
            return false;
        }
    }
}
