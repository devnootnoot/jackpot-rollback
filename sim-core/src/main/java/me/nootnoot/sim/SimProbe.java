package me.nootnoot.sim;

public final class SimProbe {
    public static final int MELEE_CLAIM_ATTEMPT = 0;
    public static final int MELEE_CLAIM_GRANTED = 1;
    public static final int MELEE_CLAIM_GRANTED_LIVE_HULL = 2;
    public static final int MELEE_CLAIM_GRANTED_REWOUND_HULL = 3;
    public static final int MELEE_CLAIM_GRANTED_OUTSIDE_LIVE_REACH = 4;
    public static final int MELEE_CLAIM_REFUSED = 5;
    public static final int MELEE_CLAIM_REFUSED_OUT_OF_REACH = 6;
    public static final int MELEE_CLAIM_REFUSED_OCCLUDED = 7;
    public static final int MELEE_CANDIDATE_HULL_TESTED = 8;
    public static final int MELEE_CANDIDATE_HULL_IN_REACH = 9;
    public static final int SIGHT_TEST = 10;
    public static final int SIGHT_BLOCKED_BY_ARENA = 11;
    public static final int SIGHT_BLOCKED_BY_PLACED_BLOCK = 12;
    public static final int COBWEB_SIGHT_TEST = 13;
    public static final int COBWEB_SIGHT_CROSSED = 14;
    public static final int ARROW_CLAIM_ATTEMPT = 15;
    public static final int ARROW_CLAIM_SELF_TARGET = 16;
    public static final int ARROW_CLAIM_PEER_TARGET = 17;
    public static final int ARROW_CLAIM_GRANTED = 18;
    public static final int ARROW_CLAIM_REFUSED = 19;
    public static final int PROJECTILE_SPAWN_REFUSED = 20;
    public static final int EXPLODE = 21;
    public static final int BLAST_CELL_REMOVED = 22;
    public static final int BLAST_CELL_BUDGET_EXHAUSTED = 23;
    public static final int CRYSTAL_PLACED = 24;
    public static final int CRYSTAL_DETONATED_BY_HIT = 25;
    public static final int CRYSTAL_DESTROYED_BY_BLAST = 26;
    public static final int CRYSTAL_DESTROYED_BY_PROJECTILE = 27;
    public static final int ANCHOR_PLACED = 28;
    public static final int ANCHOR_CHARGED = 29;
    public static final int ANCHOR_DETONATED = 30;
    public static final int PLACED_BLOCK_ADDED = 31;
    public static final int PLACED_BLOCK_REMOVED = 32;
    public static final int ARENA_VOXEL_BROKEN_BY_MINING = 33;
    public static final int ARENA_VOXEL_BROKEN_BY_BLAST = 34;
    public static final int ARENA_VOXEL_BROKEN_BY_DECOR_PLACE = 35;
    public static final int COBWEB_ADDED = 36;
    public static final int COBWEB_REMOVED = 37;
    public static final int CONTAINER_OPENED = 38;
    public static final int CONTAINER_CLOSED = 39;
    public static final int ITEM_ENTITY_SPAWNED = 40;
    public static final int ITEM_ENTITY_REFUSED = 41;
    public static final int DURABILITY_DAMAGED = 42;
    public static final int ITEM_BROKEN = 43;
    public static final int ROUND_RESET = 44;
    public static final int CLICK_ATTACK_COUNTED_DRAIN = 45;
    public static final int CLICK_ATTACK_EDGE_DRAIN = 46;
    public static final int CLICK_USE_COUNTED_DRAIN = 47;
    public static final int CLICK_USE_UNCOUNTED_DRAIN = 48;
    public static final int CLICK_DROP_COUNTED_DRAIN = 49;
    public static final int CLICK_INV_COUNTED_DRAIN = 50;
    public static final int CLICK_SWAP_COUNTED_DRAIN = 51;

    public static final int ATTACK_REFUSED_BY_MISS_PENALTY = 52;
    public static final int MINING_REFUSED_BY_MISS_PENALTY = 53;
    public static final int MISS_PENALTY_ARMED = 54;

    public static final int MELEE_CLAIM_REFUSED_INSIDE_MIN_REACH = 55;

    public static final int PROJECTILE_SPAWN_BASE = 56;
    public static final int PROJECTILE_TYPES = 8;

    public static final int BLOCK_ACTION_BASE = PROJECTILE_SPAWN_BASE + PROJECTILE_TYPES;
    public static final int BLOCK_ACTIONS = 14;

    public static final int INV_ACTION_BASE = BLOCK_ACTION_BASE + BLOCK_ACTIONS;
    public static final int INV_ACTIONS = 14;

    public static final int USE_MAIN_BASE = INV_ACTION_BASE + INV_ACTIONS;
    public static final int USE_KINDS = 12;

    public static final int USE_OFFHAND_BASE = USE_MAIN_BASE + USE_KINDS;

    public static final int USE_FIRED_BASE = USE_OFFHAND_BASE + USE_KINDS;

    public static final int MELEE_CLAIM_GRANTED_OFF_AIM = USE_FIRED_BASE + USE_KINDS;

    public static final int INV_OP_ON_A_MOVING_FRAME = MELEE_CLAIM_GRANTED_OFF_AIM + 1;

    public static final int AUTHORITY_STAMP_WALKED = INV_OP_ON_A_MOVING_FRAME + 1;

    public static final int AUTHORITY_STAMP_CLIPPED = AUTHORITY_STAMP_WALKED + 1;

    public static final int AUTHORITY_STAMP_TOO_FAR = AUTHORITY_STAMP_CLIPPED + 1;

    public static final int AUTHORITY_STAMP_UNOWNED = AUTHORITY_STAMP_TOO_FAR + 1;

    public static final int AUTHORITY_STAMP_SUSPENDED = AUTHORITY_STAMP_UNOWNED + 1;

    public static final int AUTHORITY_STAMP_HELD = AUTHORITY_STAMP_SUSPENDED + 1;

    public static final int MELEE_CLAIM_REFUSED_BEHIND_THE_BACK = AUTHORITY_STAMP_HELD + 1;

    public static final int LEFT_CLICK_CLAIMED_TWO_TARGETS =
            MELEE_CLAIM_REFUSED_BEHIND_THE_BACK + 1;

    public static final int INV_OP_ON_A_COMBAT_FRAME = LEFT_CLICK_CLAIMED_TWO_TARGETS + 1;

    public static final int COUNTERS = INV_OP_ON_A_COMBAT_FRAME + 1;

    private static final String[] PROJECTILE_NAMES = {
            "arrow", "pearl", "snowball", "egg", "firework", "splash-potion", "xp-bottle",
            "wind-charge",
    };

    private static final String[] BLOCK_ACTION_NAMES = {
            "none", "place", "break", "place-crystal", "hit-crystal", "place-anchor",
            "charge-anchor", "detonate-anchor", "place-water", "place-lava", "pickup-fluid",
            "place-offhand", "open-container", "close-container",
    };

    private static final String[] INV_ACTION_NAMES = {
            "none", "move", "drop-one", "drop-stack", "container-take", "container-put",
            "pickup", "pickup-half", "swap-slot", "pickup-all", "drop-cursor-one",
            "drop-cursor-all", "cursor-resolve", "quick-move",
    };

    private static final String[] USE_KIND_NAMES = {
            "none", "pearl", "food", "snowball", "egg", "bow", "firework", "crossbow",
            "splash-potion", "shield", "xp-bottle", "wind-charge",
    };

    private static final String[] NAMES = buildNames();

    private static volatile long[] sink;

    private SimProbe() {
    }

    private static String[] buildNames() {
        String[] n = new String[COUNTERS];
        n[MELEE_CLAIM_ATTEMPT] = "melee-claim-attempt";
        n[MELEE_CLAIM_GRANTED] = "melee-claim-granted";
        n[MELEE_CLAIM_GRANTED_LIVE_HULL] = "melee-claim-granted-live-hull";
        n[MELEE_CLAIM_GRANTED_REWOUND_HULL] = "melee-claim-granted-rewound-hull";
        n[MELEE_CLAIM_GRANTED_OUTSIDE_LIVE_REACH] = "melee-claim-granted-outside-live-reach";
        n[MELEE_CLAIM_REFUSED] = "melee-claim-refused";
        n[MELEE_CLAIM_REFUSED_OUT_OF_REACH] = "melee-claim-refused-out-of-reach";
        n[MELEE_CLAIM_REFUSED_OCCLUDED] = "melee-claim-refused-occluded";
        n[MELEE_CLAIM_REFUSED_INSIDE_MIN_REACH] = "melee-claim-refused-inside-min-reach";
        n[MELEE_CANDIDATE_HULL_TESTED] = "melee-candidate-hull-tested";
        n[MELEE_CANDIDATE_HULL_IN_REACH] = "melee-candidate-hull-in-reach";
        n[SIGHT_TEST] = "sight-test";
        n[SIGHT_BLOCKED_BY_ARENA] = "sight-blocked-by-arena";
        n[SIGHT_BLOCKED_BY_PLACED_BLOCK] = "sight-blocked-by-placed-block";
        n[COBWEB_SIGHT_TEST] = "cobweb-sight-test";
        n[COBWEB_SIGHT_CROSSED] = "cobweb-sight-crossed";
        n[ARROW_CLAIM_ATTEMPT] = "arrow-claim-attempt";
        n[ARROW_CLAIM_SELF_TARGET] = "arrow-claim-self-target";
        n[ARROW_CLAIM_PEER_TARGET] = "arrow-claim-peer-target";
        n[ARROW_CLAIM_GRANTED] = "arrow-claim-granted";
        n[ARROW_CLAIM_REFUSED] = "arrow-claim-refused";
        n[PROJECTILE_SPAWN_REFUSED] = "projectile-spawn-refused";
        n[EXPLODE] = "explode";
        n[BLAST_CELL_REMOVED] = "blast-cell-removed";
        n[BLAST_CELL_BUDGET_EXHAUSTED] = "blast-cell-budget-exhausted";
        n[CRYSTAL_PLACED] = "crystal-placed";
        n[CRYSTAL_DETONATED_BY_HIT] = "crystal-detonated-by-hit";
        n[CRYSTAL_DESTROYED_BY_BLAST] = "crystal-destroyed-by-blast";
        n[CRYSTAL_DESTROYED_BY_PROJECTILE] = "crystal-destroyed-by-projectile";
        n[ANCHOR_PLACED] = "anchor-placed";
        n[ANCHOR_CHARGED] = "anchor-charged";
        n[ANCHOR_DETONATED] = "anchor-detonated";
        n[PLACED_BLOCK_ADDED] = "placed-block-added";
        n[PLACED_BLOCK_REMOVED] = "placed-block-removed";
        n[ARENA_VOXEL_BROKEN_BY_MINING] = "arena-voxel-broken-by-mining";
        n[ARENA_VOXEL_BROKEN_BY_BLAST] = "arena-voxel-broken-by-blast";
        n[ARENA_VOXEL_BROKEN_BY_DECOR_PLACE] = "arena-voxel-broken-by-decor-place";
        n[COBWEB_ADDED] = "cobweb-added";
        n[COBWEB_REMOVED] = "cobweb-removed";
        n[CONTAINER_OPENED] = "container-opened";
        n[CONTAINER_CLOSED] = "container-closed";
        n[ITEM_ENTITY_SPAWNED] = "item-entity-spawned";
        n[ITEM_ENTITY_REFUSED] = "item-entity-refused";
        n[DURABILITY_DAMAGED] = "durability-damaged";
        n[ITEM_BROKEN] = "item-broken";
        n[ROUND_RESET] = "round-reset";
        n[CLICK_ATTACK_COUNTED_DRAIN] = "click-attack-counted-drain";
        n[CLICK_ATTACK_EDGE_DRAIN] = "click-attack-edge-drain";
        n[CLICK_USE_COUNTED_DRAIN] = "click-use-counted-drain";
        n[CLICK_USE_UNCOUNTED_DRAIN] = "click-use-uncounted-drain";
        n[CLICK_DROP_COUNTED_DRAIN] = "click-drop-counted-drain";
        n[CLICK_INV_COUNTED_DRAIN] = "click-inv-counted-drain";
        n[CLICK_SWAP_COUNTED_DRAIN] = "click-swap-counted-drain";
        n[ATTACK_REFUSED_BY_MISS_PENALTY] = "attack-refused-by-miss-penalty";
        n[MINING_REFUSED_BY_MISS_PENALTY] = "mining-refused-by-miss-penalty";
        n[MISS_PENALTY_ARMED] = "miss-penalty-armed";
        n[MELEE_CLAIM_GRANTED_OFF_AIM] = "melee-claim-granted-off-aim";
        n[INV_OP_ON_A_MOVING_FRAME] = "inv-op-on-a-moving-frame";
        n[AUTHORITY_STAMP_WALKED] = "authority-stamp-walked";
        n[AUTHORITY_STAMP_CLIPPED] = "authority-stamp-clipped";
        n[AUTHORITY_STAMP_TOO_FAR] = "authority-stamp-too-far";
        n[AUTHORITY_STAMP_UNOWNED] = "authority-stamp-unowned";
        n[AUTHORITY_STAMP_SUSPENDED] = "authority-stamp-suspended";
        n[AUTHORITY_STAMP_HELD] = "authority-stamp-held";
        n[MELEE_CLAIM_REFUSED_BEHIND_THE_BACK] = "melee-claim-refused-behind-the-back";
        n[LEFT_CLICK_CLAIMED_TWO_TARGETS] = "left-click-claimed-two-targets";
        n[INV_OP_ON_A_COMBAT_FRAME] = "inv-op-on-a-combat-frame";
        for (int i = 0; i < PROJECTILE_TYPES; i++) {
            n[PROJECTILE_SPAWN_BASE + i] = "projectile-spawn-" + PROJECTILE_NAMES[i];
        }
        for (int i = 0; i < BLOCK_ACTIONS; i++) {
            n[BLOCK_ACTION_BASE + i] = "block-action-" + BLOCK_ACTION_NAMES[i];
        }
        for (int i = 0; i < INV_ACTIONS; i++) {
            n[INV_ACTION_BASE + i] = "inv-action-" + INV_ACTION_NAMES[i];
        }
        for (int i = 0; i < USE_KINDS; i++) {
            n[USE_MAIN_BASE + i] = "use-main-dispatch-" + USE_KIND_NAMES[i];
            n[USE_OFFHAND_BASE + i] = "use-offhand-dispatch-" + USE_KIND_NAMES[i];
            n[USE_FIRED_BASE + i] = "use-fired-" + USE_KIND_NAMES[i];
        }
        return n;
    }

    public static String name(int counter) {
        return NAMES[counter];
    }

    public static String[] names() {
        return NAMES.clone();
    }

    public static void install(long[] target) {
        if (target != null && target.length != COUNTERS) {
            throw new IllegalArgumentException("probe sink must have " + COUNTERS + " slots");
        }
        sink = target;
    }

    public static void uninstall() {
        sink = null;
    }

    public static boolean installed() {
        return sink != null;
    }

    public static void hit(int counter) {
        long[] t = sink;
        if (t != null && counter >= 0 && counter < t.length) {
            t[counter]++;
        }
    }

    public static void band(int base, int offset, int size) {
        long[] t = sink;
        if (t == null || offset < 0 || offset >= size) {
            return;
        }
        int counter = base + offset;
        if (counter >= 0 && counter < t.length) {
            t[counter]++;
        }
    }

    public static void add(int counter, long amount) {
        long[] t = sink;
        if (t != null && counter >= 0 && counter < t.length) {
            t[counter] += amount;
        }
    }
}
