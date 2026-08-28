package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import me.nootnoot.sim.ArenaAgreement;
import me.nootnoot.sim.Checksum;
import me.nootnoot.sim.GameStateFrame0Codec;
import me.nootnoot.sim.MatchSetupFrame0Decoder;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.net.LoopbackNetwork;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CrossPlayCrystalMatchTest {

    private static final int RING = 2048;
    private static final int FRAMES = 6500;
    private static final int ROUNDS_TARGET = 9;
    private static final int ONE_WAY_FRAMES = 2;
    private static final int JITTER_FRAMES = 1;
    private static final double LOSS = 0.03;

    private static final long SESSION = 0x51D2A77C0FFEEL;

    private static final int END_WINDOW_FRAMES = 8;

    private static final int EDGE_SLOT = CrossPlayFixture.EDGE_SLOT;
    private static final int MOD_SLOT = CrossPlayFixture.MOD_SLOT;

    private static byte[] blob(int slot) {
        return CrossPlayFixture.setupBlob(CrossPlayFixture.kit(), SESSION, slot,
                new byte[]{1, 2, 3, 4}, ROUNDS_TARGET);
    }

    private record Seen(int deaths, int roundResets, int crystals, int anchorsPlaced,
                        int anchorsCharged, int projectiles, int containerOpens,
                        int containersDrained, int projectilesAcrossARoundBoundary,
                        int maxRoundWins, Map<Integer, Long> edgeSeal, Map<Integer, Long> modSeal) {

        int compare() {
            int compared = 0;
            for (Map.Entry<Integer, Long> e : edgeSeal.entrySet()) {
                Long theirs = modSeal.get(e.getKey());
                if (theirs == null) {
                    continue;
                }
                compared++;
                assertEquals(e.getValue(), theirs, "the two hosts confirmed DIFFERENT state at"
                        + " frame " + e.getKey() + ". A cross-play match that ends on one checksum"
                        + " after diverging in the middle is not converged, it is lucky");
            }
            return compared;
        }

        String describe() {
            return "deaths=" + deaths + " resets=" + roundResets + " crystals=" + crystals
                    + " anchors=" + anchorsPlaced + "/" + anchorsCharged + " projectiles="
                    + projectiles + " opens=" + containerOpens + " drained=" + containersDrained
                    + " airborneAtRoundEnd=" + projectilesAcrossARoundBoundary
                    + " maxRoundWins=" + maxRoundWins;
        }
    }

    @Test
    void theCanonicalDecoderTurnsOneSetupBlobIntoOneFrameZeroEveryTime() {
        byte[] wire = blob(MOD_SLOT);

        GameState first = MatchSetupFrame0Decoder.decode(wire).state();
        GameState second = MatchSetupFrame0Decoder.decode(wire).state();

        assertArrayEquals(GameStateFrame0Codec.encode(first),
                GameStateFrame0Codec.encode(second),
                "one blob must decode to the same bytes every time, not merely to states that"
                        + " happen to check out the same");
        assertEquals(Checksum.of(first), Checksum.of(second));
        assertTrue(first.edgeHosted[EDGE_SLOT], "slot 0 is the unmodded, edge hosted player");
        assertFalse(first.edgeHosted[MOD_SLOT], "slot 1 is the modded client");
    }

    @Test
    void readdressingTheBlobForTheOtherSlotChangesNothingTheSimReads() {
        GameState mod = MatchSetupFrame0Decoder.decode(blob(MOD_SLOT)).state();
        GameState edge = MatchSetupFrame0Decoder.decode(blob(EDGE_SLOT)).state();

        assertArrayEquals(GameStateFrame0Codec.encode(mod), GameStateFrame0Codec.encode(edge),
                "cross-play ships ONE setup blob and re-addresses it for the other slot. The slot"
                        + " byte and the relay token are the only things that may differ, so both"
                        + " sides must decode the identical frame 0 - that is the whole reason the"
                        + " re-address exists instead of two independent encodes");
    }

    @Test
    void aPreChargedCrossbowIsSeededIntoTheFrameAndIsChecksummed() {
        GameState decoded = MatchSetupFrame0Decoder.decode(blob(MOD_SLOT)).state();

        for (int slot = 0; slot < 2; slot++) {
            PlayerState p = decoded.players[slot];
            assertTrue(p.slotCrossbowLoaded[CrossPlayFixture.SLOT_CROSSBOW],
                    "the kit ships a charged crossbow, so the frame must open with it loaded");
            assertNotEquals(ItemDict.NONE, p.slotCrossbowEntry[CrossPlayFixture.SLOT_CROSSBOW],
                    "a loaded crossbow has to know WHICH arrow is in it, or the two hosts fire"
                            + " different projectiles from the same click");
        }

        long seeded = Checksum.of(decoded);
        decoded.players[MOD_SLOT].slotCrossbowEntry[CrossPlayFixture.SLOT_CROSSBOW] = ItemDict.NONE;
        assertNotEquals(seeded, Checksum.of(decoded),
                "slotCrossbowEntry is checksummed, so a host that forgets to seed it desyncs from"
                        + " frame 0. That was the cross-play bug; this line is why it cannot come"
                        + " back silently");
    }

    @Test
    void theHandshakeRefusesAFrameZeroTheTwoHostsDoNotShare() {
        Arena arena = Arena.flat(CrossPlayFixture.GROUND_Y);
        GameState edgeSide = MatchSetupFrame0Decoder.decode(blob(EDGE_SLOT)).state();
        GameState modSide = MatchSetupFrame0Decoder.decode(blob(MOD_SLOT)).state();
        modSide.players[MOD_SLOT].slotCrossbowEntry[CrossPlayFixture.SLOT_CROSSBOW] = ItemDict.NONE;

        ArenaAgreementExchange edge =
                new ArenaAgreementExchange(ArenaAgreement.of(arena, edgeSide, "edge"));
        ArenaAgreementExchange mod =
                new ArenaAgreementExchange(ArenaAgreement.of(arena, modSide, "mod"));

        assertTrue(edge.offer(mod.local().encode()));
        assertNull(mod.local().disagreement(mod.local()));
        assertTrue(edge.abortReason() != null && edge.abortReason().contains("frame 0 state"),
                "a frame 0 the two hosts do not share must be refused BEFORE the first tick,"
                        + " not discovered as a checksum desync several rounds later: "
                        + edge.abortReason());
    }

    @Test
    void aFullCrossPlayCrystalMatchConfirmsTheSameStateOnBothHosts() {
        Arena arena = Arena.flat(CrossPlayFixture.GROUND_Y);

        GameState edgeState = MatchSetupFrame0Decoder.decode(blob(EDGE_SLOT)).state();
        GameState modState = MatchSetupFrame0Decoder.decode(blob(MOD_SLOT)).state();

        ArenaAgreement edgeAgreement = ArenaAgreement.of(arena, edgeState, "edge");
        ArenaAgreement modAgreement = ArenaAgreement.of(arena, modState, "mod");
        assertNull(edgeAgreement.disagreement(modAgreement),
                "the two hosts disagreed before a single frame was simulated");

        LoopbackNetwork net = new LoopbackNetwork(0xC0551A4L, ONE_WAY_FRAMES, JITTER_FRAMES, LOSS);
        MatchDriver edge = new MatchDriver(net.endpoint(EDGE_SLOT), EDGE_SLOT, arena, edgeState,
                RING, new CrossPlayScript(EDGE_SLOT, true, CrossPlayFixture.EDGE_X),
                new CrossPlayFixture.NoRender());
        MatchDriver mod = new MatchDriver(net.endpoint(MOD_SLOT), MOD_SLOT, arena, modState,
                RING, new CrossPlayScript(MOD_SLOT, false, CrossPlayFixture.MOD_X),
                new CrossPlayFixture.NoRender());

        Seen seen = run(net, edge, mod);

        assertFalse(edge.aborted(), "the edge host aborted: " + edge.abortReason());
        assertFalse(mod.aborted(), "the mod host aborted: " + mod.abortReason());
        assertEquals(-1, edge.desyncFrame(), "the edge host saw a checksum desync");
        assertEquals(-1, mod.desyncFrame(), "the mod host saw a checksum desync");

        assertTrue(seen.deaths() >= 4, "expected the match to kill somebody in several rounds, saw "
                + seen.deaths() + " deaths");
        assertTrue(seen.roundResets() >= 3, "expected several rounds to reset, saw "
                + seen.roundResets());
        assertTrue(seen.crystals() > 0, "no crystal was ever live in a CRYSTAL kit match");
        assertTrue(seen.anchorsPlaced() > 0, "no respawn anchor was ever placed");
        assertTrue(seen.anchorsCharged() > 0,
                "an anchor that is never charged can never be detonated, so the explosion half of"
                        + " the anchor path went untested");
        assertTrue(seen.projectiles() > 0, "no arrow was ever in flight");
        assertTrue(seen.containerOpens() > 0, "no container was ever opened");
        assertTrue(seen.containersDrained() > 0,
                "a container that is opened and never emptied does not exercise the take path");
        assertTrue(seen.projectilesAcrossARoundBoundary() > 0,
                "no arrow was ever still in flight when a round ended. That is the case where the"
                        + " round reset and the projectile list have to agree on BOTH hosts, and"
                        + " it is the one an unscripted duel hits constantly");

        assertTrue(edge.finishedNormally(),
                "the edge host did not finish the match normally: " + seen.describe());
        assertTrue(mod.finishedNormally(),
                "the mod host did not finish the match normally: " + seen.describe());
        assertEquals(ROUNDS_TARGET, seen.maxRoundWins(),
                "the match must run all the way to its round target, not stop early: "
                        + seen.describe());

        int common = Math.min(edge.confirmedFrame(), mod.confirmedFrame());
        assertTrue(common > 300,
                "expected both hosts to confirm several hundred frames of real play, got "
                        + common);
        int compared = seen.compare();
        assertTrue(compared > 300, "the run compared only " + compared + " confirmed frames, so"
                + " asserting they all matched proves very little");
        int drift = Math.abs(edge.confirmedFrame() - mod.confirmedFrame());
        assertTrue(drift <= 1,
                "the hosts stop ticking independently, so the last frame each of them confirmed is"
                        + " allowed to differ by the one frame the loser's side is still draining."
                        + " Any more than that and 'the state they ended on' is not a frame the"
                        + " pair of them ever shared: edge=" + edge.confirmedFrame() + " mod="
                        + mod.confirmedFrame());
        int lastShared = -1;
        for (int frame : seen.edgeSeal().keySet()) {
            if (seen.modSeal().containsKey(frame) && frame > lastShared) {
                lastShared = frame;
            }
        }
        int reached = Math.min(edge.confirmedFrame(), mod.confirmedFrame());
        assertTrue(lastShared >= reached - END_WINDOW_FRAMES,
                "a host confirms in bursts, so the two seal logs do not carry every frame number."
                        + " The newest frame both of them sealed is " + lastShared + " against "
                        + reached + " confirmed, which is too far back for agreeing there to say"
                        + " anything about how the match ended");
        assertEquals(seen.edgeSeal().get(lastShared), seen.modSeal().get(lastShared),
                "the two hosts confirmed different states on the last frame they both confirmed,"
                        + " which is the whole thing cross-play has to not do");
        assertEquals(edge.roundWins(EDGE_SLOT), mod.roundWins(EDGE_SLOT),
                "the two hosts disagree about the score");
        assertEquals(edge.roundWins(MOD_SLOT), mod.roundWins(MOD_SLOT),
                "the two hosts disagree about the score");
    }

    @Test
    void aFrameZeroTheHostsDoNotShareReallyDoesBreakTheMatch() {
        Arena arena = Arena.flat(CrossPlayFixture.GROUND_Y);

        GameState edgeState = MatchSetupFrame0Decoder.decode(blob(EDGE_SLOT)).state();
        GameState modState = MatchSetupFrame0Decoder.decode(blob(MOD_SLOT)).state();
        modState.players[MOD_SLOT].slotCrossbowEntry[CrossPlayFixture.SLOT_CROSSBOW] = ItemDict.NONE;
        modState.roundInitial[MOD_SLOT].slotCrossbowEntry[CrossPlayFixture.SLOT_CROSSBOW] =
                ItemDict.NONE;

        LoopbackNetwork net = new LoopbackNetwork(0xC0551A4L, ONE_WAY_FRAMES, JITTER_FRAMES, LOSS);
        MatchDriver edge = new MatchDriver(net.endpoint(EDGE_SLOT), EDGE_SLOT, arena, edgeState,
                RING, new CrossPlayScript(EDGE_SLOT, true, CrossPlayFixture.EDGE_X),
                new CrossPlayFixture.NoRender());
        MatchDriver mod = new MatchDriver(net.endpoint(MOD_SLOT), MOD_SLOT, arena, modState,
                RING, new CrossPlayScript(MOD_SLOT, false, CrossPlayFixture.MOD_X),
                new CrossPlayFixture.NoRender());

        run(net, edge, mod);

        assertTrue(edge.desyncFrame() >= 0 || mod.desyncFrame() >= 0
                        || edge.aborted() || mod.aborted(),
                "one un-seeded field in one host's frame 0 has to break the match, or the frame 0"
                        + " agreement check added for it is guarding nothing");
    }

    private static int stock(GameState s, int containerId) {
        Container c = s.containers.get(containerId);
        if (c == null) {
            return -1;
        }
        int total = 0;
        for (int cell = 0; cell < Container.CELLS; cell++) {
            total += c.count[cell];
        }
        return total;
    }

    private static Seen run(LoopbackNetwork net, MatchDriver edge, MatchDriver mod) {
        int deaths = 0;
        int resets = 0;
        int crystals = 0;
        int anchorsPlaced = 0;
        int anchorsCharged = 0;
        int projectiles = 0;
        int opens = 0;
        int drained = 0;
        int airborneAtRoundEnd = 0;
        int wins = 0;
        int prevProjectiles = 0;
        int fullStock = -1;
        boolean resetting = false;
        boolean edgeLive = true;
        boolean modLive = true;
        Map<Integer, Long> edgeSeal = new HashMap<>();
        Map<Integer, Long> modSeal = new HashMap<>();

        for (int i = 0; i < FRAMES && (edgeLive || modLive); i++) {
            if (edgeLive) {
                edgeLive = edge.tick();
            }
            if (modLive) {
                modLive = mod.tick();
            }
            net.step();

            edgeSeal.put(edge.confirmedFrame(), Checksum.of(edge.confirmedState()));
            modSeal.put(mod.confirmedFrame(), Checksum.of(mod.confirmedState()));

            GameState confirmed = edge.confirmedState();
            for (CombatEvent e : confirmed.events) {
                if (e.type() == CombatEvent.DEATH) {
                    deaths++;
                }
            }
            boolean nowResetting = confirmed.roundResetCountdown > 0;
            if (nowResetting && !resetting
                    && confirmed.roundResetCountdown > Simulation.ROUND_COUNTDOWN_TICKS) {
                resets++;
                if (prevProjectiles > 0) {
                    airborneAtRoundEnd++;
                }
            }
            resetting = nowResetting;
            crystals = Math.max(crystals, confirmed.crystals.size());
            anchorsPlaced = Math.max(anchorsPlaced, confirmed.anchors.size());
            for (int charge : confirmed.anchors.values()) {
                anchorsCharged = Math.max(anchorsCharged, charge);
            }
            prevProjectiles = confirmed.projectiles.size();
            projectiles = Math.max(projectiles, prevProjectiles);
            if (confirmed.players[EDGE_SLOT].openContainer >= 0
                    || confirmed.players[MOD_SLOT].openContainer >= 0) {
                opens++;
            }
            int edgeStock = stock(confirmed, CrossPlayFixture.CONTAINER_EDGE);
            if (fullStock < 0) {
                fullStock = edgeStock;
            }
            if (edgeStock >= 0 && fullStock > 0 && edgeStock <= fullStock - 5) {
                drained = Math.max(drained, fullStock - edgeStock);
            }
            wins = Math.max(wins, Math.max(confirmed.roundWinsP0, confirmed.roundWinsP1));
            GameState peer = mod.confirmedState();
            wins = Math.max(wins, Math.max(peer.roundWinsP0, peer.roundWinsP1));
        }

        return new Seen(deaths, resets, crystals, anchorsPlaced, anchorsCharged, projectiles,
                opens, drained, airborneAtRoundEnd, wins, edgeSeal, modSeal);
    }
}
