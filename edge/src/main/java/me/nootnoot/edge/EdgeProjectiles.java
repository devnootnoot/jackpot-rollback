package me.nootnoot.edge;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class EdgeProjectiles implements EdgeCombatFx.Tints {

    private static final int WATER_COLOR = 0x385DC6;
    private static final int XP_BOTTLE_COLOR = -13083194;

    private static final int TINT_TTL_TICKS = 40;
    private static final int MAX_TINTS = 16;
    private static final double TINT_MATCH_RADIUS = 3.0;
    private static final int XP_ORBS_PER_BOTTLE = 3;

    private static final double MOVE_EPSILON = 1.0E-6;

    private final Player viewer;
    private final int localSlot;
    private final EdgeDrops drops;

    private final Map<Integer, Live> alive = new HashMap<>();
    private final List<Tint> tints = new ArrayList<>();
    private final EdgeEntityBands.Allocator ids =
            new EdgeEntityBands.Allocator(EdgeEntityBands.PROJECTILE_BASE,
                    EdgeEntityBands.PROJECTILE_SPAN);

    private int peerEntityId = -1;

    public EdgeProjectiles(Player viewer, int localSlot, EdgeDrops drops) {
        this.viewer = viewer;
        this.localSlot = localSlot;
        this.drops = drops;
    }

    public void peerEntityId(int entityId) {
        this.peerEntityId = entityId;
    }

    private static final class Live {
        private final int entityId;
        private final int type;
        private double x;
        private double y;
        private double z;
        private double vx;
        private double vy;
        private double vz;
        private boolean stuckPlayed;
        private int color;
        private boolean instant;

        private Live(int entityId, int type) {
            this.entityId = entityId;
            this.type = type;
        }
    }

    private record Tint(double x, double y, double z, int color, boolean instant, int expiresAt) {
    }

    public void render(GameState head) {
        if (!viewer.isOnline()) {
            return;
        }
        ageTints(head.tick);
        Set<Integer> seen = new HashSet<>();
        for (ProjectileState pr : head.projectiles) {
            if (pr.dead) {
                continue;
            }
            seen.add(pr.id);
            Live live = alive.get(pr.id);
            if (live == null) {
                live = spawn(head, pr);
                if (live == null) {
                    continue;
                }
                alive.put(pr.id, live);
            }
            drive(live, pr);
        }
        if (alive.isEmpty()) {
            return;
        }
        List<Integer> gone = new ArrayList<>();
        for (Map.Entry<Integer, Live> entry : alive.entrySet()) {
            if (!seen.contains(entry.getKey())) {
                gone.add(entry.getKey());
            }
        }
        for (int id : gone) {
            Live live = alive.remove(id);
            despawn(live, head);
        }
    }

    private Live spawn(GameState head, ProjectileState pr) {
        EntityType type = entityType(pr.type);
        if (type == null) {
            return null;
        }
        int entityId = ids.alloc();
        if (entityId < 0) {
            return null;
        }
        Live live = new Live(entityId, pr.type);
        live.x = pr.x;
        live.y = pr.y;
        live.z = pr.z;
        live.vx = pr.vx;
        live.vy = pr.vy;
        live.vz = pr.vz;
        live.color = pr.type == ProjectileState.TYPE_SPLASH_POTION ? splashColor(pr) : 0;
        live.instant = pr.type == ProjectileState.TYPE_SPLASH_POTION && instantSplash(pr);

        EdgePackets.spawn(viewer, entityId, UUID.randomUUID(), type, pr.x, pr.y, pr.z,
                yawOf(pr), pitchOf(pr), spawnData(pr), pr.vx, pr.vy, pr.vz);
        ItemStack carried = carried(pr);
        if (carried != null) {
            EdgePackets.carriedItem(viewer, entityId, carried);
        }
        playLaunch(head, pr);
        return live;
    }

    private void drive(Live live, ProjectileState pr) {
        double vx = pr.stuck ? 0.0 : pr.vx;
        double vy = pr.stuck ? 0.0 : pr.vy;
        double vz = pr.stuck ? 0.0 : pr.vz;
        boolean moving = vx * vx + vy * vy + vz * vz > MOVE_EPSILON;
        boolean moved = Math.abs(pr.x - live.x) > MOVE_EPSILON
                || Math.abs(pr.y - live.y) > MOVE_EPSILON
                || Math.abs(pr.z - live.z) > MOVE_EPSILON;
        boolean pushed = Math.abs(vx - live.vx) > MOVE_EPSILON
                || Math.abs(vy - live.vy) > MOVE_EPSILON
                || Math.abs(vz - live.vz) > MOVE_EPSILON;
        if (moved || moving || pushed) {
            live.x = pr.x;
            live.y = pr.y;
            live.z = pr.z;
            live.vx = vx;
            live.vy = vy;
            live.vz = vz;
            EdgePackets.teleport(viewer, live.entityId, pr.x, pr.y, pr.z, vx, vy, vz,
                    moving ? yawOf(pr) : 0.0f, moving ? pitchOf(pr) : 0.0f);
        }
        if (pr.type == ProjectileState.TYPE_ARROW && pr.stuck && !live.stuckPlayed) {
            live.stuckPlayed = true;
            viewer.playSound(at(live), Sound.ENTITY_ARROW_HIT, 1.0f,
                    1.2f / (rand() * 0.2f + 0.9f));
        }
    }

    private void despawn(Live live, GameState head) {
        if (live == null) {
            return;
        }
        EdgePackets.destroy(viewer, live.entityId);
        ids.free(live.entityId);
        switch (live.type) {
            case ProjectileState.TYPE_PEARL -> viewer.playSound(at(live),
                    Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
            case ProjectileState.TYPE_SPLASH_POTION -> rememberTint(head, live);
            case ProjectileState.TYPE_XP_BOTTLE -> breakXpBottle(live);
            default -> {
            }
        }
    }

    private void rememberTint(GameState head, Live live) {
        int color = live.color == 0 ? WATER_COLOR : live.color;
        if (tints.size() >= MAX_TINTS) {
            tints.remove(0);
        }
        tints.add(new Tint(live.x, live.y, live.z, color, live.instant,
                head.tick + TINT_TTL_TICKS));
    }

    private void breakXpBottle(Live live) {
        viewer.playSound(at(live), Sound.ENTITY_SPLASH_POTION_BREAK, 1.0f, rand() * 0.2f + 0.9f);
        EdgePackets.levelEvent(viewer, EdgePackets.LEVEL_EVENT_POTION_SPLASH,
                floor(live.x), floor(live.y), floor(live.z), XP_BOTTLE_COLOR);
        if (drops != null) {
            drops.spawnOrbs(live.x, live.y, live.z, XP_ORBS_PER_BOTTLE);
        }
    }

    @Override
    public EdgeCombatFx.Splash splashAt(int x, int y, int z) {
        double cx = x + 0.5;
        double cy = y + 0.5;
        double cz = z + 0.5;
        Tint best = null;
        double bestDistance = TINT_MATCH_RADIUS * TINT_MATCH_RADIUS;
        for (Tint tint : tints) {
            double dx = tint.x() - cx;
            double dy = tint.y() - cy;
            double dz = tint.z() - cz;
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = tint;
            }
        }
        if (best == null) {
            return null;
        }
        tints.remove(best);
        return new EdgeCombatFx.Splash(best.color(), best.instant());
    }

    private void ageTints(int tick) {
        if (tints.isEmpty()) {
            return;
        }
        tints.removeIf(tint -> tint.expiresAt() <= tick);
    }

    private void playLaunch(GameState head, ProjectileState pr) {
        Location at = new Location(viewer.getWorld(), pr.x, pr.y, pr.z);
        if (pr.type == ProjectileState.TYPE_ARROW) {
            if (!fromCrossbow(head, pr)) {
                viewer.playSound(at, Sound.ENTITY_ARROW_SHOOT, 1.0f,
                        1.0f / (rand() * 0.4f + 1.2f) + 0.5f);
            }
            return;
        }
        float pitch = 0.4f / (rand() * 0.4f + 0.8f);
        Sound sound = switch (pr.type) {
            case ProjectileState.TYPE_PEARL -> Sound.ENTITY_ENDER_PEARL_THROW;
            case ProjectileState.TYPE_SNOWBALL -> Sound.ENTITY_SNOWBALL_THROW;
            case ProjectileState.TYPE_EGG -> Sound.ENTITY_EGG_THROW;
            case ProjectileState.TYPE_SPLASH_POTION -> Sound.ENTITY_SPLASH_POTION_THROW;
            case ProjectileState.TYPE_XP_BOTTLE -> Sound.ENTITY_EXPERIENCE_BOTTLE_THROW;
            case ProjectileState.TYPE_WIND_CHARGE -> Sound.ENTITY_WIND_CHARGE_THROW;
            case ProjectileState.TYPE_FIREWORK -> Sound.ENTITY_FIREWORK_ROCKET_LAUNCH;
            default -> null;
        };
        if (sound != null) {
            viewer.playSound(at, sound, pr.type == ProjectileState.TYPE_FIREWORK ? 1.0f : 0.5f,
                    pr.type == ProjectileState.TYPE_FIREWORK ? 1.0f : pitch);
        }
    }

    private static boolean fromCrossbow(GameState head, ProjectileState pr) {
        if (pr.owner < 0 || pr.owner >= head.players.length) {
            return false;
        }
        PlayerState owner = head.players[pr.owner];
        return owner.heldUseKind == Combat.USE_CROSSBOW;
    }

    private int spawnData(ProjectileState pr) {
        if (pr.type != ProjectileState.TYPE_ARROW) {
            return 0;
        }
        int shooter = pr.owner == localSlot ? viewer.getEntityId() : peerEntityId;
        return shooter < 0 ? 0 : shooter + 1;
    }

    private static EntityType entityType(int type) {
        return switch (type) {
            case ProjectileState.TYPE_ARROW -> EntityTypes.ARROW;
            case ProjectileState.TYPE_PEARL -> EntityTypes.ENDER_PEARL;
            case ProjectileState.TYPE_SNOWBALL -> EntityTypes.SNOWBALL;
            case ProjectileState.TYPE_EGG -> EntityTypes.EGG;
            case ProjectileState.TYPE_FIREWORK -> EntityTypes.FIREWORK_ROCKET;
            case ProjectileState.TYPE_SPLASH_POTION -> EntityTypes.SPLASH_POTION;
            case ProjectileState.TYPE_XP_BOTTLE -> EntityTypes.EXPERIENCE_BOTTLE;
            case ProjectileState.TYPE_WIND_CHARGE -> EntityTypes.WIND_CHARGE;
            default -> null;
        };
    }

    private static ItemStack carried(ProjectileState pr) {
        Material material = switch (pr.type) {
            case ProjectileState.TYPE_PEARL -> Material.ENDER_PEARL;
            case ProjectileState.TYPE_SNOWBALL -> Material.SNOWBALL;
            case ProjectileState.TYPE_EGG -> Material.EGG;
            case ProjectileState.TYPE_FIREWORK -> Material.FIREWORK_ROCKET;
            case ProjectileState.TYPE_XP_BOTTLE -> Material.EXPERIENCE_BOTTLE;
            case ProjectileState.TYPE_SPLASH_POTION -> Material.SPLASH_POTION;
            default -> null;
        };
        if (material == null) {
            return null;
        }
        ItemStack stack = new ItemStack(material);
        if (material != Material.SPLASH_POTION) {
            return stack;
        }
        if (stack.getItemMeta() instanceof PotionMeta meta) {
            boolean any = false;
            for (int i = 0; i < 4; i++) {
                int packed = pr.effect(i);
                if (packed == 0) {
                    continue;
                }
                PotionEffectType type = EdgeLoadout.bukkitEffect(ItemDict.effectId(packed));
                if (type == null) {
                    continue;
                }
                any = true;
                meta.addCustomEffect(new PotionEffect(type,
                        Math.max(1, ItemDict.effectDuration(packed)),
                        ItemDict.effectAmplifier(packed), false, true, true), true);
            }
            if (any) {
                meta.setColor(Color.fromRGB(splashColor(pr) & 0xFFFFFF));
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static boolean instantSplash(ProjectileState pr) {
        for (int i = 0; i < 4; i++) {
            int packed = pr.effect(i);
            if (packed == 0) {
                continue;
            }
            int id = ItemDict.effectId(packed);
            if (id == Effects.INSTANT_HEALTH || id == Effects.INSTANT_DAMAGE) {
                return true;
            }
        }
        return false;
    }

    private static int splashColor(ProjectileState pr) {
        int red = 0;
        int green = 0;
        int blue = 0;
        int total = 0;
        for (int i = 0; i < 4; i++) {
            int packed = pr.effect(i);
            if (packed == 0) {
                continue;
            }
            PotionEffectType type = EdgeLoadout.bukkitEffect(ItemDict.effectId(packed));
            Color color = colorOf(type);
            if (color == null) {
                continue;
            }
            int weight = ItemDict.effectAmplifier(packed) + 1;
            red += color.getRed() * weight;
            green += color.getGreen() * weight;
            blue += color.getBlue() * weight;
            total += weight;
        }
        if (total == 0) {
            return WATER_COLOR;
        }
        return ((red / total) << 16) | ((green / total) << 8) | (blue / total);
    }

    private static Color colorOf(PotionEffectType type) {
        if (type == null) {
            return null;
        }
        try {
            return type.getColor();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static float yawOf(ProjectileState pr) {
        return (float) (Math.atan2(pr.vx, pr.vz) * 180.0 / Math.PI);
    }

    private static float pitchOf(ProjectileState pr) {
        double horizontal = Math.sqrt(pr.vx * pr.vx + pr.vz * pr.vz);
        return (float) (Math.atan2(pr.vy, horizontal) * 180.0 / Math.PI);
    }

    private Location at(Live live) {
        return new Location(viewer.getWorld(), live.x, live.y, live.z);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static float rand() {
        return ThreadLocalRandom.current().nextFloat();
    }

    public void clear() {
        for (Live live : alive.values()) {
            EdgePackets.destroy(viewer, live.entityId);
        }
        alive.clear();
        tints.clear();
        ids.reset();
    }
}
