package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import me.nootnoot.sim.harness.StateFacets;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class BlockRuleEnforcementTest {

    private static final double GROUND_Y = 64.0;

    private static final int OAK_PLANKS_ITEM = 900;
    private static final int TNT_ITEM = 901;
    private static final int PICKAXE_ITEM = 902;

    private static final int SLOT_PLANKS = 0;
    private static final int SLOT_TNT = 1;
    private static final int SLOT_PICKAXE = 2;

    private static final int TX = 2;
    private static final int TZ = 0;
    private static final int PLACE_Y = 65;

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

        TestKit kit = TestKit.of(s);
        kit.give(0, SLOT_PLANKS, 64, TestKit.item().itemId(OAK_PLANKS_ITEM)
                .flags(ItemDict.FLAG_BLOCK));
        kit.give(0, SLOT_TNT, 64, TestKit.item().itemId(TNT_ITEM).flags(ItemDict.FLAG_BLOCK));
        kit.give(0, SLOT_PICKAXE, 1, TestKit.item().itemId(PICKAXE_ITEM).maxStack(1));

        s.blocks.place(TX, (int) GROUND_Y, TZ, OAK_PLANKS_ITEM);
        return s;
    }

    private static Input place(int slot) {
        return Input.NONE.withBlockAction(Input.BLOCK_PLACE, TX, PLACE_Y, TZ).withHeldSlot(slot);
    }

    private static Input mine(int slot, int y) {
        return Input.NONE.withBlockAction(Input.BLOCK_BREAK, TX, y, TZ).withHeldSlot(slot)
                .withClicks(new Clicks(1, 0, 0, 0, 0));
    }

    private static void mineUntilGone(GameState s, Arena arena, int slot, int y, int ticks) {
        for (int i = 0; i < ticks; i++) {
            Simulation.tick(s, arena, mine(slot, y), Input.NONE);
        }
    }

    @Test
    void aMatchWithNoBlockRulesIsUnrestrictedAndUntouched() {
        GameState s = kit();
        assertArrayEquals(GameState.NO_ITEM_IDS, s.breakableItemIds,
                "a GameState nobody configured has to allow everything, or every fixture and"
                        + " every harness scenario silently stops being able to build");
        assertArrayEquals(GameState.NO_ITEM_IDS, s.placeableItemIds);
        assertTrue(Combat.placeAllowed(s, TNT_ITEM));
        assertTrue(Combat.breakAllowed(s, TNT_ITEM));
        assertTrue(Combat.breakAllowed(s, 0),
                "an arena voxel whose palette entry carried no block item resolves to 0; with no"
                        + " whitelist that must still be minable, which is every match today");
    }

    @Test
    void anUnmoddedHostsPlacementIsRefusedBySimWhenTheItemIsNotOnTheWhitelist() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState allowed = kit();
        allowed.placeableItemIds = new int[]{OAK_PLANKS_ITEM};
        Simulation.tick(allowed, arena, place(SLOT_PLANKS), Input.NONE);
        assertTrue(allowed.blocks.contains(TX, PLACE_Y, TZ),
                "control: a whitelisted block still places");

        GameState s = kit();
        s.placeableItemIds = new int[]{OAK_PLANKS_ITEM};
        int before = s.players[0].slotCount[SLOT_TNT];

        Simulation.tick(s, arena, place(SLOT_TNT), Input.NONE);

        assertFalse(s.blocks.contains(TX, PLACE_Y, TZ),
                "the input carried BLOCK_PLACE, which is what an UNMODDED player's host sends:"
                        + " the edge input source has no block whitelist at all. If the only"
                        + " enforcement lives in the modded client's input source then a modded"
                        + " player is bound by the game type and an unmodded one is not");
        assertEquals(before, s.players[0].slotCount[SLOT_TNT],
                "and a refused placement must not spend the block");
    }

    @Test
    void theOffHandPlaceRoutesThroughTheSameWhitelist() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = kit();
        TestKit.of(s).give(0, ItemDict.OFF_HAND, 64,
                TestKit.item().itemId(TNT_ITEM).flags(ItemDict.FLAG_BLOCK));
        s.placeableItemIds = new int[]{OAK_PLANKS_ITEM};

        Simulation.tick(s, arena, Input.NONE
                .withBlockAction(Input.BLOCK_PLACE_OFFHAND, TX, PLACE_Y, TZ), Input.NONE);

        assertFalse(s.blocks.contains(TX, PLACE_Y, TZ),
                "the off-hand place is a second door into the same placement; a rule that only"
                        + " covers the main hand is the way around itself");
    }

    @Test
    void aPlacedBlockThatIsNotOnTheBreakWhitelistCannotBeMinedBack() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState allowed = kit();
        allowed.blocks.place(TX, PLACE_Y, TZ, OAK_PLANKS_ITEM);
        allowed.breakableItemIds = new int[]{OAK_PLANKS_ITEM};
        mineUntilGone(allowed, arena, SLOT_PICKAXE, PLACE_Y, 200);
        assertFalse(allowed.blocks.contains(TX, PLACE_Y, TZ),
                "control: a whitelisted block still mines out");

        GameState s = kit();
        s.blocks.place(TX, PLACE_Y, TZ, TNT_ITEM);
        s.breakableItemIds = new int[]{OAK_PLANKS_ITEM};

        mineUntilGone(s, arena, SLOT_PICKAXE, PLACE_Y, 200);

        assertTrue(s.blocks.contains(TX, PLACE_Y, TZ));
        assertEquals(0f, s.players[0].miningProgress,
                "a refused target must not keep accumulating progress either, or the block pops"
                        + " the instant the rule is relaxed mid-match");
    }

    private static final int STONE_ITEM = 71;
    private static final int COBBLE_ITEM = 72;
    private static final int WALL_ITEM = 73;

    private static final int FLOOR_X = 1;
    private static final int WALL_X = 3;
    private static final int TERRAIN_Y = 65;
    private static final int TERRAIN_Z = 0;

    private static Arena walledArena() {
        boolean[] grid = new boolean[4];
        Map<Long, Float> resistance = new HashMap<>();
        Map<Long, Integer> drops = new HashMap<>();
        Map<Long, Integer> blockItems = new HashMap<>();
        for (int x = 0; x < 4; x++) {
            grid[x] = true;
            long key = BlockStore.key(x, TERRAIN_Y, TERRAIN_Z);
            boolean floor = x < 2;
            drops.put(key, floor ? COBBLE_ITEM : WALL_ITEM);
            blockItems.put(key, floor ? STONE_ITEM : WALL_ITEM);
        }
        return new Arena(GROUND_Y, grid, 0, TERRAIN_Y, TERRAIN_Z, 4, 1, 1,
                new double[0][], resistance, drops, new HashSet<>(), blockItems);
    }

    private static GameState terrainKit() {
        GameState s = kit();
        s.blocks.removeAt(TX, (int) GROUND_Y, TZ);
        s.vanillaBuild = true;
        s.players[0].x = 1.5;
        s.players[0].z = 2.5;
        s.players[0].pitch = 30f;
        return s;
    }

    private static void mineTerrain(GameState s, Arena arena, int x) {
        for (int i = 0; i < 600; i++) {
            Simulation.tick(s, arena, Input.NONE
                    .withBlockAction(Input.BLOCK_BREAK, x, TERRAIN_Y, TERRAIN_Z)
                    .withHeldSlot(SLOT_PICKAXE)
                    .withClicks(new Clicks(1, 0, 0, 0, 0)), Input.NONE);
        }
    }

    @Test
    void arenaTerrainIsGatedOnTheBlocksOwnItemNotOnWhatItDrops() {
        Arena arena = walledArena();

        GameState unrestricted = terrainKit();
        mineTerrain(unrestricted, arena, FLOOR_X);
        assertTrue(unrestricted.brokenArena.contains(
                        BlockStore.key(FLOOR_X, TERRAIN_Y, TERRAIN_Z)),
                "control: with no whitelist this voxel mines out, so the fixture really does"
                        + " reach the arena-terrain branch");

        GameState floor = terrainKit();
        floor.breakableItemIds = new int[]{STONE_ITEM};
        mineTerrain(floor, arena, FLOOR_X);
        assertTrue(floor.brokenArena.contains(BlockStore.key(FLOOR_X, TERRAIN_Y, TERRAIN_Z)),
                "the game type lists STONE and this voxel IS stone; it drops cobblestone, so a"
                        + " gate that read the drop id would compare cobblestone against a stone"
                        + " whitelist and refuse the one block the rule exists to allow");

        GameState wall = terrainKit();
        wall.breakableItemIds = new int[]{STONE_ITEM};
        mineTerrain(wall, arena, WALL_X);
        assertTrue(wall.brokenArena.isEmpty(),
                "the arena WALL is not on the list and must survive, which is the whole point of"
                        + " GameType.allowedBlocksToBreak: the floor is diggable, the walls are"
                        + " not, and that has to hold for BOTH hosts");
    }

    @Test
    void theSameRuleDecidesWhetherABlastCanChewThroughArenaTerrain() {
        Arena arena = walledArena();
        GameState open = kit();
        GameState restricted = kit();
        restricted.breakableItemIds = new int[]{STONE_ITEM};

        assertTrue(Combat.breakAllowed(open, WALL_ITEM));
        assertFalse(Combat.breakAllowed(restricted, WALL_ITEM),
                "control: the wall material is unlisted only in the restricted state");
        assertTrue(Combat.breakAllowed(restricted, STONE_ITEM));
        assertEquals(WALL_ITEM, arena.voxelBlockItem(WALL_X, TERRAIN_Y, TERRAIN_Z),
                "the sim reads the arena voxel's own block item to decide both the mining gate"
                        + " and the blast gate, so the two can never disagree the way they did"
                        + " when one lived in the mod's arena builder and the other in its input"
                        + " source");
        assertEquals(STONE_ITEM, arena.voxelBlockItem(FLOOR_X, TERRAIN_Y, TERRAIN_Z));
    }

    @Test
    void aWholeMatchsBlockRulesMoveTheChecksumTheTwoHostsExchange() {
        GameState agreed = kit();
        GameState breakRule = kit();
        GameState placeRule = kit();
        long open = Checksum.of(agreed);

        breakRule.breakableItemIds = new int[]{OAK_PLANKS_ITEM};
        placeRule.placeableItemIds = new int[]{OAK_PLANKS_ITEM};

        assertNotEquals(open, Checksum.of(breakRule),
                "two hosts that decoded different block rules out of the same setup blob would"
                        + " otherwise agree on every checksum they exchange right up until the"
                        + " first block, which is the desync this rule is supposed to make"
                        + " impossible");
        assertNotEquals(open, Checksum.of(placeRule));
        assertNotEquals(Checksum.of(breakRule), Checksum.of(placeRule));
    }

    @Test
    void anEmptyRuleListLeavesTheChecksumExactlyWhereItWas() {
        GameState s = kit();
        long before = Checksum.of(s);
        s.breakableItemIds = new int[0];
        s.placeableItemIds = new int[0];

        assertEquals(before, Checksum.of(s),
                "every match shipping today carries empty lists. If an empty list moved the"
                        + " checksum, the committed harness reference would have had to be"
                        + " re-recorded for a change that alters no behaviour, and the one thing"
                        + " that proves this change is behaviour-neutral would be gone");
    }

    @Test
    void theRulesSurviveTheCopyThatRollbackSavesAndRestores() {
        GameState s = kit();
        s.breakableItemIds = new int[]{OAK_PLANKS_ITEM};
        s.placeableItemIds = new int[]{TNT_ITEM};

        GameState restored = s.copy();

        assertArrayEquals(s.breakableItemIds, restored.breakableItemIds,
                "a rollback restores from copy(), so a rule that copy() drops is a rule that"
                        + " stops applying the moment a rollback happens - and rollbacks happen"
                        + " thousands of times a match");
        assertArrayEquals(s.placeableItemIds, restored.placeableItemIds);
        assertEquals(Checksum.of(s), Checksum.of(restored));
    }

    @Test
    void theWhitelistIsSearchedAsASortedSetSoTheDecoderMustNormalize() {
        GameState s = kit();
        s.breakableItemIds = MatchSetupFrame0Decoder.normalizeItemIds(
                new int[]{TNT_ITEM, OAK_PLANKS_ITEM, TNT_ITEM});

        assertArrayEquals(new int[]{OAK_PLANKS_ITEM, TNT_ITEM}, s.breakableItemIds);
        assertTrue(Combat.breakAllowed(s, OAK_PLANKS_ITEM),
                "Combat.breakAllowed binary searches the array. An unsorted list would answer"
                        + " `not allowed` for an item that IS listed, and it would do it on one"
                        + " host and not the other depending on the order the server happened to"
                        + " hand each of them");
        assertTrue(Combat.breakAllowed(s, TNT_ITEM));
        assertFalse(Combat.breakAllowed(s, PICKAXE_ITEM));

        GameState other = kit();
        other.breakableItemIds = MatchSetupFrame0Decoder.normalizeItemIds(
                new int[]{OAK_PLANKS_ITEM, TNT_ITEM});
        assertEquals(Checksum.of(s), Checksum.of(other),
                "and two hosts handed the same set in a different order have to checksum the same");
    }

    @Test
    void theRuleFilterStillHandsTheSimTheVeryInputItWasGivenWhenNothingIsForbidden() {
        GameState s = kit();
        for (int action = Input.BLOCK_NONE; action <= Input.BLOCK_CLOSE_CONTAINER; action++) {
            Input in = Input.NONE.withBlockAction(action, TX, PLACE_Y, TZ);
            assertSame(in, Combat.ruleFiltered(s, in),
                    "the block whitelist must not be folded into ruleFiltered: it needs the held"
                            + " item and the target block, which the Input alone does not name."
                            + " ruleFiltered stays the ACTION-only filter both hosts count clicks"
                            + " from, for action " + action);
        }
    }

    private static int swings(GameState s) {
        int n = 0;
        for (CombatEvent e : s.events) {
            if (e.type() == CombatEvent.SWING) {
                n++;
            }
        }
        return n;
    }

    @Test
    void aRefusedPlacementRaisesNoSwingForTheOtherSideToRender() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = kit();
        s.placeableItemIds = new int[]{OAK_PLANKS_ITEM};
        s.events.clear();

        Simulation.tick(s, arena, place(SLOT_TNT), Input.NONE);

        assertEquals(0, swings(s),
                "the swing event is what both peers render off; a refused place that still swung"
                        + " would show the opponent an arm animation for a block that never went"
                        + " up");
    }

    @Test
    void everySetOfRulesIsVisibleToTheFacetReportThatNamesADivergence() {
        GameState open = kit();
        GameState restricted = kit();
        restricted.breakableItemIds = new int[]{OAK_PLANKS_ITEM};

        int setup = Arrays.asList(StateFacets.NAMES).indexOf("setup");
        assertNotEquals(StateFacets.of(open)[setup], StateFacets.of(restricted)[setup],
                "the facet digest is what an incident report reads to say WHICH part of the state"
                        + " the two machines disagree about. A rule the facets cannot see is a"
                        + " desync the report has to call `unknown`");
    }
}
