package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class QuickMoveTargetTest {

    private static TestKit.Item chestplate() {
        return TestKit.item().itemId(311).maxStack(1).maxDamage(528)
                .armor(8, 2f, 0f, ItemDict.EQUIP_CHEST);
    }

    private static TestKit.Item elytra() {
        return TestKit.item().itemId(7301).maxStack(1).maxDamage(432)
                .flags(ItemDict.FLAG_ELYTRA).armor(0, 0f, 0f, ItemDict.EQUIP_CHEST);
    }

    private static TestKit.Item block() {
        return TestKit.item().itemId(4).maxStack(64);
    }

    @Test
    void aWornArmourPieceShiftClicksBackIntoTheInventory() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        TestKit kit = TestKit.of(s);
        int chest = kit.add(chestplate());
        kit.put(0, ItemDict.ARMOR_CHEST, chest, 1);

        assertTrue(Loadout.quickMove(s, p, null, ItemDict.ARMOR_CHEST),
                "vanilla InventoryMenu.quickMoveStack sends an armour slot to moveItemStackTo(9,45)"
                        + " - the main inventory rows first, hotbar after. Doing nothing at all made"
                        + " shift-clicking a worn piece dead on both hosts");
        assertEquals(ItemDict.NONE, Loadout.entryAt(p, ItemDict.ARMOR_CHEST));
        assertEquals(chest, Loadout.entryAt(p, ItemDict.HOTBAR));
    }

    @Test
    void theOffHandShiftClicksBackIntoTheInventory() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        TestKit kit = TestKit.of(s);
        int stone = kit.add(block());
        kit.put(0, ItemDict.OFF_HAND, stone, 12);

        assertTrue(Loadout.quickMove(s, p, null, ItemDict.OFF_HAND));
        assertEquals(ItemDict.NONE, Loadout.entryAt(p, ItemDict.OFF_HAND));
        assertEquals(12, Loadout.countAt(p, ItemDict.HOTBAR));
    }

    @Test
    void aWornPieceFallsBackToTheHotbarWhenTheInventoryIsFull() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        TestKit kit = TestKit.of(s);
        int chest = kit.add(chestplate());
        int stone = kit.add(block());
        kit.put(0, ItemDict.ARMOR_CHEST, chest, 1);
        for (int i = ItemDict.HOTBAR; i < ItemDict.MAIN_SLOTS; i++) {
            kit.put(0, i, stone, 64);
        }
        for (int i = 1; i < ItemDict.HOTBAR; i++) {
            kit.put(0, i, stone, 64);
        }

        assertTrue(Loadout.quickMove(s, p, null, ItemDict.ARMOR_CHEST));
        assertEquals(chest, Loadout.entryAt(p, 0),
                "the hotbar is the second half of vanilla's 9..45 range, not an unreachable region");
    }

    @Test
    void shiftClickingAGliderOutOfTheHotbarWearsIt() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        TestKit kit = TestKit.of(s);
        int wings = kit.add(elytra());
        kit.put(0, 3, wings, 1);

        assertTrue(Loadout.quickMove(s, p, null, 3),
                "vanilla routes an armour stack to its empty equipment slot before it considers the"
                        + " generic inventory ranges - that is the shift-click half of equipping an"
                        + " elytra, and it has to reach the sim or the wings can never open");
        assertEquals(wings, Loadout.entryAt(p, ItemDict.ARMOR_CHEST));
        Loadout.recomputeDerived(s, p);
        assertTrue(p.hasElytra);
    }

    @Test
    void anOccupiedChestSlotIsNotStolenByAShiftClick() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        TestKit kit = TestKit.of(s);
        int chest = kit.add(chestplate());
        int wings = kit.add(elytra());
        kit.put(0, ItemDict.ARMOR_CHEST, chest, 1);
        kit.put(0, 3, wings, 1);

        assertTrue(Loadout.quickMove(s, p, null, 3));
        assertEquals(chest, Loadout.entryAt(p, ItemDict.ARMOR_CHEST),
                "vanilla only auto-equips into an EMPTY equipment slot; with a chestplate already"
                        + " on, the shift-click is an ordinary inventory move");
        assertEquals(wings, Loadout.entryAt(p, ItemDict.HOTBAR));
    }

    @Test
    void aShiftClickFillsEveryMatchingStackNotJustTheFirst() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        TestKit kit = TestKit.of(s);
        int stone = kit.add(block());
        kit.put(0, 0, stone, 64);
        kit.put(0, ItemDict.HOTBAR, stone, 40);
        kit.put(0, ItemDict.HOTBAR + 1, stone, 40);

        assertTrue(Loadout.quickMove(s, p, null, 0));
        assertEquals(ItemDict.NONE, Loadout.entryAt(p, 0),
                "vanilla moveItemStackTo keeps topping up matching stacks until the source is"
                        + " empty; returning ONE destination stranded the remainder in the source"
                        + " slot and is the whole shift-click divergence");
        assertEquals(64, Loadout.countAt(p, ItemDict.HOTBAR));
        assertEquals(64, Loadout.countAt(p, ItemDict.HOTBAR + 1));
    }

    @Test
    void aShiftClickIntoAContainerFillsCellsInMenuOrder() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        TestKit kit = TestKit.of(s);
        int stone = kit.add(block());
        kit.put(0, 0, stone, 64);
        Container box = new Container();
        box.entry[0] = stone;
        box.count[0] = 60;

        assertTrue(Loadout.quickMove(s, p, box, 0));
        assertEquals(64, box.count[0], "the partially filled cell tops up first");
        assertEquals(60, box.count[1], "the remainder lands in the first empty cell");
        assertEquals(ItemDict.NONE, Loadout.entryAt(p, 0));
    }

    @Test
    void aShiftClickOutOfAContainerFillsTheHotbarBeforeTheStorageRows() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        TestKit kit = TestKit.of(s);
        int stone = kit.add(block());
        Container box = new Container();
        box.entry[5] = stone;
        box.count[5] = 64;

        assertTrue(Loadout.quickMove(s, p, box, Input.cellAddr(5)));
        assertEquals(64, Loadout.countAt(p, ItemDict.HOTBAR - 1),
                "ChestMenu.quickMoveStack walks the player half in REVERSE, so the last hotbar slot"
                        + " receives before anything else");
        assertEquals(0, box.count[5]);
    }

    @Test
    void anEmptySourceIsRefusedRatherThanClaimed() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        assertFalse(Loadout.quickMove(s, p, null, 4));
        assertFalse(Loadout.quickMove(s, p, null, Input.cellAddr(0)),
                "a cell address with no container open cannot move anything");
    }
}
