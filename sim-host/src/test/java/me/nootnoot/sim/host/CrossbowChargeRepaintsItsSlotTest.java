package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

final class CrossbowChargeRepaintsItsSlotTest {

    private static final int CROSSBOW_SLOT = 3;

    private static final int CROSSBOW = 21;

    private static final int ARROW = 22;

    private static PlayerState table() {
        PlayerState p = new PlayerState();
        p.slotEntry[CROSSBOW_SLOT] = CROSSBOW;
        p.slotCount[CROSSBOW_SLOT] = 1;
        p.slotEntry[9] = ARROW;
        p.slotCount[9] = 32;
        return p;
    }

    @Test
    void firingTheCrossbowRepaintsItsSlotAndNothingElse() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState p = table();
        p.slotCrossbowLoaded[CROSSBOW_SLOT] = true;

        plan.plan(p, null, 0);

        p.slotCrossbowLoaded[CROSSBOW_SLOT] = false;
        plan.plan(p, null, 1);

        assertTrue(plan.repaint(CROSSBOW_SLOT),
                "entry, count and damage are all unchanged when a crossbow fires - only the charge"
                        + " moves. A plan that ignores the charge leaves the player looking at a"
                        + " loaded crossbow they have already shot");
        assertEquals(1, plan.repaintCount(),
                "and it must repaint only that slot, not the whole table");
    }

    @Test
    void reloadingRepaintsItBack() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState p = table();

        plan.plan(p, null, 0);
        p.slotCrossbowLoaded[CROSSBOW_SLOT] = true;
        plan.plan(p, null, 1);

        assertTrue(plan.repaint(CROSSBOW_SLOT), "the finished reload is the other half of the same"
                + " transition and is what tells the player the bolt is ready");
    }

    @Test
    void aChargedCrossbowOnTheCursorIsTrackedToo() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState p = table();
        p.cursorEntry = CROSSBOW;
        p.cursorCount = 1;

        plan.plan(p, null, 0);
        p.cursorCrossbowLoaded = true;
        plan.plan(p, null, 1);

        assertTrue(plan.repaint(InventoryPaintPlan.PLAYER_CURSOR),
                "PlayerState carries cursorCrossbowLoaded because a charged crossbow can be picked"
                        + " up, and a cell the plan will not repaint is a cell the mirror never"
                        + " corrects");
    }

    @Test
    void aSlotWithNoCrossbowIsNotRepaintedByTheChargeArray() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState p = table();

        plan.plan(p, null, 0);
        plan.plan(p, null, 1);

        for (int slot = 0; slot < ItemDict.SLOTS; slot++) {
            assertFalse(plan.repaint(slot),
                    "nothing changed between the two plans, so nothing may repaint");
        }
    }
}
