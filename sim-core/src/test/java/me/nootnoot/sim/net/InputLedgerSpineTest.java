package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import me.nootnoot.sim.InputLedger;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class InputLedgerSpineTest {
    private static final double GROUND_Y = 64.0;

    private static final int FRAMES = 3000;

    private static InputLedger ledger(NetSession s, String name) throws Exception {
        Field f = NetSession.class.getDeclaredField(name);
        f.setAccessible(true);
        return (InputLedger) f.get(s);
    }

    private static int retention() throws Exception {
        Field f = NetSession.class.getDeclaredField("INPUT_RETENTION_FRAMES");
        f.setAccessible(true);
        return f.getInt(null);
    }

    @Test
    void theSessionInputLedgersDropTheirPrefixInsteadOfNullingItInPlace() throws Exception {
        Arena arena = Arena.flat(GROUND_Y);
        LoopbackNetwork net = new LoopbackNetwork(0x7C7CL, 1, 0, 0.0);
        NetSession s0 = new NetSession(net.endpoint(0), 0, arena, HarnessScenarios.duel(arena), 4096);
        NetSession s1 = new NetSession(net.endpoint(1), 1, arena, HarnessScenarios.duel(arena), 4096);

        for (int i = 0; i < FRAMES; i++) {
            net.step();
            s0.update(Input.NONE);
            s1.update(Input.NONE);
        }
        for (int i = 0; i < 4000 && s0.confirmedFrame() < FRAMES - 8; i++) {
            net.step();
            s0.flush();
            s1.flush();
        }

        assertFalse(s0.aborted(), "s0 aborted: " + s0.abortReason());
        int floor = s0.inputPruneFloor();
        assertTrue(floor > 0,
                "the match has to outrun the " + retention() + " frame retention window for this"
                        + " test to mean anything");

        int ceiling = retention() + 512;
        for (String name : new String[]{"rawInputs", "localInputs"}) {
            InputLedger held = ledger(s0, name);
            assertEquals(floor, held.base(), name + " kept a prefix the prune floor has passed");
            assertTrue(held.retained() <= ceiling,
                    name + " retains " + held.retained() + " list slots at prune floor " + floor
                            + "; a ledger indexed straight by frame number grows one reference per"
                            + " frame for the whole match even when every entry behind the floor"
                            + " has been released");
        }
    }
}
