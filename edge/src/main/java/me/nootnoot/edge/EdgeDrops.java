package me.nootnoot.edge;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class EdgeDrops {

    private static final double COLLECT_RANGE = 1.75;
    private static final double COLLECT_RANGE_SQ = COLLECT_RANGE * COLLECT_RANGE;
    private static final double BODY_CENTRE = 0.9;

    private static final double ORB_STEP = 0.45;
    private static final double ORB_ABSORB = 0.9;
    private static final int ORB_LIFE_TICKS = 200;
    private static final int MAX_ORBS = 32;
    private static final int ORB_VALUE = 3;

    private static final double MOVE_EPSILON = 1.0E-4;

    private final Player viewer;
    private final int localSlot;

    private final Map<Integer, Live> alive = new HashMap<>();
    private final List<Orb> orbs = new ArrayList<>();
    private final EdgeEntityBands.Allocator itemIds =
            new EdgeEntityBands.Allocator(EdgeEntityBands.ITEM_BASE, EdgeEntityBands.ITEM_SPAN);
    private final EdgeEntityBands.Allocator orbIds =
            new EdgeEntityBands.Allocator(EdgeEntityBands.ORB_BASE, EdgeEntityBands.ORB_SPAN);

    private int peerEntityId = -1;
    private int shownPickupSeq = Integer.MIN_VALUE;

    public EdgeDrops(Player viewer, int localSlot) {
        this.viewer = viewer;
        this.localSlot = localSlot;
    }

    public void peerEntityId(int entityId) {
        this.peerEntityId = entityId;
    }

    private static final class Live {
        private final int entityId;
        private int count;
        private double x;
        private double y;
        private double z;

        private Live(int entityId) {
            this.entityId = entityId;
        }
    }

    private static final class Orb {
        private final int entityId;
        private double x;
        private double y;
        private double z;
        private int life;

        private Orb(int entityId, double x, double y, double z) {
            this.entityId = entityId;
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public void render(GameState head, GameState confirmed) {
        if (!viewer.isOnline()) {
            return;
        }
        renderItems(head);
        tickOrbs(head);
        mirrorPickups(confirmed);
    }

    private void renderItems(GameState head) {
        Set<Integer> seen = new HashSet<>();
        for (ItemEntityState item : head.items) {
            if (item.dead) {
                continue;
            }
            seen.add(item.id);
            Live live = alive.get(item.id);
            if (live == null) {
                int entityId = itemIds.alloc();
                if (entityId < 0) {
                    continue;
                }
                live = new Live(entityId);
                live.x = item.x;
                live.y = item.y;
                live.z = item.z;
                live.count = item.count;
                alive.put(item.id, live);
                EdgePackets.spawn(viewer, entityId, UUID.randomUUID(), EntityTypes.ITEM,
                        item.x, item.y, item.z, 0.0f, 0.0f, 0);
                EdgePackets.carriedItem(viewer, entityId, EdgeStacks.plain(item.itemId, item.count));
                continue;
            }
            if (live.count != item.count) {
                live.count = item.count;
                EdgePackets.carriedItem(viewer, live.entityId,
                        EdgeStacks.plain(item.itemId, item.count));
            }
            if (Math.abs(item.x - live.x) > MOVE_EPSILON || Math.abs(item.y - live.y) > MOVE_EPSILON
                    || Math.abs(item.z - live.z) > MOVE_EPSILON) {
                live.x = item.x;
                live.y = item.y;
                live.z = item.z;
                EdgePackets.teleport(viewer, live.entityId, item.x, item.y, item.z, 0.0f, 0.0f);
            }
        }
        if (alive.isEmpty()) {
            return;
        }
        List<Integer> gone = new ArrayList<>();
        for (int simId : alive.keySet()) {
            if (!seen.contains(simId)) {
                gone.add(simId);
            }
        }
        for (int simId : gone) {
            Live live = alive.remove(simId);
            int collector = nearestPlayer(head, live.x, live.y, live.z, COLLECT_RANGE_SQ);
            if (collector >= 0) {
                EdgePackets.collect(viewer, live.entityId, collector, Math.max(1, live.count));
            }
            EdgePackets.destroy(viewer, live.entityId);
            itemIds.free(live.entityId);
        }
    }

    public void spawnOrbs(double x, double y, double z, int count) {
        if (!viewer.isOnline()) {
            return;
        }
        for (int i = 0; i < count && orbs.size() < MAX_ORBS; i++) {
            int entityId = orbIds.alloc();
            if (entityId < 0) {
                return;
            }
            double ox = x + (rand() - 0.5) * 0.4;
            double oy = y + rand() * 0.3;
            double oz = z + (rand() - 0.5) * 0.4;
            orbs.add(new Orb(entityId, ox, oy, oz));
            EdgePackets.spawnOrb(viewer, entityId, ox, oy, oz, ORB_VALUE);
        }
    }

    private void tickOrbs(GameState head) {
        if (orbs.isEmpty()) {
            return;
        }
        orbs.removeIf(orb -> {
            if (++orb.life > ORB_LIFE_TICKS) {
                EdgePackets.destroy(viewer, orb.entityId);
                orbIds.free(orb.entityId);
                return true;
            }
            int target = nearestSlot(head, orb.x, orb.y, orb.z);
            if (target < 0) {
                return false;
            }
            PlayerState towards = head.players[target];
            double dx = towards.x - orb.x;
            double dy = towards.y + BODY_CENTRE - orb.y;
            double dz = towards.z - orb.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance <= ORB_ABSORB) {
                EdgePackets.collect(viewer, orb.entityId, entityIdOf(target), 1);
                EdgePackets.destroy(viewer, orb.entityId);
                orbIds.free(orb.entityId);
                if (target == localSlot) {
                    viewer.playSound(viewer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.1f,
                            0.5f * ((rand() - rand()) * 0.7f + 1.8f));
                }
                return true;
            }
            double step = Math.min(ORB_STEP, distance);
            orb.x += dx / distance * step;
            orb.y += dy / distance * step;
            orb.z += dz / distance * step;
            EdgePackets.teleport(viewer, orb.entityId, orb.x, orb.y, orb.z, 0.0f, 0.0f);
            return false;
        });
    }

    private void mirrorPickups(GameState confirmed) {
        if (confirmed == null) {
            return;
        }
        PlayerState mine = confirmed.players[localSlot];
        if (shownPickupSeq == Integer.MIN_VALUE) {
            shownPickupSeq = mine.pickupSeq;
            return;
        }
        if (mine.pickupSeq == shownPickupSeq) {
            return;
        }
        int from = Math.max(shownPickupSeq, mine.pickupSeq - PlayerState.PICKUP_RING);
        boolean any = false;
        for (int seq = from + 1; seq <= mine.pickupSeq; seq++) {
            int ring = Math.floorMod(seq, PlayerState.PICKUP_RING);
            int itemId = mine.pickupRingItemId[ring];
            if (itemId == 0) {
                continue;
            }
            ItemStack picked = EdgeStacks.plain(itemId, Math.max(1, mine.pickupRingCount[ring]));
            if (picked == null) {
                continue;
            }
            viewer.getInventory().addItem(picked);
            any = true;
        }
        shownPickupSeq = mine.pickupSeq;
        if (any) {
            viewer.playSound(viewer.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.2f,
                    (rand() - rand()) * 1.4f + 2.0f);
        }
    }

    private int nearestPlayer(GameState head, double x, double y, double z, double limitSq) {
        int slot = nearestSlot(head, x, y, z);
        if (slot < 0) {
            return -1;
        }
        PlayerState p = head.players[slot];
        double dx = p.x - x;
        double dy = p.y + BODY_CENTRE - y;
        double dz = p.z - z;
        if (dx * dx + dy * dy + dz * dz > limitSq) {
            return -1;
        }
        return entityIdOf(slot);
    }

    private static int nearestSlot(GameState head, double x, double y, double z) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int slot = 0; slot < head.players.length; slot++) {
            PlayerState p = head.players[slot];
            if (p.dead) {
                continue;
            }
            double dx = p.x - x;
            double dy = p.y + BODY_CENTRE - y;
            double dz = p.z - z;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = slot;
            }
        }
        return best;
    }

    private int entityIdOf(int slot) {
        return slot == localSlot ? viewer.getEntityId() : peerEntityId;
    }

    private static float rand() {
        return ThreadLocalRandom.current().nextFloat();
    }

    public void clear() {
        for (Live live : alive.values()) {
            EdgePackets.destroy(viewer, live.entityId);
        }
        alive.clear();
        for (Orb orb : orbs) {
            EdgePackets.destroy(viewer, orb.entityId);
        }
        orbs.clear();
        itemIds.reset();
        orbIds.reset();
    }
}
