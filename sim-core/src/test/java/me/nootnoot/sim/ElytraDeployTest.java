package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.contract.HostFrameContract;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ElytraDeployTest {
    private static final double GROUND_Y = 64.0;
    private static final int ELYTRA_ITEM_ID = 7301;
    private static final int ROCKET_ITEM_ID = 7302;

    private static GameState airborne() {
        GameState s = new GameState();
        PlayerState p = s.players[0];
        p.x = 0.5;
        p.y = 120.0;
        p.z = 0.5;
        p.onGround = false;
        p.health = 20f;
        PlayerState v = s.players[1];
        v.x = 10_000.0;
        v.y = 120.0;
        v.z = 0.5;
        v.health = 20f;
        return s;
    }

    private static TestKit wearElytra(GameState s) {
        TestKit kit = TestKit.of(s);
        int elytra = kit.add(TestKit.item().itemId(ELYTRA_ITEM_ID).maxStack(1).maxDamage(432)
                .flags(ItemDict.FLAG_ELYTRA).armor(0, 0f, 0f, ItemDict.EQUIP_CHEST));
        kit.put(0, ItemDict.ARMOR_CHEST, elytra, 1);
        return kit;
    }

    private static Input jump(boolean held) {
        return new Input(false, false, false, false, held, false, false, false, false, 0f, 0f, 0);
    }

    @Test
    void aDeployNamedOnTheFrameStartsAGlideWhileTheJumpKeyIsAlreadyHeld() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = new GameState();
        PlayerState p = s.players[0];
        p.x = 0.5;
        p.y = GROUND_Y;
        p.z = 0.5;
        p.onGround = true;
        p.health = 20f;
        s.players[1].x = 10_000.0;
        s.players[1].y = GROUND_Y;
        s.players[1].health = 20f;
        wearElytra(s);

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        for (int i = 0; i < 4; i++) {
            Simulation.tick(s, arena, jump(true), Input.NONE);
        }
        assertFalse(p.onGround, "the jump has to have left the floor");
        assertFalse(p.gliding,
                "space went down on the floor, so there is no rising edge left in the air and the"
                        + " jump bit alone can never open the wings");

        Simulation.tick(s, arena, jump(true).withElytraStart(true), Input.NONE);

        assertTrue(p.gliding,
                "START_FALL_FLYING is its own intent: the elytra must open even when the player"
                        + " never let go of space, which is exactly what happens when the chest"
                        + " plate is swapped for the elytra in mid-air");
    }

    @Test
    void aDeployIntentCannotBeSmuggledInAsAJump() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = new GameState();
        PlayerState p = s.players[0];
        p.x = 0.5;
        p.y = GROUND_Y;
        p.z = 0.5;
        p.onGround = true;
        p.health = 20f;
        s.players[1].x = 10_000.0;
        s.players[1].y = GROUND_Y;
        s.players[1].health = 20f;
        wearElytra(s);

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        double restY = p.y;
        Simulation.tick(s, arena, Input.NONE.withElytraStart(true), Input.NONE);

        assertFalse(p.gliding, "you cannot open the wings standing on the floor");
        assertTrue(p.y <= restY + 1.0E-9,
                "the deploy channel is not the jump key: folding it into the jump bit made the sim"
                        + " launch a player who never pressed space");
    }

    @Test
    void aPinnedDeployBitOnlyCountsOnTheFrameItArrives() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = new GameState();
        PlayerState p = s.players[0];
        p.x = 0.5;
        p.y = GROUND_Y;
        p.z = 0.5;
        p.onGround = true;
        p.health = 20f;
        s.players[1].x = 10_000.0;
        s.players[1].y = GROUND_Y;
        s.players[1].health = 20f;
        wearElytra(s);

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        Input pinned = jump(true).withElytraStart(true);
        for (int i = 0; i < 20; i++) {
            Simulation.tick(s, arena, pinned, Input.NONE);
            assertFalse(p.gliding,
                    "the deploy bit is an event, not a state: a client that never clears it would"
                            + " otherwise hold the deploy condition true for the whole match and"
                            + " open the wings the instant the jump leaves the floor");
        }
    }

    @Test
    void theDeployRuleIsTheSameOnBothHosts() {
        assertTrue(HostFrameContract.elytraDeploy(true, false, false, true, false, 0),
                "airborne, wearing an elytra, jump just pressed: this is vanilla"
                        + " LocalPlayer.aiStep calling tryToStartFallFlying");
        assertFalse(HostFrameContract.elytraDeploy(true, true, false, true, false, 0),
                "vanilla gates on !wasJumping, so a key that was already down is not a deploy");
        assertFalse(HostFrameContract.elytraDeploy(true, false, true, true, false, 0),
                "canGlide() refuses on the ground");
        assertFalse(HostFrameContract.elytraDeploy(true, false, false, false, false, 0),
                "canGlide() refuses with nothing gliding-capable equipped");
        assertFalse(HostFrameContract.elytraDeploy(true, false, false, true, true, 0),
                "tryToStartFallFlying refuses when already fall flying");
        assertFalse(HostFrameContract.elytraDeploy(true, false, false, true, false, 12),
                "the cage drop owns the player until it releases them");
    }

    @Test
    void aRocketBoostsAGlidingPlayerAlongTheirLook() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = airborne();
        TestKit kit = wearElytra(s);
        int rocket = kit.add(TestKit.item().itemId(ROCKET_ITEM_ID).maxStack(64)
                .useKind(Combat.USE_FIREWORK).fireworkFlight(3));
        kit.put(0, 0, rocket, 3);
        PlayerState p = s.players[0];
        p.pitch = -90f;

        Input look = new Input(false, false, false, false, false, false, false, false, false,
                0f, -90f, 0);
        Simulation.tick(s, arena, look, Input.NONE);
        Simulation.tick(s, arena, look.withElytraStart(true), Input.NONE);
        assertTrue(p.gliding, "the glide has to be open before a rocket can mean anything");

        Simulation.tick(s, arena, look, Input.NONE);
        double vyBefore = p.vy;
        Input use = new Input(false, false, false, false, false, false, false, false, true,
                0f, -90f, 0).withUsePress(true);
        Simulation.tick(s, arena, use, Input.NONE);

        assertEquals(2, p.slotCount[0], "the rocket must have been spent");
        assertTrue(p.fireworkTicks > 0, "a rocket used while gliding arms the boost");

        for (int i = 0; i < 3; i++) {
            Simulation.tick(s, arena, look, Input.NONE);
        }

        assertTrue(p.gliding, "the boost may not close the wings");
        assertTrue(p.vy > vyBefore,
                "FireworkRocketEntity pulls a fall-flying holder towards its look vector, so a"
                        + " straight-up rocket must beat gravity");
    }

    @Test
    void anAirbornePlayerWhoPutsTheElytraOnGlidesAndARocketAcceleratesThem() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = new GameState();
        PlayerState p = s.players[0];
        p.x = 0.5;
        p.y = GROUND_Y;
        p.z = 0.5;
        p.onGround = true;
        p.health = 20f;
        s.players[1].x = 10_000.0;
        s.players[1].y = GROUND_Y;
        s.players[1].health = 20f;
        TestKit kit = TestKit.of(s);
        int elytra = kit.add(TestKit.item().itemId(ELYTRA_ITEM_ID).maxStack(1).maxDamage(432)
                .flags(ItemDict.FLAG_ELYTRA).armor(0, 0f, 0f, ItemDict.EQUIP_CHEST));
        int rocket = kit.add(TestKit.item().itemId(ROCKET_ITEM_ID).maxStack(64)
                .useKind(Combat.USE_FIREWORK).fireworkFlight(3));
        kit.put(0, 0, elytra, 1);
        kit.put(0, 1, rocket, 3);

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        Simulation.tick(s, arena, Input.NONE.withInvAction(Input.INV_MOVE, 0, ItemDict.ARMOR_CHEST),
                Input.NONE);
        assertTrue(p.hasElytra,
                "moving the elytra into the worn chest slot is the whole equip path the mod's"
                        + " right-click hot-swap and a shift-click both compile down to");

        Simulation.tick(s, arena, jump(true), Input.NONE);
        Simulation.tick(s, arena, jump(true), Input.NONE);
        Simulation.tick(s, arena, jump(false), Input.NONE);
        assertFalse(p.onGround, "the jump has to have left the floor before the wings can open");

        Simulation.tick(s, arena, jump(true), Input.NONE);
        assertTrue(p.gliding,
                "a jump pressed fresh while airborne with a glider on the chest is vanilla"
                        + " LocalPlayer.aiStep -> tryToStartFallFlying");

        Input hold = new Input(false, false, false, false, false, false, false, false, false,
                0f, -90f, 1);
        for (int i = 0; i < 3; i++) {
            Simulation.tick(s, arena, hold, Input.NONE);
        }
        double vyBefore = p.vy;
        Simulation.tick(s, arena, new Input(false, false, false, false, false, false, false, false,
                true, 0f, -90f, 1).withUsePress(true), Input.NONE);
        assertEquals(2, p.slotCount[1], "the rocket must have been spent");
        assertTrue(p.fireworkTicks > 0, "a rocket used while gliding arms the boost");
        for (int i = 0; i < 4; i++) {
            Simulation.tick(s, arena, hold, Input.NONE);
        }
        assertTrue(p.gliding, "the boost may not close the wings");
        assertTrue(p.vy > vyBefore,
                "FireworkRocketEntity pulls a fall-flying holder along its look vector, so a"
                        + " straight-up rocket must beat gravity");
    }

    @Test
    void aRocketCannotOpenTheWingsWithoutADeployEdge() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = airborne();
        TestKit kit = wearElytra(s);
        int rocket = kit.add(TestKit.item().itemId(ROCKET_ITEM_ID).maxStack(64)
                .useKind(Combat.USE_FIREWORK).fireworkFlight(3));
        kit.put(0, 0, rocket, 3);
        PlayerState p = s.players[0];

        Input look = new Input(false, false, false, false, false, false, false, false, false,
                0f, -90f, 0);
        Simulation.tick(s, arena, look, Input.NONE);
        assertFalse(p.gliding, "nothing has opened the wings yet");
        assertTrue(p.hasElytra, "the elytra is worn - that is the whole point of the case");

        Input use = new Input(false, false, false, false, false, false, false, false, true,
                0f, -90f, 0).withUsePress(true);
        for (int i = 0; i < 10; i++) {
            Simulation.tick(s, arena, use, Input.NONE);
            assertFalse(p.gliding,
                    "vanilla FireworkRocketItem.use only boosts a player who isFallFlying; a rocket"
                            + " is not a deploy, so right-clicking one while merely airborne must"
                            + " never open the wings");
            assertEquals(0, p.fireworkTicks,
                    "no glide means no boost timer - the rocket is a thrown entity, not a push");
        }
    }

    @Test
    void anElytraOneHitFromBreakingCannotOpen() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = airborne();
        wearElytra(s);
        PlayerState p = s.players[0];
        p.slotDamage[ItemDict.ARMOR_CHEST] = 431;
        Loadout.recomputeDerived(s, p);
        assertFalse(p.hasElytra,
                "vanilla canGlideUsing refuses a stack whose nextDamageWillBreak is true");

        Simulation.tick(s, arena, jump(false), Input.NONE);
        Simulation.tick(s, arena, jump(true), Input.NONE);
        assertFalse(p.gliding, "a glider one hit from breaking is not a glider");

        p.slotDamage[ItemDict.ARMOR_CHEST] = 430;
        Loadout.recomputeDerived(s, p);
        assertTrue(p.hasElytra, "two hits of durability left is still a working glider");
        Simulation.tick(s, arena, jump(false), Input.NONE);
        Simulation.tick(s, arena, jump(true), Input.NONE);
        assertTrue(p.gliding,
                "the ONLY thing separating these two runs is one point of chest-slot damage - a"
                        + " host that snapshots a temporarily sabotaged durability into the slot"
                        + " table disables gliding for the whole match with no other symptom");
    }
}
