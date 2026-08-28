package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ShieldImmunityWindowTest {
    private static final double GROUND_Y = 64.0;

    private static GameState faceOff() {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        a.x = 0;
        a.y = GROUND_Y;
        a.z = 0;
        a.yaw = -90f;
        a.onGround = true;
        a.vy = -0.0784;
        a.health = 20f;
        a.attackTicker = 100;

        PlayerState v = g.players[1];
        v.x = 2.0;
        v.y = GROUND_Y;
        v.z = 0;
        v.yaw = 90f;
        v.onGround = true;
        v.vy = -0.0784;
        v.health = 20f;
        v.attackTicker = 100;
        TestKit kit = TestKit.of(g);
        kit.give(0, 0, 1, TestKit.item().melee(6f, 1.6f).flags(ItemDict.FLAG_SWORD));
        kit.give(1, 0, 1, TestKit.item().useKind(Combat.USE_SHIELD).flags(ItemDict.FLAG_SHIELD));
        return g;
    }

    private static Input idle(float yaw) {
        return new Input(false, false, false, false, false, false, false, false, false, yaw, 0f, 0);
    }

    private static Input raiseShield(float yaw) {
        return new Input(false, false, false, false, false, false, false, false, true, yaw, 0f, 0);
    }

    private static Input swing(float yaw) {
        return new Input(false, false, false, false, false, false, false, true, false, yaw, 0f, 0)
                .withMeleeHit(true);
    }

    private static void warmUpShield(GameState g, Arena arena) {
        Input block = raiseShield(90f);
        for (int i = 0; i < Combat.SHIELD_WARMUP + 1; i++) {
            Simulation.tick(g, arena, idle(-90f), block);
        }
        assertTrue(Combat.isBlocking(g.players[1]), "shield not active after the warmup");
    }

    @Test
    void aBlockInsideTheImmunityWindowLeavesTheAccumulatorAlone() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState att = g.players[0];
        PlayerState vic = g.players[1];
        warmUpShield(g, arena);

        vic.hurtTime = 6;
        vic.lastDamage = 8.0f;
        float before = vic.health;
        att.attackTicker = 100;
        att.prevAttack = false;

        Simulation.tick(g, arena, swing(-90f), raiseShield(90f));

        assertEquals(before, vic.health, 0f, "a blocked hit must deal no damage");
        assertEquals(8.0f, vic.lastDamage, 0f,
                "a block taken inside the immunity window must not clear lastDamage");
        assertEquals(5, vic.hurtTime,
                "a block inside the window must not restart it, only let it run down");
    }

    @Test
    void aBlockInsideTheWindowDoesNotHandTheNextHitFullDamage() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState att = g.players[0];
        PlayerState vic = g.players[1];
        warmUpShield(g, arena);

        vic.hurtTime = 9;
        vic.lastDamage = 8.0f;
        att.attackTicker = 100;
        att.prevAttack = false;
        Simulation.tick(g, arena, swing(-90f), raiseShield(90f));

        Simulation.tick(g, arena, idle(-90f), idle(90f));

        float before = vic.health;
        att.attackTicker = 100;
        att.prevAttack = false;
        Simulation.tick(g, arena, swing(-90f), idle(90f));

        assertTrue(vic.hurtTime > 0, "the window should still be open for the follow-up");
        assertEquals(before, vic.health, 0f,
                "a 6-damage hit inside a window that already absorbed 8 must add nothing");
    }

    @Test
    void aBlockOutsideTheWindowOpensOneAndClearsTheAccumulator() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState att = g.players[0];
        PlayerState vic = g.players[1];
        warmUpShield(g, arena);

        vic.hurtTime = 0;
        vic.lastDamage = 8.0f;
        float before = vic.health;
        att.attackTicker = 100;
        att.prevAttack = false;

        Simulation.tick(g, arena, swing(-90f), raiseShield(90f));

        assertEquals(before, vic.health, 0f, "a blocked hit must deal no damage");
        assertEquals(0f, vic.lastDamage, 0f,
                "a block outside the window is vanilla's lastHurt = 0 branch");
        assertEquals(Combat.I_FRAMES, vic.hurtTime,
                "a block outside the window opens one, like vanilla's invulnerableTime = 20");
    }
}
