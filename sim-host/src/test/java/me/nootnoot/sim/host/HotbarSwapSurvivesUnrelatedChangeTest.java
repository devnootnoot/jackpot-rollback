package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.ClaimAuthority;
import me.nootnoot.sim.contract.InventoryIntents;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

final class HotbarSwapSurvivesUnrelatedChangeTest {

    private static final int OLD_GLOBAL_HOLD_TIMEOUT_TICKS = 40;

    private static final int STORAGE_SLOT = 20;

    private static final int HOTBAR_SLOT = 3;

    private static final int SWORD = 11;

    private static final int PEARL = 12;

    private static final int CHESTPLATE = 13;

    private static final class WholeTableRule {

        private long shown = Long.MIN_VALUE;

        boolean repaintsEverything(PlayerState mine) {
            long sig = 0xcbf29ce484222325L;
            for (int i = 0; i < mine.slotEntry.length; i++) {
                sig = (sig ^ mine.slotEntry[i]) * 0x100000001b3L;
                sig = (sig ^ mine.slotCount[i]) * 0x100000001b3L;
                sig = (sig ^ mine.slotDamage[i]) * 0x100000001b3L;
            }
            sig = (sig ^ mine.cursorEntry) * 0x100000001b3L;
            sig = (sig ^ mine.cursorCount) * 0x100000001b3L;
            sig = (sig ^ mine.cursorDamage) * 0x100000001b3L;
            if (sig == shown) {
                return false;
            }
            shown = sig;
            return true;
        }
    }

    private static PlayerState table() {
        PlayerState p = new PlayerState();
        for (int i = 0; i < ItemDict.SLOTS; i++) {
            p.slotEntry[i] = ItemDict.NONE;
        }
        p.slotEntry[STORAGE_SLOT] = PEARL;
        p.slotCount[STORAGE_SLOT] = 16;
        p.slotEntry[HOTBAR_SLOT] = SWORD;
        p.slotCount[HOTBAR_SLOT] = 1;
        p.slotEntry[ItemDict.ARMOR_CHEST] = CHESTPLATE;
        p.slotCount[ItemDict.ARMOR_CHEST] = 1;
        p.cursorEntry = ItemDict.NONE;
        return p;
    }

    private static PlayerState swapped(PlayerState from) {
        PlayerState p = from.copy();
        p.slotEntry[STORAGE_SLOT] = SWORD;
        p.slotCount[STORAGE_SLOT] = 1;
        p.slotEntry[HOTBAR_SLOT] = PEARL;
        p.slotCount[HOTBAR_SLOT] = 16;
        return p;
    }

    @Test
    void theOldWholeTableRuleRepaintsTheSwappedSlotsOnAnUnrelatedArmourHit() {
        WholeTableRule rule = new WholeTableRule();
        PlayerState confirmed = table();
        assertTrue(rule.repaintsEverything(confirmed), "first paint always runs");

        confirmed.slotDamage[ItemDict.ARMOR_CHEST] = 1;

        assertTrue(rule.repaintsEverything(confirmed),
                "a durability point on the chestplate repaints the WHOLE table under the old rule, "
                        + "including the two slots the in-flight hotbar swap owns - that repaint is the "
                        + "player watching the swap cancel itself");
    }

    @Test
    void anUnrelatedArmourHitLeavesTheSwappedSlotsAlone() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState confirmed = table();
        PlayerState head = swapped(confirmed);

        int headFrame = 100;
        int confirmedTick = 92;
        plan.ownIntent(new InventoryIntents.Intent(
                me.nootnoot.sim.state.Input.INV_SWAP_SLOT, STORAGE_SLOT, HOTBAR_SLOT), headFrame);

        plan.plan(confirmed, head, confirmedTick);
        assertTrue(plan.repaint(STORAGE_SLOT));
        assertTrue(plan.repaint(HOTBAR_SLOT));
        assertSame(head, plan.view(STORAGE_SLOT), "an owned slot shows the sim's own predicted value");
        assertSame(head, plan.view(HOTBAR_SLOT));

        confirmed.slotDamage[ItemDict.ARMOR_CHEST] = 1;
        plan.plan(confirmed, head, confirmedTick + 1);

        assertTrue(plan.repaint(ItemDict.ARMOR_CHEST), "the chestplate is the slot that changed");
        assertFalse(plan.repaint(STORAGE_SLOT),
                "the armour hit must not touch the slot the swap owns");
        assertFalse(plan.repaint(HOTBAR_SLOT),
                "the armour hit must not touch the slot the swap owns");
        assertEquals(1, plan.repaintCount(), "exactly one slot changed, so exactly one slot is written");
    }

    @Test
    void ownershipIsReleasedByTheLandingFrameNotByAClock() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState confirmed = table();
        PlayerState head = swapped(confirmed);
        int headFrame = 100;
        int landing = InventoryPaintPlan.landingFrame(headFrame);
        assertEquals(headFrame + ClaimAuthority.INPUT_DELAY_FRAMES, landing);

        plan.ownIntent(new InventoryIntents.Intent(
                me.nootnoot.sim.state.Input.INV_SWAP_SLOT, STORAGE_SLOT, HOTBAR_SLOT), headFrame);

        for (int tick = headFrame - 40; tick < landing; tick++) {
            plan.plan(confirmed, head, tick);
            assertTrue(plan.predicted(STORAGE_SLOT),
                    "still in flight at tick " + tick + " - no wall clock may expire this");
        }

        plan.plan(confirmed, head, landing);
        assertFalse(plan.predicted(STORAGE_SLOT), "confirmed reached the landing frame; the slot is free");
        assertEquals(0, plan.ownedCells(landing));
    }

    @Test
    void aHeldSlotFarBeyondTheOldTwoSecondBoundIsStillNotClobbered() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState confirmed = table();
        PlayerState head = swapped(confirmed);
        int headFrame = 1000;
        int oldBound = OLD_GLOBAL_HOLD_TIMEOUT_TICKS;
        int confirmedTick = headFrame - 4 * oldBound;
        plan.plan(confirmed, head, confirmedTick - 1);

        plan.ownIntent(new InventoryIntents.Intent(
                me.nootnoot.sim.state.Input.INV_SWAP_SLOT, STORAGE_SLOT, HOTBAR_SLOT), headFrame);

        plan.plan(confirmed, head, confirmedTick);
        assertTrue(plan.repaint(HOTBAR_SLOT), "the predicted value is painted once, straight away");
        assertSame(head, plan.view(HOTBAR_SLOT));

        for (int i = 1; i <= 4 * oldBound; i++) {
            confirmed.slotDamage[ItemDict.ARMOR_CHEST] = i % 7;
            plan.plan(confirmed, head, confirmedTick + i);
            assertFalse(plan.repaint(HOTBAR_SLOT),
                    "the old global hold gave up after " + oldBound + " ticks and then let the absolute "
                            + "repaint through; per-slot ownership has no such cliff (tick " + i + ")");
        }
    }

    @Test
    void ownershipOfOneSlotNeverBlocksAnother() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState confirmed = table();
        plan.plan(confirmed, confirmed, 0);

        plan.ownIntent(new InventoryIntents.Intent(
                me.nootnoot.sim.state.Input.INV_SWAP_SLOT, STORAGE_SLOT, HOTBAR_SLOT), 10);

        PlayerState head = confirmed.copy();
        confirmed = confirmed.copy();
        confirmed.slotCount[0] = 5;
        confirmed.slotEntry[0] = PEARL;
        plan.plan(confirmed, head, 5);

        assertTrue(plan.repaint(0), "an unowned slot repaints immediately, exactly as before");
        assertFalse(plan.predicted(0));
        assertTrue(plan.predicted(STORAGE_SLOT));
    }

    @Test
    void anIdenticalTableIsNeverRepainted() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState confirmed = table();
        plan.plan(confirmed, confirmed, 0);
        assertEquals(InventoryPaintPlan.PLAYER_CELLS, plan.repaintCount(), "the first plan paints all");
        plan.plan(confirmed, confirmed, 1);
        assertEquals(0, plan.repaintCount());
        plan.reseed();
        plan.plan(confirmed, confirmed, 2);
        assertEquals(InventoryPaintPlan.PLAYER_CELLS, plan.repaintCount(), "reseed repaints all");
    }

    @Test
    void aCrossbowFlagChangeIsSeen() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState confirmed = table();
        plan.plan(confirmed, confirmed, 0);
        confirmed.slotCrossbowLoaded[HOTBAR_SLOT] = true;
        plan.plan(confirmed, confirmed, 1);
        assertTrue(plan.repaint(HOTBAR_SLOT),
                "the edge mirror's old signature omitted this flag, so an unmodded player's crossbow "
                        + "never changed picture when it was loaded or fired");
        assertEquals(1, plan.repaintCount());
    }

    @Test
    void theCursorIsACellOfItsOwn() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState confirmed = table();
        plan.plan(confirmed, confirmed, 0);

        plan.ownIntent(new InventoryIntents.Intent(
                me.nootnoot.sim.state.Input.INV_PICKUP, STORAGE_SLOT, 0), 10);

        PlayerState head = confirmed.copy();
        head.slotEntry[STORAGE_SLOT] = ItemDict.NONE;
        head.slotCount[STORAGE_SLOT] = 0;
        head.cursorEntry = PEARL;
        head.cursorCount = 16;

        confirmed = confirmed.copy();
        confirmed.slotDamage[ItemDict.ARMOR_HEAD] = 3;
        plan.plan(confirmed, head, 5);

        assertTrue(plan.predicted(InventoryPaintPlan.PLAYER_CURSOR));
        assertSame(head, plan.view(InventoryPaintPlan.PLAYER_CURSOR));
        assertTrue(plan.repaint(InventoryPaintPlan.PLAYER_CURSOR),
                "the item the player just picked up shows on the cursor now, not a round trip later");
        assertNotEquals(0, plan.repaintCount());
    }

    @Test
    void aQuickMoveOwnsTheWholeTableBecauseTheSimChoosesTheDestination() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        PlayerState confirmed = table();
        plan.plan(confirmed, confirmed, 0);
        plan.ownIntent(new InventoryIntents.Intent(
                me.nootnoot.sim.state.Input.INV_QUICK_MOVE, STORAGE_SLOT, 0), 10);
        assertEquals(InventoryPaintPlan.PLAYER_CELLS, plan.ownedCells(5));
    }

    @Test
    void aSwapOwnsExactlyTwoSlots() {
        InventoryPaintPlan plan = InventoryPaintPlan.forPlayer();
        plan.ownIntent(new InventoryIntents.Intent(
                me.nootnoot.sim.state.Input.INV_SWAP_SLOT, STORAGE_SLOT, HOTBAR_SLOT), 10);
        assertEquals(2, plan.ownedCells(5),
                "a hotbar-key swap is exactly two slots; nothing else in the table may be held hostage");
    }
}
