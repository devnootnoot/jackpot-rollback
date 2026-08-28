package me.nootnoot.relay;

import java.net.SocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

final class RateLimiter {
    private static final class Bucket {
        double packets;
        double bytes;
        long lastNanos;
    }

    private final double srcPktRate;
    private final double srcPktBurst;
    private final double srcByteRate;
    private final double srcByteBurst;
    private final double sesPktRate;
    private final double sesPktBurst;
    private final double globalPktRate;
    private final double globalPktBurst;

    private final Bucket global = new Bucket();
    private final Map<SocketAddress, Bucket> sources;
    private final Map<Long, Bucket> bySession = new java.util.HashMap<>();

    RateLimiter(double srcPktRate, double srcPktBurst, double srcByteRate, double srcByteBurst,
                double sesPktRate, double sesPktBurst, double globalPktRate, double globalPktBurst,
                int maxSources) {
        this.srcPktRate = srcPktRate;
        this.srcPktBurst = srcPktBurst;
        this.srcByteRate = srcByteRate;
        this.srcByteBurst = srcByteBurst;
        this.sesPktRate = sesPktRate;
        this.sesPktBurst = sesPktBurst;
        this.globalPktRate = globalPktRate;
        this.globalPktBurst = globalPktBurst;
        this.global.packets = Double.MAX_VALUE;
        this.global.bytes = Double.MAX_VALUE;
        final int cap = Math.max(16, maxSources);
        this.sources = new LinkedHashMap<>(1024, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<SocketAddress, Bucket> eldest) {
                return size() > cap;
            }
        };
    }

    boolean allowGlobal(int packetBytes, long now) {
        return take(global, globalPktRate, globalPktBurst, 0, 0, packetBytes, now);
    }

    boolean allowSource(SocketAddress src, int packetBytes, long now) {
        Bucket bk = sources.computeIfAbsent(src, k -> fresh(now));
        return take(bk, srcPktRate, srcPktBurst, srcByteRate, srcByteBurst, packetBytes, now);
    }

    boolean allowSession(long session, int packetBytes, long now) {
        Bucket bk = bySession.computeIfAbsent(session, k -> fresh(now));
        return take(bk, sesPktRate, sesPktBurst, 0, 0, packetBytes, now);
    }

    void forgetSession(long session) {
        bySession.remove(session);
    }

    private static Bucket fresh(long now) {
        Bucket bk = new Bucket();
        bk.packets = Double.MAX_VALUE;
        bk.bytes = Double.MAX_VALUE;
        bk.lastNanos = now;
        return bk;
    }

    private static boolean take(Bucket bk, double pktRate, double pktBurst,
                                double byteRate, double byteBurst, int packetBytes, long now) {
        double elapsed = (now - bk.lastNanos) / 1_000_000_000.0;
        if (elapsed < 0) {
            elapsed = 0;
        }
        bk.lastNanos = now;
        bk.packets = Math.min(pktBurst, bk.packets + elapsed * pktRate);
        if (byteRate > 0) {
            bk.bytes = Math.min(byteBurst, bk.bytes + elapsed * byteRate);
        }
        if (bk.packets < 1.0) {
            return false;
        }
        if (byteRate > 0 && bk.bytes < packetBytes) {
            return false;
        }
        bk.packets -= 1.0;
        if (byteRate > 0) {
            bk.bytes -= packetBytes;
        }
        return true;
    }
}
