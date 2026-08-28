package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class BlockStoreTest {
    private static final double GROUND_Y = 64.0;

    private static final float SOFT_HARDNESS = 1f / 40f;

    private static final int SUPPORT_ITEM_ID = 200;
    private static final int OBSIDIAN_ITEM_ID = 201;

    private static void softBlocks(GameState s, int... ids) {
        me.nootnoot.sim.state.BlockProps.Builder b = new me.nootnoot.sim.state.BlockProps.Builder();
        for (int id : ids) {
            b.add(id, SOFT_HARDNESS, 6f, id, -1, me.nootnoot.sim.state.ItemDict.TOOL_NONE, false);
        }
        s.blockProps = b.build();
        s.players[0].onGround = false;
    }

    private static void instantBlocks(GameState s, int... ids) {
        me.nootnoot.sim.state.BlockProps.Builder b = new me.nootnoot.sim.state.BlockProps.Builder();
        for (int id : ids) {
            b.add(id, 0f, 6f, id, -1, me.nootnoot.sim.state.ItemDict.TOOL_NONE, false);
        }
        s.blockProps = b.build();
    }

    @Test
    void placedBlockIsStoredAndChecksummed() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        standBy(s, 100, 100, 100);
        giveBlock(s, 0, 8);
        support(s, 100, 100, 100);
        long before = Checksum.of(s);
        Input place = Input.NONE.withBlockAction(Input.BLOCK_PLACE, 100, 100, 100);
        Simulation.tick(s, arena, place, Input.NONE);
        assertTrue(s.blocks.contains(100, 100, 100), "block should be placed");
        assertNotEquals(before, Checksum.of(s), "checksum must reflect the placed block");
    }

    @Test
    void miningBreaksAfterEnoughProgress() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        standBy(s, 100, 100, 100);
        softBlocks(s, 1);
        s.blocks.place(100, 100, 100, 1);
        Input breaking = Input.NONE.withBlockAction(Input.BLOCK_BREAK, 100, 100, 100);
        Simulation.tick(s, arena, breaking, Input.NONE);
        assertTrue(s.blocks.contains(100, 100, 100));
        Simulation.tick(s, arena, breaking, Input.NONE);
        assertTrue(s.blocks.contains(100, 100, 100));
        Simulation.tick(s, arena, breaking, Input.NONE);
        assertFalse(s.blocks.contains(100, 100, 100), "block should break once progress >= 1");
    }

    @Test
    void miningProgressResetsWhenTargetChanges() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.blocks.place(100, 100, 100, 1);
        s.blocks.place(101, 100, 100, 1);
        standBy(s, 100, 100, 100);
        softBlocks(s, 1);
        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_BREAK, 100, 100, 100), Input.NONE);

        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_BREAK, 101, 100, 100), Input.NONE);
        assertTrue(s.blocks.contains(100, 100, 100), "first target should NOT break (we switched away)");
        assertTrue(s.blocks.contains(101, 100, 100), "second target only reached 0.9");
    }

    @Test
    void copyIsIndependentAndDeterministic() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.blocks.place(5, 6, 7, 42);
        GameState c = s.copy();
        assertEquals(Checksum.of(s), Checksum.of(c), "copy must checksum-equal the original");
        c.blocks.place(8, 9, 10, 1);
        assertFalse(s.blocks.contains(8, 9, 10), "mutating the copy must not affect the original");
        assertNotEquals(Checksum.of(s), Checksum.of(c));
    }

    @Test
    void crystalPlacesAndDetonatesOnHit() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);

        standBy(s, 100, 100, 100);
        giveCrystal(s);
        obsidianBase(s, 100, 100, 100);
        Input place = Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 100, 100, 100);
        Simulation.tick(s, arena, place, Input.NONE);
        assertEquals(1, s.crystals.size(), "crystal should be placed");

        s.players[0].x = 100.5;
        s.players[0].y = 101.0;
        s.players[0].z = 100.5;
        float hpBefore = s.players[0].health;
        Input hit = new Input(false, false, false, false, false, false, false, true, false,
                0f, 0f, 0).withBlockAction(Input.BLOCK_HIT_CRYSTAL, 100, 100, 100);
        Simulation.tick(s, arena, hit, Input.NONE);
        assertEquals(0, s.crystals.size(), "crystal should be removed after detonation");
        assertTrue(s.players[0].health < hpBefore, "point-blank crystal must damage the player");
    }

    @Test
    void crystalStateIsCopiedAndChecksummed() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        standBy(s, 60, 80, 60);
        giveCrystal(s);
        obsidianBase(s, 60, 80, 60);
        long before = Checksum.of(s);
        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 60, 80, 60), Input.NONE);
        assertNotEquals(before, Checksum.of(s), "checksum must reflect the placed crystal");
        GameState c = s.copy();
        assertEquals(Checksum.of(s), Checksum.of(c));
        c.crystals.clear();
        assertEquals(1, s.crystals.size(), "copy must be independent of the original");
    }

    @Test
    void anchorChargesThenDetonates() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        standBy(s, 100, 100, 100);
        TestKit kit = TestKit.of(s);
        kit.give(0, 0, 1,
                TestKit.item().itemId(9).flags(me.nootnoot.sim.state.ItemDict.FLAG_RESPAWN_ANCHOR));
        kit.give(0, 1, 8,
                TestKit.item().itemId(10).flags(me.nootnoot.sim.state.ItemDict.FLAG_GLOWSTONE));
        support(s, 100, 100, 100);
        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_PLACE_ANCHOR, 100, 100, 100), Input.NONE);
        assertTrue(s.blocks.contains(100, 100, 100), "anchor block placed");
        assertEquals(0, (int) s.anchors.get(me.nootnoot.sim.state.BlockStore.key(100, 100, 100)));

        settle(s, arena);
        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_DETONATE_ANCHOR, 100, 100, 100), Input.NONE);
        assertTrue(s.blocks.contains(100, 100, 100), "uncharged anchor must NOT detonate");

        Input charge = Input.NONE.withHeldSlot(1)
                .withBlockAction(Input.BLOCK_CHARGE_ANCHOR, 100, 100, 100);
        settle(s, arena);
        Simulation.tick(s, arena, charge, Input.NONE);
        settle(s, arena);
        Simulation.tick(s, arena, charge, Input.NONE);
        assertEquals(2, (int) s.anchors.get(me.nootnoot.sim.state.BlockStore.key(100, 100, 100)));

        s.players[0].x = 100.5;
        s.players[0].y = 100.5;
        s.players[0].z = 100.5;
        float hp = s.players[0].health;
        settle(s, arena);
        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_DETONATE_ANCHOR, 100, 100, 100), Input.NONE);
        assertFalse(s.blocks.contains(100, 100, 100), "charged anchor detonates and is removed");
        assertTrue(s.anchors.isEmpty(), "anchor charge entry cleared on detonation");
        assertTrue(s.players[0].health < hp, "anchor blast must damage a nearby player");
    }

    @Test
    void droppedItemFallsThenIsPickedUp() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);

        TestKit.of(s).give(0, 0, 1, TestKit.item().itemId(99));
        Input dropWithItem = Input.NONE.withDrop(true, false);
        Simulation.tick(s, arena, dropWithItem, Input.NONE);
        assertEquals(1, s.items.size(), "an item entity should spawn on drop");

        s.players[0].x = s.items.get(0).x;
        s.players[0].z = s.items.get(0).z;
        s.players[0].y = s.items.get(0).y;
        int seqBefore = s.players[0].pickupSeq;
        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        assertEquals(seqBefore, s.players[0].pickupSeq, "no pickup during the delay");

        for (int i = 0; i < ItemEntities.DEFAULT_PICKUP_DELAY + 2 && !s.items.isEmpty(); i++) {
            s.players[0].x = s.items.isEmpty() ? s.players[0].x : s.items.get(0).x;
            s.players[0].z = s.items.isEmpty() ? s.players[0].z : s.items.get(0).z;
            s.players[0].y = s.items.isEmpty() ? s.players[0].y : s.items.get(0).y;
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
        assertTrue(s.items.isEmpty(), "item should be picked up after the delay");
        assertTrue(s.players[0].pickupSeq > seqBefore, "pickupSeq must bump on pickup");
        assertEquals(99, s.players[0].lastPickupItemId);
    }

    @Test
    void vanillaBuildMinesArenaTerrainAndFallsThrough() {
        int sx = 3;
        int sy = 1;
        int sz = 3;
        boolean[] grid = new boolean[sx * sy * sz];
        java.util.Arrays.fill(grid, true);
        Arena arena = new Arena(0.0, grid, 0, 64, 0, sx, sy, sz, new double[0][], java.util.Map.of());
        GameState s = HarnessScenarios.duel(arena);
        s.vanillaBuild = true;
        instantBlocks(s, 0);
        s.players[0].x = 1.5;
        s.players[0].y = 65.0;
        s.players[0].z = 1.5;
        s.players[0].vy = 0.0;
        for (int i = 0; i < 5; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
        double restY = s.players[0].y;
        assertTrue(restY >= 64.9, "player should rest on the arena floor, was " + restY);

        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_BREAK, 1, 64, 1), Input.NONE);
        assertTrue(s.brokenArena.contains(me.nootnoot.sim.state.BlockStore.key(1, 64, 1)), "voxel mined away");

        for (int i = 0; i < 6; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
        assertTrue(s.players[0].y < restY - 0.5, "player should fall through the mined floor, y=" + s.players[0].y);
    }

    @Test
    void arenaTerrainNotMineableWithoutVanillaBuild() {
        boolean[] grid = {true};
        Arena arena = new Arena(0.0, grid, 0, 64, 0, 1, 1, 1, new double[0][], java.util.Map.of());
        GameState s = HarnessScenarios.duel(arena);
        s.vanillaBuild = false;
        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_BREAK, 0, 64, 0), Input.NONE);
        assertTrue(s.brokenArena.isEmpty(), "arena terrain must NOT be mineable outside vanilla-build duels");
    }

    @Test
    void projectilesPassThroughMinedArenaHoles() {
        boolean[] grid = {true};
        Arena arena = new Arena(0.0, grid, 1, 64, 0, 1, 1, 1, new double[0][], java.util.Map.of());
        GameState s = HarnessScenarios.duel(arena);

        java.util.function.Supplier<Boolean> arrowStops = () -> {
            GameState g = s.copy();
            g.projectiles.clear();
            me.nootnoot.sim.state.ProjectileState arrow = new me.nootnoot.sim.state.ProjectileState();
            arrow.type = me.nootnoot.sim.state.ProjectileState.TYPE_ARROW;
            arrow.owner = 0;
            arrow.x = 0.5;
            arrow.y = 64.5;
            arrow.z = 0.5;
            arrow.vx = 1.0;
            arrow.fresh = false;
            g.projectiles.add(arrow);
            for (int i = 0; i < 4 && !g.projectiles.isEmpty() && !g.projectiles.get(0).stuck; i++) {
                Projectiles.tick(g, arena, Input.NONE, Input.NONE);
            }
            return !g.projectiles.isEmpty() && g.projectiles.get(0).stuck;
        };

        assertTrue(arrowStops.get(), "arrow should lodge in intact terrain");
        s.brokenArena.add(me.nootnoot.sim.state.BlockStore.key(1, 64, 0));
        assertFalse(arrowStops.get(), "arrow should fly through the mined hole");
    }

    @Test
    void explosionBreaksBlocksButObsidianSurvives() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);

        standBy(s, 100, 100, 100);
        giveCrystal(s);
        obsidianBase(s, 100, 100, 100);
        s.players[1].x = -50;
        s.blocks.place(101, 101, 100, 1);
        s.blockResistance.put(me.nootnoot.sim.state.BlockStore.key(101, 101, 100), 1200f);
        s.blocks.place(99, 101, 100, 2);
        s.blockResistance.put(me.nootnoot.sim.state.BlockStore.key(99, 101, 100), 6f);
        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 100, 100, 100), Input.NONE);
        Simulation.tick(s, arena,
                new Input(false, false, false, false, false, false, false, true, false, 0f, 0f, 0)
                        .withBlockAction(Input.BLOCK_HIT_CRYSTAL, 100, 100, 100),
                Input.NONE);
        assertFalse(s.blocks.contains(99, 101, 100), "breakable block should be destroyed by the blast");
        assertTrue(s.blocks.contains(101, 101, 100), "obsidian should survive the blast");
    }

    @Test
    void replayingSameInputsConverges() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState a = HarnessScenarios.duel(arena);
        GameState b = HarnessScenarios.duel(arena);
        standBy(a, 50, 70, 50);
        standBy(b, 50, 70, 50);
        giveBlock(a, 0, 8);
        giveBlock(b, 0, 8);
        support(a, 50, 70, 50);
        support(b, 50, 70, 50);
        Input place = Input.NONE.withBlockAction(Input.BLOCK_PLACE, 50, 70, 50);
        for (int i = 0; i < 5; i++) {
            Input in = i == 0 ? place : Input.NONE;
            Simulation.tick(a, arena, in, Input.NONE);
            Simulation.tick(b, arena, in, Input.NONE);
        }
        assertEquals(Checksum.of(a), Checksum.of(b), "same inputs must produce identical block state");
    }

    private static void settle(GameState s, Arena arena) {
        PlayerState p = s.players[0];
        double x = p.x;
        double y = p.y;
        double z = p.z;
        double vy = p.vy;
        for (int i = 0; i < Combat.USE_REPEAT_DELAY; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
        p.x = x;
        p.y = y;
        p.z = z;
        p.vy = vy;
    }

    private static void support(GameState s, int x, int y, int z) {
        s.blocks.place(x, y - 1, z, SUPPORT_ITEM_ID);
    }

    private static void obsidianBase(GameState s, int x, int y, int z) {
        s.obsidianItemId = OBSIDIAN_ITEM_ID;
        s.blocks.place(x, y, z, OBSIDIAN_ITEM_ID);
    }

    private static void giveBlock(GameState s, int slot, int count) {
        TestKit.of(s).give(0, slot, count,
                TestKit.item().itemId(7).flags(me.nootnoot.sim.state.ItemDict.FLAG_BLOCK));
    }

    private static void giveCrystal(GameState s) {
        TestKit.of(s).give(0, 0, 4,
                TestKit.item().itemId(8).flags(me.nootnoot.sim.state.ItemDict.FLAG_END_CRYSTAL));
    }

    private static void standBy(GameState s, int x, int y, int z) {
        s.players[0].x = x + 0.5;
        s.players[0].y = y;
        s.players[0].z = z + 2.5;
    }
}
