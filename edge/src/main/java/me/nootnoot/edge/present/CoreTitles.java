package me.nootnoot.edge.present;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class CoreTitles {

    private static final String VICTORY_TEXT = "✪ VICTORY ✪";
    private static final int VICTORY_FRAME_INTERVAL = 1;
    private static final int VICTORY_HALF_FRAMES = 20;
    private static final int VICTORY_TOTAL_FRAMES = VICTORY_HALF_FRAMES * 2;
    private static final int SHIMMER_LEAD = 3;
    private static final String VICTORY_FINAL =
            "<#ffaa00>✪ <#ffd700><b>VICTORY</b> <#ffaa00>✪";

    private static final String FIGHT_TEXT = "⚔ FIGHT ⚔";
    private static final int FIGHT_BOLD_START = 2;
    private static final int FIGHT_BOLD_END = 6;
    private static final int FIGHT_BORDER_HEX = 0x00cc66;
    private static final int FIGHT_ACCENT_HEX = 0x55ff55;
    private static final String FIGHT_FINAL =
            "<#00cc66>⚔ <#55ff55><b>FIGHT</b> <#00cc66>⚔";

    private static final String DEATHMATCH_TEXT = "⚔ DEATHMATCH ⚔";
    private static final int DEATHMATCH_BOLD_START = 2;
    private static final int DEATHMATCH_BOLD_END = 11;
    private static final int DEATHMATCH_BORDER_HEX = 0xcc0000;
    private static final int DEATHMATCH_ACCENT_HEX = 0xff4444;
    private static final String DEATHMATCH_FINAL =
            "<#cc0000>⚔ <#ff4444><b>DEATHMATCH</b> <#cc0000>⚔";

    private static final int FIGHT_HALF_FRAMES = 10;

    private static final Map<UUID, Long> VICTORY_GENERATION = new ConcurrentHashMap<>();

    private static Plugin plugin;

    private CoreTitles() {
    }

    public static void install(Plugin owner) {
        plugin = owner;
    }

    public static void sendTitle(Player player, String title, String subtitle, int fadeIn, int stay,
                                 int fadeOut) {
        if (player == null || !player.isOnline()) {
            return;
        }
        final Title.Times times = Title.Times.times(
                Duration.ofMillis(fadeIn * 50L),
                Duration.ofMillis(stay * 50L),
                Duration.ofMillis(fadeOut * 50L)
        );

        player.showTitle(Title.title(
                CoreText.mini(title),
                CoreText.mini(subtitle),
                times
        ));
    }

    public static void sendLegacyTitle(Player player, String title, String subtitle, int fadeIn,
                                       int stay, int fadeOut) {
        if (player == null || !player.isOnline()) {
            return;
        }
        player.showTitle(Title.title(
                CoreText.legacy(title),
                CoreText.legacy(subtitle),
                Title.Times.times(Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L),
                        Duration.ofMillis(fadeOut * 50L))
        ));
    }

    public static void cancelVictoryAnimation(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        VICTORY_GENERATION.compute(playerUuid, (k, v) -> v == null ? 1L : v + 1L);
    }

    private static boolean isVictoryGenerationCurrent(UUID playerUuid, long capturedGeneration) {
        final Long current = VICTORY_GENERATION.get(playerUuid);
        return current != null && current == capturedGeneration;
    }

    public static void playVictory(Player player, String subtitleMini) {
        if (player == null || !player.isOnline() || plugin == null) {
            return;
        }
        final Title.Times times = Title.Times.times(
                Duration.ofMillis(0),
                Duration.ofMillis(4000),
                Duration.ofMillis(500)
        );

        player.showTitle(Title.title(
                CoreText.mini(buildVictoryFrame(-SHIMMER_LEAD)),
                CoreText.mini(subtitleMini),
                times
        ));

        final UUID uuid = player.getUniqueId();
        final long generation = VICTORY_GENERATION.compute(uuid, (k, v) -> v == null ? 1L : v + 1L);
        final int travel = VICTORY_TEXT.length() + SHIMMER_LEAD * 2;

        for (int frame = 1; frame <= VICTORY_TOTAL_FRAMES; frame++) {
            final int shimmerPos;
            if (frame <= VICTORY_HALF_FRAMES) {
                shimmerPos = travel * frame / VICTORY_HALF_FRAMES - SHIMMER_LEAD;
            } else {
                final int back = frame - VICTORY_HALF_FRAMES;
                shimmerPos = travel - travel * back / VICTORY_HALF_FRAMES - SHIMMER_LEAD;
            }
            final long delay = (long) frame * VICTORY_FRAME_INTERVAL;

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!isVictoryGenerationCurrent(uuid, generation)) {
                    return;
                }

                final Player resolved = Bukkit.getPlayer(uuid);

                if (resolved == null || !resolved.isOnline()) {
                    return;
                }

                resolved.sendTitlePart(TitlePart.TITLE, CoreText.mini(buildVictoryFrame(shimmerPos)));
            }, delay);
        }

        final int shimmerEndTick = VICTORY_TOTAL_FRAMES * VICTORY_FRAME_INTERVAL;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!isVictoryGenerationCurrent(uuid, generation)) {
                return;
            }

            final Player resolved = Bukkit.getPlayer(uuid);

            if (resolved == null || !resolved.isOnline()) {
                return;
            }

            resolved.sendTitlePart(TitlePart.TITLE, CoreText.mini(VICTORY_FINAL));
        }, (long) shimmerEndTick + 2L);
    }

    public static void playFightTitle(Player player, String subtitleMini) {
        runFightStyleAnimation(player, subtitleMini,
                FIGHT_TEXT, FIGHT_BOLD_START, FIGHT_BOLD_END,
                FIGHT_BORDER_HEX, FIGHT_ACCENT_HEX, FIGHT_FINAL);

        CoreSounds.matchStart(player);
    }

    public static void playDeathmatchTitle(Player player, String subtitleMini) {
        runFightStyleAnimation(player, subtitleMini,
                DEATHMATCH_TEXT, DEATHMATCH_BOLD_START, DEATHMATCH_BOLD_END,
                DEATHMATCH_BORDER_HEX, DEATHMATCH_ACCENT_HEX, DEATHMATCH_FINAL);

        if (player == null || !player.isOnline()) {
            return;
        }

        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.3F, 1.1F);
    }

    private static void runFightStyleAnimation(Player player, String subtitleMini,
                                               String text, int boldStart, int boldEnd,
                                               int borderHex, int accentHex, String finalTitle) {
        if (player == null || !player.isOnline() || plugin == null) {
            return;
        }

        final Title.Times times = Title.Times.times(
                Duration.ofMillis(0),
                Duration.ofMillis(1200),
                Duration.ofMillis(250)
        );

        player.showTitle(Title.title(
                CoreText.mini(buildFightStyleFrame(text, -SHIMMER_LEAD, boldStart, boldEnd,
                        borderHex, accentHex)),
                CoreText.mini(subtitleMini),
                times
        ));

        final UUID uuid = player.getUniqueId();
        final int travel = text.length() + SHIMMER_LEAD * 2;
        final int totalFrames = FIGHT_HALF_FRAMES * 2;

        for (int frame = 1; frame <= totalFrames; frame++) {
            final int shimmerPos;
            if (frame <= FIGHT_HALF_FRAMES) {
                shimmerPos = travel * frame / FIGHT_HALF_FRAMES - SHIMMER_LEAD;
            } else {
                final int back = frame - FIGHT_HALF_FRAMES;
                shimmerPos = travel - travel * back / FIGHT_HALF_FRAMES - SHIMMER_LEAD;
            }

            final long delay = frame;

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                final Player resolved = Bukkit.getPlayer(uuid);

                if (resolved == null || !resolved.isOnline()) {
                    return;
                }

                resolved.sendTitlePart(TitlePart.TITLE, CoreText.mini(
                        buildFightStyleFrame(text, shimmerPos, boldStart, boldEnd, borderHex,
                                accentHex)));
            }, delay);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            final Player resolved = Bukkit.getPlayer(uuid);

            if (resolved == null || !resolved.isOnline()) {
                return;
            }

            resolved.sendTitlePart(TitlePart.TITLE, CoreText.mini(finalTitle));
        }, (long) totalFrames + 2L);
    }

    private static String buildFightStyleFrame(String text, int shimmerPos,
                                               int boldStart, int boldEnd,
                                               int borderHex, int accentHex) {
        final StringBuilder sb = new StringBuilder();
        final int lastIdx = text.length() - 1;

        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final int dist = Math.abs(i - shimmerPos);
            final boolean inBold = i >= boldStart && i <= boldEnd;
            final boolean borderChar = !inBold && (i == 0 || i == 1 || i == lastIdx);
            final int baseHex = borderChar ? borderHex : accentHex;

            final String colorTag;
            if (dist == 0) {
                colorTag = "<#ffffff>";
            } else if (dist == 1) {
                colorTag = lerpToWhite(baseHex, 0.8f);
            } else if (dist == 2) {
                colorTag = lerpToWhite(baseHex, 0.55f);
            } else if (dist == 3) {
                colorTag = lerpToWhite(baseHex, 0.3f);
            } else {
                colorTag = String.format("<#%06x>", baseHex);
            }

            if (i == boldStart) {
                sb.append("<b>");
            }

            sb.append(colorTag).append(c);

            if (i == boldEnd) {
                sb.append("</b>");
            }
        }

        return sb.toString();
    }

    private static String lerpToWhite(int hex, float t) {
        final int r = (hex >> 16) & 0xff;
        final int g = (hex >> 8) & 0xff;
        final int b = hex & 0xff;
        final int nr = Math.round(r + t * (0xff - r));
        final int ng = Math.round(g + t * (0xff - g));
        final int nb = Math.round(b + t * (0xff - b));
        return String.format("<#%02x%02x%02x>", nr, ng, nb);
    }

    private static String buildVictoryFrame(int shimmerPos) {
        final StringBuilder sb = new StringBuilder();

        for (int i = 0; i < VICTORY_TEXT.length(); i++) {
            final char c = VICTORY_TEXT.charAt(i);
            final int dist = Math.abs(i - shimmerPos);
            final boolean borderChar = i == 0 || i == 1 || i == 10;

            final String color;
            if (dist == 0) {
                color = "<#ffffff>";
            } else if (dist == 1) {
                color = "<#fff8dc>";
            } else if (dist == 2) {
                color = "<#ffe680>";
            } else if (dist == 3) {
                color = borderChar ? "<#ffc34a>" : "<#ffdf52>";
            } else {
                color = borderChar ? "<#ffaa00>" : "<#ffd700>";
            }

            if (i == 2) {
                sb.append("<b>");
            }

            sb.append(color).append(c);

            if (i == 8) {
                sb.append("</b>");
            }
        }

        return sb.toString();
    }
}
