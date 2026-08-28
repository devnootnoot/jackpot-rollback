package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EdgeDevKitRulesTest {

    private static final int VANILLA_BUILD = 0;
    private static final int ALLOW_BUCKET = 1;
    private static final int ALLOW_EXPLOSION = 2;
    private static final int EXPLOSION_PARTICLES = 3;
    private static final int TOTEM_PARTICLES = 4;
    private static final int INSTA_READY_0 = 5;
    private static final int INSTA_READY_1 = 6;
    private static final int POT_SWORD_BOOST = 7;
    private static final int RULE_BYTES = 8;

    private static final byte ON = 1;
    private static final byte OFF = 0;

    @Test
    void theRuleBlockIsExactlyTheEightBytesTheModReadsAfterTheRoundCount() {
        for (String id : EdgeGameTypes.IDS) {
            assertEquals(RULE_BYTES, EdgeDevKit.ruleBytes(id).length,
                    id + " must write the same eight flag bytes MatchSetup.decode reads straight"
                            + " after the round count: vanillaBuild, allowBucket, allowExplosion,"
                            + " explosionParticles, totemParticles, instaReady0, instaReady1,"
                            + " potSwordBoost. A short block silently reinterprets every field"
                            + " after it");
        }
    }

    @Test
    void aCrystalDevMatchIsAllowedToDetonateAnchorsAndCrystals() {
        byte[] rules = EdgeDevKit.ruleBytes(EdgeGameTypes.CRYSTAL);

        assertEquals(ON, rules[ALLOW_EXPLOSION],
                "McInputSource gates BLOCK_PLACE_CRYSTAL, BLOCK_PLACE_ANCHOR, BLOCK_CHARGE_ANCHOR,"
                        + " BLOCK_DETONATE_ANCHOR and the left-click crystal hit on"
                        + " setup.allowExplosion. With this byte at 0 the dev kit hands the tester"
                        + " 256 end crystals and a stack of respawn anchors that the client will"
                        + " never turn into a single input, so no crystal or anchor bug is"
                        + " reachable with gradlew devAssign -PgameType=CRYSTAL");
        assertEquals(ON, rules[VANILLA_BUILD],
                "vanillaBuild is what lets a blast or a pickaxe touch arena terrain"
                        + " (Combat.explode and the mining path both test it). With it at 0 an"
                        + " anchor blast leaves the floor untouched, which reads exactly like the"
                        + " bug report 'the explosion does nothing to blocks'");
        assertEquals(ON, rules[ALLOW_BUCKET],
                "water and lava buckets are suppressed client-side when this is 0");
        assertEquals(ON, rules[EXPLOSION_PARTICLES],
                "McSimRenderer returns before the explosion sound and the EXPLOSION_EMITTER when"
                        + " this is 0, so a dev tester detonating an anchor sees and hears"
                        + " nothing at all");
        assertEquals(ON, rules[TOTEM_PARTICLES],
                "the CRYSTAL kit is totem-heavy and the pop is the feedback");
        assertEquals(OFF, rules[INSTA_READY_0], "the dev duel keeps its cage and countdown");
        assertEquals(OFF, rules[INSTA_READY_1], "the dev duel keeps its cage and countdown");
        assertEquals(OFF, rules[POT_SWORD_BOOST],
                "the +33% sword boost belongs to POT only, exactly as"
                        + " RollbackHandoffManager sets it");
    }

    @Test
    void thePotDevKitCarriesThePotSwordBoostAndNothingElse() {
        byte[] pot = EdgeDevKit.ruleBytes(EdgeGameTypes.POT);
        assertEquals(ON, pot[POT_SWORD_BOOST],
                "core sets potSwordBoost for GameType.POT, so a dev POT duel that leaves it at 0"
                        + " swings for less damage than the real thing and no sword-damage bug is"
                        + " reproducible there");
        assertEquals(OFF, pot[ALLOW_EXPLOSION], "a pot duel has nothing to detonate");
        assertEquals(OFF, pot[VANILLA_BUILD], "and nothing to build with");

        assertEquals(OFF, EdgeDevKit.ruleBytes(EdgeGameTypes.NETHERITE_POT)[POT_SWORD_BOOST],
                "NETHERITE_POT does not get the boost in core either");
    }

    @Test
    void everyDevKitThatShipsBlocksItMayNotPlaceSaysSoBeforeTheDuelStarts() {
        for (String id : EdgeGameTypes.IDS) {
            EdgeGameTypes.Rules rules = EdgeGameTypes.rules(id);
            String twin = EdgeGameTypes.kitAliasTwins().getOrDefault(id, id);
            assertEquals(EdgeGameTypes.rules(twin), rules,
                    id + " is a demo-kit bucket, not a game type. It must enforce exactly what"
                            + " mcleagues-core enforces for " + twin);
            if (!rules.vanillaBuild()) {
                List<String> banner = EdgeGameTypes.warningLines(id,
                        EdgeGameTypes.BUILD_MATERIALS, "a dev kit");
                for (String block : EdgeGameTypes.BUILD_MATERIALS) {
                    assertTrue(banner.stream().anyMatch(line -> line.contains(block)),
                            id + " stands for " + twin + ", which cannot place " + block
                                    + " in production. The dev stack used to grant building to"
                                    + " every non-pot bucket, so a local duel cratered an arena"
                                    + " production would not let it touch. Now that it does not,"
                                    + " the tester has to be told which items went inert: "
                                    + banner);
                }
            }
            assertTrue(rules.explosionParticles() && rules.totemParticles(),
                    id + " must render its own feedback in dev - these two are per-recipient"
                            + " client settings, not a game rule, and a tester who cannot see the"
                            + " blast cannot report on it");
            assertFalse(rules.instaReady(),
                    id + " keeps the countdown so round resets stay testable");
        }
    }

    @Test
    void theDevKitDescriptionNamesTheRulesItIsAboutToEnforce() {
        String described = EdgeGameTypes.rules(EdgeGameTypes.CRYSTAL).describe();
        assertTrue(described.contains("explosions=on"),
                "EdgePlugin logs this line when it fills the demo kit, and it is the only place a"
                        + " tester can see that anchors are live before starting the duel: "
                        + described);
    }
}
