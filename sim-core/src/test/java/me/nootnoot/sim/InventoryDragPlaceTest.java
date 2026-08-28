package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.contract.InventoryDrag;
import me.nootnoot.sim.contract.InventoryIntents;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class InventoryDragPlaceTest {

    private static final double GROUND_Y = 64.0;

    private static final Arena ARENA = Arena.flat(GROUND_Y);

    private static final int CLICK_QUICK_CRAFT = -1;

    private static final int LEFT = InventoryIntents.BUTTON_PRIMARY;

    private record Click(int kind, int slotId, int button) {
    }

    private static final class Capture {

        private final InventoryDrag drag = new InventoryDrag();

        private final List<InventoryIntents.Intent> out = new ArrayList<>();

        private void offer(Click click, boolean slotFilled, boolean cursorEmpty) {
            if (click.kind() == CLICK_QUICK_CRAFT) {
                InventoryDrag.Collapse collapse = drag.offer(click.slotId(), click.button(),
                        cursorEmpty, click.slotId() >= 0, false);
                if (collapse.pickup()) {
                    decide(InventoryIntents.CLICK_PICKUP, collapse.slotId(), collapse.button(),
                            slotFilled, cursorEmpty);
                }
                return;
            }
            if (drag.dragging()) {
                drag.reset();
                return;
            }
            decide(click.kind(), click.slotId(), click.button(), slotFilled, cursorEmpty);
        }

        private void decide(int kind, int addr, int button, boolean slotFilled,
                            boolean cursorEmpty) {
            InventoryIntents.Intent intent = InventoryIntents.decide(kind, addr, button, slotFilled,
                    cursorEmpty);
            if (intent.acts()) {
                out.add(intent);
            }
        }
    }

    private static List<Click> dragGesture(int slotId, int type) {
        return List.of(
                new Click(CLICK_QUICK_CRAFT, -999, InventoryDrag.mask(0, type)),
                new Click(CLICK_QUICK_CRAFT, slotId, InventoryDrag.mask(1, type)),
                new Click(CLICK_QUICK_CRAFT, -999, InventoryDrag.mask(2, type)));
    }

    private static GameState duel() {
        return HarnessScenarios.duel(ARENA);
    }

    private static void run(GameState s, InventoryIntents.Intent intent) {
        Simulation.tick(s, ARENA, Input.NONE.withInvAction(intent.action(), intent.src(),
                intent.dst()).withClicks(Clicks.NONE.withInv(1)), Input.NONE);
    }

    private static void idle(GameState s) {
        Simulation.tick(s, ARENA, Input.NONE, Input.NONE);
    }

    @Test
    void pressMoveReleaseActuallyMovesTheStack() {
        GameState s = duel();
        PlayerState a = s.players[0];
        Capture capture = new Capture();

        capture.offer(new Click(InventoryIntents.CLICK_PICKUP, 2, LEFT), true, true);
        assertEquals(1, capture.out.size(), "the press has to produce the pickup");
        run(s, capture.out.get(0));
        idle(s);
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.cursorEntry(a));
        assertEquals(16, Loadout.cursorCount(a));

        for (Click click : dragGesture(7, InventoryDrag.TYPE_EVEN)) {
            capture.offer(click, false, false);
        }
        assertEquals(2, capture.out.size(),
                "the release over slot 7 is a one-slot QUICK_CRAFT, and vanilla itself turns that back"
                        + " into a PICKUP on that slot. Dropping it is why the cursor kept the stack"
                        + " forever and the inventory read as frozen");
        run(s, capture.out.get(1));
        idle(s);

        assertEquals(ItemDict.NONE, Loadout.cursorEntry(a), "the cursor must be empty again");
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, 7),
                "the stack has to land in the slot the gesture released over");
        assertEquals(16, a.slotCount[7]);
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, 2), "and must not still be in the old slot");
    }

    @Test
    void aRightButtonDragPlacesOneItem() {
        GameState s = duel();
        PlayerState a = s.players[0];
        Capture capture = new Capture();

        capture.offer(new Click(InventoryIntents.CLICK_PICKUP, 2, LEFT), true, true);
        run(s, capture.out.get(0));
        idle(s);

        for (Click click : dragGesture(7, InventoryDrag.TYPE_SINGLE)) {
            capture.offer(click, false, false);
        }
        assertEquals(2, capture.out.size());
        assertEquals(Input.INV_PICKUP_HALF, capture.out.get(1).action(),
                "vanilla collapses with the drag TYPE as the button, so a right-button drag has to"
                        + " arrive as the half/one-item pickup and not the whole-stack one");
        run(s, capture.out.get(1));
        idle(s);

        assertEquals(1, a.slotCount[7], "a right-button drag over one slot places exactly one item");
        assertEquals(15, Loadout.cursorCount(a), "the rest stays on the cursor");
    }

    @Test
    void aMultiSlotSpreadIsRefusedWithoutLosingTheStack() {
        GameState s = duel();
        PlayerState a = s.players[0];
        Capture capture = new Capture();

        capture.offer(new Click(InventoryIntents.CLICK_PICKUP, 2, LEFT), true, true);
        run(s, capture.out.get(0));
        idle(s);

        capture.offer(new Click(CLICK_QUICK_CRAFT, -999,
                InventoryDrag.mask(0, InventoryDrag.TYPE_EVEN)), false, false);
        capture.offer(new Click(CLICK_QUICK_CRAFT, 7,
                InventoryDrag.mask(1, InventoryDrag.TYPE_EVEN)), false, false);
        capture.offer(new Click(CLICK_QUICK_CRAFT, 8,
                InventoryDrag.mask(1, InventoryDrag.TYPE_EVEN)), false, false);
        capture.offer(new Click(CLICK_QUICK_CRAFT, -999,
                InventoryDrag.mask(2, InventoryDrag.TYPE_EVEN)), false, false);

        assertEquals(1, capture.out.size(),
                "there is no even-spread operation to send, so the gesture must produce nothing at all"
                        + " rather than a guess");
        idle(s);
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.cursorEntry(a),
                "and the stack has to still be on the cursor, not silently gone");
        assertEquals(16, Loadout.cursorCount(a));
    }

    @Test
    void aPlainClickDuringADragCancelsItLikeVanilla() {
        Capture capture = new Capture();
        capture.offer(new Click(CLICK_QUICK_CRAFT, -999,
                InventoryDrag.mask(0, InventoryDrag.TYPE_EVEN)), false, false);
        capture.offer(new Click(CLICK_QUICK_CRAFT, 7,
                InventoryDrag.mask(1, InventoryDrag.TYPE_EVEN)), false, false);
        capture.offer(new Click(InventoryIntents.CLICK_PICKUP, 3, LEFT), true, false);

        assertTrue(capture.out.isEmpty(),
                "AbstractContainerMenu.doClick resets a live quick-craft on any other click and does"
                        + " nothing else that click; a host that answered it would act where vanilla"
                        + " did not");
        assertFalse(capture.drag.dragging(), "and the drag must be over");
    }

    @Test
    void aDragThatStartsWithAnEmptyCursorNeverBecomesAPickup() {
        Capture capture = new Capture();
        for (Click click : dragGesture(7, InventoryDrag.TYPE_EVEN)) {
            capture.offer(click, true, true);
        }
        assertTrue(capture.out.isEmpty(),
                "vanilla only enters a quick-craft with something on the cursor; collapsing an"
                        + " empty-cursor drag would invent a pickup the other host never saw");
    }

    @Test
    void theCloneDragIsRefusedOutsideCreative() {
        Capture capture = new Capture();
        for (Click click : dragGesture(7, InventoryDrag.TYPE_CLONE)) {
            capture.offer(click, false, false);
        }
        assertTrue(capture.out.isEmpty(),
                "type 2 is the creative clone drag and vanilla gates it on infinite materials, which"
                        + " no duelist has");
    }

    @Test
    void theQuickCraftMaskArithmeticMatchesVanilla() {
        for (int header = 0; header <= 2; header++) {
            for (int type = 0; type <= 2; type++) {
                int mask = InventoryDrag.mask(header, type);
                assertEquals(header, InventoryDrag.header(mask));
                assertEquals(type, InventoryDrag.type(mask));
            }
        }
    }

    @Test
    void aPickupThenAPlaceIsWhatAPredictedCursorProduces() {
        GameState s = duel();
        PlayerState a = s.players[0];

        Capture capture = new Capture();
        capture.offer(new Click(InventoryIntents.CLICK_PICKUP, 2, LEFT), true, true);
        run(s, capture.out.get(0));
        idle(s);

        capture.offer(new Click(InventoryIntents.CLICK_PICKUP, 7, LEFT), false, false);
        run(s, capture.out.get(1));
        idle(s);
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, 7));
        assertEquals(ItemDict.NONE, Loadout.cursorEntry(a));

        GameState unpredicted = duel();
        PlayerState b = unpredicted.players[0];
        Capture stale = new Capture();
        stale.offer(new Click(InventoryIntents.CLICK_PICKUP, 2, LEFT), true, true);
        run(unpredicted, stale.out.get(0));
        idle(unpredicted);
        stale.offer(new Click(InventoryIntents.CLICK_PICKUP, 2, LEFT), false, true);
        run(unpredicted, stale.out.get(1));
        idle(unpredicted);
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(b, 2),
                "two pickups on one slot cancel out - that is the tester's dead inventory, and it is a"
                        + " client-side cursor problem, not a sim one");
        assertEquals(ItemDict.NONE, Loadout.cursorEntry(b));
    }
}
