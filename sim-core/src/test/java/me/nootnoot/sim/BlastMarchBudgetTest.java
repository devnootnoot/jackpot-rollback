package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class BlastMarchBudgetTest {
    private static final double GROUND_Y = 64.0;

    private static final int RUBBLE_ITEM_ID = 5100;
    private static final int RUBBLE_X = 20;
    private static final int RUBBLE_Y = 100;
    private static final int RUBBLE_Z = 20;
    private static final int RUBBLE_SPAN = 5;
    private static final float SOFT_RESISTANCE = 0.1f;

    private static final int SMALL_MARCH_BUDGET = 40;

    private static final int SHELL_RAYS = 1352;

    private static GameState rubbleField() {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = GROUND_Y;
        a.z = 0.5;
        a.health = 20f;
        PlayerState v = s.players[1];
        v.x = 10_000.0;
        v.y = GROUND_Y;
        v.health = 20f;
        for (int x = RUBBLE_X; x < RUBBLE_X + RUBBLE_SPAN; x++) {
            for (int y = RUBBLE_Y; y < RUBBLE_Y + RUBBLE_SPAN; y++) {
                for (int z = RUBBLE_Z; z < RUBBLE_Z + RUBBLE_SPAN; z++) {
                    s.blocks.place(x, y, z, RUBBLE_ITEM_ID);
                    s.blockResistance.put(BlockStore.key(x, y, z), SOFT_RESISTANCE);
                }
            }
        }
        return s;
    }

    private static int rubbleStanding(GameState s) {
        int n = 0;
        for (int x = RUBBLE_X; x < RUBBLE_X + RUBBLE_SPAN; x++) {
            for (int y = RUBBLE_Y; y < RUBBLE_Y + RUBBLE_SPAN; y++) {
                for (int z = RUBBLE_Z; z < RUBBLE_Z + RUBBLE_SPAN; z++) {
                    if (s.blocks.contains(x, y, z)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static void blastRubble(GameState s, Arena arena) {
        Combat.explode(s, arena, RUBBLE_X + 2.5, RUBBLE_Y + 2.5, RUBBLE_Z + 2.5,
                Combat.CRYSTAL_POWER, 0, false);
    }

    @Test
    void theRayMarchIsBoundedAndNotJustTheRemoval() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = rubbleField();
        s.blastMarchBudget = SMALL_MARCH_BUDGET;

        blastRubble(s, arena);

        assertEquals(0, s.blastMarchBudget,
                "the removal budget only ever bounded the cells taken out of the world. The work"
                        + " that finds them, 1352 shell rays of up to 35 cells each, was unbounded,"
                        + " so a tick full of detonations could burn tens of thousands of block"
                        + " lookups before a single removal was refused. The march has to spend"
                        + " from a budget of its own and stop when it is gone.");
        assertTrue(s.blastCellBudget > 0,
                "and stopping the march early must leave the removal allowance untouched, or the"
                        + " two bounds would be the same bound wearing two names");
    }

    @Test
    void aBlastThatRanOutOfMarchBudgetLeavesMoreOfTheRubbleStanding() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState clipped = rubbleField();
        clipped.blastMarchBudget = SMALL_MARCH_BUDGET;
        GameState whole = rubbleField();

        blastRubble(clipped, arena);
        blastRubble(whole, arena);

        assertTrue(rubbleStanding(clipped) > rubbleStanding(whole),
                "a truncated march finds fewer cells, which is the point of bounding it");
    }

    @Test
    void aSecondBlastInTheSameTickCannotReopenASpentMarchBudget() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = rubbleField();
        s.blastMarchBudget = SMALL_MARCH_BUDGET;

        blastRubble(s, arena);
        int afterFirst = rubbleStanding(s);
        blastRubble(s, arena);

        assertEquals(afterFirst, rubbleStanding(s),
                "the march allowance is per tick and shared by every explosion in that tick");
        assertEquals(0, s.blastMarchBudget, "and it stays spent");
    }

    @Test
    void anOrdinaryCrystalBlastComesNowhereNearTheMarchAllowance() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = rubbleField();

        blastRubble(s, arena);
        int spent = GameState.BLAST_MARCH_CELLS_PER_TICK - s.blastMarchBudget;

        assertTrue(s.blastMarchBudget > 0,
                "the bound must only bite on a tick already far past anything a real crystal chain"
                        + " does; one blast spent " + spent + " of "
                        + GameState.BLAST_MARCH_CELLS_PER_TICK);
        assertTrue(spent <= SHELL_RAYS * Combat.blastRaySteps(Combat.CRYSTAL_POWER * 1.3f),
                "and one blast can never charge more than its shell rays at their longest");
    }

    @Test
    void bothBlastBudgetsAreRestoredAtTheTopOfEveryTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.blastCellBudget = 0;
        s.blastMarchBudget = 0;

        Simulation.tick(s, arena, Input.NONE, Input.NONE);

        assertEquals(GameState.BLAST_CELLS_PER_TICK, s.blastCellBudget,
                "the removal allowance is per tick");
        assertEquals(GameState.BLAST_MARCH_CELLS_PER_TICK, s.blastMarchBudget,
                "and so is the march allowance");
    }

    @Test
    void aSnapshotCarriesBothBudgetsAndTheChecksumCanSeeThem() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.blastCellBudget = 111;
        s.blastMarchBudget = 222;

        GameState copy = s.copy();
        assertEquals(111, copy.blastCellBudget, "copy() has always carried the removal budget");
        assertEquals(222, copy.blastMarchBudget, "and now carries the march budget too");
        assertEquals(Checksum.of(s), Checksum.of(copy), "a faithful snapshot checksums the same");

        GameState other = s.copy();
        other.blastCellBudget = 112;
        assertNotEquals(Checksum.of(s), Checksum.of(other),
                "copy() carried this field but neither the checksum nor the frame-0 codec could"
                        + " see it, so a state that had already spent its allowance and one that"
                        + " had not hashed identically. A rollback that resimulated the tail of a"
                        + " tick could hand back a different budget and no comparison anywhere"
                        + " would notice.");

        GameState third = s.copy();
        third.blastMarchBudget = 223;
        assertNotEquals(Checksum.of(s), Checksum.of(third), "the same goes for the march budget");
    }

    @Test
    void theFrameZeroCodecRoundTripsBothBudgets() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.blastCellBudget = 77;
        s.blastMarchBudget = 4321;

        GameState back = GameStateFrame0Codec.decode(GameStateFrame0Codec.encode(s));

        assertEquals(77, back.blastCellBudget,
                "a decoded frame 0 that silently reset to the full allowance would be a different"
                        + " state than the one that was encoded");
        assertEquals(4321, back.blastMarchBudget, "and the same for the march budget");
        assertEquals(Checksum.of(s), Checksum.of(back),
                "which is exactly what the checksum now has to agree about");
    }
}
