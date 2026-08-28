package me.nootnoot.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.math.Raycast;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;

public final class ClaimAuthority {
    public static final int INPUT_DELAY_FRAMES = 1;

    public static final int RECORD_OFFSET_FRAMES = 1;

    public static final int PRESENTATION_LAG_FRAMES = 3;

    public static final int WINDOW_FRAMES =
            INPUT_DELAY_FRAMES + RECORD_OFFSET_FRAMES + PRESENTATION_LAG_FRAMES;

    public static final double HULL_INFLATE = 0.1;

    public static final double ARROW_MARGIN = 0.1;

    public static final double PATH_LINK_GAP = 1.0;

    public static final double PATH_STEP_XZ = Simulation.PLAYER_WIDTH;

    public static final int PATH_LINK_MAX_STEPS = 2;

    public static final int CANDIDATE_CAPACITY = WINDOW_FRAMES * PATH_LINK_MAX_STEPS + 1;

    public static final double SIGHT_CONTACT_EPS = 1.0E-6;

    public static final double AIM_SLACK = 0.5;

    public static final double AIM_BEHIND_COS = -0.5;

    public static final double AIM_AHEAD_EPS_SQ = 1.0E-8;

    private ClaimAuthority() {
    }

    public static void record(GameState s) {
        for (PlayerState p : s.players) {
            int i = p.rewindPos;
            p.rewindX[i] = p.x;
            p.rewindY[i] = p.y;
            p.rewindZ[i] = p.z;
            p.rewindHeight[i] = Simulation.poseHeight(p);
            p.rewindPos = (i + 1) % PlayerState.REWIND_FRAMES;
            if (p.rewindFilled < PlayerState.REWIND_FRAMES) {
                p.rewindFilled++;
            }
        }
    }

    public static boolean offAimRateExceeds(PlayerState p, int minClaims, int numerator,
                                            int denominator) {
        if (p.meleeClaimsGranted < minClaims || denominator <= 0) {
            return false;
        }
        return (long) p.meleeClaimsOffAim * denominator > (long) p.meleeClaimsGranted * numerator;
    }

    public static void clear(PlayerState p) {
        Arrays.fill(p.rewindX, 0.0);
        Arrays.fill(p.rewindY, 0.0);
        Arrays.fill(p.rewindZ, 0.0);
        Arrays.fill(p.rewindHeight, 0.0);
        p.rewindPos = 0;
        p.rewindFilled = 0;
    }

    public record Claim(Aabb hull, Vec3 origin, Vec3 aim) {
    }

    public static double meleeLimit(GameState s, PlayerState a) {
        return Combat.attackReachLimit(s, a);
    }

    public static double meleeMinLimit(GameState s, PlayerState a) {
        return Combat.attackPickMinReach(s, a);
    }

    public static boolean beyondMinLimit(double ox, double oy, double oz, Vec3 aim,
                                         double minLimit) {
        if (minLimit <= 0.0) {
            return true;
        }
        double dx = aim.x() - ox;
        double dy = aim.y() - oy;
        double dz = aim.z() - oz;
        return dx * dx + dy * dy + dz * dz >= minLimit * minLimit;
    }

    public static Claim meleeClaim(GameState s, Arena arena, PlayerState a, PlayerState v) {
        SimProbe.hit(SimProbe.MELEE_CLAIM_ATTEMPT);
        Vec3 look = Combat.lookVector(a.yaw, a.pitch);
        double limit = meleeLimit(s, a);
        double minLimit = meleeMinLimit(s, a);

        Aabb[] hulls = new Aabb[CANDIDATE_CAPACITY];
        int n = candidates(v, hulls);
        double[] eyes = new double[(INPUT_DELAY_FRAMES + 1) * 3];
        int m = frameEyes(a, eyes, INPUT_DELAY_FRAMES);
        Aabb self = hull(a.x, a.y, a.z, Simulation.poseHeight(a));

        List<Aabb> webs = new ArrayList<>();
        collectNearCobwebs(s, webs, sweptBounds(hulls, n, eyes, m));

        boolean liveHullReached = false;
        boolean anyHullReached = false;
        boolean anyHullInsideMinReach = false;
        boolean anyHullBehindTheBack = false;
        for (int i = 0; i < n; i++) {
            Aabb raw = hulls[i];
            for (int f = 0; f < m; f++) {
                double ox = eyes[f * 3];
                double oy = eyes[f * 3 + 1];
                double oz = eyes[f * 3 + 2];
                SimProbe.hit(SimProbe.MELEE_CANDIDATE_HULL_TESTED);
                if (!reaches(raw, ox, oy, oz, limit)) {
                    continue;
                }
                SimProbe.hit(SimProbe.MELEE_CANDIDATE_HULL_IN_REACH);
                anyHullReached = true;
                liveHullReached |= i == 0;
                Vec3 aim = aimPoint(raw, ox, oy, oz, look, limit);
                if (!beyondMinLimit(ox, oy, oz, aim, minLimit)) {
                    anyHullInsideMinReach = true;
                    continue;
                }
                if (!self.intersects(raw) && !aimAhead(ox, oy, oz, look, aim)) {
                    anyHullBehindTheBack = true;
                    continue;
                }
                if (unobstructed(s, arena, ox, oy, oz, aim)
                        && !cobwebCrosses(webs, ox, oy, oz, aim)) {
                    SimProbe.hit(SimProbe.MELEE_CLAIM_GRANTED);
                    SimProbe.hit(i == 0
                            ? SimProbe.MELEE_CLAIM_GRANTED_LIVE_HULL
                            : SimProbe.MELEE_CLAIM_GRANTED_REWOUND_HULL);
                    a.meleeClaimsGranted++;
                    if (!aimCovers(raw, ox, oy, oz, look, limit)) {
                        SimProbe.hit(SimProbe.MELEE_CLAIM_GRANTED_OFF_AIM);
                        a.meleeClaimsOffAim++;
                    }
                    if (!liveHullReached) {
                        SimProbe.hit(SimProbe.MELEE_CLAIM_GRANTED_OUTSIDE_LIVE_REACH);
                    }
                    return new Claim(raw, new Vec3(ox, oy, oz), aim);
                }
            }
        }
        SimProbe.hit(SimProbe.MELEE_CLAIM_REFUSED);
        if (anyHullInsideMinReach) {
            SimProbe.hit(SimProbe.MELEE_CLAIM_REFUSED_INSIDE_MIN_REACH);
        }
        if (anyHullBehindTheBack) {
            SimProbe.hit(SimProbe.MELEE_CLAIM_REFUSED_BEHIND_THE_BACK);
        }
        SimProbe.hit(anyHullReached
                ? SimProbe.MELEE_CLAIM_REFUSED_OCCLUDED
                : SimProbe.MELEE_CLAIM_REFUSED_OUT_OF_REACH);
        return null;
    }

    private static Aabb sweptBounds(Aabb[] hulls, int n, double[] eyes, int m) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            Aabb h = hulls[i];
            minX = Math.min(minX, h.minX);
            minY = Math.min(minY, h.minY);
            minZ = Math.min(minZ, h.minZ);
            maxX = Math.max(maxX, h.maxX);
            maxY = Math.max(maxY, h.maxY);
            maxZ = Math.max(maxZ, h.maxZ);
        }
        for (int f = 0; f < m; f++) {
            minX = Math.min(minX, eyes[f * 3]);
            minY = Math.min(minY, eyes[f * 3 + 1]);
            minZ = Math.min(minZ, eyes[f * 3 + 2]);
            maxX = Math.max(maxX, eyes[f * 3]);
            maxY = Math.max(maxY, eyes[f * 3 + 1]);
            maxZ = Math.max(maxZ, eyes[f * 3 + 2]);
        }
        return new Aabb(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static int frameEyes(PlayerState a, double[] out, int window) {
        out[0] = a.x;
        out[1] = a.y + Combat.eyeHeight(a);
        out[2] = a.z;
        int n = 1;

        int filled = Math.min(a.rewindFilled, window);
        int idx = a.rewindPos;
        for (int i = 0; i < filled; i++) {
            idx = (idx - 1 + PlayerState.REWIND_FRAMES) % PlayerState.REWIND_FRAMES;
            out[n * 3] = a.rewindX[idx];
            out[n * 3 + 1] = a.rewindY[idx] + Combat.eyeHeightForPose(a.rewindHeight[idx]);
            out[n * 3 + 2] = a.rewindZ[idx];
            n++;
        }
        return n;
    }

    public static boolean sightClear(GameState s, Arena arena, PlayerState a, Vec3 aim) {
        return unobstructed(s, arena, a.x, a.y + Combat.eyeHeight(a), a.z, aim);
    }

    public static boolean cobwebCrosses(GameState s, double ox, double oy, double oz, Vec3 aim) {
        if (s.cobwebs.isEmpty()) {
            return false;
        }
        List<Aabb> near = new ArrayList<>();
        collectNearCobwebs(s, near, new Aabb(
                Math.min(ox, aim.x()), Math.min(oy, aim.y()), Math.min(oz, aim.z()),
                Math.max(ox, aim.x()), Math.max(oy, aim.y()), Math.max(oz, aim.z())));
        return cobwebCrosses(near, ox, oy, oz, aim);
    }

    public static boolean cobwebCrosses(List<Aabb> near, double ox, double oy, double oz, Vec3 aim) {
        SimProbe.hit(SimProbe.COBWEB_SIGHT_TEST);
        if (near.isEmpty()) {
            return false;
        }
        double dx = aim.x() - ox;
        double dy = aim.y() - oy;
        double dz = aim.z() - oz;
        for (int i = 0; i < near.size(); i++) {
            double t = Raycast.segmentBox(near.get(i), ox, oy, oz, dx, dy, dz);
            if (t >= 0.0 && t < 1.0 - SIGHT_CONTACT_EPS) {
                SimProbe.hit(SimProbe.COBWEB_SIGHT_CROSSED);
                return true;
            }
        }
        return false;
    }

    public static void collectNearCobwebs(GameState s, List<Aabb> out, Aabb bounds) {
        if (s.cobwebs.isEmpty()) {
            return;
        }
        int x0 = (int) Math.floor(bounds.minX) - 1;
        int x1 = (int) Math.floor(bounds.maxX) + 1;
        int y0 = (int) Math.floor(bounds.minY) - 1;
        int y1 = (int) Math.floor(bounds.maxY) + 1;
        int z0 = (int) Math.floor(bounds.minZ) - 1;
        int z1 = (int) Math.floor(bounds.maxZ) + 1;
        long cells = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (cells <= s.cobwebs.size()) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        if (s.cobwebs.containsKey(BlockStore.key(x, y, z))) {
                            out.add(new Aabb(x, y, z, x + 1.0, y + 1.0, z + 1.0));
                        }
                    }
                }
            }
            return;
        }
        long[] hits = new long[s.cobwebs.size()];
        int n = 0;
        for (long k : s.cobwebs.keySet()) {
            int cx = BlockStore.unpackX(k);
            int cy = BlockStore.unpackY(k);
            int cz = BlockStore.unpackZ(k);
            if (cx < x0 || cx > x1 || cy < y0 || cy > y1 || cz < z0 || cz > z1) {
                continue;
            }
            hits[n++] = k;
        }
        Arrays.sort(hits, 0, n);
        for (int i = 0; i < n; i++) {
            long k = hits[i];
            int cx = BlockStore.unpackX(k);
            int cy = BlockStore.unpackY(k);
            int cz = BlockStore.unpackZ(k);
            out.add(new Aabb(cx, cy, cz, cx + 1.0, cy + 1.0, cz + 1.0));
        }
    }

    public static Vec3 nearestPoint(Aabb box, double x, double y, double z) {
        return nearestOn(box, x, y, z);
    }

    public static boolean aimAhead(double ox, double oy, double oz, Vec3 look, Vec3 aim) {
        double dx = aim.x() - ox;
        double dy = aim.y() - oy;
        double dz = aim.z() - oz;
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq <= AIM_AHEAD_EPS_SQ) {
            return true;
        }
        double dot = dx * look.x() + dy * look.y() + dz * look.z();
        if (dot >= 0.0) {
            return true;
        }
        return dot * dot < AIM_BEHIND_COS * AIM_BEHIND_COS * lenSq;
    }

    public static boolean aimCovers(Aabb hull, double ox, double oy, double oz, Vec3 look,
                                    double limit) {
        double reach = limit + AIM_SLACK;
        double t = Raycast.segmentBox(hull.inflate(AIM_SLACK), ox, oy, oz,
                look.x() * reach, look.y() * reach, look.z() * reach);
        return t >= 0.0 && t <= 1.0;
    }

    public static Vec3 aimPoint(Aabb hull, double ox, double oy, double oz, Vec3 look, double limit) {
        double px = ox;
        double py = oy;
        double pz = oz;
        double t = Raycast.segmentBox(hull.inflate(HULL_INFLATE), ox, oy, oz,
                look.x() * limit, look.y() * limit, look.z() * limit);
        if (t >= 0.0 && t <= 1.0) {
            px = ox + look.x() * limit * t;
            py = oy + look.y() * limit * t;
            pz = oz + look.z() * limit * t;
        }
        return nearestOn(hull, px, py, pz);
    }

    private static Vec3 nearestOn(Aabb hull, double x, double y, double z) {
        return new Vec3(
                Math.max(hull.minX, Math.min(x, hull.maxX)),
                Math.max(hull.minY, Math.min(y, hull.maxY)),
                Math.max(hull.minZ, Math.min(z, hull.maxZ)));
    }

    public static boolean unobstructed(GameState s, Arena arena,
                                       double ox, double oy, double oz, Vec3 aim) {
        SimProbe.hit(SimProbe.SIGHT_TEST);
        double dx = aim.x() - ox;
        double dy = aim.y() - oy;
        double dz = aim.z() - oz;
        double minX = Math.min(ox, aim.x());
        double minY = Math.min(oy, aim.y());
        double minZ = Math.min(oz, aim.z());
        double maxX = Math.max(ox, aim.x());
        double maxY = Math.max(oy, aim.y());
        double maxZ = Math.max(oz, aim.z());

        List<Aabb> near = new ArrayList<>();
        arena.collectNearOccluders(near, minX, minY, minZ, maxX, maxY, maxZ, s.brokenArena);
        for (int i = 0; i < near.size(); i++) {
            if (crosses(near.get(i), ox, oy, oz, dx, dy, dz)) {
                SimProbe.hit(SimProbe.SIGHT_BLOCKED_BY_ARENA);
                return false;
            }
        }

        if (s.blocks.isEmpty()) {
            return true;
        }
        int x0 = (int) Math.floor(minX);
        int x1 = (int) Math.floor(maxX);
        int y0 = (int) Math.floor(minY);
        int y1 = (int) Math.floor(maxY);
        int z0 = (int) Math.floor(minZ);
        int z1 = (int) Math.floor(maxZ);
        if (sparserThanTheGrid(s.blocks.size(), (long) x1 - x0 + 1, (long) y1 - y0 + 1,
                (long) z1 - z0 + 1)) {
            for (long k : s.blocks.sortedKeys()) {
                int x = BlockStore.unpackX(k);
                int y = BlockStore.unpackY(k);
                int z = BlockStore.unpackZ(k);
                if (x < x0 || x > x1 || y < y0 || y > y1 || z < z0 || z > z1) {
                    continue;
                }
                Aabb cell = new Aabb(x, y, z, x + 1.0, y + 1.0, z + 1.0);
                if (crosses(cell, ox, oy, oz, dx, dy, dz)) {
                    SimProbe.hit(SimProbe.SIGHT_BLOCKED_BY_PLACED_BLOCK);
                    return false;
                }
            }
            return true;
        }
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    if (!s.blocks.contains(x, y, z)) {
                        continue;
                    }
                    Aabb cell = new Aabb(x, y, z, x + 1.0, y + 1.0, z + 1.0);
                    if (crosses(cell, ox, oy, oz, dx, dy, dz)) {
                        SimProbe.hit(SimProbe.SIGHT_BLOCKED_BY_PLACED_BLOCK);
                        return false;
                    }
                }
            }
        }
        return true;
    }

    static boolean sparserThanTheGrid(int placed, long spanX, long spanY, long spanZ) {
        if (spanX > placed || spanY > placed || spanZ > placed) {
            return true;
        }
        long plane = spanX * spanY;
        return plane > placed || plane * spanZ > placed;
    }

    private static boolean crosses(Aabb box, double ox, double oy, double oz,
                                   double dx, double dy, double dz) {
        double t = Raycast.segmentBox(box, ox, oy, oz, dx, dy, dz);
        return t >= 0.0 && t < 1.0 - SIGHT_CONTACT_EPS;
    }

    public static boolean arrowClaim(GameState s, Arena arena, ProjectileState p, int target) {
        if (target < 0 || target >= s.players.length) {
            return false;
        }
        if (target == p.owner && !p.leftOwner) {
            return false;
        }
        PlayerState v = s.players[target];
        if (v.dead) {
            return false;
        }
        SimProbe.hit(SimProbe.ARROW_CLAIM_ATTEMPT);
        SimProbe.hit(target == p.owner
                ? SimProbe.ARROW_CLAIM_SELF_TARGET
                : SimProbe.ARROW_CLAIM_PEER_TARGET);

        double ax = p.x - p.vx;
        double ay = p.y - p.vy;
        double az = p.z - p.vz;
        double dx = p.vx;
        double dy = p.vy;
        double dz = p.vz;

        Aabb[] hulls = new Aabb[CANDIDATE_CAPACITY];
        int n = target == p.owner
                ? liveOnly(v, hulls)
                : candidates(v, hulls);
        for (int i = 0; i < n; i++) {
            Aabb raw = hulls[i];
            Aabb box = raw.inflate(ARROW_MARGIN);
            if (contains(box, p.x, p.y, p.z)) {
                if (unobstructed(s, arena, ax, ay, az, nearestOn(raw, p.x, p.y, p.z))) {
                    SimProbe.hit(SimProbe.ARROW_CLAIM_GRANTED);
                    return true;
                }
                continue;
            }
            double t = Raycast.segmentBox(box, ax, ay, az, dx, dy, dz);
            if (t >= 0.0 && t <= 1.0) {
                Vec3 aim = nearestOn(raw, ax + dx * t, ay + dy * t, az + dz * t);
                if (unobstructed(s, arena, ax, ay, az, aim)) {
                    SimProbe.hit(SimProbe.ARROW_CLAIM_GRANTED);
                    return true;
                }
            }
        }
        SimProbe.hit(SimProbe.ARROW_CLAIM_REFUSED);
        return false;
    }

    private static int liveOnly(PlayerState v, Aabb[] out) {
        out[0] = hull(v.x, v.y, v.z, Simulation.poseHeight(v));
        return 1;
    }

    private static int candidates(PlayerState v, Aabb[] out) {
        out[0] = hull(v.x, v.y, v.z, Simulation.poseHeight(v));
        int n = 1;

        double px = v.x;
        double py = v.y;
        double pz = v.z;
        double ph = Simulation.poseHeight(v);

        int filled = Math.min(v.rewindFilled, WINDOW_FRAMES);
        int idx = v.rewindPos;
        for (int i = 0; i < filled; i++) {
            idx = (idx - 1 + PlayerState.REWIND_FRAMES) % PlayerState.REWIND_FRAMES;
            double x = v.rewindX[idx];
            double y = v.rewindY[idx];
            double z = v.rewindZ[idx];
            double h = v.rewindHeight[idx];

            double dx = x - px;
            double dy = y - py;
            double dz = z - pz;
            boolean continuous = dx * dx + dy * dy + dz * dz <= PATH_LINK_GAP * PATH_LINK_GAP;
            int steps = continuous ? linkSteps(dx, dz) : 1;
            for (int k = 1; k < steps; k++) {
                double f = (double) k / steps;
                out[n] = hull(px + dx * f, py + dy * f, pz + dz * f, ph + (h - ph) * f);
                n++;
            }
            out[n] = hull(x, y, z, h);
            n++;

            px = x;
            py = y;
            pz = z;
            ph = h;
        }
        return n;
    }

    private static int linkSteps(double dx, double dz) {
        double spread = Math.max(Math.abs(dx), Math.abs(dz));
        int steps = 1;
        while (steps < PATH_LINK_MAX_STEPS && spread > steps * PATH_STEP_XZ) {
            steps++;
        }
        return steps;
    }

    private static boolean reaches(Aabb box, double ox, double oy, double oz, double limit) {
        double nx = Math.max(box.minX, Math.min(ox, box.maxX));
        double ny = Math.max(box.minY, Math.min(oy, box.maxY));
        double nz = Math.max(box.minZ, Math.min(oz, box.maxZ));
        double dx = ox - nx;
        double dy = oy - ny;
        double dz = oz - nz;
        return Combat.withinAttackRange(dx * dx + dy * dy + dz * dz, limit);
    }

    private static boolean contains(Aabb box, double x, double y, double z) {
        return x >= box.minX && x <= box.maxX
                && y >= box.minY && y <= box.maxY
                && z >= box.minZ && z <= box.maxZ;
    }

    private static Aabb hull(double x, double y, double z, double height) {
        return Aabb.player(x, y, z, Simulation.PLAYER_WIDTH, height);
    }
}
