package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockProps;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class AnchorBlastTerrainReachTest {

    private static final double GROUND_Y = 64.0;
    private static final float FACING_EAST = -90f;

    private static final int ANCHOR_X = 3;
    private static final int ANCHOR_Y = 64;
    private static final int ANCHOR_Z = 0;
    private static final long ANCHOR_KEY = BlockStore.key(ANCHOR_X, ANCHOR_Y, ANCHOR_Z);

    private static final int OBSIDIAN_X = 2;
    private static final int GLOWSTONE_X = 4;

    private static final float VANILLA_OBSIDIAN_RESISTANCE = 1200f;
    private static final float VANILLA_ANCHOR_RESISTANCE = 1200f;
    private static final float VANILLA_GLOWSTONE_RESISTANCE = 0.3f;
    private static final float VANILLA_DIRT_RESISTANCE = 0.5f;
    private static final float VANILLA_BEDROCK_RESISTANCE = 3_600_000f;

    private static final int FLOOR_RADIUS = 8;
    private static final int FLOOR_DEPTH = 4;

    private static GameState kit() {
        GameState s = CrystalKitFixture.build(GROUND_Y);
        s.vanillaBuild = true;
        s.players[0].yaw = FACING_EAST;
        BlockProps.Builder props = new BlockProps.Builder();
        props.add(CrystalKitFixture.ID_OBSIDIAN, 50f, VANILLA_OBSIDIAN_RESISTANCE,
                CrystalKitFixture.ID_OBSIDIAN, 4, 0, true);
        props.add(CrystalKitFixture.ID_RESPAWN_ANCHOR, 50f, VANILLA_ANCHOR_RESISTANCE,
                CrystalKitFixture.ID_RESPAWN_ANCHOR, 4, 0, true);
        props.add(CrystalKitFixture.ID_GLOWSTONE, 0.3f, VANILLA_GLOWSTONE_RESISTANCE,
                CrystalKitFixture.ID_GLOWSTONE, -1, 0, false);
        s.blockProps = props.build();
        return s;
    }

    private static Input act(int action, int slot, int x, int y, int z) {
        return new Input(false, false, false, false, false, false, false, false, false,
                FACING_EAST, 0f, slot).withBlockAction(action, x, y, z);
    }

    private static void idle(GameState s, Arena arena, int ticks) {
        for (int i = 0; i < ticks; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
    }

    private static Arena voxelFloor() {
        int baseX = -FLOOR_RADIUS;
        int baseY = (int) GROUND_Y - FLOOR_DEPTH;
        int baseZ = -FLOOR_RADIUS;
        int sizeX = 2 * FLOOR_RADIUS + 1;
        int sizeZ = sizeX;
        boolean[] grid = new boolean[sizeX * FLOOR_DEPTH * sizeZ];
        Arrays.fill(grid, true);
        Map<Long, Float> resistance = new HashMap<>();
        for (int x = baseX; x < baseX + sizeX; x++) {
            for (int z = baseZ; z < baseZ + sizeZ; z++) {
                resistance.put(BlockStore.key(x, baseY, z), VANILLA_BEDROCK_RESISTANCE);
                for (int y = baseY + 1; y < baseY + FLOOR_DEPTH; y++) {
                    resistance.put(BlockStore.key(x, y, z), VANILLA_DIRT_RESISTANCE);
                }
            }
        }
        return new Arena(GROUND_Y, grid, baseX, baseY, baseZ, sizeX, FLOOR_DEPTH, sizeZ,
                new double[0][], resistance, Map.of());
    }

    private record Blast(int explosions, boolean obsidianSurvived, boolean glowstoneSurvived,
                         int arenaCellsBroken, int fires) {
    }

    private static Blast detonate(Arena arena) {
        GameState s = kit();
        PlayerState p = s.players[0];

        Simulation.tick(s, arena, act(Input.BLOCK_PLACE_ANCHOR, CrystalKitFixture.SLOT_ANCHOR,
                ANCHOR_X, ANCHOR_Y, ANCHOR_Z), Input.NONE);
        assertTrue(s.blocks.contains(ANCHOR_X, ANCHOR_Y, ANCHOR_Z),
                "the anchor has to go down before any of this means anything");

        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena, act(Input.BLOCK_CHARGE_ANCHOR, CrystalKitFixture.SLOT_GLOWSTONE,
                ANCHOR_X, ANCHOR_Y, ANCHOR_Z), Input.NONE);
        assertEquals(1, (int) s.anchors.get(ANCHOR_KEY), "and it has to take a charge");

        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena, act(Input.BLOCK_PLACE, CrystalKitFixture.SLOT_OBSIDIAN,
                OBSIDIAN_X, ANCHOR_Y, ANCHOR_Z), Input.NONE);
        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena, act(Input.BLOCK_PLACE, CrystalKitFixture.SLOT_GLOWSTONE,
                GLOWSTONE_X, ANCHOR_Y, ANCHOR_Z), Input.NONE);
        assertTrue(s.blocks.contains(OBSIDIAN_X, ANCHOR_Y, ANCHOR_Z)
                        && s.blocks.contains(GLOWSTONE_X, ANCHOR_Y, ANCHOR_Z),
                "one blast-proof block and one flimsy one, both from the CRYSTAL hotbar");
        idle(s, arena, Combat.USE_REPEAT_DELAY);

        p.x = ANCHOR_X - 4.5;
        s.events.clear();
        Simulation.tick(s, arena, act(Input.BLOCK_DETONATE_ANCHOR, CrystalKitFixture.SLOT_ANCHOR,
                ANCHOR_X, ANCHOR_Y, ANCHOR_Z), Input.NONE);

        int explosions = 0;
        for (CombatEvent e : s.events) {
            if (e.type() == CombatEvent.EXPLOSION) {
                explosions++;
            }
        }
        return new Blast(explosions, s.blocks.contains(OBSIDIAN_X, ANCHOR_Y, ANCHOR_Z),
                s.blocks.contains(GLOWSTONE_X, ANCHOR_Y, ANCHOR_Z), s.brokenArena.size(),
                s.fires.size());
    }

    @Test
    void onRealTerrainAnAnchorCratersTheFloorAndLeavesObsidianStanding() {
        Blast blast = detonate(voxelFloor());

        assertEquals(1, blast.explosions(), "the third right click is the detonation");
        assertTrue(blast.arenaCellsBroken() > 0,
                "vanilla RespawnAnchorBlock.explode removes the anchor and then calls"
                        + " level.explode(..., 5.0F, true, Level.ExplosionInteraction.BLOCK);"
                        + " ServerLevel maps BLOCK to DESTROY_WITH_DECAY, so the blast is supposed"
                        + " to eat terrain and this is the assertion that says our sim does too");
        assertTrue(blast.fires() > 0,
                "the same call passes fire=true, so an anchor blast scatters fire onto the cells"
                        + " it cleared");
        assertFalse(blast.glowstoneSurvived(),
                "glowstone stops (0.3 + 0.3) * 0.3 = 0.18 of a ray, which power 5 walks straight"
                        + " through");
        assertTrue(blast.obsidianSurvived(),
                "obsidian stops (1200 + 0.3) * 0.3 = 360.09 on the first cell it is met in, and an"
                        + " anchor ray never carries more than 5 * 1.3 = 6.5. An anchor CANNOT"
                        + " break obsidian in vanilla, so an obsidian-walled crystal arena showing"
                        + " no damage after a detonation is not a bug");
    }

    @Test
    void onTheFlatFallbackArenaNoAnchorBlastCanEverTouchTheGround() {
        Blast flat = detonate(Arena.flat(GROUND_Y));

        assertEquals(1, flat.explosions(),
                "the anchor still places, charges and goes off - the loop itself is testable in"
                        + " the dev stack");
        assertFalse(flat.glowstoneSurvived(),
                "and a block the player PLACED still comes apart, so the blast is running");
        assertEquals(0, flat.arenaCellsBroken(),
                "but Arena.flat carries no voxel grid, only the infinite ground slab, and"
                        + " Combat.blastResistanceAt consults blocks, isSolidVoxel and"
                        + " isDecorVoxel and never the slab. gradlew devRun provisions"
                        + " arena-file: arena.bin and never writes it, and devAssign without"
                        + " -ParenaName ships no arena bytes, so both edges land here and a"
                        + " tester detonating an anchor sees the floor untouched no matter what"
                        + " the sim does. Run gradlew devSampleArena once (or pass -ParenaName)"
                        + " before judging any anchor or crystal blast in dev");
        assertEquals(0, flat.fires(),
                "Combat.scatterFire needs a solid cell UNDER the one it lights and reads that"
                        + " through isSolidVoxel too, so the flat fallback also swallows every"
                        + " fire an anchor is supposed to leave behind");
    }
}
