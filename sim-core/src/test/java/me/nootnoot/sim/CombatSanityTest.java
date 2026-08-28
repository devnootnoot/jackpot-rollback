package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CombatSanityTest {
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

    private static void give(GameState g, int player, int count, TestKit.Item item) {
        TestKit.of(g).give(player, 0, count, item);
    }

    private static Input attack(float yaw, boolean sprint) {
        return new Input(sprint, false, false, false, false, sprint, false, true, false, yaw, 0f, 0)
                .withMeleeHit(true);
    }

    private static Input idle(float yaw) {
        return new Input(false, false, false, false, false, false, false, false, false, yaw, 0f, 0);
    }

    @Test
    void meleeInReachDealsDamageAndKnockback() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        double vStartX = g.players[1].x;

        Simulation.tick(g, arena, attack(-90f, false), idle(90f));

        assertTrue(g.players[1].health < 20f, "victim took no damage");
        assertTrue(g.players[1].vx > 0.0, "victim not knocked back along +X: vx=" + g.players[1].vx);

        assertTrue(g.players[1].vy > 0.0 && g.players[1].vy <= 0.4 + 1e-9,
                "grounded victim vertical knockback out of range: vy=" + g.players[1].vy);

        for (int i = 0; i < 10; i++) {
            Simulation.tick(g, arena, idle(-90f), idle(90f));
        }
        assertTrue(g.players[1].x > vStartX + 0.3, "victim did not travel from knockback");
    }

    @Test
    void iFramesBlockRapidSecondHit() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();

        Simulation.tick(g, arena, attack(-90f, false), idle(90f));
        float afterFirst = g.players[1].health;

        Simulation.tick(g, arena, idle(-90f), idle(90f));
        Simulation.tick(g, arena, attack(-90f, false), idle(90f));
        assertEquals(afterFirst, g.players[1].health, 1e-6, "i-frames did not block the rapid second hit");
    }

    @Test
    void sprintHitKnocksBackHarderThanNormal() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState normal = faceOff();
        Simulation.tick(normal, arena, attack(-90f, false), idle(90f));
        double normalVx = normal.players[1].vx;

        GameState sprint = faceOff();
        Simulation.tick(sprint, arena, attack(-90f, true), idle(90f));
        double sprintVx = sprint.players[1].vx;

        assertTrue(sprintVx > normalVx, "sprint knockback not stronger: normal=" + normalVx + " sprint=" + sprintVx);
    }

    @Test
    void missWhenLookingAway() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();

        assertFalse(Combat.reachHit(g.players[0], 90f, 0f, g.players[1]), "reach decided a hit while looking away");
        Input swing = new Input(false, false, false, false, false, false, false, true, false, 90f, 0f, 0)
                .withMeleeHit(false);
        Simulation.tick(g, arena, swing, idle(90f));
        assertEquals(20f, g.players[1].health, 1e-6, "hit registered while looking away");
    }

    @Test
    void pearlTeleportsThrowerForward() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = new GameState();
        PlayerState a = g.players[0];
        a.x = 0;
        a.y = GROUND_Y;
        a.z = 0;
        a.yaw = -90f;
        a.pitch = 30f;
        a.onGround = true;
        a.health = 20f;
        a.pearls = 16;
        give(g, 0, 16, TestKit.item().useKind(Combat.USE_PEARL));
        g.players[1].x = 500;
        g.players[1].y = GROUND_Y;
        g.players[1].onGround = true;
        g.players[1].health = 20f;

        Input throwPearl = new Input(false, false, false, false, false, false, false, false, true, -90f, 30f, 0);
        Simulation.tick(g, arena, throwPearl, Input.NONE);
        assertEquals(1, g.projectiles.size(), "pearl was not spawned");

        for (int i = 0; i < 100 && !g.projectiles.isEmpty(); i++) {
            Simulation.tick(g, arena, new Input(false, false, false, false, false, false, false, false, false, -90f, 30f, 0), Input.NONE);
        }
        assertTrue(g.projectiles.isEmpty(), "pearl never landed");
        assertTrue(g.players[0].x > 1.0, "thrower was not teleported forward by the pearl: x=" + g.players[0].x);
        assertEquals(GROUND_Y, g.players[0].y, 0.5, "thrower did not land near the floor");
    }

    @Test
    void crossbowChargesThenFiresOnClick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState a = g.players[0];
        a.pitch = 0f;

        int loadTicks = ItemDict.MIN_CROSSBOW_LOAD;
        TestKit kit = TestKit.of(g);
        kit.give(0, 0, 1, TestKit.item().useKind(Combat.USE_CROSSBOW).flags(ItemDict.FLAG_CROSSBOW)
                .ranged(0, ItemDict.MAX_QUICK_CHARGE, 0, false, 0));
        kit.give(0, 1, 10, TestKit.item().itemId(262).flags(ItemDict.FLAG_ARROW_PLAIN));
        Input hold = new Input(false, false, false, false, false, false, false, false, true, -90f, 0f, 0);
        Input release = new Input(false, false, false, false, false, false, false, false, false, -90f, 0f, 0);

        for (int i = 0; i < loadTicks - 1; i++) {
            Simulation.tick(g, arena, hold, Input.NONE);
        }
        assertTrue(!a.slotCrossbowLoaded[0], "crossbow loaded before its pull time elapsed");
        Simulation.tick(g, arena, hold, Input.NONE);
        assertTrue(a.slotCrossbowLoaded[0], "crossbow did not finish loading at its pull time");
        assertEquals(0, g.projectiles.size(), "crossbow fired before a fresh click");

        Simulation.tick(g, arena, release, Input.NONE);
        Simulation.tick(g, arena, hold, Input.NONE);
        assertTrue(!a.slotCrossbowLoaded[0], "crossbow stayed loaded after firing");
        assertEquals(1, g.projectiles.size(), "loaded crossbow did not fire on click");
    }

    @Test
    void backToBackBitesAreSeparatedByAnEmptyHandTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState p = g.players[0];
        p.food = 4.0f;

        give(g, 0, 8, TestKit.item().food(8, 12.8f, 0, false));
        Input eat = new Input(false, false, false, false, false, false, false, false, true, -90f, 0f, 0);

        boolean sawGap = false;
        int bites = 0;
        int prevEatTicks = 0;
        for (int i = 0; i < Combat.EAT_DURATION * 3; i++) {
            Simulation.tick(g, arena, eat, idle(90f));
            if (prevEatTicks > 0 && p.eatTicks == 0) {
                bites++;
                assertFalse(p.eating, "the hand must be empty on the tick a bite completes");
                sawGap = true;
            }
            prevEatTicks = p.eatTicks;
        }
        assertTrue(bites >= 2, "expected at least two bites in three eat durations, got " + bites);
        assertTrue(sawGap, "never observed the inter-bite gap");
    }

    @Test
    void theInterBiteGapIsExactlyOneTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState p = g.players[0];
        p.food = 4.0f;

        give(g, 0, 8, TestKit.item().food(1, 0f, 0, false));
        Input eat = new Input(false, false, false, false, false, false, false, false, true, -90f, 0f, 0);

        for (int i = 0; i < Combat.EAT_DURATION; i++) {
            Simulation.tick(g, arena, eat, idle(90f));
        }
        assertFalse(p.eating, "the bite should have completed on this tick");
        assertEquals(Combat.EAT_BITE_GAP, p.eatGap);

        Simulation.tick(g, arena, eat, idle(90f));
        assertFalse(p.eating, "the gap tick must not start a new bite");
        assertEquals(0, p.eatGap);

        Simulation.tick(g, arena, eat, idle(90f));
        assertTrue(p.eating, "eating should resume one tick after the gap");
        assertEquals(1, p.eatTicks);
    }

    @Test
    void aBlockedHitAppliesBothVanillaKnockbacksNetting0Point15() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState att = g.players[0];
        PlayerState vic = g.players[1];
        vic.yaw = 90f;

        Input block = raiseShield(90f);
        for (int i = 0; i < Combat.SHIELD_WARMUP + 1; i++) {
            Simulation.tick(g, arena, idle(-90f), block);
        }
        assertTrue(Combat.isBlocking(vic));

        att.attackTicker = 100;
        vic.vx = 0.0;
        vic.vz = 0.0;
        Input swing = new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                .withMeleeHit(true);
        Simulation.tick(g, arena, swing, block);

        assertEquals(0.15, vic.vx, 1.0E-9,
                "a blocked hit should net vanilla's 0.15 away, not the bare 0.4");
    }

    private static Input raiseShield(float yaw) {
        return new Input(false, false, false, false, false, false, false, false, true, yaw, 0f, 0);
    }

    @Test
    void shieldBlocksFrontalMelee() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState att = g.players[0];
        PlayerState vic = g.players[1];
        vic.yaw = 90f;
        float before = vic.health;

        Input block = raiseShield(90f);
        for (int i = 0; i < Combat.SHIELD_WARMUP + 1; i++) {
            Simulation.tick(g, arena, idle(-90f), block);
        }
        assertTrue(Combat.isBlocking(vic), "shield not active after the warmup");

        att.attackTicker = 100;
        Input swing = new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                .withMeleeHit(true);
        Simulation.tick(g, arena, swing, block);
        assertEquals(before, vic.health, 0.001f, "shield did not block the frontal melee hit");
    }

    @Test
    void axeDisablesABlockingShield() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState att = g.players[0];
        PlayerState vic = g.players[1];
        vic.yaw = 90f;

        Input block = raiseShield(90f);
        for (int i = 0; i < Combat.SHIELD_WARMUP + 1; i++) {
            Simulation.tick(g, arena, idle(-90f), block);
        }
        att.attackTicker = 100;
        give(g, 0, 1, TestKit.item().melee(9f, 1f).flags(ItemDict.FLAG_AXE));
        Input axeSwing = new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                .withMeleeHit(true);
        Simulation.tick(g, arena, axeSwing, block);
        assertTrue(vic.shieldDisabled > 0, "axe did not disable the shield");
        assertFalse(Combat.isBlocking(vic), "shield still blocking after an axe disable");
    }

    @Test
    void maceWindBurstReTriggersOnEachSmash() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState att = g.players[0];
        PlayerState vic = g.players[1];
        give(g, 0, 1, TestKit.item().melee(6f, 0.6f).mace(true, 1, 0, 0));
        Input swing = new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                .withMeleeHit(true);
        vic.health = 100f;
        vic.maxHealth = 100f;

        att.y = GROUND_Y + 2.0;
        att.onGround = false;
        att.vy = -0.8;
        att.fallDistance = 5.0f;
        att.attackTicker = 100;
        att.prevAttack = false;
        Simulation.tick(g, arena, swing, idle(90f));
        assertTrue(att.vy > 0.5, "first smash did not launch the attacker: vy=" + att.vy);

        att.y = GROUND_Y + 2.0;
        att.onGround = false;
        att.vy = -0.8;
        att.fallDistance = 5.0f;
        att.attackTicker = 100;
        att.prevAttack = false;
        vic.hurtTime = 0;
        Simulation.tick(g, arena, swing, idle(90f));
        assertTrue(att.vy > 0.5, "second smash did not re-launch the attacker: vy=" + att.vy);
    }

    @Test
    void totemSavesFromLethalDamageAndGrantsEffects() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState att = g.players[0];
        PlayerState vic = g.players[1];
        vic.health = 2f;

        give(g, 1, 1, TestKit.item().flags(ItemDict.FLAG_TOTEM));
        Input vicHold = new Input(false, false, false, false, false, false, false, false, false, 90f, 0f, 0);
        give(g, 0, 1, TestKit.item().melee(100f, 1.6f).flags(ItemDict.FLAG_SWORD));
        att.attackTicker = 100;
        Input swing = new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                .withMeleeHit(true);

        Simulation.tick(g, arena, swing, vicHold);
        assertEquals(1, vic.totemSeq, "totem did not pop on lethal damage");
        assertEquals(1.0f, vic.health, 0.001f, "totem did not set health to 1");
        assertFalse(vic.dead, "player died despite holding a totem");
        assertTrue(vic.effectTicks[Effects.REGENERATION] > 0, "totem did not grant Regeneration");
        assertTrue(vic.absorption > 0f, "totem did not grant Absorption");
    }

    @Test
    void bowConsumesArrowUnlessInfinity() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState g = faceOff();
        TestKit kit = TestKit.of(g);
        kit.give(0, 0, 1, TestKit.item().useKind(Combat.USE_BOW).flags(ItemDict.FLAG_BOW));
        kit.give(0, 1, 10, TestKit.item().itemId(262).flags(ItemDict.FLAG_ARROW_PLAIN));
        Input draw = new Input(false, false, false, false, false, false, false, false, true, -90f, 0f, 0);
        Input release = new Input(false, false, false, false, false, false, false, false, false, -90f, 0f, 0);
        for (int i = 0; i < 20; i++) {
            Simulation.tick(g, arena, draw, Input.NONE);
        }
        Simulation.tick(g, arena, release, Input.NONE);
        assertEquals(1, g.players[0].arrowsConsumed, "bow did not consume an arrow on fire");

        GameState g2 = faceOff();
        TestKit kit2 = TestKit.of(g2);
        kit2.give(0, 0, 1, TestKit.item().useKind(Combat.USE_BOW).flags(ItemDict.FLAG_BOW)
                .ranged(0, 0, 0, true, 0));
        kit2.give(0, 1, 10, TestKit.item().itemId(262).flags(ItemDict.FLAG_ARROW_PLAIN));
        Input drawInf = new Input(false, false, false, false, false, false, false, false, true, -90f, 0f, 0);
        Input releaseInf = new Input(false, false, false, false, false, false, false, false, false, -90f, 0f, 0);
        for (int i = 0; i < 20; i++) {
            Simulation.tick(g2, arena, drawInf, Input.NONE);
        }
        Simulation.tick(g2, arena, releaseInf, Input.NONE);
        assertEquals(0, g2.players[0].arrowsConsumed, "Infinity bow should not consume an arrow");
    }

    @Test
    void splashPotionBurstsAndAffectsNearbyPlayers() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState victim = g.players[1];
        float before = victim.health;

        int harm = ItemDict.packEffect(Effects.INSTANT_DAMAGE, 0, 1);
        give(g, 0, 4, TestKit.item().useKind(Combat.USE_SPLASH_POTION).effect(0, harm));
        Input throwIt = new Input(false, false, false, false, false, false, false, false, true, -90f, 80f, 0);
        Simulation.tick(g, arena, throwIt, Input.NONE);
        assertEquals(1, g.projectiles.size(), "splash potion was not thrown");
        assertEquals(1, g.players[0].consumeSeq, "splash potion was not consumed");

        for (int i = 0; i < 80 && !g.projectiles.isEmpty(); i++) {
            Simulation.tick(g, arena, idle(-90f), Input.NONE);
        }
        assertTrue(g.projectiles.isEmpty(), "splash potion never burst");
        assertTrue(victim.health < before, "splash Harming did not damage the nearby victim: " + victim.health);
    }

    @Test
    void fireworkPlacesOnAimedBlockButNotInOpenAir() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = new GameState();
        g.players[0].x = 0;
        g.players[0].y = GROUND_Y;
        g.players[0].z = 0;
        g.players[0].onGround = true;
        give(g, 0, 3, TestKit.item().useKind(Combat.USE_FIREWORK).fireworkFlight(2));
        Input down = new Input(false, false, false, false, false, false, false, false, true, 0f, 89f, 0);
        Simulation.tick(g, arena, down, Input.NONE);
        assertEquals(1, g.projectiles.size(), "firework not placed when aiming at the floor");
        assertEquals(1, g.players[0].consumeSeq, "placed firework should consume one");

        GameState g2 = new GameState();
        g2.players[0].x = 0;
        g2.players[0].y = GROUND_Y;
        g2.players[0].z = 0;
        g2.players[0].onGround = true;
        give(g2, 0, 3, TestKit.item().useKind(Combat.USE_FIREWORK).fireworkFlight(2));
        Input up = new Input(false, false, false, false, false, false, false, false, true, 0f, -89f, 0);
        Simulation.tick(g2, arena, up, Input.NONE);
        assertEquals(0, g2.projectiles.size(), "firework launched while aiming at open air");
        assertEquals(3, g2.players[0].slotCount[0],
                "firework stock must be intact when the launch never happened");
    }

    @Test
    void saturationFastRegensHealthAtFullFood() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = new GameState();
        PlayerState a = g.players[0];
        a.x = 0;
        a.y = GROUND_Y;
        a.z = 0;
        a.onGround = true;
        a.maxHealth = 20f;
        a.health = 10f;
        a.food = 20f;
        a.saturation = 10f;
        a.exhaustion = 0f;
        g.players[1].health = 20f;

        for (int i = 0; i < 12; i++) {
            Simulation.tick(g, arena, idle(0f), Input.NONE);
        }
        assertTrue(a.health > 10f, "saturation did not fast-regen health: " + a.health);
    }

    @Test
    void liveHeldCountConsumesRepeatedlyAndTracksSlot() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = new GameState();
        PlayerState a = g.players[0];
        a.x = 0;
        a.y = GROUND_Y;
        a.z = 0;
        a.onGround = true;
        a.health = 20f;
        a.food = 0f;
        g.players[1].health = 20f;

        give(g, 0, 5, TestKit.item().food(4, 9.6f, 32, true));
        Input eat = new Input(false, false, false, false, false, false, false, false, true, 0f, 0f, 0);

        for (int i = 0; i < Combat.EAT_DURATION; i++) {
            Simulation.tick(g, arena, eat, Input.NONE);
        }
        assertEquals(1, a.consumeSeq, "one bite should consume exactly one item");

        for (int i = 0; i < Combat.EAT_DURATION + Combat.EAT_BITE_GAP; i++) {
            Simulation.tick(g, arena, eat, Input.NONE);
        }
        assertEquals(2, a.consumeSeq, "a second bite should consume a second item");
        assertEquals(0, a.consumeSlot, "consume should be attributed to the held slot");
    }

    @Test
    void aBlockedAxeHitDealsNoDamageAndLeavesTheFollowUpAtFullValue() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState att = g.players[0];
        PlayerState vic = g.players[1];
        vic.yaw = 90f;

        Input block = raiseShield(90f);
        for (int i = 0; i < Combat.SHIELD_WARMUP + 1; i++) {
            Simulation.tick(g, arena, idle(-90f), block);
        }

        float beforeAxe = vic.health;
        att.attackTicker = 100;
        give(g, 0, 1, TestKit.item().melee(9f, 1f).flags(ItemDict.FLAG_AXE));
        Simulation.tick(g, arena,
                new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                        .withMeleeHit(true), block);

        assertEquals(beforeAxe, vic.health, 0f, "a blocked hit must deal no damage at all");
        assertEquals(Combat.I_FRAMES, vic.hurtTime,
                "a block taken outside the window opens one, like vanilla's invulnerableTime = 20");
        assertEquals(0f, vic.lastDamage, 0f, "a blocked hit must leave lastDamage clear");
        assertTrue(vic.shieldDisabled > 0, "the axe should still have disabled the shield");

        att.attackTicker = 100;
        att.prevAttack = false;
        float beforeFollowUp = vic.health;
        give(g, 0, 1, TestKit.item().melee(7f, 1.6f).flags(ItemDict.FLAG_SWORD));
        Simulation.tick(g, arena,
                new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                        .withMeleeHit(true), idle(90f));

        assertTrue(beforeFollowUp - vic.health > 4.0f,
                "the follow-up should land at full value, not reduced by the axe's i-frames: dealt "
                        + (beforeFollowUp - vic.health));
    }

    @Test
    void oneAxeClickCannotBothBlockAndDamage() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState g = faceOff();
        PlayerState att = g.players[0];
        PlayerState vic = g.players[1];
        vic.yaw = 90f;
        vic.health = 100f;
        vic.maxHealth = 100f;

        Input block = raiseShield(90f);
        for (int i = 0; i < Combat.SHIELD_WARMUP + 1; i++) {
            Simulation.tick(g, arena, idle(-90f), block);
        }

        float before = vic.health;
        att.attackTicker = 100;
        give(g, 0, 1, TestKit.item().melee(9f, 1f).flags(ItemDict.FLAG_AXE));
        Simulation.tick(g, arena,
                new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                        .withMeleeHit(true), block);

        assertEquals(before, vic.health, 0f, "a single click both blocked AND dealt damage");
    }
}
