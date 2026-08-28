package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import me.nootnoot.sim.ClaimAuthority;
import org.junit.jupiter.api.Test;

class InventoryPredictionLedgerTest {

    @Test
    void theLandingFrameIsTheOnlyThingTheLedgerStillDecides() {
        assertEquals(100 + ClaimAuthority.INPUT_DELAY_FRAMES,
                InventoryPredictionLedger.landingFrame(100));
        assertEquals(InventoryPredictionLedger.landingFrame(7), InventoryPaintPlan.landingFrame(7),
                "the paint plan and the cursor-fill stamp must agree on when an input lands, or a slot "
                        + "is released before the value it is waiting for exists");
    }

    @Test
    void theLandingFrameIsStrictlyAheadOfTheFrameThatQueuedIt() {
        assertTrue(ClaimAuthority.INPUT_DELAY_FRAMES > 0);
        for (int head = -5; head < 5; head++) {
            assertTrue(InventoryPredictionLedger.landingFrame(head) > head);
        }
    }

    @Test
    void noProductionFileCountsTicksToDecideWhetherToRepaintTheInventory() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : sourceRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String body = Files.readString(file);
                    if (body.contains("HOLD_TIMEOUT") || body.contains("heldTicks")
                            || body.contains("inventoryHoldActive") || body.contains("settleInventoryHold")) {
                        offenders.add(file.toString());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "a tick-counted inventory hold is back in " + offenders + " - the repaint it would be "
                        + "defending is per slot now, so nothing needs holding; a hold with a bound only "
                        + "moves the clobber to slower connections");
    }

    private static List<Path> sourceRoots() {
        Path module = Path.of("").toAbsolutePath();
        Path repo = module.getParent();
        Path jackpot = repo == null ? null : repo.getParent();
        List<Path> roots = new ArrayList<>();
        if (repo != null) {
            roots.add(repo.resolve("sim-core/src/main/java"));
            roots.add(repo.resolve("sim-host/src/main/java"));
            roots.add(repo.resolve("edge/src/main/java"));
        }
        if (jackpot != null) {
            roots.add(jackpot.resolve("pvphq-rollback-mod/src/main/java"));
        }
        return roots;
    }
}
