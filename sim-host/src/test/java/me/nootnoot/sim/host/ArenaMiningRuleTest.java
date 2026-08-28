package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class ArenaMiningRuleTest {

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

    private static Path mod() {
        Path jackpot = rollbackRoot().getParent();
        return jackpot == null ? Path.of("nowhere") : jackpot.resolve(
                "pvphq-rollback-mod/src/main/java/me/nootnoot/rollback/client/McInputSource.java");
    }

    static boolean modPresent() {
        return Files.isRegularFile(mod());
    }

    private static final int SPAN = 4;

    private static Arena slab() {
        boolean[] grid = new boolean[SPAN * SPAN * SPAN];
        java.util.Arrays.fill(grid, true);
        return new Arena(64.0, grid, 0, 62, 0, SPAN, SPAN, SPAN, new double[0][],
                java.util.Map.of());
    }

    @Test
    void vanillaBuildIsWhatMakesArenaTerrainMineableAndTheSimOwnsIt() {
        Arena arena = slab();
        GameState off = new GameState();
        GameState on = new GameState();
        on.vanillaBuild = true;

        assertTrue(arena.isSolidVoxel(1, 63, 1) || arena.isDecorVoxel(1, 63, 1),
                "the fixture has to actually contain arena terrain, or the two assertions below"
                        + " both pass for the wrong reason");
        assertFalse(Combat.breakableCell(off, arena, 1, 63, 1),
                "a game type that is not a vanilla-build duel cannot mine the map. That is a"
                        + " GameType rule, and it lives in the sim");
        assertTrue(Combat.breakableCell(on, arena, 1, 63, 1),
                "and a vanilla-build duel can");

        on.brokenArena.add(BlockStore.key(1, 63, 1));
        assertFalse(Combat.breakableCell(on, arena, 1, 63, 1),
                "a voxel already broken is not breakable twice");
    }

    @Test
    void aPlacedBlockIsBreakableWhateverTheGameTypeSaysAboutTheMap() {
        Arena arena = slab();
        GameState s = new GameState();
        s.cobwebs.put(BlockStore.key(2, 65, 2), 1);
        assertTrue(Combat.breakableCell(s, arena, 2, 65, 2),
                "vanillaBuild is about the ARENA, not about the things players put into it. A pot"
                        + " duel with buildings off still has to be able to cut a cobweb");
        assertFalse(Combat.breakableCell(s, arena, 3, 90, 3),
                "and empty air is not a mining target on either host");
        assertFalse(Combat.breakableCell(s, null, 1, 63, 1),
                "a host that has no arena yet must answer no rather than throw: the mod samples"
                        + " input before the driver exists");
    }

    @Test
    void theEdgeKeepsNoSecondCopyOfTheRule() throws IOException {
        String cells = Files.readString(rollbackRoot().resolve(
                "edge/src/main/java/me/nootnoot/edge/EdgeCells.java"), StandardCharsets.UTF_8);
        int at = cells.indexOf("public boolean breakable(");
        assertTrue(at > 0, "EdgeCells.breakable is where the edge decides a mining frame");
        String body = cells.substring(at, cells.indexOf('}', at));
        assertTrue(body.contains("Combat.breakableCell("),
                "the edge has to ask the sim. It used to spell out"
                        + " !state.vanillaBuild || brokenArena || isSolidVoxel || isDecorVoxel"
                        + " itself, which is the same expression Combat.mineTarget runs");
        assertFalse(body.contains("vanillaBuild"),
                "and having asked, it must not also restate the rule");
    }

    @Test
    @EnabledIf("modPresent")
    void bothProducersNameAMiningFrameForTheSameCells() throws IOException {
        String source = Files.readString(mod(), StandardCharsets.UTF_8);
        assertTrue(source.contains("Combat.breakableCell("),
                "McInputSource emitted BLOCK_BREAK for any cell the client could chip at, while"
                        + " the edge stopped digging as soon as Combat said the cell was not"
                        + " breakable. That is not a harmless prediction difference:"
                        + " HostFrameContract.attackClicks zeroes the attack counter on a MINING"
                        + " frame, so a modded player holding left click at unmineable arena floor"
                        + " spent no attack clicks and an unmodded one spent all of them. Same"
                        + " press, different ClickBudget, different melee on that tick");
        assertTrue(source.contains("simBreakable("),
                "and it has to ask through one named helper, so there is one place to look");
    }

    @Test
    void theSimStillDecidesTheSameWayItAlwaysDid() {
        Arena arena = slab();
        GameState s = new GameState();
        s.vanillaBuild = true;
        for (int y = 61; y <= 67; y++) {
            for (int x = 0; x <= 5; x++) {
                boolean legacy = s.vanillaBuild
                        && (arena.isSolidVoxel(x, y, 1) || arena.isDecorVoxel(x, y, 1))
                        && !s.brokenArena.contains(BlockStore.key(x, y, 1));
                assertEquals(legacy, Combat.arenaTerrainMineable(s, arena, x, y, 1),
                        "the extracted predicate has to be the expression Combat.mineTarget used"
                                + " to hold inline, exactly, or the move changed simulated"
                                + " behaviour and the checksum rev has to move with it."
                                + " x=" + x + " y=" + y);
            }
        }
    }
}
