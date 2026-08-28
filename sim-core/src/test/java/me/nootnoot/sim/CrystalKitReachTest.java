package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CrystalKitReachTest {

    private static final double GROUND_Y = 64.0;
    private static final float FACING_EAST = -90f;

    private static final int TX = 3;
    private static final int TY = 64;
    private static final int TZ = 0;
    private static final long TKEY = BlockStore.key(TX, TY, TZ);

    private static GameState kit() {
        GameState s = CrystalKitFixture.build(GROUND_Y);
        s.vanillaBuild = true;
        s.players[0].yaw = FACING_EAST;
        return s;
    }

    private static Input act(int action, int slot) {
        return new Input(false, false, false, false, false, false, false, false, false,
                FACING_EAST, 0f, slot).withBlockAction(action, TX, TY, TZ);
    }

    private static void idle(GameState s, Arena arena, int ticks) {
        for (int i = 0; i < ticks; i++) {
            Simulation.tick(s, arena, Input.NONE, Input.NONE);
        }
    }

    private static Arena voxelFloor() {
        int baseX = TX - 3;
        int baseY = TY - 1;
        int baseZ = TZ - 3;
        int sx = 7;
        int sy = 1;
        int sz = 7;
        boolean[] grid = new boolean[sx * sy * sz];
        java.util.Arrays.fill(grid, true);
        return new Arena(GROUND_Y, grid, baseX, baseY, baseZ, sx, sy, sz, new double[0][],
                java.util.Map.of(), java.util.Map.of());
    }

    private static int explosions(GameState s) {
        int n = 0;
        for (CombatEvent e : s.events) {
            if (e.type() == CombatEvent.EXPLOSION) {
                n++;
            }
        }
        return n;
    }

    private static boolean carries(GameState s, IntPredicate wanted) {
        PlayerState p = s.players[0];
        for (int slot = 0; slot < ItemDict.SLOTS; slot++) {
            int entry = p.slotEntry[slot];
            if (entry != ItemDict.NONE && p.slotCount[slot] > 0 && wanted.test(entry)) {
                return true;
            }
        }
        return false;
    }

    private static boolean carriesUse(GameState s, int useKind) {
        return carries(s, e -> s.dict.useKind(e) == useKind);
    }

    @Test
    void theCrystalKitCarriesEveryMechanicTheTesterWasAskedToExercise() {
        GameState s = kit();
        List<String> missing = new ArrayList<>();

        record Reach(String mechanic, boolean present) {
        }

        for (Reach r : List.of(
                new Reach("end crystals", carries(s, s.dict::isEndCrystal)),
                new Reach("respawn anchors", carries(s, s.dict::isRespawnAnchor)),
                new Reach("glowstone to charge them", carries(s, s.dict::isGlowstone)),
                new Reach("ender pearls", carriesUse(s, Combat.USE_PEARL)),
                new Reach("a shield", carries(s, s.dict::isShield)),
                new Reach("a mace", carries(s, s.dict::isMace)),
                new Reach("a bow", carries(s, s.dict::isBow)),
                new Reach("a crossbow", carries(s, s.dict::isCrossbow)),
                new Reach("plain arrows", carries(s, s.dict::isArrowPlain)),
                new Reach("tipped arrows", carries(s, s.dict::isArrowSpecial)),
                new Reach("an elytra", carries(s, s.dict::isElytra)),
                new Reach("rockets", carriesUse(s, Combat.USE_FIREWORK)),
                new Reach("a shulker box", carries(s, s.dict::isShulker)),
                new Reach("an ender chest", carries(s, s.dict::isEnderChest)),
                new Reach("golden apples", carriesUse(s, Combat.USE_FOOD)),
                new Reach("a water bucket", carries(s, s.dict::isBucketWater)),
                new Reach("a lava bucket", carries(s, s.dict::isBucketLava)),
                new Reach("placeable blocks", carries(s, e -> s.dict.isBlock(e)
                        && !s.dict.isRespawnAnchor(e) && !s.dict.isShulker(e))),
                new Reach("cobwebs", carries(s, e -> s.dict.itemId(e) == s.cobwebItemId)),
                new Reach("a totem", carries(s, s.dict::isTotem)),
                new Reach("a pickaxe to mine with", carries(s, e -> s.dict.efficiency(e) > 0)))) {
            if (!r.present()) {
                missing.add(r.mechanic());
            }
        }

        assertTrue(missing.isEmpty(),
                "the CRYSTAL kit is the only kit the tester is given, so a mechanic it does not"
                        + " carry is a mechanic no amount of testing will ever find a bug in."
                        + " Missing: " + missing);
    }

    @Test
    void anAnchorGoesUpChargesAndDetonatesStraightOutOfTheCrystalKitSlots() {
        Arena arena = voxelFloor();
        GameState s = kit();
        PlayerState p = s.players[0];
        int anchorsBefore = p.slotCount[CrystalKitFixture.SLOT_ANCHOR];
        int glowstoneBefore = p.slotCount[CrystalKitFixture.SLOT_GLOWSTONE];

        Simulation.tick(s, arena,
                act(Input.BLOCK_PLACE_ANCHOR, CrystalKitFixture.SLOT_ANCHOR), Input.NONE);
        assertTrue(s.blocks.contains(TX, TY, TZ),
                "hotbar slot " + CrystalKitFixture.SLOT_ANCHOR + " of the CRYSTAL kit is a stack"
                        + " of respawn anchors and this is the input the client sends for a right"
                        + " click on the ground with one held");
        assertEquals(anchorsBefore - 1, p.slotCount[CrystalKitFixture.SLOT_ANCHOR]);

        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena,
                act(Input.BLOCK_CHARGE_ANCHOR, CrystalKitFixture.SLOT_GLOWSTONE), Input.NONE);
        assertEquals(1, (int) s.anchors.get(TKEY),
                "hotbar slot " + CrystalKitFixture.SLOT_GLOWSTONE + " is the glowstone that arms"
                        + " it, which is the one thing a respawn anchor needs before it can"
                        + " explode");
        assertEquals(glowstoneBefore - 1, p.slotCount[CrystalKitFixture.SLOT_GLOWSTONE]);

        idle(s, arena, Combat.USE_REPEAT_DELAY);
        Simulation.tick(s, arena,
                act(Input.BLOCK_DETONATE_ANCHOR, CrystalKitFixture.SLOT_ANCHOR), Input.NONE);

        assertEquals(1, explosions(s), "and the third right click sets it off");
        assertFalse(s.blocks.contains(TX, TY, TZ),
                "vanilla RespawnAnchorBlock.explode calls level.removeBlock(pos, false) before"
                        + " level.explode, so the anchor is gone on the tick it goes off");
        assertNull(s.anchors.get(TKEY));
        assertFalse(s.brokenArena.isEmpty(),
                "vanilla passes radius 5.0F with Level.ExplosionInteraction.BLOCK, which"
                        + " ServerLevel.explode maps to DESTROY_WITH_DECAY, so the blast has to"
                        + " eat terrain. If a tester sees an anchor blast leave the floor alone,"
                        + " the mode flag is off, not the explosion");
    }
}
