package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class RoundStartCursorTest {

    @Test
    void aCursorStackIsStillPutBackWhileTheRoundStartIsLocked() {
        Arena arena = Arena.flat(64.0);
        GameState s = new GameState();
        PlayerState p = s.players[0];
        p.x = 0.5;
        p.y = 64.0;
        p.z = 0.5;
        p.onGround = true;
        p.health = 20f;
        s.players[1].x = 10_000.0;
        s.players[1].y = 64.0;
        s.players[1].health = 20f;
        TestKit kit = TestKit.of(s);
        int stone = kit.add(TestKit.item().itemId(4).maxStack(64));
        p.cursorEntry = stone;
        p.cursorCount = 17;
        p.cursorDamage = 0;
        s.roundStartGrace = Simulation.ROUND_START_GRACE;

        assertTrue(Combat.roundStartLocked(s), "this test is only meaningful inside the lock");
        Simulation.tick(s, arena, Input.NONE.withInvAction(Input.INV_CURSOR_RESOLVE, 0, 0),
                Input.NONE);

        assertEquals(ItemDict.NONE, Loadout.cursorEntry(p),
                "closing the inventory names INV_CURSOR_RESOLVE; refusing to honour it during the"
                        + " round-start lock strands the carried stack on the cursor across the"
                        + " start, where no later click can reach it");
        assertEquals(17, Loadout.countAt(p, 0) + Loadout.countAt(p, ItemDict.HOTBAR),
                "the stack has to land back in the inventory, not evaporate");
    }
}
