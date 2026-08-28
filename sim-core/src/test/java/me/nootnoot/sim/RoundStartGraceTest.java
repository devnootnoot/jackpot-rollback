package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class RoundStartGraceTest {
    private static final double GROUND_Y = 64.0;

    private static final int BLOCK_ITEM_ID = 970;
    private static final int SWORD_ITEM_ID = 971;
    private static final int SPARE_ITEM_ID = 972;

    private static final int SLOT_SWORD = 0;
    private static final int SLOT_BLOCKS = 1;
    private static final int SLOT_SPARE = 2;

    private static final int TARGET_X = 3;
    private static final int TARGET_Y = 64;
    private static final int TARGET_Z = 0;

    private static GameState duel(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = arena.groundY;
        a.z = 0.5;
        a.yaw = -90f;
        a.pitch = 0f;
        a.onGround = true;
        a.attackTicker = 100;

        PlayerState v = s.players[1];
        v.x = 2.0;
        v.y = arena.groundY;
        v.z = 0.5;
        v.yaw = 90f;
        v.onGround = true;

        TestKit kit = TestKit.of(s);
        kit.give(0, SLOT_SWORD, 1, TestKit.item().itemId(SWORD_ITEM_ID).maxStack(1)
                .melee(8f, 1.6f).flags(ItemDict.FLAG_SWORD));
        kit.give(0, SLOT_BLOCKS, 16, TestKit.item().itemId(BLOCK_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        kit.give(0, SLOT_SPARE, 16, TestKit.item().itemId(SPARE_ITEM_ID));
        return s;
    }

    private static void enterGrace(GameState s) {
        s.roundStartGrace = Simulation.ROUND_START_GRACE;
    }

    private static Input swing() {
        return new Input(false, false, false, false, false, false, false, true, false,
                -90f, 0f, SLOT_SWORD).withMeleeHit(true);
    }

    @Test
    void theGraceWindowIsTheOneTheEdgeStopsValidatingMovementFor() {
        assertTrue(Simulation.ROUND_START_GRACE > 0);

        GameState s = new GameState();
        assertFalse(Combat.roundStartLocked(s), "no grace outside the window");
        s.roundStartGrace = 1;
        assertTrue(Combat.roundStartLocked(s),
                "every input consumer has to read one predicate, because EdgeInputSource folds"
                        + " roundStartGrace into roundLocked and stops validating movement for"
                        + " exactly this window");
    }

    @Test
    void aMeleeSwingDoesNothingDuringTheGrace() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        float before = s.players[1].health;
        enterGrace(s);

        Simulation.tick(s, arena, swing(), Input.NONE);

        assertEquals(before, s.players[1].health,
                "an attack landed while the movement envelope was switched off, so a client could"
                        + " teleport into reach unvalidated and hit on the round-start tick");
        assertTrue(s.players[0].attackTicker > 1,
                "the attack cooldown keeps recovering through the grace, but a suppressed swing"
                        + " must never reset the ticker the way swingOnce does");
    }

    @Test
    void theSameSwingLandsOnceTheGraceHasRunOut() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        float before = s.players[1].health;

        Simulation.tick(s, arena, swing(), Input.NONE);

        assertTrue(s.players[1].health < before, "control: the swing is otherwise a landing hit");
    }

    @Test
    void aBlockPlacementDoesNothingDuringTheGrace() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        enterGrace(s);

        Simulation.tick(s, arena, Input.NONE.withHeldSlot(SLOT_BLOCKS)
                .withBlockAction(Input.BLOCK_PLACE, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);

        assertFalse(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z),
                "block actions were already suppressed and must stay suppressed");
    }

    @Test
    void anInventoryMoveDoesNothingDuringTheGrace() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        enterGrace(s);
        int spare = s.players[0].slotEntry[SLOT_SPARE];

        Simulation.tick(s, arena,
                Input.NONE.withInvAction(Input.INV_MOVE, SLOT_SPARE, 8), Input.NONE);

        assertEquals(spare, s.players[0].slotEntry[SLOT_SPARE],
                "an inventory shuffle is an input consumer too and the grace has to cover it");
        assertEquals(ItemDict.NONE, s.players[0].slotEntry[8]);
    }

    @Test
    void aDropDoesNothingDuringTheGrace() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        enterGrace(s);

        Simulation.tick(s, arena, Input.NONE.withHeldSlot(SLOT_SPARE).withDrop(true, false),
                Input.NONE);

        assertTrue(s.items.isEmpty(),
                "dropping items into a round the opponent cannot react to yet is the same"
                        + " unvalidated window");
        assertEquals(16, s.players[0].slotCount[SLOT_SPARE]);
    }

    @Test
    void aCountedClickCannotBuyItsWayPastTheGraceEither() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        enterGrace(s);
        float before = s.players[1].health;

        Simulation.tick(s, arena, swing().withClicks(new Clicks(7, 7, 7, 7, 0)), Input.NONE);

        assertEquals(before, s.players[1].health);
        assertTrue(s.items.isEmpty());
        assertFalse(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z));
    }

    @Test
    void theHeldStateLatchesStillTrackThroughTheGraceSoNothingFiresOnTheFirstLiveTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        s.roundStartGrace = 1;

        Simulation.tick(s, arena, swing(), Input.NONE);
        assertTrue(s.players[0].prevAttack,
                "the button was down through the grace, so the edge latch has to have moved;"
                        + " otherwise holding attack across the window fires a free swing the"
                        + " tick the grace lifts");

        float before = s.players[1].health;
        Simulation.tick(s, arena, swing(), Input.NONE);
        assertEquals(before, s.players[1].health,
                "a button that never came up is not a new click");
    }

    @Test
    void theGraceCountsDownAndReleasesEveryConsumerTogether() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena);
        enterGrace(s);

        for (int i = 0; i < Simulation.ROUND_START_GRACE; i++) {
            assertTrue(Combat.roundStartLocked(s), "still locked at tick " + i);
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }

        assertEquals(0, s.roundStartGrace);
        assertFalse(Combat.roundStartLocked(s));

        Simulation.tick(s, arena, Input.NONE.withHeldSlot(SLOT_BLOCKS)
                .withBlockAction(Input.BLOCK_PLACE, TARGET_X, TARGET_Y, TARGET_Z), Input.NONE);
        assertTrue(s.blocks.contains(TARGET_X, TARGET_Y, TARGET_Z),
                "the window has to end for every consumer on the same tick");
    }
}
