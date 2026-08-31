package me.nootnoot.sim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.vanilla.ClientMovementInput.Impulse;
import java.util.Map.Entry;
import me.nootnoot.sim.state.BlockStore;
import java.util.Set;
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
        int impulseSeq0 = state.players[0].impulseSeq;
        int impulseSeq1 = state.players[1].impulseSeq;
        Combat.resolve(state, arena, input0, input1);
        if (state.roundStartGrace > 0) {
            state.roundStartGrace--;
        }
        Projectiles.tick(state, arena, input0, input1);
        Combat.resolveArrowPickups(state);
        ItemEntities.tick(state, arena, input0, input1);
        Fluids.flow(state, arena);
        tickFires(state);
        armImpulseHold(state, impulseSeq0, impulseSeq1);
        resolveDeaths(state);
        checkRoundEnd(state);
        state.tick++;
    }

    public static final double AUTHORITY_RESUME_BLOCKS = 2.0;
    public static final int AUTHORITY_SUSPEND_TICKS = 40;
    public static final int AUTHORITY_WALK_MAX_STEPS = 64;
    public static final double AUTHORITY_WALK_STEP = 0.5;
    public static final double GROUND_PROBE = 0.02;
    public static final double MAX_AUTHORITY_STEP = 24.0;



   public static final int IMPULSE_HOLD_TICKS = 20;

   public static final int IMPULSE_HOLD_MIN_TICKS = 6;

   private static void armImpulseHold(GameState s, int seq0, int seq1) {
      for (int i = 0; i < s.players.length; i++) {
         if (!s.edgeHosted[i]) {
            continue;
         }
         PlayerState p = s.players[i];
         int before = i == 0 ? seq0 : seq1;
         if (p.impulseSeq != before) {
            p.impulseHoldTicks = IMPULSE_HOLD_TICKS;
         } else if (p.impulseHoldTicks > 0) {
            p.impulseHoldTicks--;
         }
      }
   }

   private static void resolveDeaths(GameState s) {
      for (int i = 0; i < s.players.length; i++) {
         Combat.tryKill(s, i, 1 - i);
      }
   }

   private static void checkRoundEnd(GameState s) {
      boolean p0Dead = s.players[0].dead;
      boolean p1Dead = s.players[1].dead;
      if (p0Dead || p1Dead) {
         int winner = p0Dead ? 1 : 0;
         int wins = winner == 0 ? ++s.roundWinsP0 : ++s.roundWinsP1;
         if (wins >= s.roundsTarget) {
            s.roundMatchOver = true;
            s.roundMatchWinner = winner;
         } else {
            s.roundResetCountdown = 120;
         }
      }
   }

   private static void tickCageDrop(GameState s, int slot) {
      PlayerState p = s.players[slot];
      if (p.cageFallTicks > 0) {
         double releaseY = Double.MAX_VALUE;
         if (s.roundInitial != null && slot < s.roundInitial.length && s.roundInitial[slot] != null) {
            releaseY = s.roundInitial[slot].y + 0.5;
         }

         int elapsed = 600 - p.cageFallTicks;
         if (!p.dead && (!p.onGround || !(p.y <= releaseY) || elapsed < 20)) {
            p.cageFallTicks--;
         } else {
            p.cageFallTicks = 0;
         }
      }
   }

   private static void resetForNextRound(GameState s) {
      SimProbe.hit(44);
      if (s.roundInitial != null) {
         int[] granted = new int[]{s.players[0].meleeClaimsGranted, s.players[1].meleeClaimsGranted};
         int[] offAim = new int[]{s.players[0].meleeClaimsOffAim, s.players[1].meleeClaimsOffAim};
         s.players[0] = s.roundInitial[0].copy();
         s.players[1] = s.roundInitial[1].copy();
         s.players[0].meleeClaimsGranted = granted[0];
         s.players[1].meleeClaimsGranted = granted[1];
         s.players[0].meleeClaimsOffAim = offAim[0];
         s.players[1].meleeClaimsOffAim = offAim[1];
      } else {
         s.players[0].dead = false;
         s.players[1].dead = false;
      }

      for (PlayerState p : s.players) {
         p.health = p.maxHealth;
         p.food = 20.0F;
         p.saturation = 5.0F;
         p.absorption = 0.0F;
         p.fallDistance = 0.0F;
         p.fireTicks = 0;
         ClaimAuthority.clear(p);
      }

      s.projectiles.clear();
      s.blocks.clear();
      s.crystals.clear();
      s.anchors.clear();
      s.items.clear();
      s.brokenArena.clear();
      s.blockResistance.clear();
      s.fluids.clear();
      s.cobwebs.clear();
      s.fires.clear();
      s.blockContainers.clear();
      s.containers.clear();

      for (Entry<Integer, Container> e : s.roundInitialContainers.entrySet()) {
         s.containers.put(e.getKey(), e.getValue().copy());
      }
   }

   private static boolean stamps(GameState s, PlayerState p, Input in) {
      Authority auth = in.authority();
      if (auth.present() && !p.dead) {
         int idx = p == s.players[0] ? 0 : 1;
         if (!s.edgeHosted[idx]) {
            SimProbe.hit(133);
            return false;
         }

         if (!withinAuthorityStep(p, auth)) {
            SimProbe.hit(132);
            return false;
         }

         if (p.impulseHoldTicks > 0) {
            int held = IMPULSE_HOLD_TICKS - p.impulseHoldTicks;
            if (held < IMPULSE_HOLD_MIN_TICKS || !p.onGround || !auth.onGround()) {
               SimProbe.hit(SimProbe.AUTHORITY_STAMP_IMPULSE_HELD);
               return false;
            }

            p.impulseHoldTicks = 0;
         }

         if (p.authoritySuspendTicks > 0) {
            double dx = auth.x() - p.x;
            double dy = auth.y() - p.y;
            double dz = auth.z() - p.z;
            if (dx * dx + dy * dy + dz * dz > 4.0) {
               p.authoritySuspendTicks--;
               SimProbe.hit(134);
               return false;
            }

            p.authoritySuspendTicks = 0;
         }

         return true;
      } else {
         return false;
      }
   }

   private static boolean withinAuthorityStep(PlayerState p, Authority auth) {
      double dx = auth.x() - p.x;
      double dy = auth.y() - p.y;
      double dz = auth.z() - p.z;
      double stepSq = dx * dx + dy * dy + dz * dz;
      return stepSq <= 576.0;
   }

   private static Vec3 authorityMove(Arena arena, Aabb box, PlayerState p, Authority auth, boolean onGroundEntry, BlockStore blocks, Set<Long> broken) {
      double dx = auth.x() - p.x;
      double dy = auth.y() - p.y;
      double dz = auth.z() - p.z;
      int steps = authorityWalkSteps(dx, dy, dz);
      if (steps <= 1) {
         return collideArena(arena, box, dx, dy, dz, onGroundEntry, 0.6, blocks, broken);
      }

      SimProbe.hit(130);
      double legX = dx / steps;
      double legY = dy / steps;
      double legZ = dz / steps;
      Aabb at = box;
      boolean ground = onGroundEntry;
      double ax = 0.0;
      double ay = 0.0;
      double az = 0.0;

      for (int i = 0; i < steps; i++) {
         Vec3 leg = collideArena(arena, at, legX, legY, legZ, ground, 0.6, blocks, broken);
         ax += leg.x();
         ay += leg.y();
         az += leg.z();
         at = at.offset(leg.x(), leg.y(), leg.z());
         ground = ground || leg.y() != legY && legY < 0.0;
      }

      if (ax != dx || ay != dy || az != dz) {
         SimProbe.hit(131);
      }

      return new Vec3(ax, ay, az);
   }

   private static boolean authorityGround(Arena arena, BlockStore blocks, Set<Long> broken, PlayerState p, boolean claimed, double appliedY) {
      return supportedBelow(arena, blocks, broken, p.x, p.y, p.z) && (claimed || appliedY <= 0.0);
   }

   private static void tickPlayer(GameState s, PlayerState p, Arena arena, Input in) {
      BlockStore blocks = s.blocks;
      Set<Long> broken = s.brokenArena;
      p.yaw = in.yaw();
      p.pitch = in.pitch();
      p.heldSlot = in.heldSlot();
      int lastMainHandItemId = p.heldItemId;
      Loadout.recomputeDerived(s, p);
      if (p.heldItemId != lastMainHandItemId) {
         p.attackTicker = 0;
      }

      if (p.shieldDisabled > 0) {
         p.shieldDisabled--;
      }

      boolean raisingShield = in.use() && p.heldUseKind == 9 && p.shieldDisabled == 0;
      if (!in.synthetic()) {
         p.blockTicks = raisingShield ? p.blockTicks + 1 : 0;
      }

      tickConsumeEffects(p);
      boolean using = p.eating || p.offhandEatTicks > 0 || p.drawTicks > 0 || p.blockTicks > 0;
      boolean forwardImpulse = in.forward() && !in.back();
      boolean sprinting = in.sprint() && p.food > 6.0F && forwardImpulse && (!using || p.sprinting) && (p.sprinting || !in.sneak() || p.submergedEye);
      boolean prevSprinting = p.sprinting;
      boolean wasSwimming = p.swimming;
      double hzHeight = poseHeight(wasSwimming, in.sneak());
      int hx0 = (int)Math.floor(p.x - 0.3);
      int hx1 = (int)Math.floor(p.x + 0.3);
      int hy0 = (int)Math.floor(p.y);
      int hy1 = (int)Math.floor(p.y + hzHeight - 1.0E-7);
      int hz0 = (int)Math.floor(p.z - 0.3);
      int hz1 = (int)Math.floor(p.z + 0.3);
      boolean inWater = Fluids.rangeHas(s, 0, hx0, hy0, hz0, hx1, hy1, hz1);
      boolean inLava = Fluids.rangeHas(s, 1, hx0, hy0, hz0, hx1, hy1, hz1);
      boolean inWeb = cobwebInRange(s, hx0, hy0, hz0, hx1, hy1, hz1);
      boolean inFire = fireInRange(s, hx0, hy0, hz0, hx1, hy1, hz1);
      boolean inDeepWater = inWater && Fluids.maxWaterHeightInRange(s, hx0, hy0, hz0, hx1, hy1, hz1) > 0.4;
      boolean inDeepLava = inLava && Fluids.maxHeightInRange(s, 1, hx0, hy0, hz0, hx1, hy1, hz1) > 0.4;
      boolean submergedNow = p.submergedEye && inWater;
      p.submergedEye = eyeCellIsWater(s, p, wasSwimming, in.sneak());
      p.swimming = wasSwimming ? prevSprinting && inWater : prevSprinting && submergedNow && feetCellIsWater(s, p);
      p.sprinting = sprinting;
      p.sneaking = in.sneak();
      double poseH = poseHeight(p.swimming, in.sneak());
      if (wasSwimming && !p.swimming && !noSolidIn(arena, blocks, broken, Aabb.player(p.x, p.y, p.z, 0.6, poseH))) {
         poseH = noSolidIn(arena, blocks, broken, Aabb.player(p.x, p.y, p.z, 0.6, 1.5)) ? 1.5 : 0.6;
         p.swimming = poseH == 0.6;
      }

      boolean onGroundEntry = p.onGround;
      if (p.jumpCooldown > 0) {
         p.jumpCooldown--;
      }

      boolean justStartedGliding = false;
      boolean deployEdge = !in.synthetic() && (in.jump() && !p.prevJump || in.elytraStart() && !p.prevElytraStart);
      if (deployEdge && !onGroundEntry && p.hasElytra && !p.gliding && p.cageFallTicks <= 0) {
         p.gliding = true;
         justStartedGliding = true;
      }

      if (p.gliding && p.cageFallTicks > 0) {
         p.gliding = false;
      }

      if (p.gliding && !justStartedGliding && (onGroundEntry || !p.hasElytra)) {
         p.gliding = false;
         if (onGroundEntry) {
            p.vx = 0.0;
            p.vz = 0.0;
         }
      }

      if (!in.synthetic()) {
         p.prevJump = in.jump();
         p.prevElytraStart = in.elytraStart();
      }

      if (p.gliding) {
         p.fallDistance = 0.0F;
         tickElytra(s, p, arena, in, blocks, broken);
      } else {
         boolean swimUp = in.jump() && (inWater && (!onGroundEntry || inDeepWater) || inLava && !inWater && (!onGroundEntry || inDeepLava));
         if (swimUp) {
            p.vy += 0.04;
         }

         if (p.swimming) {
            double lookY = -MathTables.sin(p.pitch * (float) (Math.PI / 180.0));
            double pull = lookY < -0.2 ? 0.085 : 0.06;
            if (lookY <= 0.0 || in.jump() || fluidAtHead(s, p)) {
               p.vy = p.vy + (lookY - p.vy) * pull;
            }
         }

         boolean jumped = false;
         if (in.jump() && !swimUp && onGroundEntry && p.jumpCooldown == 0 && !inDeepWater) {
            double jumpPower = 0.42F;
            if (p.effectTicks[6] > 0) {
               jumpPower += 0.1 * (p.effectAmp[6] + 1);
            }

            p.vy = Math.max(jumpPower, p.vy);
            if (sprinting) {
               float yawRad = p.yaw * (float) (Math.PI / 180.0);
               p.vx = p.vx + -MathTables.sin(yawRad) * 0.2;
               p.vz = p.vz + MathTables.cos(yawRad) * 0.2;
            }

            p.jumpCooldown = 10;
            jumped = true;
         }

         float slip = 0.6F;
         float friction = onGroundEntry ? 0.54600006F : 0.91F;
         float movementSpeed = 0.1F * (sprinting ? 1.3F : 1.0F);
         int spdLvl = p.effectTicks[1] > 0 ? p.effectAmp[1] + 1 : 0;
         int slowLvl = p.effectTicks[2] > 0 ? p.effectAmp[2] + 1 : 0;
         float effectScale = Math.max(0.0F, 1.0F + 0.2F * spdLvl - 0.15F * slowLvl);
         float speed = onGroundEntry ? movementSpeed * 1.0F * effectScale : (sprinting ? 0.025999999F : 0.02F);
         if (inWater || inLava) {
            speed = 0.02F;
         }

         float rawStrafe = (in.left() ? 1.0F : 0.0F) - (in.right() ? 1.0F : 0.0F);
         float rawForward = (in.forward() ? 1.0F : 0.0F) - (in.back() ? 1.0F : 0.0F);
         if (rawStrafe != 0.0F || rawForward != 0.0F) {
            Impulse impulse = ClientMovementInput.apply(rawStrafe, rawForward, using ? 0.2F : 1.0F, in.sneak() ? 0.3F : 1.0F);
            double strafe = impulse.sideways() * speed;
            double forward = impulse.forward() * speed;
            float yawRad = p.yaw * (float) (Math.PI / 180.0);
            float sin = MathTables.sin(yawRad);
            float cos = MathTables.cos(yawRad);
            p.vx += strafe * cos - forward * sin;
            p.vz += forward * cos + strafe * sin;
         }

         if (inWeb) {
            p.vx *= 0.25;
            p.vy *= 0.05;
            p.vz *= 0.25;
         }

         double desiredVx = p.vx;
         double desiredVy = p.vy;
         double desiredVz = p.vz;
         double height = poseH;
         Aabb box = Aabb.player(p.x, p.y, p.z, 0.6, height);
         if (in.sneak() && onGroundEntry && desiredVy <= 0.0) {
            double[] adj = backOffFromEdge(arena, blocks, broken, box, desiredVx, desiredVz, 0.6);
            desiredVx = adj[0];
            desiredVz = adj[1];
         }

         Vec3 moved = collideArena(arena, box, desiredVx, desiredVy, desiredVz, onGroundEntry, 0.6, blocks, broken);
         Authority auth = in.authority();
         boolean stamped = stamps(s, p, in);
         Vec3 authMove = stamped ? authorityMove(arena, box, p, auth, onGroundEntry, blocks, broken) : null;
         double appliedX = stamped ? authMove.x() : moved.x();
         double appliedY = stamped ? authMove.y() : moved.y();
         double appliedZ = stamped ? authMove.z() : moved.z();
         p.x += appliedX;
         p.y += appliedY;
         p.z += appliedZ;
         boolean xBlocked = moved.x() != desiredVx;
         boolean yBlocked = moved.y() != desiredVy;
         boolean zBlocked = moved.z() != desiredVz;
         if (stamped) {
            p.vx = appliedX;
            p.vy = appliedY;
            p.vz = appliedZ;
            p.onGround = authorityGround(arena, blocks, broken, p, auth.onGround(), appliedY);
         } else {
            p.onGround = yBlocked && desiredVy < 0.0;
         }

         boolean endInFluid = !s.fluids.isEmpty()
            && (
               Fluids.rangeHas(
                     s,
                     0,
                     (int)Math.floor(p.x - 0.3),
                     (int)Math.floor(p.y),
                     (int)Math.floor(p.z - 0.3),
                     (int)Math.floor(p.x + 0.3),
                     (int)Math.floor(p.y + 1.8 - 1.0E-7),
                     (int)Math.floor(p.z + 0.3)
                  )
                  || Fluids.rangeHas(
                     s,
                     1,
                     (int)Math.floor(p.x - 0.3),
                     (int)Math.floor(p.y),
                     (int)Math.floor(p.z - 0.3),
                     (int)Math.floor(p.x + 0.3),
                     (int)Math.floor(p.y + 1.8 - 1.0E-7),
                     (int)Math.floor(p.z + 0.3)
                  )
            );
         if (p.noFallTicks > 0) {
            p.noFallTicks--;
         }

         if (p.noFallTicks > 0 || p.effectTicks[15] > 0 || inWater || inLava || inWeb || endInFluid) {
            p.fallDistance = 0.0F;
         } else if (p.onGround) {
            if (p.fallDistance > 0.0F) {
               float safeFall = 3.0F;
               if (p.effectTicks[6] > 0) {
                  safeFall += p.effectAmp[6] + 1;
               }

               int fall = (int)Math.ceil(p.fallDistance - safeFall);
               if (fall > 0) {
                  float fallDmg = fall * (1.0F - Math.min(20.0F, p.protection + 3.0F * p.featherFalling) / 25.0F);
                  if (Combat.applyDamage(p, fallDmg)) {
                     int idx = p == s.players[0] ? 0 : 1;
                     s.events.add(new CombatEvent(0, idx, idx, fall > 4, 6));
                  }
               }
            }

            p.fallDistance = 0.0F;
         } else if (appliedY < 0.0) {
            p.fallDistance -= (float)appliedY;
         }

         if (xBlocked) {
            p.vx = 0.0;
         }

         if (yBlocked) {
            p.vy = 0.0;
         }

         if (zBlocked) {
            p.vz = 0.0;
         }

         if (inWeb) {
            p.vx = 0.0;
            p.vy = 0.0;
            p.vz = 0.0;
         }

         boolean waterMove = inWater;
         boolean lavaMove = inLava && !inWater;
         boolean fluidMove = waterMove || lavaMove;
         float horizFriction;
         if (waterMove) {
            horizFriction = p.sprinting ? 0.9F : 0.8F;
         } else if (lavaMove) {
            horizFriction = 0.5F;
         } else {
            horizFriction = friction;
         }

         if (!fluidMove) {
            p.vy = p.vy - (p.effectTicks[15] > 0 && p.vy <= 0.0 ? 0.01 : 0.08);
         }

         p.vx *= horizFriction;
         if (!fluidMove) {
            p.vy *= 0.98;
         } else if (lavaMove && inDeepLava) {
            p.vy *= 0.5;
         } else {
            p.vy *= 0.8;
         }

         p.vz *= horizFriction;
         boolean fireTickThisTick = false;
         if (waterMove) {
            if (!p.sprinting) {
               p.vy -= 0.005;
            }

            applyCurrentPush(s, arena, p, 0, 0.014);
            p.fireTicks = 0;
         } else if (lavaMove) {
            if (!inDeepLava && !p.sprinting) {
               p.vy -= 0.005;
            }

            p.vy -= 0.02;
            applyCurrentPush(s, arena, p, 1, 0.00233);
         }

         if (inLava) {
            p.fireTicks = Math.max(p.fireTicks, 300);
            if (s.tick % 10 == 0 && !fireImmune(p)) {
               if (Combat.applyDamage(p, Combat.reduceByDefenseFireArmored(p, 4.0))) {
                  fireHurt(s, p);
               }

               fireTickThisTick = true;
            }
         }

         if (inFire && !inWater) {
            p.fireTicks = Math.max(p.fireTicks, 160);
            if (s.tick % 10 == 0 && !fireImmune(p)) {
               if (Combat.applyDamage(p, Combat.reduceByDefenseFireArmored(p, 1.0))) {
                  fireHurt(s, p);
               }

               fireTickThisTick = true;
            }
         }

         if (p.fireTicks > 0) {
            if (!inLava && !fireImmune(p) && p.fireTicks % 20 == 0) {
               if (Combat.applyDamage(p, Combat.reduceByDefenseFire(p, 1.0))) {
                  fireHurt(s, p);
               }

               fireTickThisTick = true;
            }

            p.fireTicks--;
         }

         p.tookFireDamageThisTick = fireTickThisTick;
         double dist = Math.sqrt(appliedX * appliedX + appliedZ * appliedZ);
         tickHunger(p, sprinting && p.onGround, dist, jumped);
      }
   }

   private static void applyCurrentPush(GameState s, Arena arena, PlayerState p, int type, double strength) {
      double[] fv = Fluids.flowVector(s, arena, type, (int)Math.floor(p.x), (int)Math.floor(p.y), (int)Math.floor(p.z));
      if (fv != null) {
         double mag = Math.sqrt(fv[0] * fv[0] + fv[1] * fv[1]);
         if (mag > 1.0E-4) {
            p.vx = p.vx + fv[0] / mag * strength;
            p.vz = p.vz + fv[1] / mag * strength;
         }
      }
   }

   private static void fireHurt(GameState s, PlayerState p) {
      int slot = p == s.players[0] ? 0 : 1;
      s.events.add(new CombatEvent(0, slot, slot, false, 5));
   }

   private static boolean fireImmune(PlayerState p) {
      return p.effectTicks[16] > 0;
   }

   private static boolean cobwebInRange(GameState s, int x0, int y0, int z0, int x1, int y1, int z1) {
      if (s.cobwebs.isEmpty()) {
         return false;
      }

      for (int x = x0; x <= x1; x++) {
         for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
               if (s.cobwebs.containsKey(BlockStore.key(x, y, z))) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private static boolean fireInRange(GameState s, int x0, int y0, int z0, int x1, int y1, int z1) {
      if (s.fires.isEmpty()) {
         return false;
      }

      for (int x = x0; x <= x1; x++) {
         for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
               if (s.fires.containsKey(BlockStore.key(x, y, z))) {
                  return true;
               }
            }
         }
      }

      return false;
   }

   private static void tickFires(GameState s) {
      if (!s.fires.isEmpty()) {
         Iterator<Entry<Long, Integer>> it = s.fires.entrySet().iterator();

         while (it.hasNext()) {
            Entry<Long, Integer> e = it.next();
            int life = e.getValue() - 1;
            if (life <= 0) {
               it.remove();
            } else {
               e.setValue(life);
            }
         }
      }
   }

   private static double[] backOffFromEdge(Arena arena, BlockStore blocks, Set<Long> broken, Aabb box, double dx, double dz, double stepHeight) {
      int boundX = edgeBackOffSteps(dx);

      for (int i = 0; i < boundX && dx != 0.0 && noSolidIn(arena, blocks, broken, box.offset(dx, -stepHeight, 0.0)); i++) {
         dx = edgeBackOffStep(dx);
      }

      int boundZ = edgeBackOffSteps(dz);

      for (int i = 0; i < boundZ && dz != 0.0 && noSolidIn(arena, blocks, broken, box.offset(0.0, -stepHeight, dz)); i++) {
         dz = edgeBackOffStep(dz);
      }

      int boundBoth = Math.min(edgeBackOffSteps(dx), edgeBackOffSteps(dz));

      for (int i = 0; i < boundBoth && dx != 0.0 && dz != 0.0 && noSolidIn(arena, blocks, broken, box.offset(dx, -stepHeight, dz)); i++) {
         dx = edgeBackOffStep(dx);
         dz = edgeBackOffStep(dz);
      }

      return new double[]{dx, dz};
   }

   private static boolean fluidAtHead(GameState s, PlayerState p) {
      return s.fluids.isEmpty() ? false : Fluids.at(s, (int)Math.floor(p.x), (int)Math.floor(p.y + 0.9), (int)Math.floor(p.z)) != null;
   }

   private static boolean eyeCellIsWater(GameState s, PlayerState p, boolean swimming, boolean sneaking) {
      if (s.fluids.isEmpty()) {
         return false;
      }

      double eyeY = p.y + (swimming ? 0.4 : (sneaking ? 1.27 : 1.62));
      int ey = (int)Math.floor(eyeY);
      Integer v = Fluids.at(s, (int)Math.floor(p.x), ey, (int)Math.floor(p.z));
      return v != null && Fluids.type(v) == 0 ? ey + Fluids.heightOf(v) > eyeY : false;
   }

   private static boolean feetCellIsWater(GameState s, PlayerState p) {
      if (s.fluids.isEmpty()) {
         return false;
      }

      Integer v = Fluids.at(s, (int)Math.floor(p.x), (int)Math.floor(p.y), (int)Math.floor(p.z));
      return v != null && Fluids.type(v) == 0;
   }

   static int authorityWalkSteps(double dx, double dy, double dz) {
      double span = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
      if (!(span > 0.5)) {
         return 1;
      }

      double n = Math.ceil(span / 0.5);
      return !(n < 64.0) ? 64 : (int)n;
   }

   static boolean supportedBelow(Arena arena, BlockStore blocks, Set<Long> broken, double x, double y, double z) {
      Aabb probe = new Aabb(x - 0.3, y - 0.02, z - 0.3, x + 0.3, y, z + 0.3);
      return !noSolidIn(arena, blocks, broken, probe);
   }

   static Vec3 collideArena(Arena arena, Aabb box, double dx, double dy, double dz, boolean onGround, double stepHeight, BlockStore blocks, Set<Long> broken) {
      List<Aabb> near = new ArrayList<>();
      double m = 1.0;
      arena.collectNearSolids(
         near,
         Math.min(box.minX, box.minX + dx) - m,
         Math.min(box.minY, box.minY + dy) - m,
         Math.min(box.minZ, box.minZ + dz) - m,
         Math.max(box.maxX, box.maxX + dx) + m,
         Math.max(box.maxY, box.maxY + dy) + stepHeight + m,
         Math.max(box.maxZ, box.maxZ + dz) + m,
         broken
      );
      Aabb[] arenaNear = near.toArray(new Aabb[0]);
      return Collision.collide(box, dx, dy, dz, onGround, stepHeight, arenaNear, blocks.solids());
   }

   static final double EDGE_BACK_OFF_STEP = 0.05;

   static final int EDGE_BACK_OFF_STEP_CAP = 4096;

   static int edgeBackOffSteps(double d) {
      if (!Double.isFinite(d)) {
         return 0;
      }

      double n = Math.ceil(Math.abs(d) / EDGE_BACK_OFF_STEP) + 2.0;
      return !(n < (double) EDGE_BACK_OFF_STEP_CAP) ? EDGE_BACK_OFF_STEP_CAP : (int)n;
   }

   static double edgeBackOffStep(double d) {
      if (d < EDGE_BACK_OFF_STEP && d >= -EDGE_BACK_OFF_STEP) {
         return 0.0;
      } else {
         return d > 0.0 ? d - EDGE_BACK_OFF_STEP : d + EDGE_BACK_OFF_STEP;
      }
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
