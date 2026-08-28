package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import org.junit.jupiter.api.Test;

class SharedPoolOwnershipTest {
    private static final int Y0 = 64;

    private static final double GROUND_Y = Y0;

    private static final int OBSIDIAN_ITEM_ID = 4301;
    private static final int CRYSTAL_ITEM_ID = 4302;

    private static final int[] CRYSTAL_BASE = {2, Y0, 0};

    private static final String WHY =
            "a bounded collection with one shared first-come cap is a denial button: the side that"
                    + " reaches the cap first takes the mechanic away from the other side, and in a"
                    + " ranked duel taking crystals away from your opponent is winning. Projectiles"
                    + " and item entities already reserve half the pool per owner; these are the"
                    + " same shape";

    private static GameState duelWithCrystals() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.players[1].x = 40.0;
        TestKit.of(s).give(0, 1, 4,
                TestKit.item().itemId(CRYSTAL_ITEM_ID).flags(ItemDict.FLAG_END_CRYSTAL));
        s.obsidianItemId = OBSIDIAN_ITEM_ID;
        s.blocks.place(CRYSTAL_BASE[0], CRYSTAL_BASE[1], CRYSTAL_BASE[2], OBSIDIAN_ITEM_ID);
        return s;
    }

    private static void seedCrystals(GameState s, int owner, int count) {
        for (int i = 0; i < count; i++) {
            CrystalState c = new CrystalState();
            c.id = s.nextCrystalId++;
            c.owner = owner;
            c.bx = 500 + i;
            c.by = Y0;
            c.bz = 500;
            s.crystals.add(c);
        }
    }

    private static Input placeCrystal() {
        return Input.NONE.withHeldSlot(1)
                .withBlockAction(Input.BLOCK_PLACE_CRYSTAL,
                        CRYSTAL_BASE[0], CRYSTAL_BASE[1], CRYSTAL_BASE[2]);
    }

    @Test
    void halfTheCrystalPoolIsReservedForEachSide() {
        assertEquals(Combat.MAX_CRYSTALS / 2, Combat.MAX_CRYSTALS_PER_OWNER,
                "the reservation is half the pool each, the same split Projectiles makes");

        GameState s = new GameState();
        seedCrystals(s, 0, Combat.MAX_CRYSTALS_PER_OWNER);

        assertFalse(Combat.crystalRoomFor(s, 0),
                "the side that has spent its half is done placing. " + WHY);
        assertTrue(Combat.crystalRoomFor(s, 1),
                "and the other side still has its half, with the pool only half full. " + WHY);
        assertTrue(s.crystals.size() < Combat.MAX_CRYSTALS,
                "if one side could reach MAX_CRYSTALS on its own the reservation would be a"
                        + " formality");
    }

    @Test
    void aCrystalRecordsTheHandThatPlacedIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duelWithCrystals();

        Simulation.tick(s, arena, placeCrystal(), Input.NONE);

        assertEquals(1, s.crystals.size(), "the placement has to land or nothing is being counted");
        assertEquals(0, s.crystals.get(0).owner,
                "the cap can only be a reservation if a crystal remembers whose half it came out"
                        + " of, which is the same field ProjectileState already carries");
    }

    @Test
    void aSideThatSpentItsHalfStopsPlacingAndTheOtherSideDoesNot() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState spent = duelWithCrystals();
        seedCrystals(spent, 0, Combat.MAX_CRYSTALS_PER_OWNER);
        Simulation.tick(spent, arena, placeCrystal(), Input.NONE);
        assertEquals(Combat.MAX_CRYSTALS_PER_OWNER, spent.crystals.size(),
                "player 0 had already placed its half, so this one is refused. " + WHY);

        GameState opponentSpent = duelWithCrystals();
        seedCrystals(opponentSpent, 1, Combat.MAX_CRYSTALS_PER_OWNER);
        Simulation.tick(opponentSpent, arena, placeCrystal(), Input.NONE);
        assertEquals(Combat.MAX_CRYSTALS_PER_OWNER + 1, opponentSpent.crystals.size(),
                "and a pool half full of the OPPONENT's crystals takes nothing away from player 0,"
                        + " which is the whole point of splitting it. " + WHY);
    }

    @Test
    void halfTheFluidPoolIsReservedForEachSide() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = new GameState();
        for (int i = 0; i < Fluids.MAX_CELLS_PER_OWNER; i++) {
            s.fluids.put(BlockStore.key(1000 + i, Y0 + 4, 1000),
                    Fluids.pack(Fluids.WATER, 8, true, false, 0));
        }

        assertFalse(Fluids.roomFor(s, 0), "player 0 has spent its half of the cells. " + WHY);
        assertTrue(Fluids.roomFor(s, 1), "player 1 has not. " + WHY);

        assertFalse(Fluids.place(s, arena, 0, Fluids.WATER, 0, Y0 + 2, 0),
                "so the bucket that filled the pool cannot place another source");
        assertTrue(Fluids.place(s, arena, 1, Fluids.WATER, 0, Y0 + 2, 0),
                "and the opponent's bucket still works, which is the mechanic the shared cap used"
                        + " to hand away first come. " + WHY);
        assertEquals(1, Fluids.owner(s.fluids.get(BlockStore.key(0, Y0 + 2, 0))),
                "the cell is charged to the hand that placed it");
    }

    @Test
    void everyCellASourceFeedsIsChargedToTheHandThatPlacedIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.players[0].x = 10_000;
        s.players[0].z = 10_000;
        s.players[1].x = -10_000;
        s.players[1].z = -10_000;

        assertTrue(Fluids.place(s, arena, 1, Fluids.WATER, 0, Y0, 0),
                "the source has to land before it can spread");
        for (int i = 0; i < 200; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }

        assertTrue(s.fluids.size() > 1, "the source should have spread across the floor");
        for (int v : s.fluids.values()) {
            assertEquals(1, Fluids.owner(v),
                    "spread inherits the owner of the cell that fed it, so the cap counts the whole"
                            + " puddle against the player who poured it. A cap that only counted"
                            + " the source cells would be no cap at all, because spread is what"
                            + " actually fills the map. " + WHY);
        }
    }

    @Test
    void anUnownedCellIsNeitherSidesHalf() {
        GameState s = new GameState();
        int neutral = Fluids.pack(Fluids.WATER, 8, true, false);

        assertEquals(-1, Fluids.owner(neutral),
                "a cell nobody placed reads back as unowned rather than as player 0's, or seeded"
                        + " water would eat one side's reservation before the match started");
        s.fluids.put(BlockStore.key(0, Y0 + 6, 0), neutral);
        assertEquals(0, Fluids.cellsOwnedBy(s, 0), "and it counts against neither half");
        assertEquals(0, Fluids.cellsOwnedBy(s, 1), "and it counts against neither half");
    }
}
