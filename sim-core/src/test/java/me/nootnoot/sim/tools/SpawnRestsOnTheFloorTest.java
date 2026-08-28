package me.nootnoot.sim.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpawnRestsOnTheFloorTest {

    private static final List<String> PALETTE =
            List.of("minecraft:air", "minecraft:stone");

    private static FarenaArena.Schematic withFloorAt(int worldY) {
        char[] ids = new char[FarenaArena.SECTION_CELLS];
        int sectionY = Math.floorDiv(worldY, 16);
        int ly = worldY - (sectionY * 16);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                ids[(lz << 8) | (ly << 4) | lx] = 1;
            }
        }
        return new FarenaArena.Schematic(List.of(), PALETTE,
                List.of(new FarenaArena.Section(0, 0, sectionY, ids)));
    }

    private static FarenaArena.Spawn snap(FarenaArena.Schematic schematic, double spawnY) {
        return FarenaArena.restOnFloor(new FarenaArena.Spawn(4.5, spawnY, 4.5, 0f, 0f),
                schematic, DevPaletteGeometry.of(PALETTE));
    }

    @Test
    void aSpawnFloatingHighAboveTheFloorIsBroughtDownToIt() {
        assertEquals(6.0, snap(withFloorAt(5), 32.0).y(),
                "every shipped arena declared its spawns at y=32 with the floor at y=5. The sim"
                        + " derives its invisible ground slab from the spawn, so the modded host"
                        + " floated there while the unmodded host fell to the real floor");
    }

    @Test
    void aSpawnAlreadyStandingOnTheFloorIsLeftAlone() {
        assertEquals(6.0, snap(withFloorAt(5), 6.0).y());
    }

    @Test
    void aSmallDropIsLeftAloneSoNormalArenasAreNotMoved() {
        assertEquals(7.5, snap(withFloorAt(5), 7.5).y(),
                "a spawn a block or two above the floor is ordinary map authoring and must not be"
                        + " rewritten");
    }

    @Test
    void aSpawnOverNothingAtAllKeepsItsDeclaredHeight() {
        FarenaArena.Schematic empty = new FarenaArena.Schematic(List.of(), PALETTE, List.of());
        assertEquals(32.0, snap(empty, 32.0).y(),
                "with no geometry under the spawn there is nothing to rest on, so the declared"
                        + " height stands and SpawnSupport reports it instead");
    }
}
