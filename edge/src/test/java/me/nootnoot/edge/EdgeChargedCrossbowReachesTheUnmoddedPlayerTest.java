package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class EdgeChargedCrossbowReachesTheUnmoddedPlayerTest {

    private static final Path EDGE = Path.of("src/main/java/me/nootnoot/edge");

    private static String read(String name) throws IOException {
        return Files.readString(EDGE.resolve(name));
    }

    private static Path modFile(String name) {
        return Path.of("").toAbsolutePath().getParent().getParent()
                .resolve("pvphq-rollback-mod/src/main/java/me/nootnoot/rollback/client")
                .resolve(name);
    }

    @Test
    void theSlotTheSimCallsLoadedIsPaintedLoaded() throws IOException {
        String mirror = read("EdgeInventoryMirror.java");

        assertTrue(mirror.contains("src.slotCrossbowLoaded[slot]"),
                "EdgeInventoryMirror builds the stack an UNMODDED player actually holds. The sim"
                        + " tracks the charge per slot in slotCrossbowLoaded, and until that value"
                        + " reaches the stack the player is handed a crossbow that always looks"
                        + " empty: the kit crossbow that starts LOADED never shows its bolt, the"
                        + " client plays a 25 tick load animation for a shot the sim fires"
                        + " instantly, and no reload the sim performs is ever visible");
        assertTrue(mirror.contains("src.cursorCrossbowLoaded"),
                "the cursor is the 42nd cell of the same table and carries its own charge flag");
    }

    @Test
    void theStackBuilderIsWhatAppliesTheCharge() throws IOException {
        String stacks = read("EdgeEntryStacks.java");

        assertTrue(stacks.contains("boolean crossbowLoaded"),
                "the charge has to be an argument of the stack builder. Passing it anywhere else"
                        + " means the frame-0 template decides the charge forever, and that"
                        + " template is whatever the kit happened to hand out on tick 0");
        assertTrue(stacks.contains("EdgeHeldItems.charged("),
                "and it has to go through the one helper that both the peer mirror and the"
                        + " inventory mirror use, so the two never drift");
    }

    @Test
    void theHelperForcesBothDirectionsRatherThanOnlyAddingACharge() throws IOException {
        String held = read("EdgeHeldItems.java");
        int start = held.indexOf("public static ItemStack charged(");
        assertTrue(start > 0, "EdgeHeldItems.charged is the shared helper");
        String body = held.substring(start, held.indexOf("\n    }", start));

        assertTrue(body.contains("addChargedProjectile"),
                "loading has to add a projectile the vanilla client will render");
        assertTrue(body.contains("setChargedProjectiles(null)"),
                "and unloading has to CLEAR it. A kit crossbow that starts charged seeds the"
                        + " frame-0 template with a bolt already in it, so a helper that only ever"
                        + " adds would leave the crossbow looking loaded for the whole match after"
                        + " the sim has already fired it");
    }

    @Test
    void noOtherEdgeSourceKeepsItsOwnCopyOfTheChargeRule() throws IOException {
        String peer = read("EdgePeerMirror.java");
        assertTrue(peer.contains("EdgeHeldItems.charged("),
                "the peer mirror used to carry a private copy of this helper; two copies of a"
                        + " render rule is how the two halves of one bug get fixed separately");
        assertFalse(peer.contains("private static ItemStack charged("),
                "the private copy must be gone, not merely unused");
    }

    @Test
    void theModPaintsTheSameFlagOntoItsOwnSlots() throws IOException {
        Path renderer = modFile("McSimRenderer.java");
        if (!Files.exists(renderer)) {
            return;
        }
        String body = Files.readString(renderer);
        assertTrue(body.contains("mine.slotCrossbowLoaded[slot]"),
                "the modded client reads the same per-slot flag when it builds its own inventory."
                        + " This test exists so the two halves are asserted in one place: if the"
                        + " mod half is the only one that reads it, an unmodded player cannot"
                        + " exercise a charged crossbow and a modded one can");
    }
}
