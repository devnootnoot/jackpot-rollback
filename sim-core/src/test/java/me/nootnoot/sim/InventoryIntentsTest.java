package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.contract.InventoryIntents;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class InventoryIntentsTest {

    private static final int FILLED_SLOT = 3;

    private static InventoryIntents.Intent decide(int click, int addr, int button) {
        return InventoryIntents.decide(click, addr, button, true, true);
    }

    @Test
    void aLeftAndRightPickupAreTheOnlyTwoPickupOutcomes() {
        assertEquals(Input.INV_PICKUP,
                decide(InventoryIntents.CLICK_PICKUP, FILLED_SLOT,
                        InventoryIntents.BUTTON_PRIMARY).action());
        assertEquals(Input.INV_PICKUP_HALF,
                decide(InventoryIntents.CLICK_PICKUP, FILLED_SLOT,
                        InventoryIntents.BUTTON_SECONDARY).action());
        assertFalse(decide(InventoryIntents.CLICK_PICKUP, FILLED_SLOT, 2).acts(),
                "vanilla only sends button 0 and 1 for a PICKUP; anything else has no meaning and"
                        + " must not become an action on either host");
    }

    @Test
    void anUnknownClickIsSilentOnBothHosts() {
        for (int addr = 0; addr < ItemDict.SLOTS; addr++) {
            assertFalse(decide(InventoryIntents.CLICK_UNKNOWN, addr, 0).acts(),
                    "a drag (QUICK_CRAFT), a clone, or any click neither host models has exactly"
                            + " one representable outcome: nothing. The mod used to answer one of"
                            + " these with a CURSOR_RESOLVE, which puts the carried stack back"
                            + " while the edge left it on the cursor");
        }
        assertFalse(InventoryIntents.decide(InventoryIntents.CLICK_UNKNOWN,
                InventoryIntents.ADDR_OUTSIDE, 0, false, false).acts(),
                "a drag that starts outside the window is still a drag");
    }

    @Test
    void aSlotTheHostCouldNotAddressIsSilent() {
        assertFalse(decide(InventoryIntents.CLICK_PICKUP, InventoryIntents.ADDR_NONE, 0).acts(),
                "a crafting-grid slot, or any slot that is not the player inventory or the open"
                        + " container, is unaddressable and must produce no intent");
        assertFalse(decide(InventoryIntents.CLICK_PICKUP, ItemDict.SLOTS, 0).acts(),
                "a player address past the last real slot is not a cell address either");
        assertFalse(decide(InventoryIntents.CLICK_PICKUP, InventoryIntents.maxAddr() + 1, 0).acts());
        assertTrue(decide(InventoryIntents.CLICK_PICKUP, InventoryIntents.maxAddr(), 0).acts(),
                "the last container cell is addressable");
    }

    @Test
    void aThrowFromAnEmptySlotIsRefused() {
        assertFalse(InventoryIntents.decide(InventoryIntents.CLICK_THROW, FILLED_SLOT,
                        InventoryIntents.BUTTON_PRIMARY, false, true).acts(),
                "the edge used to send a Q-throw for a slot holding nothing, which claimed a drop"
                        + " click the mod never spent");
        assertEquals(Input.INV_DROP_ONE,
                decide(InventoryIntents.CLICK_THROW, FILLED_SLOT,
                        InventoryIntents.BUTTON_PRIMARY).action());
        assertEquals(Input.INV_DROP_STACK,
                decide(InventoryIntents.CLICK_THROW, FILLED_SLOT,
                        InventoryIntents.BUTTON_SECONDARY).action());
    }

    @Test
    void aThrowWithAFullCursorIsRefusedButOneOutOfAContainerCellIsNot() {
        assertFalse(InventoryIntents.decide(InventoryIntents.CLICK_THROW, FILLED_SLOT,
                InventoryIntents.BUTTON_PRIMARY, true, false).acts());
        assertEquals(Input.INV_DROP_ONE, decide(InventoryIntents.CLICK_THROW, Input.cellAddr(0),
                InventoryIntents.BUTTON_PRIMARY).action(),
                "pressing Q over a cell of an open shulker has to drop out of the container."
                        + " Refusing every cell address here is what made dropping anything out of"
                        + " a container do nothing at all, on both hosts");
        assertEquals(Input.cellAddr(0), decide(InventoryIntents.CLICK_THROW, Input.cellAddr(0),
                InventoryIntents.BUTTON_PRIMARY).src(),
                "the cell address has to survive into the intent, or the sim drops out of whatever"
                        + " player slot happens to share that number");
        assertEquals(Input.INV_DROP_STACK, decide(InventoryIntents.CLICK_THROW, Input.cellAddr(3),
                InventoryIntents.BUTTON_SECONDARY).action());
    }

    @Test
    void aNumberKeyOverTheSlotItWouldSwapWithIsRefused() {
        assertFalse(decide(InventoryIntents.CLICK_SWAP, 4, 4).acts(),
                "swapping a hotbar slot with itself is a no-op the sim already refuses, but the"
                        + " edge still claimed an inventory click for it and the mod did not");
        assertFalse(decide(InventoryIntents.CLICK_SWAP, ItemDict.OFF_HAND,
                InventoryIntents.OFFHAND_SWAP_BUTTON).acts());
        assertEquals(Input.INV_SWAP_SLOT, decide(InventoryIntents.CLICK_SWAP, 20, 4).action());
        assertEquals(4, decide(InventoryIntents.CLICK_SWAP, 20, 4).dst());
        assertEquals(ItemDict.OFF_HAND, decide(InventoryIntents.CLICK_SWAP, 20,
                InventoryIntents.OFFHAND_SWAP_BUTTON).dst());
        assertFalse(decide(InventoryIntents.CLICK_SWAP, 20, ItemDict.HOTBAR).acts(),
                "there is no tenth hotbar button");
    }

    @Test
    void aDoubleClickWithAnEmptyCursorGathersNothing() {
        assertFalse(InventoryIntents.decide(InventoryIntents.CLICK_PICKUP_ALL, 5, 0, true, true)
                .acts(), "vanilla's PICKUP_ALL is gated on a carried stack, and the sim refuses it"
                        + " anyway - claiming the click on an empty cursor only burns budget");
        assertEquals(Input.INV_PICKUP_ALL,
                InventoryIntents.decide(InventoryIntents.CLICK_PICKUP_ALL, 5, 0, true, false)
                        .action());
    }

    @Test
    void anOutsideClickOnlyDropsTheCursor() {
        assertEquals(Input.INV_DROP_CURSOR_ALL,
                InventoryIntents.decide(InventoryIntents.CLICK_PICKUP,
                        InventoryIntents.ADDR_OUTSIDE, InventoryIntents.BUTTON_PRIMARY, false,
                        false).action());
        assertEquals(Input.INV_DROP_CURSOR_ONE,
                InventoryIntents.decide(InventoryIntents.CLICK_PICKUP,
                        InventoryIntents.ADDR_OUTSIDE, InventoryIntents.BUTTON_SECONDARY, false,
                        false).action());
        assertFalse(InventoryIntents.decide(InventoryIntents.CLICK_PICKUP,
                        InventoryIntents.ADDR_OUTSIDE, InventoryIntents.BUTTON_PRIMARY, false, true)
                .acts(), "there is nothing to throw when the cursor is empty");
        assertFalse(InventoryIntents.decide(InventoryIntents.CLICK_QUICK_MOVE,
                InventoryIntents.ADDR_OUTSIDE, 0, false, false).acts());
    }

    @Test
    void aQuickMoveNamesOnlyItsSourceSoTheSimOwnsTheDestination() {
        InventoryIntents.Intent intent =
                decide(InventoryIntents.CLICK_QUICK_MOVE, 12, InventoryIntents.BUTTON_PRIMARY);
        assertEquals(Input.INV_QUICK_MOVE, intent.action());
        assertEquals(12, intent.src());
        assertEquals(0, intent.dst(),
                "a host that resolved the destination itself is a second copy of vanilla's"
                        + " moveItemStackTo, and the two copies drifted");
        assertTrue(intent.quickMoves());
        assertEquals(Input.INV_QUICK_MOVE, decide(InventoryIntents.CLICK_QUICK_MOVE,
                Input.cellAddr(3), InventoryIntents.BUTTON_SECONDARY).action());
        assertFalse(InventoryIntents.decide(InventoryIntents.CLICK_QUICK_MOVE, 12, 0, false, true)
                .acts());
    }

    @Test
    void everyIntentTheTableCanProduceIsOneTheSimAccepts() {
        int[] clicks = {InventoryIntents.CLICK_UNKNOWN, InventoryIntents.CLICK_PICKUP,
                InventoryIntents.CLICK_QUICK_MOVE, InventoryIntents.CLICK_SWAP,
                InventoryIntents.CLICK_THROW, InventoryIntents.CLICK_PICKUP_ALL};
        int[] addrs = {InventoryIntents.ADDR_OUTSIDE, InventoryIntents.ADDR_NONE, 0, 8, 20,
                ItemDict.ARMOR_CHEST, ItemDict.OFF_HAND, Input.cellAddr(0),
                Input.cellAddr(Container.CELLS - 1), InventoryIntents.maxAddr() + 1};
        for (int click : clicks) {
            for (int addr : addrs) {
                for (int button = -1; button <= 41; button++) {
                    for (int filled = 0; filled < 2; filled++) {
                        for (int cursor = 0; cursor < 2; cursor++) {
                            InventoryIntents.Intent intent = InventoryIntents.decide(click, addr,
                                    button, filled == 1, cursor == 1);
                            assertTrue(intent.action() >= Input.INV_NONE
                                            && intent.action() <= Input.INV_MAX,
                                    "the decision table may only name actions the wire carries");
                            assertTrue(intent.src() >= 0 && intent.src() <= InventoryIntents.maxAddr(),
                                    "a source address has to survive Input's byte-wide inv fields");
                            assertTrue(intent.dst() >= 0 && intent.dst() <= InventoryIntents.maxAddr());
                            Input in = Input.NONE.withInvAction(intent.action(), intent.src(),
                                    intent.dst());
                            assertEquals(intent.action(), in.invAction(),
                                    "an intent the table produced must not be clamped away by"
                                            + " Input's own validation");
                            assertEquals(intent.src(), in.invSrc());
                            assertEquals(intent.dst(), in.invDst());
                        }
                    }
                }
            }
        }
    }

    @Test
    void chestArmourIsReadOffTheReplicatedDictionaryNotTheHostsOwnItemTable() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        TestKit kit = TestKit.of(s);
        int wings = kit.add(TestKit.item().itemId(7301).maxStack(1)
                .flags(ItemDict.FLAG_ELYTRA).armor(0, 0f, 0f, ItemDict.EQUIP_CHEST));
        int boots = kit.add(TestKit.item().itemId(313).maxStack(1)
                .armor(3, 0f, 0f, ItemDict.EQUIP_FEET));
        int stone = kit.add(TestKit.item().itemId(4).maxStack(64));
        kit.put(0, 0, wings, 1);
        kit.put(0, 1, boots, 1);
        kit.put(0, 2, stone, 64);

        assertTrue(InventoryIntents.chestArmour(s, p, 0),
                "the elytra wears on the chest, and the SIM dictionary is the one table both hosts"
                        + " already agree on - the edge used to answer this from Material names and"
                        + " the mod from the live EQUIPPABLE component");
        assertFalse(InventoryIntents.chestArmour(s, p, 1));
        assertFalse(InventoryIntents.chestArmour(s, p, 2));
        assertFalse(InventoryIntents.chestArmour(s, p, 3), "an empty hand is not armour");
        assertFalse(InventoryIntents.chestArmour(s, p, ItemDict.ARMOR_CHEST),
                "only a HOTBAR slot can be the held item");
        assertFalse(InventoryIntents.chestArmour(null, p, 0));

        InventoryIntents.Intent equip = InventoryIntents.chestEquip(0);
        assertEquals(Input.INV_MOVE, equip.action());
        assertEquals(0, equip.src());
        assertEquals(ItemDict.ARMOR_CHEST, equip.dst());
    }
}
