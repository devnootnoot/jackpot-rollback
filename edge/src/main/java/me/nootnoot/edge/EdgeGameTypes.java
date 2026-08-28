package me.nootnoot.edge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class EdgeGameTypes {

    public static final String IRON = "IRON";
    public static final String DIAMOND = "DIAMOND";
    public static final String POT = "POT";
    public static final String NETHERITE_POT = "NETHERITE_POT";
    public static final String CRYSTAL = "CRYSTAL";

    public static final String UNKNOWN_FALLBACK = CRYSTAL;

    public static final List<String> IDS = List.of(IRON, DIAMOND, POT, NETHERITE_POT, CRYSTAL);

    private static final Map<String, String> RULE_SET_BY_NAME = ruleSetsByName();

    private static final Map<String, Rules> RULES_BY_NAME = rulesByName();

    private static final Map<String, String> ALIAS_TWIN = aliasTwins();

    private EdgeGameTypes() {
    }

    private static Map<String, String> ruleSetsByName() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(IRON, IRON);
        map.put("SWORD", IRON);
        map.put("BOW", IRON);
        map.put("AXE", IRON);

        map.put(DIAMOND, DIAMOND);
        map.put("SMP", DIAMOND);
        map.put("DIAMOND_SMP", DIAMOND);
        map.put("DIAMOND_SMP_NEW", DIAMOND);
        map.put("MONEY_SMP", DIAMOND);
        map.put("UHC", DIAMOND);
        map.put("SHIELDLESS_UHC", DIAMOND);

        map.put(POT, POT);
        map.put(NETHERITE_POT, NETHERITE_POT);

        map.put(CRYSTAL, CRYSTAL);
        map.put("VANILLA", CRYSTAL);
        map.put("HT_VANILLA", CRYSTAL);
        map.put("LT_CART", CRYSTAL);
        map.put("HT_CART", CRYSTAL);
        map.put("CREEPER", CRYSTAL);
        map.put("MACE", CRYSTAL);
        map.put("SPEAR_MACE", CRYSTAL);
        map.put("SPEAR_ELYTRA", CRYSTAL);
        map.put("CUSTOM", CRYSTAL);
        return Map.copyOf(map);
    }

    public static String normalize(String gameType) {
        String key = key(gameType);
        String mapped = RULE_SET_BY_NAME.get(key);
        return mapped != null ? mapped : UNKNOWN_FALLBACK;
    }

    public static boolean isKnown(String gameType) {
        return RULE_SET_BY_NAME.containsKey(key(gameType));
    }

    public static List<String> knownNames() {
        return List.copyOf(RULE_SET_BY_NAME.keySet());
    }

    private static String key(String gameType) {
        return gameType == null ? "" : gameType.trim().toUpperCase(Locale.ROOT);
    }

    public record Rules(boolean vanillaBuild, boolean allowBucket, boolean allowExplosion,
                        boolean explosionParticles, boolean totemParticles, boolean instaReady,
                        boolean potSwordBoost) {

        public String describe() {
            return "build=" + on(vanillaBuild) + " buckets=" + on(allowBucket)
                    + " explosions=" + on(allowExplosion)
                    + " blast-fx=" + on(explosionParticles)
                    + " totem-fx=" + on(totemParticles)
                    + " insta-ready=" + on(instaReady)
                    + " pot-sword-boost=" + on(potSwordBoost);
        }

        private static String on(boolean value) {
            return value ? "on" : "off";
        }
    }

    private static Map<String, Rules> rulesByName() {
        Map<String, Rules> map = new LinkedHashMap<>();
        core(map, "SWORD", false, false, false);
        core(map, "BOW", false, false, false);
        core(map, "AXE", false, false, false);
        core(map, "MACE", true, false, true);
        core(map, "SPEAR_MACE", true, false, true);
        core(map, "SPEAR_ELYTRA", true, false, true);
        core(map, "UHC", true, false, false);
        core(map, "NETHERITE_POT", false, false, false);
        core(map, "POT", false, false, false);
        core(map, "SMP", false, false, false);
        core(map, "DIAMOND_SMP", true, false, false);
        core(map, "DIAMOND_SMP_NEW", true, false, false);
        core(map, "SHIELDLESS_UHC", true, false, false);
        core(map, "LT_CART", true, true, true);
        core(map, "HT_CART", true, true, true);
        core(map, "CREEPER", true, true, true);
        core(map, "VANILLA", true, true, true);
        core(map, "MONEY_SMP", true, true, false);
        core(map, "CUSTOM", true, true, true);
        core(map, "HT_VANILLA", true, true, true);
        return Map.copyOf(map);
    }

    private static void core(Map<String, Rules> map, String name, boolean allowBuilding,
                             boolean allowAllBuildingBreaking, boolean allowExplosion) {
        map.put(name, new Rules(allowAllBuildingBreaking,
                allowBuilding || allowAllBuildingBreaking, allowExplosion, true, true, false,
                POT.equals(name)));
    }

    private static Map<String, String> aliasTwins() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put(IRON, "SWORD");
        map.put(DIAMOND, "DIAMOND_SMP");
        map.put(CRYSTAL, "VANILLA");
        return Map.copyOf(map);
    }

    public static Map<String, Rules> coreRuleTable() {
        return RULES_BY_NAME;
    }

    public static Map<String, String> kitAliasTwins() {
        return ALIAS_TWIN;
    }

    public static String canonical(String gameType) {
        String key = key(gameType);
        return RULE_SET_BY_NAME.containsKey(key) ? key : UNKNOWN_FALLBACK;
    }

    public static Rules rules(String id) {
        String key = key(id);
        Rules direct = RULES_BY_NAME.get(key);
        if (direct != null) {
            return direct;
        }
        String twin = ALIAS_TWIN.get(key);
        if (twin == null) {
            twin = ALIAS_TWIN.get(UNKNOWN_FALLBACK);
        }
        return RULES_BY_NAME.get(twin);
    }

    public static final List<String> EXPLOSION_MATERIALS =
            List.of("END_CRYSTAL", "RESPAWN_ANCHOR", "GLOWSTONE");
    public static final List<String> BUCKET_MATERIALS =
            List.of("WATER_BUCKET", "LAVA_BUCKET");
    public static final List<String> BUILD_MATERIALS =
            List.of("OBSIDIAN", "COBWEB", "COBBLESTONE", "GLOWSTONE");

    private static final String EXPLOSION_EFFECT =
            "never place, never charge and never detonate: the sim refuses the action, so there is"
                    + " no crystal pvp and no anchor bomb at all";
    private static final String BUCKET_EFFECT =
            "never place or pick up, so there is no MLG water and no lava cast";
    private static final String BUILD_EFFECT =
            "still place, but the arena itself is never mineable and never breaks to a blast, so"
                    + " you cannot dig out of an obsidian box or crater the map";

    public record DeadItems(String mechanic, List<String> materials, String kitItems,
                            String effect) {

        public String describe() {
            return mechanic + " OFF -> " + kitItems + " " + effect;
        }
    }

    public static List<DeadItems> deadKitItems(String id) {
        return deadKitItems(id, null);
    }

    public static List<DeadItems> deadKitItems(String id, Collection<String> kitMaterials) {
        Rules rules = rules(id);
        List<DeadItems> dead = new ArrayList<>();
        if (!rules.allowExplosion()) {
            add(dead, "EXPLOSIONS", EXPLOSION_MATERIALS, EXPLOSION_EFFECT, kitMaterials);
        }
        if (!rules.allowBucket()) {
            add(dead, "BUCKETS", BUCKET_MATERIALS, BUCKET_EFFECT, kitMaterials);
        }
        if (!rules.vanillaBuild()) {
            add(dead, "BUILDING", BUILD_MATERIALS, BUILD_EFFECT, kitMaterials);
        }
        return dead;
    }

    private static void add(List<DeadItems> dead, String mechanic, List<String> materials,
                            String effect, Collection<String> kitMaterials) {
        if (kitMaterials == null) {
            dead.add(new DeadItems(mechanic, materials,
                    "every " + list(materials) + " the kit turns out to carry", effect));
            return;
        }
        Set<String> present = new LinkedHashSet<>();
        for (String material : materials) {
            if (contains(kitMaterials, material)) {
                present.add(material);
            }
        }
        if (present.isEmpty()) {
            return;
        }
        List<String> named = List.copyOf(present);
        dead.add(new DeadItems(mechanic, named, "the " + list(named) + " in this kit", effect));
    }

    private static boolean contains(Collection<String> kitMaterials, String material) {
        for (String name : kitMaterials) {
            if (name != null && material.equalsIgnoreCase(name.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String list(List<String> materials) {
        if (materials.size() == 1) {
            return materials.get(0);
        }
        return String.join(", ", materials.subList(0, materials.size() - 1))
                + " and " + materials.get(materials.size() - 1);
    }

    public static String kitLossHeadline(String id) {
        return kitLossHeadline(id, null);
    }

    public static String kitLossHeadline(String id, Collection<String> kitMaterials) {
        List<DeadItems> dead = deadKitItems(id, kitMaterials);
        if (dead.isEmpty()) {
            return "";
        }
        List<String> mechanics = new ArrayList<>();
        for (DeadItems item : dead) {
            mechanics.add(item.mechanic());
        }
        return normalize(id) + " RUNS WITH " + String.join(" AND ", mechanics) + " OFF";
    }

    public static List<String> warningLines(String gameType, Collection<String> kitMaterials,
                                            String source) {
        List<String> lines = new ArrayList<>();
        if (!isKnown(gameType)) {
            lines.add("################################################################");
            lines.add("## UNKNOWN GAME TYPE '" + (gameType == null || gameType.isBlank()
                    ? "" : gameType.trim()) + "' (" + source + ")");
            lines.add("## No rule set is mapped to that name, so this edge fell back to "
                    + UNKNOWN_FALLBACK + ", the");
            lines.add("## only rule set with every mechanic on. Nothing is stripped, but the kit is");
            lines.add("## not the one the name asked for. Known names: " + knownNames());
            lines.add("################################################################");
        }
        List<DeadItems> dead = deadKitItems(gameType, kitMaterials);
        if (dead.isEmpty()) {
            return lines;
        }
        lines.add("################################################################");
        lines.add("## " + kitLossHeadline(gameType, kitMaterials) + " (" + source + ")");
        lines.add("## THESE KIT ITEMS DO NOTHING IN THIS MATCH:");
        for (DeadItems item : dead) {
            lines.add("##   " + item.mechanic() + " off -> " + item.kitItems());
            lines.add("##     they " + item.effect());
            for (String material : item.materials()) {
                lines.add("##       - " + material);
            }
        }
        lines.add("## The items are still handed out and still fill the hotbar, so the kit LOOKS");
        lines.add("## complete while part of it is inert. This is the rule set, not a bug in the");
        lines.add("## sim - " + CRYSTAL + " is the only game type with every mechanic on. Set");
        lines.add("## game-type: " + CRYSTAL + " in config.yml on BOTH edges, or send an"
                + " assignment");
        lines.add("## whose gameType maps to it (mcleagues-core VANILLA and HT_VANILLA do).");
        lines.add("################################################################");
        return lines;
    }

    public static String summary(String id) {
        return switch (normalize(id)) {
            case CRYSTAL -> "full netherite + 192 end crystals + 128 obsidian + respawn anchors"
                    + " + glowstone + totem shulkers + loose totems + elytra + flight 1/2/3 rockets"
                    + " + efficiency V netherite pickaxe + netherite axe + bow + a crossbow that"
                    + " starts LOADED + a second crossbow with Multishot"
                    + " + plain and weakness arrows + wind charges + mace + shield + ender pearls"
                    + " + water and lava buckets + cobwebs"
                    + " + ender chests + 128 golden apples + 16 enchanted golden apples";
            case NETHERITE_POT -> "netherite sword + full netherite + splash healing, harming,"
                    + " speed, strength and fire resistance + a golden apple";
            case POT -> "diamond sword + full diamond + splash healing, harming, speed, strength"
                    + " and fire resistance + a golden apple";
            case DIAMOND -> "diamond sword + full diamond + golden apples + ender pearls + cobblestone";
            default -> "diamond sword + full iron + 64 cobblestone + 8 golden apples";
        };
    }

    public static boolean pots(String id) {
        String norm = normalize(id);
        return POT.equals(norm) || NETHERITE_POT.equals(norm);
    }

    public static boolean totems(String id) {
        return CRYSTAL.equals(normalize(id));
    }
}
