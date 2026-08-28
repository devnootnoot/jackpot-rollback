package me.nootnoot.sim.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class DirectLink implements AutoCloseable {

    public static final int MAGIC = LinkFrame.MAGIC;

    public static final int LEGACY_MAGIC = LinkFrame.LEGACY_MAGIC;

    public static final int HEADER_BYTES = LinkFrame.HEADER_BYTES;

    static final int MAX_PACKET = 16384;

    public static final int MIN_LINK_SECRET_BYTES = 16;

    static final long REBIND_GRACE_NANOS = 5_000_000_000L;

    static final int MAX_INBOX_PACKETS = 4096;

    static final int MAX_INBOX_BYTES = 4 * 1024 * 1024;

    static final double PKT_RATE = 600.0;
    static final double PKT_BURST = 1_200.0;
    static final double BYTE_RATE = 1_000_000.0;
    static final double BYTE_BURST = 2_000_000.0;

    static final double ENDPOINT_PKT_RATE = 500_000.0;
    static final double ENDPOINT_PKT_BURST = 1_000_000.0;

    static final double SRC_PKT_RATE = 250.0;
    static final double SRC_PKT_BURST = 500.0;
    static final double SRC_BYTE_RATE = 1_000_000.0;
    static final double SRC_BYTE_BURST = 2_000_000.0;

    static final int MAX_SOURCES = 65_536;

    private static final long HELLO_KEEPALIVE_NANOS = 500_000_000L;

    private static final int SOCKET_TIMEOUT_MILLIS = 200;

    private final DatagramSocket socket;
    private final Map<Long, Channel> channels = new ConcurrentHashMap<>();
    private final Thread reader;

    private final Bucket endpoint = Bucket.fresh(System.nanoTime());

    private final Map<SocketAddress, Bucket> sources =
            new LinkedHashMap<>(1024, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<SocketAddress, Bucket> eldest) {
                    return size() > MAX_SOURCES;
                }
            };

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong unknownSession = new AtomicLong();
    private final AtomicLong inboxOverflow = new AtomicLong();
    private final AtomicLong rateLimited = new AtomicLong();
    private final AtomicLong versionMismatch = new AtomicLong();
    private final AtomicLong rebinds = new AtomicLong();
    private final AtomicLong rebindsRefused = new AtomicLong();
    private final AtomicLong badSlotTokens = new AtomicLong();
    private final AtomicLong badFrameTags = new AtomicLong();
    private final AtomicLong replayedFrames = new AtomicLong();
    private final AtomicLong untaggedFrames = new AtomicLong();
    private final AtomicInteger lastMismatchedPeerVersion = new AtomicInteger(VersionFence.UNKNOWN);

    private volatile boolean open = true;

    public DirectLink(int port) throws SocketException {
        this(port, null);
    }

    public DirectLink(int port, String bindHost) throws SocketException {
        this.socket = bindHost == null || bindHost.isBlank()
                ? new DatagramSocket(port)
                : new DatagramSocket(new InetSocketAddress(bindHost, port));
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
        } catch (SocketException ignored) {
        }
        this.reader = new Thread(this::readLoop, "direct-link-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    public int openSessions() {
        return channels.size();
    }

    public long acceptedPackets() {
        return accepted.get();
    }

    public long rejectedPackets() {
        return rejected.get();
    }

    public long unknownSessionPackets() {
        return unknownSession.get();
    }

    public long inboxOverflowPackets() {
        return inboxOverflow.get();
    }

    public long rateLimitedPackets() {
        return rateLimited.get();
    }

    public long versionMismatchedHellos() {
        return versionMismatch.get();
    }

    public long peerRebinds() {
        return rebinds.get();
    }

    public long refusedRebinds() {
        return rebindsRefused.get();
    }

    public long badSlotTokens() {
        return badSlotTokens.get();
    }

    public long badFrameTags() {
        return badFrameTags.get();
    }

    public long replayedFrames() {
        return replayedFrames.get();
    }

    public long untaggedFrames() {
        return untaggedFrames.get();
    }

    public String untaggedFrameDiagnosis() {
        if (untaggedFrames.get() == 0) {
            return null;
        }
        return "the peer on the direct link is still framing datagrams the pre-tag way (magic "
                + Integer.toHexString(LEGACY_MAGIC) + ", no per-packet tag), so every one of its"
                + " packets is refused and the match will sit at the arena handshake until it"
                + " times out. That edge is running an older jackpot-edge.jar than this one."
                + " Deploy both edges together, or set direct.enabled=false on this one to put"
                + " every match back on the relay. " + String.join(" ", VersionFence.whereToLook());
    }

    public int lastMismatchedPeerVersion() {
        return lastMismatchedPeerVersion.get();
    }

    public String versionMismatchDiagnosis() {
        if (versionMismatch.get() == 0) {
            return null;
        }
        return "the peer on the direct link refused to bind: "
                + VersionFence.describe("the opponent's build", lastMismatchedPeerVersion.get())
                + " and this build is at " + VersionFence.local() + ", so the two never agreed a"
                + " link and every direct-link match falls back or times out. "
                + String.join(" ", VersionFence.whereToLook());
    }

    public Transport open(long sessionId, int localSlot, SocketAddress peer, byte[] linkSecret) {
        if (localSlot != 0 && localSlot != 1) {
            throw new IllegalArgumentException("slot must be 0 or 1: " + localSlot);
        }
        if (linkSecret == null || linkSecret.length < MIN_LINK_SECRET_BYTES) {
            throw new IllegalArgumentException("the direct link secret for session " + sessionId
                    + " is " + (linkSecret == null ? "absent" : linkSecret.length + " bytes")
                    + " and at least " + MIN_LINK_SECRET_BYTES + " are required. Fall back to the"
                    + " relay rather than opening a link whose slots nobody can vouch for.");
        }
        Channel channel = new Channel(sessionId, localSlot, peer, linkSecret.clone());
        Channel prev = channels.putIfAbsent(sessionId, channel);
        if (prev != null) {
            throw new IllegalStateException("session " + sessionId + " is already open on this link");
        }
        channel.sendHello();
        return channel;
    }

    @Override
    public void close() {
        open = false;
        channels.clear();
        socket.close();
    }

    private void readLoop() {
        byte[] buf = new byte[MAX_PACKET];
        while (open) {
            DatagramPacket dp = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(dp);
                deliver(dp);
            } catch (SocketTimeoutException expected) {
            } catch (IOException e) {
                if (!open) {
                    return;
                }
            } catch (Throwable t) {
                if (!open) {
                    return;
                }
                rejected.incrementAndGet();
            }
            keepAlive();
        }
    }

    private void keepAlive() {
        long now = System.nanoTime();
        for (Channel c : channels.values()) {
            c.keepAlive(now);
        }
    }

    private void deliver(DatagramPacket dp) {
        int len = dp.getLength();
        long now = System.nanoTime();
        SocketAddress from = dp.getSocketAddress();
        if (!endpoint.take(ENDPOINT_PKT_RATE, ENDPOINT_PKT_BURST, 0.0, 0.0, len, now)
                || !allowSource(from, len, now)) {
            rateLimited.incrementAndGet();
            return;
        }
        if (len < 4) {
            rejected.incrementAndGet();
            return;
        }
        byte[] data = dp.getData();
        int magic = LinkFrame.magicOf(data);
        if (magic != MAGIC) {
            if (magic == LEGACY_MAGIC) {
                untaggedFrames.incrementAndGet();
            } else {
                rejected.incrementAndGet();
            }
            return;
        }
        if (len <= HEADER_BYTES) {
            rejected.incrementAndGet();
            return;
        }
        long sessionId = LinkFrame.sessionOf(data);
        int senderSlot = LinkFrame.slotOf(data);
        Channel channel = channels.get(sessionId);
        if (channel == null) {
            unknownSession.incrementAndGet();
            return;
        }
        SocketAddress src = dp.getSocketAddress();
        if (!channel.admits(src, senderSlot)) {
            rejected.incrementAndGet();
            return;
        }
        if (!channel.authentic(data, len)) {
            badFrameTags.incrementAndGet();
            return;
        }
        if (!channel.unreplayed(LinkFrame.counterOf(data))) {
            replayedFrames.incrementAndGet();
            return;
        }
        int bodyLen = len - HEADER_BYTES;
        byte[] body = Arrays.copyOfRange(data, HEADER_BYTES, HEADER_BYTES + bodyLen);
        if (!Protocol.isWellFormed(body, bodyLen)) {
            rejected.incrementAndGet();
            return;
        }
        if (!channel.accept(src, body, bodyLen, now)) {
            rejected.incrementAndGet();
            return;
        }
        accepted.incrementAndGet();
    }

    private final class Channel implements Transport {

        private final long sessionId;
        private final int localSlot;
        private final SocketAddress peer;
        private final byte[] linkSecret;
        private final byte[] expectedPeerToken;
        private final byte[] helloPayload;
        private final AtomicLong outCounter = new AtomicLong();
        private final LinkFrame.ReplayWindow replay = new LinkFrame.ReplayWindow();
        private final Queue<byte[]> inbox = new ConcurrentLinkedQueue<>();
        private final AtomicInteger inboxDepth = new AtomicInteger();
        private final AtomicInteger inboxBytes = new AtomicInteger();

        private volatile SocketAddress boundPeer;
        private volatile boolean closed;
        private volatile boolean abortedLocally;
        private volatile long lastHelloNanos;
        private volatile long lastPeerPacketNanos;

        private double pktTokens = PKT_BURST;
        private double byteTokens = BYTE_BURST;
        private long bucketNanos;

        Channel(long sessionId, int localSlot, SocketAddress peer, byte[] linkSecret) {
            this.sessionId = sessionId;
            this.localSlot = localSlot;
            this.peer = peer;
            this.linkSecret = linkSecret;
            this.expectedPeerToken = SlotTokens.derive(linkSecret, sessionId, 1 - localSlot);
            this.helloPayload = Protocol.encode(new Message.Hello(sessionId, localSlot,
                    Protocol.VERSION, SlotTokens.derive(linkSecret, sessionId, localSlot)));
            this.lastHelloNanos = System.nanoTime();
            this.bucketNanos = this.lastHelloNanos;
            this.lastPeerPacketNanos = this.lastHelloNanos;
        }

        @Override
        public void send(byte[] packet) {
            if (closed) {
                return;
            }
            transmit(frame(packet));
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

        @Override
        public void close() {
            closed = true;
            inbox.clear();
            inboxDepth.set(0);
            inboxBytes.set(0);
            channels.remove(sessionId, this);
        }

        void abortLocally() {
            if (abortedLocally) {
                return;
            }
            abortedLocally = true;
            byte[] abort = Protocol.encode(new Message.Abort(Protocol.ABORT_VERSION_MISMATCH));
            inbox.add(abort);
            inboxDepth.incrementAndGet();
            inboxBytes.addAndGet(abort.length);
        }

        void sendHello() {
            transmit(frame(helloPayload));
            lastHelloNanos = System.nanoTime();
        }

        boolean authentic(byte[] wire, int len) {
            return !closed && LinkFrame.verify(linkSecret, wire, len);
        }

        boolean unreplayed(long counter) {
            synchronized (replay) {
                return replay.fresh(counter);
            }
        }

        void keepAlive(long now) {
            if (closed || now - lastHelloNanos < HELLO_KEEPALIVE_NANOS) {
                return;
            }
            sendHello();
        }

        boolean admits(SocketAddress src, int senderSlot) {
            if (closed || senderSlot == localSlot || (senderSlot != 0 && senderSlot != 1)) {
                return false;
            }
            SocketAddress proven = boundPeer;
            if (proven != null) {
                return proven.equals(src);
            }
            return sameHost(src, peer);
        }

        boolean accept(SocketAddress src, byte[] body, int bodyLen, long now) {
            if (closed) {
                return false;
            }
            if (!allow(bodyLen, now)) {
                rateLimited.incrementAndGet();
                return false;
            }
            if (Protocol.isHello(body, bodyLen)) {
                bindFrom(src, body, now);
                return true;
            }
            if (boundPeer == null || !boundPeer.equals(src)) {
                return false;
            }
            lastPeerPacketNanos = now;
            if (inboxDepth.get() >= MAX_INBOX_PACKETS || inboxBytes.get() >= MAX_INBOX_BYTES) {
                inboxOverflow.incrementAndGet();
                return false;
            }
            inbox.add(body);
            inboxDepth.incrementAndGet();
            inboxBytes.addAndGet(bodyLen);
            return true;
        }

        private boolean allow(int bytes, long now) {
            double elapsed = (now - bucketNanos) / 1_000_000_000.0;
            if (elapsed < 0) {
                elapsed = 0;
            }
            bucketNanos = now;
            pktTokens = Math.min(PKT_BURST, pktTokens + elapsed * PKT_RATE);
            byteTokens = Math.min(BYTE_BURST, byteTokens + elapsed * BYTE_RATE);
            if (pktTokens < 1.0 || byteTokens < bytes) {
                return false;
            }
            pktTokens -= 1.0;
            byteTokens -= bytes;
            return true;
        }

        private void bindFrom(SocketAddress src, byte[] body, long now) {
            Message decoded;
            try {
                decoded = Protocol.decode(body);
            } catch (RuntimeException malformed) {
                return;
            }
            if (!(decoded instanceof Message.Hello h)) {
                return;
            }
            if (h.sessionId() != sessionId || h.slot() == localSlot) {
                return;
            }
            if (h.protocolVersion() != Protocol.VERSION) {
                versionMismatch.incrementAndGet();
                lastMismatchedPeerVersion.set(h.protocolVersion());
                abortLocally();
                return;
            }
            if (!MessageDigest.isEqual(expectedPeerToken, h.token())) {
                badSlotTokens.incrementAndGet();
                return;
            }
            SocketAddress proven = boundPeer;
            if (proven != null && !proven.equals(src)) {
                boolean sameHost = sameHost(src, proven);
                boolean stale = now - lastPeerPacketNanos >= REBIND_GRACE_NANOS;
                if (!sameHost && !stale) {
                    rebindsRefused.incrementAndGet();
                    return;
                }
                rebinds.incrementAndGet();
            }
            boundPeer = src;
            lastPeerPacketNanos = now;
        }

        private void transmit(byte[] wire) {
            try {
                socket.send(new DatagramPacket(wire, wire.length, peer));
            } catch (Throwable ignored) {
            }
        }

        private byte[] frame(byte[] payload) {
            return LinkFrame.encode(sessionId, localSlot, outCounter.getAndIncrement(),
                    linkSecret, payload);
        }
    }

    private boolean allowSource(SocketAddress src, int bytes, long now) {
        Bucket bk;
        synchronized (sources) {
            bk = sources.computeIfAbsent(src, k -> Bucket.fresh(now));
        }
        return bk.take(SRC_PKT_RATE, SRC_PKT_BURST, SRC_BYTE_RATE, SRC_BYTE_BURST, bytes, now);
    }

    private static final class Bucket {
        private double packets;
        private double bytes;
        private long lastNanos;

        static Bucket fresh(long now) {
            Bucket bk = new Bucket();
            bk.packets = Double.MAX_VALUE;
            bk.bytes = Double.MAX_VALUE;
            bk.lastNanos = now;
            return bk;
        }

        synchronized boolean take(double pktRate, double pktBurst, double byteRate,
                                  double byteBurst, int packetBytes, long now) {
            double elapsed = (now - lastNanos) / 1_000_000_000.0;
            if (elapsed < 0) {
                elapsed = 0;
            }
            lastNanos = now;
            packets = Math.min(pktBurst, packets + elapsed * pktRate);
            if (byteRate > 0) {
                bytes = Math.min(byteBurst, bytes + elapsed * byteRate);
            }
            if (packets < 1.0) {
                return false;
            }
            if (byteRate > 0 && bytes < packetBytes) {
                return false;
            }
            packets -= 1.0;
            if (byteRate > 0) {
                bytes -= packetBytes;
            }
            return true;
        }
    }

    private static boolean sameHost(SocketAddress a, SocketAddress b) {
        if (a instanceof InetSocketAddress x && b instanceof InetSocketAddress y) {
            return x.getAddress() != null && x.getAddress().equals(y.getAddress());
        }
        return false;
    }
}
