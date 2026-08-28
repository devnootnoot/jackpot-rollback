package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import me.nootnoot.sim.contract.InventoryIntents;
import me.nootnoot.sim.harness.HarnessScenarios;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.Loadout;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class InventoryClickTableTest {

    private static final double GROUND_Y = 64.0;

    private static final Arena ARENA = Arena.flat(GROUND_Y);

    private static final int APPLES = 2;

    private static final int PEARLS = 1;

    private static final int EMPTY_HOTBAR = 5;

    private static final int FIRST_STORAGE = 9;

    private record Row(String physicalAction,
                       String modClickType, int modKind, int modButton,
                       String edgeClickType, int edgeKind, int edgeButton,
                       int addr, boolean slotFilled, boolean cursorEmpty,
                       int expectedAction) {
    }

    private static List<Row> table() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("left click a filled slot with an empty cursor",
                "PICKUP", InventoryIntents.CLICK_PICKUP, 0,
                "LEFT", InventoryIntents.CLICK_PICKUP, 0,
                APPLES, true, true, Input.INV_PICKUP));
        rows.add(new Row("right click a filled slot with an empty cursor",
                "PICKUP", InventoryIntents.CLICK_PICKUP, 1,
                "RIGHT", InventoryIntents.CLICK_PICKUP, 1,
                APPLES, true, true, Input.INV_PICKUP_HALF));
        rows.add(new Row("left click an empty slot with a loaded cursor",
                "PICKUP", InventoryIntents.CLICK_PICKUP, 0,
                "LEFT", InventoryIntents.CLICK_PICKUP, 0,
                EMPTY_HOTBAR, false, false, Input.INV_PICKUP));
        rows.add(new Row("right click an empty slot with a loaded cursor",
                "PICKUP", InventoryIntents.CLICK_PICKUP, 1,
                "RIGHT", InventoryIntents.CLICK_PICKUP, 1,
                EMPTY_HOTBAR, false, false, Input.INV_PICKUP_HALF));
        rows.add(new Row("shift left click a filled slot",
                "QUICK_MOVE", InventoryIntents.CLICK_QUICK_MOVE, 0,
                "SHIFT_LEFT", InventoryIntents.CLICK_QUICK_MOVE, 0,
                APPLES, true, true, Input.INV_QUICK_MOVE));
        rows.add(new Row("shift right click a filled slot",
                "QUICK_MOVE", InventoryIntents.CLICK_QUICK_MOVE, 1,
                "SHIFT_RIGHT", InventoryIntents.CLICK_QUICK_MOVE, 1,
                APPLES, true, true, Input.INV_QUICK_MOVE));
        rows.add(new Row("shift click an empty slot",
                "QUICK_MOVE", InventoryIntents.CLICK_QUICK_MOVE, 0,
                "SHIFT_LEFT", InventoryIntents.CLICK_QUICK_MOVE, 0,
                EMPTY_HOTBAR, false, true, Input.INV_NONE));
        rows.add(new Row("hotbar key 5 over a filled slot",
                "SWAP", InventoryIntents.CLICK_SWAP, EMPTY_HOTBAR,
                "NUMBER_KEY", InventoryIntents.CLICK_SWAP, EMPTY_HOTBAR,
                APPLES, true, true, Input.INV_SWAP_SLOT));
        rows.add(new Row("offhand key over a filled slot",
                "SWAP", InventoryIntents.CLICK_SWAP, InventoryIntents.OFFHAND_SWAP_BUTTON,
                "SWAP_OFFHAND", InventoryIntents.CLICK_SWAP,
                InventoryIntents.OFFHAND_SWAP_BUTTON,
                APPLES, true, true, Input.INV_SWAP_SLOT));
        rows.add(new Row("hotbar key over the slot it would swap with",
                "SWAP", InventoryIntents.CLICK_SWAP, APPLES,
                "NUMBER_KEY", InventoryIntents.CLICK_SWAP, APPLES,
                APPLES, true, true, Input.INV_NONE));
        rows.add(new Row("drop key over a filled slot",
                "THROW", InventoryIntents.CLICK_THROW, 0,
                "DROP", InventoryIntents.CLICK_THROW, 0,
                APPLES, true, true, Input.INV_DROP_ONE));
        rows.add(new Row("control drop key over a filled slot",
                "THROW", InventoryIntents.CLICK_THROW, 1,
                "CONTROL_DROP", InventoryIntents.CLICK_THROW, 1,
                APPLES, true, true, Input.INV_DROP_STACK));
        rows.add(new Row("drop key over a filled slot while the cursor is loaded",
                "THROW", InventoryIntents.CLICK_THROW, 0,
                "DROP", InventoryIntents.CLICK_THROW, 0,
                APPLES, true, false, Input.INV_NONE));
        rows.add(new Row("double click with a loaded cursor",
                "PICKUP_ALL", InventoryIntents.CLICK_PICKUP_ALL, 0,
                "DOUBLE_CLICK", InventoryIntents.CLICK_PICKUP_ALL, 0,
                APPLES, true, false, Input.INV_PICKUP_ALL));
        rows.add(new Row("double click with an empty cursor",
                "PICKUP_ALL", InventoryIntents.CLICK_PICKUP_ALL, 0,
                "DOUBLE_CLICK", InventoryIntents.CLICK_PICKUP_ALL, 0,
                APPLES, true, true, Input.INV_NONE));
        rows.add(new Row("left click outside the window with a loaded cursor",
                "PICKUP", InventoryIntents.CLICK_PICKUP, 0,
                "WINDOW_BORDER_LEFT", InventoryIntents.CLICK_PICKUP, 0,
                InventoryIntents.ADDR_OUTSIDE, false, false, Input.INV_DROP_CURSOR_ALL));
        rows.add(new Row("right click outside the window with a loaded cursor",
                "PICKUP", InventoryIntents.CLICK_PICKUP, 1,
                "WINDOW_BORDER_RIGHT", InventoryIntents.CLICK_PICKUP, 1,
                InventoryIntents.ADDR_OUTSIDE, false, false, Input.INV_DROP_CURSOR_ONE));
        rows.add(new Row("left click outside the window with an empty cursor",
                "PICKUP", InventoryIntents.CLICK_PICKUP, 0,
                "LEFT", InventoryIntents.CLICK_PICKUP, 0,
                InventoryIntents.ADDR_OUTSIDE, false, true, Input.INV_NONE));
        rows.add(new Row("drop key outside the window",
                "THROW", InventoryIntents.CLICK_THROW, 0,
                "DROP", InventoryIntents.CLICK_THROW, 0,
                InventoryIntents.ADDR_OUTSIDE, false, true, Input.INV_NONE));
        rows.add(new Row("middle click a filled slot",
                "CLONE", InventoryIntents.CLICK_UNKNOWN, 2,
                "MIDDLE", InventoryIntents.CLICK_UNKNOWN, 2,
                APPLES, true, true, Input.INV_NONE));
        rows.add(new Row("any click on a crafting grid or other unaddressable slot",
                "PICKUP", InventoryIntents.CLICK_PICKUP, 0,
                "LEFT", InventoryIntents.CLICK_PICKUP, 0,
                InventoryIntents.ADDR_NONE, true, true, Input.INV_NONE));
        rows.add(new Row("a drag frame, which neither host models",
                "QUICK_CRAFT", InventoryIntents.CLICK_UNKNOWN, 0,
                "DRAG", InventoryIntents.CLICK_UNKNOWN, 0,
                APPLES, true, false, Input.INV_NONE));
        return rows;
    }

    private static InventoryIntents.Intent modIntent(Row r) {
        return InventoryIntents.decide(r.modKind(), r.addr(), r.modButton(), r.slotFilled(),
                r.cursorEmpty());
    }

    private static InventoryIntents.Intent edgeIntent(Row r) {
        return InventoryIntents.decide(r.edgeKind(), r.addr(), r.edgeButton(), r.slotFilled(),
                r.cursorEmpty());
    }

    @Test
    void everyPhysicalClickMeansTheSameThingOnBothHosts() {
        for (Row r : table()) {
            assertEquals(modIntent(r), edgeIntent(r),
                    "the modded client translates \"" + r.physicalAction() + "\" from "
                            + r.modClickType() + "/" + r.modButton() + " and the edge translates the"
                            + " same physical click from Bukkit " + r.edgeClickType() + "/"
                            + r.edgeButton() + ". The two hosts must hand the sim the same intent or"
                            + " a modded and an unmodded player get different inventories out of the"
                            + " same click");
        }
    }

    @Test
    void everyPhysicalClickResolvesToTheActionTheTableNames() {
        for (Row r : table()) {
            assertEquals(r.expectedAction(), modIntent(r).action(),
                    "\"" + r.physicalAction() + "\" changed meaning. This table is the click"
                            + " contract both hosts and the sim are written against");
        }
    }

    private static GameState duel() {
        return HarnessScenarios.duel(ARENA);
    }

    private static Input frame(InventoryIntents.Intent intent) {
        return Input.NONE.withInvAction(intent.action(), intent.src(), intent.dst())
                .withClicks(Clicks.NONE.withInv(1));
    }

    private static void step(GameState s, Input in) {
        Simulation.tick(s, ARENA, in, Input.NONE);
    }

    private static void run(GameState s, Row r) {
        step(s, frame(modIntent(r)));
    }

    private static Row row(String physicalAction) {
        for (Row r : table()) {
            if (r.physicalAction().equals(physicalAction)) {
                return r;
            }
        }
        throw new IllegalArgumentException(physicalAction);
    }

    @Test
    void aLeftClickPutsTheStackOnTheCursorAndEmptiesTheSlot() {
        GameState s = duel();
        PlayerState a = s.players[0];

        run(s, row("left click a filled slot with an empty cursor"));

        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.cursorEntry(a),
                "this is the click the tester could not make work: the item has to leave the slot"
                        + " and land on the cursor");
        assertEquals(16, Loadout.cursorCount(a));
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, APPLES));
    }

    @Test
    void aRightClickTakesHalfAndLeavesTheRest() {
        GameState s = duel();
        PlayerState a = s.players[0];

        run(s, row("right click a filled slot with an empty cursor"));

        assertEquals(8, Loadout.cursorCount(a));
        assertEquals(8, a.slotCount[APPLES]);
    }

    @Test
    void aLoadedCursorPlacesTheWholeStackOnTheLeftAndOneOnTheRight() {
        GameState s = duel();
        PlayerState a = s.players[0];

        run(s, row("left click a filled slot with an empty cursor"));
        step(s, Input.NONE);
        run(s, row("right click an empty slot with a loaded cursor"));

        assertEquals(1, a.slotCount[EMPTY_HOTBAR], "a right click places exactly one");
        assertEquals(15, Loadout.cursorCount(a));

        step(s, Input.NONE);
        run(s, row("left click an empty slot with a loaded cursor"));

        assertEquals(16, a.slotCount[EMPTY_HOTBAR], "a left click places the whole cursor");
        assertEquals(ItemDict.NONE, Loadout.cursorEntry(a));
    }

    @Test
    void shiftClickingAHotbarSlotSendsItToTheFirstStorageSlotLikeVanilla() {
        GameState s = duel();
        PlayerState a = s.players[0];

        run(s, row("shift left click a filled slot"));

        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, FIRST_STORAGE),
                "vanilla InventoryMenu.quickMoveStack moves a hotbar stack into slots 9..35 in"
                        + " ascending order, so an unmodded player sees it land in slot 9");
        assertEquals(16, a.slotCount[FIRST_STORAGE]);
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, APPLES));
    }

    @Test
    void aHotbarKeySwapsTheHoveredSlotWithThatHotbarSlot() {
        GameState s = duel();
        PlayerState a = s.players[0];

        run(s, row("hotbar key 5 over a filled slot"));

        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, EMPTY_HOTBAR),
                "this is the tester's hotbar-swap report: the stack has to move to the numbered"
                        + " hotbar slot and stay there");
        assertEquals(16, a.slotCount[EMPTY_HOTBAR]);
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, APPLES));
    }

    @Test
    void theOffhandKeySwapsWithTheOffhandSlot() {
        GameState s = duel();
        PlayerState a = s.players[0];

        run(s, row("offhand key over a filled slot"));

        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.entryAt(a, ItemDict.OFF_HAND));
        assertEquals(ItemDict.NONE, Loadout.entryAt(a, APPLES));
    }

    @Test
    void theDropKeyThrowsOneAndControlDropThrowsTheStack() {
        GameState one = duel();
        run(one, row("drop key over a filled slot"));
        assertEquals(15, one.players[0].slotCount[APPLES],
                "Q over a slot throws a single item out of it");

        GameState all = duel();
        run(all, row("control drop key over a filled slot"));
        assertEquals(ItemDict.NONE, Loadout.entryAt(all.players[0], APPLES),
                "control-Q over a slot throws the whole stack");
    }

    @Test
    void aDoubleClickGathersMatchingStacksOntoTheCursor() {
        GameState s = duel();
        PlayerState a = s.players[0];
        a.slotEntry[FIRST_STORAGE] = HarnessScenarios.APPLE_ENTRY;
        a.slotCount[FIRST_STORAGE] = 4;

        run(s, row("right click a filled slot with an empty cursor"));
        step(s, Input.NONE);
        run(s, row("double click with a loaded cursor"));

        assertTrue(Loadout.cursorCount(a) > 8,
                "a double click pulls matching stacks onto the cursor; it started at 8");
        assertEquals(HarnessScenarios.APPLE_ENTRY, Loadout.cursorEntry(a));
    }

    @Test
    void droppingTheCursorOutsideTheWindowEmptiesItWholeOrOneAtATime() {
        GameState whole = duel();
        run(whole, row("left click a filled slot with an empty cursor"));
        step(whole, Input.NONE);
        run(whole, row("left click outside the window with a loaded cursor"));
        assertEquals(ItemDict.NONE, Loadout.cursorEntry(whole.players[0]),
                "a left click outside the window throws the whole carried stack");

        GameState single = duel();
        run(single, row("left click a filled slot with an empty cursor"));
        step(single, Input.NONE);
        run(single, row("right click outside the window with a loaded cursor"));
        assertEquals(15, Loadout.cursorCount(single.players[0]),
                "a right click outside the window throws exactly one off the cursor");
    }

    @Test
    void everySilentRowLeavesTheInventoryExactlyAsItWas() {
        for (Row r : table()) {
            if (r.expectedAction() != Input.INV_NONE) {
                continue;
            }
            GameState s = duel();
            PlayerState a = s.players[0];
            int seqBefore = a.invActionSeq;
            int[] before = a.slotCount.clone();

            run(s, r);

            assertEquals(seqBefore, a.invActionSeq,
                    "\"" + r.physicalAction() + "\" produces no intent, so the sim must not move"
                            + " anything. A host that answers it locally anyway shows the player a"
                            + " move that never happened");
            for (int i = 0; i < before.length; i++) {
                assertEquals(before[i], a.slotCount[i],
                        "\"" + r.physicalAction() + "\" changed slot " + i);
            }
        }
    }

    @Test
    void theConfirmedInventoryCounterIsNotAnAcknowledgementOfAClick() {
        GameState s = duel();
        PlayerState a = s.players[0];
        int before = a.invActionSeq;

        Input swap = Input.NONE.withSwapHands(true).withClicks(Clicks.NONE.withSwap(1));
        assertEquals(Input.INV_NONE, swap.invAction(),
                "the frame under test carries no inventory click at all");
        step(s, swap);

        assertNotEquals(before, a.invActionSeq,
                "PlayerState.invActionSeq counts every inventory MUTATION, not every player click:"
                        + " a hand swap, a ground pickup, an arrow being consumed, a crossbow"
                        + " retype and an item breaking all advance it. A client that reconciles an"
                        + " optimistic inventory click by waiting for this counter to advance will"
                        + " release on somebody else's event and repaint the whole slot table from"
                        + " a confirmed state that does not contain the click yet - the item hops"
                        + " back into its slot and the cursor empties. Confirm a click by the FRAME"
                        + " it was sent on, never by this counter");
    }

    private static Path rollbackRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))
                    && Files.isDirectory(p.resolve("sim-core"))
                    && Files.isDirectory(p.resolve("edge"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    private static Path modFile(Path root, String tail) {
        Path up = root.getParent();
        for (int i = 0; up != null && i < 3; i++) {
            Path candidate = up.resolve("pvphq-rollback-mod/src/main/java/me/nootnoot/rollback/"
                    + "client/" + tail);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            up = up.getParent();
        }
        return null;
    }

    private static String strip(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < n && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
                out.append("LITERAL");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static Map<String, String> clickSurfaces() throws IOException {
        Path root = rollbackRoot();
        assertTrue(root != null, "this gate could not find the jackpot-rollback root from "
                + Path.of("").toAbsolutePath() + ", so it can read neither host and would pass"
                + " without checking anything. Fix the lookup, do not delete it");
        Map<String, Path> files = new LinkedHashMap<>();
        files.put("EdgePlugin", root.resolve("edge/src/main/java/me/nootnoot/edge/EdgePlugin.java"));
        files.put("ClientPlayerInteractionManagerMixin",
                modFile(root, "mixin/ClientPlayerInteractionManagerMixin.java"));
        files.put("RollbackContainerScreen", modFile(root, "RollbackContainerScreen.java"));
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Path> e : files.entrySet()) {
            assertTrue(e.getValue() != null && Files.isRegularFile(e.getValue()),
                    "the click surface " + e.getKey() + " is not where this gate looks. Fix the"
                            + " path, do not delete the entry");
            out.put(e.getKey(), strip(Files.readString(e.getValue())));
        }
        return out;
    }

    private static TreeSet<String> clickKinds(String body) {
        TreeSet<String> out = new TreeSet<>();
        String token = "InventoryIntents.CLICK_";
        int from = 0;
        while (true) {
            int at = body.indexOf(token, from);
            if (at < 0) {
                return out;
            }
            int end = at + token.length();
            while (end < body.length() && (Character.isLetterOrDigit(body.charAt(end))
                    || body.charAt(end) == '_')) {
                end++;
            }
            out.add(body.substring(at + "InventoryIntents.".length(), end));
            from = end;
        }
    }

    @Test
    void bothHostsTranslateTheSameSetOfClickKinds() throws IOException {
        Map<String, String> surfaces = clickSurfaces();
        TreeSet<String> edge = clickKinds(surfaces.get("EdgePlugin"));
        TreeSet<String> mod = clickKinds(surfaces.get("ClientPlayerInteractionManagerMixin"));
        assertFalse(edge.isEmpty(), "the edge names no click kinds at all, so this gate is vacuous");
        assertEquals(edge, mod,
                "one host understands a physical click the other one drops on the floor. The set of"
                        + " click kinds each host can mint has to be identical or the same key press"
                        + " does two different things depending on whether you run the mod");
    }

    private static int occurrences(String body, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = body.indexOf(token, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + token.length();
        }
    }

    @Test
    void theModNeverAppliesAClickItCouldNotSend() throws IOException {
        Map<String, String> surfaces = clickSurfaces();
        List<String> complaints = new ArrayList<>();
        for (Map.Entry<String, String> e : surfaces.entrySet()) {
            if (e.getKey().equals("EdgePlugin")) {
                continue;
            }
            for (String call : List.of("queueInventoryIntent(", "queueInventoryDrop(")) {
                int total = occurrences(e.getValue(), "RollbackModClient." + call);
                int gated = occurrences(e.getValue(), "!RollbackModClient." + call);
                if (total != gated) {
                    complaints.add(e.getKey() + " makes " + total + " " + call + " calls but only "
                            + gated + " of them are checked");
                }
            }
        }
        assertTrue(complaints.isEmpty(),
                "the modded client applies a click to its own screen optimistically. The queue that"
                        + " carries the intent to the sim is bounded and rejects an illegal"
                        + " address, so an unchecked queue call means the screen shows a move the"
                        + " sim will never make - it stands until the next confirmed repaint and"
                        + " then snaps back. Every local application has to be gated on the queue"
                        + " having accepted the intent: " + complaints);
    }
}
