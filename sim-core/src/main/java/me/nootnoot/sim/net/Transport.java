package me.nootnoot.sim.net;

import java.util.List;

public interface Transport {
    void send(byte[] packet);

    List<byte[]> receive();

    default void close() {
    }
}
