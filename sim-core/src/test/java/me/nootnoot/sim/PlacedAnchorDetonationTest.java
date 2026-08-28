package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class PlacedAnchorDetonationTest {

    private static final double GROUND_Y = 64.0;
    private static final int ANCHOR_ITEM_ID = 7601;
    private static final int GLOWSTONE_ITEM_ID = 7602;
    private static final int PLANK_ITEM_ID = 7603;

    private static final int AX = 3;
    private static final int AY = 64;
    private static final int AZ = 0;
    private static final long AKEY = BlockStore.key(AX, AY, AZ);

    private static final int ANCHOR_SLOT = 0;
    private static final int GLOWSTONE_SLOT = 1;

    private static GameState duel() {
        GameState s = new GameState();
        s.vanillaBuild = true;
        PlayerState p = s.players[0];
        p.x = 0.5;
        p.y = GROUND_Y;
        p.z = 0.5;
        p.yaw = -90f;
        p.onGround = true;
        p.health = 20f;
        p.maxHealth = 20f;
        PlayerState v = s.players[1];
        v.x = 10_000.0;
        v.y = GROUND_Y;
        v.z = 0.5;
        v.health = 20f;
        v.maxHealth = 20f;

        BlockProps.Builder b = new BlockProps.Builder();
        b.add(ANCHOR_ITEM_ID, 0f, 1200f, ANCHOR_ITEM_ID, -1, ItemDict.TOOL_NONE, false);
        b.add(GLOWSTONE_ITEM_ID, 0f, 0.3f, GLOWSTONE_ITEM_ID, -1, ItemDict.TOOL_NONE, false);
        b.add(PLANK_ITEM_ID, 0f, 3f, PLANK_ITEM_ID, -1, ItemDict.TOOL_NONE, false);
        s.blockProps = b.build();

        TestKit kit = TestKit.of(s);
        int anchor = kit.add(TestKit.item().itemId(ANCHOR_ITEM_ID).maxStack(64)
                .flags(ItemDict.FLAG_RESPAWN_ANCHOR));
        int glow = kit.add(TestKit.item().itemId(GLOWSTONE_ITEM_ID).maxStack(64)
                .flags(ItemDict.FLAG_GLOWSTONE));
        kit.put(0, ANCHOR_SLOT, anchor, 4);
        kit.put(0, GLOWSTONE_SLOT, glow, 8);
        return s;
    }

    private static void plankRing(GameState s) {
        for (int x = AX - 2; x <= AX + 2; x++) {
            for (int z = AZ - 2; z <= AZ + 2; z++) {
                if (x == AX && z == AZ) {
                    continue;
                }
                s.blocks.place(x, AY, z, PLANK_ITEM_ID);
                s.blockResistance.put(BlockStore.key(x, AY, z), 3f);
            }
        }
    }

    private static Input act(int action, int heldSlot) {
        return new Input(false, false, false, false, false, false, false, false, false,
                -90f, 0f, heldSlot).withBlockAction(action, AX, AY, AZ);
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
    void anAnchorAPlayerPlacedCharpedAndSetOffIsGoneAndTookTheTerrainWithIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        PlayerState p = s.players[0];

        Simulation.tick(s, arena, act(Input.BLOCK_PLACE_ANCHOR, ANCHOR_SLOT), Input.NONE);
        assertTrue(s.blocks.contains(AX, AY, AZ),
                "the place has to land first - everything after this asserts about a PLAYER-PLACED"
                        + " anchor, not one a test dropped into the block store");
        assertEquals(0, (int) s.anchors.get(AKEY), "a freshly placed anchor is uncharged");
        assertEquals(3, p.slotCount[ANCHOR_SLOT], "placing it must spend one");

        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena, act(Input.BLOCK_CHARGE_ANCHOR, GLOWSTONE_SLOT), Input.NONE);
        assertEquals(1, (int) s.anchors.get(AKEY), "one glowstone is one charge");
        assertEquals(7, p.slotCount[GLOWSTONE_SLOT], "charging must spend one glowstone");

        plankRing(s);
        idle(s, arena, Combat.USE_REPEAT_DELAY);
        int before = s.blocks.size();

        Simulation.tick(s, arena, act(Input.BLOCK_DETONATE_ANCHOR, ANCHOR_SLOT), Input.NONE);

        assertEquals(1, explosions(s), "a charged anchor detonated in an arena has to go off");
        assertFalse(s.blocks.contains(AX, AY, AZ),
                "the anchor block itself must be gone on the tick it detonates - vanilla"
                        + " RespawnAnchorBlock.explode calls level.removeBlock(pos, false) BEFORE"
                        + " it calls level.explode");
        assertNull(s.anchors.get(AKEY), "and its charge with it");
        assertNull(s.blockResistance.get(AKEY),
                "and the 1200 blast resistance the placement stamped on that cell");
        assertTrue(s.blocks.size() < before - 1,
                "vanilla explodes an anchor at power 5.0F with ExplosionInteraction.BLOCK, so the"
                        + " soft blocks around it have to come down too; blocks went " + before
                        + " -> " + s.blocks.size());
    }

    @Test
    void aPlacedAnchorAtZeroChargeIsNotDetonatable() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();

        Simulation.tick(s, arena, act(Input.BLOCK_PLACE_ANCHOR, ANCHOR_SLOT), Input.NONE);
        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena, act(Input.BLOCK_DETONATE_ANCHOR, ANCHOR_SLOT), Input.NONE);

        assertEquals(0, explosions(s),
                "vanilla useWithoutItem only reaches explode() when CHARGE > 0");
        assertTrue(s.blocks.contains(AX, AY, AZ), "and the block stays put");
    }

    private static void detonateAFreshAnchor(GameState s, Arena arena) {
        Simulation.tick(s, arena, act(Input.BLOCK_PLACE_ANCHOR, ANCHOR_SLOT), Input.NONE);
        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena, act(Input.BLOCK_CHARGE_ANCHOR, GLOWSTONE_SLOT), Input.NONE);
        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena, act(Input.BLOCK_DETONATE_ANCHOR, ANCHOR_SLOT), Input.NONE);
    }

    private static Arena voxelFloor() {
        int baseX = AX - 3;
        int baseY = AY - 1;
        int baseZ = AZ - 3;
        int sx = 7;
        int sy = 1;
        int sz = 7;
        boolean[] grid = new boolean[sx * sy * sz];
        java.util.Arrays.fill(grid, true);
        return new Arena(GROUND_Y, grid, baseX, baseY, baseZ, sx, sy, sz, new double[0][],
                java.util.Map.of(), java.util.Map.of());
    }

    @Test
    void theArenaFloorOnlyComesDownWhenTheModeAllowsBuilding() {
        Arena arena = voxelFloor();
        GameState guarded = duel();
        guarded.vanillaBuild = false;
        detonateAFreshAnchor(guarded, arena);
        assertEquals(1, explosions(guarded), "the anchor still goes off");
        assertFalse(guarded.blocks.contains(AX, AY, AZ), "and still removes itself");
        assertTrue(guarded.brokenArena.isEmpty(),
                "a mode that forbids building keeps its arena: this is the ONLY reason an anchor"
                        + " blast leaves the floor intact, and it is a mode flag the plugin sets"
                        + " from Game.isAllBuildingAllowed(), not a missing explosion");

        GameState open = duel();
        detonateAFreshAnchor(open, arena);
        assertFalse(open.brokenArena.isEmpty(),
                "with building on, power 5 at the anchor's own centre has to eat arena voxels -"
                        + " vanilla passes ExplosionInteraction.BLOCK");
    }
}
