package me.nootnoot.sim.harness;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.SimProbe;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;

public final class HarnessCounts {
    public static final int ARENA_PARTIAL_BOX = 0;
    public static final int ARENA_OUTLINE_CLIPPED_BOX = 1;
    public static final int TICK = 2;
    public static final int GLIDING_TICK = 3;
    public static final int FIREWORK_BOOST_TICK = 4;
    public static final int SWIMMING_TICK = 5;
    public static final int SUBMERGED_TICK = 6;
    public static final int SPRINTING_TICK = 7;
    public static final int SNEAKING_TICK = 8;
    public static final int MINING_TICK = 9;
    public static final int CONTAINER_OPEN_TICK = 10;
    public static final int CURSOR_STACK_HELD_TICK = 11;
    public static final int SPEAR_IN_HAND_TICK = 12;
    public static final int COUNTED_CLICKS_MAXED_TICK = 13;
    public static final int ROUND_RESET_COUNTDOWN_TICK = 14;
    public static final int CRYSTAL_LIVE_TICK = 15;
    public static final int ANCHOR_LIVE_TICK = 16;
    public static final int CHARGED_ANCHOR_LIVE_TICK = 17;
    public static final int FLUID_LIVE_TICK = 18;
    public static final int FIRE_LIVE_TICK = 19;
    public static final int COBWEB_LIVE_TICK = 20;
    public static final int PLACED_BLOCK_LIVE_TICK = 21;
    public static final int BROKEN_ARENA_VOXEL_TICK = 22;
    public static final int PROJECTILE_LIVE_TICK = 23;
    public static final int ITEM_ENTITY_LIVE_TICK = 24;
    public static final int CONTAINER_CONTENTS_CHANGED = 25;
    public static final int EVENT_SWING = 26;
    public static final int EVENT_DEATH = 27;
    public static final int EVENT_SHIELD_BLOCK = 28;
    public static final int EVENT_TOTEM = 29;
    public static final int EVENT_EXPLOSION = 30;
    public static final int EVENT_FLUID_SOLIDIFY = 31;
    public static final int EVENT_POTION_SPLASH = 32;
    public static final int EVENT_FIRE_EXTINGUISH = 33;
    public static final int EVENT_BUCKET_FILL = 34;
    public static final int EVENT_BUCKET_EMPTY = 35;
    public static final int EVENT_ITEM_BREAK = 36;
    public static final int HIT_WEAK = 37;
    public static final int HIT_STRONG = 38;
    public static final int HIT_KNOCKBACK = 39;
    public static final int HIT_CRIT = 40;
    public static final int HIT_ARROW_PEER = 41;
    public static final int HIT_ARROW_SELF = 42;
    public static final int HIT_FIRE = 43;
    public static final int HIT_FALL = 44;
    public static final int HIT_SMASH = 45;
    public static final int HIT_SMASH_HEAVY = 46;
    public static final int HIT_EXPLOSION = 47;
    public static final int OBSERVED_COUNTERS = 48;

    private static final String[] OBSERVED_NAMES = {
            "arena-partial-box",
            "arena-outline-clipped-box",
            "tick",
            "gliding-tick",
            "firework-boost-tick",
            "swimming-tick",
            "submerged-tick",
            "sprinting-tick",
            "sneaking-tick",
            "mining-tick",
            "container-open-tick",
            "cursor-stack-held-tick",
            "spear-in-hand-tick",
            "counted-clicks-maxed-tick",
            "round-reset-countdown-tick",
            "crystal-live-tick",
            "anchor-live-tick",
            "charged-anchor-live-tick",
            "fluid-live-tick",
            "fire-live-tick",
            "cobweb-live-tick",
            "placed-block-live-tick",
            "broken-arena-voxel-tick",
            "projectile-live-tick",
            "item-entity-live-tick",
            "container-contents-changed",
            "event-swing",
            "event-death",
            "event-shield-block",
            "event-totem",
            "event-explosion",
            "event-fluid-solidify",
            "event-potion-splash",
            "event-fire-extinguish",
            "event-bucket-fill",
            "event-bucket-empty",
            "event-item-break",
            "hit-weak",
            "hit-strong",
            "hit-knockback",
            "hit-crit",
            "hit-arrow-peer",
            "hit-arrow-self",
            "hit-fire",
            "hit-fall",
            "hit-smash",
            "hit-smash-heavy",
            "hit-explosion",
    };

    private static final Object PROBE_LOCK = new Object();

    public record Result(long[] entryPoints, long[] observed, long finalChecksum) {
        public long entryPoint(int counter) {
            return entryPoints[counter];
        }

        public long observation(int counter) {
            return observed[counter];
        }

        public List<String> zeroEntryPoints() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < entryPoints.length; i++) {
                if (entryPoints[i] == 0) {
                    out.add(SimProbe.name(i));
                }
            }
            return out;
        }

        public List<String> zeroObservations() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < observed.length; i++) {
                if (observed[i] == 0) {
                    out.add(OBSERVED_NAMES[i]);
                }
            }
            return out;
        }
    }

    private HarnessCounts() {
    }

    public static String observedName(int counter) {
        return OBSERVED_NAMES[counter];
    }

    public static String[] observedNames() {
        return OBSERVED_NAMES.clone();
    }

    public static Result run() {
        long[] entryPoints = new long[SimProbe.COUNTERS];
        long[] observed = new long[OBSERVED_COUNTERS];

        Arena arena = HarnessScenarios.arena();
        GameState g = HarnessScenarios.combat(arena);
        InputLog log = InputLog.scripted(HarnessDigest.SEED, HarnessDigest.TICKS);

        observeArena(observed, arena);

        long checksum;
        synchronized (PROBE_LOCK) {
            SimProbe.install(entryPoints);
            try {
                long prevContainerFill = Long.MIN_VALUE;
                for (Input[] frame : log.frames) {
                    Simulation.tick(g, arena, frame[0], frame[1]);
                    prevContainerFill = observe(observed, g, frame[0], frame[1], prevContainerFill);
                }
            } finally {
                SimProbe.uninstall();
            }
            checksum = Checksum.of(g);
        }
        return new Result(entryPoints, observed, checksum);
    }

    private static void observeArena(long[] o, Arena arena) {
        for (Aabb box : arena.partialBoxes()) {
            o[ARENA_PARTIAL_BOX]++;
            if (Arena.outlineOf(box) != box) {
                o[ARENA_OUTLINE_CLIPPED_BOX]++;
            }
        }
    }

    private static long observe(long[] o, GameState g, Input in0, Input in1, long prevContainerFill) {
        o[TICK]++;

        for (PlayerState p : g.players) {
            if (p.gliding) {
                o[GLIDING_TICK]++;
            }
            if (p.fireworkTicks > 0) {
                o[FIREWORK_BOOST_TICK]++;
            }
            if (p.swimming) {
                o[SWIMMING_TICK]++;
            }
            if (p.submergedEye) {
                o[SUBMERGED_TICK]++;
            }
            if (p.sprinting) {
                o[SPRINTING_TICK]++;
            }
            if (p.sneaking) {
                o[SNEAKING_TICK]++;
            }
            if (p.miningProgress > 0f) {
                o[MINING_TICK]++;
            }
            if (p.openContainer >= 0) {
                o[CONTAINER_OPEN_TICK]++;
            }
            if (p.cursorCount > 0) {
                o[CURSOR_STACK_HELD_TICK]++;
            }
            if (p.heldItemId == HarnessScenarios.ID_SPEAR) {
                o[SPEAR_IN_HAND_TICK]++;
            }
        }

        if (maxedClicks(in0)) {
            o[COUNTED_CLICKS_MAXED_TICK]++;
        }
        if (maxedClicks(in1)) {
            o[COUNTED_CLICKS_MAXED_TICK]++;
        }

        if (g.roundResetCountdown > 0) {
            o[ROUND_RESET_COUNTDOWN_TICK]++;
        }
        if (!g.crystals.isEmpty()) {
            o[CRYSTAL_LIVE_TICK]++;
        }
        if (!g.anchors.isEmpty()) {
            o[ANCHOR_LIVE_TICK]++;
        }
        for (int charge : g.anchors.values()) {
            if (charge > 0) {
                o[CHARGED_ANCHOR_LIVE_TICK]++;
                break;
            }
        }
        if (!g.fluids.isEmpty()) {
            o[FLUID_LIVE_TICK]++;
        }
        if (!g.fires.isEmpty()) {
            o[FIRE_LIVE_TICK]++;
        }
        if (!g.cobwebs.isEmpty()) {
            o[COBWEB_LIVE_TICK]++;
        }
        if (g.blocks.size() > 0) {
            o[PLACED_BLOCK_LIVE_TICK]++;
        }
        if (!g.brokenArena.isEmpty()) {
            o[BROKEN_ARENA_VOXEL_TICK]++;
        }
        if (!g.projectiles.isEmpty()) {
            o[PROJECTILE_LIVE_TICK]++;
        }
        if (!g.items.isEmpty()) {
            o[ITEM_ENTITY_LIVE_TICK]++;
        }

        for (CombatEvent e : g.events) {
            switch (e.type()) {
                case CombatEvent.SWING -> o[EVENT_SWING]++;
                case CombatEvent.DEATH -> o[EVENT_DEATH]++;
                case CombatEvent.BLOCK -> o[EVENT_SHIELD_BLOCK]++;
                case CombatEvent.TOTEM -> o[EVENT_TOTEM]++;
                case CombatEvent.EXPLOSION -> o[EVENT_EXPLOSION]++;
                case CombatEvent.FLUID_SOLIDIFY -> o[EVENT_FLUID_SOLIDIFY]++;
                case CombatEvent.POTION_SPLASH -> o[EVENT_POTION_SPLASH]++;
                case CombatEvent.FIRE_EXTINGUISH -> o[EVENT_FIRE_EXTINGUISH]++;
                case CombatEvent.BUCKET_FILL -> o[EVENT_BUCKET_FILL]++;
                case CombatEvent.BUCKET_EMPTY -> o[EVENT_BUCKET_EMPTY]++;
                case CombatEvent.ITEM_BREAK -> o[EVENT_ITEM_BREAK]++;
                case CombatEvent.HIT -> countHit(o, e);
                default -> {
                }
            }
        }

        long fill = containerFill(g);
        if (prevContainerFill != Long.MIN_VALUE && fill != prevContainerFill) {
            o[CONTAINER_CONTENTS_CHANGED]++;
        }
        return fill;
    }

    private static void countHit(long[] o, CombatEvent e) {
        switch (e.kind()) {
            case CombatEvent.HIT_WEAK -> o[HIT_WEAK]++;
            case CombatEvent.HIT_STRONG -> o[HIT_STRONG]++;
            case CombatEvent.HIT_KNOCKBACK -> o[HIT_KNOCKBACK]++;
            case CombatEvent.HIT_CRIT -> o[HIT_CRIT]++;
            case CombatEvent.HIT_ARROW -> {
                if (e.attacker() == e.victim()) {
                    o[HIT_ARROW_SELF]++;
                } else {
                    o[HIT_ARROW_PEER]++;
                }
            }
            case CombatEvent.HIT_FIRE -> o[HIT_FIRE]++;
            case CombatEvent.HIT_FALL -> o[HIT_FALL]++;
            case CombatEvent.HIT_SMASH -> o[HIT_SMASH]++;
            case CombatEvent.HIT_SMASH_HEAVY -> o[HIT_SMASH_HEAVY]++;
            case CombatEvent.HIT_EXPLOSION -> o[HIT_EXPLOSION]++;
            default -> {
            }
        }
    }

    private static boolean maxedClicks(Input in) {
        Clicks c = in.clicks();
        return c.attack() >= Clicks.MAX || c.use() >= Clicks.MAX || c.inv() >= Clicks.MAX
                || c.drop() >= Clicks.MAX || c.swap() >= Clicks.MAX;
    }

    private static long containerFill(GameState g) {
        long fill = 0;
        for (Container c : g.containers.values()) {
            for (int i = 0; i < Container.CELLS; i++) {
                fill = fill * 31 + c.entry[i] * 97L + c.count[i];
            }
        }
        return fill;
    }
}
