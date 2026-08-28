package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class SharedContainerConcurrentClickTest {

    private static final int STACK = 7;

    private static int carried(PlayerState p, int entry) {
        int n = 0;
        for (int slot = 0; slot < p.slotEntry.length; slot++) {
            if (p.slotEntry[slot] == entry) {
                n += p.slotCount[slot];
            }
        }
        return n;
    }

    private static int inBox(Container c, int entry) {
        int n = 0;
        for (int cell = 0; cell < Container.CELLS; cell++) {
            if (c.entry[cell] == entry) {
                n += c.count[cell];
            }
        }
        return n;
    }

    @Test
    void twoPlayersTakingTheSameCellCannotBetweenThemTakeMoreThanItHeld() {
        Arena arena = Arena.flat(64.0);
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        PlayerState b = s.players[1];

        Container shared = new Container();
        int entry = a.slotEntry[0] != 0 ? a.slotEntry[0] : 1;
        int emptySlotA = freeSlot(a);
        int emptySlotB = freeSlot(b);
        shared.entry[0] = entry;
        shared.count[0] = STACK;

        int beforeA = carried(a, entry);
        int beforeB = carried(b, entry);

        boolean movedA = Loadout.moveContainer(s, a, shared, 0, emptySlotA, true);
        boolean movedB = Loadout.moveContainer(s, b, shared, 0, emptySlotB, true);

        int tookA = carried(a, entry) - beforeA;
        int tookB = carried(b, entry) - beforeB;

        assertTrue(movedA, "the first taker must actually get the stack, or this test proves nothing");
        assertEquals(STACK, tookA + tookB + inBox(shared, entry),
                "player 0 resolves fully before player 1 in Combat.resolve, and every container op"
                        + " re-reads the live Container rather than a snapshot, so the second taker"
                        + " sees the cell the first already emptied. If either of those is ever"
                        + " refactored away, this is the dupe it becomes: both players walk away"
                        + " with a full stack from one cell");
        assertEquals(0, tookB,
                "the cell was emptied by the first take, so the second must come away with nothing");
        assertTrue(!movedB || tookB == 0, "a move that took nothing must not report a transfer");
    }

    private static int freeSlot(PlayerState p) {
        for (int slot = 9; slot < p.slotEntry.length; slot++) {
            if (p.slotCount[slot] == 0) {
                return slot;
            }
        }
        throw new IllegalStateException("the kit left no empty slot to take into");
    }
}
