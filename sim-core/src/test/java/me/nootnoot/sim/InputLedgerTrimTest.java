package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class InputLedgerTrimTest {
    private static final double GROUND_Y = 64.0;

    private static final int RING = 256;

    private static final int FRAMES = 4000;

    private static final String[] LEDGERS = {"localInputs", "remoteActual", "remoteUsed"};

    private static InputLedger ledger(RollbackController c, String name) throws Exception {
        Field f = RollbackController.class.getDeclaredField(name);
        f.setAccessible(true);
        return (InputLedger) f.get(c);
    }

    private static int live(InputLedger ledger) {
        int n = 0;
        for (int f = ledger.base(); f < ledger.end(); f++) {
            if (ledger.get(f) != null) {
                n++;
            }
        }
        return n;
    }

    private static RollbackController runSession() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0xA11CEL, FRAMES);
        RollbackController c = new RollbackController(arena, 0, HarnessScenarios.duel(arena), RING);
        for (int frame = 0; frame < FRAMES; frame++) {
            c.onRemoteInput(frame, log.frames.get(frame)[1]);
            c.advance(log.frames.get(frame)[0]);
        }
        return c;
    }

    @Test
    void theInputLedgersStopGrowingBehindTheConfirmedFrame() throws Exception {
        RollbackController c = runSession();

        assertEquals(FRAMES, c.head());
        assertTrue(c.inputFloor() > 0, "a long session has to have trimmed something");

        int ceiling = RING + RollbackController.INPUT_RETENTION_MARGIN + 2;
        for (String name : LEDGERS) {
            InputLedger ledger = ledger(c, name);
            assertEquals(FRAMES, ledger.end(), name + " stays frame indexed");
            assertTrue(live(ledger) <= ceiling,
                    name + " held " + live(ledger) + " live frames after " + FRAMES
                            + "; five parallel ledgers at one entry per frame is what made a"
                            + " thirty minute match carry 36000 inputs per ledger");
        }
    }

    @Test
    void theLedgerSpinesAreBoundedNotJustTheInputsTheyHold() throws Exception {
        RollbackController c = runSession();

        int ceiling = RING + RollbackController.INPUT_RETENTION_MARGIN + 2;
        for (String name : LEDGERS) {
            InputLedger ledger = ledger(c, name);
            assertEquals(c.inputFloor(), ledger.base(),
                    name + " drops the released prefix rather than nulling it in place");
            assertTrue(ledger.retained() <= ceiling,
                    name + " retains " + ledger.retained() + " list slots after " + FRAMES
                            + " frames; nulling the entries frees the Input objects but leaves the"
                            + " spine growing one reference per frame for the whole match");
        }
    }

    @Test
    void everythingTheRingCanStillRollBackToIsUntouched() throws Exception {
        RollbackController c = runSession();

        int reachable = c.head() - RING;
        assertTrue(c.inputFloor() < reachable,
                "the floor has to sit strictly behind the oldest frame the state ring can restore,"
                        + " or a rollback would replay a null input");

        for (String name : LEDGERS) {
            InputLedger ledger = ledger(c, name);
            for (int f = reachable; f < FRAMES; f++) {
                assertTrue(ledger.get(f) != null,
                        name + " frame " + f + " is inside the rollback window and was released");
            }
        }
    }

    @Test
    void aRemoteFrameArrivingBehindTheFloorIsIgnoredInsteadOfOverrunning() {
        RollbackController c = runSession();
        int rollbacks = c.rollbackCount();

        c.onRemoteInput(0, Input.NONE);

        assertEquals(rollbacks, c.rollbackCount(),
                "a released frame reads back as null, so without the floor guard the mismatch"
                        + " test would see a null 'used' input and roll back to a frame the state"
                        + " ring cannot restore");
        assertEquals(FRAMES, c.head());
    }

    @Test
    void trimmingChangesNoSimulationResult() {
        Arena arena = Arena.flat(GROUND_Y);
        InputLog log = InputLog.generated(0x5EEDL, FRAMES);

        GameState truth = HarnessScenarios.duel(arena);
        for (int frame = 0; frame < FRAMES; frame++) {
            Simulation.tick(truth, arena, log.frames.get(frame)[0], log.frames.get(frame)[1]);
        }

        RollbackController c = new RollbackController(arena, 0, HarnessScenarios.duel(arena), RING);
        for (int frame = 0; frame < FRAMES; frame++) {
            c.onRemoteInput(frame, log.frames.get(frame)[1]);
            c.advance(log.frames.get(frame)[0]);
        }

        assertEquals(Checksum.of(truth), c.checksum(),
                "releasing inputs the ring can no longer reach must not move the sim");
    }
}
