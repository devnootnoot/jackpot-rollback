package me.nootnoot.edge;

import java.util.List;
import java.util.Locale;

public final class EdgeArchGate {

    public static final String X86_64 = "x86_64";
    public static final String AARCH64 = "aarch64";

    public static final List<String> IDS = List.of(X86_64, AARCH64);

    public static final String ENV = "EDGE_EXPECTED_ARCH";
    public static final String PATH = "broker.expected-arch";

    private EdgeArchGate() {
    }

    public record Verdict(boolean brokerAllowed, boolean warning, String expected, String running,
                          String message) {
    }

    public static String normalize(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (v) {
            case "x86_64", "amd64", "x64", "x8664", "em64t" -> X86_64;
            case "aarch64", "arm64", "armv8", "armv8_64" -> AARCH64;
            default -> v;
        };
    }

    public static Verdict evaluate(String expectedRaw, String osArch) {
        String running = normalize(osArch);
        String expected = normalize(expectedRaw);
        String runningLabel = running.isEmpty() ? "unknown" : running;

        if (expected.isEmpty()) {
            return new Verdict(true, true, "", runningLabel, unset(runningLabel));
        }
        if (!IDS.contains(expected)) {
            return new Verdict(false, false, expected, runningLabel, unknown(expected, runningLabel));
        }
        if (!expected.equals(running)) {
            return new Verdict(false, false, expected, runningLabel, mismatch(expected, runningLabel));
        }
        return new Verdict(true, false, expected, runningLabel, agreed(expected, osArch));
    }

    private static String agreed(String expected, String osArch) {
        return "cpu architecture pinned: " + ENV + "=" + expected + " and this jvm reports os.arch="
                + osArch + ". " + why();
    }

    private static String unset(String running) {
        return "#### CPU ARCHITECTURE NOT PINNED ####"
                + " This edge is brokering anyway, but nothing has told it what architecture the"
                + " fleet runs on, so nothing here can catch a box that was built on the wrong one."
                + " Set " + ENV + "=" + running + " (or " + PATH + ": " + running + " in"
                + " plugins/JackpotEdge/config.yml) on this box: " + running + " is what this jvm"
                + " reports, and setting it is a deliberate statement that every other edge in the"
                + " pool is on " + running + " too. Once it is set, a box that boots on the other"
                + " architecture refuses to broker instead of silently joining. " + why();
    }

    private static String unknown(String expected, String running) {
        return refusal("the configured architecture '" + expected + "' is not one this build knows")
                + " Use one of " + IDS + ", or unset " + ENV + " and " + PATH + " entirely to go"
                + " back to a warning. This jvm reports " + running + ", so " + ENV + "=" + running
                + " is the value that lets this box broker.";
    }

    private static String mismatch(String expected, String running) {
        return refusal("this box is " + running + " and the fleet is pinned to " + expected)
                + " Either move this edge onto " + expected + " hardware, or, if the whole fleet is"
                + " being moved, change " + ENV + " on EVERY edge in the same deploy. A single"
                + " mismatched box desyncs every match it plays against the rest of the fleet, and"
                + " its version fence triple is identical to theirs, so nothing else will say why.";
    }

    private static String refusal(String because) {
        return "#### BROKERING REFUSED: CPU ARCHITECTURE MISMATCH ####"
                + " This edge will not take assignments because " + because + "."
                + " The rollback sim is only proven bit-identical across operating systems, not yet"
                + " across cpu architectures.";
    }

    private static String why() {
        return "The fleet must stay on one architecture until the arm64 half of the determinism"
                + " gate is green, because two edges on different architectures desync every match"
                + " between their players and the version fence compares build numbers, not"
                + " results.";
    }
}
