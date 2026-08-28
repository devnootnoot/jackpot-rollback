package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class AttackRangeGateTest {
    private static final double GROUND_Y = 64.0;

    private static final double LANE_Z = 0.5;

    private static final int SWORD_ITEM_ID = 7100;
    private static final int SPEAR_ITEM_ID = 7101;

    private static GameState faceOff(double victimX, int weaponFlags) {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.y = GROUND_Y;
        a.z = LANE_Z;
        a.yaw = -90f;
        a.onGround = true;
        a.health = 20f;
        a.attackTicker = 100;

        PlayerState v = s.players[1];
        v.x = victimX;
        v.y = GROUND_Y;
        v.z = LANE_Z;
        v.yaw = 90f;
        v.onGround = true;
        v.health = 20f;
        v.attackTicker = 100;

        boolean spear = (weaponFlags & ItemDict.FLAG_SPEAR) != 0;
        TestKit.of(s).give(0, 0, 1, TestKit.item()
                .itemId(spear ? SPEAR_ITEM_ID : SWORD_ITEM_ID)
                .maxStack(1)
                .flags(weaponFlags));
        return s;
    }

    private static Input claimedHit() {
        return new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                .withMeleeHit(true);
    }

    private static boolean swingLands(GameState s, Arena arena) {
        float before = s.players[1].health;
        Simulation.tick(s, arena, claimedHit(), Input.NONE);
        return s.players[1].health < before;
    }

    private static double eyeToHull(GameState s) {
        PlayerState a = s.players[0];
        PlayerState v = s.players[1];
        return v.x - Simulation.PLAYER_WIDTH / 2.0 - a.x;
    }

    @Test
    void aSwordReachesVanillasSixBlocksAndNoFurther() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState inside = faceOff(5.0, ItemDict.FLAG_SWORD);
        assertTrue(eyeToHull(inside) < Combat.attackReachLimit(inside, inside.players[0]),
                "this stance has to sit inside the 3.0 + 0.0 + 3.0 handleAttack bound");
        assertTrue(swingLands(inside, arena),
                "vanilla lands this hit, so refusing it would refuse a legal swing");

        GameState outside = faceOff(7.0, ItemDict.FLAG_SWORD);
        assertTrue(eyeToHull(outside) > Combat.attackReachLimit(outside, outside.players[0]),
                "and this one outside it");
        assertFalse(swingLands(outside, arena),
                "a sword carries no ATTACK_RANGE component, so 6.0 is the whole of its reach");
    }

    @Test
    void aSpearReachesFurtherThanASwordBecauseItsEntryCarriesTheRange() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState spear = faceOff(7.0, ItemDict.FLAG_SPEAR);
        assertEquals(7.625, Combat.attackReachLimit(spear, spear.players[0]), 1.0E-9,
                "AttackRange(2.0, 4.5, 2.0, 6.5, 0.125, 0.5) is 4.5 + 0.125 + the 3.0 buffer");
        assertTrue(swingLands(spear, arena),
                "the same stance a sword cannot reach is inside a spear's reach");

        GameState tooFar = faceOff(8.5, ItemDict.FLAG_SPEAR);
        assertTrue(eyeToHull(tooFar) > Combat.attackReachLimit(tooFar, tooFar.players[0]),
                "this stance has to sit outside even the spear bound");
        assertFalse(swingLands(tooFar, arena),
                "a longer component is still a bound, not an exemption");
    }

    @Test
    void theReachIsReadOffTheHeldEntryAndNotOffTheAttacker() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState s = faceOff(7.0, ItemDict.FLAG_SPEAR);
        assertTrue(swingLands(s, arena), "the spear in hand reaches this far");

        GameState swapped = faceOff(7.0, ItemDict.FLAG_SPEAR);
        swapped.players[0].slotCount[0] = 0;
        Loadout.recomputeDerived(swapped, swapped.players[0]);
        assertEquals(ItemDict.DEFAULT_ATTACK_RANGE,
                Loadout.attackMaxRange(swapped, swapped.players[0]), 0.0,
                "an empty hand falls back to the AttackRange.defaultFor maxReach");
        assertFalse(swingLands(swapped, arena),
                "dropping the spear has to drop the reach with it on the very same tick");
    }
}
