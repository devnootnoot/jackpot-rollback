package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.nootnoot.sim.contract.MatchRules;
import me.nootnoot.sim.state.GameState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class MatchRuleBlockTest {

    private static final List<String> ORDER = List.of(
            "vanillaBuild", "allowBucket", "allowExplosion", "explosionParticles",
            "totemParticles", "instaReady0", "instaReady1", "potSwordBoost");

    private static MatchRules of(int mask) {
        return new MatchRules((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0,
                (mask & 16) != 0, (mask & 32) != 0, (mask & 64) != 0, (mask & 128) != 0);
    }

    @Test
    void theBlockIsEightBytesAndEveryOneOfThemSurvivesARoundTrip() {
        for (int mask = 0; mask < 256; mask++) {
            MatchRules rules = of(mask);
            byte[] wire = rules.bytes();
            assertEquals(MatchRules.BYTES, wire.length,
                    "the rule block is a fixed width run of flags in the middle of the setup blob."
                            + " A byte more or less here does not fail: it silently reinterprets"
                            + " the breakable item id count that follows, and the match dies far"
                            + " away from the mistake. mask=" + mask);
            assertEquals(rules, MatchRules.read(ByteBuffer.wrap(wire)),
                    "read and write must be inverses for every combination, or a flag that is"
                            + " rarely off is the one that gets swapped. mask=" + mask);
        }
    }

    @Test
    void everyFlagSitsInItsOwnByteAndInTheOrderTheProtocolFixed() {
        for (int i = 0; i < MatchRules.BYTES; i++) {
            byte[] wire = of(1 << i).bytes();
            for (int b = 0; b < MatchRules.BYTES; b++) {
                assertEquals(b == i ? 1 : 0, wire[b],
                        "setting " + ORDER.get(i) + " alone must light byte " + i + " and no"
                                + " other. Two flags sharing a byte, or a pair swapped, is the"
                                + " failure mode that gave a dev POT duel crystal rules");
            }
        }
    }

    @Test
    void applyingTheBlockWritesExactlyTheSimulatedFlagsAndNoOthers() {
        GameState state = new GameState();
        MatchRules rules = new MatchRules(true, false, false, false, false, true, false, true);
        rules.applyTo(state);

        assertTrue(state.vanillaBuild);
        assertFalse(state.allowBucket);
        assertFalse(state.allowExplosion);
        assertTrue(state.potSwordBoost);
        assertTrue(state.players[0].instaReady);
        assertFalse(state.players[1].instaReady);

        long before = Checksum.of(state);
        new MatchRules(true, false, false, true, true, true, false, true).applyTo(state);
        assertEquals(before, Checksum.of(state),
                "explosionParticles and totemParticles are per-recipient CLIENT settings. They"
                        + " ride the rule block because they are addressed to the same client, but"
                        + " the two peers do not have to agree on them. A host that let either one"
                        + " reach simulated state would desync a player who turned particles off"
                        + " against one who left them on");
    }

    @Test
    void anOpenRuleBlockIsWhatAMatchGetsWhenNothingIsForbidden() {
        GameState fresh = new GameState();
        GameState opened = new GameState();
        MatchRules.OPEN.applyTo(opened);
        assertEquals(fresh.allowExplosion, opened.allowExplosion);
        assertEquals(fresh.allowBucket, opened.allowBucket);
        assertNotEquals(fresh.vanillaBuild, opened.vanillaBuild,
                "GameState defaults vanillaBuild to false while a match with nothing forbidden"
                        + " builds; if that stops being true the OPEN constant is lying");
    }

    private static Path rollbackRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))
                    && Files.isDirectory(p.resolve("sim-core"))) {
                return p;
            }
            p = p.getParent();
        }
        return Path.of("nowhere");
    }

    private static Path core(String rel) {
        Path jackpot = rollbackRoot().getParent();
        Path dev = jackpot == null ? null : jackpot.getParent();
        return dev == null ? Path.of("nowhere")
                : dev.resolve("mcleagues/mcleagues-core").resolve(rel);
    }

    private static final String CORE_CODEC =
            "src/main/java/me/nootnoot/modules/practice/managers/MatchSetupCodec.java";

    static boolean corePresent() {
        return Files.isRegularFile(core(CORE_CODEC));
    }

    @Test
    @EnabledIf("corePresent")
    void thePracticeServerWritesTheSameEightFlagsInTheSameOrder() throws IOException {
        String source = Files.readString(core(CORE_CODEC), StandardCharsets.UTF_8);
        int from = source.indexOf("b.putInt(rounds)");
        int to = source.indexOf("writeItemIdList(b, breakable)");
        assertTrue(from > 0 && to > from,
                "MatchSetupCodec no longer writes the rule block between the round count and the"
                        + " breakable item ids, so this scan is reading the wrong window and"
                        + " proves nothing");

        List<String> written = new ArrayList<>();
        Matcher m = Pattern.compile("b\\.put\\(\\(byte\\) \\((\\w+) \\? 1 : 0\\)\\);")
                .matcher(source.substring(from, to));
        while (m.find()) {
            written.add(m.group(1));
        }
        assertEquals(ORDER, written,
                "mcleagues-core cannot see sim-core, so MatchSetupCodec is the one writer of this"
                        + " block that cannot call MatchRules. That makes it the one copy that can"
                        + " drift, and a drift here is silent: the mod decodes the bytes in"
                        + " MatchRules order and every flag after the mistake means something"
                        + " else. Keep the two lists identical, or move MatchSetupCodec onto"
                        + " sim-core so it can share the record");
    }

    @Test
    @EnabledIf("corePresent")
    void theOnlyReadersAndWritersInThisBuildGoThroughTheRecord() throws IOException {
        Path root = rollbackRoot();
        List<String> handRolled = new ArrayList<>();
        for (String rel : new String[]{
            "sim-core/src/main/java/me/nootnoot/sim/MatchSetupFrame0Decoder.java",
            "edge/src/main/java/me/nootnoot/edge/EdgeDevKit.java"}) {
            String source = Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
            if (source.contains("boolean allowExplosion = b.get()")
                    || source.contains("flag(rules.vanillaBuild())")) {
                handRolled.add(rel);
            }
            if (!source.contains("MatchRules")) {
                handRolled.add(rel + " (no MatchRules at all)");
            }
        }
        assertTrue(handRolled.isEmpty(),
                handRolled + " spells the rule block out by hand again. Every reader and writer"
                        + " inside this build shares MatchRules so that adding a flag is one edit"
                        + " rather than four");
    }

    @Test
    void theRecordComponentsAreTheWireOrder() {
        List<String> declared = new ArrayList<>();
        for (var c : MatchRules.class.getRecordComponents()) {
            declared.add(c.getName());
        }
        assertEquals(ORDER, declared,
                "write() puts the components out in declaration order and read() takes them back"
                        + " in the same order, so reordering the record reorders the wire. If that"
                        + " is deliberate, every peer has to be redeployed together and this list"
                        + " updated with it");
        assertArrayEquals(new byte[MatchRules.BYTES],
                new MatchRules(false, false, false, false, false, false, false, false).bytes());
    }
}
