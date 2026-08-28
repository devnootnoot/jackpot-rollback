package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class EdgeAndModPaintInventoryTheSameWayTest {

    private static final Path EDGE = Path.of("src/main/java/me/nootnoot/edge");

    private static final String MIRROR = "EdgeInventoryMirror.java";

    private static final String CONTAINER_MIRROR = "EdgeContainerMirror.java";

    private static Path modFile(String name) {
        return Path.of("").toAbsolutePath().getParent().getParent()
                .resolve("pvphq-rollback-mod/src/main/java/me/nootnoot/rollback/client").resolve(name);
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p);
    }

    @Test
    void bothSidesDecideWhatToRepaintWithTheSameSharedPlan() throws IOException {
        String edge = read(EDGE.resolve(MIRROR));
        assertTrue(edge.contains("InventoryPaintPlan.forPlayer()"),
                "the edge mirror must use the shared plan, not its own rule");

        Path mod = modFile("McSimRenderer.java");
        if (Files.exists(mod)) {
            String body = read(mod);
            assertTrue(body.contains("RollbackModClient.slotPaint()"),
                    "the mod renderer must use the shared plan, not its own rule");
        }
    }

    @Test
    void neitherMirrorHashesTheWholeTableToDecideWhetherToRepaint() throws IOException {
        List<Path> guarded = new ArrayList<>(List.of(EDGE.resolve(MIRROR), EDGE.resolve(CONTAINER_MIRROR)));
        Path mod = modFile("McSimRenderer.java");
        if (Files.exists(mod)) {
            guarded.add(mod);
        }
        for (Path file : guarded) {
            String body = read(file);
            assertFalse(body.contains("0x100000001b3L") || body.contains("1099511628211L"),
                    file.getFileName() + " still folds the WHOLE table into one signature. That is the "
                            + "root cause: one signature means one all-or-nothing decision, so a "
                            + "durability point on a chestplate repaints the slot the player is in the "
                            + "middle of moving.");
        }
    }

    @Test
    void everyEdgeInventoryIntentRegistersTheSlotsItWillTouch() throws IOException {
        String plugin = read(EDGE.resolve("EdgePlugin.java"));
        assertTrue(plugin.contains("inventory().own(") && plugin.contains("container().own("),
                "a routed click must claim the slots it will change, or the mirror will repaint them "
                        + "from state that predates the click");
        assertTrue(plugin.contains("ownHandSwap("),
                "the off-hand swap key moves two slots and must claim them too");
        assertFalse(plugin.contains("repaintInventory("),
                "the click listener used to force a full repaint from CONFIRMED state on every click. "
                        + "Confirmed state predates the click by a whole round trip, so that repaint "
                        + "actively re-drew the pre-click inventory over the click the player just made - "
                        + "the unmodded half of this bug.");

        String input = read(EDGE.resolve("EdgeInputSource.java"));
        assertTrue(input.contains("ownPaint("),
                "the exact landing frame is only known when the intent enters an input frame, so the "
                        + "drain has to re-claim with it");
    }

    @Test
    void theEdgeMirrorPaintsOnlyTheSlotsThePlanMarked() throws IOException {
        String body = read(EDGE.resolve(MIRROR));
        int paint = body.indexOf("inv.setItem(");
        assertTrue(paint > 0);
        String before = body.substring(0, paint);
        assertTrue(before.lastIndexOf("plan.repaint(slot)") > before.lastIndexOf("for (int slot"),
                "every slot write must be guarded by the plan; an unguarded loop over all 41 slots is "
                        + "the absolute repaint coming back");
    }

    @Test
    void noEdgeSourceStillCallsTheOneArgumentMirrorApply() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(EDGE)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String body = Files.readString(file);
                if (body.contains("inventory.apply(confirmed)") || body.contains("container.apply(confirmed)")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                offenders + " still paints from confirmed state alone. The predicted head is what lets "
                        + "an unmodded player see their own click land at the same moment a modded player "
                        + "does, instead of a round trip later.");
    }
}
