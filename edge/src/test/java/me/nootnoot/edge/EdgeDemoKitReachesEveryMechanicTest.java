package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.ItemDict;
import org.junit.jupiter.api.Test;

class EdgeDemoKitReachesEveryMechanicTest {

    private static final Path DEMO_KITS =
            Path.of("src/main/java/me/nootnoot/edge/EdgeDemoKits.java");

    private static final Map<String, String> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put("Material.NETHERITE_AXE",
                "an axe is the only thing that disables a shield (Combat.SHIELD_DISABLE_TICKS)."
                        + " Without one in a kit the five-second disable cannot be played at all,"
                        + " and it is the cross-play row most worth running because the modded and"
                        + " the unmodded host have to agree on the same five seconds");
        REQUIRED.put("Material.WIND_CHARGE",
                "a thrown wind charge is the only route to Combat.USE_WIND_CHARGE and to the"
                        + " windBurst explosion. The vanilla parity pass rewrote that explosion and"
                        + " nobody has ever felt it");
        REQUIRED.put("Enchantment.MULTISHOT",
                "Loadout.multishot decides whether one crossbow shot becomes three arrows. A kit"
                        + " with only a plain crossbow leaves that branch unplayable");
        REQUIRED.put("Enchantment.WIND_BURST",
                "Loadout.windBurst is the mace half of the wind mechanic and fires on a smash."
                        + " Combat.windBurstKnockback has a three-level table that has never been"
                        + " felt");
        REQUIRED.put("Enchantment.BREACH",
                "reduceByDefenseBreach was rewritten to subtract from the armour FRACTION rather"
                        + " than to scale armour POINTS. Only a Breach weapon reaches it");
        REQUIRED.put("Material.ENCHANTED_GOLDEN_APPLE",
                "EdgeKitTables.effectsOf has a notch branch that packs four effects instead of two."
                        + " The plain apple never reaches it");
        REQUIRED.put("PotionType.STRONG_HARMING",
                "splash instant damage routes through i-frames and protection, which is a rewritten"
                        + " path with no other item in any kit that reaches it");
        REQUIRED.put("PotionType.STRONG_SWIFTNESS",
                "Effects.SPEED is applied to movement in Simulation and no kit reached it otherwise");
        REQUIRED.put("PotionType.STRONG_STRENGTH",
                "Effects.STRENGTH is applied to melee damage in Combat and no kit reached it"
                        + " otherwise");
        REQUIRED.put("PotionType.LONG_FIRE_RESISTANCE",
                "Effects.FIRE_RESISTANCE gates the lava and in-fire damage path, which the vanilla"
                        + " parity pass also rewrote");
    }

    @Test
    void everyMechanicTheSimModelsHasAnItemInSomeDemoKit() throws IOException {
        String source = Files.readString(DEMO_KITS, StandardCharsets.UTF_8);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> required : REQUIRED.entrySet()) {
            if (!source.contains(required.getKey())) {
                missing.add(required.getKey() + " - " + required.getValue());
            }
        }
        assertTrue(missing.isEmpty(), "EdgeDemoKits no longer carries: " + missing
                + ". devAssign cannot serialize a real per-player inventory, so a demo kit is the"
                + " ONLY way a mechanic gets played on the dev stack. Dropping one of these does not"
                + " fail any other test - it silently returns the mechanic to the TESTPLAN deferred"
                + " list, which is where all of them sat until 2026-08-26.");
    }

    @Test
    void theSimConstantsThoseItemsFeedAreStillThere() {
        assertEquals(100, Combat.SHIELD_DISABLE_TICKS,
                "the axe row in TESTPLAN quotes five seconds off this constant");
        assertEquals(11, Combat.USE_WIND_CHARGE);
        assertEquals(1, ItemDict.MAX_MULTISHOT,
                "the kit enchants at level 1; a cap below that would clamp it away");
        assertEquals(3, ItemDict.MAX_WIND_BURST,
                "the kit enchants the mace at Wind Burst III");
        assertEquals(4, ItemDict.MAX_BREACH,
                "the kit enchants the same mace at Breach IV");
        assertTrue(Effects.INSTANT_DAMAGE > Effects.NONE);
        assertTrue(Effects.FIRE_RESISTANCE < Effects.COUNT);
    }

    @Test
    void piercingStaysOutOfTheKitsWhileNothingReadsIt() throws IOException {
        String source = Files.readString(DEMO_KITS, StandardCharsets.UTF_8);
        assertFalse(source.contains("Enchantment.PIERCING"),
                "ItemDict.piercing is clamped and replicated over the wire and read by NOTHING -"
                        + " there is no pierce counter on ProjectileState and no bypass in"
                        + " Combat.blocksProjectile. A Piercing crossbow in a demo kit would behave"
                        + " exactly like a plain one, which a tester reports as a bug rather than as"
                        + " an unimplemented feature. Put it in the kit in the same change that"
                        + " implements it, not before.");
    }
}
