package me.nootnoot.sim.host.load;

import java.util.List;
import me.nootnoot.sim.host.SimRenderer;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;

public final class CountingRenderer implements SimRenderer {

    private long catchUpBursts;
    private long catchUpFrames;
    private int deepestBurst;
    private long confirmedEvents;

    @Override
    public void render(GameState head, GameState confirmed) {
    }

    @Override
    public void playEvents(List<CombatEvent> events, GameState state) {
        confirmedEvents += events.size();
    }

    @Override
    public void beginCatchUp(int frames) {
        if (frames > 0) {
            catchUpBursts++;
            catchUpFrames += frames;
            if (frames > deepestBurst) {
                deepestBurst = frames;
            }
        }
    }

    @Override
    public void clear() {
    }

    public void resetDeepest() {
        deepestBurst = 0;
    }

    public long catchUpBursts() {
        return catchUpBursts;
    }

    public long catchUpFrames() {
        return catchUpFrames;
    }

    public int deepestBurst() {
        return deepestBurst;
    }

    public long confirmedEvents() {
        return confirmedEvents;
    }
}
