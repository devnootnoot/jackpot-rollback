package me.nootnoot.sim.tools;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import me.nootnoot.sim.ArenaCodec;

public final class DevPaletteGeometry {

    public static final float DEFAULT_RESISTANCE = 6.0f;
    public static final float OBSIDIAN_RESISTANCE = 1200.0f;
    public static final float INDESTRUCTIBLE_RESISTANCE = 3_600_000.0f;

    private static final double[][] NO_BOXES = new double[0][];
    private static final double CARPET_HEIGHT = 0.0625;
    private static final double PLATE_HEIGHT = 0.0625;
    private static final double PATH_HEIGHT = 0.9375;
    private static final double SNOW_LAYER = 0.125;

    private static final Set<String> AIR = Set.of(
            "air", "cave_air", "void_air");

    private static final Set<String> FLUID = Set.of(
            "water", "lava", "bubble_column");

    private static final Set<String> INDESTRUCTIBLE = Set.of(
            "barrier", "bedrock", "command_block", "chain_command_block", "repeating_command_block",
            "structure_block", "structure_void", "jigsaw", "light", "end_portal", "end_gateway",
            "end_portal_frame", "moving_piston");

    private static final Set<String> OBSIDIAN_LIKE = Set.of(
            "obsidian", "crying_obsidian", "respawn_anchor", "ancient_debris", "netherite_block",
            "enchanting_table", "anvil", "chipped_anvil", "damaged_anvil", "reinforced_deepslate",
            "ender_chest");

    private static final Set<String> DECOR = Set.of(
            "grass", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "seagrass",
            "tall_seagrass", "kelp", "kelp_plant", "sugar_cane", "vine", "glow_lichen",
            "cave_vines", "cave_vines_plant", "weeping_vines", "weeping_vines_plant",
            "twisting_vines", "twisting_vines_plant", "hanging_roots", "torch", "wall_torch",
            "soul_torch", "soul_wall_torch", "redstone_torch", "redstone_wall_torch",
            "redstone_wire", "repeater", "comparator", "tripwire", "tripwire_hook", "lever",
            "rail", "powered_rail", "detector_rail", "activator_rail", "ladder", "cobweb",
            "nether_portal", "fire", "soul_fire", "wheat", "carrots", "potatoes", "beetroots",
            "nether_wart", "sweet_berry_bush", "crimson_roots", "warped_roots", "nether_sprouts",
            "lily_pad", "flower_pot", "sunflower", "lilac", "rose_bush", "peony", "dandelion",
            "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "wither_rose", "torchflower", "pitcher_plant", "spore_blossom",
            "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus", "bamboo_sapling",
            "sculk_vein", "small_dripleaf", "pink_petals", "moss_carpet");

    private DevPaletteGeometry() {
    }

    public static ArenaCodec.PaletteEntry[] of(List<String> palette) {
        ArenaCodec.PaletteEntry[] out = new ArenaCodec.PaletteEntry[palette.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = entryOf(palette.get(i));
        }
        return out;
    }

    public static boolean isAir(String blockData) {
        return AIR.contains(id(blockData));
    }

    public static String describe(ArenaCodec.PaletteEntry[] geometry) {
        int skip = 0;
        int full = 0;
        int partial = 0;
        int decor = 0;
        for (ArenaCodec.PaletteEntry entry : geometry) {
            switch (entry.kind()) {
                case ArenaCodec.KIND_FULL_CUBE -> full++;
                case ArenaCodec.KIND_PARTIAL -> partial++;
                case ArenaCodec.KIND_DECOR -> decor++;
                default -> skip++;
            }
        }
        return geometry.length + " states (" + full + " full, " + partial + " partial, "
                + decor + " decor, " + skip + " skipped)";
    }

    private static ArenaCodec.PaletteEntry entryOf(String blockData) {
        String id = id(blockData);
        if (AIR.contains(id) || FLUID.contains(id)) {
            return new ArenaCodec.PaletteEntry(ArenaCodec.KIND_SKIP, 0f, 0, 0, NO_BOXES);
        }
        float resistance = resistanceOf(id);
        if (DECOR.contains(id) || id.endsWith("_sapling") || id.endsWith("_sign")
                || id.endsWith("_banner") || id.endsWith("_button") || id.endsWith("_torch")) {
            return new ArenaCodec.PaletteEntry(ArenaCodec.KIND_DECOR, resistance, 0, 0, NO_BOXES);
        }
        double[][] boxes = partialBoxes(id, blockData);
        if (boxes != null) {
            return new ArenaCodec.PaletteEntry(ArenaCodec.KIND_PARTIAL, resistance, 0, 0, boxes);
        }
        return new ArenaCodec.PaletteEntry(ArenaCodec.KIND_FULL_CUBE, resistance, 0, 0, NO_BOXES);
    }

    private static double[][] partialBoxes(String id, String blockData) {
        if (id.endsWith("_slab")) {
            String type = property(blockData, "type");
            if ("top".equals(type)) {
                return box(0.0, 0.5, 0.0, 1.0, 1.0, 1.0);
            }
            if ("double".equals(type)) {
                return null;
            }
            return box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
        }
        if (id.endsWith("_carpet") || "moss_carpet".equals(id)) {
            return box(0.0, 0.0, 0.0, 1.0, CARPET_HEIGHT, 1.0);
        }
        if (id.endsWith("_pressure_plate")) {
            return box(0.0, 0.0, 0.0, 1.0, PLATE_HEIGHT, 1.0);
        }
        if ("farmland".equals(id) || "dirt_path".equals(id) || "grass_path".equals(id)) {
            return box(0.0, 0.0, 0.0, 1.0, PATH_HEIGHT, 1.0);
        }
        if ("snow".equals(id)) {
            int layers = intProperty(blockData, "layers", 1);
            if (layers <= 1) {
                return NO_BOXES;
            }
            return box(0.0, 0.0, 0.0, 1.0, (layers - 1) * SNOW_LAYER, 1.0);
        }
        return null;
    }

    private static float resistanceOf(String id) {
        if (INDESTRUCTIBLE.contains(id)) {
            return INDESTRUCTIBLE_RESISTANCE;
        }
        if (OBSIDIAN_LIKE.contains(id)) {
            return OBSIDIAN_RESISTANCE;
        }
        return DEFAULT_RESISTANCE;
    }

    private static double[][] box(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
        return new double[][]{{minX, minY, minZ, maxX, maxY, maxZ}};
    }

    private static String id(String blockData) {
        String value = blockData == null ? "" : blockData.trim().toLowerCase(Locale.ROOT);
        int bracket = value.indexOf('[');
        if (bracket >= 0) {
            value = value.substring(0, bracket);
        }
        int colon = value.indexOf(':');
        return colon >= 0 ? value.substring(colon + 1) : value;
    }

    private static String property(String blockData, String name) {
        int open = blockData.indexOf('[');
        if (open < 0) {
            return null;
        }
        String body = blockData.substring(open + 1, blockData.length() - 1);
        for (String part : body.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(name)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static int intProperty(String blockData, String name, int fallback) {
        String raw = property(blockData, name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
