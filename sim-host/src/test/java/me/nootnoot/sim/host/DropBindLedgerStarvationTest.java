package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class DropBindLedgerStarvationTest {

    private static final int UID_BASE = 1 << 28;

    private static final int SLOT = 3;

    @Test
    void aSeqWhoseRecordHasNotArrivedYetIsStillBindableAfterwards() {
        DropBindLedger<String> ledger = new DropBindLedger<>();
        ledger.pass(0, UID_BASE, DropBindLedger.NO_SLOT);

        assertTrue(ledger.pass(1, UID_BASE, SLOT).isEmpty(),
                "there is no record to bind yet, so this pass has nothing to hand out");
        assertFalse(ledger.caughtUp(1),
                "seq 1 was minted and never bound, so the ledger has NOT caught up. Advancing the"
                        + " cursor over a seq the queue could not feed retires it forever: the"
                        + " record that belongs to it can still arrive, and when it does every"
                        + " later drop binds one entry out of step for the rest of the match.");

        ledger.record(SLOT, "netherite sword");
        List<DropBindLedger.Bind<String>> binds = ledger.pass(1, UID_BASE, SLOT);

        assertEquals(1, binds.size(), "the record arrived, so seq 1 must bind now");
        assertEquals(1, binds.get(0).seq());
        assertEquals(UID_BASE | 1, binds.get(0).uid());
        assertEquals("netherite sword", binds.get(0).stack());
        assertTrue(ledger.caughtUp(1));
        assertEquals(0, ledger.pending());
    }

    @Test
    void aRunOfUnfedSeqsIsRetriedInOrderRatherThanSkipped() {
        DropBindLedger<String> ledger = new DropBindLedger<>();
        ledger.pass(0, UID_BASE, DropBindLedger.NO_SLOT);

        ledger.record(SLOT, "first");
        List<DropBindLedger.Bind<String>> partial = ledger.pass(3, UID_BASE, SLOT);
        assertEquals(1, partial.size(), "only one record was there, so only seq 1 can bind");
        assertEquals(1, partial.get(0).seq());
        assertEquals(1, ledger.boundSeq(),
                "the cursor may only stand on the last seq that actually took a record");

        ledger.record(SLOT, "second");
        ledger.record(SLOT, "third");
        List<DropBindLedger.Bind<String>> rest = ledger.pass(3, UID_BASE, SLOT);

        assertEquals(2, rest.size(), "seqs 2 and 3 are still owed a bind");
        assertEquals(2, rest.get(0).seq());
        assertEquals("second", rest.get(0).stack());
        assertEquals(3, rest.get(1).seq());
        assertEquals("third", rest.get(1).stack());
        assertEquals(0, ledger.pending());
    }

    @Test
    void aRecordThatIsNeverComingDoesNotWedgeTheLedgerForever() {
        DropBindLedger<String> ledger = new DropBindLedger<>();
        ledger.pass(0, UID_BASE, DropBindLedger.NO_SLOT);

        int gap = DropBindLedger.MAX_STARVED_GAP + 4;
        for (int seq = 1; seq <= gap; seq++) {
            assertTrue(ledger.pass(seq, UID_BASE, SLOT).isEmpty(),
                    "nothing was ever recorded, so nothing can bind");
        }

        assertTrue(ledger.boundSeq() > 0,
                "waiting forever for a record that is never coming is its own bug: once the run of"
                        + " unfed seqs is wider than the whole record queue could ever hold, no"
                        + " future record can belong to them, so the ledger has to give them up"
                        + " and keep binding the drops that follow.");

        ledger.record(SLOT, "late");
        List<DropBindLedger.Bind<String>> binds = ledger.pass(gap + 1, UID_BASE, SLOT);

        assertEquals(1, binds.size(),
                "after the give-up the very next drop must bind normally again");
        assertEquals("late", binds.get(0).stack());
        assertEquals(0, ledger.pending());
    }
}
