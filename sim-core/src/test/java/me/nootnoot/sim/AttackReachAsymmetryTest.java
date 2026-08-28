package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class AttackReachAsymmetryTest {
    private static final double CLIENT_PICK_DEFAULT = 3.0;
    private static final double CLIENT_PICK_SPEAR = 4.625;
    private static final double SERVER_BOUND_DEFAULT = 6.0;
    private static final double SERVER_BOUND_SPEAR = 7.625;

    private static final int SWORD_ITEM_ID = 7200;
    private static final int SPEAR_ITEM_ID = 7201;

    private static final String CLIENT_SOURCE =
            "LocalPlayer.raycastHitResult: an item with no ATTACK_RANGE component falls through to"
                    + " pick(cameraEntity, blockInteractionRange(), entityInteractionRange(), partial),"
                    + " whose entity result is returned as filterHitResult(entityHitResult, from,"
                    + " entityInteractionRange) - buffer ZERO, so a vanilla client never picks an entity"
                    + " past the 3.0 ENTITY_INTERACTION_RANGE attribute";

    private static final String CLIENT_SPEAR_SOURCE =
            "LocalPlayer.raycastHitResult calls AttackRange.getClosesetHit for an item that HAS the"
                    + " component, and Minecraft.startAttack then re-gates the entity hit with"
                    + " customItemRange.isInRange(this.player, this.hitResult.getLocation()), which is"
                    + " isInRange(attacker, location::distanceToSqr, 0.0) = effectiveMaxRange + hitboxMargin"
                    + " + 0.0 = 4.5 + 0.125";

    private static final String SERVER_SOURCE =
            "ServerGamePacketListenerImpl.handleAttack calls"
                    + " this.player.isWithinAttackRange(mainHandItem, targetBounds, 3.0), and"
                    + " AttackRange.isInRange is distance <= effectiveMaxRange + hitboxMargin + extraBuffer;"
                    + " that +3.0 is LAG COMPENSATION for an honest 3-block hit, never permission to swing"
                    + " from 6 blocks";

    private static GameState armed(int flags) {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.health = 20f;
        TestKit.of(s).give(0, 0, 1, TestKit.item()
                .itemId((flags & ItemDict.FLAG_SPEAR) != 0 ? SPEAR_ITEM_ID : SWORD_ITEM_ID)
                .maxStack(1)
                .flags(flags));
        return s;
    }

    @Test
    void aNormalWeaponPicksAtThreeAndIsAuthorisedToSix() {
        GameState s = armed(ItemDict.FLAG_SWORD);
        PlayerState a = s.players[0];
        assertEquals(CLIENT_PICK_DEFAULT, Combat.attackPickReach(s, a), 1.0E-9, CLIENT_SOURCE);
        assertEquals(SERVER_BOUND_DEFAULT, Combat.attackReachLimit(s, a), 1.0E-9, SERVER_SOURCE);
    }

    @Test
    void aSpearPicksAtFourPointSixTwoFiveAndIsAuthorisedToSevenPointSixTwoFive() {
        GameState s = armed(ItemDict.FLAG_SPEAR);
        PlayerState a = s.players[0];
        assertEquals(CLIENT_PICK_SPEAR, Combat.attackPickReach(s, a), 1.0E-9, CLIENT_SPEAR_SOURCE);
        assertEquals(SERVER_BOUND_SPEAR, Combat.attackReachLimit(s, a), 1.0E-9,
                SERVER_SOURCE + "; Item.Properties().spear sets"
                        + " AttackRange(2.0F, 4.5F, 2.0F, 6.5F, 0.125F, 0.5F), so 4.5 + 0.125 + 3.0");
    }

    @Test
    void thePickAndTheBoundAreTwoNumbersAndMustStayTwoNumbers() {
        for (int flags : new int[]{ItemDict.FLAG_SWORD, ItemDict.FLAG_SPEAR}) {
            GameState s = armed(flags);
            PlayerState a = s.players[0];
            double pick = Combat.attackPickReach(s, a);
            double bound = Combat.attackReachLimit(s, a);
            assertNotEquals(pick, bound,
                    "the client pick and the server bound are NOT the same quantity: " + CLIENT_SOURCE
                            + "; " + SERVER_SOURCE);
            assertEquals(Combat.ENTITY_ATTACK_BUFFER, bound - pick, 1.0E-9,
                    "the ONLY difference between them is the handleAttack lag-compensation buffer;"
                            + " if this drifts, either the client got reach it should not have or the sim"
                            + " stopped compensating for latency");
            assertTrue(pick < bound,
                    "the modded client must pick no further than an unmodded one while the sim still"
                            + " accepts a laggy honest hit out to the buffered bound");
        }
    }

    @Test
    void thePickIsResolvedFromTheNamedSlotSoAHotbarSwitchIsNotDelayed() {
        GameState s = armed(ItemDict.FLAG_SWORD);
        PlayerState a = s.players[0];
        TestKit.of(s).give(0, 1, 1, TestKit.item()
                .itemId(SPEAR_ITEM_ID)
                .maxStack(1)
                .flags(ItemDict.FLAG_SPEAR));
        a.heldSlot = 0;
        assertEquals(CLIENT_PICK_DEFAULT, Combat.attackPickReachAt(s, a, 0), 1.0E-9,
                "slot 0 holds the sword, so it picks at the default attribute reach");
        assertEquals(CLIENT_PICK_SPEAR, Combat.attackPickReachAt(s, a, 1), 1.0E-9,
                "the client passes the LIVE hotbar slot it is about to send, because the head state's"
                        + " heldSlot lags it by the input-delay frames and would otherwise pick a fresh"
                        + " spear at the sword's 3.0 for several ticks");
        assertEquals(CLIENT_PICK_DEFAULT, Combat.attackPickReach(s, a), 1.0E-9,
                "the no-slot overload still reads the sim's own heldSlot, which is what the authority"
                        + " side must keep using");
    }
}
