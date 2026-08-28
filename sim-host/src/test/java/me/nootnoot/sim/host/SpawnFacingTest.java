package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.Combat;
import me.nootnoot.sim.math.Vec3;
import org.junit.jupiter.api.Test;

class SpawnFacingTest {

    private record Pair(String arena, double x0, double z0, double x1, double z1) {
    }

    private static final Pair[] SHIPPED_ARENAS = {
            new Pair("colosseum", 30.510, 0.455, -29.459, 0.529),
            new Pair("courtyard", 0.494, 30.475, 0.511, -29.475),
            new Pair("dragon", 30.452, 0.415, -29.509, 0.526),
            new Pair("frost_hold", 0.513, -29.416, 0.526, 30.406),
            new Pair("hellscape", 30.448, 0.469, -29.497, 0.546),
            new Pair("knight", 0.507, -19.489, 0.511, 20.443),
            new Pair("outpost", -19.507, 0.524, 20.502, 0.545),
            new Pair("stone_field", -29.476, 0.485, 30.488, 0.455),
            new Pair("temple", 0.485, -29.475, 0.519, 30.486),
    };

    private static double lookAlignment(double fromX, double fromZ, double toX, double toZ) {
        float yaw = SpawnFacing.yaw(fromX, fromZ, toX, toZ);
        Vec3 look = Combat.lookVector(yaw, 0f);
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        double lookLen = Math.sqrt(look.x() * look.x() + look.z() * look.z());
        return (look.x() * dx + look.z() * dz) / (len * lookLen);
    }

    @Test
    void theCardinalsMatchTheVanillaYawConvention() {
        assertEquals(0f, SpawnFacing.yaw(0.0, 0.0, 0.0, 10.0), 1.0E-3f);
        assertEquals(90f, SpawnFacing.yaw(0.0, 0.0, -10.0, 0.0), 1.0E-3f);
        assertEquals(180f, Math.abs(SpawnFacing.yaw(0.0, 0.0, 0.0, -10.0)), 1.0E-3f);
        assertEquals(-90f, SpawnFacing.yaw(0.0, 0.0, 10.0, 0.0), 1.0E-3f);
    }

    @Test
    void bothSlotsOfEveryShippedArenaSpawnFacingTheOther() {
        for (Pair p : SHIPPED_ARENAS) {
            double slot0 = lookAlignment(p.x0(), p.z0(), p.x1(), p.z1());
            double slot1 = lookAlignment(p.x1(), p.z1(), p.x0(), p.z0());
            assertTrue(slot0 > 0.9999,
                    p.arena() + " slot 0 must look straight at slot 1, alignment was " + slot0
                            + "; a value near -1 is the 180-degree spawn the tester reported");
            assertTrue(slot1 > 0.9999,
                    p.arena() + " slot 1 must look straight at slot 0, alignment was " + slot1);
        }
    }

    @Test
    void theTwoDerivedFacingsAreExactlyOpposite() {
        for (Pair p : SHIPPED_ARENAS) {
            float y0 = SpawnFacing.yaw(p.x0(), p.z0(), p.x1(), p.z1());
            float y1 = SpawnFacing.yaw(p.x1(), p.z1(), p.x0(), p.z0());
            double delta = Math.abs(((y0 - y1) % 360.0 + 360.0) % 360.0 - 180.0);
            assertTrue(delta < 1.0E-3,
                    p.arena() + " the two spawn facings must differ by exactly 180 degrees,"
                            + " off by " + delta);
        }
    }

    @Test
    void pairingAYawWithTheOtherSlotsPositionIsTheOneEightyBug() {
        Pair p = SHIPPED_ARENAS[0];
        float wrong = SpawnFacing.yaw(p.x1(), p.z1(), p.x0(), p.z0());
        Vec3 look = Combat.lookVector(wrong, 0f);
        double dx = p.x1() - p.x0();
        double dz = p.z1() - p.z0();
        double len = Math.sqrt(dx * dx + dz * dz);
        double alignment = (look.x() * dx + look.z() * dz) / len;
        assertTrue(alignment < -0.9999,
                "slot 0 standing at its own spawn but wearing slot 1's yaw looks exactly away from"
                        + " its opponent - this is the shape of the reported bug, and deriving both"
                        + " yaws from the positions is what makes it unreachable");
    }
}
