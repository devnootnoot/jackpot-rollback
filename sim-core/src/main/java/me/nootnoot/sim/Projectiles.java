package me.nootnoot.sim;

import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.math.Raycast;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;

public final class Projectiles {
    public static final double PEARL_GRAVITY = 0.03;
    public static final double ARROW_GRAVITY = 0.05;
    public static final double DRAG = 0.99;
    public static final float ARROW_DAMAGE = 4.0F;

    public static final double WIND_CHARGE_HITBOX = 0.5;

    public static final double OWNER_CLEAR_MARGIN = 0.3;

    public static final int MAX_LIFE = 1200;

    public static final int MAX_PROJECTILES = 128;

    public static final int MAX_PROJECTILES_PER_OWNER = MAX_PROJECTILES / 2;

    private Projectiles() {
    }

    public static boolean hasRoom(GameState s, int owner) {
        return roomFor(s, owner, 1);
    }

    public static boolean roomFor(GameState s, int owner, int count) {
        if (count <= 0) {
            return true;
        }
        if (s.projectiles.size() + count > MAX_PROJECTILES) {
            return false;
        }
        int mine = 0;
        for (ProjectileState p : s.projectiles) {
            if (p.owner == owner) {
                mine++;
            }
        }
        return mine + count <= MAX_PROJECTILES_PER_OWNER;
    }

    public static boolean spawn(GameState s, ProjectileState p) {
        if (!roomFor(s, p.owner, 1)) {
            SimProbe.hit(SimProbe.PROJECTILE_SPAWN_REFUSED);
            return false;
        }
        if (p.life <= 0 || p.life > MAX_LIFE) {
            p.life = MAX_LIFE;
        }
        SimProbe.band(SimProbe.PROJECTILE_SPAWN_BASE, p.type, SimProbe.PROJECTILE_TYPES);
        s.projectiles.add(p);
        return true;
    }

    public static void tick(GameState s, Arena arena, Input in0, Input in1) {
        boolean[] claimOpen = {
                !in0.synthetic() && in0.projectileHit() >= 0,
                !in1.synthetic() && in1.projectileHit() >= 0};
        for (ProjectileState p : s.projectiles) {
            if (p.dead) {
                continue;
            }
            stepOne(s, arena, p, in0, in1, claimOpen);
        }
        s.projectiles.removeIf(p -> p.dead);
    }

    public static final int STICK_LIFE = 200;

    public static final int STICK_SHAKE_TICKS = 7;

    private static void stepOne(GameState s, Arena arena, ProjectileState p, Input in0, Input in1,
                                boolean[] claimOpen) {
        if (p.fresh) {
            p.fresh = false;
            return;
        }
        if (p.shakeTime > 0) {
            p.shakeTime--;
        }
        if (p.life > 0) {
            p.life--;
            if (p.life == 0) {
                p.dead = true;
                return;
            }
        }
        if (p.stuck) {
            return;
        }

        if (p.type == ProjectileState.TYPE_WIND_CHARGE && !s.isInsidePlayableArea(p.x, p.z)) {
            p.dead = true;
            return;
        }

        if (p.type == ProjectileState.TYPE_ARROW && !p.claimSpent && (p.owner == 0 || p.owner == 1)
                && claimOpen[p.owner]) {
            Input owner = p.owner == 0 ? in0 : in1;
            int ph = owner.projectileHit();
            if (ph >= 0 && (p.id & Input.PROJECTILE_HIT_ID_MASK) == (ph & Input.PROJECTILE_HIT_ID_MASK)) {
                int target = (ph & Input.PROJECTILE_HIT_SELF) != 0 ? p.owner : (1 - p.owner);

                claimOpen[p.owner] = false;
                p.claimSpent = true;
                if (ClaimAuthority.arrowClaim(s, arena, p, target)) {
                    onImpact(s, arena, p, target, p.x, p.y, p.z, p.vx, p.vz);
                    p.dead = true;
                    return;
                }
            }
        }

        p.vy -= gravityOf(p);
        p.vx *= DRAG;
        p.vy *= DRAG;
        p.vz *= DRAG;

        double ax = p.x;
        double ay = p.y;
        double az = p.z;
        double dx = p.vx;
        double dy = p.vy;
        double dz = p.vz;

        double bestT = 2.0;
        int hitPlayer = -1;

        java.util.List<Aabb> arenaNear = new java.util.ArrayList<>();
        double m = 0.5;
        arena.collectNearSolids(arenaNear,
                Math.min(ax, ax + dx) - m, Math.min(ay, ay + dy) - m, Math.min(az, az + dz) - m,
                Math.max(ax, ax + dx) + m, Math.max(ay, ay + dy) + m, Math.max(az, az + dz) + m,
                s.brokenArena);
        for (Aabb b : arenaNear) {
            double t = Raycast.segmentBox(b, ax, ay, az, dx, dy, dz);
            if (t >= 0.0 && t <= 1.0 && t < bestT) {
                bestT = t;
                hitPlayer = -1;
            }
        }
        for (Aabb b : s.blocks.solids()) {
            double t = Raycast.segmentBox(b, ax, ay, az, dx, dy, dz);
            if (t >= 0.0 && t <= 1.0 && t < bestT) {
                bestT = t;
                hitPlayer = -1;
            }
        }
        if (!p.leftOwner && (p.owner == 0 || p.owner == 1)) {
            PlayerState ownerP = s.players[p.owner];
            double oh = Simulation.poseHeight(ownerP);
            Aabb ob = Aabb.player(ownerP.x, ownerP.y, ownerP.z, Simulation.PLAYER_WIDTH, oh)
                    .inflate(OWNER_CLEAR_MARGIN);
            if (ax < ob.minX || ax > ob.maxX || ay < ob.minY || ay > ob.maxY || az < ob.minZ || az > ob.maxZ) {
                p.leftOwner = true;
            }
        }
        for (int j = 0; j < s.players.length; j++) {
            if (j == p.owner && !(p.leftOwner
                    && (p.type == ProjectileState.TYPE_SPLASH_POTION || p.type == ProjectileState.TYPE_XP_BOTTLE))) {
                continue;
            }
            PlayerState v = s.players[j];
            if (v.dead) {
                continue;
            }
            double height = Simulation.poseHeight(v);
            Aabb vbox = Aabb.player(v.x, v.y, v.z, Simulation.PLAYER_WIDTH, height);
            if (p.type == ProjectileState.TYPE_SPLASH_POTION || p.type == ProjectileState.TYPE_XP_BOTTLE) {
                vbox = vbox.inflate(0.3);
            }
            double t = Raycast.segmentBox(vbox, ax, ay, az, dx, dy, dz);

            if (t >= 0.0 && t <= 1.0 && t <= bestT) {
                bestT = t;
                hitPlayer = j;
            }
        }

        int hitCrystal = -1;
        for (int ci = 0; ci < s.crystals.size(); ci++) {
            me.nootnoot.sim.state.CrystalState c = s.crystals.get(ci);

            Aabb cbox = Combat.crystalBox(c.bx, c.by, c.bz);
            double t = Raycast.segmentBox(cbox, ax, ay, az, dx, dy, dz);
            if (t >= 0.0 && t <= 1.0 && t < bestT) {
                bestT = t;
                hitPlayer = -1;
                hitCrystal = ci;
            }
        }

        if (p.type == ProjectileState.TYPE_PEARL) {
            for (ProjectileState other : s.projectiles) {
                if (other == p || other.dead || other.type != ProjectileState.TYPE_WIND_CHARGE) {
                    continue;
                }
                double r = WIND_CHARGE_HITBOX;
                Aabb wbox = new Aabb(other.x - r, other.y - r, other.z - r, other.x + r, other.y + r, other.z + r);
                double t = Raycast.segmentBox(wbox, ax, ay, az, dx, dy, dz);
                if (t >= 0.0 && t <= 1.0 && t < bestT) {
                    bestT = t;
                    hitPlayer = -1;
                    hitCrystal = -1;
                }
            }
        }

        if (bestT <= 1.0) {
            double hx = ax + dx * bestT;
            double hy = ay + dy * bestT;
            double hz = az + dz * bestT;
            if (hitCrystal >= 0) {
                SimProbe.hit(SimProbe.CRYSTAL_DESTROYED_BY_PROJECTILE);
                me.nootnoot.sim.state.CrystalState c = s.crystals.remove(hitCrystal);
                Combat.explode(s, arena, c.bx + 0.5, c.by + 1.0, c.bz + 0.5, Combat.CRYSTAL_POWER, p.owner, false);
                p.dead = true;
                if (p.type == ProjectileState.TYPE_PEARL) {
                    onImpact(s, arena, p, -1, hx, hy, hz, dx, dz);
                }
                return;
            }
            if (p.type == ProjectileState.TYPE_ARROW && hitPlayer < 0) {
                p.x = hx;
                p.y = hy;
                p.z = hz;
                p.stuck = true;
                p.shakeTime = STICK_SHAKE_TICKS;
                p.life = STICK_LIFE;
                return;
            }
            p.dead = true;
            onImpact(s, arena, p, hitPlayer, hx, hy, hz, dx, dz);
            return;
        }

        p.x += dx;
        p.y += dy;
        p.z += dz;
    }

    static final long ARROW_CRIT_SEED_SALT = 0x5851F42D4C957F2DL;

    static long arrowCritSeed(int id, int tick) {
        long h = ((long) id * 2654435761L) ^ ((long) tick * 40503L) ^ ARROW_CRIT_SEED_SALT;
        h ^= h >>> 33;
        h *= -49064778989728563L;
        h ^= h >>> 29;
        h *= -4265267296055464877L;
        h ^= h >>> 32;
        return h;
    }

    static int arrowImpactDamage(GameState s, ProjectileState p) {
        double base = p.damage > 0f ? p.damage : Combat.ARROW_BASE_DAMAGE;
        double speed = Math.sqrt(p.vx * p.vx + p.vy * p.vy + p.vz * p.vz);
        double scaled = speed * base;
        if (scaled < 0.0) {
            scaled = 0.0;
        } else if (scaled > Integer.MAX_VALUE) {
            scaled = Integer.MAX_VALUE;
        }
        int damage = (int) Math.ceil(scaled);
        if ((p.bowEnchants & Combat.ARROW_CRIT_BIT) != 0) {
            damage += new java.util.Random(arrowCritSeed(p.id, s.tick)).nextInt(damage / 2 + 2);
        }
        return damage;
    }

    private static double gravityOf(ProjectileState p) {
        return switch (p.type) {
            case ProjectileState.TYPE_ARROW -> ARROW_GRAVITY;
            case ProjectileState.TYPE_FIREWORK -> -0.04;
            case ProjectileState.TYPE_SPLASH_POTION -> 0.05;
            case ProjectileState.TYPE_XP_BOTTLE -> 0.07;
            case ProjectileState.TYPE_WIND_CHARGE -> 0.0;
            default -> PEARL_GRAVITY;
        };
    }

    private static void applySplash(GameState s, ProjectileState p, double hx, double hy, double hz) {
        for (PlayerState v : s.players) {
            if (v.dead) {
                continue;
            }
            double dx = v.x - hx;
            double dy = (v.y + 1.0) - hy;
            double dz = v.z - hz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > Combat.SPLASH_RADIUS) {
                continue;
            }
            double proximity = 1.0 - dist / Combat.SPLASH_RADIUS;
            for (int i = 0; i < 4; i++) {
                int packed = p.effect(i);
                int id = me.nootnoot.sim.state.ItemDict.effectId(packed);
                if (id == me.nootnoot.sim.state.Effects.NONE) {
                    continue;
                }
                int amp = me.nootnoot.sim.state.ItemDict.effectAmplifier(packed);
                int dur = me.nootnoot.sim.state.ItemDict.effectDuration(packed);
                if (id == me.nootnoot.sim.state.Effects.INSTANT_HEALTH) {
                    v.health = Math.min(v.maxHealth, v.health + (float) (4.0 * (1 << amp) * proximity));
                } else if (id == me.nootnoot.sim.state.Effects.INSTANT_DAMAGE) {
                    Combat.applyDamage(v,
                            Combat.reduceByDefenseMagic(v, 6.0 * (1 << amp) * proximity));
                } else {
                    Combat.applyEffect(v, id, amp, (int) (dur * proximity));
                }
            }
        }
        s.events.add(new me.nootnoot.sim.state.CombatEvent(
                me.nootnoot.sim.state.CombatEvent.POTION_SPLASH,
                (int) Math.floor(hx), (int) Math.floor(hy), false, (int) Math.floor(hz)));
    }

    private static void applyArrowEffects(PlayerState v, ProjectileState p) {
        for (int i = 0; i < 4; i++) {
            int packed = p.effect(i);
            int id = me.nootnoot.sim.state.ItemDict.effectId(packed);
            if (id == me.nootnoot.sim.state.Effects.NONE) {
                continue;
            }
            Combat.applyEffect(v, id, me.nootnoot.sim.state.ItemDict.effectAmplifier(packed),
                    me.nootnoot.sim.state.ItemDict.effectDuration(packed));
        }
    }

    private static void ejectFromSolids(GameState s, Arena arena, PlayerState owner) {
        double width = Simulation.PLAYER_WIDTH;
        double height = Simulation.poseHeight(owner);
        for (int iter = 0; iter < 10; iter++) {
            Aabb box = Aabb.player(owner.x, owner.y, owner.z, width, height);
            java.util.List<Aabb> near = new java.util.ArrayList<>();
            arena.collectNearSolids(near, box.minX - 1.0, box.minY - 1.0, box.minZ - 1.0,
                    box.maxX + 1.0, box.maxY + 1.0, box.maxZ + 1.0, s.brokenArena);
            for (Aabb b : s.blocks.solids()) {
                near.add(b);
            }
            Aabb hit = null;
            double minPen = Double.MAX_VALUE;
            for (Aabb b : near) {
                double ox = Math.min(box.maxX, b.maxX) - Math.max(box.minX, b.minX);
                double oy = Math.min(box.maxY, b.maxY) - Math.max(box.minY, b.minY);
                double oz = Math.min(box.maxZ, b.maxZ) - Math.max(box.minZ, b.minZ);
                if (ox <= 1.0E-7 || oy <= 1.0E-7 || oz <= 1.0E-7) {
                    continue;
                }
                double pen = Math.min(ox, Math.min(oy, oz));
                if (pen < minPen) {
                    minPen = pen;
                    hit = b;
                }
            }
            if (hit == null) {
                return;
            }
            double ox = Math.min(box.maxX, hit.maxX) - Math.max(box.minX, hit.minX);
            double oy = Math.min(box.maxY, hit.maxY) - Math.max(box.minY, hit.minY);
            double oz = Math.min(box.maxZ, hit.maxZ) - Math.max(box.minZ, hit.minZ);
            double cx = (box.minX + box.maxX) * 0.5;
            double cy = (box.minY + box.maxY) * 0.5;
            double cz = (box.minZ + box.maxZ) * 0.5;
            double bcx = (hit.minX + hit.maxX) * 0.5;
            double bcy = (hit.minY + hit.maxY) * 0.5;
            double bcz = (hit.minZ + hit.maxZ) * 0.5;
            if (oy <= ox && oy <= oz) {
                double push = oy + 1.0E-4;
                if (cy >= bcy) {
                    owner.y += push;
                    owner.onGround = true;
                    owner.vy = 0.0;
                } else {
                    owner.y -= push;
                }
            } else if (ox <= oz) {
                owner.x += cx >= bcx ? (ox + 1.0E-4) : -(ox + 1.0E-4);
            } else {
                owner.z += cz >= bcz ? (oz + 1.0E-4) : -(oz + 1.0E-4);
            }
        }
    }

    private static final double PEARL_STEP_SIZE = 0.25;
    private static final double PEARL_MAX_PUSH_BACK = 3.0;
    private static final int PEARL_PUSH_BACK_STEPS = (int) (PEARL_MAX_PUSH_BACK / PEARL_STEP_SIZE);
    private static final double PEARL_SURFACE_TOLERANCE = 0.05;

    private static boolean pearlLandingClear(GameState s, Arena arena, PlayerState owner,
                                             double x, double y, double z) {
        double height = Simulation.poseHeight(owner);
        double hw = Simulation.PLAYER_WIDTH * 0.5 - PEARL_SURFACE_TOLERANCE;
        double minX = x - hw, maxX = x + hw;
        double minY = y + PEARL_SURFACE_TOLERANCE, maxY = y + height - PEARL_SURFACE_TOLERANCE;
        double minZ = z - hw, maxZ = z + hw;
        java.util.List<Aabb> near = new java.util.ArrayList<>();
        arena.collectNearSolids(near, minX - 1.0, minY - 1.0, minZ - 1.0,
                maxX + 1.0, maxY + 1.0, maxZ + 1.0, s.brokenArena);
        for (Aabb b : s.blocks.solids()) {
            near.add(b);
        }
        for (Aabb b : near) {
            if (maxX > b.minX && minX < b.maxX && maxY > b.minY && minY < b.maxY && maxZ > b.minZ && minZ < b.maxZ) {
                return false;
            }
        }
        return true;
    }

    private static void onImpact(GameState s, Arena arena, ProjectileState p, int hitPlayer,
                                 double hx, double hy, double hz, double dx, double dz) {
        if (p.type == ProjectileState.TYPE_PEARL) {
            if (!s.isInsidePlayableArea(hx, hz)) {
                return;
            }
            PlayerState owner = s.players[p.owner];

            double lx = hx, ly = hy, lz = hz;
            if (!pearlLandingClear(s, arena, owner, hx, hy, hz)) {
                double dirX = p.vx, dirY = p.vy, dirZ = p.vz;
                double len = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
                if (len > 1.0E-6) {
                    dirX /= len;
                    dirY /= len;
                    dirZ /= len;
                    for (int si = 1; si <= PEARL_PUSH_BACK_STEPS; si++) {
                        double step = PEARL_STEP_SIZE * si;
                        double cxp = hx - dirX * step;
                        double cyp = hy - dirY * step;
                        double czp = hz - dirZ * step;
                        if (s.isInsidePlayableArea(cxp, czp) && pearlLandingClear(s, arena, owner, cxp, cyp, czp)) {
                            lx = cxp;
                            ly = cyp;
                            lz = czp;
                            break;
                        }
                    }
                }
            }
            owner.x = lx;
            owner.y = ly;
            owner.z = lz;
            owner.vx = 0.0;
            owner.vy = 0.0;
            owner.vz = 0.0;
            owner.onGround = false;
            owner.fallDistance = 0.0f;
            owner.authoritySuspendTicks = Simulation.AUTHORITY_SUSPEND_TICKS;

            ejectFromSolids(s, arena, owner);

            float pearlDmg = 5.0f * (1.0f - Math.min(20.0f, owner.protection + 3.0f * owner.featherFalling) / 25.0f);
            if (Combat.applyDamage(owner, pearlDmg)) {
                s.events.add(new me.nootnoot.sim.state.CombatEvent(
                        me.nootnoot.sim.state.CombatEvent.HIT, p.owner, p.owner, false,
                        me.nootnoot.sim.state.CombatEvent.HIT_FALL));
            }
            return;
        }
        if (p.type == ProjectileState.TYPE_SPLASH_POTION) {
            applySplash(s, p, hx, hy, hz);
            return;
        }
        if (p.type == ProjectileState.TYPE_WIND_CHARGE) {
            Combat.windBurst(s, arena, hx, hy, hz, p.owner);
            return;
        }
        if (hitPlayer < 0) {
            return;
        }
        PlayerState v = s.players[hitPlayer];
        if (v.dead) {
            return;
        }
        if (p.type == ProjectileState.TYPE_SNOWBALL || p.type == ProjectileState.TYPE_EGG) {
            Combat.knockback(v, Combat.BASE_KNOCKBACK, -dx, -dz);
            return;
        }
        if (p.type != ProjectileState.TYPE_ARROW) {
            return;
        }

        if (Combat.blocksProjectile(v, dx, dz)) {
            v.hurtTime = Combat.I_FRAMES;
            v.lastDamage = 0.0f;
            s.events.add(new me.nootnoot.sim.state.CombatEvent(
                    me.nootnoot.sim.state.CombatEvent.BLOCK, p.owner, hitPlayer, false));
            return;
        }
        double raw = arrowImpactDamage(s, p);
        float dmg = Combat.reduceByDefenseProjectile(v, raw);
        if (!Combat.applyDamage(v, dmg)) {
            return;
        }
        Combat.damageArmor(s, v, dmg);
        Combat.knockback(v, Combat.BASE_KNOCKBACK, -dx, -dz);
        int punch = p.bowEnchants & 0x7;
        if (punch > 0) {
            double horiz = Math.sqrt(dx * dx + dz * dz);
            if (horiz > 1.0E-4) {
                double k = punch * 0.6;
                v.vx += dx / horiz * k;
                v.vz += dz / horiz * k;
                v.vy += 0.1;
            }
        }
        if (((p.bowEnchants >> 3) & 1) != 0) {
            v.fireTicks = Math.max(v.fireTicks, 100);
        }
        applyArrowEffects(v, p);

        s.events.add(new me.nootnoot.sim.state.CombatEvent(me.nootnoot.sim.state.CombatEvent.HIT,
                p.owner, hitPlayer, false, me.nootnoot.sim.state.CombatEvent.HIT_ARROW));

    }
}
