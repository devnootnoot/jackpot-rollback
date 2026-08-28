package me.nootnoot.edge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import me.nootnoot.sim.ArenaAgreement;
import me.nootnoot.sim.net.Message;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.net.Transport;

public final class EdgeHandshake implements Transport {

    public enum Status {
        WAITING,
        AGREED,
        MISMATCH,
        TIMEOUT
    }

    private static final int SEND_INTERVAL_TICKS = 5;
    private static final int TAIL_SENDS = 3;
    private static final int MAX_HELD_PACKETS = 512;

    private final Transport delegate;
    private final ArenaAgreement local;
    private final byte[] packet;
    private final int timeoutTicks;
    private final Deque<byte[]> held = new ArrayDeque<>();

    private Status status = Status.WAITING;
    private String failure;
    private ArenaAgreement peer;
    private int ticks;

    public EdgeHandshake(Transport delegate, ArenaAgreement local, int timeoutTicks) {
        this.delegate = delegate;
        this.local = local;
        this.packet = Protocol.encode(new Message.Container(local.encode()));
        this.timeoutTicks = Math.max(1, timeoutTicks);
    }

    public Status pump() {
        if (status != Status.WAITING) {
            return status;
        }
        for (byte[] raw : delegate.receive()) {
            byte[] body = containerBody(raw);
            ArenaAgreement remote = ArenaAgreement.decode(body);
            if (remote == null) {
                int claimed = ArenaAgreement.peerVersion(body);
                if (claimed >= 0 && claimed != ArenaAgreement.VERSION) {
                    failure = "the peer speaks arena-agreement version " + claimed
                            + " and this edge speaks " + ArenaAgreement.VERSION
                            + ", so the two are not the same build";
                    status = Status.MISMATCH;
                    break;
                }
                hold(raw);
                continue;
            }
            peer = remote;
            String reason = local.disagreement(remote);
            if (reason != null) {
                failure = reason;
                status = Status.MISMATCH;
                break;
            }
            status = Status.AGREED;
        }
        if (status != Status.WAITING) {
            for (int i = 0; i < TAIL_SENDS; i++) {
                delegate.send(packet);
            }
            return status;
        }
        if (ticks % SEND_INTERVAL_TICKS == 0) {
            delegate.send(packet);
        }
        if (++ticks >= timeoutTicks) {
            failure = "the peer edge never sent an arena hash within " + timeoutTicks + " ticks";
            status = Status.TIMEOUT;
        }
        return status;
    }

    public Status status() {
        return status;
    }

    public String failure() {
        return failure;
    }

    public ArenaAgreement local() {
        return local;
    }

    public ArenaAgreement peer() {
        return peer;
    }

    @Override
    public void send(byte[] packet) {
        delegate.send(packet);
    }

    @Override
    public List<byte[]> receive() {
        List<byte[]> fresh = delegate.receive();
        if (held.isEmpty()) {
            return fresh;
        }
        List<byte[]> out = new ArrayList<>(held.size() + fresh.size());
        out.addAll(held);
        held.clear();
        out.addAll(fresh);
        return out;
    }

    @Override
    public void close() {
        held.clear();
        delegate.close();
    }

    private void hold(byte[] packet) {
        if (held.size() >= MAX_HELD_PACKETS) {
            held.pollFirst();
        }
        held.addLast(packet);
    }

    private static byte[] containerBody(byte[] packet) {
        if (packet == null || !Protocol.isWellFormed(packet, packet.length)) {
            return null;
        }
        try {
            return Protocol.decode(packet) instanceof Message.Container c ? c.data() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
