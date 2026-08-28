package me.nootnoot.sim;

import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;

public final class ItemEntities {
    private static final double GRAVITY = 0.04;
    private static final double HALF = 0.125;
    private static final double HEIGHT = 0.25;
    public static final int DEFAULT_PICKUP_DELAY = 40;
    public static final int DEFAULT_LIFE = 6000;

    private static final double AIR_FRICTION = 0.98;
    private static final double GROUND_FRICTION = 0.6 * 0.98;

    private static final double MERGE_DIST_SQ = 2.5 * 2.5;
    private static final int MAX_STACK = 64;

    public static final int MAX_ITEMS = 256;

    public static final int NEUTRAL_OWNER = ItemEntityState.NEUTRAL_OWNER;

    public static final int MAX_NEUTRAL_ITEMS = 32;

    public static final int MAX_ITEMS_PER_OWNER = (MAX_ITEMS - MAX_NEUTRAL_ITEMS) / 2;

    private ItemEntities() {
    }

    public static void tick(GameState s, Arena arena, Input in0, Input in1) {
        s.itemGrid.markDirty();
        for (ItemEntityState e : s.items) {
            if (e.dead) {
                continue;
            }
            if (e.pickupDelay > 0) {
                e.pickupDelay--;
            }
            if (--e.life <= 0) {
                e.dead = true;
                continue;
            }
            e.vy -= GRAVITY;
            double desiredVy = e.vy;
            Aabb box = new Aabb(e.x - HALF, e.y, e.z - HALF, e.x + HALF, e.y + HEIGHT, e.z + HALF);

            Vec3 moved = Simulation.collideArena(arena, box, e.vx, e.vy, e.vz, false, 0.0, s.blocks, s.brokenArena);
            e.x += moved.x();
            e.y += moved.y();
            e.z += moved.z();
            boolean grounded = moved.y() != desiredVy && desiredVy < 0.0;
            e.vy = moved.y() != desiredVy ? 0.0 : e.vy * 0.98;
            double hf = grounded ? GROUND_FRICTION : AIR_FRICTION;
            e.vx *= hf;
            e.vz *= hf;
            if (Math.abs(e.vx) < 1.0E-4) {
                e.vx = 0.0;
            }
            if (Math.abs(e.vz) < 1.0E-4) {
                e.vz = 0.0;
            }
            if (e.pickupDelay == 0) {
                Aabb itemBox = new Aabb(e.x - HALF, e.y, e.z - HALF,
                        e.x + HALF, e.y + HEIGHT, e.z + HALF);
                for (int pi = 0; pi < s.players.length; pi++) {
                    PlayerState p = s.players[pi];
                    if (p.dead) {
                        continue;
                    }
                    if (Combat.pickupSweep(p).intersects(itemBox)) {
                        ItemDict d = Loadout.dict(s);
                        int entry = d.valid(e.entry) ? e.entry : d.entryForItemId(e.itemId);
                        if (entry == ItemDict.NONE) {
                            continue;
                        }
                        int remainder = Loadout.addItemRemainder(s, p, entry, e.count, e.damage);
                        int taken = e.count - remainder;
                        if (taken <= 0) {
                            continue;
                        }
                        Loadout.recomputeDerived(s, p);
                        p.pickupSeq++;
                        p.lastPickupItemId = e.itemId;
                        p.lastPickupCount = taken;
                        p.lastPickupDropUid = e.dropUid;
                        int slot = Math.floorMod(p.pickupSeq, PlayerState.PICKUP_RING);
                        p.pickupRingItemId[slot] = e.itemId;
                        p.pickupRingCount[slot] = taken;
                        p.pickupRingDropUid[slot] = e.dropUid;
                        if (remainder > 0) {
                            e.count = remainder;
                        } else {
                            e.dead = true;
                        }
                        break;
                    }
                }
            }
        }
        mergeStacks(s, arena);
        s.items.removeIf(e -> e.dead);
    }

    private static void mergeStacks(GameState s, Arena arena) {
        int n = s.items.size();
        if (n < 2) {
            return;
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        int[] near = new int[n];
        for (int i = 0; i < n; i++) {
            ItemEntityState a = s.items.get(i);
            if (a.dead) {
                continue;
            }
            int m = s.itemGrid.collectNear(s.items, a.x, a.z, near);
            for (int q = 0; q < m; q++) {
                int j = near[q];
                if (j <= i) {
                    continue;
                }
                ItemEntityState b = s.items.get(j);
                if (b.dead || b.entry != a.entry || b.damage != a.damage || b.itemId != a.itemId) {
                    continue;
                }
                double dx = a.x - b.x;
                double dz = a.z - b.z;
                if (dx * dx + dz * dz < MERGE_DIST_SQ && Math.abs(a.y - b.y) < 0.5) {
                    union(parent, i, j);
                }
            }
        }
        int[] root = new int[n];
        int[] first = new int[n];
        int[] next = new int[n];
        for (int i = 0; i < n; i++) {
            root[i] = find(parent, i);
            first[i] = -1;
            next[i] = -1;
        }
        for (int i = n - 1; i >= 0; i--) {
            if (s.items.get(i).dead) {
                continue;
            }
            next[i] = first[root[i]];
            first[root[i]] = i;
        }
        for (int r = 0; r < n; r++) {
            if (root[r] != r || first[r] < 0) {
                continue;
            }
            int total = 0;
            int members = 0;
            int maxLife = 0;
            int maxDelay = 0;
            double sumX = 0.0;
            double sumZ = 0.0;
            for (int j = first[r]; j >= 0; j = next[j]) {
                ItemEntityState m = s.items.get(j);
                total += m.count;
                members++;
                sumX += m.x * m.count;
                sumZ += m.z * m.count;
                maxLife = Math.max(maxLife, m.life);
                maxDelay = Math.max(maxDelay, m.pickupDelay);
            }
            double centroidX = sumX / total;
            double centroidZ = sumZ / total;
            boolean firstSurvivor = true;
            for (int j = first[r]; j >= 0; j = next[j]) {
                ItemEntityState m = s.items.get(j);
                if (total <= 0) {
                    m.dead = true;
                    continue;
                }
                int give = Math.min(MAX_STACK, total);
                m.count = give;
                m.life = maxLife;
                m.pickupDelay = maxDelay;
                total -= give;
                if (firstSurvivor && members > 1 && !overlapsSolid(s, arena, centroidX, m.y, centroidZ)) {
                    m.x = centroidX;
                    m.z = centroidZ;
                    firstSurvivor = false;
                }
            }
        }
        s.itemGrid.markDirty();
    }

    private static boolean overlapsSolid(GameState s, Arena arena, double x, double y, double z) {
        int x0 = (int) Math.floor(x - HALF);
        int x1 = (int) Math.floor(x + HALF);
        int y0 = (int) Math.floor(y);
        int y1 = (int) Math.floor(y + HEIGHT);
        int z0 = (int) Math.floor(z - HALF);
        int z1 = (int) Math.floor(z + HALF);
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = y0; by <= y1; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    if (s.blocks.contains(bx, by, bz)) {
                        return true;
                    }
                    long k = me.nootnoot.sim.state.BlockStore.key(bx, by, bz);
                    if (!s.brokenArena.contains(k) && arena.isSolidVoxel(bx, by, bz)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra == rb) {
            return;
        }
        if (ra < rb) {
            parent[rb] = ra;
        } else {
            parent[ra] = rb;
        }
    }

    public static ItemEntityState spawn(GameState s, int owner, double x, double y, double z,
                             double vx, double vy, double vz, int itemId, int count, int pickupDelay) {
        return spawn(s, owner, x, y, z, vx, vy, vz, Loadout.dict(s).entryForItemId(itemId), 0, itemId,
                count, pickupDelay);
    }

    public static int ownerCap(int owner) {
        return owner == 0 || owner == 1 ? MAX_ITEMS_PER_OWNER : MAX_NEUTRAL_ITEMS;
    }

    public static int ownedBy(GameState s, int owner) {
        int n = 0;
        for (ItemEntityState e : s.items) {
            if (e.owner == owner) {
                n++;
            }
        }
        return n;
    }

    public static boolean hasRoom(GameState s, int owner) {
        return roomFor(s, owner, 1);
    }

    public static boolean roomFor(GameState s, int owner, int count) {
        if (count <= 0) {
            return true;
        }
        if (s.items.size() + count > MAX_ITEMS) {
            return false;
        }
        return ownedBy(s, owner) + count <= ownerCap(owner);
    }

    public static ItemEntityState spawn(GameState s, int owner, double x, double y, double z,
                             double vx, double vy, double vz, int entry, int damage, int itemId,
                             int count, int pickupDelay) {
        if (!hasRoom(s, owner)) {
            SimProbe.hit(SimProbe.ITEM_ENTITY_REFUSED);
            s.itemsRefused++;
            return null;
        }
        ItemEntityState e = new ItemEntityState();
        e.id = s.nextItemId++;
        e.owner = owner;
        e.entry = entry;
        e.itemId = itemId;
        e.count = count;
        e.damage = damage;
        e.x = x;
        e.y = y;
        e.z = z;
        e.vx = vx;
        e.vy = vy;
        e.vz = vz;
        e.pickupDelay = pickupDelay;
        e.life = DEFAULT_LIFE;
        SimProbe.hit(SimProbe.ITEM_ENTITY_SPAWNED);
        s.items.add(e);
        s.itemGrid.insert(s.items, s.items.size() - 1);
        return e;
    }
}
