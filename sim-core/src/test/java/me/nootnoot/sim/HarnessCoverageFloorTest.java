package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import me.nootnoot.sim.harness.HarnessCounts;
import me.nootnoot.sim.harness.HarnessDigest;
import org.junit.jupiter.api.Test;

class HarnessCoverageFloorTest {
    private record Floor(String name, long atLeast) {
    }

    private static Floor floor(String name, long atLeast) {
        return new Floor(name, atLeast);
    }

    private static final List<Floor> FLOORS = List.of(
            floor("melee-claim-attempt", 1685L),
            floor("melee-claim-granted", 557L),
            floor("melee-claim-granted-live-hull", 464L),
            floor("melee-claim-granted-rewound-hull", 73L),
            floor("melee-claim-granted-outside-live-reach", 2L),
            floor("melee-claim-refused", 1128L),
            floor("melee-claim-refused-out-of-reach", 703L),
            floor("melee-claim-refused-occluded", 424L),
            floor("melee-candidate-hull-tested", 21265L),
            floor("melee-candidate-hull-in-reach", 12217L),
            floor("sight-test", 13371L),
            floor("sight-blocked-by-arena", 10602L),
            floor("cobweb-sight-test", 558L),
            floor("arrow-claim-attempt", 3L),
            floor("arrow-claim-self-target", 2L),
            floor("arrow-claim-peer-target", 4L),
            floor("arrow-claim-granted", 3L),
            floor("arrow-claim-refused", 2L),
            floor("explode", 7L),
            floor("blast-cell-removed", 5L),
            floor("crystal-placed", 7L),
            floor("crystal-detonated-by-hit", 6L),
            floor("crystal-destroyed-by-blast", 1L),
            floor("anchor-placed", 3L),
            floor("anchor-charged", 8L),
            floor("anchor-detonated", 1L),
            floor("placed-block-added", 24L),
            floor("placed-block-removed", 7L),
            floor("arena-voxel-broken-by-mining", 2L),
            floor("arena-voxel-broken-by-blast", 5L),
            floor("arena-voxel-broken-by-decor-place", 1L),
            floor("cobweb-added", 2L),
            floor("cobweb-removed", 2L),
            floor("container-opened", 3L),
            floor("container-closed", 4L),
            floor("item-entity-spawned", 169L),
            floor("durability-damaged", 563L),
            floor("item-broken", 2L),
            floor("round-reset", 12L),
            floor("click-attack-counted-drain", 7822L),
            floor("click-attack-edge-drain", 2382L),
            floor("click-use-counted-drain", 1560L),
            floor("click-use-uncounted-drain", 270L),
            floor("click-drop-counted-drain", 157L),
            floor("click-inv-counted-drain", 527L),
            floor("click-swap-counted-drain", 164L),
            floor("miss-penalty-armed", 1274L),
            floor("attack-refused-by-miss-penalty", 3614L),
            floor("mining-refused-by-miss-penalty", 67L),
            floor("projectile-spawn-arrow", 5L),
            floor("projectile-spawn-pearl", 54L),
            floor("projectile-spawn-snowball", 183L),
            floor("projectile-spawn-egg", 4L),
            floor("projectile-spawn-splash-potion", 184L),
            floor("projectile-spawn-xp-bottle", 192L),
            floor("projectile-spawn-wind-charge", 88L),
            floor("block-action-none", 7262L),
            floor("block-action-place", 228L),
            floor("block-action-break", 375L),
            floor("block-action-place-crystal", 236L),
            floor("block-action-hit-crystal", 16L),
            floor("block-action-place-anchor", 34L),
            floor("block-action-charge-anchor", 44L),
            floor("block-action-detonate-anchor", 6L),
            floor("block-action-place-water", 7L),
            floor("block-action-place-lava", 8L),
            floor("block-action-pickup-fluid", 26L),
            floor("block-action-place-offhand", 16L),
            floor("block-action-open-container", 6L),
            floor("block-action-close-container", 4L),
            floor("inv-action-move", 440L),
            floor("inv-action-container-take", 1L),
            floor("inv-action-container-put", 1L),
            floor("inv-action-pickup", 4L),
            floor("inv-action-pickup-half", 2L),
            floor("inv-action-swap-slot", 1L),
            floor("inv-action-pickup-all", 1L),
            floor("inv-action-quick-move", 1L),
            floor("use-main-dispatch-none", 6222L),
            floor("use-main-dispatch-pearl", 182L),
            floor("use-main-dispatch-food", 105L),
            floor("use-main-dispatch-snowball", 205L),
            floor("use-main-dispatch-bow", 430L),
            floor("use-main-dispatch-firework", 187L),
            floor("use-main-dispatch-splash-potion", 63L),
            floor("use-main-dispatch-shield", 131L),
            floor("use-main-dispatch-xp-bottle", 195L),
            floor("use-main-dispatch-wind-charge", 197L),
            floor("use-offhand-dispatch-none", 3324L),
            floor("use-offhand-dispatch-pearl", 188L),
            floor("use-offhand-dispatch-snowball", 2565L),
            floor("use-offhand-dispatch-egg", 19L),
            floor("use-offhand-dispatch-splash-potion", 23L),
            floor("use-offhand-dispatch-xp-bottle", 54L),
            floor("use-offhand-dispatch-wind-charge", 37L),
            floor("use-fired-pearl", 54L),
            floor("use-fired-snowball", 183L),
            floor("use-fired-egg", 4L),
            floor("use-fired-firework", 8L),
            floor("use-fired-splash-potion", 184L),
            floor("use-fired-xp-bottle", 192L),
            floor("use-fired-wind-charge", 88L),
            floor("arena-partial-box", 4L),
            floor("arena-outline-clipped-box", 2L),
            floor("tick", 4400L),
            floor("gliding-tick", 255L),
            floor("firework-boost-tick", 90L),
            floor("swimming-tick", 66L),
            floor("submerged-tick", 83L),
            floor("sprinting-tick", 2018L),
            floor("sneaking-tick", 882L),
            floor("mining-tick", 180L),
            floor("container-open-tick", 54L),
            floor("cursor-stack-held-tick", 18L),
            floor("spear-in-hand-tick", 170L),
            floor("counted-clicks-maxed-tick", 1179L),
            floor("round-reset-countdown-tick", 260L),
            floor("crystal-live-tick", 18L),
            floor("anchor-live-tick", 276L),
            floor("charged-anchor-live-tick", 70L),
            floor("fluid-live-tick", 148L),
            floor("cobweb-live-tick", 44L),
            floor("placed-block-live-tick", 1225L),
            floor("broken-arena-voxel-tick", 2180L),
            floor("projectile-live-tick", 1998L),
            floor("item-entity-live-tick", 1284L),
            floor("container-contents-changed", 4L),
            floor("event-swing", 9526L),
            floor("event-death", 12L),
            floor("event-shield-block", 3L),
            floor("event-explosion", 51L),
            floor("event-fluid-solidify", 1L),
            floor("event-potion-splash", 181L),
            floor("event-bucket-fill", 3L),
            floor("event-bucket-empty", 4L),
            floor("event-item-break", 2L),
            floor("hit-weak", 93L),
            floor("hit-strong", 4L),
            floor("hit-knockback", 2L),
            floor("hit-crit", 3L),
            floor("hit-arrow-peer", 2L),
            floor("hit-arrow-self", 2L),
            floor("hit-fire", 2L),
            floor("hit-fall", 48L),
            floor("hit-explosion", 11L),
            floor("sight-blocked-by-placed-block", 234L),
            floor("cobweb-sight-crossed", 564L),
            floor("projectile-spawn-refused", 1L),
            floor("crystal-destroyed-by-projectile", 1L),
            floor("projectile-spawn-firework", 15L),
            floor("use-main-dispatch-egg", 31L),
            floor("use-main-dispatch-crossbow", 60L),
            floor("use-offhand-dispatch-food", 20L),
            floor("use-offhand-dispatch-bow", 20L),
            floor("use-offhand-dispatch-firework", 20L),
            floor("use-offhand-dispatch-crossbow", 19L),
            floor("use-offhand-dispatch-shield", 185L),
            floor("use-fired-crossbow", 1L),
            floor("fire-live-tick", 65L),
            floor("event-totem", 1L),
            floor("event-fire-extinguish", 2L),
            floor("hit-smash", 1L),
            floor("hit-smash-heavy", 1L),
            floor("melee-claim-granted-off-aim", 186L),
            floor("inv-op-on-a-moving-frame", 266L),
            floor("melee-claim-refused-behind-the-back", 13L),
            floor("left-click-claimed-two-targets", 66L),
            floor("inv-op-on-a-combat-frame", 110L)
    );

    private static final Set<String> KNOWN_UNCOVERED = Set.of(
            "authority-stamp-suspended",
            "authority-stamp-held",
            "authority-stamp-walked",
            "authority-stamp-clipped",
            "authority-stamp-too-far",
            "authority-stamp-unowned",
            "authority-stamp-impulse-held",
            "melee-claim-refused-inside-min-reach",
            "blast-cell-budget-exhausted",
            "item-entity-refused",
            "inv-action-none",
            "inv-action-drop-one",
            "inv-action-drop-stack",
            "inv-action-drop-cursor-one",
            "inv-action-drop-cursor-all",
            "inv-action-cursor-resolve",
            "use-fired-none",
            "use-fired-food",
            "use-fired-bow",
            "use-fired-shield"
    );

    private static Map<String, Long> measure() {
        HarnessCounts.Result r = HarnessCounts.run();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < SimProbe.COUNTERS; i++) {
            counts.put(SimProbe.name(i), r.entryPoint(i));
        }
        for (int i = 0; i < HarnessCounts.OBSERVED_COUNTERS; i++) {
            counts.put(HarnessCounts.observedName(i), r.observation(i));
        }
        return counts;
    }

    @Test
    void theCountedRunIsTheSameRunTheDigestMeasures() {
        assertEquals(Long.toHexString(HarnessDigest.run().finalChecksum()),
                Long.toHexString(HarnessCounts.run().finalChecksum()),
                "HarnessCounts replays the scripted log on one instance while HarnessDigest replays"
                        + " it on two. If those two runs ever end in different states the counts"
                        + " below stop describing the run the gate actually measures, and the"
                        + " coverage claim becomes unfalsifiable.");
    }

    @Test
    void everyCounterIsEitherFlooredOrDeclaredUncovered() {
        Set<String> floored = new LinkedHashSet<>();
        for (Floor f : FLOORS) {
            floored.add(f.name());
        }
        List<String> unclassified = new ArrayList<>();
        for (String name : measure().keySet()) {
            if (!floored.contains(name) && !KNOWN_UNCOVERED.contains(name)) {
                unclassified.add(name);
            }
        }
        assertTrue(unclassified.isEmpty(),
                "a new sim entry point was added to SimProbe or HarnessCounts without deciding"
                        + " whether the scenario reaches it. Give it a floor, or add it to"
                        + " KNOWN_UNCOVERED and say so in the phase report: " + unclassified);
    }

    @Test
    void everyFlooredEntryPointIsStillReachedAtLeastThatOften() {
        Map<String, Long> counts = measure();
        List<String> regressions = new ArrayList<>();
        for (Floor f : FLOORS) {
            Long got = counts.get(f.name());
            if (got == null) {
                regressions.add(f.name() + " no longer exists");
            } else if (got < f.atLeast()) {
                regressions.add(f.name() + " reached " + got + " times, floor is " + f.atLeast());
            }
        }
        assertTrue(regressions.isEmpty(),
                "the scenario narrowed: paths the determinism gate is supposed to cover are being"
                        + " reached less often than when the floors were recorded. Widen the"
                        + " scenario back out, or lower the floor deliberately and say why: "
                        + regressions);
    }

    @Test
    void noFurtherEntryPointHasFallenToZero() {
        Set<String> nowZero = new TreeSet<>();
        for (Map.Entry<String, Long> e : measure().entrySet()) {
            if (e.getValue() == 0L) {
                nowZero.add(e.getKey());
            }
        }
        Set<String> newlyZero = new TreeSet<>(nowZero);
        newlyZero.removeAll(KNOWN_UNCOVERED);
        assertTrue(newlyZero.isEmpty(),
                "these sim entry points used to be exercised by the scripted scenario and are now"
                        + " never reached, so the digest cannot respond to changes in them: "
                        + newlyZero);
    }

    @Test
    void theDeclaredUncoveredSetIsNotStale() {
        Map<String, Long> counts = measure();
        List<String> nowCovered = new ArrayList<>();
        for (String name : KNOWN_UNCOVERED) {
            Long got = counts.get(name);
            if (got != null && got > 0L) {
                nowCovered.add(name + " is now reached " + got + " times");
            }
        }
        assertTrue(nowCovered.isEmpty(),
                "KNOWN_UNCOVERED still lists paths the scenario has since started exercising."
                        + " Move them into FLOORS so the coverage cannot silently fall back out: "
                        + nowCovered);
    }
}
