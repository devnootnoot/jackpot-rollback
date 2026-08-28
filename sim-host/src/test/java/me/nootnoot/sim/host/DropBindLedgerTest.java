package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class DropBindLedgerTest {

    private static final double GROUND_Y = 64.0;

    private static final int LOCAL_SLOT = 0;

    private static final int UID_BASE = (LOCAL_SLOT + 1) << 28;

    private static final int SLOTS = 8;

    private static final int FIRST_RAW_ID = 100;

    private record Snap(int slot, int rawId, int ordinal) {
    }

    private static final class Client {

        private final DropBindLedger<Snap> ledger = new DropBindLedger<>();
        private final List<Snap> recorded = new ArrayList<>();
        private final Map<Integer, Snap> bound = new HashMap<>();
        private final List<Integer> boundOrder = new ArrayList<>();

        void record(int slot) {
            Snap snap = new Snap(slot, FIRST_RAW_ID + slot, recorded.size());
            recorded.add(snap);
            assertTrue(ledger.record(slot, snap),
                    "the ledger refused record " + snap.ordinal() + "; the reproduction never"
                            + " reaches MAX_RECORDS");
        }

        List<DropBindLedger.Bind<Snap>> pass(PlayerState mine) {
            List<DropBindLedger.Bind<Snap>> binds =
                    ledger.pass(mine.dropSeq, UID_BASE, mine.lastDropSlot);
            for (DropBindLedger.Bind<Snap> bind : binds) {
                assertEquals(null, bound.put(bind.uid(), bind.stack()),
                        "uid " + bind.uid() + " was bound twice");
                boundOrder.add(bind.seq());
            }
            return binds;
        }

        void drainPasses(PlayerState mine) {
            int guard = 0;
            while (!ledger.caughtUp(mine.dropSeq)) {
                pass(mine);
                assertTrue(++guard < 64, "the ledger never caught up with dropSeq");
            }
        }
    }

    private static final class ShippedBinder {

        private final Map<Integer, java.util.ArrayDeque<Snap>> bySlot = new HashMap<>();
        private final Map<Integer, Snap> bound = new HashMap<>();
        private int boundDropSeq = -1;

        void record(Snap snap) {
            bySlot.computeIfAbsent(snap.slot(), k -> new java.util.ArrayDeque<>()).add(snap);
        }

        void pass(PlayerState mine) {
            if (boundDropSeq < 0 || mine.dropSeq <= boundDropSeq) {
                boundDropSeq = mine.dropSeq;
                return;
            }
            int from = Math.max(boundDropSeq + 1,
                    mine.dropSeq - DropBindLedger.MAX_BINDS_PER_PASS + 1);
            for (int seq = from; seq <= mine.dropSeq; seq++) {
                java.util.ArrayDeque<Snap> queue = bySlot.get(mine.lastDropSlot);
                Snap snap = queue == null ? null : queue.poll();
                if (snap != null) {
                    bound.put(UID_BASE | seq, snap);
                }
            }
            boundDropSeq = mine.dropSeq;
        }
    }

    private static GameState kit() {
        GameState s = new GameState();
        ItemDict.Builder b = new ItemDict.Builder();
        int[] entries = new int[SLOTS];
        for (int i = 0; i < SLOTS; i++) {
            entries[i] = b.add(FIRST_RAW_ID + i, 64, 0, ItemDict.FLAG_BLOCK, Combat.USE_NONE,
                    1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                    ItemDict.EQUIP_NONE, -1);
        }
        s.dict = b.build();
        for (int i = 0; i < 2; i++) {
            PlayerState p = s.players[i];
            p.health = 20f;
            p.food = 20f;
            p.saturation = 5f;
            p.onGround = true;
            p.y = GROUND_Y;
            p.z = 0.5;
            for (int slot = 0; slot < SLOTS; slot++) {
                p.slotEntry[slot] = entries[slot];
                p.slotCount[slot] = 64;
                p.slotDamage[slot] = 0;
            }
        }
        s.players[0].x = 0.5;
        s.players[1].x = 10_000.0;
        return s;
    }

    private static Input toss(int slot, int presses) {
        return new Input(false, false, false, false, false, false, false, false, false,
                0f, 0f, slot)
                .withDrop(true, false)
                .withClicks(new Clicks(0, 0, presses, 0, 0));
    }

    private static Input tossWholeStack(int slot, int presses) {
        return new Input(false, false, false, false, false, false, false, false, false,
                0f, 0f, slot)
                .withDrop(true, true)
                .withClicks(new Clicks(0, 0, presses, 0, 0));
    }

    private static int mintedBy(GameState s, Arena arena, Input in) {
        int before = s.players[0].dropSeq;
        Simulation.tick(s, arena, in, Input.NONE);
        return s.players[0].dropSeq - before;
    }

    private static GameState settle(Arena arena) {
        GameState s = kit();
        for (int i = 0; i < 30; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
        return s;
    }

    private static int tossTick(GameState s, Arena arena, Client client, int slot, int presses) {
        int before = s.players[0].dropSeq;
        for (int i = 0; i < presses; i++) {
            client.record(slot);
        }
        Simulation.tick(s, arena, toss(slot, presses), Input.NONE);
        int minted = s.players[0].dropSeq - before;
        assertEquals(presses, minted,
                "the sim refused a toss the client had already recorded, so the reproduction"
                        + " would be measuring record drift instead of the binder");
        return minted;
    }

    private static Map<Integer, Integer> itemIdByUid(GameState s) {
        Map<Integer, Integer> byUid = new HashMap<>();
        for (ItemEntityState e : s.items) {
            if (e.dropUid != 0) {
                byUid.put(e.dropUid, e.itemId);
            }
        }
        return byUid;
    }

    @Test
    void everyDropOfAMultiDropTickBindsItsOwnRecord() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = settle(arena);
        PlayerState mine = s.players[0];
        Client client = new Client();
        client.pass(mine);

        int presses = 4;
        tossTick(s, arena, client, 3, presses);
        List<DropBindLedger.Bind<Snap>> binds = client.pass(mine);

        assertEquals(presses, binds.size(),
                "a tick that minted " + presses + " drop uids must produce " + presses
                        + " binds; binding only the newest leaves the rest with no remembered"
                        + " stack and leaves the record queue out of step");
        for (int i = 0; i < presses; i++) {
            DropBindLedger.Bind<Snap> bind = binds.get(i);
            assertEquals(mine.dropSeq - presses + 1 + i, bind.seq());
            assertEquals(UID_BASE | bind.seq(), bind.uid());
            assertEquals(3, bind.slot());
            assertSame(client.recorded.get(i), bind.stack(),
                    "bind " + i + " took the wrong record off the queue");
        }
    }

    @Test
    void aGapSpanningTwoSlotsBindsEachSeqToItsOwnSlot() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = settle(arena);
        PlayerState mine = s.players[0];
        Client client = new Client();
        client.pass(mine);

        int firstSlot = 2;
        int secondSlot = 5;
        int first = tossTick(s, arena, client, firstSlot, 4);
        int second = tossTick(s, arena, client, secondSlot, 3);

        List<DropBindLedger.Bind<Snap>> binds = client.pass(mine);

        assertEquals(first + second, binds.size(),
                "one pass has to cover both ticks of the gap");
        assertEquals(secondSlot, mine.lastDropSlot,
                "PlayerState only remembers the LAST drop's slot, which is exactly why passing it"
                        + " for every seq in the gap binds the earlier ticks against the wrong"
                        + " per-slot queue");
        for (int i = 0; i < binds.size(); i++) {
            DropBindLedger.Bind<Snap> bind = binds.get(i);
            int want = i < first ? firstSlot : secondSlot;
            assertEquals(want, bind.slot(),
                    "seq " + bind.seq() + " came from slot " + want + " but bound a record from"
                            + " slot " + bind.slot());
            assertSame(client.recorded.get(i), bind.stack(),
                    "seq " + bind.seq() + " bound record " + bind.stack().ordinal()
                            + " instead of record " + i);
        }

        Map<Integer, Integer> byUid = itemIdByUid(s);
        int checked = 0;
        for (DropBindLedger.Bind<Snap> bind : binds) {
            Integer itemId = byUid.get(bind.uid());
            if (itemId == null) {
                continue;
            }
            checked++;
            assertEquals(bind.stack().rawId(), itemId.intValue(),
                    "uid " + bind.uid() + " is an item entity of type " + itemId
                            + " but was bound to a remembered stack of type "
                            + bind.stack().rawId());
        }
        assertTrue(checked >= 2,
                "same-tick tosses merge, but at least one surviving entity per slot must remain"
                        + " for the binding to be checked against the world");

        ShippedBinder shipped = new ShippedBinder();
        for (Snap snap : client.recorded) {
            shipped.record(snap);
        }
        shipped.boundDropSeq = 0;
        shipped.pass(mine);
        Snap wrong = shipped.bound.get(UID_BASE | 1);
        assertNotNull(wrong, "the shipped binder did reach seq 1");
        assertEquals(secondSlot, wrong.slot(),
                "this is the shipped bug, pinned: passing mine.lastDropSlot for every seq in the"
                        + " gap makes seq 1 - a slot " + firstSlot + " toss - poll slot "
                        + secondSlot + "'s queue, so it binds another item's stack and leaves"
                        + " slot " + firstSlot + "'s queue undrained");
        assertTrue(shipped.bySlot.get(firstSlot) != null
                        && !shipped.bySlot.get(firstSlot).isEmpty(),
                "and slot " + firstSlot + "'s records are stranded, so every later toss from it"
                        + " binds one entry behind forever");
    }

    @Test
    void aBurstLargerThanOnePassIsFinishedByTheNextPassNotAbandoned() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = settle(arena);
        PlayerState mine = s.players[0];
        Client client = new Client();
        client.pass(mine);

        int ticks = 12;
        int perTick = Clicks.MAX;
        int total = 0;
        for (int t = 0; t < ticks; t++) {
            total += tossTick(s, arena, client, t % SLOTS, perTick);
        }
        assertTrue(total > DropBindLedger.MAX_BINDS_PER_PASS,
                "the burst has to exceed the per-pass clamp or it proves nothing: minted " + total);

        List<DropBindLedger.Bind<Snap>> firstPass = client.pass(mine);
        assertEquals(DropBindLedger.MAX_BINDS_PER_PASS, firstPass.size(),
                "a pass stays bounded");
        assertEquals(1, firstPass.get(0).seq(),
                "the pass has to start at the OLDEST unbound seq. Clamping the START instead"
                        + " abandons the oldest drops outright, and every abandoned seq also"
                        + " leaves its record on the queue so every later drop binds one behind");

        client.drainPasses(mine);

        assertEquals(total, client.boundOrder.size(),
                "every minted seq must end up bound across the passes, none skipped");
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < client.boundOrder.size(); i++) {
            assertEquals(i + 1, client.boundOrder.get(i).intValue(),
                    "seqs must be bound in order with no gaps");
            assertTrue(seen.add(client.boundOrder.get(i)), "a seq was bound twice");
        }
        assertEquals(0, client.ledger.pending(),
                "the record queue must be fully drained, otherwise it is one entry out of step"
                        + " for every later drop");

        Map<Integer, Integer> byUid = itemIdByUid(s);
        int checked = 0;
        for (Map.Entry<Integer, Snap> e : client.bound.entrySet()) {
            Integer itemId = byUid.get(e.getKey());
            if (itemId == null) {
                continue;
            }
            checked++;
            assertEquals(e.getValue().rawId(), itemId.intValue(),
                    "uid " + e.getKey() + " bound the wrong remembered stack");
        }
        assertTrue(checked >= SLOTS,
                "at least one surviving entity per slot should be checkable against the world");

        ShippedBinder shipped = new ShippedBinder();
        for (Snap snap : client.recorded) {
            shipped.record(snap);
        }
        shipped.boundDropSeq = 0;
        shipped.pass(mine);
        int abandoned = total - DropBindLedger.MAX_BINDS_PER_PASS;
        for (int seq = 1; seq <= abandoned; seq++) {
            assertTrue(!shipped.bound.containsKey(UID_BASE | seq),
                    "this is the shipped clamp, pinned: clamping the gap START abandons the "
                            + abandoned + " oldest seqs of a " + total + "-drop burst outright."
                            + " Seq " + seq + " is never bound and never revisited, because the"
                            + " same pass then moves boundDropSeq all the way to dropSeq");
        }
        int lost = 0;
        for (int seq = 1; seq <= total; seq++) {
            if (!shipped.bound.containsKey(UID_BASE | seq)) {
                lost++;
            }
        }
        assertTrue(lost > abandoned,
                "and the loss is worse than the clamp alone, because every seq the pass does walk"
                        + " polls mine.lastDropSlot's queue, which holds only that slot's records:"
                        + " " + lost + " of " + total + " drops end up with no remembered stack");
        assertEquals(total, client.boundOrder.size(),
                "the ledger loses none of them");
    }

    @Test
    void aWholeStackTossMintsOneSeqHoweverManyPressesRideTheTick() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = settle(arena);
        int presses = 4;

        int minted = mintedBy(s, arena, tossWholeStack(3, presses));

        assertEquals(1, minted,
                "the first ctrl-drop takes the WHOLE slot, so every later press on the same tick"
                        + " finds it empty and mints nothing");
        assertEquals(minted, DropBindLedger.mintable(true, presses, 64, 0),
                "the record count the client writes has to be the count the sim will mint, or the"
                        + " record queue and the seq stream stop being the same stream");
    }

    @Test
    void moreDropClicksThanItemsMintOnlyAsManySeqsAsThereAreItems() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = settle(arena);
        int slot = 4;
        int have = 2;
        s.players[0].slotCount[slot] = have;
        int presses = 5;

        int minted = mintedBy(s, arena, toss(slot, presses));

        assertEquals(have, minted,
                "a tick can carry more drop clicks than the stack has items; the sim stops when the"
                        + " slot runs out");
        assertEquals(minted, DropBindLedger.mintable(false, presses, have, 0),
                "the client can see the same stack the sim can, so it can record exactly as many"
                        + " as will be minted instead of one per press");
    }

    @Test
    void aTickThatKeyDropsAndInventoryTossesSharesOneMintBudget() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = settle(arena);
        int keyPresses = Clicks.MAX;
        int invPresses = Clicks.MAX;
        Input both = toss(3, keyPresses)
                .withInvAction(Input.INV_DROP_ONE, 5, 0)
                .withClicks(new Clicks(0, 0, keyPresses, invPresses, 0));

        int minted = mintedBy(s, arena, both);

        assertEquals(Clicks.MAX, minted,
                "the key drops and the inventory tosses of one tick share ONE budget in the sim,"
                        + " so a tick can never mint more than Clicks.MAX drops however the two"
                        + " streams split it");
        int keyRecords = DropBindLedger.mintable(false, keyPresses, 64, 0);
        int invRecords = DropBindLedger.mintable(false, invPresses, 64, keyRecords);
        assertEquals(minted, keyRecords + invRecords,
                "recording each stream against its own count writes " + (keyPresses + invPresses)
                        + " records for a tick that minted " + minted + ", and the surplus stays"
                        + " queued in front of every later drop");
    }

    @Test
    void onePerPressOnAWholeStackTossPutsEveryLaterBindOneRecordBehind() {
        DropBindLedger<Snap> byPress = new DropBindLedger<>();
        DropBindLedger<Snap> byMintable = new DropBindLedger<>();
        byPress.pass(0, UID_BASE, DropBindLedger.NO_SLOT);
        byMintable.pass(0, UID_BASE, DropBindLedger.NO_SLOT);

        int slot = 3;
        int presses = 4;
        Snap ctrlDropped = new Snap(slot, FIRST_RAW_ID + slot, 0);
        Snap laterDropped = new Snap(slot, FIRST_RAW_ID + slot, 1);

        for (int i = 0; i < presses; i++) {
            byPress.record(slot, ctrlDropped);
        }
        for (int i = 0; i < DropBindLedger.mintable(true, presses, 64, 0); i++) {
            byMintable.record(slot, ctrlDropped);
        }
        byPress.pass(1, UID_BASE, slot);
        byMintable.pass(1, UID_BASE, slot);

        byPress.record(slot, laterDropped);
        byMintable.record(slot, laterDropped);
        List<DropBindLedger.Bind<Snap>> wrong = byPress.pass(2, UID_BASE, slot);
        List<DropBindLedger.Bind<Snap>> right = byMintable.pass(2, UID_BASE, slot);

        assertEquals(1, wrong.size());
        assertSame(ctrlDropped, wrong.get(0).stack(),
                "this is the record-drift, pinned: a ctrl-drop that minted ONE seq wrote FOUR"
                        + " records, so the next toss binds a leftover of the ctrl-drop and every"
                        + " toss after it is one entry behind for the rest of the match. The slot"
                        + " resync cannot see it, because the leftovers name the same slot.");
        assertEquals(3, byPress.pending(),
                "and the leftovers stay queued, so the drift never heals on its own");

        assertEquals(1, right.size());
        assertSame(laterDropped, right.get(0).stack(),
                "recording the count the sim will actually mint keeps the queue and the seq stream"
                        + " in step");
        assertEquals(0, byMintable.pending());
    }

    @Test
    void recordsAreBoundedAndOverflowIsRefusedNotSilentlyDropped() {
        DropBindLedger<Snap> ledger = new DropBindLedger<>();
        for (int i = 0; i < DropBindLedger.MAX_RECORDS; i++) {
            assertTrue(ledger.record(0, new Snap(0, FIRST_RAW_ID, i)));
        }
        assertTrue(ledger.full());
        assertTrue(!ledger.record(0, new Snap(0, FIRST_RAW_ID, DropBindLedger.MAX_RECORDS)),
                "an overflowing record must be REFUSED so the caller can refuse the click,"
                        + " never accepted and quietly discarded");
        assertEquals(DropBindLedger.MAX_RECORDS, ledger.pending());
    }

    @Test
    void aDriftedRecordQueueResyncsOnTheSlotTheSimReports() {
        DropBindLedger<Snap> ledger = new DropBindLedger<>();
        ledger.pass(0, UID_BASE, DropBindLedger.NO_SLOT);
        Snap stale = new Snap(2, FIRST_RAW_ID + 2, 0);
        Snap real = new Snap(5, FIRST_RAW_ID + 5, 1);
        ledger.record(stale.slot(), stale);
        ledger.record(real.slot(), real);

        List<DropBindLedger.Bind<Snap>> binds = ledger.pass(1, UID_BASE, 5);

        assertEquals(1, binds.size());
        assertNotNull(binds.get(0));
        assertSame(real, binds.get(0).stack(),
                "the sim minted ONE seq from slot 5, so the stale slot-2 record the sim refused"
                        + " has to be discarded rather than bound to it");
        assertEquals(0, ledger.pending());
    }
}
