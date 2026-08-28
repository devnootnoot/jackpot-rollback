package me.nootnoot.edge;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class EdgeCombatFx {

    public record Splash(int color, boolean instant) {
    }

    public interface Tints {
        Splash splashAt(int x, int y, int z);
    }

    public static final int STATUS_DEATH = 3;
    public static final int STATUS_TOTEM = 35;

    public static final int SWING_USE_HAND = 1;

    private static final float DEG_TO_RAD = (float) Math.PI / 180.0f;

    private static final double SWEEP_SPEED_LIMIT_SQ = 0.25 * 0.25;

    private static final double BODY_CENTRE = 0.9;
    private static final double HEAD_HEIGHT = 1.0;

    private static final float DAMAGE_INDICATOR_THRESHOLD = 2.0f;
    private static final double DAMAGE_INDICATOR_SPREAD = 0.1;
    private static final double DAMAGE_INDICATOR_SPEED = 0.2;

    private static final int SMASH_RING = 12;
    private static final int SMASH_RING_HEAVY = 24;
    private static final double SMASH_RING_SPEED = 0.3;
    private static final double SMASH_RING_SPEED_HEAVY = 0.5;

    private static final int WATER_COLOR = 0x385DC6;
    private static final int FIZZ_PARTICLES = 8;

    private final Player player;
    private final int localSlot;
    private final EdgeLoadout loadout;

    private final int[] lastHurtSoundTick = {Integer.MIN_VALUE, Integer.MIN_VALUE};

    private int peerEntityId = -1;
    private int lastHeadFrame = Integer.MIN_VALUE;
    private boolean knockbackPending;
    private Tints tints;

    public EdgeCombatFx(Player player, int localSlot, EdgeLoadout loadout) {
        this.player = player;
        this.localSlot = localSlot;
        this.loadout = loadout;
    }

    public void peerEntityId(int entityId) {
        this.peerEntityId = entityId;
    }

    public void tints(Tints source) {
        this.tints = source;
    }

    public boolean pollKnockback() {
        boolean pending = knockbackPending;
        knockbackPending = false;
        return pending;
    }

    public void playConfirmed(List<CombatEvent> events, GameState state) {
        if (!player.isOnline() || events.isEmpty()) {
            return;
        }
        for (CombatEvent e : events) {
            switch (e.type()) {
                case CombatEvent.HIT -> {
                    if (e.attacker() != localSlot) {
                        playHit(e, state);
                    }
                }
                case CombatEvent.SWING -> playUseSwing(e);
                case CombatEvent.BLOCK -> playBlock(e, state);
                case CombatEvent.TOTEM -> playTotem(e);
                case CombatEvent.DEATH -> playDeath(e, state);
                case CombatEvent.EXPLOSION -> playBlast(e);
                case CombatEvent.POTION_SPLASH -> playPotionSplash(e);
                case CombatEvent.FIRE_EXTINGUISH, CombatEvent.FLUID_SOLIDIFY -> playFizz(e);
                case CombatEvent.BUCKET_FILL, CombatEvent.BUCKET_EMPTY -> playBucket(e);
                case CombatEvent.ITEM_BREAK -> playItemBreak(e, state);
                default -> {
                }
            }
        }
    }

    public void playLocal(List<CombatEvent> events, GameState head, int headFrame) {
        if (!player.isOnline() || headFrame <= lastHeadFrame) {
            return;
        }
        lastHeadFrame = headFrame;
        for (CombatEvent e : events) {
            if (e.type() == CombatEvent.HIT && e.attacker() == localSlot) {
                playHit(e, head);
            }
        }
    }

    private void playHit(CombatEvent e, GameState state) {
        PlayerState victim = slotState(state, e.victim());
        if (victim == null) {
            return;
        }
        PlayerState attacker = slotState(state, e.attacker());
        Location victimAt = at(victim, HEAD_HEIGHT);
        boolean sweep = sweeping(e, attacker);

        if (e.crit()) {
            critParticles(e.victim());
        }

        Sound attackSound = attackSound(e, sweep);
        if (attackSound != null) {
            Location source = e.kind() == CombatEvent.HIT_ARROW || e.kind() == CombatEvent.HIT_FALL
                    || attacker == null ? victimAt : at(attacker, HEAD_HEIGHT);
            player.playSound(source, attackSound, 1.0f, 1.0f);
        }
        if (sweep && attacker != null) {
            sweepParticles(attacker);
        }
        if (e.kind() == CombatEvent.HIT_SMASH || e.kind() == CombatEvent.HIT_SMASH_HEAVY) {
            smashParticles(victim, e.kind() == CombatEvent.HIT_SMASH_HEAVY);
        }
        if (victim.lastDamage > DAMAGE_INDICATOR_THRESHOLD) {
            player.spawnParticle(Particle.DAMAGE_INDICATOR, at(victim, BODY_CENTRE),
                    (int) (victim.lastDamage * 0.5f), DAMAGE_INDICATOR_SPREAD, 0.0,
                    DAMAGE_INDICATOR_SPREAD, DAMAGE_INDICATOR_SPEED);
        }
        if (e.kind() == CombatEvent.HIT_FIRE) {
            return;
        }
        int victimSlot = e.victim();
        if (victimSlot >= 0 && victimSlot < lastHurtSoundTick.length) {
            if (lastHurtSoundTick[victimSlot] == state.tick) {
                return;
            }
            lastHurtSoundTick[victimSlot] = state.tick;
        }
        player.playSound(victimAt, Sound.ENTITY_PLAYER_HURT, 1.0f, hurtPitch());
    }

    private void playUseSwing(CombatEvent e) {
        if (e.kind() != SWING_USE_HAND || e.attacker() == localSlot || peerEntityId < 0) {
            return;
        }
        EdgePackets.swing(player, peerEntityId);
    }

    private void playBlock(CombatEvent e, GameState state) {
        PlayerState blocker = slotState(state, e.victim());
        if (blocker == null) {
            return;
        }
        Location at = at(blocker, HEAD_HEIGHT);
        player.playSound(at, Sound.ITEM_SHIELD_BLOCK, 1.0f, hurtPitch());
        if (e.crit()) {
            player.playSound(at, Sound.ITEM_SHIELD_BREAK, 1.0f, 1.0f);
        }
        if (e.victim() == localSlot) {
            knockbackPending = true;
        }
    }

    private void playTotem(CombatEvent e) {
        int entityId = entityIdOf(e.victim());
        if (entityId < 0) {
            return;
        }
        EdgePackets.status(player, entityId, STATUS_TOTEM);
    }

    private void playDeath(CombatEvent e, GameState state) {
        PlayerState victim = slotState(state, e.victim());
        if (victim == null) {
            return;
        }
        player.playSound(at(victim, HEAD_HEIGHT), Sound.ENTITY_PLAYER_DEATH, 1.0f, hurtPitch());
    }

    private void playBlast(CombatEvent e) {
        Location at = cell(e);
        if (e.crit()) {
            player.playSound(at, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 0.9f + rand() * 0.2f);
            player.spawnParticle(Particle.GUST_EMITTER_SMALL, at, 1, 0.0, 0.0, 0.0, 0.0);
            return;
        }
        player.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 1.0f + (rand() - rand()) * 0.2f);
        player.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0.0, 0.0, 0.0, 0.0);
    }

    private void playPotionSplash(CombatEvent e) {
        Splash splash = tints == null ? null : tints.splashAt(e.attacker(), e.victim(), e.kind());
        int color = splash == null ? WATER_COLOR : splash.color();
        boolean instant = splash != null && splash.instant();
        EdgePackets.levelEvent(player, instant
                        ? EdgePackets.LEVEL_EVENT_INSTANT_POTION_SPLASH
                        : EdgePackets.LEVEL_EVENT_POTION_SPLASH,
                e.attacker(), e.victim(), e.kind(), color & 0xFFFFFF);
    }

    private void playFizz(CombatEvent e) {
        Location at = cell(e);
        player.playSound(at, Sound.BLOCK_FIRE_EXTINGUISH, 1.0f, 2.6f + (rand() - rand()) * 0.8f);
        player.spawnParticle(Particle.LARGE_SMOKE, at, FIZZ_PARTICLES, 0.25, 0.25, 0.25, 0.02);
    }

    private void playItemBreak(CombatEvent e, GameState state) {
        PlayerState owner = slotState(state, e.attacker());
        if (owner == null) {
            return;
        }
        player.playSound(at(owner, HEAD_HEIGHT), Sound.ENTITY_ITEM_BREAK, 0.8f,
                0.8f + (rand() - rand()) * 0.4f);
    }

    private void playBucket(CombatEvent e) {
        Location at = cell(e);
        boolean lava = e.crit();
        boolean fill = e.type() == CombatEvent.BUCKET_FILL;
        Sound sound = fill
                ? (lava ? Sound.ITEM_BUCKET_FILL_LAVA : Sound.ITEM_BUCKET_FILL)
                : (lava ? Sound.ITEM_BUCKET_EMPTY_LAVA : Sound.ITEM_BUCKET_EMPTY);
        player.playSound(at, sound, 1.0f, 1.0f);
    }

    private void critParticles(int victimSlot) {
        int entityId = entityIdOf(victimSlot);
        if (entityId < 0) {
            return;
        }
        EdgePackets.criticalHit(player, entityId);
    }

    private void sweepParticles(PlayerState attacker) {
        double dx = -Math.sin(attacker.yaw * DEG_TO_RAD);
        double dz = Math.cos(attacker.yaw * DEG_TO_RAD);
        World world = player.getWorld();
        player.spawnParticle(Particle.SWEEP_ATTACK,
                new Location(world, attacker.x + dx, attacker.y + BODY_CENTRE, attacker.z + dz),
                0, dx, 0.0, dz, 0.0);
    }

    private void smashParticles(PlayerState victim, boolean heavy) {
        World world = player.getWorld();
        Location feet = new Location(world, victim.x, victim.y, victim.z);
        player.playSound(feet, heavy ? Sound.ITEM_MACE_SMASH_GROUND_HEAVY : Sound.ITEM_MACE_SMASH_GROUND,
                1.0f, 1.0f);
        int steps = heavy ? SMASH_RING_HEAVY : SMASH_RING;
        double speed = heavy ? SMASH_RING_SPEED_HEAVY : SMASH_RING_SPEED;
        for (int i = 0; i < steps; i++) {
            double angle = i * (Math.PI * 2.0 / steps);
            player.spawnParticle(Particle.POOF, feet, 0,
                    Math.cos(angle) * speed, 0.05, Math.sin(angle) * speed, 1.0);
        }
        player.spawnParticle(Particle.EXPLOSION, feet, 1, 0.0, 0.0, 0.0, 0.0);
    }

    private boolean sweeping(CombatEvent e, PlayerState attacker) {
        if (e.kind() != CombatEvent.HIT_STRONG || attacker == null || !attacker.onGround) {
            return false;
        }
        if (attacker.vx * attacker.vx + attacker.vz * attacker.vz >= SWEEP_SPEED_LIMIT_SQ) {
            return false;
        }
        return sword(e.attacker(), attacker.heldSlot);
    }

    private boolean sword(int slot, int heldSlot) {
        if (loadout == null || slot < 0 || slot > 1) {
            return false;
        }
        int hotbar = heldSlot < 0 || heldSlot >= EdgeLoadout.HOTBAR_SLOTS ? 0 : heldSlot;
        ItemStack stack = loadout.item(slot, hotbar);
        return stack != null && Tag.ITEMS_SWORDS.isTagged(stack.getType());
    }

    private Sound attackSound(CombatEvent e, boolean sweep) {
        return switch (e.kind()) {
            case CombatEvent.HIT_ARROW -> e.attacker() == localSlot ? Sound.ENTITY_ARROW_HIT_PLAYER : null;
            case CombatEvent.HIT_FIRE -> Sound.ENTITY_PLAYER_HURT_ON_FIRE;
            case CombatEvent.HIT_CRIT -> Sound.ENTITY_PLAYER_ATTACK_CRIT;
            case CombatEvent.HIT_KNOCKBACK -> Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK;
            case CombatEvent.HIT_STRONG -> sweep
                    ? Sound.ENTITY_PLAYER_ATTACK_SWEEP : Sound.ENTITY_PLAYER_ATTACK_STRONG;
            case CombatEvent.HIT_FALL -> e.crit()
                    ? Sound.ENTITY_PLAYER_BIG_FALL : Sound.ENTITY_PLAYER_SMALL_FALL;
            case CombatEvent.HIT_SMASH, CombatEvent.HIT_SMASH_HEAVY, CombatEvent.HIT_EXPLOSION -> null;
            default -> Sound.ENTITY_PLAYER_ATTACK_WEAK;
        };
    }

    private int entityIdOf(int slot) {
        if (slot == localSlot) {
            return player.getEntityId();
        }
        return peerEntityId;
    }

    private static PlayerState slotState(GameState state, int slot) {
        if (state == null || slot < 0 || slot >= state.players.length) {
            return null;
        }
        return state.players[slot];
    }

    private Location at(PlayerState p, double yOffset) {
        return new Location(player.getWorld(), p.x, p.y + yOffset, p.z);
    }

    private Location cell(CombatEvent e) {
        return new Location(player.getWorld(), e.attacker() + 0.5, e.victim() + 0.5, e.kind() + 0.5);
    }

    private static float hurtPitch() {
        return 1.0f + (rand() - rand()) * 0.2f;
    }

    private static float rand() {
        return ThreadLocalRandom.current().nextFloat();
    }
}
