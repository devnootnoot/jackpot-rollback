package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class WeaponWearTest {
    private static final double GROUND_Y = 64.0;
    private static final int WEAPON_MAX_DAMAGE = 250;
    private static final int WEAPON_ITEM_ID = 8001;

    private static GameState faceOff() {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        a.x = 0.0;
        a.y = GROUND_Y;
        a.z = 0.0;
        a.yaw = -90f;
        a.onGround = true;
        a.health = 20f;
        a.attackTicker = 100;

        PlayerState v = g.players[1];
        v.x = 2.0;
        v.y = GROUND_Y;
        v.z = 0.0;
        v.yaw = 90f;
        v.onGround = true;
        v.health = 20f;
        v.attackTicker = 100;
        return g;
    }

    private static Input swing() {
        return new Input(false, false, false, false, false, false, false, true, false,
                -90f, 0f, 0).withMeleeHit(true);
    }

    private static void giveWeapon(GameState s, int flags, int toolClass) {
        TestKit.of(s).give(0, 0, 1, TestKit.item().itemId(WEAPON_ITEM_ID).maxStack(1)
                .maxDamage(WEAPON_MAX_DAMAGE).melee(7f, 1.6f).flags(flags)
                .tool(Loadout.TIER_DIAMOND, 0, toolClass, false));
    }

    @Test
    void aLandedSwordHitWearsTheWeapon() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff();
        giveWeapon(s, ItemDict.FLAG_SWORD, ItemDict.TOOL_SWORD);

        Combat.resolve(s, arena, swing(), Input.NONE);

        assertTrue(s.players[1].health < 20f, "the hit must have landed");
        assertEquals(Combat.WEAPON_HIT_WEAR, s.players[0].slotDamage[0],
                "a landed hit must wear the weapon it was swung with");
    }

    @Test
    void aLandedAxeHitWearsTwice() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff();
        giveWeapon(s, ItemDict.FLAG_AXE, ItemDict.TOOL_AXE);

        Combat.resolve(s, arena, swing(), Input.NONE);

        assertTrue(s.players[1].health < 20f, "the hit must have landed");
        assertEquals(Combat.DIGGER_HIT_WEAR, s.players[0].slotDamage[0],
                "an axe must take twice the wear of a sword");
    }

    @Test
    void everyDiggerWearsTwicePerHitAndOnlySwordsWearOnce() {
        int[] diggers = {ItemDict.TOOL_PICKAXE, ItemDict.TOOL_AXE, ItemDict.TOOL_SHOVEL,
                ItemDict.TOOL_HOE};
        for (int toolClass : diggers) {
            Arena arena = Arena.flat(GROUND_Y);
            GameState s = faceOff();
            giveWeapon(s, 0, toolClass);

            Combat.resolve(s, arena, swing(), Input.NONE);

            assertTrue(s.players[1].health < 20f, "the hit must have landed for tool class " + toolClass);
            assertEquals(Combat.DIGGER_HIT_WEAR, s.players[0].slotDamage[0],
                    "vanilla charges two durability per attack to the whole digger family,"
                            + " tool class " + toolClass + " included");
        }

        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff();
        giveWeapon(s, ItemDict.FLAG_SWORD, ItemDict.TOOL_SWORD);

        Combat.resolve(s, arena, swing(), Input.NONE);

        assertEquals(Combat.WEAPON_HIT_WEAR, s.players[0].slotDamage[0],
                "only swords and tridents may wear a single point per attack");
    }

    @Test
    void aHitStoppedByAShieldStillWearsTheWeapon() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff();
        giveWeapon(s, ItemDict.FLAG_AXE, ItemDict.TOOL_AXE);
        PlayerState v = s.players[1];
        v.blockTicks = Combat.SHIELD_WARMUP;
        v.shieldDisabled = 0;

        Combat.resolve(s, arena, swing(), Input.NONE);

        assertEquals(20f, v.health, 0f, "a blocked hit must not damage the victim");
        assertEquals(Combat.DIGGER_HIT_WEAR, s.players[0].slotDamage[0],
                "a blocked hit must wear the weapon just the same");
    }

    @Test
    void aWeaponThatReachesItsDurabilityLimitBreaks() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff();
        giveWeapon(s, ItemDict.FLAG_SWORD, ItemDict.TOOL_SWORD);
        PlayerState a = s.players[0];
        a.slotDamage[0] = WEAPON_MAX_DAMAGE - 1;
        float armed = a.attackDamage;

        Combat.resolve(s, arena, swing(), Input.NONE);

        assertEquals(0, a.slotCount[0], "a weapon at its durability limit must be destroyed");
        assertEquals(ItemDict.NONE, a.slotEntry[0], "the broken slot must be cleared, not left worn");
        assertEquals(0, a.slotDamage[0], "the cleared slot must not keep the wear of a gone item");
        assertNotEquals(armed, a.attackDamage,
                "breaking the weapon must recompute derived stats");
        assertEquals(ItemDict.FIST_DAMAGE, a.attackDamage, 0f,
                "an empty hand hits for fist damage");
        assertTrue(s.events.stream().anyMatch(e -> e.type() == CombatEvent.ITEM_BREAK
                        && e.attacker() == 0 && e.victim() == 0 && e.kind() == WEAPON_ITEM_ID),
                "the break must be announced so both hosts can render it");
    }

    private static void rearm(GameState s) {
        s.players[1].health = 20f;
        s.players[1].hurtTime = 0;
        s.players[1].lastDamage = 0f;
        s.players[0].attackTicker = 100;
    }

    @Test
    void bothHostsBreakTheWeaponOnTheSameFrame() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState hostA = faceOff();
        GameState hostB = faceOff();
        giveWeapon(hostA, ItemDict.FLAG_SWORD, ItemDict.TOOL_SWORD);
        giveWeapon(hostB, ItemDict.FLAG_SWORD, ItemDict.TOOL_SWORD);
        hostA.players[0].slotDamage[0] = WEAPON_MAX_DAMAGE - 4;
        hostB.players[0].slotDamage[0] = WEAPON_MAX_DAMAGE - 4;

        int frame = 0;
        int frameA = -1;
        int frameB = -1;
        int hits = 0;
        for (int i = 0; i < 16 && frameA < 0; i++) {
            Combat.resolve(hostA, arena, Input.NONE, Input.NONE);
            Combat.resolve(hostB, arena, Input.NONE, Input.NONE);
            frame++;
            hostA.tick++;
            hostB.tick++;
            rearm(hostA);
            rearm(hostB);
            Combat.resolve(hostA, arena, swing(), Input.NONE);
            Combat.resolve(hostB, arena, swing(), Input.NONE);
            frame++;
            hostA.tick++;
            hostB.tick++;
            hits++;
            if (frameA < 0 && hostA.players[0].slotCount[0] == 0) {
                frameA = frame;
            }
            if (frameB < 0 && hostB.players[0].slotCount[0] == 0) {
                frameB = frame;
            }
            assertEquals(Checksum.of(hostA), Checksum.of(hostB),
                    "two hosts running the same replicated loadout must not diverge on wear");
        }

        assertTrue(frameA >= 0, "the weapon must break within the run, not wear forever");
        assertEquals(frameA, frameB, "both hosts must break the weapon on the same frame");
        assertEquals(4, hits, "the fourth single-point hit is the one that reaches the limit");
    }
}
