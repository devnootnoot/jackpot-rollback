package me.nootnoot.sim.harness;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;

public final class InputLog {
    public final List<Input[]> frames = new ArrayList<>();

    public static InputLog generated(long seed, int ticks) {
        InputLog log = new InputLog();
        long s0 = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        long s1 = seed ^ 0xD1B54A32D192ED03L;
        if (s1 == 0) {
            s1 = 1;
        }
        float yaw0 = 0f;
        float yaw1 = 180f;
        for (int t = 0; t < ticks; t++) {
            s0 = xorshift(s0);
            s1 = xorshift(s1);
            yaw0 += yawDrift(s0);
            yaw1 += yawDrift(s1);
            log.frames.add(new Input[]{inputFrom(s0, yaw0), inputFrom(s1, yaw1)});
        }
        return log;
    }

    public static final int SIGHTLINE_ARENA_VOXEL = 0;
    public static final int MINE_ARENA_VOXEL = 100;
    public static final int MELEE_LANDED = 240;
    public static final int SHIELD_BLOCKS = 360;
    public static final int VICTIM_RUNS = 440;
    public static final int VICTIM_RETURNS = 500;
    public static final int ARCHERY = 700;
    public static final int SIGHTLINE_PLACED_BLOCK = 860;
    public static final int SIGHTLINE_COBWEB = 960;
    public static final int BUILD_AND_BREAK = 1060;
    public static final int SHULKER_CONTAINER = 1320;
    public static final int ENDER_CONTAINER = 1460;
    public static final int THROWABLES = 1580;
    public static final int CRYSTALS = 1760;
    public static final int ANCHORS = 1980;
    public static final int POST_RESET_SIGHTLINE = 2200;
    public static final int FLUIDS = 2320;
    public static final int COUNTED_MELEE = 2680;
    public static final int COUNTED_USE = 2900;
    public static final int COUNTED_THROWABLES = 3120;
    public static final int COUNTED_DROP = 3340;
    public static final int COUNTED_INVENTORY = 3560;
    public static final int COUNTED_SHARED = 3780;
    public static final int COUNTED_ANCHOR = 4000;
    public static final int COUNTED_CRYSTAL = 4220;
    public static final int FREE_RUNNING = 4440;

    private static final int PEARL_WINDOW = 120;
    private static final int CRYSTAL_SCRIPT_START = 60;

    private static final int ARCHERY_CYCLE = 32;
    private static final int ARCHERY_DRAW = 20;
    private static final int BUILD_CYCLE = 20;

    private static final int[] BUILD_CELL_X = {1, -1, 1, -1, 1, -1, 1, -1, 1, -1, 1, -1};
    private static final int[] BUILD_CELL_Z = {-4, -4, -3, -3, -2, -2, 2, 2, 3, 3, 4, 4};
    private static final int STRONG_SWING_PERIOD = 14;

    public static final int PRELUDE_TICKS = 2140;

    public static final int TAIL_TICKS = 520;

    public static final int LEGACY_END = PRELUDE_TICKS + FREE_RUNNING;

    public static final int GAP_RESET_A = 0;
    public static final int GAP_MINE = 200;
    public static final int GAP_BLOCK_SIGHT = 360;
    public static final int GAP_WEB_SIGHT = 500;
    public static final int GAP_OFFHAND = 660;
    public static final int GAP_CROSSBOW = 880;
    public static final int GAP_EGG = 1000;
    public static final int GAP_ROCKET = 1060;
    public static final int GAP_RESET_B = 1120;
    public static final int GAP_CRYSTAL_SHOT = 1320;
    public static final int GAP_RESET_C = 1420;
    public static final int GAP_MACE = 1620;
    public static final int GAP_TOTEM = 1860;
    public static final int GAP_RESET_D = 2100;
    public static final int GAP_FIRE = 2300;
    public static final int GAP_FLOOD = 2620;
    public static final int GAP_RESET_E = 2740;
    public static final int GAP_TICKS = 2940;

    private static final int GAP_RESET_KILL_TICKS = 160;

    public static final int GAP_END = LEGACY_END + GAP_TICKS;

    public static final int TAIL_END = GAP_END + TAIL_TICKS;

    public static final int FREE_RUN_TICKS = 1700;

    public static final int SCRIPT_END = TAIL_END + FREE_RUN_TICKS;

    public static InputLog scripted(long seed, int ticks) {
        InputLog log = new InputLog();
        long s0 = seed == 0 ? 0x9E3779B97F4A7C15L : seed;
        long s1 = seed ^ 0xD1B54A32D192ED03L;
        if (s1 == 0) {
            s1 = 1;
        }
        float yaw0 = 0f;
        float yaw1 = 180f;
        for (int t = 0; t < ticks; t++) {
            s0 = xorshift(s0);
            s1 = xorshift(s1);
            yaw0 += yawDrift(s0);
            yaw1 += yawDrift(s1);
            if (t < PRELUDE_TICKS) {
                log.frames.add(new Input[]{preludeAttacker(t), preludeVictim(t)});
            } else if (t < LEGACY_END) {
                int u = t - PRELUDE_TICKS;
                log.frames.add(new Input[]{attacker(u), victim(u)});
            } else if (t < GAP_END) {
                int u = t - LEGACY_END;
                log.frames.add(new Input[]{gapAttacker(u), gapVictim(u)});
            } else if (t < TAIL_END) {
                int u = t - GAP_END;
                log.frames.add(new Input[]{tailAttacker(u), tailVictim(u)});
            } else {
                log.frames.add(new Input[]{inputFrom(s0, yaw0), inputFrom(s1, yaw1)});
            }
        }
        return log;
    }

    private static Input attacker(int t) {
        boolean attack = fastSwing(t);
        float yaw = HarnessScenarios.ATTACKER_YAW;

        if (t < MINE_ARENA_VOXEL) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false).withMeleeHit(true);
        }
        if (t < MELEE_LANDED) {
            return act(yaw, 0f, HarnessScenarios.SLOT_PICKAXE, attack, false)
                    .withBlockAction(Input.BLOCK_BREAK, HarnessScenarios.PILLAR_X,
                            HarnessScenarios.SIGHTLINE_VOXEL_Y, HarnessScenarios.PILLAR_Z);
        }
        if (t < SHIELD_BLOCKS) {
            boolean strong = t < MELEE_LANDED + 70;
            return act(yaw, 0f, HarnessScenarios.SLOT_UTILITY,
                    strong ? strongSwing(t) : attack, false).withMeleeHit(true);
        }
        if (t < ARCHERY) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false).withMeleeHit(true);
        }
        if (t < SIGHTLINE_PLACED_BLOCK) {
            int local = t - ARCHERY;
            int shot = local / ARCHERY_CYCLE;
            int step = local % ARCHERY_CYCLE;
            float pitch = (shot & 1) == 1 ? -45f : 0f;
            if (step < ARCHERY_DRAW) {
                return act(yaw, pitch, HarnessScenarios.SLOT_BOW, attack, true);
            }
            Input released = act(yaw, pitch, HarnessScenarios.SLOT_BOW, attack, false);
            if (step >= ARCHERY_DRAW + 1 && step <= ARCHERY_DRAW + 5) {
                return released.withProjectileHit(shot);
            }
            return released;
        }
        if (t < SIGHTLINE_COBWEB) {
            int local = t - SIGHTLINE_PLACED_BLOCK;
            if (local == 0) {
                return move(HarnessScenarios.SLOT_UTILITY, attack)
                        .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_COBBLESTONE,
                                HarnessScenarios.SLOT_UTILITY);
            }
            if (local <= 6) {
                return sightlineBlock(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE);
            }
            if (local <= 69) {
                return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false).withMeleeHit(true);
            }
            if (local <= 95) {
                return sightlineBlock(attack, HarnessScenarios.SLOT_PICKAXE, Input.BLOCK_BREAK);
            }
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
        }
        if (t < BUILD_AND_BREAK) {
            int local = t - SIGHTLINE_COBWEB;
            if (local == 0 || local == 94) {
                return move(HarnessScenarios.SLOT_UTILITY, attack)
                        .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_COBWEB,
                                HarnessScenarios.SLOT_UTILITY);
            }
            if (local <= 4) {
                return sightlineBlock(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE);
            }
            if (local <= 49) {
                return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false).withMeleeHit(true);
            }
            if (local <= 53) {
                return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE,
                        HarnessScenarios.FEET_CELL_X, HarnessScenarios.FEET_CELL_Y,
                        HarnessScenarios.FEET_CELL_Z);
            }
            if (local <= 69) {
                return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
            }
            if (local <= 81) {
                return cell(attack, HarnessScenarios.SLOT_SWORD, Input.BLOCK_BREAK,
                        HarnessScenarios.FEET_CELL_X, HarnessScenarios.FEET_CELL_Y,
                        HarnessScenarios.FEET_CELL_Z);
            }
            if (local <= 93) {
                return sightlineBlock(attack, HarnessScenarios.SLOT_SWORD, Input.BLOCK_BREAK);
            }
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
        }
        if (t < SHULKER_CONTAINER) {
            int local = t - BUILD_AND_BREAK;
            int cellIndex = (local / BUILD_CYCLE) % BUILD_CELL_X.length;
            int step = local % BUILD_CYCLE;
            int bx = BUILD_CELL_X[cellIndex];
            int bz = BUILD_CELL_Z[cellIndex];
            if (step == 0) {
                return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE,
                        bx, HarnessScenarios.BUILD_Y, bz);
            }
            if (step <= 14) {
                return cell(attack, HarnessScenarios.SLOT_PICKAXE, Input.BLOCK_BREAK,
                        bx, HarnessScenarios.BUILD_Y, bz);
            }
            return act(yaw, 0f, HarnessScenarios.SLOT_PICKAXE, attack, false);
        }
        if (t < ENDER_CONTAINER) {
            return shulkerScript(t - SHULKER_CONTAINER, attack, yaw);
        }
        if (t < THROWABLES) {
            return enderChestScript(t - ENDER_CONTAINER, attack, yaw);
        }
        if (t < CRYSTALS) {
            int local = t - THROWABLES;
            Input in = act(yaw, 0f, HarnessScenarios.SLOT_UTILITY, attack, false)
                    .withOffhandUse(true)
                    .withOffhandUsePress(local % 6 == 0);
            if (local == 150 || local == 160) {
                return in.withSwapHands(true);
            }
            if (local == 170) {
                return in.withDrop(true, false);
            }
            return in;
        }
        if (t < ANCHORS) {
            return crystalScript(t - CRYSTALS, attack, yaw);
        }
        if (t < POST_RESET_SIGHTLINE) {
            return anchorScript(t - ANCHORS, attack, yaw);
        }
        if (t < FLUIDS) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false).withMeleeHit(true);
        }
        if (t < COUNTED_MELEE) {
            return fluidScript(t - FLUIDS, attack, yaw);
        }
        boolean pace = advancing(t - COUNTED_MELEE);
        if (t < COUNTED_USE) {
            return countedMelee(t - COUNTED_MELEE, pace);
        }
        if (t < COUNTED_THROWABLES) {
            return countedUse(t - COUNTED_USE, pace);
        }
        if (t < COUNTED_DROP) {
            return countedThrowables(t - COUNTED_THROWABLES, pace);
        }
        if (t < COUNTED_INVENTORY) {
            return countedDrop(t - COUNTED_DROP, pace);
        }
        if (t < COUNTED_SHARED) {
            return countedInventory(t - COUNTED_INVENTORY, pace);
        }
        if (t < COUNTED_ANCHOR) {
            return countedShared(t - COUNTED_SHARED, pace);
        }
        if (t < COUNTED_CRYSTAL) {
            return countedAnchor(t - COUNTED_ANCHOR, pace);
        }
        return countedCrystal(t - COUNTED_CRYSTAL, pace);
    }

    private static final int[] COUNT_CYCLE = {1, 2, 3, 4, 5, 6, 7, 7, 0, 0};

    private static final int COUNT_PERIOD = COUNT_CYCLE.length;

    private static final int PACE_PERIOD = 40;

    private static final int PACE_ADVANCE = 32;

    private static final int THROWABLE_PERIOD = 40;

    private static final int ANCHOR_PERIOD = 110;

    private static int cycled(int step) {
        return COUNT_CYCLE[Math.floorMod(step, COUNT_PERIOD)];
    }

    private static boolean advancing(int band) {
        return band % PACE_PERIOD < PACE_ADVANCE;
    }

    private static boolean attackHeld(int step) {
        return step != COUNT_PERIOD - 2;
    }

    private static Input clicked(Input in, int attack, int use, int drop, int inv, int swap) {
        return in.withClicks(new Clicks(attack, use, drop, inv, swap));
    }

    private static Input stride(boolean fwd, int slot, boolean attack, boolean use) {
        return new Input(fwd, !fwd, false, false, false, false, false, attack, use,
                HarnessScenarios.ATTACKER_YAW, 0f, slot);
    }

    private static Input strideCell(boolean fwd, int slot, int action, int x, int y, int z) {
        return stride(fwd, slot, true, false).withBlockAction(action, x, y, z);
    }

    private static Input countedMove(boolean fwd, int src, int dst) {
        return clicked(stride(fwd, HarnessScenarios.SLOT_UTILITY, true, false)
                .withInvAction(Input.INV_MOVE, src, dst), 0, 0, 0, 1, 0);
    }

    private static Input countedMelee(int local, boolean fwd) {
        int step = local % COUNT_PERIOD;
        return clicked(stride(fwd, HarnessScenarios.SLOT_SWORD, attackHeld(step), false)
                .withMeleeHit(true), cycled(step), 0, 0, 0, 0);
    }

    private static Input countedUse(int local, boolean fwd) {
        int step = local % COUNT_PERIOD;
        if (step == COUNT_PERIOD - 1) {
            return countedMove(fwd, HarnessScenarios.STASH_SNOWBALL, ItemDict.OFF_HAND);
        }
        Input off = clicked(stride(fwd, HarnessScenarios.SLOT_OBSIDIAN, attackHeld(step), false)
                .withOffhandUse(true)
                .withOffhandUsePress(step == 0 || step == 5), 0, cycled(step), 0, 0, 0);
        if (step == 3 || step == 7) {
            int cycle = local / COUNT_PERIOD;
            int cellIndex = cycle % BUILD_CELL_X.length;
            return off.withBlockAction(cycle % 2 == 0 ? Input.BLOCK_PLACE : Input.BLOCK_PLACE_OFFHAND,
                    BUILD_CELL_X[cellIndex], HarnessScenarios.BUILD_Y, BUILD_CELL_Z[cellIndex]);
        }
        return off;
    }

    private static Input countedThrowables(int local, boolean fwd) {
        int leg = local % THROWABLE_PERIOD;
        if (leg < 2) {
            return countedMove(fwd, ItemDict.OFF_HAND, HarnessScenarios.PARK_OFFHAND);
        }
        if (leg < 4) {
            return countedMove(fwd, HarnessScenarios.STASH_WIND_CHARGE, ItemDict.OFF_HAND);
        }
        if (leg >= 14 && leg < 16) {
            return countedMove(fwd, ItemDict.OFF_HAND, HarnessScenarios.PARK_WIND);
        }
        if (leg >= 16 && leg < 18) {
            return countedMove(fwd, HarnessScenarios.STASH_XP_BOTTLE, ItemDict.OFF_HAND);
        }
        if (leg >= 27 && leg < 29) {
            return countedMove(fwd, ItemDict.OFF_HAND, HarnessScenarios.PARK_XP);
        }
        if (leg >= 29 && leg < 31) {
            return countedMove(fwd, HarnessScenarios.STASH_SPLASH_POTION, ItemDict.OFF_HAND);
        }
        int step = local % COUNT_PERIOD;
        return clicked(stride(fwd, HarnessScenarios.SLOT_OBSIDIAN, attackHeld(step), false)
                .withOffhandUse(true)
                .withOffhandUsePress(step == 2 || step == 6), 0, cycled(step), 0, 0, 0);
    }

    private static Input countedDrop(int local, boolean fwd) {
        int step = local % COUNT_PERIOD;
        Input base = stride(fwd, HarnessScenarios.SLOT_OBSIDIAN, attackHeld(step), false);
        if (step == 0) {
            return clicked(base.withDrop(true, false), 0, 0, 0, 0, 0);
        }
        if (step <= 4) {
            return clicked(base.withDrop(true, step == 4), 0, 0, cycled(step), 0, 0);
        }
        if (step <= 7) {
            return clicked(base.withInvAction(Input.INV_DROP_ONE,
                    HarnessScenarios.STASH_COBBLESTONE, 0), 0, 0, 0, cycled(step), 0);
        }
        if (step == 8) {
            return clicked(base.withDrop(true, false)
                            .withInvAction(Input.INV_DROP_STACK,
                                    HarnessScenarios.STASH_COBBLESTONE, 0),
                    0, 0, 3, 3, 0);
        }
        return base;
    }

    private static Input countedInventory(int local, boolean fwd) {
        int step = local % COUNT_PERIOD;
        Input base = stride(fwd, HarnessScenarios.SLOT_SWORD, attackHeld(step), false);
        if (step == 0) {
            return clicked(base.withSwapHands(true), 0, 0, 0, 0, 0);
        }
        if (step <= 4) {
            return clicked(base.withSwapHands(true), 0, 0, 0, 0, cycled(step));
        }
        if (step <= 7) {
            return clicked(base.withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_COBBLESTONE,
                    HarnessScenarios.SLOT_UTILITY), 0, 0, 0, cycled(step), 0);
        }
        if (step == 8) {
            return clicked(base.withSwapHands(true)
                            .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_COBBLESTONE,
                                    HarnessScenarios.SLOT_UTILITY), 0, 0, 0, 2, 2);
        }
        return base;
    }

    private static Input countedShared(int local, boolean fwd) {
        int step = local % COUNT_PERIOD;
        int n = cycled(step);
        if (step < 5 || step >= COUNT_PERIOD - 2) {
            Input mine = stride(fwd, HarnessScenarios.SLOT_PICKAXE, attackHeld(step), false)
                    .withMeleeHit(true)
                    .withDrop(true, false)
                    .withBlockAction(Input.BLOCK_BREAK, HarnessScenarios.PILLAR_X,
                            HarnessScenarios.SIGHTLINE_VOXEL_Y, HarnessScenarios.PILLAR_Z);
            return clicked(mine, n, n, n, n, 0);
        }
        int cellIndex = (local / COUNT_PERIOD) % BUILD_CELL_X.length;
        Input build = stride(fwd, HarnessScenarios.SLOT_OBSIDIAN, attackHeld(step), false)
                .withMeleeHit(true)
                .withOffhandUse(true)
                .withOffhandUsePress(step == 5)
                .withSwapHands(step == COUNT_PERIOD - 2)
                .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_COBBLESTONE,
                        HarnessScenarios.SLOT_UTILITY)
                .withCrystalHit(true, HarnessScenarios.ARENA_CRYSTAL_BASE_X,
                        HarnessScenarios.ARENA_CRYSTAL_BASE_Y,
                        HarnessScenarios.ARENA_CRYSTAL_BASE_Z)
                .withBlockAction(Input.BLOCK_PLACE, BUILD_CELL_X[cellIndex],
                        HarnessScenarios.BUILD_Y, BUILD_CELL_Z[cellIndex]);
        return clicked(build, n, n, 0, n, n);
    }

    private static Input countedAnchor(int band, boolean fwd) {
        int local = band % ANCHOR_PERIOD;
        if (local < 3) {
            return countedMove(fwd, HarnessScenarios.STASH_ANCHOR, HarnessScenarios.SLOT_UTILITY);
        }
        if (local < 6) {
            return countedMove(fwd, HarnessScenarios.STASH_GLOWSTONE, HarnessScenarios.SLOT_BOW);
        }
        if (local >= 8 && local <= 13) {
            return clicked(strideCell(fwd, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE_ANCHOR,
                    HarnessScenarios.ANCHOR_X, HarnessScenarios.ANCHOR_Y,
                    HarnessScenarios.ANCHOR_Z), 0, 2, 0, 0, 0);
        }
        if (local == 16 || local == 24) {
            return clicked(strideCell(fwd, HarnessScenarios.SLOT_BOW, Input.BLOCK_CHARGE_ANCHOR,
                    HarnessScenarios.ANCHOR_X, HarnessScenarios.ANCHOR_Y,
                    HarnessScenarios.ANCHOR_Z), 0, Clicks.MAX, 0, 0, 0);
        }
        if (local >= 18 && local <= 22) {
            return clicked(strideCell(fwd, HarnessScenarios.SLOT_BOW, Input.BLOCK_CHARGE_ANCHOR,
                    HarnessScenarios.ANCHOR_X, HarnessScenarios.ANCHOR_Y,
                    HarnessScenarios.ANCHOR_Z), 0, cycled(local), 0, 0, 0);
        }
        if (local >= 40 && local < 43) {
            return countedMove(fwd, HarnessScenarios.STASH_SPARE_ANCHOR,
                    HarnessScenarios.SLOT_UTILITY);
        }
        if (local >= 43 && local < 46) {
            return countedMove(fwd, HarnessScenarios.STASH_SPARE_GLOWSTONE,
                    HarnessScenarios.SLOT_BOW);
        }
        if (local >= 48 && local <= 53) {
            return clicked(strideCell(fwd, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE_ANCHOR,
                    FAR_ANCHOR_X, FAR_ANCHOR_Y, FAR_ANCHOR_Z), 0, 2, 0, 0, 0);
        }
        if (local >= 56 && local <= 60) {
            return clicked(strideCell(fwd, HarnessScenarios.SLOT_BOW, Input.BLOCK_CHARGE_ANCHOR,
                    FAR_ANCHOR_X, FAR_ANCHOR_Y, FAR_ANCHOR_Z), 0, Clicks.MAX, 0, 0, 0);
        }
        if (local >= 70 && local <= 75) {
            return clicked(strideCell(fwd, HarnessScenarios.SLOT_BOW, Input.BLOCK_DETONATE_ANCHOR,
                    FAR_ANCHOR_X, FAR_ANCHOR_Y, FAR_ANCHOR_Z), 0, 2, 0, 0, 0);
        }
        int step = band % COUNT_PERIOD;
        return clicked(stride(fwd, HarnessScenarios.SLOT_SWORD, attackHeld(step), false)
                .withMeleeHit(true), cycled(step), 0, 0, 0, 0);
    }

    private static Input countedCrystal(int local, boolean fwd) {
        if (local % ANCHOR_PERIOD <= 6) {
            return clicked(strideCell(fwd, HarnessScenarios.SLOT_OBSIDIAN, Input.BLOCK_PLACE,
                    HarnessScenarios.PLACED_CRYSTAL_BASE_X, HarnessScenarios.PLACED_CRYSTAL_BASE_Y,
                    HarnessScenarios.PLACED_CRYSTAL_BASE_Z), 0, 2, 0, 0, 0);
        }
        int step = local % COUNT_PERIOD;
        int n = cycled(step);
        if (step == 6) {
            return clicked(strideCell(fwd, HarnessScenarios.SLOT_END_CRYSTAL,
                    Input.BLOCK_HIT_CRYSTAL, HarnessScenarios.ARENA_CRYSTAL_BASE_X,
                    HarnessScenarios.ARENA_CRYSTAL_BASE_Y,
                    HarnessScenarios.ARENA_CRYSTAL_BASE_Z), n, 0, 0, 0, 0);
        }
        boolean arenaBase = (local / COUNT_PERIOD) % 2 == 0;
        int baseX = arenaBase
                ? HarnessScenarios.ARENA_CRYSTAL_BASE_X : HarnessScenarios.PLACED_CRYSTAL_BASE_X;
        int baseZ = arenaBase
                ? HarnessScenarios.ARENA_CRYSTAL_BASE_Z : HarnessScenarios.PLACED_CRYSTAL_BASE_Z;
        Input combo = stride(fwd, HarnessScenarios.SLOT_END_CRYSTAL, attackHeld(step), false)
                .withBlockAction(Input.BLOCK_PLACE_CRYSTAL, baseX,
                        HarnessScenarios.ARENA_CRYSTAL_BASE_Y, baseZ)
                .withCrystalHit(true, baseX, HarnessScenarios.ARENA_CRYSTAL_BASE_Y, baseZ);
        return clicked(combo, n, n, 0, 0, 0);
    }

    private static Input shulkerScript(int local, boolean attack, float yaw) {
        if (local == 0 || local == 121) {
            return move(HarnessScenarios.SLOT_UTILITY, attack)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_SHULKER,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (local <= 6) {
            return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE,
                    HarnessScenarios.SHULKER_X, HarnessScenarios.SHULKER_Y, HarnessScenarios.SHULKER_Z);
        }
        if (local <= 10 || (local >= 45 && local <= 48)) {
            return cell(attack, HarnessScenarios.SLOT_SWORD, Input.BLOCK_OPEN_CONTAINER,
                    HarnessScenarios.SHULKER_X, HarnessScenarios.SHULKER_Y, HarnessScenarios.SHULKER_Z);
        }
        if (local <= 25) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false)
                    .withInvAction(Input.INV_CONTAINER_TAKE, 0, HarnessScenarios.STASH_SCRATCH);
        }
        if (local <= 36) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false)
                    .withInvAction(Input.INV_CONTAINER_PUT, HarnessScenarios.STASH_SCRATCH, 2);
        }
        if (local == 37) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
        }
        if (local <= 40) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false)
                    .withInvAction(Input.INV_QUICK_MOVE, HarnessScenarios.STASH_SCRATCH, 0);
        }
        if (local <= 44) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false)
                    .withBlockAction(Input.BLOCK_CLOSE_CONTAINER, 0, 0, 0);
        }
        if (local <= 120) {
            return cell(attack, HarnessScenarios.SLOT_SWORD, Input.BLOCK_BREAK,
                    HarnessScenarios.SHULKER_X, HarnessScenarios.SHULKER_Y, HarnessScenarios.SHULKER_Z);
        }
        return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
    }

    private static Input enderChestScript(int local, boolean attack, float yaw) {
        if (local == 0 || local == 111) {
            return move(HarnessScenarios.SLOT_UTILITY, attack)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_ENDER_CHEST,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (local <= 6) {
            return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE,
                    HarnessScenarios.ENDER_CHEST_X, HarnessScenarios.ENDER_CHEST_Y,
                    HarnessScenarios.ENDER_CHEST_Z);
        }
        if (local <= 10) {
            return cell(attack, HarnessScenarios.SLOT_SWORD, Input.BLOCK_OPEN_CONTAINER,
                    HarnessScenarios.ENDER_CHEST_X, HarnessScenarios.ENDER_CHEST_Y,
                    HarnessScenarios.ENDER_CHEST_Z);
        }
        if (local <= 25) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false)
                    .withInvAction(Input.INV_CONTAINER_PUT, HarnessScenarios.SLOT_ARROWS, 0);
        }
        if (local <= 36) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false)
                    .withInvAction(Input.INV_CONTAINER_TAKE, 0, HarnessScenarios.SLOT_ARROWS);
        }
        if (local == 37) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
        }
        if (local <= 40) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false)
                    .withInvAction(Input.INV_QUICK_MOVE, Input.cellAddr(0), 0);
        }
        if (local <= 44) {
            return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false)
                    .withBlockAction(Input.BLOCK_CLOSE_CONTAINER, 0, 0, 0);
        }
        if (local <= 110) {
            return cell(attack, HarnessScenarios.SLOT_SWORD, Input.BLOCK_BREAK,
                    HarnessScenarios.ENDER_CHEST_X, HarnessScenarios.ENDER_CHEST_Y,
                    HarnessScenarios.ENDER_CHEST_Z);
        }
        return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
    }

    private static Input crystalScript(int local, boolean attack, float yaw) {
        int step = local - CRYSTAL_SCRIPT_START;
        if (step >= 0 && step <= 9) {
            return cell(attack, HarnessScenarios.SLOT_OBSIDIAN, Input.BLOCK_PLACE,
                    HarnessScenarios.PLACED_CRYSTAL_BASE_X, HarnessScenarios.PLACED_CRYSTAL_BASE_Y,
                    HarnessScenarios.PLACED_CRYSTAL_BASE_Z);
        }
        if (step >= 10 && step <= 19) {
            return cell(attack, HarnessScenarios.SLOT_END_CRYSTAL, Input.BLOCK_PLACE_CRYSTAL,
                    HarnessScenarios.PLACED_CRYSTAL_BASE_X, HarnessScenarios.PLACED_CRYSTAL_BASE_Y,
                    HarnessScenarios.PLACED_CRYSTAL_BASE_Z);
        }
        if (step >= 20 && step <= 29) {
            return cell(attack, HarnessScenarios.SLOT_END_CRYSTAL, Input.BLOCK_PLACE_CRYSTAL,
                    HarnessScenarios.ARENA_CRYSTAL_BASE_X, HarnessScenarios.ARENA_CRYSTAL_BASE_Y,
                    HarnessScenarios.ARENA_CRYSTAL_BASE_Z);
        }
        if (step >= 30 && step <= 39) {
            return cell(attack, HarnessScenarios.SLOT_END_CRYSTAL, Input.BLOCK_HIT_CRYSTAL,
                    HarnessScenarios.ARENA_CRYSTAL_BASE_X, HarnessScenarios.ARENA_CRYSTAL_BASE_Y,
                    HarnessScenarios.ARENA_CRYSTAL_BASE_Z);
        }
        return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
    }

    public static final int FAR_ANCHOR_X = 3;
    public static final int FAR_ANCHOR_Y = 64;
    public static final int FAR_ANCHOR_Z = 2;

    private static Input anchorScript(int local, boolean attack, float yaw) {
        if (local == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, attack)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_ANCHOR,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (local == 1) {
            return move(HarnessScenarios.SLOT_UTILITY, attack)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_GLOWSTONE,
                            HarnessScenarios.SLOT_BOW);
        }
        if (local >= 5 && local <= 14) {
            return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE_ANCHOR,
                    HarnessScenarios.ANCHOR_X, HarnessScenarios.ANCHOR_Y, HarnessScenarios.ANCHOR_Z);
        }
        if (local >= 15 && local <= 25) {
            return cell(attack, HarnessScenarios.SLOT_BOW, Input.BLOCK_CHARGE_ANCHOR,
                    HarnessScenarios.ANCHOR_X, HarnessScenarios.ANCHOR_Y, HarnessScenarios.ANCHOR_Z);
        }
        if (local == 80) {
            return move(HarnessScenarios.SLOT_UTILITY, attack)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_SPARE_ANCHOR,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (local == 81) {
            return move(HarnessScenarios.SLOT_UTILITY, attack)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_SPARE_GLOWSTONE,
                            HarnessScenarios.SLOT_BOW);
        }
        if (local >= 85 && local <= 94) {
            return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE_ANCHOR,
                    FAR_ANCHOR_X, FAR_ANCHOR_Y, FAR_ANCHOR_Z);
        }
        if (local >= 95 && local <= 97) {
            return cell(attack, HarnessScenarios.SLOT_BOW, Input.BLOCK_CHARGE_ANCHOR,
                    FAR_ANCHOR_X, FAR_ANCHOR_Y, FAR_ANCHOR_Z);
        }
        if (local >= 100 && local <= 109) {
            return cell(attack, HarnessScenarios.SLOT_BOW, Input.BLOCK_DETONATE_ANCHOR,
                    FAR_ANCHOR_X, FAR_ANCHOR_Y, FAR_ANCHOR_Z);
        }
        return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
    }

    private static Input fluidScript(int local, boolean attack, float yaw) {
        if (local <= 9) {
            return cell(attack, HarnessScenarios.SLOT_LAVA_BUCKET, Input.BLOCK_PLACE_LAVA,
                    HarnessScenarios.FEET_CELL_X, HarnessScenarios.FEET_CELL_Y, HarnessScenarios.FEET_CELL_Z);
        }
        if (local >= 40 && local <= 49) {
            return cell(attack, HarnessScenarios.SLOT_LAVA_BUCKET, Input.BLOCK_PICKUP_FLUID,
                    HarnessScenarios.FEET_CELL_X, HarnessScenarios.FEET_CELL_Y, HarnessScenarios.FEET_CELL_Z);
        }
        if (local == 50) {
            return move(HarnessScenarios.SLOT_UTILITY, attack)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_WATER_BUCKET,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (local >= 51 && local <= 60) {
            return cell(attack, HarnessScenarios.SLOT_WATER_BUCKET, Input.BLOCK_PLACE_WATER,
                    HarnessScenarios.WATER_NEAR_X, HarnessScenarios.WATER_NEAR_Y,
                    HarnessScenarios.WATER_NEAR_Z);
        }
        if (local >= 61 && local <= 70) {
            return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE_WATER,
                    HarnessScenarios.WATER_FAR_X, HarnessScenarios.WATER_FAR_Y,
                    HarnessScenarios.WATER_FAR_Z);
        }
        if (local >= 71 && local <= 250) {
            return new Input(true, false, false, false, false, true, false, attack, false,
                    yaw, 0f, HarnessScenarios.SLOT_SWORD);
        }
        if (local >= 251 && local <= 265) {
            return cell(attack, HarnessScenarios.SLOT_WATER_BUCKET, Input.BLOCK_PICKUP_FLUID,
                    HarnessScenarios.WATER_NEAR_X, HarnessScenarios.WATER_NEAR_Y,
                    HarnessScenarios.WATER_NEAR_Z);
        }
        if (local >= 266 && local <= 280) {
            return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PICKUP_FLUID,
                    HarnessScenarios.WATER_FAR_X, HarnessScenarios.WATER_FAR_Y,
                    HarnessScenarios.WATER_FAR_Z);
        }
        return act(yaw, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
    }

    private static Input victim(int t) {
        boolean attack = fastSwing(t);

        if (t < MELEE_LANDED) {
            boolean eating = t >= MINE_ARENA_VOXEL + 80;
            return walk(eating ? HarnessScenarios.VICTIM_SLOT_APPLES
                    : HarnessScenarios.VICTIM_SLOT_SWORD, attack, eating, !eating, false);
        }
        if (t < SHIELD_BLOCKS) {
            return walk(HarnessScenarios.VICTIM_SLOT_SWORD, attack, false, true, false);
        }
        if (t < VICTIM_RUNS) {
            return walk(HarnessScenarios.VICTIM_SLOT_SHIELD, attack, true, false, false);
        }
        if (t < VICTIM_RETURNS) {
            return new Input(true, false, false, false, false, true, false, attack, false,
                    HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.VICTIM_SLOT_SWORD);
        }
        if (t >= THROWABLES && t < THROWABLES + PEARL_WINDOW) {
            int local = t - THROWABLES;
            return act(HarnessScenarios.ATTACKER_YAW, -10f, HarnessScenarios.VICTIM_SLOT_PEARLS,
                    attack, true).withUsePress(local % 25 == 0);
        }
        if (t >= COUNTED_MELEE) {
            return countedVictim(t - COUNTED_MELEE);
        }
        boolean sneaking = t >= POST_RESET_SIGHTLINE && t < FLUIDS;
        return walk(HarnessScenarios.VICTIM_SLOT_SWORD, attack, false, !sneaking, sneaking);
    }

    private static final int[] VICTIM_USE_SLOT = {
            HarnessScenarios.VICTIM_SLOT_PEARLS, HarnessScenarios.VICTIM_SLOT_PEARLS,
            HarnessScenarios.VICTIM_SLOT_WIND, HarnessScenarios.VICTIM_SLOT_WIND,
            HarnessScenarios.VICTIM_SLOT_XP, HarnessScenarios.VICTIM_SLOT_XP,
            HarnessScenarios.VICTIM_SLOT_SPLASH, HarnessScenarios.VICTIM_SLOT_SPLASH,
            HarnessScenarios.VICTIM_SLOT_SNOWBALL, HarnessScenarios.VICTIM_SLOT_SNOWBALL};

    private static Input countedVictim(int local) {
        int step = local % COUNT_PERIOD;
        int n = cycled(step);
        Input in = walk(VICTIM_USE_SLOT[step], attackHeld(step), true, false, false)
                .withMeleeHit(true)
                .withUsePress(step % 2 == 0)
                .withOffhandUse(true)
                .withOffhandUsePress(step % 2 == 1);
        int cycle = (local / COUNT_PERIOD) % 4;
        if (step == 0 && (cycle == 1 || cycle == 3)) {
            in = in.withSwapHands(true);
        } else if (step == 3) {
            if (cycle == 0) {
                in = in.withInvAction(Input.INV_MOVE, HarnessScenarios.VICTIM_SLOT_SWORD,
                        HarnessScenarios.VICTIM_SLOT_COBBLESTONE);
            } else if (cycle == 2) {
                in = in.withDrop(true, false);
            } else if (cycle == 3) {
                in = in.withInvAction(Input.INV_DROP_ONE,
                        HarnessScenarios.VICTIM_SLOT_COBBLESTONE, 0);
            }
        }
        return clicked(in, n, n, n, n, n);
    }

    public static final int PRE_CLEAR = 0;
    public static final int PRE_SELF_ARROW = 100;
    public static final int PRE_ARCHERY = 400;
    public static final int PRE_SPEAR = 620;
    public static final int PRE_REACH = 880;
    public static final int PRE_REWIND = 1040;
    public static final int PRE_OCCLUSION = 1260;
    public static final int PRE_THROWABLES = 1400;
    public static final int PRE_CURSOR = 1680;
    public static final int PRE_FLOOD = 1800;
    public static final int PRE_KILL = 2040;

    private static final int SPEAR_RETREAT_START = 20;
    private static final int SPEAR_RETREAT_END = 120;

    private static final int REACH_SWAP_PERIOD = 20;

    private static final int REWIND_PERIOD = 40;
    private static final int REWIND_AWAY = 20;

    private static final int ARCHERY_SHOT_PERIOD = 55;
    private static final int ARCHERY_DRAW_TICKS = 25;
    private static final int ARCHERY_STRAFE = 5;

    public static final int ARROW_CLAIM_DELAY = 3;

    private static final int SELF_SHOT_PERIOD = 140;

    public static final int SELF_ARROW_CLAIM_DELAY = 103;

    private static final int SELF_SHOTS = (PRE_ARCHERY - PRE_SELF_ARROW) / SELF_SHOT_PERIOD;

    private static final float[] THROW_PITCH = {-20f, -20f, 85f, -20f, -20f, -20f, -20f};

    private static final int THROW_LEG = 40;

    private static final int[] THROW_SOURCE = {
            HarnessScenarios.STASH_SNOWBALL, HarnessScenarios.STASH_EGG,
            HarnessScenarios.STASH_PEARL, HarnessScenarios.STASH_WIND_CHARGE,
            HarnessScenarios.STASH_XP_BOTTLE, HarnessScenarios.STASH_SPLASH_POTION,
            HarnessScenarios.STASH_SNOWBALL};

    private static final int[] THROW_PARK = {
            HarnessScenarios.PARK_OFFHAND, HarnessScenarios.PARK_WIND,
            HarnessScenarios.PARK_XP, HarnessScenarios.PARK_CHESTPLATE,
            HarnessScenarios.PARK_SPARE_A, HarnessScenarios.PARK_SPARE_B,
            HarnessScenarios.PARK_OFFHAND};

    private static final int FLOOD_SWIM_PERIOD = 30;

    private static final int KILL_PERIOD = 20;

    private static final int TAIL_FLIGHT_PERIOD = 100;

    private static Input preludeAttacker(int t) {
        if (t < PRE_SELF_ARROW) {
            return clearScript(t);
        }
        if (t < PRE_ARCHERY) {
            return selfArrowScript(t - PRE_SELF_ARROW);
        }
        if (t < PRE_SPEAR) {
            return archeryScript(t - PRE_ARCHERY);
        }
        if (t < PRE_REACH) {
            return spearScript(t - PRE_SPEAR);
        }
        if (t < PRE_REWIND) {
            return reachScript(t - PRE_REACH);
        }
        if (t < PRE_OCCLUSION) {
            return rewindScript(t - PRE_REWIND);
        }
        if (t < PRE_THROWABLES) {
            return occlusionScript(t - PRE_OCCLUSION);
        }
        if (t < PRE_CURSOR) {
            return throwableScript(t - PRE_THROWABLES);
        }
        if (t < PRE_FLOOD) {
            return cursorScript(t - PRE_CURSOR);
        }
        if (t < PRE_KILL) {
            return floodScript(t - PRE_FLOOD);
        }
        return killScript(t - PRE_KILL);
    }

    private static Input clearScript(int t) {
        return cell(fastSwing(t), HarnessScenarios.SLOT_PICKAXE, Input.BLOCK_BREAK,
                HarnessScenarios.PILLAR_X, HarnessScenarios.SIGHTLINE_VOXEL_Y,
                HarnessScenarios.PILLAR_Z);
    }

    private static Input spearScript(int t) {
        if (t == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, true)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_SPEAR,
                            HarnessScenarios.SLOT_UTILITY);
        }
        return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_UTILITY,
                fastSwing(t), false).withMeleeHit(true);
    }

    private static Input reachScript(int r) {
        int slot = (r / REACH_SWAP_PERIOD) % 2 == 0
                ? HarnessScenarios.SLOT_UTILITY : HarnessScenarios.SLOT_SWORD;
        return act(HarnessScenarios.ATTACKER_YAW, 0f, slot, fastSwing(r), false).withMeleeHit(true);
    }

    private static Input rewindScript(int r) {
        return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD,
                fastSwing(r), false).withMeleeHit(true);
    }

    private static Input occlusionScript(int o) {
        boolean attack = fastSwing(o);
        if (o == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, attack)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.SLOT_UTILITY,
                            HarnessScenarios.STASH_SPEAR);
        }
        if (o == 2) {
            return move(HarnessScenarios.SLOT_UTILITY, attack)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_COBBLESTONE,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (o >= 4 && o <= 10) {
            return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE,
                    HarnessScenarios.SIGHT_BLOCK_X, HarnessScenarios.SIGHT_BLOCK_Y,
                    HarnessScenarios.SIGHT_BLOCK_Z);
        }
        if (o <= 60) {
            return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD,
                    attack, false).withMeleeHit(true);
        }
        if (o <= 95) {
            return cell(attack, HarnessScenarios.SLOT_PICKAXE, Input.BLOCK_BREAK,
                    HarnessScenarios.SIGHT_BLOCK_X, HarnessScenarios.SIGHT_BLOCK_Y,
                    HarnessScenarios.SIGHT_BLOCK_Z);
        }
        if (o <= 115) {
            return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD,
                    attack, false).withMeleeHit(true);
        }
        if (o <= 125) {
            return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE,
                    HarnessScenarios.BEHIND_POST_X, HarnessScenarios.BEHIND_POST_Y,
                    HarnessScenarios.BEHIND_POST_Z);
        }
        if (o <= 135) {
            return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE,
                    HarnessScenarios.DECOR_X, HarnessScenarios.DECOR_Y, HarnessScenarios.DECOR_Z);
        }
        return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
    }

    private static Input archeryScript(int a) {
        int step = a % ARCHERY_SHOT_PERIOD;
        if (step < ARCHERY_DRAW_TICKS) {
            return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_BOW, false, true);
        }
        Input released = act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_BOW,
                false, false);
        if (step == ARCHERY_DRAW_TICKS + ARROW_CLAIM_DELAY) {
            return released.withProjectileHit(SELF_SHOTS + a / ARCHERY_SHOT_PERIOD);
        }
        return released;
    }

    private static Input selfArrowScript(int s) {
        int step = s % SELF_SHOT_PERIOD;
        if (step < ARCHERY_DRAW_TICKS) {
            return act(HarnessScenarios.ATTACKER_YAW, -90f, HarnessScenarios.SLOT_BOW, false, true);
        }
        Input released = act(HarnessScenarios.ATTACKER_YAW, -90f, HarnessScenarios.SLOT_BOW,
                false, false);
        if (step == ARCHERY_DRAW_TICKS + SELF_ARROW_CLAIM_DELAY) {
            return released.withProjectileHit(
                    Input.PROJECTILE_HIT_SELF | (s / SELF_SHOT_PERIOD));
        }
        return released;
    }

    private static Input throwableScript(int w) {
        int leg = w / THROW_LEG;
        int step = w % THROW_LEG;
        int held = HarnessScenarios.SLOT_OBSIDIAN;
        if (leg >= THROW_SOURCE.length) {
            return act(HarnessScenarios.ATTACKER_YAW, 0f, held, false, false);
        }
        if (step == 0) {
            return move(held, false)
                    .withInvAction(Input.INV_MOVE, ItemDict.OFF_HAND, THROW_PARK[leg]);
        }
        if (step == 2) {
            return move(held, false)
                    .withInvAction(Input.INV_MOVE, THROW_SOURCE[leg], ItemDict.OFF_HAND);
        }
        if (step < 4) {
            return act(HarnessScenarios.ATTACKER_YAW, 0f, held, false, false);
        }
        return act(HarnessScenarios.ATTACKER_YAW, THROW_PITCH[leg], held, false, false)
                .withOffhandUse(true)
                .withOffhandUsePress((step - 4) % 6 == 0);
    }

    private static Input cursorScript(int c) {
        Input idle = act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD,
                false, false);
        return switch (c) {
            case 0 -> idle.withInvAction(Input.INV_PICKUP, HarnessScenarios.STASH_COBBLESTONE, 0);
            case 4 -> idle.withInvAction(Input.INV_PICKUP, HarnessScenarios.PARK_SPARE_A, 0);
            case 8 -> idle.withInvAction(Input.INV_PICKUP_HALF, HarnessScenarios.STASH_GLOWSTONE, 0);
            case 12 -> idle.withInvAction(Input.INV_PICKUP_HALF, HarnessScenarios.PARK_SPARE_B, 0);
            case 16 -> idle.withInvAction(Input.INV_SWAP_SLOT, HarnessScenarios.STASH_COBWEB,
                    HarnessScenarios.SLOT_UTILITY);
            case 20 -> idle.withInvAction(Input.INV_PICKUP, HarnessScenarios.STASH_GLOWSTONE, 0);
            case 24 -> idle.withDrop(true, false)
                    .withInvAction(Input.INV_DROP_CURSOR_ONE, HarnessScenarios.PARK_SPARE_A, 0);
            case 28 -> idle.withDrop(true, true)
                    .withInvAction(Input.INV_DROP_CURSOR_ALL, HarnessScenarios.PARK_SPARE_A, 0);
            case 32 -> idle.withInvAction(Input.INV_PICKUP, HarnessScenarios.STASH_GLOWSTONE, 0);
            case 36 -> idle.withInvAction(Input.INV_CURSOR_RESOLVE, 0, 0);
            case 40 -> idle.withInvAction(Input.INV_PICKUP_ALL, HarnessScenarios.STASH_COBBLESTONE, 0);
            case 44 -> idle.withInvAction(Input.INV_CURSOR_RESOLVE, 0, 0);
            default -> idle;
        };
    }

    private static Input floodScript(int f) {
        boolean attack = fastSwing(f);
        if (f <= 6) {
            return cell(attack, HarnessScenarios.SLOT_WATER_BUCKET, Input.BLOCK_PLACE_WATER,
                    HarnessScenarios.FEET_CELL_X, HarnessScenarios.FEET_CELL_Y,
                    HarnessScenarios.FEET_CELL_Z);
        }
        if (f == 8) {
            return move(HarnessScenarios.SLOT_UTILITY, attack)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_WATER_BUCKET,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (f >= 10 && f <= 16) {
            return cell(attack, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE_WATER,
                    HarnessScenarios.WATER_NEAR_X, HarnessScenarios.WATER_NEAR_Y,
                    HarnessScenarios.WATER_NEAR_Z);
        }
        if (f >= 18 && f < 150) {
            boolean out = ((f - 18) / FLOOD_SWIM_PERIOD) % 2 == 0;
            float yaw = out ? HarnessScenarios.ATTACKER_YAW : HarnessScenarios.VICTIM_YAW;
            return new Input(true, false, false, false, false, true, false, attack, false,
                    yaw, 0f, HarnessScenarios.SLOT_SWORD);
        }
        if (f >= 152 && f <= 158) {
            return cell(attack, HarnessScenarios.SLOT_LAVA_BUCKET, Input.BLOCK_PLACE_LAVA,
                    HarnessScenarios.LAVA_CONTACT_X, HarnessScenarios.LAVA_CONTACT_Y,
                    HarnessScenarios.LAVA_CONTACT_Z);
        }
        if (f >= 180 && f <= 190) {
            return cell(attack, HarnessScenarios.SLOT_WATER_BUCKET, Input.BLOCK_PICKUP_FLUID,
                    HarnessScenarios.FEET_CELL_X, HarnessScenarios.FEET_CELL_Y,
                    HarnessScenarios.FEET_CELL_Z);
        }
        if (f >= 200 && f <= 210) {
            return cell(attack, HarnessScenarios.SLOT_LAVA_BUCKET, Input.BLOCK_PICKUP_FLUID,
                    HarnessScenarios.WATER_NEAR_X, HarnessScenarios.WATER_NEAR_Y,
                    HarnessScenarios.WATER_NEAR_Z);
        }
        return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
    }

    private static final int KILL_ACTIVE = 60;

    private static Input killScript(int k) {
        int step = k % KILL_PERIOD;
        boolean attack = fastSwing(k);
        if (k >= KILL_ACTIVE) {
            return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD,
                    attack, false);
        }
        if (step <= 4) {
            return cell(attack, HarnessScenarios.SLOT_OBSIDIAN, Input.BLOCK_PLACE,
                    HarnessScenarios.KILL_BASE_X, HarnessScenarios.KILL_BASE_Y,
                    HarnessScenarios.KILL_BASE_Z);
        }
        if (step <= 10) {
            return cell(attack, HarnessScenarios.SLOT_END_CRYSTAL, Input.BLOCK_PLACE_CRYSTAL,
                    HarnessScenarios.KILL_BASE_X, HarnessScenarios.KILL_BASE_Y,
                    HarnessScenarios.KILL_BASE_Z);
        }
        if (step <= 16) {
            return cell(attack, HarnessScenarios.SLOT_END_CRYSTAL, Input.BLOCK_HIT_CRYSTAL,
                    HarnessScenarios.KILL_BASE_X, HarnessScenarios.KILL_BASE_Y,
                    HarnessScenarios.KILL_BASE_Z)
                    .withCrystalHit(true, HarnessScenarios.KILL_BASE_X,
                            HarnessScenarios.KILL_BASE_Y, HarnessScenarios.KILL_BASE_Z);
        }
        return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD, attack, false);
    }

    public static final int GAP_FIRE_ANCHOR_X = 1;
    public static final int GAP_FIRE_ANCHOR_Y = 64;
    public static final int GAP_FIRE_ANCHOR_Z = 4;

    private static final int[][] GAP_FIRE_PLATFORM = {
            {4, 4}, {3, 5}, {3, 4}, {3, 3}, {2, 5}, {2, 4}, {2, 3}, {1, 5}, {1, 3}};

    private static final int GAP_FIRE_FLOOR_Y = 64;
    private static final int GAP_FIRE_TOP_Y = 65;

    private static final int GAP_PLACE_CELL_TICKS = 6;
    private static final int GAP_BREAK_CELL_TICKS = 8;

    public static final int GAP_SNOWBALL_SLOT = HarnessScenarios.SLOT_UTILITY;
    public static final int GAP_EGG_SLOT = HarnessScenarios.SLOT_BOW;
    public static final int GAP_XP_SLOT = HarnessScenarios.SLOT_ARROWS;
    public static final int GAP_SPLASH_SLOT = HarnessScenarios.SLOT_WATER_BUCKET;
    public static final int GAP_ROCKET_SLOT = HarnessScenarios.SLOT_LAVA_BUCKET;

    private static final int GAP_CROSSBOW_CYCLE = 40;

    private static final int GAP_MACE_LAUNCH_ONE = 8;
    private static final int GAP_MACE_LAUNCH_TWO = 120;
    private static final int GAP_MACE_SMASH_OPEN = 23;
    private static final int GAP_MACE_SMASH_SHUT = 27;
    private static final int GAP_MACE_HEAVY_OPEN = 27;
    private static final int GAP_MACE_HEAVY_SHUT = 31;
    private static final int GAP_MACE_SPRINT = 200;

    private static Input gapIdle(int slot, boolean attack) {
        return act(HarnessScenarios.ATTACKER_YAW, 0f, slot, attack, false);
    }

    private static Input gapReset(int r) {
        if (r < GAP_RESET_KILL_TICKS) {
            return gapKillCrystal(r);
        }
        return gapIdle(HarnessScenarios.SLOT_SWORD, true);
    }

    private static Input gapAttacker(int u) {
        if (u < GAP_MINE) {
            return gapReset(u - GAP_RESET_A);
        }
        if (u < GAP_BLOCK_SIGHT) {
            return cell(fastSwing(u), HarnessScenarios.SLOT_PICKAXE, Input.BLOCK_BREAK,
                    HarnessScenarios.PILLAR_X, HarnessScenarios.SIGHTLINE_VOXEL_Y,
                    HarnessScenarios.PILLAR_Z);
        }
        if (u < GAP_WEB_SIGHT) {
            return gapBlockSight(u - GAP_BLOCK_SIGHT);
        }
        if (u < GAP_OFFHAND) {
            return gapWebSight(u - GAP_WEB_SIGHT);
        }
        if (u < GAP_CROSSBOW) {
            return gapIdle(HarnessScenarios.SLOT_SWORD, false);
        }
        if (u < GAP_EGG) {
            return gapCrossbow(u - GAP_CROSSBOW);
        }
        if (u < GAP_ROCKET) {
            return gapEgg(u - GAP_EGG);
        }
        if (u < GAP_RESET_B) {
            return gapRocket(u - GAP_ROCKET);
        }
        if (u < GAP_CRYSTAL_SHOT) {
            return gapReset(u - GAP_RESET_B);
        }
        if (u < GAP_RESET_C) {
            return gapCrystalShot(u - GAP_CRYSTAL_SHOT);
        }
        if (u < GAP_MACE) {
            return gapReset(u - GAP_RESET_C);
        }
        if (u < GAP_TOTEM) {
            return gapMace(u - GAP_MACE);
        }
        if (u < GAP_RESET_D) {
            return gapTotemKill(u - GAP_TOTEM);
        }
        if (u < GAP_FIRE) {
            return gapReset(u - GAP_RESET_D);
        }
        if (u < GAP_FLOOD) {
            return gapFire(u - GAP_FIRE);
        }
        if (u < GAP_RESET_E) {
            return gapFlood(u - GAP_FLOOD);
        }
        return gapReset(u - GAP_RESET_E);
    }

    private static final int[][] GAP_KILL_RING = {
            {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};

    private static final int[][] GAP_TOTEM_RING = {
            {4, 2}, {3, -3}, {4, -2}, {3, 3}};

    private static Input gapTotemKill(int k) {
        return crystalRingStep(k, GAP_TOTEM_RING);
    }

    private static Input gapKillCrystal(int k) {
        return crystalRingStep(k, GAP_KILL_RING);
    }

    private static Input crystalRingStep(int k, int[][] ring) {
        int step = k % KILL_PERIOD;
        int[] base = ring[(k / KILL_PERIOD) % ring.length];
        int bx = base[0];
        int bz = base[1];
        boolean attack = fastSwing(k);
        if (step <= 4) {
            return cell(attack, HarnessScenarios.SLOT_OBSIDIAN, Input.BLOCK_PLACE,
                    bx, HarnessScenarios.KILL_BASE_Y, bz);
        }
        if (step <= 10) {
            return cell(attack, HarnessScenarios.SLOT_END_CRYSTAL, Input.BLOCK_PLACE_CRYSTAL,
                    bx, HarnessScenarios.KILL_BASE_Y, bz);
        }
        if (step <= 16) {
            return cell(attack, HarnessScenarios.SLOT_END_CRYSTAL, Input.BLOCK_HIT_CRYSTAL,
                    bx, HarnessScenarios.KILL_BASE_Y, bz)
                    .withCrystalHit(true, bx, HarnessScenarios.KILL_BASE_Y, bz);
        }
        return gapIdle(HarnessScenarios.SLOT_SWORD, attack);
    }

    private static Input gapOccluderMelee(boolean attack) {
        return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD, attack, false)
                .withMeleeHit(true);
    }

    private static final int GAP_SPRINT_HIT_TICKS = 91;

    private static final int GAP_SPRINT_CRIT_START = 48;

    private static final int GAP_SPRINT_SWING_PERIOD = 16;

    private static final int GAP_SPRINT_CRIT_PERIOD = 14;

    private static final int GAP_SPRINT_CRIT_JUMP = 6;

    private static Input gapBlockSight(int u) {
        if (u < GAP_SPRINT_CRIT_START) {
            boolean swing = u % GAP_SPRINT_SWING_PERIOD == GAP_SPRINT_SWING_PERIOD - 1;
            return new Input(true, false, false, false, false, true, false, swing, false,
                    HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD)
                    .withMeleeHit(true);
        }
        if (u < GAP_SPRINT_HIT_TICKS) {
            int c = u - GAP_SPRINT_CRIT_START;
            int phase = c % GAP_SPRINT_CRIT_PERIOD;
            if (phase == GAP_SPRINT_CRIT_JUMP) {
                return jumped(HarnessScenarios.SLOT_SWORD);
            }
            if (phase == 0 && c > 0) {
                return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD,
                        true, false).withMeleeHit(true);
            }
            return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD, false, false);
        }
        int v = u - GAP_SPRINT_HIT_TICKS;
        if (v == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_COBBLESTONE,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (v <= 10) {
            return cell(false, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE,
                    HarnessScenarios.PILLAR_X, HarnessScenarios.SIGHTLINE_VOXEL_Y,
                    HarnessScenarios.PILLAR_Z);
        }
        return gapOccluderMelee(fastSwing(v));
    }

    private static Input gapWebSight(int v) {
        if (v <= 50) {
            return cell(fastSwing(v), HarnessScenarios.SLOT_PICKAXE, Input.BLOCK_BREAK,
                    HarnessScenarios.PILLAR_X, HarnessScenarios.SIGHTLINE_VOXEL_Y,
                    HarnessScenarios.PILLAR_Z);
        }
        if (v == 52) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_COBWEB,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (v >= 54 && v <= 64) {
            return cell(false, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE,
                    HarnessScenarios.PILLAR_X, HarnessScenarios.SIGHTLINE_VOXEL_Y,
                    HarnessScenarios.PILLAR_Z);
        }
        return gapOccluderMelee(fastSwing(v));
    }

    private static Input gapCrossbow(int v) {
        if (v == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_CROSSBOW,
                            HarnessScenarios.SLOT_UTILITY);
        }
        int step = v % GAP_CROSSBOW_CYCLE;
        if (step < 30) {
            return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_UTILITY,
                    false, true);
        }
        if (step < 33) {
            return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_UTILITY,
                    false, false);
        }
        return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_UTILITY, false, true)
                .withUsePress(step == 33);
    }

    private static Input gapEgg(int v) {
        if (v == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_EGG,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (v < 6) {
            return gapIdle(HarnessScenarios.SLOT_UTILITY, false);
        }
        return act(HarnessScenarios.ATTACKER_YAW, -20f, HarnessScenarios.SLOT_UTILITY, false, true)
                .withUsePress(v % 12 == 6);
    }

    private static Input gapRocket(int v) {
        if (v == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_FIREWORK,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (v < 6) {
            return gapIdle(HarnessScenarios.SLOT_UTILITY, false);
        }
        return act(HarnessScenarios.ATTACKER_YAW, 89f, HarnessScenarios.SLOT_UTILITY, false, true)
                .withUsePress(v % 12 == 6);
    }

    private static final int GAP_CRYSTAL_SHOT_CYCLE = 50;

    private static Input gapCrystalShot(int u) {
        if (u == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_SNOWBALL,
                            HarnessScenarios.SLOT_UTILITY);
        }
        int v = u % GAP_CRYSTAL_SHOT_CYCLE;
        if (v >= 2 && v <= 12) {
            return cell(false, HarnessScenarios.SLOT_OBSIDIAN, Input.BLOCK_PLACE,
                    HarnessScenarios.PLACED_CRYSTAL_BASE_X, HarnessScenarios.PLACED_CRYSTAL_BASE_Y,
                    HarnessScenarios.PLACED_CRYSTAL_BASE_Z);
        }
        if (v >= 14 && v <= 24) {
            return cell(false, HarnessScenarios.SLOT_END_CRYSTAL, Input.BLOCK_PLACE_CRYSTAL,
                    HarnessScenarios.PLACED_CRYSTAL_BASE_X, HarnessScenarios.PLACED_CRYSTAL_BASE_Y,
                    HarnessScenarios.PLACED_CRYSTAL_BASE_Z);
        }
        if (v < 30) {
            return gapIdle(HarnessScenarios.SLOT_UTILITY, false);
        }
        return act(0f, 0f, HarnessScenarios.SLOT_UTILITY, false, true)
                .withUsePress(v % 8 == 6);
    }

    private static Input gapMace(int v) {
        if (v == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_MACE,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (v == 2) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_WIND_CHARGE,
                            HarnessScenarios.SLOT_BOW);
        }
        if (v == GAP_MACE_LAUNCH_ONE || v == GAP_MACE_LAUNCH_TWO) {
            return act(HarnessScenarios.ATTACKER_YAW, 89f, HarnessScenarios.SLOT_BOW, false, true)
                    .withUsePress(true);
        }
        if (v >= GAP_MACE_SPRINT) {
            boolean swing = (v - GAP_MACE_SPRINT) % 16 >= 8;
            return new Input(true, false, false, false, false, true, false, swing, false,
                    HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_SWORD)
                    .withMeleeHit(true);
        }
        int since = v - (v < GAP_MACE_LAUNCH_TWO ? GAP_MACE_LAUNCH_ONE : GAP_MACE_LAUNCH_TWO);
        boolean smashWindow = v < GAP_MACE_LAUNCH_TWO
                ? since >= GAP_MACE_SMASH_OPEN && since <= GAP_MACE_SMASH_SHUT
                : since >= GAP_MACE_HEAVY_OPEN && since <= GAP_MACE_HEAVY_SHUT;
        return act(HarnessScenarios.ATTACKER_YAW, 0f, HarnessScenarios.SLOT_UTILITY,
                smashWindow, false).withMeleeHit(smashWindow);
    }

    private static Input gapFire(int v) {
        int cells = GAP_FIRE_PLATFORM.length;
        int floorSpan = cells * GAP_PLACE_CELL_TICKS;
        if (v < floorSpan) {
            int[] c = GAP_FIRE_PLATFORM[v / GAP_PLACE_CELL_TICKS];
            return cell(false, HarnessScenarios.SLOT_OBSIDIAN, Input.BLOCK_PLACE,
                    c[0], GAP_FIRE_FLOOR_Y, c[1]);
        }
        int afterFloor = v - floorSpan;
        if (afterFloor == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_COBBLESTONE,
                            HarnessScenarios.SLOT_UTILITY);
        }
        int topSpan = cells * GAP_PLACE_CELL_TICKS;
        if (afterFloor <= topSpan) {
            int[] c = GAP_FIRE_PLATFORM[(afterFloor - 1) / GAP_PLACE_CELL_TICKS];
            return cell(false, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE,
                    c[0], GAP_FIRE_TOP_Y, c[1]);
        }
        int afterTop = afterFloor - topSpan - 1;
        if (afterTop == 0) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_ANCHOR,
                            HarnessScenarios.SLOT_UTILITY);
        }
        if (afterTop == 2) {
            return move(HarnessScenarios.SLOT_UTILITY, false)
                    .withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_GLOWSTONE,
                            HarnessScenarios.SLOT_BOW);
        }
        if (afterTop >= 4 && afterTop <= 14) {
            return cell(false, HarnessScenarios.SLOT_UTILITY, Input.BLOCK_PLACE_ANCHOR,
                    GAP_FIRE_ANCHOR_X, GAP_FIRE_ANCHOR_Y, GAP_FIRE_ANCHOR_Z);
        }
        if (afterTop >= 16 && afterTop <= 30) {
            return cell(false, HarnessScenarios.SLOT_BOW, Input.BLOCK_CHARGE_ANCHOR,
                    GAP_FIRE_ANCHOR_X, GAP_FIRE_ANCHOR_Y, GAP_FIRE_ANCHOR_Z);
        }
        if (afterTop >= 33 && afterTop <= 44) {
            return cell(false, HarnessScenarios.SLOT_BOW, Input.BLOCK_DETONATE_ANCHOR,
                    GAP_FIRE_ANCHOR_X, GAP_FIRE_ANCHOR_Y, GAP_FIRE_ANCHOR_Z);
        }
        if (afterTop >= 46 && afterTop < 66) {
            return new Input(true, false, false, false, false, true, false, false, false,
                    0f, 0f, HarnessScenarios.SLOT_PICKAXE);
        }
        int sweep = afterTop - 66;
        if (sweep >= 0) {
            int index = sweep / GAP_BREAK_CELL_TICKS;
            if (index < cells) {
                int[] c = GAP_FIRE_PLATFORM[cells - 1 - index];
                return cell(fastSwing(sweep), HarnessScenarios.SLOT_PICKAXE, Input.BLOCK_BREAK,
                        c[0], GAP_FIRE_TOP_Y, c[1]);
            }
        }
        return gapIdle(HarnessScenarios.SLOT_SWORD, false);
    }

    private static final int[] GAP_FLOOD_SOURCE = {
            HarnessScenarios.STASH_SNOWBALL, HarnessScenarios.STASH_EGG,
            HarnessScenarios.STASH_XP_BOTTLE, HarnessScenarios.STASH_SPLASH_POTION,
            HarnessScenarios.STASH_FIREWORK};

    private static final int[] GAP_FLOOD_SLOT = {
            GAP_SNOWBALL_SLOT, GAP_EGG_SLOT, GAP_XP_SLOT, GAP_SPLASH_SLOT, GAP_ROCKET_SLOT};

    private static final int GAP_FLOOD_LOAD = GAP_FLOOD_SOURCE.length * 2;

    private static final int GAP_FLOOD_VOLLEY = 4;

    private static Input gapFlood(int v) {
        if (v < GAP_FLOOD_LOAD) {
            if (v % 2 != 0) {
                return gapIdle(HarnessScenarios.SLOT_SWORD, false);
            }
            int i = v / 2;
            return move(HarnessScenarios.SLOT_SWORD, false)
                    .withInvAction(Input.INV_MOVE, GAP_FLOOD_SOURCE[i], GAP_FLOOD_SLOT[i]);
        }
        int fired = v - GAP_FLOOD_LOAD;
        int lane = fired / GAP_FLOOD_VOLLEY;
        if (lane < 4) {
            return clicked(act(HarnessScenarios.ATTACKER_YAW, -90f, GAP_FLOOD_SLOT[lane],
                    false, true).withUsePress(true), 0, Clicks.MAX, 0, 0, 0);
        }
        return act(HarnessScenarios.ATTACKER_YAW, 89f, GAP_ROCKET_SLOT, false, true)
                .withUsePress(fired % 4 == 0);
    }

    private static final int[] GAP_OFFHAND_SOURCE = {
            HarnessScenarios.VICTIM_SLOT_APPLES, HarnessScenarios.VICTIM_STASH_BOW,
            HarnessScenarios.VICTIM_STASH_FIREWORK, HarnessScenarios.VICTIM_STASH_CROSSBOW,
            HarnessScenarios.VICTIM_SLOT_SHIELD};

    private static final int GAP_OFFHAND_LEG = 40;

    private static Input gapVictim(int u) {
        if (u >= GAP_OFFHAND && u < GAP_CROSSBOW) {
            return gapVictimOffhand(u - GAP_OFFHAND);
        }
        if (u >= GAP_TOTEM && u < GAP_RESET_D) {
            int v = u - GAP_TOTEM;
            if (v == 0) {
                return gapVictimStand(false)
                        .withInvAction(Input.INV_MOVE, ItemDict.OFF_HAND,
                                HarnessScenarios.VICTIM_PARK_OFFHAND);
            }
            if (v == 2) {
                return gapVictimStand(false)
                        .withInvAction(Input.INV_MOVE, HarnessScenarios.VICTIM_STASH_TOTEM,
                                ItemDict.OFF_HAND);
            }
        }
        return gapVictimStand(fastSwing(u));
    }

    private static Input gapVictimStand(boolean attack) {
        return new Input(false, false, false, false, false, false, false, attack, false,
                HarnessScenarios.VICTIM_YAW, 0f, HarnessScenarios.VICTIM_SLOT_SWORD);
    }

    private static Input gapVictimOffhand(int v) {
        int leg = v / GAP_OFFHAND_LEG;
        int step = v % GAP_OFFHAND_LEG;
        if (leg >= GAP_OFFHAND_SOURCE.length) {
            return gapVictimStand(false);
        }
        Input held = new Input(false, false, false, false, false, false, false, false, false,
                HarnessScenarios.VICTIM_YAW, 0f, HarnessScenarios.VICTIM_SLOT_COBBLESTONE);
        if (step == 0) {
            return held.withInvAction(Input.INV_MOVE, ItemDict.OFF_HAND,
                    HarnessScenarios.VICTIM_PARK_OFFHAND);
        }
        if (step == 2) {
            return held.withInvAction(Input.INV_MOVE, GAP_OFFHAND_SOURCE[leg], ItemDict.OFF_HAND);
        }
        return held.withOffhandUse(step > 8).withOffhandUsePress(step == 10);
    }

    private static Input preludeVictim(int t) {
        boolean attack = fastSwing(t);
        if (t < PRE_ARCHERY) {
            return victimHold(attack);
        }
        if (t < PRE_SPEAR) {
            int step = (t - PRE_ARCHERY) % (2 * ARCHERY_STRAFE);
            return victimRun(step < ARCHERY_STRAFE ? 0f : 180f, attack);
        }
        if (t < PRE_REACH) {
            int u = t - PRE_SPEAR;
            if (u >= SPEAR_RETREAT_START && u < SPEAR_RETREAT_END) {
                return victimRun(HarnessScenarios.ATTACKER_YAW, attack);
            }
            return victimHold(attack);
        }
        if (t < PRE_REWIND) {
            return victimRun(HarnessScenarios.ATTACKER_YAW, attack);
        }
        if (t < PRE_OCCLUSION) {
            int step = (t - PRE_REWIND) % REWIND_PERIOD;
            return victimRun(step < REWIND_AWAY
                    ? HarnessScenarios.VICTIM_YAW : HarnessScenarios.ATTACKER_YAW, attack);
        }
        if (t < PRE_THROWABLES) {
            return t - PRE_OCCLUSION < 30
                    ? victimRun(HarnessScenarios.VICTIM_YAW, attack) : victimHold(attack);
        }
        return victimHold(attack);
    }

    private static Input victimRun(float yaw, boolean attack) {
        return new Input(true, false, false, false, false, true, false, attack, false,
                yaw, 0f, HarnessScenarios.VICTIM_SLOT_SWORD);
    }

    private static Input victimHold(boolean attack) {
        return new Input(false, false, false, false, false, false, false, attack, false,
                HarnessScenarios.VICTIM_YAW, 0f, HarnessScenarios.VICTIM_SLOT_SWORD);
    }

    private static final int TAIL_EQUIP = 140;

    private static final int TAIL_LAUNCH = 160;

    private static Input tailAttacker(int e) {
        int held = HarnessScenarios.SLOT_UTILITY;
        if (e == TAIL_EQUIP) {
            return move(held, false).withInvAction(Input.INV_MOVE, ItemDict.ARMOR_CHEST,
                    HarnessScenarios.PARK_CHESTPLATE);
        }
        if (e == TAIL_EQUIP + 4) {
            return move(held, false).withInvAction(Input.INV_MOVE, HarnessScenarios.STASH_ELYTRA,
                    ItemDict.ARMOR_CHEST);
        }
        if (e == TAIL_EQUIP + 8) {
            return move(held, false).withInvAction(Input.INV_MOVE,
                    HarnessScenarios.STASH_FIREWORK, held);
        }
        if (e < TAIL_LAUNCH) {
            return act(HarnessScenarios.ATTACKER_YAW, 0f, held, false, false);
        }
        int step = (e - TAIL_LAUNCH) % TAIL_FLIGHT_PERIOD;
        float yaw = HarnessScenarios.ATTACKER_YAW + (step % 20) * 3f;
        if (step == 0) {
            return jumped(held);
        }
        if (step == 1) {
            return act(yaw, 0f, held, false, false);
        }
        if (step == 2) {
            return act(yaw, -30f, held, false, false).withElytraStart(true);
        }
        if (step <= 10) {
            return act(yaw, -30f, held, false, true).withUsePress(step == 3 || step == 7);
        }
        if (step <= 70) {
            float pitch = (step / 10) % 2 == 0 ? -15f : 10f;
            return act(yaw, pitch, held, false, false);
        }
        return act(yaw, 70f, held, false, false);
    }

    private static Input jumped(int slot) {
        return new Input(false, false, false, false, true, false, false, false, false,
                HarnessScenarios.ATTACKER_YAW, 0f, slot);
    }

    private static Input tailVictim(int e) {
        return walk(HarnessScenarios.VICTIM_SLOT_SWORD, fastSwing(e), false, true, false);
    }

    private static Input walk(int slot, boolean attack, boolean use, boolean sprint, boolean sneak) {
        return new Input(true, false, false, false, false, sprint, sneak, attack, use,
                HarnessScenarios.VICTIM_YAW, 0f, slot);
    }

    private static boolean fastSwing(int t) {
        return (t & 1) == 0;
    }

    private static boolean strongSwing(int t) {
        return t % STRONG_SWING_PERIOD < STRONG_SWING_PERIOD / 2;
    }

    private static Input act(float yaw, float pitch, int slot, boolean attack, boolean use) {
        return new Input(false, false, false, false, false, false, false, attack, use,
                yaw, pitch, slot);
    }

    private static Input move(int slot, boolean attack) {
        return act(HarnessScenarios.ATTACKER_YAW, 0f, slot, attack, false);
    }

    private static Input sightlineBlock(boolean attack, int slot, int action) {
        return cell(attack, slot, action, HarnessScenarios.PILLAR_X,
                HarnessScenarios.SIGHTLINE_VOXEL_Y, HarnessScenarios.PILLAR_Z);
    }

    private static Input cell(boolean attack, int slot, int action, int x, int y, int z) {
        return act(HarnessScenarios.ATTACKER_YAW, 0f, slot, attack, false)
                .withBlockAction(action, x, y, z);
    }

    private static long xorshift(long x) {
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        return x;
    }

    private static float yawDrift(long r) {
        int d = (int) ((r >>> 40) % 7L) - 3;
        return d * 0.7f;
    }

    private static Input inputFrom(long r, float yaw) {
        return new Input(
                (r & 1L) != 0,
                (r & 2L) != 0,
                (r & 4L) != 0,
                (r & 8L) != 0,
                (r & 16L) != 0,
                (r & 32L) != 0,
                (r & 64L) != 0,
                (r & 128L) != 0,
                (r & 256L) != 0,
                yaw,
                0f,
                (int) ((r >>> 50) % 9L))

                .withMeleeHit((r & 512L) != 0)
                .withClicks(new Clicks(
                        (int) ((r >>> 10) & 7L),
                        (int) ((r >>> 13) & 7L),
                        (int) ((r >>> 16) & 7L),
                        (int) ((r >>> 19) & 7L),
                        (int) ((r >>> 22) & 7L)));
    }
}
