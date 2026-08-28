package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class GameRuleEnforcementTest {

    private static final double GROUND_Y = 64.0;

    private static final int OBSIDIAN_ITEM_ID = 900;
    private static final int ANCHOR_ITEM_ID = 901;
    private static final int GLOWSTONE_ITEM_ID = 902;
    private static final int CRYSTAL_ITEM_ID = 903;
    private static final int EMPTY_BUCKET_ITEM_ID = 910;
    private static final int WATER_BUCKET_ITEM_ID = 911;
    private static final int LAVA_BUCKET_ITEM_ID = 912;

    private static final int SLOT_CRYSTAL = 0;
    private static final int SLOT_ANCHOR = 1;
    private static final int SLOT_GLOWSTONE = 2;
    private static final int SLOT_WATER = 3;
    private static final int SLOT_EMPTY_BUCKET = 4;
    private static final int SLOT_LAVA = 5;

    private static final int TX = 3;
    private static final int TZ = 0;
    private static final int BASE_Y = 64;
    private static final int OPEN_Y = 65;
    private static final long OPEN_KEY = BlockStore.key(TX, OPEN_Y, TZ);

    private static GameState kit() {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = GROUND_Y;
        a.z = 0.5;
        a.yaw = -90f;
        a.onGround = true;
        a.health = 20f;
        PlayerState v = s.players[1];
        v.x = 10_000.0;
        v.y = GROUND_Y;
        v.z = 0.5;
        v.onGround = true;
        v.health = 20f;

        s.obsidianItemId = OBSIDIAN_ITEM_ID;
        s.glowstoneItemId = GLOWSTONE_ITEM_ID;

        TestKit kit = TestKit.of(s);
        kit.give(0, SLOT_CRYSTAL, 64,
                TestKit.item().itemId(CRYSTAL_ITEM_ID).flags(ItemDict.FLAG_END_CRYSTAL));
        kit.give(0, SLOT_ANCHOR, 64, TestKit.item().itemId(ANCHOR_ITEM_ID)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_RESPAWN_ANCHOR));
        kit.give(0, SLOT_GLOWSTONE, 64, TestKit.item().itemId(GLOWSTONE_ITEM_ID)
                .flags(ItemDict.FLAG_BLOCK | ItemDict.FLAG_GLOWSTONE));
        kit.give(0, SLOT_WATER, 1, TestKit.item().itemId(WATER_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_WATER));
        kit.give(0, SLOT_EMPTY_BUCKET, 1, TestKit.item().itemId(EMPTY_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_EMPTY));
        kit.give(0, SLOT_LAVA, 1, TestKit.item().itemId(LAVA_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_LAVA));

        s.blocks.place(TX, BASE_Y, TZ, OBSIDIAN_ITEM_ID);
        return s;
    }

    private static Input at(int action, int slot, int y) {
        return Input.NONE.withBlockAction(action, TX, y, TZ).withHeldSlot(slot);
    }

    private static void idle(GameState s, Arena arena, int ticks) {
        for (int i = 0; i < ticks; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
    }

    private static int explosions(GameState s) {
        int n = 0;
        for (CombatEvent e : s.events) {
            if (e.type() == CombatEvent.EXPLOSION) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aStateWithEveryMechanicOnHandsTheSimTheVeryInputItWasGiven() {
        GameState s = kit();
        assertTrue(s.allowExplosion, "a GameState nobody configured has to allow everything, or"
                + " every fixture and every harness scenario silently loses its crystals");
        assertTrue(s.allowBucket);

        for (int action = Input.BLOCK_NONE; action <= Input.BLOCK_CLOSE_CONTAINER; action++) {
            Input in = at(action, 0, OPEN_Y).withCrystalHit(true, TX, BASE_Y, TZ);
            assertSame(in, Combat.ruleFiltered(s, in),
                    "the rule filter rebuilt the input for action " + action + " in a match that"
                            + " forbids nothing. Every existing match runs through this path, so it"
                            + " has to be identity when both rules are on - that is what keeps the"
                            + " committed harness digest reproducible across a rule change");
        }
    }

    @Test
    void anUnmoddedHostsCrystalPlaceIsRefusedBySimWhenExplosionsAreOff() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState allowed = kit();
        Simulation.tick(allowed, arena, at(Input.BLOCK_PLACE_CRYSTAL, SLOT_CRYSTAL, BASE_Y),
                Input.NONE);
        assertEquals(1, allowed.crystals.size(),
                "control: this is the exact input an edge host mints for a right click on"
                        + " obsidian with a crystal held, and it has to work when explosions are on");

        GameState s = kit();
        s.allowExplosion = false;
        int before = s.players[0].slotCount[SLOT_CRYSTAL];

        Simulation.tick(s, arena, at(Input.BLOCK_PLACE_CRYSTAL, SLOT_CRYSTAL, BASE_Y), Input.NONE);

        assertTrue(s.crystals.isEmpty(),
                "the input carried BLOCK_PLACE_CRYSTAL, which is what an UNMODDED player's host"
                        + " sends: the edge input source has no rule gate at all. If the only"
                        + " enforcement lives in the modded client's input source then a modded"
                        + " player is bound by the game type and an unmodded one is not");
        assertEquals(before, s.players[0].slotCount[SLOT_CRYSTAL],
                "and a refused placement must not spend the crystal");
    }

    @Test
    void everyAnchorStepIsRefusedWhenExplosionsAreOff() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = kit();
        s.allowExplosion = false;
        int anchorsBefore = s.players[0].slotCount[SLOT_ANCHOR];
        int glowstoneBefore = s.players[0].slotCount[SLOT_GLOWSTONE];

        Simulation.tick(s, arena, at(Input.BLOCK_PLACE_ANCHOR, SLOT_ANCHOR, OPEN_Y), Input.NONE);
        assertFalse(s.blocks.contains(TX, OPEN_Y, TZ), "the anchor must never go up");
        assertEquals(anchorsBefore, s.players[0].slotCount[SLOT_ANCHOR]);

        s.blocks.place(TX, OPEN_Y, TZ, ANCHOR_ITEM_ID);
        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena, at(Input.BLOCK_CHARGE_ANCHOR, SLOT_GLOWSTONE, OPEN_Y),
                Input.NONE);
        assertNull(s.anchors.get(OPEN_KEY),
                "an anchor that somehow exists still must not take a charge");
        assertEquals(glowstoneBefore, s.players[0].slotCount[SLOT_GLOWSTONE]);

        s.anchors.put(OPEN_KEY, 1);
        idle(s, arena, Combat.USE_REPEAT_DELAY);
        s.events.clear();
        Simulation.tick(s, arena, at(Input.BLOCK_DETONATE_ANCHOR, SLOT_ANCHOR, OPEN_Y), Input.NONE);
        assertEquals(0, explosions(s), "and a charged anchor must not go off");
        assertTrue(s.blocks.contains(TX, OPEN_Y, TZ));
    }

    private static GameState standingCrystal() {
        GameState s = kit();
        CrystalState c = new CrystalState();
        c.id = s.nextCrystalId++;
        c.bx = TX;
        c.by = BASE_Y;
        c.bz = TZ;
        s.crystals.add(c);
        return s;
    }

    private static Input leftClick(Input in) {
        return in.withClicks(new Clicks(1, 0, 0, 0, 0));
    }

    @Test
    void aPlacedCrystalCannotBeSetOffByHandWhenExplosionsAreOff() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState channel = standingCrystal();
        Simulation.tick(channel, arena, leftClick(
                Input.NONE.withCrystalHit(true, TX, BASE_Y, TZ).withHeldSlot(SLOT_CRYSTAL)),
                Input.NONE);
        assertEquals(1, explosions(channel),
                "control: a left click on a crystal in range detonates it when explosions are on");

        GameState legacy = standingCrystal();
        Simulation.tick(legacy, arena,
                leftClick(at(Input.BLOCK_HIT_CRYSTAL, SLOT_CRYSTAL, BASE_Y)), Input.NONE);
        assertEquals(1, explosions(legacy),
                "control: the legacy opcode reaches the same detonation");

        GameState s = standingCrystal();
        s.allowExplosion = false;
        Simulation.tick(s, arena, leftClick(
                Input.NONE.withCrystalHit(true, TX, BASE_Y, TZ).withHeldSlot(SLOT_CRYSTAL)),
                Input.NONE);
        assertEquals(1, s.crystals.size(), "the crystalHit channel is the one the mod gates behind"
                + " allowExplosion, so it is the one an unmodded host would still send");
        assertEquals(0, explosions(s));

        GameState legacyOff = standingCrystal();
        legacyOff.allowExplosion = false;
        Simulation.tick(legacyOff, arena,
                leftClick(at(Input.BLOCK_HIT_CRYSTAL, SLOT_CRYSTAL, BASE_Y)), Input.NONE);
        assertEquals(1, legacyOff.crystals.size(),
                "BLOCK_HIT_CRYSTAL is the second way to reach the same detonation and has to be"
                        + " shut off with it, not left as the way around the rule");
        assertEquals(0, explosions(legacyOff));
    }

    @Test
    void pouringAndScoopingAreBothRefusedWhenBucketsAreOff() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState allowed = kit();
        Simulation.tick(allowed, arena, at(Input.BLOCK_PLACE_WATER, SLOT_WATER, OPEN_Y),
                Input.NONE);
        assertNotNull(allowed.fluids.get(OPEN_KEY), "control: the pour works when buckets are on");

        GameState s = kit();
        s.allowBucket = false;
        Simulation.tick(s, arena, at(Input.BLOCK_PLACE_WATER, SLOT_WATER, OPEN_Y), Input.NONE);
        assertNull(s.fluids.get(OPEN_KEY), "no MLG water when the game type forbids buckets");
        assertTrue(s.dict.isBucketWater(s.players[0].slotEntry[SLOT_WATER]),
                "and the refused bucket must not have been emptied");

        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena, at(Input.BLOCK_PLACE_LAVA, SLOT_LAVA, OPEN_Y), Input.NONE);
        assertNull(s.fluids.get(OPEN_KEY), "no lava cast either");

        assertTrue(Fluids.place(s, arena, 0, Fluids.WATER, TX, OPEN_Y, TZ));
        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena, at(Input.BLOCK_PICKUP_FLUID, SLOT_EMPTY_BUCKET, OPEN_Y),
                Input.NONE);
        assertNotNull(s.fluids.get(OPEN_KEY),
                "picking a source back up is half of a bucket duel and is gated by the same rule");
        assertTrue(s.dict.isBucketEmpty(s.players[0].slotEntry[SLOT_EMPTY_BUCKET]));
    }

    @Test
    void theTwoRulesDoNotShutEachOtherOff() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState noBlast = kit();
        noBlast.allowExplosion = false;
        Simulation.tick(noBlast, arena, at(Input.BLOCK_PLACE_WATER, SLOT_WATER, OPEN_Y),
                Input.NONE);
        assertNotNull(noBlast.fluids.get(OPEN_KEY),
                "explosions off must leave buckets alone, or a crystal-free game type quietly"
                        + " loses MLG water too");

        GameState noBucket = kit();
        noBucket.allowBucket = false;
        Simulation.tick(noBucket, arena, at(Input.BLOCK_PLACE_CRYSTAL, SLOT_CRYSTAL, BASE_Y),
                Input.NONE);
        assertEquals(1, noBucket.crystals.size(), "and buckets off must leave crystals alone");
    }

    @Test
    void bothRuleFlagsMoveTheChecksumTheTwoHostsExchange() {
        GameState blast = kit();
        GameState bucket = kit();
        long agreed = Checksum.of(kit());

        blast.allowExplosion = false;
        bucket.allowBucket = false;

        assertNotEquals(agreed, Checksum.of(blast),
                "two hosts that decoded a different allowExplosion out of the same setup blob"
                        + " would otherwise agree on every checksum they exchange right up until"
                        + " the first crystal, which is the desync this rule is supposed to make"
                        + " impossible");
        assertNotEquals(agreed, Checksum.of(bucket));
        assertNotEquals(Checksum.of(blast), Checksum.of(bucket));
    }

    @Test
    void theRulesSurviveTheCopyThatRollbackSavesAndRestores() {
        GameState s = kit();
        s.allowExplosion = false;
        s.allowBucket = false;

        GameState restored = s.copy();

        assertFalse(restored.allowExplosion,
                "a rollback restores from copy(), so a rule that copy() drops is a rule that stops"
                        + " applying the moment a rollback happens - and rollbacks happen"
                        + " thousands of times a match");
        assertFalse(restored.allowBucket);
        assertEquals(Checksum.of(s), Checksum.of(restored));
    }
}
