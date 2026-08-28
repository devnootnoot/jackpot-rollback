package me.nootnoot.sim.host.load;

import java.util.List;
import me.nootnoot.sim.net.Transport;

public final class CountingTransport implements Transport {

    private final Transport delegate;

    private long packetsSent;
    private long bytesSent;
    private long packetsReceived;
    private long bytesReceived;

    public CountingTransport(Transport delegate) {
        this.delegate = delegate;
    }

    @Override
    public void send(byte[] packet) {
        packetsSent++;
        bytesSent += packet.length;
        delegate.send(packet);
    }

    @Override
    public List<byte[]> receive() {
        List<byte[]> in = delegate.receive();
        packetsReceived += in.size();
        for (byte[] p : in) {
            bytesReceived += p.length;
        }
        return in;
    }

    @Override
    public void close() {
        delegate.close();
    }

    public long packetsSent() {
        return packetsSent;
    }

    public long bytesSent() {
        return bytesSent;
    }

    public long packetsReceived() {
        return packetsReceived;
    }

    public long bytesReceived() {
        return bytesReceived;
    }
}
