package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;
import org.junit.jupiter.api.Test;

class ClaimMarginTest {
    private static final double GROUND_Y = 64.0;

    private static final double LANE_Z = 0.5;
    private static final double LANE_Y = 65.0;

    private static final double VICTIM_X = 6.3;
    private static final double ARROW_X = 6.1;
    private static final double ARROW_VX = 0.8;

    private static final int COBWEB_ITEM_ID = 921;

    private static final float MID_TURN_YAW = -10f;

    private static GameState faceOff(double victimX) {
        GameState g = new GameState();
        PlayerState a = g.players[0];
        a.x = 0.0;
        a.y = GROUND_Y;
        a.z = LANE_Z;
        a.yaw = -90f;
        a.onGround = true;
        a.health = 20f;
        a.attackTicker = 100;

        PlayerState v = g.players[1];
        v.x = victimX;
        v.y = GROUND_Y;
        v.z = LANE_Z;
        v.yaw = 90f;
        v.onGround = true;
        v.health = 20f;
        v.attackTicker = 100;
        return g;
    }

    private static ProjectileState arrow(GameState s, double z) {
        ProjectileState p = new ProjectileState();
        p.id = 5;
        p.type = ProjectileState.TYPE_ARROW;
        p.owner = 0;
        p.x = ARROW_X;
        p.y = LANE_Y;
        p.z = z;
        p.vx = ARROW_VX;
        p.damage = Projectiles.ARROW_DAMAGE;
        p.fresh = false;
        s.projectiles.add(p);
        return p;
    }

    @Test
    void theArrowMarginIsTheMargingTheClientItselfClaimsWith() {
        assertEquals(0.1, ClaimAuthority.ARROW_MARGIN, 0.0,
                "the claim margin exists only to absorb the difference between the box the MOD"
                        + " tested and the hull the sim rebuilds, and McInputSource tests"
                        + " oppEnt.getBoundingBox().inflate(0.1), so 0.1 is every bit of slack an"
                        + " honest client can consume; the sim's own arrow collision in"
                        + " Projectiles.stepOne inflates the victim hull by nothing at all, and"
                        + " vanilla's ProjectileUtil.computeMargin is max(0.0F, min(0.3F,"
                        + " (tickCount - 2) / 20.0F)), a ramp that only reaches 0.3 after eight"
                        + " ticks of flight, so a flat 0.3 was above vanilla for every close shot");
    }

    @Test
    void anArrowClaimOutsideTheClientMarginIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(VICTIM_X);
        ProjectileState p = arrow(s, LANE_Z + 0.7);

        assertFalse(ClaimAuthority.arrowClaim(s, arena, p, 1),
                "the victim hull reaches z 0.8 and the arrow flies at 1.2, so the gap is 0.4 and"
                        + " no margin this side of vanilla's ceiling can cover it");
    }

    @Test
    void anArrowClaimInsideTheClientMarginStillLands() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(VICTIM_X);
        ProjectileState p = arrow(s, LANE_Z + 0.35);

        assertTrue(ClaimAuthority.arrowClaim(s, arena, p, 1),
                "a 0.05 gap is inside the 0.1 the mod itself inflated the opponent box by, so"
                        + " tightening the margin must not start refusing what the client saw");
    }

    @Test
    void aGapVanillaWouldHaveForgivenButNoHonestClientCanProduceIsNowRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(VICTIM_X);
        ProjectileState p = arrow(s, LANE_Z + 0.55);

        assertFalse(ClaimAuthority.arrowClaim(s, arena, p, 1),
                "a 0.25 gap sat inside the old flat 0.3 but outside the mod's own 0.1 test, so it"
                        + " is slack only a tampered client could ever have asked for");
    }

    @Test
    void theArrowClaimSegmentIsTheOneStepTheInputDelayOwes() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(VICTIM_X);
        ProjectileState p = arrow(s, LANE_Z);
        p.x = 7.0;

        double face = VICTIM_X + Simulation.PLAYER_WIDTH * 0.5 + ClaimAuthority.ARROW_MARGIN;
        assertTrue(p.x > face, "the arrow is already clear of the inflated hull this frame");
        assertTrue(p.x - p.vx < face, "but it was inside it one step ago");

        assertTrue(ClaimAuthority.arrowClaim(s, arena, p, 1),
                "NetSession indexes rawInputs by the head frame and applies raw input i at frame"
                        + " i + INPUT_DELAY_FRAMES, so the arrow the client saw sits exactly one"
                        + " step behind where the claim is judged; the segment must still reach"
                        + " back over that step");
    }

    @Test
    void theArrowClaimSegmentDoesNotReachForwardIntoMotionTheClientNeverSaw() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(VICTIM_X);
        ProjectileState p = arrow(s, LANE_Z);
        p.x = VICTIM_X - Simulation.PLAYER_WIDTH * 0.5 - ClaimAuthority.ARROW_MARGIN - 0.05;

        assertTrue(p.x + p.vx > VICTIM_X, "one more step would put the arrow inside the hull");

        assertFalse(ClaimAuthority.arrowClaim(s, arena, p, 1),
                "the old segment ran p - v to p + v and so authorised a crossing the client could"
                        + " not have rendered yet; that half is future motion, and the client gets"
                        + " to claim it next frame as its own backward half instead");
    }

    private static ClaimAuthority.Claim swing(GameState s, Arena arena) {
        return ClaimAuthority.meleeClaim(s, arena, s.players[0], s.players[1]);
    }

    private static ProjectileState arrowAt(GameState s, double x, double z, double vx) {
        ProjectileState p = new ProjectileState();
        p.id = 6;
        p.type = ProjectileState.TYPE_ARROW;
        p.owner = 0;
        p.x = x;
        p.y = LANE_Y;
        p.z = z;
        p.vx = vx;
        p.damage = Projectiles.ARROW_DAMAGE;
        p.fresh = false;
        s.projectiles.add(p);
        return p;
    }

    private static GameState victimSteppedFrom(double fromX, double fromZ, double toX, double toZ) {
        GameState s = faceOff(fromX);
        PlayerState v = s.players[1];
        v.z = fromZ;
        ClaimAuthority.record(s);
        v.x = toX;
        v.z = toZ;
        return s;
    }

    private static Aabb standing(double x, double z) {
        return Aabb.player(x, GROUND_Y, z, Simulation.PLAYER_WIDTH, Simulation.PLAYER_HEIGHT)
                .inflate(ClaimAuthority.ARROW_MARGIN);
    }

    @Test
    void aPathLinkNoLongerHandsOutTheUnionOfTwoConsecutiveHulls() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = victimSteppedFrom(0.6, 0.6, 0.0, 0.0);

        Aabb older = standing(0.6, 0.6);
        Aabb live = standing(0.0, 0.0);
        double cornerX = 0.7;
        double cornerZ = 0.0;
        assertTrue(cornerX > live.maxX && cornerZ < older.minZ,
                "the probe corner is outside BOTH hulls the victim was ever recorded in");
        assertTrue(cornerX < Math.max(live.maxX, older.maxX)
                        && cornerZ > Math.min(live.minZ, older.minZ),
                "but it is inside span(prev, cur), the union AABB the old candidates() emitted");

        ProjectileState p = arrowAt(s, 0.9, cornerZ, 0.4);
        assertFalse(ClaimAuthority.arrowClaim(s, arena, p, 1),
                "a 0.6 0.6 diagonal step is 0.849 blocks, inside PATH_LINK_GAP, and its union AABB"
                        + " is 1.177 x 2.377 x 1.177 = 3.29 cubic blocks against a real hull of"
                        + " 0.648, a factor of 5.1; the mixed corner it invents was never occupied"
                        + " at any instant and must not be claimable");
    }

    @Test
    void thePositionsTheVictimActuallyOccupiedAreStillClaimable() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = victimSteppedFrom(0.6, 0.6, 0.0, 0.0);

        ProjectileState p = arrowAt(s, 0.9, 0.6, 0.4);
        assertTrue(ClaimAuthority.arrowClaim(s, arena, p, 1),
                "dropping the union must not drop the rewind sample itself");
    }

    @Test
    void aLinkTooLongToOverlapIsChoppedSoTheMidPathIsStillCovered() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = victimSteppedFrom(0.9, 0.0, 0.0, 0.0);

        Aabb older = standing(0.9, 0.0);
        Aabb live = standing(0.0, 0.0);
        assertTrue(live.maxX < older.minX,
                "a 0.9 step is wider than the 0.6 hull, so the two sampled boxes do not touch");

        ProjectileState p = arrowAt(s, 0.46, 0.0, 0.02);
        assertTrue(ClaimAuthority.arrowClaim(s, arena, p, 1),
                "the gap between them is on the straight line the renderer interpolated the victim"
                        + " along, so it is covered by a real intermediate hull rather than by"
                        + " fattening the endpoints");
    }

    @Test
    void theChopIsFineEnoughThatConsecutiveHullsAlwaysOverlap() {
        assertEquals(Simulation.PLAYER_WIDTH, ClaimAuthority.PATH_STEP_XZ, 0.0,
                "a sub-step wider than the hull would leave a hole in the path");
        assertTrue(ClaimAuthority.PATH_LINK_GAP / ClaimAuthority.PATH_LINK_MAX_STEPS
                        < Simulation.PLAYER_WIDTH,
                "PATH_LINK_MAX_STEPS 2 splits the widest linkable step, 1.0, into 0.5 sub-steps,"
                        + " which is inside the 0.6 hull width, so two hulls in a chain always"
                        + " overlap and the chain has no hole for the claim to fall through");
    }

    @Test
    void aHitMadeWhileSpinningTheCameraIsStillClaimable() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.5);
        s.players[0].yaw = MID_TURN_YAW;

        Vec3 look = Combat.lookVector(s.players[0].yaw, s.players[0].pitch);
        assertFalse(ClaimAuthority.aimCovers(
                        Aabb.player(2.5, GROUND_Y, LANE_Z, Simulation.PLAYER_WIDTH, 1.8),
                        0.0, GROUND_Y + Combat.eyeHeight(s.players[0]), LANE_Z, look, 3.0),
                "the crosshair is 80 degrees off the body: this is the mid-turn hit, not an"
                        + " aimed one");

        assertTrue(swing(s, arena) != null,
                "ServerPlayNetworkHandler.onPlayerInteractEntity gates an attack on"
                        + " canAttackEntityIn alone, which is a distance test to the bounding box;"
                        + " vanilla has no crosshair test, and ours would read a yaw sampled at"
                        + " 20 Hz from a camera that moved continuously through the sample, so a"
                        + " crosshair gate only ever cost honest players mid-turn hits. That"
                        + " decision is unchanged: everything short of behind the attacker is"
                        + " still granted and merely counted");
    }

    @Test
    void aHitClaimedWithTheVictimBehindTheAttackerIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.5);
        s.players[0].yaw = 90f;

        Vec3 look = Combat.lookVector(s.players[0].yaw, s.players[0].pitch);
        assertTrue(look.x() < 0.0, "the attacker's live look vector points away from the victim");

        assertFalse(swing(s, arena) != null,
                "reach, occlusion and the rewind window were the whole of the melee claim, so a"
                        + " client could set meleeHit on a frame whose own yaw put the victim"
                        + " squarely behind it and be believed. aimAhead refuses only that:"
                        + " every candidate hull in reach lying more than 120 degrees off the"
                        + " frame's own look vector. A human cannot rotate far enough inside one"
                        + " 50 ms tick to land there - it takes 2400 deg/s - so this refuses no"
                        + " flick, no spin-click and no jump crit, and it is decided from yaw,"
                        + " pitch and the rewind ring, all of which both peers and the relay"
                        + " referee already replicate. Interpenetrating hulls are exempt; see"
                        + " twoPlayersStandingInsideEachOtherAreExemptFromTheFacingTest");
    }

    @Test
    void twoPlayersStandingInsideEachOtherAreExemptFromTheFacingTest() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(-0.08);
        s.players[0].x = 0.5;

        assertTrue(swing(s, arena) != null,
                "0.58 blocks apart is two hitboxes interpenetrating, and which side of a"
                        + " 0.6-wide box the other player's centre is on at that separation is"
                        + " sub-block noise, not a statement about where anyone is looking. The"
                        + " scripted determinism scenario parks the victim exactly here for two"
                        + " hundred ticks, so an unexempted facing test refuses two hundred hits"
                        + " in the project's own gate and would refuse every scramble in a"
                        + " corner. The exemption costs nothing: a claimant already inside their"
                        + " opponent gains no reach from it");
    }

    @Test
    void refusingTheBehindTheBackClaimIsAttributable() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.5);
        s.players[0].yaw = 90f;

        long[] sink = new long[SimProbe.COUNTERS];
        SimProbe.install(sink);
        try {
            swing(s, arena);
        } finally {
            SimProbe.uninstall();
        }

        assertEquals(1, sink[SimProbe.MELEE_CLAIM_REFUSED_BEHIND_THE_BACK],
                "a refusal an operator cannot see is a refusal that gets blamed on the netcode"
                        + " the first time an honest player reports a hit that did not register");
        assertEquals(0, sink[SimProbe.MELEE_CLAIM_REFUSED_OUT_OF_REACH],
                "and it must not be filed as a reach refusal, which is a different bug report");
    }

    @Test
    void theReachBoundStillHoldsWhicheverWayTheCameraPoints() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState near = faceOff(6.0);
        near.players[0].yaw = -90f;
        assertTrue(swing(near, arena) != null, "control: 5.7 to the hull is inside the 6.0 bound");

        GameState far = faceOff(6.5);
        far.players[0].yaw = -90f;
        assertFalse(swing(far, arena) != null,
                "6.2 to the hull is past the bound, and dropping the look test must not have"
                        + " loosened the distance one");

        GameState farAway = faceOff(6.5);
        farAway.players[0].yaw = MID_TURN_YAW;
        assertFalse(swing(farAway, arena) != null,
                "and it is still refused mid-turn, where reach rather than facing is the thing"
                        + " doing the refusing");
    }

    @Test
    void aWallStillRefusesTheClaimWhicheverWayTheCameraPoints() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState s = faceOff(2.5);
        s.players[0].yaw = MID_TURN_YAW;
        s.blocks.place(1, 65, 0, 920);

        assertFalse(swing(s, arena) != null,
                "the sightline test is the deliberate departure and it is the one that has to"
                        + " carry the wall case, not a look-direction proxy. The yaw here is"
                        + " mid-turn rather than spun right round so that aimAhead cannot be the"
                        + " thing refusing it");
    }

    private static void web(GameState s, int x, int y, int z) {
        s.cobwebItemId = COBWEB_ITEM_ID;
        s.cobwebs.put(BlockStore.key(x, y, z), COBWEB_ITEM_ID);
    }

    private static boolean everyWeb(GameState s, double ox, double oy, double oz, Vec3 aim) {
        List<Aabb> all = new ArrayList<>();
        for (long k : s.cobwebs.keySet()) {
            int cx = BlockStore.unpackX(k);
            int cy = BlockStore.unpackY(k);
            int cz = BlockStore.unpackZ(k);
            all.add(new Aabb(cx, cy, cz, cx + 1.0, cy + 1.0, cz + 1.0));
        }
        return ClaimAuthority.cobwebCrosses(all, ox, oy, oz, aim);
    }

    @Test
    void theSpatialFilterAnswersExactlyWhatTheFullScanDid() {
        GameState s = new GameState();
        long seed = 0x5DEECE66DL;
        for (int i = 0; i < 400; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int x = (int) ((seed >>> 33) % 24) - 12;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int y = 64 + (int) ((seed >>> 33) % 8);
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int z = (int) ((seed >>> 33) % 24) - 12;
            web(s, x, y, z);
        }

        int crossed = 0;
        for (int i = 0; i < 3000; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double ox = ((seed >>> 20) % 20000) / 1000.0 - 10.0;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double oy = 64.0 + ((seed >>> 20) % 8000) / 1000.0;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double oz = ((seed >>> 20) % 20000) / 1000.0 - 10.0;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double ax = ((seed >>> 20) % 20000) / 1000.0 - 10.0;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double ay = 64.0 + ((seed >>> 20) % 8000) / 1000.0;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            double az = ((seed >>> 20) % 20000) / 1000.0 - 10.0;

            Vec3 aim = new Vec3(ax, ay, az);
            boolean bounded = ClaimAuthority.cobwebCrosses(s, ox, oy, oz, aim);
            boolean full = everyWeb(s, ox, oy, oz, aim);
            assertEquals(full, bounded,
                    "the bounded scan must decide every segment the same way the unfiltered scan"
                            + " did: origin " + ox + " " + oy + " " + oz + " aim " + aim);
            if (full) {
                crossed++;
            }
        }
        assertTrue(crossed > 100,
                "the fuzz has to actually cross cobwebs, or it proves nothing; crossed=" + crossed);
    }

    @Test
    void theSpatialFilterKeepsAWebThatOnlyTouchesTheSegmentPlane() {
        GameState s = new GameState();
        web(s, 4, 65, 0);

        Vec3 aim = new Vec3(6.0, 65.5, 0.5);
        assertTrue(ClaimAuthority.cobwebCrosses(s, 5.0, 65.5, 0.5, aim),
                "the segment runs along x = 5.0, which is the web cell's own max face, and"
                        + " segmentBox accepts a touch; the filter's one cell halo is what keeps"
                        + " that cell in the candidate set");
    }

    @Test
    void aDistantWebFieldDoesNotTouchTheClaim() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.5);
        for (int x = 40; x < 60; x++) {
            for (int z = 40; z < 60; z++) {
                web(s, x, 65, z);
            }
        }

        assertTrue(swing(s, arena) != null,
                "400 cobwebs nowhere near the swing must neither refuse it nor be visited");
    }

    @Test
    void aWebOnTheSwingLineStillRefusesIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = faceOff(2.5);
        web(s, 1, 65, 0);

        assertFalse(swing(s, arena) != null,
                "bounding the scan must not lose the cobweb rule itself");
    }
}
