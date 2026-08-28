package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.math.Vec3;
import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class AttackMinReachSymmetryTest {

    private static final double GROUND_Y = 64.0;
    private static final double LANE_Z = 0.5;

    private static final int SWORD_ITEM_ID = 7200;
    private static final int SPEAR_ITEM_ID = 7201;

    private static final String CLIENT_FLOOR =
            "ProjectileUtil.collectPiercingCollisions starts the attack ray at"
                    + " eyePos + look * AttackRange.getEffectiveMinRange(entity), so a vanilla"
                    + " CLIENT never picks an entity that sits entirely inside the item's min"
                    + " reach and never sends an attack packet for it. Item.Properties().spear"
                    + " sets AttackRange(2.0F, 4.5F, ...), so the floor is 2.0 for a spear and"
                    + " 0.0 for everything else";

    private static final String WHO_ENFORCED_THE_FLOOR =
            "ServerGamePacketListenerImpl.handleAttack validates with"
                    + " isWithinAttackRange(stack, box, 3.0), whose lower bound is"
                    + " minRange - hitboxMargin - 3.0 = 2.0 - 0.125 - 3.0, i.e. negative, so the"
                    + " vanilla SERVER enforces no minimum at all. That left the floor enforced"
                    + " in two different places on the two hosts: the mod's producer applied"
                    + " attackPickMinReachAt itself, and the edge inherited it only from the"
                    + " REMOTE vanilla client's own pick. One of those is ours and one of them is"
                    + " the thing being validated - a client that simply sends the attack packet"
                    + " anyway had no floor, because ClaimAuthority carried none. The floor now"
                    + " lives in ClaimAuthority.meleeClaim, which both hosts run over the"
                    + " replicated state, so it binds whoever holds the spear";

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

    private static GameState pointBlank(int flags) {
        GameState s = armed(flags);
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.y = GROUND_Y;
        a.z = LANE_Z;
        a.yaw = -90f;
        a.onGround = true;
        a.attackTicker = 100;

        PlayerState v = s.players[1];
        v.x = 0.4;
        v.y = GROUND_Y;
        v.z = LANE_Z;
        v.yaw = 90f;
        v.onGround = true;
        v.health = 20f;
        v.attackTicker = 100;
        return s;
    }

    @Test
    void anOrdinaryWeaponHasNoMinimumReach() {
        GameState s = armed(ItemDict.FLAG_SWORD);
        assertEquals(ItemDict.DEFAULT_ATTACK_MIN_RANGE,
                Combat.attackPickMinReachAt(s, s.players[0], 0), 1.0E-9, CLIENT_FLOOR);
        assertEquals(0.0, ItemDict.DEFAULT_ATTACK_MIN_RANGE, 1.0E-9,
                "an item with no ATTACK_RANGE component gets"
                        + " AttackRangeComponent.defaultForEntity, whose min_reach is 0.0F");
    }

    @Test
    void aSpearPicksNoCloserThanTwoBlocks() {
        GameState s = armed(ItemDict.FLAG_SPEAR);
        assertEquals(ItemDict.SPEAR_ATTACK_MIN_RANGE,
                Combat.attackPickMinReachAt(s, s.players[0], 0), 1.0E-9, CLIENT_FLOOR);
        assertEquals(2.0, ItemDict.SPEAR_ATTACK_MIN_RANGE, 1.0E-9, CLIENT_FLOOR);
    }

    @Test
    void theFloorIsResolvedFromTheNamedSlotLikeTheReachAbove() {
        GameState s = armed(ItemDict.FLAG_SWORD);
        PlayerState a = s.players[0];
        TestKit.of(s).give(0, 1, 1, TestKit.item()
                .itemId(SPEAR_ITEM_ID)
                .maxStack(1)
                .flags(ItemDict.FLAG_SPEAR));
        a.heldSlot = 0;
        assertEquals(ItemDict.DEFAULT_ATTACK_MIN_RANGE,
                Combat.attackPickMinReachAt(s, a, 0), 1.0E-9,
                "slot 0 holds the sword");
        assertEquals(ItemDict.SPEAR_ATTACK_MIN_RANGE,
                Combat.attackPickMinReachAt(s, a, 1), 1.0E-9,
                "the host passes the LIVE hotbar slot for the floor for the same reason it does"
                        + " for the ceiling: the head state's heldSlot lags it by the input delay"
                        + " frames, so a fresh spear would keep the sword's floor for several"
                        + " ticks and pick targets an unmodded client refuses");
    }

    @Test
    void theSimBoundCarriesTheFloorSoBothHostsObeyIt() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState sword = pointBlank(ItemDict.FLAG_SWORD);
        assertNotNull(ClaimAuthority.meleeClaim(sword, arena, sword.players[0], sword.players[1]),
                "an ordinary weapon has a floor of " + ItemDict.DEFAULT_ATTACK_MIN_RANGE
                        + ", so point blank is exactly where it is supposed to work. Moving the"
                        + " spear floor into the claim must not cost every other item its"
                        + " close-range hit");

        GameState spear = pointBlank(ItemDict.FLAG_SPEAR);
        assertNull(ClaimAuthority.meleeClaim(spear, arena, spear.players[0], spear.players[1]),
                WHO_ENFORCED_THE_FLOOR);
    }

    @Test
    void theSpearStillReachesPastItsOwnFloor() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = pointBlank(ItemDict.FLAG_SPEAR);
        PlayerState v = s.players[1];
        v.x = 3.0;

        double floor = ClaimAuthority.meleeMinLimit(s, s.players[0]);
        assertEquals(ItemDict.SPEAR_ATTACK_MIN_RANGE, floor, 1.0E-9, WHO_ENFORCED_THE_FLOOR);
        assertNotNull(ClaimAuthority.meleeClaim(s, arena, s.players[0], v),
                "the floor is a dead zone, not a nerf: past " + floor + " blocks the spear must"
                        + " still claim everything ClaimAuthority.meleeLimit grants it, all the"
                        + " way out to " + ClaimAuthority.meleeLimit(s, s.players[0]));
    }

    @Test
    void theFloorIsMeasuredOnTheSameQuantityTheCeilingIs() {
        GameState s = pointBlank(ItemDict.FLAG_SPEAR);
        PlayerState a = s.players[0];
        double eyeY = a.y + Combat.eyeHeight(a);
        double floor = ClaimAuthority.meleeMinLimit(s, a);

        assertTrue(ClaimAuthority.beyondMinLimit(a.x, eyeY, a.z,
                new Vec3(a.x + floor, eyeY, a.z), floor),
                "a contact point exactly AT the floor is inside the band vanilla's ray starts"
                        + " from, so it must be granted, not refused");
        assertTrue(!ClaimAuthority.beyondMinLimit(a.x, eyeY, a.z,
                new Vec3(a.x + floor - 0.001, eyeY, a.z), floor),
                "one millimetre inside the floor is the whole of the rule: vanilla's client pick"
                        + " starts the ray at eye + look * minRange, so nothing nearer than that"
                        + " can be named. " + WHO_ENFORCED_THE_FLOOR);
    }

    @Test
    void theFloorIsSmallerThanTheCeilingForEveryItemTheKitsCarry() {
        for (int flags : new int[]{ItemDict.FLAG_SWORD, ItemDict.FLAG_AXE, ItemDict.FLAG_MACE,
                ItemDict.FLAG_SPEAR}) {
            GameState s = armed(flags);
            PlayerState a = s.players[0];
            double floor = Combat.attackPickMinReachAt(s, a, 0);
            double ceiling = Combat.attackPickReachAt(s, a, 0);
            assertTrue(floor < ceiling,
                    "a floor that met the ceiling would make the weapon unable to hit anything"
                            + " on EITHER host, now that ClaimAuthority.meleeClaim is the one"
                            + " place the floor is applied");
            assertTrue(floor >= 0.0, CLIENT_FLOOR);
        }
    }
}
