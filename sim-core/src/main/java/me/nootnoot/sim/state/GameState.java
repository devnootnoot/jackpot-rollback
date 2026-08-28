package me.nootnoot.sim.state;

import java.util.ArrayList;
import java.util.List;

public final class GameState {
    public int tick;
    public final PlayerState[] players = {new PlayerState(), new PlayerState()};
    public final List<ProjectileState> projectiles = new ArrayList<>();
    public int nextProjectileId;

    public BlockStore blocks = new BlockStore();

    public final List<CrystalState> crystals = new ArrayList<>();
    public int nextCrystalId;

    public java.util.HashMap<Long, Integer> anchors = new java.util.HashMap<>();

    public final List<ItemEntityState> items = new ArrayList<>();
    public int nextItemId;

    public int itemsRefused;

    public final ItemGrid itemGrid = new ItemGrid();

    public java.util.HashSet<Long> brokenArena = new java.util.HashSet<>();

    public boolean vanillaBuild;

    public boolean allowExplosion = true;

    public boolean allowBucket = true;

    public boolean potSwordBoost;

    public static final int[] NO_ITEM_IDS = new int[0];

    public int[] breakableItemIds = NO_ITEM_IDS;

    public int[] placeableItemIds = NO_ITEM_IDS;

    public java.util.HashMap<Long, Float> blockResistance = new java.util.HashMap<>();

    public java.util.HashMap<Long, Integer> fluids = new java.util.HashMap<>();

    public java.util.HashMap<Long, Integer> cobwebs = new java.util.HashMap<>();

    public java.util.HashMap<Long, Integer> fires = new java.util.HashMap<>();

    public int cobwebItemId;

    public int stringItemId;

    public int obsidianItemId;
    public int cobblestoneItemId;

    public int mudItemId;

    public int glowstoneItemId;
    public int glowstoneDustItemId;

    public double playCenterX;
    public double playCenterZ;
    public double playRadius = Double.POSITIVE_INFINITY;
    public boolean playCircular;

    public int roundWinsP0;
    public int roundWinsP1;
    public int roundsTarget = 1;
    public int roundResetCountdown;

    public boolean awaitingReady;
    public int roundStartGrace;
    public boolean roundMatchOver;
    public int roundMatchWinner = -1;

    public PlayerState[] roundInitial;

    public ItemDict dict = ItemDict.empty();

    public BlockProps blockProps = BlockProps.empty();

    public final boolean[] edgeHosted = new boolean[2];

    public java.util.HashMap<Integer, Container> containers = new java.util.HashMap<>();

    public java.util.HashMap<Long, Integer> blockContainers = new java.util.HashMap<>();

    public java.util.HashMap<Integer, Container> roundInitialContainers = new java.util.HashMap<>();

    public int nextContainerId;

    public final List<CombatEvent> events = new ArrayList<>();

    public static final int BLAST_CELLS_PER_TICK = 2048;

    public static final int BLAST_MARCH_CELLS_PER_TICK = 262144;

    public int blastCellBudget = BLAST_CELLS_PER_TICK;

    public int blastMarchBudget = BLAST_MARCH_CELLS_PER_TICK;

    public int blastSeq;

    public GameState copy() {
        GameState g = new GameState();
        g.tick = tick;
        g.players[0] = players[0].copy();
        g.players[1] = players[1].copy();
        g.nextProjectileId = nextProjectileId;
        g.roundWinsP0 = roundWinsP0;
        g.roundWinsP1 = roundWinsP1;
        g.roundsTarget = roundsTarget;
        g.roundResetCountdown = roundResetCountdown;
        g.awaitingReady = awaitingReady;
        g.roundStartGrace = roundStartGrace;
        g.roundMatchOver = roundMatchOver;
        g.roundMatchWinner = roundMatchWinner;
        if (roundInitial != null) {
            g.roundInitial = new PlayerState[roundInitial.length];
            for (int i = 0; i < roundInitial.length; i++) {
                g.roundInitial[i] = roundInitial[i] == null ? null : roundInitial[i].copy();
            }
        }
        for (ProjectileState p : projectiles) {
            g.projectiles.add(p.copy());
        }
        g.blocks = blocks.copy();
        for (CrystalState c : crystals) {
            g.crystals.add(c.copy());
        }
        g.nextCrystalId = nextCrystalId;
        g.anchors = new java.util.HashMap<>(anchors);
        for (ItemEntityState e : items) {
            g.items.add(e.copy());
        }
        g.nextItemId = nextItemId;
        g.itemsRefused = itemsRefused;
        g.brokenArena = new java.util.HashSet<>(brokenArena);
        g.vanillaBuild = vanillaBuild;
        g.allowExplosion = allowExplosion;
        g.allowBucket = allowBucket;
        g.potSwordBoost = potSwordBoost;
        g.breakableItemIds = breakableItemIds;
        g.placeableItemIds = placeableItemIds;
        g.blockResistance = new java.util.HashMap<>(blockResistance);
        g.fluids = new java.util.HashMap<>(fluids);
        g.cobwebs = new java.util.HashMap<>(cobwebs);
        g.fires = new java.util.HashMap<>(fires);
        g.cobwebItemId = cobwebItemId;
        g.stringItemId = stringItemId;
        g.obsidianItemId = obsidianItemId;
        g.cobblestoneItemId = cobblestoneItemId;
        g.mudItemId = mudItemId;
        g.glowstoneItemId = glowstoneItemId;
        g.glowstoneDustItemId = glowstoneDustItemId;
        g.playCenterX = playCenterX;
        g.playCenterZ = playCenterZ;
        g.playRadius = playRadius;
        g.playCircular = playCircular;
        g.dict = dict;
        g.blockProps = blockProps;
        g.edgeHosted[0] = edgeHosted[0];
        g.edgeHosted[1] = edgeHosted[1];
        g.containers = new java.util.HashMap<>();
        for (java.util.Map.Entry<Integer, Container> e : containers.entrySet()) {
            g.containers.put(e.getKey(), e.getValue().copy());
        }
        g.blockContainers = new java.util.HashMap<>(blockContainers);
        g.roundInitialContainers = new java.util.HashMap<>();
        for (java.util.Map.Entry<Integer, Container> e : roundInitialContainers.entrySet()) {
            g.roundInitialContainers.put(e.getKey(), e.getValue().copy());
        }
        g.nextContainerId = nextContainerId;
        g.events.addAll(events);
        g.blastCellBudget = blastCellBudget;
        g.blastMarchBudget = blastMarchBudget;
        g.blastSeq = blastSeq;
        return g;
    }

    public boolean isInsidePlayableArea(double x, double z) {
        if (Double.isInfinite(playRadius)) {
            return true;
        }
        double dx = x - playCenterX;
        double dz = z - playCenterZ;
        if (playCircular) {
            return dx * dx + dz * dz <= playRadius * playRadius;
        }
        return Math.abs(dx) <= playRadius && Math.abs(dz) <= playRadius;
    }
}
