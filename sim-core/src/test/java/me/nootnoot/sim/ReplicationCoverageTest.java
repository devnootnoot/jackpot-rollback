package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import me.nootnoot.sim.net.InputCodec;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.ClickBudget;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.ItemEntityState;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;
import org.junit.jupiter.api.Test;

class ReplicationCoverageTest {
    private static Map<String, Consumer<GameState>> replicatedFieldMutations() {
        Map<String, Consumer<GameState>> all = new LinkedHashMap<>();
        all.put("tick", s -> s.tick += 1);
        all.put("players", s -> s.players[1].health -= 1.0f);
        all.put("projectiles", s -> {
            ProjectileState p = new ProjectileState();
            p.id = 9;
            p.type = ProjectileState.TYPE_ARROW;
            p.damage = 6.0f;
            s.projectiles.add(p);
        });
        all.put("nextProjectileId", s -> s.nextProjectileId += 1);
        all.put("blocks", s -> s.blocks.place(4, 5, 6, 21));
        all.put("crystals", s -> {
            CrystalState c = new CrystalState();
            c.id = 3;
            c.bx = 1;
            c.by = 2;
            c.bz = 3;
            s.crystals.add(c);
        });
        all.put("nextCrystalId", s -> s.nextCrystalId += 1);
        all.put("anchors", s -> s.anchors.put(77L, 2));
        all.put("items", s -> {
            ItemEntityState e = new ItemEntityState();
            e.id = 5;
            e.entry = 1;
            e.itemId = 21;
            e.count = 1;
            s.items.add(e);
        });
        all.put("nextItemId", s -> s.nextItemId += 1);
        all.put("itemsRefused", s -> s.itemsRefused += 1);
        all.put("brokenArena", s -> s.brokenArena.add(91L));
        all.put("vanillaBuild", s -> s.vanillaBuild = !s.vanillaBuild);
        all.put("allowExplosion", s -> s.allowExplosion = !s.allowExplosion);
        all.put("allowBucket", s -> s.allowBucket = !s.allowBucket);
        all.put("potSwordBoost", s -> s.potSwordBoost = !s.potSwordBoost);
        all.put("breakableItemIds", s -> s.breakableItemIds = new int[]{7, 9});
        all.put("placeableItemIds", s -> s.placeableItemIds = new int[]{11});
        all.put("blockResistance", s -> s.blockResistance.put(12L, 6.0f));
        all.put("fluids", s -> s.fluids.put(13L, 8));
        all.put("cobwebs", s -> s.cobwebs.put(14L, 1));
        all.put("fires", s -> s.fires.put(15L, 40));
        all.put("cobwebItemId", s -> s.cobwebItemId += 1);
        all.put("stringItemId", s -> s.stringItemId += 1);
        all.put("obsidianItemId", s -> s.obsidianItemId += 1);
        all.put("cobblestoneItemId", s -> s.cobblestoneItemId += 1);
        all.put("mudItemId", s -> s.mudItemId += 1);
        all.put("glowstoneItemId", s -> s.glowstoneItemId += 1);
        all.put("glowstoneDustItemId", s -> s.glowstoneDustItemId += 1);
        all.put("playCenterX", s -> s.playCenterX += 1.0);
        all.put("playCenterZ", s -> s.playCenterZ += 1.0);
        all.put("playRadius", s -> s.playRadius = 12.5);
        all.put("playCircular", s -> s.playCircular = !s.playCircular);
        all.put("roundWinsP0", s -> s.roundWinsP0 += 1);
        all.put("roundWinsP1", s -> s.roundWinsP1 += 1);
        all.put("roundsTarget", s -> s.roundsTarget += 2);
        all.put("roundResetCountdown", s -> s.roundResetCountdown += 1);
        all.put("awaitingReady", s -> s.awaitingReady = !s.awaitingReady);
        all.put("roundStartGrace", s -> s.roundStartGrace += 1);
        all.put("roundMatchOver", s -> s.roundMatchOver = !s.roundMatchOver);
        all.put("roundMatchWinner", s -> s.roundMatchWinner = 1);
        all.put("roundInitial", s -> s.roundInitial[0].health += 1.0f);
        all.put("dict", s -> s.dict = otherDict());
        all.put("blockProps", s -> s.blockProps = otherBlockProps());
        all.put("edgeHosted", s -> s.edgeHosted[1] = !s.edgeHosted[1]);
        all.put("containers", s -> s.containers.put(4, filledContainer()));
        all.put("blockContainers", s -> s.blockContainers.put(31L, 4));
        all.put("roundInitialContainers", s -> s.roundInitialContainers.put(4, filledContainer()));
        all.put("nextContainerId", s -> s.nextContainerId += 1);
        all.put("blastCellBudget", s -> s.blastCellBudget -= 1);
        all.put("blastMarchBudget", s -> s.blastMarchBudget -= 1);
        all.put("blastSeq", s -> s.blastSeq += 1);
        return all;
    }

    private static Map<String, String> unreplicatedFieldReasons() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("events", "a per-tick output buffer the host drains after every step. It is not"
                + " carried by copy(), not written by the frame-0 codec and deliberately outside"
                + " the checksum: it is presentation, not state.");
        all.put("itemGrid", "a derived spatial index rebuilt from items. It holds no state of its"
                + " own, so checksumming it would only hash items a second time.");
        return all;
    }

    private static Map<String, String> unreplicatedNestedFieldReasons() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("PlayerState.clickBudget", "a per-tick input allowance refilled from the frame at"
                + " the top of every tick. copy() clears it and the checksum does not read it,"
                + " both deliberately: restoring it across a rollback would let the clicks it"
                + " holds be spent twice.");
        return all;
    }

    private static ItemDict otherDict() {
        ItemDict.Builder b = new ItemDict.Builder();
        b.add(7, 64, 0, 0, 0, 1.0f, 4.0f, 0, 0, 0, 0, 0, 0, 0.0f, 0, 0, 0, 0, 0, 0,
                0, 0.0f, 0.0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        return b.build();
    }

    private static BlockProps otherBlockProps() {
        return new BlockProps.Builder().add(21, 50.0f, 1200.0f, 21, 3, 1, true).build();
    }

    private static Container filledContainer() {
        Container c = new Container();
        c.entry[0] = 1;
        c.count[0] = 2;
        return c;
    }

    private static List<Field> declaredState() {
        List<Field> out = new ArrayList<>();
        for (Field f : GameState.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    private static GameState seeded() {
        GameState s = new GameState();
        s.potSwordBoost = true;
        s.cobwebItemId = 11;
        s.stringItemId = 12;
        s.obsidianItemId = 13;
        s.cobblestoneItemId = 14;
        s.mudItemId = 15;
        s.glowstoneItemId = 16;
        s.glowstoneDustItemId = 17;
        s.playCenterX = 3.0;
        s.playCenterZ = -4.0;
        s.playRadius = 40.0;
        s.playCircular = true;
        s.players[0].health = 20.0f;
        s.players[1].health = 20.0f;
        s.roundInitial = new PlayerState[]{s.players[0].copy(), s.players[1].copy()};
        return s;
    }

    private static final String STATE_PACKAGE = "me.nootnoot.sim.state.";

    private record Probe(String label, Class<?> type, Function<GameState, Object> plant) {
    }

    private static List<Probe> nestedProbes() {
        List<Probe> all = new ArrayList<>();
        all.add(new Probe("players[0]", PlayerState.class, s -> s.players[0]));
        all.add(new Probe("roundInitial[0]", PlayerState.class, s -> s.roundInitial[0]));
        all.add(new Probe("projectiles[0]", ProjectileState.class, s -> {
            ProjectileState p = new ProjectileState();
            p.id = 9;
            p.type = ProjectileState.TYPE_ARROW;
            p.damage = 6.0f;
            s.projectiles.add(p);
            return p;
        }));
        all.add(new Probe("crystals[0]", CrystalState.class, s -> {
            CrystalState c = new CrystalState();
            c.id = 3;
            c.bx = 1;
            c.by = 2;
            c.bz = 3;
            s.crystals.add(c);
            return c;
        }));
        all.add(new Probe("items[0]", ItemEntityState.class, s -> {
            ItemEntityState e = new ItemEntityState();
            e.id = 5;
            e.entry = 1;
            e.itemId = 21;
            e.count = 1;
            s.items.add(e);
            return e;
        }));
        all.add(new Probe("containers[4]", Container.class, s -> {
            Container c = filledContainer();
            s.containers.put(4, c);
            return c;
        }));
        all.add(new Probe("roundInitialContainers[4]", Container.class, s -> {
            Container c = filledContainer();
            s.roundInitialContainers.put(4, c);
            return c;
        }));
        return all;
    }

    private static Map<String, String> nestedTypesCoveredElsewhere() {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("BlockStore", "an opaque block table, not a struct of named fields. What it holds"
                + " is exercised at the GameState level by the blocks mutation.");
        all.put("ItemDict", "a replicated lookup table built once from the handoff. The dict"
                + " mutation swaps a whole different table in, which is the only way it ever"
                + " changes.");
        all.put("BlockProps", "same shape as ItemDict: swapped whole by the blockProps mutation,"
                + " never edited field by field.");
        all.put("ItemGrid", "a derived spatial index rebuilt from items, already excused at the"
                + " GameState level.");
        all.put("CombatEvent", "a per-tick output record on the events buffer, already excused at"
                + " the GameState level and deliberately outside the checksum.");
        all.put("ClickBudget", "a per-tick input allowance, not state. copy() calls clear() on it"
                + " rather than carrying it and the checksum does not read it, both on purpose:"
                + " it is refilled from the frame at the top of every tick, so a rollback that"
                + " restored it would double-spend the clicks it holds.");
        return all;
    }

    private static Set<Class<?>> reachableStateTypes() {
        Set<Class<?>> out = new LinkedHashSet<>();
        collectStateTypes(GameState.class, out);
        return out;
    }

    private static void collectStateTypes(Class<?> owner, Set<Class<?>> out) {
        for (Field f : owner.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }
            for (Class<?> c : elementTypes(f.getGenericType())) {
                if (!isStateStruct(c) || !out.add(c)) {
                    continue;
                }
                collectStateTypes(c, out);
            }
        }
    }

    private static Set<Class<?>> elementTypes(Type t) {
        Set<Class<?>> out = new LinkedHashSet<>();
        if (t instanceof Class<?> c) {
            Class<?> e = c;
            while (e.isArray()) {
                e = e.getComponentType();
            }
            out.add(e);
        } else if (t instanceof ParameterizedType p) {
            out.addAll(elementTypes(p.getRawType()));
            for (Type arg : p.getActualTypeArguments()) {
                out.addAll(elementTypes(arg));
            }
        }
        return out;
    }

    private static boolean isStateStruct(Class<?> c) {
        return c.getName().startsWith(STATE_PACKAGE) && !c.isPrimitive() && !c.isEnum()
                && !c.isInterface();
    }

    private static List<Field> mutableFields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }
            out.add(f);
        }
        return out;
    }

    private static boolean perturb(Object owner, Field f) throws IllegalAccessException {
        f.setAccessible(true);
        Class<?> t = f.getType();
        if (t == boolean.class) {
            f.setBoolean(owner, !f.getBoolean(owner));
            return true;
        }
        if (t == byte.class) {
            f.setByte(owner, (byte) (f.getByte(owner) + 1));
            return true;
        }
        if (t == short.class) {
            f.setShort(owner, (short) (f.getShort(owner) + 1));
            return true;
        }
        if (t == char.class) {
            f.setChar(owner, (char) (f.getChar(owner) + 1));
            return true;
        }
        if (t == int.class) {
            f.setInt(owner, f.getInt(owner) + 1);
            return true;
        }
        if (t == long.class) {
            f.setLong(owner, f.getLong(owner) + 1);
            return true;
        }
        if (t == float.class) {
            f.setFloat(owner, f.getFloat(owner) + 1.0f);
            return true;
        }
        if (t == double.class) {
            f.setDouble(owner, f.getDouble(owner) + 1.0);
            return true;
        }
        if (t.isArray() && t.getComponentType().isPrimitive()) {
            Object array = f.get(owner);
            int len = array == null ? 0 : Array.getLength(array);
            if (len == 0) {
                return false;
            }
            int last = len - 1;
            Class<?> e = t.getComponentType();
            if (e == boolean.class) {
                Array.setBoolean(array, last, !Array.getBoolean(array, last));
            } else if (e == double.class) {
                Array.setDouble(array, last, Array.getDouble(array, last) + 1.0);
            } else if (e == float.class) {
                Array.setFloat(array, last, Array.getFloat(array, last) + 1.0f);
            } else if (e == long.class) {
                Array.setLong(array, last, Array.getLong(array, last) + 1L);
            } else {
                Array.setInt(array, last, Array.getInt(array, last) + 1);
            }
            return true;
        }
        return false;
    }

    private static String describe(Field f) {
        return f.getDeclaringClass().getSimpleName() + "." + f.getName();
    }

    @Test
    void everyNestedReplicatedTypeReachableFromGameStateIsWalkedOrDocumented() {
        Set<String> walked = new LinkedHashSet<>();
        for (Probe p : nestedProbes()) {
            walked.add(p.type().getSimpleName());
        }
        Set<String> excused = nestedTypesCoveredElsewhere().keySet();

        Set<Class<?>> reachable = reachableStateTypes();
        assertTrue(reachable.size() >= 8, "the reflective walk only reached " + reachable.size()
                + " types under " + STATE_PACKAGE + ", so it is not actually walking anything");
        assertTrue(reachable.contains(PlayerState.class),
                "the walk did not even reach PlayerState, which is the type this gate exists for");

        List<String> undecided = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (Class<?> c : reachable) {
            present.add(c.getSimpleName());
            if (!walked.contains(c.getSimpleName()) && !excused.contains(c.getSimpleName())) {
                undecided.add(c.getSimpleName());
            }
        }
        assertTrue(undecided.isEmpty(),
                "GameState now reaches " + undecided + " and nothing here walks its fields."
                        + " Add a probe to nestedProbes() that plants a live instance, or an entry"
                        + " to nestedTypesCoveredElsewhere() naming the coarser mutation that"
                        + " already covers it. The GameState-level map only sees GameState's OWN"
                        + " fields, so a field added to a nested type is invisible to it and a new"
                        + " nested type is a whole struct nobody is checking");

        List<String> stale = new ArrayList<>(new TreeSet<>(walked));
        stale.addAll(excused);
        stale.removeAll(present);
        assertTrue(stale.isEmpty(),
                "this test still names types GameState no longer reaches: " + stale);
    }

    @Test
    void everyFieldOfEveryNestedReplicatedTypeMovesTheChecksum() throws IllegalAccessException {
        Map<String, String> excused = unreplicatedNestedFieldReasons();
        int checked = 0;
        for (Probe probe : nestedProbes()) {
            for (Field f : mutableFields(probe.type())) {
                String name = describe(f);
                if (excused.containsKey(name)) {
                    continue;
                }
                GameState base = seeded();
                probe.plant().apply(base);
                long before = Checksum.of(base);

                GameState moved = seeded();
                Object target = probe.plant().apply(moved);
                assertTrue(perturb(target, f),
                        name + " could not be perturbed reflectively, so this gate cannot say"
                                + " anything about it. Give it a primitive or primitive-array"
                                + " shape, or document it in unreplicatedNestedFieldReasons()");
                assertNotEquals(before, Checksum.of(moved),
                        name + " decides the simulation from inside " + probe.label()
                                + " and is invisible to the checksum. Two peers can hold a"
                                + " different value for it and still agree on every checksum they"
                                + " exchange, which is a desync that reports itself as healthy");
                checked++;
            }
        }
        assertTrue(checked >= 150, "only " + checked + " nested fields were checked, so the walk"
                + " is not reaching the types it claims to");
    }

    @Test
    void everyFieldOfEveryNestedReplicatedTypeSurvivesCopy() throws IllegalAccessException {
        Map<String, String> excused = unreplicatedNestedFieldReasons();
        for (Probe probe : nestedProbes()) {
            for (Field f : mutableFields(probe.type())) {
                String name = describe(f);
                if (excused.containsKey(name)) {
                    continue;
                }
                GameState s = seeded();
                Object target = probe.plant().apply(s);
                if (!perturb(target, f)) {
                    continue;
                }
                assertEquals(Checksum.of(s), Checksum.of(s.copy()),
                        name + " moves the checksum from inside " + probe.label()
                                + " but copy() drops it, so a rollback would restore a different"
                                + " world than the one it saved");
            }
        }
    }

    @Test
    void theNestedWalkIsReadingTheChecksumAndNotAgreeingWithItself() throws IllegalAccessException {
        GameState base = seeded();
        long before = Checksum.of(base);

        GameState moved = seeded();
        Field swap = null;
        for (Field f : mutableFields(ClickBudget.class)) {
            if ("swap".equals(f.getName())) {
                swap = f;
            }
        }
        assertTrue(swap != null, "ClickBudget.swap is the field this control rides on");
        assertTrue(perturb(moved.players[0].clickBudget, swap));

        assertEquals(before, Checksum.of(moved),
                "ClickBudget is the one nested struct deliberately outside the checksum, so"
                        + " perturbing it must NOT move the digest. The same perturb() moves the"
                        + " digest for every other nested field, which is how we know the walk"
                        + " above is reading Checksum.of and not agreeing with itself");
    }

    @Test
    void everyGameStateFieldIsEitherMutatedHereOrDocumentedAsUnreplicated() {
        Set<String> known = new LinkedHashSet<>(replicatedFieldMutations().keySet());
        Set<String> excused = unreplicatedFieldReasons().keySet();

        List<String> undecided = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (Field f : declaredState()) {
            present.add(f.getName());
            if (!known.contains(f.getName()) && !excused.contains(f.getName())) {
                undecided.add(f.getName());
            }
        }

        assertTrue(undecided.isEmpty(),
                "GameState grew " + undecided + " and this test does not know what to do with it."
                        + " Add a mutation to replicatedFieldMutations() if the field decides the"
                        + " simulation and must move the checksum, or an entry to"
                        + " unreplicatedFieldReasons() saying why it may not. Do not delete this"
                        + " assertion: new state nobody checksummed is how a desync ships.");

        List<String> stale = new ArrayList<>();
        for (String name : known) {
            if (!present.contains(name)) {
                stale.add(name);
            }
        }
        for (String name : excused) {
            if (!present.contains(name)) {
                stale.add(name);
            }
        }
        assertTrue(stale.isEmpty(), "this test still names fields GameState no longer has: " + stale);
    }

    @Test
    void everyReplicatedGameStateFieldMovesTheChecksum() {
        long base = Checksum.of(seeded());
        for (Map.Entry<String, Consumer<GameState>> m : replicatedFieldMutations().entrySet()) {
            GameState s = seeded();
            m.getValue().accept(s);
            assertNotEquals(base, Checksum.of(s),
                    m.getKey() + " decides the simulation but is invisible to the checksum");
        }
    }

    @Test
    void everyReplicatedGameStateFieldSurvivesCopy() {
        for (Map.Entry<String, Consumer<GameState>> m : replicatedFieldMutations().entrySet()) {
            GameState s = seeded();
            m.getValue().accept(s);
            assertEquals(Checksum.of(s), Checksum.of(s.copy()),
                    m.getKey() + " moves the checksum but copy() drops it, so a rollback would"
                            + " restore a different world than the one it saved");
        }
    }

    @Test
    void theFourArmourItemIdsAreChecksummedLikeEveryOtherDerivedField() {
        long before = Checksum.of(seeded());
        for (String field : List.of("armorFeetId", "armorLegsId", "armorChestId", "armorHeadId")) {
            GameState s = seeded();
            switch (field) {
                case "armorFeetId" -> s.players[0].armorFeetId = 91;
                case "armorLegsId" -> s.players[0].armorLegsId = 92;
                case "armorChestId" -> s.players[0].armorChestId = 93;
                default -> s.players[0].armorHeadId = 94;
            }
            assertNotEquals(before, Checksum.of(s),
                    field + " is carried by copy() and by the frame-0 codec, and the edge renders"
                            + " the peer's armour from it, so it has to be checksummed too."
                            + " recomputeDerived writes it beside heldItemId and offhandItemId,"
                            + " which both are");
        }
    }

    @Test
    void projectileDamageMovesTheChecksum() {
        GameState a = seeded();
        ProjectileState pa = new ProjectileState();
        pa.id = 1;
        pa.type = ProjectileState.TYPE_ARROW;
        pa.damage = 6.0f;
        a.projectiles.add(pa);

        GameState b = seeded();
        ProjectileState pb = pa.copy();
        pb.damage = 9.0f;
        b.projectiles.add(pb);

        assertNotEquals(Checksum.of(a), Checksum.of(b),
                "arrow damage decides the hit outcome and must be checksummed");
    }

    @Test
    void copyDoesNotAliasTheRoundSeed() {
        GameState s = seeded();
        GameState g = s.copy();
        assertNotSame(s.roundInitial, g.roundInitial, "roundInitial array must not be shared");
        assertNotSame(s.roundInitial[0], g.roundInitial[0], "roundInitial entries must not be shared");
        assertEquals(Checksum.of(s), Checksum.of(g));

        g.roundInitial[0].health = 3.0f;
        assertEquals(20.0f, s.roundInitial[0].health, "mutating a snapshot must not reach the original");
        assertNotEquals(Checksum.of(s), Checksum.of(g));
    }

    @Test
    void copyPreservesANullRoundSeed() {
        GameState s = seeded();
        s.roundInitial = null;
        assertEquals(Checksum.of(s), Checksum.of(s.copy()));
    }

    @Test
    void everyInputComponentHasARecordedHostParityDecision() {
        Map<String, HostParityDecisions.Emission> decisions = HostParityDecisions.inputComponents();
        List<String> undecided = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (RecordComponent c : Input.class.getRecordComponents()) {
            present.add(c.getName());
            if (!decisions.containsKey(c.getName())) {
                undecided.add(c.getName());
            }
        }
        assertTrue(undecided.isEmpty(),
                "Input grew " + undecided + " and no host parity decision was recorded for it."
                        + " Add an entry to HostParityDecisions.inputComponents() naming the"
                        + " builder call that fills it and which of the two frame producers must"
                        + " emit it. A field the sim honours and one host never produces is a"
                        + " modded player and an unmodded player behaving differently for"
                        + " identical physical input, which has now happened with reach, with"
                        + " attack charge, with the swap counter and with the crystal channel");

        List<String> stale = new ArrayList<>();
        for (String name : decisions.keySet()) {
            if (!present.contains(name)) {
                stale.add(name);
            }
        }
        assertTrue(stale.isEmpty(),
                "HostParityDecisions still names Input components that no longer exist: " + stale);
    }

    private static Input roundTrip(Input in) {
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, in);
        b.flip();
        return InputCodec.read(b);
    }

    private static Map<String, Input> inputComponentSamples() {
        Map<String, Input> all = new LinkedHashMap<>();
        all.put("forward", held(0));
        all.put("back", held(1));
        all.put("left", held(2));
        all.put("right", held(3));
        all.put("jump", held(4));
        all.put("sprint", held(5));
        all.put("sneak", held(6));
        all.put("attack", held(7));
        all.put("use", held(8));
        all.put("yaw", new Input(false, false, false, false, false, false, false, false, false,
                90f, 0f, 0));
        all.put("pitch", new Input(false, false, false, false, false, false, false, false, false,
                0f, -45f, 0));
        all.put("heldSlot", Input.NONE.withHeldSlot(7));
        all.put("usePress", Input.NONE.withUsePress(true));
        all.put("offhandUse", Input.NONE.withOffhandUse(true));
        all.put("offhandUsePress", Input.NONE.withOffhandUsePress(true));
        all.put("meleeHit", Input.NONE.withMeleeHit(true));
        all.put("dropItem", Input.NONE.withDrop(true, false));
        all.put("dropStack", Input.NONE.withDrop(true, true));
        all.put("swapHands", Input.NONE.withSwapHands(true));
        all.put("blockAction", Input.NONE.withBlockAction(Input.BLOCK_PLACE, 0, 0, 0));
        all.put("targetX", Input.NONE.withBlockAction(Input.BLOCK_PLACE, 11, 0, 0));
        all.put("targetY", Input.NONE.withBlockAction(Input.BLOCK_PLACE, 0, 12, 0));
        all.put("targetZ", Input.NONE.withBlockAction(Input.BLOCK_PLACE, 0, 0, 13));
        all.put("projectileHit", Input.NONE.withProjectileHit(21));
        all.put("invAction", Input.NONE.withInvAction(Input.INV_MOVE, 0, 0));
        all.put("invSrc", Input.NONE.withInvAction(Input.INV_MOVE, 5, 0));
        all.put("invDst", Input.NONE.withInvAction(Input.INV_MOVE, 0, 6));
        all.put("authority", Input.NONE.withAuthority(Authority.at(1.5, 65.0, -2.5, true)));
        all.put("clicks", Input.NONE.withClicks(Clicks.NONE.withAttack(3)));
        all.put("crystalHit", Input.NONE.withCrystalHit(true, 0, 0, 0));
        all.put("crystalX", Input.NONE.withCrystalHit(true, 31, 0, 0));
        all.put("crystalY", Input.NONE.withCrystalHit(true, 0, 32, 0));
        all.put("crystalZ", Input.NONE.withCrystalHit(true, 0, 0, 33));
        all.put("elytraStart", Input.NONE.withElytraStart(true));
        all.put("synthetic", Input.NONE.withSynthetic(true));
        return all;
    }

    private static Input held(int index) {
        boolean[] k = new boolean[9];
        k[index] = true;
        return new Input(k[0], k[1], k[2], k[3], k[4], k[5], k[6], k[7], k[8], 0f, 0f, 0);
    }

    @Test
    void everyInputComponentIsCarriedByTheWire() {
        Map<String, Input> samples = inputComponentSamples();

        List<String> undecided = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (RecordComponent c : Input.class.getRecordComponents()) {
            present.add(c.getName());
            if (!samples.containsKey(c.getName())) {
                undecided.add(c.getName());
            }
        }
        assertTrue(undecided.isEmpty(),
                "Input grew " + undecided + " and this test does not know how to vary it. A frame"
                        + " field the codec silently drops is dead no matter what either host"
                        + " emits, which is how the swap counter stayed dead on both hosts while"
                        + " every reflective gate here still passed: they covered GameState only");

        List<String> stale = new ArrayList<>();
        for (String name : samples.keySet()) {
            if (!present.contains(name)) {
                stale.add(name);
            }
        }
        assertTrue(stale.isEmpty(), "this test still names Input components that no longer"
                + " exist: " + stale);

        for (Map.Entry<String, Input> e : samples.entrySet()) {
            assertNotEquals(Input.NONE, e.getValue(),
                    e.getKey() + " has a sample that does not actually differ from Input.NONE, so"
                            + " the round trip below would prove nothing about it");
            assertEquals(e.getValue(), roundTrip(e.getValue()),
                    e.getKey() + " does not survive InputCodec. The frame producers can agree"
                            + " perfectly and the simulation still never sees it");
        }
    }

    @Test
    void everyClicksComponentIsCarriedByTheWire() throws Exception {
        for (RecordComponent c : Clicks.class.getRecordComponents()) {
            String builder = "with" + Character.toUpperCase(c.getName().charAt(0))
                    + c.getName().substring(1);
            Method with = Clicks.class.getMethod(builder, int.class);
            Clicks loud = (Clicks) with.invoke(Clicks.NONE, 3);
            assertNotEquals(Clicks.NONE, loud,
                    builder + " did not change the " + c.getName() + " counter");
            assertEquals(loud, roundTrip(Input.NONE.withClicks(loud)).clicks(),
                    c.getName() + " does not survive InputCodec, so both hosts can count it"
                            + " honestly and the simulation still reads zero. The swap counter"
                            + " being dead on both hosts is what this reads for");
        }
    }

    @Test
    void everyClicksComponentHasARecordedHostParityDecision() {
        Map<String, String> decisions = HostParityDecisions.clickCounters();
        List<String> undecided = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (RecordComponent c : Clicks.class.getRecordComponents()) {
            present.add(c.getName());
            if (!decisions.containsKey(c.getName())) {
                undecided.add(c.getName());
            }
        }
        assertTrue(undecided.isEmpty(),
                "Clicks grew " + undecided + " and no host parity decision was recorded for it."
                        + " Add an entry to HostParityDecisions.clickCounters() naming the shared"
                        + " HostFrameContract rule that both producers must derive it from."
                        + " HostProducerParityGateTest then fails until both actually do");

        List<String> stale = new ArrayList<>();
        for (String name : decisions.keySet()) {
            if (!present.contains(name)) {
                stale.add(name);
            }
        }
        assertTrue(stale.isEmpty(),
                "HostParityDecisions still names click counters Clicks no longer has: " + stale);
    }
}
