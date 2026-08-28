package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;
import org.junit.jupiter.api.Test;

class ArrowClaimTest {
    private static final double GROUND_Y = 64.0;

    private static final int WALL_ITEM_ID = 910;

    private static final int ARROW_ID = 7;

    private static final double LANE_Z = 0.5;
    private static final double LANE_Y = 65.2;

    private static final double VICTIM_X = 8.0;
    private static final double ARROW_X = 8.5;
    private static final double ARROW_VX = 3.0;

    private static final int WALL_CELL_X = 6;

    private static GameState duel(Arena arena, double victimX) {
        GameState s = HarnessScenarios.duel(arena);
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.z = LANE_Z;

        PlayerState v = s.players[1];
        v.x = victimX;
        v.y = GROUND_Y;
        v.z = LANE_Z;
        return s;
    }

    private static ProjectileState arrow(GameState s, double x, double vx) {
        ProjectileState p = new ProjectileState();
        p.id = ARROW_ID;
        p.type = ProjectileState.TYPE_ARROW;
        p.owner = 0;
        p.x = x;
        p.y = LANE_Y;
        p.z = LANE_Z;
        p.vx = vx;
        p.damage = Projectiles.ARROW_DAMAGE;
        p.fresh = false;
        s.projectiles.add(p);
        s.nextProjectileId = ARROW_ID + 1;
        return p;
    }

    private static Input claim() {
        return Input.NONE.withProjectileHit(ARROW_ID);
    }

    private static void wall(GameState s, int x) {
        s.blocks.place(x, 64, 0, WALL_ITEM_ID);
        s.blocks.place(x, 65, 0, WALL_ITEM_ID);
    }

    @Test
    void aRefusedClaimLeavesTheArrowFlyingInsteadOfFreezingIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, 60.0);
        ProjectileState p = arrow(s, 0.0, 1.0);
        p.y = 70.0;

        double startY = p.y;
        for (int frame = 0; frame < 3; frame++) {
            Simulation.tick(s, arena, claim(), Input.NONE);
        }

        assertFalse(p.dead, "a refused claim must not consume the arrow");
        assertTrue(p.x > 2.5,
                "a refused claim must fall through to physics, not abort the tick: the arrow"
                        + " has to keep travelling");
        assertTrue(p.y < startY, "gravity must still have run on the refused tick");
        assertTrue(p.vy < 0.0, "the refused tick must still integrate velocity");
        assertEquals(20.0f, s.players[1].health, "nothing was hit, so nothing may take damage");
    }

    @Test
    void aClaimedArrowHitLandsWhenTheLaneIsClear() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, VICTIM_X);
        ProjectileState p = arrow(s, ARROW_X, ARROW_VX);

        Simulation.tick(s, arena, claim(), Input.NONE);

        assertTrue(s.players[1].health < 20.0f,
                "a claim with nothing in the way has to land, or the wall case proves nothing");
        assertTrue(p.dead, "an authorised claim consumes the arrow");
    }

    @Test
    void aClaimedArrowHitThroughAWallIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, VICTIM_X);
        ProjectileState p = arrow(s, ARROW_X, ARROW_VX);
        wall(s, WALL_CELL_X);

        Simulation.tick(s, arena, claim(), Input.NONE);

        assertEquals(20.0f, s.players[1].health,
                "an arrow claim whose sightline crosses a placed block must be refused");
        assertFalse(p.dead, "the refused arrow must survive to collide with the wall itself");
        assertTrue(p.x > ARROW_X, "the refused arrow must have kept moving toward the wall");
    }

    @Test
    void aClaimedArrowHitThroughArenaTerrainIsRefused() {
        boolean[] grid = {true, true};
        Arena arena = new Arena(GROUND_Y, grid, WALL_CELL_X, 64, 0, 1, 2, 1,
                new double[0][], java.util.Map.of());
        GameState s = duel(arena, VICTIM_X);
        arrow(s, ARROW_X, ARROW_VX);

        Simulation.tick(s, arena, claim(), Input.NONE);
        assertEquals(20.0f, s.players[1].health,
                "arena solids occlude an arrow claim exactly as placed blocks do");
    }

    private static Input selfClaim() {
        return Input.NONE.withProjectileHit(ARROW_ID | Input.PROJECTILE_HIT_SELF);
    }

    private static ProjectileState arrowOnTheOwner(GameState s) {
        PlayerState a = s.players[0];
        ProjectileState p = arrow(s, a.x, 0.1);
        p.y = a.y + 1.0;
        p.z = a.z;
        return p;
    }

    @Test
    void aClientCannotResolveItsOwnFreshArrowAgainstItself() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, 60.0);
        ProjectileState p = arrowOnTheOwner(s);

        assertFalse(p.leftOwner, "the arrow has not cleared the shooter yet");
        assertFalse(ClaimAuthority.arrowClaim(s, arena, p, p.owner),
                "vanilla projectiles cannot hit their own owner until leftOwner flips");

        float before = s.players[0].health;
        Simulation.tick(s, arena, selfClaim(), Input.NONE);

        assertEquals(before, s.players[0].health,
                "PROJECTILE_HIT_SELF must not let a client damage itself on demand, which is how"
                        + " it would buy invulnerability frames on a tick of its choosing");
        assertFalse(p.dead, "and the refused claim must not consume the arrow either");
    }

    @Test
    void anArrowThatHasLeftTheOwnerMayStillComeBackDown() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, 60.0);
        ProjectileState p = arrowOnTheOwner(s);
        p.leftOwner = true;

        assertTrue(ClaimAuthority.arrowClaim(s, arena, p, p.owner),
                "once the arrow is clear of its shooter vanilla lets it hit them, so we must too");
    }

    @Test
    void aSelfClaimIsJudgedWhereTheOwnerIsNowNotWhereTheyStood() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, 60.0);
        PlayerState a = s.players[0];
        ProjectileState p = arrowOnTheOwner(s);
        p.leftOwner = true;

        assertTrue(ClaimAuthority.arrowClaim(s, arena, p, p.owner),
                "control: while the owner is stood on their own arrow the self hit is real");

        ClaimAuthority.record(s);
        a.x += 5.0;

        assertFalse(ClaimAuthority.arrowClaim(s, arena, p, p.owner),
                "the rewind window exists to forgive the OPPONENT's latency; an owner is never"
                        + " stale to themselves, so a self claim judged against a hull they have"
                        + " already left is a free knockback the client picks the timing of");
    }

    @Test
    void theRewindWindowStillCoversTheOpponentAfterTheSelfGuardTightens() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, VICTIM_X);
        ProjectileState p = arrow(s, ARROW_X, ARROW_VX);

        ClaimAuthority.record(s);
        s.players[1].x = VICTIM_X + 5.0;

        assertTrue(ClaimAuthority.arrowClaim(s, arena, p, 1),
                "narrowing the self claim must not narrow the claim against the other player");
    }

    @Test
    void anArrowClaimAgainstTheOpponentIsUnaffectedByTheOwnerGuard() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, VICTIM_X);
        ProjectileState p = arrow(s, ARROW_X, ARROW_VX);

        assertFalse(p.leftOwner, "a fresh arrow still has to be able to hit the other player");
        assertTrue(ClaimAuthority.arrowClaim(s, arena, p, 1));
    }

    @Test
    void anArrowLeavesItsOwnerOnceItIsClearOfTheirHull() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, 60.0);
        ProjectileState p = arrow(s, 0.0, 1.0);
        p.y = 70.0;

        for (int frame = 0; frame < 3; frame++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }

        assertTrue(p.leftOwner,
                "an arrow well clear of the shooter must record that it left them, or the guard"
                        + " would refuse a legitimate self hit forever");
    }

    @Test
    void miningTheWallAwayRestoresTheArrowSightline() {
        boolean[] grid = {true, true};
        Arena arena = new Arena(GROUND_Y, grid, WALL_CELL_X, 64, 0, 1, 2, 1,
                new double[0][], java.util.Map.of());
        GameState s = duel(arena, VICTIM_X);
        arrow(s, ARROW_X, ARROW_VX);
        s.brokenArena.add(BlockStore.key(WALL_CELL_X, 64, 0));
        s.brokenArena.add(BlockStore.key(WALL_CELL_X, 65, 0));

        Simulation.tick(s, arena, claim(), Input.NONE);
        assertTrue(s.players[1].health < 20.0f,
                "a voxel that has already been mined out must not keep occluding");
    }

    private static void reArm(ProjectileState p, GameState s) {
        p.x = ARROW_X;
        p.y = LANE_Y;
        p.z = LANE_Z;
        p.vx = ARROW_VX;
        p.vy = 0.0;
        p.vz = 0.0;
        s.players[1].x = VICTIM_X;
        s.players[1].y = GROUND_Y;
        s.players[1].z = LANE_Z;
    }

    @Test
    void aClaimIsOfferedOnceAndLatchedEvenWhenTheFirstOfferIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, 60.0);
        ProjectileState p = arrow(s, ARROW_X, ARROW_VX);

        Simulation.tick(s, arena, claim(), Input.NONE);
        assertEquals(20.0f, s.players[1].health,
                "the probe tick is geometrically hopeless: the victim is 50 blocks away");
        assertTrue(p.claimSpent, "but offering it at all has to consume the claim");

        reArm(p, s);
        Simulation.tick(s, arena, claim(), Input.NONE);

        assertEquals(20.0f, s.players[1].health,
                "stepOne read projectileHit fresh on EVERY tick of flight, so a client could hold"
                        + " the claim bit down and have the same arrow re-tested until one tick"
                        + " happened to satisfy the geometry; a claim is consumed once");
        assertFalse(p.dead, "and the latched arrow keeps flying under its own physics");
    }

    @Test
    void theVerySameSecondTickLandsWhenTheClaimWasNotAlreadySpent() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, 60.0);
        ProjectileState p = arrow(s, ARROW_X, ARROW_VX);

        Simulation.tick(s, arena, Input.NONE, Input.NONE);
        assertFalse(p.claimSpent, "a tick with no claim bit must not burn the latch");

        reArm(p, s);
        Simulation.tick(s, arena, claim(), Input.NONE);

        assertTrue(s.players[1].health < 20.0f,
                "control: the second tick is identical apart from the earlier probe, so the latch"
                        + " and not the geometry is what refused the previous case");
    }

    @Test
    void anAcceptedClaimIsAlsoSpentSoAnArrowCanNeverBeClaimedTwice() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel(arena, VICTIM_X);
        ProjectileState p = arrow(s, ARROW_X, ARROW_VX);

        Simulation.tick(s, arena, claim(), Input.NONE);
        assertTrue(p.claimSpent, "an accepted claim is spent too");
        assertTrue(p.dead, "and it consumed the arrow");
    }
}
