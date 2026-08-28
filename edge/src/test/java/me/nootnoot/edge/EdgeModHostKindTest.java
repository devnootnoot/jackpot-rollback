package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import me.nootnoot.edge.tools.DevAssignMain;
import org.junit.jupiter.api.Test;

class EdgeModHostKindTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private static EdgeAssignment forSlot(int slot, String selfKind, String oppKind) {
        UUID self = slot == 0 ? A : B;
        UUID opp = slot == 0 ? B : A;
        String json = DevAssignMain.assignment(99L, slot, self, "p" + slot, opp, "q" + slot,
                "127.0.0.1", 7777, 1, System.currentTimeMillis() + 60_000L, true,
                EdgeGameTypes.CRYSTAL, DevAssignMain.ArenaRef.dev(-60.0), null, 0, null,
                selfKind, oppKind);
        EdgeAssignment a = EdgeAssignment.parse(json);
        assertNotNull(a, "the assignment must parse");
        return a;
    }

    @Test
    void theDefaultAssignmentIsStillEdgeHostedOnBothSides() {
        EdgeAssignment a = EdgeAssignment.parse(DevAssignMain.assignment(1L, 0, A, "a", B, "b",
                "127.0.0.1", 7777, -60.0, 1, System.currentTimeMillis() + 60_000L, true));
        assertNotNull(a);
        assertFalse(a.selfIsModded(),
                "an assignment pushed without -Pmod must stay EDGE-hosted; that is the only"
                        + " configuration the dev stack produced before the mod handoff existed");
        assertFalse(a.opponentIsModded());
    }

    @Test
    void modEqualsAMakesSlotZeroModHostedAndSlotOneEdgeHosted() {
        String k0 = DevAssignMain.hostKindOf("A", 0);
        String k1 = DevAssignMain.hostKindOf("A", 1);
        assertEquals(EdgeAssignment.HOST_MOD, k0);
        assertEquals(EdgeAssignment.HOST_EDGE, k1);

        EdgeAssignment modSide = forSlot(0, k0, k1);
        assertTrue(modSide.selfIsModded(),
                "slot 0 hosts the sim on its own client, so its edge must hand it off to the limbo"
                        + " instead of running EdgeMatch for it");
        assertFalse(modSide.opponentIsModded());

        EdgeAssignment edgeSide = forSlot(1, k1, k0);
        assertFalse(edgeSide.selfIsModded(),
                "slot 1 is still hosted by its edge - cross-play is exactly one host of each kind");
        assertTrue(edgeSide.opponentIsModded(),
                "the edge slot must see a MOD opponent, which is what keeps the relay in the path"
                        + " (a modded client behind NAT cannot be dialled directly)");
    }

    @Test
    void modEqualsBothMakesEveryViewModHosted() {
        assertEquals(EdgeAssignment.HOST_MOD, DevAssignMain.hostKindOf("both", 0));
        assertEquals(EdgeAssignment.HOST_MOD, DevAssignMain.hostKindOf("both", 1));
        EdgeAssignment a = forSlot(0, EdgeAssignment.HOST_MOD, EdgeAssignment.HOST_MOD);
        assertTrue(a.selfIsModded());
        assertTrue(a.opponentIsModded());
    }

    @Test
    void anUnknownModFlagIsRejectedRatherThanSilentlyMeaningNone() {
        assertNull(DevAssignMain.hostKindOf("playerA", 0),
                "a typo must fail loudly: silently falling back to EDGE would hand the tester the"
                        + " one configuration they were trying to get away from");
        assertEquals(EdgeAssignment.HOST_EDGE, DevAssignMain.hostKindOf("none", 0));
        assertEquals(EdgeAssignment.HOST_EDGE, DevAssignMain.hostKindOf(null, 1));
    }
}
