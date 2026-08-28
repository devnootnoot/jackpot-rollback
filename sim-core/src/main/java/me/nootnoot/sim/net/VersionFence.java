package me.nootnoot.sim.net;

import java.util.ArrayList;
import java.util.List;

public final class VersionFence {

    public static final int UNKNOWN = -1;

    public static final String EDGE_ARTIFACT = "jackpot-edge.jar (plugins/ on the edge server)";

    public static final String MOD_ARTIFACT = "pvphq-mod-<mc>.jar (mods/ on the client)";

    public static final String CORE_ARTIFACT =
            "mcleagues.jar (RollbackModRegistry.EXPECTED_VERSION on the practice server)";

    public static final String BANNER = "######## ROLLBACK VERSION FENCE MISMATCH ########";

    public static final String FOOTER = "################################################";

    private VersionFence() {
    }

    public static int inputBytes(int version) {
        return version < 0 ? UNKNOWN : (version >>> 8) & 0xFFFFFF;
    }

    public static int checksumRev(int version) {
        return version < 0 ? UNKNOWN : version & 0xFF;
    }

    public static String triple(int version) {
        if (version < 0) {
            return "none";
        }
        return inputBytes(version) + "/" + checksumRev(version) + "/" + version;
    }

    public static String local() {
        return triple(Protocol.VERSION);
    }

    public static String describe(String artifact, int version) {
        if (version < 0) {
            return artifact + " reported no version at all";
        }
        return artifact + " is at inputBytes/checksumRev/protocolVersion " + triple(version);
    }

    public static int likelyStale(int a, int b) {
        if (a == b) {
            return UNKNOWN;
        }
        if (a < 0) {
            return 0;
        }
        if (b < 0) {
            return 1;
        }
        int ra = checksumRev(a);
        int rb = checksumRev(b);
        if (ra != rb) {
            return ra < rb ? 0 : 1;
        }
        return inputBytes(a) < inputBytes(b) ? 0 : 1;
    }

    public static List<String> whereToLook() {
        List<String> out = new ArrayList<>(7);
        out.add("Every artifact below embeds its own copy of sim-core, and each one carries the");
        out.add("protocolVersion it was built from. Find the one whose number is the odd one out:");
        out.add("  " + EDGE_ARTIFACT
                + " -> jackpot-rollback: gradlew verifyEmbeddedSimVersion");
        out.add("  " + MOD_ARTIFACT
                + " -> pvphq-rollback-mod: gradlew verifyModJars");
        out.add("  " + CORE_ARTIFACT
                + " -> mcleagues-core: gradlew verifyRollbackFence");
        out.add("This build compiles to " + local() + ". Runbook: jackpot-rollback/RUNBOOK.md,"
                + " section 'A version fence mismatch'.");
        return out;
    }

    public static List<String> report(String context, String labelA, int versionA,
                                      String labelB, int versionB) {
        List<String> out = new ArrayList<>(16);
        out.add(BANNER);
        if (context != null && !context.isBlank()) {
            out.add(context);
        }
        out.add("  " + describe(labelA, versionA));
        out.add("  " + describe(labelB, versionB));
        int stale = likelyStale(versionA, versionB);
        if (stale == UNKNOWN) {
            out.add("  Both sides report the SAME fence, so this is not a version skew."
                    + " Something else refused the match - do not go looking for a stale jar.");
        } else {
            String staleLabel = stale == 0 ? labelA : labelB;
            int staleVersion = stale == 0 ? versionA : versionB;
            int freshVersion = stale == 0 ? versionB : versionA;
            if (staleVersion < 0) {
                out.add("  LIKELY STALE: " + staleLabel + " - it sent no version at all, so it is"
                        + " either not running the rollback build or is old enough to predate the"
                        + " fence. The other side is at " + triple(freshVersion) + ".");
            } else {
                out.add("  LIKELY STALE: " + staleLabel + " - checksumRev " + checksumRev(staleVersion)
                        + " against " + checksumRev(freshVersion) + ", and checksumRev only ever"
                        + " goes up. Rebuild and redeploy THAT side first.");
            }
        }
        out.addAll(whereToLook());
        out.add(FOOTER);
        return out;
    }
}
