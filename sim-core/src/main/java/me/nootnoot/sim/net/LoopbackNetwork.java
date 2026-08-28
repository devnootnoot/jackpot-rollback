package me.nootnoot.sim.net;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public final class LoopbackNetwork {
    private static final class Pkt {
        final int deliverAt;
        final int seq;
        final byte[] data;

        Pkt(int deliverAt, int seq, byte[] data) {
            this.deliverAt = deliverAt;
            this.seq = seq;
            this.data = data;
        }
    }

    private final Random rng;
    private final int baseDelay;
    private final int jitter;
    private final double loss;

    private final List<Pkt> inbox0 = new ArrayList<>();
    private final List<Pkt> inbox1 = new ArrayList<>();
    private int now;
    private int seq;

    public LoopbackNetwork(long seed, int baseDelay, int jitter, double loss) {
        this.rng = new Random(seed);
        this.baseDelay = baseDelay;
        this.jitter = jitter;
        this.loss = loss;
    }

    public void step() {
        now++;
    }

    public Transport endpoint(int slot) {
        return new Transport() {
            @Override
            public void send(byte[] packet) {
                deliver(slot, packet);
            }

            @Override
            public List<byte[]> receive() {
                return drain(slot);
            }
        };
    }

    private void deliver(int fromSlot, byte[] data) {
        if (loss > 0.0 && rng.nextDouble() < loss) {
            return;
        }
        int delay = baseDelay + (jitter > 0 ? rng.nextInt(jitter + 1) : 0);
        Pkt p = new Pkt(now + delay, seq++, data);
        (fromSlot == 0 ? inbox1 : inbox0).add(p);
    }

    private List<byte[]> drain(int slot) {
        List<Pkt> inbox = slot == 0 ? inbox0 : inbox1;
        List<Pkt> ready = new ArrayList<>();
        Iterator<Pkt> it = inbox.iterator();
        while (it.hasNext()) {
            Pkt p = it.next();
            if (p.deliverAt <= now) {
                ready.add(p);
                it.remove();
            }
        }
        ready.sort(Comparator.comparingInt((Pkt p) -> p.deliverAt).thenComparingInt(p -> p.seq));
        List<byte[]> out = new ArrayList<>(ready.size());
        for (Pkt p : ready) {
            out.add(p.data);
        }
        return out;
    }
}
