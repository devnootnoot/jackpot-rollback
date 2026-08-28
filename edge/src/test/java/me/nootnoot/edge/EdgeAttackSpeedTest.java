package me.nootnoot.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EdgeAttackSpeedTest {

    private static final double VANILLA = EdgeAttackSpeed.VANILLA_BASE;

    @Test
    void theVanillaPlayerBaseIsFour() {
        assertEquals(4.0, EdgeAttackSpeed.VANILLA_BASE,
                "net.minecraft.world.entity.ai.attributes.Attributes declares"
                        + " DEFAULT_ATTACK_SPEED = 4.0 and registers attack_speed as"
                        + " new RangedAttribute(\"attribute.name.attack_speed\", 4.0, 0.0, 1024.0);"
                        + " Player.createAttributes() adds it with no override, so a player's base"
                        + " is 4.0");
        assertEquals(1024.0, EdgeAttackSpeed.MAX_BASE,
                "1024.0 is the RangedAttribute ceiling, and the exact value mcleagues-core"
                        + " GameTeam.applyCustomKitState writes for a spam-hits custom kit");
    }

    @Test
    void aCleanAccountIsLeftAlone() {
        assertEquals(EdgeAttackSpeed.Verdict.CLEAN, EdgeAttackSpeed.verdict(VANILLA, VANILLA));
    }

    @Test
    void aShiftedBaseIsTheDamageThisEdgeUsedToPersist() {
        for (double melee : new double[]{1.6, 1.0, 0.6, 1.2, 3.9}) {
            assertEquals(EdgeAttackSpeed.Verdict.DAMAGED,
                    EdgeAttackSpeed.verdict(EdgeStatusMirror.correctedBase(VANILLA, VANILLA, melee),
                            VANILLA),
                    "a held-item melee speed of " + melee + " shifted the base to"
                            + " base + (want - live), which is the shape of the damage");
        }
    }

    @Test
    void aBaseAnotherFeatureOwnsIsNeverClobbered() {
        assertEquals(EdgeAttackSpeed.Verdict.FOREIGN,
                EdgeAttackSpeed.verdict(1024.0, VANILLA),
                "mcleagues-core sets 1024.0 for a CUSTOM kit with spam-hits on. The edge can never"
                        + " produce that value, so it must be reported and left alone");
        assertEquals(EdgeAttackSpeed.Verdict.FOREIGN, EdgeAttackSpeed.verdict(16.0, VANILLA));
        assertEquals(EdgeAttackSpeed.Verdict.FOREIGN, EdgeAttackSpeed.verdict(0.0, VANILLA));
        assertEquals(EdgeAttackSpeed.Verdict.FOREIGN, EdgeAttackSpeed.verdict(-2.0, VANILLA));
    }

    @Test
    void theRepairBandCoversEveryShiftTheMirrorCanWrite() {
        double slowest = EdgeStatusMirror.correctedBase(VANILLA, VANILLA, 0.6);
        double fastest = EdgeStatusMirror.correctedBase(VANILLA, VANILLA, 4.0);
        assertEquals(EdgeAttackSpeed.Verdict.DAMAGED, EdgeAttackSpeed.verdict(slowest, VANILLA));
        assertEquals(EdgeAttackSpeed.Verdict.CLEAN, EdgeAttackSpeed.verdict(fastest, VANILLA),
                "a fist-speed hold shifts nothing, so it is not damage");
    }
}
