package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ProjectileState;
import org.junit.jupiter.api.Test;

class ProjectileBoundsTest {
    private static final double GROUND_Y = 64.0;
    private static final int SNOWBALL_ITEM_ID = 4101;
    private static final int SNOWBALLS = 16;

    private static GameState empty(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        for (int i = 0; i < s.players.length; i++) {
            s.players[i].x = i * 200.0;
            s.players[i].y = arena.groundY;
            s.players[i].z = 0.0;
            s.players[i].onGround = true;
        }
        return s;
    }

    private static ProjectileState firework(GameState s, double y) {
        return firework(s, y, 0);
    }

    private static ProjectileState firework(GameState s, double y, int owner) {
        ProjectileState p = new ProjectileState();
        p.id = s.nextProjectileId++;
        p.type = ProjectileState.TYPE_FIREWORK;
        p.owner = owner;
        p.x = 0.0;
        p.y = y;
        p.z = 0.0;
        p.vy = 0.5;
        return p;
    }

    @Test
    void aProjectileThatNeverCollidesStillExpires() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = empty(arena);
        Projectiles.spawn(s, firework(s, GROUND_Y + 4.0));

        assertEquals(Projectiles.MAX_LIFE, s.projectiles.get(0).life,
                "a spawn with no life of its own has to take the flight cap; a firework climbs"
                        + " under negative gravity and never touches a solid, so without one it"
                        + " ticks for the whole match");

        for (int t = 0; t <= Projectiles.MAX_LIFE; t++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }

        assertTrue(s.projectiles.isEmpty(),
                "the flight cap has to actually retire it, not just be recorded");
    }

    @Test
    void theFlightCapDoesNotCutAProjectileShortInsideIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = empty(arena);
        Projectiles.spawn(s, firework(s, GROUND_Y + 4.0));

        for (int t = 0; t < Projectiles.MAX_LIFE - 2; t++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }

        assertFalse(s.projectiles.isEmpty(),
                "one tick of slack short of the cap the projectile is still in flight");
    }

    @Test
    void aShorterLifeAlreadyOnTheProjectileIsLeftAlone() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = empty(arena);
        ProjectileState p = firework(s, GROUND_Y + 4.0);
        p.life = 30;
        Projectiles.spawn(s, p);

        assertEquals(30, p.life, "a firework's own fuse is shorter than the cap and owns it");
    }

    @Test
    void aLifeBeyondTheCapIsClampedToIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = empty(arena);
        ProjectileState p = firework(s, GROUND_Y + 4.0);
        p.life = Integer.MAX_VALUE;
        Projectiles.spawn(s, p);

        assertEquals(Projectiles.MAX_LIFE, p.life,
                "a hostile or malformed spawn cannot buy itself an unbounded flight");
    }

    @Test
    void theProjectileListIsCappedByRefusingTheSpawnRatherThanEvictingOne() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = empty(arena);

        for (int i = 0; i < Projectiles.MAX_PROJECTILES * 3; i++) {
            Projectiles.spawn(s, firework(s, GROUND_Y + 4.0));
            assertTrue(s.projectiles.size() <= Projectiles.MAX_PROJECTILES,
                    "the cap must hold on every spawn, not only at the end");
        }

        assertEquals(Projectiles.MAX_PROJECTILES_PER_OWNER, s.projectiles.size());

        int first = s.projectiles.get(0).id;
        int last = s.projectiles.get(s.projectiles.size() - 1).id;
        assertEquals(0, first, "nothing already in flight may be deleted to make room");
        assertEquals(Projectiles.MAX_PROJECTILES_PER_OWNER - 1, last,
                "past the cap the new spawn is refused, so a spammer cannot delete the"
                        + " projectiles the opponent already has in the air");
    }

    @Test
    void aRefusedSpawnSaysSoSoTheCallerCanKeepTheItem() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = empty(arena);

        for (int i = 0; i < Projectiles.MAX_PROJECTILES_PER_OWNER; i++) {
            assertTrue(Projectiles.spawn(s, firework(s, GROUND_Y + 4.0)),
                    "spawn " + i + " is inside the cap and must be accepted");
        }
        assertFalse(Projectiles.spawn(s, firework(s, GROUND_Y + 4.0)),
                "a spawn that never reaches the list has to report it; every caller consumes the"
                        + " item first, so a silent refusal destroys the item outright");
    }

    @Test
    void oneOwnerHoldingTheCapFullCannotDenyTheOpponentASpawn() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = empty(arena);

        for (int i = 0; i < Projectiles.MAX_PROJECTILES * 3; i++) {
            Projectiles.spawn(s, firework(s, GROUND_Y + 4.0, 0));
        }

        assertTrue(Projectiles.hasRoom(s, 1),
                "a shared cap lets a spammer sit on the whole list and starve the opponent;"
                        + " the budget is scoped to the owner, so slot 1 always has its own room");
        assertTrue(Projectiles.spawn(s, firework(s, GROUND_Y + 4.0, 1)));
        assertEquals(Projectiles.MAX_PROJECTILES_PER_OWNER + 1, s.projectiles.size());
    }

    @Test
    void aThrowIsRefusedBeforeTheItemIsSpentWhenTheBudgetIsFull() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        TestKit.of(s).give(0, 0, SNOWBALLS, TestKit.item().itemId(SNOWBALL_ITEM_ID)
                .maxStack(SNOWBALLS).useKind(Combat.USE_SNOWBALL));
        for (int i = 0; i < Projectiles.MAX_PROJECTILES_PER_OWNER; i++) {
            Projectiles.spawn(s, firework(s, GROUND_Y + 4.0, 0));
        }

        Input spam = new Input(false, false, false, false, false, false, false, false, true,
                0f, 0f, 0).withUsePress(true).withClicks(Clicks.NONE.withUse(Clicks.MAX));
        for (int t = 0; t < 20; t++) {
            Simulation.tick(s, arena, spam, Input.NONE);
        }

        assertEquals(SNOWBALLS, s.players[0].slotCount[0],
                "the budget was full, so every throw is refused; spending the snowball first and"
                        + " then refusing the spawn would delete it with nothing thrown");
    }

    @Test
    void thePerOwnerBudgetsCannotOverrunTheWholeListCap() {
        assertTrue(Projectiles.MAX_PROJECTILES_PER_OWNER * 2 <= Projectiles.MAX_PROJECTILES,
                "two players at their own ceiling must still fit inside the list cap, or the"
                        + " per owner budget quietly raises the memory the rollback re-simulates");
    }

    @Test
    void theCapLeavesRoomForEverySpawnAHumanRateCanProduce() {
        assertTrue(Projectiles.MAX_PROJECTILES_PER_OWNER >= 32,
                "a bow shot every 20 ticks with a 200 tick stick life is about 20 arrows per"
                        + " player in the air, so the cap has to clear that with room to spare");
        assertTrue(Projectiles.MAX_LIFE >= 20 * 30,
                "a lobbed pearl or a firework has to be able to finish its arc");
    }
}
