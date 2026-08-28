package me.nootnoot.sim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.nootnoot.sim.contract.HostFrameContract;
import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.math.MathTables;
import me.nootnoot.sim.math.Raycast;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.ClickBudget;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;

public final class Combat {
    public static final float BASE_DAMAGE = 6.0F;
    public static final double ATTACK_COOLDOWN_TICKS = 12.5;
    public static final double DEFAULT_ATTACK_RANGE = ItemDict.DEFAULT_ATTACK_RANGE;
    public static final double EYE_STANDING = 1.62;
    public static final double EYE_SNEAKING = 1.27;
    public static final double EYE_GLIDING = 0.4;

    public static double eyeHeight(PlayerState p) {
        if (p.gliding || p.swimming) {
            return EYE_GLIDING;
        }
        return p.sneaking ? EYE_SNEAKING : EYE_STANDING;
    }

    public static double eyeHeightForPose(double poseHeight) {
        if (poseHeight <= Simulation.PLAYER_SWIM_HEIGHT) {
            return EYE_GLIDING;
        }
        return poseHeight <= Simulation.PLAYER_SNEAK_HEIGHT ? EYE_SNEAKING : EYE_STANDING;
    }

    public static final double BASE_KNOCKBACK = 0.4;

    public static final double SPRINT_KNOCKBACK = 0.5;

    public static final double SHIELD_COUNTER_KNOCKBACK = 0.5;
    public static final int I_FRAMES = 10;
    public static final float CRIT_MULTIPLIER = 1.5F;
    public static final double PEARL_SPEED = 1.5;
    public static final int KNOCKBACK_ENCHANT = 0;

    private static final float DEG_TO_RAD = (float) Math.PI / 180.0F;

    private static final double STACKED_KB_EPS_SQ = 0.01;

    private Combat() {
    }

    public static void resolve(GameState s, Arena arena, Input in0, Input in1) {
        tickCombatState(s.players[0]);
        tickCombatState(s.players[1]);

        handleAttack(s, arena, 0, 1, in0);
        handleAttack(s, arena, 1, 0, in1);

        handleUse(s, arena, 0, in0);
        handleUse(s, arena, 1, in1);

        revalidateOpenContainer(s, 0);
        revalidateOpenContainer(s, 1);

        handleInventory(s, 0, in0);
        handleInventory(s, 1, in1);

        handleDrop(s, 0, in0);
        handleDrop(s, 1, in1);

        carryGestureEdges(s.players[0], in0);
        carryGestureEdges(s.players[1], in1);

        tickMissPenalty(s.players[0]);
        tickMissPenalty(s.players[1]);
    }

    private static void carryGestureEdges(PlayerState p, Input in) {
        if (in.synthetic()) {
            return;
        }
        p.prevAttack = in.attack();
        p.prevUse = in.use();
        p.prevOffhandUse = in.offhandUse();
        p.prevUsePress = in.usePress();
    }

    public static void resolveBlockActions(GameState s, Arena arena, Input in0, Input in1) {
        if (roundStartLocked(s)) {
            return;
        }
        handleBlockAction(s, arena, 0, ruleFiltered(s, in0));
        handleBlockAction(s, arena, 1, ruleFiltered(s, in1));
    }

    public static boolean ruleForbids(GameState s, int action) {
        if (!s.allowExplosion
                && (action == Input.BLOCK_PLACE_CRYSTAL
                    || action == Input.BLOCK_HIT_CRYSTAL
                    || action == Input.BLOCK_PLACE_ANCHOR
                    || action == Input.BLOCK_CHARGE_ANCHOR
                    || action == Input.BLOCK_DETONATE_ANCHOR)) {
            return true;
        }
        return !s.allowBucket
                && (action == Input.BLOCK_PLACE_WATER
                    || action == Input.BLOCK_PLACE_LAVA
                    || action == Input.BLOCK_PICKUP_FLUID);
    }

    public static Input ruleFiltered(GameState s, Input in) {
        if (s.allowExplosion && s.allowBucket) {
            return in;
        }
        Input out = in;
        if (!s.allowExplosion && in.crystalHit()) {
            out = out.withCrystalHit(false, 0, 0, 0);
        }
        if (ruleForbids(s, in.blockAction())) {
            out = out.withBlockAction(Input.BLOCK_NONE, 0, 0, 0);
        }
        return out;
    }

    public static Input contractFiltered(Input in) {
        if (in == null || in.synthetic()) {
            return in;
        }
        Input out = in;
        if (out.meleeHit() && out.crystalHit()) {
            SimProbe.hit(SimProbe.LEFT_CLICK_CLAIMED_TWO_TARGETS);
        }
        if (out.blockAction() == Input.BLOCK_BREAK
                && !HostFrameContract.minesThisFrame(out.crystalHit(), out.meleeHit())) {
            out = out.withBlockAction(Input.BLOCK_NONE, 0, 0, 0);
        }
        if (invOpAction(out.invAction()) && HostFrameContract.worldActionFrame(out)) {
            SimProbe.hit(SimProbe.INV_OP_ON_A_COMBAT_FRAME);
        }
        Clicks c = out.clicks();
        int attack = HostFrameContract.attackClicks(out.blockAction(), c.attack());
        if (attack != c.attack()) {
            out = out.withClicks(c.withAttack(attack));
        }
        return out;
    }

    public static final int DROP_UID_SLOT_SHIFT = 28;

    public static final int DROP_UID_SEQ_MASK = 0x0FFFFFFF;

    public static int dropUid(int slot, int seq) {
        return ((slot + 1) << DROP_UID_SLOT_SHIFT) | (seq & DROP_UID_SEQ_MASK);
    }

    public static int dropUidOwner(int uid) {
        return ((uid >>> DROP_UID_SLOT_SHIFT) & 0xF) - 1;
    }

    public static boolean breakAllowed(GameState s, int blockItemId) {
        return whitelisted(s.breakableItemIds, blockItemId);
    }

    public static boolean placeAllowed(GameState s, int itemId) {
        return whitelisted(s.placeableItemIds, itemId);
    }

    private static boolean whitelisted(int[] allowed, int itemId) {
        return allowed == null || allowed.length == 0
                || Arrays.binarySearch(allowed, itemId) >= 0;
    }

    public static boolean roundStartLocked(GameState s) {
        return s.roundStartGrace > 0;
    }

    public static boolean reachHit(PlayerState a, float yaw, float pitch, PlayerState v) {
        double eye = eyeHeight(a);
        Vec3 look = lookVector(yaw, pitch);
        double vHeight = Simulation.poseHeight(v);
        Aabb vbox = Aabb.player(v.x, v.y, v.z, Simulation.PLAYER_WIDTH, vHeight);
        double t = Raycast.segmentBox(vbox, a.x, a.y + eye, a.z,
                look.x() * DEFAULT_ATTACK_RANGE, look.y() * DEFAULT_ATTACK_RANGE,
                look.z() * DEFAULT_ATTACK_RANGE);
        return t >= 0.0 && t <= 1.0;
    }

    public static final double BLOCK_INTERACTION_BUFFER = 1.0;

    public static final double CONTAINER_INTERACTION_BUFFER = 4.0;

    public static final double ENTITY_ATTACK_BUFFER = 3.0;

    public static final double PICKUP_SWEEP_XZ = 1.0;

    public static final double PICKUP_SWEEP_Y = 0.5;

    public static Aabb pickupSweep(PlayerState p) {
        return Aabb.player(p.x, p.y, p.z, Simulation.PLAYER_WIDTH, Simulation.poseHeight(p))
                .inflate(PICKUP_SWEEP_XZ, PICKUP_SWEEP_Y, PICKUP_SWEEP_XZ);
    }

    private static boolean withinBlockReach(PlayerState a, Input in) {
        return withinBlockReach(a, in.targetX(), in.targetY(), in.targetZ());
    }

    private static boolean withinBlockReach(PlayerState a, int bx, int by, int bz) {
        return withinCellReach(a, bx, by, bz, blockReachLimit());
    }

    public static double blockReachLimit() {
        return BLOCK_REACH + BLOCK_INTERACTION_BUFFER;
    }

    public static double containerReachLimit() {
        return BLOCK_REACH + CONTAINER_INTERACTION_BUFFER;
    }

    public static double attackPickReach(GameState s, PlayerState a) {
        return attackPickReachAt(s, a, Loadout.mainSlot(a));
    }

    public static double attackPickReachAt(GameState s, PlayerState a, int slot) {
        ItemDict d = Loadout.dict(s);
        int entry = Loadout.entryAt(a, Loadout.clampSlot(slot));
        return d.attackMaxRange(entry) + d.attackHitboxMargin(entry);
    }

    public static double attackPickMinReach(GameState s, PlayerState a) {
        return attackPickMinReachAt(s, a, Loadout.mainSlot(a));
    }

    public static double attackPickMinReachAt(GameState s, PlayerState a, int slot) {
        ItemDict d = Loadout.dict(s);
        int entry = Loadout.entryAt(a, Loadout.clampSlot(slot));
        return d.attackMinRange(entry);
    }

    public static double attackPickHitboxMarginAt(GameState s, PlayerState a, int slot) {
        ItemDict d = Loadout.dict(s);
        int entry = Loadout.entryAt(a, Loadout.clampSlot(slot));
        return d.attackHitboxMargin(entry);
    }

    public static double attackReachLimit(GameState s, PlayerState a) {
        return attackPickReach(s, a) + ENTITY_ATTACK_BUFFER;
    }

    public static boolean withinAttackRange(double distanceSq, double limit) {
        return Math.sqrt(distanceSq) <= limit;
    }

    private static boolean withinCellReach(PlayerState a, int bx, int by, int bz, double limit) {
        double eye = eyeHeight(a);
        double cx = Math.max(bx, Math.min(a.x, bx + 1.0));
        double cy = Math.max(by, Math.min(a.y + eye, by + 1.0));
        double cz = Math.max(bz, Math.min(a.z, bz + 1.0));
        double dx = a.x - cx;
        double dy = a.y + eye - cy;
        double dz = a.z - cz;
        return dx * dx + dy * dy + dz * dz < limit * limit;
    }

    public static Aabb crystalBox(int bx, int by, int bz) {
        return new Aabb(bx - 0.5, by + 1.0, bz - 0.5, bx + 1.5, by + 3.0, bz + 1.5);
    }

    public static final double CRYSTAL_PICK_MISS = -1.0;

    public static double crystalPickDistanceSq(GameState s, PlayerState a, int slot,
                                               double lookX, double lookY, double lookZ,
                                               int bx, int by, int bz) {
        double reach = attackPickReachAt(s, a, slot);
        if (reach <= 0.0) {
            return CRYSTAL_PICK_MISS;
        }
        double minReach = attackPickMinReachAt(s, a, slot);
        double margin = attackPickHitboxMarginAt(s, a, slot);
        double eye = eyeHeight(a);
        double ex = a.x;
        double ey = a.y + eye;
        double ez = a.z;
        double fx = ex + lookX * minReach;
        double fy = ey + lookY * minReach;
        double fz = ez + lookZ * minReach;
        double tx = ex + lookX * reach;
        double ty = ey + lookY * reach;
        double tz = ez + lookZ * reach;
        Aabb box = crystalBox(bx, by, bz);
        if (boxContains(box, fx, fy, fz)) {
            return 0.0;
        }
        double t = clipFraction(box, fx, fy, fz, tx, ty, tz);
        if (t < 0.0 && margin > 0.0) {
            t = clipFraction(box.inflate(margin), fx, fy, fz, tx, ty, tz);
        }
        if (t < 0.0) {
            return CRYSTAL_PICK_MISS;
        }
        double hx = fx + (tx - fx) * t - ex;
        double hy = fy + (ty - fy) * t - ey;
        double hz = fz + (tz - fz) * t - ez;
        return hx * hx + hy * hy + hz * hz;
    }

    private static boolean boxContains(Aabb box, double x, double y, double z) {
        return x >= box.minX && x <= box.maxX
                && y >= box.minY && y <= box.maxY
                && z >= box.minZ && z <= box.maxZ;
    }

    private static final double RAY_PARALLEL_EPS = 1.0E-9;

    private static double clipFraction(Aabb box, double ox, double oy, double oz,
                                       double ex, double ey, double ez) {
        double dx = ex - ox;
        double dy = ey - oy;
        double dz = ez - oz;
        double tmin = 0.0;
        double tmax = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            double origin = axis == 0 ? ox : axis == 1 ? oy : oz;
            double delta = axis == 0 ? dx : axis == 1 ? dy : dz;
            double lo = axis == 0 ? box.minX : axis == 1 ? box.minY : box.minZ;
            double hi = axis == 0 ? box.maxX : axis == 1 ? box.maxY : box.maxZ;
            if (Math.abs(delta) < RAY_PARALLEL_EPS) {
                if (origin < lo || origin > hi) {
                    return CRYSTAL_PICK_MISS;
                }
                continue;
            }
            double t1 = (lo - origin) / delta;
            double t2 = (hi - origin) / delta;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
            if (tmin > tmax) {
                return CRYSTAL_PICK_MISS;
            }
        }
        return tmin;
    }

    public static int projectileClaim(GameState s, int owner) {
        if (s == null || owner < 0 || owner >= s.players.length) {
            return Input.NO_PROJECTILE_HIT;
        }
        int opponent = 1 - owner;
        Aabb oppHull = opponent >= 0 && opponent < s.players.length
                ? claimHull(s.players[opponent]) : null;
        Aabb ownHull = claimHull(s.players[owner]);
        for (int i = 0; i < s.projectiles.size(); i++) {
            ProjectileState p = s.projectiles.get(i);
            if (p.dead || p.stuck || p.fresh || p.claimSpent
                    || p.type != ProjectileState.TYPE_ARROW || p.owner != owner) {
                continue;
            }
            if (oppHull != null && crosses(oppHull, p)) {
                return p.id & Input.PROJECTILE_HIT_ID_MASK;
            }
            if (p.leftOwner && ownHull != null && crosses(ownHull, p)) {
                return Input.PROJECTILE_HIT_SELF | (p.id & Input.PROJECTILE_HIT_ID_MASK);
            }
        }
        return Input.NO_PROJECTILE_HIT;
    }

    private static Aabb claimHull(PlayerState v) {
        if (v == null || v.dead) {
            return null;
        }
        return Aabb.player(v.x, v.y, v.z, Simulation.PLAYER_WIDTH, Simulation.poseHeight(v))
                .inflate(ClaimAuthority.ARROW_MARGIN);
    }

    private static boolean crosses(Aabb hull, ProjectileState p) {
        if (boxContains(hull, p.x, p.y, p.z)) {
            return true;
        }
        double t = Raycast.segmentBox(hull, p.x, p.y, p.z, p.vx, p.vy, p.vz);
        return t >= 0.0 && t <= 1.0;
    }

    public static boolean withinCrystalAttackRange(GameState s, PlayerState a, int slot,
                                                   int bx, int by, int bz) {
        Aabb box = crystalBox(bx, by, bz);
        double eye = eyeHeight(a);
        Vec3 near = ClaimAuthority.nearestPoint(box, a.x, a.y + eye, a.z);
        double dx = a.x - near.x();
        double dy = a.y + eye - near.y();
        double dz = a.z - near.z();
        double limit = attackPickReachAt(s, a, slot) + ENTITY_ATTACK_BUFFER;
        return withinAttackRange(dx * dx + dy * dy + dz * dz, limit);
    }

    private static boolean crystalInSight(GameState s, Arena arena, PlayerState a,
                                          int bx, int by, int bz) {
        double eye = eyeHeight(a);
        Vec3 aim = ClaimAuthority.nearestPoint(crystalBox(bx, by, bz), a.x, a.y + eye, a.z);
        return ClaimAuthority.sightClear(s, arena, a, aim)
                && !ClaimAuthority.cobwebCrosses(s, a.x, a.y + eye, a.z, aim);
    }

    public static Aabb cellBox(int bx, int by, int bz) {
        return new Aabb(bx, by, bz, bx + 1.0, by + 1.0, bz + 1.0);
    }

    private static boolean cellInSight(GameState s, Arena arena, PlayerState a,
                                       int bx, int by, int bz) {
        double eye = eyeHeight(a);
        Vec3 aim = ClaimAuthority.nearestPoint(cellBox(bx, by, bz), a.x, a.y + eye, a.z);
        if (aim.x() == a.x && aim.y() == a.y + eye && aim.z() == a.z) {
            return true;
        }
        return ClaimAuthority.sightClear(s, arena, a, aim)
                && !ClaimAuthority.cobwebCrosses(s, a.x, a.y + eye, a.z, aim);
    }

    private static void tickCombatState(PlayerState p) {
        if (p.hurtTime > 0) {
            p.hurtTime--;
        }
        if (p.attackTicker < 72000) {
            p.attackTicker++;
        }
    }

    private static boolean attackEdge(PlayerState a, Input in) {
        return in.attack() && !a.prevAttack;
    }

    static ClickBudget budget(GameState s, PlayerState a, Input in) {
        ClickBudget b = a.clickBudget;
        if (!b.loadedFor(s.tick)) {
            b.load(s.tick, in.clicks(), useClickCap(s, in), attackEdge(a, in), in.synthetic());
        }
        return b;
    }

    private static int useClickCap(GameState s, Input in) {
        int action = in.blockAction();
        return blockUseClicks(ruleForbids(s, action) ? Input.BLOCK_NONE : action, Clicks.MAX);
    }

    private static void handleAttack(GameState s, Arena arena, int attackerIdx, int victimIdx, Input in) {
        if (roundStartLocked(s)) {
            return;
        }
        PlayerState a = s.players[attackerIdx];
        ClickBudget b = budget(s, a, in);
        if (a.dead || a.eating || a.blockTicks > 0) {
            return;
        }
        while (true) {
            if (!b.takeAttack()) {
                break;
            }
            if (!spendLeftClick(a)) {
                continue;
            }
            swingOnce(s, arena, attackerIdx, victimIdx, in);
            if (leftClickNamedNothing(in)) {
                armMissPenalty(a);
            }
        }
    }

    public static final int MISS_PENALTY_TICKS = 10;

    public static boolean missPenaltyActive(PlayerState a) {
        return a.missTicks > 0;
    }

    private static boolean leftClickNamedNothing(Input in) {
        return !in.meleeHit() && !in.crystalHit() && in.blockAction() == Input.BLOCK_NONE;
    }

    private static boolean spendLeftClick(PlayerState a) {
        if (missPenaltyActive(a)) {
            SimProbe.hit(SimProbe.ATTACK_REFUSED_BY_MISS_PENALTY);
            return false;
        }
        return true;
    }

    private static void armMissPenalty(PlayerState a) {
        SimProbe.hit(SimProbe.MISS_PENALTY_ARMED);
        a.missTicks = MISS_PENALTY_TICKS;
    }

    private static void tickMissPenalty(PlayerState p) {
        if (p.missTicks > 0) {
            p.missTicks--;
        }
    }

    private static void swingOnce(GameState s, Arena arena, int attackerIdx, int victimIdx, Input in) {
        PlayerState a = s.players[attackerIdx];
        PlayerState v = s.players[victimIdx];

        double cooldownTicks = a.attackSpeed > 0.01f ? 20.0 / a.attackSpeed : ATTACK_COOLDOWN_TICKS;

        s.events.add(new CombatEvent(CombatEvent.SWING, attackerIdx, attackerIdx, false));

        double progress = Math.min(1.0, (a.attackTicker + 0.5) / cooldownTicks);
        a.attackTicker = 0;

        if (v.dead) {
            return;
        }

        if (!in.meleeHit()) {
            return;
        }
        if (a.meleeClaimTick == s.tick) {
            return;
        }
        a.meleeClaimTick = s.tick;
        if (ClaimAuthority.meleeClaim(s, arena, a, v) == null) {
            return;
        }

        if (isBlocking(v) && blockedFront(v, a.x, a.z)) {
            boolean axe = Loadout.isAxe(s, a);
            if (axe) {
                v.shieldDisabled = SHIELD_DISABLE_TICKS;
                v.blockTicks = 0;
            }
            v.lastBlockHitTick = s.tick;
            boolean blockTookFullDamage = v.hurtTime <= 0;
            if (blockTookFullDamage) {
                v.lastDamage = 0.0f;
                v.hurtTime = I_FRAMES;
            }

            double bkx = a.x - v.x;
            double bkz = a.z - v.z;
            if (bkx * bkx + bkz * bkz < STACKED_KB_EPS_SQ) {
                float bkYaw = a.yaw * DEG_TO_RAD;
                bkx = -MathTables.sin(bkYaw);
                bkz = MathTables.cos(bkYaw);
            }
            knockback(v, SHIELD_COUNTER_KNOCKBACK, -bkx, -bkz);
            if (blockTookFullDamage) {
                knockback(v, BASE_KNOCKBACK, bkx, bkz);
            }

            wearWeapon(s, a);
            s.events.add(new CombatEvent(CombatEvent.BLOCK, attackerIdx, victimIdx, axe));
            return;
        }

        double baseDamage = a.attackDamage;
        if (a.effectTicks[Effects.STRENGTH] > 0) {
            baseDamage += 3.0 * (a.effectAmp[Effects.STRENGTH] + 1);
        }
        if (a.effectTicks[Effects.WEAKNESS] > 0) {
            baseDamage -= 4.0 * (a.effectAmp[Effects.WEAKNESS] + 1);
        }
        baseDamage = Math.max(0.0, baseDamage);

        boolean fullStrength = progress > 0.9;
        double dmg = baseDamage * (0.2 + progress * progress * 0.8);

        boolean smash = Loadout.isMace(s, a) && a.fallDistance > 1.5f && !a.gliding;
        if (smash) {
            float fd = a.fallDistance;
            double fallBonus = fd <= 3.0f ? 4.0 * fd : (fd <= 8.0f ? 12.0 + 2.0 * (fd - 3.0) : 22.0 + (fd - 8.0));
            dmg += fallBonus + 0.5 * Loadout.density(s, a) * fd;
        }

        boolean crit = fullStrength && canCrit(a);
        if (crit) {
            dmg *= CRIT_MULTIPLIER;
        }
        int sharpness = Loadout.sharpness(s, a);
        if (sharpness > 0) {
            dmg += (0.5 + 0.5 * sharpness) * progress;
        }

        if (s.potSwordBoost && Loadout.isSword(s, a)) {
            dmg *= 1.33;
        }

        int breach = Loadout.breach(s, a);
        float finalDmg = breach > 0 ? reduceByDefenseBreach(v, dmg, breach) : reduceByDefense(v, dmg);
        boolean fresh = applyDamage(v, finalDmg);
        a.exhaustion += 0.1f;
        if (!fresh) {
            return;
        }
        wearWeapon(s, a);
        damageArmor(s, v, finalDmg);

        double bkx = a.x - v.x;
        double bkz = a.z - v.z;
        if (bkx * bkx + bkz * bkz < STACKED_KB_EPS_SQ) {
            float bkYaw = a.yaw * DEG_TO_RAD;
            bkx = -MathTables.sin(bkYaw);
            bkz = MathTables.cos(bkYaw);
        }
        knockback(v, BASE_KNOCKBACK, bkx, bkz);

        boolean knockbackAttack = a.sprinting && fullStrength;
        double kbBonus = a.knockbackLevel * 0.5 + (knockbackAttack ? SPRINT_KNOCKBACK : 0.0);
        if (kbBonus > 0.0) {
            float yawRad = a.yaw * DEG_TO_RAD;
            knockback(v, kbBonus, MathTables.sin(yawRad), -MathTables.cos(yawRad));
            a.vx *= 0.6;
            a.vz *= 0.6;
            a.sprinting = false;
        }

        int hitKind = crit ? CombatEvent.HIT_CRIT
                : knockbackAttack ? CombatEvent.HIT_KNOCKBACK
                : fullStrength ? CombatEvent.HIT_STRONG
                : CombatEvent.HIT_WEAK;

        if (smash) {
            hitKind = a.fallDistance > 5.0f ? CombatEvent.HIT_SMASH_HEAVY : CombatEvent.HIT_SMASH;
        }
        s.events.add(new CombatEvent(CombatEvent.HIT, attackerIdx, victimIdx, crit, hitKind));
        int fireAspect = Loadout.fireAspect(s, a);
        if (fireAspect > 0) {
            v.fireTicks = Math.max(v.fireTicks, fireAspect * 80);
        }

        if (smash) {
            a.vy = MACE_SMASH_REBOUND_VY;
            a.fallDistance = 0.0f;
            int burst = Loadout.windBurst(s, a);
            if (burst > 0) {
                a.onGround = false;
                windBurst(s, arena, a.x, a.y, a.z,
                        WIND_BURST_ENCHANT_RADIUS, windBurstKnockback(burst), attackerIdx);
            }
        }

    }

    public static final double MACE_SMASH_REBOUND_VY = 0.01;

    public static final double WIND_BURST_ENCHANT_RADIUS = 7.0;

    private static final double[] WIND_BURST_ENCHANT_KNOCKBACK = {1.2, 1.75, 2.2};

    public static double windBurstKnockback(int level) {
        if (level <= 0) {
            return 0.0;
        }
        if (level <= WIND_BURST_ENCHANT_KNOCKBACK.length) {
            return WIND_BURST_ENCHANT_KNOCKBACK[level - 1];
        }
        return 1.5 + 0.35 * (level - 1);
    }

    private static boolean canCrit(PlayerState a) {
        return a.fallDistance > 0.0f && !a.onGround && !a.sprinting && !a.swimming && !a.gliding;
    }

    public static final int WEAPON_HIT_WEAR = 1;
    public static final int DIGGER_HIT_WEAR = 2;

    static void wearWeapon(GameState s, PlayerState a) {
        int slot = Loadout.mainSlot(a);
        Loadout.damageSlot(s, a, slot, meleeWear(s, Loadout.entryAt(a, slot)));
    }

    public static int meleeWear(GameState s, int entry) {
        ItemDict d = s.dict;
        if (d.isAxe(entry)) {
            return DIGGER_HIT_WEAR;
        }
        int toolClass = d.toolClass(entry);
        return toolClass == ItemDict.TOOL_PICKAXE || toolClass == ItemDict.TOOL_AXE
                || toolClass == ItemDict.TOOL_SHOVEL || toolClass == ItemDict.TOOL_HOE
                ? DIGGER_HIT_WEAR : WEAPON_HIT_WEAR;
    }

    public static void knockback(PlayerState v, double strength, double dx, double dz) {
        strength *= 1.0 - v.kbResistance;
        if (strength <= 0.0) {
            return;
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) {
            return;
        }
        double nx = dx / len;
        double nz = dz / len;
        double curX = v.vx;
        double curY = v.vy;
        double curZ = v.vz;
        v.vx = curX / 2.0 - nx * strength;
        v.vz = curZ / 2.0 - nz * strength;
        if (v.onGround) {
            v.vy = Math.min(0.4, curY / 2.0 + strength);
        }
        recordImpulse(v);
    }

    public static void recordImpulse(PlayerState v) {
        v.impulseVx = v.vx;
        v.impulseVy = v.vy;
        v.impulseVz = v.vz;
        v.impulseSeq++;
    }

    public static final int USE_NONE = 0;
    public static final int USE_PEARL = 1;
    public static final int USE_FOOD = 2;
    public static final int USE_SNOWBALL = 3;
    public static final int USE_EGG = 4;
    public static final int USE_BOW = 5;
    public static final int USE_FIREWORK = 6;
    public static final int USE_CROSSBOW = 7;
    public static final int USE_SPLASH_POTION = 8;
    public static final int USE_SHIELD = 9;

    public static final int USE_XP_BOTTLE = 10;
    public static final int USE_WIND_CHARGE = 11;

    public static final int SHIELD_WARMUP = 5;

    public static final int SHIELD_DISABLE_TICKS = 100;

    public static final int PEARL_COOLDOWN_TICKS = 20;

    public static final int WIND_CHARGE_COOLDOWN_TICKS = 10;

    public static final int WIND_CHARGE_MAX_AGE = 60;

    public static final int SNOWBALL_COOLDOWN_TICKS = 0;

    public static final int EGG_COOLDOWN_TICKS = 0;

    public static final int SPLASH_POTION_COOLDOWN_TICKS = 0;

    public static final int XP_BOTTLE_COOLDOWN_TICKS = 0;

    public static final int FIREWORK_COOLDOWN_TICKS = 0;

    public static final int CROSSBOW_COOLDOWN_TICKS = 0;

    public static final int USE_REPEAT_DELAY = 4;

    public static boolean continuousUse(int kind) {
        return kind == USE_SHIELD || kind == USE_BOW || kind == USE_CROSSBOW || kind == USE_FOOD;
    }

    public static int useCooldownTicks(int kind) {
        return switch (kind) {
            case USE_PEARL -> PEARL_COOLDOWN_TICKS;
            case USE_WIND_CHARGE -> WIND_CHARGE_COOLDOWN_TICKS;
            case USE_SNOWBALL -> SNOWBALL_COOLDOWN_TICKS;
            case USE_EGG -> EGG_COOLDOWN_TICKS;
            case USE_SPLASH_POTION -> SPLASH_POTION_COOLDOWN_TICKS;
            case USE_XP_BOTTLE -> XP_BOTTLE_COOLDOWN_TICKS;
            case USE_FIREWORK -> FIREWORK_COOLDOWN_TICKS;
            case USE_CROSSBOW -> CROSSBOW_COOLDOWN_TICKS;
            default -> 0;
        };
    }

    public static void tickUseDelays(PlayerState p) {
        for (int i = 0; i < p.useCooldown.length; i++) {
            if (p.useCooldown[i] > 0) {
                p.useCooldown[i]--;
            }
        }
        if (p.useDelay > 0) {
            p.useDelay--;
        }
    }

    private static boolean discreteUseClick(PlayerState a, Input in) {
        return in.usePress() && (!a.prevUsePress || in.use() != a.prevUse);
    }

    private static boolean useSpentThisTick(PlayerState a) {
        return a.useDelay == USE_REPEAT_DELAY;
    }

    private static boolean useFires(PlayerState a, boolean press, boolean held, int kind,
                                    boolean discrete) {
        if (a.useCooldown[kind] > 0) {
            return false;
        }
        if (!press && !held) {
            return false;
        }
        if (useSpentThisTick(a)) {
            return false;
        }
        return discrete || a.useDelay == 0;
    }

    private static int useAttempts(ClickBudget b, PlayerState a, boolean press, boolean held,
                                   int kind, boolean discrete) {
        if (b.use > 0) {
            return press || held ? b.use : 0;
        }
        return b.useUncounted && useFires(a, press, held, kind, discrete) ? 1 : 0;
    }

    private static boolean takeUseAttempt(ClickBudget b) {
        return b.takeUse();
    }

    private static boolean useReady(PlayerState a, int kind) {
        return a.useCooldown[kind] == 0;
    }

    private static void startUseItem(PlayerState a, int kind) {
        SimProbe.band(SimProbe.USE_FIRED_BASE, kind, SimProbe.USE_KINDS);
        a.useDelay = USE_REPEAT_DELAY;
        a.useCooldown[kind] = useCooldownTicks(kind);
    }

    public static final int DESTROY_DELAY = 5;

    public static boolean isBlockUse(int action) {
        return action == Input.BLOCK_OPEN_CONTAINER
                || action == Input.BLOCK_PLACE
                || action == Input.BLOCK_PLACE_OFFHAND
                || action == Input.BLOCK_PLACE_WATER
                || action == Input.BLOCK_PLACE_LAVA
                || action == Input.BLOCK_PICKUP_FLUID
                || action == Input.BLOCK_PLACE_CRYSTAL
                || action == Input.BLOCK_PLACE_ANCHOR
                || action == Input.BLOCK_CHARGE_ANCHOR
                || action == Input.BLOCK_DETONATE_ANCHOR;
    }

    public static boolean arenaTerrainMineable(GameState s, Arena arena, int x, int y, int z) {
        return s.vanillaBuild && arena != null
                && (arena.isSolidVoxel(x, y, z) || arena.isDecorVoxel(x, y, z))
                && !s.brokenArena.contains(BlockStore.key(x, y, z));
    }

    public static boolean breakableCell(GameState s, Arena arena, int x, int y, int z) {
        long key = BlockStore.key(x, y, z);
        if (s.blocks.contains(x, y, z) || s.cobwebs.containsKey(key)
                || s.fires.containsKey(key)) {
            return true;
        }
        return arenaTerrainMineable(s, arena, x, y, z);
    }

    public static final int BLOCK_USE_CLICKS = 1;

    public static int blockUseClicks(int action, int useClicks) {
        return isBlockUse(action) && useClicks > BLOCK_USE_CLICKS ? BLOCK_USE_CLICKS : useClicks;
    }

    private static void startBlockUse(PlayerState a) {
        a.useDelay = USE_REPEAT_DELAY;
    }

    public static final double WIND_BURST_RADIUS = 2.4;
    public static final double WIND_BURST_KNOCKBACK = 1.22;
    public static final double WIND_BURST_RADIUS_GLIDE = 3.5;
    public static final double WIND_BURST_KNOCKBACK_GLIDE = 1.5;

    public static final int EAT_DURATION = 32;

    public static final int MIN_EAT_TICKS = 16;

    public static final int EAT_BITE_GAP = 1;

    public static final double THROW_SPEED = 1.5;

    public static final double ARROW_MAX_SPEED = 3.0;

    public static final int CROSSBOW_LOAD = 25;

    public static final double CROSSBOW_SPEED = 3.15;

    public static final double POTION_THROW_SPEED = 0.5;

    public static final double XP_BOTTLE_THROW_SPEED = 0.7;

    public static final double THROWN_ITEM_SPAWN_EYE_DROP = 0.1;

    public static final double SPLASH_RADIUS = 4.0;

    public static final double BLOCK_REACH = 4.5;

    private static void handleUse(GameState s, Arena arena, int idx, Input in) {
        if (roundStartLocked(s) || in.synthetic()) {
            return;
        }
        PlayerState a = s.players[idx];
        if (!in.use()) {
            a.crossbowHeldUseSlot = PlayerState.NO_CROSSBOW_HELD_USE;
        }
        if (a.dead) {
            a.eating = false;
            a.eatTicks = 0;
            a.offhandEatTicks = 0;
            return;
        }
        ClickBudget b = budget(s, a, in);

        int kind = Loadout.useKind(s, a);
        SimProbe.band(SimProbe.USE_MAIN_BASE, kind, SimProbe.USE_KINDS);

        if (kind != USE_NONE) {
            a.offhandEatTicks = 0;
        }

        if (kind == USE_FOOD) {
            int slot = clampSlot(a.heldSlot);
            boolean hasItem = Loadout.countAt(a, slot) > 0;
            boolean hungry = a.food < 20.0f || Loadout.alwaysEdible(s, a, Loadout.HAND_MAIN);
            if (in.use() && hasItem && hungry && a.eatGap == 0) {
                a.eating = true;
                a.eatTicks++;
                int mainEatTicks = Loadout.foodEatTicks(s, a, Loadout.HAND_MAIN);
                int eatTime = Math.max(MIN_EAT_TICKS, mainEatTicks > 0 ? mainEatTicks : EAT_DURATION);
                if (a.eatTicks >= eatTime) {
                    int bite = Loadout.entryAt(a, slot);
                    int nutrition = s.dict.foodNutrition(bite);
                    float saturation = s.dict.foodSaturation(bite);
                    if (Loadout.consume(a, slot, 1)) {
                        a.food = Math.min(20.0f, a.food + nutrition);
                        a.saturation = Math.min(a.food, a.saturation + saturation);
                        applyEntryEffects(s, a, bite);
                    }
                    a.eating = false;
                    a.eatTicks = 0;
                    a.eatGap = EAT_BITE_GAP;
                }
            } else if (a.eatGap > 0) {
                a.eating = false;
                a.eatTicks = 0;
                a.eatGap--;
            } else {
                a.eating = false;
                a.eatTicks = 0;
            }
            return;
        }
        a.eating = false;
        a.eatTicks = 0;

        if (kind == USE_BOW) {
            if (in.use() && a.arrows > 0 && (a.drawTicks > 0 || !a.prevUse)) {
                a.drawTicks++;
            } else {
                if (a.drawTicks > 0 && a.arrows > 0) {
                    fireArrow(s, idx, a.drawTicks);
                }
                a.drawTicks = 0;
            }
            return;
        }

        if (kind == USE_CROSSBOW) {
            int xbSlot = clampSlot(a.heldSlot);
            if (a.slotCrossbowLoaded[xbSlot]) {
                if (a.crossbowHeldUseSlot == xbSlot) {
                    a.drawTicks = 0;
                    return;
                }
                int shots = useAttempts(b, a, in.usePress(), in.use(), USE_CROSSBOW,
                        discreteUseClick(a, in));
                for (int i = 0; i < shots; i++) {
                    if (!takeUseAttempt(b)) {
                        break;
                    }
                    if (!a.slotCrossbowLoaded[xbSlot] || !useReady(a, USE_CROSSBOW)
                            || !Projectiles.roomFor(s, idx, Loadout.multishot(s, a) ? 3 : 1)) {
                        break;
                    }
                    fireCrossbow(s, idx, a.slotCrossbowConsumed[xbSlot], a.slotCrossbowEntry[xbSlot]);
                    a.slotCrossbowLoaded[xbSlot] = false;
                    a.slotCrossbowConsumed[xbSlot] = false;
                    a.slotCrossbowEntry[xbSlot] = ItemDict.NONE;
                    startUseItem(a, USE_CROSSBOW);
                }
                a.drawTicks = 0;
            } else if (in.use() && a.arrows > 0 && (a.drawTicks > 0 || !a.prevUse)) {
                a.drawTicks++;
                int loadTicks = Loadout.crossbowLoadTicks(s, a);
                if (a.drawTicks >= loadTicks) {
                    int loadArrowSlot = Loadout.activeArrowSlot(s, a);
                    if (loadArrowSlot < 0) {
                        return;
                    }
                    int loadArrowEntry = Loadout.entryAt(a, loadArrowSlot);
                    if (!Loadout.consume(a, loadArrowSlot, 1)) {
                        return;
                    }
                    a.slotCrossbowLoaded[xbSlot] = true;
                    a.slotCrossbowConsumed[xbSlot] = true;
                    a.slotCrossbowEntry[xbSlot] = loadArrowEntry;
                    a.crossbowHeldUseSlot = xbSlot;
                    a.drawTicks = 0;
                    a.arrows = Loadout.arrows(s, a);
                    a.arrowsConsumed++;
                }
            } else {
                a.drawTicks = 0;
            }
            return;
        }
        a.drawTicks = 0;

        if (kind == USE_FIREWORK) {
            int launches = useAttempts(b, a, in.usePress(), in.use(), USE_FIREWORK,
                    discreteUseClick(a, in));
            for (int i = 0; i < launches; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, USE_FIREWORK)) {
                    break;
                }
                int slot = clampSlot(a.heldSlot);
                int rocketEntry = Loadout.entryAt(a, slot);
                int flight = Math.max(1, Loadout.fireworkFlight(s, a));
                if (Loadout.consume(a, slot, 1)) {
                    int duration = 10 * (flight + 1);
                    boolean launched = false;
                    if (a.gliding && a.hasElytra && !a.onGround && a.cageFallTicks <= 0) {
                        a.fireworkTicks = duration;
                        launched = true;
                    } else {
                        double eye = eyeHeight(a);
                        Vec3 look = lookVector(a.yaw, a.pitch);
                        double t = blockHit(arena, a.x, a.y + eye, a.z, look, BLOCK_REACH);
                        if (t >= 0.0) {
                            ProjectileState rocket = new ProjectileState();
                            rocket.id = s.nextProjectileId++;
                            rocket.type = ProjectileState.TYPE_FIREWORK;
                            rocket.owner = idx;
                            rocket.x = a.x + look.x() * BLOCK_REACH * t;
                            rocket.y = a.y + eye + look.y() * BLOCK_REACH * t;
                            rocket.z = a.z + look.z() * BLOCK_REACH * t;
                            rocket.vy = 0.5;
                            rocket.life = duration + 10;
                            launched = Projectiles.spawn(s, rocket);
                        }
                    }
                    if (!launched) {
                        Loadout.addItem(s, a, rocketEntry, 1);
                    }
                    startUseItem(a, USE_FIREWORK);
                }
            }
            return;
        }

        if (kind == USE_SNOWBALL || kind == USE_EGG) {
            int uses = useAttempts(b, a, in.usePress(), in.use(), kind, discreteUseClick(a, in));
            for (int i = 0; i < uses; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, kind) || !Projectiles.hasRoom(s, idx)) {
                    break;
                }
                int slot = clampSlot(a.heldSlot);
                if (Loadout.consume(a, slot, 1)) {
                    throwProjectile(s, idx,
                            kind == USE_SNOWBALL ? ProjectileState.TYPE_SNOWBALL : ProjectileState.TYPE_EGG,
                            THROW_SPEED, 0f);
                    startUseItem(a, kind);
                }
            }
            return;
        }

        if (kind == USE_SPLASH_POTION) {
            int uses = useAttempts(b, a, in.usePress(), in.use(), USE_SPLASH_POTION,
                    discreteUseClick(a, in));
            for (int i = 0; i < uses; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, USE_SPLASH_POTION) || !Projectiles.hasRoom(s, idx)) {
                    break;
                }
                int slot = clampSlot(a.heldSlot);
                int potEntry = Loadout.entryAt(a, slot);
                if (Loadout.consume(a, slot, 1)) {
                    Vec3 dir = arcedThrowDir(a.yaw, a.pitch);
                    double eye = eyeHeight(a);
                    ProjectileState pot = new ProjectileState();
                    pot.id = s.nextProjectileId++;
                    pot.type = ProjectileState.TYPE_SPLASH_POTION;
                    pot.owner = idx;
                    pot.x = a.x;
                    pot.y = a.y + eye;
                    pot.z = a.z;
                    pot.vx = dir.x() * POTION_THROW_SPEED + a.vx;
                    pot.vy = dir.y() * POTION_THROW_SPEED + (a.onGround ? 0.0 : a.vy);
                    pot.vz = dir.z() * POTION_THROW_SPEED + a.vz;
                    carryEntryEffects(s, potEntry, pot);
                    Projectiles.spawn(s, pot);
                    a.potionsThrown++;
                    startUseItem(a, USE_SPLASH_POTION);
                }
            }
            return;
        }

        if (kind == USE_XP_BOTTLE) {
            int uses = useAttempts(b, a, in.usePress(), in.use(), USE_XP_BOTTLE,
                    discreteUseClick(a, in));
            for (int i = 0; i < uses; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, USE_XP_BOTTLE) || !Projectiles.hasRoom(s, idx)) {
                    break;
                }
                int slot = clampSlot(a.heldSlot);
                if (Loadout.consume(a, slot, 1)) {
                    Vec3 dir = arcedThrowDir(a.yaw, a.pitch);
                    double eye = eyeHeight(a);
                    ProjectileState bottle = new ProjectileState();
                    bottle.id = s.nextProjectileId++;
                    bottle.type = ProjectileState.TYPE_XP_BOTTLE;
                    bottle.owner = idx;
                    bottle.x = a.x;
                    bottle.y = a.y + eye - THROWN_ITEM_SPAWN_EYE_DROP;
                    bottle.z = a.z;
                    bottle.vx = dir.x() * XP_BOTTLE_THROW_SPEED + a.vx;
                    bottle.vy = dir.y() * XP_BOTTLE_THROW_SPEED + (a.onGround ? 0.0 : a.vy);
                    bottle.vz = dir.z() * XP_BOTTLE_THROW_SPEED + a.vz;
                    Projectiles.spawn(s, bottle);
                    startUseItem(a, USE_XP_BOTTLE);
                }
            }
            return;
        }

        if (kind == USE_PEARL) {
            int uses = useAttempts(b, a, in.usePress(), in.use(), USE_PEARL, discreteUseClick(a, in));
            for (int i = 0; i < uses; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, USE_PEARL) || a.cageFallTicks > 0
                        || !Projectiles.hasRoom(s, idx)) {
                    break;
                }
                int slot = clampSlot(a.heldSlot);
                if (!Loadout.consume(a, slot, 1)) {
                    break;
                }
                throwProjectile(s, idx, ProjectileState.TYPE_PEARL, THROW_SPEED, 0f);
                startUseItem(a, USE_PEARL);
            }
            return;
        }
        if (kind == USE_WIND_CHARGE) {
            int uses = useAttempts(b, a, in.usePress(), in.use(), USE_WIND_CHARGE,
                    discreteUseClick(a, in));
            for (int i = 0; i < uses; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, USE_WIND_CHARGE) || !Projectiles.hasRoom(s, idx)
                        || !Loadout.consume(a, clampSlot(a.heldSlot), 1)) {
                    break;
                }
                throwProjectile(s, idx, ProjectileState.TYPE_WIND_CHARGE, THROW_SPEED, 0f);
                startUseItem(a, USE_WIND_CHARGE);
            }
        }
        if (kind == USE_NONE) {
            handleOffhandUse(s, idx, in, b);
        }

    }

    private static void handleOffhandUse(GameState s, int idx, Input in, ClickBudget b) {
        PlayerState a = s.players[idx];
        if (a.dead) {
            return;
        }
        int entry = Loadout.entryAt(a, ItemDict.OFF_HAND);
        int kind = s.dict.useKind(entry);
        SimProbe.band(SimProbe.USE_OFFHAND_BASE, kind, SimProbe.USE_KINDS);
        if (kind == USE_NONE || Loadout.countAt(a, ItemDict.OFF_HAND) <= 0) {
            return;
        }
        boolean use = in.offhandUse();
        boolean offPress = in.offhandUsePress();
        boolean offhandDiscrete = discreteUseClick(a, in);

        if (kind == USE_FOOD) {
            boolean hungry = a.food < 20.0f || s.dict.alwaysEdible(entry);
            if (a.offhandEatGap > 0) {
                a.offhandEatGap--;
                a.offhandEatTicks = 0;
            } else if (use && hungry) {
                a.offhandEatTicks++;
                int offEatTicks = s.dict.foodEatTicks(entry);
                int eatTime = Math.max(MIN_EAT_TICKS, offEatTicks > 0 ? offEatTicks : EAT_DURATION);
                if (a.offhandEatTicks >= eatTime) {
                    int nutrition = s.dict.foodNutrition(entry);
                    float saturation = s.dict.foodSaturation(entry);
                    if (Loadout.consume(a, ItemDict.OFF_HAND, 1)) {
                        a.food = Math.min(20.0f, a.food + nutrition);
                        a.saturation = Math.min(a.food, a.saturation + saturation);
                        applyEntryEffects(s, a, entry);
                        a.offhandConsumeSeq++;
                    }
                    a.offhandEatTicks = 0;
                    a.offhandEatGap = EAT_BITE_GAP;
                }
            } else {
                a.offhandEatTicks = 0;
            }
            return;
        }

        if (kind == USE_PEARL) {
            int uses = useAttempts(b, a, offPress, use, USE_PEARL, offhandDiscrete);
            for (int i = 0; i < uses; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, USE_PEARL) || a.cageFallTicks > 0
                        || !Projectiles.hasRoom(s, idx)
                        || !Loadout.consume(a, ItemDict.OFF_HAND, 1)) {
                    break;
                }
                throwProjectile(s, idx, ProjectileState.TYPE_PEARL, THROW_SPEED, 0f);
                a.offhandConsumeSeq++;
                startUseItem(a, USE_PEARL);
            }
            return;
        }
        if (kind == USE_SNOWBALL || kind == USE_EGG) {
            int uses = useAttempts(b, a, offPress, use, kind, offhandDiscrete);
            for (int i = 0; i < uses; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, kind) || !Projectiles.hasRoom(s, idx)
                        || !Loadout.consume(a, ItemDict.OFF_HAND, 1)) {
                    break;
                }
                throwProjectile(s, idx,
                        kind == USE_SNOWBALL ? ProjectileState.TYPE_SNOWBALL : ProjectileState.TYPE_EGG,
                        THROW_SPEED, 0f);
                a.offhandConsumeSeq++;
                startUseItem(a, kind);
            }
            return;
        }
        if (kind == USE_WIND_CHARGE) {
            int uses = useAttempts(b, a, offPress, use, USE_WIND_CHARGE, offhandDiscrete);
            for (int i = 0; i < uses; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, USE_WIND_CHARGE) || !Projectiles.hasRoom(s, idx)
                        || !Loadout.consume(a, ItemDict.OFF_HAND, 1)) {
                    break;
                }
                throwProjectile(s, idx, ProjectileState.TYPE_WIND_CHARGE, THROW_SPEED, 0f);
                a.offhandConsumeSeq++;
                startUseItem(a, USE_WIND_CHARGE);
            }
            return;
        }
        if (kind == USE_XP_BOTTLE) {
            int uses = useAttempts(b, a, offPress, use, USE_XP_BOTTLE, offhandDiscrete);
            for (int i = 0; i < uses; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, USE_XP_BOTTLE) || !Projectiles.hasRoom(s, idx)
                        || !Loadout.consume(a, ItemDict.OFF_HAND, 1)) {
                    break;
                }
                Vec3 dir = arcedThrowDir(a.yaw, a.pitch);
                double eye = eyeHeight(a);
                ProjectileState bottle = new ProjectileState();
                bottle.id = s.nextProjectileId++;
                bottle.type = ProjectileState.TYPE_XP_BOTTLE;
                bottle.owner = idx;
                bottle.x = a.x;
                bottle.y = a.y + eye - THROWN_ITEM_SPAWN_EYE_DROP;
                bottle.z = a.z;
                bottle.vx = dir.x() * XP_BOTTLE_THROW_SPEED + a.vx;
                bottle.vy = dir.y() * XP_BOTTLE_THROW_SPEED + (a.onGround ? 0.0 : a.vy);
                bottle.vz = dir.z() * XP_BOTTLE_THROW_SPEED + a.vz;
                Projectiles.spawn(s, bottle);
                a.offhandConsumeSeq++;
                startUseItem(a, USE_XP_BOTTLE);
            }
            return;
        }
        if (kind == USE_SPLASH_POTION) {
            int uses = useAttempts(b, a, offPress, use, USE_SPLASH_POTION, offhandDiscrete);
            for (int i = 0; i < uses; i++) {
                if (!takeUseAttempt(b)) {
                    break;
                }
                if (!useReady(a, USE_SPLASH_POTION) || !Projectiles.hasRoom(s, idx)
                        || !Loadout.consume(a, ItemDict.OFF_HAND, 1)) {
                    break;
                }
                Vec3 dir = arcedThrowDir(a.yaw, a.pitch);
                double eye = eyeHeight(a);
                ProjectileState pot = new ProjectileState();
                pot.id = s.nextProjectileId++;
                pot.type = ProjectileState.TYPE_SPLASH_POTION;
                pot.owner = idx;
                pot.x = a.x;
                pot.y = a.y + eye;
                pot.z = a.z;
                pot.vx = dir.x() * POTION_THROW_SPEED + a.vx;
                pot.vy = dir.y() * POTION_THROW_SPEED + (a.onGround ? 0.0 : a.vy);
                pot.vz = dir.z() * POTION_THROW_SPEED + a.vz;
                carryEntryEffects(s, entry, pot);
                Projectiles.spawn(s, pot);
                a.offhandConsumeSeq++;
                a.potionsThrown++;
                startUseItem(a, USE_SPLASH_POTION);
            }
        }
    }

    private static final double PLAYER_HALF_WIDTH = 0.3;

    private static final double ARROW_HALF_WIDTH = 0.25;
    private static final double ARROW_HEIGHT = 0.5;

    private static void handleBlockAction(GameState s, Arena arena, int idx, Input in) {
        if (in.synthetic()) {
            return;
        }
        PlayerState a = s.players[idx];
        ClickBudget b = budget(s, a, in);
        if (a.dead) {
            return;
        }
        int action = in.blockAction();

        if (in.crystalHit() || action == Input.BLOCK_HIT_CRYSTAL) {
            boolean channel = in.crystalHit();
            int cx = channel ? in.crystalX() : in.targetX();
            int cy = channel ? in.crystalY() : in.targetY();
            int cz = channel ? in.crystalZ() : in.targetZ();
            int reachSlot = clampSlot(in.heldSlot());
            while (true) {
                if (!b.takeAttack()) {
                    break;
                }
                if (!spendLeftClick(a)) {
                    continue;
                }
                hitCrystalOnce(s, arena, idx, reachSlot, cx, cy, cz);
            }
        }

        if (isBlockUse(action)) {
            boolean counted = b.use > 0;
            int uses = counted ? blockUseClicks(action, b.use) : 1;
            for (int i = 0; i < uses; i++) {
                if (!b.takeUse()) {
                    break;
                }
                handleBlockActionOnce(s, arena, idx, in, counted);
            }
            return;
        }
        handleBlockActionOnce(s, arena, idx, in, false);
    }

    private static void hitCrystalOnce(GameState s, Arena arena, int idx, int slot,
                                       int bx, int by, int bz) {
        PlayerState a = s.players[idx];
        a.meleeClaimTick = s.tick;
        int struck = -1;
        for (int i = 0; i < s.crystals.size(); i++) {
            CrystalState c = s.crystals.get(i);
            if (c.bx == bx && c.by == by && c.bz == bz) {
                struck = i;
                break;
            }
        }
        if (struck >= 0 && withinCrystalAttackRange(s, a, slot, bx, by, bz)
                && crystalInSight(s, arena, a, bx, by, bz)) {
            SimProbe.hit(SimProbe.CRYSTAL_DETONATED_BY_HIT);
            s.crystals.remove(struck);
            explode(s, arena, bx + 0.5, by + 1.0, bz + 0.5, CRYSTAL_POWER, idx, false);
        }
        a.miningTarget = Long.MIN_VALUE;
        a.miningProgress = 0f;
    }

    private static void handleBlockActionOnce(GameState s, Arena arena, int idx, Input in,
                                              boolean clickPath) {
        PlayerState a = s.players[idx];
        int action = in.blockAction();
        SimProbe.band(SimProbe.BLOCK_ACTION_BASE, action, SimProbe.BLOCK_ACTIONS);
        if (action == Input.BLOCK_CLOSE_CONTAINER) {
            SimProbe.hit(SimProbe.CONTAINER_CLOSED);
            a.openContainer = -1;
            a.openContainerKey = Long.MIN_VALUE;
            a.miningTarget = Long.MIN_VALUE;
            a.miningProgress = 0f;
            return;
        }
        if (action != Input.BLOCK_NONE && action != Input.BLOCK_HIT_CRYSTAL
                && !withinBlockReach(a, in)) {
            return;
        }
        if (isBlockUse(action)) {
            if (!clickPath && a.useDelay > 0 && !discreteUseClick(a, in)) {
                a.miningTarget = Long.MIN_VALUE;
                a.miningProgress = 0f;
                return;
            }
            startBlockUse(a);
        }

        if (action == Input.BLOCK_OPEN_CONTAINER) {
            int cx = in.targetX();
            int cy = in.targetY();
            int cz = in.targetZ();
            long ck = BlockStore.key(cx, cy, cz);
            Integer cid = s.blockContainers.get(ck);
            if (!cellInSight(s, arena, a, cx, cy, cz)) {
                a.miningTarget = Long.MIN_VALUE;
                a.miningProgress = 0f;
                return;
            }
            if (cid != null && s.containers.containsKey(cid)) {
                SimProbe.hit(SimProbe.CONTAINER_OPENED);
                a.openContainer = cid;
                a.openContainerKey = ck;
            } else if (s.blocks.contains(cx, cy, cz)
                    && s.dict.isEnderChest(s.dict.entryForItemId(s.blocks.idAt(cx, cy, cz)))) {
                SimProbe.hit(SimProbe.CONTAINER_OPENED);
                a.openContainer = enderContainer(s, a);
                a.openContainerKey = ck;
            }
            a.miningTarget = Long.MIN_VALUE;
            a.miningProgress = 0f;
            return;
        }
        if (action == Input.BLOCK_PLACE || action == Input.BLOCK_PLACE_OFFHAND) {
            boolean offhand = action == Input.BLOCK_PLACE_OFFHAND;
            int slot = offhand ? ItemDict.OFF_HAND : clampSlot(in.heldSlot());
            int entry = Loadout.entryAt(a, slot);
            int x = in.targetX();
            int y = in.targetY();
            int z = in.targetZ();
            long pk = BlockStore.key(x, y, z);
            int placedId = s.dict.itemId(entry);
            if (s.dict.isBlock(entry) && placeAllowed(s, placedId) && Loadout.countAt(a, slot) > 0
                    && placementLegal(s, arena, a, x, y, z)) {
                if (s.cobwebItemId != 0 && placedId == s.cobwebItemId) {
                    if (cellFreeForWeb(s, x, y, z) && Loadout.consume(a, slot, 1)) {
                        SimProbe.hit(SimProbe.COBWEB_ADDED);
                        s.cobwebs.put(pk, placedId);
                        if (offhand) {
                            a.offhandConsumeSeq++;
                        }
                        s.events.add(new CombatEvent(CombatEvent.SWING, idx, idx, false, 1));
                            }
                } else if (cellFreeForBlock(s, x, y, z) && Loadout.consume(a, slot, 1)) {
                    s.blocks.place(x, y, z, placedId);
                    if (arena.isDecorVoxel(x, y, z) && !s.brokenArena.contains(pk)) {
                        SimProbe.hit(SimProbe.ARENA_VOXEL_BROKEN_BY_DECOR_PLACE);
                        s.brokenArena.add(pk);
                    }
                    s.fires.remove(pk);
                    s.blockResistance.put(pk, s.blockProps.blastResistance(placedId));
                    bindPlacedContainer(s, pk, entry);
                    if (offhand) {
                        a.offhandConsumeSeq++;
                    }
                    s.events.add(new CombatEvent(CombatEvent.SWING, idx, idx, false, 1));
                    }
            }
            a.miningTarget = Long.MIN_VALUE;
            a.miningProgress = 0f;
            return;
        }
        if (action == Input.BLOCK_PLACE_WATER || action == Input.BLOCK_PLACE_LAVA) {
            boolean lava = action == Input.BLOCK_PLACE_LAVA;
            int type = lava ? Fluids.LAVA : Fluids.WATER;
            int slot = clampSlot(in.heldSlot());
            int entry = Loadout.entryAt(a, slot);
            boolean holds = lava ? s.dict.isBucketLava(entry) : s.dict.isBucketWater(entry);
            int px = in.targetX();
            int py = in.targetY();
            int pz = in.targetZ();
            if (holds && cellInSight(s, arena, a, px, py, pz)
                    && Fluids.place(s, arena, idx, type, px, py, pz)) {
                int emptied = bucketEntry(s, ItemDict.FLAG_BUCKET_EMPTY);
                dropOverflow(s, a, idx, emptied, Loadout.retype(s, a, slot, emptied));
                s.events.add(new CombatEvent(CombatEvent.SWING, idx, idx, false, 1));
                s.events.add(new CombatEvent(CombatEvent.BUCKET_EMPTY, px, py, lava, pz));
            }
            a.miningTarget = Long.MIN_VALUE;
            a.miningProgress = 0f;
            return;
        }
        if (action == Input.BLOCK_PICKUP_FLUID) {
            int slot = clampSlot(in.heldSlot());
            int entry = Loadout.entryAt(a, slot);
            int px = in.targetX();
            int py = in.targetY();
            int pz = in.targetZ();
            if (s.dict.isBucketEmpty(entry) && cellInSight(s, arena, a, px, py, pz)) {
                int got = Fluids.pickup(s, px, py, pz);
                if (got >= 0) {
                    int filled = bucketEntry(s, got == Fluids.LAVA
                            ? ItemDict.FLAG_BUCKET_LAVA : ItemDict.FLAG_BUCKET_WATER);
                    dropOverflow(s, a, idx, filled, Loadout.retype(s, a, slot, filled));
                    s.events.add(new CombatEvent(CombatEvent.SWING, idx, idx, false, 1));
                    s.events.add(new CombatEvent(CombatEvent.BUCKET_FILL, px, py, got == Fluids.LAVA, pz));
                    }
            }
            a.miningTarget = Long.MIN_VALUE;
            a.miningProgress = 0f;
            return;
        }
        if (action == Input.BLOCK_BREAK) {
            if (a.eating) {
                a.miningTarget = Long.MIN_VALUE;
                a.miningProgress = 0f;
                return;
            }
            int x = in.targetX();
            int y = in.targetY();
            int z = in.targetZ();
            long t = BlockStore.key(x, y, z);
            boolean freshDestroyPress = budget(s, a, in).takeAttack();
            if (missPenaltyActive(a)) {
                SimProbe.hit(SimProbe.MINING_REFUSED_BY_MISS_PENALTY);
                return;
            }
            if (freshDestroyPress) {
                s.events.add(new CombatEvent(CombatEvent.SWING, idx, idx, false, 1));
            }
            if (a.destroyDelay > 0) {
                a.destroyDelay--;
                if (!freshDestroyPress) {
                    return;
                }
            }
            if (s.fires.containsKey(t)) {
                s.fires.remove(t);
                s.events.add(new CombatEvent(
                        CombatEvent.FIRE_EXTINGUISH, x, y, false, z));
                a.miningTarget = Long.MIN_VALUE;
                a.miningProgress = 0f;
                a.destroyDelay = DESTROY_DELAY;
                return;
            }
            int mineSlot = clampSlot(in.heldSlot());
            if (s.cobwebs.containsKey(t)) {
                if (a.miningTarget != t) {
                    a.miningTarget = t;
                    a.miningProgress = 0f;
                }
                a.miningProgress += Loadout.minePerTick(s, a, mineSlot, s.cobwebItemId);
                if (a.miningProgress >= 1.0f) {
                    SimProbe.hit(SimProbe.COBWEB_REMOVED);
                    s.cobwebs.remove(t);
                    int webTool = Loadout.entryAt(a, mineSlot);
                    int dropId = s.dict.isShears(webTool) || s.dict.isSword(webTool)
                            ? s.cobwebItemId : s.stringItemId;
                    if (dropId != 0) {
                        ItemEntities.spawn(s, idx, x + 0.5, y + 0.25, z + 0.5, 0.0, 0.15, 0.0, dropId, 1, 10);
                    }
                    a.toolDamageSeq++;
                    Loadout.damageSlot(s, a, mineSlot, 1);
                    a.miningTarget = Long.MIN_VALUE;
                    a.miningProgress = 0f;
                    a.destroyDelay = DESTROY_DELAY;
                }
                return;
            }
            if (!cellInSight(s, arena, a, x, y, z)) {
                a.miningTarget = Long.MIN_VALUE;
                a.miningProgress = 0f;
                return;
            }
            boolean placed = s.blocks.contains(x, y, z);

            boolean arenaTerrain = !placed && arenaTerrainMineable(s, arena, x, y, z);
            int targetBlockId = placed ? s.blocks.idAt(x, y, z) : arena.voxelBlockItem(x, y, z);
            if ((placed || arenaTerrain) && !breakAllowed(s, targetBlockId)) {
                a.miningTarget = Long.MIN_VALUE;
                a.miningProgress = 0f;
                return;
            }
            if (placed || arenaTerrain) {
                if (a.miningTarget != t) {
                    a.miningTarget = t;
                    a.miningProgress = 0f;
                }
                int mineBlockId = placed ? s.blocks.idAt(x, y, z) : arena.voxelDropItem(x, y, z);
                a.miningProgress += Loadout.minePerTick(s, a, mineSlot, mineBlockId);
                if (a.miningProgress >= 1.0f) {
                    if (placed) {
                        int placedId = s.blocks.idAt(x, y, z);
                        int containerEntry = unbindPlacedContainer(s, t);
                        s.blocks.removeAt(x, y, z);
                        s.blockResistance.remove(t);
                        s.anchors.remove(t);
                        boolean drops = Loadout.dropsBlock(s, a, mineSlot, placedId);
                        int dropId = drops ? blastDrop(s, s.blockProps.dropItemId(placedId)) : 0;
                        if (containerEntry != ItemDict.NONE) {
                            ItemEntities.spawn(s, idx, x + 0.5, y + 0.25, z + 0.5, 0.0, 0.15, 0.0,
                                    containerEntry, 0, s.dict.itemId(containerEntry), 1, 10);
                        } else if (dropId != 0) {
                            ItemEntities.spawn(s, idx, x + 0.5, y + 0.25, z + 0.5, 0.0, 0.15, 0.0, dropId, 1, 10);
                        }
                    } else {
                        SimProbe.hit(SimProbe.ARENA_VOXEL_BROKEN_BY_MINING);
                        s.brokenArena.add(t);
                        int voxelId = arena.voxelDropItem(x, y, z);
                        int lootId = Loadout.dropsBlock(s, a, mineSlot, voxelId)
                                ? s.blockProps.dropItemId(voxelId) : 0;
                        if (lootId != 0) {
                            ItemEntities.spawn(s, idx, x + 0.5, y + 0.25, z + 0.5, 0.0, 0.15, 0.0, lootId, 1, 10);
                        }
                    }
                    a.toolDamageSeq++;
                    Loadout.damageSlot(s, a, mineSlot, 1);
                    a.miningTarget = Long.MIN_VALUE;
                    a.miningProgress = 0f;
                    a.destroyDelay = DESTROY_DELAY;
                }
                return;
            }
            a.miningTarget = Long.MIN_VALUE;
            a.miningProgress = 0f;
            return;
        }
        if (action == Input.BLOCK_PLACE_CRYSTAL) {
            int bx = in.targetX();
            int by = in.targetY();
            int bz = in.targetZ();
            int slot = clampSlot(in.heldSlot());

            if (crystalRoomFor(s, idx)
                    && s.dict.isEndCrystal(Loadout.entryAt(a, slot))
                    && crystalPlacementOpen(s, arena, a, bx, by, bz)
                    && Loadout.consume(a, slot, 1)) {
                CrystalState c = new CrystalState();
                c.id = s.nextCrystalId++;
                c.owner = idx;
                c.bx = bx;
                c.by = by;
                c.bz = bz;
                SimProbe.hit(SimProbe.CRYSTAL_PLACED);
                s.crystals.add(c);
                s.events.add(new CombatEvent(CombatEvent.SWING, idx, idx, false, 1));
            }
            a.miningTarget = Long.MIN_VALUE;
            a.miningProgress = 0f;
            return;
        }
        if (action == Input.BLOCK_PLACE_ANCHOR) {
            int x = in.targetX();
            int y = in.targetY();
            int z = in.targetZ();
            int slot = clampSlot(in.heldSlot());
            int anchorEntry = Loadout.entryAt(a, slot);
            long ak = BlockStore.key(x, y, z);
            if (s.dict.isRespawnAnchor(anchorEntry)
                    && placementOpen(s, arena, a, x, y, z)
                    && Loadout.consume(a, slot, 1)) {
                int anchorId = s.dict.itemId(anchorEntry);
                s.blocks.place(x, y, z, anchorId);
                s.blockResistance.put(ak, s.blockProps.blastResistance(anchorId));
                SimProbe.hit(SimProbe.ANCHOR_PLACED);
                s.anchors.put(ak, 0);
                s.events.add(new CombatEvent(CombatEvent.SWING, idx, idx, false, 1));
            }
            a.miningTarget = Long.MIN_VALUE;
            a.miningProgress = 0f;
            return;
        }
        if (action == Input.BLOCK_CHARGE_ANCHOR) {
            int x = in.targetX();
            int y = in.targetY();
            int z = in.targetZ();
            long key = BlockStore.key(x, y, z);
            Integer charge = anchorChargeAt(s, key);
            if (charge != null && charge < ANCHOR_MAX_CHARGE) {
                int slot = clampSlot(in.heldSlot());
                if (s.dict.isGlowstone(Loadout.entryAt(a, slot))
                        && cellInSight(s, arena, a, x, y, z)
                        && Loadout.consume(a, slot, 1)) {
                    SimProbe.hit(SimProbe.ANCHOR_CHARGED);
                    s.anchors.put(key, charge + 1);
                    s.events.add(new CombatEvent(CombatEvent.SWING, idx, idx, false, 1));
                }
            }
            a.miningTarget = Long.MIN_VALUE;
            a.miningProgress = 0f;
            return;
        }
        if (action == Input.BLOCK_DETONATE_ANCHOR) {
            int x = in.targetX();
            int y = in.targetY();
            int z = in.targetZ();
            long key = BlockStore.key(x, y, z);
            Integer charge = anchorChargeAt(s, key);
            if (charge != null && charge >= 1 && cellInSight(s, arena, a, x, y, z)) {
                detonateAnchor(s, arena, idx, x, y, z, key);
            }
            a.miningTarget = Long.MIN_VALUE;
            a.miningProgress = 0f;
            return;
        }

        a.miningTarget = Long.MIN_VALUE;
        a.miningProgress = 0f;
    }

    static Integer anchorChargeAt(GameState s, long key) {
        Integer charge = s.anchors.get(key);
        if (charge == null) {
            return null;
        }
        int x = BlockStore.unpackX(key);
        int y = BlockStore.unpackY(key);
        int z = BlockStore.unpackZ(key);
        if (!s.blocks.contains(x, y, z)) {
            s.anchors.remove(key);
            return null;
        }
        return charge;
    }

    private static void detonateAnchor(GameState s, Arena arena, int idx, int x, int y, int z,
                                       long key) {
        SimProbe.hit(SimProbe.ANCHOR_DETONATED);
        s.anchors.remove(key);
        s.blocks.removeAt(x, y, z);
        s.blockResistance.remove(key);
        explode(s, arena, x + 0.5, y + 0.5, z + 0.5, ANCHOR_POWER, idx, true);
    }

    private static void revalidateOpenContainer(GameState s, int idx) {
        PlayerState p = s.players[idx];
        if (p.openContainer < 0) {
            p.openContainerKey = Long.MIN_VALUE;
            return;
        }
        if (openContainerStillValid(s, p)) {
            return;
        }
        p.openContainer = -1;
        p.openContainerKey = Long.MIN_VALUE;
    }

    private static boolean openContainerStillValid(GameState s, PlayerState p) {
        long key = p.openContainerKey;
        if (key == Long.MIN_VALUE || !s.containers.containsKey(p.openContainer)) {
            return false;
        }
        int x = BlockStore.unpackX(key);
        int y = BlockStore.unpackY(key);
        int z = BlockStore.unpackZ(key);
        Integer bound = s.blockContainers.get(key);
        boolean present;
        if (bound != null) {
            present = bound == p.openContainer;
        } else {
            present = p.openContainer == p.enderContainer
                    && s.blocks.contains(x, y, z)
                    && s.dict.isEnderChest(s.dict.entryForItemId(s.blocks.idAt(x, y, z)));
        }
        return present && withinCellReach(p, x, y, z, containerReachLimit());
    }

    private static boolean invClickClaimed(Input in) {
        return in.swapHands() || invOpAction(in.invAction());
    }

    private static boolean invOpAction(int action) {
        return action == Input.INV_MOVE
                || action == Input.INV_CONTAINER_TAKE
                || action == Input.INV_CONTAINER_PUT
                || action == Input.INV_PICKUP
                || action == Input.INV_PICKUP_HALF
                || action == Input.INV_SWAP_SLOT
                || action == Input.INV_PICKUP_ALL
                || action == Input.INV_QUICK_MOVE;
    }

    private static boolean guiOnlyInvAction(int action) {
        return action != Input.INV_NONE
                && action != Input.INV_DROP_ONE
                && action != Input.INV_DROP_STACK;
    }

    private static void handleInventory(GameState s, int idx, Input in) {
        PlayerState p = s.players[idx];
        boolean claim = !in.synthetic() && invClickClaimed(in);
        boolean fresh = claim && !p.prevInvClick;
        if (!in.synthetic()) {
            p.prevInvClick = claim;
        }
        if (p.dead) {
            resolveCursor(s, idx);
            return;
        }
        if (!in.synthetic() && in.invAction() == Input.INV_CURSOR_RESOLVE) {
            resolveCursor(s, idx);
        }
        if (roundStartLocked(s)) {
            return;
        }
        ClickBudget b = budget(s, p, in);
        if (in.swapHands()) {
            boolean counted = b.swap > 0;
            int swaps = counted ? b.swap : (fresh ? 1 : 0);
            if (!counted && swaps > 0) {
                fresh = false;
            }
            for (int i = 0; i < swaps; i++) {
                if (counted && !b.takeSwap()) {
                    break;
                }
                Loadout.swapHands(s, p);
                Loadout.recomputeDerived(s, p);
            }
        }
        if (invOpAction(in.invAction())) {
            if (guiOnlyInvAction(in.invAction())
                    && !HostFrameContract.screenQuietFrame(in)) {
                SimProbe.hit(SimProbe.INV_OP_ON_A_MOVING_FRAME);
            }
            boolean counted = b.inv > 0;
            int ops = counted ? b.inv : (fresh ? 1 : 0);
            for (int i = 0; i < ops; i++) {
                if (counted && !b.takeInv()) {
                    break;
                }
                if (!invOpOnce(s, p, in)) {
                    break;
                }
            }
        }
    }

    private static boolean invOpOnce(GameState s, PlayerState p, Input in) {
        int action = in.invAction();
        SimProbe.band(SimProbe.INV_ACTION_BASE, action, SimProbe.INV_ACTIONS);
        if (action == Input.INV_MOVE) {
            if (Loadout.moveStack(s, p, in.invSrc(), in.invDst())) {
                Loadout.recomputeDerived(s, p);
            }
            return true;
        }
        if (action == Input.INV_QUICK_MOVE) {
            int addr = in.invSrc();
            Container box = s.containers.get(p.openContainer);
            if (Input.addrIsCell(addr) && box == null) {
                return false;
            }
            if (Loadout.quickMove(s, p, box, addr)) {
                Loadout.recomputeDerived(s, p);
            }
            return true;
        }
        if (action == Input.INV_CONTAINER_TAKE || action == Input.INV_CONTAINER_PUT) {
            Container c = s.containers.get(p.openContainer);
            if (c == null) {
                return false;
            }
            boolean take = action == Input.INV_CONTAINER_TAKE;
            int cell = take ? in.invSrc() : in.invDst();
            int slot = take ? in.invDst() : in.invSrc();
            if (Loadout.moveContainer(s, p, c, cell, slot, take)) {
                Loadout.recomputeDerived(s, p);
            }
            return true;
        }
        return cursorClickOnce(s, p, in, action);
    }

    private static boolean cursorClickOnce(GameState s, PlayerState p, Input in, int action) {
        int addr = in.invSrc();
        boolean cell = Input.addrIsCell(addr);
        Container c = cell ? s.containers.get(p.openContainer) : null;
        if (cell && c == null) {
            return false;
        }
        int index = Input.addrIndex(addr);
        boolean changed;
        if (action == Input.INV_PICKUP || action == Input.INV_PICKUP_HALF) {
            boolean primary = action == Input.INV_PICKUP;
            changed = cell ? Loadout.clickCell(s, p, c, index, primary)
                    : Loadout.clickSlot(s, p, index, primary);
        } else if (action == Input.INV_SWAP_SLOT) {
            changed = cell ? Loadout.swapCellWithHotbar(s, p, c, index, in.invDst())
                    : Loadout.swapWithHotbar(s, p, index, in.invDst());
        } else if (action == Input.INV_PICKUP_ALL) {
            changed = Loadout.pickupAllToCursor(s, p, s.containers.get(p.openContainer));
        } else {
            return false;
        }
        if (changed) {
            Loadout.recomputeDerived(s, p);
        }
        return true;
    }

    private static void resolveCursor(GameState s, int idx) {
        PlayerState p = s.players[idx];
        if (Loadout.cursorEntry(p) == ItemDict.NONE) {
            return;
        }
        int left = Loadout.placeCursorBack(s, p);
        Loadout.recomputeDerived(s, p);
        if (left <= 0) {
            return;
        }
        dropCursorOnce(s, p, idx, true);
    }

    private static void handleDrop(GameState s, int idx, Input in) {
        PlayerState a = s.players[idx];
        int action = in.invAction();
        boolean invToss = action == Input.INV_DROP_ONE || action == Input.INV_DROP_STACK;
        boolean cursorToss = action == Input.INV_DROP_CURSOR_ONE
                || action == Input.INV_DROP_CURSOR_ALL;
        boolean claim = !in.synthetic() && (in.dropItem() || invToss || cursorToss);
        boolean dropPress = claim && !a.prevDrop;
        if (!in.synthetic()) {
            a.prevDrop = claim;
        }
        if (a.dead || roundStartLocked(s)) {
            return;
        }
        ClickBudget b = budget(s, a, in);
        int spent = 0;
        if (in.dropItem()) {
            boolean counted = b.drop > 0;
            int keyDrops = counted ? b.drop : (dropPress ? 1 : 0);
            for (int i = 0; i < keyDrops && spent < Clicks.MAX; i++) {
                if (counted && !b.takeDrop()) {
                    break;
                }
                if (!dropOnce(s, a, idx, clampSlot(in.heldSlot()), in.dropStack())) {
                    break;
                }
                spent++;
            }
        }
        if (invToss || cursorToss) {
            boolean counted = b.inv > 0;
            int tossDrops = counted ? b.inv : (dropPress && !in.dropItem() ? 1 : 0);
            for (int i = 0; i < tossDrops && spent < Clicks.MAX; i++) {
                if (counted && !b.takeInv()) {
                    break;
                }
                boolean tossed = cursorToss
                        ? dropCursorOnce(s, a, idx, action == Input.INV_DROP_CURSOR_ALL)
                        : dropAddrOnce(s, a, idx, in.invSrc(), action == Input.INV_DROP_STACK);
                if (!tossed) {
                    break;
                }
                spent++;
            }
        }
    }

    private static boolean dropCursorOnce(GameState s, PlayerState a, int idx, boolean whole) {
        int entry = Loadout.cursorEntry(a);
        int itemId = s.dict.itemId(entry);
        if (itemId == 0) {
            return false;
        }
        if (!ItemEntities.hasRoom(s, idx)) {
            return false;
        }
        int damage = Loadout.cursorDamage(a);
        int count = whole ? Math.max(1, Loadout.cursorCount(a)) : 1;
        if (!Loadout.consumeCursor(a, count)) {
            return false;
        }
        Loadout.recomputeDerived(s, a);
        double eye = eyeHeight(a);
        Vec3 look = lookVector(a.yaw, a.pitch);
        double speed = 0.3;
        ItemEntityState dropped = ItemEntities.spawn(s, idx, a.x, a.y + eye - 0.3, a.z,
                look.x() * speed, look.y() * speed + 0.1, look.z() * speed,
                entry, damage, itemId, count, ItemEntities.DEFAULT_PICKUP_DELAY);
        a.dropSeq++;
        a.lastDropSlot = CURSOR_DROP_SLOT;
        a.lastDropItemId = itemId;
        a.lastDropCount = count;
        if (dropped != null) {
            dropped.dropUid = dropUid(idx, a.dropSeq);
        }
        return true;
    }

    public static final int CURSOR_DROP_SLOT = ItemDict.SLOTS;

    private static boolean dropAddrOnce(GameState s, PlayerState a, int idx, int addr,
                                        boolean whole) {
        if (!Input.addrIsCell(addr)) {
            return dropOnce(s, a, idx, addr, whole);
        }
        return dropCellOnce(s, a, idx, Input.addrIndex(addr), whole);
    }

    private static boolean dropCellOnce(GameState s, PlayerState a, int idx, int cell,
                                        boolean whole) {
        Container c = s.containers.get(a.openContainer);
        if (c == null || cell < 0 || cell >= Container.CELLS) {
            return false;
        }
        int entry = c.entry[cell];
        int itemId = s.dict.itemId(entry);
        if (itemId == 0 || c.count[cell] <= 0) {
            return false;
        }
        if (!ItemEntities.hasRoom(s, idx)) {
            return false;
        }
        int damage = c.damage[cell];
        int count = whole ? Math.max(1, c.count[cell]) : 1;
        if (!Loadout.consumeCell(c, cell, count)) {
            return false;
        }
        a.invActionSeq++;
        double eye = eyeHeight(a);
        Vec3 look = lookVector(a.yaw, a.pitch);
        double speed = 0.3;
        ItemEntityState dropped = ItemEntities.spawn(s, idx, a.x, a.y + eye - 0.3, a.z,
                look.x() * speed, look.y() * speed + 0.1, look.z() * speed,
                entry, damage, itemId, count, ItemEntities.DEFAULT_PICKUP_DELAY);
        a.dropSeq++;
        a.lastDropSlot = Input.cellAddr(cell);
        a.lastDropItemId = itemId;
        a.lastDropCount = count;
        if (dropped != null) {
            dropped.dropUid = dropUid(idx, a.dropSeq);
        }
        return true;
    }

    private static boolean dropOnce(GameState s, PlayerState a, int idx, int slot, boolean whole) {
        if (!Loadout.legalSlot(slot)) {
            return false;
        }
        int entry = Loadout.entryAt(a, slot);
        int itemId = s.dict.itemId(entry);
        if (itemId == 0) {
            return false;
        }

        if (!ItemEntities.hasRoom(s, idx)) {
            return false;
        }
        int damage = a.slotDamage[slot];
        int count = whole ? Math.max(1, Loadout.countAt(a, slot)) : 1;
        if (!Loadout.consume(a, slot, count)) {
            return false;
        }
        Loadout.recomputeDerived(s, a);
        double eye = eyeHeight(a);
        Vec3 look = lookVector(a.yaw, a.pitch);
        double speed = 0.3;
        ItemEntityState dropped = ItemEntities.spawn(s, idx, a.x, a.y + eye - 0.3, a.z,
                look.x() * speed, look.y() * speed + 0.1, look.z() * speed,
                entry, damage, itemId, count, ItemEntities.DEFAULT_PICKUP_DELAY);
        a.dropSeq++;
        a.lastDropSlot = slot;
        a.lastDropItemId = itemId;
        a.lastDropCount = count;
        if (dropped != null) {
            dropped.dropUid = dropUid(idx, a.dropSeq);
        }
        return true;
    }

    private static void bindPlacedContainer(GameState s, long key, int entry) {
        if (!s.dict.isShulker(entry)) {
            return;
        }
        int cid = s.dict.containerSeed(entry);
        if (cid < 0) {
            return;
        }
        if (!s.containers.containsKey(cid)) {
            s.containers.put(cid, new Container());
        }
        s.blockContainers.put(key, cid);
    }

    private static int unbindPlacedContainer(GameState s, long key) {
        Integer cid = s.blockContainers.remove(key);
        if (cid == null) {
            return ItemDict.NONE;
        }
        for (PlayerState p : s.players) {
            if (p.openContainer == cid) {
                p.openContainer = -1;
                p.openContainerKey = Long.MIN_VALUE;
            }
        }
        return s.dict.entryForContainer(cid);
    }

    private static int enderContainer(GameState s, PlayerState a) {
        if (a.enderContainer >= 0 && s.containers.containsKey(a.enderContainer)) {
            return a.enderContainer;
        }
        int cid = s.nextContainerId++;
        s.containers.put(cid, new Container());
        a.enderContainer = cid;
        return cid;
    }

    private static void dropOverflow(GameState s, PlayerState a, int idx, int entry, int count) {
        if (count <= 0 || !s.dict.valid(entry)) {
            return;
        }
        ItemEntities.spawn(s, idx, a.x, a.y + 0.5, a.z, 0.0, 0.1, 0.0,
                entry, 0, s.dict.itemId(entry), count, ItemEntities.DEFAULT_PICKUP_DELAY);
    }

    private static int plainArrowItemId(GameState s) {
        ItemDict d = s.dict;
        for (int e = 1; e <= d.size(); e++) {
            if (d.isArrowPlain(e)) {
                return d.itemId(e);
            }
        }
        return 0;
    }

    private static int bucketEntry(GameState s, int flagBit) {
        ItemDict d = s.dict;
        for (int e = 1; e <= d.size(); e++) {
            if (d.flag(e, flagBit)) {
                return e;
            }
        }
        return ItemDict.NONE;
    }

    static void damageArmor(GameState s, PlayerState v, double rawDmg) {
        int wear = Math.max(1, (int) (rawDmg / 4.0));
        v.armorDamageSeq++;
        v.armorDamageAmount = wear;
        ItemDict d = Loadout.dict(s);
        for (int slot = ItemDict.ARMOR_FEET; slot <= ItemDict.ARMOR_HEAD; slot++) {
            if (d.isElytra(Loadout.entryAt(v, slot))) {
                continue;
            }
            Loadout.damageSlot(s, v, slot, wear);
        }
        Loadout.recomputeDerived(s, v);
    }

    private static boolean hasCrystalAt(GameState s, int bx, int by, int bz) {
        for (CrystalState c : s.crystals) {
            if (c.bx == bx && c.by == by && c.bz == bz) {
                return true;
            }
        }
        return false;
    }

    public static final float CRYSTAL_POWER = 6.0f;

    public static final int MAX_CRYSTALS = 128;

    public static final int MAX_CRYSTALS_PER_OWNER = MAX_CRYSTALS / 2;

    public static int crystalsOwnedBy(GameState s, int owner) {
        int n = 0;
        for (CrystalState c : s.crystals) {
            if (c.owner == owner) {
                n++;
            }
        }
        return n;
    }

    public static boolean crystalRoomFor(GameState s, int owner) {
        if (s.crystals.size() >= MAX_CRYSTALS) {
            return false;
        }
        return crystalsOwnedBy(s, owner) < MAX_CRYSTALS_PER_OWNER;
    }

    public static final float ANCHOR_POWER = 5.0f;
    public static final int ANCHOR_MAX_CHARGE = 4;

    public static void explode(GameState s, Arena arena, double cx, double cy, double cz, float power, int owner,
                               boolean createFire) {
        SimProbe.hit(SimProbe.EXPLODE);
        s.events.add(new CombatEvent(CombatEvent.EXPLOSION,
                (int) Math.floor(cx), (int) Math.floor(cy), false, (int) Math.floor(cz)));
        blastPlayers(s, arena, cx, cy, cz, power, owner);
        blastItems(s, arena, cx, cy, cz, power);
        blastCrystals(s, cx, cy, cz, power);
        blastBlocks(s, arena, cx, cy, cz, power, owner, createFire);
    }

    private static void blastCrystals(GameState s, double cx, double cy, double cz, float power) {
        double q = power * 2.0;
        for (int i = s.crystals.size() - 1; i >= 0; i--) {
            CrystalState c = s.crystals.get(i);
            double dx = c.bx + 0.5 - cx;
            double dy = c.by + 1.0 - cy;
            double dz = c.bz + 0.5 - cz;
            if (dx * dx + dy * dy + dz * dz <= q * q) {
                SimProbe.hit(SimProbe.CRYSTAL_DESTROYED_BY_BLAST);
                s.crystals.remove(i);
            }
        }
    }

    static long blastSeed(int icx, int icy, int icz, int tick, float power, int seq) {
        long h = ((long) icx * 73856093L) ^ ((long) icy * 19349663L) ^ ((long) icz * 83492791L)
                ^ ((long) tick * 2654435761L) ^ (long) (power * 1000.0f)
                ^ ((long) seq * -7046029254386353131L);
        h ^= h >>> 30;
        h *= -4658895280553007687L;
        h ^= h >>> 27;
        h *= -7723592293110705685L;
        h ^= h >>> 31;
        return h;
    }

    static final float BLAST_RAY_STEP_ENERGY = 0.22500001f;

    static final double BLAST_RAY_STEP_LENGTH = 0.30000001192092896;

    static int blastRaySteps(float energy) {
        if (!(energy > 0.0f)) {
            return 0;
        }
        if (!Float.isFinite(energy)) {
            throw new IllegalArgumentException("blast ray energy must be finite: " + energy);
        }
        double exact = (double) energy / (double) BLAST_RAY_STEP_ENERGY;
        long steps = (long) Math.ceil(exact);
        if (steps > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("blast ray energy is too large to march: " + energy);
        }
        return (int) steps;
    }

    private static void blastBlocks(GameState s, Arena arena, double cx, double cy, double cz, float power,
                                    int owner, boolean createFire) {
        if (s.blastCellBudget <= 0 || s.blastMarchBudget <= 0) {
            return;
        }
        int icx = (int) Math.floor(cx);
        int icy = (int) Math.floor(cy);
        int icz = (int) Math.floor(cz);
        int seq = s.blastSeq++;
        long seed = blastSeed(icx, icy, icz, s.tick, power, seq);
        java.util.Random rng = new java.util.Random(seed);
        java.util.Random dropRng = new java.util.Random(seed ^ BLAST_DROP_SEED_SALT);
        java.util.HashSet<Long> toBreak = new java.util.HashSet<>();
        java.util.HashSet<Long> firesToClear = s.fires.isEmpty() ? null : new java.util.HashSet<>();
        for (int j = 0; j < 16 && s.blastMarchBudget > 0; j++) {
            for (int k = 0; k < 16 && s.blastMarchBudget > 0; k++) {
                for (int l = 0; l < 16 && s.blastMarchBudget > 0; l++) {
                    if (j != 0 && j != 15 && k != 0 && k != 15 && l != 0 && l != 15) {
                        continue;
                    }
                    double d0 = j / 15.0 * 2.0 - 1.0;
                    double d1 = k / 15.0 * 2.0 - 1.0;
                    double d2 = l / 15.0 * 2.0 - 1.0;
                    double d3 = Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
                    d0 /= d3;
                    d1 /= d3;
                    d2 /= d3;
                    float energy = power * (0.7f + rng.nextFloat() * 0.6f);
                    int steps = blastRaySteps(energy);
                    if (steps > s.blastMarchBudget) {
                        steps = s.blastMarchBudget;
                    }
                    double sx = d0 * BLAST_RAY_STEP_LENGTH;
                    double sy = d1 * BLAST_RAY_STEP_LENGTH;
                    double sz = d2 * BLAST_RAY_STEP_LENGTH;
                    float drain = 0.0f;
                    int marched = 0;
                    for (int i = 0; i < steps; i++) {
                        float f = energy - i * BLAST_RAY_STEP_ENERGY - drain;
                        if (f <= 0.0f) {
                            break;
                        }
                        marched++;
                        int bx = (int) Math.floor(cx + sx * i);
                        int by = (int) Math.floor(cy + sy * i);
                        int bz = (int) Math.floor(cz + sz * i);
                        Float res = blastResistanceAt(s, arena, bx, by, bz);
                        if (res != null) {
                            float bite = (res + 0.3f) * 0.3f;
                            drain += bite;
                            f -= bite;
                        }
                        if (f > 0.0f && res != null) {
                            toBreak.add(BlockStore.key(bx, by, bz));
                        }
                        if (firesToClear != null && f > 0.0f) {
                            long fck = BlockStore.key(bx, by, bz);
                            if (s.fires.containsKey(fck)) {
                                firesToClear.add(fck);
                            }
                        }
                    }
                    s.blastMarchBudget -= marched;
                }
            }
        }

        long[] breakKeys = new long[toBreak.size()];
        int breakKeyCount = 0;
        for (long bk : toBreak) {
            breakKeys[breakKeyCount++] = bk;
        }
        java.util.Arrays.sort(breakKeys);
        int spend = Math.min(breakKeyCount, s.blastCellBudget);
        SimProbe.add(SimProbe.BLAST_CELL_REMOVED, spend);
        if (spend < breakKeyCount) {
            SimProbe.hit(SimProbe.BLAST_CELL_BUDGET_EXHAUSTED);
        }
        s.blastCellBudget -= spend;
        for (int ki = 0; ki < spend; ki++) {
            long k = breakKeys[ki];
            if (s.anchors.containsKey(k)) {
                continue;
            }
            int bx = BlockStore.unpackX(k);
            int by = BlockStore.unpackY(k);
            int bz = BlockStore.unpackZ(k);

            double dropVx = 0.0;
            double dropVz = 0.0;
            if (s.blocks.contains(bx, by, bz)) {
                int dropId = blastDrop(s, s.blocks.idAtKey(k));
                int containerEntry = unbindPlacedContainer(s, k);
                s.blocks.removeAt(bx, by, bz);
                s.blockResistance.remove(k);
                if (containerEntry != ItemDict.NONE) {
                    ItemEntities.spawn(s, owner, bx + 0.5, by + 0.25, bz + 0.5, dropVx, 0.15, dropVz,
                            containerEntry, 0, s.dict.itemId(containerEntry), 1, 10);
                } else if (dropId != 0 && blastDropSurvives(dropRng, power)) {
                    ItemEntities.spawn(s, owner, bx + 0.5, by + 0.25, bz + 0.5, dropVx, 0.15, dropVz, dropId, 1, 10);
                }
            } else if (s.vanillaBuild && arena.isSolidVoxel(bx, by, bz) && !s.brokenArena.contains(k)) {
                SimProbe.hit(SimProbe.ARENA_VOXEL_BROKEN_BY_BLAST);
                s.brokenArena.add(k);
                int dropId = blastDrop(s, arena.voxelDropItem(bx, by, bz));
                if (dropId != 0 && blastDropSurvives(dropRng, power)) {
                    ItemEntities.spawn(s, owner, bx + 0.5, by + 0.25, bz + 0.5, dropVx, 0.15, dropVz, dropId, 1, 10);
                }
            } else if (s.vanillaBuild && arena.isDecorVoxel(bx, by, bz) && !s.brokenArena.contains(k)) {
                SimProbe.hit(SimProbe.ARENA_VOXEL_BROKEN_BY_BLAST);
                s.brokenArena.add(k);
            }
        }
        if (firesToClear != null) {
            for (long fk : firesToClear) {
                s.fires.remove(fk);
            }
        }
        if (createFire) {
            scatterFire(s, arena, breakKeys, spend, rng);
        }
    }

    private static void scatterFire(GameState s, Arena arena, long[] keys, int count, java.util.Random rng) {
        for (int ki = 0; ki < count; ki++) {
            long k = keys[ki];
            if (rng.nextInt(3) != 0) {
                continue;
            }
            int bx = BlockStore.unpackX(k);
            int by = BlockStore.unpackY(k);
            int bz = BlockStore.unpackZ(k);
            if (s.fires.containsKey(k) || s.fluids.containsKey(k) || s.cobwebs.containsKey(k)
                    || s.anchors.containsKey(k) || isSolidForFire(s, arena, bx, by, bz)) {
                continue;
            }
            if (!isSolidForFire(s, arena, bx, by - 1, bz)) {
                continue;
            }
            s.fires.put(k, FIRE_MIN_TICKS + rng.nextInt(FIRE_TICK_VARIANCE));
        }
    }

    private static boolean isSolidForFire(GameState s, Arena arena, int x, int y, int z) {
        if (s.blocks.contains(x, y, z)) {
            return true;
        }
        long k = BlockStore.key(x, y, z);
        return !s.brokenArena.contains(k) && arena.isSolidVoxel(x, y, z);
    }

    private static final int FIRE_MIN_TICKS = 160;
    private static final int FIRE_TICK_VARIANCE = 160;

    private static final float DECOR_BLAST_RESISTANCE = 0.1f;

    private static Float blastResistanceAt(GameState s, Arena arena, int x, int y, int z) {
        long k = BlockStore.key(x, y, z);
        if (s.blocks.contains(x, y, z)) {
            Float r = s.blockResistance.get(k);
            return r != null ? r : Arena.DEFAULT_VOXEL_RESISTANCE;
        }
        if (!s.brokenArena.contains(k) && arena.isSolidVoxel(x, y, z)) {
            return breakAllowed(s, arena.voxelBlockItem(x, y, z))
                    ? arena.voxelResistance(x, y, z) : ArenaCodec.BLAST_PROOF_RESISTANCE;
        }
        if (!s.brokenArena.contains(k) && arena.isDecorVoxel(x, y, z)) {
            return breakAllowed(s, arena.voxelBlockItem(x, y, z))
                    ? DECOR_BLAST_RESISTANCE : ArenaCodec.BLAST_PROOF_RESISTANCE;
        }
        return null;
    }

    private static void blastPlayers(GameState s, Arena arena, double cx, double cy, double cz, float power, int owner) {
        double q = power * 2.0;
        for (int pi = 0; pi < s.players.length; pi++) {
            PlayerState p = s.players[pi];
            if (p.dead) {
                continue;
            }
            double dx = p.x - cx;
            double dy = p.y - cy;
            double dz = p.z - cz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > q) {
                continue;
            }
            double kx = p.x - cx;
            double ky = (p.y + eyeHeight(p)) - cy;
            double kz = p.z - cz;
            double len = Math.sqrt(kx * kx + ky * ky + kz * kz);
            if (len == 0.0) {
                continue;
            }
            double exposure = seenPercent(s, arena, cx, cy, cz, p);
            double t = (1.0 - dist / q) * exposure;
            double dmg = (t * t + t) / 2.0 * 7.0 * q + 1.0;
            float finalDmg = reduceByDefenseExplosion(p, dmg);
            boolean fresh = applyDamage(p, finalDmg);
            damageArmor(s, p, finalDmg);

            if (fresh) {
                s.events.add(new CombatEvent(CombatEvent.HIT, owner, pi, false, CombatEvent.HIT_EXPLOSION));
            }

            double kt = t * (1.0 - explosionKnockbackResistance(p));
            double lift = EXPLOSION_EXTRA_LIFT;
            p.vx += kx / len * kt;
            p.vy += ky / len * kt + lift;
            p.vz += kz / len * kt;
            recordImpulse(p);
        }
    }

    private static final int ITEM_HEALTH = 5;

    private static void blastItems(GameState s, Arena arena, double cx, double cy, double cz, float power) {
        double q = power * 2.0;
        for (ItemEntityState e : s.items) {
            if (e.dead) {
                continue;
            }
            double dx = e.x - cx;
            double dy = (e.y + 0.125) - cy;
            double dz = e.z - cz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > q) {
                continue;
            }
            double exposure = rayClear(s, arena, e.x, e.y + 0.125, e.z, cx, cy, cz) ? 1.0 : 0.0;
            double t = (1.0 - dist / q) * exposure;
            double dmg = (t * t + t) / 2.0 * 7.0 * q + 1.0;
            if ((int) (ITEM_HEALTH - dmg) <= 0) {
                e.dead = true;
            }
        }
    }

    private static int blastDrop(GameState s, int dropId) {
        return dropId == s.glowstoneItemId && s.glowstoneDustItemId != 0 ? s.glowstoneDustItemId : dropId;
    }

    static final long BLAST_DROP_SEED_SALT = 0x9E3779B97F4A7C15L;

    static boolean blastDropSurvives(java.util.Random dropRng, float power) {
        return dropRng.nextFloat() <= 1.0f / power;
    }

    public static void windBurst(GameState s, Arena arena, double cx, double cy, double cz, int owner) {
        windBurst(s, arena, cx, cy, cz, Double.NaN, Double.NaN, owner);
    }

    public static void windBurst(GameState s, Arena arena, double cx, double cy, double cz,
                                 double fixedRadius, double fixedKnockback, int owner) {
        boolean perPlayer = Double.isNaN(fixedRadius) || Double.isNaN(fixedKnockback);
        s.events.add(new CombatEvent(CombatEvent.EXPLOSION,
                (int) Math.floor(cx), (int) Math.floor(cy), true, (int) Math.floor(cz)));
        for (int pi = 0; pi < s.players.length; pi++) {
            PlayerState p = s.players[pi];
            if (p.dead) {
                continue;
            }

            double radius = perPlayer
                    ? (p.gliding ? WIND_BURST_RADIUS_GLIDE : WIND_BURST_RADIUS) : fixedRadius;
            double knock = perPlayer
                    ? (p.gliding ? WIND_BURST_KNOCKBACK_GLIDE : WIND_BURST_KNOCKBACK) : fixedKnockback;
            double feetDist = Math.sqrt((p.x - cx) * (p.x - cx)
                    + (p.y - cy) * (p.y - cy) + (p.z - cz) * (p.z - cz));
            if (feetDist > radius) {
                continue;
            }
            double ex = p.x - cx;

            double eye = eyeHeight(p);
            double ey = (p.y + eye) - cy;
            double ez = p.z - cz;
            double elen = Math.sqrt(ex * ex + ey * ey + ez * ez);
            double kb = (1.0 - feetDist / radius) * knock * (1.0 - explosionKnockbackResistance(p));
            if (elen > 1.0E-4) {
                p.vx += ex / elen * kb;
                p.vy += ey / elen * kb;
                p.vz += ez / elen * kb;
                recordImpulse(p);
            }
            p.fallDistance = 0.0f;

            if (pi != owner) {
                s.events.add(new CombatEvent(CombatEvent.HIT,
                        owner, pi, false, CombatEvent.HIT_EXPLOSION));
            }
        }
    }

    private static double seenPercent(GameState s, Arena arena, double cx, double cy, double cz, PlayerState p) {
        double minX = p.x - PLAYER_HALF_WIDTH;
        double maxX = p.x + PLAYER_HALF_WIDTH;
        double minY = p.y;
        double maxY = p.y + Simulation.poseHeight(p);
        double minZ = p.z - PLAYER_HALF_WIDTH;
        double maxZ = p.z + PLAYER_HALF_WIDTH;
        double d0 = 1.0 / ((maxX - minX) * 2.0 + 1.0);
        double d1 = 1.0 / ((maxY - minY) * 2.0 + 1.0);
        double d2 = 1.0 / ((maxZ - minZ) * 2.0 + 1.0);
        double d3 = (1.0 - Math.floor(1.0 / d0) * d0) / 2.0;
        double d4 = (1.0 - Math.floor(1.0 / d2) * d2) / 2.0;
        if (!(d0 > 0.0) || !(d1 > 0.0) || !(d2 > 0.0)) {
            return 0.0;
        }
        int nf = exposureSampleCount(d0);
        int ng = exposureSampleCount(d1);
        int nh = exposureSampleCount(d2);
        int total = nf * ng * nh;
        if (total == 0) {
            return 0.0;
        }
        int hit = 0;
        for (int fi = 0; fi < nf; fi++) {
            double px = minX + (maxX - minX) * (fi * d0) + d3;
            for (int gi = 0; gi < ng; gi++) {
                double py = minY + (maxY - minY) * (gi * d1);
                for (int hi = 0; hi < nh; hi++) {
                    double pz = minZ + (maxZ - minZ) * (hi * d2) + d4;
                    if (rayClear(s, arena, px, py, pz, cx, cy, cz)) {
                        hit++;
                    }
                }
            }
        }
        return (double) hit / total;
    }

    static final int EXPOSURE_SAMPLE_CAP = 64;

    static int exposureSampleCount(double step) {
        if (!(step > 0.0) || !Double.isFinite(step)) {
            throw new IllegalArgumentException("exposure axis step must be finite and positive: " + step);
        }
        int n = 0;
        while (n <= EXPOSURE_SAMPLE_CAP && n * step <= 1.0) {
            n++;
        }
        if (n > EXPOSURE_SAMPLE_CAP) {
            throw new IllegalStateException("exposure axis for step " + step + " needs more than "
                    + EXPOSURE_SAMPLE_CAP + " samples");
        }
        return n;
    }

    private static boolean rayClear(GameState s, Arena arena, double fx, double fy, double fz,
                                    double tx, double ty, double tz) {
        double dx = tx - fx;
        double dy = ty - fy;
        double dz = tz - fz;
        java.util.List<Aabb> near = new java.util.ArrayList<>();
        arena.collectNearSolids(near, Math.min(fx, tx), Math.min(fy, ty), Math.min(fz, tz),
                Math.max(fx, tx), Math.max(fy, ty), Math.max(fz, tz), s.brokenArena);
        for (Aabb b : near) {
            double t = Raycast.segmentBox(b, fx, fy, fz, dx, dy, dz);
            if (t >= 0.0 && t < 1.0) {
                return false;
            }
        }
        for (Aabb b : s.blocks.solids()) {
            double t = Raycast.segmentBox(b, fx, fy, fz, dx, dy, dz);
            if (t >= 0.0 && t < 1.0) {
                return false;
            }
        }
        return true;
    }

    private static boolean blockIntersectsPlayers(GameState s, int x, int y, int z) {
        for (int i = 0; i < s.players.length; i++) {
            PlayerState p = s.players[i];

            double h = p.sneaking ? Simulation.PLAYER_SNEAK_HEIGHT : Simulation.PLAYER_HEIGHT;
            if (x + 1.0 > p.x - PLAYER_HALF_WIDTH && x < p.x + PLAYER_HALF_WIDTH
                    && y + 1.0 > p.y && y < p.y + h
                    && z + 1.0 > p.z - PLAYER_HALF_WIDTH && z < p.z + PLAYER_HALF_WIDTH) {
                return true;
            }
        }
        return false;
    }

    public static boolean placementOpen(GameState s, Arena arena, PlayerState a,
                                        int x, int y, int z) {
        return placementLegal(s, arena, a, x, y, z) && cellFreeForBlock(s, x, y, z);
    }

    public static boolean webPlacementOpen(GameState s, Arena arena, PlayerState a,
                                           int x, int y, int z) {
        return placementLegal(s, arena, a, x, y, z) && cellFreeForWeb(s, x, y, z);
    }

    public static boolean crystalPlacementOpen(GameState s, Arena arena, PlayerState a,
                                               int bx, int by, int bz) {
        return crystalBaseAt(s, arena, bx, by, bz)
                && cellInSight(s, arena, a, bx, by + 1, bz)
                && !hasCrystalAt(s, bx, by, bz)
                && !cellHasCrystal(s, bx, by + 1, bz) && !cellHasCrystal(s, bx, by + 2, bz)
                && !crystalHullBlocked(s, arena, bx, by + 1, bz)
                && !blockIntersectsPlayers(s, bx, by + 1, bz)
                && !blockIntersectsPlayers(s, bx, by + 2, bz)
                && !cellHasItem(s, bx, by + 1, bz) && !cellHasItem(s, bx, by + 2, bz);
    }

    public static boolean solidCell(GameState s, Arena arena, int x, int y, int z) {
        return s.blocks.contains(x, y, z) || arenaSolidCell(s, arena, x, y, z);
    }

    private static boolean cellFreeForWeb(GameState s, int x, int y, int z) {
        return !s.blocks.contains(x, y, z) && !s.cobwebs.containsKey(BlockStore.key(x, y, z));
    }

    private static boolean cellFreeForBlock(GameState s, int x, int y, int z) {
        return cellFreeForWeb(s, x, y, z)
                && !blockIntersectsPlayers(s, x, y, z)
                && !cellHasCrystal(s, x, y, z) && !cellHasItem(s, x, y, z);
    }

    private static boolean placementLegal(GameState s, Arena arena, PlayerState a, int x, int y, int z) {
        if (arenaSolidCell(s, arena, x, y, z)) {
            return false;
        }
        if (!cellInSight(s, arena, a, x, y, z)) {
            return false;
        }
        return supportCell(s, arena, x - 1, y, z)
                || supportCell(s, arena, x + 1, y, z)
                || supportCell(s, arena, x, y - 1, z)
                || supportCell(s, arena, x, y + 1, z)
                || supportCell(s, arena, x, y, z - 1)
                || supportCell(s, arena, x, y, z + 1)
                || partialSupport(s, arena, x, y, z);
    }

    private static boolean supportCell(GameState s, Arena arena, int x, int y, int z) {
        return solidCell(s, arena, x, y, z);
    }

    private static final double CELL_PROBE_INSET = 0.001;

    private static boolean partialSupport(GameState s, Arena arena, int x, int y, int z) {
        List<Aabb> boxes = new ArrayList<>(4);
        return arenaFillsPartOfCell(s, arena, boxes, x - 1, y, z)
                || arenaFillsPartOfCell(s, arena, boxes, x + 1, y, z)
                || arenaFillsPartOfCell(s, arena, boxes, x, y - 1, z)
                || arenaFillsPartOfCell(s, arena, boxes, x, y + 1, z)
                || arenaFillsPartOfCell(s, arena, boxes, x, y, z - 1)
                || arenaFillsPartOfCell(s, arena, boxes, x, y, z + 1);
    }

    private static boolean arenaFillsPartOfCell(GameState s, Arena arena, List<Aabb> boxes,
                                                int x, int y, int z) {
        boxes.clear();
        arena.collectNearSolids(boxes,
                x + CELL_PROBE_INSET, y + CELL_PROBE_INSET, z + CELL_PROBE_INSET,
                x + 1.0 - CELL_PROBE_INSET, y + 1.0 - CELL_PROBE_INSET, z + 1.0 - CELL_PROBE_INSET,
                s.brokenArena);
        return !boxes.isEmpty();
    }

    private static boolean arenaSolidCell(GameState s, Arena arena, int x, int y, int z) {
        if (s.brokenArena.contains(BlockStore.key(x, y, z))) {
            return false;
        }
        return arena.isSolidVoxel(x, y, z) || arena.staticFillsCell(x, y, z);
    }

    private static boolean crystalHullBlocked(GameState s, Arena arena, int x, int y, int z) {
        return solidCell(s, arena, x, y, z);
    }

    private static boolean crystalBaseAt(GameState s, Arena arena, int x, int y, int z) {
        if (s.blocks.contains(x, y, z)) {
            return crystalBaseId(s, s.blocks.idAt(x, y, z));
        }
        if (!arenaSolidCell(s, arena, x, y, z)) {
            return false;
        }
        if (!arena.isSolidVoxel(x, y, z)) {
            return true;
        }
        int voxelId = arena.voxelDropItem(x, y, z);
        if (voxelId != 0) {
            return crystalBaseId(s, voxelId);
        }
        return arena.voxelResistance(x, y, z) >= ArenaCodec.HIGH_RESISTANCE_THRESHOLD;
    }

    private static boolean crystalBaseId(GameState s, int id) {
        if (id == 0) {
            return false;
        }
        return id == s.obsidianItemId || Loadout.blockProps(s).hardness(id) < 0f;
    }

    private static boolean cellHasCrystal(GameState s, int x, int y, int z) {
        for (CrystalState c : s.crystals) {
            if (c.bx == x && c.bz == z && (y == c.by + 1 || y == c.by + 2)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cellHasItem(GameState s, int x, int y, int z) {
        return s.itemGrid.occupiedCell(s.items, x, y, z);
    }

    private static double blockHit(Arena arena, double ox, double oy, double oz, Vec3 look, double reach) {
        double bestT = 2.0;
        for (Aabb b : arena.solids) {
            double t = Raycast.segmentBox(b, ox, oy, oz, look.x() * reach, look.y() * reach, look.z() * reach);
            if (t >= 0.0 && t <= 1.0 && t < bestT) {
                bestT = t;
            }
        }
        return bestT <= 1.0 ? bestT : -1.0;
    }

    private static ProjectileState throwProjectile(GameState s, int idx, int type, double speed, float damage) {
        PlayerState a = s.players[idx];
        Vec3 look = lookVector(a.yaw, a.pitch);
        double eye = eyeHeight(a);
        ProjectileState pr = new ProjectileState();
        pr.id = s.nextProjectileId++;
        pr.type = type;
        pr.owner = idx;
        pr.x = a.x;
        pr.y = a.y + eye;
        pr.z = a.z;

        pr.vx = look.x() * speed + a.vx;
        pr.vy = look.y() * speed + (a.onGround ? 0.0 : a.vy);
        pr.vz = look.z() * speed + a.vz;
        pr.damage = damage;
        if (type == ProjectileState.TYPE_WIND_CHARGE) {
            pr.life = WIND_CHARGE_MAX_AGE;
        }
        Projectiles.spawn(s, pr);
        return pr;
    }

    private static void fireArrow(GameState s, int idx, int drawTicks) {
        PlayerState a = s.players[idx];
        int arrowSlot = Loadout.activeArrowSlot(s, a);
        if (arrowSlot < 0) {
            return;
        }
        float f = drawTicks / 20.0f;
        f = (f * f + 2.0f * f) / 3.0f;
        if (f < 0.1f) {
            return;
        }
        if (f > 1.0f) {
            f = 1.0f;
        }
        double speed = f * ARROW_MAX_SPEED;
        float dmg = arrowBaseDamage(Loadout.bowPower(s, a));
        int arrowEntry = Loadout.entryAt(a, arrowSlot);
        int arrowItemId = s.dict.itemId(arrowEntry);
        int bowEnchants = (Loadout.punch(s, a) & 0x7) | (Loadout.flame(s, a) != 0 ? 0x8 : 0)
                | (f >= 1.0f ? ARROW_CRIT_BIT : 0);
        boolean infinite = Loadout.infinity(s, a);
        if (!Projectiles.hasRoom(s, idx)) {
            return;
        }
        if (!infinite && !Loadout.consume(a, arrowSlot, 1)) {
            return;
        }
        ProjectileState pr = throwProjectile(s, idx, ProjectileState.TYPE_ARROW, speed, dmg);
        pr.bowEnchants = bowEnchants;
        carryEntryEffects(s, arrowEntry, pr);
        pr.infiniteArrow = infinite;
        pr.arrowItemId = arrowItemId;
        pr.arrowEntry = arrowEntry;
        if (!infinite) {
            a.arrows = Loadout.arrows(s, a);
            a.arrowsConsumed++;
        }
    }

    public static void resolveArrowPickups(GameState s) {
        for (ProjectileState p : s.projectiles) {
            if (p.dead || p.type != ProjectileState.TYPE_ARROW || !p.stuck || p.infiniteArrow || p.arrowItemId == 0) {
                continue;
            }
            if (p.shakeTime > 0) {
                continue;
            }
            int arrowEntry = s.dict.valid(p.arrowEntry)
                    ? p.arrowEntry : s.dict.entryForItemId(p.arrowItemId);
            if (arrowEntry == ItemDict.NONE) {
                continue;
            }
            Aabb arrowBox = new Aabb(p.x - ARROW_HALF_WIDTH, p.y, p.z - ARROW_HALF_WIDTH,
                    p.x + ARROW_HALF_WIDTH, p.y + ARROW_HEIGHT, p.z + ARROW_HALF_WIDTH);
            for (int i = 0; i < s.players.length; i++) {
                PlayerState taker = s.players[i];
                if (taker.dead || Loadout.invFull(s, taker)
                        || !pickupSweep(taker).intersects(arrowBox)
                        || !Loadout.addItem(s, taker, arrowEntry, 1)) {
                    continue;
                }
                taker.arrows = Loadout.arrows(s, taker);
                if (i == p.owner) {
                    taker.arrowsConsumed--;
                }
                p.dead = true;
                break;
            }
        }
    }

    private static void carryEntryEffects(GameState s, int entry, ProjectileState pr) {
        pr.effect0 = s.dict.effect(entry, 0);
        pr.effect1 = s.dict.effect(entry, 1);
        pr.effect2 = s.dict.effect(entry, 2);
        pr.effect3 = s.dict.effect(entry, 3);
    }

    private static void applyEntryEffects(GameState s, PlayerState a, int entry) {
        for (int i = 0; i < 4; i++) {
            int packed = s.dict.effect(entry, i);
            int id = ItemDict.effectId(packed);
            if (id == Effects.NONE) {
                continue;
            }
            applyEffect(a, id, ItemDict.effectAmplifier(packed), ItemDict.effectDuration(packed));
        }
    }

    public static void applyEffect(PlayerState a, int id, int rawAmp, int rawDur) {
        int amp = Math.max(0, Math.min(ItemDict.MAX_EFFECT_AMPLIFIER, rawAmp));
        int dur = Math.max(0, Math.min(ItemDict.MAX_EFFECT_DURATION, rawDur));
        if (id == Effects.INSTANT_HEALTH) {
            a.health = Math.min(a.maxHealth, a.health + 4f * (1 << amp));
        } else if (id == Effects.INSTANT_DAMAGE) {
            applyDamage(a, reduceByDefenseMagic(a, 6f * (1 << amp)));
        } else if (id == Effects.ABSORPTION) {
            a.absorption = Math.max(a.absorption, 4.0f * (amp + 1));
            a.effectTicks[id] = Math.max(a.effectTicks[id], dur);
            a.effectAmp[id] = Math.max(a.effectAmp[id], amp);
        } else if (id > 0 && id < Effects.COUNT) {
            a.effectAmp[id] = Math.max(a.effectAmp[id], amp);
            a.effectTicks[id] = Math.max(a.effectTicks[id], dur);
            a.effectCounter[id] = 0;
        }
    }

    public static boolean isBlocking(PlayerState v) {
        return v.shieldDisabled == 0 && v.blockTicks >= SHIELD_WARMUP;
    }

    private static boolean blockedFront(PlayerState v, double sx, double sz) {
        Vec3 look = lookVector(v.yaw, 0.0f);
        return look.x() * (v.x - sx) + look.z() * (v.z - sz) < 0.0;
    }

    public static boolean blocksProjectile(PlayerState v, double vx, double vz) {
        if (!isBlocking(v)) {
            return false;
        }
        Vec3 look = lookVector(v.yaw, 0.0f);
        return look.x() * vx + look.z() * vz < 0.0;
    }

    public static void tryKill(GameState s, int idx, int killerIdx) {
        PlayerState v = s.players[idx];
        if (v.dead || v.health > 0.0f) {
            return;
        }
        int totemSlot = Loadout.totemSlot(s, v);
        if (totemSlot >= 0 && Loadout.consume(v, totemSlot, 1)) {
            v.hasTotem = Loadout.hasTotem(s, v);
            v.health = 1.0f;
            clearEffects(v);
            applyEffect(v, Effects.REGENERATION, 1, 900);
            applyEffect(v, Effects.ABSORPTION, 1, 100);
            applyEffect(v, Effects.FIRE_RESISTANCE, 0, 800);
            v.totemSeq++;
            s.events.add(new CombatEvent(CombatEvent.TOTEM, idx, idx, false));
        } else {
            v.health = 0.0f;
            v.dead = true;
            s.events.add(new CombatEvent(CombatEvent.DEATH, killerIdx, idx, false));
        }
    }

    private static void clearEffects(PlayerState v) {
        for (int i = 0; i < Effects.COUNT; i++) {
            v.effectTicks[i] = 0;
            v.effectAmp[i] = 0;
            v.effectCounter[i] = 0;
        }
        v.absorption = 0.0f;
    }

    static float reduceByDefense(PlayerState v, double rawDamage) {
        float damage = (float) rawDamage;
        float f = 2.0f + v.armorToughness / 4.0f;
        float effArmor = Math.max(v.armor * 0.2f, Math.min(20.0f, v.armor - damage / f));
        float afterArmor = damage * (1.0f - effArmor / 25.0f);
        return afterArmor * (1.0f - Math.min(20.0f, v.protection) / 25.0f);
    }

    static float reduceByDefenseExplosion(PlayerState v, double rawDamage) {
        float damage = (float) rawDamage;
        float f = 2.0f + v.armorToughness / 4.0f;
        float effArmor = Math.max(v.armor * 0.2f, Math.min(20.0f, v.armor - damage / f));
        float afterArmor = damage * (1.0f - effArmor / 25.0f);
        float epf = Math.min(20.0f, v.protection + 2.0f * v.blastProtection);
        return afterArmor * (1.0f - epf / 25.0f);
    }

    static float reduceByDefenseProjectile(PlayerState v, double rawDamage) {
        float damage = (float) rawDamage;
        float f = 2.0f + v.armorToughness / 4.0f;
        float effArmor = Math.max(v.armor * 0.2f, Math.min(20.0f, v.armor - damage / f));
        float afterArmor = damage * (1.0f - effArmor / 25.0f);
        float epf = Math.min(20.0f, v.protection + 2.0f * v.projectileProtection);
        return afterArmor * (1.0f - epf / 25.0f);
    }

    static float reduceByDefenseFire(PlayerState v, double rawDamage) {
        float epf = Math.min(20.0f, v.protection + 2.0f * v.fireProtection);
        return (float) rawDamage * (1.0f - epf / 25.0f);
    }

    static float reduceByDefenseFireArmored(PlayerState v, double rawDamage) {
        float damage = (float) rawDamage;
        float f = 2.0f + v.armorToughness / 4.0f;
        float effArmor = Math.max(v.armor * 0.2f, Math.min(20.0f, v.armor - damage / f));
        float afterArmor = damage * (1.0f - effArmor / 25.0f);
        float epf = Math.min(20.0f, v.protection + 2.0f * v.fireProtection);
        return afterArmor * (1.0f - epf / 25.0f);
    }

    static float reduceByDefenseMagic(PlayerState v, double rawDamage) {
        return (float) rawDamage * (1.0f - Math.min(20.0f, v.protection) / 25.0f);
    }

    public static final double EXPLOSION_EXTRA_LIFT = 0.1;

    public static final float MAX_EXPLOSION_KNOCKBACK_RESISTANCE = 1.0f;

    public static final float BLAST_PROTECTION_KNOCKBACK_PER_LEVEL = 0.15f;

    static double explosionKnockbackResistance(PlayerState v) {
        return Math.min(MAX_EXPLOSION_KNOCKBACK_RESISTANCE,
                BLAST_PROTECTION_KNOCKBACK_PER_LEVEL * v.blastProtection);
    }

    static float reduceByDefenseBreach(PlayerState v, double rawDamage, int breach) {
        float damage = (float) rawDamage;
        float f = 2.0f + v.armorToughness / 4.0f;
        float effArmor = Math.max(v.armor * 0.2f, Math.min(20.0f, v.armor - damage / f));
        float fraction = effArmor / 25.0f - 0.15f * breach;
        if (fraction < 0.0f) {
            fraction = 0.0f;
        } else if (fraction > 1.0f) {
            fraction = 1.0f;
        }
        float afterArmor = damage * (1.0f - fraction);
        return afterArmor * (1.0f - Math.min(20.0f, v.protection) / 25.0f);
    }

    public static boolean applyDamage(PlayerState v, float dmg) {
        if (dmg < 0.0f) {
            dmg = 0.0f;
        }
        if (v.hurtTime > 0) {
            if (dmg <= v.lastDamage) {
                return false;
            }
            hurt(v, dmg - v.lastDamage);
            v.lastDamage = dmg;
            return false;
        }
        v.lastDamage = dmg;
        hurt(v, dmg);
        v.hurtTime = I_FRAMES;
        return true;
    }

    public static void hurt(PlayerState v, float dmg) {
        if (v.effectTicks[Effects.RESISTANCE] > 0) {
            int level = v.effectAmp[Effects.RESISTANCE] + 1;
            dmg *= Math.max(0.0f, 1.0f - 0.2f * level);
        }
        if (v.absorption > 0f) {
            float soak = Math.min(v.absorption, dmg);
            v.absorption -= soak;
            dmg -= soak;
        }
        v.health -= dmg;
    }

    public static final float ARROW_BASE_DAMAGE = 2.0f;

    public static final int ARROW_CRIT_BIT = 0x10;

    public static float arrowBaseDamage(int power) {
        return power > 0 ? ARROW_BASE_DAMAGE + 0.5f + 0.5f * power : ARROW_BASE_DAMAGE;
    }

    private static void fireCrossbow(GameState s, int idx, boolean pickupable, int arrowEntry) {
        float dmg = ARROW_BASE_DAMAGE;
        if (Loadout.multishot(s, s.players[idx])) {
            fireBolt(s, idx, dmg, -10.0f, false, arrowEntry);
            fireBolt(s, idx, dmg, 0.0f, pickupable, arrowEntry);
            fireBolt(s, idx, dmg, 10.0f, false, arrowEntry);
        } else {
            fireBolt(s, idx, dmg, 0.0f, pickupable, arrowEntry);
        }
    }

    private static void fireBolt(GameState s, int idx, float dmg, float yawOffset, boolean pickupable,
                                 int arrowEntry) {
        PlayerState a = s.players[idx];
        Vec3 look = lookVector(a.yaw + yawOffset, a.pitch);
        double eye = eyeHeight(a);
        ProjectileState pr = new ProjectileState();
        pr.id = s.nextProjectileId++;
        pr.type = ProjectileState.TYPE_ARROW;
        pr.owner = idx;
        pr.x = a.x;
        pr.y = a.y + eye;
        pr.z = a.z;
        pr.vx = look.x() * CROSSBOW_SPEED;
        pr.vy = look.y() * CROSSBOW_SPEED;
        pr.vz = look.z() * CROSSBOW_SPEED;
        pr.damage = dmg;
        pr.fresh = true;
        carryEntryEffects(s, arrowEntry, pr);

        int loadedArrowId = s.dict.itemId(arrowEntry);
        int loadedEntry = arrowEntry;
        if (loadedArrowId == 0) {
            loadedArrowId = plainArrowItemId(s);
            loadedEntry = ItemDict.NONE;
        }
        pr.arrowItemId = pickupable ? loadedArrowId : 0;
        pr.arrowEntry = pickupable ? loadedEntry : ItemDict.NONE;
        Projectiles.spawn(s, pr);
    }

    private static int clampSlot(int slot) {
        return Math.max(0, Math.min(8, slot));
    }

    public static Vec3 lookVector(float yaw, float pitch) {
        float radPitch = pitch * DEG_TO_RAD;
        float radYaw = -yaw * DEG_TO_RAD;
        float cosPitch = MathTables.cos(radPitch);
        float sinPitch = MathTables.sin(radPitch);
        float cosYaw = MathTables.cos(radYaw);
        float sinYaw = MathTables.sin(radYaw);
        return new Vec3(sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch);
    }

    private static Vec3 arcedThrowDir(float yaw, float pitch) {
        Vec3 look = lookVector(yaw, pitch);
        double vy = -MathTables.sin((pitch - 20.0f) * DEG_TO_RAD);
        double mag = Math.sqrt(look.x() * look.x() + vy * vy + look.z() * look.z());
        if (mag < 1.0E-6) {
            return new Vec3(0.0, 0.0, 0.0);
        }
        return new Vec3(look.x() / mag, vy / mag, look.z() / mag);
    }
}
