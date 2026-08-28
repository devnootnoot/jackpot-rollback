package me.nootnoot.sim.host.load;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.host.MatchDriver;
import me.nootnoot.sim.net.LoopbackNetwork;
import me.nootnoot.sim.state.Arena;

public final class LoadMatch {

    private final int index;
    private final LoopbackNetwork net;
    private final MatchDriver[] drivers = new MatchDriver[2];
    private final CountingRenderer[] renderers = new CountingRenderer[2];
    private final CountingTransport[] transports = new CountingTransport[2];
    private final ScriptedInputSource[] inputs = new ScriptedInputSource[2];
    private final boolean[] alive = {true, true};

    private String deadReason;

    public LoadMatch(int index, InputLog log, Arena arena, LoadConfig config, long seed) {
        this.index = index;
        this.net = new LoopbackNetwork(seed, config.latencyFrames(), config.jitterFrames(),
                config.loss());
        int offset = Math.floorMod(seed >>> 13, Math.max(1, log.frames.size()));
        for (int slot = 0; slot < 2; slot++) {
            transports[slot] = new CountingTransport(net.endpoint(slot));
            renderers[slot] = new CountingRenderer();
            inputs[slot] = new ScriptedInputSource(log, slot, offset);
            drivers[slot] = new MatchDriver(transports[slot], slot, arena,
                    HarnessScenarios.combat(arena), config.ringCapacity(), inputs[slot],
                    renderers[slot]);
        }
    }

    public int index() {
        return index;
    }

    public void stepNetwork() {
        net.step();
    }

    public boolean alive(int slot) {
        return alive[slot];
    }

    public boolean anyAlive() {
        return alive[0] || alive[1];
    }

    public MatchDriver driver(int slot) {
        return drivers[slot];
    }

    public CountingRenderer renderer(int slot) {
        return renderers[slot];
    }

    public CountingTransport transport(int slot) {
        return transports[slot];
    }

    public void tick(int slot) {
        if (!alive[slot]) {
            return;
        }
        if (!drivers[slot].tick()) {
            alive[slot] = false;
            if (deadReason == null) {
                deadReason = drivers[slot].aborted()
                        ? "aborted: " + drivers[slot].abortReason()
                        : "finished normally at frame " + drivers[slot].head();
            }
        }
    }

    public String deadReason() {
        return deadReason;
    }
}
