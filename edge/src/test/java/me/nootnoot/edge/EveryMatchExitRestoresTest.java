package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class EveryMatchExitRestoresTest {

    private static final Path PLUGIN = Path.of("src/main/java/me/nootnoot/edge/EdgePlugin.java");
    private static final Path MIRROR =
            Path.of("src/main/java/me/nootnoot/edge/EdgeStatusMirror.java");

    private static final String FUNNEL = "endMatch(";

    private static final List<String> EXITS = List.of(
            "private void finish(UUID uuid, EdgeMatch m)",
            "public void onQuit(PlayerQuitEvent event)",
            "public void onDisable()",
            "public boolean onCommand(CommandSender sender, Command command, String label,"
                    + " String[] args)");

    private static String source(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String bodyOf(String src, String signature, Path path) {
        int start = src.indexOf(signature);
        assertTrue(start >= 0, path.getFileName() + " no longer declares '" + signature
                + "'. This guard reads the source of every match-exit path by name, so a rename"
                + " must come with a rename here - otherwise the guard silently stops guarding.");
        return braceBody(src, start, signature);
    }

    private static String braceBody(String src, int from, String what) {
        int i = src.indexOf('{', from);
        assertTrue(i >= 0, what + " has no body");
        int open = i;
        int depth = 0;
        for (; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError(what + " has no closing brace");
    }

    @Test
    void everyWayAMatchCanEndGoesThroughTheOneTotalTeardown() throws IOException {
        String src = source(PLUGIN);
        List<String> missing = new ArrayList<>();
        for (String exit : EXITS) {
            if (!bodyOf(src, exit, PLUGIN).contains(FUNNEL)) {
                missing.add(exit);
            }
        }
        assertTrue(missing.isEmpty(),
                "these match-exit paths do not route through " + FUNNEL + ": " + missing
                        + ". EdgeMatch.end() is the ONLY route to EdgeStatusMirror.reset(), which"
                        + " is what hands the player back the ATTACK_SPEED base the match shifted."
                        + " A clean finish, a forfeit, a quit, a kick (which fires PlayerQuitEvent"
                        + " too), /edge stop, a desync or peer-gone abort, and plugin disable are"
                        + " all ways a match ends, and every one of them has to reach the same"
                        + " teardown. A new exit that tears down by hand leaks the attribute into"
                        + " every other mode on the server.");
    }

    @Test
    void nothingOutsideTheFunnelEvictsALiveMatchOrEndsItByHand() throws IOException {
        String src = source(PLUGIN);
        String funnel = bodyOf(src, "private void endMatch(UUID uuid, EdgeMatch m, String localExit,"
                + " Runnable farewell)", PLUGIN);

        int evictions = count(src, "matches.remove(");
        assertEquals(1, evictions,
                "matches.remove( appears " + evictions + " times in EdgePlugin. Exactly one is"
                        + " allowed and it must be the one inside endMatch: a site that evicts the"
                        + " match from the live map and then tears down by hand is a teardown that"
                        + " no longer has anything forcing it to finish, and the match object is"
                        + " unreachable afterwards so nothing can finish it later.");
        assertTrue(funnel.contains("matches.remove("),
                "the single matches.remove( is not the one inside endMatch");

        Matcher hand = Pattern.compile("(?<![\\w.])(\\w+)\\.end\\(\\)\\s*;").matcher(src);
        List<String> byHand = new ArrayList<>();
        while (hand.find()) {
            byHand.add(hand.group());
        }
        assertTrue(byHand.isEmpty(),
                "EdgePlugin calls " + byHand + " directly. Every end() must go through endMatch,"
                        + " which is the only place that guarantees the restore runs even when an"
                        + " earlier step throws.");
    }

    @Test
    void theFunnelRunsTheRestoreEvenWhenAnEarlierStepThrows() throws IOException {
        String funnel = bodyOf(source(PLUGIN), "private void endMatch(UUID uuid, EdgeMatch m,"
                + " String localExit, Runnable farewell)", PLUGIN);

        int finallyAt = funnel.indexOf("} finally {");
        assertTrue(finallyAt >= 0,
                "endMatch has no finally block. The steps before the restore are a broker report"
                        + " over the network, an end screen drawn for a player who may already be"
                        + " gone, and a Bukkit inventory restore - any of them can throw, and when"
                        + " one did, m.end() never ran and the player kept the match's"
                        + " ATTACK_SPEED. This is the exact defect shape the week keeps"
                        + " reproducing: the fix only runs on the happy path.");
        assertTrue(funnel.indexOf("m::end") > finallyAt,
                "m::end is not inside endMatch's finally block, so the restore is still skippable");

        String happyPath = funnel.substring(0, finallyAt);
        for (String bare : new String[] {"reportResult(", "releaseMatch(", "farewell.run()"}) {
            int at = happyPath.indexOf(bare);
            if (at < 0) {
                continue;
            }
            assertTrue(happyPath.lastIndexOf("teardownStep(", at) > happyPath.lastIndexOf(';', at),
                    "endMatch calls " + bare + " unguarded. One throwing step must not stop the"
                            + " steps after it - a half-run teardown strands the cage, the arena"
                            + " paste or the pre-match inventory.");
        }
    }

    @Test
    void theAttributeRestoreHappensBeforeTheOfflineEarlyReturn() throws IOException {
        String reset = bodyOf(source(MIRROR), "public void reset()", MIRROR);

        int attribute = reset.indexOf("restoreAttackSpeed");
        int offline = reset.indexOf("player.isOnline()");
        assertTrue(attribute >= 0, "EdgeStatusMirror.reset() no longer restores the attack speed");
        assertTrue(offline >= 0 && attribute < offline,
                "reset() checks isOnline() before restoring ATTACK_SPEED. The commonest teardown of"
                        + " all is a quit, and by the time PlayerQuitEvent runs the player is"
                        + " already offline - so an early return above the restore means the ONE"
                        + " path that leaks the attribute into the player's saved playerdata is"
                        + " precisely the path that skips the fix. The attribute lives on the"
                        + " Player object either way; only the cosmetic restores below need the"
                        + " player to still be connected.");
    }

    @Test
    void noRestoreInResetCanBeSkippedByAnEarlierOne() throws IOException {
        String mirror = source(MIRROR);
        String reset = bodyOf(mirror, "public void reset()", MIRROR);
        for (String bare : new String[] {
                "player.setAbsorptionAmount(0.0);", "player.setFireTicks(0);",
                "player.getActivePotionEffects()"}) {
            assertTrue(!reset.contains(bare),
                    "reset() still calls " + bare + " unguarded, so an earlier Bukkit call that"
                            + " throws takes the rest of the restores down with it");
        }
        assertTrue(reset.contains("restoreStep("),
                "reset() no longer routes its restores through the guard that keeps one failure"
                        + " from eating the rest");

        String attribute = bodyOf(mirror, "private void restoreAttackSpeed()", MIRROR);
        assertEquals(2, count(attribute, "restoreStep("),
                "restoreAttackSpeed() has two halves - putting back the base this match captured,"
                        + " and the sweep that catches a base some earlier build already corrupted."
                        + " Both must run: the sweep is the only thing that repairs a player whose"
                        + " playerdata already carries a shifted base, and it used to be skipped"
                        + " whenever the write above it threw.");
    }

    private static int count(String src, String needle) {
        int n = 0;
        for (int at = src.indexOf(needle); at >= 0; at = src.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }
}
