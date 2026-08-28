package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class MissPenaltyTest {
    private static final double GROUND_Y = 64.0;

    private static final int SOFT_ITEM_ID = 6300;

    private static final int SOFT_X = 2;
    private static final int SOFT_Y = 64;
    private static final int SOFT_Z = 0;

    private static final String VANILLA =
            "Minecraft.startAttack: the MISS arm of the hitResult switch runs"
                    + " if (this.gameMode.hasMissTime()) { this.missTime = 10; } and every later"
                    + " startAttack returns false while this.missTime > 0, so a whiffed left click"
                    + " suppresses the next ten ticks of attacks. Minecraft.tick decrements it once"
                    + " a tick and Minecraft.continueAttack zeroes it the moment the button comes"
                    + " up (if (!down) this.missTime = 0)";

    private static GameState faceOff(double victimX) {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.y = GROUND_Y;
        a.z = 0.5;
        a.yaw = -90f;
        a.onGround = true;
        a.vy = -0.0784;
        a.health = 20f;
        a.attackTicker = 100;

        PlayerState v = s.players[1];
        v.x = victimX;
        v.y = GROUND_Y;
        v.z = 0.5;
        v.yaw = 90f;
        v.onGround = true;
        v.vy = -0.0784;
        v.health = 20f;
        v.maxHealth = 20f;
        v.attackTicker = 100;
        TestKit.of(s).give(0, 0, 1, TestKit.item().melee(6f, 1.6f).flags(ItemDict.FLAG_SWORD));
        return s;
    }

    private static Input left(boolean held, boolean meleeHit, int clicks) {
        Input in = new Input(false, false, false, false, false, false, false, held, false,
                -90f, 0f, 0).withMeleeHit(meleeHit);
        return clicks > 0 ? in.withClicks(new Clicks(clicks, 0, 0, 0, 0)) : in;
    }

    private static int swings(GameState s) {
        int n = 0;
        for (CombatEvent e : s.events) {
            if (e.type() == CombatEvent.SWING && e.attacker() == 0
                    && e.kind() == CombatEvent.HIT_WEAK) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aWhiffInsideOneTickEatsTheRestOfThatTicksClicks() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);

        Simulation.tick(s, arena, left(true, false, 3), Input.NONE);

        assertEquals(1, swings(s),
                "three left clicks arrived in one sample and the FIRST one named no target, so"
                        + " the two behind it hit a live missTime and never reached the swing."
                        + " " + VANILLA);
        assertEquals(Combat.MISS_PENALTY_TICKS - 1, s.players[0].missTicks,
                "the whiff arms ten ticks and the tick it was armed on spends one of them");
    }

    @Test
    void aSecondPressWithTheButtonStillDownIsPunished() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);

        Simulation.tick(s, arena, left(true, false, 1), Input.NONE);
        assertTrue(s.players[0].missTicks > 0, "the whiff armed the penalty");

        Simulation.tick(s, arena, left(true, true, 1), Input.NONE);

        assertEquals(20f, s.players[1].health, 0f,
                "the press count and the held bit are both numbers the CLIENT chooses, so a frame"
                        + " that clears the penalty by asserting a press is a frame that repeals"
                        + " the penalty it is supposed to be subject to: whiff, assert a press,"
                        + " swing, whiff again, forever. Nothing in vanilla clears missTime on a"
                        + " press - startAttack only READS it. " + VANILLA);
        assertTrue(s.players[0].missTicks > 0, "and the penalty is still running");
    }

    @Test
    void theButtonComingUpNoLongerPaysTheWhiffEarly() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.0);

        Simulation.tick(s, arena, left(true, false, 1), Input.NONE);
        assertEquals(Combat.MISS_PENALTY_TICKS - 1, s.players[0].missTicks,
                "the whiff armed the ten and the tick it was armed on spent one");

        Simulation.tick(s, arena, left(false, false, 0), Input.NONE);

        assertEquals(Combat.MISS_PENALTY_TICKS - 2, s.players[0].missTicks,
                "vanilla keys its early clear on the attack key not being down, and the attack bit"
                        + " is authored by the client every frame, so withholding it for a single"
                        + " frame repealed the whole penalty. Nothing the sim can observe stands in"
                        + " for a real button coming up, so the early clear is gone and only the"
                        + " once-a-tick decrement Minecraft.tick already ran unconditionally is"
                        + " left. " + VANILLA);

        Simulation.tick(s, arena, left(true, true, 1), Input.NONE);

        assertEquals(20f, s.players[1].health, 0f,
                "so the press straight after the release buys nothing it did not buy before");

        for (int i = 0; i < Combat.MISS_PENALTY_TICKS; i++) {
            Simulation.tick(s, arena, left(false, false, 0), Input.NONE);
        }

        assertEquals(0, s.players[0].missTicks,
                "the ten tick themselves out whether or not the client ever asserts attack again,"
                        + " so a whiff costs the same ten ticks to everybody and withholding the"
                        + " bit gains nothing");

        Simulation.tick(s, arena, left(true, true, 1), Input.NONE);

        assertTrue(s.players[1].health < 20f,
                "and once they are paid the next press lands");
    }

    @Test
    void namingATargetNeverArmsThePenalty() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState hit = faceOff(2.0);
        Simulation.tick(hit, arena, left(true, true, 1), Input.NONE);
        assertEquals(0, hit.players[0].missTicks,
                "vanilla arms missTime on the MISS arm of the hitResult switch only, so an ENTITY"
                        + " hit leaves it at zero");

        GameState dig = diggingScene(arena);
        Input onBlock = new Input(false, false, false, false, false, false, false, true, false,
                -90f, 0f, 0).withBlockAction(Input.BLOCK_BREAK, SOFT_X, SOFT_Y, SOFT_Z)
                .withClicks(new Clicks(1, 0, 0, 0, 0));
        Simulation.tick(dig, arena, onBlock, Input.NONE);
        assertEquals(0, dig.players[0].missTicks,
                "and neither does the BLOCK arm: a frame that names a cell is a frame whose"
                        + " crosshair was on a block, which is the one thing a MISS is not");
    }

    private static GameState diggingScene(Arena arena) {
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = arena.groundY;
        a.z = 0.5;
        a.yaw = -90f;
        a.pitch = 0f;
        a.onGround = true;
        s.players[1].x = 40.0;
        s.blockProps = new BlockProps.Builder()
                .add(SOFT_ITEM_ID, 0f, 6f, SOFT_ITEM_ID, -1, ItemDict.TOOL_NONE, false)
                .build();
        s.blocks.place(SOFT_X, SOFT_Y, SOFT_Z, SOFT_ITEM_ID);
        return s;
    }

    @Test
    void aWhiffStopsTheHeldDigForTheWholeTenTicks() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = diggingScene(arena);

        Simulation.tick(s, arena, left(true, false, 1), Input.NONE);
        assertEquals(Combat.MISS_PENALTY_TICKS - 1, s.players[0].missTicks,
                "the whiff armed the ten and the tick it was armed on spent one");

        Input hold = new Input(false, false, false, false, false, false, false, true, false,
                -90f, 0f, 0).withBlockAction(Input.BLOCK_BREAK, SOFT_X, SOFT_Y, SOFT_Z);
        for (int i = 1; i < Combat.MISS_PENALTY_TICKS; i++) {
            Simulation.tick(s, arena, hold, Input.NONE);
            assertTrue(s.blocks.contains(SOFT_X, SOFT_Y, SOFT_Z),
                    "Minecraft.continueAttack only reaches gameMode.continueDestroyBlock inside"
                            + " if (this.missTime <= 0 && !this.player.isUsingItem()), and a HELD"
                            + " button raises no fresh press to zero it with. Swinging at air and"
                            + " then holding on a block mines nothing until the ten are paid."
                            + " tick " + i + ". " + VANILLA);
        }

        Simulation.tick(s, arena, hold, Input.NONE);

        assertFalse(s.blocks.contains(SOFT_X, SOFT_Y, SOFT_Z),
                "and the tenth tick after the whiff is the one the dig finally runs on");
    }
}
