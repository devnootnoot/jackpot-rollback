package me.nootnoot.edge.present;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class CoreSounds {

    private static Plugin plugin;

    private CoreSounds() {
    }

    public static void install(Plugin owner) {
        plugin = owner;
    }

    public static void kill(Player killer) {
        if (killer == null) {
            return;
        }
        play(killer, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.6F);
        playLater(killer, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7F, 1.8F, 2L);
    }

    public static void killEffects(Player killer, Location victimLocation) {
        if (killer != null && killer.isOnline()) {
            play(killer, Sound.ENTITY_PLAYER_LEVELUP, 0.7F, 1.8F);
            play(killer, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.7F);
            playLater(killer, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6F, 2.0F, 4L);
            playLater(killer, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5F, 1.8F, 8L);
        }

        if (victimLocation != null && victimLocation.getWorld() != null) {
            final World world = victimLocation.getWorld();
            final Location center = victimLocation.clone().add(0, 1, 0);

            world.spawnParticle(Particle.EXPLOSION, center, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.SOUL_FIRE_FLAME, center, 18, 0.3, 0.5, 0.3, 0.05);
            world.spawnParticle(Particle.END_ROD, center, 10, 0.3, 0.5, 0.3, 0.07);
            world.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 0.4F, 1.6F);
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.35F, 1.7F);
        }
    }

    public static void death(Player victim) {
        if (victim == null) {
            return;
        }
        play(victim, Sound.BLOCK_NOTE_BLOCK_BASS, 0.9F, 0.5F);
        playLater(victim, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7F, 0.4F, 4L);
    }

    public static void matchStart(Player player) {
        if (player == null) {
            return;
        }
        play(player, Sound.BLOCK_BEACON_ACTIVATE, 0.9F, 1.0F);
        playLater(player, Sound.ITEM_TRIDENT_RETURN, 0.8F, 1.3F, 2L);
    }

    public static void countdownTick(Player player, int remaining) {
        if (player == null) {
            return;
        }
        float pitch = remaining >= 3 ? 1.0F : remaining == 2 ? 1.2F : 1.4F;
        play(player, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0F, pitch);
    }

    public static void roundWon(Player player) {
        if (player == null) {
            return;
        }
        play(player, Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.4F);
        playLater(player, Sound.BLOCK_NOTE_BLOCK_BELL, 0.7F, 1.6F, 2L);
    }

    public static void roundLost(Player player) {
        if (player == null) {
            return;
        }
        play(player, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8F, 1.2F);
        playLater(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6F, 0.6F, 3L);
    }

    public static void play(Player player, Sound sound, float volume, float pitch) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    public static void playLater(Player player, Sound sound, float volume, float pitch,
                                 long delayTicks) {
        if (player == null || plugin == null) {
            return;
        }
        final UUID uuid = player.getUniqueId();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            final Player resolved = Bukkit.getPlayer(uuid);

            if (resolved == null || !resolved.isOnline()) {
                return;
            }

            resolved.playSound(resolved.getLocation(), sound, volume, pitch);
        }, delayTicks);
    }
}
