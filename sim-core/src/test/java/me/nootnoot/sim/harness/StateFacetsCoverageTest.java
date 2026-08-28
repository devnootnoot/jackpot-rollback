package me.nootnoot.sim.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class StateFacetsCoverageTest {
    @Test
    void everyGameStateFieldIsAttributedToAFacet() {
        assertAttributed(GameState.class, StateFacets.GAME_STATE_FIELDS);
    }

    @Test
    void everyPlayerStateFieldIsAttributedToAFacet() {
        assertAttributed(PlayerState.class, StateFacets.PLAYER_STATE_FIELDS);
    }

    @Test
    void theFacetTablesDoNotNameFieldsThatNoLongerExist() {
        assertNoStaleNames(GameState.class, StateFacets.GAME_STATE_FIELDS);
        assertNoStaleNames(PlayerState.class, StateFacets.PLAYER_STATE_FIELDS);
    }

    @Test
    void everyFacetActuallyMovesDuringTheScriptedRun() {
        Arena arena = HarnessScenarios.arena();
        GameState g = HarnessScenarios.combat(arena);
        InputLog log = InputLog.scripted(HarnessDigest.SEED, HarnessDigest.TICKS);

        int[] first = StateFacets.of(g);
        boolean[] moved = new boolean[StateFacets.COUNT];
        for (Input[] frame : log.frames) {
            Simulation.tick(g, arena, frame[0], frame[1]);
            int[] now = StateFacets.of(g);
            for (int i = 0; i < StateFacets.COUNT; i++) {
                moved[i] |= now[i] != first[i];
            }
        }

        List<String> inert = new ArrayList<>();
        for (int i = 0; i < StateFacets.COUNT; i++) {
            if (!moved[i]) {
                inert.add(StateFacets.name(i));
            }
        }
        assertTrue(inert.isEmpty(), "these facets never changed across the whole scripted run: "
                + inert + ". A facet that is constant cannot localise a divergence, which is the"
                + " only reason the per-tick columns are written at all");
    }

    @Test
    void facetsAreStableForAStateAndSensitiveToIt() {
        Arena arena = HarnessScenarios.arena();
        GameState g = HarnessScenarios.combat(arena);
        assertArrayEqualsAsHex(StateFacets.of(g), StateFacets.of(g));

        GameState moved = g.copy();
        moved.players[0].vy = Double.longBitsToDouble(
                Double.doubleToRawLongBits(moved.players[0].vy) ^ 1L);
        int[] before = StateFacets.of(g);
        int[] after = StateFacets.of(moved);
        assertFalse(Arrays.equals(before, after),
                "flipping the lowest bit of one velocity left every facet unchanged");
        assertEquals("p0.motion", DeterminismHarness.namedFacets(before, after).split(" ")[0],
                "a one-bit change to player 0 velocity has to be attributed to p0.motion,"
                        + " otherwise the report points a bisect at the wrong part of the sim");
    }

    private static void assertArrayEqualsAsHex(int[] a, int[] b) {
        assertEquals(Arrays.toString(a), Arrays.toString(b));
    }

    private static void assertAttributed(Class<?> type, String[] table) {
        Set<String> named = new LinkedHashSet<>(Arrays.asList(table));
        named.addAll(Arrays.asList(StateFacets.NOT_CHECKSUMMED));

        List<String> orphans = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }
            if (!named.contains(f.getName())) {
                orphans.add(f.getName());
            }
        }
        assertTrue(orphans.isEmpty(), type.getSimpleName() + " has field(s) " + orphans
                + " that no facet claims. Add each one to the facet that owns it in StateFacets,"
                + " or to NOT_CHECKSUMMED if Checksum.of deliberately ignores it. An unattributed"
                + " field is a divergence the cross-machine report cannot name");
    }

    private static void assertNoStaleNames(Class<?> type, String[] table) {
        Set<String> declared = new LinkedHashSet<>();
        for (Field f : type.getDeclaredFields()) {
            declared.add(f.getName());
        }
        List<String> stale = new ArrayList<>();
        for (String name : table) {
            if (!declared.contains(name)) {
                stale.add(name);
            }
        }
        assertTrue(stale.isEmpty(), "StateFacets names " + stale + " for "
                + type.getSimpleName() + " but no such field exists any more");
    }
}
