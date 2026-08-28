package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.Container;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CrossPlayDecoderExecutionParityTest {

    private static final String MOD_TAIL =
            "pvphq-rollback-mod/src/main/java/me/nootnoot/rollback/client/MatchSetup.java";
    private static final String CANONICAL_TAIL =
            "sim-core/src/main/java/me/nootnoot/sim/MatchSetupFrame0Decoder.java";

    private static final Set<String> STUBBED_MINECRAFT_TYPES = Set.of(
            "net.minecraft.resources.Identifier",
            "net.minecraft.core.registries.BuiltInRegistries",
            "net.minecraft.world.item.Item",
            "net.minecraft.world.item.Items");

    private static final Map<String, String> STUB_SOURCES = Map.of(
            "net/minecraft/resources/Identifier.java",
            """
            package net.minecraft.resources;

            public final class Identifier {
                private final String value;

                private Identifier(String value) {
                    this.value = value;
                }

                public static Identifier tryParse(String raw) {
                    return raw == null ? null : new Identifier(raw);
                }

                public String value() {
                    return value;
                }
            }
            """,
            "net/minecraft/world/item/Item.java",
            """
            package net.minecraft.world.item;

            public class Item {
                public final String name;

                public Item(String name) {
                    this.name = name;
                }
            }
            """,
            "net/minecraft/world/item/Items.java",
            """
            package net.minecraft.world.item;

            public final class Items {
                public static final Item AIR = new Item("minecraft:air");

                private Items() {
                }
            }
            """,
            "net/minecraft/core/registries/BuiltInRegistries.java",
            """
            package net.minecraft.core.registries;

            import java.util.LinkedHashMap;
            import java.util.Map;
            import net.minecraft.resources.Identifier;
            import net.minecraft.world.item.Item;
            import net.minecraft.world.item.Items;

            public final class BuiltInRegistries {

                public static final class ItemRegistry {
                    private final Map<String, Item> byName = new LinkedHashMap<>();
                    private final Map<Item, Integer> ids = new LinkedHashMap<>();

                    public Item getValue(Identifier id) {
                        Item item = byName.get(id.value());
                        return item == null ? Items.AIR : item;
                    }

                    public int getId(Item item) {
                        Integer id = ids.get(item);
                        return id == null ? 0 : id.intValue();
                    }

                    public void register(String name, int id) {
                        Item item = new Item(name);
                        byName.put(name, item);
                        ids.put(item, Integer.valueOf(id));
                    }
                }

                public static final ItemRegistry ITEM = new ItemRegistry();

                static {
                    ITEM.register("minecraft:obsidian", 49);
                    ITEM.register("minecraft:glowstone", 89);
                    ITEM.register("minecraft:cobweb", 30);
                }

                private BuiltInRegistries() {
                }
            }
            """);

    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?<![A-Za-z0-9_$.])(?:p|state(?:\\s*\\.\\s*players\\s*\\[[^\\]]*\\])?)"
                    + "\\s*\\.\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*"
                    + "(?:\\[[^\\]]*\\]\\s*)?(?:=(?!=)|\\.\\s*(?:put|clear)\\s*\\()");

    private static final Pattern MINECRAFT_REFERENCE =
            Pattern.compile("net\\.minecraft(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private static final double GROUND_Y = 64.5;
    private static final int EDGE_SLOT = 0;
    private static final int MOD_SLOT = 1;
    private static final int ROUNDS_TARGET = 5;
    private static final int HEAD_SLOT = 39;
    private static final int LEGACY_HOTBAR_BYTES = 41;
    private static final int SHULKER_CONTAINER = 1;
    private static final int ARROW_CONTAINER = 4;

    private static final List<URLClassLoader> LOADERS = new ArrayList<>();

    private static Path work;
    private static Path stubClasses;
    private static String modSource;
    private static Path modPath;
    private static Path canonicalPath;

    @BeforeAll
    static void prepare() throws IOException, URISyntaxException {
        Path root = rollbackRoot();
        assertTrue(root != null, "this gate could not find the jackpot-rollback root from "
                + Path.of("").toAbsolutePath() + ", so it can execute neither decoder");
        canonicalPath = root.resolve(CANONICAL_TAIL);
        assertTrue(Files.isRegularFile(canonicalPath),
                "the canonical setup decoder is not at " + canonicalPath);
        modPath = locateModDecoder(root);
        modSource = Files.readString(modPath);

        work = Files.createTempDirectory("crossplay-decoder-parity");
        stubClasses = work.resolve("stubs");
        Files.createDirectories(stubClasses);
        Path stubSrc = work.resolve("stub-src");
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, String> e : STUB_SOURCES.entrySet()) {
            Path file = stubSrc.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue());
            files.add(file.toString());
        }
        compile(files, simCoreClasses(), stubClasses, "the minecraft stubs");
    }

    @AfterAll
    static void cleanUp() throws IOException {
        for (URLClassLoader loader : LOADERS) {
            loader.close();
        }
        LOADERS.clear();
        if (work == null || !Files.exists(work)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(work)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    p.toFile().deleteOnExit();
                }
            });
        }
    }

    @Test
    void theTwoDecodersTurnOneSetupBlobIntoChecksumEqualFrame0States() throws Exception {
        byte[] blob = crossPlayBlob();
        MatchSetupFrame0Decoder.Frame0 canonical = MatchSetupFrame0Decoder.decode(blob);
        Object mod = decodeWithModSource(modSource, "asShipped", blob);
        GameState modState = stateOf(mod);

        assertEquals(Checksum.of(canonical.state()), Checksum.of(modState),
                "the modded client and the edge host decoded the SAME setup blob into frame 0"
                        + " states with different checksums, so every cross-play match desyncs at"
                        + " tick 0. Canonical decoder: " + canonicalPath + "; modded decoder: "
                        + modPath);
        assertArrayEquals(GameStateFrame0Codec.encode(canonical.state()),
                GameStateFrame0Codec.encode(modState),
                "the two decoders agree on the checksum but not on the frame 0 bytes, which means"
                        + " some field diverged inside a checksum collision or outside the"
                        + " checksum's reach");
        assertTrue(modState.edgeHosted[EDGE_SLOT], "slot 0 is the unmodded, edge hosted player");
        assertFalse(modState.edgeHosted[MOD_SLOT], "slot 1 is the modded client");
    }

    @Test
    void theTwoDecodersAlsoAgreeOnEverythingCarriedBesideTheGameState() throws Exception {
        byte[] blob = crossPlayBlob();
        MatchSetupFrame0Decoder.Frame0 canonical = MatchSetupFrame0Decoder.decode(blob);
        Object mod = decodeWithModSource(modSource, "asShipped", blob);

        Arena canonicalArena = new Arena(canonical.arenaGroundY(), canonical.arenaBoxes());
        assertEquals(ArenaHash.of(canonicalArena), ArenaHash.of((Arena) field(mod, "arena")),
                "ground-y and the arena boxes are decoded off the same bytes, so the two hosts"
                        + " must build the same collision world");
        assertArrayEquals(canonical.selectedSlot(), (int[]) field(mod, "selectedSlots"),
                "the two hosts disagree on which hotbar slot each player starts on");

        byte[][][] canonicalInventory = canonical.inventory();
        Object[] modRows = (Object[]) field(mod, "inventory");
        for (int i = 0; i < 2; i++) {
            Object[] row = (Object[]) modRows[i];
            assertEquals(canonicalInventory[i].length, row.length,
                    "inventory slot count differs for player " + i);
            for (int s = 0; s < row.length; s++) {
                assertArrayEquals(canonicalInventory[i][s], (byte[]) call(row[s], "nbt"),
                        "inventory nbt differs for player " + i + " slot " + s);
            }
        }

        Object[] modIdentities = (Object[]) field(mod, "identities");
        for (int i = 0; i < 2; i++) {
            MatchSetupFrame0Decoder.Identity want = canonical.identities()[i];
            Object got = modIdentities[i];
            assertEquals(want.uuid(), call(got, "uuid"), "uuid differs for player " + i);
            assertEquals(want.name(), call(got, "name"), "name differs for player " + i);
            assertEquals(want.skinValue(), call(got, "skinValue"),
                    "skin value differs for player " + i);
            assertEquals(want.skinSignature(), call(got, "skinSignature"),
                    "skin signature differs for player " + i);
            assertEquals(want.chatPrefixJson(), call(got, "chatPrefixJson"),
                    "chat prefix differs for player " + i);
        }
    }

    @Test
    void thisBlobMovesEveryFieldTheCanonicalDecoderWritesOffItsDefault() throws Exception {
        Set<String> written = fieldsWrittenByTheCanonicalDecoder();
        GameState decoded = MatchSetupFrame0Decoder.decode(crossPlayBlob()).state();
        GameState fresh = new GameState();

        Set<String> unresolved = new TreeSet<>();
        Set<String> untouched = new TreeSet<>();
        for (String name : written) {
            Field onState = fieldOf(GameState.class, name);
            Field onPlayer = fieldOf(PlayerState.class, name);
            if (onState == null && onPlayer == null) {
                unresolved.add(name);
                continue;
            }
            boolean moved = onState != null
                    && !deepEquals(onState.get(decoded), onState.get(fresh));
            for (int i = 0; i < 2 && !moved; i++) {
                moved = onPlayer != null
                        && !deepEquals(onPlayer.get(decoded.players[i]),
                                onPlayer.get(fresh.players[i]));
            }
            if (!moved) {
                untouched.add(name);
            }
        }

        assertTrue(unresolved.isEmpty(),
                "the field scanner read " + unresolved + " out of " + canonicalPath + " but none"
                        + " of those are public fields of GameState or PlayerState, so the scanner"
                        + " has drifted away from the source it reads");
        assertTrue(untouched.isEmpty(),
                "the cross-play blob in this test leaves " + untouched + " at its default value,"
                        + " so a modded decoder that never wrote " + untouched + " would still"
                        + " produce a checksum-equal frame 0 here and this whole comparison would"
                        + " be blind to it. Give those fields a distinctive value in"
                        + " crossPlayBlob()");
    }

    @Test
    void everyFieldTheCanonicalDecoderWritesIsVisibleToTheChecksum() throws Exception {
        Set<String> written = fieldsWrittenByTheCanonicalDecoder();
        byte[] blob = crossPlayBlob();
        Set<String> invisible = new TreeSet<>();
        for (String name : written) {
            GameState decoded = MatchSetupFrame0Decoder.decode(blob).state();
            GameState fresh = new GameState();
            long before = Checksum.of(decoded);
            Field onState = fieldOf(GameState.class, name);
            if (onState != null) {
                revert(onState, decoded, fresh);
            }
            Field onPlayer = fieldOf(PlayerState.class, name);
            if (onPlayer != null) {
                for (int i = 0; i < 2; i++) {
                    revert(onPlayer, decoded.players[i], fresh.players[i]);
                    if (decoded.roundInitial != null) {
                        revert(onPlayer, decoded.roundInitial[i], fresh.players[i]);
                    }
                }
            }
            if (Checksum.of(decoded) == before) {
                invisible.add(name);
            }
        }
        assertTrue(invisible.isEmpty(),
                "Checksum.of cannot see " + invisible + ", so the two hosts can decode different"
                        + " values into those fields, agree on every checksum they exchange, and"
                        + " still diverge the moment the sim reads one of them. Either put the"
                        + " field in the checksum or stop seeding it in the decoder");
    }

    @Test
    void aModdedDecoderThatDivergesIsCaughtByThisComparison() throws Exception {
        byte[] blob = crossPlayBlob();
        long canonical = Checksum.of(MatchSetupFrame0Decoder.decode(blob).state());
        String[][] mutations = {
            {"noCrossbowSeeding", "MatchSetupFrame0Decoder.seedChargedCrossbows(state, p);", ""},
            {"noCrossbowConsumed",
                "p.slotCrossbowConsumed[slot] = dict.crossbowCharged(entry);", ""},
            {"uncharged", "p.attackTicker = 100;", "p.attackTicker = 0;"},
            {"noHostKind", "state.edgeHosted[0] = b.get() != 0;", "b.get();"},
            {"noBreakRules", "state.breakableItemIds = breakableItemIds;", ""},
            {"noPlaceRules", "state.placeableItemIds = placeableItemIds;", ""},
        };

        for (String[] mutation : mutations) {
            String anchor = mutation[1];
            assertEquals(1, occurrences(modSource, anchor),
                    "the negative control '" + mutation[0] + "' anchors on \"" + anchor + "\","
                            + " which appears " + occurrences(modSource, anchor) + " times in "
                            + modPath + ". Re-point the anchor at the line that moved, or this"
                            + " control silently stops proving that the comparison detects a"
                            + " divergent modded decoder");
            String mutated = modSource.replace(anchor, mutation[2]);
            long divergent;
            try {
                divergent = Checksum.of(stateOf(decodeWithModSource(mutated, mutation[0], blob)));
            } catch (InvocationTargetException refused) {
                continue;
            }
            assertTrue(divergent != canonical,
                    "a modded decoder mutated by '" + mutation[0] + "' still produced a"
                            + " checksum-equal frame 0, so this comparison would not have caught"
                            + " that divergence in the shipped client either");
        }
    }

    @Test
    void theModdedDecoderTouchesNoMinecraftTypeAtAll() {
        Set<String> referenced = new TreeSet<>();
        Matcher m = MINECRAFT_REFERENCE.matcher(modSource);
        while (m.find()) {
            referenced.add(m.group());
        }
        assertTrue(referenced.isEmpty(),
                "the modded decoder reaches for " + referenced + ". A client registry lookup in"
                        + " the setup path resolves against THIS client's ids, and the two peers"
                        + " can be on different game versions, so the same blob would decode to"
                        + " two different id sets. The setup blob carries canonical ids for"
                        + " exactly that reason: keep the registry out of MatchSetup.decode");
    }

    @Test
    void bothDecodersReadTheBlockRuleListsIntoTheSameGameStateFields() throws Exception {
        Object mod = decodeWithModSource(modSource, "asShipped", crossPlayBlob());
        GameState modState = (GameState) field(mod, "initialState");
        GameState canonical = MatchSetupFrame0Decoder.decode(crossPlayBlob()).state();

        assertArrayEquals(new int[]{49, 89}, canonical.breakableItemIds,
                "the wire carried 49, 89 and a duplicate 49; the decoder has to sort and dedupe"
                        + " so binary search works and so two hosts that were handed the same set"
                        + " in a different order still checksum the same");
        assertArrayEquals(new int[]{30}, canonical.placeableItemIds);
        assertArrayEquals(canonical.breakableItemIds, modState.breakableItemIds,
                "the modded client and the edge have to end up with the same block rules or the"
                        + " unmodded player can break what the modded one cannot");
        assertArrayEquals(canonical.placeableItemIds, modState.placeableItemIds);
    }

    private static Set<String> fieldsWrittenByTheCanonicalDecoder() throws IOException {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = ASSIGNMENT.matcher(stripCommentsAndLiterals(Files.readString(canonicalPath)));
        while (m.find()) {
            out.add(m.group(1));
        }
        assertTrue(out.size() >= 30, "the canonical decoder was parsed down to only " + out.size()
                + " written fields (" + out + "), which cannot be right for a decoder that seeds a"
                + " whole GameState");
        assertTrue(out.contains("slotCrossbowEntry"),
                "slotCrossbowEntry is the field whose absence from the modded decoder desynced"
                        + " cross-play at frame 0; confirm on purpose before relaxing this line");
        return out;
    }

    private static Object decodeWithModSource(String source, String tag, byte[] blob)
            throws Exception {
        Path out = work.resolve(tag).resolve("classes");
        if (!Files.isDirectory(out)) {
            Path file = work.resolve(tag)
                    .resolve("src/me/nootnoot/rollback/client/MatchSetup.java");
            Files.createDirectories(file.getParent());
            Files.createDirectories(out);
            Files.writeString(file, source);
            compile(List.of(file.toString()),
                    simCoreClasses() + File.pathSeparator + stubClasses, out,
                    "the modded decoder (" + tag + ")");
        }
        URLClassLoader loader = new URLClassLoader(
                new URL[]{out.toUri().toURL(), stubClasses.toUri().toURL()},
                CrossPlayDecoderExecutionParityTest.class.getClassLoader());
        LOADERS.add(loader);
        Class<?> setup = Class.forName("me.nootnoot.rollback.client.MatchSetup", true, loader);
        Method decode = setup.getMethod("decode", byte[].class);
        return decode.invoke(null, (Object) blob);
    }

    private static void compile(List<String> files, String classpath, Path out, String what) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "there is no java compiler in this jvm, so " + what
                + " cannot be executed against the canonical decoder. Run the build on a jdk");
        List<String> argv = new ArrayList<>(
                List.of("-nowarn", "-classpath", classpath, "-d", out.toString()));
        argv.addAll(files);
        int rc = compiler.run(null, null, System.err, argv.toArray(new String[0]));
        assertEquals(0, rc, what + " did not compile against sim-core plus the minecraft stubs;"
                + " the compiler output is above");
    }

    private static String simCoreClasses() throws URISyntaxException {
        return Path.of(GameState.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toString();
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

    private static Path locateModDecoder(Path root) {
        List<Path> candidates = new ArrayList<>();
        Path up = root.getParent();
        for (int i = 0; up != null && i < 3; i++) {
            candidates.add(up.resolve(MOD_TAIL));
            up = up.getParent();
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        assertTrue(false, "the modded setup decoder was not found at any of " + candidates
                + ". Both trees are normally checked out side by side; this gate fails rather than"
                + " silently skipping the half of cross-play it exists to prove");
        return null;
    }

    private static int occurrences(String haystack, String needle) {
        int n = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            n++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return n;
    }

    private static Field fieldOf(Class<?> owner, String name) {
        try {
            return owner.getField(name);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static void revert(Field f, Object target, Object source) throws Exception {
        Object from = f.get(source);
        if (f.getType().isArray() && from != null) {
            Object to = f.get(target);
            if (to == null || Array.getLength(from) != Array.getLength(to)) {
                f.set(target, from);
                return;
            }
            int n = Math.min(Array.getLength(from), Array.getLength(to));
            for (int i = 0; i < n; i++) {
                Array.set(to, i, Array.get(from, i));
            }
            return;
        }
        if (Map.class.isAssignableFrom(f.getType())) {
            ((Map<?, ?>) f.get(target)).clear();
            return;
        }
        f.set(target, from);
    }

    private static boolean deepEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.getClass().isArray()) {
            int n = Array.getLength(a);
            if (n != Array.getLength(b)) {
                return false;
            }
            for (int i = 0; i < n; i++) {
                if (!deepEquals(Array.get(a, i), Array.get(b, i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static GameState stateOf(Object setup) throws Exception {
        return (GameState) field(setup, "initialState");
    }

    private static Object field(Object owner, String name) throws Exception {
        return owner.getClass().getField(name).get(owner);
    }

    private static Object call(Object owner, String name) throws Exception {
        return owner.getClass().getMethod(name).invoke(owner);
    }

    private static String stripCommentsAndLiterals(String src) {
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

    private static byte[] crossPlayBlob() {
        GameState kit = new GameState();
        ItemDict.Builder b = new ItemDict.Builder();
        int sword = b.add(979, 1, 2031, ItemDict.FLAG_SWORD, Combat.USE_NONE, 7.5f, 1.85f, 3, 5, 0,
                0, ItemDict.packTool(5, 0, ItemDict.TOOL_SWORD, false),
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        int crystal = b.add(1096, 64, 0, ItemDict.FLAG_END_CRYSTAL, Combat.USE_NONE, 1f, 4f, 0, 0,
                0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int obsidian = b.add(405, 64, 0, ItemDict.FLAG_BLOCK, Combat.USE_NONE, 1f, 4f, 0, 0, 0, 0,
                0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        int crossbow = b.add(1150, 1, 465,
                ItemDict.FLAG_CROSSBOW | ItemDict.FLAG_CROSSBOW_CHARGED, Combat.USE_CROSSBOW,
                1f, 4f, 0, 0, 0, ItemDict.packRanged(1, 0, 0, false, 0), 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        int helmet = b.add(748, 1, 407, 0, Combat.USE_NONE, 1f, 4f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 3, 2f, 0.1f, 4, 3, 2, 1, 1, ItemDict.EQUIP_HEAD, -1);
        int shulker = b.add(621, 1, 0, ItemDict.FLAG_BLOCK | ItemDict.FLAG_SHULKER,
                Combat.USE_NONE, 1f, 4f, 0, 0, 0, 0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f,
                0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, SHULKER_CONTAINER);
        int arrow = b.add(1071, 64, 0, ItemDict.FLAG_ARROW_PLAIN, Combat.USE_NONE, 1f, 4f, 0, 0, 0,
                0, 0, 0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0,
                ItemDict.EQUIP_NONE, -1);
        int totem = b.add(1132, 1, 0, ItemDict.FLAG_TOTEM, Combat.USE_NONE, 1f, 4f, 0, 0, 0, 0, 0,
                0, 0f, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0, 0, 0, 0, 0, ItemDict.EQUIP_NONE, -1);
        kit.dict = b.build();

        BlockProps.Builder props = new BlockProps.Builder();
        props.add(405, 50f, 1200f, 405, 3, 1, true);
        props.add(292, 0.3f, 0.3f, 292, 0, 0, false);
        props.add(118, 4f, 4f, 899, 0, 2, false);
        kit.blockProps = props.build();

        Container totems = new Container();
        for (int cell = 0; cell < Container.CELLS; cell++) {
            totems.entry[cell] = totem;
            totems.count[cell] = 1;
        }
        Container arrows = new Container();
        arrows.entry[0] = arrow;
        arrows.count[0] = 32;
        arrows.entry[7] = crystal;
        arrows.count[7] = 9;
        kit.containers.put(SHULKER_CONTAINER, totems);
        kit.containers.put(ARROW_CONTAINER, arrows);
        kit.nextContainerId = ARROW_CONTAINER + 1;
        kit.cobwebItemId = 118;
        kit.stringItemId = 899;
        kit.obsidianItemId = 405;
        kit.cobblestoneItemId = 22;
        kit.mudItemId = 33;
        kit.glowstoneItemId = 292;
        kit.glowstoneDustItemId = 293;
        for (int i = 0; i < 2; i++) {
            PlayerState p = kit.players[i];
            put(p, 0, sword, 1, 12);
            put(p, 1, crystal, 64, 0);
            put(p, 2, obsidian, 64, 0);
            put(p, 3, crossbow, 1, 5);
            put(p, 5, shulker, 1, 0);
            put(p, 6, arrow, i == EDGE_SLOT ? 64 : 16, 0);
            put(p, ItemDict.OFF_HAND, totem, 1, 0);
            put(p, HEAD_SLOT, helmet, 1, 3);
        }
        kit.edgeHosted[EDGE_SLOT] = true;
        kit.edgeHosted[MOD_SLOT] = false;

        byte[] sections = MatchSetupFrame0Encoder.sections(kit);
        ByteBuffer w = ByteBuffer.allocate(1 << 19);
        w.putLong(0x1122334455667788L);
        w.put((byte) MOD_SLOT);
        w.putShort((short) 4);
        w.put(new byte[]{9, 8, 7, 6});
        putString(w, "relay.example.internal");
        w.putInt(7777);
        w.putInt(ROUNDS_TARGET);
        w.put((byte) 1);
        w.put((byte) 0);
        w.put((byte) 0);
        w.put((byte) 0);
        w.put((byte) 1);
        w.put((byte) 1);
        w.put((byte) 0);
        w.put((byte) 1);
        w.putInt(3);
        w.putInt(49);
        w.putInt(89);
        w.putInt(49);
        w.putInt(1);
        w.putInt(30);
        w.putDouble(GROUND_Y);
        w.putInt(2);
        double[][] boxes = {
            {-4.0, GROUND_Y, -4.0, 4.0, GROUND_Y + 3.0, -3.0},
            {-4.0, GROUND_Y, 3.0, 4.0, GROUND_Y + 3.0, 4.0},
        };
        for (double[] box : boxes) {
            for (double v : box) {
                w.putDouble(v);
            }
        }
        w.putDouble(3.5);
        w.putDouble(-7.25);
        w.putDouble(42.0);
        w.put((byte) 1);
        putPlayer(w, -1.5, 90f, 3.5f, "Unmodded", EDGE_SLOT);
        putPlayer(w, 2.25, -90f, -11f, "Modded", MOD_SLOT);
        w.put((byte) 1);
        w.putInt(16);
        w.putInt(-8);
        w.putInt(32);
        w.put(sections);

        byte[] out = new byte[w.position()];
        w.flip();
        w.get(out);
        return out;
    }

    private static void put(PlayerState p, int slot, int entry, int count, int damage) {
        p.slotEntry[slot] = entry;
        p.slotCount[slot] = count;
        p.slotDamage[slot] = damage;
    }

    private static void putPlayer(ByteBuffer w, double x, float yaw, float pitch, String name,
                                  int slot) {
        boolean edge = slot == EDGE_SLOT;
        w.putDouble(x);
        w.putDouble(GROUND_Y);
        w.putDouble(edge ? 0.5 : -2.75);
        w.putFloat(yaw);
        w.putFloat(pitch);
        w.putFloat(edge ? 20f : 18.5f);
        w.putFloat(edge ? 1f : 1.25f);
        w.putFloat(edge ? 4.0f : 1.9f);
        w.putInt(slot);
        w.putFloat(edge ? 8f : 4f);
        w.putFloat(edge ? 0f : 0.25f);
        w.putInt(16);
        w.putLong(0x0102030405060708L + slot);
        w.putLong(name.hashCode());
        putString(w, name);
        putString(w, "skin-value-" + name);
        putString(w, "skin-signature-" + name);
        putString(w, "{\"text\":\"" + name + "\"}");
        w.putInt(edge ? 0 : 3);
        w.putFloat(edge ? 20f : 17f);
        w.putFloat(edge ? 5f : 2.5f);
        w.putInt(edge ? 4 : 2);
        w.putInt(edge ? 3 : 1);
        w.putFloat(edge ? 2f : 1f);
        for (int i = 0; i < 9 * LEGACY_HOTBAR_BYTES; i++) {
            w.put((byte) (i & 0x7F));
        }
        w.putInt(64);
        w.put((byte) 0);
        for (int s = 0; s < MatchSetupFrame0Decoder.INVENTORY_SLOTS; s++) {
            byte[] nbt = new byte[(s % 5) + slot];
            for (int k = 0; k < nbt.length; k++) {
                nbt[k] = (byte) (s * 31 + k + slot);
            }
            w.putInt(nbt.length);
            w.put(nbt);
        }
        if (edge) {
            w.putInt(2);
            w.putInt(1);
            w.putInt(1);
            w.putInt(600);
            w.putInt(5);
            w.putInt(0);
            w.putInt(1200);
        } else {
            w.putInt(1);
            w.putInt(10);
            w.putInt(0);
            w.putInt(300);
        }
    }

    private static void putString(ByteBuffer w, String s) {
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        w.putShort((short) raw.length);
        w.put(raw);
    }
}
