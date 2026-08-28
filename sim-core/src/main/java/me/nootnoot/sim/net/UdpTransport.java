package me.nootnoot.sim.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class UdpTransport implements Transport {
    private static final int MAX_PACKET = 16384;
    private static final long HELLO_KEEPALIVE_NANOS = 500_000_000L;
    static final int MAX_INBOX = 4096;

    static final int MAX_INBOX_BYTES = 4 * 1024 * 1024;

    private final DatagramSocket socket;
    private final SocketAddress relay;
    private final byte[] helloPacket;
    private final Queue<byte[]> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger inboxDepth = new AtomicInteger();
    private final AtomicInteger inboxBytes = new AtomicInteger();
    private final AtomicLong inboxOverflow = new AtomicLong();
    private final Thread reader;
    private volatile boolean open = true;

    public UdpTransport(SocketAddress relay, long sessionId, int slot) throws SocketException {
        this(relay, sessionId, slot, Message.EMPTY_TOKEN);
    }

    public UdpTransport(SocketAddress relay, long sessionId, int slot, byte[] token) throws SocketException {
        this.socket = new DatagramSocket();
        this.relay = relay;
        try {
            socket.setSoTimeout(200);
        } catch (SocketException ignored) {
        }
        try {
            socket.connect(relay);
        } catch (Throwable ignored) {
        }
        byte[] tok = token == null ? Message.EMPTY_TOKEN : token;
        this.helloPacket = Protocol.encode(new Message.Hello(sessionId, slot, Protocol.VERSION, tok));
        send(helloPacket);
        this.reader = new Thread(this::readLoop, "udp-transport-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    @Override
    public void send(byte[] packet) {
        try {
            socket.send(new DatagramPacket(packet, packet.length, relay));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public List<byte[]> receive() {
        List<byte[]> out = new ArrayList<>();
        byte[] b;
        while ((b = inbox.poll()) != null) {
            inboxDepth.decrementAndGet();
            inboxBytes.addAndGet(-b.length);
            out.add(b);
        }
        return out;
    }

    public long inboxOverflowPackets() {
        return inboxOverflow.get();
    }

    @Override
    public void close() {
        open = false;
        socket.close();
    }

    private void readLoop() {
        byte[] buf = new byte[MAX_PACKET];
        long lastHello = System.nanoTime();
        while (open) {
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(dp);

                if (relay.equals(dp.getSocketAddress())
                        && Protocol.isWellFormed(dp.getData(), dp.getLength())) {
                    int len = dp.getLength();
                    if (inboxDepth.get() < MAX_INBOX && inboxBytes.get() + len <= MAX_INBOX_BYTES) {
                        inbox.add(Arrays.copyOf(dp.getData(), len));
                        inboxDepth.incrementAndGet();
                        inboxBytes.addAndGet(len);
                    } else {
                        inboxOverflow.incrementAndGet();
                    }
                }
            } catch (SocketTimeoutException e) {
            } catch (IOException e) {
                if (!open) {
                    return;
                }
            } catch (Throwable t) {
                if (!open) {
                    return;
                }
            }
            long now = System.nanoTime();
            if (now - lastHello >= HELLO_KEEPALIVE_NANOS) {
                send(helloPacket);
                lastHello = now;
            }
        }
    }
}
