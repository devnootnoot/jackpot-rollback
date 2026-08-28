package me.nootnoot.edge;

import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import me.nootnoot.edge.present.CoreHeadActionBar;
import me.nootnoot.edge.present.CoreReport;
import me.nootnoot.edge.present.CoreSounds;
import me.nootnoot.edge.present.CoreText;
import me.nootnoot.edge.present.CoreTitles;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.MatchSetupFrame0Decoder;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.host.SimRenderer;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

public final class EdgeRenderer implements SimRenderer {

    public static final String DEFAULT_NAME = "opponent";

    public static final double HOLD_LEASH_BLOCKS = 0.75;


    private final EdgePeerSmoother peerSmoother = new EdgePeerSmoother();

    private static final boolean PEER_SMOOTHING = !"false".equalsIgnoreCase(
            String.valueOf(System.getenv("EDGE_PEER_SMOOTHING")));

    private static final double CAGE_HOLD_MARGIN = 0.5;
    private static final double CAGE_HOLD_HEADROOM = 1.0;

    private static final float HURT_YAW = 0.0f;

    private static final double DEATH_LIFT = 2.0;
    private static final int DEATH_CLOUD = 30;
    private static final int DEATH_INDICATORS = 12;

    private static final int PHASE_LIVE = 0;
    private static final int PHASE_ROUND_OVER = 1;
    private static final int PHASE_COUNTDOWN = 2;

    private static final int TICKS_PER_SECOND = 20;

    private static final long RESULT_DELAY_TICKS = 20L * 5L;
    private static final long DEFEAT_HORN_DELAY_TICKS = 10L;
    private static final long REPORT_DELAY_TICKS = 1L;

    private static final String ROUND_WON_TITLE =
            "<#00cc66>🏆 <#55ff55><b>ROUND WON</b> <#00cc66>🏆";
    private static final String ROUND_LOST_TITLE =
            "<#cc0000>☠ <#ff4444><b>ROUND LOST</b> <#cc0000>☠";
    private static final String DEFEAT_TITLE =
            "<#aa0000>☠ <#ff4444><b>DEFEAT</b> <#aa0000>☠";
    private static final String SCORE_PREFIX = "<#e5e5e5>Score: ";
    private static final String FINAL_SCORE_PREFIX = "<#e5e5e5>Final Score: ";
    private static final String LOBBY_SUBTITLE = "<#e5e5e5>Teleporting to lobby...";

    private static final String READY_PROMPT = "Punch to ready up » ";
    private static final String READY_YES = "✔";
    private static final String READY_NO = "✘";
    private static final TextColor READY_PROMPT_COLOR = TextColor.fromHexString(CoreText.MAIN_HEX);
    private static final TextColor READY_YES_COLOR = TextColor.color(0x55FF55);
    private static final TextColor READY_NO_COLOR = TextColor.color(0xFF5555);

    private final Player player;
    private final int localSlot;
    private final EdgeInputSource input;
    private final EdgeLoadout loadout;
    private final EdgeCageDisplay cage;
    private final EdgeCombatFx fx;
    private final EdgeStatusMirror status;
    private final boolean peerShieldOffHand;
    private final Plugin plugin;
    private final boolean pots;
    private final boolean totems;
    private final EdgeMatchStats.Counts localTotals;
    private final EdgeMatchStats.Counts peerTotals;
    private final EdgePeerMirror peer;
    private final EdgeWorldMirror worldMirror;
    private final EdgeCrystals crystals;
    private final EdgeDrops drops;
    private final EdgeProjectiles projectiles;
    private final EdgeDigDisplay dig;
    private final EdgeInventoryMirror inventory;
    private final EdgeContainerMirror container;

    private volatile EdgeMatchStats.Counts peerLeft;

    private EdgePlayerEntity opponent;
    private volatile int opponentEntityId = -1;

    private int phase = PHASE_LIVE;
    private int shownSecond = -1;
    private int shownWinsMine;
    private int shownWinsTheirs;
    private boolean resultShown;

    private EdgeTelemetry telemetry;

    private UUID identityUuid;
    private String identityName = DEFAULT_NAME;
    private String identitySkin;
    private String identitySkinSignature;
    private boolean identityDirty;

    private int prevHurtTime;
    private int lastImpulseSeq;
    private int prevOtherHurtTime;
    private int prevOtherAttackTicker;
    private int prevOtherHeldItemId;
    private boolean cagePending;
    private boolean trackersSeeded;
    private boolean peerDeathShown;
    private boolean localDeathShown;
    private int shownFireworkTicks;
    private int boostEntityId = -1;
    private boolean shownReady;
    private boolean readyPromptUp;

    public EdgeRenderer(Player player, int localSlot, EdgeInputSource input, EdgeLoadout loadout,
                        EdgeCageDisplay cage, Plugin plugin, boolean pots, boolean totems,
                        World world, EdgeArenaPaster.Overlay overlay) {
        this.player = player;
        this.localSlot = localSlot;
        this.input = input;
        this.loadout = loadout;
        this.cage = cage;
        this.plugin = plugin;
        this.pots = pots;
        this.totems = totems;
        this.fx = new EdgeCombatFx(player, localSlot, loadout);
        this.status = new EdgeStatusMirror(player, plugin.getLogger());
        this.peerShieldOffHand = loadout != null && loadout.offhandShield(1 - localSlot);
        this.localTotals = EdgeMatchStats.totals(loadout, localSlot);
        this.peerTotals = EdgeMatchStats.totals(loadout, 1 - localSlot);
        this.peerLeft = peerTotals;
        this.peer = new EdgePeerMirror(player, world, new EdgeStacks(loadout, 1 - localSlot),
                peerShieldOffHand);
        this.worldMirror = new EdgeWorldMirror(player, world, overlay,
                input == null ? null : input.acks());
        this.crystals = new EdgeCrystals(player, input);
        this.drops = new EdgeDrops(player, localSlot);
        this.projectiles = new EdgeProjectiles(player, localSlot, drops);
        this.dig = new EdgeDigDisplay(player, world, localSlot);
        EdgeEntryStacks entryStacks = new EdgeEntryStacks(loadout);
        this.inventory = new EdgeInventoryMirror(player, entryStacks, localSlot);
        this.container = new EdgeContainerMirror(player, entryStacks, localSlot);
        this.fx.tints(this.projectiles);
        if (loadout != null) {
            MatchSetupFrame0Decoder.Identity peerIdentity = loadout.identity(1 - localSlot);
            applyOpponentIdentity(peerIdentity.uuid(), peerIdentity.name(),
                    blank(peerIdentity.skinValue()), blank(peerIdentity.skinSignature()));
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public int opponentEntityId() {
        return opponentEntityId;
    }

    public EdgeContainerMirror container() {
        return container;
    }

    public EdgeInventoryMirror inventory() {
        return inventory;
    }

    public String opponentName() {
        return identityName;
    }

    public void applyPeerCounts(EdgeMatchStats.Counts counts) {
        if (counts != null) {
            this.peerLeft = counts;
        }
    }

    public void applyOpponentIdentity(UUID uuid, String name, String skinValue, String skinSignature) {
        String resolved = name == null || name.isBlank() ? DEFAULT_NAME : name;
        if (Objects.equals(identityUuid, uuid)
                && Objects.equals(identityName, resolved)
                && Objects.equals(identitySkin, skinValue)
                && Objects.equals(identitySkinSignature, skinSignature)) {
            return;
        }
        identityUuid = uuid;
        identityName = resolved;
        identitySkin = skinValue;
        identitySkinSignature = skinSignature;
        identityDirty = true;
    }

    private EdgePlayerEntity ensureOpponent(PlayerState other) {
        if (opponent != null && !identityDirty) {
            return opponent;
        }
        if (opponent != null) {
            opponent.despawn();
        }
        peerSmoother.reset();
        identityDirty = false;
        peer.reset();
        opponent = new EdgePlayerEntity(player, EdgePlayerEntity.nextEntityId(), profileUuid(),
                identityName, identitySkin, identitySkinSignature);
        opponentEntityId = opponent.entityId();
        equipOpponent(opponent, other.heldSlot);
        opponent.spawn(other.x, other.y, other.z, other.yaw, other.pitch);
        return opponent;
    }

    private void equipOpponent(EdgePlayerEntity npc, int heldSlot) {
        if (loadout == null) {
            return;
        }
        int peerSlot = 1 - localSlot;
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        gear.put(EquipmentSlot.BOOTS,
                EdgePlayerEntity.itemOf(loadout.item(peerSlot, EdgeLoadout.ARMOR_FEET)));
        gear.put(EquipmentSlot.LEGGINGS,
                EdgePlayerEntity.itemOf(loadout.item(peerSlot, EdgeLoadout.ARMOR_LEGS)));
        gear.put(EquipmentSlot.CHEST_PLATE,
                EdgePlayerEntity.itemOf(loadout.item(peerSlot, EdgeLoadout.ARMOR_CHEST)));
        gear.put(EquipmentSlot.HELMET,
                EdgePlayerEntity.itemOf(loadout.item(peerSlot, EdgeLoadout.ARMOR_HEAD)));
        gear.put(EquipmentSlot.OFF_HAND,
                EdgePlayerEntity.itemOf(loadout.item(peerSlot, EdgeLoadout.OFF_HAND)));
        gear.put(EquipmentSlot.MAIN_HAND,
                EdgePlayerEntity.itemOf(loadout.item(peerSlot, hotbar(heldSlot))));
        npc.setEquipment(gear);
    }

    private static int hotbar(int heldSlot) {
        return heldSlot < 0 || heldSlot >= EdgeLoadout.HOTBAR_SLOTS ? 0 : heldSlot;
    }

    private UUID profileUuid() {
        if (identityUuid == null || Bukkit.getPlayer(identityUuid) != null) {
            return UUID.randomUUID();
        }
        return identityUuid;
    }

    public void attachTelemetry(EdgeTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public void beginCatchUp(int frames) {
        if (telemetry != null) {
            telemetry.catchUp(frames);
        }
    }

    @Override
    public void render(GameState head, GameState confirmed) {
        if (!player.isOnline()) {
            return;
        }
        PlayerState mine = head.players[localSlot];
        PlayerState other = head.players[1 - localSlot];

        if (peerDeathShown && !other.dead) {
            peerDeathShown = false;
            identityDirty = true;
        }

        EdgePlayerEntity npc = ensureOpponent(other);
        boolean peerJumped = head.roundResetCountdown > 0 || other.dead
                || other.authoritySuspendTicks > 0 || !PEER_SMOOTHING;
        peerSmoother.follow(other, peerJumped, System.nanoTime());
        fx.peerEntityId(npc.entityId());
        projectiles.peerEntityId(npc.entityId());
        drops.peerEntityId(npc.entityId());
        dig.peerEntityId(npc.entityId());
        npc.move(peerSmoother.x(), peerSmoother.y(), peerSmoother.z(), other.yaw, other.pitch,
                other.onGround);
        npc.setState(other.sneaking, other.sprinting, other.swimming, other.gliding,
                other.fireTicks > 0);
        peer.apply(npc, other);

        if (trackersSeeded && other.attackTicker < prevOtherAttackTicker
                && other.heldItemId == prevOtherHeldItemId) {
            npc.swing();
        }
        if (trackersSeeded && other.hurtTime > prevOtherHurtTime) {
            npc.hurt(HURT_YAW);
        }
        prevOtherAttackTicker = other.attackTicker;
        prevOtherHeldItemId = other.heldItemId;
        prevOtherHurtTime = other.hurtTime;
        trackersSeeded = true;

        if (other.dead && !peerDeathShown) {
            peerDeathShown = true;
            npc.status(EdgeCombatFx.STATUS_DEATH);
            npc.setHealth(0.0f);
        } else if (!other.dead) {
            npc.setHealth(Math.max(0.5f, other.health));
        }

        worldMirror.render(head);
        crystals.render(head);
        projectiles.render(head);
        drops.render(head, confirmed);
        dig.render(head);

        status.apply(head.dict, mine);
        inventory.apply(head, confirmed);
        container.apply(head, confirmed);
        localFirework(mine);
        if (player.isGliding() != mine.gliding) {
            player.setGliding(mine.gliding);
        }

        boolean hurtEdge = mine.hurtTime > prevHurtTime;
        if (mine.impulseSeq != 0 && mine.impulseSeq != lastImpulseSeq) {
            lastImpulseSeq = mine.impulseSeq;
            player.setVelocity(new Vector(mine.impulseVx, mine.impulseVy, mine.impulseVz));
        }
        if (hurtEdge) {
            EdgePackets.hurt(player, player.getEntityId(), HURT_YAW);
        }
        prevHurtTime = mine.hurtTime;

        presentLocalDeath(head, mine);

        presentRounds(head, confirmed, mine, other);

        flushBlockAck();
    }

    private void flushBlockAck() {
        if (input == null) {
            return;
        }
        EdgeBlockAcks acks = input.acks();
        int sequence = acks.release();
        if (sequence < 0) {
            return;
        }
        World world = player.getWorld();
        for (long cell : acks.settled()) {
            int x = BlockStore.unpackX(cell);
            int y = BlockStore.unpackY(cell);
            int z = BlockStore.unpackZ(cell);
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            player.sendBlockChange(new Location(world, x, y, z),
                    world.getBlockAt(x, y, z).getBlockData());
        }
        EdgePackets.blockAck(player, sequence);
    }


    private void presentLocalDeath(GameState head, PlayerState mine) {
        if (mine.dead && !localDeathShown) {
            localDeathShown = true;
            enterDeath(head, mine);
            return;
        }
        if (!mine.dead && localDeathShown) {
            localDeathShown = false;
            leaveDeath();
        }
    }

    private void enterDeath(GameState head, PlayerState mine) {
        EdgePackets.hurt(player, player.getEntityId(), HURT_YAW);
        EdgePackets.status(player, player.getEntityId(), EdgeCombatFx.STATUS_DEATH);

        Location at = new Location(player.getWorld(), mine.x, mine.y + 1.0, mine.z);
        player.spawnParticle(Particle.CLOUD, at, DEATH_CLOUD, 0.4, 0.6, 0.4, 0.05);
        player.spawnParticle(Particle.DAMAGE_INDICATOR, at, DEATH_INDICATORS, 0.3, 0.5, 0.3, 0.1);

        player.setFireTicks(0);
        try {
            player.setHealth(maxHealth());
        } catch (IllegalArgumentException ignored) {
        }
        player.setInvulnerable(true);
        if (head.roundMatchOver) {
            player.setGameMode(GameMode.ADVENTURE);
            player.setAllowFlight(true);
            player.setFlying(true);
        } else {
            player.setGameMode(GameMode.SPECTATOR);
        }
        Location from = player.getLocation();
        teleport(mine.x, mine.y + DEATH_LIFT, mine.z, from.getYaw(), from.getPitch());
    }

    private void leaveDeath() {
        player.setGameMode(GameMode.SURVIVAL);
        player.setFlying(false);
        player.setAllowFlight(false);
        player.setInvulnerable(false);
        player.setCollidable(true);
        player.setFallDistance(0);
    }

    private double maxHealth() {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        double value = attribute != null ? attribute.getValue() : 20.0;
        return value <= 0.0 ? 20.0 : value;
    }

    private void localFirework(PlayerState mine) {
        if (mine.fireworkTicks > shownFireworkTicks) {
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH,
                    SoundCategory.PLAYERS, 1.0f, 1.0f);
            attachBoost(mine);
        }
        if (mine.fireworkTicks <= 0) {
            detachBoost();
        }
        shownFireworkTicks = mine.fireworkTicks;
    }

    private void attachBoost(PlayerState mine) {
        detachBoost();
        boostEntityId = EdgeEntityBands.boost(localSlot);
        EdgePackets.attachedRocket(player, boostEntityId, player.getEntityId(),
                mine.x, mine.y, mine.z);
    }

    private void detachBoost() {
        if (boostEntityId < 0) {
            return;
        }
        EdgePackets.destroy(player, boostEntityId);
        boostEntityId = -1;
    }

    private void presentRounds(GameState head, GameState confirmed, PlayerState mine,
                               PlayerState other) {
        int winsMine = localSlot == 0 ? head.roundWinsP0 : head.roundWinsP1;
        int winsTheirs = localSlot == 0 ? head.roundWinsP1 : head.roundWinsP0;

        if (winsMine + winsTheirs > shownWinsMine + shownWinsTheirs && !head.roundMatchOver) {
            boolean wonRound = winsMine > shownWinsMine;
            showRoundEnd(head, wonRound, winsMine, winsTheirs, mine, other);
        }
        shownWinsMine = winsMine;
        shownWinsTheirs = winsTheirs;

        if (head.roundMatchOver) {
            closeCage();
            return;
        }

        int countdown = head.roundResetCountdown;
        int now = countdown > Simulation.ROUND_COUNTDOWN_TICKS ? PHASE_ROUND_OVER
                : countdown > 0 ? PHASE_COUNTDOWN : PHASE_LIVE;
        if (now != phase) {
            phase = now;
            shownSecond = -1;
            if (now == PHASE_COUNTDOWN) {
                enterCountdown(head);
            } else if (now == PHASE_LIVE) {
                clearReadyPrompt();
                shownReady = false;
                hold(mine, true);
                startRound(head, winsMine, winsTheirs);
            } else {
                clearReadyPrompt();
                shownReady = false;
                closeCage();
            }
        }
        if (now == PHASE_COUNTDOWN) {
            tickCountdown(head, confirmed, mine, countdown);
        } else if (now != PHASE_LIVE) {
            hold(mine, false);
        } else {
            holdLive(mine);
        }
    }

    private void enterCountdown(GameState head) {
        if (loadout != null) {
            loadout.applyInventory(player, localSlot);
        }
        inventory.reseed();
        container.reseed();
        if (input != null) {
            input.acks().clear();
        }
        PlayerState spawn = spawnOf(head, localSlot);
        teleport(spawn.x, spawn.y, spawn.z, spawn.yaw, spawn.pitch);
        cagePending = true;
        raiseCageOnceTheRoundResetLanded(head);
    }

    private void raiseCageOnceTheRoundResetLanded(GameState head) {
        if (!cagePending) {
            return;
        }
        if (head.roundResetCountdown >= Simulation.ROUND_COUNTDOWN_TICKS
                && (head.players[0].dead || head.players[1].dead)) {
            return;
        }
        cagePending = false;
        placeCage(head);
        resendCage();
    }

    private void startRound(GameState head, int winsMine, int winsTheirs) {
        openCage();

        final int roundNumber = winsMine + winsTheirs + 1;
        final boolean deathmatch = head.roundsTarget > 1
                && winsMine == head.roundsTarget - 1 && winsTheirs == head.roundsTarget - 1;

        if (deathmatch) {
            CoreTitles.playDeathmatchTitle(player, "<#ff4444>Next Kill Wins!");
        } else if (head.roundsTarget > 1) {
            CoreTitles.playFightTitle(player, "<white>Round #" + roundNumber);
        } else {
            player.setFireTicks(0);
            CoreTitles.playFightTitle(player, "<white>Good luck!");
        }

        sendScoreActionbar(winsMine, winsTheirs);
    }

    private void sendScoreActionbar(int winsMine, int winsTheirs) {
        CoreHeadActionBar.Face self = new CoreHeadActionBar.Face(player.getName(),
                player.getUniqueId(), selfSkinValue(), selfSkinSignature());
        CoreHeadActionBar.Face peer = new CoreHeadActionBar.Face(identityName,
                identityUuid == null ? UUID.randomUUID() : identityUuid,
                identitySkin, identitySkinSignature);
        CoreHeadActionBar.send(player, self, " " + winsMine + " - " + winsTheirs + " ", peer);
    }

    private String selfSkinValue() {
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if ("textures".equals(property.getName())) {
                return property.getValue();
            }
        }
        return null;
    }

    private String selfSkinSignature() {
        for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if ("textures".equals(property.getName())) {
                return property.getSignature();
            }
        }
        return null;
    }

    private static PlayerState spawnOf(GameState state, int slot) {
        if (state.roundInitial != null && slot < state.roundInitial.length
                && state.roundInitial[slot] != null) {
            return state.roundInitial[slot];
        }
        return state.players[slot];
    }

    private void placeCage(GameState head) {
        if (cage == null) {
            return;
        }
        try {
            cage.place(head);
        } catch (RuntimeException ignored) {
            return;
        }
    }

    private void openCage() {
        if (cage == null || !cage.placed() || cage.opened()) {
            return;
        }
        try {
            cage.open();
        } catch (RuntimeException ignored) {
            return;
        }
    }

    private void closeCage() {
        if (cage == null || !cage.placed()) {
            return;
        }
        try {
            cage.remove();
        } catch (RuntimeException ignored) {
            return;
        }
    }

    private void resendCage() {
        if (cage == null) {
            return;
        }
        try {
            cage.resend(player);
        } catch (RuntimeException ignored) {
            return;
        }
    }

    private void tickCountdown(GameState head, GameState confirmed, PlayerState mine,
                               int countdown) {
        holdInCage(mine);
        presentReady(head, confirmed);
        int second = (countdown + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
        if (second == shownSecond || second <= 0) {
            return;
        }
        shownSecond = second;
        CoreSounds.countdownTick(player, second);
        CoreTitles.sendLegacyTitle(player, CoreReport.colorForCountdown(second) + second, "&f",
                0, 40, 0);
    }

    private void presentReady(GameState head, GameState confirmed) {
        boolean youReady = head.players[localSlot].ready;
        boolean theyReady = confirmed.players[1 - localSlot].ready;
        if (youReady && !shownReady) {
            CoreSounds.play(player, Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 1F, 1F);
        }
        shownReady = youReady;
        if (youReady && theyReady) {
            clearReadyPrompt();
            return;
        }
        player.sendActionBar(Component.text(READY_PROMPT, READY_PROMPT_COLOR)
                .append(Component.text(player.getName() + " ", NamedTextColor.WHITE))
                .append(Component.text(youReady ? READY_YES : READY_NO,
                        youReady ? READY_YES_COLOR : READY_NO_COLOR))
                .append(Component.text("   " + identityName + " ", NamedTextColor.WHITE))
                .append(Component.text(theyReady ? READY_YES : READY_NO,
                        theyReady ? READY_YES_COLOR : READY_NO_COLOR)));
        readyPromptUp = true;
    }

    private void clearReadyPrompt() {
        if (!readyPromptUp) {
            return;
        }
        readyPromptUp = false;
        player.sendActionBar(Component.empty());
    }

    private void showRoundEnd(GameState head, boolean won, int winsMine, int winsTheirs,
                              PlayerState mine, PlayerState other) {
        final String score = SCORE_PREFIX + CoreReport.roundScoreDisplayClean(winsMine, winsTheirs);
        CoreTitles.sendTitle(player, won ? ROUND_WON_TITLE : ROUND_LOST_TITLE, score, 5, 60, 5);
        if (won) {
            CoreSounds.roundWon(player);
        } else {
            CoreSounds.roundLost(player);
        }
        sendReport(true, head.roundsTarget, won, winsMine, winsTheirs, mine, other);
    }

    public void showResult(boolean won, int winsMine, int winsTheirs, GameState finalState) {
        if (resultShown || !player.isOnline()) {
            return;
        }
        resultShown = true;

        final int roundsTarget = finalState == null ? 1 : finalState.roundsTarget;
        final PlayerState mine = finalState == null ? null : finalState.players[localSlot];
        final PlayerState other = finalState == null ? null : finalState.players[1 - localSlot];

        final String subTitle = roundsTarget > 1
                ? FINAL_SCORE_PREFIX + CoreReport.roundScoreDisplay(winsMine, winsTheirs)
                : LOBBY_SUBTITLE;

        if (won) {
            CoreTitles.playVictory(player, subTitle);
            CoreSounds.play(player, Sound.ITEM_GOAT_HORN_SOUND_0, 1F, 1F);
        } else {
            CoreTitles.sendTitle(player, DEFEAT_TITLE, subTitle, 10, 80, 10);
            CoreSounds.playLater(player, Sound.ITEM_GOAT_HORN_SOUND_3, 1F, 1F,
                    DEFEAT_HORN_DELAY_TICKS);
        }

        CoreSounds.playLater(player, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1F, 1F,
                RESULT_DELAY_TICKS);

        sendReport(false, roundsTarget, won, winsMine, winsTheirs, mine, other);
    }

    private void sendReport(boolean round, int roundsTarget, boolean won, int winsMine,
                            int winsTheirs, PlayerState mine, PlayerState other) {
        final CoreReport.Side me = side(player.getName(), mine, localCounts(), localTotals);
        final CoreReport.Side them = side(identityName, other, peerLeft, peerTotals);
        final CoreReport.Side winner = won ? me : them;
        final CoreReport.Side loser = won ? them : me;
        final String score = CoreReport.reportScore(won ? winsMine : winsTheirs,
                won ? winsTheirs : winsMine, !won);
        final List<Component> message = CoreReport.build(round, winner, loser, pots, totems, score,
                roundsTarget);

        if (plugin == null) {
            for (Component line : message) {
                player.sendMessage(line);
            }
            return;
        }

        final UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            final Player resolved = Bukkit.getPlayer(uuid);
            if (resolved == null || !resolved.isOnline()) {
                return;
            }
            for (Component line : message) {
                resolved.sendMessage(line);
            }
        }, REPORT_DELAY_TICKS);
    }

    private EdgeMatchStats.Counts localCounts() {
        EdgeMatchStats.Counts live = EdgeMatchStats.left(player);
        return live == null ? localTotals : live;
    }

    private static CoreReport.Side side(String name, PlayerState state,
                                        EdgeMatchStats.Counts left,
                                        EdgeMatchStats.Counts totals) {
        EdgeMatchStats.Counts resolved = left == null ? totals : left;
        return new CoreReport.Side(name, state == null ? 0.0 : state.health,
                resolved.healthPots(), totals.healthPots(),
                resolved.totems(), totals.totems());
    }

    private void holdInCage(PlayerState mine) {
        EdgeCage.Extent box = cage == null || !cage.placed() ? null : cage.extent(localSlot);
        if (box == null) {
            hold(mine, false);
            return;
        }
        Location at = player.getLocation();
        double bx = Math.floor(mine.x);
        double by = Math.floor(mine.y);
        double bz = Math.floor(mine.z);
        if (at.getX() >= bx + box.minX() - CAGE_HOLD_MARGIN
                && at.getX() <= bx + box.maxX() + 1.0 + CAGE_HOLD_MARGIN
                && at.getZ() >= bz + box.minZ() - CAGE_HOLD_MARGIN
                && at.getZ() <= bz + box.maxZ() + 1.0 + CAGE_HOLD_MARGIN
                && at.getY() >= by + box.minY() - CAGE_HOLD_MARGIN
                && at.getY() <= by + box.maxY() + CAGE_HOLD_HEADROOM) {
            return;
        }
        teleport(mine.x, mine.y, mine.z, at.getYaw(), at.getPitch());
    }

    private void holdLive(PlayerState mine) {
        if (mine.dead || mine.authoritySuspendTicks <= 0) {
            return;
        }
        Location at = player.getLocation();
        double gap = EdgeLiveSnap.distance(at.getX() - mine.x, at.getY() - mine.y,
                at.getZ() - mine.z);
        if (!EdgeLiveSnap.needsSnap(mine.authoritySuspendTicks, gap)) {
            return;
        }
        teleport(mine.x, mine.y, mine.z, at.getYaw(), at.getPitch());
    }

    private void hold(PlayerState mine, boolean force) {
        if (mine.dead) {
            return;
        }
        Location at = player.getLocation();
        double dx = at.getX() - mine.x;
        double dy = at.getY() - mine.y;
        double dz = at.getZ() - mine.z;
        double gap = dx * dx + dy * dy + dz * dz;
        if (gap <= (force ? 0.0 : HOLD_LEASH_BLOCKS * HOLD_LEASH_BLOCKS)) {
            return;
        }
        teleport(mine.x, mine.y, mine.z, at.getYaw(), at.getPitch());
    }

    private void teleport(double x, double y, double z, float yaw, float pitch) {
        if (input != null) {
            input.markServerTeleport(x, y, z);
        }
        try {
            player.teleport(new Location(player.getWorld(), x, y, z, yaw, pitch));
        } catch (RuntimeException ignored) {
            return;
        }
    }

    @Override
    public void playEvents(List<CombatEvent> events, GameState state) {
        fx.playConfirmed(events, state);
        for (CombatEvent event : events) {
            if (event.type() == CombatEvent.DEATH) {
                announceDeath(event, state);
            }
        }
    }

    private void announceDeath(CombatEvent event, GameState state) {
        final int victim = event.victim();
        final int killer = event.attacker();
        final String victimName = nameOf(victim);
        final String killerName = nameOf(killer);

        if (killer < 0 || killer == victim) {
            player.sendMessage(CoreText.mini("<#8A1817>☠ <red>" + victimName
                    + " <white>has died."));
        } else {
            player.sendMessage(CoreText.mini("<#8A1817>🗡 <red>" + victimName
                    + " <white>has been killed by <red>" + killerName + "<white>."));
        }

        if (killer == localSlot && victim != localSlot && state != null) {
            PlayerState dead = state.players[victim];
            CoreSounds.killEffects(player,
                    new Location(player.getWorld(), dead.x, dead.y, dead.z));
        }
    }

    private String nameOf(int slot) {
        return slot == localSlot ? player.getName() : identityName;
    }

    @Override
    public void playLocalHits(List<CombatEvent> headEvents, GameState head, int headFrame) {
        fx.playLocal(headEvents, head, headFrame);
    }

    @Override
    public void clear() {
        try {
            EdgePlayerEntity npc = opponent;
            opponent = null;
            opponentEntityId = -1;
            if (localDeathShown) {
                localDeathShown = false;
                teardownStep("leaveDeath", this::leaveDeath);
            }
            if (npc != null) {
                teardownStep("despawn opponent", npc::despawn);
            }
            teardownStep("peer", peer::reset);
            teardownStep("dig", dig::clear);
            teardownStep("projectiles", projectiles::clear);
            teardownStep("firework boost", this::detachBoost);
            teardownStep("crystals", crystals::clear);
            teardownStep("drops", drops::clear);
            teardownStep("worldMirror", worldMirror::clear);
            teardownStep("victory animation",
                    () -> CoreTitles.cancelVictoryAnimation(player.getUniqueId()));
            if (cage != null) {
                teardownStep("cage", cage::remove);
            }
        } finally {
            status.reset();
        }
    }

    private void teardownStep(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable e) {
            plugin.getLogger().warning("[edge] clear() step '" + what + "' failed for "
                    + player.getName() + ": " + e);
        }
    }
}
