package me.nootnoot.sim.host;

import java.util.List;
import me.nootnoot.sim.ClaimAuthority;
import me.nootnoot.sim.net.InputCodec;
import me.nootnoot.sim.net.NetSession;
import me.nootnoot.sim.net.Transport;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.PlayerState;

/**
 * Drives one rollback match: every host tick it samples local input, advances the networked sim
 * ({@link NetSession}), and presents the authoritative local state. Pure glue — depends only on
 * sim-core + the integration seams, so it carries no game-API types and is testable headlessly.
 *
 * <p>Lifecycle: constructed at handoff (with the peer {@link Transport}, this peer's slot, and the
 * agreed {@link Arena} + seeded {@link GameState}); {@link #tick()} each host tick until it returns
 * false (match ended or aborted); then {@link #end()}.
 */
public final class MatchDriver {

    /** Frames of LOCAL input delay (both peers MUST match — it's fenced by the protocol checksum rev).
     *  The single biggest smoothness knob: each frame of delay sends inputs one frame ahead so the peer
     *  usually has them before it simulates that frame, cutting mispredictions/rollbacks (smoother opponent
     *  + better hit-reg) — at the cost of that many ticks (×50 ms) of local input latency. 0 = pure 0-ms. */
    private static final int INPUT_DELAY = ClaimAuthority.INPUT_DELAY_FRAMES;

    private static final int DIAGNOSTIC_INTERVAL_TICKS = 20;

    private final NetSession session;
    private final Transport transport;
    private final InputSource input;
    private final SimRenderer renderer;
    private final MatchTelemetry telemetry;

    private boolean finished;
    private boolean ended;
    private int localSlot = -1;
    private int dbgTick;
    private final MatchStats stats = new MatchStats();
    private final Arena arena;
    private final double[] corrScratch = new double[3];
    private final double[] peerCorrScratch = new double[3]; // reused drain buffer for the local rollback correction

    public MatchDriver(Transport transport, int slot, Arena arena, GameState initial,
                       int ringCapacity, InputSource input, SimRenderer renderer) {
        this(transport, slot, arena, initial, ringCapacity, input, renderer, MatchTelemetry.NONE);
    }

    public MatchDriver(Transport transport, int slot, Arena arena, GameState initial,
                       int ringCapacity, InputSource input, SimRenderer renderer,
                       MatchTelemetry telemetry) {
        this.transport = transport;
        this.arena = arena;
        this.session = new NetSession(transport, slot, arena, initial, ringCapacity, INPUT_DELAY);
        this.input = input;
        this.renderer = renderer;
        this.telemetry = telemetry != null ? telemetry : MatchTelemetry.NONE;
        this.localSlot = slot; // needed for the favor-the-attacker hit decision in tick()
        this.telemetry.matchStarted(slot, InputCodec.BYTES);
    }

    /** Combat stats accumulated from confirmed events — read at match end for the result screen. */
    public MatchStats stats() {
        return stats;
    }

    public Arena arena() {
        return arena;
    }

    /** Advance one host tick. Returns false when the match is over or has aborted. */
    public boolean tick() {
        if (session.aborted()) {
            return false;
        }
        // The favor-the-attacker melee-hit decision is made by the InputSource (the mod ray-casts the real
        // camera against the opponent ENTITY's rendered box — what the player actually sees), so
        // the sample the session pulls already carries the correct meleeHit flag. The session pulls it
        // ITSELF, and only on a tick it is going to run - sampling is a destructive read.
        session.update(input::sample);
        int catchUpFrames = session.drainCatchUpFrames();
        // Hand the renderer this tick's local rollback correction so it can ease the camera across a
        // re-simulated knockback instead of letting setPosition snap to the corrected head.
        session.drainLocalCorrection(corrScratch);
        session.drainPeerCorrection(peerCorrScratch);
        List<CombatEvent> confirmedEvents = session.drainConfirmedEvents();
        GameState confirmed = session.confirmedState();
        GameState headState = session.state();
        stats.accept(confirmedEvents, confirmed);
        try {
            renderer.beginCatchUp(catchUpFrames);
            renderer.feedLocalCorrection(corrScratch[0], corrScratch[1], corrScratch[2]);
            renderer.feedPeerCorrection(peerCorrScratch[0], peerCorrScratch[1],
                    peerCorrScratch[2]);
            renderer.render(headState, confirmed);
            renderer.playLocalHits(headState.events, headState, session.head());
            renderer.playEvents(confirmedEvents, confirmed);
        } catch (Throwable t) {
            telemetry.renderFailed(t);
        }
        if (++dbgTick % DIAGNOSTIC_INTERVAL_TICKS == 0) {
            PlayerState mine = session.state().players[Math.max(0, localSlot)];
            telemetry.tickDiagnostics(dbgTick, mine);
        }
        if (session.aborted()) {
            return false;
        }
        // Over when WE confirm a death, OR the peer CONCEDED (named us the winner). A peer Finish that names
        // the PEER is never a result here: taking it would let a tampered client end a live match and make
        // this honest host report itself the loser. NetSession keeps simulating on such a claim and, if this
        // side still cannot confirm it, resolves it as a forfeit by the claimer.
        if (session.confirmedMatchOver() || session.remoteFinished()) {
            finished = true;
            return false;
        }
        return true;
    }

    public MatchDriver withLocalSlot(int slot) {
        this.localSlot = slot;
        return this;
    }

    public boolean aborted() {
        return session.aborted();
    }

    /** Confirmed round wins for a player slot (0/1) — the final best-of-N score, reported to the plugin. */
    public int roundWins(int slot) {
        // If we ended on the peer's Finish (before locally confirming the deciding frame), use its authoritative
        // score so our end screen matches; otherwise our own confirmed score.
        if (session.remoteFinished() && session.confirmedLoser() < 0) {
            return slot == 0 ? session.remoteWinsP0() : session.remoteWinsP1();
        }
        return session.confirmedRoundWins(slot);
    }

    /** True if the match ended normally (a player died), as opposed to {@link #aborted()}. */
    public boolean finishedNormally() {
        return finished;
    }

    /** True if THIS peer won. Only meaningful after a normal finish. Prefers our own confirmed result; falls
     *  back to the peer's reported winner when we ended on its Finish before confirming the deciding frame. */
    public boolean localWon() {
        int loser = session.confirmedLoser();
        if (loser >= 0) {
            return loser != localSlot;
        }
        if (session.remoteFinished()) {
            return session.remoteWinnerSlot() == localSlot;
        }
        return false;
    }

    public String abortReason() {
        return session.abortReason();
    }

    /** True if the match aborted because the PEER DISCONNECTED mid-match — the remaining player wins by forfeit. */
    public boolean peerDisconnected() {
        return session.peerDisconnected();
    }

    public boolean selfFaulted() {
        return session.selfFaulted();
    }

    public boolean peerFaulted() {
        return session.peerFaulted();
    }

    public boolean peerAnnouncedDesync() {
        return session.peerAnnouncedDesync();
    }

    public int peerChecksumMismatches() {
        return session.peerChecksumMismatches();
    }

    public int peerChecksumOverreach() {
        return session.peerChecksumOverreach();
    }

    // --- diagnostics ---
    public int myFrameAdvantage() {
        return session.myFrameAdvantage();
    }

    public int peerFrameAdvantage() {
        return session.peerFrameAdvantage();
    }

    public int head() {
        return session.head();
    }

    public int confirmedFrame() {
        return session.confirmedFrame();
    }

    public int rollbackCount() {
        return session.rollbackCount();
    }

    public long resimulatedFrames() {
        return session.resimulatedFrames();
    }

    public NetSession.Retention retention() {
        return session.retention();
    }

    public int desyncFrame() {
        return session.desyncFrame();
    }

    public GameState state() {
        return session.state();
    }

    public GameState confirmedState() {
        return session.confirmedState();
    }

    /** Send a chat line to the opponent (out-of-band over the relay — never affects the sim). */
    public void sendChat(String text) {
        session.sendChat(text);
    }

    /** Chat lines received from the opponent since the last call (to display in the local chat HUD). */
    public List<String> pollChat() {
        return session.drainChat();
    }

    /** Send a container-sync blob to the opponent (out-of-band over the relay — never affects the sim). */
    public void sendContainer(byte[] data) {
        session.sendContainer(data);
    }

    /** Container-sync blobs received from the opponent since the last call (applied locally this tick). */
    public List<byte[]> pollContainer() {
        return session.drainContainer();
    }

    /** Forfeit: tell the peer they won (a {@link me.nootnoot.sim.net.Message.Finish}), so /leave ends the
     *  match cleanly as a loss for us and a win for them. */
    public void forfeit() {
        finished = true;
        session.sendFinish(1 - localSlot);
    }

    /** Release presentation + network resources when the match ends. */
    public void end() {
        if (ended) {
            return;
        }
        ended = true;
        // If we're leaving WITHOUT a clean finish (a quit / forced teardown) and the peer doesn't already
        // know (they didn't abort), tell them NOW so their match ends immediately instead of waiting out the
        // ~30s peer-silence timeout. On a clean finish both peers detect it independently, so we stay quiet to
        // avoid racing a stray abort against their own clean end.
        if (!finished && !session.aborted()) {
            endStep("peer teardown notice", session::teardown);
        }
        endStep("renderer clear", renderer::clear);
        endStep("transport close", transport::close);
    }

    public boolean ended() {
        return ended;
    }

    private void endStep(String what, Runnable step) {
        try {
            step.run();
        } catch (Throwable t) {
            telemetry.teardownFailed(what, t);
        }
    }
}
