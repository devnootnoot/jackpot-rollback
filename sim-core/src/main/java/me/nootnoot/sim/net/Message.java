package me.nootnoot.sim.net;

import java.util.List;
import me.nootnoot.sim.state.Input;

public sealed interface Message {
    record Hello(long sessionId, int slot, int protocolVersion, byte[] token) implements Message {
        public Hello(long sessionId, int slot, int protocolVersion) {
            this(sessionId, slot, protocolVersion, EMPTY_TOKEN);
        }
    }

    byte[] EMPTY_TOKEN = new byte[0];

    record InputFrames(int baseFrame, List<Input> inputs, int ack, int frameAdvantage) implements Message {
        public InputFrames(int baseFrame, List<Input> inputs, int ack) {
            this(baseFrame, inputs, ack, 0);
        }
    }

    record Checksum(int frame, long value) implements Message {
    }

    record Abort(int code) implements Message {
    }

    record Finish(int winnerSlot, int winsP0, int winsP1) implements Message {
    }

    record Snapshot(double groundY, double[][] boxes) implements Message {
    }

    record Heartbeat(long nonce) implements Message {
    }

    record Chat(String text) implements Message {
    }

    record Container(byte[] data) implements Message {
    }
}
