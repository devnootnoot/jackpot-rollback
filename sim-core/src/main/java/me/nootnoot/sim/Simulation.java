package me.nootnoot.sim;

import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.math.MathTables;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.vanilla.ClientMovementInput;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;

public final class Simulation {
    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final float AIR_FRICTION = 0.91F;
    public static final float DEFAULT_SLIPPERINESS = 0.6F;
    public static final float GROUND_SPEED_CONST = 0.21600002F;
    public static final float BASE_MOVEMENT_SPEED = 0.1F;
    public static final float SPRINT_MULTIPLIER = 1.3F;

    public static final float AIR_SPEED_SPRINT = 0.025999999F;
    public static final float AIR_SPEED_WALK = 0.02F;
    public static final float SNEAK_INPUT_MULTIPLIER = 0.3F;

    public static final float EATING_INPUT_MULTIPLIER = 0.2F;
    public static final double JUMP_VELOCITY = 0.42F;
    public static final double SPRINT_JUMP_BOOST = 0.2;
    public static final int JUMP_COOLDOWN_TICKS = 10;

    public static final double PLAYER_WIDTH = 0.6;
    public static final double PLAYER_HEIGHT = 1.8;
    public static final double PLAYER_SNEAK_HEIGHT = 1.5;

    public static final double PLAYER_SWIM_HEIGHT = 0.6;
    public static final double STEP_HEIGHT = 0.6;

    public static final double SWIM_HEIGHT = 0.4;

    public static double poseHeight(boolean prone, boolean sneaking) {
        if (prone) {
            return PLAYER_SWIM_HEIGHT;
        }
        return sneaking ? PLAYER_SNEAK_HEIGHT : PLAYER_HEIGHT;
    }

    public static double poseHeight(PlayerState p) {
        return poseHeight(p.swimming || p.gliding, p.sneaking);
    }

    private static final float DEG_TO_RAD = (float) Math.PI / 180.0F;

    private Simulation() {
    }

    public static final int ROUND_COUNTDOWN_TICKS = 100;

    public static final int ROUND_DISPLAY_TICKS = 20;

    public static final int ROUND_RESET_TOTAL = ROUND_COUNTDOWN_TICKS + ROUND_DISPLAY_TICKS;

    public static final int CAGE_FALL_GRACE = 40;

    public static final int ROUND_START_GRACE = 15;

    public static final int CAGE_DROP_MAX_TICKS = 30 * 20;

    public static final double CAGE_DROP_RELEASE_MARGIN = 0.5;

    public static final int CAGE_DROP_GROUND_TICKS = 20;

    public static final int ELYTRA_WEAR_PERIOD = 20;

    public static final int ELYTRA_BOOST_WEAR_PERIOD = 10;

    public static void tick(GameState state, Arena arena, Input rawInput0, Input rawInput1) {
        Input input0 = Combat.contractFiltered(rawInput0);
        Input input1 = Combat.contractFiltered(rawInput1);
        state.events.clear();
        state.blastCellBudget = GameState.BLAST_CELLS_PER_TICK;
        state.blastMarchBudget = GameState.BLAST_MARCH_CELLS_PER_TICK;
        ClaimAuthority.record(state);

        if (state.roundMatchOver) {
            state.tick++;
            return;
        }

        if (state.roundResetCountdown > 0) {
            if (state.roundResetCountdown == ROUND_COUNTDOWN_TICKS
                    && (state.players[0].dead || state.players[1].dead)) {
                resetForNextRound(state);
                state.players[0].ready = false;
                state.players[1].ready = false;
            }

            if (state.roundResetCountdown <= ROUND_COUNTDOWN_TICKS) {
                if (input0.attack() || state.players[0].instaReady) {
                    state.players[0].ready = true;
                }
                if (input1.attack() || state.players[1].instaReady) {
                    state.players[1].ready = true;
                }
                if (state.players[0].ready && state.players[1].ready && state.roundResetCountdown > 1) {
                    state.roundResetCountdown = 1;
                }
            }
            state.roundResetCountdown--;
            if (state.roundResetCountdown == 0) {
                state.players[0].noFallTicks = CAGE_FALL_GRACE;
                state.players[1].noFallTicks = CAGE_FALL_GRACE;
                state.players[0].cageFallTicks = CAGE_DROP_MAX_TICKS;
                state.players[1].cageFallTicks = CAGE_DROP_MAX_TICKS;
                state.roundStartGrace = ROUND_START_GRACE;
            }
            state.tick++;
            return;
        }

        Combat.tickUseDelays(state.players[0]);
        Combat.tickUseDelays(state.players[1]);

        Combat.resolveBlockActions(state, arena, input0, input1);
        tickCageDrop(state, 0);
        tickCageDrop(state, 1);
        tickPlayer(state, state.players[0], arena, input0);
        tickPlayer(state, state.players[1], arena, input1);
        Combat.resolve(state, arena, input0, input1);
        if (state.roundStartGrace > 0) {
            state.roundStartGrace--;
        }
        Projectiles.tick(state, arena, input0, input1);
        Combat.resolveArrowPickups(state);
        ItemEntities.tick(state, arena, input0, input1);
        Fluids.flow(state, arena);
        tickFires(state);
        resolveDeaths(state);
        checkRoundEnd(state);
        state.tick++;
    }

    private static final double LIFT_STEP = 0.25;

    public static boolean playerBoxFree(GameState s, Arena arena, double x, double y, double z) {
        return noSolidIn(arena, s.blocks, s.brokenArena,
                Aabb.player(x, y, z, PLAYER_WIDTH, PLAYER_HEIGHT));
    }

    public static double resolveStandingY(GameState s, Arena arena, double x, double y, double z,
                                          double maxLift) {
        if (playerBoxFree(s, arena, x, y, z)) {
            return y;
        }
        int steps = (int) Math.floor(maxLift / LIFT_STEP);
        for (int i = 1; i <= steps; i++) {
            double lift = i * LIFT_STEP;
            if (playerBoxFree(s, arena, x, y + lift, z)) {
                return y + lift;
            }
        }
        return Double.NaN;
    }

    private static boolean noSolidIn(Arena arena, me.nootnoot.sim.state.BlockStore blocks,
                                     java.util.Set<Long> broken, Aabb box) {
        java.util.List<Aabb> near = new java.util.ArrayList<>();
        arena.collectNearSolids(near, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, broken);
        for (Aabb a : near) {
            if (overlaps(a, box)) {
                return false;
            }
        }
        for (Aabb a : blocks.solids()) {
            if (overlaps(a, box)) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlaps(Aabb a, Aabb b) {
        return a.maxX > b.minX && a.minX < b.maxX
                && a.maxY > b.minY && a.minY < b.maxY
                && a.maxZ > b.minZ && a.minZ < b.maxZ;
    }

    private static void tickElytra(GameState s, PlayerState p, Arena arena, Input in,
                                   me.nootnoot.sim.state.BlockStore blocks,
                                   java.util.Set<Long> broken) {
        int wearPeriod = p.fireworkTicks > 0 ? ELYTRA_BOOST_WEAR_PERIOD : ELYTRA_WEAR_PERIOD;
        if (s.tick % wearPeriod == 0) {
            Loadout.damageSlot(s, p, ItemDict.ARMOR_CHEST, 1);
            p.hasElytra = Loadout.hasElytra(s, p);
        }

        Vec3 look = Combat.lookVector(p.yaw, p.pitch);
        double lookX = look.x();
        double lookY = look.y();
        double lookZ = look.z();

        if (p.fireworkTicks > 0) {
            p.vx += lookX * 0.1 + (lookX * 1.5 - p.vx) * 0.5;
            p.vy += lookY * 0.1 + (lookY * 1.5 - p.vy) * 0.5;
            p.vz += lookZ * 0.1 + (lookZ * 1.5 - p.vz) * 0.5;
        }

        double horizLook = Math.sqrt(lookX * lookX + lookZ * lookZ);
        double hSpeed = Math.sqrt(p.vx * p.vx + p.vz * p.vz);
        float pitchRad = p.pitch * DEG_TO_RAD;
        double cosP = StrictMath.cos((double) pitchRad);
        cosP = cosP * cosP;

        p.vy += 0.08 * (-1.0 + cosP * 0.9);
        if (p.vy < 0.0 && horizLook > 0.0) {
            double d = p.vy * -0.1 * cosP;
            p.vy += d;
            p.vx += lookX * d / horizLook;
            p.vz += lookZ * d / horizLook;
        }
        if (pitchRad < 0.0f && horizLook > 0.0) {
            double d = hSpeed * (double) (-MathTables.sin(pitchRad)) * 0.04;
            p.vy += d * 3.2;
            p.vx -= lookX * d / horizLook;
            p.vz -= lookZ * d / horizLook;
        }
        if (horizLook > 0.0) {
            p.vx += (lookX / horizLook * hSpeed - p.vx) * 0.1;
            p.vz += (lookZ / horizLook * hSpeed - p.vz) * 0.1;
        }
        p.vx *= 0.99;
        p.vy *= 0.98;
        p.vz *= 0.99;

        double dvx = p.vx;
        double dvy = p.vy;
        double dvz = p.vz;
        double height = PLAYER_SWIM_HEIGHT;
        Aabb box = Aabb.player(p.x, p.y, p.z, PLAYER_WIDTH, height);
        Vec3 moved = collideArena(arena, box, dvx, dvy, dvz, false, 0.0, blocks, broken);
        Authority auth = in.authority();
        boolean stamped = stamps(s, p, in);
        Vec3 authMove = stamped
                ? authorityMove(arena, box, p, auth, false, blocks, broken)
                : null;
        double appliedX = stamped ? authMove.x() : moved.x();
        double appliedY = stamped ? authMove.y() : moved.y();
        double appliedZ = stamped ? authMove.z() : moved.z();
        p.x += appliedX;
        p.y += appliedY;
        p.z += appliedZ;
        if (stamped) {
            p.vx = appliedX;
            p.vy = appliedY;
            p.vz = appliedZ;
        }
        if (moved.x() != dvx) {
            p.vx = 0.0;
        }
        if (moved.y() != dvy) {
            p.vy = 0.0;
        }
        if (moved.z() != dvz) {
            p.vz = 0.0;
        }
        p.onGround = stamped
                ? authorityGround(arena, blocks, broken, p, auth.onGround(), appliedY)
                : (moved.y() != dvy && dvy < 0.0);
        if (p.onGround) {
            p.gliding = false;
        }
        double dist = Math.sqrt(appliedX * appliedX + appliedZ * appliedZ);
        tickHunger(p, false, dist, false);
    }

    private static void tickConsumeEffects(PlayerState p) {
        if (p.effectTicks[Effects.REGENERATION] > 0
                && bump(p, Effects.REGENERATION, 50) && p.health < p.maxHealth) {
            p.health = Math.min(p.maxHealth, p.health + 1.0f);
        }
        if (p.effectTicks[Effects.POISON] > 0 && bump(p, Effects.POISON, 25) && p.health > 1.0f) {
            p.health = Math.max(1.0f, p.health - 1.0f);
        }
        if (p.effectTicks[Effects.WITHER] > 0 && bump(p, Effects.WITHER, 40)) {
            p.health -= 1.0f;
        }
        for (int id = 1; id < Effects.COUNT; id++) {
            if (p.effectTicks[id] > 0) {
                p.effectTicks[id]--;
                if (p.effectTicks[id] == 0) {
                    p.effectAmp[id] = 0;
                    if (id == Effects.ABSORPTION) {
                        p.absorption = 0f;
                    }
                }
            }
        }
    }

    private static boolean bump(PlayerState p, int id, int base) {
        if (++p.effectCounter[id] >= Math.max(1, base >> p.effectAmp[id])) {
            p.effectCounter[id] = 0;
            return true;
        }
        return false;
    }

    static final float MAX_EXHAUSTION = 40.0f;

    static float clampExhaustion(float exhaustion) {
        if (!Float.isFinite(exhaustion)) {
            return 0.0f;
        }
        return Math.min(exhaustion, MAX_EXHAUSTION);
    }

    static int exhaustionRounds(float exhaustion) {
        if (!(exhaustion >= 4.0f)) {
            return 0;
        }
        return (int) Math.floor((double) exhaustion / 4.0);
    }

    static int distanceCentimetres(double horizontalDistance) {
        float cm = (float) horizontalDistance * 100.0f;
        if (!Float.isFinite(cm) || cm <= 0.0f) {
            return 0;
        }
        return cm >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.round(cm);
    }

    private static void tickHunger(PlayerState p, boolean sprinting, double horizontalDistance, boolean jumped) {
        if (sprinting) {
            p.exhaustion += 0.1f * distanceCentimetres(horizontalDistance) * 0.01f;
        }
        if (jumped) {
            p.exhaustion += sprinting ? 0.2f : 0.05f;
        }
        p.exhaustion = clampExhaustion(p.exhaustion);
        int rounds = exhaustionRounds(p.exhaustion);
        if (rounds > 0) {
            p.exhaustion -= 4.0f * rounds;
            int saturationRounds = Math.max(0, Math.min(rounds, (int) Math.ceil((double) p.saturation)));
            if (saturationRounds > 0) {
                p.saturation = Math.max(0.0f, p.saturation - saturationRounds);
            }
            int foodRounds = rounds - saturationRounds;
            if (foodRounds > 0) {
                p.food = Math.max(0.0f, p.food - foodRounds);
            }
        }
        if (p.saturation > 0.0f && p.food >= 20.0f && p.health < p.maxHealth) {
            if (++p.regenTimer >= 10) {
                float f = Math.min(p.saturation, 6.0f);
                p.health = Math.min(p.maxHealth, p.health + f / 6.0f);
                p.exhaustion += f;
                p.regenTimer = 0;
            }
        } else if (p.food >= 18.0f && p.health < p.maxHealth) {
            if (++p.regenTimer >= 80) {
                p.health = Math.min(p.maxHealth, p.health + 1.0f);
                p.exhaustion += 6.0f;
                p.regenTimer = 0;
            }
        } else if (p.food <= 0.0f) {
            if (++p.regenTimer >= 80) {
                p.health = Math.max(1.0f, p.health - 1.0f);
                p.regenTimer = 0;
            }
        } else {
            p.regenTimer = 0;
        }
    }
}
