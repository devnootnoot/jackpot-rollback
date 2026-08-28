package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class MeleeSightlineTest {
    private static final double GROUND_Y = 64.0;

    private static final int WALL_ITEM_ID = 900;
    private static final int COBWEB_ITEM_ID = 901;
    private static final int BUILD_ITEM_ID = 902;
    private static final int SUPPORT_ITEM_ID = 903;

    private static final double LANE_Z = 0.5;

    private static GameState faceOff(double victimX) {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        a.x = 0.0;
        a.y = GROUND_Y;
        a.z = LANE_Z;
        a.yaw = -90f;
        a.onGround = true;
        a.vy = -0.0784;
        a.health = 20f;
        a.attackTicker = 100;

        PlayerState v = g.players[1];
        v.x = victimX;
        v.y = GROUND_Y;
        v.z = LANE_Z;
        v.yaw = 90f;
        v.onGround = true;
        v.vy = -0.0784;
        v.health = 20f;
        v.attackTicker = 100;
        return g;
    }

    private static Input claimedHit() {
        return new Input(false, false, false, false, false, false, false, true, false, -90f, 0f, 0)
                .withMeleeHit(true);
    }

    private static void idleAt(GameState s, Arena arena, double victimX) {
        s.players[1].x = victimX;
        Simulation.tick(s, arena, Input.NONE, Input.NONE);
    }

    private static void wall(GameState s, int x) {
        s.blocks.place(x, 65, 0, WALL_ITEM_ID);
    }

    private static void cobweb(GameState s, int x) {
        s.cobwebItemId = COBWEB_ITEM_ID;
        s.cobwebs.put(BlockStore.key(x, 65, 0), COBWEB_ITEM_ID);
    }

    private static boolean swingLands(GameState s, Arena arena) {
        float before = s.players[1].health;
        Simulation.tick(s, arena, claimedHit(), Input.NONE);
        return s.players[1].health < before;
    }

    @Test
    void aHitThroughASolidWallIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState open = faceOff(2.5);
        assertTrue(swingLands(open, arena),
                "the same swing with nothing in the way has to land, or the wall proves nothing");

        GameState walled = faceOff(2.5);
        wall(walled, 1);
        assertFalse(swingLands(walled, arena),
                "a melee claim whose sightline crosses a placed block must be refused");
    }

    @Test
    void aHitThroughArenaTerrainIsRefused() {
        boolean[] grid = {true};
        Arena arena = new Arena(GROUND_Y, grid, 1, 65, 0, 1, 1, 1, new double[0][], Map.of());

        GameState s = faceOff(2.5);
        assertFalse(swingLands(s, arena),
                "arena solids occlude a melee claim exactly as placed blocks do");
    }

    @Test
    void miningTheWallAwayRestoresTheSightline() {
        boolean[] grid = {true};
        Arena arena = new Arena(GROUND_Y, grid, 1, 65, 0, 1, 1, 1, new double[0][], Map.of());

        GameState s = faceOff(2.5);
        s.brokenArena.add(BlockStore.key(1, 65, 0));

        assertTrue(swingLands(s, arena),
                "a voxel that has already been mined out must not keep occluding");
    }

    @Test
    void aHitThroughACobwebIsRefusedByTheCobwebRuleAlone() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState s = faceOff(2.5);
        cobweb(s, 1);
        assertTrue(s.blocks.isEmpty(),
                "no solid is involved, so only the cobweb rule can refuse this swing");

        assertFalse(swingLands(s, arena), "a cobweb in front of the claimed hull still stops the hit");

        GameState cleared = faceOff(2.5);
        assertTrue(swingLands(cleared, arena), "and it is the cobweb that stopped it, nothing else");
    }

    @Test
    void aCobwebPastTheRewoundHullNoLongerStopsTheHit() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.5);

        idleAt(s, arena, 2.5);
        idleAt(s, arena, 2.5);
        idleAt(s, arena, 2.5);
        cobweb(s, 3);
        s.players[1].x = 12.0;

        assertTrue(swingLands(s, arena),
                "the cobweb sits past the hull the claim was granted against, so it cannot occlude it");
    }

    @Test
    void aCobwebInFrontOfTheRewoundHullStillStopsTheHit() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.5);

        idleAt(s, arena, 2.5);
        idleAt(s, arena, 2.5);
        idleAt(s, arena, 2.5);
        cobweb(s, 1);
        s.players[1].x = 12.0;

        assertFalse(swingLands(s, arena),
                "a cobweb between the eye and the rewound hull occludes the claim it was granted for");
    }

    private static void idleAt(GameState s, Arena arena, double victimX, double victimZ) {
        s.players[1].x = victimX;
        s.players[1].z = victimZ;
        Simulation.tick(s, arena, Input.NONE, Input.NONE);
    }

    private static final double SIDE_LANE_Z = 2.5;

    private static GameState victimSteppedOutOfTheWebbedLane(Arena arena) {
        GameState s = faceOff(2.5);
        for (int i = 0; i < 3; i++) {
            idleAt(s, arena, 2.5, SIDE_LANE_Z);
        }
        s.players[1].z = LANE_Z;
        return s;
    }

    @Test
    void aCobwebIsTestedPerCandidateJustAsASolidIs() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState s = victimSteppedOutOfTheWebbedLane(arena);
        cobweb(s, 1);

        assertTrue(swingLands(s, arena),
                "the webbed lane only covers the newest candidates; an older candidate the attacker"
                        + " genuinely shared a frame with is clear of it, and a cobweb tested once"
                        + " against whichever hull the loop happened to return would have thrown"
                        + " that candidate away while a solid in the same place would not");
    }

    @Test
    void aCobwebCoveringEveryCandidateStillRefusesTheHit() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState s = victimSteppedOutOfTheWebbedLane(arena);
        for (int z = 0; z <= 3; z++) {
            s.cobwebItemId = COBWEB_ITEM_ID;
            s.cobwebs.put(BlockStore.key(1, 65, z), COBWEB_ITEM_ID);
        }

        assertFalse(swingLands(s, arena),
                "per candidate is not per excuse: webbing every lane refuses every candidate");
    }

    @Test
    void aMaxReachHitAtHighLatencyStillLandsPastAWallTheVictimHasSinceRunBehind() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(3.3);

        for (int i = 0; i < ClaimAuthority.WINDOW_FRAMES - 2; i++) {
            idleAt(s, arena, 3.3);
        }
        wall(s, 8);
        s.players[1].x = 9.5;

        assertTrue(swingLands(s, arena),
                "occlusion must be tested against the hull the claim was authorised against,"
                        + " not against where the victim happens to be standing now");
    }

    @Test
    void aWallInFrontOfTheRewoundHullRefusesTheHit() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(3.3);

        for (int i = 0; i < ClaimAuthority.WINDOW_FRAMES - 2; i++) {
            idleAt(s, arena, 3.3);
        }
        wall(s, 1);
        s.players[1].x = 9.0;

        assertFalse(swingLands(s, arena),
                "the rewound hull is behind a wall, so no candidate in the window is claimable");
    }

    private static GameState builder(Arena arena, double standZ) {
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = GROUND_Y;
        a.z = standZ;
        a.onGround = true;
        s.players[1].x = 40.0;
        TestKit.of(s).give(0, 0, 8,
                TestKit.item().itemId(BUILD_ITEM_ID).flags(ItemDict.FLAG_BLOCK));
        return s;
    }

    private static double eyeToCell(PlayerState a, int bx, int by, int bz) {
        double eye = Combat.eyeHeight(a);
        double cx = Math.max(bx, Math.min(a.x, bx + 1.0));
        double cy = Math.max(by, Math.min(a.y + eye, by + 1.0));
        double cz = Math.max(bz, Math.min(a.z, bz + 1.0));
        double dx = a.x - cx;
        double dy = a.y + eye - cy;
        double dz = a.z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void theBlockReachGateIsVanillasBoundWithNothingOfOurOwnOnTop() {
        assertEquals(4.5, Combat.BLOCK_REACH, 0.0,
                "Attributes.BLOCK_INTERACTION_RANGE is a RangedAttribute with default 4.5");
        assertEquals(1.0, Combat.BLOCK_INTERACTION_BUFFER, 0.0,
                "every isWithinBlockInteractionRange call site on the interaction path passes 1.0:"
                        + " handleUseItemOn, handleBlockBreakAction and handlePickItemFromBlock");
        assertEquals(5.5, Combat.blockReachLimit(), 1.0E-9,
                "Player.isWithinBlockInteractionRange is blockInteractionRange() + buffer, so"
                        + " 4.5 + 1.0, and vanilla's own buffer is already the lag slack");
    }

    @Test
    void aPlacementAtExactlyVanillasBoundIsRefusedBecauseVanillaComparesStrictly() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState s = builder(arena, 0.5);
        s.blocks.place(0, 64, 6, SUPPORT_ITEM_ID);
        assertEquals(5.5, eyeToCell(s.players[0], 0, 65, 6), 1.0E-9,
                "this cell is exactly vanilla's 4.5 + 1.0 server bound away");

        Simulation.tick(s, arena, Input.NONE.withBlockAction(Input.BLOCK_PLACE, 0, 65, 6), Input.NONE);

        assertFalse(s.blocks.contains(0, 65, 6),
                "isWithinBlockInteractionRange is new AABB(pos).distanceToSqr(eye) < d * d, so the"
                        + " bound itself is excluded and vanilla refuses this placement too");
    }

    @Test
    void aBlockAtSurvivalReachIsPlacedAndOneAtSixBlocksIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState near = builder(arena, 0.5);
        near.blocks.place(0, 64, 5, SUPPORT_ITEM_ID);
        assertEquals(4.5, eyeToCell(near.players[0], 0, 65, 5), 1.0E-9,
                "this cell is exactly vanilla survival block reach away");
        Simulation.tick(near, arena, Input.NONE.withBlockAction(Input.BLOCK_PLACE, 0, 65, 5), Input.NONE);
        assertTrue(near.blocks.contains(0, 65, 5),
                "a placement at vanilla survival reach must still be honoured");

        GameState far = builder(arena, 1.0);
        far.blocks.place(0, 64, 7, SUPPORT_ITEM_ID);
        assertEquals(6.0, eyeToCell(far.players[0], 0, 65, 7), 1.0E-9,
                "this cell is six blocks away");
        Simulation.tick(far, arena, Input.NONE.withBlockAction(Input.BLOCK_PLACE, 0, 65, 7), Input.NONE);
        assertFalse(far.blocks.contains(0, 65, 7),
                "six blocks is a block and a half past vanilla and must be refused");
    }
}
