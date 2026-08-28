package me.nootnoot.sim.net;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class LaggedTransport implements Transport {

    public static final int MAX_ONE_WAY_MILLIS = 2000;

    private record Held(long releaseAtNanos, byte[] packet) {
    }

    private final Transport delegate;
    private final int oneWayMillis;
    private final ArrayDeque<Held> outbound = new ArrayDeque<>();
    private final ArrayDeque<Held> inbound = new ArrayDeque<>();

    public LaggedTransport(Transport delegate, int oneWayMillis) {
        if (delegate == null) {
            throw new IllegalArgumentException("LaggedTransport needs something to wrap");
        }
        this.delegate = delegate;
        this.oneWayMillis = clamp(oneWayMillis);
    }

    public static int clamp(int oneWayMillis) {
        if (oneWayMillis <= 0) {
            return 0;
        }
        return Math.min(MAX_ONE_WAY_MILLIS, oneWayMillis);
    }

    public int oneWayMillis() {
        return oneWayMillis;
    }

    public int addedRoundTripMillis() {
        return oneWayMillis * 2;
    }

    @Override
    public void send(byte[] packet) {
        if (oneWayMillis == 0) {
            delegate.send(packet);
            return;
        }
        outbound.addLast(new Held(System.nanoTime() + oneWayMillis * 1_000_000L, packet));
        flushOutbound();
    }

    @Override
    public List<byte[]> receive() {
        if (oneWayMillis == 0) {
            return delegate.receive();
        }
        flushOutbound();
        long now = System.nanoTime();
        for (byte[] packet : delegate.receive()) {
            inbound.addLast(new Held(now + oneWayMillis * 1_000_000L, packet));
        }
        List<byte[]> due = new ArrayList<>();
        while (!inbound.isEmpty() && inbound.peekFirst().releaseAtNanos() <= now) {
            due.add(inbound.pollFirst().packet());
        }
        return due;
    }

    @Override
    public void close() {
        outbound.clear();
        inbound.clear();
        delegate.close();
    }

    private void flushOutbound() {
        long now = System.nanoTime();
        while (!outbound.isEmpty() && outbound.peekFirst().releaseAtNanos() <= now) {
            delegate.send(outbound.pollFirst().packet());
        }
    }
}
