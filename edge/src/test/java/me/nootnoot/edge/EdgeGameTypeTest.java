package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.nootnoot.edge.tools.DevAssignMain;
import org.junit.jupiter.api.Test;

class EdgeGameTypeTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static EdgeAssignment devAssignment(String gameType) {
        return EdgeAssignment.parse(DevAssignMain.assignment(1234L, 0, A, "playerA", B, "playerB",
                "127.0.0.1", 7777, -60.0, 1, System.currentTimeMillis() + 60_000L, true, gameType));
    }

    @Test
    void coreGameTypeNamesMapOntoADemoKit() {
        assertEquals(EdgeGameTypes.NETHERITE_POT, EdgeGameTypes.normalize("NETHERITE_POT"));
        assertEquals(EdgeGameTypes.NETHERITE_POT, EdgeGameTypes.normalize("netherite_pot"));
        assertEquals(EdgeGameTypes.POT, EdgeGameTypes.normalize("POT"));
        assertEquals(EdgeGameTypes.POT, EdgeGameTypes.normalize("pot"));
        assertEquals(EdgeGameTypes.DIAMOND, EdgeGameTypes.normalize("DIAMOND"));
        assertEquals(EdgeGameTypes.IRON, EdgeGameTypes.normalize("SWORD"));
    }

    @Test
    void theCoreVanillaNamesAreCrystalPvpAndMustKeepTheirCrystals() {
        assertEquals(EdgeGameTypes.CRYSTAL, EdgeGameTypes.normalize("VANILLA"),
                "mcleagues-core GameType.VANILLA is crystal pvp: END_CRYSTAL icon, allowExplosion,"
                        + " allowBuilding and TOTEMS healing. Mapping it onto a rule set that turns"
                        + " explosions and buckets off hands the tester a kit with no crystals, no"
                        + " anchors and no buckets, which makes every fixed crystal bug look"
                        + " unfixed");
        assertEquals(EdgeGameTypes.CRYSTAL, EdgeGameTypes.normalize("HT_VANILLA"));
        assertEquals(EdgeGameTypes.CRYSTAL, EdgeGameTypes.normalize("ht_vanilla"));
        assertEquals(EdgeGameTypes.CRYSTAL, EdgeGameTypes.normalize("LT_CART"));
        assertEquals(EdgeGameTypes.CRYSTAL, EdgeGameTypes.normalize("HT_CART"));
        assertEquals(EdgeGameTypes.CRYSTAL, EdgeGameTypes.normalize("CREEPER"));
    }

    @Test
    void aNameNoRuleSetClaimsFallsBackToTheEverythingOnRuleSet() {
        for (String unknown : new String[]{"SOMETHING_NEW", "", "   ", null}) {
            assertFalse(EdgeGameTypes.isKnown(unknown));
            assertEquals(EdgeGameTypes.UNKNOWN_FALLBACK, EdgeGameTypes.normalize(unknown),
                    "an unmapped name must not silently pick a rule set that strips items:"
                            + " an over-full kit costs nothing, an under-full one is the trap");
            assertTrue(EdgeGameTypes.deadKitItems(unknown).isEmpty());
            assertFalse(EdgeGameTypes.warningLines(unknown, null, "test").isEmpty(),
                    "the fallback still has to say out loud that the name was not recognised");
        }
    }

    @Test
    void noSubstringOfAKnownNameIsTreatedAsThatName() {
        assertEquals(EdgeGameTypes.UNKNOWN_FALLBACK, EdgeGameTypes.normalize("POTATO_DUELS"),
                "substring matching is what mapped every name containing POT onto the pot kit");
        assertEquals(EdgeGameTypes.UNKNOWN_FALLBACK, EdgeGameTypes.normalize("DIAMOND_LEGACY"));
    }

    @Test
    void everyKnownTypeHasADemoKitSummary() {
        for (String id : EdgeGameTypes.IDS) {
            assertFalse(EdgeGameTypes.summary(id).isBlank(), id + " must describe its demo kit");
        }
    }

    @Test
    void potTypesAskForThePotSuffixAndVanillaForTotems() {
        assertTrue(EdgeGameTypes.pots(EdgeGameTypes.POT));
        assertTrue(EdgeGameTypes.pots(EdgeGameTypes.NETHERITE_POT));
        assertFalse(EdgeGameTypes.pots(EdgeGameTypes.IRON));
        assertTrue(EdgeGameTypes.totems("VANILLA"));
        assertTrue(EdgeGameTypes.totems(EdgeGameTypes.CRYSTAL));
        assertFalse(EdgeGameTypes.totems(EdgeGameTypes.IRON));
        assertFalse(EdgeGameTypes.totems(EdgeGameTypes.POT));
    }

    @Test
    void devAssignCarriesTheGameTypeToBothEdges() {
        EdgeAssignment a = devAssignment("NETHERITE_POT");
        assertNotNull(a);
        assertEquals(EdgeGameTypes.NETHERITE_POT, a.gameType(),
                "the edge builds its demo kit from this string, so both edges must be told the same");
        assertTrue(a.pots());
        assertFalse(a.totems());
        assertTrue(a.devKit());
        assertFalse(a.hasSetup(),
                "devAssign still has no server to serialize inventories with");
    }

    @Test
    void devAssignDefaultsToTheEverythingOnDemoKit() {
        EdgeAssignment a = devAssignment(null);
        assertNotNull(a);
        assertEquals(EdgeGameTypes.CRYSTAL, a.gameType());
        assertTrue(a.totems());
        assertFalse(a.pots());
    }

    @Test
    void devAssignCarriesNoArenaBytes() {
        EdgeAssignment a = devAssignment("POT");
        assertNotNull(a);
        assertFalse(a.hasArenaBlob(),
                "devAssign has no arena to ship, so both edges must fall back to their local file");
    }
}
