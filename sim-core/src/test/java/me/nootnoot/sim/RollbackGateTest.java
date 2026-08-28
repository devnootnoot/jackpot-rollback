package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.nootnoot.sim.harness.HarnessDigest;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.RollbackAudit;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.state.Arena;
import org.junit.jupiter.api.Test;

class RollbackGateTest {
    @Test
    void theGateResimulatesEveryFrameItRollsBackAndLandsOnTheForwardRun() {
        HarnessDigest.Run run = HarnessDigest.run();
        assertTrue(run.converged(),
                "the gate did not reproduce the forward run through the rollback path: "
                        + run.disagreement());
        RollbackAudit.Stats s = run.rollback();
        assertTrue(s.rollbacks() > 0, "the rollback pass never rolled back, so it proved nothing");
        assertTrue(s.resimulatedFrames() > 0, "the rollback pass never resimulated a frame");
        assertTrue(s.deepestRollback() >= RollbackAudit.PREDICTION_BOUND,
                "the deepest scripted misprediction was " + s.deepestRollback()
                        + " frames, which never reaches the prediction bound of "
                        + RollbackAudit.PREDICTION_BOUND);
        assertTrue(s.batchedRollbacks() > 0,
                "no burst of late inputs was ever delivered as one batch, so the batched"
                        + " rollback path is untested");
    }

    @Test
    void everyRollbackShapeTheGateClaimsToCoverIsActuallyReached() {
        List<String> uncovered = HarnessDigest.run().rollback().uncovered();
        assertTrue(uncovered.isEmpty(),
                "the scripted mispredictions no longer produce these rollback shapes, so the"
                        + " committed rollback digest stops responding to them: " + uncovered);
    }

    @Test
    void theRollbackDigestIsReproducibleWithinOneJvm() {
        long first = HarnessDigest.run().rollbackDigest();
        long second = HarnessDigest.run().rollbackDigest();
        assertEquals(Long.toHexString(first), Long.toHexString(second),
                "the rollback pass is not reproducible even on one machine");
    }

    @Test
    void theRollbackDigestIsItsOwnEvidenceNotARestatementOfTheForwardStream() {
        HarnessDigest.Run run = HarnessDigest.run();
        assertNotEquals(Long.toHexString(run.streamDigest()), Long.toHexString(run.rollbackDigest()),
                "the rollback digest folds the same numbers as the forward digest, so committing"
                        + " it adds no cross-machine evidence");
    }

    @Test
    void aReferenceThatDisagreesAboutTheRollbackPathIsReportedAsAMismatch() {
        HarnessDigest.Run run = HarnessDigest.run();
        HarnessDigest.Reference tampered = new HarnessDigest.Reference(
                HarnessDigest.TICKS, HarnessDigest.SEED, Protocol.CHECKSUM_REV,
                run.finalChecksum(), run.streamDigest(), run.arenaHash(),
                run.rollbackDigest() ^ 1L);
        List<String> problems = HarnessDigest.mismatches(tampered, run);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("rollback digest:"), problems.get(0));
    }

    @Test
    void theHarnessExtractsTheArenaTwiceInsteadOfHandingOneCopyToBothInstances() {
        byte[] bytesA = HarnessScenarios.arenaBytes();
        byte[] bytesB = HarnessScenarios.arenaBytes();
        assertNotSame(bytesA, bytesB, "arenaBytes() handed out the same array twice");
        assertArrayEquals(bytesA, bytesB,
                "the two extractions did not start from the same arena bytes");

        Arena a = HarnessScenarios.arena();
        Arena b = HarnessScenarios.arena();
        assertNotSame(a, b, "arena() returned the same Arena instance twice");
        assertNotSame(a.solids, b.solids, "the two arenas share their collision array");
        assertNotSame(a.partialBoxes(), b.partialBoxes());
        assertEquals(Long.toHexString(ArenaHash.of(a)), Long.toHexString(ArenaHash.of(b)),
                "two independent extractions of the same bytes produced different arenas");

        assertTrue(HarnessDigest.run().independentArenas(),
                "the digest run compared an arena extraction against itself");
    }
}
