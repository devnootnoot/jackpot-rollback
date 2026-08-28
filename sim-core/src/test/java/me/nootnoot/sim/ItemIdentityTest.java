package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;
import org.junit.jupiter.api.Test;

class ItemIdentityTest {
    private static final double GROUND_Y = 64.0;
    private static final int SWORD_ITEM_ID = 7001;
    private static final int PLAIN_ARROW_ITEM_ID = 7100;
    private static final int TIPPED_ARROW_ITEM_ID = 7101;
    private static final int WORN = 40;

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

    private static void put(GameState s, int player, int slot, int entry, int count, int damage) {
        PlayerState p = s.players[player];
        p.slotEntry[slot] = entry;
        p.slotCount[slot] = count;
        p.slotDamage[slot] = damage;
        Loadout.recomputeDerived(s, p);
    }

    @Test
    void aDroppedItemIsPickedUpAsTheSameEntryWithTheSameWear() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        TestKit kit = TestKit.of(s);
        int enchanted = kit.add(TestKit.item().itemId(SWORD_ITEM_ID).maxStack(1).maxDamage(250)
                .flags(ItemDict.FLAG_SWORD).weapon(ItemDict.MAX_SHARPNESS, 0, 0, 0));
        int plain = kit.add(TestKit.item().itemId(SWORD_ITEM_ID).maxStack(1).maxDamage(250)
                .flags(ItemDict.FLAG_SWORD));
        put(s, 0, 0, plain, 1, WORN);

        assertEquals(enchanted, s.dict.entryForItemId(SWORD_ITEM_ID),
                "the item id alone must resolve to the other entry, otherwise this proves nothing");

        Combat.resolve(s, arena, Input.NONE.withDrop(true, true), Input.NONE);
        assertEquals(1, s.items.size(), "the drop must have spawned exactly one ground stack");
        ItemEntityState e = s.items.get(0);
        assertEquals(plain, e.entry, "a drop must carry the entry it was dropped from");
        assertEquals(WORN, e.damage, "a drop must carry the wear it was dropped with");

        PlayerState p = s.players[0];
        e.x = p.x;
        e.y = p.y + 0.5;
        e.z = p.z;
        e.vx = 0.0;
        e.vy = 0.0;
        e.vz = 0.0;
        e.pickupDelay = 0;
        ItemEntities.tick(s, arena, Input.NONE, Input.NONE);

        assertTrue(s.items.isEmpty(), "the stack must have been picked up");
        assertEquals(plain, p.slotEntry[0], "a drop and pickup must not launder an item into another entry");
        assertEquals(WORN, p.slotDamage[0], "a drop and pickup must not repair the item");
        assertEquals(0, s.dict.sharpness(p.slotEntry[0]), "a plain sword must not come back enchanted");
    }

    @Test
    void groundStacksOfDifferentEntriesOrWearDoNotMerge() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        TestKit kit = TestKit.of(s);
        int enchanted = kit.add(TestKit.item().itemId(SWORD_ITEM_ID).maxStack(1).maxDamage(250)
                .flags(ItemDict.FLAG_SWORD).weapon(ItemDict.MAX_SHARPNESS, 0, 0, 0));
        int plain = kit.add(TestKit.item().itemId(SWORD_ITEM_ID).maxStack(1).maxDamage(250)
                .flags(ItemDict.FLAG_SWORD));

        ItemEntities.spawn(s, 0, 4.0, GROUND_Y, 4.0, 0.0, 0.0, 0.0,
                enchanted, 0, SWORD_ITEM_ID, 1, 200);
        ItemEntities.spawn(s, 0, 4.5, GROUND_Y, 4.0, 0.0, 0.0, 0.0,
                plain, WORN, SWORD_ITEM_ID, 1, 200);
        ItemEntities.tick(s, arena, Input.NONE, Input.NONE);

        assertEquals(2, s.items.size(), "two different items must not collapse into one ground stack");
        for (ItemEntityState e : s.items) {
            assertEquals(1, e.count, "neither stack may absorb the other");
        }
        assertNotEquals(s.items.get(0).entry, s.items.get(1).entry,
                "the two stacks must still be the two entries they were spawned as");
    }

    @Test
    void aCrossbowFiresTheArrowItWasLoadedWith() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        TestKit kit = TestKit.of(s);
        kit.add(TestKit.item().itemId(PLAIN_ARROW_ITEM_ID).flags(ItemDict.FLAG_ARROW_PLAIN));
        int tipped = kit.add(TestKit.item().itemId(TIPPED_ARROW_ITEM_ID)
                .flags(ItemDict.FLAG_ARROW_SPECIAL)
                .effect(0, ItemDict.packEffect(Effects.POISON, 0, 200)));
        int crossbow = kit.add(TestKit.item().itemId(7102).maxStack(1)
                .useKind(Combat.USE_CROSSBOW).flags(ItemDict.FLAG_CROSSBOW));
        put(s, 0, 1, tipped, 1, 0);
        put(s, 0, 0, crossbow, 1, 0);

        for (int i = 0; i < 30; i++) {
            Simulation.tick(s, arena, holdUse(), Input.NONE);
        }
        assertEquals(0, s.players[0].slotCount[1], "loading must have spent the tipped arrow");
        assertTrue(s.players[0].slotCrossbowLoaded[0],
                "a crossbow whose charge finished under a held right click HOLDS its bolt:"
                        + " getUseDuration is 72000, so the player is still using the item and"
                        + " Minecraft.handleKeybinds drains every use click into an empty loop");
        assertEquals(0, s.projectiles.size(), "holding the button may not let the bolt go");

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        Simulation.tick(s, arena, holdUse(), Input.NONE);

        assertFalse(s.players[0].slotCrossbowLoaded[0],
                "releasing and pressing again re-enters CrossbowItem.use, which shoots a"
                        + " charged crossbow");
        assertEquals(1, s.projectiles.size(), "the crossbow must have fired exactly one bolt");
        ProjectileState pr = s.projectiles.get(0);
        assertEquals(ItemDict.packEffect(Effects.POISON, 0, 200), pr.effect0,
                "the bolt must carry the effects of the arrow it was loaded with");
        assertEquals(TIPPED_ARROW_ITEM_ID, pr.arrowItemId,
                "the bolt must be recoverable as the arrow it was loaded with");
    }

    @Test
    void retypingAStackConvertsExactlyOneItemAndLeavesTheRestAlone() {
        GameState s = lone();
        TestKit kit = TestKit.of(s);
        int empty = kit.add(TestKit.item().itemId(7200).maxStack(16)
                .flags(ItemDict.FLAG_BUCKET_EMPTY));
        int water = kit.add(TestKit.item().itemId(7201).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_WATER));
        put(s, 0, 0, empty, 16, 0);

        int lost = Loadout.retype(s, s.players[0], 0, water);

        PlayerState p = s.players[0];
        assertEquals(0, lost, "an empty inventory has room for the one bucket that was filled");
        assertEquals(1, count(p, water), "filling a bucket must yield exactly one full bucket");
        assertEquals(15, count(p, empty),
                "the untouched buckets in the stack must still be empty buckets");
        assertEquals(15, p.slotCount[0], "the source stack loses exactly the one item it converted");
        assertEquals(empty, p.slotEntry[0], "the source stack must keep its old entry");
    }

    @Test
    void retypingASingleItemConvertsItWhereItIs() {
        GameState s = lone();
        TestKit kit = TestKit.of(s);
        int empty = kit.add(TestKit.item().itemId(7200).maxStack(16)
                .flags(ItemDict.FLAG_BUCKET_EMPTY));
        int water = kit.add(TestKit.item().itemId(7201).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_WATER));
        put(s, 0, 0, empty, 1, 0);

        int lost = Loadout.retype(s, s.players[0], 0, water);

        PlayerState p = s.players[0];
        assertEquals(0, lost, "a bucket converted in place has nowhere to overflow to");
        assertEquals(1, count(p, water), "one empty bucket becomes one full bucket");
        assertEquals(0, count(p, empty), "the single empty bucket must be gone");
        assertEquals(water, p.slotEntry[0], "the filled bucket must stay in the hand it was filled from");
        assertEquals(1, p.slotCount[0], "one item in, one item out");
    }

    @Test
    void aRecoveredArrowComesBackAsTheArrowThatWasFired() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        TestKit kit = TestKit.of(s);
        int weak = kit.add(TestKit.item().itemId(TIPPED_ARROW_ITEM_ID)
                .flags(ItemDict.FLAG_ARROW_SPECIAL)
                .effect(0, ItemDict.packEffect(Effects.POISON, 0, 100)));
        int strong = kit.add(TestKit.item().itemId(TIPPED_ARROW_ITEM_ID)
                .flags(ItemDict.FLAG_ARROW_SPECIAL)
                .effect(0, ItemDict.packEffect(Effects.POISON, ItemDict.MAX_EFFECT_AMPLIFIER, 1200)));
        int bow = kit.add(TestKit.item().itemId(7103).maxStack(1)
                .useKind(Combat.USE_BOW).flags(ItemDict.FLAG_BOW));
        put(s, 0, 1, strong, 1, 0);
        put(s, 0, 0, bow, 1, 0);

        assertEquals(weak, s.dict.entryForItemId(TIPPED_ARROW_ITEM_ID),
                "the item id alone must resolve to the other tipped arrow, otherwise this proves nothing");

        for (int i = 0; i < 20; i++) {
            Combat.resolve(s, arena, holdUse(), Input.NONE);
        }
        Combat.resolve(s, arena, Input.NONE, Input.NONE);

        assertEquals(1, s.projectiles.size(), "the bow must have fired exactly one arrow");
        ProjectileState pr = s.projectiles.get(0);
        assertEquals(strong, pr.arrowEntry, "a fired arrow must carry the entry it was fired from");

        PlayerState p = s.players[0];
        pr.stuck = true;
        pr.x = p.x;
        pr.y = p.y + 1.0;
        pr.z = p.z;
        Combat.resolveArrowPickups(s);

        assertTrue(pr.dead, "the owner standing on the arrow must have picked it up");
        int recovered = ItemDict.NONE;
        for (int slot = 0; slot < ItemDict.SLOTS; slot++) {
            if (s.dict.isArrow(Loadout.entryAt(p, slot))) {
                recovered = p.slotEntry[slot];
                break;
            }
        }
        assertEquals(strong, recovered,
                "recovering a fired arrow must not launder it into a different tipped arrow");
        assertNotEquals(weak, recovered, "the weaker arrow must not be what comes back");
        assertEquals(ItemDict.packEffect(Effects.POISON, ItemDict.MAX_EFFECT_AMPLIFIER, 1200),
                s.dict.effect(recovered, 0), "the recovered arrow must keep its own effect");
    }

    private static int count(PlayerState p, int entry) {
        int total = 0;
        for (int slot = 0; slot < ItemDict.SLOTS; slot++) {
            if (p.slotEntry[slot] == entry) {
                total += p.slotCount[slot];
            }
        }
        return total;
    }
}
