package me.nootnoot.edge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.kyori.adventure.util.TriState;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;

public final class EdgeArenaWorld {

    public static final String DEFAULT_NAME = "jackpot_arena";

    private static final int SPAWN_Y = 64;

    private static final class VoidTerrain extends ChunkGenerator {
    }

    private final Plugin plugin;
    private final Logger log;
    private final String name;
    private final World world;

    public EdgeArenaWorld(Plugin plugin, Logger log, String name) {
        this.plugin = plugin;
        this.log = log;
        this.name = name == null ? "" : name.trim();
        this.world = build();
    }

    public boolean available() {
        return world != null;
    }

    public boolean is(World other) {
        return world != null && world.equals(other);
    }

    public World or(World fallback) {
        return world != null ? world : fallback;
    }

    public Location home() {
        for (World candidate : plugin.getServer().getWorlds()) {
            if (!candidate.equals(world)) {
                return candidate.getSpawnLocation();
            }
        }
        return plugin.getServer().getWorlds().get(0).getSpawnLocation();
    }

    private World build() {
        if (name.isEmpty()) {
            log.severe("arena.world is empty, so every match pastes into whatever world the player"
                    + " is standing in. That world keeps its own terrain and everything an earlier"
                    + " match left in it, and the arena blob carries no air cells, so an arena can"
                    + " only be ADDED to what is already there - it never replaces it.");
            return null;
        }
        World loaded = plugin.getServer().getWorld(name);
        if (loaded != null) {
            loaded.setAutoSave(false);
            return loaded;
        }
        wipe(new File(plugin.getServer().getWorldContainer(), name));
        World created;
        try {
            created = new WorldCreator(name)
                    .environment(World.Environment.NORMAL)
                    .type(WorldType.FLAT)
                    .generateStructures(false)
                    .generator(new VoidTerrain())
                    .keepSpawnLoaded(TriState.FALSE)
                    .createWorld();
        } catch (RuntimeException ex) {
            log.severe("could not create the arena world " + name + ": " + ex);
            return null;
        }
        if (created == null) {
            log.severe("the server refused to create the arena world " + name);
            return null;
        }
        created.setAutoSave(false);
        created.setDifficulty(Difficulty.NORMAL);
        created.setSpawnLocation(0, SPAWN_Y, 0);
        log.info("arena world " + name + ": empty void, autosave off. The paste is the only thing"
                + " in it, so every cell the arena blob does not name is air - the same world the"
                + " modded client paints into - and a forced kill cannot leave blocks on disk for"
                + " the next match to stand on.");
        return created;
    }

    private void wipe(File dir) {
        if (!dir.isDirectory()) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            log.info("deleted the stale " + dir.getName() + " folder so this boot starts from an"
                    + " empty void");
        } catch (IOException ex) {
            log.warning("could not delete " + dir + " (" + ex + ") - an arena world left on disk"
                    + " by an earlier run can still be holding its blocks");
        }
    }

    public void sendHome(Player player) {
        if (player == null || !player.isOnline() || !is(player.getWorld())) {
            return;
        }
        try {
            player.teleport(home());
        } catch (RuntimeException ex) {
            log.warning("failed to move " + player.getName() + " out of the arena world: " + ex);
        }
    }

    public void shutdown() {
        if (world == null) {
            return;
        }
        for (Player player : new ArrayList<>(world.getPlayers())) {
            sendHome(player);
        }
        try {
            plugin.getServer().unloadWorld(world, false);
        } catch (RuntimeException ex) {
            log.warning("failed to unload the arena world " + name + ": " + ex);
        }
    }
}
