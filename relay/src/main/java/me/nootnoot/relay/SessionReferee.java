package me.nootnoot.relay;

import java.security.MessageDigest;
import java.util.List;
import me.nootnoot.sim.RefereeSession;
import me.nootnoot.sim.net.ControlProtocol;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;

final class SessionReferee {
    private final long sessionId;
    private final byte[][] tokens;
    private final RefereeSession sim;

    SessionReferee(long sessionId, byte[] slot0Token, byte[] slot1Token, Arena arena,
                   GameState frame0) {
        this.sessionId = sessionId;
        this.tokens = new byte[][]{slot0Token, slot1Token};
        this.sim = new RefereeSession(arena, frame0);
    }

    boolean tokenMatches(int slot, byte[] token) {
        if (slot < 0 || slot > 1 || token == null) {
            return false;
        }
        return MessageDigest.isEqual(tokens[slot], token);
    }

    synchronized void ingest(int slot, int baseFrame, List<Input> inputs) {
        sim.ingest(slot, baseFrame, inputs);
    }

    synchronized boolean matchOver() {
        return sim.matchOver();
    }

    synchronized ControlProtocol.Result result() {
        return new ControlProtocol.Result(sessionId, sim.matchOver(), sim.winnerSlot(),
                sim.roundWins(0), sim.roundWins(1), sim.confirmedFrame(), false);
    }

    synchronized int confirmedFrame() {
        return sim.confirmedFrame();
    }
}
