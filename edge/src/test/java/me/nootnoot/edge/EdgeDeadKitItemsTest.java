package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EdgeDeadKitItemsTest {

    private static final Path DEMO_KITS =
            Path.of("src/main/java/me/nootnoot/edge/EdgeDemoKits.java");

    @Test
    void everyMaterialTheWarningNamesIsActuallyInTheKitItBlames() throws IOException {
        String source = Files.readString(DEMO_KITS, StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        for (String material : concat(EdgeGameTypes.EXPLOSION_MATERIALS,
                EdgeGameTypes.BUCKET_MATERIALS, EdgeGameTypes.BUILD_MATERIALS)) {
            if (!source.contains("Material." + material)) {
                missing.add(material);
            }
        }
        assertTrue(missing.isEmpty(), "EdgeGameTypes names " + missing + " as kit items a rule kills,"
                + " but EdgeDemoKits no longer puts them in any kit. The warning would be telling the"
                + " tester about items they do not have, which is exactly as useless as the silence"
                + " it replaced.");
    }

    @Test
    void theOnlyGameTypeThatLosesNothingIsCrystal() {
        for (String id : EdgeGameTypes.IDS) {
            List<EdgeGameTypes.DeadItems> dead = EdgeGameTypes.deadKitItems(id);
            if (EdgeGameTypes.CRYSTAL.equals(id)) {
                assertTrue(dead.isEmpty(), "CRYSTAL is the everything-on rule set");
                assertEquals("", EdgeGameTypes.kitLossHeadline(id));
            } else {
                assertFalse(dead.isEmpty(), id + " turns mechanics off, so it must say which kit"
                        + " items that kills");
                assertFalse(EdgeGameTypes.kitLossHeadline(id).isEmpty());
            }
        }
    }

    @Test
    void anAssignmentWithNoGameTypeStripsNothing() {
        assertTrue(EdgeGameTypes.deadKitItems("").isEmpty(),
                "an assignment that carries no gameType falls back to the everything-on rule set."
                        + " It used to fall back to one that killed crystals, anchors and buckets,"
                        + " which is the case the tester never saw coming");
        assertEquals(List.of("EXPLOSIONS", "BUCKETS", "BUILDING"), EdgeGameTypes.deadKitItems(
                        EdgeGameTypes.IRON).stream()
                        .map(EdgeGameTypes.DeadItems::mechanic).toList(),
                "IRON is the demo-kit bucket for core SWORD, BOW and AXE, and all three are"
                        + " allowBuilding=false. The edge used to grant building to this bucket"
                        + " anyway, so a local iron duel placed blocks that production refuses");
    }

    @Test
    void theWarningNamesEveryDisabledItemThatIsActuallyInTheKit() {
        List<String> lines = EdgeGameTypes.warningLines(EdgeGameTypes.IRON,
                List.of("DIAMOND_SWORD", "COBBLESTONE", "END_CRYSTAL", "WATER_BUCKET"),
                "a test kit");
        String banner = String.join(" | ", lines);
        assertTrue(banner.contains("END_CRYSTAL"), banner);
        assertTrue(banner.contains("WATER_BUCKET"), banner);
        assertFalse(banner.contains("RESPAWN_ANCHOR"),
                "the kit carries no anchor, so naming one would send the tester looking for an"
                        + " item they were never given");
    }

    @Test
    void aRuleSetThatDisablesNothingTheKitCarriesStaysQuiet() {
        assertTrue(EdgeGameTypes.warningLines(EdgeGameTypes.IRON,
                List.of("DIAMOND_SWORD", "GOLDEN_APPLE"), "a test kit").isEmpty());
        assertTrue(EdgeGameTypes.warningLines(EdgeGameTypes.CRYSTAL,
                List.of("END_CRYSTAL", "WATER_BUCKET", "OBSIDIAN"), "a test kit").isEmpty());
    }

    @Test
    void anIronKitCarryingCobblestoneIsToldTheCobblestoneIsDead() {
        String banner = String.join(" | ", EdgeGameTypes.warningLines(EdgeGameTypes.IRON,
                List.of("DIAMOND_SWORD", "COBBLESTONE", "GOLDEN_APPLE"), "a test kit"));
        assertTrue(banner.contains("COBBLESTONE"), "core SWORD, BOW and AXE are all"
                + " allowBuilding=false, so the cobblestone in an iron kit places nothing. This"
                + " banner was silent while the dev stack granted building to the whole bucket: "
                + banner);
    }

    @Test
    void everyRuleThatIsOffNamesItsItemsAndItsConsequence() {
        for (String id : EdgeGameTypes.IDS) {
            for (EdgeGameTypes.DeadItems dead : EdgeGameTypes.deadKitItems(id)) {
                assertFalse(dead.kitItems().isBlank(), id + "/" + dead.mechanic()
                        + " must list the items, not just the mechanic name");
                assertFalse(dead.effect().isBlank(), id + "/" + dead.mechanic()
                        + " must say what happens instead");
                assertFalse(dead.materials().isEmpty());
            }
        }
    }

    private static List<String> concat(List<String> first, List<String> second, List<String> third) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        all.addAll(third);
        return all;
    }
}
