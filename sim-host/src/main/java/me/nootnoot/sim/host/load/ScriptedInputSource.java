package me.nootnoot.sim.host.load;

import java.util.List;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.host.InputSource;
import me.nootnoot.sim.state.Input;

public final class ScriptedInputSource implements InputSource {

    private final List<Input[]> frames;
    private final int slot;
    private final int offset;
    private int cursor;

    public ScriptedInputSource(InputLog log, int slot, int offset) {
        this.frames = log.frames;
        this.slot = slot;
        this.offset = Math.floorMod(offset, Math.max(1, log.frames.size()));
    }

    @Override
    public Input sample() {
        int i = Math.floorMod(offset + cursor++, frames.size());
        return frames.get(i)[slot];
    }

    public int sampled() {
        return cursor;
    }
}
