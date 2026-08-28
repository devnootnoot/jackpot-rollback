package me.nootnoot.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.nootnoot.sim.state.Arena;
import me.nootnoot.sim.state.BlockStore;
import me.nootnoot.sim.state.CombatEvent;
import me.nootnoot.sim.state.CrystalState;
import me.nootnoot.sim.state.GameState;
import me.nootnoot.sim.state.Input;
import me.nootnoot.sim.state.ItemDict;
import me.nootnoot.sim.state.PlayerState;
import org.junit.jupiter.api.Test;

class ExplosionGeometryTest {
    private static final double GROUND_Y = 64.0;

    private static final int OBSIDIAN_ITEM_ID = 900;
    private static final float OBSIDIAN_RESISTANCE = 1200f;
    private static final int ANCHOR_ITEM_ID = 901;
    private static final int EMPTY_BUCKET_ITEM_ID = 910;
    private static final int WATER_BUCKET_ITEM_ID = 911;

    private static GameState duel() {
        GameState s = new GameState();
        PlayerState a = s.players[0];
        a.x = 0.5;
        a.y = GROUND_Y;
        a.z = 0.5;
        a.yaw = -90f;
        a.onGround = true;
        a.health = 20f;
        PlayerState v = s.players[1];
        v.x = 10_000.0;
        v.y = GROUND_Y;
        v.z = 0.5;
        v.onGround = true;
        v.health = 20f;
        return s;
    }

    private static void obsidian(GameState s, int x, int y, int z) {
        s.blocks.place(x, y, z, OBSIDIAN_ITEM_ID);
        s.blockResistance.put(BlockStore.key(x, y, z), OBSIDIAN_RESISTANCE);
    }

    private static void obsidianWall(GameState s, int x) {
        for (int y = 64; y <= 68; y++) {
            for (int z = -3; z <= 4; z++) {
                obsidian(s, x, y, z);
            }
        }
    }

    private static void crystal(GameState s, int bx, int by, int bz) {
        CrystalState c = new CrystalState();
        c.id = s.nextCrystalId++;
        c.bx = bx;
        c.by = by;
        c.bz = bz;
        s.crystals.add(c);
    }

    private static void anchor(GameState s, int x, int y, int z, int charge) {
        s.blocks.place(x, y, z, ANCHOR_ITEM_ID);
        s.anchors.put(BlockStore.key(x, y, z), charge);
    }

    private static void giveBucket(GameState s, boolean full) {
        TestKit kit = TestKit.of(s);
        int empty = kit.add(TestKit.item().itemId(EMPTY_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_EMPTY));
        int water = kit.add(TestKit.item().itemId(WATER_BUCKET_ITEM_ID).maxStack(1)
                .flags(ItemDict.FLAG_BUCKET_WATER));
        kit.put(0, 0, full ? water : empty, 1);
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

    private static double blastDistance(PlayerState p, double cx, double cy, double cz) {
        double dx = p.x - cx;
        double dy = p.y - cy;
        double dz = p.z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static final float SMALL_POWER = 1.0f;


    private static GameState inTheOpen() {
        GameState s = duel();
        PlayerState a = s.players[0];
        a.x = 0.0;
        a.y = 100.0;
        a.z = 0.0;
        a.onGround = false;
        return s;
    }

    @Test
    void aBlastCentredOnTheOldMidBodyProbePointStillHurts() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = inTheOpen();
        PlayerState a = s.players[0];

        Combat.explode(s, arena, a.x, a.y + Combat.EYE_STANDING * 0.5, a.z, SMALL_POWER, 0, false);

        assertTrue(a.health < 20f,
                "ServerExplosion.hurtEntities skips an entity only when the vector it normalises"
                        + " for the push has zero length, and that vector runs to getEyeY(), so"
                        + " the only point that skips a player is their eye, not a probe point"
                        + " 0.81 above their feet");
    }

    @Test
    void theFalloffIsMeasuredFromTheEntityPositionAndThePushFromTheEye() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = inTheOpen();
        PlayerState a = s.players[0];
        a.vx = 0.0;
        a.vy = 0.0;
        a.vz = 0.0;

        double q = SMALL_POWER * 2.0;
        Combat.explode(s, arena, a.x + 1.0, a.y, a.z, SMALL_POWER, 0, false);

        double t = 1.0 - 1.0 / q;
        double expected = (t * t + t) / 2.0 * 7.0 * q + 1.0;
        assertEquals(20.0 - expected, a.health, 1.0E-4,
                "getEntityDamageAmount divides Math.sqrt(entity.distanceToSqr(vec3)) by radius*2,"
                        + " and distanceToSqr(Vec3) runs off position(), which is the feet: one"
                        + " block away horizontally is one block, not 1.287");

        assertEquals(Combat.EYE_STANDING, (a.vy - Combat.EXPLOSION_EXTRA_LIFT) / -a.vx, 1.0E-9,
                "but the push is built from (entity instanceof PrimedTnt ? entity.getY() :"
                        + " entity.getEyeY()) - this.center.y, so the vertical term of the push"
                        + " has to be the eye height while the falloff term is the feet."
                        + " EXPLOSION_EXTRA_LIFT is this sim's own addition on top of that:"
                        + " ServerExplosion.hurtEntities pushes by direction.scale(power) alone");
    }

    @Test
    void aCrystalCaughtInABlastIsRemovedWithoutDetonatingInTurn() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        s.players[0].x = -2.0;
        crystal(s, 0, 64, 0);
        crystal(s, 11, 64, 0);

        assertTrue(11.0 <= Combat.CRYSTAL_POWER * 2.0,
                "the second crystal has to sit inside the blast radius or this proves nothing");

        Simulation.tick(s, arena,
                new Input(false, false, false, false, false, false, false, true, false, 0f, 0f, 0)
                        .withBlockAction(Input.BLOCK_HIT_CRYSTAL, 0, 64, 0),
                Input.NONE);

        assertTrue(s.crystals.isEmpty(),
                "vanilla ServerExplosion.hurtEntities destroys every crystal inside radius*2, and"
                        + " EndCrystal.hurtServer ignores the damage amount, so both are gone");
        assertEquals(1, explosions(s),
                "but EndCrystal.hurtServer only re-explodes when the source is NOT in"
                        + " DamageTypeTags.IS_EXPLOSION, and an explosion's own damage source is,"
                        + " so a crystal popped by a blast never detonates in turn");
    }

    @Test
    void aBlastCannotBeRelayedThroughObsidianByACrystalOnTheFarSide() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        s.players[0].x = -2.0;
        PlayerState v = s.players[1];
        v.x = 14.0;

        crystal(s, 0, 64, 0);
        crystal(s, 11, 64, 0);
        obsidianWall(s, 5);

        double q = Combat.CRYSTAL_POWER * 2.0;
        assertTrue(blastDistance(v, 0.5, 65.0, 0.5) > q,
                "the victim has to sit outside the blast that was actually thrown");
        assertTrue(blastDistance(v, 11.5, 65.0, 0.5) < q,
                "and well inside the one the chain used to manufacture on the far side of the wall");

        Simulation.tick(s, arena,
                new Input(false, false, false, false, false, false, false, true, false, 0f, 0f, 0)
                        .withBlockAction(Input.BLOCK_HIT_CRYSTAL, 0, 64, 0),
                Input.NONE);

        assertEquals(20f, v.health, 1.0E-6f,
                "a blast may not be teleported across a solid wall by a crystal standing behind it");
        assertTrue(s.blocks.contains(5, 66, 0),
                "and the wall itself has to have survived, or the two sides were never separated");
        assertEquals(1, explosions(s), "exactly one explosion happened: the one that was thrown");
    }

    @Test
    void aChargedAnchorBehindAWallCannotBeDetonated() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        PlayerState a = s.players[0];
        anchor(s, 3, 64, 0, 1);
        obsidianWall(s, 2);

        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_DETONATE_ANCHOR, 3, 64, 0), Input.NONE);

        assertNotNull(s.anchors.get(BlockStore.key(3, 64, 0)),
                "an anchor you cannot see is an anchor you cannot set off");
        assertEquals(20f, a.health, 1.0E-6f, "and nothing may have gone off in your own face");
        assertEquals(0, explosions(s));
    }

    @Test
    void aChargedAnchorInTheOpenIsStillDetonated() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        anchor(s, 3, 64, 0, 1);

        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_DETONATE_ANCHOR, 3, 64, 0), Input.NONE);

        assertNull(s.anchors.get(BlockStore.key(3, 64, 0)),
                "control: the sightline test must not refuse a detonation with nothing in the way");
        assertEquals(1, explosions(s));
    }

    @Test
    void aFullyChargedAnchorBehindAWallCannotBeDetonatedByChargingItAgain() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        anchor(s, 3, 64, 0, Combat.ANCHOR_MAX_CHARGE);
        obsidianWall(s, 2);

        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_CHARGE_ANCHOR, 3, 64, 0), Input.NONE);

        assertNotNull(s.anchors.get(BlockStore.key(3, 64, 0)),
                "the charge action detonates a full anchor, so it needs the same sightline gate");
        assertEquals(0, explosions(s));
    }

    @Test
    void aWaterBucketCannotBeEmptiedIntoACellBehindAWall() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        giveBucket(s, true);
        obsidianWall(s, 2);

        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_PLACE_WATER, 4, 64, 0).withHeldSlot(0),
                Input.NONE);

        assertNull(s.fluids.get(BlockStore.key(4, 64, 0)),
                "vanilla resolves a bucket against its own server raycast from the eye, so a cell"
                        + " the player cannot see is a cell the player cannot fill");
        assertTrue(s.dict.isBucketWater(s.players[0].slotEntry[0]),
                "and the bucket must not have been spent");
    }

    @Test
    void aWaterBucketInTheOpenIsStillEmptied() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        giveBucket(s, true);

        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_PLACE_WATER, 4, 64, 0).withHeldSlot(0),
                Input.NONE);

        assertNotNull(s.fluids.get(BlockStore.key(4, 64, 0)),
                "control: the sightline test must not refuse a pour the player really made");
        assertTrue(s.dict.isBucketEmpty(s.players[0].slotEntry[0]));
    }

    @Test
    void anEmptyBucketCannotScoopASourceBehindAWall() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        giveBucket(s, false);
        assertTrue(Fluids.place(s, arena, 0, Fluids.WATER, 4, 64, 0));
        obsidianWall(s, 2);

        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_PICKUP_FLUID, 4, 64, 0).withHeldSlot(0),
                Input.NONE);

        assertNotNull(s.fluids.get(BlockStore.key(4, 64, 0)),
                "a source you cannot see is a source you cannot pick up");
        assertTrue(s.dict.isBucketEmpty(s.players[0].slotEntry[0]));
    }

    @Test
    void anEmptyBucketInTheOpenStillScoopsTheSource() {
        Arena arena = Arena.flat(GROUND_Y);
        GameState s = duel();
        giveBucket(s, false);
        assertTrue(Fluids.place(s, arena, 0, Fluids.WATER, 4, 64, 0));

        Simulation.tick(s, arena,
                Input.NONE.withBlockAction(Input.BLOCK_PICKUP_FLUID, 4, 64, 0).withHeldSlot(0),
                Input.NONE);

        assertNull(s.fluids.get(BlockStore.key(4, 64, 0)),
                "control: the sightline test must not refuse a scoop the player really made");
        assertFalse(s.dict.isBucketEmpty(s.players[0].slotEntry[0]));
    }
}
