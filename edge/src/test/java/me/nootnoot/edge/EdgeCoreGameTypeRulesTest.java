package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EdgeCoreGameTypeRulesTest {

    @Test
    void everyNameTheEdgeAcceptsEitherCarriesProductionRulesOrBorrowsThem() {
        Map<String, EdgeGameTypes.Rules> table = EdgeGameTypes.coreRuleTable();
        Map<String, String> twins = EdgeGameTypes.kitAliasTwins();

        for (String name : EdgeGameTypes.knownNames()) {
            assertTrue(table.containsKey(name) || twins.containsKey(name),
                    name + " is a name this edge accepts in config.yml and on an assignment, but"
                            + " it has no row in the production rule table and borrows nobody"
                            + " else's. It would silently get the unknown fallback while"
                            + " production enforces whatever GameType says, which is the whole"
                            + " defect gradlew verifyGameTypeRules exists to catch");
        }
        for (String name : table.keySet()) {
            assertTrue(EdgeGameTypes.isKnown(name),
                    name + " carries production rules but is not a name this edge recognises, so"
                            + " normalize() sends it to the fallback demo kit and warningLines()"
                            + " opens with UNKNOWN GAME TYPE for a type production defines");
        }
        for (Map.Entry<String, String> alias : twins.entrySet()) {
            assertTrue(table.containsKey(alias.getValue()),
                    alias.getKey() + " borrows from " + alias.getValue()
                            + ", which is not a production game type");
            assertSame(table.get(alias.getValue()), EdgeGameTypes.rules(alias.getKey()),
                    alias.getKey() + " is a demo-kit bucket, not a game type. It must enforce"
                            + " exactly what " + alias.getValue() + " enforces in production");
        }
    }

    @Test
    void theRulesAreTheFourFlagsRollbackHandoffManagerPutsOnTheWire() {
        for (Map.Entry<String, EdgeGameTypes.Rules> row : EdgeGameTypes.coreRuleTable().entrySet()) {
            EdgeGameTypes.Rules rules = row.getValue();
            assertSame(rules, EdgeGameTypes.rules(row.getKey()),
                    row.getKey() + " must resolve to its own row, not to a bucket");
            if (rules.vanillaBuild()) {
                assertTrue(rules.allowBucket(), row.getKey() + " breaks arena terrain but cannot"
                        + " place a block. Core builds allowBucket out of"
                        + " GameType.isAllowBuilding(), which the constructor already ORs with"
                        + " allowAllBuildingBreaking, so this combination cannot exist in"
                        + " production and must not exist here");
            }
            assertEquals(EdgeGameTypes.POT.equals(row.getKey()), rules.potSwordBoost(),
                    row.getKey() + ": core sets potSwordBoost for GameType.POT and nothing else");
        }
    }

    @Test
    void theGameTypesThatUsedToBeWrongLocallyNowEnforceWhatProductionEnforces() {
        EdgeGameTypes.Rules sword = EdgeGameTypes.rules("SWORD");
        assertFalse(sword.vanillaBuild(), "core SWORD is allowAllBuildingBreaking=false. The edge"
                + " used to bucket SWORD into IRON and hand it vanillaBuild=true, so a local"
                + " SWORD duel could crater the arena and production could not");
        assertFalse(sword.allowBucket());
        assertFalse(sword.allowExplosion());

        EdgeGameTypes.Rules uhc = EdgeGameTypes.rules("UHC");
        assertTrue(uhc.allowBucket(), "core UHC is allowBuilding=true, so the MLG water bucket is"
                + " live. The edge bucketed UHC into DIAMOND and turned buckets off");
        assertFalse(uhc.vanillaBuild(), "core UHC is allowAllBuildingBreaking=false");

        EdgeGameTypes.Rules moneySmp = EdgeGameTypes.rules("MONEY_SMP");
        assertTrue(moneySmp.vanillaBuild());
        assertFalse(moneySmp.allowExplosion(), "core MONEY_SMP is the one all-building type with"
                + " explosions off");

        EdgeGameTypes.Rules mace = EdgeGameTypes.rules("MACE");
        assertTrue(mace.allowExplosion(), "core MACE allows explosions");
        assertFalse(mace.vanillaBuild(), "but not terrain breaking. The edge bucketed MACE into"
                + " CRYSTAL, which allows both");
    }

    @Test
    void anUnknownNameStillFallsBackToTheOnlyRuleSetWithEverythingOn() {
        EdgeGameTypes.Rules fallback = EdgeGameTypes.rules("POTATO_DUELS");
        assertTrue(fallback.vanillaBuild() && fallback.allowBucket() && fallback.allowExplosion(),
                "an unknown name must not quietly strip a mechanic - the tester would read that"
                        + " as a broken crystal rather than a missing mapping");
        assertSame(EdgeGameTypes.rules(EdgeGameTypes.UNKNOWN_FALLBACK), fallback);
    }

    @Test
    void canonicalKeepsTheNameSoTheRulesSurviveTheTripThroughAnAssignment() {
        assertEquals("UHC", EdgeGameTypes.canonical("uhc"),
                "normalize collapses UHC to the DIAMOND demo-kit bucket. If config.yml or"
                        + " devAssign stored the bucket, the exact rules for UHC would be lost"
                        + " before the match ever started");
        assertEquals("SWORD", EdgeGameTypes.canonical("SWORD"));
        assertEquals(EdgeGameTypes.UNKNOWN_FALLBACK, EdgeGameTypes.canonical("POTATO_DUELS"));
        assertEquals(EdgeGameTypes.DIAMOND, EdgeGameTypes.normalize("UHC"),
                "the demo KIT is still picked by bucket - that part was never the defect");
    }
}
