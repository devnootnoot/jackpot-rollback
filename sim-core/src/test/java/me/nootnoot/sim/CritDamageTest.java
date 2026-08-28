package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CritDamageTest {

    private static final double GROUND_Y = 64.0;
    private static final float SWORD_DAMAGE = 7f;
    private static final float SWORD_SPEED = 1.6f;
    private static final int DESCENDING_TICK = 6;


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
        TestKit.of(g).give(0, 0, 1, TestKit.item().melee(SWORD_DAMAGE, SWORD_SPEED)
                .flags(me.nootnoot.sim.state.ItemDict.FLAG_SWORD));

        PlayerState v = g.players[1];
        v.x = 2.0;
        v.y = GROUND_Y;
        v.z = 0;
        v.yaw = 90f;
        v.onGround = true;
        v.vy = -0.0784;
        v.health = 20f;
        v.attackTicker = 100;
        return g;
    }

    private static Input stamped(boolean forward, boolean sprint, boolean jump, boolean attack,
                                 double y, boolean onGround) {
        return new Input(forward, false, false, false, jump, sprint, false, attack, false,
                -90f, 0f, 0)
                .withMeleeHit(attack)
                .withAuthority(Authority.at(0.0, y, 0.0, onGround));
    }

    private static Input victimIdle() {
        return new Input(false, false, false, false, false, false, false, false, false, 90f, 0f, 0)
                .withAuthority(Authority.at(2.0, GROUND_Y, 0.0, true));
    }

    private static float jumpAttackDamage(boolean sprintFlag, boolean forward) {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        double y = GROUND_Y;
        double clientVy = 0.0;
        boolean onGround = true;
        for (int t = 0; t <= DESCENDING_TICK; t++) {
            if (t == 0) {
                clientVy = Simulation.JUMP_VELOCITY;
                onGround = false;
            } else {
                clientVy = (clientVy - Simulation.GRAVITY) * Simulation.VERTICAL_DRAG;
            }
            double next = y + clientVy;
            if (next <= GROUND_Y) {
                next = GROUND_Y;
                clientVy = 0.0;
                onGround = true;
            }
            boolean attack = t == DESCENDING_TICK;
            float before = g.players[1].health;
            Simulation.tick(g, arena, stamped(forward, sprintFlag, t == 0, attack, next, onGround),
                    victimIdle());
            if (attack) {
                assertTrue(g.players[0].vy < 0.0, "the attacker should be descending on the hit tick");
                return before - g.players[1].health;
            }
            y = next;
        }
        return -1f;
    }

    @Test
    void aJumpAttackCritsWhileTheSprintKeyIsStillHeld() {
        float damage = jumpAttackDamage(true, false);
        assertEquals(SWORD_DAMAGE * Combat.CRIT_MULTIPLIER, damage, 1.0E-4,
                "a descending hit with no forward impulse is a crit: holding the sprint key must"
                        + " not keep the attacker in the sim's sprinting state");
    }

    @Test
    void aJumpAttackCritsWithNoSprintFlagAtAll() {
        assertEquals(SWORD_DAMAGE * Combat.CRIT_MULTIPLIER, jumpAttackDamage(false, false), 1.0E-4);
    }

    @Test
    void aSprintJumpAttackDoesNotCrit() {
        assertEquals(SWORD_DAMAGE, jumpAttackDamage(true, true), 1.0E-4,
                "vanilla refuses the crit while the attacker is actually sprinting");
    }

    @Test
    void aGroundedAttackDoesNotCrit() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        float before = g.players[1].health;
        Simulation.tick(g, arena, stamped(false, false, false, true, GROUND_Y, true), victimIdle());
        assertEquals(SWORD_DAMAGE, before - g.players[1].health, 1.0E-4);
    }

    @Test
    void theCritIsWorthMoreThanTheSameHitOnTheGround() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        float before = g.players[1].health;
        Simulation.tick(g, arena, stamped(false, false, false, true, GROUND_Y, true), victimIdle());
        float grounded = before - g.players[1].health;
        assertTrue(jumpAttackDamage(true, false) > grounded + 1.0E-4,
                "the crit must actually take more health off");
    }

    @Test
    void authorityStampedFallingAccumulatesFallDistance() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        double y = GROUND_Y + 10.0;
        g.players[0].y = y;
        g.players[0].onGround = false;
        double clientVy = 0.0;
        for (int t = 0; t < 40; t++) {
            clientVy = (clientVy - Simulation.GRAVITY) * Simulation.VERTICAL_DRAG;
            double next = y + clientVy;
            boolean onGround = next <= GROUND_Y;
            if (onGround) {
                next = GROUND_Y;
            }
            Simulation.tick(g, arena, stamped(false, false, false, false, next, onGround),
                    victimIdle());
            if (onGround) {
                break;
            }
            assertTrue(g.players[0].fallDistance > 0.0f,
                    "fallDistance must build up while the stamped position is dropping");
            y = next;
        }
        assertTrue(g.players[0].health < 20f,
                "a ten block drop must cost health once fall state is tracked");
    }

    @Test
    void theCageFallGraceStillSuppressesFallDamage() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        double y = GROUND_Y + 10.0;
        g.players[0].y = y;
        g.players[0].onGround = false;
        g.players[0].noFallTicks = Simulation.CAGE_FALL_GRACE;
        double clientVy = 0.0;
        for (int t = 0; t < 40; t++) {
            clientVy = (clientVy - Simulation.GRAVITY) * Simulation.VERTICAL_DRAG;
            double next = y + clientVy;
            boolean onGround = next <= GROUND_Y;
            if (onGround) {
                next = GROUND_Y;
            }
            Simulation.tick(g, arena, stamped(false, false, false, false, next, onGround),
                    victimIdle());
            if (onGround) {
                break;
            }
            y = next;
        }
        assertEquals(20f, g.players[0].health, 1.0E-4,
                "the round-start grace must still absorb the drop out of the cage");
    }
}
