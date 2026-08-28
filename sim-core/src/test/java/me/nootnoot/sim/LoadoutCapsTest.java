package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class LoadoutCapsTest {
    private static GameState twoPlayerState() {
        GameState s = new GameState();
        for (PlayerState p : s.players) {
            p.y = 0;
        }
        return s;
    }

    @Test
    void anAbsurdWeaponIsClampedInTheDictionaryItself() {
        GameState s = twoPlayerState();
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1, TestKit.item().melee(10_000f, 100f).knockback(99)
                .weapon(31, 7, 7, 1).mace(true, 7, 15, 15));

        assertEquals(ItemDict.MAX_ATTACK_DAMAGE, s.dict.meleeDamage(1), 0f);
        assertEquals(ItemDict.MAX_ATTACK_SPEED, s.dict.meleeSpeed(1), 0f);
        assertEquals(ItemDict.MAX_KNOCKBACK, s.dict.knockback(1));
        assertEquals(ItemDict.MAX_SHARPNESS, s.dict.sharpness(1));
        assertEquals(ItemDict.MAX_FIRE_ASPECT, s.dict.fireAspect(1));
        assertEquals(ItemDict.MAX_BREACH, s.dict.breach(1));
        assertEquals(ItemDict.MAX_DENSITY, s.dict.density(1));
        assertEquals(ItemDict.MAX_WIND_BURST, s.dict.windBurst(1));
    }

    @Test
    void oneStrongKitDoesNotRaiseTheOtherPlayersCeiling() {
        GameState s = twoPlayerState();
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1, TestKit.item().melee(9.5f, 1.6f));
        kit.give(1, 0, 1, TestKit.item().melee(6.0f, 1.6f));
        LoadoutCaps.seal(s);

        assertEquals(9.5f, s.players[0].attackDamage, 0f);
        assertEquals(6.0f, s.players[1].attackDamage, 0f);
    }

    @Test
    void totalKnockbackImmunityIsImpossible() {
        GameState s = twoPlayerState();
        TestKit kit = TestKit.of(s);
        for (int slot = ItemDict.ARMOR_FEET; slot <= ItemDict.ARMOR_HEAD; slot++) {
            kit.give(0, slot, 1, TestKit.item()
                    .armor(64, 64f, 1.0f, slot - ItemDict.ARMOR_FEET + 1)
                    .armorEnchants(64, 64, 64, 64, 64));
        }
        LoadoutCaps.seal(s);

        PlayerState p = s.players[0];
        assertTrue(p.kbResistance <= ItemDict.MAX_KB_RESISTANCE_TOTAL);
        assertEquals(4 * ItemDict.MAX_ARMOR_POINTS_PER_PIECE, p.armor, 0f);
        assertEquals(ItemDict.MAX_EPF_LEVEL, p.protection, 0f);
        assertEquals(ItemDict.MAX_EPF_LEVEL, p.blastProtection);
        assertEquals(ItemDict.MAX_FEATHER_FALLING, p.featherFalling);
    }

    @Test
    void aPlayerWithNoArmourClaimsNoProtectionFloor() {
        GameState s = twoPlayerState();
        LoadoutCaps.seal(s);

        assertEquals(0f, s.players[0].armor, 0f);
        assertEquals(0f, s.players[0].protection, 0f);
        assertEquals(0, s.players[0].projectileProtection);
        assertEquals(0, s.players[0].fireProtection);
        assertEquals(0, s.players[0].featherFalling);
    }

    @Test
    void theSimulationResolvesStatsFromTheTableEveryTick() {
        GameState s = twoPlayerState();
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1, TestKit.item().melee(6f, 1.6f));
        LoadoutCaps.seal(s);

        me.nootnoot.sim.state.Arena arena = me.nootnoot.sim.state.Arena.flat(0);
        s.players[0].attackDamage = 10_000f;
        s.players[0].armor = 500f;
        Simulation.tick(s, arena, Input.NONE, Input.NONE);

        assertEquals(6f, s.players[0].attackDamage, 0f);
        assertEquals(1.6f, s.players[0].attackSpeed, 0f);
        assertEquals(0f, s.players[0].armor, 0f);
    }

    @Test
    void anEffectAmplifierCannotOverflowTheShift() {
        GameState s = twoPlayerState();
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1, TestKit.item()
                .effect(0, ItemDict.packEffect(me.nootnoot.sim.state.Effects.SPEED, 200, 60_000)));

        int packed = s.dict.effect(1, 0);
        assertEquals(ItemDict.MAX_EFFECT_AMPLIFIER, ItemDict.effectAmplifier(packed));
        assertTrue(ItemDict.effectDuration(packed) <= ItemDict.MAX_EFFECT_DURATION);
    }
}
