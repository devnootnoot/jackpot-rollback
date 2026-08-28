package me.nootnoot.relay;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import me.nootnoot.sim.ArenaCodec;
import me.nootnoot.sim.GameStateFrame0Codec;
import me.nootnoot.sim.MatchSetupFrame0Decoder;
import me.nootnoot.sim.net.ControlProtocol;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;

final class RefereeManager {

    private volatile Runnable onShed;
    @FunctionalInterface
    interface ResultSink {
        void accept(ControlProtocol.Result result);
    }

    private static final class Entry {
        final SessionReferee referee;
        final ResultSink sink;
        final long expiryEpochMs;
        final long arenaKey;
        volatile boolean ingested;
        private boolean reported;

        Entry(SessionReferee referee, ResultSink sink, long expiryEpochMs, long arenaKey) {
            this.referee = referee;
            this.sink = sink;
            this.expiryEpochMs = expiryEpochMs;
            this.arenaKey = arenaKey;
        }

        synchronized boolean claimReport() {
            if (reported) {
                return false;
            }
            reported = true;
            return true;
        }
    }

    private record Job(long sessionId, int slot, byte[] packet) {
    }

    private final Map<Long, Entry> sessions = new ConcurrentHashMap<>();
    private final ArenaPool arenas = new ArenaPool();
    private static final int MAX_QUEUE = 65536;

    private final ConcurrentLinkedQueue<Job> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final AtomicLong shed = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;
    private long lastSweepMs = System.currentTimeMillis();
    private static final long SWEEP_INTERVAL_MS = 5_000L;

    RefereeManager() {
        this.worker = new Thread(this::loop, "referee-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    void authorize(ControlProtocol.Authorize a, ResultSink sink) {
        Arena arena = new Arena(a.arenaGroundY(), a.arenaBoxes());
        GameState frame0 = GameStateFrame0Codec.decode(a.frame0());
        register(a.sessionId(), a.expiryEpochMs(), a.slot0Token(), a.slot1Token(), arena, frame0,
                sink, ArenaPool.NOT_POOLED);
    }

    void authorize(ControlProtocol.AuthorizeSetup a, ResultSink sink) {
        MatchSetupFrame0Decoder.Frame0 f = MatchSetupFrame0Decoder.decode(a.matchSetup());
        byte[] blob = a.arenaBlob();
        boolean pooled = blob != null && blob.length > 0;
        long arenaKey = pooled ? ArenaPool.keyOf(blob) : ArenaPool.NOT_POOLED;
        Arena arena = pooled ? arenas.acquire(arenaKey, blob)
                : new Arena(f.arenaGroundY(), f.arenaBoxes());
        register(a.sessionId(), a.expiryEpochMs(), a.slot0Token(), a.slot1Token(), arena, f.state(),
                sink, arenaKey);
    }

    private void register(long sessionId, long expiryEpochMs, byte[] slot0Token, byte[] slot1Token,
                          Arena arena, GameState frame0, ResultSink sink, long arenaKey) {
        Entry live = sessions.get(sessionId);
        if (live != null && live.ingested) {
            arenas.release(arenaKey);
            return;
        }
        Entry replaced = sessions.put(sessionId, new Entry(
                new SessionReferee(sessionId, slot0Token, slot1Token, arena, frame0), sink,
                expiryEpochMs, arenaKey));
        if (replaced != null) {
            arenas.release(replaced.arenaKey);
        }
    }

    int pooledArenas() {
        return arenas.pooled();
    }

    boolean hasJudgedFrames(long sessionId) {
        Entry e = sessions.get(sessionId);
        return e != null && e.ingested;
    }

    boolean isAuthorized(long sessionId) {
        return sessions.containsKey(sessionId);
    }

    boolean tokenMatches(long sessionId, int slot, byte[] token) {
        Entry e = sessions.get(sessionId);
        return e != null && e.referee.tokenMatches(slot, token);
    }

    void tee(long sessionId, int slot, byte[] packetCopy) {
        Entry e = sessions.get(sessionId);
        if (e == null) {
            return;
        }
        if (queueDepth.get() >= MAX_QUEUE) {
            shed(sessionId, e);
            return;
        }
        queue.add(new Job(sessionId, slot, packetCopy));
        queueDepth.incrementAndGet();
    }

    private void shed(long sessionId, Entry e) {
        if (!sessions.remove(sessionId, e)) {
            return;
        }
        arenas.release(e.arenaKey);
        shed.incrementAndGet();
        Runnable hook = onShed;
        if (hook != null) {
            hook.run();
        }
        System.err.println("[relay] #### REFEREE SHED SESSION " + sessionId + " #### the"
                + " re-simulation queue is full (" + MAX_QUEUE + "), so this relay is taking more"
                + " matches than one referee thread can carry. Dropping frames from every session"
                + " instead would leave every verdict quietly wrong, so this session is dropped"
                + " WHOLE and openly: it now falls back to the two clients corroborating each"
                + " other. Watch rollback_relay_referee_shed_total and add a relay.");
        if (e.claimReport()) {
            e.sink.accept(e.referee.result());
        }
    }

    void onShed(Runnable hook) {
        this.onShed = hook;
    }

    long shedCount() {
        return shed.get();
    }

    int queueDepth() {
        return queueDepth.get();
    }

    void finalizeSession(long sessionId) {
        Entry e = sessions.remove(sessionId);
        if (e == null) {
            return;
        }
        arenas.release(e.arenaKey);
        if (e.claimReport()) {
            e.sink.accept(e.referee.result());
        }
    }

    int confirmedFrame(long sessionId) {
        Entry e = sessions.get(sessionId);
        return e == null ? -1 : e.referee.confirmedFrame();
    }

    void stop() {
        running = false;
        worker.interrupt();
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        if (now - lastSweepMs < SWEEP_INTERVAL_MS) {
            return;
        }
        lastSweepMs = now;
        for (Map.Entry<Long, Entry> e : sessions.entrySet()) {
            if (e.getValue().expiryEpochMs > 0L && now >= e.getValue().expiryEpochMs) {
                finalizeSession(e.getKey());
            }
        }
    }

    private void loop() {
        while (running) {
            Job job = queue.poll();
            if (job != null) {
                queueDepth.decrementAndGet();
            }
            if (job == null) {
                sweepExpired();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            Entry e = sessions.get(job.sessionId());
            if (e == null) {
                continue;
            }
            try {
                Message m = Protocol.decode(job.packet());
                if (m instanceof Message.InputFrames inf) {
                    e.referee.ingest(job.slot(), inf.baseFrame(), inf.inputs());
                    e.ingested = true;
                    if (e.referee.matchOver() && e.claimReport()) {
                        if (sessions.remove(job.sessionId(), e)) {
                            arenas.release(e.arenaKey);
                        }
                        e.sink.accept(e.referee.result());
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
    }
}
