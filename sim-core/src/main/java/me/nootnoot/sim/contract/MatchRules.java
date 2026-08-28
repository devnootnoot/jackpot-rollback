package me.nootnoot.sim.contract;

import java.nio.ByteBuffer;
import me.nootnoot.sim.state.GameState;

public record MatchRules(boolean vanillaBuild, boolean allowBucket, boolean allowExplosion,
                         boolean explosionParticles, boolean totemParticles,
                         boolean instaReady0, boolean instaReady1, boolean potSwordBoost) {

    public static final int BYTES = 8;

    public static final MatchRules OPEN =
            new MatchRules(true, true, true, true, true, false, false, false);

    public static MatchRules read(ByteBuffer b) {
        boolean vanillaBuild = b.get() != 0;
        boolean allowBucket = b.get() != 0;
        boolean allowExplosion = b.get() != 0;
        boolean explosionParticles = b.get() != 0;
        boolean totemParticles = b.get() != 0;
        boolean instaReady0 = b.get() != 0;
        boolean instaReady1 = b.get() != 0;
        boolean potSwordBoost = b.get() != 0;
        return new MatchRules(vanillaBuild, allowBucket, allowExplosion, explosionParticles,
                totemParticles, instaReady0, instaReady1, potSwordBoost);
    }

    public void write(ByteBuffer b) {
        b.put(flag(vanillaBuild));
        b.put(flag(allowBucket));
        b.put(flag(allowExplosion));
        b.put(flag(explosionParticles));
        b.put(flag(totemParticles));
        b.put(flag(instaReady0));
        b.put(flag(instaReady1));
        b.put(flag(potSwordBoost));
    }

    public byte[] bytes() {
        ByteBuffer b = ByteBuffer.allocate(BYTES);
        write(b);
        return b.array();
    }

    public boolean instaReady(int slot) {
        return slot == 0 ? instaReady0 : instaReady1;
    }

    public void applyTo(GameState state) {
        state.vanillaBuild = vanillaBuild;
        state.allowBucket = allowBucket;
        state.allowExplosion = allowExplosion;
        state.potSwordBoost = potSwordBoost;
        for (int i = 0; i < state.players.length; i++) {
            state.players[i].instaReady = instaReady(i);
        }
    }

    private static byte flag(boolean value) {
        return (byte) (value ? 1 : 0);
    }
}
