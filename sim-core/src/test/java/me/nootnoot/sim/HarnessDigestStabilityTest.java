package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import me.nootnoot.sim.harness.HarnessDigest;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.harness.InputLog;
import me.nootnoot.sim.net.Protocol;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HarnessDigestStabilityTest {
    @Test
    void thisMachineReproducesTheCommittedReferenceDigest() {
        HarnessDigest.Run run = HarnessDigest.run();
        HarnessDigest.Reference ref = HarnessDigest.reference();

        assertTrue(run.converged(), "the harness diverged inside one JVM at tick " + run.divergedAtTick());
        assertEquals(Long.toHexString(ref.finalChecksum()), Long.toHexString(run.finalChecksum()),
                HarnessDigest.verdict(ref, HarnessDigest.mismatches(ref, run)));
        assertEquals(Long.toHexString(ref.streamDigest()), Long.toHexString(run.streamDigest()),
                HarnessDigest.verdict(ref, HarnessDigest.mismatches(ref, run)));
    }

    @Test
    void thisMachineReproducesTheCommittedArenaHash() {
        HarnessDigest.Run run = HarnessDigest.run();
        HarnessDigest.Reference ref = HarnessDigest.reference();

        assertTrue(run.independentArenas(),
                "the two harness sides shared one Arena instance, so the arena hash below would"
                        + " agree with itself no matter what extraction did");
        assertNotEquals(0L, ref.arenaHash(),
                "the committed reference records arena-hash=0, which is the value mismatches()"
                        + " used to treat as `not recorded`. A zero here is how this check gets"
                        + " turned off without anyone deleting it");
        assertEquals(Long.toHexString(ref.arenaHash()), Long.toHexString(run.arenaHash()),
                HarnessDigest.verdict(ref, HarnessDigest.mismatches(ref, run)));
    }

    @Test
    void thisMachineReproducesTheCommittedRollbackDigest() {
        HarnessDigest.Run run = HarnessDigest.run();
        HarnessDigest.Reference ref = HarnessDigest.reference();

        assertTrue(run.rollback().uncovered().isEmpty(),
                "the rollback pass no longer reaches " + run.rollback().uncovered()
                        + ", so its digest is a forward-only digest wearing a rollback name");
        assertNotEquals(0L, ref.rollbackDigest(),
                "the committed reference records rollback-digest=0, the old `not recorded`"
                        + " sentinel, so save/load/resimulate would not be compared at all");
        assertEquals(Long.toHexString(ref.rollbackDigest()), Long.toHexString(run.rollbackDigest()),
                HarnessDigest.verdict(ref, HarnessDigest.mismatches(ref, run)));
    }

    @Test
    void everyReferenceNumberIsActuallyComparedAgainstThisRun() {
        HarnessDigest.Run run = HarnessDigest.run();
        HarnessDigest.Reference ref = HarnessDigest.reference();

        assertTrue(HarnessDigest.mismatches(ref, run).isEmpty(),
                "the reference and this run disagree: " + HarnessDigest.mismatches(ref, run));

        for (String key : List.of("final-checksum", "stream-digest", "arena-hash", "rollback-digest")) {
            HarnessDigest.Reference tampered = bend(ref, key);
            assertFalse(HarnessDigest.mismatches(tampered, run).isEmpty(),
                    key + " can be wrong in the reference and mismatches() still reports nothing,"
                            + " so that number is decoration. A reference key nobody compares is"
                            + " the same gate hole as a reference key nobody requires");
        }
    }

    @Test
    void aReferenceThatOmitsAKeyIsRejectedRatherThanDefaulted() throws IOException {
        List<String> complete = List.of(
                "ticks=" + HarnessDigest.TICKS,
                "seed=" + Long.toHexString(HarnessDigest.SEED),
                "checksum-rev=" + Protocol.CHECKSUM_REV,
                "final-checksum=1",
                "stream-digest=2",
                "arena-hash=3",
                "rollback-digest=4");
        assertEquals(4L, HarnessDigest.parse(complete).rollbackDigest(),
                "the control case has to parse, or the negative cases below prove nothing");

        for (String dropped : complete) {
            List<String> partial = new ArrayList<>(complete);
            partial.remove(dropped);
            IOException e = assertThrows(IOException.class, () -> HarnessDigest.parse(partial),
                    "a reference that simply omits `" + dropped + "` was accepted. Defaulting a"
                            + " missing key is how this gate quietly downgrades to a"
                            + " stream-digest-only check");
            assertTrue(e.getMessage().contains(dropped.substring(0, dropped.indexOf('='))),
                    "the rejection does not name the key that is missing: " + e.getMessage());
        }
    }

    private static HarnessDigest.Reference bend(HarnessDigest.Reference r, String key) {
        return new HarnessDigest.Reference(r.ticks(), r.seed(), r.checksumRev(),
                "final-checksum".equals(key) ? r.finalChecksum() ^ 1L : r.finalChecksum(),
                "stream-digest".equals(key) ? r.streamDigest() ^ 1L : r.streamDigest(),
                "arena-hash".equals(key) ? r.arenaHash() ^ 1L : r.arenaHash(),
                "rollback-digest".equals(key) ? r.rollbackDigest() ^ 1L : r.rollbackDigest());
    }

    @Test
    void theReferenceRecordsTheCheckSumRevItWasTakenAt() {
        HarnessDigest.Reference ref = HarnessDigest.reference();
        assertEquals(Protocol.CHECKSUM_REV, ref.checksumRev(),
                "the reference digest was recorded at CHECKSUM_REV " + ref.checksumRev()
                        + " but the sim is now at " + Protocol.CHECKSUM_REV
                        + "; re-record with ./gradlew :sim-core:updateHarnessDigest");
        assertEquals(HarnessDigest.TICKS, ref.ticks());
        assertEquals(HarnessDigest.SEED, ref.seed());
    }

    @Test
    void theDigestSurvivesTheJitCompilingTheSimUnderneathIt() {
        long cold = HarnessDigest.run().streamDigest();

        churnTheJit();

        long hot = HarnessDigest.run().streamDigest();

        assertEquals(Long.toHexString(cold), Long.toHexString(hot),
                "the harness produced a different digest once the JIT had compiled the sim;"
                        + " a tick that is not bit-stable across compilation tiers cannot be replicated");
    }

    @Test
    void theDigestIsTheSameOnAThreadWithItsOwnCompilationHistory() throws InterruptedException {
        long onTestThread = HarnessDigest.run().streamDigest();

        AtomicLong other = new AtomicLong();
        Thread t = new Thread(() -> {
            churnTheJit();
            other.set(HarnessDigest.run().streamDigest());
        }, "harness-digest-stability");
        t.start();
        t.join();

        assertEquals(Long.toHexString(onTestThread), Long.toHexString(other.get()),
                "the digest depends on which thread ran the sim");
    }

    @Test
    void aWrittenReferenceParsesBackToTheSameNumbers(@TempDir Path dir) throws IOException {
        HarnessDigest.Run run = HarnessDigest.run();
        Path file = dir.resolve("reference-digest.txt");
        HarnessDigest.writeReference(file, run);

        HarnessDigest.Reference ref = HarnessDigest.parse(Files.readAllLines(file));
        assertEquals(HarnessDigest.TICKS, ref.ticks());
        assertEquals(HarnessDigest.SEED, ref.seed());
        assertEquals(Protocol.CHECKSUM_REV, ref.checksumRev());
        assertEquals(run.finalChecksum(), ref.finalChecksum());
        assertEquals(run.streamDigest(), ref.streamDigest());
        assertEquals(run.arenaHash(), ref.arenaHash());
        assertEquals(run.rollbackDigest(), ref.rollbackDigest());
        assertTrue(HarnessDigest.mismatches(ref, run).isEmpty());
    }

    private static void churnTheJit() {
        Arena arena = HarnessScenarios.arena();
        long sink = 0;
        for (int seed = 1; seed <= 60; seed++) {
            InputLog log = InputLog.scripted(seed * 0x9E3779B1L, 400);
            GameState g = HarnessScenarios.combat(arena);
            for (Input[] f : log.frames) {
                Simulation.tick(g, arena, f[0], f[1]);
            }
            sink ^= Checksum.of(g);
        }
        if (sink == 0xDEADBEEFCAFEBABEL) {
            throw new IllegalStateException("unreachable, keeps the warmup from being optimised away");
        }
    }
}
