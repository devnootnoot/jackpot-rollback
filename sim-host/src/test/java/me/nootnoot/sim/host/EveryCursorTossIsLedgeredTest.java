package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

class EveryCursorTossIsLedgeredTest {

    private static final String MOD = "pvphq-rollback-mod/src/main/java/me/nootnoot/rollback/client/";

    private static final String[] TOSS_SITES = {
        MOD + "RollbackContainerScreen.java",
        MOD + "mixin/ClientPlayerInteractionManagerMixin.java",
    };

    private static final String INPUT_SOURCE = MOD + "McInputSource.java";

    private static Path mod(String rel) {
        Path here = Path.of("").toAbsolutePath();
        Path jackpot = here.getParent() == null ? null : here.getParent().getParent();
        return jackpot == null ? Path.of("nowhere") : jackpot.resolve(rel);
    }

    static boolean modPresent() {
        for (String site : TOSS_SITES) {
            if (!Files.exists(mod(site))) {
                return false;
            }
        }
        return Files.exists(mod(INPUT_SOURCE));
    }

    private static List<String[]> branches(String rel) throws IOException {
        String[] lines = Files.readString(mod(rel)).split("\n");
        List<String[]> found = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].contains(".dropsCursor()")) {
                continue;
            }
            int end = Math.min(lines.length, i + 12);
            found.add(Arrays.copyOfRange(lines, i, end));
        }
        return found;
    }

    private static boolean holds(String[] branch, String needle) {
        for (String line : branch) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @EnabledIf("modPresent")
    void everyCursorTossRefusesTheClickTheQueueRefused() throws IOException {
        for (String site : TOSS_SITES) {
            List<String[]> branches = branches(site);
            assertTrue(!branches.isEmpty(), site + " has no cursor-toss branch to check");
            for (String[] branch : branches) {
                assertTrue(holds(branch, "if (!RollbackModClient.queueInventoryIntent(intent))"),
                        site + " tosses the cursor stack without checking that the sim accepted"
                                + " the intent. A refused queue means the sim never drops it, so"
                                + " shrinking the carried stack anyway deletes the item from the"
                                + " player's screen while the sim still holds it, and the next"
                                + " repaint puts it back - a flicker the player reads as a dupe.");
            }
        }
    }

    @Test
    @EnabledIf("modPresent")
    void everyCursorTossWritesTheRecordItsMintedSeqWillAskFor() throws IOException {
        for (String site : TOSS_SITES) {
            for (String[] branch : branches(site)) {
                assertTrue(holds(branch, "Containers.recordDrop("),
                        site + " drops the cursor stack without recording it. The sim mints a"
                                + " dropSeq for it either way, so the ledger's queue is now one"
                                + " short: that seq can never be bound, and worse, the NEXT"
                                + " throw's record is what the pass hands it - every later drop"
                                + " carries another item's enchants for the rest of the match."
                                + " One record per minted seq, from every site that mints one.");
            }
        }
    }

    @Test
    @EnabledIf("modPresent")
    void theDropKeyRecordsWhatTheSimWillMintNotOnePerPress() throws IOException {
        String body = Files.readString(mod(INPUT_SOURCE));
        assertTrue(!body.contains("i < dropClicks"),
                "the drop key writes one record per press. A ctrl-drop empties the slot on the"
                        + " FIRST press, and a stack of two cannot feed five presses, so the sim"
                        + " mints fewer seqs than the tick had presses and the surplus records sit"
                        + " in front of every later drop forever. Record"
                        + " DropBindLedger.mintable(...) many instead - the client can see the same"
                        + " stack the sim can.");
        assertTrue(body.contains("DropBindLedger.mintable("),
                "the one place that decides how many records a tick writes has to be the one the"
                        + " ledger's own test pins against the sim");
    }
}
