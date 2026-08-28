package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import me.nootnoot.sim.state.ProjectileState;
import org.junit.jupiter.api.Test;

class PickupVolumeTest {
    private static final double GROUND_Y = 64.0;

    private static final int CARGO_ITEM_ID = 7300;
    private static final int ARROW_ITEM_ID = 7301;

    private static final int ARROW_PROJECTILE_ID = 11;

    private static GameState lone() {
        GameState g = new GameState();
        for (PlayerState p : g.players) {
            p.y = GROUND_Y;
            p.onGround = true;
            p.health = 20f;
            p.attackTicker = 100;
        }
        g.players[0].x = 0.0;
        g.players[0].z = 0.0;
        g.players[1].x = 40.0;
        return g;
    }

    private static boolean itemTaken(GameState s, Arena arena, double x, double y, double z) {
        int entry = TestKit.of(s).add(TestKit.item().itemId(CARGO_ITEM_ID));
        ItemEntities.spawn(s, 0, x, y, z, 0.0, 0.0, 0.0, entry, 0, CARGO_ITEM_ID, 1, 0);
        ItemEntities.tick(s, arena, Input.NONE, Input.NONE);
        return s.items.isEmpty();
    }

    @Test
    void anItemBeyondTheOldCylinderButInsideTheVanillaSweepIsPickedUp() {
        Arena arena = Arena.flat(GROUND_Y);
        assertTrue(itemTaken(lone(), arena, 1.4, GROUND_Y, 0.0),
                "PlayerEntity.tickMovement sweeps getBoundingBox().expand(1.0, 0.5, 1.0) and"
                        + " getOtherEntities intersects that against the item's own 0.25 wide box,"
                        + " so the axis reach is 0.3 + 1.0 + 0.125 = 1.425 blocks, not 1.0");
    }

    @Test
    void anItemPastTheVanillaSweepIsStillLeftOnTheGround() {
        Arena arena = Arena.flat(GROUND_Y);
        assertFalse(itemTaken(lone(), arena, 1.5, GROUND_Y, 0.0),
                "1.5 puts the item's near face at 1.375, past the 1.3 sweep face, so widening the"
                        + " volume to vanilla must not turn it into an unbounded vacuum");
    }

    @Test
    void theVanillaSweepIsABoxSoTheDiagonalReachesFurtherThanACylinderWould() {
        Arena arena = Arena.flat(GROUND_Y);
        assertTrue(itemTaken(lone(), arena, 0.95, GROUND_Y, 0.95),
                "Box.intersects is per axis, so a corner 1.34 blocks out is inside the sweep even"
                        + " though a radius 1.0 cylinder refuses it");
    }

    @Test
    void anItemBelowTheFeetIsInsideTheVanillaSweep() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        s.players[0].y = GROUND_Y + 0.7;
        assertTrue(itemTaken(s, arena, 0.0, GROUND_Y, 0.0),
                "expand(1.0, 0.5, 1.0) drops the sweep floor half a block below the feet, and the"
                        + " item box reaches 0.25 up, so 0.75 below the feet still counts");
    }

    @Test
    void anItemAboveTheVanillaSweepIsNoLongerPulledIn() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        s.players[0].y = GROUND_Y - 2.35;
        assertFalse(itemTaken(s, arena, 0.0, GROUND_Y, 0.0),
                "the sweep ceiling is the bounding box top plus 0.5, so 1.8 + 0.5 = 2.3 above the"
                        + " feet, and nothing above that is collected");
    }

    @Test
    void theSweepFollowsThePoseTheWayTheBoundingBoxDoes() {
        Arena arena = Arena.flat(GROUND_Y);

        GameState standing = lone();
        standing.players[0].y = GROUND_Y - 2.05;
        assertTrue(itemTaken(standing, arena, 0.0, GROUND_Y, 0.0),
                "control: standing the sweep reaches 2.3 above the feet");

        GameState sneaking = lone();
        sneaking.players[0].y = GROUND_Y - 2.05;
        sneaking.players[0].sneaking = true;
        assertFalse(itemTaken(sneaking, arena, 0.0, GROUND_Y, 0.0),
                "the sweep is built from getBoundingBox(), so a sneaking player's 1.5 box only"
                        + " reaches 2.0 and a fixed 1.8 would have been wrong for every pose");
    }

    private static ProjectileState stuckArrow(GameState s, double x, double y, double z) {
        int entry = TestKit.of(s).add(TestKit.item().itemId(ARROW_ITEM_ID)
                .flags(ItemDict.FLAG_ARROW_PLAIN));
        ProjectileState p = new ProjectileState();
        p.id = ARROW_PROJECTILE_ID;
        p.type = ProjectileState.TYPE_ARROW;
        p.owner = 0;
        p.x = x;
        p.y = y;
        p.z = z;
        p.stuck = true;
        p.fresh = false;
        p.arrowItemId = ARROW_ITEM_ID;
        p.arrowEntry = entry;
        s.projectiles.add(p);
        s.nextProjectileId = ARROW_PROJECTILE_ID + 1;
        return p;
    }

    private static boolean arrowTaken(GameState s, double x, double y, double z) {
        ProjectileState p = stuckArrow(s, x, y, z);
        Combat.resolveArrowPickups(s);
        return p.dead;
    }

    @Test
    void aStuckArrowIsCollectedOnTheSamePlayerSweepAnItemIs() {
        assertTrue(arrowTaken(lone(), 1.5, GROUND_Y, 0.0),
                "PersistentProjectileEntity.onPlayerCollision is driven by the one sweep in"
                        + " PlayerEntity.tickMovement, and an arrow is 0.5 wide, so the axis reach"
                        + " is 0.3 + 1.0 + 0.25 = 1.55 and not the old 0.8");
    }

    @Test
    void aStuckArrowPastTheVanillaSweepIsLeftInTheGround() {
        assertFalse(arrowTaken(lone(), 1.6, GROUND_Y, 0.0),
                "1.6 puts the arrow's near face at 1.35, past the 1.3 sweep face");
    }

    @Test
    void aStuckArrowBelowTheFeetIsInsideTheVanillaSweep() {
        assertTrue(arrowTaken(lone(), 0.0, GROUND_Y - 0.8, 0.0),
                "the arrow box is anchored at its position and stands 0.5 tall, so an arrow 0.8"
                        + " below the feet still overlaps the sweep floor at 0.5 below");
    }

    @Test
    void aStuckArrowWellBelowTheVanillaSweepIsNotCollected() {
        assertFalse(arrowTaken(lone(), 0.0, GROUND_Y - 1.1, 0.0),
                "at 1.1 below the feet the arrow's top face is 0.6 below, clear of the sweep");
    }

    @Test
    void collectingAStuckArrowStillPutsItBackInTheQuiver() {
        GameState s = lone();
        PlayerState owner = s.players[0];
        owner.arrowsConsumed = 1;

        assertTrue(arrowTaken(s, 1.5, GROUND_Y, 0.0), "control: the arrow is inside the sweep");
        assertEquals(0, owner.arrowsConsumed,
                "widening the volume must not change what a collected arrow does to the quiver");
        assertTrue(owner.arrows > 0, "the arrow has to land in the inventory, not vanish");
        assertEquals(ARROW_ITEM_ID, s.dict.itemId(owner.slotEntry[0]),
                "and it has to come back as the arrow it was, not as some other entry");
    }

    @Test
    void aStuckArrowIsNotCollectableUntilTheVanillaShakeHasRunDown() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        ProjectileState p = stuckArrow(s, 1.5, GROUND_Y, 0.0);
        p.life = Projectiles.STICK_LIFE;
        p.shakeTime = Projectiles.STICK_SHAKE_TICKS;

        for (int tick = 1; tick <= Projectiles.STICK_SHAKE_TICKS; tick++) {
            Projectiles.tick(s, arena, Input.NONE, Input.NONE);
            Combat.resolveArrowPickups(s);
            if (tick < Projectiles.STICK_SHAKE_TICKS) {
                assertFalse(p.dead,
                        "AbstractArrow.playerTouch is gated on (isInGround() || isNoPhysics()) &&"
                                + " shakeTime <= 0, and onHitBlock sets shakeTime = 7, so an arrow"
                                + " that just landed cannot be walked over for 7 ticks");
            }
        }

        assertTrue(p.dead,
                "and on the seventh decrement shakeTime reaches 0, which is exactly when vanilla"
                        + " starts letting the arrow be picked up");
    }

    @Test
    void anArrowThatSticksArmsTheShakeItself() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = lone();
        int entry = TestKit.of(s).add(TestKit.item().itemId(ARROW_ITEM_ID)
                .flags(ItemDict.FLAG_ARROW_PLAIN));
        ProjectileState p = new ProjectileState();
        p.id = ARROW_PROJECTILE_ID;
        p.type = ProjectileState.TYPE_ARROW;
        p.owner = 0;
        p.x = 0.5;
        p.y = GROUND_Y + 1.0;
        p.z = 0.0;
        p.vy = -2.0;
        p.fresh = false;
        p.arrowItemId = ARROW_ITEM_ID;
        p.arrowEntry = entry;
        s.projectiles.add(p);

        Projectiles.tick(s, arena, Input.NONE, Input.NONE);
        Combat.resolveArrowPickups(s);

        assertTrue(p.stuck, "the arrow drove into the ground slab");
        assertEquals(Projectiles.STICK_SHAKE_TICKS, p.shakeTime,
                "sticking has to arm the shake, or the gate is dead code");
        assertFalse(p.dead,
                "and it is lying inside the shooter's own pickup sweep, so without the shake it"
                        + " would have been collected on the very tick it landed");
    }

    @Test
    void aStuckArrowIsCollectableByAnyPlayerNotOnlyTheShooter() {
        GameState s = lone();
        PlayerState shooter = s.players[0];
        PlayerState other = s.players[1];
        other.x = 5.0;
        other.z = 0.0;
        shooter.arrowsConsumed = 1;

        ProjectileState p = stuckArrow(s, 5.5, GROUND_Y, 0.0);
        assertFalse(Combat.pickupSweep(shooter).intersects(
                        new me.nootnoot.sim.math.Aabb(p.x - 0.25, p.y, p.z - 0.25,
                                p.x + 0.25, p.y + 0.5, p.z + 0.25)),
                "the arrow is well outside the shooter's own sweep");

        Combat.resolveArrowPickups(s);

        assertTrue(p.dead,
                "AbstractArrow.playerTouch takes the Player that collided with it and tryPickup"
                        + " only reads this.pickup, never the owner, so ANY player walking over a"
                        + " Pickup.ALLOWED arrow takes it; restricting it to the shooter was ours");
        assertEquals(ARROW_ITEM_ID, s.dict.itemId(other.slotEntry[0]),
                "and it lands in the inventory of whoever picked it up");
        assertEquals(1, shooter.arrowsConsumed,
                "the shooter did not get an arrow back, so their spent count must not move");
    }

    @Test
    void theShooterStillGetsTheirOwnQuiverCreditBack() {
        GameState s = lone();
        PlayerState shooter = s.players[0];
        shooter.arrowsConsumed = 1;

        assertTrue(arrowTaken(s, 1.5, GROUND_Y, 0.0), "control: the arrow is inside their sweep");
        assertEquals(0, shooter.arrowsConsumed,
                "widening the taker set must not cost the owner their own bookkeeping");
    }

    @Test
    void theItemAndArrowSweepsAreTheSameOne() {
        GameState s = lone();
        PlayerState p = s.players[0];
        assertEquals(1.0, Combat.PICKUP_SWEEP_XZ, 0.0,
                "PlayerEntity.tickMovement expands by 1.0 horizontally");
        assertEquals(0.5, Combat.PICKUP_SWEEP_Y, 0.0, "and by 0.5 vertically");
        assertEquals(p.x - 1.3, Combat.pickupSweep(p).minX, 1.0E-9,
                "0.6 wide box, so the sweep face is 1.3 out");
        assertEquals(p.y + 2.3, Combat.pickupSweep(p).maxY, 1.0E-9,
                "1.8 tall box, so the sweep ceiling is 2.3 up");
    }
}
