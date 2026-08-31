package me.nootnoot.edge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.Fluids;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.contract.InventoryIntents;
import me.nootnoot.sim.host.InputFrameRules;
import me.nootnoot.sim.host.InputSource;
import me.nootnoot.sim.host.InventoryPaintPlan;
import me.nootnoot.sim.host.MatchDriver;
import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class EdgeInputSource implements InputSource {

    private static final int SWING_SUPPRESS_SAMPLES = 2;

    private static final int MINING_FINISH_GRACE = 20;

    private static final int CLIENT_DIG_TAIL = InputFrameRules.MINING_TAIL_TICKS;

    private static final int MAX_QUEUED_INTENTS = 16;

    private static final int TELEPORT_HOLD_TICKS = 20;

    private static final double TELEPORT_SETTLED_BLOCKS = 0.5;

    private static final int MAX_CRYSTAL_GHOSTS = 16;

    private static final int MAX_INV_ADDR = InventoryIntents.maxAddr();


    private static final double BLOCK_REACH = Combat.BLOCK_REACH;
    private static final double FLUID_RAY_STEP = 0.05;

    private static final int FLUID_RAY_STEPS = (int) Math.floor(BLOCK_REACH / FLUID_RAY_STEP) + 1;


    public record UseIntent(boolean offHand, boolean againstBlock, int x, int y, int z,
                            int faceX, int faceY, int faceZ) {
    }

    private record DropIntent(boolean whole, int itemId) {
    }

    private record InvIntent(int action, int src, int dst) {
    }

    private final Player player;
    private final int slot;
    private final EdgeMovementValidator validator;
    private final EdgeTelemetry telemetry;
    private final EdgeLoadout loadout;
    private final EdgeCells cells;
    private final Arena arena;
    private static final double CORRECTION_LIFT_LIMIT = 3.0;

    private static final double LOCAL_DIVERGENCE_TRACE_BLOCKS = 0.3;

    private final ConcurrentLinkedQueue<UseIntent> useQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<DropIntent> dropQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<InvIntent> invQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger swapCount = new AtomicInteger();
    private volatile double teleportX;
    private volatile double teleportY;
    private volatile double teleportZ;
    private volatile int teleportHoldTicks;
    private final EdgeBlockAcks acks = new EdgeBlockAcks();
    private volatile boolean closeContainerClaim;
    private final Map<Integer, Long> crystalEntities = new ConcurrentHashMap<>();
    private final Map<Integer, Long> crystalGhosts = new ConcurrentHashMap<>();

    private MatchDriver driver;

    private InventoryPaintPlan slotPaint;

    private InventoryPaintPlan cellPaint;

    private volatile boolean forward;
    private volatile boolean back;
    private volatile boolean left;
    private volatile boolean right;
    private volatile boolean jump;
    private volatile boolean sneak;
    private volatile boolean sprintLatch;
    private volatile boolean elytraStart;
    private int equipCooldown;
    private boolean prevUse;
    private final AtomicInteger swingCount = new AtomicInteger();
    private volatile boolean meleeClaim;
    private volatile boolean crystalClaim;
    private volatile long crystalEntityClaim = Long.MIN_VALUE;
    private volatile int swingSuppress;
    private volatile boolean correcting;

    private volatile boolean digging;
    private volatile boolean clientDigging;
    private volatile int clientDigGrace;
    private volatile int digX;
    private volatile int digY;
    private volatile int digZ;
    private volatile int digFinishGrace;

    private volatile boolean vanillaUse;

    public EdgeInputSource(Player player, int slot, EdgeMovementValidator.Limits limits,
                           EdgeTelemetry telemetry, EdgeLoadout loadout, Arena arena, World world) {
        this.player = player;
        this.slot = slot;
        this.validator = new EdgeMovementValidator(limits);
        this.telemetry = telemetry;
        this.loadout = loadout;
        this.cells = new EdgeCells(arena, world);
        this.arena = arena;
        this.vanillaUse = computeVanillaUse();
    }

    public void attach(MatchDriver driver) {
        this.driver = driver;
    }

    public void attachPaint(InventoryPaintPlan slots, InventoryPaintPlan cells) {
        this.slotPaint = slots;
        this.cellPaint = cells;
    }

    private void ownPaint(InventoryIntents.Intent intent, int headFrame) {
        if (slotPaint != null) {
            slotPaint.ownIntent(intent, headFrame);
        }
        if (cellPaint != null) {
            cellPaint.ownIntent(intent, headFrame);
        }
    }

    public EdgeMovementValidator validator() {
        return validator;
    }

    public void markServerTeleport() {
        validator.markServerTeleport();
        teleportHoldTicks = 0;
    }

    public void markServerTeleport(double x, double y, double z) {
        validator.markServerTeleport();
        teleportX = x;
        teleportY = y;
        teleportZ = z;
        teleportHoldTicks = TELEPORT_HOLD_TICKS;
    }

    public boolean correcting() {
        return correcting;
    }

    public void onInput(org.bukkit.Input in) {
        forward = in.isForward();
        back = in.isBackward();
        left = in.isLeft();
        right = in.isRight();
        jump = in.isJump();
        sneak = in.isSneak();
    }

    public void claimAttack() {
        meleeClaim = true;
        countSwing();
        swingSuppress = SWING_SUPPRESS_SAMPLES;
    }

    public void claimSwing() {
        crystalClaim = true;
        if (swingSuppress > 0) {
            swingSuppress = 0;
            return;
        }
        countSwing();
        if (clientDigging) {
            return;
        }
        abortBlockDig();
    }

    /** A vanilla client sends ONE arm-swing packet per counted attack click and can send several between two
     *  20Hz samples, so the packets are COUNTED, not latched into a boolean. Latching them was the unmodded
     *  half of the 10-actions-per-second ceiling: an unmodded player clicking 15 CPS got 10 swings. The dig
     *  animation streams the same packet for the whole dig and is NOT an attack in vanilla (doAttack resets the
     *  attack charge for an entity hit or a miss, never for the BLOCK case); a dig frame is stripped of its
     *  attack clicks by HostFrameContract.attackClicks, which is the one place both hosts spell that rule, so
     *  claimSwing keeps counting and only declines to ABORT the dig it belongs to. */
    private void countSwing() {
        swingCount.updateAndGet(v -> v >= Clicks.MAX ? Clicks.MAX : v + 1);
    }

    /** Drain the run of queued intents identical to {@code head}, capped at {@code limit} so the drained total
     *  can never exceed the wire's click ceiling. The input frame names ONE target, so only an identical repeat
     *  can honestly ride its click count; a differently-targeted intent stays queued for the next sample. */
    private static <T> int drainRepeats(ConcurrentLinkedQueue<T> queue, T head, int limit) {
        int extra = 0;
        while (extra < limit) {
            T next = queue.peek();
            if (next == null || !next.equals(head)) {
                break;
            }
            queue.poll();
            extra++;
        }
        return extra;
    }

    public void beginBlockDig(int x, int y, int z) {
        digX = x;
        digY = y;
        digZ = z;
        digFinishGrace = 0;
        digging = true;
        clientDigging = true;
        clientDigGrace = 0;
        crystalClaim = true;
        swingSuppress = SWING_SUPPRESS_SAMPLES;
    }

    public void finishBlockDig() {
        digFinishGrace = MINING_FINISH_GRACE;
        clientDigGrace = CLIENT_DIG_TAIL;
    }

    public void abortBlockDig() {
        digging = false;
        clientDigging = false;
        clientDigGrace = 0;
        digFinishGrace = 0;
    }

    public void onSprint(boolean sprinting) {
        sprintLatch = sprinting;
    }

    public void onElytraStart() {
        elytraStart = true;
    }

    public void onUseItem(boolean offHand) {
        offer(new UseIntent(offHand, false, 0, 0, 0, 0, 0, 0));
    }

    public void onUseItemOn(boolean offHand, int x, int y, int z, int faceX, int faceY, int faceZ) {
        offer(new UseIntent(offHand, true, x, y, z, faceX, faceY, faceZ));
    }

    public void onDrop(boolean whole) {
        if (dropQueue.size() < MAX_QUEUED_INTENTS) {
            dropQueue.add(new DropIntent(whole, 0));
        }
    }

    public void onSwapHands() {
        swapCount.updateAndGet(v -> v >= Clicks.MAX ? Clicks.MAX : v + 1);
    }

    public void onInventoryIntent(InventoryIntents.Intent intent) {
        if (intent == null || !intent.acts()) {
            return;
        }
        offerInv(new InvIntent(intent.action(), intent.src(), intent.dst()));
    }

    public void onCursorResolve() {
        onInventoryIntent(InventoryIntents.cursorResolve());
    }

    public void onCloseContainer() {
        closeContainerClaim = true;
    }

    public EdgeBlockAcks acks() {
        return acks;
    }

    public void onInventoryDrop(int slot, boolean whole) {
        onInventoryIntent(InventoryIntents.slotThrow(slot, whole));
    }

    private void offerInv(InvIntent intent) {
        if (intent.src() < 0 || intent.src() > MAX_INV_ADDR
                || intent.dst() < 0 || intent.dst() > MAX_INV_ADDR) {
            return;
        }
        if (invQueue.size() < MAX_QUEUED_INTENTS) {
            invQueue.add(intent);
        }
    }

    private void offer(UseIntent intent) {
        if (useQueue.size() < MAX_QUEUED_INTENTS) {
            useQueue.add(intent);
        }
    }

    public void registerCrystal(int entityId, int bx, int by, int bz) {
        crystalGhosts.remove(entityId);
        crystalEntities.put(entityId, BlockStore.key(bx, by, bz));
    }

    public void unregisterCrystal(int entityId) {
        Long cell = crystalEntities.remove(entityId);
        if (cell == null) {
            return;
        }
        if (crystalGhosts.size() >= MAX_CRYSTAL_GHOSTS) {
            crystalGhosts.clear();
        }
        crystalGhosts.put(entityId, cell);
    }

    public void clearCrystals() {
        crystalEntities.clear();
        crystalGhosts.clear();
    }

    public boolean ownsCrystal(int entityId) {
        return crystalEntities.containsKey(entityId) || crystalGhosts.containsKey(entityId);
    }

    public void claimCrystalEntity(int entityId) {
        Long cell = crystalEntities.get(entityId);
        if (cell == null) {
            cell = crystalGhosts.get(entityId);
        }
        if (cell != null) {
            crystalEntityClaim = cell;
            countSwing();
            swingSuppress = SWING_SUPPRESS_SAMPLES;
        }
    }

    public boolean vanillaUseAllowed() {
        return vanillaUse;
    }

    private static int offhandUseKindOf(ItemStack stack) {
        return InputFrameRules.offhandUseKind(EdgeHeldItems.useKindOf(stack));
    }

    private boolean computeVanillaUse() {
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        int mainKind = EdgeHeldItems.useKindOf(main);
        if (EdgeHeldItems.continuousUse(mainKind)) {
            return true;
        }
        if (mainKind != Combat.USE_NONE) {
            return false;
        }
        if (EdgeHeldItems.interactsWithWorld(main.getType())
                || EdgeHeldItems.equippable(main.getType())) {
            return false;
        }
        if (off.getType() == Material.SHIELD) {
            return true;
        }
        return EdgeHeldItems.continuousUse(offhandUseKindOf(off));
    }

    @Override
    public Input sample() {
        Location loc = player.getLocation();
        int swingPresses = swingCount.getAndSet(0);
        boolean swing = swingPresses > 0;
        boolean melee = meleeClaim;
        boolean crystalLeftClick = crystalClaim;
        long crystalEntity = crystalEntityClaim;
        meleeClaim = false;
        crystalClaim = false;
        crystalEntityClaim = Long.MIN_VALUE;
        boolean elytra = elytraStart;
        elytraStart = false;
        if (clientDigging && clientDigGrace > 0 && --clientDigGrace == 0) {
            clientDigging = false;
        }
        boolean digHeld = clientDigging;
        if (swingSuppress > 0) {
            swingSuppress--;
        }

        GameState head = driver != null ? driver.state() : null;
        PlayerState mine = head != null && slot >= 0 && slot < head.players.length
                ? head.players[slot] : null;
        boolean roundLocked = head != null && (head.roundResetCountdown > 0 || head.roundStartGrace > 0
                || head.roundMatchOver);

        double reportedX = loc.getX();
        double reportedY = loc.getY();
        double reportedZ = loc.getZ();
        if (teleportHoldTicks > 0) {
            double dx = reportedX - teleportX;
            double dy = reportedY - teleportY;
            double dz = reportedZ - teleportZ;
            if (dx * dx + dy * dy + dz * dz
                    > TELEPORT_SETTLED_BLOCKS * TELEPORT_SETTLED_BLOCKS) {
                reportedX = teleportX;
                reportedY = teleportY;
                reportedZ = teleportZ;
                teleportHoldTicks--;
            } else {
                teleportHoldTicks = 0;
            }
        }
        EdgeMovementValidator.Verdict verdict = validator.validate(mine, roundLocked,
                reportedX, reportedY, reportedZ, System.nanoTime());
        if (verdict.warn() && telemetry != null) {
            telemetry.movementFlagged(verdict.violations());
        }
        if (verdict.correction()) {
            correct(loc, verdict, head);
        }
        if (EdgeTrace.on()) {
            PlayerState me = head.players[slot];
            double ddx = loc.getX() - me.x;
            double ddy = loc.getY() - me.y;
            double ddz = loc.getZ() - me.z;
            double gap = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            if (gap > LOCAL_DIVERGENCE_TRACE_BLOCKS) {
                EdgeTrace.log("DIVERGE gap=" + String.format(java.util.Locale.ROOT, "%.3f", gap)
                        + " client=(" + String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                                loc.getX(), loc.getY(), loc.getZ())
                        + ") sim=(" + String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                                me.x, me.y, me.z)
                        + ") simGround=" + me.onGround + " clientGround=" + player.isOnGround()
                        + " hold=" + me.impulseHoldTicks + " headTick=" + head.tick);
            }
        }

        int heldSlot = player.getInventory().getHeldItemSlot();
        boolean sprint = sprintLatch && forward && !sneak;
        int swapPresses = swapCount.getAndSet(0);
        boolean swap = swapPresses > 0;
        int swapClicks = InputFrameRules.swapClicks(swap, swapPresses);
        if (loadout == null) {
            prevUse = false;
            return new Input(forward, back, left, right, jump, sprint, sneak,
                    InputFrameRules.attackFrame(swing, digHeld, Input.BLOCK_NONE), false,
                    loc.getYaw(), loc.getPitch(), heldSlot)
                    .withElytraStart(elytra)
                    .withMeleeHit(melee)
                    .withSwapHands(swap)
                    .withClicks(Clicks.NONE
                            .withAttack(InputFrameRules.attackClicks(Input.BLOCK_NONE, swingPresses))
                            .withSwap(swapClicks))
                    .withAuthority(Authority.at(verdict.x(), verdict.y(), verdict.z(),
                            player.isOnGround()));
        }

        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        vanillaUse = computeVanillaUse();
        EdgeHeldItems.Classified classified = EdgeHeldItems.classify(main,
                loadout.classified(slot, heldSlot));
        int useKind = resolveUseKind(classified.useKind(), main, off);

        // Take the head use intent. A vanilla client sends one use packet per counted right-click and can send
        // several between two samples, so the run of identical repeats behind the head rides the frame as a click
        // count; taking one and leaving the rest queued deferred them a tick each and pinned an unmodded player at
        // 10 uses per second. A frame that resolves to a block-targeted use is the exception: it names ONE cell, so
        // it carries exactly one use click and the rest stay queued (InputFrameRules.drainableUseRepeats, below).
        UseIntent intent = useQueue.poll();
        boolean mainPress = intent != null && !intent.offHand();
        boolean offPress = intent != null && intent.offHand();

        boolean handRaised = player.isHandRaised();
        EquipmentSlot raisedHand = handRaised ? player.getHandRaised() : null;
        boolean mainRaised = handRaised
                && (useKind == Combat.USE_SHIELD || raisedHand == EquipmentSlot.HAND);
        boolean use = InputFrameRules.useFrame(mainPress, mainRaised);

        boolean mainInteractable = InputFrameRules.mainHandConsumesUse(
                EdgeHeldItems.interactsWithWorld(main.getType()),
                EdgeHeldItems.equippable(main.getType()),
                intent != null && intent.againstBlock());
        int offhandUseKind = useKind == Combat.USE_NONE && !mainInteractable
                ? offhandUseKindOf(off) : Combat.USE_NONE;
        boolean offhandRaised = handRaised && raisedHand == EquipmentSlot.OFF_HAND;
        boolean offhandUse = InputFrameRules.offhandUseFrame(offhandUseKind, offPress,
                offhandRaised);

        boolean ranged = useKind == Combat.USE_BOW || useKind == Combat.USE_CROSSBOW;
        boolean meleeClaimed = melee && !(ranged && mainRaised);

        BlockIntent action = resolveBlockAction(head, mine, intent, main, off, useKind,
                crystalLeftClick, meleeClaimed, handRaised, crystalEntity, heldSlot);
        boolean meleeHit = InputFrameRules.leftClickTarget(action.crystalHit,
                action.crystalDistanceSq, meleeClaimed, InputFrameRules.CLAIMED_DISTANCE_SQ)
                == InputFrameRules.TARGET_MELEE;
        boolean attack = InputFrameRules.attackFrame(swing, digHeld, action.type);
        int counted = InputFrameRules.ruledBlockAction(head, action.type);
        int attackClicks = InputFrameRules.attackClicks(counted, swingPresses);
        int useClicks = 0;
        if (intent != null) {
            useClicks = InputFrameRules.useClicks(counted,
                    1 + drainRepeats(useQueue, intent,
                            InputFrameRules.drainableUseRepeats(counted)));
        }

        DropIntent drop = dropQueue.poll();
        int dropClicks = InputFrameRules.dropClicks(drop != null, drop == null ? 0
                : 1 + drainRepeats(dropQueue, drop, InputFrameRules.repeatDrainLimit()));
        boolean chestEquip = InputFrameRules.chestEquip(
                InputFrameRules.chestEquipPress(mainPress, use, prevUse),
                InventoryIntents.chestArmour(head, mine, heldSlot), equipCooldown);
        equipCooldown = InputFrameRules.chestEquipCooldown(
                InputFrameRules.chestEquipUseFrame(use, mainPress), equipCooldown);
        prevUse = use;
        InvIntent inv = chestEquip ? null : invQueue.poll();
        int invPresses = chestEquip ? 1
                : (inv == null ? 0
                        : 1 + drainRepeats(invQueue, inv, InputFrameRules.repeatDrainLimit()));
        int invClicks = InputFrameRules.invClicks(chestEquip || inv != null, invPresses);

        observeBlockReach(mine, action);

        Input in = new Input(forward, back, left, right, jump, sprint, sneak, attack, use,
                loc.getYaw(), loc.getPitch(), heldSlot)
                .withElytraStart(elytra)
                .withOffhandUse(offhandUse)
                .withOffhandUsePress(InputFrameRules.offhandUsePress(offhandUseKind, offPress))
                .withUsePress(mainPress)
                .withBlockAction(action.type, action.x, action.y, action.z)
                .withCrystalHit(action.crystalHit, action.crystalX, action.crystalY, action.crystalZ)
                .withMeleeHit(meleeHit)
                .withProjectileHit(InputFrameRules.projectileClaim(head, slot))
                .withSwapHands(swap)
                .withClicks(new Clicks(attackClicks, useClicks, dropClicks, invClicks, swapClicks));
        if (drop != null) {
            in = in.withDrop(true, drop.whole());
        }
        int headFrame = driver != null ? driver.head() : 0;
        if (drop != null && slotPaint != null) {
            slotPaint.own(heldSlot, InventoryPaintPlan.landingFrame(headFrame));
        }
        if (swap && slotPaint != null) {
            slotPaint.ownHandSwap(heldSlot, headFrame);
        }
        if (chestEquip) {
            InventoryIntents.Intent equip = InventoryIntents.chestEquip(heldSlot);
            in = in.withInvAction(equip.action(), equip.src(), equip.dst());
            ownPaint(equip, headFrame);
        } else if (inv != null) {
            in = in.withInvAction(inv.action(), inv.src(), inv.dst());
            ownPaint(new InventoryIntents.Intent(inv.action(), inv.src(), inv.dst()), headFrame);
        }
        return in.withAuthority(Authority.at(verdict.x(), verdict.y(), verdict.z(),
                player.isOnGround()));
    }

    private void observeBlockReach(PlayerState mine, BlockIntent action) {
        if (telemetry == null || mine == null || action.type == Input.BLOCK_NONE) {
            return;
        }
        double limit = Combat.blockReachLimit();
        double eye = mine.y + Combat.eyeHeight(mine);
        double dx = mine.x - Math.max(action.x, Math.min(mine.x, action.x + 1.0));
        double dy = eye - Math.max(action.y, Math.min(eye, action.y + 1.0));
        double dz = mine.z - Math.max(action.z, Math.min(mine.z, action.z + 1.0));
        if (dx * dx + dy * dy + dz * dz >= limit * limit) {
            telemetry.count(EdgeMetrics.BLOCK_REACH_REFUSED);
        }
    }

    private static final class BlockIntent {

        private int type = Input.BLOCK_NONE;
        private int x;
        private int y;
        private int z;

        private boolean crystalHit;
        private int crystalX;
        private int crystalY;
        private int crystalZ;
        private double crystalDistanceSq;

        private void at(int action, int bx, int by, int bz) {
            type = action;
            x = bx;
            y = by;
            z = bz;
        }

        private void crystal(int bx, int by, int bz, double distanceSq) {
            crystalHit = true;
            crystalX = bx;
            crystalY = by;
            crystalZ = bz;
            crystalDistanceSq = distanceSq;
        }

        private void dropCrystal() {
            crystalHit = false;
            crystalX = 0;
            crystalY = 0;
            crystalZ = 0;
            crystalDistanceSq = 0.0;
        }
    }

    private BlockIntent resolveBlockAction(GameState head, PlayerState mine, UseIntent intent,
                                     ItemStack main, ItemStack off, int useKind,
                                     boolean leftClick, boolean melee, boolean handRaised,
                                     long crystalEntity, int heldSlot) {
        BlockIntent out = new BlockIntent();
        boolean closeContainer = closeContainerClaim;
        closeContainerClaim = false;
        if (head == null || mine == null) {
            return out;
        }
        if (closeContainer) {
            out.at(Input.BLOCK_CLOSE_CONTAINER, 0, 0, 0);
            abortBlockDig();
            return out;
        }
        if (crystalEntity != Long.MIN_VALUE) {
            out.crystal(BlockStore.unpackX(crystalEntity), BlockStore.unpackY(crystalEntity),
                    BlockStore.unpackZ(crystalEntity), InputFrameRules.CLAIMED_DISTANCE_SQ);
        } else if (leftClick && InputFrameRules.leftClickActs(handRaised)) {
            CrystalPick hit = crystalUnderCrosshair(head, mine, heldSlot);
            if (hit != null) {
                out.crystal(hit.crystal().bx, hit.crystal().by, hit.crystal().bz,
                        hit.distanceSq());
            }
        }
        if (InputFrameRules.leftClickTarget(out.crystalHit, out.crystalDistanceSq, melee,
                InputFrameRules.CLAIMED_DISTANCE_SQ) != InputFrameRules.TARGET_CRYSTAL) {
            out.dropCrystal();
        }
        if (out.crystalHit) {
            abortBlockDig();
        }
        if (intent != null && InputFrameRules.leftClickActs(handRaised)
                && useVanillaWorldAction(head, mine, intent, main, off, useKind, out)) {
            return out;
        }
        if (!handRaised && InputFrameRules.minesThisFrame(out.crystalHit, melee)) {
            applyMining(head, mine, main, out);
        }
        return out;
    }

    private boolean useVanillaWorldAction(GameState head, PlayerState mine, UseIntent intent,
                                          ItemStack main, ItemStack off, int useKind, BlockIntent out) {
        Material mainType = main.getType();
        if (mainType == Material.BUCKET) {
            int[] source = firstFluidSourceAlongLook(head, mine);
            if (source == null) {
                return false;
            }
            out.at(Input.BLOCK_PICKUP_FLUID, source[0], source[1], source[2]);
            return true;
        }
        if (!intent.againstBlock()) {
            return false;
        }
        int cx = intent.x();
        int cy = intent.y();
        int cz = intent.z();
        Material clicked = cells.at(head, cx, cy, cz);
        if (!sneak && openableContainer(clicked)) {
            if (InputFrameRules.containerOpens(head, arena, cx, cy, cz)) {
                out.at(Input.BLOCK_OPEN_CONTAINER, cx, cy, cz);
            }
            return true;
        }
        boolean replaceClicked = clicked == Material.FIRE || clicked == Material.SOUL_FIRE;
        int px = replaceClicked ? cx : cx + intent.faceX();
        int py = replaceClicked ? cy : cy + intent.faceY();
        int pz = replaceClicked ? cz : cz + intent.faceZ();
        int charge = InputFrameRules.anchorCharge(head, cx, cy, cz);

        if (intent.offHand()) {
            return placeFrom(off, px, py, pz, Input.BLOCK_PLACE_OFFHAND, out);
        }
        int anchor = InputFrameRules.anchorAction(mainType == Material.GLOWSTONE,
                mainType == Material.RESPAWN_ANCHOR, charge);
        if (anchor == Input.BLOCK_CHARGE_ANCHOR) {
            out.at(anchor, cx, cy, cz);
            return true;
        }
        if (mainType == Material.END_CRYSTAL
                && InputFrameRules.crystalBaseCell(head, arena, mine, cx, cy, cz)) {
            out.at(Input.BLOCK_PLACE_CRYSTAL, cx, cy, cz);
            return true;
        }
        if (anchor == Input.BLOCK_PLACE_ANCHOR) {
            out.at(anchor, px, py, pz);
            return true;
        }
        if (anchor == Input.BLOCK_DETONATE_ANCHOR) {
            out.at(anchor, cx, cy, cz);
            return true;
        }
        if (mainType == Material.WATER_BUCKET || mainType == Material.LAVA_BUCKET) {
            out.at(mainType == Material.LAVA_BUCKET
                    ? Input.BLOCK_PLACE_LAVA : Input.BLOCK_PLACE_WATER, px, py, pz);
            return true;
        }
        if (placeFrom(main, px, py, pz, Input.BLOCK_PLACE, out)) {
            notePlacementPrecheck(head, mine, main, px, py, pz);
            return true;
        }
        if (useKind == Combat.USE_NONE
                && !InputFrameRules.mainHandConsumesUse(EdgeHeldItems.interactsWithWorld(mainType),
                        EdgeHeldItems.equippable(mainType), true)) {
            return placeFrom(off, px, py, pz, Input.BLOCK_PLACE_OFFHAND, out);
        }
        return false;
    }

    private static boolean openableContainer(Material material) {
        return material == Material.ENDER_CHEST || Tag.SHULKER_BOXES.isTagged(material);
    }

    private void notePlacementPrecheck(GameState head, PlayerState mine, ItemStack stack,
                                       int x, int y, int z) {
        if (telemetry == null || head == null || mine == null || stack == null) {
            return;
        }
        boolean legal = stack.getType() == Material.COBWEB
                ? InputFrameRules.webbableCell(head, arena, mine, x, y, z)
                : InputFrameRules.placeableCell(head, arena, mine, x, y, z);
        if (!legal) {
            telemetry.count(EdgeMetrics.PLACEMENT_PRECHECK_STALE);
        }
    }

    private boolean placeFrom(ItemStack stack, int x, int y, int z, int action, BlockIntent out) {
        if (stack == null || !stack.getType().isBlock() || stack.getType().isAir()
                || stack.getAmount() <= 0) {
            return false;
        }
        out.at(action, x, y, z);
        return true;
    }

    private void applyMining(GameState head, PlayerState mine, ItemStack main, BlockIntent out) {
        if (!digging) {
            return;
        }
        if (digFinishGrace > 0 && --digFinishGrace == 0) {
            digging = false;
            acks.decided(digX, digY, digZ);
            return;
        }
        if (!cells.breakable(head, digX, digY, digZ)) {
            digging = false;
            acks.decided(digX, digY, digZ);
            return;
        }
        out.at(Input.BLOCK_BREAK, digX, digY, digZ);
    }

    private record CrystalPick(CrystalState crystal, double distanceSq) {
    }

    private CrystalPick crystalUnderCrosshair(GameState head, PlayerState mine, int heldSlot) {
        if (head.crystals.isEmpty()) {
            return null;
        }
        double[] look = lookVector(mine.yaw, mine.pitch);
        CrystalState best = null;
        double bestDistanceSq = 0.0;
        for (CrystalState c : head.crystals) {
            double d = InputFrameRules.crystalPickDistanceSq(head, mine, heldSlot,
                    look[0], look[1], look[2], c.bx, c.by, c.bz);
            if (d < 0.0) {
                continue;
            }
            if (best == null || d < bestDistanceSq) {
                bestDistanceSq = d;
                best = c;
            }
        }
        return best == null ? null : new CrystalPick(best, bestDistanceSq);
    }

    private int[] firstFluidSourceAlongLook(GameState head, PlayerState mine) {
        if (head.fluids.isEmpty()) {
            return null;
        }
        double eye = Combat.eyeHeight(mine);
        double[] look = lookVector(mine.yaw, mine.pitch);
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int i = 0; i < FLUID_RAY_STEPS; i++) {
            double t = FLUID_RAY_STEP * i;
            int x = (int) Math.floor(mine.x + look[0] * t);
            int y = (int) Math.floor(mine.y + eye + look[1] * t);
            int z = (int) Math.floor(mine.z + look[2] * t);
            if (x == lastX && y == lastY && z == lastZ) {
                continue;
            }
            lastX = x;
            lastY = y;
            lastZ = z;
            Integer value = head.fluids.get(BlockStore.key(x, y, z));
            if (value == null) {
                Material at = cells.at(head, x, y, z);
                if (!at.isAir() && at != Material.WATER && at != Material.LAVA) {
                    return null;
                }
                continue;
            }
            if (Fluids.isSource(value)) {
                return new int[]{x, y, z};
            }
        }
        return null;
    }

    private static double[] lookVector(float yaw, float pitch) {
        Vec3 look = Combat.lookVector(yaw, pitch);
        return new double[]{look.x(), look.y(), look.z()};
    }

    private static int resolveUseKind(int classified, ItemStack main, ItemStack off) {
        if (classified != Combat.USE_NONE) {
            return classified;
        }
        if (main != null && main.getType() == Material.SHIELD) {
            return Combat.USE_SHIELD;
        }
        if (main != null && EdgeHeldItems.interactsWithWorld(main.getType())) {
            return Combat.USE_NONE;
        }
        if (main != null && EdgeHeldItems.equippable(main.getType())) {
            return Combat.USE_NONE;
        }
        if (off != null && off.getType() == Material.SHIELD) {
            return Combat.USE_SHIELD;
        }
        return Combat.USE_NONE;
    }

    private void correct(Location loc, EdgeMovementValidator.Verdict verdict, GameState head) {
        double y = Simulation.resolveStandingY(head, arena, verdict.x(), verdict.y(), verdict.z(),
                CORRECTION_LIFT_LIMIT);
        if (Double.isNaN(y)) {
            validator.markServerTeleport();
            if (telemetry != null) {
                telemetry.movementCorrectionRefused();
            }
            return;
        }
        if (EdgeTrace.on()) {
            EdgeTrace.log("CORRECTION client=(" + loc.getX() + "," + loc.getY() + "," + loc.getZ()
                    + ") accepted=(" + verdict.x() + "," + verdict.y() + "," + verdict.z()
                    + ") resolvedY=" + y + " sim=(" + head.players[slot].x + ","
                    + head.players[slot].y + "," + head.players[slot].z
                    + ") violations=" + verdict.violations());
        }
        teleportUnvalidated(new Location(player.getWorld(), verdict.x(), y, verdict.z(),
                loc.getYaw(), loc.getPitch()));
    }

    private void teleportUnvalidated(Location target) {
        correcting = true;
        try {
            player.teleport(target);
        } catch (RuntimeException ignored) {
        } finally {
            correcting = false;
        }
    }
}
