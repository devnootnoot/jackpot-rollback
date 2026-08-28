package me.nootnoot.edge.present;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;

public final class CoreReport {

    public record Side(String name, double health, int healthPotsLeft, int totalHealthPots,
                       int totemsLeft, int totalTotems) {
    }

    private CoreReport() {
    }

    public static String formatHealth(double health) {
        double rounded = Math.round(health * 10.0) / 10.0;
        if (health > 0.0 && rounded <= 0.0) {
            rounded = 0.1;
        }
        if (rounded == Math.floor(rounded)) {
            return Long.toString((long) rounded);
        }
        return Double.toString(rounded);
    }

    public static String formatSide(Side side, boolean health, boolean pots, boolean totems,
                                    boolean winner) {
        final double reportedHealth = winner ? side.health() : 0;
        final String name = side.name();

        if (pots) {
            return name + " <accent_color>(<red>" + formatHealth(reportedHealth) + " ❤<white>, "
                    + side.healthPotsLeft() + "/" + side.totalHealthPots() + " ⚗<accent_color>)";
        } else if (totems) {
            return name + " <accent_color>(<red>" + formatHealth(reportedHealth) + " ❤<white>, "
                    + side.totemsLeft() + "/" + side.totalTotems() + " ⛨<accent_color>)";
        } else if (health) {
            return name + " <accent_color>(<red>" + formatHealth(reportedHealth) + " ❤<accent_color>)";
        } else {
            return name;
        }
    }

    public static List<Component> build(boolean round, Side winner, Side loser, boolean pots,
                                        boolean totems, String score, int rounds) {
        List<Component> report = new ArrayList<>();
        report.add(Component.empty());
        report.add(CoreText.mini(round
                ? "<main_color>🏆 <bold>ROUND REPORT</bold> <main_color>🏆"
                : "<main_color>🏆 <bold>MATCH REPORT</bold> <main_color>🏆"));
        report.add(CoreText.mini("<main_color>► <white>Winner: ")
                .append(CoreText.mini("<accent_color>"
                        + formatSide(winner, true, pots, totems, true))));
        report.add(CoreText.mini("<main_color>► <white>Loser: ")
                .append(CoreText.mini("<accent_color>"
                        + formatSide(loser, false, pots, totems, false))));
        report.add(CoreText.mini("<main_color>► <white>Score: <accent_color>" + score));
        report.add(CoreText.mini("<main_color>► <white>First to: <accent_color>"
                + (rounds <= 0 ? "∞" : rounds)));
        report.add(Component.empty());

        return report;
    }

    public static String reportScore(int winnerWins, int loserWins, boolean viewerIsLoser) {
        if (viewerIsLoser) {
            return loserWins + "-" + winnerWins;
        }
        return winnerWins + "-" + loserWins;
    }

    public static String roundScoreDisplay(int viewerWins, int otherWins) {
        return renderRoundScore(viewerWins, otherWins);
    }

    public static String roundScoreDisplayClean(int viewerWins, int otherWins) {
        return renderRoundScore(viewerWins, otherWins);
    }

    private static String renderRoundScore(int viewerWins, int otherWins) {
        return "<green>" + viewerWins + " <gray>- <red>" + otherWins;
    }

    public static String colorForCountdown(int number) {
        if (number > 3) {
            return "&c";
        } else if (number == 3) {
            return "&6";
        } else if (number == 2) {
            return "&e";
        } else if (number == 1) {
            return "&a";
        }
        return "&c";
    }
}
