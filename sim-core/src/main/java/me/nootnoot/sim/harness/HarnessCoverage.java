package me.nootnoot.sim.harness;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;

public final class HarnessCoverage {
    public static final String[] REQUIREMENTS = {
            "arena-partial-boxes",
            "arena-outline-clipped",
            "melee-hit",
            "melee-crit",
            "shield-block",
            "spear-in-hand",
            "arrow-hit-peer",
            "arrow-hit-self",
            "explosion-hit",
            "fall-damage",
            "fire-damage",
            "block-placed",
            "block-broken",
            "arena-voxel-broken",
            "cobweb-placed",
            "crystal-placed",
            "crystal-removed",
            "anchor-placed",
            "anchor-charged",
            "anchor-removed",
            "explosion",
            "projectile-arrow",
            "projectile-pearl",
            "projectile-snowball",
            "projectile-egg",
            "projectile-splash-potion",
            "projectile-xp-bottle",
            "projectile-wind-charge",
            "potion-splash",
            "container-opened",
            "container-contents-changed",
            "item-entity-live",
            "item-broken",
            "durability-worn",
            "elytra-gliding",
            "firework-boost",
            "swimming",
            "submerged",
            "fluid-placed",
            "fluid-picked-up",
            "fluid-solidified",
            "round-reset",
            "cursor-stack-held",
            "counted-clicks-maxed",
            "blast-cells-spent",
            "mace-smash",
            "mace-smash-heavy",
            "totem-consumed",
            "fire-burning",
            "fire-extinguished",
    };

    private static final int ARENA_PARTIAL_BOXES = 0;
    private static final int ARENA_OUTLINE_CLIPPED = 1;
    private static final int MELEE_HIT = 2;
    private static final int MELEE_CRIT = 3;
    private static final int SHIELD_BLOCK = 4;
    private static final int SPEAR_IN_HAND = 5;
    private static final int ARROW_HIT_PEER = 6;
    private static final int ARROW_HIT_SELF = 7;
    private static final int EXPLOSION_HIT = 8;
    private static final int FALL_DAMAGE = 9;
    private static final int FIRE_DAMAGE = 10;
    private static final int BLOCK_PLACED = 11;
    private static final int BLOCK_BROKEN = 12;
    private static final int ARENA_VOXEL_BROKEN = 13;
    private static final int COBWEB_PLACED = 14;
    private static final int CRYSTAL_PLACED = 15;
    private static final int CRYSTAL_REMOVED = 16;
    private static final int ANCHOR_PLACED = 17;
    private static final int ANCHOR_CHARGED = 18;
    private static final int ANCHOR_REMOVED = 19;
    private static final int EXPLOSION = 20;
    private static final int PROJECTILE_ARROW = 21;
    private static final int PROJECTILE_PEARL = 22;
    private static final int PROJECTILE_SNOWBALL = 23;
    private static final int PROJECTILE_EGG = 24;
    private static final int PROJECTILE_SPLASH_POTION = 25;
    private static final int PROJECTILE_XP_BOTTLE = 26;
    private static final int PROJECTILE_WIND_CHARGE = 27;
    private static final int POTION_SPLASH = 28;
    private static final int CONTAINER_OPENED = 29;
    private static final int CONTAINER_CHANGED = 30;
    private static final int ITEM_ENTITY_LIVE = 31;
    private static final int ITEM_BROKEN = 32;
    private static final int DURABILITY_WORN = 33;
    private static final int ELYTRA_GLIDING = 34;
    private static final int FIREWORK_BOOST = 35;
    private static final int SWIMMING = 36;
    private static final int SUBMERGED = 37;
    private static final int FLUID_PLACED = 38;
    private static final int FLUID_PICKED_UP = 39;
    private static final int FLUID_SOLIDIFIED = 40;
    private static final int ROUND_RESET = 41;
    private static final int CURSOR_STACK_HELD = 42;
    private static final int COUNTED_CLICKS_MAXED = 43;
    private static final int BLAST_CELLS_SPENT = 44;
    private static final int MACE_SMASH = 45;
    private static final int MACE_SMASH_HEAVY = 46;
    private static final int TOTEM_CONSUMED = 47;
    private static final int FIRE_BURNING = 48;
    private static final int FIRE_EXTINGUISHED = 49;

    private final boolean[] seen = new boolean[REQUIREMENTS.length];

    private int prevBlocks = -1;
    private int prevBrokenArena = -1;
    private int prevCrystals = -1;
    private int prevAnchors = -1;
    private long prevContainerFill = Long.MIN_VALUE;
    private long prevDurability = Long.MIN_VALUE;
    private int prevRoundWins = -1;

    public void observeArena(Arena arena) {
        Aabb[] partials = arena.partialBoxes();
        if (partials.length > 0) {
            seen[ARENA_PARTIAL_BOXES] = true;
        }
        for (Aabb box : partials) {
            if (Arena.outlineOf(box) != box) {
                seen[ARENA_OUTLINE_CLIPPED] = true;
            }
        }
    }

    public void observe(GameState g, Input in0, Input in1) {
        for (CombatEvent e : g.events) {
            switch (e.type()) {
                case CombatEvent.HIT -> observeHit(e);
                case CombatEvent.BLOCK -> seen[SHIELD_BLOCK] = true;
                case CombatEvent.EXPLOSION -> seen[EXPLOSION] = true;
                case CombatEvent.POTION_SPLASH -> seen[POTION_SPLASH] = true;
                case CombatEvent.FLUID_SOLIDIFY -> seen[FLUID_SOLIDIFIED] = true;
                case CombatEvent.BUCKET_EMPTY -> seen[FLUID_PLACED] = true;
                case CombatEvent.BUCKET_FILL -> seen[FLUID_PICKED_UP] = true;
                case CombatEvent.ITEM_BREAK -> seen[ITEM_BROKEN] = true;
                case CombatEvent.TOTEM -> seen[TOTEM_CONSUMED] = true;
                case CombatEvent.FIRE_EXTINGUISH -> seen[FIRE_EXTINGUISHED] = true;
                default -> {
                }
            }
        }

        for (ProjectileState p : g.projectiles) {
            markProjectile(p.type);
        }

        observeCounts(g);
        observeClicks(in0);
        observeClicks(in1);

        for (PlayerState p : g.players) {
            if (p.gliding) {
                seen[ELYTRA_GLIDING] = true;
            }
            if (p.fireworkTicks > 0) {
                seen[FIREWORK_BOOST] = true;
            }
            if (p.swimming) {
                seen[SWIMMING] = true;
            }
            if (p.submergedEye) {
                seen[SUBMERGED] = true;
            }
            if (p.openContainer >= 0) {
                seen[CONTAINER_OPENED] = true;
            }
            if (p.cursorCount > 0) {
                seen[CURSOR_STACK_HELD] = true;
            }
            if (p.heldItemId == HarnessScenarios.ID_SPEAR) {
                seen[SPEAR_IN_HAND] = true;
            }
        }

        if (!g.items.isEmpty()) {
            seen[ITEM_ENTITY_LIVE] = true;
        }
        if (!g.cobwebs.isEmpty()) {
            seen[COBWEB_PLACED] = true;
        }
        if (g.blastCellBudget < GameState.BLAST_CELLS_PER_TICK) {
            seen[BLAST_CELLS_SPENT] = true;
        }
        if (!g.fires.isEmpty()) {
            seen[FIRE_BURNING] = true;
        }
        for (int charge : g.anchors.values()) {
            if (charge > 0) {
                seen[ANCHOR_CHARGED] = true;
            }
        }
    }

    private void observeHit(CombatEvent e) {
        switch (e.kind()) {
            case CombatEvent.HIT_WEAK, CombatEvent.HIT_STRONG, CombatEvent.HIT_KNOCKBACK ->
                    seen[MELEE_HIT] = true;
            case CombatEvent.HIT_CRIT -> {
                seen[MELEE_HIT] = true;
                seen[MELEE_CRIT] = true;
            }
            case CombatEvent.HIT_ARROW -> {
                if (e.attacker() == e.victim()) {
                    seen[ARROW_HIT_SELF] = true;
                } else {
                    seen[ARROW_HIT_PEER] = true;
                }
            }
            case CombatEvent.HIT_FIRE -> seen[FIRE_DAMAGE] = true;
            case CombatEvent.HIT_FALL -> seen[FALL_DAMAGE] = true;
            case CombatEvent.HIT_EXPLOSION -> seen[EXPLOSION_HIT] = true;
            case CombatEvent.HIT_SMASH -> seen[MACE_SMASH] = true;
            case CombatEvent.HIT_SMASH_HEAVY -> seen[MACE_SMASH_HEAVY] = true;
            default -> {
            }
        }
    }

    private void markProjectile(int type) {
        switch (type) {
            case ProjectileState.TYPE_ARROW -> seen[PROJECTILE_ARROW] = true;
            case ProjectileState.TYPE_PEARL -> seen[PROJECTILE_PEARL] = true;
            case ProjectileState.TYPE_SNOWBALL -> seen[PROJECTILE_SNOWBALL] = true;
            case ProjectileState.TYPE_EGG -> seen[PROJECTILE_EGG] = true;
            case ProjectileState.TYPE_SPLASH_POTION -> seen[PROJECTILE_SPLASH_POTION] = true;
            case ProjectileState.TYPE_XP_BOTTLE -> seen[PROJECTILE_XP_BOTTLE] = true;
            case ProjectileState.TYPE_WIND_CHARGE -> seen[PROJECTILE_WIND_CHARGE] = true;
            default -> {
            }
        }
    }

    private void observeClicks(Input in) {
        Clicks c = in.clicks();
        if (c.attack() >= Clicks.MAX || c.use() >= Clicks.MAX || c.inv() >= Clicks.MAX
                || c.drop() >= Clicks.MAX || c.swap() >= Clicks.MAX) {
            seen[COUNTED_CLICKS_MAXED] = true;
        }
    }

    private void observeCounts(GameState g) {
        int blocks = g.blocks.size();
        if (prevBlocks >= 0) {
            if (blocks > prevBlocks) {
                seen[BLOCK_PLACED] = true;
            } else if (blocks < prevBlocks) {
                seen[BLOCK_BROKEN] = true;
            }
        }
        prevBlocks = blocks;

        int brokenArena = g.brokenArena.size();
        if (prevBrokenArena >= 0 && brokenArena > prevBrokenArena) {
            seen[ARENA_VOXEL_BROKEN] = true;
        }
        prevBrokenArena = brokenArena;

        int crystals = g.crystals.size();
        if (prevCrystals >= 0) {
            if (crystals > prevCrystals) {
                seen[CRYSTAL_PLACED] = true;
            } else if (crystals < prevCrystals) {
                seen[CRYSTAL_REMOVED] = true;
            }
        }
        prevCrystals = crystals;

        int anchors = g.anchors.size();
        if (prevAnchors >= 0) {
            if (anchors > prevAnchors) {
                seen[ANCHOR_PLACED] = true;
            } else if (anchors < prevAnchors) {
                seen[ANCHOR_REMOVED] = true;
            }
        }
        prevAnchors = anchors;

        long fill = 0;
        for (Container c : g.containers.values()) {
            for (int i = 0; i < Container.CELLS; i++) {
                fill = fill * 31 + c.entry[i] * 97L + c.count[i];
            }
        }
        if (prevContainerFill != Long.MIN_VALUE && fill != prevContainerFill) {
            seen[CONTAINER_CHANGED] = true;
        }
        prevContainerFill = fill;

        long worn = 0;
        for (PlayerState p : g.players) {
            for (int d : p.slotDamage) {
                worn += d;
            }
        }
        if (prevDurability != Long.MIN_VALUE && worn > prevDurability) {
            seen[DURABILITY_WORN] = true;
        }
        prevDurability = worn;

        int wins = g.roundWinsP0 + g.roundWinsP1;
        if (prevRoundWins >= 0 && wins > prevRoundWins) {
            seen[ROUND_RESET] = true;
        }
        prevRoundWins = wins;
    }

    public List<String> missing() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < seen.length; i++) {
            if (!seen[i]) {
                out.add(REQUIREMENTS[i]);
            }
        }
        return out;
    }

    public int covered() {
        int n = 0;
        for (boolean b : seen) {
            if (b) {
                n++;
            }
        }
        return n;
    }
}
