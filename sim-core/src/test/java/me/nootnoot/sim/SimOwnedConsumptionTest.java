package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class SimOwnedConsumptionTest {
    private static final double GROUND_Y = 64.0;

    private static GameState lone() {
        GameState g = new GameState();
        for (PlayerState p : g.players) {
            p.y = GROUND_Y;
            p.onGround = true;
            p.health = 20f;
            p.attackTicker = 100;
        }
        g.players[1].x = 40.0;
        return g;
    }

    private static Input holdUse() {
        return new Input(false, false, false, false, false, false, false, false, true, 0f, 0f, 0);
    }

    @Test
    void aThrownStackRunsOutAndCannotBeThrownAgain() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        TestKit.of(s).give(0, 0, 3, TestKit.item().itemId(3001).useKind(Combat.USE_SNOWBALL));

        for (int i = 0; i < 60; i++) {
            Simulation.tick(s, arena, holdUse(), Input.NONE);
        }

        assertEquals(3, s.nextProjectileId, "a stack of three must produce exactly three throws");
        assertEquals(0, s.players[0].slotCount[0], "the slot must be emptied by throwing");
        assertEquals(ItemDict.NONE, s.players[0].slotEntry[0], "an emptied slot must clear its entry");
    }

    @Test
    void theOffHandIsAsFiniteAsTheMainHand() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        TestKit.of(s).give(0, ItemDict.OFF_HAND, 4,
                TestKit.item().itemId(3002).useKind(Combat.USE_SNOWBALL));

        Input offhand = Input.NONE.withOffhandUse(true);
        for (int i = 0; i < 80; i++) {
            Simulation.tick(s, arena, offhand, Input.NONE);
        }

        assertEquals(4, s.nextProjectileId, "the off hand must not be an infinite ammunition source");
        assertEquals(0, s.players[0].slotCount[ItemDict.OFF_HAND]);
    }

    @Test
    void aTotemIsSpentWhenItSavesYou() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        TestKit.of(s).give(0, ItemDict.OFF_HAND, 1,
                TestKit.item().itemId(3003).flags(ItemDict.FLAG_TOTEM));
        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        assertTrue(s.players[0].hasTotem);

        s.players[0].health = -5f;
        Combat.tryKill(s, 0, 1);
        assertEquals(1, s.players[0].totemSeq, "the totem must fire");
        assertEquals(0, s.players[0].slotCount[ItemDict.OFF_HAND], "the totem must be spent");
        assertFalse(s.players[0].hasTotem, "a spent totem must not still be held");

        s.players[0].health = -5f;
        Combat.tryKill(s, 0, 1);
        assertTrue(s.players[0].dead, "a second death with no totem left must be fatal");
        assertEquals(1, s.players[0].totemSeq, "there was never a second totem to fire");
    }

    @Test
    void miningWearsTheToolInTheSlotItWasSwungFrom() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.players[0].x = 2.5;
        s.players[0].z = 2.5;
        s.players[0].onGround = true;
        BlockProps.Builder props = new BlockProps.Builder();
        props.add(55, 1f / 40f, 6f, 55, -1, ItemDict.TOOL_NONE, false);
        s.blockProps = props.build();
        s.blocks.place(2, 65, 2, 55);

        assertEquals(0, s.players[0].slotDamage[0]);
        for (int i = 0; i < 8 && s.blocks.contains(2, 65, 2); i++) {
            Simulation.tick(s, arena,
                    Input.NONE.withBlockAction(Input.BLOCK_BREAK, 2, 65, 2), Input.NONE);
        }

        assertFalse(s.blocks.contains(2, 65, 2), "the block should have broken");
        assertEquals(1, s.players[0].slotDamage[0], "breaking a block must wear the held tool by one");
    }

    @Test
    void armourWearsFromTheTableAndBreaksAtItsLimit() {
        GameState s = lone();
        TestKit kit = TestKit.of(s);
        for (int slot = ItemDict.ARMOR_FEET; slot <= ItemDict.ARMOR_HEAD; slot++) {
            kit.give(0, slot, 1, TestKit.item().itemId(3100 + slot).maxDamage(10)
                    .armor(4, 1f, 0f, slot - ItemDict.ARMOR_FEET + 1));
        }
        PlayerState p = s.players[0];
        float armourBefore = p.armor;
        assertTrue(armourBefore > 0f, "the pieces must be protecting the player to begin with");

        for (int i = 0; i < 4; i++) {
            Combat.damageArmor(s, p, 8.0);
        }

        for (int slot = ItemDict.ARMOR_FEET; slot <= ItemDict.ARMOR_HEAD; slot++) {
            assertEquals(8, p.slotDamage[slot], "wear must accumulate from the replicated table");
            assertEquals(1, p.slotCount[slot], "a worn but unbroken piece stays in the slot");
        }
        assertEquals(armourBefore, p.armor, 0f, "a worn but unbroken piece keeps its armour points");

        Combat.damageArmor(s, p, 8.0);

        for (int slot = ItemDict.ARMOR_FEET; slot <= ItemDict.ARMOR_HEAD; slot++) {
            assertEquals(0, p.slotCount[slot], "a piece that reaches its durability limit must break");
            assertEquals(ItemDict.NONE, p.slotEntry[slot], "the broken slot must be cleared");
            assertEquals(0, p.slotDamage[slot], "the cleared slot must not keep the wear of a gone piece");
        }
        assertEquals(0f, p.armor, 0f, "broken armour must stop protecting");
    }

    @Test
    void anElytraTakesNoWearFromBeingHitAndTheRestOfTheArmourStillDoes() {
        GameState s = lone();
        TestKit kit = TestKit.of(s);
        kit.give(0, ItemDict.ARMOR_CHEST, 1, TestKit.item().itemId(3200).maxDamage(10)
                .flags(ItemDict.FLAG_ELYTRA).armor(0, 0f, 0f, ItemDict.EQUIP_CHEST));
        kit.give(0, ItemDict.ARMOR_HEAD, 1, TestKit.item().itemId(3201).maxDamage(10)
                .armor(4, 1f, 0f, ItemDict.EQUIP_HEAD));
        PlayerState p = s.players[0];

        for (int i = 0; i < 20; i++) {
            Combat.damageArmor(s, p, 8.0);
        }

        assertEquals(0, p.slotDamage[ItemDict.ARMOR_CHEST],
                "vanilla elytra carry damage_on_hurt=false, so hits must not wear one");
        assertEquals(1, p.slotCount[ItemDict.ARMOR_CHEST],
                "armour wear must never be able to destroy an elytra");
        assertTrue(p.hasElytra, "an unworn elytra must still be flyable after a beating");
        assertEquals(0, p.slotCount[ItemDict.ARMOR_HEAD],
                "the real armour beside it must still wear out and break");
    }

    @Test
    void elytraWearIsASimTickSoBothHostsLoseFlightOnTheSameFrame() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        TestKit.of(s).give(0, ItemDict.ARMOR_CHEST, 1, TestKit.item().itemId(3200)
                .maxDamage(5).flags(ItemDict.FLAG_ELYTRA).armor(0, 0f, 0f, ItemDict.EQUIP_CHEST));
        PlayerState p = s.players[0];
        p.y = GROUND_Y + 400.0;
        p.onGround = false;
        p.gliding = true;
        p.hasElytra = true;

        int flipTick = -1;
        for (int i = 0; i < 100; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
            if (flipTick < 0 && !p.hasElytra) {
                flipTick = s.tick;
            }
        }

        assertEquals(4, p.slotDamage[ItemDict.ARMOR_CHEST],
                "wear must clamp one short of destruction");
        assertEquals(61, flipTick, "flight must cut out on an exact, replicated sim frame");
        assertFalse(p.gliding, "a worn-out elytra must stop the glide");
    }

    @Test
    void aConsumedStackSurvivesASnapshotAndReplay() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        TestKit.of(s).give(0, 0, 8, TestKit.item().itemId(3300).useKind(Combat.USE_SNOWBALL));

        for (int i = 0; i < 5; i++) {
            Simulation.tick(s, arena, holdUse(), Input.NONE);
        }

        GameState snapshot = s.copy();
        List<Long> live = new ArrayList<>();
        List<Integer> liveCount = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Simulation.tick(s, arena, holdUse(), Input.NONE);
            live.add(Checksum.of(s));
            liveCount.add(s.players[0].slotCount[0]);
        }

        for (int i = 0; i < 40; i++) {
            Simulation.tick(snapshot, arena, holdUse(), Input.NONE);
            assertEquals(live.get(i).longValue(), Checksum.of(snapshot),
                    "a replay from a snapshot diverged at frame " + i);
            assertEquals(liveCount.get(i).intValue(), snapshot.players[0].slotCount[0],
                    "the slot table must be cloned per snapshot, not aliased");
        }

        assertTrue(liveCount.get(39) < 8, "the replay has to have consumed something to be a test");
    }
}
