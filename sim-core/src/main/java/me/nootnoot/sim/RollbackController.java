package me.nootnoot.sim;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;

public final class RollbackController {
    private final Arena arena;
    private final int localPlayer;
    private final StateRingBuffer states;
    private final int ringCapacity;

    private final List<CombatEvent>[] frameEvents;
    private int lastEmittedFrame;

    private GameState current;
    private int head;

    private final InputLedger localInputs = new InputLedger();
    private boolean batching;
    private int pendingRollbackFrame = Integer.MAX_VALUE;
    private final InputLedger remoteActual = new InputLedger();
    private final InputLedger remoteUsed = new InputLedger();

    private int knownContiguous;
    private Input predictedBase = Input.NONE;
    private int rollbackCount;
    private int lastRollbackFrame = -1;
    private long resimulatedFrames;

    public static final int INPUT_RETENTION_MARGIN = 64;

    public static final int HONEST_PEER_LAG_FRAMES = 20;

    public static final int PEER_JITTER_FRAMES = 4;

    public static final int RESIM_FRAMES_PER_ADVANCE =
            HONEST_PEER_LAG_FRAMES + PEER_JITTER_FRAMES;

    public static final int RESIM_BUDGET_RINGS = 8;

    private final int resimBudgetCapacity;
    private int resimBudget;

    private int inputFloor;

    private double localCorrX;
    private double localCorrY;
    private double localCorrZ;

    @SuppressWarnings("unchecked")
    public RollbackController(Arena arena, int localPlayer, GameState initial, int ringCapacity) {
        if (localPlayer != 0 && localPlayer != 1) {
            throw new IllegalArgumentException("localPlayer must be 0 or 1");
        }
        this.arena = arena;
        this.localPlayer = localPlayer;
        this.current = initial;
        this.ringCapacity = ringCapacity;
        this.states = new StateRingBuffer(ringCapacity);
        this.frameEvents = (List<CombatEvent>[]) new List[ringCapacity];
        this.resimBudgetCapacity = Math.max(ringCapacity,
                (int) Math.min(Integer.MAX_VALUE, (long) ringCapacity * RESIM_BUDGET_RINGS));
        this.resimBudget = this.resimBudgetCapacity;
    }

    public void advance(Input localInput) {
        if (head != localInputs.end()) {
            throw new IllegalStateException("advance() out of sync: head=" + head + " inputs=" + localInputs.end());
        }
        localInputs.add(localInput);
        remoteActual.ensureEnd(head + 1);
        simulateFrame();
        resimBudget = (int) Math.min(resimBudgetCapacity,
                (long) resimBudget + RESIM_FRAMES_PER_ADVANCE);
    }

    public int resimBudget() {
        return resimBudget;
    }

    public int resimBudgetCapacity() {
        return resimBudgetCapacity;
    }

    public boolean acceptsRemoteInput(int frame) {
        if (frame < inputFloor || frame > head + ringCapacity) {
            return false;
        }
        return remoteActual.get(frame) == null;
    }

    public void onRemoteInput(int frame, Input value) {
        if (!acceptsRemoteInput(frame)) {
            return;
        }

        remoteActual.ensureEnd(frame + 1);
        remoteActual.set(frame, value);
        recomputeContiguous();

        if (frame < head) {
            Input used = remoteUsed.get(frame);
            if (used == null || !value.equals(used)) {
                if (batching) {
                    pendingRollbackFrame = Math.min(pendingRollbackFrame, frame);
                } else {
                    rollbackTo(frame);
                }
            }
        }
    }

    public void beginInputBatch() {
        batching = true;
        pendingRollbackFrame = Integer.MAX_VALUE;
    }

    public void endInputBatch() {
        batching = false;
        int frame = pendingRollbackFrame;
        pendingRollbackFrame = Integer.MAX_VALUE;

        if (frame < head) {
            rollbackTo(frame);
        }
    }

    public GameState state() {
        return current;
    }

    public long checksum() {
        return Checksum.of(current);
    }

    public int head() {
        return head;
    }

    public int confirmedFrame() {
        return Math.min(knownContiguous, head);
    }

    public int rollbackCount() {
        return rollbackCount;
    }

    public int lastRollbackFrame() {
        return lastRollbackFrame;
    }

    public long resimulatedFrames() {
        return resimulatedFrames;
    }

    public int retainedLocalInputs() {
        return localInputs.retained();
    }

    public int retainedRemoteActual() {
        return remoteActual.retained();
    }

    public int retainedRemoteUsed() {
        return remoteUsed.retained();
    }

    public GameState confirmedState() {
        int cf = confirmedFrame();
        if (cf >= head) {
            return current;
        }
        GameState s = states.peek(cf);
        return s != null ? s : current;
    }

    public int knownRemoteFrames() {
        return knownContiguous;
    }

    public long checksumAt(int n) {
        if (n == head) {
            return Checksum.of(current);
        }
        GameState s = states.peek(n);
        if (s == null) {
            throw new RollbackOverrunException(n, head);
        }
        return Checksum.of(s);
    }

    private void simulateFrame() {
        Input local = localInputs.get(head);
        Input remote = knownRemote(head);
        remoteUsed.ensureEnd(head + 1);
        remoteUsed.set(head, remote);

        states.save(current);

        Input in0 = localPlayer == 0 ? local : remote;
        Input in1 = localPlayer == 0 ? remote : local;
        Simulation.tick(current, arena, in0, in1);

        frameEvents[head % ringCapacity] =
                current.events.isEmpty() ? null : new ArrayList<>(current.events);
        head++;
        trimInputs();
    }

    public int inputFloor() {
        return inputFloor;
    }

    private void trimInputs() {
        int floor = confirmedFrame() - ringCapacity - INPUT_RETENTION_MARGIN;
        if (floor <= inputFloor) {
            return;
        }
        localInputs.releaseBelow(floor);
        remoteActual.releaseBelow(floor);
        remoteUsed.releaseBelow(floor);
        inputFloor = floor;
    }

    public List<CombatEvent> drainConfirmedEvents() {
        int cf = confirmedFrame();
        List<CombatEvent> out = new ArrayList<>();
        int from = Math.max(lastEmittedFrame, cf - ringCapacity + 1);
        if (from < 0) {
            from = 0;
        }
        for (int f = from; f < cf; f++) {
            List<CombatEvent> ev = frameEvents[f % ringCapacity];
            if (ev != null) {
                out.addAll(ev);
            }
        }
        if (cf > lastEmittedFrame) {
            lastEmittedFrame = cf;
        }
        return out;
    }

    private void rollbackTo(int frame) {
        int depth = head - frame;
        if (depth > resimBudget) {
            throw new ResimulationBudgetException(frame, head, depth, resimBudget,
                    resimBudgetCapacity);
        }
        GameState restored = states.load(frame);
        if (restored == null) {
            throw new RollbackOverrunException(frame, head);
        }
        resimBudget -= depth;
        int target = head;
        double preX = current.players[localPlayer].x;
        double preY = current.players[localPlayer].y;
        double preZ = current.players[localPlayer].z;
        current = restored;
        head = frame;
        rollbackCount++;
        resimulatedFrames += target - frame;
        lastRollbackFrame = frame;
        while (head < target) {
            simulateFrame();
        }

        localCorrX += preX - current.players[localPlayer].x;
        localCorrY += preY - current.players[localPlayer].y;
        localCorrZ += preZ - current.players[localPlayer].z;
    }

    public void drainLocalCorrection(double[] out) {
        out[0] = localCorrX;
        out[1] = localCorrY;
        out[2] = localCorrZ;
        localCorrX = 0;
        localCorrY = 0;
        localCorrZ = 0;
    }

    public static final int PREDICTION_DECAY_FRAMES = 20;

    private Input knownRemote(int frame) {
        Input actual = remoteActual.get(frame);
        if (actual != null) {
            return actual;
        }
        if (frame - knownContiguous >= PREDICTION_DECAY_FRAMES) {
            return predictedBase.withSynthetic(true).released();
        }

        return predictedBase.heldOnly();
    }

    private void recomputeContiguous() {
        while (knownContiguous < remoteActual.end() && remoteActual.get(knownContiguous) != null) {
            knownContiguous++;
        }
        predictedBase = knownContiguous > 0 ? remoteActual.get(knownContiguous - 1) : Input.NONE;
    }
}
