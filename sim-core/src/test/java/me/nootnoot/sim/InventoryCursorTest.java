package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.net.InputCodec;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class InventoryCursorTest {
    private static final double GROUND_Y = 64.0;

    private static final Arena ARENA = Arena.flat(GROUND_Y);

    private static GameState duel() {
        return HarnessScenarios.duel(ARENA);
    }

    private static Input click(int action, int src, int dst) {
        return Input.NONE.withInvAction(action, src, dst)
                .withClicks(Clicks.NONE.withInv(1));
    }

    private static void step(GameState s, Input in) {
        Simulation.tick(s, ARENA, in, Input.NONE);
    }

    @Test
    void leftClickTakesTheWholeStackOntoTheCursor() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_PICKUP, 2, 0));

        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.cursorEntry(a),
                "a left click on a filled slot has to land the stack on the cursor");
        assertEquals(16, Loadout.cursorCount(a));
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, 2), "the slot it came from must empty");
    }

    @Test
    void rightClickTakesHalfRoundedUp() {
        GameState s = duel();
        PlayerState a = s.players[0];
        a.slotCount[2] = 9;

        step(s, click(Input.INV_PICKUP_HALF, 2, 0));

        assertEquals(5, Loadout.cursorCount(a), "vanilla takes (count + 1) / 2");
        assertEquals(4, a.slotCount[2]);
    }

    @Test
    void rightClickPlacesExactlyOneFromTheCursor() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_PICKUP, 2, 0));
        step(s, Input.NONE);
        step(s, click(Input.INV_PICKUP_HALF, 5, 0));

        assertEquals(1, a.slotCount[5], "a right click drops a single item into an empty slot");
        assertEquals(15, Loadout.cursorCount(a));
    }

    @Test
    void leftClickOnADifferentItemSwapsSlotAndCursor() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_PICKUP, 2, 0));
        step(s, Input.NONE);
        step(s, click(Input.INV_PICKUP, 1, 0));

        assertEquals(HarnessScenarios.PEARL_ENTRY, Loadout.cursorEntry(a),
                "the pearls have to end up on the cursor");
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, 1),
                "the apples have to end up in the clicked slot");
        assertEquals(16, a.slotCount[1]);
    }

    @Test
    void leftClickOnTheSameItemMergesAndKeepsTheOverflowOnTheCursor() {
        GameState s = duel();
        PlayerState a = s.players[0];
        a.slotEntry[5] = HarnessScenarios.PEARL_ENTRY;
        a.slotCount[5] = 10;

        step(s, click(Input.INV_PICKUP, 1, 0));
        step(s, Input.NONE);
        step(s, click(Input.INV_PICKUP, 5, 0));

        assertEquals(16, a.slotCount[5], "pearls cap at a 16 stack");
        assertEquals(10, Loadout.cursorCount(a), "the overflow stays on the cursor");
    }

    @Test
    void theCursorRefusesToEnterAnArmourSlotItDoesNotFit() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_PICKUP, 0, 0));
        step(s, Input.NONE);
        step(s, click(Input.INV_PICKUP, ItemDict.ARMOR_HEAD, 0));

        assertEquals(HarnessScenarios.SWORD_ENTRY, Loadout.cursorEntry(a),
                "a sword cannot be worn, so the click must be a no-op");
        assertEquals(HarnessScenarios.HELMET_ENTRY, Loadout.entryAt(a, ItemDict.ARMOR_HEAD));
    }

    @Test
    void aHotbarKeyOverASlotSwapsInsteadOfMerging() {
        GameState s = duel();
        PlayerState a = s.players[0];
        a.slotEntry[20] = HarnessScenarios.APPLE_ENTRY;
        a.slotCount[20] = 5;

        step(s, click(Input.INV_SWAP_SLOT, 20, 2));

        assertEquals(5, a.slotCount[2], "a number-key swap trades the two stacks");
        assertEquals(16, a.slotCount[20]);
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, 20));
    }

    @Test
    void aHotbarKeyOverAnEmptySlotMovesTheHotbarStackOut() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_SWAP_SLOT, 20, 1));

        assertEquals(ItemDict.NONE, Loadout.entryAt(a, 1), "the hotbar slot empties");
        assertEquals(HarnessScenarios.PEARL_ENTRY, Loadout.entryAt(a, 20));
        assertEquals(16, a.slotCount[20]);
    }

    @Test
    void aHotbarKeyIntoAnArmourSlotIsRefused() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_SWAP_SLOT, ItemDict.ARMOR_HEAD, 0));

        assertEquals(HarnessScenarios.HELMET_ENTRY, Loadout.entryAt(a, ItemDict.ARMOR_HEAD),
                "a sword cannot swap into the helmet slot");
        assertEquals(HarnessScenarios.SWORD_ENTRY, Loadout.entryAt(a, 0));
    }

    @Test
    void backToBackHotbarKeysBothLand() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_SWAP_SLOT, 20, 1));
        step(s, click(Input.INV_SWAP_SLOT, 21, 2));

        assertEquals(HarnessScenarios.PEARL_ENTRY, Loadout.entryAt(a, 20),
                "the first swap must not be swallowed by the second frame");
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, 21),
                "a counted inventory click on the very next tick still resolves");
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, 1));
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, 2));
    }

    @Test
    void aSwapOntoTheSameSlotDoesNothing() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_SWAP_SLOT, 2, 2));

        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, 2));
        assertEquals(16, a.slotCount[2], "swapping a slot with itself must not duplicate it");
    }

    @Test
    void aSwapCanReachTheOffHand() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_SWAP_SLOT, 2, ItemDict.OFF_HAND));

        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, ItemDict.OFF_HAND));
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, 2));
    }

    @Test
    void closingTheScreenPutsTheCarriedStackBackInTheInventory() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_PICKUP, 2, 0));
        assertEquals(16, Loadout.cursorCount(a));

        step(s, click(Input.INV_CURSOR_RESOLVE, 0, 0));

        assertEquals(ItemDict.NONE, Loadout.cursorEntry(a), "the cursor has to empty on close");
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, 2),
                "vanilla places the carried stack back into the inventory");
        assertEquals(16, a.slotCount[2]);
    }

    @Test
    void dyingResolvesTheCursorSoNothingIsHeldHostage() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_PICKUP, 2, 0));
        a.dead = true;
        step(s, Input.NONE);

        assertEquals(ItemDict.NONE, Loadout.cursorEntry(a));
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, 2));
    }

    @Test
    void clickingOutsideTheWindowThrowsTheCarriedStack() {
        GameState s = duel();
        PlayerState a = s.players[0];
        int before = s.items.size();

        step(s, click(Input.INV_PICKUP, 2, 0));
        step(s, Input.NONE);
        step(s, Input.NONE.withInvAction(Input.INV_DROP_CURSOR_ALL, 0, 0)
                .withClicks(Clicks.NONE.withInv(1)));

        assertEquals(ItemDict.NONE, Loadout.cursorEntry(a), "the whole cursor stack is tossed");
        assertTrue(s.items.size() > before, "the toss has to spawn an item entity");
    }

    @Test
    void aDoubleClickGathersMatchingStacksOntoTheCursor() {
        GameState s = duel();
        PlayerState a = s.players[0];
        a.slotEntry[20] = HarnessScenarios.APPLE_ENTRY;
        a.slotCount[20] = 30;

        step(s, click(Input.INV_PICKUP, 2, 0));
        step(s, Input.NONE);
        step(s, click(Input.INV_PICKUP_ALL, 2, 0));

        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.cursorEntry(a));
        assertEquals(46, Loadout.cursorCount(a),
                "the gather sweeps the matching stacks into the carried one");
    }

    @Test
    void theCursorIsDeepCopiedAndChecksummed() {
        GameState s = duel();
        PlayerState a = s.players[0];

        step(s, click(Input.INV_PICKUP, 2, 0));
        long withCursor = Checksum.of(s);

        PlayerState copy = a.copy();
        assertEquals(a.cursorEntry, copy.cursorEntry);
        assertEquals(a.cursorCount, copy.cursorCount);
        copy.cursorCount = 1;
        assertNotEquals(1, a.cursorCount, "copy() must not alias the cursor");

        a.cursorCount -= 1;
        assertNotEquals(withCursor, Checksum.of(s),
                "the carried stack decides the simulation, so it has to move the checksum");
    }

    @Test
    void theCodecCarriesEveryClickType() {
        for (int action = Input.INV_NONE; action <= Input.INV_MAX; action++) {
            Input in = Input.NONE.withInvAction(action, Input.cellAddr(26), ItemDict.OFF_HAND);
            ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
            InputCodec.write(b, in);
            b.flip();
            Input back = InputCodec.read(b);
            assertEquals(action, back.invAction(), "action " + action + " must survive the wire");
            assertEquals(Input.cellAddr(26), back.invSrc());
            assertEquals(ItemDict.OFF_HAND, back.invDst());
        }
    }

    @Test
    void anOutOfRangeClickTypeIsRejected() {
        Input in = Input.NONE.withInvAction(Input.INV_MAX + 1, 0, 0);
        assertEquals(Input.INV_NONE, in.invAction(),
                "an unknown click type must not reach the inventory model");
    }

    @Test
    void cellAddressesAndSlotAddressesNeverOverlap() {
        assertFalse(Input.addrIsCell(ItemDict.SLOTS - 1),
                "every player slot has to read as a slot address");
        assertTrue(Input.addrIsCell(Input.cellAddr(0)));
        assertEquals(26, Input.addrIndex(Input.cellAddr(26)));
    }

    @Test
    void quickMoveIsSharedByBothHosts() {
        GameState s = duel();
        PlayerState a = s.players[0];
        int carried = Loadout.countAt(a, 0);

        assertTrue(Loadout.quickMove(s, a, null, 0),
                "a hotbar stack shift-moves into the storage rows");
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, 0), "the source slot empties");
        assertEquals(carried, Loadout.countAt(a, ItemDict.HOTBAR),
                "the whole stack lands in the first free storage slot");

        a.slotEntry[9] = HarnessScenarios.APPLE_ENTRY;
        a.slotCount[9] = 1;
        int apples = Loadout.countAt(a, 2);
        assertTrue(Loadout.quickMove(s, a, null, 2));
        assertEquals(1 + apples, Loadout.countAt(a, 9),
                "a matching partial stack is topped up before any empty slot is used");
    }
}
