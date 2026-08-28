package me.nootnoot.sim.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.Random;
import me.nootnoot.sim.state.Authority;
import me.nootnoot.sim.state.Clicks;
import me.nootnoot.sim.state.Input;
import org.junit.jupiter.api.Test;

class InputCodecTest {

    private static final float[] WILD_ANGLES = {
            0f, -0f, 1f, -1f, 89.9f, 90f, 90.0001f, -90f, -90.0001f,
            179.9f, 180f, -180f, 180.0001f, -180.0001f, 359.9f, 360f, -360f,
            540f, -540f, 719.9f, -719.9f,
            Math.nextDown(180f), Math.nextUp(-180f), Math.nextDown(-180f), Math.nextUp(180f),
            Input.MAX_WRAPPABLE_YAW, -Input.MAX_WRAPPABLE_YAW,
            Math.nextUp(Input.MAX_WRAPPABLE_YAW), -Math.nextUp(Input.MAX_WRAPPABLE_YAW),
            1.0e9f, -1.0e9f, 1.0e30f, -1.0e30f,
            Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, -Float.MIN_VALUE,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
    };

    private static final int[] WILD_INTS = {
            0, 1, -1, 8, 9, -8, 127, 128, 255, 256, -255, -256,
            Input.BLOCK_NONE, Input.BLOCK_CLOSE_CONTAINER, Input.BLOCK_CLOSE_CONTAINER + 1,
            Input.INV_NONE, Input.INV_CONTAINER_PUT, Input.INV_CONTAINER_PUT + 1,
            Input.NO_PROJECTILE_HIT, Input.PROJECTILE_HIT_SELF,
            Input.PROJECTILE_HIT_SELF | Input.PROJECTILE_HIT_ID_MASK,
            Short.MAX_VALUE, Short.MIN_VALUE, 0x12345, -0x12345,
            Input.MAX_TARGET_COORD, -Input.MAX_TARGET_COORD,
            Input.MAX_TARGET_COORD + 1, -Input.MAX_TARGET_COORD - 1,
            1 << 25, -(1 << 25), Integer.MAX_VALUE, Integer.MIN_VALUE
    };

    private static final double[] WILD_DOUBLES = {
            0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 64.0, -80.25, 1.0e9, -1.0e9,
            Double.MIN_VALUE, -Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
    };

    private static Input roundTrip(Input in) {
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, in);
        assertEquals(InputCodec.BYTES, b.position(), "encoded length must equal InputCodec.BYTES");
        b.flip();
        Input out = InputCodec.read(b);
        assertEquals(0, b.remaining(), "decoder must consume exactly InputCodec.BYTES");
        return out;
    }

    private static Input rebuild(Input x) {
        return new Input(x.forward(), x.back(), x.left(), x.right(), x.jump(), x.sprint(),
                x.sneak(), x.attack(), x.use(), x.usePress(), x.offhandUse(), x.offhandUsePress(),
                x.meleeHit(), x.dropItem(), x.dropStack(), x.swapHands(), x.yaw(), x.pitch(),
                x.heldSlot(),
                x.blockAction(), x.targetX(), x.targetY(), x.targetZ(), x.projectileHit(),
                x.invAction(), x.invSrc(), x.invDst(), x.authority(), x.clicks())
                .withCrystalHit(x.crystalHit(), x.crystalX(), x.crystalY(), x.crystalZ());
    }

    private static void assertSurvivesTheWire(Input in) {
        assertEquals(in, rebuild(in), "the canonical form must be a fixed point of the constructor");
        assertEquals(in, roundTrip(in), "read(write(x)) must equal x");
        assertEquals(0, Float.compare(in.yaw(), Input.wrapYaw(in.yaw())), "yaw was not a fixed point");
        assertEquals(0, Float.compare(in.pitch(), Input.clampPitch(in.pitch())),
                "pitch was not a fixed point");
        assertTrue(in.yaw() >= -180f && in.yaw() < 180f, "yaw escaped its canonical range");
        assertTrue(in.pitch() >= -90f && in.pitch() <= 90f, "pitch escaped its canonical range");
    }

    private static float angle(Random r) {
        int roll = r.nextInt(4);
        if (roll == 0) {
            return WILD_ANGLES[r.nextInt(WILD_ANGLES.length)];
        }
        if (roll == 1) {
            return Float.intBitsToFloat(r.nextInt());
        }
        if (roll == 2) {
            return (r.nextFloat() - 0.5f) * 720f;
        }
        return (r.nextFloat() - 0.5f) * 4.0e4f;
    }

    private static int wildInt(Random r) {
        int roll = r.nextInt(3);
        if (roll == 0) {
            return WILD_INTS[r.nextInt(WILD_INTS.length)];
        }
        if (roll == 1) {
            return r.nextInt(1024) - 512;
        }
        return r.nextInt();
    }

    private static double coordinate(Random r) {
        int roll = r.nextInt(3);
        if (roll == 0) {
            return WILD_DOUBLES[r.nextInt(WILD_DOUBLES.length)];
        }
        if (roll == 1) {
            return Double.longBitsToDouble(r.nextLong());
        }
        return (r.nextDouble() - 0.5) * 6.0e7;
    }

    private static Authority authority(Random r) {
        switch (r.nextInt(5)) {
            case 0:
                return null;
            case 1:
                return Authority.NONE;
            case 2:
                return new Authority(false, coordinate(r), coordinate(r), coordinate(r),
                        r.nextBoolean());
            default:
                return Authority.at(coordinate(r), coordinate(r), coordinate(r), r.nextBoolean());
        }
    }

    private static Input random(Random r) {
        return new Input(r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(),
                r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(),
                r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(), r.nextBoolean(),
                r.nextBoolean(), r.nextBoolean(), angle(r), angle(r), wildInt(r), wildInt(r),
                wildInt(r), wildInt(r),
                wildInt(r), wildInt(r), wildInt(r), wildInt(r), wildInt(r), authority(r),
                new Clicks(wildInt(r), wildInt(r), wildInt(r), wildInt(r), 0));
    }

    private static Input base(Authority authority) {
        return new Input(true, false, true, false, true, true, false, true, true, true, true,
                true, true, false, false, true, -91.5f, -12.25f, 3,
                Input.BLOCK_PLACE, 12, -34, 56, Input.NO_PROJECTILE_HIT,
                Input.INV_MOVE, 7, 40, authority, new Clicks(2, 1, 3, 1, 0));
    }

    @Test
    void anOrdinaryInputSurvivesTheWireUnchanged() {
        assertSurvivesTheWire(base(Authority.at(120.5, 64.0, -80.25, true)));
    }

    @Test
    void everyFieldOfTheLegalDomainSurvivesTheWireUnchanged() {
        assertSurvivesTheWire(new Input(false, true, false, true, false, false, true, false, false,
                false, false, true, false, true, true, false, 179.9f, 90f, 8,
                Input.BLOCK_CLOSE_CONTAINER, Input.MAX_TARGET_COORD, -Input.MAX_TARGET_COORD, 0,
                0x4001, Input.INV_CONTAINER_PUT, 255, 0, Authority.NONE,
                new Clicks(Clicks.MAX, Clicks.MAX, Clicks.MAX, Clicks.MAX, 0)));
    }

    @Test
    void anyInputAtAllIsAFixedPointOfTheCodec() {
        Random r = new Random(0xC0FFEEL);
        for (int i = 0; i < 20000; i++) {
            assertSurvivesTheWire(random(r));
        }
    }

    @Test
    void everyWildAngleCombinationIsAFixedPointOfTheCodec() {
        for (float yaw : WILD_ANGLES) {
            for (float pitch : WILD_ANGLES) {
                assertSurvivesTheWire(new Input(false, false, false, false, false, false, false,
                        false, false, yaw, pitch, 0));
            }
        }
    }

    @Test
    void everyWildIntegerCombinationIsAFixedPointOfTheCodec() {
        for (int v : WILD_INTS) {
            assertSurvivesTheWire(new Input(true, true, true, true, true, true, true, true, true,
                    true, true, true, true, true, true, true, 45.5f, -30f, v,
                    v, v, v, v, v, v, v, v, Authority.at(v, -v, v * 0.5, true),
                    new Clicks(v, v, v, v, 0)));
        }
    }

    @Test
    void everyWildCoordinateIsAFixedPointOfTheCodec() {
        for (double v : WILD_DOUBLES) {
            assertSurvivesTheWire(base(Authority.at(v, v, v, true)));
            assertSurvivesTheWire(base(Authority.at(v, -v, 0.0, false)));
            assertSurvivesTheWire(base(new Authority(false, v, v, v, true)));
        }
    }

    @Test
    void wrappingAYawTwiceChangesNothing() {
        for (float yaw : WILD_ANGLES) {
            float once = Input.wrapYaw(yaw);
            assertEquals(0, Float.compare(once, Input.wrapYaw(once)), "wrapYaw is not idempotent");
        }
        Random r = new Random(0xBEEFL);
        for (int i = 0; i < 200000; i++) {
            float yaw = angle(r);
            float once = Input.wrapYaw(yaw);
            assertTrue(once >= -180f && once < 180f, "wrapYaw escaped its range for " + yaw);
            assertEquals(0, Float.compare(once, Input.wrapYaw(once)),
                    "wrapYaw is not idempotent for " + yaw);
        }
    }

    @Test
    void aYawTooLargeToWrapWithoutLosingBitsIsRejected() {
        assertEquals(0f, Input.wrapYaw(Math.nextUp(Input.MAX_WRAPPABLE_YAW)));
        assertEquals(0f, Input.wrapYaw(-Math.nextUp(Input.MAX_WRAPPABLE_YAW)));
        assertEquals(0f, Input.wrapYaw(1.0e30f));
        assertEquals(0f, Input.wrapYaw(Float.MAX_VALUE));
        assertEquals(0f, Input.wrapYaw(Float.NaN));
        assertEquals(0f, Input.wrapYaw(Float.POSITIVE_INFINITY));
        assertEquals(0f, Input.wrapYaw(Float.NEGATIVE_INFINITY));
    }

    @Test
    void theEncodedLengthIsExactlyTheAdvertisedWidth() {
        assertEquals(67, InputCodec.BYTES);
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, Input.NONE);
        assertEquals(InputCodec.BYTES, b.position());
    }

    @Test
    void anOutOfRangeSlotSelectionIsClampedIdenticallyOnBothSides() {
        Input in = Input.NONE.withHeldSlot(200);
        assertEquals(8, roundTrip(in).heldSlot());
    }

    @Test
    void anUnknownBlockOpcodeDecodesAsNoAction() {
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, Input.NONE.withBlockAction(200, 1, 2, 3));
        b.flip();
        assertEquals(Input.BLOCK_NONE, InputCodec.read(b).blockAction());
    }

    @Test
    void anUnknownInventoryOpcodeDecodesAsNoAction() {
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, Input.NONE.withInvAction(200, 1, 2));
        b.flip();
        assertEquals(Input.INV_NONE, InputCodec.read(b).invAction());
    }

    @Test
    void aWildTargetCoordinateCannotAliasOntoAnotherCell() {
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, Input.NONE.withBlockAction(Input.BLOCK_BREAK,
                Integer.MAX_VALUE, Integer.MIN_VALUE, 1 << 25));
        b.flip();
        Input decoded = InputCodec.read(b);
        assertEquals(Input.MAX_TARGET_COORD, decoded.targetX());
        assertEquals(-Input.MAX_TARGET_COORD, decoded.targetY());
        assertEquals(Input.MAX_TARGET_COORD, decoded.targetZ());
    }

    @Test
    void aWildLookAngleIsNormalisedIdenticallyOnBothSides() {
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, new Input(false, false, false, false, false, false, false, false,
                false, 1.0e30f, -400f, 0));
        b.flip();
        Input decoded = InputCodec.read(b);
        assertTrue(decoded.yaw() >= -180f && decoded.yaw() < 180f, "yaw was not wrapped");
        assertEquals(-90f, decoded.pitch());
    }

    @Test
    void aNonFiniteLookAngleIsScrubbedByTheConstructor() {
        Input in = new Input(false, false, false, false, false, false, false, false,
                false, Float.NaN, Float.POSITIVE_INFINITY, 0);
        assertEquals(0f, in.yaw());
        assertEquals(0f, in.pitch());
        assertSurvivesTheWire(in);
    }

    @Test
    void aNonFiniteAuthorityIsScrubbedByTheConstructorSoBothHostsSeeTheSameZero() {
        Input in = base(Authority.at(Double.NaN, Double.POSITIVE_INFINITY, 5.0, false));
        assertEquals(0.0, in.authority().x());
        assertEquals(0.0, in.authority().y());
        assertEquals(5.0, in.authority().z());
        Authority decoded = roundTrip(in).authority();
        assertTrue(decoded.present());
        assertEquals(0.0, decoded.x());
        assertEquals(0.0, decoded.y());
        assertEquals(5.0, decoded.z());
    }

    @Test
    void anAbsentAuthorityStaysAbsent() {
        assertEquals(Authority.NONE, roundTrip(base(Authority.NONE)).authority());
    }

    @Test
    void anAbsentAuthorityCarryingCoordinatesCollapsesBeforeItIsEverSimulated() {
        Input in = base(new Authority(false, 12.5, -64.0, 900.25, true));
        assertEquals(Authority.NONE, in.authority());
        assertSurvivesTheWire(in);
    }

    @Test
    void aNullAuthorityBecomesTheAbsentAuthority() {
        Input in = base(null);
        assertNotNull(in.authority());
        assertEquals(Authority.NONE, in.authority());
        assertSurvivesTheWire(in);
    }

    @Test
    void theTopFlagBitIsTheElytraDeployChannel() {
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, Input.NONE);
        byte[] raw = b.array();
        raw[0] = (byte) 0x80;
        ByteBuffer tampered = ByteBuffer.wrap(raw);
        assertEquals(Input.NONE.withElytraStart(true), InputCodec.read(tampered),
                "bits 18 to 29 of the flag word are the four click counts, bit 30 is the crystal"
                        + " hit channel and bit 31 is the elytra deploy; nothing else may move");
    }

    @Test
    void theCrystalHitChannelSurvivesTheWireBesideABlockAction() {
        Input in = base(Authority.NONE)
                .withBlockAction(Input.BLOCK_PLACE_CRYSTAL, 100, 64, -12)
                .withCrystalHit(true, 98, 64, -12);
        assertSurvivesTheWire(in);

        Input out = roundTrip(in);
        assertTrue(out.crystalHit(), "the left click channel is carried alongside the right click");
        assertEquals(98, out.crystalX());
        assertEquals(64, out.crystalY());
        assertEquals(-12, out.crystalZ());
        assertEquals(Input.BLOCK_PLACE_CRYSTAL, out.blockAction());
        assertEquals(100, out.targetX());
    }

    @Test
    void everyLegalSwapCountSurvivesTheWireBesideTheHeldSlot() {
        for (int swap = 0; swap <= Clicks.MAX; swap++) {
            for (int slot = 0; slot <= 8; slot++) {
                Input in = base(Authority.NONE).withHeldSlot(slot)
                        .withClicks(Clicks.NONE.withSwap(swap));
                assertSurvivesTheWire(in);
                assertEquals(swap, roundTrip(in).clicks().swap(), "swap count did not survive");
                assertEquals(slot, roundTrip(in).heldSlot(), "the slot it shares a byte with did");
            }
        }
    }

    @Test
    void everyLegalClickCountSurvivesTheWireUnchanged() {
        for (int attack = 0; attack <= Clicks.MAX; attack++) {
            for (int use = 0; use <= Clicks.MAX; use++) {
                for (int drop = 0; drop <= Clicks.MAX; drop++) {
                    for (int inv = 0; inv <= Clicks.MAX; inv++) {
                        Clicks c = new Clicks(attack, use, drop, inv, 0);
                        Input in = base(Authority.NONE).withClicks(c);
                        assertSurvivesTheWire(in);
                        Clicks out = roundTrip(in).clicks();
                        assertEquals(attack, out.attack(), "attack count did not survive");
                        assertEquals(use, out.use(), "use count did not survive");
                        assertEquals(drop, out.drop(), "drop count did not survive");
                        assertEquals(inv, out.inv(), "inventory count did not survive");
                    }
                }
            }
        }
    }

    @Test
    void anOutOfRangeClickCountIsSaturatedIdenticallyOnBothSides() {
        for (int v : WILD_INTS) {
            int expected = v < 0 ? 0 : Math.min(Clicks.MAX, v);
            Clicks c = new Clicks(v, v, v, v, 0);
            assertEquals(expected, c.attack(), "attack count escaped the ceiling for " + v);
            assertEquals(expected, c.use(), "use count escaped the ceiling for " + v);
            assertEquals(expected, c.drop(), "drop count escaped the ceiling for " + v);
            assertEquals(expected, c.inv(), "inventory count escaped the ceiling for " + v);
            Clicks decoded = roundTrip(Input.NONE.withClicks(c)).clicks();
            assertEquals(c, decoded, "a saturated count is not a fixed point of the codec");
        }
    }

    @Test
    void aNullClickCountBecomesTheEmptyCount() {
        Input in = Input.NONE.withClicks(null);
        assertEquals(Clicks.NONE, in.clicks());
        assertSurvivesTheWire(in);
    }

    @Test
    void aClickCountCannotBleedIntoAnyOtherField() {
        Clicks loud = new Clicks(Clicks.MAX, Clicks.MAX, Clicks.MAX, Clicks.MAX, 0);
        Input in = Input.NONE.withClicks(loud);
        Input decoded = roundTrip(in);
        assertEquals(loud, decoded.clicks());
        assertEquals(Input.NONE, decoded.withClicks(Clicks.NONE),
                "carrying click counts disturbed some other field");
    }

    @Test
    void aHostileFlagWordCannotPushAClickCountAboveItsCeiling() {
        ByteBuffer b = ByteBuffer.allocate(InputCodec.BYTES);
        InputCodec.write(b, Input.NONE);
        byte[] raw = b.array();
        raw[0] = (byte) 0xFF;
        raw[1] = (byte) 0xFF;
        Clicks decoded = InputCodec.read(ByteBuffer.wrap(raw)).clicks();
        assertEquals(Clicks.MAX, decoded.attack());
        assertEquals(Clicks.MAX, decoded.use());
        assertEquals(Clicks.MAX, decoded.drop());
        assertEquals(Clicks.MAX, decoded.inv());
    }
}
