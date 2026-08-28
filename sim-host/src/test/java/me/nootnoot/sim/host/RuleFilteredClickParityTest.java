package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class RuleFilteredClickParityTest {

    private static final String EDGE =
            "jackpot-rollback/edge/src/main/java/me/nootnoot/edge/EdgeInputSource.java";

    private static final String MOD =
            "pvphq-rollback-mod/src/main/java/me/nootnoot/rollback/client/McInputSource.java";

    private static final int[] FORBIDDEN_WITHOUT_EXPLOSIONS = {
        Input.BLOCK_PLACE_CRYSTAL,
        Input.BLOCK_HIT_CRYSTAL,
        Input.BLOCK_PLACE_ANCHOR,
        Input.BLOCK_CHARGE_ANCHOR,
        Input.BLOCK_DETONATE_ANCHOR,
    };

    private static final int[] FORBIDDEN_WITHOUT_BUCKETS = {
        Input.BLOCK_PLACE_WATER,
        Input.BLOCK_PLACE_LAVA,
        Input.BLOCK_PICKUP_FLUID,
    };

    private static GameState noExplosions() {
        GameState s = new GameState();
        s.allowExplosion = false;
        return s;
    }

    private static GameState noBuckets() {
        GameState s = new GameState();
        s.allowBucket = false;
        return s;
    }

    private static int moddedUseClicks(GameState s, int intended, int presses) {
        int counted = InputFrameRules.ruledBlockAction(s, intended);
        return InputFrameRules.useClicks(counted, presses);
    }

    private static int moddedUseClicksAsShipped(GameState s, int intended, int presses) {
        int emitted = Combat.ruleForbids(s, intended) ? Input.BLOCK_NONE : intended;
        return InputFrameRules.useClicks(emitted, presses);
    }

    private static int edgeUseClicks(GameState s, int intended, int presses) {
        int counted = InputFrameRules.ruledBlockAction(s, intended);
        int drained = Math.min(presses - 1, InputFrameRules.drainableUseRepeats(counted));
        return InputFrameRules.useClicks(counted, 1 + drained);
    }

    private static int edgeUseClicksAsShipped(GameState s, int intended, int presses) {
        int drained = Math.min(presses - 1, InputFrameRules.drainableUseRepeats(intended));
        return InputFrameRules.useClicks(intended, 1 + drained);
    }

    @Test
    void theTwoHostsMintTheSameUseClicksForAnActionTheRulesForbid() {
        for (GameState s : List.of(noExplosions(), noBuckets())) {
            for (int action : s.allowExplosion ? FORBIDDEN_WITHOUT_BUCKETS
                    : FORBIDDEN_WITHOUT_EXPLOSIONS) {
                for (int presses = 1; presses <= Clicks.MAX; presses++) {
                    assertEquals(moddedUseClicks(s, action, presses),
                            edgeUseClicks(s, action, presses),
                            "neither host refuses a forbidden action in its own input source any"
                                    + " more: both emit the intent and both count off"
                                    + " InputFrameRules.ruledBlockAction, which is the sim's own"
                                    + " Combat.ruleForbids. If the two count differently, the same"
                                    + " player input spends a different ClickBudget on each side"
                                    + " and the next action on that tick resolves differently."
                                    + " action=" + action + " presses=" + presses);
                }
            }
        }
    }

    @Test
    void countingOffTheUnfilteredActionIsWhatUsedToDiverge() {
        GameState s = noExplosions();
        int presses = 4;

        assertEquals(1, edgeUseClicksAsShipped(s, Input.BLOCK_PLACE_CRYSTAL, presses),
                "before the ruled action was introduced the edge treated a forbidden crystal"
                        + " place as a single-use frame: one click counted, the other three left"
                        + " queued a tick each");
        assertEquals(presses, moddedUseClicksAsShipped(s, Input.BLOCK_PLACE_CRYSTAL, presses),
                "while the mod, which used to refuse the placement in its own else-if chain,"
                        + " emitted BLOCK_NONE and counted all four as plain uses");
        assertNotEquals(edgeUseClicksAsShipped(s, Input.BLOCK_PLACE_CRYSTAL, presses),
                moddedUseClicksAsShipped(s, Input.BLOCK_PLACE_CRYSTAL, presses));
        assertEquals(edgeUseClicks(s, Input.BLOCK_PLACE_CRYSTAL, presses),
                moddedUseClicks(s, Input.BLOCK_PLACE_CRYSTAL, presses),
                "counting off the ruled action on BOTH hosts closes it");
        assertEquals(presses, moddedUseClicks(s, Input.BLOCK_PLACE_CRYSTAL, presses),
                "and the count both hosts now agree on is the one the sim's own filter implies:"
                        + " the frame the sim keeps is BLOCK_NONE, which is not a single-use"
                        + " frame, so all four presses are ordinary uses");
    }

    @Test
    void nothingChangesForAnActionTheRulesAllow() {
        GameState open = new GameState();
        for (int action = Input.BLOCK_NONE; action <= Input.BLOCK_CLOSE_CONTAINER; action++) {
            assertEquals(action, InputFrameRules.ruledBlockAction(open, action),
                    "a match that forbids nothing must hand the counting rules the very action"
                            + " they were always given, or every duel shipping today changes its"
                            + " click accounting for no reason");
            for (int presses = 1; presses <= Clicks.MAX; presses++) {
                assertEquals(edgeUseClicksAsShipped(open, action, presses),
                        edgeUseClicks(open, action, presses));
            }
        }
    }

    @Test
    void aNullHeadStillCounts() {
        assertEquals(Input.BLOCK_PLACE_CRYSTAL,
                InputFrameRules.ruledBlockAction(null, Input.BLOCK_PLACE_CRYSTAL),
                "the edge samples input before the driver has a head state on the first frames of"
                        + " a match; a null head must not throw and must not silently zero the"
                        + " action");
    }

    private static Path repo(String rel) {
        Path here = Path.of("").toAbsolutePath();
        Path jackpot = here.getParent() == null ? null : here.getParent().getParent();
        return jackpot == null ? Path.of("nowhere") : jackpot.resolve(rel);
    }

    static boolean sourcesPresent() {
        return Files.exists(repo(EDGE)) && Files.exists(repo(MOD));
    }

    private static final String[] COUNTING_CALLS = {
        "InputFrameRules.attackClicks(",
        "InputFrameRules.useClicks(",
        "InputFrameRules.drainableUseRepeats(",
    };

    @Test
    @EnabledIf("sourcesPresent")
    void theEdgeNeverCountsClicksOffTheUnfilteredBlockAction() throws IOException {
        String source = Files.readString(repo(EDGE));
        List<String> raw = new ArrayList<>();
        for (String call : COUNTING_CALLS) {
            if (source.contains(call + "action.type")) {
                raw.add(call + "action.type");
            }
        }
        assertTrue(raw.isEmpty(),
                "EdgeInputSource still counts clicks straight off the raw block intent at " + raw
                        + ". action.type is what the player aimed at; the sim then throws it away"
                        + " when the game type forbids it, and the modded host never emitted it at"
                        + " all. Count off InputFrameRules.ruledBlockAction(head, action.type)");
        assertTrue(source.contains("InputFrameRules.ruledBlockAction("),
                "and the ruled action has to actually be computed, or the check above passes"
                        + " because the counting moved somewhere this scan cannot see");
    }

    @Test
    @EnabledIf("sourcesPresent")
    void theModdedHostKeepsNoCopyOfTheExplosionAndBucketRules() throws IOException {
        String source = Files.readString(repo(MOD));
        assertFalse(source.contains("allowExplosion") || source.contains("allowBucket"),
                "McInputSource is gating its own crystal / anchor / bucket branches again. Those"
                        + " two flags are GameType rules and the sim already enforces them in"
                        + " Combat.ruleFiltered, which BOTH hosts run. A second enforcement point"
                        + " in one host only is how the two came to spend different ClickBudgets"
                        + " on the same press: emit the intent and let the sim filter it");
        assertTrue(source.contains(
                        "InputFrameRules.ruledBlockAction(confirmedState(), blockAction)"),
                "and the modded host has to count its clicks off the RULED action, exactly as the"
                        + " edge does, or the two disagree the moment a rule refuses something");
        assertTrue(source.contains("InputFrameRules.useClicks(counted, usePresses)"),
                "the modded host counts uses off the ruled action; if that line moved, the model"
                        + " in this file no longer describes the shipped client");
    }

    @Test
    @EnabledIf("sourcesPresent")
    void neitherHostCountsClicksOffTheRawIntent() throws IOException {
        for (String rel : new String[]{EDGE, MOD}) {
            String source = Files.readString(repo(rel));
            List<String> raw = new ArrayList<>();
            for (String call : COUNTING_CALLS) {
                if (source.contains(call + "action.type")
                        || source.contains(call + "blockAction")) {
                    raw.add(rel + " " + call);
                }
            }
            assertTrue(raw.isEmpty(), "a host counts clicks straight off the raw block intent at "
                    + raw + ". The raw intent is what the player aimed at; the sim throws it away"
                    + " when the game type forbids it, so both hosts must count off"
                    + " InputFrameRules.ruledBlockAction(...)");
        }
    }

    @Test
    @EnabledIf("sourcesPresent")
    void neitherHostCarriesItsOwnCopyOfTheBlockWhitelist() throws IOException {
        for (String rel : new String[]{EDGE, MOD}) {
            String source = Files.readString(repo(rel));
            assertTrue(!source.contains("breakableIds") && !source.contains("placeableIds"),
                    rel + " still keeps its own block whitelist and gates its own input with it."
                            + " One host filtering its intent while the other emits it is exactly"
                            + " how the two ended up minting different click counts for the same"
                            + " press. The whitelist belongs to the sim (Combat.breakAllowed and"
                            + " Combat.placeAllowed), which both hosts run");
        }
    }
}
