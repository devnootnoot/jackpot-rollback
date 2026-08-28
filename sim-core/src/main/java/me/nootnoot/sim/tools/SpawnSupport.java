package me.nootnoot.sim.tools;

import me.nootnoot.sim.state.Arena;

public final class SpawnSupport {

    public static final int MAX_DROP_TO_FLOOR = 2;

    private SpawnSupport() {
    }

    public record Report(String tag, double declaredY, int floorBelow, int solidAboveAtSpawn,
                         double groundSlabTop) {

        public boolean supported() {
            return floorBelow != Integer.MIN_VALUE
                    && declaredY - (floorBelow + 1) <= MAX_DROP_TO_FLOOR
                    && declaredY - (floorBelow + 1) >= -0.5;
        }

        public String describe() {
            if (supported()) {
                return tag + " y=" + declaredY + " rests on real geometry at y=" + floorBelow;
            }
            return tag + " y=" + declaredY + " IS NOT ON ANY BLOCK. Nearest floor below is "
                    + (floorBelow == Integer.MIN_VALUE ? "nothing at all" : "y=" + floorBelow)
                    + " and the nearest solid above is "
                    + (solidAboveAtSpawn == Integer.MIN_VALUE ? "nothing"
                            : "y=" + solidAboveAtSpawn)
                    + ". The sim will float this player on its invisible ground slab at y="
                    + groundSlabTop + ", which is derived from the spawn points themselves, so a"
                    + " sim-authoritative host stands on nothing while a client-authoritative host"
                    + " falls through to the real floor. That is a guaranteed height disagreement"
                    + " between a modded and an unmodded player. Re-set this arena's spawn points"
                    + " onto its floor";
        }
    }

    public static Report of(Arena arena, double x, double y, double z, String tag) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int start = (int) Math.floor(y);
        int below = Integer.MIN_VALUE;
        for (int cy = start - 1; cy >= arena.baseY(); cy--) {
            if (arena.isSolidVoxel(bx, cy, bz)) {
                below = cy;
                break;
            }
        }
        int above = Integer.MIN_VALUE;
        for (int cy = start; cy < arena.baseY() + arena.sizeY(); cy++) {
            if (arena.isSolidVoxel(bx, cy, bz)) {
                above = cy;
                break;
            }
        }
        return new Report(tag, y, below, above, arena.groundY);
    }
}
