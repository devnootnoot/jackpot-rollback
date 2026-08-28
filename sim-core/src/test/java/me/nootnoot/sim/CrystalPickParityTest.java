package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.contract.HostFrameContract;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class CrystalPickParityTest {
    private static final double GROUND_Y = 64.0;

    private static final int SWORD_ITEM_ID = 7400;

    private static final int BX = 4;
    private static final int BY = 63;
    private static final int BZ = 0;

    private static final String EDGE_WAS =
            "EdgeInputSource.crystalUnderCrosshair cast the pick ray out to Combat.BLOCK_REACH"
                    + " (4.5) against a hand-built box inflated by a private CRYSTAL_INFLATE of"
                    + " 0.3, and measured the hit as (t * BLOCK_REACH) squared";

    private static final String MOD_WAS =
            "McInputSource cast the same pick from eye + look * attackMinReach out to attackReach"
                    + " (3.0 for a plain weapon) against the LIVE EndCrystal entity box, with the"
                    + " item's own attackHitboxMargin as the fallback inflate, and measured the hit"
                    + " as eye.distanceToSqr(hit)";

    private static final String WHY =
            "two reaches and two hitboxes for the SAME left click on the SAME crystal, in the mode"
                    + " the whole product is built on. " + EDGE_WAS + "; " + MOD_WAS;

    private static GameState armed(double x, double z) {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = x;
        a.y = GROUND_Y;
        a.z = z;
        a.yaw = -90f;
        a.pitch = 0f;
        a.onGround = true;
        a.health = 20f;
        TestKit.of(s).give(0, 0, 1, TestKit.item()
                .itemId(SWORD_ITEM_ID)
                .maxStack(1)
                .melee(6f, 1.6f)
                .flags(ItemDict.FLAG_SWORD));
        return s;
    }

    private static double pick(GameState s) {
        return HostFrameContract.crystalPickDistanceSq(s, s.players[0], 0, 1.0, 0.0, 0.0,
                BX, BY, BZ);
    }

    @Test
    void theCrystalPickIsBoundedByTheWeaponsOwnAttackReach() {
        GameState s = armed(0.0, 0.5);
        PlayerState a = s.players[0];
        double reach = Combat.attackPickReachAt(s, a, 0);

        assertTrue(reach < Combat.BLOCK_REACH,
                "a plain weapon picks entities at " + reach + " and blocks at "
                        + Combat.BLOCK_REACH + ", which is the whole of this bug: the edge picked"
                        + " the crystal with the BLOCK number. " + WHY);

        assertTrue(pick(s) < 0.0,
                "the near face of the crystal box at " + BX + " sits " + (BX - 0.5 - a.x)
                        + " blocks away, past the weapon's entity pick and inside BLOCK_REACH."
                        + " The edge named it and the mod could not. " + WHY);
    }

    @Test
    void bothHostsGetTheSameHitAndTheSameDistanceInsideReach() {
        GameState s = armed(2.5, 0.5);
        double d = pick(s);

        assertTrue(d >= 0.0, "the crystal is inside reach now, so the pick must name it");
        assertEquals(1.0, d, 1.0E-9,
                "and the distance both hosts arbitrate on is the SQUARED distance from the eye to"
                        + " the entry point, which is the metric the mod's melee pick already"
                        + " reports into HostFrameContract.leftClickTarget. Two hosts feeding that"
                        + " rule two different quantities is the same defect as two reaches. "
                        + WHY);
    }

    @Test
    void aCrystalYouAreStandingInsideIsStillPointBlank() {
        GameState s = armed(BX + 0.5, BZ + 0.5);

        assertEquals(0.0, pick(s), 0.0,
                "standing in the crystal leaves the ray with no entry point, and both hosts"
                        + " already agreed that counts as a zero-distance hit so you can blow up a"
                        + " crystal you are hugging. The shared rule keeps that");
    }

    @Test
    void thePickAgreesWithTheRangeTestTheSimWillRunOnTheFrame() {
        for (double x = 0.5; x < 6.0; x += 0.25) {
            GameState s = armed(x, 0.5);
            if (pick(s) < 0.0) {
                continue;
            }
            assertTrue(Combat.withinCrystalAttackRange(s, s.players[0], 0, BX, BY, BZ),
                    "a host that names a crystal the sim then refuses has wasted the player's"
                            + " click. The pick is bounded by attackPickReachAt and the sim's own"
                            + " test by attackPickReachAt + ENTITY_ATTACK_BUFFER, so every pick"
                            + " must clear it. x=" + x);
        }
    }
}
