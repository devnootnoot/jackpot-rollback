package me.nootnoot.sim.host;

import java.util.ArrayList;
import java.util.List;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;

/**
 * Per-player combat stats for the end-of-match screen, accumulated from the sim's CONFIRMED events so a
 * rollback never double-counts. Tracks cumulative totals + a per-round snapshot (taken whenever a round is
 * decided), so the screen can show both the match totals and a round-by-round breakdown.
 */
public final class MatchStats {

    public final int[] meleeSwings = new int[2]; // melee attack clicks (NOT block placements — kind 1 is a place)
    public final int[] meleeHits = new int[2];
    public final int[] crits = new int[2];
    public final int[] arrowsUsed = new int[2];

    /** Cumulative {meleeSwings,meleeHits,crits,arrowsUsed} per slot, captured at each round's end. */
    private final List<int[]> roundSnapshots = new ArrayList<>();
    private int roundsSeen;

    /** Fold this tick's confirmed events + state into the running totals, snapshotting on each round end. */
    public void accept(List<CombatEvent> confirmedEvents, GameState confirmed) {
        for (CombatEvent e : confirmedEvents) {
            int a = e.attacker();
            if (a != 0 && a != 1) {
                continue;
            }
            if (e.type() == CombatEvent.SWING && e.kind() == 0) {
                meleeSwings[a]++;
            } else if (e.type() == CombatEvent.HIT && e.kind() <= CombatEvent.HIT_CRIT) {
                // MELEE hits only (kind 0-3 = weak/strong/knockback/crit). Arrow hits (HIT_ARROW) and fire/lava
                // tick damage (HIT_FIRE) are NOT swings — counting them blew melee accuracy past 100% (a lava
                // dip alone fires HIT_FIRE every few ticks with no swing).
                meleeHits[a]++;
                if (e.crit()) {
                    crits[a]++;
                }
            }
        }
        arrowsUsed[0] = confirmed.players[0].arrowsConsumed;
        arrowsUsed[1] = confirmed.players[1].arrowsConsumed;
        int roundTotal = confirmed.roundWinsP0 + confirmed.roundWinsP1;
        while (roundsSeen < roundTotal) {
            roundSnapshots.add(new int[]{
                    meleeSwings[0], meleeHits[0], crits[0], arrowsUsed[0],
                    meleeSwings[1], meleeHits[1], crits[1], arrowsUsed[1]});
            roundsSeen++;
        }
    }

    public int roundCount() {
        return roundSnapshots.size();
    }

    /** One round's (0-based) delta for {@code slot}: an int[]{swings,hits,crits,arrows}. */
    public int[] round(int roundIndex, int slot) {
        int[] cur = roundSnapshots.get(roundIndex);
        int[] prev = roundIndex > 0 ? roundSnapshots.get(roundIndex - 1) : new int[8];
        int base = slot * 4;
        return new int[]{
                cur[base] - prev[base], cur[base + 1] - prev[base + 1],
                cur[base + 2] - prev[base + 2], cur[base + 3] - prev[base + 3]};
    }

    /** Whole-match totals for {@code slot}: {swings,hits,crits,arrows}. */
    public int[] total(int slot) {
        return new int[]{meleeSwings[slot], meleeHits[slot], crits[slot], arrowsUsed[slot]};
    }
}
