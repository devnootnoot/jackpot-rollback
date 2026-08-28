package me.nootnoot.sim.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.Simulation;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class SpawnPairFacingTest {

    private record Spawn(double x, double y, double z) {
    }

    private record ArenaPair(String name, Spawn a, Spawn b) {
    }

    private static final ArenaPair[] SHIPPED = {
            new ArenaPair("colosseum", new Spawn(30.510, 64.0, 0.455), new Spawn(-29.459, 64.0, 0.529)),
            new ArenaPair("courtyard", new Spawn(0.494, 64.0, 30.475), new Spawn(0.511, 64.0, -29.475)),
            new ArenaPair("knight", new Spawn(0.507, 64.0, -19.489), new Spawn(0.511, 64.0, 20.443)),
            new ArenaPair("outpost", new Spawn(-19.507, 64.0, 0.524), new Spawn(20.502, 64.0, 0.545)),
            new ArenaPair("stone_field", new Spawn(-29.476, 64.0, 0.485), new Spawn(30.488, 64.0, 0.455)),
            new ArenaPair("diagonal", new Spawn(-17.5, 64.0, -11.25), new Spawn(23.5, 64.0, 36.75)),
    };

    private static float vanillaLookAtYaw(Spawn from, Spawn to) {
        double xd = to.x() - from.x();
        double zd = to.z() - from.z();
        return wrap((float) (StrictMath.atan2(zd, xd) * 180.0 / StrictMath.PI) - 90.0f);
    }

    private static float wrap(float degrees) {
        float d = degrees % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        }
        if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }

    private static float delta(float a, float b) {
        return Math.abs(wrap(a - b));
    }

    @Test
    void eachSlotIsGivenTheYawThatLooksAtTheOtherSlotsSpawn() {
        for (ArenaPair p : SHIPPED) {
            float slot0 = SpawnFacing.yaw(p.a().x(), p.a().z(), p.b().x(), p.b().z());
            float slot1 = SpawnFacing.yaw(p.b().x(), p.b().z(), p.a().x(), p.a().z());
            float expect0 = vanillaLookAtYaw(p.a(), p.b());
            float expect1 = vanillaLookAtYaw(p.b(), p.a());

            assertTrue(delta(slot0, expect0) < 1.0E-3f,
                    p.name() + " slot 0 must spawn facing slot 1. Expected " + expect0
                            + " (Entity.lookAt: atan2(dz, dx) in degrees minus 90, transcribed"
                            + " straight out of the decompiled 26.2 source and NOT sharing a line"
                            + " of code with SpawnFacing) but got " + slot0
                            + ". A delta near 180 is the reported bug.");
            assertTrue(delta(slot1, expect1) < 1.0E-3f,
                    p.name() + " slot 1 must spawn facing slot 0. Expected " + expect1
                            + " but got " + slot1 + ".");
        }
    }

    @Test
    void aSlotWearingTheOtherSlotsYawIsExactlyTheOneEightyTheTesterReported() {
        ArenaPair p = SHIPPED[0];
        float mine = SpawnFacing.yaw(p.a().x(), p.a().z(), p.b().x(), p.b().z());
        float theirs = SpawnFacing.yaw(p.b().x(), p.b().z(), p.a().x(), p.a().z());

        assertEquals(180.0f, delta(mine, theirs), 1.0E-3f,
                "the two spawn yaws are exact opposites, so pairing slot 0's POSITION with slot"
                        + " 1's YAW anywhere in the handoff, the codec or the presenter is a clean"
                        + " 180 for BOTH players - that is the only shape of this bug that survives"
                        + " a correct SpawnFacing");
    }

    @Test
    void everyRoundResetHandsEachSlotBackItsOwnSpawnYawNotTheOtherOnes() {
        for (ArenaPair pair : SHIPPED) {
            float yaw0 = SpawnFacing.yaw(pair.a().x(), pair.a().z(), pair.b().x(), pair.b().z());
            float yaw1 = SpawnFacing.yaw(pair.b().x(), pair.b().z(), pair.a().x(), pair.a().z());

            GameState s = new GameState();
            seed(s.players[0], pair.a(), yaw0);
            seed(s.players[1], pair.b(), yaw1);
            s.roundsTarget = 3;
            s.roundInitial = new PlayerState[]{s.players[0].copy(), s.players[1].copy()};

            assertEquals(yaw0, s.roundInitial[0].yaw, 1.0E-4f,
                    pair.name() + " the frame-0 snapshot must carry slot 0's own spawn yaw");
            assertEquals(yaw1, s.roundInitial[1].yaw, 1.0E-4f,
                    pair.name() + " the frame-0 snapshot must carry slot 1's own spawn yaw");

            Arena arena = Arena.flat(pair.a().y());
            s.players[1].dead = true;
            s.players[0].yaw = 12.5f;
            s.players[1].yaw = -77.25f;
            s.players[0].x = 999.0;
            s.players[1].x = -999.0;
            for (int i = 0; i < Simulation.ROUND_RESET_TOTAL + 4; i++) {
                Simulation.tick(s, arena, Input.NONE, Input.NONE);
            }

            assertEquals(pair.a().x(), s.roundInitial[0].x, 1.0E-9,
                    pair.name() + " the spawn snapshot must never be rewritten by play");
            assertTrue(delta(s.roundInitial[0].yaw, vanillaLookAtYaw(pair.a(), pair.b())) < 1.0E-3f,
                    pair.name() + " after a full round reset slot 0's spawn facing must still be"
                            + " the look-at-the-opponent yaw for slot 0's OWN position");
            assertTrue(delta(s.roundInitial[1].yaw, vanillaLookAtYaw(pair.b(), pair.a())) < 1.0E-3f,
                    pair.name() + " after a full round reset slot 1's spawn facing must still be"
                            + " the look-at-the-opponent yaw for slot 1's OWN position");
        }
    }

    private static void seed(PlayerState p, Spawn at, float yaw) {
        p.x = at.x();
        p.y = at.y();
        p.z = at.z();
        p.yaw = yaw;
        p.pitch = 0f;
        p.onGround = true;
        p.health = 20f;
        p.maxHealth = 20f;
    }
}
