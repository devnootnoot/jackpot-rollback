package me.nootnoot.edge;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class EdgeMatchStats {

    public static final int MAGIC = 0x4A535441;
    public static final byte VERSION = 1;
    public static final int BYTES = 4 + 1 + 4 + 4;

    public record Counts(int totems, int healthPots) {

        public static final Counts NONE = new Counts(0, 0);
    }

    private EdgeMatchStats() {
    }

    public static Counts totals(EdgeLoadout loadout, int slot) {
        if (loadout == null) {
            return Counts.NONE;
        }
        int totems = 0;
        int pots = 0;
        for (int s = 0; s < EdgeLoadout.SLOTS; s++) {
            ItemStack item = loadout.item(slot, s);
            if (item == null) {
                continue;
            }
            if (item.getType() == Material.TOTEM_OF_UNDYING) {
                totems++;
            } else if (item.getType() == Material.SPLASH_POTION) {
                pots += instantHealth(item);
            }
        }
        return new Counts(totems, pots);
    }

    public static Counts left(Player player) {
        if (player == null || !player.isOnline()) {
            return Counts.NONE;
        }
        int totems = 0;
        int pots = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null) {
                continue;
            }
            if (item.getType() == Material.TOTEM_OF_UNDYING) {
                totems++;
            } else if (item.getType() == Material.SPLASH_POTION) {
                pots += instantHealth(item);
            }
        }
        return new Counts(totems, pots);
    }

    private static int instantHealth(ItemStack item) {
        if (!(item.getItemMeta() instanceof PotionMeta meta)) {
            return 0;
        }
        int found = 0;
        for (PotionEffect effect : meta.getAllEffects()) {
            if (effect.getType() == PotionEffectType.INSTANT_HEALTH) {
                found++;
            }
        }
        return found;
    }

    public static byte[] encode(Counts counts) {
        ByteBuffer b = ByteBuffer.allocate(BYTES);
        b.putInt(MAGIC);
        b.put(VERSION);
        b.putInt(counts.totems());
        b.putInt(counts.healthPots());
        return b.array();
    }

    public static Counts decode(byte[] blob) {
        if (blob == null || blob.length < BYTES) {
            return null;
        }
        try {
            ByteBuffer b = ByteBuffer.wrap(blob);
            if (b.getInt() != MAGIC || b.get() != VERSION) {
                return null;
            }
            int totems = b.getInt();
            int pots = b.getInt();
            if (totems < 0 || pots < 0) {
                return null;
            }
            return new Counts(totems, pots);
        } catch (BufferUnderflowException ex) {
            return null;
        }
    }
}
