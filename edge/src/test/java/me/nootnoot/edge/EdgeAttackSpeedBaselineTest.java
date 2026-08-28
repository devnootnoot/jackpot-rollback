package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EdgeAttackSpeedBaselineTest {

    private static final Path MAIN = Path.of("src/main/java/me/nootnoot/edge");

    private static final double VANILLA_BASE = 4.0;
    private static final double SWORD_MODIFIER = -2.4;
    private static final double SIM_SPEED = 1.6;

    @Test
    void oneCorrectionLandsTheIndicatorOnTheSimSpeedWhateverTheHeldItemAdds() {
        double live = VANILLA_BASE + SWORD_MODIFIER;
        double corrected = EdgeStatusMirror.correctedBase(VANILLA_BASE, live, SIM_SPEED);

        assertEquals(SIM_SPEED, corrected + SWORD_MODIFIER, 1.0E-9,
                "the shift has to be solved against getValue(), which carries the held item's own"
                        + " attribute modifier, and applied to getBaseValue(), which does not");
        assertEquals(VANILLA_BASE, corrected - (SIM_SPEED - live), 1.0E-9,
                "the shift is exactly want - live, so the base the mirror found before its first"
                        + " write is recoverable. That value, and nothing later, is the baseline"
                        + " the player gets back when the match ends");
    }

    @Test
    void onlyTheStatusMirrorEverWritesTheAttackSpeedAttribute() throws IOException {
        List<String> writers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(file, StandardCharsets.UTF_8);
                if (src.contains("Attribute.ATTACK_SPEED") && src.contains("setBaseValue")) {
                    writers.add(file.getFileName().toString());
                }
            }
        }

        assertEquals(List.of("EdgeAttackSpeed.java", "EdgeStatusMirror.java"), writers,
                "a second writer shifts ATTACK_SPEED before the mirror has ever looked at the"
                        + " attribute, so the mirror captures that shifted value as the player's"
                        + " own baseline and hands it back at the end of the match. The correction"
                        + " then outlives the duel and follows the player into every other mode on"
                        + " the server. One owner shifts this attribute, and it is the one that"
                        + " restores it. EdgeAttackSpeed is the only other file allowed to touch"
                        + " it, and the next test pins it to writing the vanilla default and"
                        + " nothing else.");
    }

    @Test
    void theRepairPathOnlyEverWritesTheVanillaDefault() throws IOException {
        String src = Files.readString(MAIN.resolve("EdgeAttackSpeed.java"), StandardCharsets.UTF_8);
        List<String> written = new ArrayList<>();
        int at = src.indexOf("setBaseValue(");
        while (at >= 0) {
            int open = at + "setBaseValue(".length();
            written.add(src.substring(open, src.indexOf(')', open)).trim());
            at = src.indexOf("setBaseValue(", open);
        }
        assertEquals(List.of("vanilla"), written,
                "the remediation path exists to put an account back on the vanilla default. The"
                        + " moment it writes anything else it becomes a second shifting writer,"
                        + " which is the bug it was added to clean up after: " + written);
        assertTrue(src.contains("getModifiers()"),
                "the repair has to report how many AttributeModifiers it is leaving in place -"
                        + " modifiers are how every other feature is allowed to change attack"
                        + " speed, and the repair must never remove one");
        assertTrue(src.indexOf("removeModifier") < 0 && src.indexOf("addModifier") < 0,
                "the repair touches the base value only, so a legitimately modified attribute"
                        + " from another feature survives it untouched");
    }

    @Test
    void aPlayerWhoQuitsMidMatchStillGetsTheAttributeBack() throws IOException {
        String src = Files.readString(MAIN.resolve("EdgeStatusMirror.java"), StandardCharsets.UTF_8);
        int reset = src.indexOf("public void reset()");
        assertTrue(reset > 0, "reset() is gone, so nothing restores the attribute at all");

        int restore = src.indexOf("restoreAttackSpeed()", reset);
        int gate = src.indexOf("player.isOnline()", reset);
        assertTrue(restore > 0, "reset() no longer restores the attack speed");
        assertTrue(gate < 0 || restore < gate,
                "reset() gives up on an offline player before restoring ATTACK_SPEED. The common"
                        + " way out of a live duel is quitting, the quit event is where the match"
                        + " is torn down, and the attribute is saved with the player after it - so"
                        + " skipping the restore there is exactly how the shift becomes permanent");
    }
}
