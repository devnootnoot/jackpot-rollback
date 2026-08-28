package me.nootnoot.edge;

import java.util.logging.Logger;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

public final class EdgeAttackSpeed {

    public static final double VANILLA_BASE = 4.0;

    public static final double MAX_BASE = 1024.0;

    static final double EPSILON = 1.0E-6;

    static final double FOREIGN_BASE_FLOOR = 16.0;

    public enum Verdict {
        NO_ATTRIBUTE,
        CLEAN,
        DAMAGED,
        FOREIGN
    }

    public record Report(Verdict verdict, double base, double vanillaBase, int modifiers) {

        public boolean repairable() {
            return verdict == Verdict.DAMAGED;
        }

        public String describe() {
            return switch (verdict) {
                case NO_ATTRIBUTE -> "attack-speed: this entity has no ATTACK_SPEED attribute";
                case CLEAN -> "attack-speed: base " + base + " is the vanilla default ("
                        + vanillaBase + "), " + modifiers + " modifier(s) on top - nothing to repair";
                case DAMAGED -> "attack-speed: base " + base + " is NOT the vanilla default ("
                        + vanillaBase + ") - a match-time shift was persisted into this account's"
                        + " playerdata and follows it into every other mode";
                case FOREIGN -> "attack-speed: base " + base + " is outside the band this edge can"
                        + " ever produce (0 < base < " + FOREIGN_BASE_FLOOR + "), so another"
                        + " feature owns it - mcleagues-core sets " + MAX_BASE + " for a custom kit"
                        + " with spam-hits on. Left untouched";
            };
        }
    }

    private EdgeAttackSpeed() {
    }

    public static double vanillaBase(AttributeInstance attribute) {
        if (attribute == null) {
            return VANILLA_BASE;
        }
        double declared = attribute.getDefaultValue();
        return declared > 0.0 && declared <= MAX_BASE ? declared : VANILLA_BASE;
    }

    public static Report inspect(Player player) {
        AttributeInstance attribute = player == null ? null
                : player.getAttribute(Attribute.ATTACK_SPEED);
        if (attribute == null) {
            return new Report(Verdict.NO_ATTRIBUTE, Double.NaN, VANILLA_BASE, 0);
        }
        double vanilla = vanillaBase(attribute);
        double base = attribute.getBaseValue();
        int modifiers = attribute.getModifiers().size();
        return new Report(verdict(base, vanilla), base, vanilla, modifiers);
    }

    static Verdict verdict(double base, double vanillaBase) {
        if (Double.isNaN(base)) {
            return Verdict.FOREIGN;
        }
        if (Math.abs(base - vanillaBase) <= EPSILON) {
            return Verdict.CLEAN;
        }
        if (base <= 0.0 || base >= FOREIGN_BASE_FLOOR) {
            return Verdict.FOREIGN;
        }
        return Verdict.DAMAGED;
    }

    public static Report repair(Player player, Logger log, String reason, boolean force) {
        Report report = inspect(player);
        if (report.verdict() == Verdict.NO_ATTRIBUTE || report.verdict() == Verdict.CLEAN) {
            return report;
        }
        if (report.verdict() == Verdict.FOREIGN && !force) {
            if (log != null) {
                log.warning("[attack-speed] " + player.getName() + " (" + reason + ") "
                        + report.describe() + ". Run /edge attackspeed " + player.getName()
                        + " force to overwrite it anyway.");
            }
            return report;
        }
        AttributeInstance attribute = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attribute == null) {
            return new Report(Verdict.NO_ATTRIBUTE, Double.NaN, VANILLA_BASE, 0);
        }
        double vanilla = vanillaBase(attribute);
        try {
            attribute.setBaseValue(vanilla);
        } catch (RuntimeException ex) {
            if (log != null) {
                log.warning("[attack-speed] " + player.getName() + " (" + reason + ") could not be"
                        + " repaired: " + ex);
            }
            return report;
        }
        if (log != null) {
            log.warning("[attack-speed] REPAIRED " + player.getName() + " (" + reason + "): base "
                    + report.base() + " -> " + vanilla + ". Only the BASE value was written; the "
                    + report.modifiers() + " attribute modifier(s) on this player were left"
                    + " untouched, so nothing another feature applied through an AttributeModifier"
                    + " is affected.");
        }
        return new Report(Verdict.CLEAN, vanilla, vanilla, report.modifiers());
    }
}
