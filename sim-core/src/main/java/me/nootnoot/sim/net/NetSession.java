package me.nootnoot.sim.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import me.nootnoot.sim.ClaimAuthority;
import me.nootnoot.sim.InputLedger;
import me.nootnoot.sim.ResimulationBudgetException;
import me.nootnoot.sim.RollbackController;
import me.nootnoot.sim.RollbackOverrunException;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;

public final class NetSession {
    public static final int MAX_INPUTS_PER_PACKET = Protocol.MAX_INPUTS_PER_PACKET;
    static final int INPUTS_PER_DATAGRAM = Math.max(1, (1400 - 16) / InputCodec.BYTES);

    static final int RESEND_WINDOW_FRAMES = INPUTS_PER_DATAGRAM;

    public static final int CHECKSUM_INTERVAL = 1;

    private static final int SYNC_WINDOW = 8;

    private static final int STALL_SAFETY_MARGIN = 32;

    private static final int PREDICTION_CAP = 600;

    private static final int TIME_SYNC_MIN_ADVANTAGE = 2;

    private static final int PEER_LIVE_WINDOW_TICKS = 8;

    private static final int TIME_SYNC_MAX_SLEEP_FRAMES = 3;

    private static final int TIME_SYNC_RECOMMENDATION_INTERVAL = 80;

    private static final int ABORT_DESYNC = 1;
    private static final int ABORT_TEARDOWN = 2;
    private static final int ABORT_PEER_FAULT = 3;

    public static final int PEER_TIMEOUT_TICKS = 400;

    public static final int PEER_WIN_CLAIM_GRACE_TICKS = 60;

    public static final int MAX_PEER_FRAME_ADVANTAGE = 600;

    public static final int MAX_QUEUED_CHAT = 64;

    public static final int MAX_QUEUED_CONTAINER = 64;

    private static final int CATCHUP_MAX_EXTRA_FRAMES = 3;
    private static final int CATCHUP_MIN_DEFICIT = 8;
    private static final int CATCHUP_DIVISOR = 8;

    private static final int CATCHUP_SNAP_MIN_DEFICIT = 20;

    static final int CATCHUP_BURST_MAX = 60;

    static final int CATCHUP_BUDGET_REFILL = 2;

    static final int START_TIMEOUT_TICKS = 800;

    private static final int CHECKSUM_RETENTION_FRAMES = 1200;

    private static final int INPUT_RETENTION_FRAMES = 1200;

    private final Transport transport;
    private final RollbackController controller;

    private final int inputDelay;
    private final int ringCapacity;

    private final int peerTimeoutTicks;

    private final int freeRunCeiling;

    private final InputLedger rawInputs = new InputLedger();
    private final InputLedger localInputs = new InputLedger();
    private int peerAckedThrough = -1;

    private final Map<Integer, Long> localChecksums = new HashMap<>();
    private final Map<Integer, Long> pendingRemoteChecksums = new HashMap<>();
    private int lastChecksumFrame;
    private int checksumPruneFloor;
    private int inputPruneFloor;

    private boolean aborted;
    private String abortReason;
    private boolean peerDisconnected;
    private boolean selfFaulted;
    private boolean peerFaulted;
    private boolean peerAnnouncedDesync;
    private int peerChecksumMismatches;
    private int peerChecksumOverreach;

    private final int localSlot;
    private boolean remoteFinished;
    private boolean remoteConceded;
    private boolean peerClaimedWin;
    private int ticksSincePeerWinClaim;
    private int remoteWinnerSlot = -1;
    private int remoteWinsP0;
    private int remoteWinsP1;
    private int desyncFrame = -1;

    private final java.util.ArrayDeque<String> incomingChat = new java.util.ArrayDeque<>();

    private final java.util.ArrayDeque<byte[]> incomingContainer = new java.util.ArrayDeque<>();

    private int droppedChat;

    private int droppedContainer;

    private boolean peerSeen;

    private int ticksSincePeerPacket;

    private int highestRemoteFrame = -1;

    private int highestConfirmedFrame = -1;

    private int ticksSinceRemoteProgress;

    private int peerFrameAdvantage;

    private int myFrameAdvantage;
    private final int[] lfaWindow = new int[SYNC_WINDOW];
    private int lfaCount;
    private int lfaPos;

    private int sleepFramesRemaining;

    private int framesSinceRecommendation = TIME_SYNC_RECOMMENDATION_INTERVAL;

    private boolean heldLastTick;

    private int syntheticCredit;

    private int peerAckOverreach;

    private int stallTicks;

    private int samplesLostToStalls;

    private boolean heldThisTick;

    private int peerFramesAcceptedThisTick;

    private int catchUpBudget;

    public NetSession(Transport transport, int localSlot, Arena arena, GameState initial, int ringCapacity) {
        this(transport, localSlot, arena, initial, ringCapacity, 0);
    }

    public NetSession(Transport transport, int localSlot, Arena arena, GameState initial,
                      int ringCapacity, int inputDelay) {
        if (inputDelay < 0) {
            throw new IllegalArgumentException("inputDelay must be >= 0");
        }

        this.freeRunCeiling = Math.min(PREDICTION_CAP, ringCapacity - STALL_SAFETY_MARGIN);
        this.catchUpBudget = this.freeRunCeiling;
        this.peerTimeoutTicks = Math.max(1, Math.min(PEER_TIMEOUT_TICKS, this.freeRunCeiling - 1));
        this.transport = transport;
        this.localSlot = localSlot;
        this.controller = new RollbackController(arena, localSlot, initial, ringCapacity);
        this.inputDelay = inputDelay;
        this.ringCapacity = ringCapacity;
    }

    public void update(Input rawLocalInput) {
        update(new Sample(rawLocalInput));
    }

    public void update(Supplier<Input> localInput) {
        update(new Sample(localInput));
    }

    private void update(Sample sample) {
        int atEntry = controller.head();
        catchUpBudget = Math.min(freeRunCeiling, catchUpBudget + CATCHUP_BUDGET_REFILL);
        try {
            updateTick(sample, atEntry);
        } finally {
            int produced = controller.head() - atEntry;
            if (produced <= 0 && heldThisTick && sample.taken()) {
                samplesLostToStalls++;
            }
            heldThisTick = false;
            int growth = produced - peerFramesAcceptedThisTick;
            peerFramesAcceptedThisTick = 0;
            if (growth > 0) {
                syntheticCredit = Math.min(freeRunCeiling, syntheticCredit + growth);
            }
        }
    }

    private void updateTick(Sample sample, int atEntry) {
        updateOneFrame(sample);
        boolean sampleUnspent = controller.head() == atEntry;
        int target = catchUpTarget();
        if (aborted || target <= controller.head()) {
            burstFilledFrames = 0;
            if (sample.taken()) {
                lastInputBeforeBurst = sample.get();
            }
            return;
        }
        int before = controller.head();
        boolean snap = target - before >= CATCHUP_SNAP_MIN_DEFICIT;
        while (!aborted && controller.head() < target) {
            int at = controller.head();
            advanceQuiet(sampleUnspent
                    ? sample::take
                    : () -> catchUpFiller(sample.get(), burstFilledFrames, snap));
            if (controller.head() == at) {
                break;
            }
            if (sampleUnspent) {
                sampleUnspent = false;
            } else {
                burstFilledFrames += controller.head() - at;
            }
            if (confirmedMatchOver()) {
                break;
            }
        }
        int ran = controller.head() - before;
        catchUpFramesThisTick += ran;
        catchUpBudget = Math.max(0, catchUpBudget - ran);
        if (!aborted) {
            sendInputs();
            sendChecksums();
        }
    }

    private Input catchUpFiller(Input rawLocalInput, int framesIntoBurst, boolean snap) {
        if (!snap) {
            return rawLocalInput.withSynthetic(true).heldOnly();
        }

        Input base = lastInputBeforeBurst.withSynthetic(true);
        return framesIntoBurst >= RollbackController.PREDICTION_DECAY_FRAMES
                ? base.gestureOnly()
                : base.heldOnly();
    }

    private int catchUpTarget() {
        int head = controller.head();
        if (aborted || !peerSeen) {
            return head;
        }
        if (ticksSincePeerPacket > PEER_LIVE_WINDOW_TICKS) {
            return head;
        }
        int budget = catchUpBudget;
        if (budget <= 0) {
            return head;
        }
        int deficit = (controller.knownRemoteFrames() - peerInputDelayAllowance()) - head;
        if (deficit < CATCHUP_MIN_DEFICIT) {
            return head;
        }
        if (deficit < CATCHUP_SNAP_MIN_DEFICIT) {
            return head + Math.min(budget, Math.min(CATCHUP_MAX_EXTRA_FRAMES, deficit / CATCHUP_DIVISOR));
        }
        return head + Math.min(budget, Math.min(deficit, catchUpBurstMax()));
    }

    private int catchUpBurstMax() {
        return Math.min(peerTimeoutTicks, CATCHUP_BURST_MAX);
    }

    int catchUpBudget() {
        return catchUpBudget;
    }

    static boolean fillerFrameIsPossible(int frame, int localFramesProduced, int peerDelayFrames) {
        long senderHead = (long) frame - Math.max(0, peerDelayFrames);
        return senderHead + Protocol.MAX_PEER_DELAY_ALLOWANCE < localFramesProduced;
    }

    static int peerDelayUpperBound(int localInputDelay) {
        return Math.max(0, Math.max(localInputDelay, ClaimAuthority.INPUT_DELAY_FRAMES));
    }

    private int peerDelayUpperBound() {
        return peerDelayUpperBound(inputDelay);
    }

    private int syntheticRemoteFrames;

    private int syntheticFramesStripped;

    int syntheticRemoteFrames() {
        return syntheticRemoteFrames;
    }

    int syntheticFramesStripped() {
        return syntheticFramesStripped;
    }

    int syntheticCredit() {
        return syntheticCredit;
    }

    int peerAckOverreach() {
        return peerAckOverreach;
    }

    public int stallTicks() {
        return stallTicks;
    }

    public int samplesLostToStalls() {
        return samplesLostToStalls;
    }

    private int catchUpFramesThisTick;

    private int burstFilledFrames;

    private Input lastInputBeforeBurst = Input.NONE;

    public int peerTimeoutTicks() {
        return peerTimeoutTicks;
    }

    public int freeRunCeiling() {
        return freeRunCeiling;
    }

    public int drainCatchUpFrames() {
        int n = catchUpFramesThisTick;
        catchUpFramesThisTick = 0;
        return n;
    }

    private int peerInputDelayAllowance() {
        return Protocol.MAX_PEER_DELAY_ALLOWANCE;
    }

    private static final class Sample {

        private final Supplier<Input> source;
        private Input sampled;
        private boolean has;

        Sample(Input alreadyTaken) {
            this.source = null;
            this.sampled = alreadyTaken == null ? Input.NONE : alreadyTaken;
            this.has = true;
        }

        Sample(Supplier<Input> source) {
            this.source = source;
        }

        Input take() {
            if (!has) {
                has = true;
                Input in = source == null ? null : source.get();
                sampled = in == null ? Input.NONE : in;
            }
            return sampled;
        }

        boolean taken() {
            return has;
        }

        Input get() {
            return has ? sampled : Input.NONE;
        }
    }

    private void recordSample(int frame, Input rawLocalInput) {
        while (rawInputs.end() <= frame) {
            rawInputs.add(rawLocalInput);
        }
        int knownUpTo = frame + inputDelay;
        while (localInputs.end() <= knownUpTo) {
            int f = localInputs.end();
            localInputs.add(f >= inputDelay ? rawInputs.get(f - inputDelay) : Input.NONE);
        }
    }

    private void advanceQuiet(Supplier<Input> rawLocalInput) {
        if (aborted) {
            return;
        }
        int frame = controller.head();
        updateFrameAdvantage(frame);
        framesSinceRecommendation++;
        if (ringSafetyStall() || timeSyncStall()) {
            if (!heldThisTick) {
                heldThisTick = true;
                stallTicks++;
            }
            return;
        }
        recordSample(frame, rawLocalInput.get());
        try {
            controller.advance(localInputs.get(frame));
        } catch (RollbackOverrunException e) {
            abort("prediction overrun: " + e.getMessage());
        } catch (RuntimeException e) {
            abortSelfFault("sim fault advancing frame " + frame + ": " + e);
        }
    }

    private void updateOneFrame(Sample sample) {
        if (aborted) {
            return;
        }
        pump();
        if (aborted) {
            return;
        }

        ticksSincePeerPacket++;
        if (ticksSincePeerPacket > (peerSeen ? peerTimeoutTicks : START_TIMEOUT_TICKS)) {
            if (peerSeen) {
                peerDisconnected = true;
            }
            abort(peerSeen
                    ? "peer disconnected - no packets for " + (peerTimeoutTicks / 20) + "s (they left or crashed)"
                    : "peer never connected - the other player's handoff did not complete");
            return;
        }

        // Liveness is CONFIRMED progress, not packet arrival and not the peer's own frame numbering.
        // A heartbeat or a chat line resets ticksSincePeerPacket, so a peer that sends those and no inputs
        // used to hold this side below the disconnect timeout while the prediction window filled - and then
        // ringSafetyStall() held every tick forever, with no abort and no result. That is a freeze either
        // player could inflict at will. Keying the replacement timer on the highest frame number the peer
        // got ACCEPTED only moved the dial they hold: a lone input at a far-ahead frame reset it without
        // confirming anything, and a peer recovering from burst loss by filling frames BELOW that number
        // made real progress the timer could not see, so a healthy peer could be declared dead.
        // controller.confirmedFrame() is min(contiguous remote coverage, our own head): the peer can only
        // advance it by actually supplying the next frame, and can never push it past where we have
        // simulated to.
        if (!peerSeen) {
            ticksSinceRemoteProgress = 0;
        }
        int confirmedNow = controller.confirmedFrame();
        if (confirmedNow > highestConfirmedFrame) {
            highestConfirmedFrame = confirmedNow;
            ticksSinceRemoteProgress = 0;
        }
        ticksSinceRemoteProgress++;
        if (peerSeen && ticksSinceRemoteProgress > peerTimeoutTicks) {
            peerDisconnected = true;
            abort("the opponent kept the link alive but stopped simulating - no frame has been"
                    + " confirmed for " + (peerTimeoutTicks / 20) + "s, so there is nothing left to"
                    + " agree with");
            return;
        }

        if (peerClaimedWin && !confirmedMatchOver()
                && ++ticksSincePeerWinClaim > PEER_WIN_CLAIM_GRACE_TICKS) {
            peerDisconnected = true;
            abort("the opponent announced a win this side never confirmed and then stopped"
                    + " simulating it for " + (PEER_WIN_CLAIM_GRACE_TICKS / 20) + "s - the match is"
                    + " resolved as a forfeit by whoever made the claim, because an unverifiable"
                    + " claim is worth exactly as much as leaving");
            return;
        }

        if (confirmedMatchOver()) {
            int winner = 1 - confirmedLoser();
            transport.send(Protocol.encode(
                    new Message.Finish(winner, confirmedRoundWins(0), confirmedRoundWins(1))));
        }

        int frame = controller.head();
        updateFrameAdvantage(frame);
        framesSinceRecommendation++;

        boolean startHold = frame == 0 && !peerSeen;
        if (startHold || ringSafetyStall() || timeSyncStall()) {
            if (!heldThisTick) {
                heldThisTick = true;
                stallTicks++;
            }
            sendInputs();
            sendChecksums();
            return;
        }

        recordSample(frame, sample.take());

        try {
            controller.advance(localInputs.get(frame));
        } catch (RollbackOverrunException e) {
            abort("prediction overrun: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            abortSelfFault("sim fault advancing frame " + frame + ": " + e);
            return;
        }
        sendInputs();
        sendChecksums();
    }

    private void updateFrameAdvantage(int frame) {
        if (highestRemoteFrame < 0) {
            return;
        }

        lfaWindow[lfaPos] = (frame - inputDelay) - highestRemoteFrame;
        lfaPos = (lfaPos + 1) % SYNC_WINDOW;
        if (lfaCount < SYNC_WINDOW) {
            lfaCount++;
        }
        int sum = 0;
        for (int i = 0; i < lfaCount; i++) {
            sum += lfaWindow[i];
        }
        myFrameAdvantage = Math.round((float) sum / lfaCount);
    }

    private boolean ringSafetyStall() {
        if (!peerSeen) {
            return false;
        }
        int predictionWindow = controller.head() - controller.confirmedFrame();
        return predictionWindow >= freeRunCeiling;
    }

    private boolean timeSyncStall() {
        if (controller.head() == 0 || !peerSeen) {
            return false;
        }

        if (ticksSincePeerPacket > PEER_LIVE_WINDOW_TICKS) {
            sleepFramesRemaining = 0;
            heldLastTick = false;
            return false;
        }

        if (sleepFramesRemaining <= 0 && myFrameAdvantage >= TIME_SYNC_MIN_ADVANTAGE
                && framesSinceRecommendation >= TIME_SYNC_RECOMMENDATION_INTERVAL) {
            int diff = myFrameAdvantage - peerFrameAdvantage;
            if (diff >= TIME_SYNC_MIN_ADVANTAGE) {
                sleepFramesRemaining = Math.min(diff / 2, TIME_SYNC_MAX_SLEEP_FRAMES);
                framesSinceRecommendation = 0;
            }
        }

        if (sleepFramesRemaining > 0 && !heldLastTick) {
            sleepFramesRemaining--;
            heldLastTick = true;
            return true;
        }

        heldLastTick = false;
        return false;
    }

    public void flush() {
        if (aborted) {
            return;
        }
        pump();
        if (aborted) {
            return;
        }
        sendInputs();
        sendChecksums();
    }

    public boolean aborted() {
        return aborted;
    }

    public boolean peerDisconnected() {
        return peerDisconnected;
    }

    public boolean selfFaulted() {
        return selfFaulted;
    }

    public boolean peerFaulted() {
        return peerFaulted;
    }

    public boolean peerAnnouncedDesync() {
        return peerAnnouncedDesync;
    }

    public int peerChecksumMismatches() {
        return peerChecksumMismatches;
    }

    public int peerChecksumOverreach() {
        return peerChecksumOverreach;
    }

    public boolean remoteFinished() {
        return remoteFinished;
    }

    public boolean remoteConceded() {
        return remoteConceded;
    }

    public boolean peerClaimedWin() {
        return peerClaimedWin;
    }

    public int remoteWinnerSlot() {
        return remoteWinnerSlot;
    }

    public int remoteWinsP0() {
        return remoteWinsP0;
    }

    public int remoteWinsP1() {
        return remoteWinsP1;
    }

    public String abortReason() {
        return abortReason;
    }

    public int desyncFrame() {
        return desyncFrame;
    }

    public GameState state() {
        return controller.state();
    }

    public GameState confirmedState() {
        return controller.confirmedState();
    }

    public long checksum() {
        return controller.checksum();
    }

    public int head() {
        return controller.head();
    }

    public int confirmedFrame() {
        return controller.confirmedFrame();
    }

    public int rollbackCount() {
        return controller.rollbackCount();
    }

    public long resimulatedFrames() {
        return controller.resimulatedFrames();
    }

    public record Retention(int localChecksums, int pendingRemoteChecksums, int rawInputs,
                            int localInputs, int chatQueue, int containerQueue,
                            int controllerLocalInputs, int controllerRemoteActual,
                            int controllerRemoteUsed) {

        public int total() {
            return localChecksums + pendingRemoteChecksums + rawInputs + localInputs
                    + chatQueue + containerQueue + controllerLocalInputs
                    + controllerRemoteActual + controllerRemoteUsed;
        }
    }

    public Retention retention() {
        return new Retention(localChecksums.size(), pendingRemoteChecksums.size(),
                rawInputs.retained(), localInputs.retained(), incomingChat.size(),
                incomingContainer.size(), controller.retainedLocalInputs(),
                controller.retainedRemoteActual(), controller.retainedRemoteUsed());
    }

    public void drainLocalCorrection(double[] out) {
        controller.drainLocalCorrection(out);
    }

    public void drainPeerCorrection(double[] out) {
        controller.drainPeerCorrection(out);
    }

    public java.util.List<me.nootnoot.sim.state.CombatEvent> drainConfirmedEvents() {
        return controller.drainConfirmedEvents();
    }

    public boolean confirmedMatchOver() {
        return controller.confirmedState().roundMatchOver;
    }

    public int confirmedLoser() {
        GameState s = controller.confirmedState();
        if (s.roundMatchOver && s.roundMatchWinner >= 0) {
            return 1 - s.roundMatchWinner;
        }
        return -1;
    }

    public int confirmedRoundWins(int slot) {
        GameState s = controller.confirmedState();
        return slot == 0 ? s.roundWinsP0 : s.roundWinsP1;
    }

    public void sendChat(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        transport.send(Protocol.encode(new Message.Chat(text)));
    }

    public int droppedChat() {
        return droppedChat;
    }

    public int myFrameAdvantage() {
        return myFrameAdvantage;
    }

    public int peerFrameAdvantage() {
        return peerFrameAdvantage;
    }

    public int droppedContainer() {
        return droppedContainer;
    }

    public java.util.List<String> drainChat() {
        if (incomingChat.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>(incomingChat);
        incomingChat.clear();
        return out;
    }

    public void sendContainer(byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        transport.send(Protocol.encode(new Message.Container(data)));
    }

    public java.util.List<byte[]> drainContainer() {
        if (incomingContainer.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<byte[]> out = new java.util.ArrayList<>(incomingContainer);
        incomingContainer.clear();
        return out;
    }

    public void sendFinish(int winnerSlot) {
        transport.send(Protocol.encode(
                new Message.Finish(winnerSlot, confirmedRoundWins(0), confirmedRoundWins(1))));
    }

    private void pump() {
        controller.beginInputBatch();
        try {
            pumpPackets();
        } finally {
            if (!aborted) {
                try {
                    controller.endInputBatch();
                } catch (ResimulationBudgetException e) {
                    abortPeerFault(e.getMessage());
                } catch (RollbackOverrunException e) {
                    abort("prediction overrun: " + e.getMessage());
                } catch (RuntimeException e) {
                    abortSelfFault("sim fault re-simulating remote inputs: " + e);
                }
            }
        }
    }

    private void pumpPackets() {
        for (byte[] data : transport.receive()) {
            Message m;
            try {
                m = Protocol.decode(data);
            } catch (RuntimeException malformed) {
                continue;
            }
            peerSeen = true;
            ticksSincePeerPacket = 0;
            if (m instanceof Message.InputFrames inf) {
                handleInput(inf);
            } else if (m instanceof Message.Checksum cs) {
                handleChecksum(cs);
            } else if (m instanceof Message.Abort a) {
                if (a.code() == Protocol.ABORT_VERSION_MISMATCH) {
                    abort("protocol version mismatch - this side is at inputBytes/checksumRev/"
                            + "protocolVersion " + VersionFence.local() + " and the opponent is not."
                            + " Whichever of the two reports the LOWER checksumRev is the stale"
                            + " artifact: " + VersionFence.EDGE_ARTIFACT + ", "
                            + VersionFence.MOD_ARTIFACT + " or " + VersionFence.CORE_ARTIFACT
                            + ". The relay log for this session prints both numbers side by side.");
                } else if (a.code() == ABORT_DESYNC) {
                    peerAnnouncedDesync = true;
                    abort("the opponent announced a checksum mismatch this side never observed."
                            + " Nothing here disagreed with them, so this is their word for it and"
                            + " nothing more: the match is a no contest and the announcement earns"
                            + " the announcer nothing.");
                } else if (a.code() == ABORT_TEARDOWN) {
                    peerDisconnected = true;
                    abort("opponent disconnected");
                } else if (a.code() == ABORT_PEER_FAULT) {
                    abort("the opponent stopped: it says this side demanded more resimulation than"
                            + " it will spend");
                } else {
                    abort("peer aborted (code " + a.code() + ")");
                }
            } else if (m instanceof Message.Finish f) {
                if (f.winnerSlot() == localSlot) {
                    if (!remoteFinished) {
                        remoteFinished = true;
                        remoteWinsP0 = f.winsP0();
                        remoteWinsP1 = f.winsP1();
                    }
                    if (!remoteConceded) {
                        remoteConceded = true;
                        remoteWinnerSlot = f.winnerSlot();
                    }
                } else if (!peerClaimedWin) {
                    peerClaimedWin = true;
                    ticksSincePeerWinClaim = 0;
                }
            } else if (m instanceof Message.Chat c) {
                if (incomingChat.size() >= MAX_QUEUED_CHAT) {
                    incomingChat.poll();
                    droppedChat++;
                }
                incomingChat.add(c.text());
            } else if (m instanceof Message.Container c) {
                if (incomingContainer.size() >= MAX_QUEUED_CONTAINER) {
                    incomingContainer.poll();
                    droppedContainer++;
                }
                incomingContainer.add(c.data());
            }
            if (aborted) {
                return;
            }
        }
    }

    private void handleInput(Message.InputFrames inf) {
        peerSeen = true;
        peerFrameAdvantage = Math.max(-MAX_PEER_FRAME_ADVANTAGE,
                Math.min(MAX_PEER_FRAME_ADVANTAGE, inf.frameAdvantage()));
        int ack = Math.min(inf.ack(), localInputs.end() - 1);
        if (ack < inf.ack()) {
            peerAckOverreach++;
        }
        if (ack > peerAckedThrough) {
            peerAckedThrough = ack;
        }

        int frame = inf.baseFrame();
        int highestUsable = controller.head() + ringCapacity;
        if (frame < 0 || frame > highestUsable) {
            return;
        }
        for (Input in : inf.inputs()) {
            if (frame >= 0 && frame <= highestUsable) {
                if (!controller.acceptsRemoteInput(frame)) {
                    frame++;
                    continue;
                }
                boolean strip = in.synthetic()
                        && (!fillerFrameIsPossible(frame, localInputs.end(), peerDelayUpperBound())
                            || syntheticCredit <= 0);
                if (in.synthetic()) {
                    syntheticRemoteFrames++;
                    if (strip) {
                        syntheticFramesStripped++;
                    } else {
                        syntheticCredit--;
                    }
                }
                peerFramesAcceptedThisTick++;
                Input value = strip ? in.withSynthetic(false) : in;
                try {
                    controller.onRemoteInput(frame, value);
                } catch (ResimulationBudgetException e) {
                    abortPeerFault(e.getMessage());
                    return;
                } catch (RollbackOverrunException e) {
                    abort("prediction overrun: " + e.getMessage());
                    return;
                } catch (RuntimeException e) {
                    abortSelfFault("sim fault on remote input at frame " + frame + ": " + e);
                    return;
                }
                if (frame > highestRemoteFrame) {
                    highestRemoteFrame = frame;
                }
            }
            frame++;
        }
    }

    private void handleChecksum(Message.Checksum cs) {
        int frame = cs.frame();
        if (frame < checksumPruneFloor) {
            return;
        }
        if (frame >= localInputs.end()) {
            peerChecksumOverreach++;
            return;
        }
        Long local = localChecksums.get(frame);
        if (local == null) {
            pendingRemoteChecksums.put(frame, cs.value());
        } else if (local != cs.value()) {
            peerChecksumMismatches++;
            flagDesync(frame);
        }
    }

    static int resendFrom(int newestFrame, int pruneFloor, int peerAckedThrough) {
        long ackFrom = (long) peerAckedThrough + 1L;
        long localFrom = (long) newestFrame - RESEND_WINDOW_FRAMES + 1L;
        return (int) Math.max(pruneFloor, Math.min(ackFrom, localFrom));
    }

    private void sendInputs() {
        int to = localInputs.end() - 1;
        int from = resendFrom(to, inputPruneFloor, peerAckedThrough);
        int ack = controller.confirmedFrame() - 1;
        if (to < from) {
            transport.send(Protocol.encode(
                    new Message.InputFrames(Math.max(0, to + 1), List.of(), ack, myFrameAdvantage)));
            return;
        }
        if (to - from + 1 > MAX_INPUTS_PER_PACKET) {
            // The per-packet cap used to truncate the NEWEST end of the run, so a peer reporting an
            // ack far behind (a stuck or forged one costs it nothing) made this side spend every
            // tick resending the oldest 48 frames and never sending a current one. The catch-up run
            // still gets the cap; the trailing window goes out beside it, because which frames we
            // send has to stay a property of what we produced.
            int catchUpTo = from + MAX_INPUTS_PER_PACKET - 1;
            sendRun(from, catchUpTo, ack);
            sendRun(Math.max(catchUpTo + 1, to - RESEND_WINDOW_FRAMES + 1), to, ack);
            return;
        }
        sendRun(from, to, ack);
    }

    private void sendRun(int from, int to, int ack) {
        for (int chunkFrom = from; chunkFrom <= to; chunkFrom += INPUTS_PER_DATAGRAM) {
            int chunkTo = Math.min(to, chunkFrom + INPUTS_PER_DATAGRAM - 1);
            List<Input> run = new ArrayList<>(chunkTo - chunkFrom + 1);
            for (int f = chunkFrom; f <= chunkTo; f++) {
                run.add(localInputs.get(f));
            }
            transport.send(Protocol.encode(new Message.InputFrames(chunkFrom, run, ack, myFrameAdvantage)));
        }
    }

    private void sendChecksums() {
        int cf = controller.confirmedFrame();
        for (int n = lastChecksumFrame + 1; n <= cf; n++) {
            if (CHECKSUM_INTERVAL > 1 && n % CHECKSUM_INTERVAL != 0) {
                continue;
            }
            long c;
            try {
                c = controller.checksumAt(n);
            } catch (RollbackOverrunException e) {
                abort("prediction overrun: " + e.getMessage());
                return;
            } catch (RuntimeException e) {
                abortSelfFault("sim fault checksumming frame " + n + ": " + e);
                return;
            }
            localChecksums.put(n, c);
            Long remote = pendingRemoteChecksums.remove(n);
            if (remote != null && remote != c) {
                peerChecksumMismatches++;
                flagDesync(n);
                return;
            }
            transport.send(Protocol.encode(new Message.Checksum(n, c)));
        }
        lastChecksumFrame = cf;
        pruneChecksums(cf);
        pruneInputs(cf);
    }

    private void pruneChecksums(int confirmedFrame) {
        int floor = confirmedFrame - CHECKSUM_RETENTION_FRAMES;
        if (floor <= checksumPruneFloor) {
            return;
        }
        for (int n = checksumPruneFloor; n < floor; n++) {
            localChecksums.remove(n);
            pendingRemoteChecksums.remove(n);
        }
        checksumPruneFloor = floor;
    }

    private void pruneInputs(int confirmedFrame) {
        int floor = inputRetentionFloor(confirmedFrame, peerAckedThrough);
        if (floor <= inputPruneFloor) {
            return;
        }
        rawInputs.releaseBelow(floor);
        localInputs.releaseBelow(floor);
        inputPruneFloor = floor;
    }

    static int inputRetentionFloor(int confirmedFrame, int peerAckedThrough) {
        long localFloor = (long) confirmedFrame - INPUT_RETENTION_FRAMES;
        long ackFloor = (long) peerAckedThrough + 1L - INPUT_RETENTION_FRAMES;
        long held = Math.min(localFloor, ackFloor);
        long clamped = Math.max(localFloor - INPUT_RETENTION_FRAMES, held);
        return (int) clamped;
    }

    public int inputPruneFloor() {
        return inputPruneFloor;
    }

    private void flagDesync(int frame) {
        desyncFrame = frame;
        abort("desync at frame " + frame);
        transport.send(Protocol.encode(new Message.Abort(ABORT_DESYNC)));
    }

    private void abort(String reason) {
        if (!aborted) {
            aborted = true;
            abortReason = reason;
        }
    }

    private void abortPeerFault(String reason) {
        peerFaulted = true;
        try {
            transport.send(Protocol.encode(new Message.Abort(ABORT_PEER_FAULT)));
        } catch (RuntimeException ignored) {
        }
        abort(reason);
    }

    private void abortSelfFault(String reason) {
        selfFaulted = true;
        try {
            transport.send(Protocol.encode(new Message.Abort(ABORT_TEARDOWN)));
        } catch (RuntimeException ignored) {
        }
        abort(reason);
    }

    public void teardown() {
        transport.send(Protocol.encode(new Message.Abort(ABORT_TEARDOWN)));
        abort("teardown");
    }
}
