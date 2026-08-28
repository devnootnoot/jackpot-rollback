package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class DropSeqCanJumpInOneTickTest {
    private static final double GROUND_Y = 64.0;
    private static final int SLOT = CrystalKitFixture.SLOT_CRYSTAL;

    private static Input toss(int presses) {
        return new Input(false, false, false, false, false, false, false, false, false,
                0f, 0f, SLOT)
                .withDrop(true, false)
                .withClicks(new Clicks(0, 0, presses, 0, 0));
    }

    private static GameState settle(Arena arena) {
        GameState s = CrystalKitFixture.build(GROUND_Y);
        for (int i = 0; i < 30; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
        return s;
    }

    @Test
    void oneTickCanMintSeveralDropUidsAtOnce() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = settle(arena);
        PlayerState mine = s.players[0];
        int before = mine.dropSeq;

        Simulation.tick(s, arena, toss(4), Input.NONE);

        int minted = mine.dropSeq - before;
        assertTrue(minted > 1,
                "a tick minted " + minted + " drop uids. The drop key is drained per PRESS and"
                        + " arrives as a click count, so one frame runs dropOnce that many times."
                        + " Any consumer that watches dropSeq for a CHANGE and binds one uid per"
                        + " change - McSimRenderer.bindConfirmedDrops did exactly that - binds the"
                        + " newest and silently loses the rest. Those item entities then have no"
                        + " remembered stack, so picking them up hands back a bare type-only item"
                        + " with its enchants, durability and shulker contents gone, and the"
                        + " per-slot FIFO the binder polls from is left one entry out of step for"
                        + " every drop it skipped.");
    }

    @Test
    void theSkippedUidsAreTheOnesLastDropSlotAndLastDropCountCannotDescribe() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = settle(arena);
        PlayerState mine = s.players[0];
        int before = mine.dropSeq;

        Simulation.tick(s, arena, toss(4), Input.NONE);

        Set<Integer> uids = new HashSet<>();
        for (ItemEntityState e : s.items) {
            if (e.dropUid != 0) {
                uids.add(e.dropUid);
            }
        }
        assertTrue(uids.size() >= 1,
                "the tossed items left no uid at all in confirmed state");
        assertTrue(uids.size() < mine.dropSeq - before,
                "ItemEntities.mergeStacks folds same-tick tosses of the same item into one entity,"
                        + " so confirmed state carries FEWER surviving uids than the tick minted."
                        + " That is exactly why the skipped seqs cannot be recovered by scanning"
                        + " the world alone: the binder still has to walk every seq in the gap so"
                        + " each one polls its own entry off the per-slot FIFO, or that queue"
                        + " drifts one entry out of step and every later drop from the slot is"
                        + " bound to some earlier drop's stack.");

        assertEquals(mine.lastDropSlot, SLOT,
                "PlayerState keeps only the LAST drop's slot and count, so a binder that reads"
                        + " them once per observed dropSeq change describes one drop and loses"
                        + " the others");
    }
}
