package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import me.nootnoot.sim.Combat;
import me.nootnoot.sim.contract.HostFrameContract;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class HostProducerParityGateTest {

    private static final String CONTRACT = "HostFrameContract.";
    private static final String FACADE = "InputFrameRules.";

    private static final int MAX_RESOLVE_DEPTH = 4;

    private record Producer(String name, Path source, String body) {
    }

    private static Path rollbackRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isRegularFile(p.resolve("settings.gradle"))
                    && Files.isDirectory(p.resolve("sim-core"))
                    && Files.isDirectory(p.resolve("sim-host"))
                    && Files.isDirectory(p.resolve("edge"))) {
                return p;
            }
            p = p.getParent();
        }
        return null;
    }

    private static Path edgeProducerPath(Path root) {
        return root.resolve("edge/src/main/java/me/nootnoot/edge/EdgeInputSource.java");
    }

    private static List<Path> modProducerCandidates(Path root) {
        List<Path> out = new ArrayList<>();
        String tail = "pvphq-rollback-mod/src/main/java/me/nootnoot/rollback/client/McInputSource.java";
        Path up = root.getParent();
        for (int i = 0; up != null && i < 3; i++) {
            out.add(up.resolve(tail));
            up = up.getParent();
        }
        return out;
    }

    private static Path modProducerPath(Path root) {
        for (Path c : modProducerCandidates(root)) {
            if (Files.isRegularFile(c)) {
                return c;
            }
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

    private static Producer read(String name, Path source) throws IOException {
        return new Producer(name, source, strip(Files.readString(source)));
    }

    private static List<Producer> producers() throws IOException {
        Path root = rollbackRoot();
        assertTrue(root != null, "this gate could not find the jackpot-rollback root from "
                + Path.of("").toAbsolutePath() + ", so it cannot see either frame producer and"
                + " would pass without checking anything. Fix the lookup, do not delete the test");
        Path edge = edgeProducerPath(root);
        assertTrue(Files.isRegularFile(edge), "the unmodded frame producer is not at " + edge
                + ". A host parity gate that cannot read a host is worse than no gate: it"
                + " licenses exactly the drift it looks like it prevents");
        Path mod = modProducerPath(root);
        assertTrue(mod != null, "the modded frame producer was not found at any of "
                + modProducerCandidates(root) + ". The mod is a composite build that includes"
                + " jackpot-rollback, so both trees are normally checked out side by side; this"
                + " gate deliberately fails rather than silently skipping the modded half");
        return List.of(read(HostParityDecisions.EDGE, edge), read(HostParityDecisions.MOD, mod));
    }

    private static List<String> argsAt(String body, int openParen) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = openParen; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(' || c == '[') {
                depth++;
                if (depth == 1) {
                    continue;
                }
            } else if (c == ')' || c == ']') {
                depth--;
                if (depth == 0) {
                    out.add(cur.toString().trim());
                    return out;
                }
            } else if (c == ',' && depth == 1) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        return out;
    }

    private static List<List<String>> callsTo(String body, String token) {
        List<List<String>> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = body.indexOf(token, from);
            if (at < 0) {
                return out;
            }
            out.add(argsAt(body, at + token.length() - 1));
            from = at + token.length();
        }
    }

    private static String normalise(String expr) {
        return HostParityDecisions.normalise(expr);
    }

    private static boolean isIdentifier(String expr) {
        return expr.matches("[A-Za-z_$][A-Za-z0-9_$]*");
    }

    private static boolean isZero(String expr) {
        return expr.equals("0");
    }

    private static int matchingParen(String expr, int open) {
        int depth = 0;
        for (int i = open; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String stripOuterParens(String expr) {
        String e = expr;
        while (e.length() > 1 && e.charAt(0) == '(' && matchingParen(e, 0) == e.length() - 1) {
            e = e.substring(1, e.length() - 1).trim();
        }
        return e;
    }

    private static boolean isContractCall(String expr, String method) {
        String e = stripOuterParens(normalise(expr));
        for (String owner : List.of(CONTRACT, FACADE)) {
            String head = owner + method;
            if (!e.startsWith(head)) {
                continue;
            }
            int open = e.indexOf('(', head.length());
            if (open < 0 || !e.substring(head.length(), open).isBlank()) {
                continue;
            }
            if (matchingParen(e, open) == e.length() - 1) {
                return true;
            }
        }
        return false;
    }

    private static int statementEnd(String body, int from) {
        int depth = 0;
        for (int i = from; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ';' && depth <= 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> assignmentsTo(String body, String name) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?<![A-Za-z0-9_$.])" + Pattern.quote(name)
                + "\\s*(\\+\\+|--|[-+*/%&|^]?=(?!=))").matcher(body);
        while (m.find()) {
            String op = m.group(1);
            if (!op.equals("=")) {
                out.add("UNANALYSABLE(" + name + " " + op + ")");
                continue;
            }
            int end = statementEnd(body, m.end());
            if (end < 0) {
                out.add("UNANALYSABLE(" + name + " = ...no statement end)");
                continue;
            }
            out.add(normalise(body.substring(m.end(), end)));
        }
        Matcher pre = Pattern.compile("(\\+\\+|--)\\s*" + Pattern.quote(name)
                + "(?![A-Za-z0-9_$])").matcher(body);
        while (pre.find()) {
            out.add("UNANALYSABLE(" + pre.group(1) + " " + name + ")");
        }
        return out;
    }

    private static void resolve(String body, String expr, Set<String> out, Set<String> seen,
                                int depth) {
        String e = normalise(expr);
        if (!isIdentifier(e) || depth >= MAX_RESOLVE_DEPTH || !seen.add(e)) {
            out.add(e);
            return;
        }
        List<String> assignments = assignmentsTo(body, e);
        if (assignments.isEmpty()) {
            out.add(e);
            return;
        }
        for (String a : assignments) {
            resolve(body, a, out, seen, depth + 1);
        }
    }

    private static Set<String> derivations(String body, String expr) {
        Set<String> out = new LinkedHashSet<>();
        resolve(body, expr, out, new LinkedHashSet<>(), 0);
        return out;
    }

    private static Map<String, Set<String>> counterExpressions(Producer producer,
                                                               List<String> arity) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        RecordComponent[] slots = Clicks.class.getRecordComponents();
        for (List<String> args : callsTo(producer.body(), "new Clicks(")) {
            if (args.size() != slots.length) {
                arity.add(producer.name() + " builds Clicks with " + args.size() + " arguments"
                        + " instead of " + slots.length + ". The four argument constructor is how"
                        + " the swap counter went missing for a whole release: it compiles, it"
                        + " zeroes the fifth channel, and nothing said so");
                continue;
            }
            for (int i = 0; i < args.size(); i++) {
                out.computeIfAbsent(slots[i].getName(), k -> new LinkedHashSet<>())
                        .add(normalise(args.get(i)));
            }
        }
        for (RecordComponent slot : slots) {
            String builder = ".with" + Character.toUpperCase(slot.getName().charAt(0))
                    + slot.getName().substring(1) + "(";
            for (List<String> args : callsTo(producer.body(), builder)) {
                if (args.size() == 1) {
                    out.computeIfAbsent(slot.getName(), k -> new LinkedHashSet<>())
                            .add(normalise(args.get(0)));
                }
            }
        }
        return out;
    }

    private static List<String> auditCounters(Producer producer,
                                              Map<String, HostParityDecisions.Bypass> ledger,
                                              Set<String> consumed) {
        List<String> complaints = new ArrayList<>();
        Map<String, Set<String>> filled = counterExpressions(producer, complaints);
        for (String counter : HostParityDecisions.clickCounters().keySet()) {
            String method = HostParityDecisions.contractMethod(counter);
            Set<String> sites = filled.get(counter);
            if (sites == null || sites.isEmpty()) {
                complaints.add(producer.name() + " never fills the " + counter + " counter");
                continue;
            }
            for (String site : sites) {
                boolean derived = false;
                for (String term : derivations(producer.body(), site)) {
                    if (isZero(term)) {
                        continue;
                    }
                    if (isContractCall(term, method)) {
                        derived = true;
                        continue;
                    }
                    String key = HostParityDecisions.bypassKey(producer.name(), counter, term);
                    if (ledger.containsKey(key)) {
                        consumed.add(key);
                        derived = true;
                        continue;
                    }
                    complaints.add(producer.name() + "." + counter + " reaches the frame as "
                            + site + ", which resolves to " + term + ". That is neither "
                            + CONTRACT + method + "(...) nor a recorded bypass");
                }
                if (!derived) {
                    complaints.add(producer.name() + "." + counter + " reaches the frame as "
                            + site + ", which resolves to a constant zero. A counter one host"
                            + " fills and the other hard zeroes is a rate difference between a"
                            + " modded and an unmodded player for identical physical clicking,"
                            + " and it is how the swap counter stayed dead");
                }
            }
        }
        return complaints;
    }

    @Test
    void bothFrameProducersAreOnDiskSoThisGateCannotPassVacuously() throws IOException {
        for (Producer p : producers()) {
            assertTrue(p.body().contains("new Input("),
                    p.name() + " at " + p.source() + " no longer builds an Input, so this gate is"
                            + " reading the wrong file");
        }
    }

    @Test
    void everyInputComponentIsEmittedByTheProducersItsDecisionNames() throws IOException {
        Map<String, HostParityDecisions.Emission> decisions = HostParityDecisions.inputComponents();
        List<String> wrong = new ArrayList<>();
        for (Producer p : producers()) {
            boolean isEdge = p.name().equals(HostParityDecisions.EDGE);
            for (RecordComponent c : Input.class.getRecordComponents()) {
                HostParityDecisions.Emission d = decisions.get(c.getName());
                if (d == null) {
                    continue;
                }
                boolean expected = isEdge ? d.edge() : d.mod();
                boolean actual = p.body().contains(d.token());
                if (expected != actual) {
                    wrong.add(p.name() + " " + (actual ? "emits" : "does NOT emit") + " "
                            + c.getName() + " via " + d.token() + " but the recorded decision says"
                            + " it " + (expected ? "must" : "must not") + ": " + d.why());
                }
            }
        }
        assertTrue(wrong.isEmpty(),
                "the two frame producers disagree about which fields a frame carries, which is"
                        + " how a modded and an unmodded player end up behaving differently for"
                        + " identical physical input. Either fix the host or, if the asymmetry is"
                        + " intended, change the decision in HostParityDecisions and say why: "
                        + wrong);
    }

    @Test
    void everyClickCounterIsDerivedByTheSharedContractOrRecordedAsABypass() throws IOException {
        Map<String, HostParityDecisions.Bypass> ledger =
                new LinkedHashMap<>(HostParityDecisions.counterBypassLedger());
        Set<String> consumed = new LinkedHashSet<>();
        List<String> complaints = new ArrayList<>();
        for (Producer p : producers()) {
            complaints.addAll(auditCounters(p, ledger, consumed));
        }
        assertTrue(complaints.isEmpty(),
                "a host that computes a click counter by any route other than "
                        + HostFrameContract.class.getSimpleName() + " is free to drift away from"
                        + " the other host, and every counter divergence so far has been exactly"
                        + " that. Route it through the shared class, or record it in"
                        + " HostParityDecisions.counterBypassLedger() with the reason: "
                        + complaints);

        List<String> stale = new ArrayList<>(ledger.keySet());
        stale.removeAll(consumed);
        assertTrue(stale.isEmpty(),
                "the bypass ledger still excuses " + stale + ", which no longer matches the"
                        + " source. A ledger entry is a debt, not a permanent licence: delete it"
                        + " once the host routes through the shared class");
    }

    @Test
    void everyArbitrationRuleIsStillInvokedByBothProducers() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Producer p : producers()) {
            for (Map.Entry<String, String> rule : HostParityDecisions.arbitratedChannels()
                    .entrySet()) {
                boolean called = p.body().contains(CONTRACT + rule.getKey() + "(")
                        || p.body().contains(FACADE + rule.getKey() + "(");
                if (!called) {
                    missing.add(p.name() + " no longer calls " + rule.getKey() + ": "
                            + rule.getValue());
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "this is a PRESENCE check, not a semantic one: it cannot tell whether the shared"
                        + " rule was fed the right arguments, only that the host still asks. That"
                        + " is enough to turn a silent removal into a red build, which is the"
                        + " failure mode these three rules were written for: " + missing);
    }

    private static final int FRAME_ARITY = 12;

    private static final int FRAME_ATTACK_ARG = 7;

    private static final int FRAME_USE_ARG = 8;

    private static List<String> auditFrameBit(Producer p, String site, String bit, String rule) {
        Set<String> terms = derivations(p.body(), site);
        boolean silent = true;
        for (String term : terms) {
            if (!term.equals("false")) {
                silent = false;
                break;
            }
        }
        if (silent) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String term : terms) {
            if (term.equals("false") || isContractCall(term, rule)) {
                continue;
            }
            out.add(p.name() + " builds the frame's " + bit + " bit from " + normalise(site)
                    + ", which resolves to " + term + " and not to " + CONTRACT + rule + "(...)");
        }
        return out;
    }

    @Test
    void everyFrameBitTheSimReadsAsAnActionResolvesToTheSharedRule() throws IOException {
        List<String> wrong = new ArrayList<>();
        int audited = 0;
        for (Producer p : producers()) {
            for (List<String> args : callsTo(p.body(), "new Input(")) {
                if (args.size() != FRAME_ARITY) {
                    continue;
                }
                audited++;
                wrong.addAll(auditFrameBit(p, args.get(FRAME_ATTACK_ARG), "attack", "attackFrame"));
                wrong.addAll(auditFrameBit(p, args.get(FRAME_USE_ARG), "use", "useFrame"));
            }
            for (List<String> args : callsTo(p.body(), ".withOffhandUse(")) {
                if (args.size() != 1) {
                    continue;
                }
                audited++;
                wrong.addAll(auditFrameBit(p, args.get(0), "offhandUse", "offhandUseFrame"));
            }
            for (List<String> args : callsTo(p.body(), ".withOffhandUsePress(")) {
                if (args.size() != 1) {
                    continue;
                }
                audited++;
                wrong.addAll(auditFrameBit(p, args.get(0), "offhandUsePress", "offhandUsePress"));
            }
        }
        assertTrue(audited >= 6,
                "only " + audited + " action bits were audited across both producers, so this scan"
                        + " is not reading the frames it thinks it is. Every host builds at least"
                        + " one twelve argument Input plus the two off-hand builders");
        assertTrue(wrong.isEmpty(),
                "unlike the presence check above this one follows the ARGUMENT back to where the"
                        + " host assigned it, so it sees a producer that still calls the shared"
                        + " rule for something else and hands the frame a fact of its own. A bit"
                        + " that is hard-coded false is allowed - the absence of an input cannot"
                        + " be an advantage - and everything else must resolve to the rule: "
                        + wrong);
    }

    private record Banned(String producer, String token, String why) {
    }

    private static List<Banned> hostWorldQuestions() {
        return List.of(
                new Banned(HostParityDecisions.MOD, "canBeReplaced",
                        "whether a cell takes a block is Combat.placementOpen, asked through"
                                + " HostFrameContract.placeableCell / webbableCell. The client"
                                + " world is a RENDER of the sim that lags it, so a producer that"
                                + " asks it refuses placements the sim would allow and names ones"
                                + " it would not"),
                new Banned(HostParityDecisions.MOD, "getCollisionShape",
                        "the container obstruction test is HostFrameContract.containerOpens over"
                                + " the replicated cells. Asking the client world for the collision"
                                + " shape is the same question with a second answer"),
                new Banned(HostParityDecisions.MOD, "targetIsAnchor",
                        "the mod refused EVERY placement while the crosshair sat on a respawn"
                                + " anchor, a rule the edge never had and the sim does not have."
                                + " What a right click on an anchor means is anchorAction, and"
                                + " nothing else about an anchor may gate a placement"),
                new Banned(HostParityDecisions.MOD, "offhandThrowKind",
                        "the mod's second off-hand item list. Which kinds may fire from the off"
                                + " hand is HostFrameContract.offhandUseKind over the host's own"
                                + " single item classifier; a list that exists only on one host is"
                                + " a list that drifts, and this one already had - it read the"
                                + " FOOD component alone, so an off-hand potion was USE_NONE for a"
                                + " modded player and USE_FOOD for an unmodded one"),
                new Banned(HostParityDecisions.MOD, "RespawnAnchorBlock.CHARGE",
                        "an anchor's charge is GameState.anchors, read through"
                                + " HostFrameContract.anchorCharge. The client block the renderer"
                                + " paints from that map is one frame behind it, so a producer"
                                + " that reads the block reads a charge the other host does not"
                                + " have and turns the same right click into a charge on one"
                                + " host and a detonate on the other"),
                new Banned(HostParityDecisions.MOD, "breakRefused",
                        "a mod-only refusal to mine a shulker the opponent had open. Shared"
                                + " shulkers are concurrent in the sim and the edge never had the"
                                + " rule, so the predicate had already been reduced to a constant"
                                + " false and left wired in - a dead gate on one host only is a"
                                + " divergence waiting for someone to make it return true"),
                new Banned(HostParityDecisions.MOD, "armedArrows",
                        "the mod's second copy of ProjectileState.leftOwner, kept so it could arm"
                                + " its own arrows for a self-hit. The sim already carries"
                                + " leftOwner on the arrow both hosts replicate, and"
                                + " HostFrameContract.projectileClaim reads it"),
                new Banned(HostParityDecisions.EDGE, "CRYSTAL_INFLATE",
                        "the edge's private crystal hitbox. How big a crystal is to a left click,"
                                + " and how far one may be picked from, is"
                                + " HostFrameContract.crystalPickDistanceSq over Combat.crystalBox"
                                + " and the held item's own attack reach. A second box with a"
                                + " hand-tuned 0.3 inflate, cast to the BLOCK reach, is how the"
                                + " two hosts came to detonate the same crystal from two"
                                + " different distances"),
                new Banned(HostParityDecisions.EDGE, "CRYING_OBSIDIAN",
                        "26.2 EndCrystalItem.useOn accepts OBSIDIAN and BEDROCK only. A Material"
                                + " list is a second copy of Combat.crystalPlacementOpen, and this"
                                + " is the copy that drifted"));
    }

    @Test
    void theUnmoddedClientIsHandedTheSimsOwnAttackReach() throws IOException {
        Path root = rollbackRoot();
        assertTrue(root != null, "the reach ledger could not find the jackpot-rollback root");
        Path mirror = root.resolve("edge/src/main/java/me/nootnoot/edge/EdgeStatusMirror.java");
        assertTrue(Files.isRegularFile(mirror), "EdgeStatusMirror is not at " + mirror);
        String body = strip(Files.readString(mirror));
        for (String token : List.of("ENTITY_INTERACTION_RANGE", "simAttackRange")) {
            assertTrue(body.contains(token),
                    "EdgeStatusMirror no longer names " + token + ", so nothing pushes the sim's"
                            + " attack pick onto the vanilla client's own reach attribute. A"
                            + " vanilla client sends INTERACT_ENTITY only inside that attribute,"
                            + " so without the mirror an unmodded player cannot make a melee"
                            + " claim past " + HostParityDecisions.VANILLA_ENTITY_INTERACTION_RANGE
                            + " blocks no matter what the item's reach is, and the spear's"
                            + " " + HostParityDecisions.crossPlayReachLedger().get("spear").maxGap()
                            + " blocks of extra reach become modded-only");
        }
    }

    @Test
    void theCrossPlayReachLedgerStillMatchesTheItemTable() {
        Map<String, HostParityDecisions.ReachBand> ledger =
                HostParityDecisions.crossPlayReachLedger();
        HostParityDecisions.ReachBand plain = ledger.get("plain");
        HostParityDecisions.ReachBand spear = ledger.get("spear");
        assertTrue(plain != null && spear != null, "the reach ledger lost one of its two bands");
        assertTrue(plain.maxGap() == 0.0,
                "a plain weapon's sim reach used to be exactly the vanilla entity interaction"
                        + " range, so nothing had to be mirrored for it. It is now "
                        + plain.simMax() + " against " + plain.unmoddedPick() + ", which is a"
                        + " cross-play gap on EVERY item, not just the spear - re-derive the"
                        + " ledger rather than editing the number");
        assertTrue(spear.simMax() > plain.simMax() && spear.simMin() > 0.0,
                "the spear is in this ledger because it is the one item whose band differs from"
                        + " vanilla at both ends. If that stopped being true the ledger is"
                        + " describing an item that no longer exists");
        assertTrue(spear.maxGap() > 0.0,
                "the spear's maximum reach is what EdgeStatusMirror has to push onto the vanilla"
                        + " attribute; a gap of zero would mean the mirror is now pointless and"
                        + " the test above is guarding nothing");
    }

    @Test
    void neitherProducerAsksItsOwnHostWorldAQuestionTheSimAlreadyAnswers() throws IOException {
        List<String> found = new ArrayList<>();
        for (Producer p : producers()) {
            for (Banned b : hostWorldQuestions()) {
                if (!p.name().equals(b.producer())) {
                    continue;
                }
                if (p.body().contains(b.token())) {
                    found.add(p.name() + " still names " + b.token() + ": " + b.why());
                }
            }
        }
        assertTrue(found.isEmpty(),
                "a producer that answers a world question out of its own host state is a producer"
                        + " that can disagree with the sim about what happened, which is the whole"
                        + " failure mode this architecture exists to avoid: " + found);
    }

    private static Map<String, List<String>> sharedConstants() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Class<?> owner : List.of(Combat.class, HostFrameContract.class, Input.class,
                Clicks.class, Authority.class)) {
            for (Field f : owner.getFields()) {
                if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) {
                    out.computeIfAbsent(f.getName(), k -> new ArrayList<>())
                            .add(owner.getSimpleName() + "." + f.getName());
                }
            }
        }
        return out;
    }

    @Test
    void neitherProducerKeepsItsOwnCopyOfASharedConstant() throws IOException {
        Map<String, List<String>> shared = sharedConstants();
        List<String> forked = new ArrayList<>();
        for (Producer p : producers()) {
            Matcher m = Pattern.compile(
                    "static final [A-Za-z0-9_$<>\\[\\]]+ ([A-Z][A-Z0-9_]*)\\s*=([^;]*);")
                    .matcher(p.body());
            while (m.find()) {
                List<String> owners = shared.get(m.group(1));
                if (owners == null) {
                    continue;
                }
                String init = normalise(m.group(2));
                if (!owners.contains(init)) {
                    forked.add(p.name() + " declares its own " + m.group(1) + " = " + init
                            + " while the shared value lives at " + owners);
                }
            }
        }
        assertTrue(forked.isEmpty(),
                "a host that re-types a shared number instead of importing it agrees with the"
                        + " other host only for as long as nobody edits one of the two copies,"
                        + " and reach is on the list of things that have already drifted this"
                        + " way. This check sees a duplicated NAME; it cannot see two hosts"
                        + " measuring the same quantity differently under different names: "
                        + forked);
    }

    @Test
    void theSimHostFacadeIsAPureDelegateToTheSharedContract() throws IOException {
        Path root = rollbackRoot();
        assertTrue(root != null, "cannot locate the jackpot-rollback root");
        Path facade = root.resolve(
                "sim-host/src/main/java/me/nootnoot/sim/host/InputFrameRules.java");
        assertTrue(Files.isRegularFile(facade), "the host facade is not at " + facade);
        String body = strip(Files.readString(facade));

        int declared = 0;
        Matcher decl = Pattern.compile(
                "public static [A-Za-z0-9_$<>\\[\\]]+ [A-Za-z0-9_$]+\\s*\\(").matcher(body);
        while (decl.find()) {
            declared++;
        }

        List<String> impure = new ArrayList<>();
        Matcher m = Pattern.compile(
                "public static [A-Za-z0-9_$<>\\[\\]]+ ([A-Za-z0-9_$]+)\\s*\\([^)]*\\)\\s*\\{([^}]*)}")
                .matcher(body);
        int seen = 0;
        while (m.find()) {
            seen++;
            String name = m.group(1);
            String inner = normalise(m.group(2));
            if (!inner.startsWith("return " + CONTRACT + name + "(") || !inner.endsWith(");")) {
                impure.add(name + " -> " + inner);
            }
        }
        assertTrue(seen == declared,
                "the facade declares " + declared + " public static methods but this scan matched"
                        + " only " + seen + " of them, so it is silently ignoring "
                        + (declared - seen) + ". A gate that reads less than it claims is the"
                        + " thing being repaired here, not an acceptable outcome");
        assertTrue(seen >= HostParityDecisions.clickCounters().size(),
                "only " + seen + " delegating methods were found in the host facade, so this scan"
                        + " is not reading what it thinks it is");
        assertTrue(impure.isEmpty(),
                "InputFrameRules is the name both hosts import, so it must hold no opinion of its"
                        + " own: every method is a straight delegate to " + CONTRACT + " or the"
                        + " shared decision has two implementations again: " + impure);

        List<String> forked = new ArrayList<>();
        Matcher f = Pattern.compile(
                "public static final [A-Za-z0-9_$<>\\[\\]]+ ([A-Za-z0-9_$]+)\\s*=([^;]*);")
                .matcher(body);
        while (f.find()) {
            String name = f.group(1);
            if (!normalise(f.group(2)).equals(CONTRACT + name)) {
                forked.add(name + " = " + normalise(f.group(2)));
            }
        }
        assertTrue(forked.isEmpty(),
                "a constant the facade re-declares with a value of its own is the same fork as a"
                        + " method with a body of its own, and the hosts compare against these"
                        + " constants: " + forked);
    }

    @Test
    void aComponentTheSimReadsIsProducedByBothHostsOrRecordedAsOneSided() throws IOException {
        Path root = rollbackRoot();
        assertTrue(root != null, "cannot locate the jackpot-rollback root");
        Path simSrc = root.resolve("sim-core/src/main/java/me/nootnoot/sim");
        List<String> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(simSrc)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String file = f.getFileName().toString();
                if (file.equals("Input.java") || file.equals("Clicks.java")
                        || file.equals("InputCodec.java")) {
                    continue;
                }
                sources.add(strip(Files.readString(f)));
            }
        }
        Map<String, HostParityDecisions.Emission> decisions = HostParityDecisions.inputComponents();
        Map<String, String> session = HostParityDecisions.sessionComponents();
        String sessionBody = strip(Files.readString(root.resolve(HostParityDecisions.SESSION)));
        Set<String> components = new LinkedHashSet<>();
        for (RecordComponent c : Input.class.getRecordComponents()) {
            components.add(c.getName());
        }
        List<String> staleSession = new ArrayList<>(session.keySet());
        staleSession.removeAll(components);
        assertTrue(staleSession.isEmpty(),
                "the shared-session exemption still names Input components that no longer exist: "
                        + staleSession);

        List<String> dead = new ArrayList<>();
        List<String> unargued = new ArrayList<>();
        for (RecordComponent c : Input.class.getRecordComponents()) {
            HostParityDecisions.Emission d = decisions.get(c.getName());
            if (d == null) {
                continue;
            }
            boolean read = false;
            for (String s : sources) {
                if (s.contains("." + c.getName() + "()")) {
                    read = true;
                    break;
                }
            }
            if (!read) {
                continue;
            }
            if (!d.edge() && !d.mod()) {
                if (!session.containsKey(c.getName()) || !sessionBody.contains(d.token())) {
                    dead.add(c.getName());
                }
            } else if (!d.bothHosts() && d.why().length() < 80) {
                unargued.add(c.getName());
            }
        }
        assertTrue(dead.isEmpty(),
                "the simulation honours " + dead + " and NEITHER host produces it. That is a"
                        + " capability that is dead in practice, and it is the exact shape of"
                        + " every gap this gate exists for. A field the SHARED session stamps"
                        + " instead is exempt, but only while it is named in"
                        + " HostParityDecisions.sessionComponents() AND " + HostParityDecisions.SESSION
                        + " really carries the builder call the decision names");
        assertTrue(unargued.isEmpty(),
                "the simulation honours " + unargued + " but only one host produces it, and the"
                        + " recorded reason is too thin to be a decision. Write out why the other"
                        + " host cannot or need not produce it, because a modded and an unmodded"
                        + " player behaving differently is a competitive integrity defect unless"
                        + " someone argued that it is not");
    }

    private static Producer synthetic(String body) {
        return new Producer("Synthetic", Path.of("synthetic"), strip(body));
    }

    private static List<String> auditSynthetic(String body) {
        return auditCounters(synthetic(body),
                new LinkedHashMap<>(), new LinkedHashSet<>());
    }

    private static final String HONEST_TAIL =
            "    int useClicks = InputFrameRules.useClicks(blockAction, usePresses);\n"
            + "    int dropClicks = InputFrameRules.dropClicks(dropping, dropPresses);\n"
            + "    int invClicks = InputFrameRules.invClicks(acting, invPresses);\n"
            + "    int swapClicks = InputFrameRules.swapClicks(swapping, swapPresses);\n"
            + "    Input in = base.withClicks(new Clicks(attackClicks, useClicks, dropClicks,"
            + " invClicks, swapClicks));\n";

    @Test
    void theCounterAuditAcceptsAProducerThatRoutesEveryCounterThroughTheSharedContract() {
        String body = "void sample() {\n"
                + "    int attackClicks = InputFrameRules.attackClicks(blockAction, attackPresses);\n"
                + HONEST_TAIL + "}\n";
        assertTrue(auditSynthetic(body).isEmpty(),
                "the repaired audit rejects the shape both hosts are supposed to have. An"
                        + " always-red gate gets deleted, which leaves the same hole as an"
                        + " always-green one: " + auditSynthetic(body));
    }

    @Test
    void theCounterAuditSeesASecondDivergentAssignmentInTheSameProducer() {
        String body = "void sample() {\n"
                + "    int attackClicks = InputFrameRules.attackClicks(blockAction, attackPresses);\n"
                + "    if (frozen) {\n"
                + "        attackClicks = attackPresses;\n"
                + "    }\n"
                + HONEST_TAIL + "}\n";
        List<String> found = auditSynthetic(body);
        assertTrue(found.toString().contains("attackPresses"),
                "the old gate resolved a bare name to its LAST assignment only, so a second"
                        + " branch that fills the same counter by hand was invisible. If this"
                        + " assertion stops failing on the divergent branch the gate is vacuous"
                        + " again: " + found);
    }

    @Test
    void theCounterAuditSeesASecondClicksSiteThatBypassesTheContract() {
        String body = "void sample() {\n"
                + "    int attackClicks = InputFrameRules.attackClicks(blockAction, attackPresses);\n"
                + "    int rawSwings = swingCount.getAndSet(0);\n"
                + "    if (early) {\n"
                + "        return base.withClicks(Clicks.NONE.withAttack(rawSwings));\n"
                + "    }\n"
                + HONEST_TAIL + "}\n";
        List<String> found = auditSynthetic(body);
        assertTrue(found.toString().contains("swingCount.getAndSet(0)"),
                "the old gate kept only the FIRST expression it found per slot, so a second"
                        + " construction site could fill that slot from anywhere: " + found);
    }

    @Test
    void theCounterAuditRejectsAContractCallThatIsOnlyPartOfTheDerivation() {
        for (String forged : List.of(
                "Math.max(1, InputFrameRules.attackClicks(blockAction, attackPresses))",
                "InputFrameRules.attackClicks(blockAction, attackPresses) + bonus",
                "boosted ? InputFrameRules.attackClicks(blockAction, attackPresses) : raw")) {
            String body = "void sample() {\n"
                    + "    int attackClicks = " + forged + ";\n"
                    + HONEST_TAIL + "}\n";
            assertTrue(!auditSynthetic(body).isEmpty(),
                    "the old gate asked only whether the derivation CONTAINED the shared class"
                            + " name, so a host could wrap or adjust the shared answer and still"
                            + " pass. This must be rejected: " + forged);
        }
    }

    @Test
    void theCounterAuditRejectsTheSharedCallForADifferentCounter() {
        String body = "void sample() {\n"
                + "    int attackClicks = InputFrameRules.useClicks(blockAction, attackPresses);\n"
                + HONEST_TAIL + "}\n";
        assertTrue(!auditSynthetic(body).isEmpty(),
                "each counter has its OWN rule in the shared class. Calling a different one still"
                        + " mentions HostFrameContract, which is all the old substring test asked"
                        + " for");
    }

    @Test
    void theCounterAuditRejectsACounterThatCanOnlyEverBeZero() {
        String body = "void sample() {\n"
                + "    int attackClicks = 0;\n"
                + HONEST_TAIL + "}\n";
        assertTrue(auditSynthetic(body).toString().contains("constant zero"),
                "a hard zeroed counter is the shape the swap channel had for a whole release");
    }

    @Test
    void theCounterAuditRefusesToGuessAtACompoundAssignment() {
        String body = "void sample() {\n"
                + "    int attackClicks = InputFrameRules.attackClicks(blockAction, attackPresses);\n"
                + "    attackClicks += bonusSwings;\n"
                + HONEST_TAIL + "}\n";
        assertTrue(auditSynthetic(body).toString().contains("UNANALYSABLE"),
                "a compound assignment is invisible to a plain name equals scan. It has to be"
                        + " reported, not skipped, because skipping is how the old gate passed");
    }

    @Test
    void theCounterAuditRejectsAShortClicksConstructor() {
        String body = "void sample() {\n"
                + "    int attackClicks = InputFrameRules.attackClicks(blockAction, attackPresses);\n"
                + "    int useClicks = InputFrameRules.useClicks(blockAction, usePresses);\n"
                + "    int dropClicks = InputFrameRules.dropClicks(dropping, dropPresses);\n"
                + "    int invClicks = InputFrameRules.invClicks(acting, invPresses);\n"
                + "    Input in = base.withClicks(new Clicks(attackClicks, useClicks, dropClicks,"
                + " invClicks));\n"
                + "}\n";
        assertTrue(auditSynthetic(body).toString().contains("arguments instead of"),
                "the short constructor compiles and zeroes the last channel, which is exactly how"
                        + " the swap counter went missing");
    }
}
