package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Effects;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class GameStateFrame0CodecTest {
    private static final double GROUND_Y = 64.0;

    private static GameState richFrame0() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = HarnessScenarios.duel(arena);
        s.roundsTarget = 3;
        s.roundResetCountdown = 100;
        s.vanillaBuild = true;
        s.potSwordBoost = true;
        s.cobwebItemId = 100;
        s.stringItemId = 101;
        s.obsidianItemId = 102;
        s.cobblestoneItemId = 103;
        s.mudItemId = 104;
        s.glowstoneItemId = 105;
        s.glowstoneDustItemId = 106;
        s.playCenterX = 5.0;
        s.playCenterZ = -5.0;
        s.playRadius = 50.0;
        s.playCircular = true;
        s.edgeHosted[1] = true;
        me.nootnoot.sim.state.Container chest = new me.nootnoot.sim.state.Container();
        chest.entry[0] = HarnessScenarios.PEARL_ENTRY;
        chest.count[0] = 9;
        s.containers.put(7, chest);
        s.blockContainers.put(1234L, 7);
        s.nextContainerId = 8;
        s.blockProps = new me.nootnoot.sim.state.BlockProps.Builder()
                .add(102, 50f, 1200f, 102, 2, me.nootnoot.sim.state.ItemDict.TOOL_PICKAXE, true)
                .add(103, 2f, 6f, 103, 0, me.nootnoot.sim.state.ItemDict.TOOL_PICKAXE, true)
                .build();
        for (int i = 0; i < 2; i++) {
            PlayerState p = s.players[i];
            p.armor = 8.0f + i;
            p.protection = 3.0f;
            p.attackDamage = 7.0f + i;
            p.attackSpeed = 1.6f;
            p.pearls = 16;
            p.hasElytra = i == 0;
            p.effectTicks[Effects.SPEED] = 200;
            p.effectAmp[Effects.SPEED] = 1;
            p.slotEntry[3] = HarnessScenarios.APPLE_ENTRY;
            p.slotCount[3] = 16 + i;
            p.slotDamage[0] = 7 + i;
            p.slotCrossbowLoaded[4] = true;
            p.slotCrossbowConsumed[4] = true;
            p.openContainer = i;
            p.openContainerKey = 0x5EEDBEEF00L + i;
            p.invActionSeq = 11 + i;
            p.dropSeq = 3 + i;
            p.lastDropSlot = i;
            p.lastDropItemId = 900 + i;
            p.lastDropCount = 2 + i;
            p.instaReady = i == 1;
        }
        s.roundInitial = new PlayerState[]{s.players[0].copy(), s.players[1].copy()};
        return s;
    }

    @Test
    void roundTripPreservesChecksummedStateAndSeeds() {
        GameState orig = richFrame0();
        GameState dec = GameStateFrame0Codec.decode(GameStateFrame0Codec.encode(orig));

        assertEquals(Checksum.of(orig), Checksum.of(dec), "checksummed state must survive the round trip");

        assertEquals(orig.roundsTarget, dec.roundsTarget);
        assertEquals(orig.vanillaBuild, dec.vanillaBuild);
        assertEquals(orig.potSwordBoost, dec.potSwordBoost);
        assertEquals(orig.cobwebItemId, dec.cobwebItemId);
        assertEquals(orig.stringItemId, dec.stringItemId);
        assertEquals(orig.obsidianItemId, dec.obsidianItemId);
        assertEquals(orig.cobblestoneItemId, dec.cobblestoneItemId);
        assertEquals(orig.mudItemId, dec.mudItemId);
        assertEquals(orig.glowstoneItemId, dec.glowstoneItemId);
        assertEquals(orig.glowstoneDustItemId, dec.glowstoneDustItemId);
        assertEquals(orig.playCenterX, dec.playCenterX);
        assertEquals(orig.playCenterZ, dec.playCenterZ);
        assertEquals(orig.playRadius, dec.playRadius);
        assertEquals(orig.playCircular, dec.playCircular);
        assertEquals(Checksum.of(makeState(orig.roundInitial)), Checksum.of(makeState(dec.roundInitial)),
                "roundInitial snapshot must survive the round trip");
        for (int i = 0; i < 2; i++) {
            assertEquals(orig.players[i].openContainerKey, dec.players[i].openContainerKey,
                    "openContainerKey must survive the round trip for player " + i);
            assertEquals(orig.roundInitial[i].openContainerKey, dec.roundInitial[i].openContainerKey,
                    "openContainerKey must survive the round trip for the round seed of player " + i);
        }
    }

    @Test
    void everyCheckedPlayerFieldTheCodecCarriesIsVisibleToTheChecksum() {
        GameState withKey = richFrame0();
        GameState withoutKey = richFrame0();
        withoutKey.players[0].openContainerKey = Long.MIN_VALUE;
        withoutKey.roundInitial[0].openContainerKey = Long.MIN_VALUE;
        assertNotEquals(Checksum.of(withKey), Checksum.of(withoutKey),
                "openContainerKey must move the checksum, otherwise the codec gap is invisible");
    }

    @Test
    void decodedFrame0ReplaysIdenticallyToTheOriginal() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState orig = richFrame0();
        GameState dec = GameStateFrame0Codec.decode(GameStateFrame0Codec.encode(orig));

        InputLog log = InputLog.generated(0xFACEFEEDL, 300);
        GameState a = orig.copy();
        GameState b = dec.copy();
        for (Input[] f : log.frames) {
            Simulation.tick(a, arena, f[0], f[1]);
            Simulation.tick(b, arena, f[0], f[1]);
            assertEquals(Checksum.of(a), Checksum.of(b), "replay diverged at tick " + a.tick);
        }
    }

    @Test
    void rejectsNonEmptyCollections() {
        GameState s = richFrame0();
        s.blocks.place(0, 64, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> GameStateFrame0Codec.encode(s));
    }

    private static byte[] encodedWithUseCooldownOfLength(int length) {
        GameState s = richFrame0();
        s.players[0].useCooldown = new int[length];
        s.roundInitial[0].useCooldown = new int[length];
        return GameStateFrame0Codec.encode(s);
    }

    @Test
    void aBlobWhoseUseCooldownArrayIsTheWrongLengthIsRefused() {
        assertEquals(Combat.USE_WIND_CHARGE + 1, PlayerState.USE_KINDS,
                "the array is indexed by use kind, so its length is not free-form");

        byte[] tooLong = encodedWithUseCooldownOfLength(PlayerState.USE_KINDS + 1);
        assertThrows(IllegalArgumentException.class, () -> GameStateFrame0Codec.decode(tooLong),
                "the unchecked readIntArray let a blob pick this array's size; useFires indexes it"
                        + " by use kind, so a short one is an out of bounds read on the sim thread");

        byte[] tooShort = encodedWithUseCooldownOfLength(0);
        assertThrows(IllegalArgumentException.class, () -> GameStateFrame0Codec.decode(tooShort));
    }

    @Test
    void aBlobWhoseCrossbowArraysAreTheWrongLengthIsRefused() {
        GameState s = richFrame0();
        s.players[0].slotCrossbowLoaded = new boolean[3];
        s.roundInitial[0].slotCrossbowLoaded = new boolean[3];
        byte[] wire = GameStateFrame0Codec.encode(s);

        assertThrows(IllegalArgumentException.class, () -> GameStateFrame0Codec.decode(wire),
                "the crossbow arrays are indexed by held slot and take the same fixed length check");
    }

    private static GameState tickedTwice() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = richFrame0();
        s.roundResetCountdown = 0;
        for (int i = 0; i < 4; i++) {
            s.players[1].x = 3.0 + i;
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
        return s;
    }

    @Test
    void theRewindRingSurvivesTheRoundTripAndDecidesTheChecksum() {
        GameState orig = tickedTwice();
        GameState dec = GameStateFrame0Codec.decode(GameStateFrame0Codec.encode(orig));

        assertTrue(orig.players[1].rewindFilled > 1, "the ring has to hold something to prove");
        for (int i = 0; i < PlayerState.REWIND_FRAMES; i++) {
            assertEquals(orig.players[1].rewindX[i], dec.players[1].rewindX[i], 0.0,
                    "rewindX[" + i + "] drives hit resolution, so a decoded state that lacks it"
                            + " cannot reproduce its own hit outcomes");
            assertEquals(orig.players[1].rewindY[i], dec.players[1].rewindY[i], 0.0);
            assertEquals(orig.players[1].rewindZ[i], dec.players[1].rewindZ[i], 0.0);
            assertEquals(orig.players[1].rewindHeight[i], dec.players[1].rewindHeight[i], 0.0);
        }

        GameState moved = GameStateFrame0Codec.decode(GameStateFrame0Codec.encode(orig));
        moved.players[1].rewindX[0] += 1.0;
        assertNotEquals(Checksum.of(dec), Checksum.of(moved),
                "the ring is inside the checksum on purpose: it decides hits, so two peers whose"
                        + " rings disagree have already desynced and must be told so");
    }

    @Test
    void aDecodedStateResolvesARewoundHitTheSameWayItsSourceDoes() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState orig = tickedTwice();
        orig.players[0].x = 0.0;
        orig.players[0].z = 0.5;
        orig.players[0].yaw = -90f;
        orig.players[0].attackTicker = 100;
        orig.players[1].z = 0.5;
        for (int i = 0; i < 3; i++) {
            orig.players[1].x = 2.5;
            Simulation.tick(orig, arena, Input.NONE, Input.NONE);
        }
        orig.players[1].x = 30.0;

        GameState dec = GameStateFrame0Codec.decode(GameStateFrame0Codec.encode(orig));
        Input swing = new Input(false, false, false, false, false, false, false, true, false,
                -90f, 0f, 0).withMeleeHit(true);

        Simulation.tick(orig, arena, swing, Input.NONE);
        Simulation.tick(dec, arena, swing, Input.NONE);

        assertTrue(orig.players[1].health < 20.0f,
                "the claim has to land on the original or this proves nothing");
        assertEquals(orig.players[1].health, dec.players[1].health,
                "a state rebuilt from a frame 0 blob must resolve the same hit; the ring was"
                        + " outside the codec, so the decoded copy used to have no rewound hull"
                        + " to grant the claim against");
    }

    private static GameState makeState(PlayerState[] players) {
        GameState g = new GameState();
        g.players[0] = players[0].copy();
        g.players[1] = players[1].copy();
        return g;
    }
}
