package me.nootnoot.edge;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

public final class EdgeItemIds {

    private static final String TABLE = "/pvphq/canonical_items.tsv";

    private static final Map<Material, Integer> TO_CANONICAL = new HashMap<>();
    private static final Map<Integer, Material> FROM_CANONICAL = new HashMap<>();

    private static volatile boolean loaded;
    private static volatile int mapped;
    private static volatile int unresolved;

    private EdgeItemIds() {
    }

    public static synchronized void load(Logger log) {
        if (loaded) {
            return;
        }
        loaded = true;
        Map<String, Material> byKey = new HashMap<>();
        for (Material material : Material.values()) {
            if (material.isLegacy()) {
                continue;
            }
            NamespacedKey key = keyOf(material);
            if (key != null) {
                byKey.putIfAbsent(key.toString(), material);
            }
        }
        try (InputStream in = EdgeItemIds.class.getResourceAsStream(TABLE)) {
            if (in == null) {
                if (log != null) {
                    log.severe("canonical item table " + TABLE + " is missing from the edge jar -"
                            + " block placement, mining drops and arrow pickup are all disabled");
                }
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                    StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                int id;
                try {
                    id = Integer.parseInt(line.substring(0, tab).trim());
                } catch (NumberFormatException ex) {
                    continue;
                }
                String name = line.substring(tab + 1).trim();
                Material material = byKey.get(name);
                if (material == null || id == 0) {
                    unresolved++;
                    continue;
                }
                TO_CANONICAL.putIfAbsent(material, id);
                FROM_CANONICAL.putIfAbsent(id, material);
                mapped++;
            }
        } catch (Exception ex) {
            if (log != null) {
                log.severe("failed to read the canonical item table: " + ex);
            }
            return;
        }
        if (log != null) {
            log.info("canonical item table: " + mapped + " items mapped, " + unresolved
                    + " unresolved - both edges must load the SAME table or every item id in the"
                    + " Input stream means something different on the peer");
        }
    }

    private static NamespacedKey keyOf(Material material) {
        try {
            return material.getKey();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static boolean ready() {
        return mapped > 0;
    }

    public static int canonical(Material material) {
        if (material == null || material.isAir()) {
            return 0;
        }
        Integer id = TO_CANONICAL.get(material);
        return id == null ? 0 : id;
    }

    public static int canonical(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
            return 0;
        }
        return canonical(stack.getType());
    }

    public static Material material(int canonicalId) {
        if (canonicalId == 0) {
            return Material.AIR;
        }
        Material material = FROM_CANONICAL.get(canonicalId);
        return material == null ? Material.AIR : material;
    }
}
