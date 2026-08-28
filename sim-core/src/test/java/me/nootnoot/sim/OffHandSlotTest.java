package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class OffHandSlotTest {
    private static final double GROUND_Y = 64.0;

    private static GameState duel() {
        return HarnessScenarios.duel(Arena.flat(GROUND_Y));
    }

    @Test
    void armourIsAllowedInTheOffHand() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        int chest = HarnessScenarios.CHESTPLATE_ENTRY;

        assertTrue(Loadout.slotAccepts(s, chest, ItemDict.OFF_HAND),
                "vanilla lets you carry any armour piece in the off-hand");
        float worn = a.armor;

        Simulation.tick(s, arena,
                Input.NONE.withInvAction(Input.INV_MOVE, ItemDict.ARMOR_CHEST, ItemDict.OFF_HAND),
                Input.NONE);

        assertEquals(chest, Loadout.entryAt(a, ItemDict.OFF_HAND),
                "the chestplate must land in the off-hand");
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, ItemDict.ARMOR_CHEST));
        assertTrue(a.armor < worn, "the piece is carried, not worn");
    }

    @Test
    void swappingHandsAnswersExactlyWhatTheEquipGateAnswers() {
        GameState s = duel();
        PlayerState a = s.players[0];
        a.heldSlot = 0;
        a.slotEntry[0] = HarnessScenarios.CHESTPLATE_ENTRY;
        a.slotCount[0] = 1;
        a.slotEntry[ItemDict.OFF_HAND] = ItemDict.NONE;
        a.slotCount[ItemDict.OFF_HAND] = 0;

        boolean gate = Loadout.slotAccepts(s, HarnessScenarios.CHESTPLATE_ENTRY, ItemDict.OFF_HAND)
                && Loadout.slotAccepts(s, ItemDict.NONE, 0);

        assertEquals(gate, Loadout.swapHands(s, a),
                "the hand swap must go through the same gate a move does");
        assertEquals(HarnessScenarios.CHESTPLATE_ENTRY, Loadout.entryAt(a, ItemDict.OFF_HAND));
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, 0));
    }

    @Test
    void swappingHandsSwapsTheStacksInsteadOfMergingThem() {
        GameState s = duel();
        PlayerState a = s.players[0];
        a.heldSlot = 2;
        a.slotEntry[2] = HarnessScenarios.APPLE_ENTRY;
        a.slotCount[2] = 8;
        a.slotEntry[ItemDict.OFF_HAND] = HarnessScenarios.APPLE_ENTRY;
        a.slotCount[ItemDict.OFF_HAND] = 5;

        assertTrue(Loadout.swapHands(s, a));
        assertEquals(5, a.slotCount[2], "the two stacks must trade places");
        assertEquals(8, a.slotCount[ItemDict.OFF_HAND]);
    }
}
