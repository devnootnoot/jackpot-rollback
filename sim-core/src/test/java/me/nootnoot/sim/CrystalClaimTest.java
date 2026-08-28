package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.math.Aabb;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CrystalClaimTest {
    private static final double GROUND_Y = 64.0;

    private static final int WALL_ITEM_ID = 900;

    private static final int SPEAR_ITEM_ID = 901;

    private static GameState withCrystalAt(int bx, int by, int bz) {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.y = GROUND_Y;
        a.z = 0.5;
        a.yaw = -90f;
        a.onGround = true;
        a.health = 20f;
        s.players[1].x = 60.0;
        s.players[1].y = GROUND_Y;
        s.players[1].health = 20f;

        CrystalState c = new CrystalState();
        c.id = 1;
        c.bx = bx;
        c.by = by;
        c.bz = bz;
        s.crystals.add(c);
        return s;
    }

    private static Input hit(int bx, int by, int bz) {
        return new Input(false, false, false, false, false, false, false, true, false, 0f, 0f, 0)
                .withBlockAction(Input.BLOCK_HIT_CRYSTAL, bx, by, bz);
    }

    private static double eyeToCrystal(PlayerState a, int bx, int by, int bz) {
        Aabb box = Combat.crystalBox(bx, by, bz);
        double eye = Combat.eyeHeight(a);
        double nx = Math.max(box.minX, Math.min(a.x, box.maxX));
        double ny = Math.max(box.minY, Math.min(a.y + eye, box.maxY));
        double nz = Math.max(box.minZ, Math.min(a.z, box.maxZ));
        double dx = a.x - nx;
        double dy = a.y + eye - ny;
        double dz = a.z - nz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void theCrystalHullIsTheVanillaEndCrystalBox() {
        Aabb box = Combat.crystalBox(2, 64, 0);
        assertEquals(2.0, box.maxX - box.minX, 1.0E-9, "an end crystal is two blocks wide");
        assertEquals(2.0, box.maxY - box.minY, 1.0E-9, "and two blocks tall");
        assertEquals(65.0, box.minY, 1.0E-9, "sitting on top of the block it was placed against");
    }

    @Test
    void aCrystalInTheOpenIsStillPopped() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withCrystalAt(2, 64, 0);

        Simulation.tick(s, arena, hit(2, 64, 0), Input.NONE);

        assertTrue(s.crystals.isEmpty(), "a clean claim on a crystal in front of you must detonate it");
    }

    @Test
    void aCrystalBehindAWallIsNotPopped() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withCrystalAt(2, 64, 0);
        s.blocks.place(1, 65, 0, WALL_ITEM_ID);
        s.blocks.place(1, 66, 0, WALL_ITEM_ID);

        Simulation.tick(s, arena, hit(2, 64, 0), Input.NONE);

        assertEquals(1, s.crystals.size(),
                "an exact coordinate match is not a sightline; a crystal behind a wall must survive");
        assertEquals(20f, s.players[0].health, 1.0E-6f, "and nothing may have exploded");
    }

    private static double eyeToCell(PlayerState a, int bx, int by, int bz) {
        double eye = Combat.eyeHeight(a);
        double cx = Math.max(bx, Math.min(a.x, bx + 1.0));
        double cy = Math.max(by, Math.min(a.y + eye, by + 1.0));
        double cz = Math.max(bz, Math.min(a.z, bz + 1.0));
        double dx = a.x - cx;
        double dy = a.y + eye - cy;
        double dz = a.z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Test
    void aCrystalInsideTheVanillaAttackRangeIsPopped() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withCrystalAt(6, 64, 0);
        double distance = eyeToCrystal(s.players[0], 6, 64, 0);

        assertTrue(distance <= Combat.attackReachLimit(s, s.players[0]),
                "this crystal has to sit inside 6.0 or it proves nothing: " + distance);
        assertTrue(eyeToCell(s.players[0], 6, 64, 0) > Combat.blockReachLimit(),
                "and its cell has to sit outside the block interaction gate, which is what used"
                        + " to judge it, or the two paths cannot be told apart");

        Simulation.tick(s, arena, hit(6, 64, 0), Input.NONE);

        assertTrue(s.crystals.isEmpty(),
                "hitting a crystal is an attack on an entity, so it takes the entity attack range");
    }

    @Test
    void theCrystalGateIsTheOneVanillaAppliesToEveryEntityAttack() {
        GameState s = withCrystalAt(2, 64, 0);
        assertEquals(6.0, Combat.attackReachLimit(s, s.players[0]), 1.0E-9,
                "ServerGamePacketListenerImpl.handleAttack runs isWithinAttackRange(mainHandItem,"
                        + " targetBounds, 3.0) for ANY entity id in the packet, an end crystal"
                        + " included, and AttackRange.isInRange compares sqrt(aabb.distanceToSqr("
                        + "eye)) against effectiveMaxRange + hitboxMargin + buffer, which for an"
                        + " empty hand is the 3.0 entity_interaction_range attribute plus 0.0 plus"
                        + " the 3.0 the call site passes");
    }

    @Test
    void aCrystalPastTheVanillaGateIsRefusedAndASpearReachesIt() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState bare = withCrystalAt(7, 64, 0);
        double distance = eyeToCrystal(bare.players[0], 7, 64, 0);
        assertTrue(distance > Combat.attackReachLimit(bare, bare.players[0]),
                "this crystal has to sit outside the empty hand bound: " + distance);

        Simulation.tick(bare, arena, hit(7, 64, 0), Input.NONE);
        assertEquals(1, bare.crystals.size(), "past the gate the pop is refused");

        GameState armed = withCrystalAt(7, 64, 0);
        TestKit.of(armed).give(0, 0, 1,
                TestKit.item().itemId(SPEAR_ITEM_ID).maxStack(1).flags(ItemDict.FLAG_SPEAR));
        armed.players[0].heldSlot = 0;
        assertTrue(distance <= Combat.attackReachLimit(armed, armed.players[0]),
                "and inside the spear bound: " + distance + " vs "
                        + Combat.attackReachLimit(armed, armed.players[0]));

        Simulation.tick(armed, arena, hit(7, 64, 0), Input.NONE);
        assertTrue(armed.crystals.isEmpty(),
                "the gate is the held item's AttackRange component, not a constant: a spear ships"
                        + " AttackRange(2.0, 4.5, 2.0, 6.5, 0.125, 0.5), so vanilla lets it hit an"
                        + " entity the empty hand cannot reach, and a crystal is an entity");
    }

    @Test
    void aCrystalHitAimedAtAnEmptyCellIsANoOpEvenWithAPerfectSightline() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withCrystalAt(2, 64, 0);
        s.crystals.clear();

        Simulation.tick(s, arena, hit(2, 64, 0), Input.NONE);

        assertEquals(0, s.crystals.size(), "there was never anything there to pop");
        assertEquals(20f, s.players[0].health, 1.0E-6f,
                "and an empty cell must never detonate: the existence check is the cheap gate and"
                        + " it decides the outcome on its own, so running it before the sightline"
                        + " cannot change what happens, only what it costs");

        GameState control = withCrystalAt(2, 64, 0);
        Simulation.tick(control, arena, hit(2, 64, 0), Input.NONE);
        assertTrue(control.crystals.isEmpty(),
                "control: the identical claim against a cell that does hold a crystal pops it");
    }

    @Test
    void theCrystalSightlineIsJudgedFromTheEyeTheInputAppliesTo() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withCrystalAt(2, 64, 0);
        PlayerState a = s.players[0];

        ClaimAuthority.record(s);
        double[] eyes = new double[(ClaimAuthority.WINDOW_FRAMES + 1) * 3];
        int n = ClaimAuthority.frameEyes(a, eyes, ClaimAuthority.WINDOW_FRAMES);

        assertTrue(n >= 2, "the ring has to hold the frame this test is about");
        assertEquals(eyes[0], eyes[3], 0.0,
                "Simulation.tick records the ring and then runs resolveBlockActions before"
                        + " tickPlayer, so the newest recorded frame IS the live frame: a one frame"
                        + " attacker allowance on the crystal path could never have found a"
                        + " different origin, which is why it was removed rather than widened");
        assertEquals(eyes[1], eyes[4], 0.0);
        assertEquals(eyes[2], eyes[5], 0.0);
    }

    @Test
    void aCrystalPastTheVanillaAttackRangeIsRefused() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = withCrystalAt(12, 64, 0);

        assertTrue(eyeToCrystal(s.players[0], 12, 64, 0) > Combat.attackReachLimit(s, s.players[0]),
                "this crystal has to sit outside 6.0 or it proves nothing");

        Simulation.tick(s, arena, hit(12, 64, 0), Input.NONE);

        assertEquals(1, s.crystals.size(), "vanilla refuses this attack too");
    }
}
